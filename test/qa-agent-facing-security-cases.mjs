import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"
import { redactRecord } from "../scripts/qa-agent-facing-redaction.mjs"
import { fakeEnvironment, runDriver } from "./qa-agent-facing-test-support.mjs"

test("retained protocol redaction hashes runtime locator and ref candidates", () => {
  const sensitiveRef = "opaque-runtime-ref-token"
  const retained = JSON.stringify(redactRecord({
    label: "runtime",
    message: { params: { locator: sensitiveRef }, result: { structuredContent: { ref: sensitiveRef } } },
  }))
  assert.equal(retained.includes(sensitiveRef), false)
  assert.match(retained, /"locator":"\[REDACTED_STRING sha256=[a-f0-9]{64}\]"/)
  assert.match(retained, /"ref":"\[REDACTED_STRING sha256=[a-f0-9]{64}\]"/)
})

test("QA driver hash-redacts sensitive protocol artifacts after unredacted parity validation", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-redaction-"))
  try {
    const sensitive = {
      name: "customer-derived-component",
      path: join(root, "owned", "customer-plan.jmx"),
      output: join(root, "owned", "customer-output.jmx"),
      value: "customer-derived-value",
      text: "customer-derived-text",
      key: "customer-derived-key",
    }
    const environment = fakeEnvironment(root, { matchingYaml: true, sensitive })
    const result = runDriver(environment)
    assert.equal(result.status, 0, result.stderr)
    const requests = readFileSync(join(environment.workDir, "requests.jsonl"), "utf8")
    const responses = readFileSync(join(environment.workDir, "responses.jsonl"), "utf8")
    const hashes = readFileSync(join(environment.workDir, "hashes.txt"), "utf8")
    const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
    for (const raw of Object.values(sensitive)) {
      assert.equal(requests.includes(raw), false)
      assert.equal(responses.includes(raw), false)
    }
    assert.equal(hashes.includes(environment.fixture), false)
    assert.equal(hashes.includes(environment.jar), false)
    assert.match(hashes, /\[REDACTED_PATH sha256=[a-f0-9]{64}/)
    assert.match(responses, /\[REDACTED_(?:PATH|VALUE|TEXT|STRING) sha256=[a-f0-9]{64}/)
    const responseRecords = responses.trim().split("\n").map(line => JSON.parse(line))
    const sensitiveResponse = responseRecords.find(record => record.message.result?.structuredContent?.data?.nested)
    assert.equal(typeof sensitiveResponse.message.result.structuredContent.data.name, "string")
    assert.equal(sensitiveResponse.message.result.structuredContent.data.diagnostic.code, "USAGE_ERROR")
    assert.equal(sensitiveResponse.message.result.structuredContent.data.diagnostic.category, "usage")
    assert.equal(sensitiveResponse.message.result.structuredContent.data.enabled, true)
    assert.equal(sensitiveResponse.message.result.structuredContent.data.count, 1)
    assert.equal(summary.parityLabels.length > 0, true)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver redacts live nested MCP failures from stderr and retained artifacts", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-failure-redaction-"))
  try {
    const sensitive = {
      name: "customer-derived-failure-name",
      path: join(root, "customer-plan.jmx"),
      output: join(root, "customer-output.jmx"),
      value: "customer-derived-failure-value",
      text: "customer-derived-failure-text",
      key: "customer-derived-failure-key",
    }
    const environment = fakeEnvironment(root, { sensitive })
    const result = runDriver(environment)
    const retained = [
      result.stderr,
      readFileSync(join(environment.workDir, "summary.json"), "utf8"),
      readFileSync(join(environment.workDir, "summary.txt"), "utf8"),
      readFileSync(join(environment.workDir, "requests.jsonl"), "utf8"),
      readFileSync(join(environment.workDir, "responses.jsonl"), "utf8"),
      readFileSync(join(environment.workDir, "hashes.txt"), "utf8"),
    ]
    const rawCandidates = [...Object.values(sensitive), environment.fixture, environment.workDir]
    assert.notEqual(result.status, 0)
    assert.match(result.stderr,
      /QA failure code=QA_DRIVER_FAILURE class=Error step=runtime detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
    assert.equal(existsSync(environment.workDir), true)
    for (const raw of rawCandidates) {
      for (const artifact of retained) assert.equal(artifact.includes(raw), false)
    }
    assert.match(retained[0], /\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
    assert.match(retained[1], /\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
    assert.match(retained[2], /\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver fails closed on a duplicate MCP response ID", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-duplicate-response-"))
  try {
    const result = runDriver(fakeEnvironment(root, { duplicateResponse: true }))
    assert.notEqual(result.status, 0)
    assert.match(result.stderr,
      /QA failure code=QA_DRIVER_FAILURE class=Error step=runtime detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("QA driver rejects duplicate post-mutation state", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-duplicate-state-"))
  try {
    const result = runDriver(fakeEnvironment(root, { matchingYaml: true, duplicateState: true }))
    assert.notEqual(result.status, 0)
    assert.match(result.stderr,
      /QA failure code=QA_DRIVER_FAILURE class=Error step=runtime detail=\[REDACTED_STRING sha256=[a-f0-9]{64}\]/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
