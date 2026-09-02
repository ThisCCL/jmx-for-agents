# jmx-agent-cli Specification

## Purpose
Define the local JMX Agent CLI contract for reading, editing, validating, discovering, and patching JMeter `.jmx` files through agent-friendly YAML surfaces.
## Requirements

### Requirement: CLI exposes read, set, validate, components, apply, and categories commands
The system SHALL provide a local command-line interface with `read`, `set`, `validate`, `components`, `apply`, `categories`, and `init` commands for one `.jmx` file, one output `.jmx` file, or one catalog query at a time. `set` SHALL remain available as a V1 compatibility command unless a later accepted change removes it.
#### Scenario: Help identifies supported commands
- **WHEN** the user runs the CLI help
- **THEN** the help output SHALL list `read`, `set`, `validate`, `components`, `apply`, `categories`, and `init`
- **AND** the help output SHALL describe required arguments and supported flags

### Requirement: Locators are deterministic structural identifiers
The system SHALL generate deterministic structural locators from component hierarchy and path, using an MD5-like stable hash representation. A locator SHALL remain stable while node hierarchy is unchanged and SHALL change when structural hierarchy changes.

#### Scenario: Locator stability follows hierarchy
- **GIVEN** the same JMX tree structure is read twice
- **WHEN** no structural hierarchy changes occur
- **THEN** each component receives the same locator both times

### Requirement: YAML property fields address generic JMeter properties and remain V1 set-compatible
The system SHALL expose writable runtime property-graph addresses as YAML `property` arrays when read emits properties. Every address SHALL be a non-empty array of string property/key segments and non-negative integer collection indexes. Apply SHALL accept that form natively. V1 set SHALL require the same array serialized as one JSON array argument to `--property`; every non-array argument SHALL be rejected rather than interpreted as a legacy path string.

For V1 set, `--type` SHALL accept exactly `string|boolean|int|long|float|double|null|raw|collection|map|element|rows|opaque`. Scalar `--value` inputs retain literal parsing according to the declared type. For `collection|map|element|rows|opaque`, `--value` SHALL be one shell argument containing a SnakeYAML safe-mode YAML/JSON fragment decoded by the same value decoder as apply. Named collection family types such as `arguments`, headers, cookies, files, authorization, and DNS rows SHALL be rejected with guidance to copy the target-emitted `type: rows` document.

#### Scenario: Property from read can be used by set or apply
- **GIVEN** read emits a writable property address array
- **WHEN** apply receives the native array or V1 set receives its compact JSON serialization as one `--property` argument
- **THEN** both SHALL resolve the same exact graph node without transport escape reinterpretation

#### Scenario: V1 set accepts universal rows
- **WHEN** set receives `--type rows --value '{"row_type":"org.example.Row","rows":[{"name":"a","value":"b"}]}'` for a target whose emitted schema proves those fields and row type
- **THEN** set SHALL decode the same rows document accepted by apply
- **AND** `row_type` SHALL remain an assertion rather than class authorization

#### Scenario: String property address is rejected safely
- **WHEN** set or apply receives a string property address or malformed array
- **THEN** it SHALL fail before commit with an actionable array-address error
- **AND** it SHALL not invoke a canonical path parser, create an unknown property, silently edit another property, or write an output file

#### Scenario: YAML property from read can be reused unchanged
- **GIVEN** read or components emits a writable `property` array for a component
- **WHEN** an apply set operation uses that array and representation for the same component
- **THEN** the targeted graph node SHALL be edited without requiring path conversion or J4A-specific escaping

#### Scenario: CLI set parses recursive values as safe YAML
- **WHEN** V1 set receives `--type collection`, `map`, `element`, `rows`, or `opaque`
- **THEN** `--value` SHALL be parsed as one SnakeYAML safe-mode YAML fragment by the same graph value decoder used by apply
- **AND** scalar set types SHALL retain their existing literal value behavior

### Requirement: Set command enforces write safety
The `set <file.jmx> --locator <id> --property <json-scalar-segment-array> --type <string|boolean|int|long|float|double|null|raw|collection|map|element|rows|opaque> --value <literal|yaml-json-fragment> (--out <file.jmx> | --override) [--force-out] [--jmeter-home <path>]` command SHALL edit one targeted property through the resolved local runtime, write through a temporary candidate, and commit only after SaveService save/reload validation succeeds. Omitted `--type` SHALL retain the existing default `string` only for literal scalar input; structured values SHALL require an explicit type or a complete reusable property document in apply.

#### Scenario: Out or override is required
- **WHEN** set is run without `--out` and without `--override`
- **THEN** it SHALL fail with a usage error and leave the input unchanged

#### Scenario: Existing out target requires force
- **WHEN** `--out <file.jmx>` already exists and `--force-out` is absent
- **THEN** set SHALL fail with a filesystem/write-safety error and not overwrite the target

#### Scenario: Out must differ from input
- **WHEN** `--out` resolves to the input path without `--override`
- **THEN** set SHALL return a usage error and direct the caller to use override

#### Scenario: Validation failure preserves target
- **WHEN** array-address decoding, value decoding, local runtime loading, saving, or reloading fails
- **THEN** set SHALL exit nonzero and preserve source and requested target bytes

### Requirement: Runtime constraints use JDK 8, Gradle, Aliyun, and Windows Unicode paths
The implementation SHALL target JDK 8-compatible source and bytecode with Gradle. The Gradle wrapper distribution URL SHALL use the Aliyun mirror `https://mirrors.aliyun.com/gradle/`. CLI file handling SHALL support Windows Unicode paths such as `D:\\work\\压测脚本`.
#### Scenario: Build emits Java 8 bytecode
- **WHEN** the project is compiled or packaged
- **THEN** main and test Java sources compile under the JDK 8 target
- **AND** packaged CLI classes use Java 8-compatible bytecode
#### Scenario: Windows Unicode JMX path is accepted
- **WHEN** a `.jmx` file path contains Unicode characters on Windows
- **THEN** `read`, `set`, `validate`, `apply`, and `init` handle the path without mojibake or path truncation
#### Scenario: Windows Unicode JMeter home path is accepted
- **WHEN** a local JMeter home path contains Unicode characters on Windows
- **THEN** `read`, `set`, `validate`, `components`, `apply`, and `init` resolve and use the path without mojibake or path truncation

### Requirement: Must NOT guardrails continue to define excluded behavior
The system MUST NOT implement GUI/editor UI scope, JSON or Markdown as the default agent-facing output for `read` and `components`, JMeter plugin installation or management, load-test execution as validation, default redaction, auto-fix, rollback after validation failure, batch editing many `.jmx` files, or raw XML editing as the primary interface. Ordinary JMX command execution through the packaged wrapper MUST NOT perform hidden network calls. The packaged wrapper MAY perform explicit network calls only for the user-invoked `install` flow that downloads the configured runtime jar and the user-invoked `mcp` bootstrap flow that installs a missing runtime jar before starting the Java MCP server.

#### Scenario: Agent-facing defaults are YAML and not redacted
- **WHEN** `read` or `components` is run with its default output behavior
- **THEN** output SHALL be YAML
- **AND** values SHALL NOT be redacted by default

#### Scenario: Apply is not raw XML editing
- **WHEN** `apply` edits a JMX file
- **THEN** it mutates JMeter model objects and saves through JMeter `SaveService`
- **AND** it does not accept XPath or raw XML patches as the primary edit interface

#### Scenario: Ordinary wrapper commands do not make hidden network calls
- **WHEN** the user runs a packaged wrapper command other than `install` or `mcp`
- **THEN** the wrapper does not download the runtime jar or make other remote network calls as part of command startup

#### Scenario: MCP bootstrap may install a missing runtime jar
- **WHEN** the user runs `mcp` through the packaged wrapper and the cached runtime jar is missing
- **THEN** the wrapper MAY run the explicit runtime installation flow defined by the MCP capability before starting the Java MCP server
- **AND** this bootstrap exception does not apply to ordinary Java-forwarding CLI subcommands

### Requirement: Read command emits structured YAML by default
The `read <file.jmx> [--depth <n>] [--ref <component-ref>] [--properties key|all] [--include-disabled-details] [--jmeter-home <path>]` command SHALL freshly load through the resolved local runtime and emit structured YAML with component refs, metadata, key fields, tree shape, optional property paths, and source lines. Default output SHALL be rooted at the Test Plan with depth `1`; expanded nodes SHALL use a `children` list and collapsed nodes SHALL use `child_count` plus `children_omitted`.

#### Scenario: Default read is token-efficient
- **WHEN** a valid JMX is read without depth, focus, or property flags
- **THEN** enabled components SHALL include refs, component metadata, key-field summaries, depth-limited shape, and source ranges
- **AND** properties SHALL be omitted and disabled components SHALL remain summary-only

#### Scenario: Read depth limits traversal
- **WHEN** read uses `--depth 2`
- **THEN** it SHALL include nodes through two child levels and represent deeper descendants with `child_count` and `children_omitted`

#### Scenario: Focused read anchors on a node
- **WHEN** read uses `--ref <ref>` for an existing node
- **THEN** output SHALL contain `path` breadcrumbs from document root and a `focus` subtree
- **AND** path entries SHALL include `ref`, `component`, `name`, `enabled`, and source range but no sibling, child, or property data
- **AND** focus SHALL include its ref, key fields, source range, and requested properties, while descendant properties remain omitted

#### Scenario: Focused read can be depth-limited
- **WHEN** focused read also uses `--depth 1`
- **THEN** it SHALL include only focus and direct children and represent deeper descendants as omitted

#### Scenario: Requested properties are copyable
- **WHEN** read uses `--properties key` or `--properties all`
- **THEN** it SHALL emit property paths and values usable by set/apply for the same component

#### Scenario: All properties exclude GUI bookkeeping
- **WHEN** read uses `--properties all`
- **THEN** it SHALL omit top-level and nested `TestElement.gui_class` and `TestElement.test_class` property paths

#### Scenario: Known key properties remain deterministic
- **WHEN** key properties are emitted for test plan, thread group, loop controller, or HTTP request
- **THEN** they SHALL retain the established TestElement/TestPlan/ThreadGroup/LoopController/HTTPSampler key paths

#### Scenario: Disabled details are explicitly addressable
- **WHEN** read uses `--include-disabled-details`
- **THEN** disabled component details SHALL use the same ref, component, property, value, type, and structural field names as enabled details

#### Scenario: Stale verbose and profile flags are rejected
- **WHEN** read receives `--verbose`, `--profile`, or `--validation-mode`
- **THEN** it SHALL return a usage error and direct verbose callers to property flags

### Requirement: Read Component Source Line Ranges
The `read` command SHALL include source line ranges for components when the plan is read from a file-backed JMX input.

#### Scenario: Read output includes component source ranges
- **WHEN** the user runs `read <jmx-file>`
- **THEN** each component entry in the YAML output SHALL include a `source` object
- **AND** `source.start_line` and `source.end_line` SHALL be 1-based inclusive line numbers in the original JMX file

#### Scenario: Focused read includes focused component range
- **WHEN** the user runs `read <jmx-file> --ref <component-ref>`
- **THEN** the focused component output SHALL include the component's `source` line range

#### Scenario: Source ranges do not expose raw XML
- **WHEN** the `read` command emits a component source range
- **THEN** the CLI SHALL emit line metadata only
- **AND** the CLI SHALL NOT emit raw JMX XML content as part of the `read` response

#### Scenario: Source ranges support Windows paths
- **WHEN** the user runs `read` against a JMX file whose path contains Windows separators, spaces, or non-ASCII characters
- **THEN** the CLI SHALL preserve valid component source line ranges in the YAML output

### Requirement: Components command emits restrained YAML component catalog
The `components [--category <public-category>] [--component <runtime-fqcn>|--kind <runtime-fqcn>] [--details] [--jmeter-home <path>]` command SHALL query the selected local JMeter MenuFactory registry and emit restrained YAML. List entries SHALL contain public `category`, readable `label`, and exact runtime FQCN `component`. Details SHALL retain `component`, `label`, `category`, and runtime-observed property graph representations. Property records SHALL expose representation capability fields needed by an agent, including protocol `type`, writable state, reason when not writable, representation source, value shape, and required runtime class where applicable. Ordinary detail output SHALL NOT include raw `group`, duplicate `class`, registration `kind`, resource keys, admission status, support-level judgments, allowed-parent, placement, addability, or GUI-openability judgments. `kind` is only a compatibility input alias for `component` and SHALL NOT be emitted.

#### Scenario: Default components uses the local runtime catalog
- **WHEN** the user runs `components` without a filter
- **THEN** the CLI SHALL discover current MenuFactory members from the resolved local runtime
- **AND** it SHALL emit grouped public category entries at ordinary list density

#### Scenario: Component identity remains exact FQCN
- **WHEN** a runtime menu entry is emitted or selected for detail
- **THEN** `component` SHALL equal the exact retained `menuClassName` FQCN
- **AND** arbitrary loadable class names outside current registry membership SHALL remain unresolved

#### Scenario: Category filtering uses public membership
- **WHEN** the user runs `components --category <known-public-id-or-approved-alias>`
- **THEN** output SHALL use the canonical public id and contain only matching projected entries

#### Scenario: Detail is leak-free and representation-oriented
- **WHEN** the user requests one component with `--details`
- **THEN** each property SHALL contain `property`, protocol `type`, storage `key`, and `writable`
- **AND** it SHALL include `reason` for non-writable values and MAY include default, value shape, row schema, representation source, and required runtime class when applicable
- **AND** ordinary output SHALL omit every internal or admission field prohibited by this requirement

#### Scenario: Details requires a component
- **WHEN** `--details` is supplied without `--component` or `--kind`
- **THEN** the CLI SHALL fail with an actionable usage error

### Requirement: Category Discovery Command
The CLI SHALL expose `categories ls [--jmeter-home <path>]` using the same selected local runtime and public category catalog as `components`. It SHALL emit each category once with `category`, readable `label`, and `component_count` and SHALL teach `components --category` without profile or raw-group vocabulary.

#### Scenario: List public categories
- **WHEN** the user runs `categories ls`
- **THEN** the CLI SHALL list all eleven core public categories in fixed order
- **AND** it SHALL append `local` only when an unmapped visible component exists

#### Scenario: Known category with no components is valid
- **WHEN** the user filters components by a known public category whose count is zero
- **THEN** the CLI SHALL return that category with an empty components list

#### Scenario: Unknown component category is actionable
- **WHEN** the user supplies an unknown category
- **THEN** the typed usage error SHALL identify the input and provide the exact `categories ls` recovery action

#### Scenario: Category help is public-vocabulary only
- **WHEN** the user requests category help
- **THEN** it SHALL describe the public ids, labels, counts, local-home selection, and relationship to `components --category`
- **AND** it SHALL NOT teach raw menu groups or removed profiles

### Requirement: GUI-Aligned Component Categories
Public categories SHALL be a stable projection of current JMeter MenuFactory membership, not an independent class scanner or legality classifier. Raw groups SHALL remain internal membership facts; exact FQCN component identity, MenuInfo Add decisions, materialization, and parent compatibility remain JMeter-owned.

#### Scenario: Runtime groups project to stable public ids
- **WHEN** a current registry entry uses one of the eleven mapped raw groups
- **THEN** it SHALL appear under the corresponding public id regardless of component implementation class

#### Scenario: Repeated categories are grouped
- **WHEN** multiple components project to one public category
- **THEN** output SHALL contain one category block with all matching components

#### Scenario: Unmapped local components remain discoverable
- **WHEN** a visible registry entry uses an unmapped raw group
- **THEN** it SHALL appear under `local` without exposing or normalizing the raw group

#### Scenario: Non-menu classes remain excluded
- **WHEN** a loadable TestElement class is absent from the retained MenuFactory registry
- **THEN** it SHALL NOT become discoverable or acceptable solely through scanning or class loading

### Requirement: Apply command edits JMX through strict YAML patch operations
The `apply <file.jmx> --patch <file|-> (--dry-run [--out <ignored-file.jmx>] | --override | --out <file.jmx> [--force-out]) [--jmeter-home <path>]` command SHALL apply a strict versionless YAML document containing `set`, `add`, `move`, `delete`, `append`, `insert`, and `remove` operation cards through the resolved local runtime. An add card MAY declare a request-local alias with `as`, and a later reference-bearing field MAY use `$<alias>`. Reference-bearing fields are exactly `set.ref`, `add.parent`, `add.before`, `add.after`, `move.ref`, `move.parent`, `move.before`, `move.after`, `delete.ref`, `append.ref`, `insert.ref`, and `remove.ref`; component, position, property array, index, type, row, and value fields are not reference-bearing and SHALL retain their literal data meaning even when a string begins with `$`.

#### Scenario: Apply accepts only strict versionless cards
- **WHEN** apply parses a patch
- **THEN** it SHALL require top-level `changes`, reject `version`, require exactly one known operation per item, and reject unknown fields, missing fields, malformed values, duplicate ambiguous refs, invalid types, and operation-forbidden fields before writing
- **AND** it SHALL accept `as` only on add, require alias names to match `[A-Za-z][A-Za-z0-9_-]{0,63}`, and interpret `$alias` only in reference-bearing fields after that alias's declaration
- **AND** `$`-prefixed strings in non-reference fields SHALL remain ordinary values governed by those fields' existing validation

#### Scenario: Empty add uses runtime defaults or fallback identity
- **WHEN** add supplies parent, a runtime-resolved component, exactly one placement selector, and omitted or empty properties
- **THEN** apply SHALL materialize through the runtime-authority pipeline without overlaying properties

#### Scenario: Add overlays representable properties
- **WHEN** add supplies properties matching their observed JMeter/YAML representation
- **THEN** apply SHALL materialize first and overlay those values before candidate round-trip

#### Scenario: Add structure is strict before JMeter placement
- **WHEN** add omits parent or placement, supplies multiple placement selectors, references a non-child anchor, or supplies a negative/out-of-range numeric position
- **THEN** apply SHALL return an operation error without writing
- **AND** a structurally valid placement SHALL be delegated to MenuInfo Add-enabled and MenuFactory canAddTo decisions

#### Scenario: Numeric position bounds are defined
- **WHEN** add uses numeric position
- **THEN** zero through child_count SHALL be accepted structurally, child_count SHALL append, and greater values SHALL fail before writing

#### Scenario: Runtime-resolved components are attempted
- **WHEN** add references a uniquely resolved menu identity
- **THEN** apply SHALL attempt runtime creation and fallback rather than require a J4A addability status

#### Scenario: Dry run validates without writing
- **WHEN** apply uses dry run
- **THEN** it SHALL execute the complete runtime-authority candidate pipeline, require no output mode, write no target, and report applicability

### Requirement: Apply preserves batch placement declaration order
The system SHALL derive sibling order from `HashTree.list()` and retain each key's exact subtree from `getTree(key)`. For operations sharing the same resolved parent and placement, output order SHALL follow their order in the complete `changes` list, and unrelated operations SHALL NOT reset that ordering.

| Placement operations in declaration order | Required sibling result |
| --- | --- |
| `last A`, `last B`, `last C` after `existing` | `existing, A, B, C` |
| `before anchor: X`, `before anchor: Y` | `X, Y, anchor` |
| `after anchor: X`, `after anchor: Y` | `anchor, X, Y` |

#### Scenario: Repeated last additions append in declaration order
- **GIVEN** a parent whose sibling order is `existing`
- **WHEN** one patch declares additions `A`, `B`, and `C`, each at `position: last`
- **THEN** the exact sibling order SHALL be `existing, A, B, C`

#### Scenario: Repeated before placements retain declaration order
- **GIVEN** an existing child `anchor`
- **WHEN** one patch declares placement of `X` and then `Y` before the same resolved parent and `anchor`
- **THEN** the exact local order SHALL be `X, Y, anchor`

#### Scenario: Repeated after placements retain declaration order
- **GIVEN** an existing child `anchor`
- **WHEN** one patch declares placement of `X` and then `Y` after the same resolved parent and `anchor`
- **THEN** the exact local order SHALL be `anchor, X, Y`

#### Scenario: Interleaved same-anchor operations use global declaration order
- **GIVEN** an existing child `anchor`
- **WHEN** placement of `X` after `anchor`, an unrelated operation, and placement of `Y` after the same resolved parent and `anchor` are interleaved in one `changes` list
- **THEN** the exact local order SHALL be `anchor, X, Y`
- **AND** the unrelated operation SHALL NOT reset or reverse the same-anchor group

#### Scenario: Untouched children retain identity and order
- **WHEN** a batch places children around existing siblings
- **THEN** every untouched sibling SHALL retain its original element object and subtree object
- **AND** siblings outside the affected placement SHALL retain their relative order

#### Scenario: Mutated anchor conflicts are rejected atomically
- **WHEN** one patch uses an input child as a placement anchor and also moves or deletes that anchor
- **THEN** prevalidation SHALL reject the patch with a semantic placement error at exit status 3
- **AND** no requested target SHALL be created or modified

### Requirement: Apply refs are scoped to the input snapshot
Every ordinary `jmx_*` ref in one apply SHALL resolve against the input tree before mutation and SHALL remain bound to that input object for the apply. A request-local `$alias` SHALL bind only to the object created by its preceding add. Neither ordinary refs nor aliases SHALL be persistent identities across CLI writes or invocations.

#### Scenario: Earlier mutations do not retarget input refs
- **WHEN** earlier operations insert or move peers before an object addressed by a later ordinary ref
- **THEN** the later ref SHALL still address the object to which it resolved in the input snapshot

#### Scenario: Added nodes cannot be targeted in the same patch
- **WHEN** an add declares an alias and a later change in the same ordered patch uses `$alias` in a reference-bearing field
- **THEN** the later change SHALL target the exact object materialized by that add

#### Scenario: Invalid alias dependency fails without writing
- **WHEN** a patch duplicates an alias or uses an undeclared/forward alias in a reference-bearing field
- **THEN** apply SHALL fail with a semantic patch error at exit status 3
- **AND** no requested target SHALL be created or modified

#### Scenario: Successful write requires fresh read
- **WHEN** apply writes successfully
- **THEN** CLI and agent guidance SHALL instruct CLI clients to run `read` before using ordinary refs in another patch
- **AND** insertion of a same-component predecessor MAY change an old ordinal-derived ref while insertion of a different component kind SHALL NOT change that component kind's ordinal

#### Scenario: Missing stale ref fails without writing
- **GIVEN** a client retained a ref from an earlier input snapshot
- **WHEN** that ref is absent from the current apply input
- **THEN** apply SHALL fail with `LOCATOR_NOT_FOUND` and semantic exit status 3
- **AND** the requested target SHALL NOT be created or modified
- **AND** recovery guidance SHALL instruct the client to run `read` and rebuild the patch with fresh refs

### Requirement: Apply resolves an exclusive write mode
Before input mutation, apply SHALL normalize its arguments to exactly one mode according to this truth table. A nonblank `out` means a path containing at least one non-whitespace character after trimming; path equality is determined after normal path resolution.

| Mode | Required arguments | Permitted arguments | Forbidden or ignored arguments |
| --- | --- | --- | --- |
| DRY_RUN | `dryRun=true` | nonblank `out` | `out` is ignored; `override=true` and `forceOut=true` are forbidden |
| IN_PLACE | `override=true`, `dryRun=false` | none | `out` and `forceOut=true` are forbidden |
| COPY | nonblank `out`, `dryRun=false`, `override=false` | `forceOut=true` only to replace an existing output at a path different from input | blank `out`; `out` resolving to input |

#### Scenario: Dry run may carry an ignored output
- **WHEN** apply receives `dryRun=true`, optional nonblank `out`, `override=false`, and `forceOut=false`
- **THEN** it SHALL select DRY_RUN, ignore `out`, validate without writing, and leave input and output paths unchanged

#### Scenario: In-place mode is override only
- **WHEN** apply receives `override=true`, `dryRun=false`, no `out`, and `forceOut=false`
- **THEN** it SHALL select IN_PLACE and replace the input only after candidate validation succeeds

#### Scenario: Copy mode writes a different path
- **WHEN** apply receives a nonblank `out` resolving differently from input, `dryRun=false`, and `override=false`
- **THEN** it SHALL select COPY
- **AND** `forceOut=true` SHALL authorize replacement only when that different-path output already exists

#### Scenario: Contradictory write arguments are usage errors
- **WHEN** apply receives blank `out`, no mode selector, `out` with `override`, dry run with `override`, dry run with `forceOut`, `override` with `forceOut`, or `forceOut` without a nonblank `out`
- **THEN** it SHALL return `USAGE_ERROR` with exit status 2 and state the valid DRY_RUN, IN_PLACE, and COPY alternatives
- **AND** no requested target SHALL be created or modified

#### Scenario: Same-path output never means copy
- **WHEN** `out` resolves to the input path, with or without `forceOut=true`
- **THEN** apply SHALL return `USAGE_ERROR` with exit status 2 and direct the caller to use `override`
- **AND** the input bytes SHALL remain unchanged

#### Scenario: Semantic and filesystem failures retain exit classes
- **WHEN** parsing succeeds but a locator, placement, component, or candidate is semantically invalid
- **THEN** apply SHALL return its actionable semantic code with exit status 3
- **AND** when profile infrastructure, filesystem access, output existence without permitted replacement, or atomic writing fails, apply SHALL return its actionable infrastructure/filesystem code with exit status 4
- **AND** every failure SHALL preserve the requested target bytes

### Requirement: Read, components, and apply share one YAML vocabulary
Read, components, categories, set, and apply SHALL use exact runtime FQCN `component` identity, copyable property-graph paths, recursive protocol types, and capability metadata consistently. Catalog outputs SHALL use public `category` and readable `label`; ordinary surfaces SHALL NOT expose registration-kind or raw-group vocabulary.

#### Scenario: Output-to-patch field names match
- **WHEN** components details describe a writable property
- **THEN** the emitted `property`, `type`, and value shape SHALL be accepted by set and apply for the same selected runtime

#### Scenario: Component identity matches across read components and apply
- **WHEN** an agent copies a catalog `component` FQCN into an add operation
- **THEN** apply SHALL resolve it against the same selected runtime registry

#### Scenario: Locator and ref coexist without identity ambiguity
- **WHEN** read emits locators, refs, component identities, and graph paths
- **THEN** each field SHALL retain its existing distinct addressing role

#### Scenario: Read properties remain set-compatible
- **WHEN** read emits a writable scalar, collection, map, nested, or opaque property representation
- **THEN** set SHALL accept the same representation subject to existing safety rules

### Requirement: Packaged wrapper help is available before runtime installation
The packaged `j4a` npm wrapper SHALL provide wrapper-local help for install-first usage without requiring the cached runtime jar. `j4a --help` and `j4a install --help` SHALL describe `install`, `install --with-skills`, and how ordinary commands are forwarded to the Java runtime after installation.

#### Scenario: Wrapper help works without installed jar
- **WHEN** the user runs `j4a --help`
- **AND** the configured cached jar is missing
- **THEN** the wrapper exits zero without downloading the jar
- **AND** the help output documents `j4a install` and `j4a install --with-skills`

#### Scenario: Install help works without installed jar
- **WHEN** the user runs `j4a install --help`
- **AND** the configured cached jar is missing
- **THEN** the wrapper exits zero without downloading the jar
- **AND** the help output documents `j4a install` and `j4a install --with-skills`

### Requirement: Packaged wrapper installs runtime explicitly
The packaged `j4a` npm wrapper SHALL provide an explicit `install` subcommand for runtime preparation. `j4a install` SHALL download or reuse the configured cached `j4a.jar` and verify it against the configured SHA-256 before reporting success. `j4a install --with-skills` SHALL complete the same jar installation flow first and only then continue to skill installation.

#### Scenario: Install prepares the cached runtime jar
- **WHEN** the user runs `j4a install`
- **THEN** the wrapper verifies whether the configured cache path already contains a jar matching the configured SHA-256
- **AND** it downloads the jar only when the cached file is missing or invalid
- **AND** it exits zero only after a valid cached jar is present

#### Scenario: Install with skills performs jar installation first
- **WHEN** the user runs `j4a install --with-skills`
- **THEN** the wrapper completes the same cached-jar preparation as `j4a install`
- **AND** it does not begin skill installation until the jar installation has succeeded

#### Scenario: Install with skills stops when jar installation fails
- **WHEN** the user runs `j4a install --with-skills`
- **AND** the jar cannot be downloaded or verified
- **THEN** the wrapper exits nonzero
- **AND** it does not create or modify `<cwd>/.agents/skills/j4a-master`

### Requirement: Packaged wrapper materializes whitelisted skills into the caller workspace
The packaged `j4a` npm wrapper SHALL bundle a whitelist skill payload containing only `skills/j4a-master`. That packaged skill payload SHALL be release-safe for npm consumers: it SHALL direct users to the installed `j4a` command and SHALL NOT require this repository checkout, the repository Gradle wrapper, or a locally built shadow jar. When `j4a install --with-skills` runs after successful jar installation, the wrapper SHALL install that skill into the caller's current working directory at `<cwd>/.agents/skills/j4a-master`.

#### Scenario: Skill install uses caller working directory
- **WHEN** the user runs `j4a install --with-skills` from a workspace directory
- **THEN** the wrapper copies the packaged `skills/j4a-master` payload into `<cwd>/.agents/skills/j4a-master`
- **AND** it does not target the npm package directory or the jar cache directory

#### Scenario: Skill install preserves existing target directory
- **WHEN** `j4a install --with-skills` finds that `<cwd>/.agents/skills/j4a-master` already exists
- **THEN** the wrapper does not overwrite that target directory
- **AND** it reports that the skill installation was skipped
- **AND** it exits zero

#### Scenario: Installed skill is package-safe
- **WHEN** the npm package includes `skills/j4a-master`
- **THEN** the installed skill content tells users to invoke the packaged `j4a` command
- **AND** it does not require this repository checkout, the repository Gradle wrapper, or a locally built shadow jar

#### Scenario: Skill install copy failure is actionable
- **WHEN** `j4a install --with-skills` has already installed a valid cached jar
- **AND** creating or copying `<cwd>/.agents/skills/j4a-master` fails
- **THEN** the wrapper exits nonzero
- **AND** it reports the skill installation failure with filesystem-oriented recovery guidance
- **AND** the previously prepared cached jar remains available

### Requirement: Ordinary wrapper commands require installed runtime and do not auto-download
After this change, packaged wrapper commands that forward to the Java runtime SHALL treat the cached runtime jar as a prerequisite. If the cached jar is missing or fails integrity checks, the wrapper SHALL fail before invoking Java and SHALL direct the user to run `j4a install`. Ordinary Java-forwarding command execution SHALL NOT download the runtime jar implicitly.

#### Scenario: Missing runtime fails with install guidance
- **WHEN** the user runs `j4a read <file>` or another Java-forwarding packaged wrapper command
- **AND** the configured cached jar is missing
- **THEN** the wrapper exits nonzero before invoking `java -jar`
- **AND** the error tells the user to run `j4a install`

#### Scenario: Invalid cached runtime fails before Java execution
- **WHEN** the user runs a Java-forwarding packaged wrapper command other than wrapper-local help
- **AND** the configured cached jar exists but fails the configured SHA-256 check
- **THEN** the wrapper exits nonzero before invoking `java -jar`
- **AND** it reports that the runtime jar must be reinstalled with `j4a install`

#### Scenario: Skills are not a prerequisite for ordinary commands
- **WHEN** the cached runtime jar is valid
- **AND** `<cwd>/.agents/skills/j4a-master` is absent
- **THEN** ordinary packaged wrapper commands still invoke `java -jar` normally
- **AND** the wrapper does not fail only because the skill directory is absent

### Requirement: Init command creates a minimal valid JMX
The `init <out.jmx> [--force-out] [--jmeter-home <path>] [--name <test-plan-name>] [--thread-group-name <name>]` command SHALL create through JMeter model objects an enabled Test Plan and one enabled Thread Group, SaveService-round-trip the candidate through the resolved local runtime, and use default names `JMX Agent Test Plan` and `Thread Group` when names are absent.

#### Scenario: Init writes a valid minimal plan
- **WHEN** init targets a missing path with a valid local runtime
- **THEN** it SHALL atomically write a JMX containing the enabled Test Plan and Thread Group after candidate validation

#### Scenario: Missing home creates no target
- **WHEN** no valid local home resolves
- **THEN** init SHALL fail with environment-first guidance and create no target

#### Scenario: Custom and default names are deterministic
- **WHEN** custom names are supplied they SHALL be used
- **AND** when omitted the documented default names SHALL be used

#### Scenario: Init output is read-style YAML
- **WHEN** init succeeds
- **THEN** stdout SHALL contain exactly one read-style YAML document with copyable refs and no progress text

#### Scenario: Init does not use XML templates
- **WHEN** init creates a plan
- **THEN** it SHALL use JMeter objects and SaveService rather than a bundled raw XML seed

#### Scenario: Removed profile inputs fail safely
- **WHEN** init receives `--profile` or `--validation-mode`
- **THEN** it SHALL return a usage error without creating or overwriting a target

### Requirement: Init command enforces output write safety
Init SHALL protect existing targets, create missing parent directories, reject a parent path that is a file, and write through a temporary candidate. It SHALL create or replace the target only after local-runtime candidate validation succeeds.

#### Scenario: Existing target requires force
- **WHEN** the target exists and force is absent
- **THEN** init SHALL fail without changing it

#### Scenario: Force replaces only after validation
- **WHEN** force is supplied for an existing target
- **THEN** init SHALL replace it only after candidate validation succeeds

#### Scenario: Missing parent directories are created
- **WHEN** target parent directories do not exist
- **THEN** init SHALL create them before writing the validated target

#### Scenario: Parent file is rejected
- **WHEN** a parent path component is a file
- **THEN** init SHALL return a filesystem error and create no target beneath it

#### Scenario: Validation or write setup failure preserves state
- **WHEN** candidate validation, directory creation, temporary creation, or atomic move fails
- **THEN** a missing target SHALL remain absent, an existing target SHALL remain unchanged, and temporary files SHALL be removed best-effort

### Requirement: Init enables from-zero apply workflow
Agents SHALL be able to init a base JMX, use its emitted refs or a later read, extend it with apply, and validate the final JMX through one resolved local runtime. Apply SHALL still require an existing valid JMX input.

#### Scenario: Init then apply then validate
- **WHEN** an agent initializes a base, applies a runtime-resolved child under its Thread Group ref, and validates the result
- **THEN** the workflow SHALL succeed without profile flags

#### Scenario: Apply still requires existing input
- **WHEN** apply input is missing, empty, or invalid
- **THEN** apply SHALL fail without synthesizing a root document

### Requirement: j4a YAML operations produce real JMeter-consumable JMX
J4A SHALL produce JMX that the same resolved local runtime can SaveService-load after writing. Command acceptance proves persistence/load compatibility only; GUI opening and non-GUI execution are optional QA characterization and MUST NOT gate command success.

#### Scenario: Generated JMX reloads through the selected runtime
- **WHEN** init, set, or apply commits a candidate
- **THEN** the same local runtime SHALL have reloaded it without class, alias, XML, or persistence failure
- **AND** reloaded TestElement identity SHALL match the candidate projection used by the command

#### Scenario: Real GUI smoke is non-blocking characterization
- **WHEN** implementation QA opens representative generated JMX in the same real JMeter GUI
- **THEN** observed GUI success or failure SHALL be recorded as QA evidence
- **AND** it SHALL NOT change the SaveService-based command acceptance contract

#### Scenario: Non-GUI execution is not validation
- **WHEN** implementation QA optionally runs a representative plan with JMeter `-n -t`
- **THEN** runtime results SHALL be distinguished from persistence/load validation
- **AND** command success SHALL NOT require a load-test execution

#### Scenario: Missing plugin fails actionably
- **WHEN** SaveService cannot reload because the selected local runtime lacks a required plugin class
- **THEN** failure SHALL preserve the JMeter classpath cause and direct the user to configure that runtime

### Requirement: Local plugin property details preserve structured JMeter property types
Runtime component details SHALL describe observed scalar, collection, map, nested TestElement, universal `rows`, and opaque representations without claiming component admission or GUI-openability. Exact-target runtime evidence alone SHALL determine whether `rows` is available. Properties without a proven row projection SHALL use the generic graph or verified opaque representation whenever reconstructable. Only system-owned identity metadata or transient/runtime-only/non-persisted state is an accepted non-writable classification; a persisted user-editable representation gap SHALL remain visible and fail runtime conformance. No registered semantic adapter, named collection family, or component/property identity table SHALL alter representation or metadata.

#### Scenario: Runtime-proven rows report their representation
- **WHEN** exact-target discovery proves an ordered row property
- **THEN** metadata SHALL include `type: rows`, value shape, observed row type, and ordered runtime-proven field descriptors
- **AND** it SHALL NOT include a named collection family or adapter identity

#### Scenario: Generic structured property remains writable
- **WHEN** detail discovery encounters a persisted collection, map, or nested value supported by a generic codec but not by `rows`
- **THEN** a writable recursive representation SHALL be advertised
- **AND** set and apply SHALL accept it without materializing caller-selected arbitrary Java classes

#### Scenario: Scalar plugin property remains patchable
- **WHEN** runtime observation identifies a supported scalar property
- **THEN** details SHALL expose its representation type and scalar-segment property address for set/apply

#### Scenario: Non-reconstructable or system-owned property remains explicit
- **WHEN** a property is system-owned, transient, runtime-only, non-persisted, or cannot be safely reconstructed or replaced opaquely
- **THEN** details SHALL expose it as non-writable with an actionable reason and source
- **AND** a persisted user-editable instance of this state SHALL fail the runtime conformance gate

#### Scenario: Unknown plugin needs no registration
- **WHEN** an unregistered plugin property proves a scalar, generic recursive, runtime-proven rows, or opaque representation
- **THEN** details and mutation SHALL use that representation without a production adapter or identity entry

### Requirement: CLI exposes copyable structured collection values
CLI details and read SHALL emit the single universal `type: rows` representation for every exact target whose runtime evidence proves an ordered row contract. Read value objects SHALL contain `row_type`, ordered field-descriptor `row_properties`, and `rows`; component details SHALL expose the same schema fields. Each field descriptor SHALL contain `name`, scalar `type`, `required`, and a `default` exactly when non-required. CLI set and apply SHALL accept that emitted representation unchanged for equivalent target evidence, preserve row order, defaults, and fields through SaveService reload, and fail atomically on mismatch. Named collection family types and registered representation metadata SHALL NOT be emitted or accepted.

#### Scenario: Components details describes runtime-proven rows
- **WHEN** details are requested for a property whose exact materialized target proves ordered rows
- **THEN** output SHALL include `type: rows`, value shape, row type, ordered field descriptors with required/default state, and current rows as applicable

#### Scenario: Empty read output can be reapplied
- **WHEN** read emits a `type: rows` value with schema assertions and `rows: []`
- **THEN** the same value SHALL be accepted by set or apply for equivalent target evidence

#### Scenario: Rows submitted to a non-row property fail
- **WHEN** `type: rows` is submitted to a property that does not prove an ordered row contract
- **THEN** the command SHALL fail before commit and preserve target bytes

#### Scenario: CLI rejects named collection types
- **WHEN** a removed registered or family-specific collection type is submitted
- **THEN** the command SHALL fail before writing and direct the caller to the target-emitted universal or generic representation

### Requirement: CLI uses only a resolved local JMeter runtime
Every command that loads, validates, discovers, materializes, creates, or writes JMX SHALL use one resolved local JMeter home. Resolution order SHALL be explicit `--jmeter-home`, `JMX_AGENT_JMETER_HOME`, then `JMETER_HOME`. The CLI SHALL reject removed `--profile` and `--validation-mode` options and pure, bundled, or auto syntax as usage errors without selecting a compatibility path.

#### Scenario: Environment is the default configuration
- **WHEN** no explicit JMeter home is supplied and an environment variable resolves a valid installation
- **THEN** the command SHALL use `JMX_AGENT_JMETER_HOME` before `JMETER_HOME`

#### Scenario: Explicit home takes precedence
- **WHEN** `--jmeter-home` and environment variables are present
- **THEN** the command SHALL use the explicit home

#### Scenario: Missing home fails actionably
- **WHEN** no valid local JMeter home resolves
- **THEN** the command SHALL fail before JMeter work begins
- **AND** guidance SHALL recommend `JMX_AGENT_JMETER_HOME` or `JMETER_HOME` and mention optional `--jmeter-home`

#### Scenario: Removed profile options do not alias
- **WHEN** a caller supplies `--profile`, `--validation-mode`, pure, bundled, or auto profile syntax
- **THEN** the CLI SHALL return usage exit 2
- **AND** it SHALL NOT select an alternate execution path

### Requirement: Public component categories are stable projections
The CLI SHALL project JMeter's internal menu groups into ordered public categories. It SHALL always recognize and list, in order, `post-processor`, `assertion`, `listener`, `pre-processor`, `logic-controller`, `test-fragment`, `non-test-element`, `sampler`, `thread-group`, `timer`, and `config-element`, including categories with zero components. The exact internal mappings SHALL be `menu_post_processors` to `post-processor`, `menu_assertions` to `assertion`, `menu_listener` to `listener`, `menu_pre_processors` to `pre-processor`, `menu_logic_controller` to `logic-controller`, `menu_fragments` to `test-fragment`, `menu_non_test_elements` to `non-test-element`, `menu_generative_controller` to `sampler`, `menu_threads` to `thread-group`, `menu_timer` to `timer`, and `menu_config_element` to `config-element`.

#### Scenario: Ordered categories include known empty entries
- **WHEN** `categories ls` runs against a runtime with no visible component in one or more core categories
- **THEN** all eleven public categories SHALL appear in the specified order
- **AND** each SHALL contain `category`, a readable `label`, and `component_count`, including zero

#### Scenario: Unmapped visible entries aggregate locally
- **WHEN** a visible runtime component belongs to an internal group outside the eleven mappings
- **THEN** it SHALL remain discoverable under a final `local` category labeled `Local Components`
- **AND** `local` SHALL appear only when at least one such entry is visible

#### Scenario: Raw compatibility aliases are input only
- **WHEN** `components --category` receives one of the eleven mapped raw keys during the one-release compatibility window
- **THEN** it SHALL filter the corresponding public category
- **AND** output SHALL contain only the public id

#### Scenario: Arbitrary raw groups are not aliases
- **WHEN** category input is an unmapped raw plugin group
- **THEN** it SHALL be unknown rather than an alias for `local`

### Requirement: Catalog labels and generated names are locale-safe and readable
The selected JMeter locale SHALL be initialized before SaveService, MenuFactory, or component access. Category and component labels SHALL preserve nonblank selected-locale runtime text and SHALL use deterministic readable fallbacks when runtime text is blank or an unresolved exact `[res_key=<key>]` marker. No returned label or materialized default name SHALL be blank or match `^\\[res_key=.*]$`.

#### Scenario: Valid localized label is preserved
- **WHEN** MenuInfo returns a nonblank label that is not an exact resource marker
- **THEN** the CLI SHALL return that selected-locale text unchanged

#### Scenario: Marker is resolved or humanized
- **WHEN** MenuInfo returns `[res_key=<key>]`
- **THEN** the runtime SHALL resolve `<key>` once through JMeter
- **AND** if unresolved it SHALL strip a terminal `_title`, split underscore, hyphen, and camel-case boundaries, and title-case with `Locale.ROOT`

#### Scenario: Blank label uses class fallback
- **WHEN** the runtime label is blank
- **THEN** the label SHALL humanize the menu class simple name after stripping a usable terminal `Gui`, `GUI`, or `Panel`

#### Scenario: Generated default names are repaired
- **WHEN** primary GUI creation or direct fallback yields a blank or resource-marker name and the patch supplies no explicit name
- **THEN** the candidate SHALL use the resolved readable component label before SaveService validation
- **AND** a nonblank non-marker JMeter-provided name SHALL remain unchanged

#### Scenario: Explicit patch name wins
- **WHEN** an add patch supplies `TestElement.name`
- **THEN** that exact explicit name SHALL overlay the generated default

### Requirement: Unknown component categories are typed usage errors
The CLI SHALL validate category input against the explicit public catalog before filtering runtime definitions. Unknown input SHALL produce code `USAGE_ERROR`, category `usage`, exit 2, and action `rerun categories ls to list valid category ids, then retry components --category.` without branching on message text.

#### Scenario: Known empty category succeeds
- **WHEN** `components --category <known-id>` has no matching runtime definitions
- **THEN** the CLI SHALL succeed with that public category and `components: []`

#### Scenario: Unknown category guides recovery
- **WHEN** category input is neither a public id nor one of the eleven approved aliases
- **THEN** the CLI SHALL fail with code `USAGE_ERROR`, category `usage`, and exit 2
- **AND** the action SHALL be exactly `rerun categories ls to list valid category ids, then retry components --category.`

### Requirement: CLI structured catalog data matches YAML
Successful `components` and `categories` commands SHALL expose structured data parsed from the emitted friendly YAML. The structured object SHALL use the same public category ids, labels, counts, components, and leak-free detail fields as the text output.

#### Scenario: Categories structured data equals YAML
- **WHEN** `categories ls` succeeds
- **THEN** every YAML category row SHALL have an equal structured-data row in the same order

#### Scenario: Components structured data equals YAML
- **WHEN** component list or detail discovery succeeds
- **THEN** the structured data SHALL equal the YAML data model
- **AND** neither representation SHALL expose internal raw groups or resource markers

### Requirement: J4A validates protocol and write safety only
J4A SHALL validate patch grammar, required fields, value representations, locator and anchor existence, output authorization, temporary-file ownership, and atomic-write invariants. J4A MUST NOT define or enforce component addability, component allowlists or blacklists, component-specific defaults, parent compatibility, or other JMeter semantic legality rules.

#### Scenario: Protocol error is rejected by J4A
- **WHEN** a patch is malformed or refers to a missing locator
- **THEN** J4A SHALL reject it before mutation with an actionable protocol error

#### Scenario: Component semantics are delegated
- **WHEN** a component identity resolves in the local runtime and a patch requests its addition
- **THEN** J4A SHALL delegate creation, parent compatibility, saving, and loading decisions to that runtime
- **AND** it SHALL NOT reject the component using catalog metadata or a J4A-maintained semantic rule

### Requirement: JMeter failures remain source-faithful
When runtime discovery, placement, materialization, saving, or loading fails, J4A SHALL preserve the JMeter exception type, message, and bounded cause chain where available inside the existing CLI/MCP error envelope. J4A MAY add operation phase, component, parent, JMeter home, and affected-file context, but MUST NOT reclassify the nested failure as `unsupported`, `not addable`, or a J4A semantic error.

#### Scenario: Runtime failure reports phase and cause
- **WHEN** JMeter fails while materializing or round-tripping a component
- **THEN** the result SHALL identify the failing phase and preserve the runtime cause
- **AND** recovery guidance SHALL address the actual classpath, compatibility, placement, or persistence failure

#### Scenario: Error envelope retains stable exit classes
- **WHEN** an operation fails
- **THEN** usage and schema failures SHALL retain exit class 2
- **AND** locator, identity resolution, JMeter placement rejection, materialization rejection, and candidate preservation failures SHALL use exit class 3
- **AND** invalid-home, reflection, classpath, worker, timeout, filesystem, and atomic-write failures SHALL use exit class 4

#### Scenario: Non-exception decisions have explicit codes
- **WHEN** `MenuInfo.getEnabled(ActionNames.ADD)` returns false
- **THEN** J4A SHALL report `JMETER_ADD_DISABLED` with JMeter decision context
- **AND** when `MenuFactory.canAddTo` returns false J4A SHALL report `JMETER_PLACEMENT_REJECTED`
- **AND** reflective registry incompatibility SHALL report `JMETER_MENU_REGISTRY_INCOMPATIBLE`
- **AND** an unresolved component id SHALL report `COMPONENT_IDENTITY_NOT_FOUND`

### Requirement: CLI remains standalone and local-only
Packaged CLI commands SHALL remain one-shot invocations that do not require an MCP server or daemon. A CLI worker SHALL exit before command completion, and ordinary wrapper commands SHALL retain existing installed-runtime and no-hidden-download behavior.

#### Scenario: Standalone command closes its worker
- **WHEN** a packaged read, validate, components, apply, init, or set command completes
- **THEN** its isolated local worker SHALL be terminated before the CLI exits

### Requirement: Structured property metadata describes serialization capability
Component property details SHALL report runtime-observed property address, concrete JMeter value shape, scalar, generic recursive, universal `rows`, or opaque protocol `type`, writable state, and representation source. Records SHALL NOT classify component legality, addability, or GUI-openability. A `rows` schema SHALL be emitted only from exact-target runtime evidence. Named family-specific collection types SHALL NOT be emitted. An opaque or non-writable value SHALL describe the precise serialization or safety boundary and MUST NOT use `unsupported` as a caller-submittable patch type.

#### Scenario: Writable values expose protocol types
- **WHEN** runtime property discovery observes a scalar, collection, map, nested TestElement, runtime-proven row collection, or opaque value
- **THEN** details SHALL emit the matching reusable representation and `writable: true`

#### Scenario: Opaque details expose an acquisition contract
- **WHEN** a persisted value can be replaced only as a bound opaque value
- **THEN** ordinary details SHALL state `type: opaque`, `format: jmeter-save-element-xml-v1`, `value_source: focused-read`, and `write_mode: replace-whole`
- **AND** it SHALL NOT publish a target-independent digest, payload, runtime fingerprint, or fabricated value

#### Scenario: Focused read supplies a bound opaque value
- **WHEN** focused read observes the opaque property on an exact target
- **THEN** it SHALL emit `base_digest`, `outer_property_class`, `runtime_fingerprint`, caller-editable `payload`, and the replace-whole constraint
- **AND** set/apply SHALL enforce optimistic concurrency and return a server-computed digest after a successful non-no-op replacement

#### Scenario: Unrepresentable property is a protocol limitation
- **WHEN** runtime discovery observes a system-owned, transient, runtime-only, non-persisted, or non-reconstructable value
- **THEN** diagnostics SHALL emit `writable: false`, representation source, and an actionable reason
- **AND** a persisted user-editable representation gap SHALL be reported as a conformance defect

#### Scenario: Direct fallback discovers only observable properties
- **WHEN** details uses a directly constructed TestElement fallback
- **THEN** it SHALL derive graph members only from that instance and runtime JMeter schema or BeanInfo sources that initialize successfully
- **AND** failure to produce requested details SHALL fail the details command rather than add a component-level failure field

#### Scenario: Ordinary read omits system-owned identity fields
- **WHEN** read emits `--properties all` for a TestElement containing `TestElement.gui_class` and `TestElement.test_class`
- **THEN** those system-owned identity properties SHALL be omitted from the ordinary property document
- **AND** diagnostics that include them SHALL report `ownership: system`, `writable: false`, and an identity-management reason

#### Scenario: Supported scalar and structured values expose protocol types
- **WHEN** runtime property discovery observes a scalar, collection, map, nested TestElement, or adapter-refined value
- **THEN** details SHALL emit the matching reusable representation and `writable: true`

#### Scenario: Opaque replace-whole value exposes its boundary
- **WHEN** a persisted value cannot be decomposed but can be preserved as a bound opaque value
- **THEN** details/read SHALL emit `type: opaque`, `format: jmeter-save-element-xml-v1`, `base_digest`, `outer_property_class`, `runtime_fingerprint`, caller-editable `payload`, and the replace-whole constraint
- **AND** set/apply SHALL treat `base_digest` as optimistic concurrency input and SHALL return a server-computed digest after a successful non-no-op replacement

### Requirement: Apply validates YAML property representation before mutation
Apply SHALL resolve each scalar-segment property address in the exact loaded target or newly materialized candidate graph and reject any value that cannot be converted to that node's observed JMeter value shape and exact protocol type. All operations SHALL use the same normalization and target-bound validation rules. Mutations SHALL affect only an isolated candidate until successful atomic commit. This is protocol representation validation and MUST NOT decide whether the component itself is legal or addable.

#### Scenario: Scalar cannot represent Arguments
- **WHEN** a patch supplies a scalar for a collection, map, nested, opaque, or runtime-proven structured row node
- **THEN** apply SHALL fail before target mutation with a property-representation error

#### Scenario: Wrong scalar type is rejected
- **WHEN** a patch supplies integer `100` for a node observed as `type: string`
- **THEN** apply SHALL fail before mutation without coercing the value

#### Scenario: Exact read document can be submitted without inference
- **GIVEN** read emitted a writable recursive document containing `property`, `type`, presence, value, and required runtime-class or opaque binding metadata
- **WHEN** set or apply receives that document for the same target graph and runtime
- **THEN** it SHALL decode the document without any caller-supplied Java class or alternate path syntax

#### Scenario: Unknown direct address cannot create a property
- **WHEN** a patch supplies a direct top-level or nested scalar-segment address absent from the candidate graph
- **THEN** apply SHALL fail before mutation with one property-address category

#### Scenario: Unsupported shape cannot be patched
- **WHEN** a patch supplies a value for a property reported as `writable: false`
- **THEN** apply SHALL fail before target mutation and direct the caller to omit that property

### Requirement: Apply add materializes through local JMeter runtime
The `apply <file.jmx> --patch <file|-> ... [--jmeter-home <path>]` command SHALL resolve component identity from the local JMeter menu registry and dispatch creation by its recorded registration kind and `menuClassName`. It SHALL prefer the corresponding JMeter GUI/TestBean creation path and, if that path fails after the menu class was proven to be a concrete TestElement, SHALL directly instantiate that exact class without component-specific defaults. The runtime SHALL decide parent compatibility through `MenuFactory.canAddTo`, and the candidate SHALL be committed only after SaveService saves, reloads, and preserves each added node by the deterministic projection below.

#### Scenario: JMeter creation path supplies defaults
- **WHEN** the runtime GUI/TestBean path creates the requested component
- **THEN** apply SHALL use that TestElement and its JMeter-provided defaults and identity

#### Scenario: Discovered TestElement falls back to direct construction
- **WHEN** a `METADATA_TEST_ELEMENT` or `TEST_BEAN` registration resolves a concrete non-abstract TestElement with an accessible no-argument constructor but primary JMeter creation fails
- **THEN** apply SHALL directly instantiate that class in the same worker
- **AND** it SHALL set only `TEST_CLASS` to the concrete class name, `GUI_CLASS` to JMeter's selected GUI identity, `NAME` to the MenuInfo label unless patched, `ENABLED` to true unless patched, and patch-supplied properties
- **AND** it SHALL NOT inject component-specific guessed defaults

#### Scenario: Fallback unavailable preserves original failure
- **WHEN** primary creation fails for a GUI registration, abstract class, non-TestElement class, inaccessible constructor, or identity without a JMeter-derived GUI class
- **THEN** apply SHALL return the original JMeter creation failure with context
- **AND** it SHALL NOT guess another class or GUI identity

#### Scenario: Metadata-backed JSR223 remains discoverable
- **WHEN** metadata-backed JSR223 discovery succeeds but later TestBeanGUI materialization fails
- **THEN** the catalog entry SHALL remain resolved
- **AND** apply SHALL attempt the concrete JSR223 TestElement fallback

#### Scenario: JMeter decides parent compatibility
- **WHEN** apply has materialized a candidate and resolved its parent
- **THEN** it SHALL call the local runtime's `MenuFactory.canAddTo` with JMeter tree nodes representing that placement
- **AND** a false result SHALL be reported as the JMeter runtime's placement rejection

#### Scenario: Complete candidate round-trip gates commit
- **WHEN** mutation produces a candidate tree
- **THEN** the same local runtime SHALL save it to a temporary JMX, reload it, and confirm the added node remains present
- **AND** after all patch operations apply SHALL record each added node's final zero-based structural address and expected concrete class, `TEST_CLASS`, `GUI_CLASS`, `NAME`, `ENABLED`, and patch-written JMeter properties
- **AND** after reload it SHALL resolve each final address and compare that projection while ignoring unspecified defaults and transient properties
- **AND** apply SHALL atomically commit only after every added node matches
- **AND** failure SHALL preserve the requested target bytes

#### Scenario: Dry run executes the real pipeline
- **WHEN** apply runs in dry-run mode
- **THEN** it SHALL execute the same discovery, materialization, placement, mutation, save, reload, and preservation checks
- **AND** it SHALL not commit any target file

### Requirement: Validate command uses local JMeter SaveService
The `validate <file.jmx> [--jmeter-home <path>]` command SHALL validate only through the resolved local JMeter runtime. It SHALL load the input through JMeter SaveService, save it to a temporary file, and reload that file through the same runtime without modifying the input. It SHALL NOT run a J4A semantic validator or use GUI openability as a blocking gate.

#### Scenario: Local SaveService round-trip passes
- **WHEN** the resolved JMeter runtime loads, saves, and reloads the input successfully
- **THEN** validate SHALL report success
- **AND** the original file SHALL remain unchanged

#### Scenario: Runtime load or save failure is preserved
- **WHEN** JMeter fails to load, save, or reload the file
- **THEN** validate SHALL fail with the source-faithful JMeter error and phase context

#### Scenario: GUI lifecycle does not override persistence success
- **WHEN** GUI construction or configuration would fail but SaveService load/save/reload succeeds
- **THEN** validate SHALL report persistence validation success
- **AND** GUI failure MAY appear only as non-blocking diagnostic context

### Requirement: Local JMeter loading is isolated
The system SHALL run local JMeter loading, validation, menu discovery, materialization, placement checks, creation, and writing inside an isolated worker JVM whose classpath and configuration come from the resolved JMeter home. Standalone CLI commands SHALL close their worker before command exit and remove owned temporary files.

#### Scenario: One command uses one local runtime throughout
- **WHEN** read, validate, components, apply, init, or set runs
- **THEN** all JMeter work for that command SHALL use the same resolved home and worker
- **AND** no bundled or pure loader, validator, catalog, or materializer SHALL participate

#### Scenario: Local worker honors JMeter classpath configuration
- **WHEN** the resolved home declares `search_paths`, `user.classpath`, or `plugin_dependency_paths`
- **THEN** the worker SHALL initialize those paths with JMeter-compatible semantics before first accessing MenuFactory or SaveService

#### Scenario: CLI worker lifecycle remains bounded
- **WHEN** a standalone CLI command finishes
- **THEN** its worker process tree SHALL exit
- **AND** owned candidate and validation files SHALL be removed

### Requirement: Apply add overlays every graph-representable property
Apply add SHALL materialize the runtime component before any property validation, observe the candidate's complete property graph and runtime-proven structured rows, and overlay submitted property representations before SaveService verification. Catalog descriptors, component/property registrations, and the absence of a structured row projection MUST NOT independently authorize or reject an add property.

#### Scenario: HTTP sampler is added with runtime rows
- **WHEN** one add operation creates an HTTP sampler and supplies its emitted `type: rows` Arguments value
- **THEN** apply SHALL write the collection in the same transaction
- **AND** SaveService reload and read SHALL return the requested ordered rows

#### Scenario: Empty add and populated add use one capability model
- **WHEN** the same runtime component is added once with no properties and once with graph-representable properties
- **THEN** both operations SHALL use the same materialization path
- **AND** only exact-candidate graph resolution and overlays SHALL differ

#### Scenario: Response Assertion is added with test strings
- **WHEN** one add operation creates `org.apache.jmeter.assertions.gui.AssertionGui` and supplies ordered values for `Asserion.test_strings`
- **THEN** apply SHALL write the collection in the same transaction
- **AND** SaveService reload and read SHALL return the requested ordered values

### Requirement: Components exposes compact authoring details and explicit diagnostics
The `components` command SHALL support list mode, single-component authoring detail, category authoring detail, and single-component diagnostics. A selected `component` or compatibility `kind` SHALL produce ordinary authoring detail; `--diagnostics true` SHALL require exactly one selected component and retain the complete diagnostic projection. A category MAY be combined with `--details true` to return bounded ordinary authoring details for that category, but MUST NOT be combined with component, kind, or diagnostics. Component plus kind SHALL be rejected before discovery.

Category detail pages SHALL use ascending exact component FQCN order, default limit 20, maximum limit 50, and an opaque continuation cursor bound to runtime fingerprint, canonical category, projection, limit, and last emitted component. One component detail failure SHALL produce a bounded per-component failure record and `partial: true` without aborting successful rows; diagnostic causes and stack traces SHALL not enter category pages.

#### Scenario: Category details are retrieved in one call
- **WHEN** a caller supplies a known category with `--details true`
- **THEN** the result SHALL contain up to the requested bounded number of ordinary component authoring details
- **AND** a continuation cursor SHALL be returned when more components remain

#### Scenario: Category detail failure is isolated
- **WHEN** one component cannot produce ordinary details
- **THEN** its page entry SHALL contain component identity, stable error code, phase, and a message truncated to 512 characters
- **AND** other successful entries SHALL remain present with `partial: true`

#### Scenario: Diagnostics remains focused
- **WHEN** category is combined with diagnostics or a cursor is reused with different bound inputs
- **THEN** the command SHALL fail before discovery with an actionable usage error

#### Scenario: Ordinary detail is writable and copy-oriented
- **WHEN** a caller selects one registered component without `--diagnostics`
- **THEN** the result SHALL contain only writable properties with the ordinary key set
- **AND** a proven `value_template` SHALL be included when applicable
- **AND** it SHALL not leak diagnostic or non-writable property fields

#### Scenario: Diagnostics is opt-in and complete
- **WHEN** a caller selects one component with `--diagnostics true`
- **THEN** the result SHALL retain the complete capability projection and non-writable properties
- **AND** it SHALL include `value_template` where the ordinary projection would include it

#### Scenario: Invalid detail modes fail before discovery
- **WHEN** a caller supplies details or diagnostics without a selected identity, supplies either marker as `false`, combines category with a detail marker, or supplies both component and kind
- **THEN** the command SHALL fail with an actionable usage error before component discovery

### Requirement: Set uses the canonical copy-safe write-mode contract
`set` SHALL use the same canonical/copy-safe write-mode resolution as `apply`. It SHALL require exactly one of copy mode `--out <file>` or in-place `--override`; reject `--out` with `--override`; reject `--override` with `--force-out`; reject `--force-out` without `--out`; and reject copy destinations that resolve to the input by normalized, canonical, or symlink-alias identity. `--force-out` SHALL permit replacement only of an existing distinct copy destination and SHALL never authorize overwriting the input path. The command SHALL preserve candidate validation and safe write semantics.

#### Scenario: Conflicting set modes are rejected before writing
- **WHEN** set receives `out+override`, `override+forceOut`, or `forceOut` without `out`
- **THEN** it SHALL return an actionable usage error
- **AND** it SHALL not create or replace a target

#### Scenario: Copy aliases cannot bypass input protection
- **WHEN** an `out` path is a canonical, normalized, or symlink alias of the input path
- **THEN** set SHALL reject the copy mode and direct the caller to the explicit in-place mode
- **AND** `forceOut` SHALL not authorize the input overwrite

### Requirement: Agent-facing CLI constraints remain bounded and local
The implementation SHALL remain Java 8 compatible; use the selected local JMeter runtime in isolated execution; preserve candidate validation before commit; and preserve safe write semantics with no automatic retry or mutation replay. Public documentation and implementation verification SHALL use focused tests and owned-copy live JMX QA rather than requiring `./gradlew test`; SHALL not mutate an original fixture; and SHALL not add raw JMX editing, plugin installation, load-test execution, or a transport redesign.

#### Scenario: Contract verification keeps the original fixture intact
- **WHEN** a focused live CLI or MCP write scenario is executed for this change
- **THEN** it SHALL operate only on an owned copy of the fixture
- **AND** it SHALL prove the write through the existing candidate/save/reload path without replaying a failed mutation

### Requirement: CLI read adds a bounded writable projection
The CLI `read --properties` input SHALL accept exactly `key`, `all`, or `writable`. `none` and every other value SHALL be rejected as a usage error before local-runtime work begins. Existing defaults and the meanings of `key` and `all` SHALL remain unchanged.

For `writable`, the result SHALL retain, in existing order, exactly the property records whose current property-graph capability is writable. Each retained record SHALL be the same serialized reusable property document that `all` would emit for that record, including scalar, recursive collection, map, element, universal `rows`, and opaque documents. Tree shape, focus, ref, source, disabled, depth, path, ordering, and existing rendered property fields SHALL remain unchanged.

#### Scenario: Writable is a CLI-only accepted third projection
- **WHEN** a caller runs `read` with `--properties writable`
- **THEN** the command SHALL accept it and render only the ordered writable property records
- **AND** `key` and `all` SHALL retain their existing semantics while `none` or another value fails before worker execution

#### Scenario: Writable records remain copyable all-mode records
- **WHEN** a component has writable and non-writable property-graph records
- **THEN** `writable` SHALL equal the ordered subset of `all` selected by current graph capability
- **AND** every retained document SHALL be byte-equivalent to its `all` counterpart and directly reusable by set or apply

#### Scenario: Writable output stays bounded and compatible
- **WHEN** equivalent `all` and `writable` reads render the same input and options
- **THEN** serialized `writable` bytes SHALL not exceed `all`
- **AND** existing tree/ref/source behavior SHALL not change

### Requirement: Selected component diagnostics state metadata-source availability only
Selected-component diagnostics SHALL add exactly one component-level metadata-source status with value `runtime-proven` or `runtime-metadata-unavailable`. It SHALL describe only whether all applicable stable runtime metadata sources, including supported version-pinned GUI semantic observation, were successfully queried for that selected component; an empty successful metadata result is `runtime-proven`.

The status SHALL not mean that every GUI field was discovered, SHALL not name absent GUI fields, and SHALL not synthesize property rows without runtime descriptor evidence. Ordinary selected detail, component list/category output, existing diagnostic property fields, per-property writable/reason/ownership facts, labels, categories, and exact component identity SHALL remain compatible except for property rows newly proven by an applicable runtime metadata source.

#### Scenario: Successful stable metadata query is proven even when empty
- **WHEN** diagnostics selects a component and every applicable stable runtime metadata source is queried successfully, including a query returning no metadata rows
- **THEN** diagnostics SHALL emit `runtime-proven`
- **AND** its property rows SHALL contain only values proven by the applicable runtime sources

#### Scenario: Stable metadata source is unavailable without a GUI-completeness claim
- **WHEN** an applicable stable runtime metadata source cannot be queried, is ambiguous, or exceeds its performance budget
- **THEN** diagnostics SHALL emit `runtime-metadata-unavailable` while retaining the existing successful component response and known property rows
- **AND** the output and CLI help SHALL state that this is metadata-source availability, not a claim that J4A enumerates all JMeter GUI fields

#### Scenario: Ordinary component surfaces remain compatible
- **WHEN** callers use ordinary selected details, list, or category output without diagnostics
- **THEN** no metadata-source status or reflection implementation detail SHALL appear
- **AND** newly proven writable property rows MAY appear using the existing property-detail shape

### Requirement: CLI guidance and verification retain local bounded safety
CLI help and installed agent guidance SHALL name the exact CLI read enum `key|all|writable`, the exact set type enum, the sole scalar-segment-array property syntax, the universal `type: rows` example, runtime-proven append/insert/remove examples, generic recursive collection envelopes, and copyable recovery for invalid collection shape, same-path write, and existing target. Guidance SHALL state that metadata-source availability is not GUI completeness.

The changed CLI contract SHALL remain Java 8 compatible, use the selected isolated local JMeter runtime, preserve existing input bounds and candidate/save/reload write safety, and require no original-fixture mutation. It SHALL not add effective-configuration input, configuration-scope traversal, per-property provenance, HTTP-specific component/property allowlists, J4A-owned merge precedence, named row-family mappings, sampler execution, raw XML editing, GUI interaction, plugin installation, load execution, unknown-property acceptance, weakened fail-closed mutation behavior, automatic retry, or mutation replay.

#### Scenario: CLI guidance is bounded and copyable
- **WHEN** a caller inspects packaged CLI help or installed agent guidance
- **THEN** it SHALL show one array-address example, one universal rows example, incremental row-operation examples, and shortest valid recovery templates
- **AND** it SHALL not teach canonical strings, typed-object segments, or address output selection

#### Scenario: Focused proof uses owned copies without a full suite or load execution
- **WHEN** this contract is verified
- **THEN** focused tests and packaged CLI QA SHALL use owned fixture copies and a selected local JMeter runtime
- **AND** verification SHALL not require bare `./gradlew test` or load-test/sampler execution

### Requirement: Existing init targets return explicit recovery
When init targets an existing file without force, it SHALL return stable code `OUTPUT_FILE_EXISTS`, preserve the file, and emit a `recovery` object containing exactly `choices`. `choices` SHALL contain exactly two ordered records. The first SHALL be `{action: overwrite, command: init, argv: [...]}` whose `argv` preserves the original CLI init arguments and adds `--force-out` exactly once. The second SHALL be `{action: choose-output, command: init, argv: [...]}` whose positional output is exactly `<different-output.jmx>`, preserves the other original arguments, and omits `--force-out`. Human guidance SHALL name `--force-out`; the recovery object is advisory and MUST NOT execute, retry, overwrite, or replay init.

#### Scenario: Existing CLI target teaches force-out
- **WHEN** init targets an existing file without `--force-out`
- **THEN** the error message SHALL name `--force-out`
- **AND** `recovery.choices` SHALL contain the exact overwrite and choose-output records without executing either

### Requirement: Runtime-proven row overlays preserve JMeter value shape
Apply, set, add overlays, append, insert, remove, and read SHALL support the single `type: rows` representation only when the exact target or newly materialized candidate proves an ordered row contract. The value SHALL be either a list of rows or an object containing required `rows` and optional assertion fields `row_type` and descriptor-based `row_properties`; both shapes SHALL be accepted consistently in every applicable mutation context. Each row SHALL reject unknown or incorrectly typed fields. Omitted fields SHALL be accepted only when the corresponding proven descriptor has `required: false` and supplies `default`. Candidate acceptance SHALL depend on JMeter SaveService preservation, not GUI-openability, a registered adapter, or a named collection family.

#### Scenario: Add writes Arguments rows in one transaction
- **WHEN** one add operation creates an HTTP component and supplies the runtime-emitted `type: rows` value for its Arguments property
- **THEN** the rows SHALL be applied to the materialized candidate before SaveService verification
- **AND** no runtime descriptor SHALL be required before materialization

#### Scenario: Read emits reusable universal rows
- **WHEN** read focuses any property whose exact target proves an ordered row contract
- **THEN** it SHALL emit `type: rows`, `row_type`, ordered descriptor-based `row_properties`, and `rows`
- **AND** the emitted value SHALL be accepted unchanged by add, set, and apply set for equivalent target evidence

### Requirement: CLI apply supports runtime-proven row collection mutations
Apply SHALL accept `append`, `insert`, and `remove` operation cards for properties currently exposed as `type: rows`. Each card SHALL contain a target `ref` and scalar-segment-array `property`; append and insert SHALL contain one `row`, insert and remove SHALL contain a non-negative `index`, and unknown or inapplicable fields SHALL be rejected before worker execution. These operations SHALL share the exact-target schema, isolated candidate, SaveService verification, dry-run, output, receipt, and atomicity contracts used by existing apply mutations.

#### Scenario: Apply appends the first Header row
- **WHEN** a patch appends a valid row to an empty runtime-proven Header collection
- **THEN** dry run and committed apply SHALL accept the same patch
- **AND** committed reload and read SHALL return the new row

#### Scenario: Incremental operation rejects generic collection
- **WHEN** append, insert, or remove targets a property not exposed as `type: rows`
- **THEN** apply SHALL fail before mutation with the observed property type and actionable recovery
- **AND** source and destination bytes SHALL remain unchanged

### Requirement: CLI command execution remains windowless
CLI component discovery, read, validate, set, and apply SHALL NOT start the JMeter desktop application, create or display top-level windows, or perform GUI-openability lifecycle validation. Bounded semantic metadata observation MAY instantiate non-visible component objects inside the isolated worker only when required for lazy metadata discovery.

#### Scenario: Normal write uses no GUI lifecycle gate
- **WHEN** CLI set or apply validates and commits a candidate
- **THEN** success SHALL continue to depend on property, transaction, and SaveService verification
- **AND** it SHALL not depend on opening or configuring the candidate in a real GUI

### Requirement: J4A releases pass real JMeter GUI compatibility testing
A J4A release supporting JMeter 5.6.3 SHALL perform documented exploratory and regression testing with the real JMeter 5.6.3 desktop GUI against generated JMX artifacts. A reproducible GUI-open failure caused by J4A-generated persisted structure or concrete value classes SHALL block the release, while individual CLI and MCP commands SHALL remain free of that GUI execution cost.

#### Scenario: Release candidate opens generated plans in real GUI
- **WHEN** a J4A release candidate is evaluated
- **THEN** representative generated plans, including HTTP Request Defaults and HTTP arguments, SHALL be opened and exercised in the real JMeter 5.6.3 GUI
- **AND** any reproducible J4A structural compatibility failure SHALL block release approval
