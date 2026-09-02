# generic-jmeter-property-graph Specification

## Purpose
TBD - created by archiving change generic-jmeter-property-graph-editing. Update Purpose after archive.
## Requirements

### Requirement: Runtime property graph covers every discoverable persisted user property
For the selected local JMeter runtime, the system SHALL build one recursive property graph from properties present on the exact materialized TestElement plus successfully queried JMeter schema descriptors, TestBean BeanInfo, and version-compatible GUI semantic property descriptors for that runtime object. Registered adapters, component/property identity tables, and previously observed unrelated targets SHALL NOT contribute graph membership or authorize a representation. Every discovered persisted property SHALL have an address, concrete JMeter property class, protocol `type`, storage-key status, ownership, representation source, and write capability. Every persisted property SHALL be classified as user-editable or system-owned. GUI semantic evidence SHALL supplement absent-property discovery only when it names a persisted JMeter property descriptor and SHALL NOT constitute a claim that every GUI field was enumerated.

#### Scenario: Response Assertion string collection is graph-representable
- **WHEN** the selected JMeter 5.6.3 runtime materializes `org.apache.jmeter.assertions.gui.AssertionGui`
- **THEN** `Asserion.test_strings` SHALL enter the graph from that target and its runtime metadata
- **AND** no Response Assertion-specific rule SHALL be consulted

#### Scenario: Plugin property is not excluded by an unknown component identity
- **WHEN** a locally installed menu component exposes a persisted property using a graph-representable JMeter property family
- **THEN** the property SHALL enter the graph from exact-target runtime observation or metadata
- **AND** absence of J4A component-specific knowledge SHALL NOT make it unsupported

#### Scenario: GUI-bound absent scalar enters the graph from a descriptor
- **WHEN** a persisted scalar is absent from a newly materialized target but the supported runtime's GUI semantic source exposes its JMeter property descriptor
- **THEN** the property SHALL enter the graph with its descriptor-derived name, type, default, and representation source
- **AND** component identity or visible GUI text SHALL NOT be used as authority

#### Scenario: Undiscoverable absent property is not invented
- **WHEN** a property is absent from the materialized element and all successfully queried runtime schema, BeanInfo, and supported GUI semantic sources
- **THEN** the graph SHALL NOT invent that property name or accept it as a direct write address

#### Scenario: JMeter identity properties are system-owned
- **WHEN** discovery observes `TestElement.gui_class` or `TestElement.test_class` on a top-level or nested TestElement
- **THEN** the graph SHALL retain the value for materialization and reload verification
- **AND** it SHALL classify the node as `ownership: system` and `writable: false`
- **AND** ordinary property read and mutation surfaces SHALL omit or reject it rather than count it as a missing user-editable capability

### Requirement: Property graph uses a complete recursive transport algebra
The property graph SHALL represent scalar JMeter properties, ordered collections, maps, nested TestElements, null or absent state, opaque persisted values, and runtime-proven ordered rows. A reusable property record SHALL contain exactly `property`, `type`, and `value`, where `property` is the scalar-segment array defined by this capability. Runtime-proven ordered rows SHALL use the single protocol type `rows`; named family-specific collection types SHALL NOT be accepted. A generic recursive `value` SHALL contain `presence: present|absent` and `property_class`; collection values SHALL add ordered `items`; map values SHALL add ordered `entries` whose records contain `key` and recursive `value`; element values SHALL add `element_class` and recursive `properties`. Recursive nodes SHALL retain concrete runtime property/value classes where required for reconstruction. `presence: absent` SHALL carry no container contents, while an empty present container SHALL carry an empty list. A persisted value MUST NOT become non-writable solely because its collection or nested shape lacks a row projection.

#### Scenario: Ordered collection preserves order duplicates and emptiness
- **WHEN** an agent writes an ordered collection containing duplicate strings or an empty list
- **THEN** SaveService reload and subsequent read SHALL preserve order, duplicates, and empty-list state

#### Scenario: Nested property graph remains recursive
- **WHEN** a collection or map contains nested TestElements or JMeter properties
- **THEN** read SHALL emit recursively addressed child nodes using scalar-segment arrays
- **AND** apply or set SHALL reconstruct their concrete required runtime classes

#### Scenario: Opaque persisted value has an editable bounded fallback
- **WHEN** a persisted property cannot be safely decomposed into scalar, collection, map, nested-element, or runtime-proven `rows` nodes
- **THEN** focused read SHALL expose a `jmeter-save-element-xml-v1` replace-whole document with `type`, `format`, `base_digest`, `outer_property_class`, `runtime_fingerprint`, and caller-editable `payload` produced by the selected worker when SaveService can preserve it
- **AND** ordinary patching SHALL NOT instantiate a caller-selected arbitrary Java class

#### Scenario: Opaque text content changes without class expansion
- **GIVEN** read emitted an opaque document for the current value and the caller changes only text data inside its `payload`
- **WHEN** set or apply submits that document with the original `base_digest`
- **THEN** the selected worker SHALL require the current digest, root property name, outer property class, and observed recursive class allowset to remain valid
- **AND** after candidate SaveService reload, read SHALL emit the changed content and a newly server-computed digest

#### Scenario: Stale or class-expanding opaque replacement is rejected
- **WHEN** `base_digest` no longer matches the current property, the payload changes the root property name or outer class, or deserialization would introduce a concrete class outside the observed and approved JMeter property-class skeleton
- **THEN** the worker SHALL reject the write before attaching the replacement
- **AND** the caller SHALL NOT be able to choose an arbitrary Java class or replacement digest

### Requirement: Read output is a reusable mutation document
Every writable property value emitted by `read` or component details SHALL be accepted unchanged by `set` and `apply` for the same runtime, component identity, scalar-segment property address, and representation version. Read and write SHALL preserve absent-versus-empty state and MUST NOT require the agent to infer Java implementation classes that were not emitted.

#### Scenario: Structured value is copied from read to apply
- **WHEN** read emits a recursive collection, map, or nested-element value
- **THEN** the same address array and value SHALL be valid in an apply set operation for that property
- **AND** the reloaded read value SHALL be structurally equivalent

#### Scenario: Opaque value is copied without semantic interpretation
- **WHEN** read emits a writable opaque property value
- **THEN** submitting that value unchanged SHALL preserve the property's SaveService projection

#### Scenario: Array-addressed read document is a complete write document
- **WHEN** read emits a writable recursive property
- **THEN** the document SHALL include the scalar-segment property address, protocol `type`, presence, value, and any required runtime-class or opaque binding metadata
- **AND** set and apply SHALL accept that document without requiring the caller to infer an omitted Java class or addressing rule

### Requirement: Property mutations resolve against the observed graph before mutation
Set, apply set, incremental row operations, and add property overlays SHALL resolve every submitted scalar-segment property address against the graph of the exact loaded target or newly materialized candidate before attaching a property replacement. Add SHALL NOT require a catalog-time property descriptor. Direct top-level addresses and nested addresses SHALL use the same context-aware resolution rule. Mutation SHALL occur only on an isolated in-memory candidate; any later failure SHALL discard the candidate before output replacement.

#### Scenario: Add validation follows materialization
- **WHEN** an add operation supplies a structured property that is absent from catalog metadata but present on the materialized candidate
- **THEN** the operation SHALL validate its array address against the candidate and continue
- **AND** it SHALL NOT fail because a pre-materialization descriptor is missing

#### Scenario: Unknown top-level property is rejected
- **WHEN** a patch submits a top-level string segment absent from the target graph
- **THEN** the operation SHALL fail with an actionable property-address error
- **AND** it SHALL NOT add the unknown JMeter property or create an output file

#### Scenario: Unknown nested property is rejected identically
- **WHEN** a patch submits an array whose nested segment is absent or invalid for the current graph node
- **THEN** it SHALL fail in the same error category and before mutation

### Requirement: SaveService round-trip verifies the complete written graph projection
After mutation, the system SHALL save and reload the candidate through the selected local JMeter SaveService and compare every explicitly written graph node at its final structural address. Verification SHALL compare node kind, concrete required class, absence or presence, scalar value, collection order, map keys, nested values, and opaque preservation token as applicable. GUI openability MUST NOT independently accept or reject the candidate.

#### Scenario: Complete graph projection survives reload
- **WHEN** one patch writes scalar, collection, map, and nested values
- **THEN** commit SHALL occur only when all explicitly written projections match after reload

#### Scenario: One nested mismatch rejects the whole transaction
- **WHEN** any explicitly written nested node changes class, order, presence, or value during SaveService round-trip
- **THEN** the complete operation SHALL fail atomically and preserve target bytes

### Requirement: Capability metadata distinguishes structured opaque system-owned and transient values
Property metadata SHALL expose `type`, `writable`, `reason`, `ownership`, `representation_source`, and required runtime class information. A persisted user-editable property SHALL be `writable: true` through a scalar, generic recursive, universal `rows`, or opaque representation. `writable: false` SHALL identify system-owned identity metadata or transient/runtime-only/non-persisted state. If runtime discovery encounters a persisted user-editable value that cannot be safely reconstructed or replaced opaquely, metadata SHALL report the capability defect truthfully and the runtime conformance gate SHALL fail. The system MUST NOT suggest using `unsupported` as a patch value type or imply that component/property registration controls legality.

#### Scenario: Generic collection reports baseline capability
- **WHEN** a persisted collection is generically representable without a structured row projection
- **THEN** metadata SHALL report its recursive collection representation and `writable: true`

#### Scenario: Runtime-only reference reports an honest boundary
- **WHEN** discovery encounters an internal manager reference or transient runtime object that is not a persisted editable property
- **THEN** metadata SHALL report `writable: false` with a reason and source
- **AND** write guidance SHALL direct the agent to omit it rather than submit an impossible type

#### Scenario: System-owned identity reports a distinct boundary
- **WHEN** a diagnostic surface includes `TestElement.gui_class` or `TestElement.test_class`
- **THEN** metadata SHALL report `ownership: system`, `writable: false`, and an identity-management reason

#### Scenario: Persisted user-editable representation gap fails the gate
- **WHEN** a persisted user-editable property has neither a scalar, generic recursive, runtime-proven `rows`, nor verified opaque representation
- **THEN** metadata SHALL expose the non-writable defect and actionable reason
- **AND** runtime conformance SHALL fail instead of classifying the property as an accepted unsupported boundary

### Requirement: Runtime conformance proves complete reproducible property-family coverage
The implementation SHALL commit a generated baseline at `src/test/resources/property-graph-conformance/jmeter-5.6.3-inventory.yml`. The baseline SHALL bind a selected runtime fingerprint consisting of canonical JMeter home, `jmeter.version`, and sorted SHA-256 values for `lib/*.jar` and `lib/ext/*.jar`; a sorted list of every concrete `JMeterProperty` subclass discovered on that selected worker classpath; every exact `MenuFactory` registry entry and its attempted materialization success or failure; and every sorted fixture under `src/test/resources/property-graph-conformance/**/*.jmx` plus the synthetic plugin fixture with its SHA-256.

For those exact inputs, the denominator SHALL include all discovered concrete property subclasses and all persisted property/value shapes observed from materialized components and populated fixtures. Every row SHALL have one reviewed classification: structured scalar, structured collection, structured map, structured element, opaque, system-owned, transient/runtime-only, or `materialization_failed`. Every persisted user-editable row SHALL have a demonstrated structured or opaque round-trip. An unclassified row, a newly introduced or removed registry/materialization result, a fixture hash drift, or an input fingerprint mismatch SHALL fail conformance with expected and actual evidence instead of silently shrinking the denominator. A source-faithful `materialization_failed` row remains recorded separately from codec coverage, but any change to that row requires an explicit baseline review.

#### Scenario: Built-in runtime inventory has no unclassified persisted family
- **WHEN** conformance scans the supported JMeter 5.6.3 property classes, component catalog, and populated fixtures
- **THEN** every persisted user-facing property SHALL have a structured or opaque round-trip representation

#### Scenario: Runtime fingerprint mismatch cannot reuse a stale baseline
- **WHEN** the selected JMeter home, version, or sorted library hashes differ from the committed baseline
- **THEN** conformance SHALL fail with the expected and actual fingerprint
- **AND** it SHALL NOT report completeness against the stale denominator

#### Scenario: Component materialization failure remains visible
- **WHEN** one exact MenuFactory registry entry cannot be materialized in the selected worker
- **THEN** the baseline SHALL record that entry and failure as `materialization_failed`
- **AND** later scans SHALL NOT omit it or change its status without a reviewed baseline update

#### Scenario: New plugin property family is visible
- **WHEN** a fixture plugin introduces a previously unseen JMeterProperty subclass
- **THEN** conformance SHALL require an explicit generic-codec classification or opaque boundary
- **AND** the public metadata SHALL state the resulting capability

### Requirement: Generic collections are recursive envelopes and semantic collections remain row lists
Generic `type: collection` values SHALL remain distinct from universal `type: rows`. A generic collection value SHALL require the recursive envelope `presence`, `property_class`, and recursively typed `items`; the system MUST NOT accept a bare list for a generic collection, infer arbitrary Java property or value classes, or silently reinterpret it as rows. A runtime-proven row collection SHALL accept the same `type: rows` contract in add, set, apply set, append, insert, and remove without consulting a registered adapter or named family mapper.

#### Scenario: Bare generic list fails closed
- **WHEN** a set or apply request supplies a bare list for a property represented as generic `collection`
- **THEN** validation SHALL fail before mutation and name the required `presence`, `property_class`, and `items` envelope fields
- **AND** recovery SHALL direct the caller to copy an exact value from focused read or an applicable `value_template`

#### Scenario: Runtime-proven structured collection remains a row list
- **WHEN** the exact target proves a row projection
- **THEN** its universal `type: rows` value shape SHALL be accepted by add, set, apply set, append, insert, and remove as applicable
- **AND** neither the generic collection envelope nor a named adapter SHALL replace that proven contract

### Requirement: Proven empty generic collections publish one copyable value template
Component details SHALL publish optional `value_template` only when the exact materialized property and its selected-runtime metadata prove the outer property class, item protocol type, item property class, and reconstruction operation without a component/property identity table. The template SHALL be derived from those facts and SHALL be accepted by add, set, and apply for an equivalent exact target. When an empty collection does not prove its item type and reconstruction, details SHALL omit `value_template` and SHALL direct callers to use focused read of a populated target or another available generic/opaque representation.

#### Scenario: Empty collection with runtime-proven string items publishes a template
- **WHEN** the exact empty collection's runtime class and metadata unambiguously prove `StringProperty` items and their reconstruction operation
- **THEN** details SHALL publish a copyable generic collection template containing that observed outer and item class
- **AND** changing only the placeholder string SHALL produce a valid candidate value

#### Scenario: Component identity alone cannot publish a template
- **WHEN** an empty collection's item type is not proven by the exact target/runtime evidence
- **THEN** details SHALL omit `value_template` even if the component FQCN and property name were previously known to J4A
- **AND** the implementation SHALL NOT consult a fixed component/property template table

#### Scenario: Response Assertion template becomes a valid value by replacement
- **WHEN** ordinary or diagnostic component details are requested for the mandatory Response Assertion identity
- **THEN** the response SHALL include the exact `value_template`
- **AND** replacing only `<replace-with-string>` SHALL yield a generic collection value accepted by set and apply for that property

#### Scenario: Unknown or mismatched generic collection has no template
- **WHEN** an empty generic collection has no matching proven identity or its observed class/item proof differs from the mandatory template
- **THEN** details SHALL omit `value_template`
- **AND** the caller SHALL not be invited to guess a Java class

### Requirement: Graph mutation remains bounded, copy-safe, and representation-preserving
Collection parsing and rendering SHALL preserve generic recursive envelopes, selected-runtime isolation, isolated-candidate mutation, SaveService verification before commit, and write safety. The mutation transaction MAY be replaced as one internal deep module to enforce those invariants, but SHALL not add an external validation or schema dependency and SHALL not mutate raw JMX XML or original test fixtures.

#### Scenario: Invalid generic collection leaves source and target unchanged
- **WHEN** generic collection validation rejects an invalid envelope at any point in the isolated candidate transaction
- **THEN** no mutation SHALL be committed or replayed
- **AND** the input/source bytes and any existing target bytes SHALL remain unchanged

### Requirement: Scalar writes match the exact observed protocol type
Every scalar write SHALL match the exact protocol type observed for the addressed target node. `FloatProperty` SHALL discover, render, schema-advertise, mutate, and reload as `float`; `DoubleProperty` SHALL do the same as `double`. The system MUST NOT coerce between `float` and `double`, coerce numbers, booleans, or nulls to strings, truncate numbers, parse strings into numeric or boolean values, or accept values outside the observed numeric range. `NaN` and positive or negative infinity SHALL be rejected before candidate attachment. An explicitly supplied `type` SHALL equal the observed type; when a transport permits omission, inference SHALL use only the native submitted value kind and range.

#### Scenario: Integer cannot replace a string property
- **WHEN** a caller sets `HTTPSampler.method`, observed as `type: string`, to integer `100`
- **THEN** the request SHALL fail before mutation with expected type, actual type, property address, and component context

#### Scenario: Exact scalar survives reload
- **WHEN** a caller submits a scalar whose protocol type and range match the observed node
- **THEN** the candidate SHALL be accepted only if SaveService reload preserves the value and observed type

#### Scenario: Float and double remain distinct through reload
- **WHEN** an exact target exposes both `FloatProperty` and `DoubleProperty` nodes and receives exact finite native values
- **THEN** discovery/read/components and CLI/MCP schemas SHALL expose `float` and `double` respectively
- **AND** SaveService reload SHALL preserve their concrete JMeter property classes and public protocol types

#### Scenario: Cross-type and nonfinite scalar input is rejected
- **WHEN** a caller supplies a `double` for an observed `float`, a `float` for an observed `double`, an overflowed value, `NaN`, or infinity
- **THEN** mutation SHALL fail before candidate attachment with expected type, actual type, and address context
- **AND** source and destination bytes SHALL remain unchanged

### Requirement: Nested leaf writes do not require whole-element replacement
A caller SHALL be able to write any writable descendant leaf of an `element` property by addressing that leaf directly. Addressing the `element` node itself SHALL remain complete replace-whole behavior; the system SHALL NOT define implicit merge semantics for a partial element document.

#### Scenario: Loop count changes by leaf address
- **WHEN** a caller addresses `ThreadGroup.main_controller` followed by `LoopController.loops` and writes integer `10`
- **THEN** only that leaf SHALL change
- **AND** unspecified sibling properties of the existing LoopController SHALL be preserved

#### Scenario: Partial element document is rejected
- **WHEN** a caller addresses the parent `element` node and omits required properties from its replacement value
- **THEN** the request SHALL fail as an incomplete replace-whole document
- **AND** recovery SHALL direct the caller to address a descendant leaf or copy the full element value from read

### Requirement: Property addresses use one scalar-segment array
Every public property address SHALL be one non-empty JSON/YAML array whose members are strings or integers from zero through `2147483647`. A string SHALL identify a property when the current graph node is an element and a map key when the current graph node is a map. An integer SHALL identify an item when the current node is a collection. Literal dots, brackets, backslashes, quotes, and empty strings inside string members SHALL remain data and SHALL NOT require a J4A-specific escape grammar. The resolver SHALL reject a segment whose scalar type is invalid for the current node, an empty address, an out-of-range or non-integral index, and an address that does not resolve in the exact target graph.

#### Scenario: Element collection leaf resolves without escaped punctuation
- **WHEN** a caller submits `["HeaderManager.headers", 0, "Header.name"]`
- **THEN** the resolver SHALL select property `HeaderManager.headers`, collection item zero, and property `Header.name`
- **AND** neither dot SHALL be interpreted as a structural separator

#### Scenario: Current node type distinguishes property key and index
- **WHEN** an address traverses an element, collection, and map using string, integer, and string members respectively
- **THEN** each member SHALL be interpreted according to the current graph node kind
- **AND** integer `0` SHALL remain distinguishable from string `"0"`

#### Scenario: String address is rejected
- **WHEN** a caller submits a canonical, escaped, JSONPath, JMESPath, JSON Pointer, or other string address
- **THEN** validation SHALL reject it before mutation because `property` is not an array
- **AND** recovery SHALL show the required scalar-array shape without attempting to parse or mechanically convert the rejected string

### Requirement: Array addresses are the complete reusable address document
Read and component details SHALL emit only scalar-segment arrays, and every mutation surface SHALL accept that same array unchanged for the same exact target. No output-mode selector, alternate address object, canonical renderer, or compatibility parser SHALL be part of the public property graph protocol.

#### Scenario: Read address is copied unchanged into mutation
- **WHEN** read emits a writable property using a scalar-segment array
- **THEN** set or apply SHALL accept that array unchanged for the same exact target
- **AND** YAML or JSON parsing SHALL not reinterpret punctuation inside string members

#### Scenario: Unrepresentable scalar segment does not exist
- **WHEN** an observed property or map key contains punctuation or an empty string
- **THEN** the scalar-segment array SHALL represent it directly as one string member
- **AND** read SHALL not fail with an address-rendering recovery error
