package org.jmxmcp.jmx;

import javax.management.MBeanServerConnection;
import java.io.IOException;

/**
 * Runs read-only JMX calls and, when the connection turns out to be dead, re-dials the active connection
 * and repeats the call once.
 * <p>
 * Retrying is only safe for reads: after a connection failure a write may already have been executed on the
 * target, so operations and attribute writes must not go through this class.
 */
public final class ConnectionRetry {

    /** A call against the MBean server that may fail with any JMX exception. */
    @FunctionalInterface
    public interface Call<T> {
        T call(MBeanServerConnection connection) throws Exception;
    }

    private ConnectionRetry() {
    }

    /**
     * Executes the call on the active connection; after a connection failure the connection is re-dialed
     * and the call is repeated once. Other failures, and a failed re-dial, are rethrown unchanged.
     */
    public static <T> T read(JMXConnectionManager connectionManager, Call<T> call) throws Exception {
        MBeanServerConnection used = connectionManager.getConnection();
        try {
            return call.call(used);
        } catch (Exception e) {
            if (!isConnectionFailure(e) || !connectionManager.reconnectAfterFailure(used)) {
                throw e;
            }
            return call.call(connectionManager.getConnection());
        }
    }

    /**
     * Whether the failure is a broken connection. MXBean proxies wrap the {@link IOException} of a dead
     * connection in an {@code UndeclaredThrowableException}, so the whole cause chain is inspected.
     */
    public static boolean isConnectionFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
