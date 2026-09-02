import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import test from "node:test"

import { fakeEnvironment, runDriver } from "./qa-agent-facing-test-support.mjs"

function protocolLabels(workDir) {
  return readFileSync(join(workDir, "requests.jsonl"), "utf8").trim().split("\n")
    .filter(Boolean).map(line => JSON.parse(line).label)
}

function protocolRecords(workDir, name) {
  return readFileSync(join(workDir, name), "utf8").trim().split("\n")
    .filter(Boolean).map(line => JSON.parse(line))
}

test("full driver uses a focused populated value when components omits an unproven template", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-unproven-template-"))
  try {
    const environment = fakeEnvironment(root, { matchingYaml: true, withoutValueTemplate: true })
    const result = runDriver(environment)

    assert.equal(result.status, 0, result.stderr)
    const responses = readFileSync(join(environment.workDir, "responses.jsonl"), "utf8").trim().split("\n")
      .map(line => JSON.parse(line))
    const ordinary = responses.find(record => record.label === "components-ordinary")
      ?.message.result.structuredContent.data
    const collection = ordinary.properties.find(property =>
      Array.isArray(property.property) && property.property.length === 1
        && property.property[0] === "Asserion.test_strings")
    assert.equal(Object.hasOwn(collection, "value_template"), false)
    assert.deepEqual(protocolLabels(environment.workDir), [
      "initialize", "notifications/initialized", "tools-list", "components-ordinary", "components-diagnostics",
      "read-initial", "read-focused", "apply-template-dry-run", "apply-add-with-properties", "read-added-output",
      "apply-add-then-alias-set", "read-alias-output", "set-real-copy", "read-real-copy-tree",
      "read-real-copy-focused", "reject-bare-list", "reject-misspelled-argument", "reject-set-mode",
      "reject-same-path", "components-csv", "components-keystore", "compat-read-initial",
      "compat-apply-dry-run", "compat-apply-commit", "compat-read-commit", "compat-created-ref-reuse",
      "compat-read-reused-ref", "shutdown",
    ])
    const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
    assert.equal(summary.assertions.includes("unproven_template_absent_uses_focused_value"), true)
    assert.equal(summary.assertions.includes(
      "finite_float_and_double_SaveService_reload_preserved_FloatProperty_and_DoubleProperty"), true)
    assert.equal(summary.mutations.count, 7)
    assert.equal(existsSync(join(environment.workDir, "real-copy-write.jmx")), true)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("full driver rejects a malformed advertised collection template before public mutation", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-malformed-template-"))
  try {
    const environment = fakeEnvironment(root, { matchingYaml: true, malformedValueTemplate: true })
    const result = runDriver(environment)

    assert.notEqual(result.status, 0)
    assert.deepEqual(protocolLabels(environment.workDir), [
      "initialize", "notifications/initialized", "tools-list", "components-ordinary", "components-diagnostics",
      "shutdown",
    ])
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("invalid-template driver rejects an invalid populated collection when components omits a template", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-invalid-populated-template-"))
  try {
    const environment = fakeEnvironment(root, {
      matchingYaml: true,
      withoutValueTemplate: true,
      rejectInvalidTemplate: true,
    })
    const result = runDriver(environment, undefined, "invalid-template")

    assert.equal(result.status, 0, result.stderr)
    assert.deepEqual(protocolLabels(environment.workDir), [
      "initialize", "notifications/initialized", "components-template", "read-invalid-template-input",
      "read-invalid-template-focused", "reject-invalid-template", "shutdown",
    ])
    const requests = protocolRecords(environment.workDir, "requests.jsonl")
    const responses = protocolRecords(environment.workDir, "responses.jsonl")
    const initialResponse = responses.find(record => record.label === "read-invalid-template-input")
    const focusedRequest = requests.find(record => record.label === "read-invalid-template-focused")
    const focusedResponse = responses.find(record => record.label === "read-invalid-template-focused")
    const assertionRef = initialResponse.message.result.structuredContent.data.root.children[0].ref
    assert.deepEqual(Object.keys(focusedRequest.message.params.arguments).sort(), [
      "file", "jmeter_home", "properties", "ref",
    ])
    assert.equal(focusedRequest.message.params.arguments.file,
      requests.find(record => record.label === "read-invalid-template-input").message.params.arguments.file)
    assert.equal(focusedRequest.message.params.arguments.jmeter_home,
      requests.find(record => record.label === "read-invalid-template-input").message.params.arguments.jmeter_home)
    assert.equal(focusedRequest.message.params.arguments.ref, assertionRef)
    assert.equal(focusedRequest.message.params.arguments.properties, "all")
    assert.deepEqual(focusedResponse.message.result.structuredContent.data.focus.properties[0].property,
      ["Asserion.test_strings"])
    const summary = JSON.parse(readFileSync(join(environment.workDir, "summary.json"), "utf8"))
    assert.equal(summary.assertions.includes("invalid_template_structured_rejection"), true)
    assert.equal(existsSync(join(environment.workDir, "invalid-template-output.jmx")), false)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("invalid-template driver mutates only the focused collection when a template is advertised", () => {
  const root = mkdtempSync(join(tmpdir(), "j4a-agent-facing-advertised-template-source-"))
  try {
    const applyPatchCaptureFile = join(root, "invalid-template-apply.yaml")
    const environment = fakeEnvironment(root, {
      applyPatchCaptureFile,
      distinctCollectionSentinels: true,
      matchingYaml: true,
      rejectInvalidTemplate: true,
    })
    const result = runDriver(environment, undefined, "invalid-template")

    assert.equal(result.status, 0, result.stderr)
    assert.equal(existsSync(applyPatchCaptureFile), true)
    const patch = readFileSync(applyPatchCaptureFile, "utf8")
    assert.match(patch, /focused-value-sentinel/)
    assert.doesNotMatch(patch, /advertised-template-sentinel|whole-tree-value-sentinel/)
    assert.match(patch, /value:\n\s+property_class:/)
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
