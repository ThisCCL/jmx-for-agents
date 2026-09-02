import { createHash } from "node:crypto"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { Readable } from "node:stream"

export function responseFrom(body, { statusCode = 200, headers = {} } = {}) {
  const response = Readable.from(body === undefined ? [] : [body])
  response.statusCode = statusCode
  response.headers = headers
  return response
}

export function sha256Of(value) {
  return createHash("sha256").update(value).digest("hex")
}

export async function withCacheDir(run) {
  const cacheDir = await mkdtemp(path.join(tmpdir(), "j4a-cache-"))
  try {
    return await run(cacheDir)
  } finally {
    await rm(cacheDir, { recursive: true, force: true })
  }
}
