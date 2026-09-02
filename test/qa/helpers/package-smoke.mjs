import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { chmod, cp, mkdir, mkdtemp, readFile, readdir, writeFile } from "node:fs/promises"
import { createServer } from "node:https"
import { tmpdir } from "node:os"
import path from "node:path"

import { cleanupOwnedRelease, preserveFailureAfterCleanup } from "./prepared-release-cleanup.mjs"
import { closeOwnedServer, trackOwnedServerSockets } from "./owned-server-sockets.mjs"
import { runBounded } from "./prepared-release-process.mjs"

const COMMAND_TIMEOUT_MS = 120_000

export async function createPackagedConsumer({ jarBytes = "fake jar" } = {}) {
  const root = process.cwd()
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-package-smoke-"))
  const certificatePath = path.join(workDir, "certificate.pem")
  const keyPath = path.join(workDir, "key.pem")
  const packDir = path.join(workDir, "pack")
  const packageDir = path.join(workDir, "package")
  const consumerDir = path.join(workDir, "consumer")
  const cacheDir = path.join(workDir, "cache")
  const fakeBin = path.join(workDir, "bin")
  const fakeJava = path.join(fakeBin, process.platform === "win32" ? "java.cmd" : "java")
  const javaLog = path.join(workDir, "java.log")
  let requestCount = 0
  let server

  try {
    await Promise.all([mkdir(packDir, { recursive: true }), mkdir(consumerDir, { recursive: true }), mkdir(fakeBin, { recursive: true })])
    await writeFakeJava({ fakeJava, javaLog })
    await prepareFixturePackage({ packageDir, packDir, root })
    await runBounded("openssl", certificateArgs(keyPath, certificatePath), { timeoutMs: COMMAND_TIMEOUT_MS })
    server = await createAssetServer({ certificatePath, jarBytes, keyPath, onRequest: () => { requestCount += 1 } })
    const tarball = await findTarball(packDir)
    await writeFile(path.join(consumerDir, "package.json"), JSON.stringify({ private: true, dependencies: {} }), "utf8")
    await run("npm", ["install", "--ignore-scripts", "--offline", "--no-audit", "--no-fund", tarball], { cwd: consumerDir })
    const jarUrl = `https://127.0.0.1:${server.port}/j4a/1.0.0/j4a.jar`
    const patched = await patchReleaseConfigs(consumerDir, jarUrl, sha256Of(jarBytes))
    assert.notEqual(patched.length, 0)

    return {
      cacheDir,
      certificatePath,
      consumerDir,
      fakeBin,
      fakeJava,
      get requestCount() { return requestCount },
      installedCommand: j4aCommand(consumerDir),
      javaLog,
      async cleanup() {
        await cleanupOwnedRelease({ close: () => closeOwnedServer(server?.server, server?.sockets ?? new Set()), workDir })
      },
    }
  } catch (error) {
    try {
      await cleanupOwnedRelease({ close: () => closeOwnedServer(server?.server, server?.sockets ?? new Set()), workDir })
    } catch (cleanupError) {
      preserveFailureAfterCleanup(asError(error), asError(cleanupError))
    }
    throw error
  }
}

export async function run(command, args, options = {}) {
  const result = await runBounded(command, args, { ...options, timeoutMs: options.timeoutMs ?? COMMAND_TIMEOUT_MS })
  reportOutput(result)
  return result
}

export async function runAsync(command, args, options = {}) {
  const result = await runBounded(command, args, {
    ...options,
    allowNonZero: true,
    timeoutMs: options.timeoutMs ?? COMMAND_TIMEOUT_MS,
  })
  reportOutput(result)
  return result
}

async function prepareFixturePackage({ packageDir, packDir, root }) {
  await run("pnpm", ["run", "build"], { cwd: root })
  await cp(root, packageDir, { recursive: true, filter: source => includeFixturePath(root, source) })
  await removeFixturePrepack(packageDir)
  await assertFixtureDist(packageDir)
  await run("npm", ["pack", "--ignore-scripts", "--pack-destination", packDir], { cwd: packageDir })
}

function includeFixturePath(root, source) {
  const relative = path.relative(root, source)
  return ![".git", ".gradle", ".omo", "build", "node_modules"].some(name => relative === name || relative.startsWith(`${name}${path.sep}`))
}

async function removeFixturePrepack(packageDir) {
  const manifestPath = path.join(packageDir, "package.json")
  const manifest = JSON.parse(await readFile(manifestPath, "utf8"))
  delete manifest.scripts.prepack
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8")
}

async function assertFixtureDist(packageDir) {
  for (const file of ["main.mjs", "release-config.mjs", "runtime.mjs", path.join("skills", "j4a-master", "SKILL.md")]) {
    await readFile(path.join(packageDir, "dist", file))
  }
}

async function writeFakeJava({ fakeJava, javaLog }) {
  await writeFile(
    fakeJava,
    process.platform === "win32"
      ? `@echo off\r\n(for %%A in (%*) do @echo %%~A) > "${javaLog}"\r\nexit /b 0\r\n`
      : `#!/usr/bin/env sh\nprintf '%s\\n' "$@" > "${javaLog.replaceAll("\\", "/")}"\n`,
    "utf8",
  )
  await chmod(fakeJava, 0o755)
}

async function createAssetServer({ certificatePath, jarBytes, keyPath, onRequest }) {
  const server = createServer({ cert: await readFile(certificatePath), key: await readFile(keyPath) }, (request, response) => {
    if (request.url !== "/j4a/1.0.0/j4a.jar") {
      response.writeHead(404)
      response.end("not found")
      return
    }
    onRequest()
    response.writeHead(200, { "content-type": "application/java-archive" })
    response.end(jarBytes)
  })
  const port = await new Promise((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve(server.address().port))
  })
  return { port, server, sockets: trackOwnedServerSockets(server) }
}

async function findTarball(packDir) {
  const tarballs = (await readdir(packDir)).filter(file => file.endsWith(".tgz"))
  assert.equal(tarballs.length, 1)
  return path.join(packDir, tarballs[0])
}

async function patchReleaseConfigs(rootDir, jarUrl, jarSha256) {
  const matches = await findFiles(path.join(rootDir, "node_modules"), "release-config.mjs")
  for (const filePath of matches) {
    await writeFile(filePath, ["export const releaseConfig = {", `  jarUrl: ${JSON.stringify(jarUrl)},`, `  jarSha256: ${JSON.stringify(jarSha256)},`, "}", ""].join("\n"), "utf8")
  }
  return matches
}

async function findFiles(rootDir, fileName) {
  const entries = await readdir(rootDir, { withFileTypes: true })
  const matches = []
  for (const entry of entries) {
    const entryPath = path.join(rootDir, entry.name)
    if (entry.isDirectory()) matches.push(...await findFiles(entryPath, fileName))
    else if (entry.name === fileName) matches.push(entryPath)
  }
  return matches
}

function certificateArgs(keyPath, certificatePath) {
  return ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", keyPath, "-out", certificatePath, "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1", "-days", "1"]
}

function j4aCommand(consumerDir) {
  return path.join(consumerDir, "node_modules", ".bin", process.platform === "win32" ? "j4a.cmd" : "j4a")
}

function reportOutput({ stderr, stdout }) {
  if (stdout) process.stdout.write(stdout)
  if (stderr) process.stderr.write(stderr)
}

function sha256Of(value) {
  return createHash("sha256").update(value).digest("hex")
}

function asError(error) {
  return error instanceof Error ? error : new Error(String(error))
}
