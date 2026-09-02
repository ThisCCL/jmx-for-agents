import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { pathToFileURL } from "node:url"

import { buildReleaseConfig, isDirectRun } from "../scripts/build-release.mjs"

const GRADLE_DISTRIBUTION_SHA256 = "845952a9d6afa783db70bb3b0effaae45ae5542ca2bb7929619e8af49cb634cf"
const GRADLE_WRAPPER_JAR_SHA256 = "7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172"

test("public source pins the official Gradle wrapper inputs", async () => {
  const [ignore, properties, wrapperJar] = await Promise.all([
    readFile(".gitignore", "utf8"),
    readFile("gradle/wrapper/gradle-wrapper.properties", "utf8"),
    readFile("gradle/wrapper/gradle-wrapper.jar"),
  ])

  assert.match(ignore, /^!gradle\/wrapper\/gradle-wrapper\.jar$/m)
  assert.match(properties, /^distributionUrl=https\\:\/\/services\.gradle\.org\/distributions\/gradle-8\.14\.1-bin\.zip$/m)
  assert.match(properties, new RegExp(`^distributionSha256Sum=${GRADLE_DISTRIBUTION_SHA256}$`, "m"))
  assert.equal(createHash("sha256").update(wrapperJar).digest("hex"), GRADLE_WRAPPER_JAR_SHA256)
})

test("public release coordinates and generated config are exact", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-config-"))
  const jarBytes = Buffer.from("release jar bytes")
  const jarSha256 = createHash("sha256").update(jarBytes).digest("hex")
  try {
    await writeFile(path.join(root, "package.json"), JSON.stringify({ version: "1.0.0" }))
    await writeFile(path.join(root, "release.json"), JSON.stringify({
      owner: "ThisCCL",
      repository: "jmx-for-agents",
      artifactBase: "j4a",
    }))
    const jarPath = path.join(root, "j4a-1.0.0.jar")
    const outputPath = path.join(root, "release-config.mjs")
    await writeFile(jarPath, jarBytes)

    const result = await buildReleaseConfig({
      packageJsonPath: path.join(root, "package.json"),
      releaseJsonPath: path.join(root, "release.json"),
      outputPath,
      jarPath,
    })

    const jarUrl = "https://github.com/ThisCCL/jmx-for-agents/releases/download/v1.0.0/j4a-1.0.0.jar"
    assert.deepEqual(result, { jarUrl, jarSha256 })
    assert.equal(await readFile(outputPath, "utf8"), [
      "export const releaseConfig = {",
      `  jarUrl: ${JSON.stringify(jarUrl)},`,
      `  jarSha256: ${JSON.stringify(jarSha256)},`,
      "}",
      "",
    ].join("\n"))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("public release config rejects malformed coordinates and missing JAR", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-config-"))
  try {
    await writeFile(path.join(root, "package.json"), JSON.stringify({ version: "1.0.0" }))
    const releaseJsonPath = path.join(root, "release.json")
    await writeFile(releaseJsonPath, JSON.stringify({
      owner: "ThisCCL",
      repository: "wrong-repository",
      artifactBase: "j4a",
    }))

    await assert.rejects(buildReleaseConfig({
      packageJsonPath: path.join(root, "package.json"),
      releaseJsonPath,
      outputPath: path.join(root, "release-config.mjs"),
      jarPath: path.join(root, "missing.jar"),
    }), /repository must equal jmx-for-agents/)

    await writeFile(releaseJsonPath, JSON.stringify({
      owner: "ThisCCL",
      repository: "jmx-for-agents",
      artifactBase: "j4a",
    }))
    await assert.rejects(buildReleaseConfig({
      packageJsonPath: path.join(root, "package.json"),
      releaseJsonPath,
      outputPath: path.join(root, "release-config.mjs"),
      jarPath: path.join(root, "missing.jar"),
    }), /ENOENT/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("build-release direct-run guard recognizes Windows and POSIX argv paths", () => {
  const scriptPath = path.resolve("scripts", "build-release.mjs")
  const metaUrl = pathToFileURL(scriptPath).href

  assert.equal(isDirectRun(metaUrl, scriptPath), true)
  assert.equal(isDirectRun(metaUrl, path.join("scripts", "build-release.mjs")), true)
  assert.equal(isDirectRun(metaUrl, path.resolve("scripts", "release.mjs")), false)
})
