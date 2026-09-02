# Contributing to j4a

This guide is for changing, testing, and releasing j4a. User installation and usage stay in [README.md](README.md).

## Development requirements

- Use a JDK 8 toolchain to build the Java sources. The published runtime remains compatible with Java 8 or later.
- Use Node.js 24 and the pinned `pnpm@11.5.1` toolchain for the repository scripts. The published wrapper supports Node.js 18 or later.
- Provide an Apache JMeter 5.6.3 installation through `JMX_AGENT_TEST_JMETER_HOME`, `JMETER_HOME`, or `-Dj4a.test.jmeterHome=...`.

Install the Node dependencies with the lockfile:

```sh
corepack enable
corepack prepare pnpm@11.5.1 --activate
pnpm install --frozen-lockfile
```

## Project layout

- `bin/` and `dist/` contain the Node wrapper, runtime downloader, MCP launcher, and packaged skill payload.
- `src/main/java/` contains the Java CLI, JMX operations, local JMeter integration, and MCP server.
- `skills/j4a-master/` contains the agent-facing authoring workflow distributed with the npm package.
- `openspec/specs/` contains the current behavioral contracts.
- `test/` and `src/test/` contain the Node and Java verification suites.

Scoped architecture and hard constraints live beside the Java code in `ARCHITECTURE.md` and `CONSTRAINTS.md`. Read the applicable file before changing that subsystem.

## Common checks

Run the Java and Node suites separately so a failure identifies its owning surface:

```sh
./gradlew test
pnpm test
pnpm run verify:public
```

The retained Java suite provisions many isolated JMeter scenarios and can take more than 20 minutes. During development, run the narrow owning Gradle task first, then run the full suite before an accepted release commit.

For README-only changes, run:

```sh
node --test test/readme-npm.test.mjs
```

## Runtime delivery

The npm wrapper and Java runtime are separate release artifacts:

- The npm package carries the `j4a` executable, wrapper modules, and `j4a-master` skill.
- GitHub Releases carries the version-matched runtime JAR and its SHA-256 sidecar.
- `j4a install` downloads and verifies the JAR without relying on `postinstall`.
- `j4a mcp` automatically bootstraps a missing runtime; ordinary CLI commands require the verified cache first.
- `J4A_CACHE_DIR` selects the runtime cache and `J4A_JAVA_COMMAND` selects a non-default Java executable.

Do not duplicate protocol details in the README. Keep exact command behavior in OpenSpec and the packaged `j4a-master` references so consumers and agents use one source of truth.

## Release

[RELEASE.md](RELEASE.md) owns deterministic preparation, protected tags, publication recovery, npm bootstrap credentials, and Trusted Publisher migration. Never put tokens, one-time passwords, or generated release artifacts in a commit.
