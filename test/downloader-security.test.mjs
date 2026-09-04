import assert from "node:assert/strict"
import { readFile, writeFile } from "node:fs/promises"
import { createServer as createHttpServer } from "node:http"
import { createServer as createHttpsServer } from "node:https"
import { connect } from "node:net"
import path from "node:path"
import test from "node:test"

import { downloadJar } from "../src/downloader.mjs"
import { proxyUrlFor } from "../src/proxy.mjs"
import { responseFrom, sha256Of, withCacheDir } from "./helpers/downloader.mjs"

const STALLED_SERVER_KEY = Buffer.from(
  "LS0tLS1CRUdJTiBQUklWQVRFIEtFWS0tLS0tDQpNSUdIQWdFQU1CTUdCeXFHU000OUFnRUdDQ3FHU000OUF3RUhCRzB3YXdJQkFRUWdZMm45Q0ZZZUdRR0MxZnJuDQpseHpkY3RsOFpPai9vaEgyd2Uza0Faamd5RnVoUkFOQ0FBUkJmUWtNNHhUT3dZdXVZVDFmTEJ2UTdheXBZTFFrDQpYUmlNZUxXK2czS0FYcXd0c1ZheXI4bm9NMTlCbmRGQkRCQkh4bWw3U0JyL2FNL1ozMEV5eHJoMw0KLS0tLS1FTkQgUFJJVkFURSBLRVktLS0tLQ0K",
  "base64",
).toString("utf8")

const STALLED_SERVER_CERT = Buffer.from(
  "LS0tLS1CRUdJTiBDRVJUSUZJQ0FURS0tLS0tDQpNSUlCZlRDQ0FTT2dBd0lCQWdJVUNDYi9ZMlc0bHFzVXUyUDRXKzA3NVdqczJQY3dDZ1lJS29aSXpqMEVBd0l3DQpGREVTTUJBR0ExVUVBd3dKYkc5allXeG9iM04wTUI0WERUSTJNRFl5TXpJeU1Ea3dNVm9YRFRNMk1EWXlNREl5DQpNRGt3TVZvd0ZERVNNQkFHQTFVRUF3d0piRzlqWVd4b2IzTjBNRmt3RXdZSEtvWkl6ajBDQVFZSUtvWkl6ajBEDQpBUWNEUWdBRVFYMEpET01VenNHTHJtRTlYeXdiME8yc3FXQzBKRjBZakhpMXZvTnlnRjZzTGJGV3NxL0o2RE5mDQpRWjNSUVF3UVI4WnBlMGdhLzJqUDJkOUJNc2E0ZDZOVE1GRXdIUVlEVlIwT0JCWUVGSStOYmdiZ0loZ2prdEtzDQplZHUvVis3WVRkZTVNQjhHQTFVZEl3UVlNQmFBRkkrTmJnYmdJaGdqa3RLc2VkdS9WKzdZVGRlNU1BOEdBMVVkDQpFd0VCL3dRRk1BTUJBZjh3Q2dZSUtvWkl6ajBFQXdJRFNBQXdSUUlnWU0yWm5rekUwWmJTSUxBZm8wZVVaYWNzDQpPOEZnb2NLRkxEL05oeHdnNklrQ0lRRFNKM0lTZnh2TmREVzA5NitEMzBkTHBWcmFRa0VxWktlSXg1QTIxbUFMDQpSdz09DQotLS0tLUVORCBDRVJUSUZJQ0FURS0tLS0tDQo=",
  "base64",
).toString("utf8")

function listen(server) {
  return new Promise((resolve, reject) => {
    server.once("error", reject)
    server.listen(0, "127.0.0.1", () => {
      const address = server.address()
      assert.equal(typeof address, "object")
      assert.notEqual(address, null)
      resolve(address.port)
    })
  })
}

function close(server) {
  return new Promise((resolve, reject) => {
    server.close((error) => error === undefined ? resolve() : reject(error))
  })
}

test("proxy selection supports lowercase fallback and rejects non-HTTP proxy protocols", () => {
  const target = new URL("https://downloads.example.test/j4a.jar")
  assert.equal(
    proxyUrlFor(target, { http_proxy: "proxy.example.test:8080" })?.href,
    "http://proxy.example.test:8080/",
  )
  assert.equal(
    proxyUrlFor(target, { https_proxy: "https://proxy.example.test:8443" })?.href,
    "https://proxy.example.test:8443/",
  )
  assert.throws(
    () => proxyUrlFor(target, { HTTPS_PROXY: "socks5://proxy.example.test:1080" }),
    /proxy URL must use HTTP or HTTPS/,
  )
})

test("downloadJar uses a scheme-less HTTPS_PROXY for every HTTPS redirect", async () => {
  const body = Buffer.from("proxied jar bytes", "utf8")
  const target = createHttpsServer({ key: STALLED_SERVER_KEY, cert: STALLED_SERVER_CERT }, (request, response) => {
    if (request.url === "/start") {
      response.writeHead(302, { location: "/j4a.jar" })
      response.end()
      return
    }
    response.writeHead(200, {
      "content-length": String(body.length),
      "content-type": "application/java-archive",
    })
    response.end(body)
  })
  const authorities = []
  const tunnels = new Set()
  const proxy = createHttpServer()
  proxy.on("connect", (request, client, head) => {
    authorities.push(request.url)
    const destination = new URL(`http://${request.url}`)
    const upstream = connect(Number(destination.port), destination.hostname, () => {
      client.write("HTTP/1.1 200 Connection Established\r\n\r\n")
      if (head.length > 0) upstream.write(head)
      upstream.pipe(client)
      client.pipe(upstream)
    })
    tunnels.add(client)
    tunnels.add(upstream)
    client.once("close", () => tunnels.delete(client))
    upstream.once("close", () => tunnels.delete(upstream))
    upstream.once("error", (error) => client.destroy(error))
  })
  const previousTlsRejectUnauthorized = process.env.NODE_TLS_REJECT_UNAUTHORIZED

  try {
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"
    const targetPort = await listen(target)
    const proxyPort = await listen(proxy)
    await withCacheDir(async (cacheDir) => {
      const jarPath = await downloadJar({
        jarUrl: `https://127.0.0.1:${targetPort}/start`,
        sha256: sha256Of(body),
        cacheDir,
        env: { HTTPS_PROXY: `127.0.0.1:${proxyPort}` },
      })
      assert.deepEqual(await readFile(jarPath), body)
    })
    assert.deepEqual(authorities, [
      `127.0.0.1:${targetPort}`,
      `127.0.0.1:${targetPort}`,
    ])
  } finally {
    if (previousTlsRejectUnauthorized === undefined) {
      delete process.env.NODE_TLS_REJECT_UNAUTHORIZED
    } else {
      process.env.NODE_TLS_REJECT_UNAUTHORIZED = previousTlsRejectUnauthorized
    }
    target.closeAllConnections?.()
    proxy.closeAllConnections?.()
    for (const socket of tunnels) socket.destroy()
    await Promise.all([close(target), close(proxy)])
  }
})

test("downloadJar honors NO_PROXY before opening a proxy connection", async () => {
  const body = Buffer.from("direct jar bytes", "utf8")
  const target = createHttpsServer({ key: STALLED_SERVER_KEY, cert: STALLED_SERVER_CERT }, (_request, response) => {
    response.writeHead(200, {
      "content-length": String(body.length),
      "content-type": "application/java-archive",
    })
    response.end(body)
  })
  const previousTlsRejectUnauthorized = process.env.NODE_TLS_REJECT_UNAUTHORIZED

  try {
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"
    const targetPort = await listen(target)
    await withCacheDir(async (cacheDir) => {
      const jarPath = await downloadJar({
        jarUrl: `https://127.0.0.1:${targetPort}/j4a.jar`,
        sha256: sha256Of(body),
        cacheDir,
        env: {
          HTTPS_PROXY: "127.0.0.1:1",
          NO_PROXY: "127.0.0.1",
        },
      })
      assert.deepEqual(await readFile(jarPath), body)
    })
  } finally {
    if (previousTlsRejectUnauthorized === undefined) {
      delete process.env.NODE_TLS_REJECT_UNAUTHORIZED
    } else {
      process.env.NODE_TLS_REJECT_UNAUTHORIZED = previousTlsRejectUnauthorized
    }
    target.closeAllConnections?.()
    await close(target)
  }
})

test("downloadJar accepts uppercase sha256 config while verifying downloaded bytes", async () => {
  const body = Buffer.from("fake jar bytes", "utf8")

  await withCacheDir(async (cacheDir) => {
    const jarPath = await downloadJar({
      jarUrl: "https://downloads.example.test/j4a.jar",
      sha256: sha256Of(body).toUpperCase(),
      cacheDir,
      requestImpl: async () => responseFrom(body, { headers: { "content-type": "application/java-archive" } }),
    })

    assert.equal(await readFile(jarPath, "utf8"), "fake jar bytes")
  })
})

test("downloadJar rejects non-HTTPS jar URLs before requesting bytes", async () => {
  let requested = false

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "ftp://downloads.example.test/j4a.jar",
        sha256: sha256Of("fake jar bytes"),
        cacheDir,
        requestImpl: async () => {
          requested = true
          return responseFrom("fake jar bytes")
        },
      }),
      /download failed: jarUrl must use HTTPS/,
    )
  })

  assert.equal(requested, false)
})

test("downloadJar rejects a non-HTTPS initial target before requesting bytes", async () => {
  let requested = false

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "http://downloads.example.test/j4a.jar",
        sha256: sha256Of("fake jar bytes"),
        cacheDir,
        requestImpl: async () => {
          requested = true
          return responseFrom("fake jar bytes")
        },
      }),
      /download failed: jarUrl must use HTTPS/,
    )
  })

  assert.equal(requested, false)
})

test("downloadJar rejects an HTTPS-to-HTTP redirect without requesting the downgrade target", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          if (url === "https://downloads.example.test/releases/start") {
            return responseFrom(undefined, {
              statusCode: 302,
              headers: { location: "http://downloads.example.test/j4a.jar" },
            })
          }
          throw new Error(`unexpected request: ${url}`)
        },
      }),
      /download failed: redirect target must use HTTPS/,
    )
  })

  assert.deepEqual(requests, ["https://downloads.example.test/releases/start"])
})

test("downloadJar rejects a redirect with no Location without another request", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          if (url === "https://downloads.example.test/releases/start") {
            return responseFrom(undefined, { statusCode: 302 })
          }
          throw new Error(`unexpected request: ${url}`)
        },
      }),
      /download failed: redirect missing Location/,
    )
  })

  assert.deepEqual(requests, ["https://downloads.example.test/releases/start"])
})

test("downloadJar rejects an empty redirect Location without another request", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          return responseFrom(undefined, { statusCode: 302, headers: { location: "" } })
        },
      }),
      /download failed: redirect missing Location/,
    )
  })

  assert.deepEqual(requests, ["https://downloads.example.test/releases/start"])
})

test("downloadJar rejects a redirect loop before repeating a request", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          if (requests.length > 2) {
            throw new Error(`unexpected repeated request: ${url}`)
          }
          if (url === "https://downloads.example.test/releases/start") {
            return responseFrom(undefined, { statusCode: 302, headers: { location: "loop" } })
          }
          if (url === "https://downloads.example.test/releases/loop") {
            return responseFrom(undefined, { statusCode: 302, headers: { location: "start" } })
          }
          throw new Error(`unexpected request: ${url}`)
        },
      }),
      /download failed: redirect loop/,
    )
  })

  assert.deepEqual(requests, [
    "https://downloads.example.test/releases/start",
    "https://downloads.example.test/releases/loop",
  ])
})

test("downloadJar rejects fragment-only redirects before repeating the physical request", async () => {
  const physicalRequests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          const physicalUrl = new URL(url)
          physicalUrl.hash = ""
          physicalRequests.push(physicalUrl.toString())
          if (physicalRequests.length > 1) {
            throw new Error(`unexpected repeated physical request: ${physicalUrl}`)
          }
          return responseFrom(undefined, {
            statusCode: 302,
            headers: { location: "#fragment-1" },
          })
        },
      }),
      /download failed: redirect loop/,
    )
  })

  assert.deepEqual(physicalRequests, ["https://downloads.example.test/releases/start"])
})

test("downloadJar rejects a redirect chain after five hops without caching bytes", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/0",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          const hop = Number(new URL(url).pathname.split("/").at(-1))
          if (hop >= 6) {
            throw new Error(`unexpected request after redirect limit: ${url}`)
          }
          return responseFrom(undefined, {
            statusCode: 302,
            headers: { location: String(hop + 1) },
          })
        },
      }),
      /download failed: redirect limit exceeded/,
    )
  })

  assert.deepEqual(requests, [
    "https://downloads.example.test/releases/0",
    "https://downloads.example.test/releases/1",
    "https://downloads.example.test/releases/2",
    "https://downloads.example.test/releases/3",
    "https://downloads.example.test/releases/4",
    "https://downloads.example.test/releases/5",
  ])
})

test("downloadJar rejects an invalid redirect Location without another request", async () => {
  const requests = []

  await withCacheDir(async (cacheDir) => {
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/releases/start",
        sha256: sha256Of("unused"),
        cacheDir,
        requestImpl: async (url) => {
          requests.push(url)
          return responseFrom(undefined, {
            statusCode: 302,
            headers: { location: "http://[invalid" },
          })
        },
      }),
      /download failed: invalid redirect Location/,
    )
  })

  assert.deepEqual(requests, ["https://downloads.example.test/releases/start"])
})

test("downloadJar rejects missing sha256 before cache reuse or download", async () => {
  let requested = false

  await withCacheDir(async (cacheDir) => {
    await writeFile(path.join(cacheDir, "j4a.jar"), "cached jar bytes", "utf8")
    await assert.rejects(
      downloadJar({
        jarUrl: "https://downloads.example.test/j4a.jar",
        sha256: "",
        cacheDir,
        requestImpl: async () => {
          requested = true
          return responseFrom("fake jar bytes")
        },
      }),
      /download failed: jarSha256 must be a non-empty SHA-256 hex digest/,
    )
  })

  assert.equal(requested, false)
})

test("downloadJar times out stalled responses", async () => {
  let clientClosed
  const server = createHttpsServer({ key: STALLED_SERVER_KEY, cert: STALLED_SERVER_CERT }, (request, response) => {
    clientClosed = new Promise((resolve) => {
      request.socket.once("close", resolve)
    })
    response.writeHead(200, { "content-type": "application/java-archive" })
    response.flushHeaders()
  })
  const previousTlsRejectUnauthorized = process.env.NODE_TLS_REJECT_UNAUTHORIZED

  try {
    process.env.NODE_TLS_REJECT_UNAUTHORIZED = "0"
    const port = await listen(server)
    await withCacheDir(async (cacheDir) => {
      await assert.rejects(
        downloadJar({
          jarUrl: `https://127.0.0.1:${port}/j4a.jar`,
          sha256: sha256Of("unused"),
          cacheDir,
          timeoutMs: 20,
        }),
        /download failed: timeout/,
      )
    })
    assert.notEqual(clientClosed, undefined)
    const closeObserved = await Promise.race([
      clientClosed.then(() => true),
      new Promise((resolve) => {
        setTimeout(() => resolve(false), 1_000)
      }),
    ])
    assert.equal(closeObserved, true)
  } finally {
    if (previousTlsRejectUnauthorized === undefined) {
      delete process.env.NODE_TLS_REJECT_UNAUTHORIZED
    } else {
      process.env.NODE_TLS_REJECT_UNAUTHORIZED = previousTlsRejectUnauthorized
    }
    server.closeAllConnections?.()
    server.close()
  }
})
