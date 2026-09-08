# j4a Release Guide

This guide is for maintainers of `ThisCCL/jmx-for-agents`. User installation belongs in [README.md](README.md).

## Independent release identities

The npm wrapper and Java runtime are separate artifacts with separate version authorities:

| Artifact | Version authority | Tag | Publication |
| --- | --- | --- | --- |
| npm wrapper `@jmx-for-agents/j4a` | `package.json` wrapper version | `v<wrapper-version>` | npm |
| Java runtime `j4a-<runtime-version>.jar` | `config/runtime.json` runtime version | `runtime-v<runtime-version>` | GitHub Release |

Their versions do not need to match. Compatibility is controlled by `launcherProtocol`, not by version equality. Gradle embeds the runtime version into Java CLI and MCP output; `j4a --version` reports only the wrapper version.

`config/runtime.json` also owns the runtime release tag and JAR SHA-256. `src/release-config.mjs` is the packaged projection generated from that metadata and `config/release.json`.

The current runtime is a historical exception: runtime `1.0.1` already exists under tag `v1.0.1`, with SHA-256 `d83b6fb2c71b70ea380955d3fca5827b59205c9f8d4299af8756113b1e489f8d`. Keep `releaseTag: "v1.0.1"` until a new runtime is intentionally prepared. New runtime releases use `runtime-v*` tags.

## Local verification

Source builds require a JDK 8 toolchain. Runtime integration and release acceptance use Apache JMeter 5.6.3. The published JAR remains compatible with Java 8 or later; the npm wrapper supports Node.js 18 or later.

Run the normal gates before either tag:

```sh
./gradlew test
pnpm test
pnpm run verify:public
go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/*.yml
```

The combined local rehearsal accepts a wrapper tag, builds the configured runtime, verifies both version surfaces, creates the npm tarball, and records independent identities in `build/release-manifest.json`:

```sh
pnpm run release:prepare -- --tag v<wrapper-version>
pnpm run release:verify-prebuilt -- build/release/jmx-for-agents-j4a-<wrapper-version>.tgz
```

It performs no upload or publication. The npm tarball contains the wrapper modules and `dist/skills/j4a-master`; it never contains the runtime JAR.

## Publish a new runtime

Prepare the runtime before creating its tag:

1. Set `version`, `releaseTag: "runtime-v<runtime-version>"`, and `launcherProtocol` in `config/runtime.json`. A temporary digest may be used only while calculating the deterministic JAR.
2. Build the Java 8 artifact:

   ```sh
   ./gradlew clean shadowJar
   sha256sum build/libs/j4a-<runtime-version>-all.jar
   ```

3. Put that exact digest in `config/runtime.json`, then regenerate and verify the packaged projection:

   ```sh
   pnpm run runtime:sync -- build/libs/j4a-<runtime-version>-all.jar
   git diff --exit-code -- src/release-config.mjs
   ```

   The final command is expected to be clean after the generated file has been reviewed and committed.

4. Run the full gates, merge the accepted commit to `main`, and ensure an active tag ruleset includes `refs/tags/runtime-v*`.
5. Create the protected tag:

   ```sh
   git tag runtime-v<runtime-version>
   git push origin runtime-v<runtime-version>
   ```

`.github/workflows/runtime-release.yml` requires the tag, runtime metadata, `origin/main` ancestry, and ruleset to agree. It rebuilds with JDK 8 and JMeter 5.6.3, verifies the configured digest, creates or resumes the matching GitHub Release, uploads only the JAR and SHA-256 sidecar, records or reuses the attestation, and verifies the public download. It does not read from or publish to npm.

If an existing release or asset has a different identity, the workflow stops without overwriting it. After correcting an external failure, re-run the same workflow run; do not recreate the tag.

## Publish a new wrapper

Before a wrapper tag:

1. Confirm the runtime named by `src/release-config.mjs` is already public and byte-identical to `config/runtime.json`. The runtime Release must exist before the wrapper tag is pushed.
2. Bump only `package.json` and `pnpm-lock.yaml` when the Java runtime did not change.
3. Run the full gates and the local rehearsal.
4. Ensure the GitHub environment `npm-release` exists and an active ruleset includes `refs/tags/v*`.
5. Create the protected wrapper tag:

   ```sh
   git tag v<wrapper-version>
   git push origin v<wrapper-version>
   ```

`.github/workflows/release.yml` verifies the tag against the `package.json` wrapper version, validates the configured public runtime URL, SHA-256, and Java version, builds the exact npm tarball, and publishes it with provenance. It does not create or modify a GitHub Release. npm publication is the terminal mutation, with no post-publish registry assertion; the workflow does not wait for registry read-after-write propagation.

Use npm Trusted Publisher with repository `ThisCCL/jmx-for-agents`, workflow `release.yml`, and environment `npm-release`. `NPM_BOOTSTRAP_TOKEN` is only an optional short-lived bootstrap fallback. After Trusted Publisher works, delete the environment secret and revoke the token.

## Runtime then wrapper sequence

When both artifacts change, publish in this order:

1. Commit the new runtime metadata, generated wrapper config, and source changes.
2. Push `runtime-v<runtime-version>` and wait for the public runtime Release to pass.
3. Bump the wrapper version if needed, rerun local verification, and merge.
4. Push `v<wrapper-version>` so npm publishes a wrapper that points only to the already-public runtime.

For a wrapper-only change, leave `config/runtime.json` and `src/release-config.mjs` unchanged. For a runtime-only build, remember that existing npm packages keep their old descriptor; publish a later wrapper version when users should select the new runtime.
