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

test("checked-in wrapper runtime config matches the independent runtime authority", async () => {
  const runtime = JSON.parse(await readFile("config/runtime.json", "utf8"))
  const moduleUrl = `${pathToFileURL(path.resolve("src/release-config.mjs")).href}?test=${Date.now()}`
  const { releaseConfig } = await import(moduleUrl)

  assert.equal(releaseConfig.runtimeVersion, runtime.version)
  assert.equal(releaseConfig.releaseTag, runtime.releaseTag)
  assert.equal(releaseConfig.launcherProtocol, runtime.launcherProtocol)
  assert.equal(releaseConfig.jarSha256, runtime.jarSha256)
  assert.match(releaseConfig.jarUrl, new RegExp(`/releases/download/${runtime.releaseTag}/j4a-${runtime.version}\\.jar$`))
})

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
    const runtimeJsonPath = path.join(root, "runtime.json")
    await writeFile(runtimeJsonPath, JSON.stringify({
      version: "7.6.5",
      releaseTag: "runtime-v7.6.5",
      launcherProtocol: 1,
      jarSha256,
    }))
    await writeFile(path.join(root, "release.json"), JSON.stringify({
      owner: "ThisCCL",
      repository: "jmx-for-agents",
      artifactBase: "j4a",
    }))
    const jarPath = path.join(root, "j4a-7.6.5.jar")
    const outputPath = path.join(root, "release-config.mjs")
    await writeFile(jarPath, jarBytes)

    const result = await buildReleaseConfig({
      runtimeJsonPath,
      releaseJsonPath: path.join(root, "release.json"),
      outputPath,
      jarPath,
    })

    const jarUrl = "https://github.com/ThisCCL/jmx-for-agents/releases/download/runtime-v7.6.5/j4a-7.6.5.jar"
    assert.deepEqual(result, {
      runtimeVersion: "7.6.5",
      releaseTag: "runtime-v7.6.5",
      launcherProtocol: 1,
      jarUrl,
      jarSha256,
    })
    assert.equal(await readFile(outputPath, "utf8"), [
      "export const releaseConfig = {",
      '  runtimeVersion: "7.6.5",',
      '  releaseTag: "runtime-v7.6.5",',
      "  launcherProtocol: 1,",
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
    const runtimeJsonPath = path.join(root, "runtime.json")
    await writeFile(runtimeJsonPath, JSON.stringify({
      version: "1.0.0",
      releaseTag: "runtime-v1.0.0",
      launcherProtocol: 1,
      jarSha256: "0".repeat(64),
    }))
    const releaseJsonPath = path.join(root, "release.json")
    await writeFile(releaseJsonPath, JSON.stringify({
      owner: "ThisCCL",
      repository: "wrong-repository",
      artifactBase: "j4a",
    }))

    await assert.rejects(buildReleaseConfig({
      runtimeJsonPath,
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
      runtimeJsonPath,
      releaseJsonPath,
      outputPath: path.join(root, "release-config.mjs"),
      jarPath: path.join(root, "missing.jar"),
    }), /ENOENT/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("public release config rejects a jar that differs from runtime metadata", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-config-"))
  try {
    const runtimeJsonPath = path.join(root, "runtime.json")
    const releaseJsonPath = path.join(root, "release.json")
    const jarPath = path.join(root, "j4a-1.0.0.jar")
    await writeFile(runtimeJsonPath, JSON.stringify({
      version: "1.0.0",
      releaseTag: "runtime-v1.0.0",
      launcherProtocol: 1,
      jarSha256: "0".repeat(64),
    }))
    await writeFile(releaseJsonPath, JSON.stringify({
      owner: "ThisCCL",
      repository: "jmx-for-agents",
      artifactBase: "j4a",
    }))
    await writeFile(jarPath, "different bytes")

    await assert.rejects(buildReleaseConfig({
      runtimeJsonPath,
      releaseJsonPath,
      outputPath: path.join(root, "release-config.mjs"),
      jarPath,
    }), /runtime jar SHA-256 mismatch/)
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
