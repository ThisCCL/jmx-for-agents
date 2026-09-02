import assert from "node:assert/strict"
import { access, mkdtemp, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { cleanupOwnedRelease, preserveFailureAfterCleanup } from "./helpers/prepared-release-cleanup.mjs"

test("Given a close callback error, when owned release cleanup runs, then it removes the work directory and reports that close error", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-prepared-cleanup-"))
  await writeFile(path.join(workDir, "marker"), "owned", "utf8")

  await assert.rejects(
    cleanupOwnedRelease({ close: async () => { throw new Error("server close failed") }, workDir }),
    /server close failed/,
  )
  await assert.rejects(access(workDir), { code: "ENOENT" })
})

test("Given release construction and cleanup both fail, when the failure is preserved, then the result contains both errors", async () => {
  const primary = new Error("prepare failed")
  const cleanup = new Error("cleanup failed")

  assert.throws(
    () => preserveFailureAfterCleanup(primary, cleanup),
    error => error instanceof AggregateError && error.errors.includes(primary) && error.errors.includes(cleanup),
  )
})
