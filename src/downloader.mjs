import { createHash, randomUUID } from "node:crypto"
import { createWriteStream } from "node:fs"
import http from "node:http"
import https from "node:https"
import { mkdir, readFile, rename, rm } from "node:fs/promises"
import path from "node:path"
import { pipeline } from "node:stream/promises"

const activeDownloads = new Map()
const MAX_REDIRECT_HOPS = 5

export async function downloadJar({
  jarUrl,
  sha256,
  cacheDir,
  force = false,
  reporter = () => {},
  timeoutMs = 30_000,
  requestImpl = request,
}) {
  const normalizedSha256 = validateReleaseArtifactConfig(jarUrl, sha256)
  await mkdir(cacheDir, { recursive: true })
  const jarPath = path.join(cacheDir, "j4a.jar")

  if (!force && await cachedJarIsUsable(jarPath, normalizedSha256)) {
    return jarPath
  }

  const activeDownload = activeDownloads.get(jarPath)
  if (activeDownload !== undefined) {
    await activeDownload
    await verifySha256(jarPath, normalizedSha256)
    return jarPath
  }

  const download = downloadFreshJar({
    jarUrl,
    sha256: normalizedSha256,
    jarPath,
    cacheDir,
    reporter,
    timeoutMs,
    requestImpl,
  })
  activeDownloads.set(jarPath, download)
  try {
    await download
    return jarPath
  } finally {
    activeDownloads.delete(jarPath)
  }
}

export async function requireCachedJar({ cacheDir, sha256 }) {
  const normalizedSha256 = requireSha256(sha256)
  const jarPath = path.join(cacheDir, "j4a.jar")
  await verifySha256(jarPath, normalizedSha256)
  return jarPath
}

async function downloadFreshJar({ jarUrl, sha256, jarPath, cacheDir, reporter, timeoutMs, requestImpl }) {
  const tempPath = path.join(cacheDir, `j4a.${process.pid}.${randomUUID()}.tmp`)
  try {
    reporter("j4a: 正在下载 j4a 运行时，请稍候...")
    await downloadToFile(jarUrl, tempPath, reporter, timeoutMs, requestImpl)
    await verifySha256(tempPath, sha256)
    await rename(tempPath, jarPath)
    reporter("j4a: 下载完成，运行时已准备好。")
  } catch (error) {
    await rm(tempPath, { force: true })
    throw error
  }
}

async function cachedJarIsUsable(jarPath, sha256) {
  try {
    await verifySha256(jarPath, sha256)
    return true
  } catch (error) {
    if (error instanceof Error && error.code === "ENOENT") {
      return false
    }
    return false
  }
}

async function downloadToFile(jarUrl, outputPath, reporter, timeoutMs, requestImpl) {
  let url = new URL(jarUrl)
  const visitedUrls = new Set([requestIdentity(url)])
  let redirectCount = 0
  let response = await requestImpl(url.toString(), timeoutMs)
  while (response.statusCode !== undefined && response.statusCode >= 300 && response.statusCode < 400) {
    const location = response.headers.location
    response.resume()
    if (redirectCount >= MAX_REDIRECT_HOPS) {
      throw new Error("download failed: redirect limit exceeded")
    }
    if (typeof location !== "string" || location.length === 0) {
      throw new Error("download failed: redirect missing Location")
    }
    try {
      url = new URL(location, url)
    } catch {
      throw new Error("download failed: invalid redirect Location")
    }
    if (url.protocol !== "https:") {
      throw new Error("download failed: redirect target must use HTTPS")
    }
    const redirectedRequestIdentity = requestIdentity(url)
    if (visitedUrls.has(redirectedRequestIdentity)) {
      throw new Error("download failed: redirect loop")
    }
    visitedUrls.add(redirectedRequestIdentity)
    redirectCount += 1
    response = await requestImpl(url.toString(), timeoutMs)
  }
  if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
    response.resume()
    throw new Error(`download failed: HTTP ${response.statusCode ?? "unknown"}`)
  }
  const totalBytes = Number(response.headers["content-length"] ?? "0")
  let downloadedBytes = 0
  let lastPercent = -1
  response.on("data", (chunk) => {
    downloadedBytes += chunk.length
    const percent = totalBytes > 0 ? Math.floor((downloadedBytes / totalBytes) * 100) : 0
    if (percent !== lastPercent && (percent === 100 || percent - lastPercent >= 10)) {
      lastPercent = percent
      reporter(`j4a: 下载进度 ${progressBar(percent)} ${percent}%`)
    }
  })
  await pipeline(response, createWriteStream(outputPath, { flags: "w" }))
}

function requestIdentity(url) {
  const requestUrl = new URL(url)
  requestUrl.hash = ""
  return requestUrl.href
}

function request(jarUrl, timeoutMs) {
  return new Promise((resolve, reject) => {
    const url = new URL(jarUrl)
    const client = url.protocol === "https:" ? https : http
    let response
    const req = client.get(url, { timeout: timeoutMs }, (receivedResponse) => {
      response = receivedResponse
      resolve(receivedResponse)
    })
    req.once("timeout", () => {
      const error = new Error("download failed: timeout")
      response?.destroy(error)
      req.destroy(error)
    })
    req.once("error", reject)
  })
}

async function verifySha256(filePath, sha256) {
  const bytes = await readFile(filePath)
  const actual = createHash("sha256").update(bytes).digest("hex")
  if (actual !== sha256) {
    throw new Error(`download failed: sha256 mismatch, expected ${sha256}, got ${actual}`)
  }
}

function validateReleaseArtifactConfig(jarUrl, sha256) {
  let url
  try {
    url = new URL(jarUrl)
  } catch {
    throw new Error("download failed: jarUrl must be a valid HTTP or HTTPS URL")
  }
  if (url.protocol !== "https:") {
    throw new Error("download failed: jarUrl must use HTTPS")
  }
  return requireSha256(sha256)
}

function requireSha256(sha256) {
  if (typeof sha256 !== "string" || !/^[a-fA-F0-9]{64}$/.test(sha256)) {
    throw new Error("download failed: jarSha256 must be a non-empty SHA-256 hex digest")
  }
  return sha256.toLowerCase()
}

function progressBar(percent) {
  const filled = Math.max(0, Math.min(20, Math.round(percent / 5)))
  return `[${"#".repeat(filled)}${"-".repeat(20 - filled)}]`
}
