import { existsSync } from "node:fs"
import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { sha256File } from "./qa-agent-facing-artifacts.mjs"
import {
  addPatch,
  aliasPatch,
  ASSERTION_GUI,
  ASSERTION_PROPERTY,
  clone,
  COMPACT_KEYS,
  findNode,
  findNodes,
  findProperty,
  firstStringValue,
  hasProperty,
  HTTP_GUI,
  setPatch,
  TOOL_NAMES,
  valueFromTemplate,
  exactStringCollection,
  exactTemplate,
} from "./qa-agent-facing-model.mjs"
import { yamlDocument } from "./qa-agent-facing-yaml.mjs"
import { runFullCompatibility } from "./qa-agent-facing-full-compatibility.mjs"

export async function runFull(mcp, context) {
  const { artifactRecorder, input, inputBefore, inside, jmeterHome, pass, response, summary } = context
  const { actionableText, requireToolError, requireUsageError, successData } = response
  const runCompatibilityOnly = async () => {
    await runFullCompatibility(mcp, context)
    artifactRecorder.recordMutationEvidence(mcp, [
      "compat-apply-dry-run", "compat-apply-commit", "compat-created-ref-reuse",
    ], {
      exactScalarReload: true, canonicalEnabled: true, exactRuntimeIdentities: true,
      orderedLedgers: true, createdRefReuse: true,
    })
    pass("no_client_retry")
  }
  const listed = await mcp.request("tools/list", {}, "tools-list")
  const tools = listed.result?.tools
  assert(Array.isArray(tools), "tools/list must return tools")
  deepEqual(tools.map(tool => tool.name), TOOL_NAMES, "public tool order")
  const toolsChars = JSON.stringify({ tools }).length
  assert(toolsChars <= 40_000, `tools/list size ${toolsChars} exceeds 40000`)
  pass("initialize_and_tools_list")

  const ordinaryResult = await mcp.call("components", {
    component: ASSERTION_GUI, jmeter_home: jmeterHome,
  }, "components-ordinary")
  const diagnosticsResult = await mcp.call("components", {
    component: ASSERTION_GUI, diagnostics: true, jmeter_home: jmeterHome,
  }, "components-diagnostics")
  const ordinary = successData(ordinaryResult, "ordinary components", true)
  const diagnostics = successData(diagnosticsResult, "diagnostic components", true)
  const ordinaryCollection = findProperty(ordinary, ASSERTION_PROPERTY)
  const templateAdvertised = Object.hasOwn(ordinaryCollection, "value_template")
  const ordinaryKeys = [...new Set(ordinary.properties.flatMap(property => Object.keys(property)))].sort()
  deepEqual(ordinaryKeys, templateAdvertised
    ? ["default", "property", "type", "value_shape", "value_template"]
    : ["default", "property", "type", "value_shape"],
    "ordinary Response Assertion keys")
  for (const property of ordinary.properties) {
    assert(Object.keys(property).every(key => COMPACT_KEYS.has(key)),
      `ordinary property contains extra field: ${JSON.stringify(property)}`)
  }
  deepEqual(Object.keys(ordinaryCollection), templateAdvertised
    ? ["property", "type", "value_shape", "value_template"]
    : ["property", "type", "value_shape"], "collection authoring fields")
  const diagnosticCollection = findProperty(diagnostics, ASSERTION_PROPERTY)
  equal(Object.hasOwn(diagnosticCollection, "value_template"), templateAdvertised,
    "ordinary and diagnostic template availability")
  const enabled = findProperty(ordinary, ["TestElement.enabled"])
  deepEqual(Object.keys(enabled), ["property", "type", "default"], "enabled authoring fields")
  equal(enabled.default, true, "enabled default")
  for (const property of ordinary.properties) {
    if (property !== ordinaryCollection && property !== enabled) {
      deepEqual(Object.keys(property), ["property", "type"], `${property.property} authoring fields`)
    }
  }
  const ordinaryChars = JSON.stringify(ordinary).length
  const diagnosticsChars = JSON.stringify(diagnostics).length
  assert(ordinaryChars < diagnosticsChars, "ordinary components must be smaller than diagnostics")
  const template = templateAdvertised ? clone(ordinaryCollection.value_template) : null
  if (template !== null) {
    exactTemplate(template)
    deepEqual(diagnosticCollection.value_template, template, "ordinary and diagnostic template contract")
    pass("runtime_proven_template_contract")
  }
  artifactRecorder.writeJson("size-budget.json", {
    toolsListChars: toolsChars,
    limit: 40_000,
    ordinaryChars,
    diagnosticsChars,
    ordinaryKeys,
  })
  pass("ordinary_and_diagnostic_component_budget")

  const initial = successData(await mcp.call("read", {
    file: input, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-initial"), "initial read", true)
  const assertionNode = findNode(initial, node => hasProperty(node, ASSERTION_PROPERTY))
  const httpNode = findNode(initial, node => node.component === HTTP_GUI)
  assert(httpNode?.ref, "fixture HTTP sampler ref missing")
  if (!assertionNode?.ref) {
    await runCompatibilityOnly()
    return
  }
  const focused = successData(await mcp.call("read", {
    file: input, ref: assertionNode.ref, properties: "all", jmeter_home: jmeterHome,
  }, "read-focused"), "focused read", true)
  assert(hasProperty(focused.focus, ASSERTION_PROPERTY), "focused read omitted Response Assertion property")
  pass("focused_read")

  const focusedProperty = findProperty(focused.focus, ASSERTION_PROPERTY)
  if (focusedProperty.value?.presence !== "present" || !Array.isArray(focusedProperty.value.items)
      || focusedProperty.value.items.length === 0) {
    await runCompatibilityOnly()
    return
  }
  const focusedValue = clone(focusedProperty.value)
  const collectionValue = template === null ? focusedValue : template
  exactStringCollection(collectionValue, template === null ? "focused collection" : "template")
  if (template === null) pass("unproven_template_absent_uses_focused_value")

  const dryValue = valueFromTemplate(collectionValue, "qa-template-dry-run")
  const dryPatch = setPatch(assertionNode.ref, dryValue)
  const dry = successData(await mcp.call("apply", {
    file: input, patchYaml: yamlDocument(dryPatch), dryRun: true, jmeter_home: jmeterHome,
  }, "apply-template-dry-run"), "template dry-run")
  equal(dry.dryRun, true, "dry-run receipt")
  equal(sha256File(input), inputBefore, "dry-run input hash")
  pass("template_apply_dry_run")

  const addedOutput = inside("add-with-properties.jmx")
  const addedName = "QA Agent Facing Added"
  const addValue = valueFromTemplate(collectionValue, "qa-added-value")
  successData(await mcp.call("apply", {
    file: input,
    patchYaml: yamlDocument(addPatch(httpNode.ref, addedName, addValue)),
    out: addedOutput,
    jmeter_home: jmeterHome,
  }, "apply-add-with-properties"), "add with properties")
  const addedRead = successData(await mcp.call("read", {
    file: addedOutput, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-added-output"), "added output read", true)
  const addedNodes = findNodes(addedRead, node => node.component === ASSERTION_GUI && node.name === addedName)
  equal(addedNodes.length, 1, "add-with-properties cardinality")
  equal(firstStringValue(findProperty(addedNodes[0], ASSERTION_PROPERTY)), "qa-added-value", "add-with-properties value")
  pass("add_with_properties")

  const aliasOutput = inside("add-then-alias-set.jmx")
  const aliasName = "QA Alias Added"
  const aliasValue = valueFromTemplate(collectionValue, "qa-alias-value")
  successData(await mcp.call("apply", {
    file: input,
    patchYaml: yamlDocument(aliasPatch(httpNode.ref, aliasName, aliasValue)),
    out: aliasOutput,
    jmeter_home: jmeterHome,
  }, "apply-add-then-alias-set"), "add then alias set")
  const aliasRead = successData(await mcp.call("read", {
    file: aliasOutput, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-alias-output"), "alias output read", true)
  const aliasNodes = findNodes(aliasRead, node => node.component === ASSERTION_GUI && node.name === aliasName)
  equal(aliasNodes.length, 1, "add-then-alias-set cardinality")
  const aliasNode = aliasNodes[0]
  equal(firstStringValue(findProperty(aliasNode, ASSERTION_PROPERTY)), "qa-alias-value", "alias-set value")
  pass("add_then_alias_set")

  const persistedOutput = inside("real-copy-write.jmx")
  const persistedValue = valueFromTemplate(collectionValue, "qa-persisted-value")
  successData(await mcp.call("set", {
    file: aliasOutput,
    locator: aliasNode.ref,
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: persistedValue,
    out: persistedOutput,
    jmeter_home: jmeterHome,
  }, "set-real-copy"), "real copy set")
  const persistedTree = successData(await mcp.call("read", {
    file: persistedOutput, depth: 20, properties: "none", jmeter_home: jmeterHome,
  }, "read-real-copy-tree"), "real copy tree read", true)
  const persistedNodes = findNodes(persistedTree,
    node => node.component === ASSERTION_GUI && node.name === aliasName)
  equal(persistedNodes.length, 1, "real-copy retained-node cardinality")
  const persistedFocused = successData(await mcp.call("read", {
    file: persistedOutput, ref: persistedNodes[0].ref, properties: "all", jmeter_home: jmeterHome,
  }, "read-real-copy-focused"), "real copy focused read", true)
  equal(firstStringValue(findProperty(persistedFocused.focus, ASSERTION_PROPERTY)),
    "qa-persisted-value", "persisted focused value")
  summary.outputs.realCopy = { path: "real-copy-write.jmx", sha256: sha256File(persistedOutput) }
  pass("real_copy_write_and_focused_read_back")

  const bareOutput = inside("forbidden-bare-list.jmx")
  requireUsageError(await mcp.call("set", {
    file: input,
    locator: assertionNode.ref,
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: ["must-not-write"],
    out: bareOutput,
    jmeter_home: jmeterHome,
  }, "reject-bare-list"), "bare list", ["presence", "property_class", "items", "components", "read"])
  assert(!existsSync(bareOutput), "bare-list rejection created output")
  pass("bare_list_recovery")

  requireUsageError(await mcp.call("read", {
    file: input, properites: "all", jmeter_home: jmeterHome,
  }, "reject-misspelled-argument"), "misspelled argument", ["properites"])
  pass("misspelled_argument_rejection")

  const conflictOutput = inside("forbidden-set-mode.jmx")
  requireUsageError(await mcp.call("set", {
    file: input,
    locator: assertionNode.ref,
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: dryValue,
    out: conflictOutput,
    override: true,
    jmeter_home: jmeterHome,
  }, "reject-set-mode"), "set mode", ["out", "override"])
  assert(!existsSync(conflictOutput), "set-mode rejection created output")
  pass("set_mode_rejection")

  const samePath = await mcp.call("set", {
    file: input,
    locator: assertionNode.ref,
    property: ASSERTION_PROPERTY,
    type: "collection",
    value: dryValue,
    out: input,
    jmeter_home: jmeterHome,
  }, "reject-same-path")
  requireToolError(samePath, "same-path set")
  assert(actionableText(samePath).toLowerCase().includes("override"), "same-path guidance must name override")
  equal(sha256File(input), inputBefore, "same-path input hash")
  pass("same_path_guidance")

  artifactRecorder.recordMutationEvidence(mcp, [
    "apply-template-dry-run",
    "apply-add-with-properties",
    "apply-add-then-alias-set",
    "set-real-copy",
    "reject-bare-list",
    "reject-set-mode",
    "reject-same-path",
  ], {
    addWithProperties: { cardinality: addedNodes.length, readBack: true },
    addThenAliasSet: { cardinality: aliasNodes.length, readBack: true },
    realCopy: { retainedNodeCardinality: persistedNodes.length, readBack: true },
  })
  await runFullCompatibility(mcp, context)
  pass("no_client_retry")
}
