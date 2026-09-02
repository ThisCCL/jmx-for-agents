import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"

import { fakeEnvironment, runDriver } from "./qa-agent-facing-test-support.mjs"

const mutations = [
  ["non-writable property in writable", { interactionMutation: "non-writable-in-writable" }, "read-mcp-writable", "WRITABLE_PROJECTION"],
  ["ref_scope token duplication", { interactionMutation: "ref-scope-token" }, "read-mcp-all", "REF_SCOPE"],
  ["recovery omits caller path", { interactionMutation: "recovery-missing-path" }, "stale-set", "STALE_RECOVERY"],
  ["tools/list over budget", { interactionMutation: "tools-over-budget" }, "tools-list", "TOOLS_LIST_BUDGET"],
  ["silent mutation retry", { interactionMutation: "silent-retry" }, "read-copy-output", "GENERATED_PAYLOAD"],
  ["success output masking nonzero diagnostics", { interactionMutation: "masked-diagnostic" }, "read-mcp-all", "SUCCESS_RESPONSE"],
  ["original hash drift", { interactionMutation: "original-hash-drift" }, "shutdown", "POST_SHUTDOWN_HASH"],
  ["stale generated payload", { interactionMutation: "stale-generated-payload" }, "read-copy-output", "GENERATED_PAYLOAD"],
  ["remaining Java process", { interactionMutation: "java-process-remains" }, "shutdown", "PROCESS_CLEANUP"],
  ["category page over byte budget", { interactionMutation: "category-page-over-budget" }, "mcp-category-page-1", "CATEGORY_PAGING"],
  ["fatal response with wrong exit", { interactionMutation: "fatal-wrong-exit" }, "fatal-apply-worker-kill", "APPLY_FAILURE_MATRIX"],
]

test("interaction-ux driver accepts the complete journey fixture", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-interaction-ux-green-"))
  try {
    const environment = fakeEnvironment(root, { matchingYaml: true, interactionUx: true })
    const result = runDriver(environment, undefined, "interaction-ux")
    assert.equal(result.status, 0, result.stderr)
    const summary = readFileSync(join(environment.workDir, "summary.md"), "utf8")
    assert.match(summary, /^# J4A interaction UX QA: PASS$/m)
    const structured = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
    assert.equal(structured.assertions.includes(
      "CLI_and_MCP_category_pages_are_exact_FQCN_ordered_complete_and_within_4096_UTF8_bytes"), true)
    assert.equal(structured.assertions.includes(
      "fatal_apply_worker_kill_returned_one_response_required_inspect_before_retry_and_never_replayed"), true)
    const retained = ["requests.jsonl", "responses.jsonl"].map(name => readFileSync(join(environment.workDir, name), "utf8"))
    for (const artifact of retained) assert.equal(artifact.includes("QA initialized"), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

for (const [name, option, expectedLabel, expectedGuard] of mutations) {
  test(`interaction-ux driver rejects ${name}`, () => {
    const root = mkdtempSync(join(tmpdir(), "j4a-interaction-ux-mutation-"))
    try {
      const environment = fakeEnvironment(root, { matchingYaml: true, interactionUx: true, ...option })
      const result = runDriver(environment, undefined, "interaction-ux")
      assert.notEqual(result.status, 0)
      assert.equal(existsSync(environment.workDir), true)
      const requestsPath = join(environment.workDir, "requests.jsonl")
      assert.equal(existsSync(requestsPath), true)
      const requests = readFileSync(requestsPath, "utf8")
      assert.match(requests, new RegExp(`"label":"${expectedLabel}"`))
      const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
      assert.equal(summary.ok, false)
      assert.equal(summary.case, "interaction-ux")
      assert.equal(summary.failureGuard, expectedGuard)
      if (option.interactionMutation === "silent-retry") {
        const writes = readFileSync(join(root, "mutation-writes.log"), "utf8").trim().split(/\r?\n/)
        assert.deepEqual(writes.filter(line => line === "apply-copy.jmx"), ["apply-copy.jmx", "apply-copy.jmx"])
      }
      if (option.interactionMutation === "original-hash-drift") {
        assert.notEqual(readFileSync(environment.fixture, "utf8"), "fake fixture")
      }
      if (option.interactionMutation === "java-process-remains") {
        assert.equal(readFileSync(join(root, "leaked-process-kind.txt"), "utf8"), "java")
      }
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
}
