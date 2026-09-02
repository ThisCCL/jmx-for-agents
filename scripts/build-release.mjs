import { createHash } from "node:crypto"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { buildDist } from "./build-dist-lib.mjs"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

export { buildDist }

export async function buildReleaseConfig({
  rootDir = projectRoot,
  packageJsonPath = path.join(rootDir, "package.json"),
  releaseJsonPath = path.join(rootDir, "config", "release.json"),
  outputPath = path.join(rootDir, "src", "release-config.mjs"),
  jarPath,
} = {}) {
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8"))
  const releaseJson = JSON.parse(await readFile(releaseJsonPath, "utf8"))
  const version = requireString(packageJson.version, "package.json version")
  requireExact(releaseJson.owner, "ThisCCL", "owner")
  requireExact(releaseJson.repository, "jmx-for-agents", "repository")
  requireExact(releaseJson.artifactBase, "j4a", "artifactBase")
  const jarSha256 = await sha256File(requireString(jarPath, "jarPath"))
  const jarName = `${releaseJson.artifactBase}-${version}.jar`
  const jarUrl = `https://github.com/${releaseJson.owner}/${releaseJson.repository}/releases/download/v${version}/${jarName}`

  await mkdir(path.dirname(outputPath), { recursive: true })
  const releaseConfig = [
    "export const releaseConfig = {",
    `  jarUrl: ${JSON.stringify(jarUrl)},`,
    `  jarSha256: ${JSON.stringify(jarSha256)},`,
  ]
  releaseConfig.push("}", "")
  await writeFile(
    outputPath,
    releaseConfig.join("\n"),
    "utf8",
  )
  return { jarUrl, jarSha256 }
}

export async function sha256File(filePath) {
  return createHash("sha256").update(await readFile(filePath)).digest("hex")
}

function requireString(value, name) {
  if (typeof value !== "string") {
    throw new TypeError(`${name} must be a string`)
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
