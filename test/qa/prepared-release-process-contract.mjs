import assert from "node:assert/strict"
import test from "node:test"

import { runBounded } from "./helpers/prepared-release-process.mjs"

test("Given a command that does not exit, when the bounded runner reaches its timeout, then it kills the owned process before rejecting", async () => {
  let childPid

  await assert.rejects(
    runBounded(process.execPath, ["-e", "setInterval(() => {}, 1_000)"], {
      onSpawn(pid) {
        childPid = pid
      },
      timeoutMs: 250,
    }),
    /timed out after 250ms/,
  )

  assert.notEqual(childPid, undefined)
  assert.throws(
    () => process.kill(childPid, 0),
    error => error && error.code === "ESRCH",
  )
})
