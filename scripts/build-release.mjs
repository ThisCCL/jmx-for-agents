import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { buildDist } from "./build-dist-lib.mjs"
import { resolveRuntimeConfig } from "../src/runtime-config.mjs"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

export { buildDist }

export async function buildReleaseConfig({
  rootDir = projectRoot,
  runtimeJsonPath = path.join(rootDir, "config", "runtime.json"),
  releaseJsonPath = path.join(rootDir, "config", "release.json"),
  outputPath = path.join(rootDir, "src", "release-config.mjs"),
  jarPath,
} = {}) {
  const runtimeJson = JSON.parse(await readFile(runtimeJsonPath, "utf8"))
  const releaseJson = JSON.parse(await readFile(releaseJsonPath, "utf8"))
  requireExact(releaseJson.owner, "ThisCCL", "owner")
  requireExact(releaseJson.repository, "jmx-for-agents", "repository")
  requireExact(releaseJson.artifactBase, "j4a", "artifactBase")
  const runtimeVersion = requireString(runtimeJson.version, "config/runtime.json version")
  const releaseTag = requireString(runtimeJson.releaseTag, "config/runtime.json releaseTag")
  const jarName = `${releaseJson.artifactBase}-${runtimeVersion}.jar`
  const jarUrl = `https://github.com/${releaseJson.owner}/${releaseJson.repository}/releases/download/${releaseTag}/${jarName}`
  const releaseConfig = resolveRuntimeConfig({
    runtimeVersion,
    releaseTag,
    launcherProtocol: runtimeJson.launcherProtocol,
    jarUrl,
    jarSha256: runtimeJson.jarSha256,
  })
  const actualJarSha256 = await sha256File(requireString(jarPath, "jarPath"))
  if (actualJarSha256 !== releaseConfig.jarSha256) {
    throw new Error(
      `runtime jar SHA-256 mismatch: expected ${releaseConfig.jarSha256}, received ${actualJarSha256}`,
    )
  }

  await mkdir(path.dirname(outputPath), { recursive: true })
  const output = [
    "export const releaseConfig = {",
    `  runtimeVersion: ${JSON.stringify(releaseConfig.runtimeVersion)},`,
    `  releaseTag: ${JSON.stringify(releaseConfig.releaseTag)},`,
    `  launcherProtocol: ${releaseConfig.launcherProtocol},`,
    `  jarUrl: ${JSON.stringify(releaseConfig.jarUrl)},`,
    `  jarSha256: ${JSON.stringify(releaseConfig.jarSha256)},`,
  ]
  output.push("}", "")
  await writeFile(
    outputPath,
    output.join("\n"),
    "utf8",
  )
  return releaseConfig
}

export async function sha256File(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex")
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return value
}

function requireExact(value, expected, name) {
  if (value !== expected) {
    throw new TypeError(`${name} must equal ${expected}`)
  }
}

export function isDirectRun(metaUrl, argv1 = process.argv[1]) {
  if (argv1 === undefined) {
    return false
  }
  return fileURLToPath(metaUrl) === path.resolve(argv1)
}

if (isDirectRun(import.meta.url)) {
  await buildDist()
}
