import { readFileSync } from "node:fs"

import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { sha256File } from "./qa-agent-facing-artifacts.mjs"
import { findNode, findNodes, findProperty, hasProperty } from "./qa-agent-facing-model.mjs"
import { yamlDocument } from "./qa-agent-facing-yaml.mjs"

const CSV = "org.apache.jmeter.config.CSVDataSet"
const KEYSTORE = "org.apache.jmeter.config.KeystoreConfig"
const GENERIC_GUI = "org.apache.jmeter.testbeans.gui.TestBeanGUI"
const ENABLED = ["TestElement.enabled"]
const FLOAT = ["qa.float"]
const DOUBLE = ["qa.double"]

export async function runFullCompatibility(mcp, context) {
  const { compatibilityInput: input, compatibilityInputBefore: inputBefore,
    inside, jmeterHome, pass, response, summary } = context
  const { successData } = response
  const catalog = []
  for (const component of [CSV, KEYSTORE]) {
    const detail = successData(await mcp.call("components", {
      component, jmeter_home: jmeterHome,
    }, `components-${component === CSV ? "csv" : "keystore"}`), `${component} detail`, true)
    equal(detail.component, component, `${component} catalog identity`)
    equal(findProperty(detail, ENABLED).type, "boolean", `${component} enabled type`)
    catalog.push(detail)
  }
  assert(!JSON.stringify(catalog).includes(GENERIC_GUI), "catalog exposed generic TestBeanGUI")

  const initial = successData(await mcp.call("read", {
    file: input, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "compat-read-initial"), "compatibility initial read", true)
  const scalarNode = findNode(initial, node => hasProperty(node, FLOAT) && hasProperty(node, DOUBLE))
  const parent = initial.root
  assert(scalarNode?.ref, "fixture must expose qa.float and qa.double")
  assert(parent?.ref, "fixture root ref missing")
  exactScalar(findProperty(scalarNode, FLOAT), "float", 1.25, "initial float")
  exactScalar(findProperty(scalarNode, DOUBLE), "double", 1.5, "initial double")
  equal(findProperty(scalarNode, ENABLED).type, "boolean", "initial enabled type")

  const patch = { changes: [
    { set: { ref: scalarNode.ref, properties: [
      { property: FLOAT, type: "float", value: 2.5 },
      { property: DOUBLE, type: "double", value: 3.75 },
      { property: ENABLED, type: "boolean", value: false },
    ] } },
    { add: { parent: parent.ref, position: "last", component: CSV, as: "csv", properties: [
      { property: ["TestElement.name"], type: "string", value: "QA CSV" },
      { property: ENABLED, type: "boolean", value: true },
    ] } },
    { set: { ref: "$csv", properties: [{ property: ENABLED, type: "boolean", value: false }] } },
    { add: { parent: parent.ref, position: "last", component: KEYSTORE, as: "keystore", properties: [
      { property: ["TestElement.name"], type: "string", value: "QA Keystore" },
    ] } },
  ] }
  const patchYaml = yamlDocument(patch)
  const submittedSecrets = ["2.5", "3.75", "QA CSV", "QA Keystore"]

  const dry = successData(await mcp.call("apply", {
    file: input, patchYaml, dryRun: true, jmeter_home: jmeterHome,
  }, "compat-apply-dry-run"), "compatibility dry-run")
  exactLedger(dry, "validated", 4, false, submittedSecrets)
  deepEqual(dry.createdRefs, [], "dry-run created refs")
  deepEqual(dry.deletedRefs, [], "dry-run deleted refs")
  equal(sha256File(input), inputBefore, "compat dry-run source hash")

  const output = inside("compatibility-commit.jmx")
  const committed = successData(await mcp.call("apply", {
    file: input, patchYaml, out: output, jmeter_home: jmeterHome,
  }, "compat-apply-commit"), "compatibility commit")
  exactLedger(committed, "committed", 4, true, submittedSecrets)
  equal(committed.createdRefs.length, 2, "committed created ref count")
  const created = new Map(committed.createdRefs.map(row => [row.alias, row.ref]))
  assert(created.get("csv") && created.get("keystore"), "created refs omitted surviving aliases")
  equal(committed.changeResults[1].resultRef, created.get("csv"), "CSV resultRef parity")
  equal(committed.changeResults[3].resultRef, created.get("keystore"), "Keystore resultRef parity")

  const reloaded = successData(await mcp.call("read", {
    file: output, depth: 20, properties: "all", includeDisabledDetails: true, jmeter_home: jmeterHome,
  }, "compat-read-commit"), "compatibility committed read", true)
  const reloadedScalar = findNode(reloaded, node => hasProperty(node, FLOAT) && hasProperty(node, DOUBLE))
  exactScalar(findProperty(reloadedScalar, FLOAT), "float", 2.5, "reloaded float")
  exactScalar(findProperty(reloadedScalar, DOUBLE), "double", 3.75, "reloaded double")
  equal(findProperty(reloadedScalar, ENABLED).value, false, "reloaded canonical enabled value")
  for (const [component, name] of [[CSV, "QA CSV"], [KEYSTORE, "QA Keystore"]]) {
    const nodes = findNodes(reloaded, node => node.component === component && node.name === name)
    equal(nodes.length, 1, `${component} reloaded cardinality`)
  }
  assert(!JSON.stringify(reloaded).includes(GENERIC_GUI), "read exposed generic TestBeanGUI")
  const xml = readFileSync(output, "utf8")
  assert(/<FloatProperty>[\s\S]*?<name>qa\.float<\/name>[\s\S]*?<value>2\.5<\/value>/.test(xml),
    "SaveService output omitted FloatProperty parity")
  assert(/<doubleProp>[\s\S]*?<name>qa\.double<\/name>[\s\S]*?<value>3\.75<\/value>/.test(xml),
    "SaveService output omitted DoubleProperty parity")

  successData(await mcp.call("set", {
    file: output, locator: created.get("csv"), property: ENABLED, type: "boolean", value: true,
    override: true, jmeter_home: jmeterHome,
  }, "compat-created-ref-reuse"), "created ref exact-target reuse")
  const reused = successData(await mcp.call("read", {
    file: output, ref: created.get("csv"), properties: "all", jmeter_home: jmeterHome,
  }, "compat-read-reused-ref"), "reused ref read", true)
  equal(reused.focus.component, CSV, "reused ref exact component")
  equal(findProperty(reused.focus, ENABLED).value, true, "reused ref enabled value")
  equal(sha256File(input), inputBefore, "compatibility source hash after commit")

  summary.compatibility = {
    exactScalars: { float: { type: "float", propertyClass: "FloatProperty", value: 2.5 },
      double: { type: "double", propertyClass: "DoubleProperty", value: 3.75 } },
    canonicalEnabled: true,
    publicComponents: [CSV, KEYSTORE],
    genericGuiPublic: false,
    dryRunCards: 4,
    committedCards: 4,
    createdAliases: [...created.keys()],
    exactTargetReuse: true,
    compatibilityInputHashPreserved: true,
  }
  pass("finite_float_and_double_SaveService_reload_preserved_FloatProperty_and_DoubleProperty")
  pass("canonical_boolean_TestElement_enabled_reused_across_catalog_read_apply_and_set")
  pass("CSVDataSet_and_KeystoreConfig_exact_runtime_FQCN_survived_add_reload_and_read")
  pass("dry_run_and_commit_changeResults_are_ordered_value_free_and_count_complete")
  pass("surviving_add_resultRefs_match_createdRefs_and_reuse_on_exact_unchanged_target")
}

function exactScalar(property, type, value, label) {
  equal(property.type, type, `${label} type`)
  equal(property.value, value, `${label} value`)
}

function exactLedger(receipt, status, count, expectRefs, forbidden) {
  equal(receipt.appliedCount, count, `${status} appliedCount`)
  equal(receipt.changeResults.length, count, `${status} changeResults count`)
  deepEqual(receipt.changeResults.map(row => row.index), [0, 1, 2, 3], `${status} indices`)
  deepEqual(receipt.changeResults.map(row => row.operation), ["set", "add", "set", "add"], `${status} operations`)
  deepEqual(receipt.changeResults.map(row => row.status), Array(count).fill(status), `${status} statuses`)
  const encoded = JSON.stringify(receipt.changeResults)
  for (const value of forbidden) assert(!encoded.includes(value), `${status} ledger repeated submitted value`)
  assert(!/"(?:value|type|row)"\s*:/.test(encoded), `${status} ledger exposed submitted/internal value fields`)
  for (const [index, row] of receipt.changeResults.entries()) {
    assert(row.context && typeof row.context === "object", `${status} row ${index} context missing`)
    equal(Object.hasOwn(row, "resultRef"), expectRefs && (index === 1 || index === 3),
      `${status} row ${index} resultRef presence`)
  }
}
