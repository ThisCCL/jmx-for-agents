import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { readFile } from "node:fs/promises"
import path from "node:path"
import test from "node:test"
import { promisify } from "node:util"

import { discoverDefaultTestFiles } from "../scripts/run-node-test-suite.mjs"

const execFileAsync = promisify(execFile)
const root = path.resolve()
const testDirectory = path.join(root, "test")

test("the default Node suite discovers every retained root test and only excludes opt-in tests", async () => {
  const files = await discoverDefaultTestFiles({ testDirectory })

  assert.ok(files.length > 0)
  assert.ok(files.every(file => file.startsWith("test/")))
  assert.ok(!files.includes("test/downloader-security.test.mjs"))
  assert.ok(!files.includes("test/verify-property-graph-gates.test.mjs"))
  assert.ok(files.includes("test/ci-workflow.test.mjs"))
  assert.ok(files.includes("test/node-suite.test.mjs"))
})

test("the suite CLI exposes the same retained test inventory", async () => {
  const { stdout } = await execFileAsync(process.execPath, ["scripts/run-node-test-suite.mjs", "--list"], { cwd: root })

  assert.deepEqual(JSON.parse(stdout), await discoverDefaultTestFiles({ testDirectory }))
})

test("package scripts make the retained suite default and the historical validator opt-in", async () => {
  const packageJson = JSON.parse(await readFile(path.join(root, "package.json"), "utf8"))

  assert.equal(packageJson.scripts.test, "node scripts/run-node-test-suite.mjs")
  assert.equal(
    packageJson.scripts["test:property-graph-gates"],
    "node --test test/verify-property-graph-gates.test.mjs",
  )
})
