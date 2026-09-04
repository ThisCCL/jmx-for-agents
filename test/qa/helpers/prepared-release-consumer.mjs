import { cp, mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises"
import { createServer } from "node:https"
import { tmpdir } from "node:os"
import path from "node:path"

import { readPreparedRelease } from "./prepared-release-artifact.mjs"
import { cleanupOwnedRelease, preserveFailureAfterCleanup } from "./prepared-release-cleanup.mjs"
import { closeOwnedServer, trackOwnedServerSockets } from "./owned-server-sockets.mjs"
import { runBounded } from "./prepared-release-process.mjs"

export async function createPreparedReleaseConsumer() {
  const root = process.cwd()
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-prepared-release-"))
  const projectDir = path.join(workDir, "project")
  const consumerDir = path.join(workDir, "consumer")
  const cacheDir = path.join(workDir, "cache")
  const certificatePath = path.join(workDir, "certificate.pem")
  const keyPath = path.join(workDir, "key.pem")
  let directRequests = 0
  let redirectRequests = 0
  let assetRequests = 0
  let server

  try {
    await copyReleaseProject(root, projectDir)
    const version = JSON.parse(await readFile(path.join(projectDir, "package.json"), "utf8")).version
    await runBounded("pnpm", ["run", "release:prepare", "--", "--tag", `v${version}`], { cwd: projectDir, timeoutMs: 600_000 })
    const prepared = await readPreparedRelease(projectDir)
    await runBounded("openssl", certificateArgs(keyPath, certificatePath), { timeoutMs: 120_000 })
    server = await createAssetServer({ certificatePath, jarBytes: await readFile(prepared.jarPath), keyPath, onRequest: (kind) => {
      if (kind === "direct") directRequests += 1
      if (kind === "redirect") redirectRequests += 1
      if (kind === "asset") assetRequests += 1
    }, version: prepared.manifest.version })
    await mkdir(consumerDir, { recursive: true })
    await writeFile(path.join(consumerDir, "package.json"), '{"private":true}\n', "utf8")
    await runBounded("npm", ["install", "--ignore-scripts", "--offline", "--no-audit", "--no-fund", prepared.tarballPath], { cwd: consumerDir, timeoutMs: 120_000 })
    const endpoint = `https://127.0.0.1:${server.port}`
    const installedConfigPath = await patchInstalledReleaseConfig({
      consumerDir,
      jarSha256: prepared.jarSha256,
      jarUrl: `${endpoint}/direct/j4a.jar`,
    })

    return {
      ...prepared,
      cacheDir,
      certificatePath,
      consumerDir,
      directUrl: `${endpoint}/direct/j4a.jar`,
      installedCommand: j4aCommand(consumerDir),
      installedConfigPath,
      projectDir,
      redirectUrl: releaseUrl(endpoint, prepared.manifest.version),
      workDir,
      get assetRequests() { return assetRequests },
      get directRequests() { return directRequests },
      get redirectRequests() { return redirectRequests },
      async patchRedirectConfig() {
        return patchInstalledReleaseConfig({
          consumerDir,
          jarSha256: prepared.jarSha256,
          jarUrl: releaseUrl(endpoint, prepared.manifest.version),
        })
      },
      async cleanup() {
        await cleanupOwnedRelease({ close: () => closeOwnedServer(server.server, server.sockets), workDir })
      },
    }
  } catch (error) {
    try {
      await cleanupOwnedRelease({ close: () => server ? closeOwnedServer(server.server, server.sockets) : Promise.resolve(), workDir })
    } catch (cleanupError) {
      preserveFailureAfterCleanup(error, cleanupError)
    }
    throw error
  }
}

async function copyReleaseProject(root, target) {
  await cp(root, target, {
    recursive: true,
    filter(source) {
      const relative = path.relative(root, source)
      return ![".git", ".omo", ".gradle", "build", "dist", "node_modules"].some(directory => relative === directory || relative.startsWith(`${directory}${path.sep}`))
    },
  })
}

async function createAssetServer({ certificatePath, jarBytes, keyPath, onRequest, version }) {
  const redirectPath = `/releases/download/v${version}/j4a-${version}.jar`
  const assetPath = `/assets/j4a-${version}.jar`
  const server = createServer({ cert: await readFile(certificatePath), key: await readFile(keyPath) }, (request, response) => {
    if (request.url === "/direct/j4a.jar") {
      onRequest("direct")
      response.writeHead(200, { "content-type": "application/java-archive" })
      response.end(jarBytes)
      return
    }
    if (request.url === redirectPath) {
      onRequest("redirect")
      response.writeHead(302, { location: assetPath })
      response.end()
      return
    }
    if (request.url === assetPath) {
      onRequest("asset")
      response.writeHead(200, { "content-type": "application/java-archive" })
      response.end(jarBytes)
      return
    }
    response.writeHead(404)
    response.end("not found")
  })
  const port = await new Promise((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => resolve(server.address().port))
  })
  return { port, server, sockets: trackOwnedServerSockets(server) }
}

function releaseUrl(endpoint, version) {
  return `${endpoint}/releases/download/v${version}/j4a-${version}.jar`
}

async function patchInstalledReleaseConfig({ consumerDir, jarSha256, jarUrl }) {
  const configPath = path.join(consumerDir, "node_modules", "@jmx-for-agents", "j4a", "dist", "release-config.mjs")
  await writeFile(configPath, ["export const releaseConfig = {", `  jarUrl: ${JSON.stringify(jarUrl)},`, `  jarSha256: ${JSON.stringify(jarSha256)},`, "}", ""].join("\n"), "utf8")
  return configPath
}

function certificateArgs(keyPath, certificatePath) {
  return ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", keyPath, "-out", certificatePath, "-subj", "/CN=127.0.0.1", "-addext", "subjectAltName=IP:127.0.0.1", "-days", "1"]
}

function j4aCommand(consumerDir) {
  return path.join(consumerDir, "node_modules", ".bin", process.platform === "win32" ? "j4a.cmd" : "j4a")
}
