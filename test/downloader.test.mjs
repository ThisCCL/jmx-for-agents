import assert from "node:assert/strict"
import { access, readFile, writeFile } from "node:fs/promises"
import path from "node:path"
import test from "node:test"

import { downloadJar } from "../src/downloader.mjs"
import { responseFrom, sha256Of, withCacheDir } from "./helpers/downloader.mjs"

test("downloadJar stores bytes and verifies sha256 when the cache is empty", async () => {
  const body = Buffer.from("fake jar bytes", "utf8")
  await withCacheDir(async (cacheDir) => {
    const jarPath = await downloadJar({
      jarUrl: "https://downloads.example.test/j4a.jar",
      sha256: sha256Of(body),
      cacheDir,
      requestImpl: async () => responseFrom(body, { headers: { "content-type": "application/java-archive" } }),
    })

    assert.equal(await readFile(jarPath, "utf8"), "fake jar bytes")
  })
})

test("downloadJar follows two relative HTTPS redirects before caching verified bytes", async () => {
  const body = Buffer.from("redirected jar bytes", "utf8")
  const requests = []
  const responses = new Map([
    ["https://downloads.example.test/releases/start", responseFrom(undefined, {
      statusCode: 302,
      headers: { location: "next" },
    })],
    ["https://downloads.example.test/releases/next", responseFrom(undefined, {
      statusCode: 302,
      headers: { location: "../j4a.jar" },
    })],
    ["https://downloads.example.test/j4a.jar", responseFrom(body, {
      headers: { "content-type": "application/java-archive" },
    })],
  ])

  await withCacheDir(async (cacheDir) => {
    const jarPath = await downloadJar({
      jarUrl: "https://downloads.example.test/releases/start",
      sha256: sha256Of(body),
      cacheDir,
      requestImpl: async (url) => {
        requests.push(url)
        const response = responses.get(url)
        assert.notEqual(response, undefined, `unexpected redirect request: ${url}`)
        return response
      },
    })

    assert.deepEqual(requests, [
      "https://downloads.example.test/releases/start",
      "https://downloads.example.test/releases/next",
      "https://downloads.example.test/j4a.jar",
    ])
    assert.equal(await readFile(jarPath, "utf8"), "redirected jar bytes")
  })
})

test("downloadJar rejects tampered final redirect bytes without committing a cache entry", async () => {
  const expectedBytes = Buffer.from("expected redirect jar bytes", "utf8")
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of(expectedBytes),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          if (url === "https://downloads.example.test/releases/start") {
            return responseFrom(undefined, {
              statusCode: 302,
              headers: { location: "j4a.jar" },
            })
          }
          if (url === "https://downloads.example.test/releases/j4a.jar") {
            return responseFrom("tampered redirect jar bytes", {
              headers: { "content-type": "application/java-archive" },
            })
          }
          throw new Error(`unexpected redirect request: ${url}`)
        },
      }),
      /download failed: sha256 mismatch/,
    )

    await assert.rejects(access(path.join(cacheDir, "j4a.jar")), { code: "ENOENT" })
  })

  assert.deepEqual(requests, [
    "https://downloads.example.test/releases/start",
    "https://downloads.example.test/releases/j4a.jar",
  ])
})

test("downloadJar reports friendly Chinese progress while downloading", async () => {
  const body = Buffer.from("fake jar bytes", "utf8")
  const messages = []

  await withCacheDir(async (cacheDir) => {
    await downloadJar({
      jarUrl: "https://downloads.example.test/j4a.jar",
      sha256: sha256Of(body),
      cacheDir,
      reporter: (message) => messages.push(message),
      requestImpl: async () => responseFrom(body, {
        headers: {
          "content-length": String(body.length),
          "content-type": "application/java-archive",
        },
      }),
    })
  })

  assert.deepEqual(messages, [
    "j4a: 正在下载 j4a 运行时，请稍候...",
    "j4a: 下载进度 [####################] 100%",
    "j4a: 下载完成，运行时已准备好。",
  ])
})

test("downloadJar rejects non-2xx HTTP responses with the status code", async () => {
  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/j4a.jar",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async () => responseFrom("unavailable", {
          statusCode: 503,
          headers: { "content-type": "text/plain" },
        }),
      }),
      /download failed: HTTP 503/,
    )
  })
})

test("downloadJar rejects checksum mismatch and removes the temp file", async () => {
  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/j4a.jar",
        sha256: sha256Of("expected bytes"),
        cacheDir,
        requestImpl: async () => responseFrom("wrong bytes", {
          headers: { "content-type": "application/java-archive" },
        }),
      }),
      /download failed: sha256 mismatch/,
    )
  })
})

test("downloadJar reuses a verified cache hit without another HTTP request", async () => {
  const body = Buffer.from("cached jar bytes", "utf8")
  let requests = 0

  await withCacheDir(async (cacheDir) => {
    const jarUrl = "https://downloads.example.test/j4a.jar"
    const requestImpl = async () => {
      requests += 1
      return responseFrom(body, { headers: { "content-type": "application/java-archive" } })
    }

    await downloadJar({ jarUrl, sha256: sha256Of(body), cacheDir, requestImpl })
    await downloadJar({ jarUrl, sha256: sha256Of(body), cacheDir, requestImpl })
  })

  assert.equal(requests, 1)
})

test("downloadJar handles concurrent downloads into the same cache", async () => {
  const body = Buffer.from("concurrent jar bytes", "utf8")

  await withCacheDir(async (cacheDir) => {
    const jarUrl = "https://downloads.example.test/j4a.jar"
    const requestImpl = async () => responseFrom(body, {
      headers: {
        "content-length": String(body.length),
        "content-type": "application/java-archive",
      },
    })
    const [firstPath, secondPath] = await Promise.all([
      downloadJar({ jarUrl, sha256: sha256Of(body), cacheDir, requestImpl }),
      downloadJar({ jarUrl, sha256: sha256Of(body), cacheDir, requestImpl }),
    ])

    assert.equal(firstPath, secondPath)
    assert.equal(await readFile(firstPath, "utf8"), "concurrent jar bytes")
  })
})

test("downloadJar replaces a corrupt cache entry", async () => {
  const body = Buffer.from("fresh jar bytes", "utf8")

  await withCacheDir(async (cacheDir) => {
    await writeFile(path.join(cacheDir, "j4a.jar"), "stale", "utf8")
    const jarPath = await downloadJar({
      jarUrl: "https://downloads.example.test/j4a.jar",
      sha256: sha256Of(body),
      cacheDir,
      requestImpl: async () => responseFrom(body, { headers: { "content-type": "application/java-archive" } }),
    })

    assert.equal(await readFile(jarPath, "utf8"), "fresh jar bytes")
  })
})
