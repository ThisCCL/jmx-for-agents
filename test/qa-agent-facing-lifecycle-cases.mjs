import assert from "node:assert/strict"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { once } from "node:events"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"
import { McpSession, createProcessControl, killProcessTree } from "../scripts/qa-agent-facing-mcp-session.mjs"
import {
  assertChildStoppedEventually,
  assertChildStopped,
  assertControlledLifecycleFailure,
  fakeEnvironment,
  runDriver,
  startDriver,
  terminateDetachedProcessGroup,
  waitForFile,
} from "./qa-agent-facing-test-support.mjs"

test("Windows taskkill success terminates the requested process tree without a fallback", () => {
  const calls = []
  const control = createProcessControl({
    platform: "win32",
    spawnSync(command, arguments_, options) {
      calls.push({ command, arguments_, options })
      return { status: 0, signal: null }
    },
    kill() {
      throw new Error("fallback must not run after successful taskkill")
    },
  })

  killProcessTree(4123, control)

  assert.deepEqual(calls, [{
    command: "taskkill",
    arguments_: ["/pid", "4123", "/t", "/f"],
    options: { stdio: "ignore" },
  }])
})

test("Windows taskkill ENOENT falls back to a positive-PID kill", () => {
  const fallback = []
  const missingTaskkill = Object.assign(new Error("taskkill is unavailable"), { code: "ENOENT" })
  const control = createProcessControl({
    platform: "win32",
    spawnSync: () => ({ status: null, signal: null, error: missingTaskkill }),
    kill(pid, signal) {
      fallback.push({ pid, signal })
    },
  })

  killProcessTree(4124, control)

  assert.deepEqual(fallback, [{ pid: 4124, signal: "SIGKILL" }])
})

test("Windows taskkill nonzero, terminated, and launch-error outcomes stay observable", () => {
  for (const [name, result, expected] of [
    ["nonzero", { status: 1, signal: null }, /taskkill failed for pid 4125: status 1 signal null/],
    ["terminated", { status: null, signal: "SIGTERM" }, /taskkill failed for pid 4125: status null signal SIGTERM/],
    ["launch-error", { status: null, signal: null, error: Object.assign(new Error("access denied"), { code: "EACCES" }) }, /taskkill failed for pid 4125: access denied/],
  ]) {
    const control = createProcessControl({
      platform: "win32",
      spawnSync: () => result,
      kill() {
        throw new Error("fallback is only safe for missing taskkill")
      },
    })

    assert.throws(() => killProcessTree(4125, control), expected, name)
  }
})

test("Windows close bounds a hung child exit even when taskkill reports success", async () => {
  const taskkillCalls = []
  const control = createProcessControl({
    platform: "win32",
    spawnSync(command, arguments_) {
      taskkillCalls.push({ command, arguments_ })
      return { status: 0, signal: null }
    },
    kill() {
      throw new Error("fallback must not run after successful taskkill")
    },
  })
  const session = new McpSession("unused.jar", "unused-java", { processControl: control, exitGraceMs: 25 })
  session.child = { pid: 4126, stdin: { destroyed: true, writable: false, writableEnded: false } }
  session.exit = new Promise(() => {})
  session.failLifecycle(new Error("interrupted"))
  const started = Date.now()

  const close = session.close()
  await assert.rejects(Promise.all([close, session.close()]), /MCP did not exit within 25ms after shutdown/)

  assert.ok(Date.now() - started >= 20)
  assert.ok(Date.now() - started < 1_000)
  assert.equal(session.child, null)
  assert.deepEqual(taskkillCalls, [
    { command: "taskkill", arguments_: ["/pid", "4126", "/t", "/f"] },
    { command: "taskkill", arguments_: ["/pid", "4126", "/t", "/f"] },
  ])
})

test("QA driver rejects an in-flight MCP request and kills its detached process tree on repeated SIGTERM", { skip: process.platform === "win32" }, async () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-interrupt-"))
  try {
    const environment = fakeEnvironment(root, { holdToolsList: true, spawnDescendant: true })
    const driver = startDriver(environment)
    let stderr = ""
    driver.stderr.setEncoding("utf8")
    driver.stderr.on("data", chunk => { stderr += chunk })
    await Promise.all([waitForFile(environment.descendantPidFile), waitForFile(environment.toolsListSeenFile)])
    driver.kill("SIGTERM")
    driver.kill("SIGTERM")
    const [exitCode, signal] = await once(driver, "close")

    assert.notEqual(exitCode, 0)
    await assertChildStoppedEventually(environment.childPidFile)
    await assertChildStoppedEventually(environment.descendantPidFile)
    assert.equal(signal, null)
    assert.match(stderr, /^QA failure code=QA_DRIVER_FAILURE class=Error step=interrupted detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]\n$/)
    assert.match(readFileSync(join(environment.workDir, "summary.json"), "utf8"), /"ok": false/)
  } finally {
    terminateDetachedProcessGroup(join(root, "child.pid"))
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver records a controlled failure when MCP stdin closes before initialize", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-early-exit-"))
  try {
    const environment = fakeEnvironment(root, { closeStdinOnStart: true, pauseParentAfterSpawn: 500 })
    const result = runDriver(environment)
    assertControlledLifecycleFailure(result, environment, ["initialize"])
    assertChildStopped(environment.childPidFile)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver records a controlled failure when MCP exits before initialize", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-immediate-exit-"))
  try {
    const environment = fakeEnvironment(root, {
      exitImmediately: true,
      pauseParentAfterSpawn: 500,
      parentTimeoutCap: 250,
    })
    const result = runDriver(environment)
    assertControlledLifecycleFailure(result, environment, ["initialize"])
    assertChildStopped(environment.childPidFile)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver records a controlled failure when MCP stdin closes before initialized notification", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-notify-exit-"))
  try {
    const environment = fakeEnvironment(root, { closeStdinAfterInitialize: true })
    const result = runDriver(environment)
    assertControlledLifecycleFailure(result, environment, ["initialize", "notifications/initialized"])
    assertChildStopped(environment.childPidFile)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver settles promptly when MCP exits zero without a shutdown response", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-shutdown-exit-"))
  try {
    const environment = fakeEnvironment(root, {
      exitOnShutdownWithoutResponse: true,
      matchingYaml: true,
      pauseParentAfterSpawn: 1,
      parentTimeoutCap: 3_100,
    })
    const result = runDriver(environment)
    const exitFailure = "[REDACTED_STRING sha256=be9e22b5e76fd2453af03a9250226369f0e8d25ff6f2bb16af8cbd8c38290e8a]"
    assert.notEqual(result.status, 0)
    assert.equal(result.stderr, `QA failure code=QA_DRIVER_FAILURE class=Error step=cleanup detail=${exitFailure}\n`)
    assert.doesNotMatch(result.stderr, /EPIPE|Unhandled 'error' event|file:\/\//)
    assert.equal(readFileSync(environment.shutdownSeenFile, "utf8"), "shutdown")
    const requests = readFileSync(join(environment.workDir, "requests.jsonl"), "utf8")
      .trim().split("\n").map(line => JSON.parse(line))
    const responses = readFileSync(join(environment.workDir, "responses.jsonl"), "utf8")
      .trim().split("\n").filter(Boolean).map(line => JSON.parse(line))
    assert.equal(requests.filter(record => record.label === "shutdown").length, 1)
    assert.equal(responses.filter(record => record.label === "shutdown").length, 0)
    const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
    const summaryText = readFileSync(join(environment.workDir, "summary.txt"), "utf8")
    assert.equal(summary.ok, false)
    assert.equal(summary.assertions.includes("source_and_external_hashes_preserved"), true)
    assert.equal(summary.failure, exitFailure)
    assert.match(summaryText, /external_hash_preserved=true/)
    assert.match(summaryText, /owned_input_hash_preserved=true/)
    assertChildStopped(environment.childPidFile)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
