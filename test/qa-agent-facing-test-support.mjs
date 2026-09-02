import assert from "node:assert/strict"
import {
  chmodSync,
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from "node:fs"
import { join } from "node:path"
import { spawn, spawnSync } from "node:child_process"
import { fakeMcpJava } from "./qa-agent-facing-fake-mcp.mjs"

export const script = join(process.cwd(), "scripts", "qa-agent-facing-mcp.mjs")

export function fakeEnvironment(root, options = {}) {
  const javaHome = join(root, "java-home")
  const jmeterHome = join(root, "jmeter")
  const jar = join(root, "j4a.jar")
  const fixture = join(root, "fixture.jmx")
  const workDir = join(root, "work")
  const childPidFile = join(root, "child.pid")
  const descendantPidFile = join(root, "descendant.pid")
  const shutdownSeenFile = join(root, "shutdown-seen.txt")
  const toolsListSeenFile = join(root, "tools-list-seen.txt")
  mkdirSync(join(javaHome, "bin"), { recursive: true })
  mkdirSync(join(jmeterHome, "lib", "ext"), { recursive: true })
  mkdirSync(join(jmeterHome, "bin"), { recursive: true })
  writeFileSync(join(jmeterHome, "bin", "jmeter.properties"), "")
  writeFileSync(join(jmeterHome, "bin", "saveservice.properties"), "")
  writeFileSync(join(jmeterHome, "bin", "upgrade.properties"), "")
  writeFileSync(jar, "fake jar")
  writeFileSync(fixture, "fake fixture")
  writeFileSync(join(javaHome, "bin", "java"), fakeMcpJava({
    ...options,
    childPidFile,
    descendantPidFile,
    fixture,
    leakedProcessKindFile: join(root, "leaked-process-kind.txt"),
    mutationWriteLog: join(root, "mutation-writes.log"),
    shutdownSeenFile,
    toolsListSeenFile,
  }))
  chmodSync(join(javaHome, "bin", "java"), 0o755)
  const nodeOptions = options.pauseParentAfterSpawn
    ? spawnPauseNodeOptions(root, options.pauseParentAfterSpawn, options.parentTimeoutCap)
    : ""
  return {
    javaHome, jmeterHome, jar, fixture, workDir, childPidFile, descendantPidFile, shutdownSeenFile, toolsListSeenFile, nodeOptions,
  }
}

export const SETUP_FAILURE_OPERATIONS = Object.freeze([
  {
    name: "fixture-hash",
    line: "originalBefore = sha256File(fixture)",
    currentShape: "let originalBefore = null",
    legacyShape: "const originalBefore = sha256File(fixture)",
    inputExists: false,
    artifactNames: ["hashes.txt", "summary.json", "summary.txt"],
    externalHashRecorded: false,
    externalHashPreserved: false,
  },
  {
    name: "fixture-copy",
    line: "copyFileSync(fixture, input)",
    currentShape: 'input = inside("input.jmx")',
    legacyShape: 'const input = inside("input.jmx")',
    inputExists: false,
    artifactNames: ["hashes.txt", "summary.json", "summary.txt"],
    externalHashRecorded: true,
    externalHashPreserved: true,
  },
  {
    name: "owned-input-hash",
    line: "inputBefore = sha256File(input)",
    currentShape: "let inputBefore = null",
    legacyShape: "const inputBefore = sha256File(input)",
    inputExists: true,
    artifactNames: ["hashes.txt", "input.jmx", "summary.json", "summary.txt"],
    externalHashRecorded: true,
    externalHashPreserved: true,
  },
])

export function runDriver(environment, driverScript = script, caseName = "full") {
  return spawnSync(process.execPath, driverArguments(environment, driverScript, caseName), { encoding: "utf8", env: driverEnvironment(environment) })
}

export function startDriver(environment, driverScript = script, caseName = "full") {
  return spawn(process.execPath, driverArguments(environment, driverScript, caseName), {
    env: driverEnvironment(environment),
    stdio: ["ignore", "pipe", "pipe"],
  })
}

export async function waitForFile(path, timeoutMs = 5_000) {
  const started = Date.now()
  while (!existsSync(path)) {
    if (Date.now() - started >= timeoutMs) throw new Error(`timed out waiting for ${path}`)
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

export async function assertChildStoppedEventually(pidFile, timeoutMs = 5_000) {
  await waitForFile(pidFile, timeoutMs)
  const pid = Number(readFileSync(pidFile, "utf8"))
  const started = Date.now()
  for (;;) {
    try {
      process.kill(pid, 0)
    } catch (error) {
      if (error?.code === "ESRCH") return
      throw error
    }
    if (Date.now() - started >= timeoutMs) throw new Error(`child process ${pid} remained after interruption`)
    await new Promise(resolve => setTimeout(resolve, 10))
  }
}

export function terminateDetachedProcessGroup(pidFile) {
  if (!existsSync(pidFile)) return
  const pid = Number(readFileSync(pidFile, "utf8"))
  try {
    process.kill(-pid, "SIGKILL")
  } catch (error) {
    if (error?.code !== "ESRCH") throw error
  }
}

function driverArguments(environment, driverScript, caseName) {
  return [driverScript,
    "--jar", environment.jar,
    "--jmeter-home", environment.jmeterHome,
    "--fixture", environment.fixture,
    "--work-dir", environment.workDir,
    "--case", caseName,
  ]
}

function driverEnvironment(environment) {
  return {
    ...process.env,
    JAVA_HOME: environment.javaHome,
    ...(environment.nodeOptions ? { NODE_OPTIONS: environment.nodeOptions } : {}),
  }
}

function spawnPauseNodeOptions(root, milliseconds, timeoutCap) {
  const preload = join(root, "pause-child-spawn.cjs")
  const timeoutOverride = timeoutCap === undefined ? "" : `
const originalSetTimeout = global.setTimeout
global.setTimeout = (callback, delay, ...args) => originalSetTimeout(callback, Math.min(delay, ${timeoutCap}), ...args)
`
  writeFileSync(preload, `
const childProcess = require("node:child_process")
const { syncBuiltinESMExports } = require("node:module")
${timeoutOverride}
const originalSpawn = childProcess.spawn
childProcess.spawn = (...args) => {
  const child = originalSpawn(...args)
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ${milliseconds})
  return child
}
syncBuiltinESMExports()
`)
  return `--require=${preload}`
}

export function assertControlledLifecycleFailure(result, environment, expectedRequestLabels) {
  assert.notEqual(result.status, 0)
  assert.equal(result.stdout, "")
  assert.match(result.stderr,
    /^QA failure code=QA_DRIVER_FAILURE class=Error step=runtime detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]\n$/)
  assert.doesNotMatch(result.stderr, /EPIPE|Unhandled 'error' event|file:\/\//)
  for (const raw of [environment.fixture, environment.workDir, environment.javaHome]) {
    assert.equal(result.stderr.includes(raw), false)
  }
  const artifactNames = readdirSync(environment.workDir).sort()
  assert.deepEqual(artifactNames, [
    "compatibility-input.jmx", "hashes.txt", "input.jmx", "requests.jsonl", "responses.jsonl",
    "summary.json", "summary.txt",
  ])
  const requests = readFileSync(join(environment.workDir, "requests.jsonl"), "utf8")
    .trim().split("\n").filter(Boolean).map(line => JSON.parse(line))
  assert.deepEqual(requests.map(record => record.label), expectedRequestLabels)
  const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
  const summaryText = readFileSync(join(environment.workDir, "summary.txt"), "utf8")
  const hashes = readFileSync(join(environment.workDir, "hashes.txt"), "utf8")
  assert.equal(summary.ok, false)
  assert.match(summary.failure, /^\[REDACTED_STRING sha256=[a-f0-9]{64}\]$/)
  assert.match(summaryText, /external_hash_preserved=true/)
  assert.match(summaryText, /owned_input_hash_preserved=true/)
  assert.match(hashes, /external_before [a-f0-9]{64} \[REDACTED_PATH sha256=[a-f0-9]{64}\]/)
  assert.match(hashes, /external_after [a-f0-9]{64} \[REDACTED_PATH sha256=[a-f0-9]{64}\]/)
  assert.match(hashes, /owned_input_before [a-f0-9]{64} input\.jmx/)
  assert.match(hashes, /owned_input_after [a-f0-9]{64} input\.jmx/)
}

export function assertChildStopped(pidFile) {
  const pid = Number(readFileSync(pidFile, "utf8"))
  assert.throws(() => process.kill(pid, 0), error => error?.code === "ESRCH")
}

export function setupFailureScript(root, operation, sensitiveFailure) {
  const source = readFileSync(script, "utf8")
  assert.equal(countOccurrences(source, operation.line), 1,
    `setup failure seam must match exactly once for ${operation.name}`)
  assert.equal(source.includes(operation.currentShape), true,
    `current setup source shape missing for ${operation.name}`)
  assert.equal(source.includes(operation.legacyShape), false,
    `legacy setup source shape unexpectedly present for ${operation.name}`)
  const failureExpression = `(() => { throw new Error(${JSON.stringify(sensitiveFailure)}) })()`
  const replacement = operation.name === "fixture-copy"
    ? failureExpression
    : `${operation.line.split(" = ")[0]} = ${failureExpression}`
  const changed = source.replace(operation.line, replacement)
  const scriptsDir = join(process.cwd(), "scripts")
  for (const name of readdirSync(scriptsDir)) {
    if (name.startsWith("qa-agent-facing-") && name.endsWith(".mjs") && name !== "qa-agent-facing-mcp.mjs") {
      copyFileSync(join(scriptsDir, name), join(root, name))
    }
  }
  const driverScript = join(root, `qa-agent-facing-mcp-${Buffer.from(operation.line).toString("hex")}.mjs`)
  writeFileSync(driverScript, changed)
  return driverScript
}

function countOccurrences(source, value) {
  return source.split(value).length - 1
}
