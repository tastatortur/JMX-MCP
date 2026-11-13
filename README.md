# JMX MCP Server

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MCP](https://img.shields.io/badge/MCP-2024--11--05-blue.svg)](https://modelcontextprotocol.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

A powerful **Model Context Protocol (MCP) server** that provides comprehensive JMX monitoring and management capabilities for AI assistants like Claude Desktop. Monitor Java applications, manage MBeans, and perform JMX operations through natural language interactions.

## 🎥 Demo Video

Watch the JMX MCP Server in action! See how Claude Desktop can monitor and manage Java applications through natural language:

https://github.com/user-attachments/assets/722e1885-5aeb-4584-8116-b93324e0abc1

*The demo shows real-time JMX monitoring, MBean exploration, and AI-powered Java application management through Claude Desktop.*

## 🚀 Features

### 🔍 **Comprehensive JMX Integration**
- **Real-time MBean Discovery**: Automatically discovers and catalogs all available MBeans
- **Attribute Management**: Read and write MBean attributes with full type safety
- **Operation Execution**: Execute MBean operations with parameter validation
- **Domain Exploration**: Browse and filter MBeans by domain

### 🤖 **AI-Powered Monitoring**
- **Natural Language Queries**: Ask questions like "What's the current heap memory usage?"
- **Intelligent Analysis**: AI can correlate metrics and identify performance issues
- **Automated Insights**: Get recommendations based on JMX data patterns

### 🛡️ **Enterprise-Ready**
- **Security Validation**: Built-in security controls and access validation
- **Connection Management**: Robust local and remote JMX connection handling
- **Error Handling**: Comprehensive error handling and recovery mechanisms
- **Production Logging**: Configurable logging for different environments

### 🔌 **MCP Protocol Compliance**
- **Tools**: 15 JMX management tools for AI interaction
- **Resources**: All JMX attributes exposed as discoverable resources
- **STDIO Transport**: Optimized for Claude Desktop integration
- **JSON-RPC 2.0**: Full protocol compliance for reliable communication

## 📋 Prerequisites

- **Java 17+** (OpenJDK or Oracle JDK)
- **Maven 3.6+** for building
- **Claude Desktop** or any MCP-compatible AI client

## 🛠️ Quick Start

### 1. Clone and Build

```bash
git clone https://github.com/itz4blitz/JMX-MCP.git
cd JMX-MCP
mvn clean package
```

### 2. Test the Server

```bash
# Unit tests plus the end-to-end test of all MCP tools
mvn test
```

### 3. Configure Claude Desktop

Add to your Claude Desktop MCP configuration file:

**Location:**
- **macOS**: `~/.config/claude/mcp_servers.json`
- **Windows**: `%APPDATA%\Claude\mcp_servers.json`

**Configuration:**
```json
{
  "mcpServers": {
    "jmx-mcp-server": {
      "command": "java",
      "args": [
        "-Xmx512m",
        "-Xms256m",
        "-Dspring.profiles.active=stdio",
        "-Dspring.main.banner-mode=off",
        "-Dlogging.level.root=OFF",
        "-Dspring.main.log-startup-info=false",
        "-jar",
        "/path/to/your/jmx-mcp-server-1.0.0.jar"
      ],
      "env": {
        "JAVA_OPTS": "-Djava.awt.headless=true"
      }
    }
  }
}
```

### 4. Start Using with Claude

Restart Claude Desktop and try these queries:

```
"What JMX tools are available?"
"Show me the current heap memory usage"
"List all MBean domains"
"What's the garbage collection performance?"
```

## 🔧 Available Tools (15 Total)

### Core JMX Operations
| Tool | Description | Example Usage |
|------|-------------|---------------|
| `listMBeans` | List all discovered MBeans with optional domain filtering | "Show me all memory-related MBeans" |
| `getMBeanInfo` | Get detailed information about a specific MBean | "Tell me about the Runtime MBean" |
| `getAttribute` | Read the value of an MBean attribute | "What's the current heap memory usage?" |
| `setAttribute` | Set the value of a writable MBean attribute | "Set the log level to DEBUG" |
| `invokeOperation` | Invoke an MBean operation; `arguments` is a JSON array of string values, e.g. `["2"]` for `click("2")` | "Click the 'Host game' button on the UI management MBean" |
| `listDomains` | List all available MBean domains | "What domains are available?" |

### JVM Diagnostics
| Tool | Description | Example Usage |
|------|-------------|---------------|
| `getJvmOverview` | One-call summary: VM, uptime, JVM arguments, heap/non-heap, GC counts and times, memory pools, threads, classes, CPU and physical memory | "Give me an overview of this JVM" |
| `getThreadDump` | Formatted thread dump with deadlock detection; optional `maxFrames`, `threadNameFilter`, `state` | "Is anything deadlocked? Show the blocked threads" |

### Connection Management
| Tool | Description | Example Usage |
|------|-------------|---------------|
| `listJmxConnections` | List all configured JMX connections | "Show me all available connections" |
| `addJmxConnection` | Add a new JMX connection | "Connect to the production server" |
| `removeJmxConnection` | Remove a JMX connection | "Remove the old test connection" |
| `switchJmxConnection` | Switch to a different JMX connection | "Switch to the staging environment" |
| `getConnectionInfo` | Get current JMX connection status and statistics | "Is the JMX connection healthy?" |

### Service Discovery
| Tool | Description | Example Usage |
|------|-------------|---------------|
| `discoverJmxServices` | Discover JMX-enabled Java applications on the system | "Find all Java apps with JMX enabled" |
| `autoRegisterDiscoveredConnections` | Automatically register discovered JMX services | "Auto-connect to all discovered Java apps" |

## 📊 Available Resources

The server automatically exposes **all JMX attributes** as MCP resources, making them discoverable and accessible to AI assistants:

- **Memory Metrics**: Heap usage, non-heap usage, memory pools
- **Garbage Collection**: GC statistics, collection times, collection counts
- **Threading**: Thread counts, deadlock detection, thread states
- **Runtime Information**: JVM uptime, start time, system properties
- **Class Loading**: Loaded classes, unloaded classes, class path
- **Operating System**: CPU usage, system load, available processors

**Resource URI Format:**
```
jmx://domain:type=Type,name=Name/attributes/AttributeName
```

**Example Resources:**
```
jmx://java.lang:type=Memory/attributes/HeapMemoryUsage
jmx://java.lang:type=Runtime/attributes/Uptime
jmx://java.lang:type=GarbageCollector,name=G1_Young_Generation/attributes/CollectionCount
```

## 💬 Example Interactions

### Memory Monitoring
```
User: "What's the current memory situation?"
Claude: I'll check the JMX memory metrics for you.

[Uses getAttribute tool to read HeapMemoryUsage]

The current heap memory usage is:
- Used: 245 MB
- Committed: 512 MB  
- Max: 2048 MB
- Usage: 12% of maximum heap

The application is running efficiently with low memory pressure.
```

### Performance Analysis
```
User: "Are there any performance issues?"
Claude: Let me analyze the JMX performance metrics.

[Uses multiple tools to check GC, threads, and memory]

Performance analysis:
✅ Memory: Healthy (12% heap usage)
✅ GC: Efficient (avg 15ms collection time)
⚠️  Threads: High count (245 threads) - consider investigation
✅ CPU: Normal load

Recommendation: Monitor thread creation patterns.
```

## 🏗️ Architecture

```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Claude AI     │◄──►│  JMX MCP Server  │◄──►│  Java App JMX   │
│                 │    │                  │    │                 │
│ Natural Language│    │ • Tools (15)     │    │ • MBeans        │
│ Queries         │    │ • Resources(224+)│    │ • Attributes    │
│                 │    │ • JSON-RPC 2.0   │    │ • Operations    │
│                 │    │ • Multi-Connect  │    │ • Discovery     │
└─────────────────┘    └──────────────────┘    └─────────────────┘
```

### Core Components

- **JMXConnectionManager**: Manages local and remote JMX connections
- **MBeanDiscoveryService**: Discovers and catalogs available MBeans
- **JmxService**: Provides @Tool-annotated methods for AI interaction
- **JMXToMCPMapper**: Maps JMX attributes to MCP resources
- **JmxSecurityValidator**: Validates operations for security compliance

## ⚙️ Configuration Profiles

### Default Profile
Standard configuration with full logging for development and debugging.

### STDIO Profile
Optimized for Claude Desktop integration:
- **Silent Operation**: No console output to avoid JSON-RPC interference
- **Minimal Logging**: Error-only logging to prevent file system issues
- **Fast Startup**: Optimized initialization for quick AI responses

## 🧪 Testing

### Running the Tests
```bash
mvn test
```

**End-to-end test** (`McpToolsIntegrationTest`): registers the tools like the running server and calls them with MCP-style JSON arguments against a real JMX connection to the test JVM.
- ✅ All 15 tools are registered
- ✅ Tool schemas mark only the mandatory parameters as required
- ✅ Connection management, MBean access, `invokeOperation`, `getJvmOverview` and `getThreadDump` work end to end
- ✅ Errors come back as text instead of exceptions

**Unit tests** cover the formatter, the security validator, connection handling (including re-dialing a restarted target) and each tool.

## 🔒 Security

### Built-in Security Features
- **ObjectName Validation**: Prevents access to sensitive MBeans
- **Operation Filtering**: Restricts dangerous operations
- **Type Safety**: Validates attribute types before operations
- **Access Control**: Configurable security policies

### Security Configuration
```yaml
jmx:
  security:
    enabled: true
    allowed-domains:
      - "java.lang"
      - "java.nio"
      - "com.myapp"
    blocked-operations:
      - "shutdown"
      - "restart"
```

## 🚀 Deployment

### Local Development
```bash
java -jar target/jmx-mcp-server-1.0.0.jar
```

### Production Deployment
```bash
java -Xmx1g -Xms512m \
     -Dspring.profiles.active=production \
     -jar jmx-mcp-server-1.0.0.jar
```

### Docker Deployment
```dockerfile
FROM openjdk:17-jre-slim
COPY target/jmx-mcp-server-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
```

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Development Setup
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Ensure all tests pass
6. Submit a pull request

### Code Style
- Follow Java coding conventions
- Use meaningful variable and method names
- Add comprehensive JavaDoc comments
- Maintain test coverage above 80%

## 📚 Documentation

- **[API Documentation](docs/API.md)**: Detailed API reference
- **[Configuration Guide](docs/CONFIGURATION.md)**: Advanced configuration options
- **[Troubleshooting](docs/TROUBLESHOOTING.md)**: Common issues and solutions
- **[Examples](examples/)**: Usage examples and tutorials

## 🐛 Troubleshooting

### Common Issues

**Server won't start with Claude Desktop:**
- Verify Java 17+ is installed
- Check the JAR path in configuration
- Ensure STDIO profile is active

**No tools/resources visible:**
- Restart Claude Desktop after configuration changes
- Check server logs for errors
- Verify MCP protocol compliance

**Connection issues:**
- Confirm JMX is enabled on target application
- Check network connectivity for remote connections
- Validate security settings

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](CONTRIBUTING.md) for details.

### Quick Start for Contributors

```bash
# Fork the repository on GitHub
git clone https://github.com/YOUR_USERNAME/JMX-MCP.git
cd JMX-MCP

# Build and test
mvn clean compile
mvn test

# Run the application
mvn spring-boot:run
```

### Ways to Contribute

- 🐛 **Report bugs** - Help us identify and fix issues
- 💡 **Suggest features** - Share ideas for new functionality
- 📝 **Improve documentation** - Help others understand the project
- 🔧 **Submit code** - Fix bugs or implement new features
- 🧪 **Write tests** - Improve test coverage and reliability
- 🎨 **UI/UX improvements** - Enhance user experience

### Community

- **GitHub Discussions**: Ask questions and share ideas
- **Issues**: Report bugs and request features
- **Pull Requests**: Contribute code improvements
- **Wiki**: Collaborative documentation

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- **Spring AI Team** for the excellent MCP framework
- **Model Context Protocol** for the standardized AI integration protocol
- **Anthropic** for Claude Desktop and AI assistant capabilities
- **OpenJDK Community** for the robust Java platform

## 📞 Support

- **Issues**: [GitHub Issues](https://github.com/itz4blitz/JMX-MCP/issues)
- **Discussions**: [GitHub Discussions](https://github.com/itz4blitz/JMX-MCP/discussions)
- **Documentation**: [Wiki](https://github.com/itz4blitz/JMX-MCP/wiki)

---

**Made with ❤️ for the AI and Java communities**
