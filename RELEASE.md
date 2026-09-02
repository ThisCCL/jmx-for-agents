# j4a Release Guide

This guide is for the owner of the public GitHub Release and npm publication workflow. Users install the package through [README.md](README.md); maintainers assemble release inputs locally, then let the protected-tag workflow publish the exact prepared bytes.

## Release identity

The first release is fixed to these public identities:

| Item | Value |
| --- | --- |
| GitHub repository | `ThisCCL/jmx-for-agents` |
| npm package | `@jmx-for-agents/j4a` |
| First tag and version | `v1.0.0` / `1.0.0` |
| JAR | `j4a-1.0.0.jar` |
| Checksum | `j4a-1.0.0.jar.sha256` |
| npm tarball | `jmx-for-agents-j4a-1.0.0.tgz` |
| Public JAR URL | `https://github.com/ThisCCL/jmx-for-agents/releases/download/v1.0.0/j4a-1.0.0.jar` |

The npm wrapper supports Node.js 18 or later. Its JAR requires Java 8 or later and validates against a local Apache JMeter 5.6.3 installation.

## Prepare release inputs locally

Building and preparing a release from source requires a locally installed JDK 8 toolchain. The published JAR remains compatible with Java 8 or later.

Run the local rehearsal from the accepted checkout:

```sh
pnpm run release:prepare -- --tag v1.0.0
```

It verifies version agreement, builds one deterministic JAR, generates the runtime configuration, creates one npm tarball, and smoke-tests that same tarball. It performs no upload or publication and does not contact GitHub or the npm registry.

Inspect the exact inputs before creating a real tag:

```sh
pnpm run release:verify-prebuilt -- build/release/jmx-for-agents-j4a-1.0.0.tgz
```

`build/release-manifest.json` records the JAR SHA-256, the tarball SHA-512 and integrity value, its inventory, and the tarball identity used by the smoke test. The npm package includes `bin/j4a.js`, `dist/**/*.mjs`, `dist/skills/j4a-master/**`, `README.md`, and `LICENSE`; it does not carry the runtime JAR.

Local `pnpm pack` is not a publication path. The protected workflow publishes the npm package only from the exact prepared tarball.

## Configure the first release

Complete these owner operations before pushing `v1.0.0`:

1. Confirm that the npm organization `jmx-for-agents` owns `@jmx-for-agents/j4a`.
2. Create the GitHub environment `npm-release` for the repository.
3. Activate a GitHub ruleset whose ref-name condition includes `refs/tags/v*`. The workflow checks this active rule before it changes a Release or npm.
4. Put a short-lived one-time publish token in the `npm-release` environment secret named `NPM_BOOTSTRAP_TOKEN`. Store only the token value in GitHub, never in this repository or a command history.
5. Merge the accepted release commit to `main`, prepare the exact inputs above, then create and push the protected tag:

```sh
git tag v1.0.0
git push origin v1.0.0
```

A pushed `v*.*.*` tag starts `.github/workflows/release.yml`. Its preflight permits remote release mutation only when the tag equals `v<package version>`, its commit is reachable from `origin/main`, and an active `refs/tags/v*` ruleset exists.

After the registry identity for `@jmx-for-agents/j4a@1.0.0` is verified, bind npm Trusted Publisher to GitHub owner `ThisCCL`, repository `jmx-for-agents`, workflow file `release.yml`, and environment `npm-release`. Then delete the environment secret and revoke the token, even if the Trusted Publisher setup is delayed.

Version `1.0.0` proves the one-time token bootstrap and provenance path. Its honest terminal state is **OIDC-only configured**. An OIDC-only publication is proven only by a future version after the bootstrap secret is gone.

## What the protected-tag workflow does

The workflow runs the Java, Node, and public-verification suites, then prepares one release JAR and one prebuilt tarball, smoke-tests that exact tarball, and keeps their identities together. It creates or reuses a matching draft GitHub Release, uploads only missing canonical assets, records or reuses the JAR attestation, verifies authenticated asset bytes, publishes the Release, checks the anonymous public JAR download, and only then publishes the exact prebuilt npm tarball with provenance.

The workflow compares remote SHA-256 and npm integrity values before it reuses anything. It never overwrites a same-name object whose identity differs.

## Recover a partial release

After correcting the external condition, in GitHub Actions re-run the existing workflow run for this tag. Do not recreate or push the tag again. The workflow is intentionally resumable only when every existing identity matches the locally prepared release.

| Remote state | Workflow result | Owner action |
| --- | --- | --- |
| All objects absent | Creates a draft, uploads both assets, records an attestation, verifies, publishes the Release, then publishes npm. | In GitHub Actions, re-run the existing workflow run for this tag after confirming the local manifest. |
| Partial draft release | Reuses the matching draft and uploads only the missing JAR or checksum. | In GitHub Actions, re-run the existing workflow run for this tag; do not replace an existing asset. |
| Complete matching draft | Reuses assets, verifies or records the attestation, then publishes. | In GitHub Actions, re-run the existing workflow run for this tag. |
| Attestation already present | Reuses the attestation and still verifies it with the authenticated release assets. | In GitHub Actions, re-run the existing workflow run for this tag. |
| Published GitHub Release with npm absent | Reuses the published Release, verifies the public JAR download, then publishes the exact tarball. | In GitHub Actions, re-run the existing workflow run for this tag after resolving npm availability. |
| npm version already present | Compares registry `dist.integrity` with the local prebuilt tarball. | Treat an exact integrity match as complete; otherwise stop. |
| Identity mismatch | Stops with no overwrite or later publication. | Resolve the mismatch outside the workflow; do not retry by replacing remote bytes. |
| Public-download check fails | Stops before npm publication. | Restore the Release download path, then in GitHub Actions re-run the existing workflow run for this tag. |

## Verify a completed release

1. Confirm the published Release is `v1.0.0` and has `j4a-1.0.0.jar` plus `j4a-1.0.0.jar.sha256`.
2. Download the public JAR URL and compare it with the SHA-256 sidecar.
3. Confirm the JAR attestation against `ThisCCL/jmx-for-agents`.
4. Confirm npm reports the expected `dist.integrity` for `@jmx-for-agents/j4a@1.0.0`.
5. Install the published package in a disposable consumer, run `j4a install`, then run `j4a --help`.
6. Confirm the `NPM_BOOTSTRAP_TOKEN` secret has been deleted and the one-time token revoked; retain only the Trusted Publisher binding for later versions.

## Runtime package behavior

The published wrapper downloads the public JAR during explicit `j4a install` and verifies its SHA-256 in the local cache. Ordinary `j4a read`, `set`, `validate`, `components`, `categories`, and `apply` commands use that cache and fail fast when it is absent. `j4a mcp` is the automatic-bootstrap exception.

Set `J4A_CACHE_DIR` to choose the cache location and `J4A_JAVA_COMMAND` when Java is not available as `java` on `PATH`.

## 1.0.0 compatibility notes

- Category continuation uses cursor v4. Cursor v3 is intentionally rejected; consumers restart at the first category page. Oversized complete details return a bounded `componentToken` for stateless exact-detail recovery.
- Apply usage/schema errors exit 2, accepted semantic errors exit 3, and filesystem/infrastructure/fatal failures exit 4. Fatal means unknown outcome: inspect or read the target before deciding whether to retry, with no automatic replay.
- Public generic TestBean identity is the selected-runtime exact FQCN (`org.apache.jmeter.config.CSVDataSet`, for example), never the shared `TestBeanGUI` persistence class.
- Successful apply receipts add declaration-ordered, value-free `changeResults`. Surviving adds retain `createdRefs`; CLI-created refs are valid only for the exact unchanged target, while every other or stale target requires a fresh read.
