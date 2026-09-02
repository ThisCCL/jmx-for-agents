# `set` MCP tool

Use `set` for one low-risk known property on one existing component. Use `apply` for structural/related changes or any write requiring dry-run.

1. Inspect the live schema, then copy the current target ref and non-empty scalar-array property address from `read`.
2. If type or shape is unknown, use selected-component `components`; follow [structured collections](structured-collections.md) for a reported non-scalar.
3. Choose exactly one destination intent using [write modes](write-modes.md). Default to a new, different `out` path.
4. Require the successful receipt. Focused read-back is needed only when exact observable semantics require proof.

Property addresses select existing graph nodes; they do not create entries. Before a later edit to a copied output, read that target and use its target-bound refs.
