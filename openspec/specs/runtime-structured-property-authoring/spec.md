# runtime-structured-property-authoring Specification

## Purpose
Define structured property authoring whose shape and reconstruction are proven by the exact loaded or newly materialized JMeter target, keeping JMeter as the sole authority without registered adapters or legality tables.
## Requirements

### Requirement: Structured row authoring is target-bound runtime evidence
The system SHALL expose the universal `type: rows` representation only when the exact loaded or newly materialized target in the selected local JMeter runtime proves the outer property, row value class, persisted field names, field scalar types, ordering, reconstruction path, and safe defaults. The row storage MAY be a direct multi-property containing row elements or a wrapper TestElement containing one runtime-proven row storage. Proof MAY come from existing rows, exact-target runtime metadata and reversible runtime observation, or a version-compatible GUI table consumer uniquely correlated to the exact component property through bounded differential evidence; an empty collection SHALL NOT by itself prevent row proof. Existing target rows and persisted XML SHALL take precedence over GUI semantic evidence. Component identity, property-name tables, named collection families, catalog metadata without correlated evidence, previously observed unrelated targets, and caller-supplied Java class names MUST NOT independently authorize a row write. A public generic base type SHALL NOT replace a more specific exact consumer row type.

#### Scenario: Existing Arguments property proves its row contract
- **WHEN** the exact target contains an Arguments property whose runtime rows expose stable persisted scalar fields
- **THEN** read or component authoring details SHALL expose `type: rows` with the runtime-proven row schema
- **AND** set, apply set, and add overlays SHALL accept that representation for the same target/runtime evidence

#### Scenario: Empty Header collection proves its row contract
- **WHEN** the exact target contains an empty HTTP Header collection and the selected runtime proves the Header item class, persisted scalar fields, ordering, defaults, and reconstruction path
- **THEN** read SHALL expose `type: rows` with value `{row_type, row_properties, rows: []}` and component details SHALL expose the same `row_type` and ordered `row_properties`
- **AND** append or complete replacement SHALL be able to create the first row without a caller-supplied element envelope or Java class

#### Scenario: Empty HTTP Arguments uses its GUI consumer class
- **WHEN** a newly materialized target has empty `HTTPsampler.Arguments`, its public generic API exposes `Argument`, and correlated JMeter 5.6.3 GUI consumer evidence proves `HTTPArgument`
- **THEN** component details and first-row authoring SHALL use `org.apache.jmeter.protocol.http.util.HTTPArgument`
- **AND** they SHALL NOT fall back to `org.apache.jmeter.config.Argument`

#### Scenario: Existing persisted subtype overrides cached metadata
- **WHEN** an exact loaded target contains concrete rows whose class differs from cached component metadata
- **THEN** the exact target rows SHALL be authoritative for that target
- **AND** cached evidence SHALL NOT rewrite or reclassify them

#### Scenario: Direct and wrapped storage use one public rows contract
- **WHEN** one exact target proves direct collection rows and another proves rows inside a wrapper TestElement
- **THEN** both SHALL emit and accept the same public `type: rows` value shape
- **AND** their different runtime reconstruction paths SHALL remain internal target-bound evidence

#### Scenario: Caller row type is only an assertion
- **WHEN** a caller supplies `row_type` for a structured row value
- **THEN** it SHALL match the row type discovered from the exact target or its correlated exact-runtime consumer evidence
- **AND** it SHALL NOT cause J4A to load, instantiate, or authorize a caller-selected class

#### Scenario: Unknown runtime row family needs no production adapter
- **WHEN** a test-only or plugin row family absent from production component/property family knowledge proves the same runtime row contract
- **THEN** the ordinary runtime observer, normalizer, and materializer SHALL expose and write `type: rows`
- **AND** a classloader sentinel SHALL prove that caller `row_type` caused no class loading

#### Scenario: No runtime proof falls back without an adapter lookup
- **WHEN** the exact property and every applicable exact-runtime evidence source fail to prove a structured row representation
- **THEN** the property SHALL use its generic recursive or verified opaque representation when available
- **AND** the system SHALL report whether existing-item edits are possible without inviting the caller to guess a Java class

### Requirement: Add and existing-target writes use one structured value contract
Add overlays, set, apply set, append, and insert SHALL normalize and validate the same structured row contract. The object value form SHALL contain required `rows` and MAY contain `row_type` and ordered `row_properties`. Each `row_properties` member SHALL contain exactly `name`, scalar `type`, boolean `required`, and `default` only when `required: false`; every accepted persisted scalar field SHALL appear once in runtime order. A non-required field's `default` SHALL be a native scalar of its declared type, including null when null is the proven value. Submitted schema members are target-bound assertions and SHALL match current proof rather than authorize it. An add operation SHALL first materialize the real candidate in the selected runtime and then discover its property evidence before validating overlays. A value accepted for an exact existing target SHALL be accepted for an equivalently materialized add candidate with the same observed property evidence. A field omitted from a submitted row SHALL be filled only when its descriptor is non-required and supplies the proven default; otherwise omission SHALL fail as a missing required field.

#### Scenario: HTTP sampler is added with Arguments in one operation
- **WHEN** one add operation materializes an HTTP sampler and supplies `type: rows` as either a bare row list or `{row_type, row_properties, rows}` using the emitted schema
- **THEN** the rows SHALL be applied before candidate SaveService verification
- **AND** the operation SHALL NOT require a later set operation or a pre-materialization descriptor

#### Scenario: Empty Header collection accepts its first row
- **WHEN** append supplies one row matching the emitted schema of an empty Header collection
- **THEN** the selected runtime SHALL reconstruct and attach the first item before SaveService verification
- **AND** the caller SHALL NOT provide a collection index, recursive item envelope, property class, or element class

#### Scenario: Runtime default fills an omitted optional field
- **WHEN** a submitted row omits a field whose emitted descriptor has `required: false` and contains a runtime-proven persisted `default`
- **THEN** materialization SHALL use that default and reload SHALL preserve the resulting row projection
- **AND** omission of a field without such a default SHALL fail before attachment

#### Scenario: Read rows document is copied unchanged
- **WHEN** read emits `{row_type, row_properties, rows}` for an empty or populated proven collection
- **THEN** set, apply set, or add overlay SHALL accept that object unchanged for equivalent exact-target evidence
- **AND** mismatched asserted properties or defaults SHALL fail before attachment

#### Scenario: Add and set reject the same malformed row
- **WHEN** equivalent add and set requests contain an unknown row field or a field with the wrong scalar type
- **THEN** both SHALL fail with the same property-representation category before commit
- **AND** neither target SHALL be partially changed

### Requirement: Runtime-proven row mutations are atomic
A submitted structured row replacement SHALL replace the complete target collection in request order. `append` SHALL add one validated row after the current final row; `insert` SHALL add one validated row at a requested index from zero through the current row count; and `remove` SHALL delete exactly one existing row at a requested index. Every affected row SHALL be validated and materialized on an isolated candidate before attachment, and commit SHALL occur only after the selected runtime saves, reloads, and preserves the resulting ordered projection.

#### Scenario: One invalid replacement row preserves the existing collection
- **WHEN** any submitted replacement row violates the runtime-proven field contract
- **THEN** no row SHALL be attached to the target collection
- **AND** source and destination bytes SHALL remain unchanged

#### Scenario: Append grows an existing collection
- **WHEN** a valid append targets a collection containing one row
- **THEN** reload SHALL preserve the original row followed by the appended row
- **AND** duplicate rows and empty string field values SHALL remain valid when permitted by the schema

#### Scenario: Insert validates its boundary
- **WHEN** insert uses an index from zero through the current row count
- **THEN** the new row SHALL appear at that ordered position after reload
- **AND** an index greater than the current row count SHALL fail without mutation

#### Scenario: Remove deletes one indexed row
- **WHEN** remove addresses a valid existing row index
- **THEN** reload SHALL preserve every other row in its original relative order
- **AND** an absent index SHALL fail without mutation

#### Scenario: Reload preserves ordered rows
- **WHEN** a structured row replacement or incremental mutation is valid
- **THEN** SaveService reload SHALL preserve row order, duplicate rows, empty strings, runtime defaults, and every explicitly supplied persisted field
