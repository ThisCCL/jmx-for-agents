import { existsSync, readFileSync, readdirSync, writeFileSync } from "node:fs"
import { spawnSync } from "node:child_process"

import { assert, equal } from "./qa-agent-facing-assertions.mjs"
import { sha256File } from "./qa-agent-facing-artifacts.mjs"
import { findNode, hasProperty } from "./qa-agent-facing-model.mjs"
import { parseYamlDocument, yamlDocument } from "./qa-agent-facing-yaml.mjs"

export async function runApplyFailureContract(mcp, response, context) {
  const all = response.successData(await mcp.call("read", {
    file: context.input, depth: 20, properties: "all", jmeter_home: context.jmeterHome,
  }, "contract-read-for-errors"), "contract failure source read", true)
  const node = findNode(all, candidate => hasProperty(candidate, ["qa.float"]))
    || findNode(all, candidate => hasProperty(candidate, ["TestElement.name"]))
  assert(node?.ref, "failure matrix source ref missing")
  cliFailureMatrix({ ...context, ref: node.ref })
  await fatalMcpApply(mcp, response, { ...context, ref: node.ref })
}

function cliFailureMatrix(context) {
  const sourceHash = sha256File(context.input)
  const cliRead = runCli(["read", context.input, "--properties", "all", "--jmeter-home", context.jmeterHome],
    context, "cli-read-for-failure-matrix")
  equal(cliRead.status, 0, "CLI failure matrix read exit")
  equal(cliRead.stderr, "", "CLI failure matrix read stderr")
  const cliNode = findNode(parseYamlDocument(cliRead.stdout), candidate => hasProperty(candidate, ["TestElement.name"]))
  assert(cliNode?.ref, "CLI failure matrix ref missing")
  const accepted = yamlDocument({ changes: [{ set: { ref: cliNode.ref, properties: [
    { property: ["TestElement.name"], type: "string", value: "matrix-safe" },
  ] } }] })
  const usage = runCli(["apply", context.input, "--patch", "-", "--dry-run",
    "--jmeter-home", context.jmeterHome], context, "cli-apply-schema-exit2", "changes:\n- set:\n    properties: []\n")
  equal(usage.status, 2, "CLI apply schema exit")
  assert(/changes\[0\]|change(?:s)?(?: index)?:?\s*0/i.test(usage.stderr),
    "schema failure omitted partial card context")
  assert(!/value:/i.test(usage.stderr), "schema failure leaked submitted value")
  const semanticPatch = yamlDocument({ changes: [{ add: { parent: cliNode.ref, position: "last",
    component: "example.invalid.UnknownComponent", as: "bad", properties: [
      { property: ["TestElement.name"], type: "string", value: "must-not-write" },
    ] } }] })
  const semantic = runCli(["apply", context.input, "--patch", "-", "--dry-run",
    "--jmeter-home", context.jmeterHome], context, "cli-apply-semantic-exit3", semanticPatch)
  equal(semantic.status, 3, "CLI apply semantic exit")
  assert(/changes\[0\]|operation: add|phase:/i.test(semantic.stderr), "semantic failure omitted card/phase")
  assert(/cause|exception|component|material/i.test(semantic.stderr), "semantic failure omitted source cause")
  const existing = context.inside("infrastructure-existing.jmx")
  writeFileSync(existing, "owned-existing-target", "utf8")
  const existingHash = sha256File(existing)
  const infrastructure = runCli(["apply", context.input, "--patch", "-", "--out", existing,
    "--jmeter-home", context.jmeterHome], context, "cli-apply-infrastructure-exit4", accepted)
  equal(infrastructure.status, 4, "CLI apply filesystem infrastructure exit")
  equal(sha256File(existing), existingHash, "infrastructure target hash")
  equal(sha256File(context.input), sourceHash, "CLI failure matrix source hash")
  equal(sourceHash, context.inputBefore, "CLI failure matrix original source hash")
}

async function fatalMcpApply(mcp, response, context) {
  assert(process.platform !== "win32", "fatal worker-kill QA requires /proc")
  const target = context.inside("fatal-target.jmx")
  writeFileSync(target, readFileSync(context.input))
  const sourceBefore = sha256File(context.input)
  const targetBefore = sha256File(target)
  const submittedValue = `fatal-probe-${"x".repeat(3_000_000)}`
  const changes = [{ set: { ref: context.ref, properties: [
    { property: ["TestElement.name"], type: "string", value: submittedValue },
  ] } }]
  const call = mcp.call("apply", {
    file: context.input, patchYaml: yamlDocument({ changes }), out: target, forceOut: true,
    jmeter_home: context.jmeterHome,
  }, "fatal-apply-worker-kill")
  const workerPid = await waitForWorkerChild(mcp.child.pid, 10_000)
  const readBefore = procReadChars(workerPid)
  await waitUntil(() => procReadChars(workerPid) > readBefore, 10_000, "worker did not receive authorized apply")
  process.kill(workerPid, "SIGKILL")
  const fatalResponse = await call
  const fatal = response.requireToolError(fatalResponse, "fatal apply worker kill")
  equal(fatal.structuredContent.exitStatus, 4, "fatal apply exit")
  const recovery = JSON.stringify(fatal).toLowerCase()
  assert(recovery.includes("read") || recovery.includes("inspect"), "fatal recovery omitted inspect/read target")
  assert(recovery.includes("retry"), "fatal recovery omitted retry decision")
  assert(!JSON.stringify(fatal).includes("fatal-probe-"), "fatal response repeated submitted value")
  equal(sha256File(context.input), sourceBefore, "fatal source hash")
  equal(sha256File(target), targetBefore, "fatal target hash")
  context.summary.failureHashes = {
    source: { before: sourceBefore, after: sha256File(context.input), preserved: true },
    target: { before: targetBefore, after: sha256File(target), preserved: true },
  }
  const inspect = response.successData(await mcp.call("read", {
    file: target, properties: "all", jmeter_home: context.jmeterHome,
  }, "fatal-inspect-target-before-retry"), "fatal recovery inspect", true)
  assert(inspect.root, "fatal recovery read omitted target tree")
  const stale = response.requireToolError(await mcp.call("read", {
    file: context.input, ref: context.ref, properties: "all", jmeter_home: context.jmeterHome,
  }, "fatal-confirm-ref-eviction"), "fatal ref eviction")
  assert(/ref|locator/i.test(JSON.stringify(stale)), "fatal ref eviction diagnostic omitted ref")
  equal(mcp.requests.filter(record => record.label === "fatal-apply-worker-kill").length, 1,
    "fatal apply request count")
  equal(mcp.responses.filter(record => record.label === "fatal-apply-worker-kill").length, 1,
    "fatal apply response count")
  equal(mcp.stderr.trim(), "", "MCP stderr after fatal worker eviction")
  assert(existsSync(target), "fatal target disappeared")
}

function runCli(args, context, label, input) {
  const command = [context.javaCommand, "-jar", context.jar, ...args]
  context.commands.push(`${label}: ${command.map(shellQuote).join(" ")}`)
  return spawnSync(context.javaCommand, ["-jar", context.jar, ...args], {
    encoding: "utf8", input, timeout: 120_000,
  })
}

async function waitForWorkerChild(parentPid, timeoutMs) {
  let child
  await waitUntil(() => {
    const children = [...new Set(readdirSync(`/proc/${parentPid}/task`).flatMap(thread =>
      readFileSync(`/proc/${parentPid}/task/${thread}/children`, "utf8").trim()
        .split(/\s+/).filter(Boolean).map(Number)))]
    assert(children.length <= 1, "MCP has multiple worker children during fatal probe")
    child = children[0]
    return child !== undefined
  }, timeoutMs, "MCP worker child did not start for authorized apply")
  return child
}

function procReadChars(pid) {
  const match = /^rchar:\s*(\d+)$/m.exec(readFileSync(`/proc/${pid}/io`, "utf8"))
  assert(match, `worker ${pid} rchar unavailable`)
  return Number(match[1])
}

async function waitUntil(predicate, timeoutMs, message) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    try { if (predicate()) return } catch (error) { if (error?.code !== "ENOENT") throw error }
    await new Promise(resolve => setTimeout(resolve, 5))
  }
  throw new Error(message)
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`
}
