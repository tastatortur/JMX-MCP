package org.jmxmcp.service;

import org.jmxmcp.jmx.JMXConnectionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.MBeanServerConnection;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JvmDiagnosticsServiceTest {

    @Mock
    private JMXConnectionManager connectionManager;

    private static final String THIS_THREAD = Thread.currentThread().getName();

    private JvmDiagnosticsService service;

    // JUnit calls @BeforeEach methods via reflection
    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        service = new JvmDiagnosticsService(connectionManager);
    }

    private void connectToThisJvm() {
        when(connectionManager.getConnection()).thenReturn(ManagementFactory.getPlatformMBeanServer());
    }

    @Test
    void getJvmOverview_shouldReportAllSections() {
        connectToThisJvm();

        String result = service.getJvmOverview();

        assertThat(result).contains("JVM Overview", "Runtime:", "VM: ", "Uptime: ", "Arguments: [",
            "Memory:", "Heap: ", "Non-heap: ", "Garbage collection:", "collections, ", "ms total",
            "Memory pools:", "Threads:", "Live: ", "Classes:", "Loaded: ", "Operating system:", "processors");
        assertThat(result).doesNotContain("unavailable");
    }

    @Test
    void getJvmOverview_withBrokenSection_shouldStillReportTheOthers() throws Exception {
        MBeanServerConnection connection = org.mockito.Mockito.mock(MBeanServerConnection.class);
        when(connection.getAttribute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new javax.management.AttributeNotFoundException("nope"));
        when(connectionManager.getConnection()).thenReturn(connection);

        String result = service.getJvmOverview();

        assertThat(result).contains("Runtime:", "Operating system:", "unavailable: ");
    }

    @Test
    void getJvmOverview_withDeadConnection_shouldReportOneConnectionError() throws Exception {
        MBeanServerConnection connection = org.mockito.Mockito.mock(MBeanServerConnection.class);
        when(connection.getAttribute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
            .thenThrow(new IOException("connection reset"));
        when(connectionManager.getConnection()).thenReturn(connection);

        String result = service.getJvmOverview();

        assertThat(result).isEqualTo("Error getting JVM overview: Connection failed - connection reset");
    }

    @Test
    void getJvmOverview_withoutConnection_shouldReturnError() {
        when(connectionManager.getConnection()).thenThrow(new IllegalStateException("No active JMX connection"));

        assertThat(service.getJvmOverview()).isEqualTo("Error getting JVM overview: No active JMX connection");
    }

    @Test
    void getThreadDump_shouldRenderFramesCompactly() {
        connectToThisJvm();

        String result = service.getThreadDump(null, THIS_THREAD, null);

        assertThat(result).startsWith("Thread dump: ").contains("Deadlocks: ");
        assertThat(result).containsPattern("\"[^\"]*" + java.util.regex.Pattern.quote(THIS_THREAD) + "[^\"]*\" #\\d+");
        assertThat(result).containsPattern("    at \\S+\\(.+\\)");
    }

    @Test
    void getThreadDump_shouldLimitFramesPerThread() {
        connectToThisJvm();

        String unlimited = service.getThreadDump(200, THIS_THREAD, "RUNNABLE");
        String limited = service.getThreadDump(1, THIS_THREAD, "RUNNABLE");

        assertThat(limited.lines().filter(l -> l.startsWith("    at ")).count())
            .isLessThan(unlimited.lines().filter(l -> l.startsWith("    at ")).count());
        assertThat(limited).contains("more frames");
    }

    @Test
    void getThreadDump_withNoMatch_shouldListNoThreads() {
        connectToThisJvm();

        String result = service.getThreadDump(null, "no-such-thread-xyz", null);

        assertThat(result).contains("0 match the filter").doesNotContain("    at ");
    }

    @Test
    void getThreadDump_withInvalidState_shouldListValidStates() {
        String result = service.getThreadDump(null, null, "sleeping");

        assertThat(result).startsWith("Error getting thread dump: Invalid state 'sleeping'")
            .contains("RUNNABLE", "TIMED_WAITING");
    }

    @Test
    void getThreadDump_shouldAcceptLowerCaseState() {
        connectToThisJvm();

        String result = service.getThreadDump(0, null, "runnable");

        assertThat(result).startsWith("Thread dump: ").doesNotContain("    at ");
    }

    @Test
    void getThreadDump_withConnectionFailure_shouldReturnError() {
        when(connectionManager.getConnection()).thenThrow(new IllegalStateException("No active JMX connection"));

        assertThat(service.getThreadDump(null, null, null))
            .isEqualTo("Error getting thread dump: No active JMX connection");
    }

    @Test
    void getThreadDump_withDeadlock_shouldReportItFirst() throws Exception {
        connectToThisJvm();
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        CountDownLatch bothHoldOne = new CountDownLatch(2);
        Thread first = startDeadlockThread("deadlock-test-1", lockA, lockB, bothHoldOne);
        Thread second = startDeadlockThread("deadlock-test-2", lockB, lockA, bothHoldOne);
        try {
            assertThat(bothHoldOne.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.currentTimeMillis() + 5000;
            while (!deadlockInvolves(first, second) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }

            String result = service.getThreadDump(5, null, null);

            assertThat(result).contains("DEADLOCK detected between ", "[DEADLOCKED]", "waiting on <",
                "held by \"deadlock-test-");
            assertThat(result.substring(result.indexOf("\n\""))).startsWith("\n\"deadlock-test-");
        } finally {
            // lockInterruptibly() lets the test release the threads again
            first.interrupt();
            second.interrupt();
            first.join(5000);
            second.join(5000);
        }
    }

    private static boolean deadlockInvolves(Thread first, Thread second) {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        return ids != null && java.util.Arrays.stream(ids).anyMatch(id -> id == first.getId())
            && java.util.Arrays.stream(ids).anyMatch(id -> id == second.getId());
    }

    private static Thread startDeadlockThread(String name, ReentrantLock held, ReentrantLock wanted,
                                              CountDownLatch latch) {
        Thread thread = new Thread(() -> {
            try {
                held.lockInterruptibly();
                try {
                    latch.countDown();
                    latch.await();
                    wanted.lockInterruptibly();
                    wanted.unlock();
                } finally {
                    held.unlock();
                }
            } catch (InterruptedException e) {
                // test cleanup
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @Test
    void getThreadDump_toolSchema_shouldMarkParametersOptional() throws Exception {
        var schema = new com.fasterxml.jackson.databind.ObjectMapper().readTree(
            org.springframework.ai.support.ToolCallbacks.from(service)[1].getToolDefinition().inputSchema());
        var required = new java.util.ArrayList<String>();
        schema.path("required").forEach(node -> required.add(node.asText()));

        assertThat(schema.path("properties").size()).isEqualTo(3);
        assertThat(required).isEmpty();
    }

    @Test
    void describe_shouldUnwrapProxyExceptionsAndWordConnectionFailures() {
        Throwable wrapped = new java.lang.reflect.UndeclaredThrowableException(new IOException("connection reset"));

        assertThat(JvmDiagnosticsService.describe(wrapped)).isEqualTo("Connection failed - connection reset");
        assertThat(JvmDiagnosticsService.describe(new IllegalStateException("boom"))).isEqualTo("boom");
        assertThat(JvmDiagnosticsService.describe(new IllegalStateException())).isEqualTo("IllegalStateException");
    }

    @Test
    void getThreadDump_withDeadConnection_shouldReportConnectionFailure() throws Exception {
        MBeanServerConnection connection = org.mockito.Mockito.mock(MBeanServerConnection.class);
        when(connection.isInstanceOf(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(true);
        when(connection.invoke(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IOException("connection reset"));
        when(connectionManager.getConnection()).thenReturn(connection);

        assertThat(service.getThreadDump(null, null, null))
            .isEqualTo("Error getting thread dump: Connection failed - connection reset");
    }

    @Test
    void maskArgument_shouldHideCredentialValuesAndTruncate() {
        assertThat(JvmDiagnosticsService.maskArgument("-Djavax.net.ssl.keyStorePassword=hunter2"))
            .isEqualTo("-Djavax.net.ssl.keyStorePassword=****");
        assertThat(JvmDiagnosticsService.maskArgument("-Xmx512m")).isEqualTo("-Xmx512m");
        assertThat(JvmDiagnosticsService.maskArgument("-Dfile.encoding=UTF-8")).isEqualTo("-Dfile.encoding=UTF-8");
        assertThat(JvmDiagnosticsService.maskArgument("-Dcom.sun.management.jmxremote.authenticate=false"))
            .isEqualTo("-Dcom.sun.management.jmxremote.authenticate=false");
        assertThat(JvmDiagnosticsService.maskArgument("-Dpath=" + "x".repeat(300)))
            .hasSize(203).endsWith("...");
    }

    @Test
    void getThreadDump_withoutThreadingMBean_shouldReturnError() {
        when(connectionManager.getConnection()).thenReturn(org.mockito.Mockito.mock(MBeanServerConnection.class));

        assertThat(service.getThreadDump(null, null, null)).startsWith("Error getting thread dump: ");
    }
}
