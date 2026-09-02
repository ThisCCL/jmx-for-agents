# `init` MCP tool

Use `init` when no valid base JMX exists. It creates the minimal Test Plan and Thread Group root for later J4A operations.

1. Inspect the live schema and choose an `out` path using the `init` row in [write modes](write-modes.md). On `OUTPUT_FILE_EXISTS`, choose exactly one returned recovery.
2. Supply names only when required, then call `init` under the resolved JMeter home.
3. Continue only with a successful receipt and returned target-bound Test Plan and Thread Group refs.
4. Discover every component to add and batch the requested changes in `apply`.

Start from the returned document and refs; after ref invalidation, recover according to the main skill's ref rule.
