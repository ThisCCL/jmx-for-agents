import { createHash } from "node:crypto"

const STRUCTURAL_ARTIFACT_KEYS = new Set([
  "$defs", "$ref", "additionalProperties", "address", "affectedFile", "alias", "allOf", "anyOf", "appliedCount",
  "arguments", "argumentsValue", "authorizationsValue", "base_digest", "booleanValue", "capabilities", "category",
  "changedProperty", "children", "clientInfo", "code", "collectionValue", "command", "component", "const", "content",
  "cookiesValue", "count", "created", "createdRefs", "data", "default", "deletedRefs", "depth", "description", "details", "diagnostics",
  "diagnostic", "dns_hostsValue", "dns_serversValue", "domain", "doubleValue", "dryRun", "elementValue", "element_class", "else",
  "enabled", "end_line", "entries", "enum", "exitStatus", "expires", "file", "floatValue", "focus", "forceOut",
  "format", "headersValue", "http_filesValue", "id", "if", "includeDisabledDetails", "inputSchema", "intValue", "isError",
  "items", "jmeter_home", "jsonrpc", "key", "kind", "label", "listChanged", "locator", "longValue", "mapKey", "mapValue",
  "mechanism", "message", "metadata", "method", "mimetype", "minLength", "minimum", "name", "nested", "not", "nullValue", "oneOf",
  "opaqueValue", "out", "outer_property_class", "output", "override", "ownership", "paramname", "params", "password",
  "patchFile", "patchYaml", "path", "pattern", "payload", "presence", "properites", "properties", "property", "propertyDocument",
  "propertyPath", "property_class", "protocolVersion", "realm", "reason", "recovery", "recoveryGuidance", "recursiveValue", "ref", "ref_scope",
  "representation_source", "required", "required_property_class", "required_value_class", "result", "root", "row_type", "rows",
  "runtime_fingerprint", "runtime_metadata_status", "secure", "serverInfo", "source", "start_line", "stringValue", "structuredContent", "suggestedNextAction",
  "target", "text", "then", "threadGroupName", "tool", "tools", "type", "url", "user", "value", "value_shape", "value_template", "version",
  "writable", "writeMode", "writtenTarget",
])

export function redactRecord(record) {
  return { label: record.label, message: redact(record.message) }
}

export function redact(value, path = []) {
  if (Array.isArray(value)) return value.map(item => redact(item, path))
  if (!value || typeof value !== "object") {
    if (typeof value !== "string") return value
    return redactString(value, path.at(-1), path)
  }
  return Object.fromEntries(Object.entries(value).map(([childKey, child]) => [
    redactKey(childKey, path),
    redact(child, [...path, childKey]),
  ]))
}

function redactKey(key, path) {
  if (STRUCTURAL_ARTIFACT_KEYS.has(key) || schemaPropertyKey(path)) return key
  return `[REDACTED_KEY sha256=${sha256Text(key)}]`
}

function schemaPropertyKey(path) {
  return path.includes("inputSchema") && ["$defs", "properties"].includes(path.at(-1))
}

export function redactString(value, key = "", path = []) {
  if (isStableProtocolString(key, path)) return value
  const kind = ["file", "path", "out", "output", "affectedFile", "jmeter_home"].includes(key)
    ? "PATH"
    : ["patchYaml", "patchFile", "payload", "value"].includes(key)
      ? "VALUE"
      : key === "text" ? "TEXT" : "STRING"
  return `[REDACTED_${kind} sha256=${sha256Text(value)}${kind === "TEXT" ? ` chars=${value.length}` : ""}]`
}

export function failureReceipt(error, step) {
  const message = error instanceof Error ? error.message : String(error)
  return `QA failure code=QA_DRIVER_FAILURE class=${error instanceof Error ? "Error" : typeof error} step=${step} detail=${redactString(message, "failure")}`
}

function isStableProtocolString(key, path) {
  if ([
    "jsonrpc", "method", "protocolVersion", "component", "property", "property_class", "value_shape",
    "row_type", "presence", "type", "code", "category", "propertyPath", "command", "format",
    "label", "case", "assertions", "parityLabels", "properties", "runtime_metadata_status", "source", "target", "created", "tool",
  ].includes(key)) return true
  if (key !== "name") return false
  return path.at(-2) === "params" || path.includes("tools") || path.includes("serverInfo")
}

export function sha256Text(value) {
  return createHash("sha256").update(String(value)).digest("hex")
}
