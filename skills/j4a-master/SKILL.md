---
name: j4a-master
description: J4A MCP workflow for JMeter .jmx files. Use when creating a test plan, inspecting or changing an existing plan, validating it against a local JMeter runtime, or recovering from JMX structure, property-shape, plugin, or classpath errors.
---

# J4A MCP Workflow

Use the J4A MCP tools `read`, `validate`, `components`, `categories`, `apply`, `init`, and `set` for JMX work.

## Invariants

- Treat each live MCP tool schema as the operation contract; it overrides bundled reference text.
- Resolve one JMeter home and keep it fixed through discovery, mutation, validation, and read-back. Use `jmeter_home` only for an intentional override. Plugin, JAR, property, or classpath changes require user authorization and an MCP server restart before fresh discovery.
- Select destinations through [write modes](references/write-modes.md) and default to an owned copy.
- Use only refs returned by the current MCP session for the same file and JMeter home. A surviving component ref remains reusable after an in-place write; after restart, external divergence, deletion, or replacement, recover from `MCP_REF_NOT_FOUND` with a fresh same-file/same-home `read`.
- Preserve exact values in tool calls and owned JMX files; avoid reproducing secrets in summaries.
- Keep the workflow to JMX structure/property editing and load-only validation. If the MCP tools are unavailable, report the missing prerequisite; do not edit raw XML or switch transports.

## Route the task

- New plan: read [init](references/init.md), then discover components and apply changes.
- Existing plan inspection: read [read](references/read.md).
- One known property on one existing component: read [set](references/set.md).
- Adds, moves, deletes, or related changes: read [apply](references/apply.md).
- Component selection or uncertain writable metadata: read [categories](references/categories.md) and [components](references/components.md).
- Nested or rejected property address: read [property addresses](references/property-addresses.md).
- A reported non-scalar type: read [structured collections](references/structured-collections.md). This reference is required for `type: element` because leaf editing and replace-whole use different documents. For `type: opaque`, also read [opaque values](references/opaque-values.md).
- Any `set`, `apply`, or `init` write: read [write modes](references/write-modes.md) before choosing `out`, `override`, `dryRun`, or `forceOut`.
- Explicit independent validation: read [validate](references/validate.md).

## Execute through gates

1. **Resolve runtime.** Inspect the live schemas and select one JMeter home for every planned call.
2. **Establish source.** For a new plan, call `init`; otherwise `read` with bounded depth, then focus by a returned ref. Continue only with current input/output paths, refs, and property addresses.
3. **Discover metadata.** Before every add, exhaust `categories`/`components` paging until the exact component is unique or the cursor is exhausted. For a property write, use current focused `read` plus selected-component `components` only when the writable type or shape remains unknown. Use diagnostics only to investigate a capability or runtime representation. For `type: element`, choose either one focused leaf address or the complete emitted replacement value before authoring the call. Continue only when every change has copied identity, address, type, the required value shape, and explicit parent/order where applicable.
4. **Mutate an owned copy.** Select exactly one mode from [write modes](references/write-modes.md). Use `set` for one low-risk existing property. Use one ordered `apply` for structural or related work and for any write needing dry-run. Use aliases for dependent additions in the same patch. Dry-run the identical patch before delete, move, in-place writes, existing-output replacement, multiple structural cards, or recursive/opaque replacement. Before later staged edits to a copy, read that target and use its refs.
5. **Prove completion.** J4A will save, reload, and project the owned candidate before committing it; require the successful write receipt. Dry-run validates the patch and writes no JMX file. A rejected replacement preserves the existing target bytes. Use focused read-back after delete, move, ordered structure/rows, opaque replacement, or whenever success depends on exact value, order, parentage, or observable structure. Compare only the requested semantics.

Completion requires the requested output, an unchanged input unless in-place mutation was explicit, and a successful validated write receipt. Standalone `validate` is only for an independent validation request.

## Compatibility boundary

- MCP read properties are `none|key|all|writable`; use `writable` for authoring. CLI read supports `key|all|writable`.
- Cursor v3 is rejected. Restart category paging at the first page; follow a returned `componentToken` with an exclusive components call for exact ordinary detail.
- Usage/schema failures exit 2, accepted apply semantic failures exit 3, and infrastructure or fatal failures exit 4. A fatal result is an unknown outcome: inspect/read the target before any retry, and send no automatic replay.
- Generic TestBeans use the exact selected-runtime FQCN, never `TestBeanGUI`, as public component identity.
- CLI `createdRefs` are target-snapshot refs: reuse them only against the exact unchanged target. Every other or stale target requires a fresh read.

## Recovery

- Invalid home or missing class/plugin/dependency: report the exact prerequisite. Change the selected installation or restart a shared server only with user authorization, then restart discovery.
- Property-shape/class error: refresh selected-component metadata; use diagnostics only when ordinary authoring metadata is insufficient, then rebuild from the reported representation.
- `MCP_REF_NOT_FOUND`: fresh same-file/same-home `read`, then rebuild only the cards needing new refs.
- Placement rejection: refresh the tree and correct explicit `position`, `before`, or `after` from the current runtime result.
- Candidate verification failure: preserve the current target, correct the reported cause, and retry the write.
- Fatal apply failure: inspect or read the target first, decide from the observed state whether a new request is safe, and never replay the failed request automatically.

Prefer `structuredContent` for decisions and current stable error codes/recovery actions over remembered prose.
