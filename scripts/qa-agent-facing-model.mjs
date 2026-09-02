import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"

export const ASSERTION_GUI = "org.apache.jmeter.assertions.gui.AssertionGui"
export const HTTP_GUI = "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"
export const ASSERTION_PROPERTY = ["Asserion.test_strings"]
export const TEST_ELEMENT_NAME = ["TestElement.name"]
export const TOOL_NAMES = ["read", "validate", "components", "categories", "apply", "init", "set"]
export const COMPACT_KEYS = new Set([
  "property", "type", "default", "value_shape", "row_type", "row_properties", "value_template",
])

export function findNode(value, predicate) {
  if (!value || typeof value !== "object") return null
  if (!Array.isArray(value) && predicate(value)) return value
  for (const child of Array.isArray(value) ? value : Object.values(value)) {
    const found = findNode(child, predicate)
    if (found) return found
  }
  return null
}

export function findNodes(value, predicate) {
  if (!value || typeof value !== "object") return []
  const matches = !Array.isArray(value) && predicate(value) ? [value] : []
  for (const child of Array.isArray(value) ? value : Object.values(value)) {
    matches.push(...findNodes(child, predicate))
  }
  return matches
}

export function hasProperty(node, path) {
  return Array.isArray(node?.properties) && node.properties.some(property => sameAddress(property.property, path))
}

export function findProperty(node, path) {
  const property = node?.properties?.find(candidate => sameAddress(candidate.property, path))
  assert(property, `missing property ${JSON.stringify(path)}`)
  return property
}

function sameAddress(left, right) {
  return JSON.stringify(left) === JSON.stringify(right)
}

export function firstStringValue(property) {
  return property.value.items[0].value
}

export function exactTemplate(template) {
  exactStringCollection(template, "template")
  assert(template.items.length === 1, "template must have one item")
}

export function exactStringCollection(value, label) {
  deepEqual(Object.keys(value), ["presence", "property_class", "items"], `${label} outer keys`)
  equal(value.presence, "present", `${label} presence`)
  equal(value.property_class, "org.apache.jmeter.testelement.property.CollectionProperty", `${label} property class`)
  assert(Array.isArray(value.items) && value.items.length > 0, `${label} must have at least one item`)
  for (const item of value.items) {
    deepEqual(Object.keys(item), ["type", "presence", "property_class", "value"], `${label} item keys`)
    equal(item.type, "string", `${label} item type`)
    equal(item.presence, "present", `${label} item presence`)
    equal(item.property_class, "org.apache.jmeter.testelement.property.StringProperty", `${label} item class`)
  }
}

export function clone(value) {
  return JSON.parse(JSON.stringify(value))
}

export function valueFromTemplate(template, value) {
  const result = clone(template)
  result.items[0].value = value
  return result
}

export function setPatch(ref, value) {
  return { changes: [{ set: { ref, properties: [{ property: ASSERTION_PROPERTY, type: "collection", value }] } }] }
}

export function addPatch(parent, name, value) {
  return { changes: [{ add: {
    parent,
    position: "last",
    component: ASSERTION_GUI,
    properties: [
      { property: TEST_ELEMENT_NAME, type: "string", value: name },
      { property: ASSERTION_PROPERTY, type: "collection", value },
    ],
  } }] }
}

export function aliasPatch(parent, name, value) {
  return { changes: [
    { add: {
      parent,
      position: "last",
      component: ASSERTION_GUI,
      as: "created_assertion",
      properties: [{ property: TEST_ELEMENT_NAME, type: "string", value: name }],
    } },
    { set: {
      ref: "$created_assertion",
      properties: [{ property: ASSERTION_PROPERTY, type: "collection", value }],
    } },
  ] }
}
