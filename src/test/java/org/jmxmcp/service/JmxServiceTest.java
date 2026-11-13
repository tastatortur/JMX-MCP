package org.jmxmcp.service;

import org.jmxmcp.config.JmxConnectionProperties;
import org.jmxmcp.config.JmxSecurityValidator;
import org.jmxmcp.jmx.JMXConnectionManager;
import org.jmxmcp.jmx.MBeanDiscoveryService;
import org.jmxmcp.model.MBeanInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JmxServiceTest {

    @Mock
    private MBeanDiscoveryService discoveryService;

    @Mock
    private JMXConnectionManager connectionManager;

    @Mock
    private JmxSecurityValidator securityValidator;

    @Mock
    private JmxDiscoveryService jmxDiscoveryService;

    @Mock
    private MBeanServerConnection mbeanServerConnection;

    private JmxService jmxService;

    // JUnit calls @BeforeEach methods via reflection
    @BeforeEach
    @SuppressWarnings("unused")
    void setUp() {
        jmxService = new JmxService(discoveryService, connectionManager, securityValidator, jmxDiscoveryService);
        lenient().when(securityValidator.validateObjectName(any(ObjectName.class)))
            .thenReturn(JmxSecurityValidator.ValidationResult.valid());
        lenient().when(securityValidator.validateOperation(any(ObjectName.class), anyString(), any()))
            .thenReturn(JmxSecurityValidator.ValidationResult.valid());
    }

    @Test
    void listMBeans_withNoDomain_shouldReturnAllMBeans() {
        // Given
        MBeanInfo mockMBean = createMockMBeanInfo("java.lang:type=Memory");
        when(discoveryService.getAllMBeans()).thenReturn(List.of(mockMBean));

        // When
        String result = jmxService.listMBeans(null);

        // Then
        assertThat(result)
            .contains("Found 1 MBeans")
            .contains("java.lang:type=Memory")
            .contains("TestClass");
    }

    @Test
    void listMBeans_withDomain_shouldReturnFilteredMBeans() {
        // Given
        MBeanInfo mockMBean = createMockMBeanInfo("java.lang:type=Memory");
        when(discoveryService.getMBeansByDomain("java.lang")).thenReturn(List.of(mockMBean));

        // When
        String result = jmxService.listMBeans("java.lang");

        // Then
        assertThat(result)
            .contains("Found 1 MBeans")
            .contains("in domain 'java.lang'")
            .contains("java.lang:type=Memory");
    }

    @Test
    void listMBeans_withEmptyResult_shouldReturnNoMBeansMessage() {
        // Given
        when(discoveryService.getAllMBeans()).thenReturn(List.of());

        // When
        String result = jmxService.listMBeans(null);

        // Then
        assertThat(result).isEqualTo("No MBeans discovered");
    }

    @Test
    void getMBeanInfo_withValidObjectName_shouldReturnMBeanDetails() {
        // Given
        String objectName = "java.lang:type=Memory";
        MBeanInfo mockMBean = createMockMBeanInfo(objectName);
        when(discoveryService.getMBean(objectName)).thenReturn(Optional.of(mockMBean));

        // When
        String result = jmxService.getMBeanInfo(objectName);

        // Then
        assertThat(result)
            .contains("MBean Information:")
            .contains("ObjectName: java.lang:type=Memory")
            .contains("Class: TestClass")
            .contains("Attributes (1):")
            .contains("Operations (1):");
    }

    @Test
    void getMBeanInfo_withInvalidObjectName_shouldReturnNotFoundMessage() {
        // Given
        String objectName = "invalid:name=test";
        when(discoveryService.getMBean(objectName)).thenReturn(Optional.empty());

        // When
        String result = jmxService.getMBeanInfo(objectName);

        // Then
        assertThat(result).isEqualTo("MBean not found: invalid:name=test");
    }

    @Test
    void listDomains_shouldReturnAllDomains() {
        // Given
        Set<String> domains = Set.of("java.lang", "com.sun.management", "java.util.logging");
        when(discoveryService.getAllDomains()).thenReturn(domains);

        // When
        String result = jmxService.listDomains();

        // Then
        assertThat(result)
            .contains("MBean Domains (3):")
            .contains("java.lang")
            .contains("com.sun.management")
            .contains("java.util.logging");
    }

    @Test
    void getConnectionInfo_shouldReturnConnectionDetails() {
        // Given
        JMXConnectionManager.ConnectionInfo connectionInfo =
            new JMXConnectionManager.ConnectionInfo(JmxConnectionProperties.ConnectionType.LOCAL, null, true, 25);
        MBeanDiscoveryService.DiscoveryStats stats =
            new MBeanDiscoveryService.DiscoveryStats(25, 150, 75, 5);
        
        when(connectionManager.getConnectionInfo()).thenReturn(connectionInfo);
        when(discoveryService.getDiscoveryStats()).thenReturn(stats);

        // When
        String result = jmxService.getConnectionInfo();

        // Then
        assertThat(result)
            .contains("JMX Connection Information:")
            .contains("Connection Type: LOCAL")
            .contains("Connected: Yes")
            .contains("Total MBeans: 25")
            .contains("Total Attributes: 150")
            .contains("Total Operations: 75")
            .contains("Total Domains: 5");
    }

    // ========================================
    // invokeOperation tests
    // ========================================

    private static final String UI_MBEAN = "aeol:type=ClientUiManagement";

    private MBeanInfo createUiMBeanInfo() {
        try {
            List<MBeanInfo.MBeanOperationInfo> ops = List.of(
                new MBeanInfo.MBeanOperationInfo(
                    "inspectUi", "Inspect the UI", "java.lang.String", List.of(),
                    javax.management.MBeanOperationInfo.INFO),
                new MBeanInfo.MBeanOperationInfo(
                    "click", "Click a UI element by id", "java.lang.String",
                    List.of(new MBeanInfo.MBeanParameterInfo("id", "java.lang.String", "element id")),
                    javax.management.MBeanOperationInfo.ACTION),
                new MBeanInfo.MBeanOperationInfo(
                    "setField", "Set a field", "void",
                    List.of(
                        new MBeanInfo.MBeanParameterInfo("name", "java.lang.String", "field name"),
                        new MBeanInfo.MBeanParameterInfo("value", "java.lang.String", "field value")),
                    javax.management.MBeanOperationInfo.ACTION),
                new MBeanInfo.MBeanOperationInfo(
                    "upload", "Upload data", "void",
                    List.of(new MBeanInfo.MBeanParameterInfo("data", "javax.management.openmbean.CompositeData", "payload")),
                    javax.management.MBeanOperationInfo.ACTION)
            );
            return new MBeanInfo(
                new ObjectName(UI_MBEAN), "aeol.ClientUiManagement", "UI management",
                List.of(), ops, List.of());
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void invokeOperation_withMatchingOverload_shouldInvokeAndReturnResult() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(eq(new ObjectName(UI_MBEAN)), eq("click"),
            eq(new Object[]{"2"}), eq(new String[]{"java.lang.String"}))).thenReturn("clicked 2");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"2\"]");

        // Then
        assertThat(result).contains("Result (String): clicked 2").contains("executed in");
    }

    @Test
    void invokeOperation_withNoArgs_shouldInvokeNoArgOverload() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(eq(new ObjectName(UI_MBEAN)), eq("inspectUi"),
            eq(new Object[0]), eq(new String[0]))).thenReturn("ui-state");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "inspectUi", "[]");

        // Then
        assertThat(result).contains("Result (String): ui-state");
    }

    @Test
    void invokeOperation_withWrongArgCount_shouldListAvailableSignatures() {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"1\",\"2\"]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("No overload of 'click' takes 2 argument(s)")
            .contains("click(java.lang.String id) : java.lang.String");
    }

    @Test
    void invokeOperation_withUnknownOperation_shouldListAvailableOperations() {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "explode", "[]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("Operation 'explode' not found")
            .contains("inspectUi()")
            .contains("click(java.lang.String id)");
    }

    @Test
    void invokeOperation_withUnsupportedParameterType_shouldReturnClearError() {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "upload", "[\"{}\"]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("Unsupported parameter type 'javax.management.openmbean.CompositeData'");
    }

    @Test
    void invokeOperation_withInvalidJsonArguments_shouldReturnError() {
        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "{not json");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("Invalid JSON arguments");
    }

    @Test
    void invokeOperation_withNonArrayArguments_shouldReturnError() {
        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "\"2\"");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("must be a JSON array of string values");
    }

    @Test
    void invokeOperation_withMBeanException_shouldUnwrapCause() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("click"), any(), any()))
            .thenThrow(new MBeanException(new IllegalArgumentException("no such element")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"99\"]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("IllegalArgumentException")
            .contains("no such element");
    }

    @Test
    void invokeOperation_withSecurityValidationFailure_shouldReturnSecurityError() {
        // Given
        when(securityValidator.validateObjectName(any(ObjectName.class)))
            .thenReturn(JmxSecurityValidator.ValidationResult.invalid("blocked domain"));

        // When
        String result = jmxService.invokeOperation("java.lang:type=Memory", "gc", "[]");

        // Then
        assertThat(result).isEqualTo("Security validation failed: blocked domain");
    }

    @Test
    void invokeOperation_withMalformedObjectName_shouldReturnError() {
        // When
        String result = jmxService.invokeOperation("invalid:name", "click", "[]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("Invalid ObjectName format");
    }

    @Test
    void invokeOperation_withMBeanNotFound_shouldReturnError() {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.empty());

        // When
        String result = jmxService.invokeOperation("unknown:type=Foo", "click", "[]");

        // Then
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("MBean not found: unknown:type=Foo");
    }

    @Test
    void invokeOperation_withIoException_shouldNotRetryAndShouldReconnect() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("click"), any(), any()))
            .thenThrow(new IOException("connection reset"));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"2\"]");

        // Then: the operation is invoked exactly once (it may have run on the target already)
        assertThat(result)
            .contains("Error invoking operation:")
            .contains("Connection failed")
            .contains("not retried");
        verify(mbeanServerConnection, times(1)).invoke(any(ObjectName.class), eq("click"), any(), any());
        verify(connectionManager).reconnect();
    }

    @Test
    void invokeOperation_withOverloadsOfSameArity_shouldPickConvertibleOne() throws Exception {
        // Given: set(int) is listed first but "abc" is not an int, so set(String) must be chosen
        MBeanInfo mbean = new MBeanInfo(
            new ObjectName(UI_MBEAN), "Cls", "d", List.of(),
            List.of(
                new MBeanInfo.MBeanOperationInfo("set", "d", "void",
                    List.of(new MBeanInfo.MBeanParameterInfo("v", "int", "")),
                    javax.management.MBeanOperationInfo.ACTION),
                new MBeanInfo.MBeanOperationInfo("set", "d", "void",
                    List.of(new MBeanInfo.MBeanParameterInfo("v", "java.lang.String", "")),
                    javax.management.MBeanOperationInfo.ACTION)),
            List.of());
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(mbean));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("set"),
            eq(new Object[]{"abc"}), eq(new String[]{"java.lang.String"}))).thenReturn("ok");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[\"abc\"]");

        // Then
        assertThat(result).contains("Result (String): ok");
    }

    @Test
    void invokeOperation_withExponentNumber_shouldConvertToIntegralString() throws Exception {
        // Given
        MBeanInfo mbean = new MBeanInfo(
            new ObjectName(UI_MBEAN), "Cls", "d", List.of(),
            List.of(new MBeanInfo.MBeanOperationInfo("wait", "d", "void",
                List.of(new MBeanInfo.MBeanParameterInfo("ms", "int", "")),
                javax.management.MBeanOperationInfo.ACTION)),
            List.of());
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(mbean));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("wait"),
            eq(new Object[]{1000}), eq(new String[]{"int"}))).thenReturn("done");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "wait", "[1e3]");

        // Then
        assertThat(result).contains("Result (String): done");
    }

    @Test
    void invokeOperation_withNullArgumentForString_shouldPassNull() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("click"),
            eq(new Object[]{null}), eq(new String[]{"java.lang.String"}))).thenReturn("null clicked");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[null]");

        // Then
        assertThat(result).contains("Result (String): null clicked");
    }

    private MBeanInfo mbeanWithOverloads(String op, String... paramTypes) throws Exception {
        List<MBeanInfo.MBeanOperationInfo> ops = new ArrayList<>();
        for (String type : paramTypes) {
            ops.add(new MBeanInfo.MBeanOperationInfo(op, "d", "void",
                List.of(new MBeanInfo.MBeanParameterInfo("v", type, "")),
                javax.management.MBeanOperationInfo.ACTION));
        }
        return new MBeanInfo(new ObjectName(UI_MBEAN), "Cls", "d", List.of(), ops, List.of());
    }

    @Test
    void invokeOperation_withInvalidNumberAndUnsupportedOverload_shouldReportConversionError() throws Exception {
        // Given: get(long[]) is listed first, but the useful error is that "abc" is not a long
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("get", "[J", "long")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "get", "[\"abc\"]");

        // Then
        assertThat(result)
            .contains("Invalid value 'abc'")
            .contains("of type long")
            .doesNotContain("Unsupported parameter type");
    }

    @Test
    void invokeOperation_withOnlyUnsupportedOverloads_shouldReportUnsupportedType() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("get", "[J", "[I")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "get", "[\"1\"]");

        // Then
        assertThat(result).contains("Unsupported parameter type '[J'");
    }

    @Test
    void invokeOperation_withSeveralConversionErrors_shouldReportTheFirstOverload() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "int", "long")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[\"abc\"]");

        // Then
        assertThat(result).contains("Invalid value 'abc'").contains("of type int");
    }

    @Test
    void getAttribute_withStringArray_shouldListElements() throws Exception {
        // Given
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.getAttribute(any(ObjectName.class), eq("InputArguments")))
            .thenReturn(new String[]{"-Xmx512m", "-Dfoo=bar"});

        // When
        String result = jmxService.getAttribute("java.lang:type=Runtime", "InputArguments");

        // Then
        assertThat(result).isEqualTo("Attribute 'InputArguments' = [-Xmx512m, -Dfoo=bar] (type: String[])");
    }

    @Test
    void invokeOperation_withCompositeResult_shouldRenderItemsWithoutTypeMetadata() throws Exception {
        // Given
        CompositeType type = new CompositeType("Usage", "d", new String[]{"max", "used"},
            new String[]{"max", "used"}, new OpenType<?>[]{SimpleType.LONG, SimpleType.LONG});
        CompositeData usage = new CompositeDataSupport(type, new String[]{"max", "used"}, new Object[]{100L, 42L});
        when(discoveryService.getMBean(any(ObjectName.class))).thenReturn(Optional.of(createUiMBeanInfo()));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("click"), any(), any())).thenReturn(usage);

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"1\"]");

        // Then
        assertThat(result)
            .contains("Result (CompositeDataSupport): {max=100, used=42}")
            .doesNotContain("CompositeType");
    }

    @Test
    void invokeOperation_withBooleanListedBeforeInt_shouldPickIntForNumericArgument() throws Exception {
        // Given: "5" is not a valid boolean, so set(int) must win even though set(boolean) is listed first
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "boolean", "int")));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("set"),
            eq(new Object[]{5}), eq(new String[]{"int"}))).thenReturn("int chosen");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[5]");

        // Then
        assertThat(result).contains("Result (String): int chosen");
    }

    @Test
    void invokeOperation_withStringListedBeforeInt_shouldPreferIntForNumericArgument() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "java.lang.String", "int")));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("set"),
            eq(new Object[]{5}), eq(new String[]{"int"}))).thenReturn("int chosen");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[5]");

        // Then
        assertThat(result).contains("Result (String): int chosen");
    }

    @Test
    void invokeOperation_withInvalidBooleanValue_shouldReturnError() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "boolean")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[\"yes\"]");

        // Then
        assertThat(result).contains("Error invoking operation:").contains("expected true or false");
    }

    @Test
    void invokeOperation_withNullForPrimitive_shouldFallThroughToStringOverload() throws Exception {
        // Given: null cannot go to int, so the String overload is used
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "int", "java.lang.String")));
        when(connectionManager.getConnection()).thenReturn(mbeanServerConnection);
        when(mbeanServerConnection.invoke(any(ObjectName.class), eq("set"),
            eq(new Object[]{null}), eq(new String[]{"java.lang.String"}))).thenReturn("string chosen");

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[null]");

        // Then
        assertThat(result).contains("Result (String): string chosen");
    }

    @Test
    void invokeOperation_withNullForOnlyPrimitiveOverload_shouldReturnError() throws Exception {
        // Given
        when(discoveryService.getMBean(any(ObjectName.class)))
            .thenReturn(Optional.of(mbeanWithOverloads("set", "int")));

        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "set", "[null]");

        // Then
        assertThat(result).contains("Error invoking operation:").contains("Null is not allowed for primitive");
    }

    @Test
    void invokeOperation_withTrailingGarbageAfterArray_shouldReturnError() {
        // When
        String result = jmxService.invokeOperation(UI_MBEAN, "click", "[\"1\"] xyz");

        // Then
        assertThat(result).contains("Error invoking operation:").contains("Invalid JSON arguments");
    }

    private MBeanInfo createMockMBeanInfo(String objectNameStr) {
        try {
            ObjectName objectName = new ObjectName(objectNameStr);
            
            MBeanInfo.MBeanAttributeInfo attr = new MBeanInfo.MBeanAttributeInfo(
                "TestAttribute", "java.lang.String", "Test attribute", true, false, false
            );

            MBeanInfo.MBeanOperationInfo op = new MBeanInfo.MBeanOperationInfo(
                "testOperation", "Test operation", "void", List.of(), 1
            );

            return new MBeanInfo(
                objectName,
                "TestClass",
                "Test MBean",
                List.of(attr),
                List.of(op),
                List.of()
            );
        } catch (MalformedObjectNameException e) {
            throw new RuntimeException(e);
        }
    }
}
