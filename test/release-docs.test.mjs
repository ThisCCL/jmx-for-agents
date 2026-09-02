import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const releaseDocPath = process.env.J4A_RELEASE_DOC_PATH ?? "RELEASE.md"

async function readReleaseDoc() {
  return readFile(releaseDocPath, "utf8")
}

test("RELEASE documents packaged skill assets in the npm artifact flow", async () => {
  const releaseDoc = await readReleaseDoc()

  assert.match(releaseDoc, /skills\/j4a-master/)
  assert.match(releaseDoc, /dist\/skills\/j4a-master|packaged skill payload/i)
  assert.match(releaseDoc, /pnpm pack|publish the npm package/i)
})

test("RELEASE preserves the local public release assembly contract", async () => {
  const releaseDoc = await readReleaseDoc()
  const forbidden = [
    ["Mi", "nIO"].join(""),
    ["MI", "NIO_"].join(""),
    ["10", ".50", ".186", ".99"].join(""),
    ["release", ":jar"].join(""),
    ["jar", "BaseUrl"].join(""),
    ["agent", "-tools"].join(""),
  ]

  assert.match(releaseDoc, /pnpm run release:prepare -- --tag v1\.0\.0/)
  assert.match(releaseDoc, /requires a locally installed JDK 8 toolchain/)
  assert.match(releaseDoc, /published JAR remains compatible with Java 8 or later/)
  assert.match(releaseDoc, /ThisCCL\/jmx-for-agents/)
  assert.match(releaseDoc, /j4a-1\.0\.0\.jar/)
  assert.match(releaseDoc, /j4a-1\.0\.0\.jar\.sha256/)
  assert.match(releaseDoc, /jmx-for-agents-j4a-1\.0\.0\.tgz/)
  assert.match(releaseDoc, /performs no upload or publication/i)
  assert.match(releaseDoc, /SHA-256/)
  assert.match(releaseDoc, /SHA-512|integrity/)
  for (const token of forbidden) {
    assert.equal(releaseDoc.toLowerCase().includes(token.toLowerCase()), false, `obsolete release token: ${token}`)
  }
})

test("RELEASE states the protected-tag workflow and owner handoff", async () => {
  const releaseDoc = await readReleaseDoc()
  const required = [
    /ThisCCL\/jmx-for-agents/,
    /@jmx-for-agents\/j4a/,
    /release\.yml/,
    /npm-release/,
    /refs\/tags\/v\*/,
    /NPM_BOOTSTRAP_TOKEN/,
    /Trusted Publisher/,
    /delete the environment secret and revoke the token/i,
    /OIDC-only configured/,
    /future version/i,
    /https:\/\/github\.com\/ThisCCL\/jmx-for-agents\/releases\/download\/v1\.0\.0\/j4a-1\.0\.0\.jar/,
    /A pushed `v\*\.\*\.\*` tag starts/,
    /preflight permits remote release mutation/,
    /re-run the existing workflow run for this tag/,
    /Do not recreate or push the tag again/,
    /runs the Java, Node, and public-verification suites, then prepares one release JAR/,
  ]

  for (const expected of required) assert.match(releaseDoc, expected)
})

test("RELEASE defines retry-safe recovery for every remote release state", async () => {
  const releaseDoc = await readReleaseDoc()

  for (const expected of [
    /All objects absent/,
    /Partial draft release/,
    /Identity mismatch/,
    /Published GitHub Release.*npm/i,
    /npm version already present/i,
    /Attestation already present/i,
    /no overwrite/i,
  ]) {
    assert.match(releaseDoc, expected)
  }
})
