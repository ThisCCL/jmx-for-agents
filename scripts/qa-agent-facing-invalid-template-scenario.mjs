import { existsSync } from "node:fs"
import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { sha256File } from "./qa-agent-facing-artifacts.mjs"
import {
  ASSERTION_GUI,
  ASSERTION_PROPERTY,
  clone,
  exactStringCollection,
  exactTemplate,
  findNode,
  findProperty,
  hasProperty,
  setPatch,
} from "./qa-agent-facing-model.mjs"
import { yamlDocument } from "./qa-agent-facing-yaml.mjs"

export async function runInvalidTemplate(mcp, context) {
  const { fixture, input, inputBefore, inside, jmeterHome, originalBefore, pass, response } = context
  const ordinary = response.successData(await mcp.call("components", {
    component: ASSERTION_GUI, jmeter_home: jmeterHome,
  }, "components-template"), "template components", true)
  const collection = findProperty(ordinary, ASSERTION_PROPERTY)
  const templateAdvertised = Object.hasOwn(collection, "value_template")
  if (templateAdvertised) {
    exactTemplate(clone(collection.value_template))
  } else {
    assert(!Object.hasOwn(collection, "value_template"), "unproven collection must omit value_template")
  }

  const initial = response.successData(await mcp.call("read", {
    file: input, depth: 20, properties: "all", jmeter_home: jmeterHome,
  }, "read-invalid-template-input"), "invalid-template input read", true)
  const assertionNode = findNode(initial, node => hasProperty(node, ASSERTION_PROPERTY))
  assert(assertionNode?.ref, "fixture Response Assertion ref missing")
  const initialProperty = findProperty(assertionNode, ASSERTION_PROPERTY)
  deepEqual(initialProperty.property, ASSERTION_PROPERTY, "invalid-template property address")
  const focused = response.successData(await mcp.call("read", {
    file: input, ref: assertionNode.ref, properties: "all", jmeter_home: jmeterHome,
  }, "read-invalid-template-focused"), "invalid-template focused read", true)
  const focusedProperty = findProperty(focused.focus, ASSERTION_PROPERTY)
  const invalidTemplate = clone(focusedProperty.value)
  exactStringCollection(invalidTemplate, "focused invalid-template collection")
  delete invalidTemplate.presence
  const output = inside("invalid-template-output.jmx")
  const result = await mcp.call("apply", {
    file: input,
    patchYaml: yamlDocument(setPatch(assertionNode.ref, invalidTemplate)),
    out: output,
    jmeter_home: jmeterHome,
  }, "reject-invalid-template")
  response.requireUsageError(result, "invalid template", ["presence", "property_class", "items"])
  assert(!existsSync(output), "invalid-template rejection created output")
  equal(sha256File(input), inputBefore, "invalid-template owned input hash")
  equal(sha256File(fixture), originalBefore, "invalid-template external fixture hash")
  pass("invalid_template_structured_rejection")
}
