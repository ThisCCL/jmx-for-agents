# jmeter-gui-semantic-metadata Specification

## Purpose

Define bounded JMeter 5.6.3 runtime evidence for persisted GUI-bound properties and structured table consumer types that are otherwise unobservable when a newly materialized component is empty.

## Requirements

### Requirement: GUI semantic metadata is version-pinned and worker-local
The system SHALL query GUI semantic metadata only inside the isolated worker that owns the selected JMeter runtime and only when that runtime is verified as JMeter 5.6.3. It SHALL NOT start the JMeter desktop application, create or display top-level windows, or expose GUI objects, reflection handles, or JMeter implementation instances across the worker protocol.

#### Scenario: Supported runtime supplies worker-local evidence
- **WHEN** component details require metadata from a selected JMeter 5.6.3 worker
- **THEN** the worker SHALL perform semantic observation with classes loaded from that runtime
- **AND** it SHALL return only immutable protocol metadata

#### Scenario: Unsupported runtime does not guess
- **WHEN** the selected runtime cannot be verified as JMeter 5.6.3
- **THEN** GUI semantic metadata SHALL be unavailable
- **AND** the system SHALL retain its existing runtime sources without guessing or applying 5.6.3 reflection assumptions

#### Scenario: Metadata discovery opens no window
- **WHEN** GUI semantic metadata is queried
- **THEN** no JMeter desktop application or top-level GUI window SHALL be started or displayed

### Requirement: GUI-bound scalar descriptors supplement empty-state metadata
Successfully observed GUI property descriptors SHALL be eligible runtime evidence for persisted scalar property names, types, and defaults when the exact newly materialized element omits those properties because their controls are empty. Descriptor evidence SHALL supplement rather than replace present-property, schema, BeanInfo, or existing XML evidence and SHALL NOT imply complete enumeration of every GUI field.

#### Scenario: Empty HTTP defaults expose Domain and Port
- **WHEN** JMeter 5.6.3 materializes HTTP Request Defaults with empty Domain and Port controls
- **THEN** component authoring metadata SHALL expose `HTTPSampler.domain` and `HTTPSampler.port` from the observed runtime descriptors
- **AND** no HTTP component or property allowlist SHALL authorize them

#### Scenario: Existing value remains authoritative
- **WHEN** an exact loaded target already contains a persisted property also found through a GUI descriptor
- **THEN** the target's concrete persisted property shape and value SHALL remain authoritative

### Requirement: Table consumers are correlated to JMX properties by differential evidence
For an empty structured property whose exact consumer row type is not otherwise observable, the system SHALL accept GUI table-consumer evidence only after an isolated probe produces a uniquely attributable change in the outer component's materialized JMeter property graph. The probe SHALL use a worker-generated typed sentinel and SHALL fail closed when the consumer, property path, concrete row class, or reconstruction relationship is ambiguous.

#### Scenario: HTTP argument table proves its exact row class
- **WHEN** an isolated JMeter 5.6.3 HTTP argument-table probe inserts a worker-generated sentinel row
- **THEN** the resulting outer component SHALL uniquely associate `HTTPsampler.Arguments` with `org.apache.jmeter.protocol.http.util.HTTPArgument`
- **AND** the base class `org.apache.jmeter.config.Argument` SHALL NOT replace that exact consumer type

#### Scenario: Multiple tables are probed independently
- **WHEN** one component contains multiple table consumers
- **THEN** each consumer SHALL be probed independently against a fresh temporary component instance
- **AND** the system SHALL NOT associate a property by table order, field name, GUI class name, or component identity alone

#### Scenario: Ambiguous differential result is unavailable
- **WHEN** a probe changes multiple candidate structured properties, produces no observable sentinel, or cannot preserve the concrete row class
- **THEN** the candidate mapping SHALL be reported as unavailable
- **AND** it SHALL NOT authorize a structured write

### Requirement: Semantic observation is bounded and outside normal hot paths
GUI semantic observation SHALL be lazy, single-flight per worker and component identity, bounded by explicit traversal, candidate, result, and elapsed-time budgets, and cached only as an immutable completed result for the worker lifetime. Ordinary component lists, repeated component details, reads, and writes SHALL NOT repeat a successful or failed observation in that worker. A budget breach SHALL fail the metadata source softly and SHALL NOT evict a healthy worker or mutate a user JMX file. Retry SHALL require worker eviction, crash, or replacement rather than elapsed time.

#### Scenario: Repeated details reuse one observation
- **WHEN** callers repeatedly request details for the same component in one worker lifetime
- **THEN** at most one GUI semantic observation attempt SHALL run
- **AND** later calls SHALL reuse its bounded immutable success or failure result

#### Scenario: Failed observation retries only in a replacement worker
- **WHEN** a component observation fails and later requests target the same live worker
- **THEN** those requests SHALL reuse the cached failure without another observation attempt
- **AND** a new lazy attempt MAY occur only after worker eviction, crash, or replacement

#### Scenario: Ordinary writes do not launch discovery
- **WHEN** a write can be validated from existing target evidence or cached exact-runtime semantic evidence
- **THEN** the write SHALL NOT perform a new GUI graph traversal or differential probe

#### Scenario: Observation exceeds its budget
- **WHEN** field traversal, candidate count, result count, or elapsed time exceeds its configured bound
- **THEN** observation SHALL stop and report runtime metadata unavailable for that source
- **AND** the worker and user-owned JMX bytes SHALL remain unchanged
