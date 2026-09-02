import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import test from "node:test"

import { assertPreparedReleaseContract, normalizeInventoryPath } from "./helpers/prepared-release-artifact.mjs"

const JAR_SHA256 = "20c5c7c9adee6c11c919fde7b1579934daf9be9147c4139abf39c2500f47abe0"
const TARBALL_SHA512 = "a4c4e5e106e853bba28f044475e9ddb04ac5c2080c8f0e6715ea103c9e0d4e5bef84dbb51e3cfedc806f2d3e9564bd15ededc04f34dab4dff8c684f0cda25b1f"
const PUBLIC_INVENTORY = [
  "package/LICENSE",
  "package/README.md",
  "package/bin/j4a.js",
  "package/dist/main.mjs",
  "package/dist/release-config.mjs",
  "package/dist/runtime.mjs",
  "package/dist/skills/j4a-master/SKILL.md",
  "package/package.json",
]

function preparedManifest(inventory) {
  return {
    tag: "v1.0.0",
    version: "1.0.0",
    jar: {
      file: "build/release/j4a-1.0.0.jar",
      checksumFile: "build/release/j4a-1.0.0.jar.sha256",
      url: "https://github.com/ThisCCL/jmx-for-agents/releases/download/v1.0.0/j4a-1.0.0.jar",
      sha256: JAR_SHA256,
    },
    tarball: {
      file: "build/release/jmx-for-agents-j4a-1.0.0.tgz",
      sha512: TARBALL_SHA512,
      integrity: `sha512-${Buffer.from(TARBALL_SHA512, "hex").toString("base64")}`,
      inventory,
    },
    smoke: {
      tarballFile: "build/release/jmx-for-agents-j4a-1.0.0.tgz",
      tarballSha512: TARBALL_SHA512,
    },
  }
}

test("Given a prepared public artifact, when its inventory contains only runtime and public docs, then the contract accepts the exact release", () => {
  const manifest = preparedManifest([
    "package/LICENSE",
    "package/README.md",
    "package/bin/j4a.js",
    "package/dist/main.mjs",
    "package/dist/release-config.mjs",
    "package/dist/runtime.mjs",
    "package/dist/skills/j4a-master/SKILL.md",
    "package/package.json",
  ])

  assert.doesNotThrow(() => assertPreparedReleaseContract({
    manifest,
    releaseFiles: [
      "j4a-1.0.0.jar",
      "j4a-1.0.0.jar.sha256",
      "jmx-for-agents-j4a-1.0.0.tgz",
    ],
    jarSha256: JAR_SHA256,
    tarballSha512: TARBALL_SHA512,
  }))
})

test("Given a prepared public artifact, when its npm inventory adds a source or runtime JAR file, then the contract rejects it", () => {
  const manifest = preparedManifest([...PUBLIC_INVENTORY, "package/src/main.mjs"])

  assert.throws(
    () => assertPreparedReleaseContract({
      manifest,
      releaseFiles: [
        "j4a-1.0.0.jar",
        "j4a-1.0.0.jar.sha256",
        "jmx-for-agents-j4a-1.0.0.tgz",
      ],
      jarSha256: JAR_SHA256,
      tarballSha512: TARBALL_SHA512,
    }),
    /forbidden npm package entry: package\/src\/main\.mjs/,
  )
})

test("Given a prepared public artifact, when a canonical release file is missing, then the contract rejects it", () => {
  const manifest = preparedManifest([
    "package/LICENSE",
    "package/README.md",
    "package/bin/j4a.js",
    "package/dist/main.mjs",
    "package/dist/runtime.mjs",
    "package/dist/skills/j4a-master/SKILL.md",
    "package/package.json",
  ])

  assert.throws(
    () => assertPreparedReleaseContract({
      manifest,
      releaseFiles: ["j4a-1.0.0.jar", "jmx-for-agents-j4a-1.0.0.tgz"],
      jarSha256: JAR_SHA256,
      tarballSha512: TARBALL_SHA512,
    }),
    /j4a-1\.0\.0\.jar\.sha256/,
  )
})

for (const entry of [
  "package/build.gradle",
  "package/build.gradle.kts",
  "package/settings.gradle",
  "package/settings.gradle.kts",
  "package/gradle.properties",
  "package/gradle/wrapper/gradle-wrapper.jar",
]) {
  test(`Given a prepared public artifact, when its npm inventory adds ${entry}, then the contract rejects it`, () => {
    const manifest = preparedManifest([...PUBLIC_INVENTORY, entry])
    assert.throws(
      () => assertPreparedReleaseContract({
        manifest,
        releaseFiles: ["j4a-1.0.0.jar", "j4a-1.0.0.jar.sha256", "jmx-for-agents-j4a-1.0.0.tgz"],
        jarSha256: JAR_SHA256,
        tarballSha512: TARBALL_SHA512,
      }),
      new RegExp(`forbidden npm package entry: ${escapeRegExp(entry)}`),
    )
  })
}

test("Given a Windows skill path, when it becomes an npm inventory key, then it uses slash separators", () => {
  assert.equal(normalizeInventoryPath("references\\read.md", "\\"), "references/read.md")
})

test("release rehearsal asserts packaged compatibility guidance and all three version surfaces", () => {
  const rehearsal = readFileSync(new URL("./release-rehearsal.mjs", import.meta.url), "utf8")
  for (const observable of [
    "Cursor v3 is rejected",
    "accepted apply semantic failures exit 3",
    "exact selected-runtime FQCN",
    "exact unchanged target",
    "inspect/read the target before any retry",
    "serverInfo?.version",
    "javaVersion.stdout.trim()",
    "packageVersion.stdout.trim()",
  ]) assert.match(rehearsal, new RegExp(escapeRegExp(observable)))
})

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}
