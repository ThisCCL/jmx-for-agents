import assert from "node:assert/strict"
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { spawnSync } from "node:child_process"
import test from "node:test"
import { fakeMcpJava } from "./qa-agent-facing-fake-mcp.mjs"
import {
  fakeEnvironment,
  runDriver,
  script,
  SETUP_FAILURE_OPERATIONS,
  setupFailureScript,
} from "./qa-agent-facing-test-support.mjs"

test("QA driver fails closed on missing, extra, and invalid arguments", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-args-"))
  try {
    const workDir = join(root, "must-not-exist")
    const common = [
      "--jar", "missing.jar",
      "--jmeter-home", "missing-jmeter",
      "--fixture", "missing.jmx",
      "--work-dir", workDir,
    ]
    for (const args of [[], [...common, "--case", "unknown"], [...common, "--case", "full", "--extra", "x"]]) {
      const result = spawnSync(process.execPath, [script, ...args], { encoding: "utf8" })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, /Usage:|Unsupported|Missing|required|Invalid/)
    }
    assert.equal(existsSync(workDir), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver redacts a preflight failure before creating its work directory", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-preflight-redaction-"))
  try {
    const sensitiveJar = join(root, "customer-derived-preflight-jar.jmx")
    const workDir = join(root, "must-not-exist")
    const result = spawnSync(process.execPath, [script,
      "--jar", sensitiveJar,
      "--jmeter-home", join(root, "missing-jmeter"),
      "--fixture", join(root, "missing-fixture.jmx"),
      "--work-dir", workDir,
      "--case", "full",
    ], { encoding: "utf8" })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr,
      /QA failure code=QA_DRIVER_FAILURE class=Error step=preflight detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
    assert.equal(result.stderr.includes(sensitiveJar), false)
    assert.equal(existsSync(workDir), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver redacts fixture setup failures after creating its work directory", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-setup-redaction-"))
  try {
    for (const operation of SETUP_FAILURE_OPERATIONS) {
      const environment = fakeEnvironment(join(root, operation.name))
      const input = join(environment.workDir, "input.jmx")
      const sensitiveFailure = `customer-derived-${operation.name} ${environment.fixture} ${environment.workDir} ${input}`
      const result = runDriver(environment, setupFailureScript(join(root, operation.name), operation, sensitiveFailure))
      assert.notEqual(result.status, 0)
      assert.equal(result.stdout, "")
      assert.match(result.stderr,
        /QA failure code=QA_DRIVER_FAILURE class=Error step=setup detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
      assert.equal(existsSync(environment.workDir), true)
      const artifactNames = readdirSync(environment.workDir).sort()
      assert.deepEqual(artifactNames, operation.artifactNames)
      const artifacts = artifactNames.map(name => readFileSync(join(environment.workDir, name), "utf8"))
      for (const raw of [sensitiveFailure, environment.fixture, environment.workDir, input]) {
        assert.equal(result.stderr.includes(raw), false)
        for (const artifact of artifacts) assert.equal(artifact.includes(raw), false)
      }
      const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
      assert.equal(summary.ok, false)
      assert.match(summary.failure, /^\[REDACTED_STRING sha256=[a-f0-9]{64}\]$/)
      const hashes = readFileSync(join(environment.workDir, "hashes.txt"), "utf8")
      const summaryText = readFileSync(join(environment.workDir, "summary.txt"), "utf8")
      assert.equal(existsSync(input), operation.inputExists)
      assert.equal(hashes.includes("external_before"), operation.externalHashRecorded)
      assert.equal(hashes.includes("owned_input_before"), false)
      assert.match(summaryText, new RegExp(`external_hash_preserved=${operation.externalHashPreserved}`))
      assert.match(summaryText, /owned_input_hash_preserved=false/)
    }
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver rejects live successful YAML content that differs from structured data", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-parity-"))
  try {
    const javaHome = join(root, "java-home")
    const java = join(javaHome, "bin", "java")
    const jmeterHome = join(root, "jmeter")
    const jar = join(root, "j4a.jar")
    const fixture = join(root, "fixture.jmx")
    const workDir = join(root, "work")
    mkdirSync(join(javaHome, "bin"), { recursive: true })
    mkdirSync(join(jmeterHome, "lib", "ext"), { recursive: true })
    mkdirSync(join(jmeterHome, "bin"), { recursive: true })
    writeFileSync(join(jmeterHome, "bin", "jmeter.properties"), "")
    writeFileSync(join(jmeterHome, "bin", "saveservice.properties"), "")
    writeFileSync(join(jmeterHome, "bin", "upgrade.properties"), "")
    writeFileSync(jar, "fake jar")
    writeFileSync(fixture, "fake fixture")
    writeFileSync(java, fakeMcpJava())
    chmodSync(java, 0o755)
    const result = spawnSync(process.execPath, [script,
      "--jar", jar,
      "--jmeter-home", jmeterHome,
      "--fixture", fixture,
      "--work-dir", workDir,
      "--case", "full",
    ], { encoding: "utf8", env: { ...process.env, JAVA_HOME: javaHome } })
    assert.notEqual(result.status, 0)
    assert.match(result.stderr,
      /QA failure code=QA_DRIVER_FAILURE class=Error step=runtime detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
