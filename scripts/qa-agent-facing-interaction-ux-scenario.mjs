import { copyFileSync, existsSync, writeFileSync } from "node:fs"
import { spawnSync } from "node:child_process"

import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { sha256File } from "./qa-agent-facing-artifacts.mjs"
import { ASSERTION_GUI, findNode, findNodes, hasProperty, TOOL_NAMES } from "./qa-agent-facing-model.mjs"
import { parseYamlDocument, yamlDocument } from "./qa-agent-facing-yaml.mjs"
import { runInteractionContract } from "./qa-agent-facing-interaction-contract.mjs"

const SCOPES = Object.freeze({
  read: { source: "source-bound", target: "none", created: "none" },
  init: { source: "none", target: "target-bound", created: "none" },
  none: { source: "none", target: "none", created: "none" },
  inPlace: { source: "none", target: "none", created: "target-bound" },
  copy: { source: "source-bound", target: "none", created: "target-bound" },
})

export async function runInteractionUx(mcp, context) {
  const { artifactRecorder, input, inputBefore, inside, jar, javaCommand, jmeterHome, pass, response, summary } = context
  const commands = [`driver: ${[process.execPath, ...process.argv.slice(1)].map(shellQuote).join(" ")}`]
  const sizes = {}
  summary.currentGuard = "TOOLS_LIST_BUDGET"
  const listed = await mcp.request("tools/list", {}, "tools-list")
  const tools = listed.result?.tools
  assert(Array.isArray(tools), "tools/list must return tools")
  deepEqual(tools.map(tool => tool.name), TOOL_NAMES, "public tool order")
  sizes.toolsListChars = JSON.stringify({ tools }).length
  assert(sizes.toolsListChars <= 40_000, `tools/list size ${sizes.toolsListChars} exceeds 40000`)
  sizes.toolDescriptionChars = Object.fromEntries(tools.map(tool => [tool.name, String(tool.description || "").length]))
  for (const [name, chars] of Object.entries(sizes.toolDescriptionChars)) {
    assert(chars > 0 && chars < 4096, `${name} description size ${chars} is outside 1..4095`)
  }
  pass("tools_list_budgets")

  summary.currentGuard = "COMPONENT_COMPATIBILITY"
  const ordinary = response.successData(await mcp.call("components", {
    component: ASSERTION_GUI, jmeter_home: jmeterHome,
  }, "components-ordinary"), "ordinary components", true)
  const diagnostics = response.successData(await mcp.call("components", {
    component: ASSERTION_GUI, diagnostics: true, jmeter_home: jmeterHome,
  }, "components-diagnostics"), "diagnostic components", true)
  assert(!Object.hasOwn(ordinary, "runtime_metadata_status"), "ordinary components leaked runtime metadata status")
  equal(diagnostics.runtime_metadata_status, "runtime-proven", "diagnostic runtime metadata status")
  deepEqual(Object.keys(diagnostics).filter(key => key !== "runtime_metadata_status"), Object.keys(ordinary),
    "diagnostic component ordinary fields")
  pass("diagnostics_metadata_status_and_ordinary_compatibility")

  const mcpAllResponse = await mcp.call("read", {
    file: input, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-mcp-all")
  summary.currentGuard = "SUCCESS_RESPONSE"
  const mcpAll = response.successData(mcpAllResponse, "MCP all read", true)
  summary.currentGuard = "REF_SCOPE"
  exactScope(mcpAllResponse, SCOPES.read, "MCP all read")
  const mcpWritableResponse = await mcp.call("read", {
    file: input, depth: 20, properties: "writable", jmeter_home: jmeterHome,
  }, "read-mcp-writable")
  summary.currentGuard = "SUCCESS_RESPONSE"
  const mcpWritable = response.successData(mcpWritableResponse, "MCP writable read", true)
  summary.currentGuard = "REF_SCOPE"
  exactScope(mcpWritableResponse, SCOPES.read, "MCP writable read")
  summary.currentGuard = "WRITABLE_PROJECTION"
  assertWritableProjection(mcpAll, mcpWritable)
  sizes.mcpAllChars = JSON.stringify(mcpAll).length
  sizes.mcpWritableChars = JSON.stringify(mcpWritable).length
  assert(sizes.mcpWritableChars <= sizes.mcpAllChars, "MCP writable output exceeds all output")

  summary.currentGuard = "CLI_WRITABLE_PARITY"
  const cliAll = runCliRead("all", { commands, input, jar, javaCommand, jmeterHome })
  const cliWritable = runCliRead("writable", { commands, input, jar, javaCommand, jmeterHome })
  assertWritableProjection(cliAll.data, cliWritable.data)
  deepEqual(normalizeRefs(cliWritable.data), normalizeRefs(mcpWritable), "CLI/MCP writable parity")
  sizes.cliAllBytes = cliAll.bytes
  sizes.cliWritableBytes = cliWritable.bytes
  assert(sizes.cliWritableBytes <= sizes.cliAllBytes, "CLI writable output exceeds all output")
  pass("cli_mcp_writable_parity_and_recursive_preservation")

  summary.currentGuard = "INVALID_INPUT"
  response.requireUsageError(await mcp.call("read", {
    file: input, properties: "bogus", jmeter_home: jmeterHome,
  }, "reject-mcp-enum"), "invalid MCP enum", ["properties"])
  const cliInvalid = runCli(["read", input, "--properties", "none", "--jmeter-home", jmeterHome],
    { commands, jar, javaCommand, label: "reject-cli-enum" })
  assert(cliInvalid.status !== 0, "CLI invalid enum returned exit 0")
  assert(cliInvalid.stderr.trim().length > 0, "CLI invalid enum omitted diagnostics")
  pass("invalid_enums")

  const assertionNode = findNode(mcpAll, node => hasProperty(node, ["Asserion.test_strings"]))
  const editNode = assertionNode || findNode(mcpAll, node => hasProperty(node, ["TestElement.name"]))
  assert(editNode?.ref, "fixture writable node ref missing")
  if (assertionNode) {
    const invalidOut = inside("invalid-example.jmx")
    response.requireUsageError(await mcp.call("set", {
      file: input, locator: assertionNode.ref, property: ["Asserion.test_strings"], type: "collection",
      value: ["invalid-bare-list"], out: invalidOut, jmeter_home: jmeterHome,
    }, "reject-invalid-example"), "invalid generic example", ["presence", "property_class", "items"])
    assert(!existsSync(invalidOut), "invalid example created output")
    pass("invalid_recursive_example")
  }

  summary.currentGuard = "WRITE_SEMANTICS"
  const patch = yamlDocument({ changes: [{ set: { ref: editNode.ref, properties: [{
    property: ["TestElement.name"], type: "string", value: "QA interaction updated",
  }] } }] })
  const dryResponse = await mcp.call("apply", {
    file: input, patchYaml: patch, dryRun: true, jmeter_home: jmeterHome,
  }, "apply-dry-run")
  response.successData(dryResponse, "apply dry-run")
  exactScope(dryResponse, SCOPES.none, "apply dry-run")
  equal(sha256File(input), inputBefore, "dry-run source hash")

  const setOutput = inside("set-copy.jmx")
  const setResponse = await mcp.call("set", {
    file: input, locator: editNode.ref, property: ["TestElement.name"], type: "string",
    value: "QA set copy", out: setOutput, jmeter_home: jmeterHome,
  }, "set-copy")
  response.successData(setResponse, "set copy")
  exactScope(setResponse, SCOPES.none, "set copy")
  assert(existsSync(setOutput), "set copy output missing")

  const inPlace = inside("in-place.jmx")
  copyFileSync(input, inPlace)
  const inPlaceReadResponse = await mcp.call("read", {
    file: inPlace, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-in-place-input")
  const inPlaceRead = response.successData(inPlaceReadResponse, "in-place source read", true)
  exactScope(inPlaceReadResponse, SCOPES.read, "in-place source read")
  const inPlaceRef = findNode(inPlaceRead, node => assertionNode
    ? hasProperty(node, ["Asserion.test_strings"])
    : hasProperty(node, ["TestElement.name"]))?.ref
  assert(inPlaceRef, "in-place source ref missing")
  const inPlacePatch = patch.replace(editNode.ref, inPlaceRef)
  const inPlaceResponse = await mcp.call("apply", {
    file: inPlace, patchYaml: inPlacePatch, override: true, jmeter_home: jmeterHome,
  }, "apply-in-place")
  response.successData(inPlaceResponse, "apply in-place")
  exactScope(inPlaceResponse, SCOPES.inPlace, "apply in-place")

  const copyOutput = inside("apply-copy.jmx")
  const copyResponse = await mcp.call("apply", {
    file: input, patchYaml: patch, out: copyOutput, jmeter_home: jmeterHome,
  }, "apply-copy")
  response.successData(copyResponse, "apply copy")
  exactScope(copyResponse, SCOPES.copy, "apply copy")
  const copiedReadResponse = await mcp.call("read", {
    file: copyOutput, depth: 20, properties: "writable", jmeter_home: jmeterHome,
  }, "read-copy-output")
  const copiedRead = response.successData(copiedReadResponse, "copy output read", true)
  exactScope(copiedReadResponse, SCOPES.read, "copy output read")
  summary.currentGuard = "GENERATED_PAYLOAD"
  assert(findNodes(copiedRead, node => node.name === "QA interaction updated").length === 1,
    "copy output did not preserve generated payload")

  const initOutput = inside("initialized.jmx")
  const initResponse = await mcp.call("init", {
    out: initOutput, name: "QA initialized", threadGroupName: "QA thread group", jmeter_home: jmeterHome,
  }, "init")
  response.successData(initResponse, "init")
  exactScope(initResponse, SCOPES.init, "init")
  assert(existsSync(initOutput), "init output missing")
  pass("ref_scope_full_matrix_and_copy_in_place_semantics")

  summary.currentGuard = "STALE_RECOVERY"
  const staleResponse = await mcp.call("set", {
    path: input, locator: "definitely-stale-ref", property: ["TestElement.name"], type: "string",
    value: "must-not-write", override: true, jmeter_home: jmeterHome,
  }, "stale-set")
  const stale = response.requireToolError(staleResponse, "stale set")
  equal(stale.structuredContent.diagnostics[0].code, "MCP_REF_NOT_FOUND", "stale diagnostic code")
  deepEqual(stale.structuredContent.recovery, {
    tool: "read", arguments: { path: input, jmeter_home: jmeterHome },
  }, "stale recovery")
  const recovery = stale.structuredContent.recovery
  const recoveredResponse = await mcp.call(recovery.tool, recovery.arguments, "recovery-read")
  response.successData(recoveredResponse, "copied recovery read", true)
  equal(mcp.requests.filter(record => record.label === "stale-set").length, 1, "stale mutation invocation count")
  equal(mcp.requests.filter(record => record.label === "recovery-read").length, 1, "explicit recovery invocation count")
  equal(sha256File(input), inputBefore, "source hash after stale recovery")
  pass("copied_stale_recovery_without_retry")

  const mutationLabels = [...(assertionNode ? ["reject-invalid-example"] : []),
    "apply-dry-run", "set-copy", "apply-in-place", "apply-copy", "stale-set"]
  artifactRecorder.recordMutationEvidence(mcp, mutationLabels, {
    dryRunPreservedSource: true, copyReadBack: true, inPlaceOwnedCopy: true, staleSetNonMutating: true,
  })
  await runInteractionContract(mcp, context, commands, sizes)
  artifactRecorder.writeJson("sizes.json", sizes)
  writeFileSync(inside("command-manifest.txt"), `${commands.join("\n")}\n`, "utf8")
  summary.interaction = { sizes, scopes: SCOPES, recoveryCopied: true }
  summary.currentGuard = "RUNTIME_COMPLETE"
}

function exactScope(response, expected, label) {
  const structured = response.result?.structuredContent
  deepEqual(Object.keys(structured).sort(), ["data", "diagnostics", "exitStatus", "ref_scope"],
    `${label} structuredContent fields`)
  deepEqual(Object.keys(structured.ref_scope || {}), ["source", "target", "created"], `${label} ref_scope fields`)
  deepEqual(structured.ref_scope, expected, `${label} ref_scope`)
  const scopeText = JSON.stringify(structured.ref_scope)
  for (const ref of collectRefs(structured.data)) assert(!scopeText.includes(ref), `${label} ref_scope duplicated ${ref}`)
  assert(scopeText.length < 128, `${label} ref_scope exceeds fixed bound`)
}

function assertWritableProjection(all, writable) {
  const allNodes = findNodes(all, node => typeof node.component === "string")
  const writableNodes = findNodes(writable, node => typeof node.component === "string")
  equal(writableNodes.length, allNodes.length, "writable tree node count")
  let removed = 0
  let recursive = 0
  for (let index = 0; index < allNodes.length; index += 1) {
    const allProperties = allNodes[index].properties || []
    const writableProperties = writableNodes[index].properties || []
    const allByName = new Map(allProperties.map(property => [JSON.stringify(property.property), property]))
    for (const property of writableProperties) {
      deepEqual(property, allByName.get(JSON.stringify(property.property)),
        `writable property ${JSON.stringify(property.property)}`)
      if (property.value && typeof property.value === "object") recursive += 1
    }
    removed += allProperties.length - writableProperties.length
  }
  assert(removed > 0, "writable projection did not remove any non-writable property")
  assert(recursive > 0, "writable projection omitted recursive reusable documents")
}

function runCliRead(mode, context) {
  const result = runCli(["read", context.input, "--depth", "20", "--properties", mode,
    "--jmeter-home", context.jmeterHome], { ...context, label: `cli-read-${mode}` })
  equal(result.status, 0, `CLI ${mode} exit status`)
  assert(result.stderr.trim() === "", `CLI ${mode} stderr was not empty`)
  return { bytes: Buffer.byteLength(result.stdout), data: parseYamlDocument(result.stdout) }
}

function runCli(args, { commands, jar, javaCommand, label }) {
  const command = [javaCommand, "-jar", jar, ...args]
  commands.push(`${label}: ${command.map(shellQuote).join(" ")}`)
  return spawnSync(javaCommand, ["-jar", jar, ...args], { encoding: "utf8", timeout: 60_000 })
}

function normalizeRefs(value) {
  if (Array.isArray(value)) return value.map(normalizeRefs)
  if (!value || typeof value !== "object") return value
  return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, key === "ref" ? "<REF>" : normalizeRefs(child)]))
}

function collectRefs(value, result = []) {
  if (Array.isArray(value)) for (const item of value) collectRefs(item, result)
  else if (value && typeof value === "object") for (const [key, child] of Object.entries(value)) {
    if (key === "ref" && typeof child === "string") result.push(child)
    collectRefs(child, result)
  }
  return result
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`
}
