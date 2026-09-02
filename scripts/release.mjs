import { spawn } from "node:child_process"
import { createHash } from "node:crypto"
import {
  access,
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"

import { buildDist, buildReleaseConfig, sha256File } from "./build-release.mjs"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const MCP_MAIN_CLASS = "io.github.thisccl.j4a.mcp.J4aMcpServer"
const SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/

export async function verifyVersionSync({
  tag,
  packageJsonPath = path.join(projectRoot, "package.json"),
} = {}) {
  const packageJson = JSON.parse(await readFile(packageJsonPath, "utf8"))
  const npmVersion = requireString(packageJson.version, "package.json version")
  if (!SEMVER_PATTERN.test(npmVersion)) {
    throw new Error("package.json version must be SemVer-compatible")
  }
  const expectedTag = `v${npmVersion}`
  if (tag !== expectedTag) {
    throw new Error(`tag must equal ${expectedTag}; received ${String(tag)}`)
  }
  return npmVersion
}

export async function verifyBuiltVersionSurfaces({
  root = projectRoot,
  jarPath,
  version,
  runSurface = runVersionSurfaceDefault,
} = {}) {
  const expectedVersion = requireString(version, "version")
  const surfaces = ["wrapper", "java", "mcp"]
  for (const surface of surfaces) {
    const result = await runSurface(surface, { root, jarPath })
    if (result.stderr !== "") {
      throw new Error(`${surface} version wrote stderr: ${result.stderr.trim()}`)
    }
    const actualVersion = surface === "mcp"
      ? mcpServerVersion(result.stdout)
      : exactVersionOutput(result.stdout)
    if (actualVersion !== expectedVersion) {
      throw new Error(`${surface} version mismatch: expected ${expectedVersion}, received ${actualVersion}`)
    }
  }
  return expectedVersion
}

export async function prepareRelease({
  root = projectRoot,
  tag,
  runGradle = runGradleDefault,
  verifyLicenses = verifyLicensesDefault,
  verifyVersions = verifyBuiltVersionSurfaces,
  smoke = smokeTarballDefault,
} = {}) {
  const version = await verifyVersionSync({
    tag,
    packageJsonPath: path.join(root, "package.json"),
  })
  const releaseDir = path.join(root, "build", "release")
  const manifestPath = path.join(root, "build", "release-manifest.json")
  if (await pathExists(releaseDir) || await pathExists(manifestPath)) {
    throw new Error("release output already exists; refusing a second pack")
  }

  await runGradle({ command: gradleCommand(), args: ["clean", "shadowJar"], cwd: root })
  const shadowJar = await findOnlyShadowJar(path.join(root, "build", "libs"), version)
  await mkdir(releaseDir, { recursive: true })
  const jarName = `j4a-${version}.jar`
  const jarPath = path.join(releaseDir, jarName)
  await copyFile(shadowJar, jarPath)
  const jarSha256 = await sha256File(jarPath)
  await verifyLicenses(jarPath, { root })
  const checksumPath = `${jarPath}.sha256`
  await writeFile(checksumPath, `${jarSha256}  ${jarName}\n`, "utf8")

  const releaseConfig = await buildReleaseConfig({
    rootDir: root,
    packageJsonPath: path.join(root, "package.json"),
    releaseJsonPath: path.join(root, "config", "release.json"),
    outputPath: path.join(root, "src", "release-config.mjs"),
    jarPath,
  })
  await buildDist({ rootDir: root })
  await verifyVersions({ root, jarPath, version })

  const tarballName = `jmx-for-agents-j4a-${version}.tgz`
  await packOnce({ root, releaseDir })
  const releaseFiles = await readdir(releaseDir)
  const tarballs = releaseFiles.filter(file => file.endsWith(".tgz"))
  if (tarballs.length !== 1 || tarballs[0] !== tarballName) {
    throw new Error(`expected exactly ${tarballName}; found ${tarballs.join(", ") || "no tarball"}`)
  }
  const tarballPath = path.join(releaseDir, tarballName)
  const tarballBytes = await readFile(tarballPath)
  const tarballSha512 = sha512(tarballBytes)
  const inventory = await tarInventory(tarballPath)
  await smoke(tarballPath, { root })
  if (sha512(await readFile(tarballPath)) !== tarballSha512) {
    throw new Error("smoke test modified the prebuilt tarball")
  }

  const manifest = {
    schemaVersion: 1,
    tag,
    version,
    jar: {
      file: `build/release/${jarName}`,
      checksumFile: `build/release/${jarName}.sha256`,
      url: releaseConfig.jarUrl,
      sha256: jarSha256,
    },
    tarball: {
      file: `build/release/${tarballName}`,
      sha512: tarballSha512,
      integrity: `sha512-${Buffer.from(tarballSha512, "hex").toString("base64")}`,
      inventory,
    },
    smoke: {
      tarballFile: `build/release/${tarballName}`,
      tarballSha512,
    },
  }
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8")
  return { jarPath, checksumPath, tarballPath, manifestPath, manifest }
}

export function rejectOrdinaryPack() {
  throw new Error("ordinary npm pack is disabled; use pnpm run release:prepare -- --tag v<version>")
}

export async function assertPreparedTarball({
  root = projectRoot,
  manifestPath = path.join(root, "build", "release-manifest.json"),
  tarballPath,
} = {}) {
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"))
  const expectedPath = path.join(root, manifest.tarball.file)
  const inputPath = path.resolve(requireString(tarballPath, "tarballPath"))
  if (path.basename(inputPath) !== path.basename(expectedPath) || inputPath !== path.resolve(expectedPath)) {
    throw new Error(`publish input must be ${path.basename(expectedPath)}`)
  }
  const actualSha512 = sha512(await readFile(inputPath))
  if (actualSha512 !== manifest.tarball.sha512 || actualSha512 !== manifest.smoke.tarballSha512) {
    throw new Error("prebuilt tarball SHA-512 mismatch")
  }
  return inputPath
}

export async function assertPreparedRelease({
  root = projectRoot,
  manifestPath = path.join(root, "build", "release-manifest.json"),
} = {}) {
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"))
  await verifyVersionSync({
    tag: manifest.tag,
    packageJsonPath: path.join(root, "package.json"),
  })
  const jarPath = path.join(root, manifest.jar.file)
  if (await sha256File(jarPath) !== manifest.jar.sha256) {
    throw new Error("prepared JAR SHA-256 mismatch")
  }
  const checksum = await readFile(path.join(root, manifest.jar.checksumFile), "utf8")
  if (checksum !== `${manifest.jar.sha256}  ${path.basename(jarPath)}\n`) {
    throw new Error("prepared JAR checksum sidecar mismatch")
  }
  const expectedConfig = [
    "export const releaseConfig = {",
    `  jarUrl: ${JSON.stringify(manifest.jar.url)},`,
    `  jarSha256: ${JSON.stringify(manifest.jar.sha256)},`,
    "}",
    "",
  ].join("\n")
  if (await readFile(path.join(root, "src", "release-config.mjs"), "utf8") !== expectedConfig) {
    throw new Error("generated release config is stale")
  }
  const tarballPath = path.join(root, manifest.tarball.file)
  await assertPreparedTarball({ root, manifestPath, tarballPath })
  if (manifest.tarball.integrity !== `sha512-${Buffer.from(manifest.tarball.sha512, "hex").toString("base64")}`) {
    throw new Error("prebuilt tarball integrity mismatch")
  }
  if (JSON.stringify(await tarInventory(tarballPath)) !== JSON.stringify(manifest.tarball.inventory)) {
    throw new Error("prebuilt tarball inventory mismatch")
  }
  return manifest
}

async function findOnlyShadowJar(libsDir, version) {
  let files = []
  try {
    files = await readdir(libsDir)
  } catch (error) {
    if (error?.code !== "ENOENT") throw error
  }
  const jars = files.filter(file => file.endsWith(`-${version}-all.jar`))
  if (jars.length !== 1) {
    throw new Error(`expected exactly one shadow JAR, found ${jars.length}`)
  }
  return path.join(libsDir, jars[0])
}

async function packOnce({ root, releaseDir }) {
  await runCommand(pnpmCommand(), ["--config.ignore-scripts=true", "pack", "--pack-destination", releaseDir], {
    cwd: root,
    capture: true,
  })
}

async function tarInventory(tarballPath) {
  const { stdout } = await runCommand("tar", ["-tzf", tarballPath], { capture: true })
  return stdout.trim().split("\n").filter(Boolean).sort()
}

async function runGradleDefault({ command, args, cwd }) {
  await runCommand(command, args, { cwd })
}

async function verifyLicensesDefault(jarPath, { root }) {
  await runCommand(pnpmCommand(), ["run", "verify:licenses", "--", jarPath], { cwd: root })
}

async function smokeTarballDefault(tarballPath, { root = projectRoot } = {}) {
  const consumer = await mkdtemp(path.join(tmpdir(), "j4a-release-smoke-"))
  try {
    await writeFile(path.join(consumer, "package.json"), '{"private":true}\n', "utf8")
    await runCommand(npmCommand(), [
      "install",
      "--ignore-scripts",
      "--offline",
      "--no-audit",
      "--no-fund",
      tarballPath,
    ], { cwd: consumer, capture: true })
    const installedBin = path.join(consumer, "node_modules", "@jmx-for-agents", "j4a", "bin", "j4a.js")
    const packageJson = JSON.parse(await readFile(path.join(root, "package.json"), "utf8"))
    const expectedVersion = requireString(packageJson.version, "package.json version")
    const installedVersion = await runCommand(process.execPath, [installedBin, "--version"], {
      cwd: consumer,
      capture: true,
    })
    if (installedVersion.stdout !== `${expectedVersion}\n` || installedVersion.stderr !== "") {
      throw new Error("installed package version mismatch")
    }
    await runCommand(process.execPath, [installedBin, "--help"], { cwd: consumer, capture: true })
  } finally {
    await rm(consumer, { recursive: true, force: true })
  }
}

function runCommand(command, args, { cwd, capture = false, input } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(commandForPlatform(command), argsForPlatform(command, args), {
      cwd,
      stdio: capture ? [input === undefined ? "ignore" : "pipe", "pipe", "pipe"] : "inherit",
    })
    let stdout = ""
    let stderr = ""
    if (capture) {
      child.stdout.setEncoding("utf8")
      child.stderr.setEncoding("utf8")
      child.stdout.on("data", chunk => { stdout += chunk })
      child.stderr.on("data", chunk => { stderr += chunk })
      if (input !== undefined) child.stdin.end(input)
    }
    child.once("error", reject)
    child.once("exit", code => {
      if (code === 0) {
        resolve({ stdout, stderr })
        return
      }
      reject(new Error(`${command} ${args.join(" ")} exited ${code ?? 1}${stderr ? `: ${stderr.trim()}` : ""}`))
    })
  })
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return value
}

function exactVersionOutput(stdout) {
  return stdout.endsWith("\n") && !stdout.slice(0, -1).includes("\n")
    ? stdout.slice(0, -1)
    : JSON.stringify(stdout)
}

function mcpServerVersion(stdout) {
  const responses = stdout.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
  const initialize = responses.find(response => response.id === 1)
  return String(initialize?.result?.serverInfo?.version)
}

async function runVersionSurfaceDefault(surface, { root, jarPath }) {
  if (surface === "wrapper") {
    return runCommand(process.execPath, [path.join(root, "bin", "j4a.js"), "--version"], {
      cwd: root,
      capture: true,
    })
  }
  const java = process.env.J4A_JAVA_COMMAND ?? "java"
  if (surface === "java") {
    return runCommand(java, ["-jar", requireString(jarPath, "jarPath"), "--version"], {
      cwd: root,
      capture: true,
    })
  }
  if (surface === "mcp") {
    const initialize = JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "initialize",
      params: {
        protocolVersion: "2025-06-18",
        capabilities: {},
        clientInfo: { name: "j4a-release-verifier", version: "1" },
      },
    })
    const shutdown = JSON.stringify({ jsonrpc: "2.0", id: 2, method: "shutdown", params: {} })
    return runCommand(java, ["-cp", requireString(jarPath, "jarPath"), MCP_MAIN_CLASS], {
      cwd: root,
      capture: true,
      input: `${initialize}\n${shutdown}\n`,
    })
  }
  throw new Error(`unknown version surface: ${surface}`)
}

function sha512(bytes) {
  return createHash("sha512").update(bytes).digest("hex")
}

async function pathExists(targetPath) {
  try {
    await access(targetPath)
    return true
  } catch {
    return false
  }
}

function gradleCommand() {
  return process.platform === "win32" ? "gradlew.bat" : "./gradlew"
}

function npmCommand() {
  return process.platform === "win32" ? "npm.cmd" : "npm"
}

function pnpmCommand() {
  return process.platform === "win32" ? "pnpm.cmd" : "pnpm"
}

function commandForPlatform(command) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return "cmd.exe"
  }
  return command
}

function argsForPlatform(command, args) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return ["/d", "/s", "/c", command, ...args]
  }
  return args
}

export function isDirectRun(metaUrl, argv1 = process.argv[1]) {
  if (argv1 === undefined) return false
  return fileURLToPath(metaUrl) === path.resolve(argv1)
}

function optionValue(args, name) {
  const index = args.indexOf(name)
  return index === -1 ? undefined : args[index + 1]
}

if (isDirectRun(import.meta.url)) {
  try {
    const args = process.argv.slice(2)
    if (args[0] === "reject-pack") {
      rejectOrdinaryPack()
    } else if (args[0] === "verify-prebuilt") {
      await assertPreparedRelease()
      const tarballPath = await assertPreparedTarball({ tarballPath: args[1] === "--" ? args[2] : args[1] })
      console.log(JSON.stringify({ prebuiltTarball: tarballPath }))
    } else {
      const result = await prepareRelease({ tag: optionValue(args, "--tag") })
      console.log(JSON.stringify(result.manifest))
    }
  } catch (error) {
    console.error(`j4a: ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}
