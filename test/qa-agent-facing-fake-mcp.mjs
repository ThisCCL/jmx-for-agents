export function fakeMcpJava(options = {}) {
  if (options.interactionUx) return fakeInteractionUxJava(options)
  return `#!/usr/bin/env node
const testConfig = ${JSON.stringify(options)}
const assertionProperty = ["Asserion.test_strings"]
const assertionGui = "org.apache.jmeter.assertions.gui.AssertionGui"
const httpGui = "org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui"
const csvComponent = "org.apache.jmeter.config.CSVDataSet"
const keystoreComponent = "org.apache.jmeter.config.KeystoreConfig"
const { closeSync, writeFileSync } = require("node:fs")
const { spawn } = require("node:child_process")
writeFileSync(testConfig.childPidFile, String(process.pid))
if (testConfig.spawnDescendant) {
  const descendant = spawn(process.execPath, ["-e", "setInterval(() => {}, 1000)"], { stdio: "ignore" })
  writeFileSync(testConfig.descendantPidFile, String(descendant.pid))
}
if (testConfig.exitImmediately) process.exit(17)
const advertisedTemplateValue = testConfig.distinctCollectionSentinels
  ? "advertised-template-sentinel" : "template"
const wholeTreeValue = testConfig.distinctCollectionSentinels
  ? "whole-tree-value-sentinel" : "template"
const focusedValue = testConfig.distinctCollectionSentinels
  ? "focused-value-sentinel" : "template"
const template = {
  presence: "present",
  property_class: "org.apache.jmeter.testelement.property.CollectionProperty",
  items: [{
    type: "string",
    presence: "present",
    property_class: "org.apache.jmeter.testelement.property.StringProperty",
    value: advertisedTemplateValue,
  }],
}
if (testConfig.malformedValueTemplate) template.items = []
const assertion = (name, ref, value = advertisedTemplateValue) => ({
  ref,
  component: assertionGui,
  name,
  properties: [{ property: assertionProperty, type: "collection", value: {
    ...template,
    items: [{ ...template.items[0], value }],
  } }],
})
const ordinary = {
  component: assertionGui,
  properties: [
    {
      property: assertionProperty,
      type: "collection",
      value_shape: "collection.items",
      ...(testConfig.withoutValueTemplate ? {} : { value_template: template }),
    },
    { property: ["TestElement.name"], type: "string" },
    { property: ["TestElement.enabled"], type: "boolean", default: true },
  ],
}
if (testConfig.sensitive) Object.assign(ordinary, {
  name: testConfig.sensitive.name,
  affectedFile: testConfig.sensitive.path,
  output: testConfig.sensitive.output,
  nested: {
    path: testConfig.sensitive.path,
    file: testConfig.sensitive.path,
    out: testConfig.sensitive.output,
    jmeter_home: testConfig.sensitive.path,
    value: testConfig.sensitive.value,
    text: testConfig.sensitive.text,
  },
  [testConfig.sensitive.key]: testConfig.sensitive.text,
  diagnostic: { code: "USAGE_ERROR", category: "usage" },
  enabled: true,
  count: 1,
})
let reads = 0
let buffer = ""
const yamlScalar = value => {
  if (value === null) return "null"
  if (typeof value === "boolean" || typeof value === "number") return String(value)
  return "'" + String(value).replaceAll("'", "''") + "'"
}
const yamlValue = (value, indent) => {
  const space = " ".repeat(indent)
  if (Array.isArray(value)) return value.map(item => {
    if (item && typeof item === "object") return space + "-\\n" + yamlValue(item, indent + 2)
    return space + "- " + yamlScalar(item)
  }).join("\\n")
  return Object.entries(value).map(([key, child]) => {
    if (child && typeof child === "object") return space + key + ":\\n" + yamlValue(child, indent + 2)
    return space + key + ": " + yamlScalar(child)
  }).join("\\n")
}
const respond = (id, result) => {
  const line = JSON.stringify({ jsonrpc: "2.0", id, result }) + "\\n"
  process.stdout.write(line)
  if (testConfig.duplicateResponse) process.stdout.write(line)
}
const success = data => ({
  isError: false,
  content: [{ type: "text", text: testConfig.matchingYaml ? yamlValue(data, 0) + "\\n" : "not_what_is_structured: true\\n" }],
  structuredContent: { exitStatus: 0, data, diagnostics: [] },
})
const failure = (code, message, args = {}) => ({
  isError: true,
  content: [{ type: "text", text: message }],
  structuredContent: { exitStatus: 2, data: {}, diagnostics: [{
    code,
    category: "usage",
    message,
    affectedFile: args.file,
    nested: testConfig.sensitive ? {
      path: args.file,
      file: args.file,
      out: args.out,
      jmeter_home: args.jmeter_home,
      value: testConfig.sensitive.value,
      text: testConfig.sensitive.text,
    } : undefined,
    suggestedNextAction: "retry with valid inputs",
  }], recoveryGuidance: "retry with valid inputs" },
})
const readData = () => {
  reads += 1
  if (reads === 1) return { root: { children: [assertion("Response Assertion", "assert-ref", wholeTreeValue), { ref: "http-ref", component: httpGui, name: "HTTP" }] } }
  if (reads === 2) return { focus: assertion("Response Assertion", "assert-ref", focusedValue) }
  if (reads === 3) {
    const added = assertion("QA Agent Facing Added", "added-ref", "qa-added-value")
    return { root: { children: testConfig.duplicateState ? [added, { ...added, ref: "added-ref-duplicate" }] : [added] } }
  }
  if (reads === 4) return { root: { children: [assertion("QA Alias Added", "alias-ref", "qa-alias-value")] } }
  if (reads === 5) return { root: { children: [assertion("QA Alias Added", "persisted-ref", "qa-persisted-value")] } }
  return { focus: assertion("QA Alias Added", "persisted-ref", "qa-persisted-value") }
}
const compatibilityTree = (committed = true) => ({ root: { ref: "compat-root", component: "org.apache.jmeter.testelement.TestPlan",
  name: "Compatibility", properties: [{ property: ["TestElement.enabled"], type: "boolean", value: true }], children: [
    { ref: "compat-scalar", component: assertionGui, name: "Exact Scalar Assertion", properties: [
      { property: ["qa.float"], type: "float", value: committed ? 2.5 : 1.25 },
      { property: ["qa.double"], type: "double", value: committed ? 3.75 : 1.5 },
      { property: ["TestElement.enabled"], type: "boolean", value: committed ? false : true },
    ] },
    { ref: "compat-csv", component: csvComponent, name: "QA CSV", properties: [
      { property: ["TestElement.enabled"], type: "boolean", value: true },
    ] },
    { ref: "compat-keystore", component: keystoreComponent, name: "QA Keystore", properties: [
      { property: ["TestElement.enabled"], type: "boolean", value: true },
    ] },
  ] } })
const compatibilityLedger = status => ({ appliedCount: 4, createdRefs: status === "validated" ? [] : [
  { alias: "csv", ref: "compat-csv" }, { alias: "keystore", ref: "compat-keystore" },
], deletedRefs: [], changeResults: [
  { index: 0, operation: "set", status, context: { ref: "compat-scalar", properties: [["qa.float"], ["qa.double"], ["TestElement.enabled"]] } },
  { index: 1, operation: "add", status, context: { alias: "csv", component: csvComponent, parent: "compat-root", position: "last",
    properties: [["TestElement.name"], ["TestElement.enabled"]] }, ...(status === "validated" ? {} : { resultRef: "compat-csv" }) },
  { index: 2, operation: "set", status, context: { ref: "$csv", properties: [["TestElement.enabled"]] } },
  { index: 3, operation: "add", status, context: { alias: "keystore", component: keystoreComponent, parent: "compat-root", position: "last",
    properties: [["TestElement.name"]] }, ...(status === "validated" ? {} : { resultRef: "compat-keystore" }) },
] })
const handle = message => {
  if (message.method === "initialize") {
    if (testConfig.closeStdinAfterInitialize) {
      closeSync(0)
      setTimeout(() => process.exit(17), 1_500)
    }
    return respond(message.id, { protocolVersion: "2025-06-18", serverInfo: { name: "j4a" } })
  }
  if (message.method === "shutdown") {
    if (testConfig.exitOnShutdownWithoutResponse) {
      writeFileSync(testConfig.shutdownSeenFile, message.method)
      setTimeout(() => process.exit(0), 25)
      return
    }
    return respond(message.id, null)
  }
  if (message.method === "tools/list") {
    if (testConfig.holdToolsList) writeFileSync(testConfig.toolsListSeenFile, message.method)
    if (testConfig.holdToolsList) return
    return respond(message.id, { tools: ["read", "validate", "components", "categories", "apply", "init", "set"].map(name => ({ name })) })
  }
  if (message.method !== "tools/call") return
  const { name, arguments: args } = message.params
  if (name === "components") {
    if (args.component === csvComponent || args.component === keystoreComponent) return respond(message.id, success({
      component: args.component, properties: [{ property: ["TestElement.enabled"], type: "boolean", default: true }],
    }))
    const data = args.diagnostics ? { ...ordinary, diagnostic_detail: "x".repeat(100) } : ordinary
    return respond(message.id, success(data))
  }
  if (name === "read") {
    if (args.properites) return respond(message.id, failure("USAGE_ERROR", "properites is unsupported", args))
    if (String(args.file).includes("compatibility-commit.jmx") && args.ref === "compat-csv") {
      return respond(message.id, success({ focus: compatibilityTree().root.children[1] }))
    }
    if (String(args.file).includes("compatibility-input.jmx") || String(args.file).includes("compatibility-commit.jmx")) {
      return respond(message.id, success(compatibilityTree(!String(args.file).includes("compatibility-input.jmx"))))
    }
    return respond(message.id, success(readData()))
  }
  if (name === "apply") {
    if (testConfig.applyPatchCaptureFile) writeFileSync(testConfig.applyPatchCaptureFile, args.patchYaml)
    if (testConfig.rejectInvalidTemplate) return respond(message.id,
      failure("USAGE_ERROR", "presence property_class items", args))
    if (String(args.file).includes("compatibility-input.jmx")) {
      if (!args.dryRun) writeFileSync(args.out, '<FloatProperty><name>qa.float</name><value>2.5</value></FloatProperty>\\n'
        + '<doubleProp><name>qa.double</name><value>3.75</value></doubleProp>\\n')
      return respond(message.id, success(compatibilityLedger(args.dryRun ? "validated" : "committed")))
    }
    if (!args.dryRun) writeFileSync(args.out, "fake output")
    return respond(message.id, success(args.dryRun ? { dryRun: true } : { output: args.out }))
  }
  if (name === "set") {
    if (String(args.file).includes("compatibility-commit.jmx")) {
      return respond(message.id, success({ writtenTarget: args.file }))
    }
    if (Array.isArray(args.value)) return respond(message.id, failure("USAGE_ERROR", "presence property_class items; use components then read", args))
    if (args.override) return respond(message.id, failure("USAGE_ERROR", "out and override are exclusive", args))
    if (args.out === args.file) return respond(message.id, failure("USAGE_ERROR", "use override", args))
    writeFileSync(args.out, "fake output")
    return respond(message.id, success({ output: args.out }))
  }
}
if (testConfig.closeStdinOnStart) {
  closeSync(0)
  setTimeout(() => process.exit(17), 1_500)
} else {
  process.stdin.setEncoding("utf8")
  process.stdin.on("data", chunk => {
    buffer += chunk
    for (;;) {
      const newline = buffer.indexOf("\\n")
      if (newline < 0) return
      const line = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 1)
      if (line) handle(JSON.parse(line))
    }
  })
}
`
}

function fakeInteractionUxJava(options) {
  return `#!/usr/bin/env node
const testConfig = ${JSON.stringify(options)}
const { appendFileSync, readFileSync, writeFileSync } = require("node:fs")
const { basename, dirname, join } = require("node:path")
const { spawn } = require("node:child_process")
const assertionGui = "org.apache.jmeter.assertions.gui.AssertionGui"
const assertionProperty = ["Asserion.test_strings"]
const testElementNameProperty = ["TestElement.name"]
const main = "io.github.thisccl.j4a.mcp.J4aMcpServer"
const argv = process.argv.slice(2)
const recursiveValue = { presence: "present", property_class: "org.apache.jmeter.testelement.property.CollectionProperty", items: [
  { type: "string", presence: "present", property_class: "org.apache.jmeter.testelement.property.StringProperty", value: "recursive" },
] }
const propertyRows = writable => [
  { property: testElementNameProperty, value: "Response Assertion", type: "string" },
  { property: assertionProperty, value: recursiveValue, type: "collection" },
  ...(writable && testConfig.interactionMutation !== "non-writable-in-writable" ? []
    : [{ property: ["HTTPSampler.auth_manager"], value: "hidden", type: "string" }]),
]
const tree = (writable, name = "Response Assertion") => ({ root: { ref: "root-ref", component: "org.apache.jmeter.control.gui.TestPlanGui", name: "Plan", enabled: true,
  properties: [{ property: testElementNameProperty, value: "Plan", type: "string" }], children: [
    { ref: "assert-ref", component: assertionGui, name, enabled: true, properties: propertyRows(writable).map(row => row.property === testElementNameProperty ? { ...row, value: name } : row) },
  ] } })
const yamlScalar = value => value === null ? "null" : typeof value === "boolean" || typeof value === "number" ? String(value) : "'" + String(value).replaceAll("'", "''") + "'"
const yamlValue = (value, indent = 0) => {
  const space = " ".repeat(indent)
  if (Array.isArray(value)) return value.map(item => item && typeof item === "object" ? space + "-\\n" + yamlValue(item, indent + 2) : space + "- " + yamlScalar(item)).join("\\n")
  return Object.entries(value).map(([key, child]) => child && typeof child === "object" ? space + key + ":\\n" + yamlValue(child, indent + 2) : space + key + ": " + yamlScalar(child)).join("\\n")
}
if (!argv.includes(main)) {
  const command = argv.slice(2)
  if (command[0] === "components") {
    const cursorIndex = command.indexOf("--cursor")
    const tokenIndex = command.indexOf("--component-token")
    if (cursorIndex >= 0 && command[cursorIndex + 1].startsWith("v3.")) {
      process.stderr.write("Components cursor version 3 is no longer accepted; restart from the first page\\n")
      process.exit(2)
    }
    if (tokenIndex >= 0) {
      if (command[tokenIndex + 1] !== "fake-component-token") {
        process.stderr.write("Invalid component token; restart category paging\\n")
        process.exit(2)
      }
      process.stdout.write(yamlValue({ component: "example.ZOversized", category: "sampler", properties: [
        { property: ["TestElement.enabled"], type: "boolean", default: true },
      ] }) + "\\n")
      process.exit(0)
    }
    const page = cursorIndex < 0
      ? { category: "sampler", projection: "authoring:scalar-array-v1", limit: 50, max_bytes: 4096,
          components: [{ component: "example.ASampler", category: "sampler", properties: [
            { property: ["TestElement.enabled"], type: "boolean", default: true },
          ] }], partial: false, next_cursor: "v4.fake-body.fake-signature" }
      : { category: "sampler", projection: "authoring:scalar-array-v1", limit: 50, max_bytes: 4096,
          components: [{ error: { code: "COMPONENT_DETAIL_TOO_LARGE", phase: "serialization", message: "large" },
            recovery: { componentToken: "fake-component-token" } }], partial: true }
    process.stdout.write(yamlValue(page) + "\\n")
    process.exit(0)
  }
  if (command[0] === "apply") {
    const patch = readFileSync(0, "utf8")
    if (patch.includes("example.invalid.UnknownComponent")) {
      process.stderr.write("changes[0] operation: add phase: materialization cause: component missing\\n")
      process.exit(3)
    }
    if (command.includes("--out")) {
      process.stderr.write("filesystem output already exists\\n")
      process.exit(4)
    }
    process.stderr.write("changes[0] schema requires ref\\n")
    process.exit(2)
  }
  const modeIndex = command.indexOf("--properties")
  const mode = modeIndex < 0 ? "key" : command[modeIndex + 1]
  if (command[0] !== "read" || mode === "none" || !["key", "all", "writable"].includes(mode)) {
    process.stderr.write("Usage error: properties must be key|all|writable\\n")
    process.exit(2)
  }
  process.stdout.write(yamlValue(tree(mode === "writable")) + "\\n")
  process.exit(0)
}
writeFileSync(testConfig.childPidFile, String(process.pid))
const worker = spawn(process.execPath, ["-e", "process.stdin.resume()"], { stdio: ["pipe", "ignore", "ignore"] })
worker.stdin.on("error", () => {})
let buffer = ""
let sourcePath = null
let fatalSeen = false
const scopes = {
  read: { source: "source-bound", target: "none", created: "none" },
  init: { source: "none", target: "target-bound", created: "none" },
  none: { source: "none", target: "none", created: "none" },
  inPlace: { source: "none", target: "none", created: "target-bound" },
  copy: { source: "source-bound", target: "none", created: "target-bound" },
}
const success = (data, ref_scope, label) => {
  const diagnostics = testConfig.interactionMutation === "masked-diagnostic" && label === "read-mcp-all"
    ? [{ code: "INTERNAL_ERROR", category: "internal", message: "masked" }] : []
  const scope = ref_scope && testConfig.interactionMutation === "ref-scope-token" && label === "read-mcp-all"
    ? { ...ref_scope, source: "root-ref" } : ref_scope
  const structuredContent = { exitStatus: 0, data, ...(scope ? { ref_scope: scope } : {}), diagnostics }
  return { isError: false, content: [{ type: "text", text: yamlValue(data) + "\\n" }], structuredContent }
}
const failure = (code, message, args, recovery) => ({ isError: true, content: [{ type: "text", text: message }], structuredContent: {
  exitStatus: 2, data: {}, diagnostics: [{ code, category: "usage", message, suggestedNextAction: "use valid inputs" }],
  recoveryGuidance: "use valid inputs", ...(recovery ? { recovery } : {}),
} })
const respond = (id, result) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\\n")
const fileName = file => {
  if (testConfig.interactionMutation === "stale-generated-payload" && basename(file) === "apply-copy.jmx") return "Response Assertion"
  try { const text = readFileSync(file, "utf8"); if (text.startsWith("name=")) return text.slice(5) } catch {}
  return "Response Assertion"
}
const writeOutput = (file, content) => {
  writeFileSync(file, content)
  appendFileSync(testConfig.mutationWriteLog, basename(file) + "\\n")
}
const handle = message => {
  if (message.method === "initialize") return respond(message.id, { protocolVersion: "2025-06-18", serverInfo: { name: "j4a" } })
  if (message.method === "shutdown") {
    if (worker.exitCode === null) worker.kill("SIGKILL")
    if (testConfig.interactionMutation === "original-hash-drift") appendFileSync(testConfig.fixture, "drift")
    if (testConfig.interactionMutation === "java-process-remains") {
      const source = join(dirname(testConfig.childPidFile), "QaJavaLeaker.java")
      writeFileSync(source, "class QaJavaLeaker { public static void main(String[] args) throws Exception { Thread.sleep(60000); } }\\n")
      writeFileSync(testConfig.leakedProcessKindFile, "java")
      spawn("/usr/bin/java", [source], { stdio: "ignore" })
    }
    return respond(message.id, null)
  }
  if (message.method === "tools/list") {
    const description = testConfig.interactionMutation === "tools-over-budget" ? "x".repeat(40_001) : "interaction contract"
    return respond(message.id, { tools: ["read", "validate", "components", "categories", "apply", "init", "set"].map(name => ({ name, description, inputSchema: { type: "object" } })) })
  }
  if (message.method !== "tools/call") return
  const { name, arguments: args } = message.params
  const label = (() => {
    if (name === "read" && args.properties === "all" && basename(args.file || args.path) === "input.jmx") return "read-mcp-all"
    if (name === "read" && args.properties === "writable") return basename(args.file || args.path) === "apply-copy.jmx" ? "read-copy-output" : "read-mcp-writable"
    if (name === "apply" && args.dryRun) return "apply-dry-run"
    return name
  })()
  if (name === "components") {
    if (!args.component && !args.kind && !args.componentToken && !args.category && !args.diagnostics) {
      return respond(message.id, success({ components: [
        { category: "sampler", component: "example.ASampler", label: "A" },
        { category: "sampler", component: "example.ZOversized", label: "Z" },
      ] }))
    }
    if (args.cursor?.startsWith("v3.")) return respond(message.id,
      failure("USAGE_ERROR", "cursor version 3 rejected; start category details again without cursor", args))
    if (args.cursor && args.maxBytes !== 4096) return respond(message.id,
      failure("USAGE_ERROR", "cursor binding mismatch; restart category", args))
    if (args.componentToken) {
      if (args.componentToken !== "fake-component-token") return respond(message.id,
        failure("USAGE_ERROR", "invalid component token; restart category", args))
      return respond(message.id, success({ component: "example.ZOversized", category: "sampler", properties: [
        { property: ["TestElement.enabled"], type: "boolean", default: true },
      ] }))
    }
    if (args.category) {
      const page = args.cursor
        ? { category: "sampler", projection: "authoring:scalar-array-v1", limit: 50, max_bytes: 4096,
            components: [{ error: { code: "COMPONENT_DETAIL_TOO_LARGE", phase: "serialization", message: "large" },
              recovery: { componentToken: "fake-component-token" } }], partial: true }
        : { category: "sampler", projection: "authoring:scalar-array-v1", limit: 50, max_bytes: 4096,
            components: [{ component: "example.ASampler", category: "sampler", properties: [
              { property: ["TestElement.enabled"], type: "boolean", default: true },
            ], ...(testConfig.interactionMutation === "category-page-over-budget"
              ? { description: "x".repeat(5000) } : {}) }], partial: false,
            next_cursor: "v4.fake-body.fake-signature" }
      return respond(message.id, success(page))
    }
    const ordinary = { component: assertionGui, properties: [{ property: assertionProperty, type: "collection", value_shape: "collection.items", value_template: recursiveValue }] }
    return respond(message.id, success(args.diagnostics ? { ...ordinary, runtime_metadata_status: "runtime-proven" } : ordinary))
  }
  if (name === "read") {
    if (args.properties === "bogus") return respond(message.id, failure("USAGE_ERROR", "properties must be none|key|all|writable", args))
    if (fatalSeen && args.ref) return respond(message.id,
      failure("MCP_REF_NOT_FOUND", "ref evicted after fatal worker loss", args))
    sourcePath ||= args.file || args.path
    return respond(message.id, success(tree(args.properties === "writable", fileName(args.file || args.path)), scopes.read, label))
  }
  if (name === "set") {
    if (Array.isArray(args.value)) return respond(message.id, failure("USAGE_ERROR", "presence property_class items", args))
    if (args.locator === "definitely-stale-ref") {
      const recoveryArgs = testConfig.interactionMutation === "recovery-missing-path" ? { jmeter_home: args.jmeter_home } : { path: args.path, jmeter_home: args.jmeter_home }
      return respond(message.id, failure("MCP_REF_NOT_FOUND", "stale ref", args, { tool: "read", arguments: recoveryArgs }))
    }
    writeOutput(args.out, "name=" + args.value)
    return respond(message.id, success({ writtenTarget: args.out }, scopes.none))
  }
  if (name === "apply") {
    if (args.patchYaml?.length > 1_000_000) {
      worker.stdin.write(args.patchYaml)
      worker.once("exit", () => {
        fatalSeen = true
        respond(message.id, { isError: true, content: [{ type: "text", text: "fatal worker loss; inspect or read target before retry" }],
          structuredContent: { exitStatus: testConfig.interactionMutation === "fatal-wrong-exit" ? 3 : 4,
            data: {}, diagnostics: [{ code: "WORKER_PROTOCOL_FAILURE",
            category: "fatal", message: "worker lost", suggestedNextAction: "inspect or read target before retry" }],
            recoveryGuidance: "inspect or read target before retry" } })
      })
      return
    }
    const scope = args.dryRun ? scopes.none : args.override ? scopes.inPlace : scopes.copy
    if (!args.dryRun) {
      const target = args.override ? args.file : args.out
      writeOutput(target, "name=QA interaction updated")
      if (testConfig.interactionMutation === "silent-retry" && basename(target) === "apply-copy.jmx") {
        writeOutput(target, "name=stale retry payload")
      }
    }
    return respond(message.id, success({ dryRun: !!args.dryRun, createdRefs: [] }, scope, label))
  }
  if (name === "init") {
    writeOutput(args.out, "name=QA initialized")
    return respond(message.id, success(tree(true, args.name), scopes.init))
  }
}
process.stdin.setEncoding("utf8")
process.stdin.on("data", chunk => {
  buffer += chunk
  for (;;) {
    const newline = buffer.indexOf("\\n")
    if (newline < 0) return
    const line = buffer.slice(0, newline); buffer = buffer.slice(newline + 1)
    if (line) handle(JSON.parse(line))
  }
})
`
}
