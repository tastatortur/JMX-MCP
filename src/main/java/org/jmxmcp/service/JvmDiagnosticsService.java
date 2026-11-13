package org.jmxmcp.service;

import org.jmxmcp.jmx.ConnectionRetry;
import org.jmxmcp.jmx.JMXConnectionManager;
import org.jmxmcp.model.JmxValueFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Read-only convenience tools built on the standard platform MBeans, so they work for local and remote
 * connections alike.
 */
@Service
public class JvmDiagnosticsService {

    private static final Logger logger = LoggerFactory.getLogger(JvmDiagnosticsService.class);

    static final int DEFAULT_MAX_FRAMES = 20;
    static final int MAX_FRAMES_LIMIT = 200;

    /** Maximum number of threads rendered in one dump; the rest is summarised. */
    static final int MAX_THREADS = 100;

    private static final ObjectName OPERATING_SYSTEM = name("java.lang:type=OperatingSystem");
    private static final ObjectName GARBAGE_COLLECTORS = name("java.lang:type=GarbageCollector,*");
    private static final ObjectName MEMORY_POOLS = name("java.lang:type=MemoryPool,*");
    private static final ObjectName THREADING = name("java.lang:type=Threading");

    /** Maximum length of a single JVM argument in the overview. */
    private static final int MAX_ARGUMENT_LENGTH = 200;

    /** JVM arguments whose name suggests a credential; their value is masked in the overview. */
    private static final Pattern SENSITIVE_ARGUMENT = Pattern.compile("(?i)pass|secret|token|key|credential|authorization");

    private static final double MEGABYTE = 1024.0 * 1024.0;

    private final JMXConnectionManager connectionManager;

    public JvmDiagnosticsService(JMXConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Tool(description = "Get a one-call overview of the connected JVM, like VisualVM's Overview/Monitor tabs: "
        + "VM name, version and uptime, JVM arguments, heap and non-heap memory, garbage collector counts and times, "
        + "memory pool usage, thread counts, loaded classes, and OS CPU load and physical memory. Read-only.")
    public String getJvmOverview() {
        try {
            return ConnectionRetry.read(connectionManager, this::overview);
        } catch (Exception e) {
            logger.error("Error getting JVM overview", e);
            return "Error getting JVM overview: " + describe(e);
        }
    }

    private String overview(MBeanServerConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder("JVM Overview\n============\n");
        section(sb, "Runtime", () -> runtime(connection));
        section(sb, "Memory", () -> memory(connection));
        section(sb, "Garbage collection", () -> garbageCollectors(connection));
        section(sb, "Memory pools", () -> memoryPools(connection));
        section(sb, "Threads", () -> threads(connection));
        section(sb, "Classes", () -> classes(connection));
        section(sb, "Operating system", () -> operatingSystem(connection));
        return sb.toString();
    }

    @Tool(description = "Get a formatted thread dump of the connected JVM: per thread its name, state, the lock it "
        + "waits on and who holds it, the stack frames and held locks. Deadlocked threads are reported first. "
        + "maxFrames limits the frames per thread (default 20, max 200). threadNameFilter keeps only threads whose "
        + "name contains the text (case-insensitive). state keeps only threads in that state "
        + "(NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED). All parameters are optional. "
        + "At most 100 threads are listed. Read-only.")
    public String getThreadDump(
            @ToolParam(required = false, description = "Maximum stack frames per thread (default 20, max 200)")
            Integer maxFrames,
            @ToolParam(required = false, description = "Only threads whose name contains this text (case-insensitive)")
            String threadNameFilter,
            @ToolParam(required = false, description = "Only threads in this state, e.g. BLOCKED or RUNNABLE")
            String state) {
        int frameLimit = maxFrames == null ? DEFAULT_MAX_FRAMES : Math.max(0, Math.min(maxFrames, MAX_FRAMES_LIMIT));
        Thread.State stateFilter = null;
        if (state != null && !state.isBlank()) {
            try {
                stateFilter = Thread.State.valueOf(state.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "Error getting thread dump: Invalid state '" + state + "'. Valid states: "
                    + Arrays.stream(Thread.State.values()).map(Enum::name).collect(Collectors.joining(", "));
            }
        }

        try {
            Thread.State finalStateFilter = stateFilter;
            return ConnectionRetry.read(connectionManager, connection -> {
                ThreadMXBean threading = ManagementFactory.newPlatformMXBeanProxy(
                    connection, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);
                ThreadInfo[] infos = threading.dumpAllThreads(true, true);
                long[] deadlocked = threading.findDeadlockedThreads();
                return formatThreadDump(infos, deadlocked, frameLimit, threadNameFilter, finalStateFilter);
            });
        } catch (Exception e) {
            // The MXBean proxy wraps IOExceptions of a dead connection in UndeclaredThrowableException
            logger.error("Error getting thread dump", e);
            return "Error getting thread dump: " + describe(e);
        }
    }

    // ---- thread dump ----

    static String formatThreadDump(ThreadInfo[] infos, long[] deadlockedIds, int maxFrames,
                                   String nameFilter, Thread.State stateFilter) {
        Set<Long> deadlocked = new LinkedHashSet<>();
        if (deadlockedIds != null) {
            Arrays.stream(deadlockedIds).forEach(deadlocked::add);
        }

        List<ThreadInfo> matching = Arrays.stream(infos)
            .filter(info -> info != null)
            .filter(info -> nameFilter == null || nameFilter.isBlank()
                || info.getThreadName().toLowerCase(Locale.ROOT).contains(nameFilter.trim().toLowerCase(Locale.ROOT)))
            .filter(info -> stateFilter == null || info.getThreadState() == stateFilter)
            // stable sort: deadlocked threads first, the rest keeps the JVM's order
            .sorted(Comparator.comparing(info -> !deadlocked.contains(info.getThreadId())))
            .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Thread dump: ").append(infos.length).append(" threads");
        if (matching.size() != infos.length) {
            sb.append(", ").append(matching.size()).append(" match the filter");
        }
        sb.append('\n');

        if (deadlocked.isEmpty()) {
            sb.append("Deadlocks: none\n");
        } else {
            String names = Arrays.stream(infos)
                .filter(info -> info != null && deadlocked.contains(info.getThreadId()))
                .map(info -> "\"" + info.getThreadName() + "\"")
                .collect(Collectors.joining(", "));
            sb.append("DEADLOCK detected between ").append(deadlocked.size()).append(" threads: ")
                .append(names).append('\n');
        }

        int shown = Math.min(matching.size(), MAX_THREADS);
        for (int i = 0; i < shown; i++) {
            ThreadInfo info = matching.get(i);
            sb.append('\n');
            appendThread(sb, info, deadlocked.contains(info.getThreadId()), maxFrames);
        }
        if (matching.size() > shown) {
            sb.append("\n... (").append(matching.size() - shown).append(" more threads not shown)\n");
        }
        return sb.toString();
    }

    private static void appendThread(StringBuilder sb, ThreadInfo info, boolean deadlocked, int maxFrames) {
        sb.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId())
            .append(info.isDaemon() ? " daemon " : " ").append(info.getThreadState());
        if (deadlocked) {
            sb.append(" [DEADLOCKED]");
        }
        sb.append('\n');

        if (info.getLockInfo() != null) {
            sb.append("  waiting on <").append(info.getLockInfo()).append('>');
            if (info.getLockOwnerName() != null) {
                sb.append(" held by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
            }
            sb.append('\n');
        }

        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        int frames = Math.min(stack.length, maxFrames);
        for (int i = 0; i < frames; i++) {
            sb.append("    at ").append(stack[i]).append('\n');
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                    sb.append("    - locked <").append(monitor).append(">\n");
                }
            }
        }
        if (stack.length > frames) {
            sb.append("    ... ").append(stack.length - frames).append(" more frames\n");
        }
        // Monitors locked in frames beyond the limit would otherwise be lost
        for (MonitorInfo monitor : monitors) {
            if (monitor.getLockedStackDepth() >= frames) {
                sb.append("    - locked <").append(monitor).append(">\n");
            }
        }
        for (var synchronizer : info.getLockedSynchronizers()) {
            sb.append("    - holds <").append(synchronizer).append(">\n");
        }
    }

    // ---- overview sections ----

    private interface SectionBody {
        String render() throws Exception;
    }

    private static void section(StringBuilder sb, String title, SectionBody body) throws Exception {
        sb.append('\n').append(title).append(":\n");
        try {
            sb.append(body.render());
        } catch (Exception e) {
            if (ConnectionRetry.isConnectionFailure(e)) {
                // A dead connection fails every section the same way: let the caller re-dial or report it once
                throw e;
            }
            // One unsupported MBean (e.g. on a non-HotSpot JVM) must not hide the rest of the overview
            sb.append("  unavailable: ").append(describe(e)).append('\n');
        }
    }

    private static String runtime(MBeanServerConnection connection) throws IOException {
        RuntimeMXBean runtime = ManagementFactory.newPlatformMXBeanProxy(
            connection, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
        StringBuilder sb = new StringBuilder();
        line(sb, "VM", runtime.getVmName() + " " + runtime.getVmVersion() + " (" + runtime.getVmVendor() + ")");
        line(sb, "Uptime", duration(runtime.getUptime()));
        line(sb, "Arguments", JmxValueFormatter.format(runtime.getInputArguments().stream()
            .map(JvmDiagnosticsService::maskArgument).toList()));
        return sb.toString();
    }

    private static String memory(MBeanServerConnection connection) throws IOException {
        MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(
            connection, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        StringBuilder sb = new StringBuilder();
        line(sb, "Heap", usage(memory.getHeapMemoryUsage()));
        line(sb, "Non-heap", usage(memory.getNonHeapMemoryUsage()));
        return sb.toString();
    }

    private static String garbageCollectors(MBeanServerConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (ObjectName collector : sorted(connection.queryNames(GARBAGE_COLLECTORS, null))) {
            long count = ((Number) connection.getAttribute(collector, "CollectionCount")).longValue();
            long millis = ((Number) connection.getAttribute(collector, "CollectionTime")).longValue();
            // -1 means the collector does not provide the value
            line(sb, collector.getKeyProperty("name"), count < 0 || millis < 0
                ? "n/a" : count + " collections, " + millis + " ms total");
        }
        return sb.length() == 0 ? "  none\n" : sb.toString();
    }

    private static String memoryPools(MBeanServerConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (ObjectName pool : sorted(connection.queryNames(MEMORY_POOLS, null))) {
            Object usage = connection.getAttribute(pool, "Usage");
            line(sb, pool.getKeyProperty("name"), usage instanceof CompositeData data
                ? usage(MemoryUsage.from(data)) : "n/a");
        }
        return sb.length() == 0 ? "  none\n" : sb.toString();
    }

    private static String threads(MBeanServerConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        line(sb, "Live", connection.getAttribute(THREADING, "ThreadCount")
            + " (" + connection.getAttribute(THREADING, "DaemonThreadCount") + " daemon)");
        line(sb, "Peak", String.valueOf(connection.getAttribute(THREADING, "PeakThreadCount")));
        line(sb, "Started in total", String.valueOf(connection.getAttribute(THREADING, "TotalStartedThreadCount")));
        return sb.toString();
    }

    private static String classes(MBeanServerConnection connection) throws IOException {
        ClassLoadingMXBean classes = ManagementFactory.newPlatformMXBeanProxy(
            connection, ManagementFactory.CLASS_LOADING_MXBEAN_NAME, ClassLoadingMXBean.class);
        StringBuilder sb = new StringBuilder();
        line(sb, "Loaded", String.valueOf(classes.getLoadedClassCount()));
        line(sb, "Total loaded", String.valueOf(classes.getTotalLoadedClassCount()));
        line(sb, "Unloaded", String.valueOf(classes.getUnloadedClassCount()));
        return sb.toString();
    }

    private static String operatingSystem(MBeanServerConnection connection) throws Exception {
        StringBuilder sb = new StringBuilder();
        line(sb, "OS", connection.getAttribute(OPERATING_SYSTEM, "Name") + " "
            + connection.getAttribute(OPERATING_SYSTEM, "Version") + " ("
            + connection.getAttribute(OPERATING_SYSTEM, "Arch") + "), "
            + connection.getAttribute(OPERATING_SYSTEM, "AvailableProcessors") + " processors");
        // The com.sun.management extension attributes are HotSpot specific and renamed across JDK versions
        optional(connection, "Process CPU load", sb, v -> percent((Number) v), "ProcessCpuLoad");
        optional(connection, "System CPU load", sb, v -> percent((Number) v), "CpuLoad", "SystemCpuLoad");
        optional(connection, "System load average", sb, v -> ((Number) v).doubleValue() < 0 ? "n/a" : v.toString(),
            "SystemLoadAverage");
        Object total = firstAttribute(connection, "TotalMemorySize", "TotalPhysicalMemorySize");
        Object free = firstAttribute(connection, "FreeMemorySize", "FreePhysicalMemorySize");
        if (total instanceof Number totalBytes && free instanceof Number freeBytes) {
            line(sb, "Physical memory", megabytes(totalBytes.longValue() - freeBytes.longValue()) + " used / "
                + megabytes(totalBytes.longValue()) + " total");
        }
        return sb.toString();
    }

    private static void optional(MBeanServerConnection connection, String label, StringBuilder sb,
                                 Function<Object, String> render, String... attributes) {
        Object value = firstAttribute(connection, attributes);
        if (value != null) {
            line(sb, label, render.apply(value));
        }
    }

    private static Object firstAttribute(MBeanServerConnection connection, String... attributes) {
        for (String attribute : attributes) {
            try {
                return connection.getAttribute(OPERATING_SYSTEM, attribute);
            } catch (Exception ignored) {
                // try the next candidate name
            }
        }
        return null;
    }

    // ---- helpers ----

    /** Follows proxy wrapper exceptions to the real cause and words connection failures clearly. */
    static String describe(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof UndeclaredThrowableException || cause instanceof InvocationTargetException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return cause instanceof IOException ? "Connection failed - " + message : message;
    }

    static String maskArgument(String argument) {
        int separator = argument.indexOf('=');
        String masked = separator > 0 && SENSITIVE_ARGUMENT.matcher(argument.substring(0, separator)).find()
            ? argument.substring(0, separator + 1) + "****" : argument;
        return masked.length() > MAX_ARGUMENT_LENGTH ? masked.substring(0, MAX_ARGUMENT_LENGTH) + "..." : masked;
    }

    private static void line(StringBuilder sb, String label, String value) {
        sb.append("  ").append(label).append(": ").append(value).append('\n');
    }

    private static List<ObjectName> sorted(Set<ObjectName> names) {
        return new ArrayList<>(new TreeSet<>(names));
    }

    private static String usage(MemoryUsage usage) {
        String max = usage.getMax() < 0 ? "unlimited" : megabytes(usage.getMax());
        return megabytes(usage.getUsed()) + " used, " + megabytes(usage.getCommitted()) + " committed, "
            + max + " max";
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / MEGABYTE);
    }

    private static String percent(Number fraction) {
        double value = fraction.doubleValue();
        return value < 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private static String duration(long millis) {
        long days = TimeUnit.MILLISECONDS.toDays(millis);
        long hours = TimeUnit.MILLISECONDS.toHours(millis) % 24;
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        return (days > 0 ? days + "d " : "") + hours + "h " + minutes + "m " + seconds + "s";
    }

    private static ObjectName name(String name) {
        try {
            return new ObjectName(name);
        } catch (javax.management.MalformedObjectNameException e) {
            throw new IllegalStateException(e);
        }
    }
}
