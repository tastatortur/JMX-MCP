package org.jmxmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.ServerSocket;
import java.rmi.NoSuchObjectException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test of the MCP tool surface: the tools are registered as in the running server and called with
 * the same JSON arguments an MCP client sends, against a real JMX connection to this JVM.
 * <p>
 * Replaces the former {@code comprehensive-test.py} and, unlike it, runs with {@code mvn test}.
 */
@SpringBootTest
@ActiveProfiles("test")
class McpToolsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONNECTION_ID = "mcp-tools-it";

    private static Registry registry;
    private static JMXConnectorServer server;
    private static String url;

    @Autowired
    private List<ToolCallbackProvider> providers;

    @BeforeAll
    static void startJmxTarget() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        registry = LocateRegistry.createRegistry(port);
        url = "service:jmx:rmi:///jndi/rmi://127.0.0.1:" + port + "/jmxrmi";
        server = JMXConnectorServerFactory.newJMXConnectorServer(
            new JMXServiceURL(url), null, ManagementFactory.getPlatformMBeanServer());
        server.start();
    }

    @AfterAll
    static void stopJmxTarget() throws IOException, NoSuchObjectException {
        server.stop();
        UnicastRemoteObject.unexportObject(registry, true);
    }

    @Test
    void server_shouldRegisterAllTools() {
        assertThat(tools().keySet()).containsExactlyInAnyOrder(
            "listMBeans", "getMBeanInfo", "getAttribute", "setAttribute", "invokeOperation", "listDomains",
            "getConnectionInfo", "listJmxConnections", "switchJmxConnection", "discoverJmxServices",
            "autoRegisterDiscoveredConnections", "addJmxConnection", "removeJmxConnection",
            "getJvmOverview", "getThreadDump");
    }

    @Test
    void toolSchemas_shouldRequireOnlyMandatoryParameters() throws Exception {
        Map<String, List<String>> required = new LinkedHashMap<>();
        for (ToolCallback tool : tools().values()) {
            List<String> names = new ArrayList<>();
            MAPPER.readTree(tool.getToolDefinition().inputSchema()).path("required")
                .forEach(node -> names.add(node.asText()));
            required.put(tool.getToolDefinition().name(), names);
        }

        assertThat(required.get("listMBeans")).isEmpty();
        assertThat(required.get("getJvmOverview")).isEmpty();
        assertThat(required.get("getThreadDump")).isEmpty();
        assertThat(required.get("getAttribute")).containsExactlyInAnyOrder("objectName", "attributeName");
        assertThat(required.get("setAttribute")).containsExactlyInAnyOrder("objectName", "attributeName", "value");
        assertThat(required.get("invokeOperation")).containsExactlyInAnyOrder("objectName", "operationName");
        assertThat(required.get("addJmxConnection")).containsExactlyInAnyOrder("id", "name", "type", "url");
        assertThat(required.get("switchJmxConnection")).containsExactly("connectionId");
    }

    @Test
    void tools_shouldWorkEndToEndAgainstARealJmxConnection() throws Exception {
        assertThat(call("addJmxConnection", Map.of("id", CONNECTION_ID, "name", "Integration test JVM",
            "type", "REMOTE", "url", url))).startsWith("Successfully added");
        try {
            assertThat(call("switchJmxConnection", Map.of("connectionId", CONNECTION_ID)))
                .startsWith("Successfully switched");

            assertThat(call("listDomains", Map.of())).contains("java.lang");
            assertThat(call("listMBeans", Map.of())).contains("java.lang:type=Runtime");
            assertThat(call("listMBeans", Map.of("domain", "java.lang"))).contains("java.lang:type=Memory");
            assertThat(call("getMBeanInfo", Map.of("objectName", "java.lang:type=Threading")))
                .contains("getThreadCpuTime");

            assertThat(call("getAttribute", Map.of("objectName", "java.lang:type=Runtime",
                "attributeName", "VmName"))).contains("Attribute 'VmName' = ");
            assertThat(call("getAttribute", Map.of("objectName", "java.lang:type=Memory",
                "attributeName", "HeapMemoryUsage"))).containsPattern("\\{committed=\\d+, init=\\d+, max=-?\\d+, used=\\d+\\}");

            long threadId = Thread.currentThread().getId();
            assertThat(call("invokeOperation", Map.of("objectName", "java.lang:type=Threading",
                "operationName", "getThreadCpuTime", "arguments", "[\"" + threadId + "\"]")))
                .startsWith("Result (Long): ");

            String overview = call("getJvmOverview", Map.of());
            assertThat(overview).contains("JVM Overview", "Runtime:", "Memory:", "Garbage collection:",
                "Threads:", "Operating system:").doesNotContain("unavailable");

            // Optional parameters may be omitted entirely, as MCP clients do
            assertThat(call("getThreadDump", Map.of())).startsWith("Thread dump: ").contains("Deadlocks: ");
            assertThat(call("getThreadDump", Map.of("threadNameFilter", Thread.currentThread().getName(),
                "maxFrames", 3))).contains("match the filter");

            assertThat(call("getConnectionInfo", Map.of())).contains(url, "Connected: Yes");
            assertThat(call("listJmxConnections", Map.of())).contains(CONNECTION_ID);
        } finally {
            call("removeJmxConnection", Map.of("connectionId", CONNECTION_ID));
        }
    }

    @Test
    void tools_shouldReportErrorsAsTextInsteadOfThrowing() throws Exception {
        assertThat(call("getAttribute", Map.of("objectName", "not an object name", "attributeName", "x")))
            .startsWith("Error getting attribute: ");
        assertThat(call("getThreadDump", Map.of("state", "sleeping"))).contains("Valid states");
        assertThat(call("addJmxConnection", Map.of("id", "bad", "name", "Bad", "type", "LOCAL", "url", "")))
            .contains("cannot be dialed");
    }

    private Map<String, ToolCallback> tools() {
        Map<String, ToolCallback> tools = new LinkedHashMap<>();
        for (ToolCallbackProvider provider : providers) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                tools.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
        assertThat(new TreeSet<>(tools.keySet())).isNotEmpty();
        return tools;
    }

    /** Calls a tool with JSON arguments like an MCP client and returns the text result. */
    private String call(String tool, Map<String, Object> arguments) throws Exception {
        String raw = tools().get(tool).call(MAPPER.writeValueAsString(arguments));
        JsonNode node = MAPPER.readTree(raw);
        return node.isTextual() ? node.asText() : raw;
    }
}
