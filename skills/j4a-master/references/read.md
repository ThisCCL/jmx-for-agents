# `read` MCP tool

Use `read` before editing an existing JMX and for focused proof or ref recovery.

1. Inspect the live schema and start with bounded `depth` under the resolved JMeter home.
2. Select a returned target `ref` and `component`. MCP accepts exactly `properties: none|key|all|writable`; prefer `writable` for authoring and use `all` only when non-writable values are relevant.
3. Copy every mutation target and property address unchanged from the current session.
4. For required proof, focused-read the affected target/subtree and compare only the requested values, order, parentage, or structure.

Do not synthesize refs. On `MCP_REF_NOT_FOUND`, use the invocation-aware recovery action returned by the tool and refresh the same file under the same home.

The standalone CLI accepts `key|all|writable`, not `none`. A CLI-created ref from apply is valid only against that exact unchanged target snapshot; use a fresh read for every other ref or after any possible target change.
