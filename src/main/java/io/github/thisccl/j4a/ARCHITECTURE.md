# Architecture
Scope: src/main/java/io/github/thisccl/j4a/**

## Local JMeter runtime authority

JMeter-aware operations resolve one local JMeter installation and delegate component identity, materialization, placement, and persistence compatibility to that isolated runtime. J4A retains protocol grammar and transactional file-safety ownership, but it does not maintain a competing component catalog or legality model.

## Execution ownership

Standalone CLI operations own short-lived workers, while the MCP server owns reusable runtimes through its pool. This keeps JMeter static state isolated without allowing CLI invocations to leak process-lived shared state.

## JMeter 5.6.3 semantic evidence seam

Only the isolated worker may use private reflection for GUI semantic evidence, and only after verifying the selected runtime is exactly JMeter 5.6.3. The seam is bounded, type-directed, fail-closed, and limited to reaching JMeter binding descriptors and table-consumer metadata; Swing/JDK internals, reflection members, GUI objects, and mutable models never cross the worker protocol. It supplements schema, BeanInfo, and exact target evidence without component/property allowlists, row-family mappings, or a claim that every GUI field was enumerated.

Semantic observations are lazy and single-flight per worker/component identity. Immutable successes and failures live for that worker generation; replacement discards them. Cached semantic evidence is descriptive only: exact target rows and persisted XML retain precedence, and request-scoped graph validation remains the sole mutation authorization. Commands may instantiate non-visible component objects for bounded observation but never open top-level windows, start the desktop application, run GUI-openability validation, or execute a load.

The internal rollback property `-Dj4a.guiSemanticEvidence.disabled=true` is propagated to the worker and bypasses this semantic source. Schema descriptors, BeanInfo, present-property discovery, SaveService verification, and ordinary standard-JMX usability remain active; the switch introduces no persisted J4A format and does not invalidate files already written with standard JMeter classes.

## Component reference boundary

The `reference` package exports only the `ComponentReferences` namespace, request-bound `BoundReferences`, `ReferenceResolution`, and the zero-method `ResolvedNodeHandle` marker. Their recursive public signatures contain no mutable JMeter, JOrphan, JMX-plan, registry, or prepared-state type. `BoundReferences` exchanges only immutable structural-address and component-class strings when renderers expose or match a component; exact `TestElement` identity lives in validation-owned `ExactBoundReferences` and `ExactNodeHandle`, and caller-fabricated marker implementations are rejected before they can become resolved references. Java 8 has no sealed cross-package implementation type, so `ExactNodeHandle` is public and final solely for `ReferenceResolution` authentication; it has no public constructor, method, or field and is absent from every public reference signature, while creation and node access remain package-private validation operations. Validation owns snapshot binding and mutation orchestration: production mutation flows through package-private `LocalJMeterWorkerMutations` → `LocalMutationTransaction` → `LocalMutationProgram`, with `ApplyPatchCompiler` producing a request-scoped resolved `ResolvedApplyPlan` before exact-node mutation. Session registry, token allocation, source snapshots, reconciliation proposals, identity proof, receipts, and publication remain validation implementation so callers cannot couple to transaction mechanics; reference handling does not own property paths, values, codecs, or component admission.

## Session identity ownership

Each reusable local-JMeter worker generation owns its bounded document/reference registry and identity-proof metadata. The worker composition root selects the session adapter for MCP and the snapshot adapter for standalone CLI, keeping process-lived identity state with the runtime that owns JMeter object identity.
