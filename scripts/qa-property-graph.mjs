#!/usr/bin/env node

import { spawn, execFileSync } from "node:child_process"
import { createHash, randomUUID } from "node:crypto"
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  readlinkSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs"
import { basename, delimiter, dirname, isAbsolute, join, resolve } from "node:path"
import { tmpdir } from "node:os"

const MCP_MAIN = "io.github.thisccl.j4a.mcp.J4aMcpServer"
const ASSERTION_PROPERTY = ["Asserion.test_strings"]
const ASSERTION_PROPERTY_YAML = "[Asserion.test_strings]"
const ADDED_ASSERTION_NAME = "QA Added Response Assertion"
const OPAQUE_LISTENER_NAME = "QA Opaque Listener"
const ADD_ASSERTIONS = ["login failed", "access denied", "add-value"]
const SET_ASSERTIONS = ["set-value-A", "set-value-B"]
const ACTUAL_PERSISTED_ASSERTIONS = ["apply-value-1", "apply-value-2", "apply-value-3"]
const EXPECTED_PERSISTED_ASSERTIONS = ["apply-value-1", "apply-value-2", "apply-value-3"]
const PROCESS_TIMEOUT_MS = 120_000
const MCP_TIMEOUT_MS = 60_000

class McpSession {
  constructor(name) {
    this.name = name
    this.child = null
    this.stdout = ""
    this.stderr = ""
    this.partial = ""
    this.requests = []
    this.responses = []
    this.pending = new Map()
    this.rawWaiters = []
    this.nextId = 1
    this.exit = null
    this.closed = false
  }

  async start() {
    writeJson(`mcp-${this.name}.command.json`, {
      command: "java", args: ["-cp", jar, MCP_MAIN], cwd: root, timeout_ms: MCP_TIMEOUT_MS, java_home: javaHome,
    })
    this.child = spawn("java", ["-cp", jar, MCP_MAIN], {
      cwd: root, env: javaEnv, detached: true, stdio: ["pipe", "pipe", "pipe"],
    })
    registerProcess(this.child.pid, {
      name: `mcp-${this.name}`, pgid: this.child.pid, command: ["java", "-cp", jar, MCP_MAIN],
    })
    this.child.stdout.setEncoding("utf8")
    this.child.stderr.setEncoding("utf8")
    this.child.stdout.on("data", chunk => this.consume(chunk))
    this.child.stderr.on("data", chunk => { this.stderr += chunk })
    this.exit = new Promise((resolveExit, reject) => {
      this.child.once("error", reject)
      this.child.once("close", (code, signal) => {
        unregisterProcess(this.child.pid, { code, signal })
        for (const pending of this.pending.values()) pending.reject(new Error(`MCP ${this.name} exited before response`))
        this.pending.clear()
        resolveExit({ code, signal })
      })
    })
  }

  consume(chunk) {
    this.stdout += chunk
    this.partial += chunk
    for (;;) {
      const newline = this.partial.indexOf("\n")
      if (newline < 0) return
      const line = this.partial.slice(0, newline).replace(/\r$/, "")
      this.partial = this.partial.slice(newline + 1)
      if (!line) continue
      let message
      try { message = JSON.parse(line) } catch {
        this.failPending(new Error(`MCP ${this.name} emitted non-JSON stdout: ${line.slice(0, 200)}`))
        continue
      }
      this.responses.push(message)
      const rawWaiter = this.rawWaiters.shift()
      if (rawWaiter) {
        clearTimeout(rawWaiter.timer)
        registry.timers.delete(rawWaiter.timer)
        rawWaiter.resolve(message)
      }
      if (message.id !== undefined && message.id !== null) {
        const pending = this.pending.get(String(message.id))
        if (pending) {
          clearTimeout(pending.timer)
          registry.timers.delete(pending.timer)
          this.pending.delete(String(message.id))
          pending.resolve(message)
        }
      }
    }
  }

  failPending(error) {
    for (const pending of this.pending.values()) pending.reject(error)
    this.pending.clear()
  }

  async request(method, params, label) {
    if (!this.child || this.closed) throw new Error(`MCP ${this.name} is not open`)
    const id = this.nextId++
    const request = { jsonrpc: "2.0", id, method, ...(params === undefined ? {} : { params }) }
    this.requests.push({ label, message: request })
    const response = await new Promise((resolveResponse, reject) => {
      const timer = setTimeout(() => {
        registry.timers.delete(timer)
        this.pending.delete(String(id))
        killProcessGroup(this.child.pid)
        reject(new Error(`MCP ${this.name} request ${label} timed out`))
      }, MCP_TIMEOUT_MS)
      registry.timers.add(timer)
      this.pending.set(String(id), { resolve: resolveResponse, reject, timer })
      this.child.stdin.write(`${JSON.stringify(request)}\n`, "utf8")
    })
    if (response.error) throw new Error(`MCP JSON-RPC error for ${label}: ${JSON.stringify(response.error)}`)
    return response
  }

  notify(method, params) {
    const message = { jsonrpc: "2.0", method, params }
    this.requests.push({ label: method, message })
    this.child.stdin.write(`${JSON.stringify(message)}\n`, "utf8")
  }

  async initialize() {
    const response = await this.request("initialize", {
      protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "j4a-property-graph-qa", version: "1.0.0" },
    }, "initialize")
    assertEqual(response.result.protocolVersion, "2025-06-18", "MCP protocol version")
    assertEqual(response.result.capabilities?.tools?.listChanged, false, "MCP tools listChanged")
    assertEqual(response.result.serverInfo?.name, "j4a", "MCP server name")
    assertEqual(response.result.serverInfo?.version, packageVersion, "MCP server version")
    this.notify("notifications/initialized", {})
  }

  async call(name, args, label) {
    return this.request("tools/call", { name, arguments: args }, label)
  }

  async raw(line, label) {
    if (!this.child || this.closed) throw new Error(`MCP ${this.name} is not open`)
    this.requests.push({ label, raw: line })
    const response = new Promise((resolveResponse, reject) => {
      const timer = setTimeout(() => {
        registry.timers.delete(timer)
        const index = this.rawWaiters.findIndex(waiter => waiter.timer === timer)
        if (index >= 0) this.rawWaiters.splice(index, 1)
        reject(new Error(`MCP ${this.name} raw request ${label} timed out`))
      }, MCP_TIMEOUT_MS)
      registry.timers.add(timer)
      this.rawWaiters.push({ resolve: resolveResponse, reject, timer })
    })
    this.child.stdin.write(`${line}\n`, "utf8")
    return response
  }

  async close() {
    if (!this.child || this.closed) return
    this.closed = true
    let shutdownError = null
    this.nextId = 99
    try { await this.requestForClose() } catch (error) { shutdownError = error }
    this.child.stdin.end()
    const timer = setTimeout(() => killProcessGroup(this.child.pid), 10_000)
    registry.timers.add(timer)
    const exit = await this.exit
    clearTimeout(timer)
    registry.timers.delete(timer)
    writeText(`mcp-${this.name}.requests.jsonl`, this.requests.map(value => value.raw ?? JSON.stringify(value.message)).join("\n") + "\n")
    writeText(`mcp-${this.name}.request-labels.jsonl`, this.requests.map(value => JSON.stringify({ label: value.label })).join("\n") + "\n")
    writeText(`mcp-${this.name}.responses.jsonl`, this.responses.map(value => JSON.stringify(value)).join("\n") + "\n")
    writeText(`mcp-${this.name}.stdout.jsonl`, this.stdout)
    writeText(`mcp-${this.name}.stderr`, this.stderr)
    writeJson(`mcp-${this.name}.exit.json`, { exit_code: exit.code, signal: exit.signal, pid: this.child.pid })
    if (exit.code !== 0) throw new Error(`MCP ${this.name} exited ${exit.code} signal ${exit.signal}`)
    if (shutdownError) throw shutdownError
  }

  async requestForClose() {
    this.closed = false
    try {
      const response = await this.request("shutdown", {}, "shutdown")
      assertEqual(response.result, null, "MCP shutdown result")
    } finally { this.closed = true }
  }
}

const options = parseArguments(process.argv.slice(2))
const root = process.cwd()
const packageVersion = JSON.parse(readFileSync(join(root, "package.json"), "utf8")).version
const jar = absolute(options.jar)
const jmeterHome = absolute(options.jmeterHome)
const fixture = absolute(options.fixture)
const evidenceDir = absolute(options.evidenceDir)
const configuredJavaHome = process.env.JAVA_HOME?.trim() || ""
const javaHome = configuredJavaHome ? resolve(root, configuredJavaHome) : ""
const javaEnv = {
  ...process.env,
  ...(javaHome ? {
    JAVA_HOME: javaHome,
    PATH: `${javaHome}/bin:${process.env.PATH || ""}`,
  } : {}),
  LC_ALL: "C.UTF-8",
}

mkdirSync(evidenceDir, { recursive: true })

const registry = {
  tempPaths: new Set(),
  forbiddenOutputs: new Set(),
  processes: new Map(),
  processHistory: [],
  timers: new Set(),
}
const scenarios = {}
const commandRecords = []
const readParityRecords = []
const fullReadParityRecords = []
let tempWork = ""
let primaryMcp = null
let fatalError = null
let cleanupComplete = false
let commandSequence = 0

const taskOwnedEvidencePrefix = resolve(root, ".omo/evidence/generic-jmeter-property-graph-editing/task-16/executor")
const dirtyBefore = dirtyWorktreeSnapshot()
writeJson("dirty-worktree-before.json", dirtyBefore)

for (const signal of ["SIGINT", "SIGTERM", "SIGHUP"]) {
  process.on(signal, () => {
    emergencyCleanup(signal)
    process.exit(128 + (signal === "SIGINT" ? 2 : signal === "SIGHUP" ? 1 : 15))
  })
}

try {
  validateInputs()
  writeBindings()

  tempWork = join(tmpdir(), `j4a-property-graph-qa-${process.pid}-${randomUUID()}`)
  registry.tempPaths.add(tempWork)
  mkdirSync(tempWork, { recursive: false })
  writeText("temp-workdir.registered.txt", `${tempWork}\n`)

  const tempInput = tempPath("input.jmx")
  copyFileSync(fixture, tempInput)
  const fixtureSha = sha256File(fixture)
  assertEqual(sha256File(tempInput), fixtureSha, "copied fixture hash")

  const initialRead = await cli("initial-read", [
    "-jar", jar, "read", tempInput, "--depth", "8", "--properties", "all",
    "--jmeter-home", jmeterHome,
  ])
  requireExit(initialRead, 0, "initial CLI read")
  const rootRef = extractRef(initialRead.stdout, "Property Graph Conformance", "org.apache.jmeter.control.gui.TestPlanGui")
  const httpRef = extractRef(initialRead.stdout, "Conformance HTTP Request", "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui")
  const originalAssertionRef = extractRef(initialRead.stdout, "Sanitized Response Assertion", "org.apache.jmeter.assertions.gui.AssertionGui")
  assertMatch(originalAssertionRef, /^jmx_[0-9a-f]+(?:_\d+)?$/, "deterministic Response Assertion ref")
  writeJson("deterministic-refs.json", { rootRef, httpRef, originalAssertionRef })
  pass("fixture_copy_and_deterministic_ref")

  const addPatch = addAssertionPatch(httpRef, ADDED_ASSERTION_NAME, ADD_ASSERTIONS)
  const addPatchFile = writePatch("01-add-response-assertion.yaml", addPatch)
  const addedFile = tempPath("added.jmx")
  const addSourceBefore = sha256File(tempInput)
  const addRun = await cli("add-response-assertion", [
    "-jar", jar, "apply", tempInput, "--patch", addPatchFile,
    "--out", addedFile, "--jmeter-home", jmeterHome,
  ])
  requireExit(addRun, 0, "CLI add")
  assertFile(addedFile, "CLI add output")
  assertEqual(sha256File(tempInput), addSourceBefore, "CLI add source hash")
  assertNotEqual(sha256File(addedFile), addSourceBefore, "CLI add target hash")
  const addReload = await cliRead("add-reload", addedFile)
  const addedAssertionRef = extractRef(addReload.stdout, ADDED_ASSERTION_NAME, "org.apache.jmeter.assertions.gui.AssertionGui")
  assertArrayEqual(extractAssertionDocumentFromYaml(addReload.stdout, ADDED_ASSERTION_NAME).strings, ADD_ASSERTIONS,
    "CLI add reload assertion order")
  pass("cli_add_reload_persistence")

  const setFile = tempPath("set.jmx")
  const setSourceBefore = sha256File(addedFile)
  const setRun = await cli("set-response-assertion", [
    "-jar", jar, "set", addedFile,
    "--locator", addedAssertionRef,
    "--property", JSON.stringify(ASSERTION_PROPERTY),
    "--type", "collection",
    "--value", JSON.stringify(collectionValue(SET_ASSERTIONS)),
    "--out", setFile,
    "--jmeter-home", jmeterHome,
  ])
  requireExit(setRun, 0, "CLI set")
  assertEqual(sha256File(addedFile), setSourceBefore, "CLI set source hash")
  assertNotEqual(sha256File(setFile), setSourceBefore, "CLI set target hash")
  const setReload = await cliRead("set-reload", setFile)
  const setAssertionRef = extractRef(setReload.stdout, ADDED_ASSERTION_NAME, "org.apache.jmeter.assertions.gui.AssertionGui")
  assertArrayEqual(extractAssertionDocumentFromYaml(setReload.stdout, ADDED_ASSERTION_NAME).strings, SET_ASSERTIONS,
    "CLI set reload assertion order")
  pass("cli_set_reload_persistence")

  const persistedPatch = setAssertionPatch(setAssertionRef, ACTUAL_PERSISTED_ASSERTIONS)
  const persistedPatchFile = writePatch("02-persist-response-assertion.yaml", persistedPatch)
  const cliFinal = tempPath("cli-final.jmx")
  const applySourceBefore = sha256File(setFile)
  const applyRun = await cli("apply-response-assertion", [
    "-jar", jar, "apply", setFile, "--patch", persistedPatchFile,
    "--out", cliFinal, "--jmeter-home", jmeterHome,
  ])
  requireExit(applyRun, 0, "CLI apply")
  assertEqual(sha256File(setFile), applySourceBefore, "CLI apply source hash")
  assertNotEqual(sha256File(cliFinal), applySourceBefore, "CLI apply target hash")
  const applyReload = await cliRead("apply-reload", cliFinal)
  const cliDocument = extractAssertionDocumentFromYaml(applyReload.stdout, ADDED_ASSERTION_NAME)
  const cliFinalAssertionRef = extractRef(applyReload.stdout, ADDED_ASSERTION_NAME, "org.apache.jmeter.assertions.gui.AssertionGui")
  assertArrayEqual(cliDocument.strings, EXPECTED_PERSISTED_ASSERTIONS,
    `semantic persisted assertion mismatch: expected ${JSON.stringify(EXPECTED_PERSISTED_ASSERTIONS)}`)
  pass("cli_apply_reload_persistence")
  writeJson("cli-persistence-hashes.json", {
    input: { before: addSourceBefore, after: sha256File(tempInput) },
    add: { source: addSourceBefore, target: sha256File(addedFile), values: ADD_ASSERTIONS },
    set: { source: setSourceBefore, target: sha256File(setFile), values: SET_ASSERTIONS },
    apply: { source: applySourceBefore, target: sha256File(cliFinal), values: EXPECTED_PERSISTED_ASSERTIONS },
  })

  const unknownOutput = forbiddenPath("unknown-path-output.jmx")
  const unknownInputBefore = sha256File(cliFinal)
  const unknownRun = await cli("unknown-path-failure", [
    "-jar", jar, "set", cliFinal,
    "--locator", cliFinalAssertionRef,
    "--property", '["qa.unknown"]',
    "--type", "collection",
    "--value", JSON.stringify(collectionValue(["must not persist"])),
    "--out", unknownOutput,
    "--jmeter-home", jmeterHome,
  ])
  assertNonzero(unknownRun.exitCode, "unknown path CLI exit")
  assertIncludes(unknownRun.stderr, "property", "unknown path diagnostic")
  assertEqual(sha256File(cliFinal), unknownInputBefore, "unknown path input hash")
  assertAbsent(unknownOutput, "unknown path output")
  pass("unknown_path_failure_preserves_bytes")

  const mismatchOutput = forbiddenPath("representation-mismatch-output.jmx")
  const mismatchBefore = sha256File(cliFinal)
  const mismatchRun = await cli("representation-mismatch-failure", [
    "-jar", jar, "set", cliFinal,
    "--locator", cliFinalAssertionRef,
    "--property", JSON.stringify(ASSERTION_PROPERTY),
    "--type", "string",
    "--value", "not-a-collection",
    "--out", mismatchOutput,
    "--jmeter-home", jmeterHome,
  ])
  assertNonzero(mismatchRun.exitCode, "representation mismatch CLI exit")
  assertIncludes(mismatchRun.stderr, "type", "representation mismatch diagnostic")
  assertEqual(sha256File(cliFinal), mismatchBefore, "representation mismatch input hash")
  assertAbsent(mismatchOutput, "representation mismatch output")
  pass("representation_mismatch_preserves_bytes")

  const interruptFixtureBefore = sha256File(fixture)
  await interruptedMcpProbe()
  assertEqual(sha256File(fixture), interruptFixtureBefore, "fixture after interrupted MCP")
  pass("interrupted_mcp_child_cleanup")

  primaryMcp = new McpSession("primary")
  await primaryMcp.start()
  await primaryMcp.initialize()
  const toolsList = await primaryMcp.request("tools/list", {}, "tools-list")
  const tools = toolsList.result?.tools || []
  assertArrayEqual(tools.map(tool => tool.name),
    ["read", "validate", "components", "categories", "apply", "init", "set"], "MCP exact tool order")
  const setSchema = tools.find(tool => tool.name === "set")?.inputSchema
  assertEqual(setSchema?.additionalProperties, false, "MCP set additionalProperties")
  assertArrayEqual(setSchema?.required, ["locator", "property", "value"], "MCP set required fields")
  assertIncludes(JSON.stringify(setSchema), "opaque", "MCP set opaque schema")
  assertIncludes(JSON.stringify(setSchema), "collection", "MCP set collection schema")
  pass("mcp_protocol_framing_and_tools_order")

  const malformed = await primaryMcp.raw("{not-json", "malformed-json")
  assertEqual(malformed.error?.code, -32700, "malformed JSON-RPC code")
  const malformedToolEnvelope = await primaryMcp.raw(JSON.stringify({
    jsonrpc: "2.0", id: 3, method: "tools/call", params: { arguments: {} },
  }), "malformed-tool-envelope")
  assertEqual(malformedToolEnvelope.error?.code, -32602, "malformed MCP tool envelope code")
  const invalidMissingArguments = await primaryMcp.raw(JSON.stringify({
    jsonrpc: "2.0", id: 4, method: "tools/call", params: { name: "read", arguments: {} },
  }), "missing-tool-arguments")
  requireToolUsageError(invalidMissingArguments, "MCP missing tool arguments")
  const invalidKnownToolArguments = await primaryMcp.raw(JSON.stringify({
    jsonrpc: "2.0", id: 5, method: "tools/call", params: {
      name: "read", arguments: { file: tempInput, properites: "all" },
    },
  }), "invalid-tool-arguments")
  requireToolUsageError(invalidKnownToolArguments, "MCP invalid tool arguments")
  primaryMcp.nextId = 6
  pass("mcp_malformed_and_invalid_recovery")

  const freshRead = await primaryMcp.call("read", {
    file: tempInput, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "fresh-after-interrupt")
  requireReadSuccess(freshRead, "fresh MCP process read after interruption", "Sanitized Response Assertion")
  pass("fresh_process_after_interruption")

  const mcpBaseData = readData(freshRead)
  const mcpHttpRef = findNodeByIdentity(mcpBaseData,
    "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui", "Conformance HTTP Request").ref
  const mcpAddPatch = addAssertionPatch(mcpHttpRef, ADDED_ASSERTION_NAME, ADD_ASSERTIONS)
  writePatch("03-mcp-add-response-assertion.yaml", mcpAddPatch)
  const mcpAddedFile = tempPath("mcp-added.jmx")
  const mcpBaseBefore = sha256File(tempInput)
  const mcpAdd = await primaryMcp.call("apply", {
    file: tempInput, patchYaml: mcpAddPatch, out: mcpAddedFile, jmeter_home: jmeterHome,
  }, "mcp-add")
  requireToolSuccess(mcpAdd, "MCP add")
  assertEqual(sha256File(tempInput), mcpBaseBefore, "MCP add source hash")
  const mcpAddReload = await primaryMcp.call("read", {
    file: mcpAddedFile, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "mcp-add-reload")
  requireReadSuccess(mcpAddReload, "MCP add reload", ADDED_ASSERTION_NAME)
  const mcpAddedNode = findNodeByIdentity(readData(mcpAddReload),
    "org.apache.jmeter.assertions.gui.AssertionGui", ADDED_ASSERTION_NAME)
  assertArrayEqual(assertionDocumentFromMcp(mcpAddReload, ADDED_ASSERTION_NAME).strings,
    ADD_ASSERTIONS, "MCP add reload assertion order")
  pass("mcp_add_reload_persistence")

  const mcpSetFile = tempPath("mcp-set.jmx")
  const mcpSet = await primaryMcp.call("set", {
    file: mcpAddedFile,
    locator: mcpAddedNode.ref,
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: collectionValue(SET_ASSERTIONS),
    out: mcpSetFile,
    jmeter_home: jmeterHome,
  }, "mcp-set")
  requireToolSuccess(mcpSet, "MCP set")
  const mcpSetReload = await primaryMcp.call("read", {
    file: mcpSetFile, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "mcp-set-reload")
  requireReadSuccess(mcpSetReload, "MCP set reload", ADDED_ASSERTION_NAME)
  const mcpSetNode = findNodeByIdentity(readData(mcpSetReload),
    "org.apache.jmeter.assertions.gui.AssertionGui", ADDED_ASSERTION_NAME)
  assertArrayEqual(assertionDocumentFromMcp(mcpSetReload, ADDED_ASSERTION_NAME).strings,
    SET_ASSERTIONS, "MCP set reload assertion order")
  pass("mcp_set_reload_persistence")

  const mcpApplyFile = tempPath("mcp-apply.jmx")
  const mcpApplyPatch = setAssertionPatch(mcpSetNode.ref, ACTUAL_PERSISTED_ASSERTIONS)
  writePatch("04-mcp-apply-response-assertion.yaml", mcpApplyPatch)
  const mcpApply = await primaryMcp.call("apply", {
    file: mcpSetFile,
    patchYaml: mcpApplyPatch,
    out: mcpApplyFile,
    jmeter_home: jmeterHome,
  }, "mcp-apply")
  requireToolSuccess(mcpApply, "MCP apply")
  const mcpApplyReload = await primaryMcp.call("read", {
    file: mcpApplyFile, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "mcp-apply-reload")
  requireReadSuccess(mcpApplyReload, "MCP apply reload", ADDED_ASSERTION_NAME)
  const mcpApplyNode = findNodeByIdentity(readData(mcpApplyReload),
    "org.apache.jmeter.assertions.gui.AssertionGui", ADDED_ASSERTION_NAME)
  const mcpReadDocument = assertionDocumentFromMcp(mcpApplyReload, ADDED_ASSERTION_NAME)
  assertArrayEqual(mcpReadDocument.strings, EXPECTED_PERSISTED_ASSERTIONS, "MCP apply reload assertion order")
  assertDeepEqual(mcpReadDocument.normalized, cliDocument.normalized,
    "CLI/MCP normalized Response Assertion document")
  writeJson("normalized-cli-mcp.json", {
    cli: cliDocument.normalized,
    mcp: mcpReadDocument.normalized,
    equal: true,
  })
  pass("cli_mcp_normalized_data_agree")
  pass("mcp_apply_reload_persistence")

  const opaquePatch = addOpaqueListenerPatch(rootRef)
  const opaquePatchFile = writePatch("05-add-opaque-listener.yaml", opaquePatch)
  const opaqueInput = tempPath("opaque-input.jmx")
  const opaqueAdd = await cli("add-opaque-listener", [
    "-jar", jar, "apply", tempInput, "--patch", opaquePatchFile,
    "--out", opaqueInput, "--jmeter-home", jmeterHome,
  ])
  requireExit(opaqueAdd, 0, "add opaque listener")
  const cliOpaqueRead = await cliRead("opaque-read", opaqueInput)
  const cliOpaqueRef = extractRef(cliOpaqueRead.stdout, OPAQUE_LISTENER_NAME,
    "org.apache.jmeter.visualizers.AssertionVisualizer")
  const opaqueRead = await primaryMcp.call("read", {
    file: opaqueInput, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "opaque-read")
  requireReadDataEquality(opaqueRead, "MCP opaque read")
  const opaqueNode = findNodeByName(readData(opaqueRead), OPAQUE_LISTENER_NAME)
  const opaqueRecord = findProperty(opaqueNode, ["saveConfig"])
  assertEqual(opaqueRecord.type, "opaque", "opaque property representation")

  const incompatibleValue = structuredClone(opaqueRecord.value)
  incompatibleValue.payload = incompatibleValue.payload.replace(
    /class="[^"]+"/, 'class="java.lang.ProcessBuilder"')
  assertNotEqual(incompatibleValue.payload, opaqueRecord.value.payload, "incompatible opaque type mutation")
  const cliIncompatibleOutput = forbiddenPath("cli-incompatible-opaque-output.jmx")
  const incompatibleInputBefore = sha256File(opaqueInput)
  const cliIncompatible = await cli("incompatible-opaque-failure", [
    "-jar", jar, "set", opaqueInput,
    "--locator", cliOpaqueRef,
    "--property", JSON.stringify(opaqueRecord.property),
    "--type", "opaque",
    "--value", JSON.stringify(incompatibleValue),
    "--out", cliIncompatibleOutput,
    "--jmeter-home", jmeterHome,
  ])
  assertNonzero(cliIncompatible.exitCode, "incompatible opaque CLI exit")
  assertIncludes(cliIncompatible.stderr.toLowerCase(), "expands serialized classes",
    "CLI incompatible opaque diagnostic")
  assertEqual(sha256File(opaqueInput), incompatibleInputBefore, "CLI incompatible opaque input hash")
  assertAbsent(cliIncompatibleOutput, "CLI incompatible opaque output")

  const mcpIncompatibleOutput = forbiddenPath("mcp-incompatible-opaque-output.jmx")
  const mcpIncompatible = await primaryMcp.call("set", {
    file: opaqueInput, locator: opaqueNode.ref, property: opaqueRecord.property, type: "opaque",
    value: incompatibleValue, out: mcpIncompatibleOutput, jmeter_home: jmeterHome,
  }, "incompatible-opaque")
  requireToolError(mcpIncompatible, "incompatible opaque MCP set")
  assertIncludes(toolText(mcpIncompatible).toLowerCase(), "expands serialized classes",
    "MCP incompatible opaque diagnostic")
  assertEqual(sha256File(opaqueInput), incompatibleInputBefore, "MCP incompatible opaque input hash")
  assertAbsent(mcpIncompatibleOutput, "MCP incompatible opaque output")

  const malformedValue = structuredClone(opaqueRecord.value)
  malformedValue.payload = "<objectProp"
  const cliMalformedOutput = forbiddenPath("cli-malformed-opaque-output.jmx")
  const cliMalformed = await cli("malformed-opaque-failure", [
    "-jar", jar, "set", opaqueInput,
    "--locator", cliOpaqueRef,
    "--property", JSON.stringify(opaqueRecord.property),
    "--type", "opaque",
    "--value", JSON.stringify(malformedValue),
    "--out", cliMalformedOutput,
    "--jmeter-home", jmeterHome,
  ])
  assertNonzero(cliMalformed.exitCode, "malformed opaque CLI exit")
  assertIncludes(cliMalformed.stderr.toLowerCase(), "not safe xml", "CLI malformed opaque diagnostic")
  assertEqual(sha256File(opaqueInput), incompatibleInputBefore, "CLI malformed opaque input hash")
  assertAbsent(cliMalformedOutput, "CLI malformed opaque output")

  const mcpMalformedOutput = forbiddenPath("mcp-malformed-opaque-output.jmx")
  const mcpMalformed = await primaryMcp.call("set", {
    file: opaqueInput, locator: opaqueNode.ref, property: opaqueRecord.property, type: "opaque",
    value: malformedValue, out: mcpMalformedOutput, jmeter_home: jmeterHome,
  }, "malformed-opaque")
  requireToolError(mcpMalformed, "malformed opaque MCP set")
  assertIncludes(toolText(mcpMalformed).toLowerCase(), "not safe xml", "MCP malformed opaque diagnostic")
  assertEqual(sha256File(opaqueInput), incompatibleInputBefore, "MCP malformed opaque input hash")
  assertAbsent(mcpMalformedOutput, "MCP malformed opaque output")

  const staleValue = structuredClone(opaqueRecord.value)
  staleValue.base_digest = "0".repeat(64)
  const cliStaleOutput = forbiddenPath("cli-stale-opaque-output.jmx")
  const cliStaleBefore = sha256File(opaqueInput)
  const cliStale = await cli("stale-opaque-failure", [
    "-jar", jar, "set", opaqueInput,
    "--locator", cliOpaqueRef,
    "--property", JSON.stringify(opaqueRecord.property),
    "--type", "opaque",
    "--value", JSON.stringify(staleValue),
    "--out", cliStaleOutput,
    "--jmeter-home", jmeterHome,
  ])
  assertNonzero(cliStale.exitCode, "stale opaque CLI exit")
  assertIncludes(cliStale.stderr.toLowerCase(), "opaque base digest is stale", "CLI stale opaque diagnostic")
  assertEqual(sha256File(opaqueInput), cliStaleBefore, "CLI stale opaque input hash")
  assertAbsent(cliStaleOutput, "CLI stale opaque output")

  const staleOutput = forbiddenPath("stale-opaque-output.jmx")
  const staleInputBefore = sha256File(opaqueInput)
  const stale = await primaryMcp.call("set", {
    file: opaqueInput,
    locator: opaqueNode.ref,
    ["property"]: opaqueRecord.property,
    type: "opaque",
    value: staleValue,
    out: staleOutput,
    jmeter_home: jmeterHome,
  }, "stale-opaque")
  requireToolError(stale, "stale opaque MCP set")
  assertIncludes(toolText(stale).toLowerCase(), "opaque base digest is stale", "stale opaque diagnostic")
  assertEqual(sha256File(opaqueInput), staleInputBefore, "stale opaque input hash")
  assertAbsent(staleOutput, "stale opaque output")
  pass("stale_opaque_failure_preserves_bytes")

  const mcpUnknownOutput = forbiddenPath("mcp-unknown-path-output.jmx")
  const mcpFailureInputBefore = sha256File(mcpApplyFile)
  const mcpUnknown = await primaryMcp.call("set", {
    file: mcpApplyFile, locator: mcpApplyNode.ref, property: ["qa.missing"], type: "collection",
    value: collectionValue(["must not persist"]), out: mcpUnknownOutput, jmeter_home: jmeterHome,
  }, "mcp-unknown-path")
  requireToolError(mcpUnknown, "MCP unknown path")
  assertEqual(sha256File(mcpApplyFile), mcpFailureInputBefore, "MCP unknown path input hash")
  assertAbsent(mcpUnknownOutput, "MCP unknown path output")

  const mcpMismatchOutput = forbiddenPath("mcp-representation-mismatch-output.jmx")
  const mcpMismatch = await primaryMcp.call("set", {
    file: mcpApplyFile, locator: mcpApplyNode.ref, property: ASSERTION_PROPERTY, type: "string",
    value: "not-a-collection", out: mcpMismatchOutput, jmeter_home: jmeterHome,
  }, "mcp-representation-mismatch")
  requireToolError(mcpMismatch, "MCP representation mismatch")
  assertEqual(sha256File(mcpApplyFile), mcpFailureInputBefore, "MCP representation mismatch input hash")
  assertAbsent(mcpMismatchOutput, "MCP representation mismatch output")
  pass("mcp_failure_matrix_preserves_bytes")

  const recovery = await primaryMcp.call("read", {
    file: opaqueInput, ref: opaqueNode.ref, properties: "all", jmeter_home: jmeterHome,
  }, "same-session-recovery")
  requireReadDataEquality(recovery, "same-session MCP recovery")
  assertEqual(findProperty(focusNode(readData(recovery)), ["saveConfig"]).type, "opaque",
    "same-session recovery opaque read")
  pass("same_session_mcp_recovery")

  const cliDryPatch = setAssertionPatch(cliFinalAssertionRef, ["dry run must not persist"])
  const cliDryPatchFile = writePatch("06-cli-dry-run.yaml", cliDryPatch)
  const cliIgnoredOutput = forbiddenPath("cli-ignored-output.jmx")
  const mcpIgnoredOutput = forbiddenPath("mcp-ignored-output.jmx")
  const dryInputBefore = sha256File(cliFinal)
  const cliDry = await cli("cli-exact-dry-run", [
    "-jar", jar, "apply", cliFinal,
    "--patch", cliDryPatchFile,
    "--dry-run",
    "--out", cliIgnoredOutput,
    "--jmeter-home", jmeterHome,
  ])
  requireExit(cliDry, 0, "exact CLI dry-run")
  assertIncludes(cliDry.stdout, "Patch applicable:", "CLI dry-run applicable state")
  assertIncludes(cliDry.stdout, "No JMX changes were written.", "CLI dry-run no-write state")
  assertEqual(sha256File(cliFinal), dryInputBefore, "CLI dry-run input hash")
  assertAbsent(cliIgnoredOutput, "CLI ignored dry-run output")
  const cliDryReload = await cliRead("cli-dry-run-reload", cliFinal)
  assertArrayEqual(extractAssertionDocumentFromYaml(cliDryReload.stdout, ADDED_ASSERTION_NAME).strings,
    EXPECTED_PERSISTED_ASSERTIONS, "CLI dry-run reload unchanged")
  pass("cli_exact_dry_run")

  const mcpDryInput = await primaryMcp.call("read", {
    file: cliFinal, depth: 8, properties: "all", jmeter_home: jmeterHome,
  }, "mcp-dry-run-input-read")
  requireReadSuccess(mcpDryInput, "MCP dry-run input", ADDED_ASSERTION_NAME)
  const mcpCliFinalAssertionRef = findNodeByIdentity(readData(mcpDryInput),
    "org.apache.jmeter.assertions.gui.AssertionGui", ADDED_ASSERTION_NAME).ref
  const mcpDryPatch = setAssertionPatch(mcpCliFinalAssertionRef, ["dry run must not persist"])
  const mcpDry = await primaryMcp.call("apply", {
    file: cliFinal,
    patchYaml: mcpDryPatch,
    dryRun: true,
    out: mcpIgnoredOutput,
    jmeter_home: jmeterHome,
  }, "mcp-exact-dry-run")
  requireToolSuccess(mcpDry, "exact MCP dry-run")
  assertEqual(readStructured(mcpDry).data.dryRun, true, "MCP dry-run state")
  assertEqual(sha256File(cliFinal), dryInputBefore, "MCP dry-run input hash")
  assertAbsent(mcpIgnoredOutput, "MCP ignored dry-run output")
  const mcpDryReload = await primaryMcp.call("read", {
    file: cliFinal, ref: mcpCliFinalAssertionRef, properties: "all", jmeter_home: jmeterHome,
  }, "mcp-dry-run-reload")
  requireReadSuccess(mcpDryReload, "MCP dry-run reload", ADDED_ASSERTION_NAME)
  assertArrayEqual(assertionDocumentFromMcp(mcpDryReload, ADDED_ASSERTION_NAME).strings,
    EXPECTED_PERSISTED_ASSERTIONS, "MCP dry-run reload unchanged")
  pass("mcp_exact_dry_run")

  writeJson("failure-hashes.json", {
    cli_unknown_path: { before: unknownInputBefore, after: sha256File(cliFinal), output_exists: existsSync(unknownOutput) },
    cli_representation_mismatch: { before: mismatchBefore, after: sha256File(cliFinal), output_exists: existsSync(mismatchOutput) },
    cli_stale_opaque: { before: cliStaleBefore, after: sha256File(opaqueInput), output_exists: existsSync(cliStaleOutput) },
    cli_incompatible_opaque: { before: incompatibleInputBefore, after: sha256File(opaqueInput), output_exists: existsSync(cliIncompatibleOutput) },
    cli_malformed_opaque: { before: incompatibleInputBefore, after: sha256File(opaqueInput), output_exists: existsSync(cliMalformedOutput) },
    mcp_unknown_path: { before: mcpFailureInputBefore, after: sha256File(mcpApplyFile), output_exists: existsSync(mcpUnknownOutput) },
    mcp_representation_mismatch: { before: mcpFailureInputBefore, after: sha256File(mcpApplyFile), output_exists: existsSync(mcpMismatchOutput) },
    mcp_stale_opaque: { before: staleInputBefore, after: sha256File(opaqueInput), output_exists: existsSync(staleOutput) },
    mcp_incompatible_opaque: { before: incompatibleInputBefore, after: sha256File(opaqueInput), output_exists: existsSync(mcpIncompatibleOutput) },
    mcp_malformed_opaque: { before: incompatibleInputBefore, after: sha256File(opaqueInput), output_exists: existsSync(mcpMalformedOutput) },
  })
  writeJson("dry-run-observables.json", {
    input: cliFinal,
    cli_patch_sha256: sha256Text(cliDryPatch),
    mcp_patch_sha256: sha256Text(mcpDryPatch),
    input_sha256_before: dryInputBefore,
    input_sha256_after: sha256File(cliFinal),
    cli: { exit: cliDry.exitCode, dryRun: true, ignored_output_exists: existsSync(cliIgnoredOutput) },
    mcp: { isError: mcpDry.result.isError, dryRun: readStructured(mcpDry).data.dryRun,
      ignored_output_exists: existsSync(mcpIgnoredOutput) },
    reload_assertions: EXPECTED_PERSISTED_ASSERTIONS,
  })
  pass("all_failures_and_dry_runs_byte_safe")
  pass("stale_state_hash_and_disk_reload_binding")

  await primaryMcp.close()
  primaryMcp = null
} catch (error) {
  fatalError = error
  const message = error instanceof Error ? `${error.name}: ${error.message}\n${error.stack || ""}` : String(error)
  writeText("failure.txt", `${message}\n`)
  process.stderr.write(`QA assertion failure: ${error instanceof Error ? error.message : String(error)}\n`)
} finally {
  if (primaryMcp) {
    try { await primaryMcp.close() } catch (error) {
      fatalError ||= error
    }
    primaryMcp = null
  }
  await cleanup()
}

try {
  const dirtyAfter = dirtyWorktreeSnapshot()
  writeJson("dirty-worktree-after.json", dirtyAfter)
  if (JSON.stringify(dirtyBefore) !== JSON.stringify(dirtyAfter)) {
    throw new Error("unrelated dirty worktree paths or hashes changed during QA")
  }
  pass("dirty_worktree_preserved")
  pass("cleanup_no_residue")
} catch (error) {
  fatalError ||= error
  process.stderr.write(`QA cleanup assertion failure: ${error.message}\n`)
}

const result = {
  status: fatalError ? "FAIL" : "PASS",
  scenarios,
  observables: {
    cli_commands: commandRecords.length,
    fixture_sha256: existsSync(fixture) ? sha256File(fixture) : null,
    jar_sha256: existsSync(jar) ? sha256File(jar) : null,
    script_sha256: sha256File(new URL(import.meta.url)),
    cleanup_complete: cleanupComplete,
  },
}
writeJson("result.json", result)
writeJson("harness-artifacts-manifest.json", evidenceManifest())

if (fatalError) process.exit(1)
process.stdout.write(`Property graph QA PASS: ${Object.keys(scenarios).length} scenarios\n`)

function parseArguments(args) {
  const result = {}
  const names = new Map([
    ["--jar", "jar"],
    ["--jmeter-home", "jmeterHome"],
    ["--fixture", "fixture"],
    ["--evidence-dir", "evidenceDir"],
  ])
  for (let index = 0; index < args.length; index += 2) {
    const key = names.get(args[index])
    if (!key || index + 1 >= args.length) usage()
    result[key] = args[index + 1]
  }
  if (Object.keys(result).length !== names.size) usage()
  return result
}

function usage() {
  process.stderr.write("Usage: node scripts/qa-property-graph.mjs --jar <jar> --jmeter-home <home> --fixture <jmx> --evidence-dir <dir>\n")
  process.exit(2)
}

function absolute(path) {
  return isAbsolute(path) ? resolve(path) : resolve(root, path)
}

function validateInputs() {
  assertFile(jar, "packaged shadow jar")
  assertFile(fixture, "sanitized fixture")
  for (const required of ["bin", "lib", "lib/ext", "bin/jmeter.properties", "bin/saveservice.properties", "bin/upgrade.properties"]) {
    if (!existsSync(join(jmeterHome, required))) throw new Error(`invalid JMeter home, missing ${required}`)
  }
  if (javaHome) {
    assertFile(join(javaHome, "bin/java"), "configured JAVA_HOME java")
  } else if (!(process.env.PATH || "").split(delimiter).some(directory => existsSync(join(directory, "java")))) {
    throw new Error("java executable not found on PATH")
  }
}

function writeBindings() {
  const scriptPath = new URL(import.meta.url)
  writeJson("bindings.json", {
    node: process.version,
    java_home: javaHome,
    jar: { path: jar, sha256: sha256File(jar) },
    fixture: { path: fixture, sha256: sha256File(fixture) },
    script: { path: scriptPath.pathname, sha256: sha256File(scriptPath) },
    jmeter_home: { path: jmeterHome, inventory_sha256: directoryBinding(jmeterHome) },
    expected_persisted_assertions: EXPECTED_PERSISTED_ASSERTIONS,
  })
}

function directoryBinding(path) {
  const names = ["bin/jmeter.properties", "bin/saveservice.properties", "bin/upgrade.properties"]
  const records = names.map(name => `${name}\0${sha256File(join(path, name))}`)
  for (const folder of ["lib", "lib/ext"]) {
    for (const name of readdirSync(join(path, folder)).filter(name => name.endsWith(".jar")).sort()) {
      records.push(`${folder}/${name}\0${sha256File(join(path, folder, name))}`)
    }
  }
  return sha256Text(records.join("\n"))
}

function tempPath(name) {
  const path = join(tempWork, name)
  registry.tempPaths.add(path)
  return path
}

function forbiddenPath(name) {
  const path = tempPath(name)
  registry.forbiddenOutputs.add(path)
  return path
}

function writePatch(name, content) {
  const temp = tempPath(name)
  writeFileSync(temp, content, "utf8")
  copyFileSync(temp, join(evidenceDir, name))
  writeJson(`${name}.binding.json`, { temp_path: temp, sha256: sha256File(temp), bytes: statSync(temp).size })
  return temp
}

async function cliRead(name, file) {
  const result = await cli(name, [
    "-jar", jar, "read", file, "--depth", "8", "--properties", "all",
    "--jmeter-home", jmeterHome,
  ])
  requireExit(result, 0, name)
  return result
}

async function cli(name, args) {
  commandSequence += 1
  const prefix = `${String(commandSequence).padStart(2, "0")}-${name}`
  const record = await runProcess("java", args, prefix, PROCESS_TIMEOUT_MS)
  commandRecords.push({ name, prefix, command: ["java", ...args], exitCode: record.exitCode })
  return record
}

async function runProcess(command, args, evidencePrefix, timeoutMs) {
  const commandRecord = { command, args, cwd: root, timeout_ms: timeoutMs, java_home: javaHome }
  writeJson(`${evidencePrefix}.command.json`, commandRecord)
  const child = spawn(command, args, { cwd: root, env: javaEnv, detached: true, stdio: ["ignore", "pipe", "pipe"] })
  registerProcess(child.pid, { name: evidencePrefix, pgid: child.pid, command: [command, ...args] })
  let stdout = ""
  let stderr = ""
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", chunk => { stdout += chunk })
  child.stderr.on("data", chunk => { stderr += chunk })
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    killProcessGroup(child.pid)
  }, timeoutMs)
  registry.timers.add(timer)
  const exit = await new Promise((resolveExit, reject) => {
    child.once("error", reject)
    child.once("close", (code, signal) => resolveExit({ code, signal }))
  })
  clearTimeout(timer)
  registry.timers.delete(timer)
  unregisterProcess(child.pid, { code: exit.code, signal: exit.signal })
  const exitCode = exit.code ?? (timedOut ? 124 : 128)
  writeText(`${evidencePrefix}.stdout`, stdout)
  writeText(`${evidencePrefix}.stderr`, stderr)
  writeJson(`${evidencePrefix}.exit.json`, { exit_code: exitCode, signal: exit.signal, timed_out: timedOut, pid: child.pid })
  if (timedOut || exitCode === 124) throw new Error(`${evidencePrefix} timed out; exit 124 is forbidden`)
  return { stdout, stderr, exitCode, signal: exit.signal, pid: child.pid }
}

async function interruptedMcpProbe() {
  const name = "interrupted"
  writeJson(`mcp-${name}.command.json`, {
    command: "java", args: ["-cp", jar, MCP_MAIN], cwd: root, action: "terminate immediately after startup",
  })
  const child = spawn("java", ["-cp", jar, MCP_MAIN], {
    cwd: root, env: javaEnv, detached: true, stdio: ["pipe", "pipe", "pipe"],
  })
  registerProcess(child.pid, { name: `mcp-${name}`, pgid: child.pid, command: ["java", "-cp", jar, MCP_MAIN] })
  let stdout = ""
  let stderr = ""
  child.stdout.setEncoding("utf8")
  child.stderr.setEncoding("utf8")
  child.stdout.on("data", chunk => { stdout += chunk })
  child.stderr.on("data", chunk => { stderr += chunk })
  const exitPromise = new Promise((resolveExit, reject) => {
    child.once("error", reject)
    child.once("close", (code, signal) => resolveExit({ code, signal }))
  })
  const request = { jsonrpc: "2.0", id: 1, method: "initialize", params: {
    protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "j4a-interrupt-probe", version: "1" },
  } }
  writeText(`mcp-${name}.requests.jsonl`, `${JSON.stringify(request)}\n`)
  child.stdin.write(`${JSON.stringify(request)}\n`, "utf8")
  killProcessGroup(child.pid, "SIGTERM")
  const timer = setTimeout(() => killProcessGroup(child.pid, "SIGKILL"), 5_000)
  registry.timers.add(timer)
  const exit = await exitPromise
  clearTimeout(timer)
  registry.timers.delete(timer)
  unregisterProcess(child.pid, { code: exit.code, signal: exit.signal })
  writeText(`mcp-${name}.stdout.jsonl`, stdout)
  writeText(`mcp-${name}.stderr`, stderr)
  writeJson(`mcp-${name}.exit.json`, { exit_code: exit.code, signal: exit.signal, pid: child.pid, intentionally_interrupted: true })
  if (pidAlive(child.pid)) throw new Error("interrupted MCP child remained alive")
}

function addAssertionPatch(parent, name, strings) {
  return `changes:\n  - add:\n      parent: ${parent}\n      position: last\n      component: org.apache.jmeter.assertions.gui.AssertionGui\n      properties:\n        - property: [TestElement.name]\n          value: ${name}\n          type: string\n${propertyYaml(strings, 8)}`
}

function setAssertionPatch(ref, strings) {
  return `changes:\n  - set:\n      ref: ${ref}\n      properties:\n${propertyYaml(strings, 8)}`
}

function propertyYaml(strings, indent) {
  const space = " ".repeat(indent)
  const itemSpace = " ".repeat(indent + 8)
  const items = strings.map(value => `${itemSpace}- type: string\n${itemSpace}  presence: present\n${itemSpace}  property_class: org.apache.jmeter.testelement.property.StringProperty\n${itemSpace}  value: ${value}\n`).join("")
  return `${space}- property: ${ASSERTION_PROPERTY_YAML}\n${space}  type: collection\n${space}  value:\n${space}    presence: present\n${space}    property_class: org.apache.jmeter.testelement.property.CollectionProperty\n${space}    items:\n${items}`
}

function addOpaqueListenerPatch(parent) {
  return `changes:\n  - add:\n      parent: ${parent}\n      position: last\n      component: org.apache.jmeter.visualizers.AssertionVisualizer\n      properties:\n        - property: [TestElement.name]\n          value: ${OPAQUE_LISTENER_NAME}\n          type: string\n`
}

function collectionValue(strings) {
  return {
    presence: "present",
    property_class: "org.apache.jmeter.testelement.property.CollectionProperty",
    items: strings.map(value => ({
      type: "string",
      presence: "present",
      property_class: "org.apache.jmeter.testelement.property.StringProperty",
      value,
    })),
  }
}

function extractRef(yaml, name, component) {
  const lines = yaml.split(/\r?\n/)
  for (let index = 0; index < lines.length; index++) {
    const match = lines[index].match(/^(\s*)name:\s*(.*)$/)
    if (!match || parseYamlScalar(match[2]) !== name) continue
    const nameIndent = match[1].length
    for (let cursor = index - 1; cursor >= 0; cursor--) {
      const ref = lines[cursor].match(/^(\s*)-?\s*ref:\s*(\S+)\s*$/)
      if (ref && ref[1].length <= nameIndent) {
        const block = lines.slice(cursor, index + 1).join("\n")
        if (!component || block.includes(`component: ${component}`)) return ref[2]
        break
      }
    }
  }
  throw new Error(`missing ref for node ${name}`)
}

function extractAssertionDocumentFromYaml(yaml, nodeName) {
  const lines = yaml.split(/\r?\n/)
  const nodeLine = lines.findIndex(line => line.trim() === `name: ${nodeName}`)
  if (nodeLine < 0) throw new Error(`missing YAML node ${nodeName}`)
  const propertyLine = lines.findIndex((line, index) => index > nodeLine && (
    line.trim() === `- property: ${ASSERTION_PROPERTY_YAML}`
      || (line.trim() === "- property:" && lines[index + 1]?.trim() === "- Asserion.test_strings")))
  if (propertyLine < 0) throw new Error(`missing assertion property under ${nodeName}`)
  const propertyIndent = lines[propertyLine].match(/^\s*/)[0].length
  let endLine = lines.length
  for (let index = propertyLine + 1; index < lines.length; index++) {
    if (lines[index].match(/^\s*/)[0].length === propertyIndent && lines[index].trim().startsWith("- property:")) {
      endLine = index
      break
    }
  }
  const blockLines = lines.slice(propertyLine, endLine)
  const block = blockLines.join("\n")
  const strings = []
  for (const line of blockLines) {
    const value = line.match(new RegExp(`^\\s{${propertyIndent + 6}}value:\\s*(.*)$`))
    if (value) strings.push(parseYamlScalar(value[1]))
  }
  const normalized = {
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: collectionValue(strings),
  }
  return { strings, normalized, block }
}

function parseYamlScalar(value) {
  const text = value.trim()
  if (text.startsWith("'") && text.endsWith("'")) return text.slice(1, -1).replace(/''/g, "'")
  if (text.startsWith('"') && text.endsWith('"')) return JSON.parse(text)
  if (text === "true") return true
  if (text === "false") return false
  if (text === "null" || text === "~") return null
  if (/^-?(?:0|[1-9]\d*)$/.test(text)) return Number(text)
  if (text === "[]") return []
  if (text === "{}") return {}
  return text
}

function parseYamlDocument(source) {
  const rawLines = source.replace(/\r/g, "").split("\n")
  const lines = []
  for (let index = 0; index < rawLines.length; index++) {
    const line = rawLines[index]
    if (/^\s*[^#][^:]*:\s*\[\s*$/.test(line) && rawLines[index + 1]?.trim() === "]") {
      lines.push(line.replace(/\[\s*$/, "[]"))
      index += 1
    } else {
      lines.push(line)
    }
  }

  const indentation = line => line.match(/^\s*/)[0].length
  const nextContent = start => {
    let index = start
    while (index < lines.length && lines[index].trim() === "") index += 1
    return index
  }
  const splitEntry = text => {
    const colon = text.indexOf(":")
    if (colon < 0) throw new Error(`unsupported YAML line: ${text}`)
    return [text.slice(0, colon).trim(), text.slice(colon + 1).trim()]
  }

  const scalarOrBlock = (text, lineIndex, parentIndent) => {
    if (text !== "|-" && text !== "|" && text !== ">-" && text !== ">") {
      return [parseYamlScalar(text), lineIndex]
    }
    let cursor = lineIndex
    const values = []
    let contentIndent = null
    while (cursor < lines.length) {
      const line = lines[cursor]
      if (line.trim() === "") {
        values.push("")
        cursor += 1
        continue
      }
      const indent = indentation(line)
      if (indent <= parentIndent) break
      contentIndent ??= indent
      values.push(line.slice(Math.min(contentIndent, line.length)))
      cursor += 1
    }
    const folded = text.startsWith(">") ? values.join(" ") : values.join("\n")
    return [text.endsWith("-") ? folded : `${folded}\n`, cursor]
  }

  const parseBlock = (start, expectedIndent) => {
    let index = nextContent(start)
    if (index >= lines.length) return [null, index]
    const sequence = indentation(lines[index]) === expectedIndent && lines[index].trim().startsWith("-")
    const result = sequence ? [] : {}
    while (index < lines.length) {
      index = nextContent(index)
      if (index >= lines.length) break
      const line = lines[index]
      const indent = indentation(line)
      if (indent < expectedIndent) break
      if (indent > expectedIndent) throw new Error(`unexpected YAML indentation at line ${index + 1}`)
      const trimmed = line.trim()
      if (sequence) {
        if (!trimmed.startsWith("-")) break
        const itemText = trimmed.slice(1).trim()
        if (itemText === "") {
          const childIndex = nextContent(index + 1)
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          result.push(child)
          index = next
          continue
        }
        if (!itemText.includes(":")) {
          result.push(parseYamlScalar(itemText))
          index += 1
          continue
        }
        const object = {}
        const [key, valueText] = splitEntry(itemText)
        index += 1
        if (valueText === "") {
          const childIndex = nextContent(index)
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          object[key] = child
          index = next
        } else {
          const [value, next] = scalarOrBlock(valueText, index, expectedIndent)
          object[key] = value
          index = next
        }
        while (index < lines.length) {
          index = nextContent(index)
          if (index >= lines.length || indentation(lines[index]) !== expectedIndent + 2 || lines[index].trim().startsWith("-")) break
          const [nestedKey, nestedText] = splitEntry(lines[index].trim())
          index += 1
          if (nestedText === "") {
            const childIndex = nextContent(index)
            const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
            object[nestedKey] = child
            index = next
          } else {
            const [value, next] = scalarOrBlock(nestedText, index, expectedIndent + 2)
            object[nestedKey] = value
            index = next
          }
        }
        result.push(object)
        continue
      }
      if (trimmed.startsWith("-")) break
      const [key, valueText] = splitEntry(trimmed)
      index += 1
      if (valueText === "") {
        const childIndex = nextContent(index)
        if (childIndex >= lines.length || indentation(lines[childIndex]) < expectedIndent) {
          result[key] = null
        } else {
          const [child, next] = parseBlock(childIndex, indentation(lines[childIndex]))
          result[key] = child
          index = next
        }
      } else {
        const [value, next] = scalarOrBlock(valueText, index, expectedIndent)
        result[key] = value
        index = next
      }
    }
    return [result, index]
  }

  const first = nextContent(0)
  if (first >= lines.length) return null
  return parseBlock(first, indentation(lines[first]))[0]
}

function readData(response) {
  const structured = readStructured(response)
  if (!structured.data || typeof structured.data !== "object") throw new Error("MCP structuredContent.data missing")
  return structured.data
}

function readStructured(response) {
  const result = response.result
  if (!result || typeof result !== "object" || !result.structuredContent) {
    throw new Error("MCP structuredContent missing")
  }
  return result.structuredContent
}

function toolText(response) {
  return (response.result?.content || []).map(item => item.text || "").join("\n")
}

function focusNode(data) {
  return data.focus || data.root
}

function findNodeByName(data, name) {
  const rootNode = data.root || data.focus
  const visit = node => {
    if (!node || typeof node !== "object") return null
    if (node.name === name) return node
    for (const child of node.children || []) {
      const found = visit(child)
      if (found) return found
    }
    return null
  }
  const found = visit(rootNode)
  if (!found) throw new Error(`MCP data missing node ${name}`)
  return found
}

function findNodeByIdentity(data, component, name) {
  const rootNode = data.root || data.focus
  const visit = node => {
    if (!node || typeof node !== "object") return null
    if (node.component === component && node.name === name) return node
    for (const child of node.children || []) {
      const found = visit(child)
      if (found) return found
    }
    return null
  }
  const found = visit(rootNode)
  if (!found) throw new Error(`MCP data missing ${component} named ${name}`)
  return found
}

function findProperty(node, property) {
  const found = (node.properties || []).find(candidate => JSON.stringify(candidate.property) === JSON.stringify(property))
  if (!found) throw new Error(`MCP data missing property ${JSON.stringify(property)}`)
  return found
}

function assertionDocumentFromMcp(response, nodeName) {
  const data = readData(response)
  const node = data.focus?.name === nodeName ? data.focus : findNodeByName(data, nodeName)
  const record = findProperty(node, ASSERTION_PROPERTY)
  const strings = (record.value?.items || []).map(item => item.value)
  return { strings, normalized: record }
}

function requireToolSuccess(response, label) {
  assertEqual(response.result?.isError, false, `${label} isError`)
  assertEqual(readStructured(response).exitStatus, 0, `${label} exitStatus`)
}

function requireReadSuccess(response, label, assertionName) {
  requireReadDataEquality(response, label)
  const textDocument = extractAssertionDocumentFromYaml(toolText(response), assertionName)
  const structuredDocument = assertionDocumentFromMcp(response, assertionName)
  assertDeepEqual(textDocument.normalized, structuredDocument.normalized,
    `${label} structuredContent/text normalized equality`)
  readParityRecords.push({ label, assertion_name: assertionName, equal: true,
    normalized_sha256: sha256Text(stableJson(structuredDocument.normalized)) })
  writeJson("mcp-read-text-structured-parity.json", readParityRecords)
}

function requireReadDataEquality(response, label) {
  requireToolSuccess(response, label)
  const parsedText = parseYamlDocument(toolText(response))
  const structuredData = readData(response)
  assertDeepEqual(parsedText, structuredData, `${label} full structuredContent/text equality`)
  fullReadParityRecords.push({ label, full_equal: true, data_sha256: sha256Text(stableJson(structuredData)) })
  writeJson("mcp-full-read-parity.json", fullReadParityRecords)
}

function requireToolError(response, label) {
  assertEqual(response.result?.isError, true, `${label} isError`)
  if (!(readStructured(response).exitStatus > 0)) throw new Error(`${label} expected positive exitStatus`)
}

function requireToolUsageError(response, label) {
  assertEqual(response.error, undefined, `${label} JSON-RPC error`)
  assertEqual(response.result?.isError, true, `${label} isError`)
  const diagnostic = readStructured(response).diagnostics?.[0]
  assertEqual(diagnostic?.code, "USAGE_ERROR", `${label} diagnostic code`)
  assertEqual(diagnostic?.category, "usage", `${label} diagnostic category`)
  if (typeof diagnostic?.suggestedNextAction !== "string" || diagnostic.suggestedNextAction.length === 0) {
    throw new Error(`${label} missing diagnostic next action`)
  }
}

function requireExit(result, expected, label) {
  if (result.exitCode !== expected) throw new Error(`${label} exit ${result.exitCode}; stderr: ${result.stderr.trim()}`)
}

function assertFile(path, label) {
  if (!existsSync(path) || !statSync(path).isFile() || statSync(path).size === 0) throw new Error(`${label} is missing or empty: ${path}`)
}

function assertAbsent(path, label) {
  if (existsSync(path)) throw new Error(`${label} must not exist: ${path}`)
}

function assertNonzero(value, label) {
  if (value === 0) throw new Error(`${label} unexpectedly succeeded`)
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
}

function assertNotEqual(actual, expected, label) {
  if (actual === expected) throw new Error(`${label}: values unexpectedly equal ${JSON.stringify(actual)}`)
}

function assertArrayEqual(actual, expected, label) {
  if (!Array.isArray(actual) || actual.length !== expected.length || actual.some((value, index) => value !== expected[index])) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
  }
}

function assertDeepEqual(actual, expected, label) {
  if (stableJson(actual) !== stableJson(expected)) throw new Error(`${label}: normalized values differ`)
}

function assertIncludes(actual, expected, label) {
  if (!actual.includes(expected)) throw new Error(`${label}: missing ${JSON.stringify(expected)} in ${JSON.stringify(actual.slice(0, 500))}`)
}

function assertMatch(actual, expected, label) {
  if (!expected.test(actual)) throw new Error(`${label}: ${JSON.stringify(actual)} does not match ${expected}`)
}

function stableJson(value) {
  if (Array.isArray(value)) return `[${value.map(stableJson).join(",")}]`
  if (value && typeof value === "object") return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${stableJson(value[key])}`).join(",")}}`
  return JSON.stringify(value)
}

function pass(name) {
  scenarios[name] = "PASS"
}

function sha256File(pathOrUrl) {
  return createHash("sha256").update(readFileSync(pathOrUrl)).digest("hex")
}

function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex")
}

function writeText(name, value) {
  writeFileSync(join(evidenceDir, name), value, "utf8")
}

function writeJson(name, value) {
  writeText(name, `${JSON.stringify(value, null, 2)}\n`)
}

function registerProcess(pid, record) {
  const owned = { pid, ...record, started_at: new Date().toISOString(), exit: null }
  registry.processes.set(pid, owned)
  registry.processHistory.push(owned)
}

function unregisterProcess(pid, exit) {
  const record = registry.processes.get(pid)
  if (record) record.exit = exit
  registry.processes.delete(pid)
}

function killProcessGroup(pid, signal = "SIGKILL") {
  if (!pid) return
  try { process.kill(-pid, signal) } catch (error) {
    if (error.code !== "ESRCH") {
      try { process.kill(pid, signal) } catch (nested) { if (nested.code !== "ESRCH") throw nested }
    }
  }
}

function pidAlive(pid) {
  return existsSync(`/proc/${pid}`)
}

async function cleanup() {
  for (const timer of registry.timers) clearTimeout(timer)
  registry.timers.clear()
  for (const [pid] of registry.processes) killProcessGroup(pid, "SIGTERM")
  await new Promise(resolveWait => setTimeout(resolveWait, 200))
  for (const [pid] of registry.processes) killProcessGroup(pid, "SIGKILL")
  const processAudit = registry.processHistory.map(record => ({
    ...record,
    alive_after_cleanup: pidAlive(record.pid),
    proc_cmdline: procCmdline(record.pid),
  }))
  registry.processes.clear()
  if (tempWork && existsSync(tempWork)) rmSync(tempWork, { recursive: true, force: true })
  const residue = []
  for (const path of registry.tempPaths) if (existsSync(path)) residue.push(path)
  for (const path of registry.forbiddenOutputs) if (existsSync(path)) residue.push(path)
  const alive = processAudit.filter(record => record.alive_after_cleanup)
  writeJson("process-audit.json", { registered_processes: processAudit, alive, proc_scan: procTaskScan() })
  cleanupComplete = residue.length === 0 && alive.length === 0
  writeText("cleanup-receipt.txt", [
    `status=${cleanupComplete ? "PASS" : "FAIL"}`,
    `temp_workdir=${tempWork}`,
    `temp_workdir_exists=${tempWork ? existsSync(tempWork) : false}`,
    `registered_temp_paths=${registry.tempPaths.size}`,
    `forbidden_outputs=${registry.forbiddenOutputs.size}`,
    `residue_count=${residue.length}`,
    `alive_registered_processes=${alive.length}`,
    `active_timers=${registry.timers.size}`,
    `residue=${JSON.stringify(residue)}`,
    "",
  ].join("\n"))
  if (!cleanupComplete) fatalError ||= new Error(`cleanup residue: ${JSON.stringify(residue)} alive: ${JSON.stringify(alive)}`)
}

function emergencyCleanup(signal) {
  for (const timer of registry.timers) clearTimeout(timer)
  for (const [pid] of registry.processes) killProcessGroup(pid, "SIGKILL")
  if (tempWork && existsSync(tempWork)) rmSync(tempWork, { recursive: true, force: true })
  try { writeText("cleanup-receipt.txt", `status=INTERRUPTED\nsignal=${signal}\ntemp_workdir_exists=${tempWork ? existsSync(tempWork) : false}\n`) } catch {}
}

function procCmdline(pid) {
  try { return readFileSync(`/proc/${pid}/cmdline`, "utf8").replace(/\0/g, " ") } catch { return null }
}

function procTaskScan() {
  const matches = []
  for (const name of readdirSync("/proc").filter(name => /^\d+$/.test(name))) {
    const pid = Number(name)
    if (pid === process.pid) continue
    const command = procCmdline(pid)
    if (command && (command.includes(tempWork) || command.includes("J4aMcpServer") && command.includes(jar))) {
      matches.push({ pid, command })
    }
  }
  return matches
}

function dirtyWorktreeSnapshot() {
  let output = ""
  try {
    output = execFileSync("git", ["status", "--porcelain=v1", "-z", "--untracked-files=all"], {
      cwd: root, encoding: "utf8", maxBuffer: 16 * 1024 * 1024,
    })
  } catch (error) {
    throw new Error(`cannot capture dirty worktree: ${error.message}`)
  }
  const records = []
  const fields = output.split("\0")
  for (let index = 0; index < fields.length; index++) {
    const field = fields[index]
    if (!field) continue
    const status = field.slice(0, 2)
    let path = field.slice(3)
    if (status.includes("R") || status.includes("C")) {
      const destination = fields[++index]
      path = destination || path
    }
    const absolutePath = resolve(root, path)
    if (absolutePath === resolve(root, "scripts/qa-property-graph.mjs") || absolutePath.startsWith(`${taskOwnedEvidencePrefix}/`)) continue
    records.push({ status, path, state: pathState(absolutePath) })
  }
  return records.sort((left, right) => left.path.localeCompare(right.path))
}

function pathState(path) {
  if (!existsSync(path)) return { kind: "absent" }
  const stat = statSync(path)
  if (stat.isFile()) return { kind: "file", bytes: stat.size, sha256: sha256File(path) }
  if (stat.isSymbolicLink()) return { kind: "symlink", target: readlinkSync(path) }
  return { kind: "other" }
}

function evidenceManifest() {
  const records = []
  for (const name of readdirSync(evidenceDir).sort()) {
    if (name === "harness-artifacts-manifest.json" || name === "qa.log") continue
    const path = join(evidenceDir, name)
    if (!statSync(path).isFile()) continue
    records.push({ path: name, bytes: statSync(path).size, sha256: sha256File(path) })
  }
  return { algorithm: "sha256", sealed_by: "qa-property-graph.mjs", files: records }
}
