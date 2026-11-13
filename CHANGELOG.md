# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial open source release preparation
- GitHub Actions CI/CD workflows
- Comprehensive documentation
- Security policy and contributing guidelines
- `invokeOperation` MCP tool: invoke MBean operations with a JSON array of string arguments (e.g. `["2"]` for `click("2")`); resolves the matching overload from discovered MBean info, converts String/primitive/wrapper parameters, reports unsupported parameter types and available signatures on mismatch, unwraps `MBeanException` causes, rejects trailing JSON garbage, accepts `null`/exponent numbers, picks the first same-arity overload whose parameters are convertible, and on connection failure reconnects but does not retry (operations may not be idempotent)
- Connection robustness: bare `LOCAL` connections without a URL now fail with guidance to use `discoverJmxServices()` or a remote JNDI registry URL (`service:jmx:rmi:///jndi/rmi://host:1098/jmxrmi`); `LOCAL` entries carrying a URL are dialed as `REMOTE`; `getConnection()` re-establishes a stale active connection (serialized, rate-limited to one attempt per 5 s) before failing; re-dialing closes the previous connector; `switchJmxConnection` refreshes the MBean cache immediately after a successful switch
- Documentation: remote JNDI registry URLs documented as the reliable path (local stub URLs can fail with `RemoteStub` serialVersionUID mismatches across JVMs); `authenticate=false` remote JMX documented as trusted-network-only
- `getJvmOverview` MCP tool: one-call JVM summary (VM name/version/uptime, JVM arguments, heap and non-heap usage, per-collector GC counts and times, memory pool usage, thread and class counts, OS CPU load and physical memory) built on standard platform MBeans, so it works for local and remote connections; JVM arguments with credential-like names are masked; a section that a JVM does not support is reported as unavailable instead of failing the call
- `getThreadDump` MCP tool: compact thread dump (`at Class.method(File.java:123)` frames, waiting-on lock and its owner, held monitors and synchronizers) with deadlocked threads reported first; optional `maxFrames` (default 20, max 200), `threadNameFilter` and `state` parameters, capped at 100 threads; the parameters are advertised as optional in the tool schema

### Fixed
- `getAttribute`, `getJvmOverview` and `getThreadDump` re-dial a dead connection (for example after the target JVM restarted on the same address) and repeat the call once, instead of failing with `The client has been closed` until `switchJmxConnection` or a failed `invokeOperation` re-established it. Writes (`setAttribute`, `invokeOperation`) are still never retried
- `getJvmOverview` reports a dead connection once instead of repeating the same error in every section
- JVM argument masking in `getJvmOverview` no longer hides harmless values such as `-Dcom.sun.management.jmxremote.authenticate=false`

### Changed
- Replaced the Python script `comprehensive-test.py` with the JUnit test `McpToolsIntegrationTest`, which runs with `mvn test` (and therefore in CI): it checks that all 15 tools are registered, which parameters are required, and calls the tools end to end against a real JMX connection. The script only checked 6 tool names and was never run by the build
- Upgraded Spring Boot from 3.4.1 to 3.5.16 and Spring AI from 1.0.0 to 1.1.8
- Optional tool parameters (`listMBeans` domain, `invokeOperation` arguments, `addJmxConnection` username and password) are now advertised as optional in the tool schema; previously clients were required to supply them
- `getAttribute` and `invokeOperation` render arrays, collections, maps, `CompositeData` and `TabularData` as compact readable text (e.g. `[-Xmx512m, -Dfoo=bar]`, `{committed=..., used=...}`) instead of `toString()` with repeated type metadata or `[Ljava.lang.String;@...`; key/value tables render as maps, output is capped at 100 elements per array, collection or table
- `invokeOperation` reports a failed value conversion (e.g. `"abc"` for a `long` parameter) instead of an unrelated "unsupported parameter type" error from another overload

## [1.0.0] - 2025-06-21

### Added
- **Core Features**
  - Complete MCP (Model Context Protocol) server implementation
  - JMX connection management with auto-discovery
  - Comprehensive MBean exploration and management
  - Real-time monitoring capabilities
  - Security validation and access control

- **Connection Types**
  - Local JMX connections via Attach API
  - Remote JMX connections with authentication
  - Multi-connection support with connection registry
  - Automatic JMX service discovery

- **MCP Tools**
  - `discover_jmx_services` - Auto-discover JMX-enabled applications
  - `list_mbeans` - Browse available MBeans
  - `get_mbean_info` - Detailed MBean information
  - `get_attribute` - Read MBean attributes
  - `set_attribute` - Modify MBean attributes
  - `invoke_operation` - Execute MBean operations
  - `list_connections` - Manage multiple JMX connections
  - `add_connection` - Add new JMX connections
  - `switch_connection` - Switch between connections

- **MCP Resources**
  - `jmx://connections` - Connection registry overview
  - `jmx://mbeans/{domain}` - Domain-specific MBean listings
  - `jmx://mbean/{objectName}` - Individual MBean details

- **Configuration Profiles**
  - STDIO mode for Claude Desktop integration
  - Multi-connection mode for managing multiple applications
  - Remote monitoring mode for production environments
  - Docker deployment configuration

- **Security Features**
  - Input validation and sanitization
  - Configurable operation filtering
  - Security policy enforcement
  - Audit logging

- **Documentation**
  - Comprehensive README with setup instructions
  - Configuration examples and use cases
  - Troubleshooting guide
  - API documentation

### Technical Details
- **Framework**: Spring Boot 3.4.1
- **Java**: Requires Java 17+
- **MCP Protocol**: Version 2024-11-05
- **Package**: `org.jmxmcp.*` (open source ready)
- **Build**: Maven with comprehensive test suite
- **Testing**: 36 unit and integration tests

### Supported Platforms
- **Operating Systems**: Windows, macOS, Linux
- **Java Applications**: Any JMX-enabled Java application
- **AI Assistants**: Claude Desktop with MCP support

[1.0.0]: https://github.com/itz4blitz/jmx-mcp-server/releases/tag/v1.0.0
[Unreleased]: https://github.com/itz4blitz/jmx-mcp-server/compare/v1.0.0...HEAD
