package org.jmxmcp.jmx;

import org.jmxmcp.config.JmxConnectionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.ServerSocket;
import java.rmi.registry.LocateRegistry;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionRetryTest {

    @Mock
    private JMXConnectionManager connectionManager;

    @Mock
    private MBeanServerConnection first;

    @Mock
    private MBeanServerConnection second;

    @Test
    void read_withHealthyConnection_shouldCallOnce() throws Exception {
        when(connectionManager.getConnection()).thenReturn(first);

        String result = ConnectionRetry.read(connectionManager, c -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(connectionManager, never()).reconnectAfterFailure(first);
    }

    @Test
    void read_afterConnectionFailure_shouldReconnectAndRepeatOnce() throws Exception {
        when(connectionManager.getConnection()).thenReturn(first, second);
        when(connectionManager.reconnectAfterFailure(first)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        String result = ConnectionRetry.read(connectionManager, c -> {
            if (calls.getAndIncrement() == 0) {
                assertThat(c).isSameAs(first);
                throw new IOException("connection reset");
            }
            assertThat(c).isSameAs(second);
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
        assertThat(calls).hasValue(2);
    }

    @Test
    void read_withIoExceptionWrappedByProxy_shouldAlsoReconnect() throws Exception {
        when(connectionManager.getConnection()).thenReturn(first, second);
        when(connectionManager.reconnectAfterFailure(first)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        String result = ConnectionRetry.read(connectionManager, c -> {
            if (calls.getAndIncrement() == 0) {
                throw new UndeclaredThrowableException(new IOException("connection reset"));
            }
            return "recovered";
        });

        assertThat(result).isEqualTo("recovered");
    }

    @Test
    void read_whenReconnectFails_shouldRethrowTheOriginalFailure() {
        when(connectionManager.getConnection()).thenReturn(first);
        when(connectionManager.reconnectAfterFailure(first)).thenReturn(false);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> ConnectionRetry.read(connectionManager, c -> {
            calls.incrementAndGet();
            throw new IOException("connection reset");
        })).isInstanceOf(IOException.class).hasMessage("connection reset");
        assertThat(calls).hasValue(1);
    }

    @Test
    void read_whenRepeatedCallFailsAgain_shouldNotRetryAgain() {
        when(connectionManager.getConnection()).thenReturn(first, second);
        when(connectionManager.reconnectAfterFailure(first)).thenReturn(true);
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> ConnectionRetry.read(connectionManager, c -> {
            calls.incrementAndGet();
            throw new IOException("still down");
        })).isInstanceOf(IOException.class).hasMessage("still down");
        assertThat(calls).hasValue(2);
    }

    @Test
    void read_withNonConnectionFailure_shouldNotReconnect() {
        when(connectionManager.getConnection()).thenReturn(first);

        assertThatThrownBy(() -> ConnectionRetry.read(connectionManager, c -> {
            throw new InstanceNotFoundException("no such MBean");
        })).isInstanceOf(InstanceNotFoundException.class);
        verify(connectionManager, never()).reconnectAfterFailure(first);
    }

    @Test
    void isConnectionFailure_shouldFollowTheCauseChain() {
        assertThat(ConnectionRetry.isConnectionFailure(new IOException())).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(
            new UndeclaredThrowableException(new IOException()))).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(new IllegalStateException("x"))).isFalse();
        assertThat(ConnectionRetry.isConnectionFailure(new IllegalStateException(new IOException()))).isTrue();
    }

    /** The scenario from the issue: the target restarts on the same JNDI address. */
    @Test
    void read_afterTargetRestartOnSameAddress_shouldRecoverWithoutManualReconnect() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var registry = LocateRegistry.createRegistry(port);
        String url = "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi";
        JMXConnectorServer server = startServer(url);
        try {
            JMXConnectionManager manager = new JMXConnectionManager(new JmxConnectionProperties());
            manager.addConnection("restart-test", "Restart test", JmxConnectionProperties.ConnectionType.REMOTE,
                url, null, null, Map.of());
            assertThat(manager.connectToConnection("restart-test")).isTrue();
            assertThat(ConnectionRetry.read(manager, MBeanServerConnection::getMBeanCount)).isPositive();

            server.stop();
            server = startServer(url);

            assertThat(ConnectionRetry.read(manager, MBeanServerConnection::getMBeanCount)).isPositive();
            assertThat(manager.isConnected()).isTrue();
        } finally {
            server.stop();
            java.rmi.server.UnicastRemoteObject.unexportObject(registry, true);
        }
    }

    private static JMXConnectorServer startServer(String url) throws IOException {
        JMXConnectorServer server = JMXConnectorServerFactory.newJMXConnectorServer(
            new JMXServiceURL(url), null, ManagementFactory.getPlatformMBeanServer());
        server.start();
        return server;
    }
}
