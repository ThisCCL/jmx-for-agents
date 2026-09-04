<div align="center">

# j4a

**Give AI agents a safer way to edit Apache JMeter test plans**

Use j4a through CLI or MCP to inspect, create, change, and validate `.jmx` files against your local JMeter installation.

[简体中文](README.zh-CN.md)

</div>

## Install

```sh
npx -y @jmx-for-agents/j4a install --with-skills
```

Use Node.js 18 or later, Java 8 or later, and a local Apache JMeter 5.6.3 installation. Set `JMX_AGENT_JMETER_HOME` or `JMETER_HOME` before running j4a.

Runtime downloads honor `HTTPS_PROXY`, `HTTP_PROXY`, and `NO_PROXY` (including lowercase variants). Proxy values may be full URLs or scheme-less `host:port` values, so both `http://127.0.0.1:18888` and `127.0.0.1:18888` work.

## Quickstart

j4a supports both CLI and MCP surfaces. For iterative work, MCP is recommended because its long-lived process keeps one JVM alive and keeps the selected JMeter libraries and runtime loaded across tool calls. This avoids repeated Java process startup, JMeter library loading, and runtime initialization; structured tool discovery and results also make each call explicit.

Register the MCP stdio server according to your Agent framework's MCP configuration rules, then start or restart the Agent with `JMX_AGENT_JMETER_HOME` or `JMETER_HOME` available:

```sh
npx -y @jmx-for-agents/j4a mcp
```

The registration changes your Agent framework's MCP settings, not project files. Then ask the Agent:

> Create `j4a-first-plan.jmx` with one HTTP Request to `https://example.com/`, then validate the file. Do not run the test plan.

The installed `j4a-master` skill guides the Agent through discovery, editing, and verification.

## Why j4a

Direct XML editing creates four recurring problems for agents:

- **Fragile serialization:** JMeter relies on paired element and tree nodes, runtime class names, and nested property shapes. Well-formed XML can still be invalid JMX.
- **Expensive context:** Large plans repeat tags, class names, and empty structure. Sending the whole XML consumes context tokens that the agent could use for reasoning.
- **Risky large-file patches:** Repeated nodes and deep nesting make broad text patches more likely to target the wrong element, drop a sibling, or damage the paired tree structure.
- **Runtime-specific behavior:** Available components, plugins, and properties depend on the selected JMeter installation, so an agent cannot safely infer them from XML alone.

j4a replaces blind full-file rewriting with focused structured reads, runtime-derived references, targeted operations, and JMeter save-and-reload validation.

## What you can do

- **Inspect plans:** `read` returns structured YAML instead of raw XML.
- **Discover the runtime:** `components` and `categories` show what the selected JMeter installation supports.
- **Create and edit:** `init` creates a baseline, while `set` and `apply` make focused or structural changes.
- **Validate locally:** `validate` checks a plan without running a load test.

## CLI alternative

CLI is the one-shot, scriptable surface for commands, scripts, CI, and automation:

```sh
npx -y @jmx-for-agents/j4a init j4a-first-plan.jmx
npx -y @jmx-for-agents/j4a validate j4a-first-plan.jmx
```

The first command creates `j4a-first-plan.jmx`; the second loads, saves, reloads, and reports whether JMeter accepts it.

## Safety boundaries

- **Runtime-derived behavior:** The selected JMeter installation remains the authority for available components and properties.
- **Validated writes:** Writing commands save and reload a candidate with JMeter before committing it.
- **Explicit recovery:** After a fatal write failure, inspect the target before retrying; j4a never replays the request automatically.
- **No load execution:** j4a edits and validates plans locally. It does not run samplers or connect to test targets.

For exact authoring rules, see the [j4a-master workflow](skills/j4a-master/SKILL.md). To change j4a itself, use [CONTRIBUTING.md](CONTRIBUTING.md); release owners use [RELEASE.md](RELEASE.md).

## License

[Apache-2.0](LICENSE)
