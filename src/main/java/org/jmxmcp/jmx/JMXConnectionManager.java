package org.jmxmcp.jmx;

import org.jmxmcp.config.JmxConnectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Enhanced JMX connection manager that supports multiple connections through a registry.
 *
 * This class now handles:
 * - Multiple JMX connections via ConnectionRegistry
 * - Local platform MBean server access
 * - Remote JMX server connections with authentication
 * - Connection lifecycle management
 * - Connection health monitoring
 * - Backward compatibility with single-connection usage
 */
public class JMXConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(JMXConnectionManager.class);

    /** Minimum gap between implicit reconnect attempts triggered by {@link #getConnection()}. */
    private static final long IMPLICIT_RECONNECT_INTERVAL_MS = 5_000;

    private final JmxConnectionProperties properties;
    private final JmxConnectionRegistry connectionRegistry;
    private long lastImplicitReconnectNanos;
    private boolean implicitReconnectAttempted;

    public JMXConnectionManager(JmxConnectionProperties properties, JmxConnectionRegistry connectionRegistry) {
        this.properties = properties;
        this.connectionRegistry = connectionRegistry;
        initializeDefaultConnection();
    }

    /**
     * Backward compatibility constructor for single connection
     */
    public JMXConnectionManager(JmxConnectionProperties properties) {
        this.properties = properties;
        this.connectionRegistry = new JmxConnectionRegistry();
        initializeDefaultConnection();
    }

    /**
     * Initializes the default JMX connection based on configuration.
     * For LOCAL type, we no longer auto-connect since we need to discover processes first.
     */
    private void initializeDefaultConnection() {
        try {
            if (properties.connection().type() == JmxConnectionProperties.ConnectionType.REMOTE) {
                String defaultId = "default";
                JmxConnectionInfo connectionInfo = createRemoteConnection(defaultId, "Default Remote Connection",
                    properties.connection().url(), properties.connection().username(),
                    properties.connection().password(), properties.connection().properties());

                connectionRegistry.addConnection(connectionInfo);
                connectToConnection(defaultId);
                logger.info("Default remote JMX connection initialized successfully");
            } else {
                logger.info("LOCAL connection type configured - use discovery to find and connect to local processes");
            }
        } catch (Exception e) {
            logger.error("Failed to initialize default JMX connection", e);
        }
    }



    /**
     * Creates a remote JMX connection
     */
    public JmxConnectionInfo createRemoteConnection(String id, String name, String url,
                                                   String username, String password,
                                                   Map<String, String> properties) {
        try {
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Remote JMX URL is required for remote connections");
            }

            logger.info("Creating remote JMX connection: {} -> {}", name, url);

            JMXServiceURL serviceURL = new JMXServiceURL(url);
            Map<String, Object> environment = createConnectionEnvironment(username, password, properties);

            JMXConnector connector = JmxConnectors.connect(serviceURL, environment, this.properties.connection().timeout());
            MBeanServerConnection connection = connector.getMBeanServerConnection();
            int mbeanCount = connection.getMBeanCount();

            JmxConnectionInfo connectionInfo = JmxConnectionInfo.createRemote(id, name, url, username, password, properties);
            return connectionInfo.withConnection(connection, connector,
                JmxConnectionInfo.ConnectionStatus.CONNECTED, mbeanCount);
        } catch (IOException | RuntimeException e) {
            logger.error("Failed to create remote connection to {}: {}", url, e.getMessage());
            return JmxConnectionInfo.createRemote(id, name, url, username, password, properties)
                .withStatus(JmxConnectionInfo.ConnectionStatus.FAILED, e.getMessage());
        }
    }

    /**
     * Creates the connection environment for remote JMX connections.
     */
    private Map<String, Object> createConnectionEnvironment(String username, String password,
                                                           Map<String, String> properties) {
        Map<String, Object> environment = new HashMap<>();

        // Add custom properties if provided
        if (properties != null) {
            environment.putAll(properties);
        }

        // Add authentication if provided
        if (username != null && !username.isBlank()) {
            logger.info("Using authentication for JMX connection with username: {}", username);
            String[] credentials = {username, password != null ? password : ""};
            environment.put(JMXConnector.CREDENTIALS, credentials);
        }

        return environment;
    }

    /**
     * Connects to a specific connection in the registry
     */
    public boolean connectToConnection(String connectionId) {
        Optional<JmxConnectionInfo> connectionOpt = connectionRegistry.getConnection(connectionId);
        if (connectionOpt.isEmpty()) {
            logger.warn("Connection not found: {}", connectionId);
            return false;
        }

        JmxConnectionInfo connectionInfo = connectionOpt.get();

        try {
            if (connectionInfo.type() == JmxConnectionProperties.ConnectionType.LOCAL
                    && (connectionInfo.url() == null || connectionInfo.url().isBlank())) {
                // A bare LOCAL entry has nothing to dial - fail with guidance instead of a generic deprecation error
                logger.warn("LOCAL connection {} has no JMX service URL", connectionId);
                JmxConnectionInfo failedConnection = connectionInfo.withStatus(
                    JmxConnectionInfo.ConnectionStatus.FAILED,
                    "LOCAL connections without a JMX service URL are not supported. Use discoverJmxServices() to "
                        + "find local processes, or connect via a remote JNDI registry URL like "
                        + "service:jmx:rmi:///jndi/rmi://host:1098/jmxrmi.");
                connectionRegistry.updateConnection(failedConnection);
                return false;
            }

            // Remote connections, and LOCAL entries carrying a discovered stub URL, are dialed the same way.
            // Note: local stub URLs (service:jmx:rmi://.../stub/...) can fail with RemoteStub serialVersionUID
            // mismatches when the MCP server JVM differs from the target JVM - the RMI registry route
            // (service:jmx:rmi:///jndi/rmi://host:port/jmxrmi) is the reliable path.
            JmxConnectionInfo updatedConnection = createRemoteConnection(
                connectionInfo.id(), connectionInfo.name(), connectionInfo.url(),
                connectionInfo.username(), connectionInfo.password(), connectionInfo.properties());

            if (!updatedConnection.isConnected() && isAlive(connectionInfo)) {
                // The re-dial failed but the existing connection still works: keep it rather than
                // replacing a healthy connector with a FAILED entry
                logger.warn("Re-dial of {} failed ({}); keeping the existing working connection",
                    connectionId, updatedConnection.errorMessage());
                return false;
            }

            // The previous connector (if any) is superseded: close it so a re-dial does not leak it
            closeConnectorQuietly(connectionInfo);
            connectionRegistry.updateConnection(updatedConnection);

            connectionRegistry.setActiveConnection(connectionId);
            logger.info("Successfully connected to: {}", connectionId);
            return true;
        } catch (Exception e) {
            logger.error("Failed to connect to {}: {}", connectionId, e.getMessage());
            JmxConnectionInfo failedConnection = connectionInfo.withStatus(
                JmxConnectionInfo.ConnectionStatus.FAILED, e.getMessage());
            connectionRegistry.updateConnection(failedConnection);
            return false;
        }
    }

    private boolean isAlive(JmxConnectionInfo connectionInfo) {
        if (!connectionInfo.isConnected() || connectionInfo.connection() == null) {
            return false;
        }
        try {
            connectionInfo.connection().getMBeanCount();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void closeConnectorQuietly(JmxConnectionInfo connectionInfo) {
        if (connectionInfo.connector() != null) {
            try {
                connectionInfo.connector().close();
            } catch (IOException e) {
                logger.debug("Error closing stale connector for {}: {}", connectionInfo.id(), e.getMessage());
            }
        }
    }

    /**
     * Gets the current active MBean server connection.
     *
     * If the active entry is marked as not connected (e.g. the registry was left in a
     * stale state after an interrupted call), a reconnect is attempted before giving up.
     * Implicit reconnects are serialized and rate-limited to one per
     * {@value #IMPLICIT_RECONNECT_INTERVAL_MS} ms so a down target cannot stall every caller.
     *
     * @return the MBean server connection
     * @throws IllegalStateException if not connected
     */
    public MBeanServerConnection getConnection() {
        Optional<JmxConnectionInfo> activeConnection = connectionRegistry.getActiveConnection();
        if (activeConnection.isPresent() && !activeConnection.get().isConnected()) {
            reconnectIfDue();
            activeConnection = connectionRegistry.getActiveConnection();
        }

        if (activeConnection.isEmpty()) {
            throw new IllegalStateException("No active JMX connection available - use discoverJmxServices(), "
                + "addJmxConnection() or switchJmxConnection() to connect");
        }

        JmxConnectionInfo connectionInfo = activeConnection.get();
        if (!connectionInfo.isConnected()) {
            throw new IllegalStateException("Active JMX connection is not connected (last error: "
                + connectionInfo.errorMessage() + ") - use switchJmxConnection() to reconnect");
        }

        return connectionInfo.connection();
    }

    /**
     * Checks if there is an active, connected JMX connection.
     *
     * This is a cheap status check against the registry and deliberately does
     * not perform a remote round trip (a getMBeanCount() probe on every call
     * added latency to each scheduled refresh and status check). Actual
     * connectivity is verified by the JMX calls themselves: if the connection
     * died, discovery/attribute calls fail with IOException and are handled
     * by the callers.
     */
    public boolean isConnected() {
        Optional<JmxConnectionInfo> activeConnection = connectionRegistry.getActiveConnection();
        if (activeConnection.isEmpty()) {
            return false;
        }
        return activeConnection.get().isConnected();
    }

    private synchronized void reconnectIfDue() {
        // Re-check under the lock: another thread may have just reconnected
        Optional<JmxConnectionInfo> current = connectionRegistry.getActiveConnection();
        if (current.isEmpty() || current.get().isConnected()) {
            return;
        }
        long now = System.nanoTime();
        if (implicitReconnectAttempted
                && now - lastImplicitReconnectNanos < IMPLICIT_RECONNECT_INTERVAL_MS * 1_000_000L) {
            return;
        }
        implicitReconnectAttempted = true;
        lastImplicitReconnectNanos = now;
        logger.info("Active JMX connection not connected - attempting one reconnect");
        reconnect();
    }

    /**
     * Attempts to reconnect to the active JMX server.
     */
    public synchronized void reconnect() {
        Optional<String> activeId = connectionRegistry.getActiveConnectionId();
        if (activeId.isPresent()) {
            logger.info("Attempting to reconnect to active JMX server: {}", activeId.get());
            connectToConnection(activeId.get());
        } else {
            logger.warn("No active connection to reconnect to");
        }
    }

    /**
     * Re-dials the active connection after a call on {@code failed} hit a connection failure.
     * <p>
     * The registry keeps a dead target marked as connected, so a failing call is the only signal that the
     * connection is gone. If another caller has already replaced the connection, nothing is re-dialed:
     * that caller's new connection is used and re-dialing again would close it under them.
     *
     * @return true if the active connection is a different, live one and the call can be repeated
     */
    public synchronized boolean reconnectAfterFailure(MBeanServerConnection failed) {
        Optional<JmxConnectionInfo> active = connectionRegistry.getActiveConnection();
        if (active.isEmpty()) {
            return false;
        }
        if (active.get().isConnected() && active.get().connection() != failed) {
            return true;
        }
        logger.info("Connection {} failed - attempting one reconnect", active.get().id());
        return connectToConnection(active.get().id());
    }

    /**
     * Disconnects from the active JMX server.
     */
    public void disconnect() {
        Optional<JmxConnectionInfo> activeConnection = connectionRegistry.getActiveConnection();
        if (activeConnection.isPresent()) {
            JmxConnectionInfo connectionInfo = activeConnection.get();
            disconnectConnection(connectionInfo.id());
        }
    }

    /**
     * Disconnects a specific connection
     */
    public void disconnectConnection(String connectionId) {
        Optional<JmxConnectionInfo> connectionOpt = connectionRegistry.getConnection(connectionId);
        if (connectionOpt.isEmpty()) {
            logger.warn("Connection not found for disconnect: {}", connectionId);
            return;
        }

        JmxConnectionInfo connectionInfo = connectionOpt.get();

        // Close the connector if it exists
        if (connectionInfo.connector() != null) {
            try {
                connectionInfo.connector().close();
                logger.info("JMX connector closed for: {}", connectionId);
            } catch (IOException e) {
                logger.warn("Error closing JMX connector for {}: {}", connectionId, e.getMessage());
            }
        }

        // Update connection status
        JmxConnectionInfo disconnectedConnection = connectionInfo.withStatus(
            JmxConnectionInfo.ConnectionStatus.DISCONNECTED, null);
        connectionRegistry.updateConnection(disconnectedConnection);

        logger.info("JMX connection disconnected: {}", connectionId);
    }

    /**
     * Gets connection information for the active connection (backward compatibility).
     */
    public ConnectionInfo getConnectionInfo() {
        Optional<JmxConnectionInfo> activeConnection = connectionRegistry.getActiveConnection();
        if (activeConnection.isEmpty()) {
            return new ConnectionInfo(
                JmxConnectionProperties.ConnectionType.LOCAL,
                null,
                false,
                0
            );
        }

        JmxConnectionInfo connectionInfo = activeConnection.get();
        return new ConnectionInfo(
            connectionInfo.type(),
            connectionInfo.url(),
            connectionInfo.isConnected(),
            Objects.requireNonNullElse(connectionInfo.mbeanCount(), 0)
        );
    }

    /**
     * Gets the connection registry for advanced connection management
     */
    public JmxConnectionRegistry getConnectionRegistry() {
        return connectionRegistry;
    }

    /**
     * Adds a new connection to the registry
     */
    public boolean addConnection(String id, String name, JmxConnectionProperties.ConnectionType type,
                                String url, String username, String password, Map<String, String> properties) {
        try {
            JmxConnectionInfo connectionInfo;
            if (type == JmxConnectionProperties.ConnectionType.LOCAL && (url == null || url.isBlank())) {
                connectionInfo = JmxConnectionInfo.createLocal(id, name);
            } else {
                // A LOCAL entry that carries a URL (e.g. a discovered local stub) is dialed as a remote
                // connection - map it to REMOTE so connectToConnection treats it as dialable.
                if (type == JmxConnectionProperties.ConnectionType.LOCAL) {
                    logger.info("Mapping LOCAL connection {} with URL {} to REMOTE", id, url);
                }
                connectionInfo = JmxConnectionInfo.createRemote(id, name, url, username, password, properties);
            }

            connectionRegistry.addConnection(connectionInfo);
            logger.info("Added new JMX connection: {}", id);
            return true;
        } catch (Exception e) {
            logger.error("Failed to add connection {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Removes a connection from the registry
     */
    public boolean removeConnection(String connectionId) {
        return connectionRegistry.removeConnection(connectionId);
    }

    /**
     * Sets the active connection
     */
    public boolean setActiveConnection(String connectionId) {
        return connectionRegistry.setActiveConnection(connectionId);
    }

    /**
     * Connection information record for monitoring (backward compatibility).
     */
    public record ConnectionInfo(
        JmxConnectionProperties.ConnectionType type,
        String url,
        boolean connected,
        int mbeanCount
    ) {}
}
