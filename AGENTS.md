# Repository Agent Instructions

## Release preflight

Before committing or publishing shipped-code changes, read `RELEASE.md` completely and advance every affected artifact identity in the same change.

- Wrapper and runtime versions are independent. `package.json` owns the npm wrapper version and `v<wrapper-version>` tag; `config/runtime.json` owns the Java runtime version and `runtime-v<runtime-version>` tag.
- When Java runtime JAR inputs change, bump the runtime version, build with JDK 8, record the exact JAR digest, and run `pnpm run runtime:sync -- <runtime-jar>`.
- When packaged wrapper inputs change, bump the wrapper version and update its lockfile projection when one exists.
- When both artifacts change, bump both independently, publish and verify the runtime first, then publish the wrapper that selects it.
- Published versions are immutable. Use a new version for changed bytes; a digest-only update under an existing release tag is invalid.

Before merge, run `pnpm run release:prepare -- --tag "v$(node -p 'require(\"./package.json\").version')"`. The preflight is complete only when it exits 0, the generated projection is committed, and the wrapper version, runtime version, tags, JAR version, and digest all agree with their independent authorities.
