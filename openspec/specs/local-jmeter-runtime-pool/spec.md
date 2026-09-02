## Purpose

Define the lifecycle, isolation, and caching contract for local JMeter runtimes used by the MCP server.

## Requirements

### Requirement: MCP reuses one isolated runtime per JMeter home
The MCP server SHALL maintain one isolated initialized worker JVM for each normalized real JMeter-home path used during the server session. Calls for the same home SHALL execute serially through that worker, and calls for different homes SHALL use isolated workers whose JMeter static state and plugin classes do not mix.

#### Scenario: Repeated calls reuse the initialized runtime
- **WHEN** two MCP tools resolve the same JMeter home during one server session
- **THEN** the second call SHALL reuse the existing worker, JMeter initialization, and runtime caches
- **AND** it SHALL NOT start another worker solely because it is a different tool call

#### Scenario: Different homes remain isolated
- **WHEN** MCP calls resolve two different normalized JMeter homes
- **THEN** each home SHALL use a different worker JVM
- **AND** components or plugin classes from one home SHALL NOT appear in the other home

#### Scenario: Same-home operations are serialized
- **WHEN** more than one operation targets the same initialized JMeter home
- **THEN** the worker SHALL execute those operations serially
- **AND** it SHALL NOT concurrently access that runtime's JMeter static state

### Requirement: MCP runtime caches are process-lived
An initialized worker SHALL remain available until MCP shutdown unless it crashes, corrupts the worker protocol, times out, or encounters an unrecoverable initialization failure. Ordinary JMeter load, placement, materialization, or validation failures SHALL NOT evict it.

#### Scenario: Domain failure keeps the worker warm
- **WHEN** a JMeter operation rejects an input while the worker protocol remains healthy
- **THEN** the MCP server SHALL return the operation failure
- **AND** a later call for the same home SHALL reuse the same worker

#### Scenario: Fatal worker failure rebuilds lazily
- **WHEN** a worker crashes, times out, returns an unusable protocol response, or fails initialization irrecoverably
- **THEN** the server SHALL fail the in-flight request without replaying it
- **AND** it SHALL terminate and evict the worker and remove any failed initialization future
- **AND** the next independent call for the same home SHALL initialize a replacement

#### Scenario: Shutdown releases all workers
- **WHEN** the MCP server shuts down or exits normally
- **THEN** it SHALL terminate all cached worker process trees and release owned temporary resources best-effort

### Requirement: Default runtime warm-up is single-flight
After responding to MCP initialization, the server SHALL begin background initialization of the JMeter home resolved from environment variables when one is configured. Initialization for one normalized home SHALL be single-flight, and an explicit first tool call SHALL join an in-progress initialization instead of starting another worker.

#### Scenario: Environment-selected home warms in background
- **WHEN** MCP initialization completes and the environment resolves a valid default JMeter home
- **THEN** the server SHALL begin worker, JMeter, SaveService, and menu-catalog initialization without delaying the completed MCP initialization response

#### Scenario: First tool joins warm-up
- **WHEN** a tool call targets a home whose background initialization is still running
- **THEN** the tool SHALL await the existing initialization result
- **AND** the server SHALL NOT perform duplicate initialization

### Requirement: Runtime metadata uses two-level caching
Each initialized worker SHALL build its reflected JMeter menu identity index once. It SHALL discover property descriptors and supported GUI semantic metadata lazily per component with single-flight and cache every completed immutable result, whether successful or failed, for the worker lifetime. Unsupported, ambiguous, inaccessible, failed, or budget-exceeded GUI semantic observation SHALL NOT be retried by elapsed time or an ordinary later request in the same worker. A new observation attempt is permitted only after that worker is evicted, crashes, or is replaced. The worker SHALL load mutable JMX plans fresh for every tool operation and SHALL NOT cache target-bound mutation authorization independently of the exact request target.

#### Scenario: Component list uses the warm identity index
- **WHEN** repeated components calls target the same JMeter home
- **THEN** they SHALL reuse the reflected menu identity index
- **AND** they SHALL NOT rescan the JMeter installation or rebuild the menu registry

#### Scenario: Component properties initialize once
- **WHEN** multiple calls request details for the same component identity
- **THEN** at most one property-descriptor and GUI semantic metadata initialization SHALL run
- **AND** later calls SHALL reuse its successful cached immutable result

#### Scenario: Failed semantic observation does not form a retry loop
- **WHEN** a component's GUI semantic observation is unsupported, ambiguous, or exceeds its budget
- **THEN** concurrent callers SHALL receive the same fail-soft metadata result
- **AND** every ordinary subsequent call in the same worker lifetime SHALL reuse that failure without another observation attempt

#### Scenario: Worker eviction removes semantic metadata
- **WHEN** a worker is evicted, crashes, or is replaced
- **THEN** all GUI semantic metadata and negative observation entries owned by that worker SHALL be discarded
- **AND** the replacement worker MAY perform one new lazy observation when the component is next required

#### Scenario: JMX files are never served from a mutable model cache
- **WHEN** a JMX file changes between two tool calls
- **THEN** the later call SHALL load the current file content
- **AND** it SHALL NOT reuse a mutable `JmxTestPlan` from the earlier call

### Requirement: JMeter installation changes require MCP restart
The MCP session SHALL treat each initialized JMeter home, its properties, jars, plugins, and configured classpaths as immutable for the session. Repeated calls SHALL NOT rescan the installation or recompute full classpath fingerprints.

#### Scenario: Hot path avoids installation fingerprinting
- **WHEN** a tool uses an already initialized JMeter home
- **THEN** dispatch SHALL NOT hash or recursively scan that home's jars and configuration before reusing the worker

#### Scenario: Runtime changes are activated by restart
- **WHEN** a user changes plugins, jars, or JMeter properties for a cached home
- **THEN** documentation and diagnostics SHALL direct the user to restart MCP before expecting the change to take effect

### Requirement: Runtime keys use validated canonical homes
Before creating or looking up a pool entry, the server SHALL resolve and validate the selected JMeter home, then use its real filesystem path as the key. The MCP server SHALL capture environment-based defaults once at server startup, while an explicit per-call `jmeter_home` SHALL override that captured default for that call.

#### Scenario: Windows path aliases share one runtime
- **WHEN** two Windows paths differ only by case, normalized segments, symlink, or junction but resolve to the same real JMeter home
- **THEN** they SHALL select one pool key and one worker

#### Scenario: Invalid home creates no pool entry
- **WHEN** a selected path is missing, is not a valid JMeter home, or cannot be resolved to a real path
- **THEN** the call SHALL fail with local-runtime infrastructure guidance
- **AND** the pool SHALL NOT retain a worker or initialization future for that path

#### Scenario: Environment is stable for one MCP session
- **WHEN** the operating environment changes after the MCP process starts
- **THEN** implicit home resolution SHALL continue using the startup snapshot
- **AND** a caller MAY select another valid home through explicit `jmeter_home`
