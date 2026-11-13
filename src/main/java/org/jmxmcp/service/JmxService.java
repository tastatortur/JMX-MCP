package org.jmxmcp.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.jmxmcp.config.JmxConnectionProperties;
import org.jmxmcp.config.JmxSecurityValidator;
import org.jmxmcp.jmx.JMXConnectionManager;
import org.jmxmcp.jmx.JmxConnectionInfo;
import org.jmxmcp.jmx.JmxConnectionRegistry;
import org.jmxmcp.jmx.MBeanDiscoveryService;
import org.jmxmcp.model.JmxOperationResult;
import org.jmxmcp.model.JmxValueFormatter;
import org.jmxmcp.model.MBeanInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service providing JMX operations for MCP tools.
 */
@Service
public class JmxService {

    private static final Logger logger = LoggerFactory.getLogger(JmxService.class);
    private static final String SECURITY_VALIDATION_FAILED = "Security validation failed: ";
    private static final String ERROR_GETTING_ATTRIBUTE = "Error getting attribute: ";
    private static final String ERROR_SETTING_ATTRIBUTE = "Error setting attribute: ";
    private static final String ERROR_INVOKING_OPERATION = "Error invoking operation: ";

    private static final String UNSUPPORTED_PARAMETER_TYPE = "Unsupported parameter type";

    private static final Set<String> PRIMITIVE_PARAMETER_TYPES = Set.of(
        "boolean", "byte", "short", "int", "long", "float", "double", "char");

    /** Parameter types supported for operation invocation in v1 (String, primitives, wrappers). */
    private static final Set<String> SUPPORTED_PARAMETER_TYPES = Set.of(
        "boolean", "java.lang.Boolean",
        "byte", "java.lang.Byte",
        "short", "java.lang.Short",
        "int", "java.lang.Integer",
        "long", "java.lang.Long",
        "float", "java.lang.Float",
        "double", "java.lang.Double",
        "char", "java.lang.Character",
        "java.lang.String", "String"
    );

    /** Stateless parser for operation arguments; rejects trailing tokens such as {@code ["1"] xyz}. */
    private static final ObjectReader ARGUMENT_READER = new ObjectMapper()
        .readerFor(JsonNode.class)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final MBeanDiscoveryService discoveryService;
    private final JMXConnectionManager connectionManager;
    private final JmxSecurityValidator securityValidator;
    private final JmxDiscoveryService jmxDiscoveryService;

    public JmxService(
            MBeanDiscoveryService discoveryService,
            JMXConnectionManager connectionManager,
            JmxSecurityValidator securityValidator,
            JmxDiscoveryService jmxDiscoveryService) {
        this.discoveryService = discoveryService;
        this.connectionManager = connectionManager;
        this.securityValidator = securityValidator;
        this.jmxDiscoveryService = jmxDiscoveryService;
    }

    @Tool(description = "List all discovered MBeans with their basic information. Optionally filter by domain.")
    public String listMBeans(String domain) {
        try {
            Collection<MBeanInfo> mbeans;
            if (domain != null && !domain.trim().isEmpty()) {
                mbeans = discoveryService.getMBeansByDomain(domain.trim());
            } else {
                mbeans = discoveryService.getAllMBeans();
            }
            
            if (mbeans.isEmpty()) {
                return domain != null ? 
                    "No MBeans found in domain: " + domain : 
                    "No MBeans discovered";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("Found ").append(mbeans.size()).append(" MBeans");
            if (domain != null) {
                result.append(" in domain '").append(domain).append("'");
            }
            result.append(":\n\n");
            
            for (MBeanInfo mbean : mbeans) {
                result.append("• ").append(mbean.objectName()).append("\n");
                result.append("  Class: ").append(mbean.className()).append("\n");
                if (mbean.description() != null && !mbean.description().isEmpty()) {
                    result.append("  Description: ").append(mbean.description()).append("\n");
                }
                result.append("  Attributes: ").append(mbean.attributes().size());
                result.append(", Operations: ").append(mbean.operations().size()).append("\n\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Error listing MBeans", e);
            return "Error listing MBeans: " + e.getMessage();
        }
    }

    @Tool(description = "Get detailed information about a specific MBean including all attributes and operations")
    public String getMBeanInfo(String objectName) {
        try {
            Optional<MBeanInfo> mbeanOpt = discoveryService.getMBean(objectName);
            if (mbeanOpt.isEmpty()) {
                return "MBean not found: " + objectName;
            }
            
            MBeanInfo mbean = mbeanOpt.get();
            StringBuilder result = new StringBuilder();
            
            result.append("MBean Information:\n");
            result.append("==================\n");
            result.append("ObjectName: ").append(mbean.objectName()).append("\n");
            result.append("Class: ").append(mbean.className()).append("\n");
            result.append("Description: ").append(mbean.description() != null ? mbean.description() : "N/A").append("\n\n");
            
            // Attributes
            result.append("Attributes (").append(mbean.attributes().size()).append("):\n");
            result.append("-------------------\n");
            for (MBeanInfo.MBeanAttributeInfo attr : mbean.attributes()) {
                result.append("• ").append(attr.name()).append(" (").append(attr.type()).append(")");
                
                List<String> access = new ArrayList<>();
                if (attr.readable()) access.add("R");
                if (attr.writable()) access.add("W");
                result.append(" [").append(String.join("/", access)).append("]");
                
                if (attr.description() != null && !attr.description().isEmpty()) {
                    result.append("\n  ").append(attr.description());
                }
                result.append("\n");
            }
            
            // Operations
            result.append("\nOperations (").append(mbean.operations().size()).append("):\n");
            result.append("-------------------\n");
            for (MBeanInfo.MBeanOperationInfo op : mbean.operations()) {
                result.append("• ").append(op.name()).append("(");
                result.append(op.parameters().stream()
                    .map(p -> p.type() + " " + p.name())
                    .collect(Collectors.joining(", ")));
                result.append(") : ").append(op.returnType()).append("\n");
                
                if (op.description() != null && !op.description().isEmpty()) {
                    result.append("  ").append(op.description()).append("\n");
                }
                result.append("  Impact: ").append(op.getImpactString()).append("\n");
            }
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Error getting MBean info", e);
            return "Error getting MBean info: " + e.getMessage();
        }
    }

    @Tool(description = "Get the value of an MBean attribute")
    public String getAttribute(String objectName, String attributeName) {
        try {
            ObjectName objName = new ObjectName(objectName);
            
            // Security validation
            var validation = securityValidator.validateObjectName(objName);
            if (!validation.isValid()) {
                return SECURITY_VALIDATION_FAILED + validation.getErrorMessage();
            }
            
            MBeanServerConnection connection = connectionManager.getConnection();
            Object value = connection.getAttribute(objName, attributeName);
            
            return "Attribute '" + attributeName + "' = " +
                   JmxValueFormatter.format(value) +
                   " (type: " + (value != null ? value.getClass().getSimpleName() : "null") + ")";
                   
        } catch (javax.management.MalformedObjectNameException e) {
            logger.error("Invalid ObjectName: {}", objectName, e);
            return ERROR_GETTING_ATTRIBUTE + "Invalid ObjectName format - " + e.getMessage();
        } catch (javax.management.MBeanException | javax.management.AttributeNotFoundException |
                 javax.management.InstanceNotFoundException | javax.management.ReflectionException e) {
            logger.error("JMX error getting attribute", e);
            return ERROR_GETTING_ATTRIBUTE + e.getMessage();
        } catch (java.io.IOException e) {
            logger.error("Connection error getting attribute", e);
            return ERROR_GETTING_ATTRIBUTE + "Connection failed - " + e.getMessage();
        } catch (IllegalStateException e) {
            logger.error("Connection state error getting attribute", e);
            return ERROR_GETTING_ATTRIBUTE + e.getMessage();
        } catch (RuntimeException e) {
            logger.error("Unexpected error getting attribute", e);
            return ERROR_GETTING_ATTRIBUTE + e.getMessage();
        }
    }

    @Tool(description = "Set the value of an MBean attribute")
    public String setAttribute(String objectName, String attributeName, String value) {
        try {
            ObjectName objName = new ObjectName(objectName);
            
            // Security validation
            var validation = securityValidator.validateObjectName(objName);
            if (!validation.isValid()) {
                return SECURITY_VALIDATION_FAILED + validation.getErrorMessage();
            }
            
            MBeanServerConnection connection = connectionManager.getConnection();
            
            // Get attribute info to determine type
            Optional<MBeanInfo> mbeanOpt = discoveryService.getMBean(objectName);
            if (mbeanOpt.isEmpty()) {
                return "MBean not found: " + objectName;
            }
            
            MBeanInfo.MBeanAttributeInfo attrInfo = mbeanOpt.get().getAttribute(attributeName);
            if (attrInfo == null) {
                return "Attribute not found: " + attributeName;
            }
            
            if (!attrInfo.writable()) {
                return "Attribute '" + attributeName + "' is not writable";
            }
            
            // Convert string value to appropriate type
            Object convertedValue = convertStringToType(value, attrInfo.type());
            
            Attribute attribute = new Attribute(attributeName, convertedValue);
            connection.setAttribute(objName, attribute);
            
            return "Successfully set attribute '" + attributeName + "' to '" + value + "'";
            
        } catch (javax.management.MalformedObjectNameException e) {
            logger.error("Invalid ObjectName: {}", objectName, e);
            return ERROR_SETTING_ATTRIBUTE + "Invalid ObjectName format - " + e.getMessage();
        } catch (javax.management.MBeanException | javax.management.AttributeNotFoundException |
                 javax.management.InstanceNotFoundException | javax.management.ReflectionException |
                 javax.management.InvalidAttributeValueException e) {
            logger.error("JMX error setting attribute", e);
            return ERROR_SETTING_ATTRIBUTE + e.getMessage();
        } catch (java.io.IOException e) {
            logger.error("Connection error setting attribute", e);
            return ERROR_SETTING_ATTRIBUTE + "Connection failed - " + e.getMessage();
        } catch (IllegalStateException e) {
            logger.error("Connection state error setting attribute", e);
            return ERROR_SETTING_ATTRIBUTE + e.getMessage();
        } catch (NumberFormatException e) {
            logger.error("Invalid value format for attribute", e);
            return ERROR_SETTING_ATTRIBUTE + "Invalid value format - " + e.getMessage();
        } catch (RuntimeException e) {
            logger.error("Unexpected error setting attribute", e);
            return ERROR_SETTING_ATTRIBUTE + e.getMessage();
        }
    }

    @Tool(description = "Invoke an operation on an MBean. arguments is a JSON array of string values, e.g. [\"2\"] for click(\"2\"). Returns the result or an error.")
    public String invokeOperation(String objectName, String operationName, String arguments) {
        long startTime = System.currentTimeMillis();
        ObjectName objName;
        try {
            objName = new ObjectName(objectName);
        } catch (MalformedObjectNameException e) {
            logger.error("Invalid ObjectName: {}", objectName, e);
            return ERROR_INVOKING_OPERATION + "Invalid ObjectName format - " + e.getMessage();
        }

        // Security validation (ObjectName, operation name and JSON arguments)
        var objectNameValidation = securityValidator.validateObjectName(objName);
        if (!objectNameValidation.isValid()) {
            return SECURITY_VALIDATION_FAILED + objectNameValidation.getErrorMessage();
        }
        var operationValidation = securityValidator.validateOperation(objName, operationName, arguments);
        if (!operationValidation.isValid()) {
            return SECURITY_VALIDATION_FAILED + operationValidation.getErrorMessage();
        }

        // Parse arguments: a JSON array of string values, e.g. ["2"] for click("2")
        List<String> args;
        try {
            args = parseOperationArguments(arguments);
        } catch (IllegalArgumentException e) {
            return ERROR_INVOKING_OPERATION + e.getMessage();
        }

        // Resolve the operation signature from the discovered MBean info
        Optional<MBeanInfo> mbeanOpt = discoveryService.getMBean(objName);
        if (mbeanOpt.isEmpty()) {
            return ERROR_INVOKING_OPERATION + "MBean not found: " + objectName
                + " (connect first via discoverJmxServices()/switchJmxConnection(), then listMBeans)";
        }
        MBeanInfo mbean = mbeanOpt.get();
        List<MBeanInfo.MBeanOperationInfo> candidates = mbean.getOperations(operationName);
        if (candidates.isEmpty()) {
            return ERROR_INVOKING_OPERATION + "Operation '" + operationName + "' not found on " + objectName
                + ". Available operations:\n" + formatOperationSignatures(mbean.operations());
        }

        // Select the first overload whose parameter count matches AND whose parameter types can be
        // converted from the supplied arguments; v1 supports String/primitives/wrappers only
        List<MBeanInfo.MBeanOperationInfo> sameArity = candidates.stream()
            .filter(op -> op.parameters().size() == args.size())
            // String parameters accept anything, so try overloads with fewer String parameters first
            // (stable sort keeps the reported order otherwise)
            .sorted(Comparator.comparingLong(op -> op.parameters().stream()
                .filter(p -> p.type().equals("java.lang.String") || p.type().equals("String")).count()))
            .toList();
        if (sameArity.isEmpty()) {
            return ERROR_INVOKING_OPERATION + "No overload of '" + operationName + "' takes " + args.size()
                + " argument(s). Available signatures:\n" + formatOperationSignatures(candidates);
        }
        Object[] params = null;
        String[] signature = null;
        // Report a conversion error from a supported overload in preference to an unsupported-type error from
        // another one, e.g. "abc" is not a long rather than "unsupported parameter type [J"
        String conversionError = null;
        String unsupportedError = null;
        for (MBeanInfo.MBeanOperationInfo operation : sameArity) {
            Object[] convertedParams = new Object[args.size()];
            String[] convertedSignature = new String[args.size()];
            String error = convertArguments(operationName, operation, args, convertedParams, convertedSignature);
            if (error == null) {
                params = convertedParams;
                signature = convertedSignature;
                break;
            }
            if (error.startsWith(UNSUPPORTED_PARAMETER_TYPE)) {
                if (unsupportedError == null) {
                    unsupportedError = error;
                }
            } else if (conversionError == null) {
                conversionError = error;
            }
        }
        if (params == null) {
            return ERROR_INVOKING_OPERATION + (conversionError != null ? conversionError : unsupportedError);
        }

        // Invoke the operation. A connection failure is NOT retried: the target may already have executed
        // the operation before the reply was lost, and operations are not necessarily idempotent.
        try {
            Object result = connectionManager.getConnection().invoke(objName, operationName, params, signature);
            return formatSuccess(objName, operationName, result, startTime);
        } catch (IOException e) {
            logger.error("Connection failed while invoking {}.{}", objName, operationName, e);
            connectionManager.reconnect();
            return ERROR_INVOKING_OPERATION + "Connection failed - the operation may or may not have been "
                + "executed on the target, so it was not retried. The connection was re-established; verify "
                + "the MBean state before invoking again - " + e.getMessage();
        } catch (InstanceNotFoundException | MBeanException | ReflectionException e) {
            return invocationError(objName, operationName, e);
        } catch (IllegalStateException e) {
            logger.error("Connection state error invoking operation", e);
            return ERROR_INVOKING_OPERATION + e.getMessage();
        } catch (RuntimeException e) {
            logger.error("Unexpected error invoking operation", e);
            return ERROR_INVOKING_OPERATION + e.getMessage();
        }
    }

    /**
     * Converts the string arguments to the operation's declared parameter types.
     *
     * @return null on success (params/signature filled in), otherwise an error message
     */
    private String convertArguments(String operationName, MBeanInfo.MBeanOperationInfo operation,
                                    List<String> args, Object[] params, String[] signature) {
        for (int i = 0; i < args.size(); i++) {
            MBeanInfo.MBeanParameterInfo parameter = operation.parameters().get(i);
            String paramType = parameter.type();
            if (!SUPPORTED_PARAMETER_TYPES.contains(paramType)) {
                return UNSUPPORTED_PARAMETER_TYPE + " '" + paramType + "' for parameter '"
                    + parameter.name() + "' of operation '" + operationName
                    + "' (supported: String, primitives and their wrappers)";
            }
            String value = args.get(i);
            if (value == null && PRIMITIVE_PARAMETER_TYPES.contains(paramType)) {
                return "Null is not allowed for primitive parameter '" + parameter.name() + "' of type " + paramType;
            }
            if (value != null && (paramType.equals("boolean") || paramType.equals("java.lang.Boolean"))
                    && !value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                return "Invalid value '" + value + "' for parameter '" + parameter.name()
                    + "' of type " + paramType + " - expected true or false";
            }
            if (value != null && (paramType.equals("char") || paramType.equals("java.lang.Character"))
                    && value.length() != 1) {
                return "Invalid value '" + value + "' for parameter '" + parameter.name()
                    + "' of type " + paramType + " - expected a single character";
            }
            try {
                params[i] = convertStringToType(value, paramType);
            } catch (RuntimeException e) {
                return "Invalid value '" + args.get(i) + "' for parameter '"
                    + parameter.name() + "' of type " + paramType + " - " + e.getMessage();
            }
            signature[i] = paramType;
        }
        return null;
    }

    /**
     * Formats a successful operation invocation via {@link JmxOperationResult}.
     */
    private String formatSuccess(ObjectName objName, String operationName, Object result, long startTime) {
        long elapsed = System.currentTimeMillis() - startTime;
        JmxOperationResult opResult = JmxOperationResult.success(objName, operationName, result, elapsed);
        logger.info("Invoked {}.{} in {} ms", objName, operationName, elapsed);
        return opResult.getFormattedResult() + String.format(" (executed in %d ms)", elapsed);
    }

    /**
     * Maps an exception thrown by {@code MBeanServerConnection.invoke} to an error message.
     * {@link MBeanException} is unwrapped so the real cause is reported.
     */
    private String invocationError(ObjectName objName, String operationName, Exception e) {
        logger.error("Error invoking operation {}.{}", objName, operationName, e);
        String message;
        if (e instanceof MBeanException mbeanException && mbeanException.getCause() != null) {
            Throwable cause = mbeanException.getCause();
            message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        } else {
            message = e.getMessage();
        }
        return ERROR_INVOKING_OPERATION + message;
    }

    /**
     * Parses the arguments string as a JSON array of string values, e.g. {@code ["2"]}.
     * Scalar JSON values (string/number/boolean) are accepted and coerced to strings; null is passed through.
     */
    private List<String> parseOperationArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return List.of();
        }
        JsonNode node;
        try {
            node = ARGUMENT_READER.readValue(arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                "Invalid JSON arguments (expected a JSON array of string values, e.g. [\"2\"]): " + e.getMessage());
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(
                "Arguments must be a JSON array of string values, e.g. [\"2\"] for click(\"2\") - got "
                    + node.getNodeType());
        }
        List<String> args = new ArrayList<>(node.size());
        for (int i = 0; i < node.size(); i++) {
            JsonNode element = node.get(i);
            if (element.isNull()) {
                args.add(null);
            } else if (element.isNumber()) {
                // 1e3 / 1.0 become "1000" / "1" so integral parameters accept them
                args.add(element.decimalValue().stripTrailingZeros().toPlainString());
            } else if (element.isTextual() || element.isBoolean()) {
                args.add(element.asText());
            } else {
                throw new IllegalArgumentException(
                    "Argument at index " + i + " must be a string value, e.g. [\"2\"] - got " + element.getNodeType());
            }
        }
        return args;
    }

    /**
     * Formats operation signatures for error messages, one per line.
     */
    private String formatOperationSignatures(List<MBeanInfo.MBeanOperationInfo> operations) {
        return operations.stream()
            .map(op -> "  " + op.name() + "(" + op.parameters().stream()
                .map(p -> p.type() + " " + p.name())
                .collect(Collectors.joining(", ")) + ") : " + op.returnType()
                + " [impact: " + op.getImpactString() + "]")
            .collect(Collectors.joining("\n"));
    }

    @Tool(description = "List all MBean domains")
    public String listDomains() {
        try {
            Set<String> domains = discoveryService.getAllDomains();
            
            if (domains.isEmpty()) {
                return "No domains found";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("MBean Domains (").append(domains.size()).append("):\n");
            
            domains.stream().sorted().forEach(domain -> 
                result.append("• ").append(domain).append("\n"));
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Error listing domains", e);
            return "Error listing domains: " + e.getMessage();
        }
    }

    @Tool(description = "Get JMX connection status and information")
    public String getConnectionInfo() {
        try {
            JMXConnectionManager.ConnectionInfo info = connectionManager.getConnectionInfo();
            MBeanDiscoveryService.DiscoveryStats stats = discoveryService.getDiscoveryStats();
            
            StringBuilder result = new StringBuilder();
            result.append("JMX Connection Information:\n");
            result.append("===========================\n");
            result.append("Connection Type: ").append(info.type()).append("\n");
            result.append("URL: ").append(info.url() != null ? info.url() : "N/A (local)").append("\n");
            result.append("Connected: ").append(info.connected() ? "Yes" : "No").append("\n");
            result.append("Total MBeans: ").append(stats.totalMBeans()).append("\n");
            result.append("Total Attributes: ").append(stats.totalAttributes()).append("\n");
            result.append("Total Operations: ").append(stats.totalOperations()).append("\n");
            result.append("Total Domains: ").append(stats.totalDomains()).append("\n");
            
            return result.toString();
            
        } catch (Exception e) {
            logger.error("Error getting connection info", e);
            return "Error getting connection info: " + e.getMessage();
        }
    }

    // ========================================
    // Connection Management Tools
    // ========================================

    @Tool(description = "List all available JMX connections including their status and details")
    public String listJmxConnections() {
        try {
            JmxConnectionRegistry registry = connectionManager.getConnectionRegistry();
            Collection<JmxConnectionInfo> connections = registry.getAllConnections();
            Optional<String> activeId = registry.getActiveConnectionId();

            if (connections.isEmpty()) {
                return "No JMX connections configured. Use discoverJmxServices() to find available services.";
            }

            StringBuilder result = new StringBuilder();
            result.append("JMX Connections (").append(connections.size()).append("):\n");
            result.append("=====================================\n");

            for (JmxConnectionInfo conn : connections) {
                result.append("• ").append(conn.name()).append(" (").append(conn.id()).append(")\n");
                result.append("  Type: ").append(conn.type()).append("\n");
                if (conn.url() != null) {
                    result.append("  URL: ").append(conn.url()).append("\n");
                }
                result.append("  Status: ").append(conn.status());
                if (conn.id().equals(activeId.orElse(null))) {
                    result.append(" [ACTIVE]");
                }
                result.append("\n");
                if (conn.mbeanCount() != null) {
                    result.append("  MBeans: ").append(conn.mbeanCount()).append("\n");
                }
                if (conn.errorMessage() != null) {
                    result.append("  Error: ").append(conn.errorMessage()).append("\n");
                }
                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            logger.error("Error listing JMX connections", e);
            return "Error listing JMX connections: " + e.getMessage();
        }
    }

    @Tool(description = "Switch to a different JMX connection by connection ID")
    public String switchJmxConnection(String connectionId) {
        try {
            JmxConnectionRegistry registry = connectionManager.getConnectionRegistry();

            if (!registry.hasConnection(connectionId)) {
                return "Connection not found: " + connectionId + ". Use listJmxConnections() to see available connections.";
            }

            boolean success = connectionManager.connectToConnection(connectionId);
            if (success) {
                // Refresh the MBean cache for the newly active connection so listMBeans/getMBeanInfo
                // work immediately instead of waiting for the next scheduled discovery
                discoveryService.discoverMBeans();
                Optional<JmxConnectionInfo> connection = registry.getConnection(connectionId);
                String name = connection.map(JmxConnectionInfo::name).orElse(connectionId);
                return "Successfully switched to JMX connection: " + name + " (" + connectionId + ")";
            } else {
                return "Failed to switch to connection: " + connectionId + ". Check connection details and try again.";
            }

        } catch (Exception e) {
            logger.error("Error switching JMX connection", e);
            return "Error switching JMX connection: " + e.getMessage();
        }
    }

    @Tool(description = "Discover JMX-enabled applications on the local system")
    public String discoverJmxServices() {
        try {
            JmxDiscoveryService.DiscoveryResult result = jmxDiscoveryService.discoverJmxServices();

            StringBuilder response = new StringBuilder();
            response.append("JMX Service Discovery Results:\n");
            response.append("==============================\n");
            response.append("Discovered ").append(result.getDiscoveredCount()).append(" JMX services\n\n");

            if (result.getDiscoveredCount() > 0) {
                for (JmxConnectionInfo conn : result.discoveredConnections()) {
                    response.append("• ").append(conn.name()).append("\n");
                    response.append("  ID: ").append(conn.id()).append("\n");
                    response.append("  URL: ").append(conn.url()).append("\n");
                    if (conn.mbeanCount() != null) {
                        response.append("  MBeans: ").append(conn.mbeanCount()).append("\n");
                    }
                    response.append("\n");
                }

                response.append("Use addJmxConnection() to add any of these to your connection registry,\n");
                response.append("or use autoRegisterDiscoveredConnections() to add them all automatically.");
            } else {
                response.append("No JMX services found. Make sure your applications are running with JMX enabled.\n");
                response.append("Common JMX configuration:\n");
                response.append("  -Dcom.sun.management.jmxremote\n");
                response.append("  -Dcom.sun.management.jmxremote.port=9999\n");
                response.append("  -Dcom.sun.management.jmxremote.authenticate=false\n");
                response.append("  -Dcom.sun.management.jmxremote.ssl=false");
            }

            if (result.hasErrors()) {
                response.append("\n\nErrors encountered:\n");
                for (String error : result.errors()) {
                    response.append("• ").append(error).append("\n");
                }
            }

            return response.toString();

        } catch (Exception e) {
            logger.error("Error discovering JMX services", e);
            return "Error discovering JMX services: " + e.getMessage();
        }
    }

    @Tool(description = "Automatically register all discovered JMX connections")
    public String autoRegisterDiscoveredConnections() {
        try {
            int registered = jmxDiscoveryService.autoRegisterDiscoveredConnections();

            if (registered > 0) {
                return "Successfully auto-registered " + registered + " JMX connections. " +
                       "Use listJmxConnections() to see all available connections.";
            } else {
                return "No new JMX connections were registered. All discovered connections may already be registered, " +
                       "or no services were found. Use discoverJmxServices() to see what's available.";
            }

        } catch (Exception e) {
            logger.error("Error auto-registering JMX connections", e);
            return "Error auto-registering JMX connections: " + e.getMessage();
        }
    }

    @Tool(description = "Add a new JMX connection manually. type is LOCAL or REMOTE (a LOCAL entry with a url, e.g. a discovered local stub, is dialed as REMOTE). Prefer remote JNDI registry URLs such as service:jmx:rmi:///jndi/rmi://host:1098/jmxrmi - local stub URLs (service:jmx:rmi://.../stub/...) can fail with RemoteStub serialVersionUID mismatches when the JVMs differ. Note: remote JMX with authenticate=false is trusted-network-only.")
    public String addJmxConnection(String id, String name, String type, String url, String username, String password) {
        try {
            JmxConnectionProperties.ConnectionType connectionType = parseConnectionType(type);
            if (connectionType == null) {
                return "Invalid connection type: " + type + ". Must be LOCAL or REMOTE.";
            }
            if (connectionType == JmxConnectionProperties.ConnectionType.LOCAL
                    && (url == null || url.isBlank())) {
                return "LOCAL connections without a JMX service URL cannot be dialed. Use discoverJmxServices() "
                    + "to find local processes, or add a REMOTE connection with a JNDI registry URL like "
                    + "service:jmx:rmi:///jndi/rmi://host:1098/jmxrmi";
            }

            Map<String, String> properties = new HashMap<>();
            boolean success = connectionManager.addConnection(id, name, connectionType, url, username, password, properties);

            if (success) {
                return "Successfully added JMX connection: " + name + " (" + id + ")";
            } else {
                return "Failed to add JMX connection: " + id + ". Check the connection details.";
            }

        } catch (Exception e) {
            logger.error("Error adding JMX connection", e);
            return "Error adding JMX connection: " + e.getMessage();
        }
    }

    @Tool(description = "Remove a JMX connection by ID")
    public String removeJmxConnection(String connectionId) {
        try {
            boolean success = connectionManager.removeConnection(connectionId);

            if (success) {
                return "Successfully removed JMX connection: " + connectionId;
            } else {
                return "Connection not found: " + connectionId;
            }

        } catch (Exception e) {
            logger.error("Error removing JMX connection", e);
            return "Error removing JMX connection: " + e.getMessage();
        }
    }

    /**
     * Parses a connection type string to a ConnectionType enum.
     *
     * @param type the connection type string
     * @return the ConnectionType enum or null if invalid
     */
    private JmxConnectionProperties.ConnectionType parseConnectionType(String type) {
        try {
            return JmxConnectionProperties.ConnectionType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Object convertStringToType(String value, String typeName) {
        if (value == null) return null;

        return switch (typeName) {
            case "boolean", "java.lang.Boolean" -> Boolean.parseBoolean(value);
            case "byte", "java.lang.Byte" -> Byte.parseByte(value);
            case "short", "java.lang.Short" -> Short.parseShort(value);
            case "int", "java.lang.Integer" -> Integer.parseInt(value);
            case "long", "java.lang.Long" -> Long.parseLong(value);
            case "float", "java.lang.Float" -> Float.parseFloat(value);
            case "double", "java.lang.Double" -> Double.parseDouble(value);
            case "char", "java.lang.Character" -> !value.isEmpty() ? value.charAt(0) : '\0';
            default -> value; // String or complex type
        };
    }
}
