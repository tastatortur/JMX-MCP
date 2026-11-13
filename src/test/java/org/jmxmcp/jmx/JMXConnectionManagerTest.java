package org.jmxmcp.jmx;

import org.jmxmcp.config.JmxConnectionProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JMXConnectionManager.
 */
class JMXConnectionManagerTest {

    @Test
    void testLocalConnectionInitialization() {
        // Create test properties for local connection using default constructor
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // With the new architecture, LOCAL connections don't auto-connect
        // They require discovery service to find and connect to processes
        assertFalse(connectionManager.isConnected());

        // Verify that getConnectionInfo returns appropriate defaults for no active connection
        JMXConnectionManager.ConnectionInfo info = connectionManager.getConnectionInfo();
        assertNotNull(info);
        assertEquals(JmxConnectionProperties.ConnectionType.LOCAL, info.type());
        assertFalse(info.connected());
        assertEquals(0, info.mbeanCount());
    }

    @Test
    void testConnectionInfo() {
        // Create test properties for local connection
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // Test connection info when no active connection exists
        JMXConnectionManager.ConnectionInfo info = connectionManager.getConnectionInfo();
        assertNotNull(info);
        assertEquals(JmxConnectionProperties.ConnectionType.LOCAL, info.type());
        assertFalse(info.connected());
        assertEquals(0, info.mbeanCount());
    }

    @Test
    void testGetConnectionWithNoActiveConnection() {
        // Create test properties for local connection
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // Should throw exception when no active connection is available
        IllegalStateException exception = assertThrows(IllegalStateException.class,
            connectionManager::getConnection);
        assertTrue(exception.getMessage().startsWith("No active JMX connection available"));
    }

    @Test
    void testLocalConnectionWithUrlIsMappedToRemote() {
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // A LOCAL entry carrying a JMX service URL (e.g. a discovered local stub)
        // is stored as REMOTE so it can actually be dialed
        boolean result = connectionManager.addConnection(
            "local-with-url",
            "Local with URL",
            JmxConnectionProperties.ConnectionType.LOCAL,
            "service:jmx:rmi:///jndi/rmi://localhost:1098/jmxrmi",
            null,
            null,
            Map.of()
        );

        assertTrue(result);
        var stored = connectionManager.getConnectionRegistry().getConnection("local-with-url");
        assertTrue(stored.isPresent());
        assertEquals(JmxConnectionProperties.ConnectionType.REMOTE, stored.get().type());
        assertEquals("service:jmx:rmi:///jndi/rmi://localhost:1098/jmxrmi", stored.get().url());
    }

    @Test
    void testConnectToLocalWithoutUrlFailsWithGuidance() {
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        connectionManager.addConnection(
            "bare-local",
            "Bare Local",
            JmxConnectionProperties.ConnectionType.LOCAL,
            null,
            null,
            null,
            Map.of()
        );

        boolean connected = connectionManager.connectToConnection("bare-local");

        assertFalse(connected);
        var stored = connectionManager.getConnectionRegistry().getConnection("bare-local");
        assertTrue(stored.isPresent());
        assertEquals(JmxConnectionInfo.ConnectionStatus.FAILED, stored.get().status());
        assertNotNull(stored.get().errorMessage());
        assertTrue(stored.get().errorMessage().contains("service:jmx:rmi:///jndi/rmi://host:1098/jmxrmi"));
    }

    @Test
    void testFailedRedialKeepsExistingWorkingConnection() throws Exception {
        JMXConnectionManager connectionManager = new JMXConnectionManager(new JmxConnectionProperties());
        var server = javax.management.remote.JMXConnectorServerFactory.newJMXConnectorServer(
            new javax.management.remote.JMXServiceURL("service:jmx:rmi://"), null,
            java.lang.management.ManagementFactory.getPlatformMBeanServer());
        server.start();
        try {
            var connector = javax.management.remote.JMXConnectorFactory.connect(server.getAddress());
            var connection = connector.getMBeanServerConnection();

            // A registered entry whose URL cannot be dialed, but which holds a live connector
            String badUrl = "service:jmx:rmi:///jndi/rmi://127.0.0.1:1/jmxrmi";
            connectionManager.addConnection("c1", "C1", JmxConnectionProperties.ConnectionType.REMOTE,
                badUrl, null, null, Map.of());
            var live = JmxConnectionInfo.createRemote("c1", "C1", badUrl, null, null, Map.of())
                .withConnection(connection, connector, JmxConnectionInfo.ConnectionStatus.CONNECTED,
                    connection.getMBeanCount());
            connectionManager.getConnectionRegistry().updateConnection(live);

            boolean result = connectionManager.connectToConnection("c1");

            assertFalse(result);
            var stored = connectionManager.getConnectionRegistry().getConnection("c1").orElseThrow();
            assertTrue(stored.isConnected());
            assertTrue(stored.connection().getMBeanCount() > 0, "old connector must still be usable");
            connector.close();
        } finally {
            server.stop();
        }
    }

    @Test
    void testAddConnection() {
        // Create test properties for local connection
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // Test adding a new connection to the registry
        boolean result = connectionManager.addConnection(
            "test-connection",
            "Test Connection",
            JmxConnectionProperties.ConnectionType.LOCAL,
            null,
            null,
            null,
            Map.of()
        );

        assertTrue(result);

        // Verify the connection was added to the registry
        assertTrue(connectionManager.getConnectionRegistry().hasConnection("test-connection"));
    }

    @Test
    void testConnectionRegistry() {
        // Create test properties for local connection
        JmxConnectionProperties properties = new JmxConnectionProperties();
        JMXConnectionManager connectionManager = new JMXConnectionManager(properties);

        // Test that we can get the connection registry
        assertNotNull(connectionManager.getConnectionRegistry());

        // Initially should have no connections
        assertFalse(connectionManager.getConnectionRegistry().getActiveConnection().isPresent());
    }
}
