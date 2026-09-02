# jmx-agent-mcp Specification

## Purpose
TBD - created by archiving change add-java-mcp-server-and-test-caching. Update Purpose after archive.
## Requirements

### Requirement: Java MCP server exposes stable j4a subcommands as tools
The system SHALL provide a Java-based MCP server that exposes the stable j4a subcommands as MCP tools in a long-lived JVM process. The public MCP tool names SHALL be `read`, `validate`, `components`, `categories`, `apply`, `init`, and `set` without a `j4a_` or other service prefix.

#### Scenario: MCP server lists subcommand-named tools
- **WHEN** an MCP client initializes the j4a MCP server and lists tools
- **THEN** the returned tool names SHALL include `read`, `validate`, `components`, `categories`, `apply`, `init`, and `set`
- **AND** the returned tool names SHALL NOT include prefixed aliases such as `j4a_read`, `j4a_validate`, or `j4a_probe_addability`

#### Scenario: Internal probes are not public tools
- **WHEN** an MCP client lists tools
- **THEN** internal worker operations for probing addability, discovery implementation details, lifecycle checks, or test characterization SHALL NOT appear as public tools unless a later accepted change makes them stable subcommands

### Requirement: MCP server reuses one JVM across tool calls
The MCP server SHALL execute tool calls inside the same server JVM for the lifetime of the MCP process. Tool execution SHALL NOT invoke the packaged wrapper or run `java -jar` per tool call.

#### Scenario: Consecutive tools do not cold-start Java
- **WHEN** an MCP client invokes `read` and then `validate` during one MCP server session
- **THEN** both tool calls SHALL execute through the already-running MCP server JVM
- **AND** the server SHALL NOT spawn a new j4a runtime JVM by invoking the wrapper or packaged `java -jar` command for each tool call

### Requirement: MCP tools preserve CLI command semantics
MCP tools SHALL invoke the same local-only behavior as corresponding CLI commands, including runtime-home precedence, public categories and labels, exact FQCN identity, generated readable names, universal target-bound `rows`, typed runtime errors, write safety, and persistence acceptance. Named collection families and registered representation metadata SHALL NOT be preserved. Component refs SHALL pass through the MCP session reference mechanism while mutation semantics remain shared with CLI.

#### Scenario: Read tool matches CLI read behavior
- **WHEN** MCP and CLI read the same input with equivalent arguments and local runtime
- **THEN** they SHALL return equivalent tree, source, property, and non-reference error semantics
- **AND** their distinct CLI snapshot and MCP session ref lifecycles SHALL remain governed by their existing capabilities

#### Scenario: Catalog tools match CLI discovery behavior
- **WHEN** MCP and CLI discover categories or components against the same runtime
- **THEN** they SHALL return equivalent public data, `type: rows` schemas, and typed errors

#### Scenario: Write-capable tools preserve write safety
- **WHEN** MCP calls set, apply, or init
- **THEN** output authorization, atomicity, candidate validation, and every recoverable command error SHALL match CLI
- **AND** MCP-specific ref lifecycle errors SHALL retain the MCP diagnostics defined by this capability

#### Scenario: Fatal worker loss preserves unknown-outcome no-replay semantics
- **WHEN** a reusable worker dies in the catastrophic window around an authorized filesystem commit
- **THEN** the call SHALL fail with unknown target outcome, evict the worker generation and refs, and SHALL NOT replay the mutation
- **AND** recovery SHALL direct the caller to inspect/read the target before deciding whether to retry

#### Scenario: No-replay proof has separate execution and protocol evidence
- **WHEN** no-replay behavior is verified
- **THEN** a focused worker-loss test SHALL use an execution-count sentinel to prove one accepted request performs no second mutation attempt and produces one response
- **AND** separate live packaged stdio QA SHALL prove initialize, tools/list, and the public MCP protocol journey without being used as execution-count evidence

### Requirement: MCP apply matches the stable CLI apply contract
The MCP `apply` tool SHALL accept the same strict versionless changes patch, including request-local aliases, and invoke the same local-runtime materialization, JMeter placement decision, SaveService candidate round-trip, write-mode, ordered placement, and no-target-mutation behavior as CLI. External component refs SHALL be resolved through the MCP session reference adapter before the shared mutation engine runs.

#### Scenario: MCP dry run performs real local-runtime validation
- **WHEN** MCP apply receives `dryRun=true`
- **THEN** it SHALL perform the full discovery, creation or fallback construction, `MenuFactory.canAddTo`, mutation, save, reload, and preservation pipeline
- **AND** it SHALL not commit a target file or durable ref state

#### Scenario: MCP runtime failures preserve JMeter causes
- **WHEN** discovery, placement, materialization, saving, or loading fails
- **THEN** MCP SHALL return an error with the same source-faithful JMeter cause and contextual phase as CLI
- **AND** it SHALL not report an addability status invented by MCP or J4A

#### Scenario: MCP retains existing patch and write semantics
- **WHEN** a patch uses ordered placement, request-local aliases, or a valid dry-run, in-place, or copy mode
- **THEN** MCP SHALL produce the same mutation, persistence, and write-safety guarantees as CLI
- **AND** its surviving component refs SHALL follow MCP session semantics rather than CLI snapshot semantics

### Requirement: MCP results are agent-readable and actionable
Every successful tool SHALL return useful text in `content` and typed diagnostics/data in `structuredContent`. Catalog success SHALL include parsed friendly data. Errors SHALL set `isError: true` and include stable `code`, `category`, and `action`; shared CLI/runtime failures SHALL preserve their existing meaning, while MCP session-ref lifecycle failures SHALL use the MCP-specific recovery defined by this capability. Runtime failures SHALL retain bounded source-faithful cause context, and usage errors SHALL provide decision-complete recovery.

#### Scenario: Successful tool returns useful content
- **WHEN** a tool succeeds
- **THEN** the result SHALL include agent-readable content
- **AND** `structuredContent` SHALL include success diagnostics and typed data appropriate to that tool

#### Scenario: Tool error guides recovery
- **WHEN** a call fails from invalid arguments, unknown category, missing runtime, invalid input, or missing plugin
- **THEN** the result SHALL be marked as an error
- **AND** stable diagnostics SHALL identify the error category and actionable next step without profile retry guidance

#### Scenario: Unavailable MCP ref guides a fresh read
- **WHEN** a supplied ref is unavailable in the current document registry
- **THEN** the result SHALL set `isError: true`, code `MCP_REF_NOT_FOUND`, category `usage`, and an action instructing the agent to read the same file with the same JMeter home and retry
- **AND** the message SHALL explain that the ref may be mistyped, deleted, refreshed after an external change, evicted, or lost after MCP/runtime restart
- **AND** it SHALL NOT claim to distinguish which lifecycle event occurred

### Requirement: MCP server lifecycle is explicit
The MCP server SHALL initialize through the MCP protocol over stdio and release its canonical per-home local-runtime pool and owned resources when the server process exits. It SHALL log diagnostics only to stderr or the configured sink and SHALL NOT write non-protocol output to stdout.

#### Scenario: Server speaks MCP on stdout
- **WHEN** the MCP server runs over stdio
- **THEN** stdout SHALL contain only MCP protocol messages
- **AND** diagnostics SHALL use stderr or the configured logging sink

#### Scenario: Server shutdown releases resources
- **WHEN** the MCP server exits normally or receives shutdown
- **THEN** all cached workers and owned temporary resources SHALL close or be removed best-effort according to `local-jmeter-runtime-pool`

### Requirement: npm wrapper starts the Java MCP server
The npm wrapper SHALL keep `install` as the explicit runtime installation command and SHALL provide an `mcp` command that starts the Java MCP server over stdio. If the cached runtime jar is missing when `mcp` is invoked, the wrapper SHALL run the existing install logic without force before starting the server.

#### Scenario: Explicit install command remains available
- **WHEN** a user runs `npx @zts-tester/j4a install`
- **THEN** the wrapper SHALL perform the existing runtime installation flow
- **AND** this command SHALL remain separate from ordinary CLI subcommand execution

#### Scenario: MCP command starts Java server over stdio
- **WHEN** a user runs `npx @zts-tester/j4a mcp` and the cached runtime jar exists
- **THEN** the wrapper SHALL launch the Java MCP server from that runtime jar
- **AND** the server SHALL communicate with the MCP client over stdio
- **AND** wrapper diagnostics SHALL NOT corrupt MCP protocol stdout

#### Scenario: MCP command installs missing runtime without force
- **WHEN** a user runs `npx @zts-tester/j4a mcp` and the cached runtime jar is missing
- **THEN** the wrapper SHALL run the existing install logic without `--force`
- **AND** after successful installation it SHALL launch the Java MCP server over stdio
- **AND** if installation fails it SHALL report an actionable installation error and SHALL NOT start a partial MCP server

### Requirement: MCP tools expose agent-oriented descriptions
The MCP server SHALL return a non-empty resource-backed description for every public tool. Every description SHALL state that success includes agent-readable `content` and typed `structuredContent`, while errors include actionable text plus structured diagnostics. Tool facts SHALL use local-only inputs and current schemas. Read and components descriptions SHALL explain the sole scalar-segment-array property address; components SHALL cover category detail paging; set SHALL describe `property` as that native array, `type` using the CLI enum including universal `rows`, and structured `value`; apply SHALL describe runtime-proven append, insert, and remove cards; init SHALL describe `forceOut` and existing-target recovery. No description SHALL advertise a canonical string, address-mode selector, typed-object segment, or registered/named row family.

#### Scenario: Listing public tool descriptions
- **WHEN** a client sends `tools/list`
- **THEN** all seven public tools SHALL have non-generic descriptions matching their actual schemas and result shapes
- **AND** no description SHALL teach removed address syntax, profiles, named row families, raw groups, admission fields, or resource markers

#### Scenario: Catalog descriptions teach discovery and paging
- **WHEN** a client inspects components or categories
- **THEN** descriptions SHALL teach public category ids, category detail paging, exact runtime FQCN identity, and leak-free detail metadata

#### Scenario: Mutating tool guidance remains safe
- **WHEN** a client inspects apply, init, or set
- **THEN** descriptions SHALL state write/create intent, all accepted write-safety flags, exact array property/value encodings, and no automatic recovery execution

#### Scenario: Catalog descriptions teach friendly recovery
- **WHEN** a client inspects `components` or `categories`
- **THEN** descriptions SHALL teach public category ids and `categories ls` recovery
- **AND** `components` SHALL describe exact runtime FQCN identity and leak-free detail metadata

### Requirement: MCP tool descriptions are maintained as resources
The MCP server SHALL maintain long tool-description copy as UTF-8 text resources outside the Java tool-registration logic.

The tool registry SHALL load those resources when constructing `tools/list` metadata.

#### Scenario: Resource-backed descriptions
- **WHEN** the MCP server builds metadata for a public tool
- **THEN** it loads the tool description from a classpath text resource associated with that tool name
- **AND** the Java registration logic does not embed the long prompt copy inline

#### Scenario: Packaged resource availability
- **WHEN** tests exercise the MCP tool list from the packaged runtime classpath
- **THEN** every public tool has a loadable non-empty description resource

### Requirement: MCP input schemas guide valid agent calls
Every public input property SHALL have a non-empty description. Schemas SHALL encode existing file/patch/write-mode/required-field constraints and expose only local-only runtime selection through optional `jmeter_home`. Components schema SHALL describe public `category`, exact-FQCN `component`, input-only `kind`, bounded category details, and diagnostics cardinality. Read and components SHALL expose no property-address mode selector. Set and apply SHALL require scalar-segment-array property addresses and accept scalar, generic recursive, universal `rows`, and opaque values without customizable, support-level, named-family, or GUI-openability claims. Apply patch documentation SHALL include runtime-proven append, insert, and remove cards.

#### Scenario: Recursive values and array addresses are described
- **WHEN** a client inspects read, components, set, or apply
- **THEN** the schema and descriptions SHALL explain the sole scalar-segment-array address plus scalar, collection, map, nested-element, opaque, and universal runtime-proven `rows` representations
- **AND** runtime-specific shapes SHALL come from components/read results rather than a static legality schema

#### Scenario: Catalog schema encodes bounded details
- **WHEN** a client inspects components
- **THEN** the schema SHALL allow category with details, limit, and cursor while keeping diagnostics single-component only
- **AND** no profile, validation-mode, raw group, admission status, support-level, placement field, or address-mode field SHALL be present

#### Scenario: Write destination constraints remain encoded
- **WHEN** a client inspects apply, set, or init
- **THEN** required destinations and mutually exclusive safety flags SHALL remain encoded as currently accepted

#### Scenario: File input aliases are clear
- **WHEN** a client inspects a tool accepting JMX input
- **THEN** the schema description SHALL explain the accepted file/path alias without exposing canonicalized server paths

### Requirement: MCP preserves CLI structured collection semantics
MCP components, read, set, and apply SHALL expose and accept the same scalar, generic recursive, universal `rows`, opaque, capability metadata, validation results, and persisted behavior as CLI. Rows SHALL be authorized only by exact target/runtime evidence and never by a registered adapter, named collection family, catalog descriptor, or caller-selected Java class. Proven rows SHALL expose `row_type`, ordered descriptor-based `row_properties`, and `rows`; each descriptor SHALL contain `name`, scalar `type`, `required`, and a `default` exactly when non-required.

#### Scenario: MCP catalog reports runtime-proven representation
- **WHEN** details are requested for a scalar, collection, map, nested, opaque, or runtime-proven structured row property
- **THEN** MCP SHALL include the same array address, protocol type, writable state, reason, shape, runtime-class constraints, and row descriptors as CLI

#### Scenario: MCP add and set accept equal row shapes
- **WHEN** the exact targets prove the same ordered row contract
- **THEN** MCP apply add, apply set, and set SHALL accept the same bare-list and `{row_type, row_properties, rows}` shapes
- **AND** add SHALL not require a descriptor before candidate materialization

#### Scenario: MCP invalid write matches CLI failure
- **WHEN** an address is absent, a scalar type is wrong, an asserted row descriptor differs, or a value violates the observed graph or row contract
- **THEN** MCP SHALL return the same category and recovery meaning as CLI and preserve target bytes

#### Scenario: MCP write round-trips a collection
- **WHEN** a valid generic or runtime-proven rows property graph value is written
- **THEN** saved JMX SHALL reload and subsequent read SHALL return a structurally equivalent value

#### Scenario: MCP opaque replacement enforces the CLI envelope
- **WHEN** MCP set or apply receives an opaque document
- **THEN** it SHALL enforce the same `base_digest`, runtime fingerprint, property-name, outer-class, and recursive-class constraints as CLI
- **AND** a successful non-no-op replacement SHALL return the newly server-computed digest while a stale or class-expanding payload preserves target bytes

#### Scenario: MCP invalid collection write matches CLI failure
- **WHEN** an array address is absent, a representation is incompatible, or a value violates graph or runtime-proven row constraints
- **THEN** MCP SHALL return the same category and recovery meaning as CLI and preserve target bytes

### Requirement: MCP tools use only resolved local JMeter runtimes
Every MCP tool that loads, validates, discovers, materializes, creates, or writes JMX SHALL use a resolved local JMeter home. Optional per-call `jmeter_home` SHALL override the server-start environment snapshot. MCP schemas and descriptions SHALL NOT expose profile, validation-mode, pure, bundled, auto, admission-status, or GUI-openability inputs or retry guidance.

#### Scenario: Per-call home overrides the environment snapshot
- **WHEN** a call supplies valid `jmeter_home`
- **THEN** the tool SHALL use that home through the unchanged canonical local-runtime pool

#### Scenario: Omitted home uses startup configuration
- **WHEN** `jmeter_home` is omitted
- **THEN** the tool SHALL use the server-start `JMX_AGENT_JMETER_HOME` then `JMETER_HOME` snapshot

#### Scenario: Removed profile fields do not exist
- **WHEN** a client inspects every public tool schema and description
- **THEN** none SHALL define or teach profile or validation-mode selection

### Requirement: MCP catalog results use friendly public projections
MCP `components` and `categories` SHALL use the CLI's ordered public categories, selected-locale readable labels, exact runtime FQCN `component` identity, known-empty behavior, conditional `local` aggregation, and agent-actionable property graph detail schema. The eleven mapped raw core keys MAY be accepted as input-only aliases for one release, but no raw key or arbitrary plugin group SHALL be emitted or treated as a public id.

#### Scenario: Category listing matches CLI
- **WHEN** the MCP `categories` tool and CLI `categories ls` target the same runtime
- **THEN** their categories, order, ids, labels, and counts SHALL be equal
- **AND** all eleven core categories SHALL appear even when empty

#### Scenario: Component result matches CLI
- **WHEN** MCP and CLI request the same component list or detail
- **THEN** YAML text and normalized structured data SHALL agree on public category, readable label, exact FQCN, property graph representations, and capability metadata

#### Scenario: Ordinary result leaks no internal catalog fields
- **WHEN** component or category discovery succeeds
- **THEN** neither `content` nor `structuredContent.data` SHALL contain raw group, duplicate class, registration kind, resource marker, admission status, support-level judgment, customizable, allowed parent, or placement metadata
- **AND** property details MAY contain representation source, required runtime class, writable state, and non-writable reason needed for correct agent mutation
- **AND** an included system-owned identity property SHALL be marked `ownership: system` and `writable: false`, while ordinary read property documents SHALL omit it

### Requirement: MCP unknown-category errors match CLI typed errors
MCP category validation SHALL use the same explicit public registry and typed error as CLI. Unknown category input SHALL return `isError: true` with code `USAGE_ERROR`, category `usage`, and action `rerun categories ls to list valid category ids, then retry components --category.`

#### Scenario: Known empty category succeeds
- **WHEN** MCP components filters a known category with zero matching definitions
- **THEN** it SHALL return success with the public category and an empty components list

#### Scenario: Unknown category has exact parity
- **WHEN** MCP receives an unknown public id or arbitrary raw plugin group
- **THEN** the result SHALL be an error with the same code, category, action, and diagnostic meaning as CLI exit 2
- **AND** dispatch SHALL NOT depend on matching error-message text

### Requirement: MCP catalog text and structured content are equal
Successful MCP `components` and `categories` results SHALL include readable YAML in `content` and the parsed equivalent under `structuredContent.data`. Both forms SHALL use the same friendly, leak-free data model.

#### Scenario: Categories carries parsed rows
- **WHEN** `categories` succeeds
- **THEN** `structuredContent.data.categories` SHALL equal the ordered rows in YAML content

#### Scenario: Components carries parsed rows
- **WHEN** `components` succeeds
- **THEN** `structuredContent.data` SHALL equal the component list or detail represented by YAML content

### Requirement: MCP text and structured content carry reusable property graph values
Successful MCP read and components results SHALL place human-readable YAML in `content` and the parsed equivalent in `structuredContent.data`. A writable scalar, recursive collection, map, nested-element, universal `rows`, or opaque property document from either form SHALL be reusable unchanged in MCP set or apply for equivalent exact-target evidence.

#### Scenario: Structured content value is reusable
- **WHEN** MCP read returns a writable collection, map, nested-element, `rows`, opaque, or scalar value under `structuredContent.data`
- **THEN** passing that document to MCP set or apply for equivalent target evidence SHALL be accepted
- **AND** subsequent read SHALL return a structurally equivalent value

### Requirement: MCP component refs are opaque session-stable handles
MCP `read` SHALL expose each rendered component through a quoted 16-character base64url ref with no semantic prefix or embedded generation. Within one live reusable worker generation and one unchanged canonical document identity, a previously emitted ref SHALL continue to identify the same surviving component across successful MCP in-place writes. Tokens SHALL use independent 96-bit random generation with collision retry against the union of current live tokens and tokens reserved by the prepared operation, with no deliberate reuse. Historical uniqueness and cross-registry non-collision across unbounded homes, evictions, or restarts are probabilistic rather than backed by an unbounded/global tombstone set; all unavailable-ref guarantees below are subject to that explicit accidental-collision boundary.

The `read.ref` focus input, `set.locator` input, and every external reference expression inside MCP apply SHALL consume these handles. Apply reference expressions use exactly the shared fields enumerated by the CLI apply capability. MCP init read-style output SHALL expose the same handle form bound to the created target. Callers SHALL treat their exact values as opaque.

#### Scenario: Repeated read preserves an emitted ref
- **WHEN** MCP reads an unchanged file and later renders a component whose ref was previously emitted for the same canonical JMeter home and JMX path
- **THEN** it SHALL emit the same ref for that component

#### Scenario: Structural write preserves surviving identities
- **WHEN** MCP successfully moves or reorders a component, inserts a same-class predecessor, or changes an ancestor path in place
- **THEN** the moved component, its tracked surviving descendants, and other tracked surviving components SHALL retain their prior public refs
- **AND** subsequent calls SHALL resolve those refs to their new structural locators

#### Scenario: Set preserves component identity
- **WHEN** MCP successfully changes component properties, name, or enabled state in place
- **THEN** that component's public ref SHALL remain unchanged

#### Scenario: Init establishes target-bound refs
- **WHEN** MCP successfully initializes a JMX and returns its read-style tree
- **THEN** every rendered component ref SHALL be a session ref bound to the canonical target path and selected JMeter home
- **AND** replacing an existing target SHALL invalidate its prior document entry

#### Scenario: Cross-path set does not transfer source identity
- **WHEN** MCP resolves a source ref and successfully writes set output to a different target path
- **THEN** the operation SHALL leave source mappings/fingerprint unchanged and refresh source LRU recency
- **AND** absent a racing/later external source change, the source ref SHALL remain valid
- **AND** a racing/later external source change SHALL make it stale and trigger fingerprint invalidation on next use
- **AND** any prior target entry SHALL be invalidated
- **AND** the source ref SHALL not become valid for the target

#### Scenario: Add and delete change the identity set
- **WHEN** MCP commits an add with an alias whose object survives the final candidate
- **THEN** the added component SHALL receive a new ref returned for that alias
- **AND WHEN** MCP commits an in-place delete
- **THEN** every tracked ref in the deleted subtree SHALL become unavailable, and the generator SHALL NOT deliberately reassign those token values to another component

### Requirement: MCP reference state is document-bound, bounded, and non-persistent
The reusable worker SHALL maintain ref state by validated real JMeter home and prospective-real canonical JMX path, reload the mutable plan for every operation, and retain only refs actually emitted to the caller. Document identity equality SHALL compare the complete prospective-real path: the nearest existing ancestor resolved with `Path.toRealPath()` plus the normalized unresolved suffix. Existing symlink/path aliases with equal complete paths SHALL share one identity, while distinct sibling targets under the same real ancestor SHALL remain distinct. Each worker generation SHALL retain at most 64 document entries and 100,000 live refs using access-order, whole-document LRU eviction.

The worker SHALL retain an internal SHA-256 of the expected file bytes for each document entry. Ref allocation, target replacement, and eviction SHALL be prepared atomically; capacity failure SHALL leave output/target bytes, mappings, occupancy, and recency unchanged. Failed calls and dry runs SHALL NOT refresh LRU recency. That fingerprint SHALL NOT appear in ordinary MCP content or structured data.

#### Scenario: Ref cannot cross document identity
- **WHEN** a caller supplies a valid token with a different canonical JMeter home or JMX path from the document that emitted it
- **THEN** the token SHALL be unavailable for that call
- **AND** no other document SHALL be selected or mutated through the token

#### Scenario: Distinct missing sibling targets retain distinct identities
- **WHEN** two not-yet-created copy/init targets share the same nearest existing real ancestor but have different normalized unresolved suffixes
- **THEN** they SHALL be different document identities
- **AND** neither target's refs or prior entry SHALL be installed, invalidated, or resolved through the other target

#### Scenario: External file change invalidates the document entry
- **GIVEN** a document has emitted MCP refs
- **WHEN** its current bytes differ from the internally expected fingerprint before a focused read, set, or apply
- **THEN** the worker SHALL discard the complete document entry
- **AND** the ref-consuming call SHALL fail with `MCP_REF_NOT_FOUND` before mutation

#### Scenario: Missing or unreadable source invalidates refs
- **GIVEN** a document has emitted MCP refs
- **WHEN** the document is deleted, replaced by a directory, or cannot be read before a ref-consuming call
- **THEN** the worker SHALL discard the complete document entry and fail with `MCP_REF_NOT_FOUND` before mutation

#### Scenario: Ordinary read refreshes an externally changed document
- **GIVEN** a document entry no longer matches the file bytes
- **WHEN** the caller performs an unfocused read without supplying an old ref
- **THEN** MCP SHALL discard the old entry, reload the plan, and assign fresh refs to rendered components

#### Scenario: Worker loss or LRU eviction loses refs
- **WHEN** a reusable worker generation ends or a document entry is evicted to satisfy either bound
- **THEN** its prior refs SHALL not be reconstructed
- **AND** absent the accepted independent 96-bit collision boundary, later use SHALL return `MCP_REF_NOT_FOUND`

#### Scenario: One document cannot exceed the ref bound
- **WHEN** rendering additional components for one document would exceed the per-worker live-ref bound even after all eligible other document entries are evicted in the prepared state
- **THEN** the call SHALL fail with code `MCP_REF_CAPACITY_EXCEEDED`, category `runtime`, and an action to reduce rendered scope where supported or work with a smaller document
- **AND** it SHALL NOT return output containing partially tracked refs, commit a mutating target, evict an existing entry, or change LRU recency

#### Scenario: Refresh capacity failure retains an unusable stale entry transactionally
- **GIVEN** an unfocused read detects that its current document entry fingerprint is stale
- **WHEN** fresh rendered refs cannot fit within the registry bounds
- **THEN** the read SHALL fail with `MCP_REF_CAPACITY_EXCEEDED` and leave the stale live entry and LRU order unchanged
- **AND** the retained entry SHALL remain unusable because its fingerprint does not match; a later ref-consuming call SHALL invalidate it

#### Scenario: Copy protects source refs while allocating target refs
- **WHEN** a copy apply would require evicting its source document entry to install refs for surviving target aliases
- **THEN** the copy SHALL fail with `MCP_REF_CAPACITY_EXCEEDED` before target commit
- **AND** the source and all other live registry state SHALL remain unchanged

#### Scenario: Init prepares refs before target commit
- **WHEN** init cannot allocate every ref required by its read-style result within the registry bounds
- **THEN** init SHALL fail with `MCP_REF_CAPACITY_EXCEEDED` without creating or replacing the target
- **AND** the live registry SHALL remain unchanged

### Requirement: MCP mutations resolve refs atomically through one reference space
Before executing a set or apply mutation, MCP SHALL compute the document fingerprint and parse the plan from one immutable source-byte snapshot, then resolve every supplied external ref against that loaded input plan. Apply SHALL additionally validate all alias declarations, declaration-before-use ordering, and reference-expression positions before mutation. The shared mutation engine SHALL receive resolved node handles rather than public reference strings. Before target commit, MCP SHALL reread the source bytes and reject the commit if their fingerprint no longer matches the input snapshot.

#### Scenario: One unavailable ref rejects the complete apply
- **WHEN** any external ref in an apply request is unavailable
- **THEN** MCP SHALL reject the complete request with `MCP_REF_NOT_FOUND`
- **AND** it SHALL not execute a mutation or create/replace a requested target
- **AND** it SHALL change no registry state except to discard the affected document entry when fingerprint, missing-file, or retained-record proof loss caused the failure

#### Scenario: Earlier mutation does not retarget a later ref
- **WHEN** all external refs are resolved and an earlier operation moves or inserts components before a later referenced component
- **THEN** the later operation SHALL continue to target the node handle resolved from the input plan

#### Scenario: Alias binds the created object within one apply
- **WHEN** a valid add declares an alias and a later operation references that alias
- **THEN** the later operation SHALL target the exact object materialized by that add
- **AND** no name, class-only, property, or structural-similarity matching SHALL be used

#### Scenario: Source change before commit rejects the mutation
- **WHEN** source bytes change after the immutable input snapshot is parsed but before the precommit fingerprint check
- **THEN** MCP SHALL invalidate the source entry and fail without committing an in-place or copy target
- **AND** it SHALL not replay the mutation

#### Scenario: Source change after the precommit check is detected on next use
- **WHEN** an uncoordinated external writer changes a copy source after MCP's final precommit fingerprint check
- **THEN** MCP does not claim compare-and-swap serializability for that race and MAY still commit the already validated copy target
- **AND** the next ref-consuming source call SHALL detect the fingerprint mismatch, invalidate the source entry, and return `MCP_REF_NOT_FOUND`

#### Scenario: Reloaded candidate proves every tracked survivor
- **WHEN** candidate SaveService save/reload completes before commit
- **THEN** MCP SHALL retain the existing shared CLI candidate gate and additionally compare the complete ordered component-class/subtree topology plus every proposed tracked-survivor and surviving-created-alias locator, expected class, and identity-specific canonical persisted signature between the in-memory and reloaded candidates
- **AND** each signature SHALL use the same SaveService-normalized serialization of the tracked TestElement's own persisted state on both sides, SHALL exclude descendant values, and SHALL NOT inspect unrelated untracked-node properties
- **AND** a missing, structurally reordered, class/signature-mismatched, or otherwise unproven candidate identity SHALL fail with code `MCP_REF_RECONCILIATION_FAILED`, category `runtime`, and an action to inspect or simplify the mutation before retrying
- **AND** the failure SHALL preserve source refs, registry state, and target bytes without rebinding a token or instructing a fresh read
- **AND** the identity proof SHALL NOT compare unrelated persisted values or otherwise change CLI-equivalent persistence acceptance

#### Scenario: Same-class sibling exchange cannot silently rebind refs
- **WHEN** two tracked same-class siblings have different canonical persisted signatures and candidate reload exchanges their ordered positions
- **THEN** MCP SHALL fail with `MCP_REF_RECONCILIATION_FAILED` before commit
- **AND** when same-class siblings have byte-equivalent canonical signatures, their exact ordered position SHALL be the documented persisted identity boundary

#### Scenario: Worker proves SaveService encounter-order before session mutation
- **WHEN** a reusable worker generation enables session-ref mutations
- **THEN** it SHALL first prove with distinguishable same-class siblings and subtrees that the selected runtime saves and reloads exact `HashTree.list()` encounter order without sorting or rekeying
- **AND** failure SHALL leave read/focus session refs available for the unchanged current snapshot
- **AND** set/apply/init SHALL run this generation gate before source load, fingerprint/ref/alias resolution, capacity work, or target preparation
- **AND** failure SHALL return code `MCP_REF_ORDER_UNPROVEN`, category `runtime`, and an action to select/fix a conforming JMeter runtime/home and read there for new refs before retrying, or use stateless CLI write/read recovery
- **AND** no JMX target or registry state SHALL change

#### Scenario: Order capability failure precedes stale-ref handling
- **GIVEN** a worker generation has failed its encounter-order proof and a set/apply request also supplies a stale ref
- **WHEN** MCP receives the mutation request
- **THEN** it SHALL return `MCP_REF_ORDER_UNPROVEN` before loading or fingerprinting the source
- **AND** it SHALL not invalidate, refresh, or otherwise change the stale document entry

#### Scenario: Registry publication adds no recoverable post-commit failure
- **WHEN** set, apply, or init has prepared a complete immutable registry state and the target commit succeeds
- **THEN** the worker SHALL publish that state through one non-throwing reference assignment before performing any other work
- **AND** no recoverable tool error SHALL be returned after target commit; catastrophic process death follows the fatal unknown-outcome scenario

### Requirement: MCP apply returns a compact identity-aware receipt
Successful MCP apply SHALL preserve the existing `command`, `format`, optional `output`, `dryRun`, and `writtenTarget` fields in `structuredContent.data`. It SHALL add `writeMode` with value `dry-run`, `in-place`, or `copy`; nonnegative integer `appliedCount`; `createdRefs` as declaration-ordered rows containing exactly `alias` and `ref`; and `deletedRefs` as public-ref strings ordered by input-tree traversal. `output` SHALL retain its current caller-supplied path spelling and SHALL NOT imply a write; `writtenTarget` SHALL be `null` for dry run and otherwise identify the authorized target.

Created rows SHALL contain only aliased components that survive the final candidate. Deleted refs SHALL include every tracked ref invalidated by a committed in-place delete, including tracked descendants. These identity fields SHALL augment rather than suppress mutation results required by other capabilities, including a newly computed opaque-property digest. The result SHALL NOT return a post-write tree, snapshot identifier, internal fingerprint, worker generation, or complete ref index.

#### Scenario: In-place apply returns changed identity facts without redundant refs
- **WHEN** MCP commits an in-place patch containing set, move, add-with-alias, and delete changes
- **THEN** the receipt SHALL return the total applied count, each surviving created alias and its new stable ref, and every tracked ref removed with each deleted subtree
- **AND** it SHALL not repeat unchanged refs for set, move, reorder, or indirectly shifted survivors
- **AND** it SHALL retain any non-identity mutation result required by the written value's capability

#### Scenario: Alias deleted in the same patch has no durable identity
- **WHEN** an add declares an alias and that aliased object or one of its ancestors is deleted later in the same committed patch
- **THEN** the receipt SHALL count the applied changes but SHALL omit that alias from created mappings
- **AND** it SHALL not allocate or report a durable ref for the absent final object

#### Scenario: Copy apply creates a distinct target identity
- **WHEN** MCP resolves refs against a source and commits to a different output path
- **THEN** the operation SHALL leave source document mappings/fingerprint unchanged and refresh source LRU recency
- **AND** absent a racing/later external source change, source refs SHALL remain valid; otherwise the next use SHALL apply fingerprint invalidation
- **AND** any prior target entry SHALL be invalidated
- **AND** created aliases SHALL receive new refs bound to the target path
- **AND** source refs SHALL not be reported as deleted or transferred to the target

#### Scenario: Dry run returns no durable identity changes
- **WHEN** MCP apply validates successfully with `dryRun=true`
- **THEN** the receipt SHALL report applicability with `writeMode: dry-run`, `dryRun: true`, `writtenTarget: null`, and the applied change count
- **AND** it SHALL return empty `createdRefs` and `deletedRefs` arrays and leave every registry entry unchanged, including LRU recency

### Requirement: MCP apply guidance distinguishes inline and retained YAML patches
The MCP apply schema SHALL continue to require exactly one of `patchYaml` or `patchFile` and SHALL NOT add a structured `changes` input. Tool descriptions SHALL recommend `patchYaml` for an agent-generated one-off patch and `patchFile` for YAML that already exists on the MCP server filesystem or is intentionally retained for review, reuse, or version control.

#### Scenario: Agent-generated patch needs no temporary file
- **WHEN** an agent inspects apply tool guidance before constructing a one-off patch
- **THEN** the guidance SHALL identify `patchYaml` as the preferred input regardless of patch complexity within the existing size bound
- **AND** it SHALL not instruct the agent to create a temporary patch file

#### Scenario: Existing retained patch uses patchFile
- **WHEN** an apply patch already exists on the MCP server filesystem or is intentionally retained
- **THEN** the guidance SHALL identify `patchFile` as the path-based input
- **AND** it SHALL state that both inputs use the same YAML patch grammar and existing size bound

### Requirement: MCP ref-consuming tool descriptions teach session lifecycle
The MCP `read`, `set`, `apply`, and `init` descriptions SHALL state that MCP component refs are opaque handles bound to the same canonical file, selected JMeter home, and live reusable worker generation. They SHALL state that surviving refs remain reusable after successful in-place MCP writes, while `MCP_REF_NOT_FOUND` requires reading the same file/home again. Apply guidance SHALL distinguish external session refs from request-local `$alias` expressions, and init guidance SHALL identify its returned refs as target-bound.

#### Scenario: Tool listing explains when a fresh read is required
- **WHEN** an MCP client lists `read`, `set`, `apply`, and `init`
- **THEN** their descriptions SHALL NOT repeat CLI guidance that every successful write requires a fresh read
- **AND** they SHALL explain same-file/home/live-worker scope and `MCP_REF_NOT_FOUND` read-again recovery

#### Scenario: Apply and init descriptions explain produced refs
- **WHEN** an MCP client lists `apply` and `init`
- **THEN** apply SHALL describe request-local `$alias` expressions and committed surviving alias/ref mappings
- **AND** init SHALL describe its read-style refs as bound to the created target

### Requirement: MCP components mirrors CLI authoring and diagnostics modes
The MCP `components` tool SHALL expose the same list, single-component detail, category detail, and single-component diagnostic modes as CLI. `diagnostics: true` SHALL require exactly one selected `component` or `kind`; category MAY combine with `details: true`, `limit`, and `cursor`, but SHALL be exclusive with component, kind, and diagnostics. Category pages SHALL preserve CLI ordering, limits, cursor binding, bounded per-component failures, and `partial` semantics. YAML `content` and `structuredContent.data` SHALL be equivalent.

#### Scenario: MCP category details need one call
- **WHEN** MCP calls components with category and `details: true`
- **THEN** both result forms SHALL contain the same bounded ordinary authoring page and continuation cursor

#### Scenario: MCP diagnostics remains single-component
- **WHEN** MCP calls components with category and diagnostics or reuses a cursor with mismatched bound inputs
- **THEN** it SHALL return a MCP-native usage error before worker discovery

#### Scenario: MCP ordinary components response is compact
- **WHEN** MCP calls `components` with one component or kind and no diagnostics marker
- **THEN** both result forms SHALL contain only the ordinary authoring projection
- **AND** neither form SHALL contain diagnostic fields or non-writable properties

#### Scenario: MCP diagnostics has exact CLI parity
- **WHEN** MCP calls `components` with one component or kind and `diagnostics: true`
- **THEN** both result forms SHALL contain the full diagnostic projection equal to the CLI diagnostic result

### Requirement: MCP schemas and runtime validation reject invalid tool arguments before worker execution
Every advertised public MCP schema and runtime boundary SHALL agree on permitted top-level keys and cross-field cardinality. Runtime validation SHALL reject unknown keys and ambiguous aliases before constructing CLI arguments or starting a local-JMeter worker. Tools with a JMX input SHALL require exactly one of `file` or `path`; selected components SHALL use exactly one of `component` or `kind`; category detail inputs SHALL follow the bounded paging contract; and `set` SHALL enforce exactly one of `out` or `override` with the same `forceOut` restrictions as CLI. `set.property` SHALL be exactly the non-empty scalar-segment array schema; `set.type` SHALL be exactly `string|boolean|int|long|float|double|null|raw|collection|map|element|rows|opaque`; and `set.value` SHALL accept the native JSON scalar/array/object form required by that type. `read.depth` SHALL have minimum `0` and documented default `1`; `read.properties` SHALL be exactly one of `none`, `key`, `all`, or `writable`; marker booleans SHALL have a `true` constant constraint; and the existing 8 MiB JSON-RPC and 4 MiB patch bounds SHALL remain unchanged. Read and components SHALL reject the removed `propertyAddress` key.

#### Scenario: Typoed and ambiguous arguments cannot execute a worker
- **WHEN** a valid MCP tool call contains an unknown key, both aliases, invalid category-detail cardinality, invalid paging, removed address mode, malformed scalar-segment array, invalid read bounds/enums, or conflicting set modes
- **THEN** the result SHALL be an argument failure before worker execution
- **AND** it SHALL not write a file or mutate a JMX document

#### Scenario: Tools-list schema advertises the actual constraints
- **WHEN** a client inspects `tools/list`
- **THEN** each affected tool schema SHALL expose the same allowed keys, exact-one groups, true-only markers, bounds, cursor fields, array member constraints, and enum values enforced by runtime validation

### Requirement: Valid tool-call argument failures are MCP-native usage results
For a syntactically valid JSON-RPC `tools/call` envelope targeting a known tool, every argument-validation failure SHALL produce a successful JSON-RPC response whose tool result has `isError: true`, diagnostic code `USAGE_ERROR`, category `usage`, and an MCP-native actionable next action. Malformed JSON-RPC or malformed tool-call envelopes SHALL remain JSON-RPC `-32602`; an unknown tool SHALL remain `-32601`. Usage recovery SHALL direct the agent to the named MCP tool and valid next arguments, without telling it to use a CLI-only command or retry/replay a mutation.

#### Scenario: Known-tool invalid arguments return a tool result
- **WHEN** a client calls a known tool with otherwise valid JSON-RPC but invalid arguments
- **THEN** the server SHALL return a successful JSON-RPC response containing `isError: true`, `USAGE_ERROR`, `usage`, and a MCP-native next action

#### Scenario: Protocol and tool-name errors retain JSON-RPC boundaries
- **WHEN** a JSON-RPC/tool-call envelope is malformed or a requested tool is unknown
- **THEN** the server SHALL return `-32602` for the malformed envelope or `-32601` for the unknown tool
- **AND** it SHALL not reclassify those protocol errors as `USAGE_ERROR` tool results

### Requirement: MCP writes retain safe CLI parity without transport expansion
MCP `set` SHALL use CLI's canonical/copy-safe write-mode resolution, including rejection of canonical/symlink input aliases and the exact invalid combinations. MCP SHALL retain both useful `content` and equivalent `structuredContent.data`; SHALL not add structured `changes` input, snapshots, persistent refs, bulk selectors, catalog search, automatic retry/replay, or a transport redesign. Public tool descriptions and agent guidance SHALL teach the actual MCP tool names, generic collection envelope/template workflow, write-mode matrix, safe copy chaining, diagnostics workflow, and non-redundant post-write proof.

#### Scenario: Safe MCP copy write returns one sufficient proof
- **WHEN** MCP successfully writes to an owned distinct copy using a valid mode
- **THEN** it SHALL preserve CLI safety and return equivalent content and structured data sufficient to identify the result
- **AND** guidance SHALL not require a redundant validation call after the already validated write

### Requirement: MCP read accepts the writable projection without changing its existing modes
The MCP `read.properties` schema and runtime validation SHALL accept exactly `none`, `key`, `all`, or `writable`; absent properties SHALL retain the current default. A third or malformed enum value SHALL be a MCP-native `USAGE_ERROR` before worker execution. The MCP `none` mode SHALL remain accepted and property-free.

For `writable`, MCP text and `structuredContent.data` SHALL use the same ordered writable graph-property subset and reusable documents as CLI. Existing `none`, `key`, and `all` semantics; default, tree/focus/ref/source/disabled behavior; property ordering; and `structuredContent.data` shape SHALL remain unchanged.

#### Scenario: MCP accepts only its four-mode enum
- **WHEN** a client calls `read` with `properties: "none"`, `"key"`, `"all"`, or `"writable"`
- **THEN** MCP SHALL accept the requested mode with its existing/default semantics plus the additive writable projection
- **AND** `none` SHALL remain property-free
- **AND** any other value SHALL return `isError: true` with `USAGE_ERROR` before a worker starts or a JMX document changes

#### Scenario: MCP writable equals the CLI graph-capability subset
- **WHEN** CLI and MCP read the same local-runtime input with equivalent read options and `properties: writable`
- **THEN** both forms SHALL contain the ordered subset selected solely by current property-graph writable capability
- **AND** each retained recursive/scalar document SHALL be directly reusable by existing MCP set/apply
- **AND** a retained document SHALL be byte-equivalent to that record's `all` document and writable bytes SHALL not exceed equivalent all bytes

### Requirement: MCP results add a constant-size reference scope without receipt or data drift
Successful MCP results for `read`, `init`, `set`, and `apply` SHALL add `structuredContent.ref_scope` outside `structuredContent.data`. `ref_scope` SHALL be a constant-size descriptive object with exactly `source`, `target`, and `created` string members. Each value SHALL be one of `source-bound`, `target-bound`, or `none`; it SHALL describe only categories of refs already emitted by that result and SHALL contain no ref token, ref array, path, canonical-home value, fingerprint, generation, LRU state, or registry identity.

| Command / mode | `source` | `target` | `created` | Required interpretation |
| --- | --- | --- | --- | --- |
| `read` | `source-bound` | `none` | `none` | Rendered tree/focus refs are bound to the source document. |
| `init` | `none` | `target-bound` | `none` | Existing read-style rendered refs are bound to the created/replaced target. |
| `set` | `none` | `none` | `none` | The result emits no target refs. |
| `apply` dry-run | `none` | `none` | `none` | The result emits no target refs. |
| `apply` in-place | `none` | `none` | `target-bound` | Only existing `createdRefs[].ref` are target-bound. |
| `apply` copy | `source-bound` | `none` | `target-bound` | Source refs remain source-bound; only existing `createdRefs[].ref` are target-bound; other target refs require a target read. |

Text content, CLI results, all existing `structuredContent.data` values and field order, validated write-receipt fields, `createdRefs`, registry allocation, and ref lifecycle behavior SHALL remain unchanged.

#### Scenario: Read and init scope describe already-rendered refs
- **WHEN** MCP returns a successful read or init result
- **THEN** `ref_scope` SHALL match the read/init row in the matrix
- **AND** it SHALL not repeat any actual tree or focus ref token or allocate a new ref

#### Scenario: Set and dry-run expose no target refs
- **WHEN** MCP set succeeds or apply succeeds in dry-run mode
- **THEN** `ref_scope` SHALL use the all-`none` matrix row
- **AND** no target ref, registry allocation, or receipt/data field change SHALL be introduced

#### Scenario: Apply scope preserves source and created-ref boundaries
- **WHEN** apply succeeds in-place or as a copy
- **THEN** `ref_scope` SHALL match its matrix row without adding a generic `refs` collection
- **AND** copy mode SHALL neither retarget nor transfer source refs, and a target ref other than the existing `createdRefs` SHALL require a later target read

#### Scenario: Ref-scope output remains constant-size and non-identifying
- **WHEN** the number of rendered tree/focus refs or created refs changes
- **THEN** the serialized `ref_scope` size SHALL remain bounded by a fixed implementation threshold independent of that count
- **AND** it SHALL contain no token copied from the result and no persistence, reconstruction, fingerprint, generation, LRU, canonical-path, or canonical-home disclosure

### Requirement: Unavailable-ref recovery is directly callable from the original MCP invocation
For `MCP_REF_NOT_FOUND` and other applicable unavailable-ref recovery, MCP SHALL preserve existing diagnostic code, category, message, action, and human content while adding `structuredContent.recovery` with exactly `tool: "read"` and an `arguments` object. `arguments` SHALL contain exactly the caller-supplied `file` or `path` key and spelling, plus `jmeter_home` only when that key was explicitly supplied by the caller. It SHALL contain neither resolved/canonical values nor ref, locator, patch, mutation value, secret, or unrelated input.

The recovery object SHALL be guidance for a future call only. MCP SHALL not execute it, automatically read, retry, replay a mutation, persist invocation context, change worker/registry state beyond existing invalidation rules, or write a source/target while forming recovery.

#### Scenario: Stale ref produces a reusable original-spelling read call
- **WHEN** a ref-consuming tool returns `MCP_REF_NOT_FOUND` for a caller that supplied `file` or `path`
- **THEN** `structuredContent.recovery` SHALL be directly reusable as a future MCP `read` call with the same key/value spelling
- **AND** it SHALL include `jmeter_home` only when explicitly supplied, unchanged

#### Scenario: Recovery does not alter mutation or identity state
- **WHEN** an unavailable ref is supplied to set or apply, including wrong path/home, external source change, restart/eviction, deleted ref, copy-source reuse, or a request with multiple refs
- **THEN** the request SHALL follow the existing unavailable-ref failure and invalidation rules with no implicit second request, target write, mutation replay, or ref persistence/reconstruction
- **AND** unavailable multi-ref apply SHALL remain atomic and non-mutating

### Requirement: MCP guidance and live verification stay compatible and bounded
All seven MCP tool descriptions and schemas, README, and installed agent guidance SHALL agree on `none|key|all|writable`, the sole scalar-segment-array address, the exact set type enum, universal `type: rows`, runtime-proven append/insert/remove, generic recursive envelopes, metadata-source limitation, ref scope/lifecycle, category paging, opaque focused-read acquisition, init recovery, and unavailable-ref recovery. Each tool description SHALL remain below 4 KiB and packaged `tools/list` SHALL remain at most 40,000 characters.

The contract SHALL retain Java 8 compatibility, selected isolated local-runtime authority, existing input bounds, write safety, opaque non-persistent refs, and no automatic retry/replay. It SHALL not introduce effective configuration, `ConfigTestElement` traversal, per-property provenance, HTTP allowlists, row-family mappings, GUI-field completeness claims, generic returned refs, raw XML editing, GUI/plugin installation, load execution, bare full-suite verification, receipt/data drift, an address compatibility adapter, or an active collection-family adapter. Private GUI-binding or table-consumer reflection SHALL be permitted only inside the isolated worker, only for verified JMeter 5.6.3 semantic metadata discovery, and only under the bounded fail-closed contract of `jmeter-gui-semantic-metadata`.

#### Scenario: Packaged MCP documentation is budgeted and coherent
- **WHEN** a client initializes packaged MCP and calls `tools/list`
- **THEN** every tool description and schema SHALL agree with array-address and runtime-row examples and recovery forms
- **AND** every description SHALL be under 4 KiB and the full list SHALL be at most 40,000 characters

#### Scenario: Real MCP stdio QA is live, focused, and safe
- **WHEN** this contract is verified through packaged MCP stdio
- **THEN** it SHALL execute initialize, tools/list, read, set, apply, components, init, incremental row mutation, and unavailable-ref recovery against owned copies and a selected local JMeter runtime
- **AND** focused tests and live QA SHALL prove output budgets, original-hash preservation, no implicit replay, bounded semantic metadata reuse, and cleanup without bare `./gradlew test` or load execution

#### Scenario: Ordinary MCP mutations do not open GUI windows
- **WHEN** an MCP write uses schema, existing-target, or cached exact-runtime semantic evidence
- **THEN** it SHALL not start the JMeter desktop application, display a window, or perform GUI-openability validation

### Requirement: MCP init existing-target recovery is directly callable
When MCP init targets an existing file without `forceOut: true`, it SHALL return `isError: true`, stable code `OUTPUT_FILE_EXISTS`, preserve the file, and add `structuredContent.recovery` containing exactly `choices`. `choices` SHALL contain exactly two ordered records with exactly `action`, `tool`, and `arguments`. The first record SHALL use `action: overwrite`, `tool: init`, and the caller's original argument object plus `forceOut: true`. The second SHALL use `action: choose-output`, `tool: init`, preserve every original argument except `forceOut`, and replace only `out` with `<different-output.jmx>`. Recovery SHALL preserve caller spelling, contain no undefined confirmation field, canonicalized path, secret, automatic execution, retry, or mutation replay.

#### Scenario: Existing MCP target returns two safe choices
- **WHEN** init targets an existing file without force
- **THEN** `structuredContent.recovery.choices` SHALL contain the exact overwrite and choose-output calls
- **AND** the server SHALL execute neither choice while forming the error

### Requirement: MCP exposes runtime-proven row collection mutations
MCP apply SHALL accept the same append, insert, and remove patch cards as CLI apply and SHALL return equivalent human-readable content, structured data, diagnostics, applied-count semantics, and atomic write behavior. MCP SHALL pass native property arrays and row objects through the shared mutation boundary without converting them to a string path language.

#### Scenario: MCP appends to an empty Header collection
- **WHEN** a client submits a valid append card for an empty runtime-proven Header collection
- **THEN** MCP apply SHALL commit only after SaveService reload preserves the new row
- **AND** its result SHALL report the mutation through the existing apply receipt contract

#### Scenario: MCP rejects malformed incremental mutation before worker execution
- **WHEN** append, insert, or remove has an unknown key, missing required key, invalid index, string property address, or incompatible row shape
- **THEN** MCP SHALL return an MCP-native usage or property-representation error at the applicable boundary
- **AND** it SHALL not mutate the source or destination
