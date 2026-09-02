export function assert(condition, message) {
  if (!condition) throw new Error(message)
}

export function equal(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
  }
}

export function deepEqual(actual, expected, label) {
  if (JSON.stringify(actual) !== JSON.stringify(expected)) {
    throw new Error(`${label}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`)
  }
}

export function createResponseAssertions(summary, parseYamlDocument) {
  const successData = (response, label, requireYamlParity = false) => {
    const result = response.result
    assert(result && result.isError === false, `${label} failed: ${JSON.stringify(result)}`)
    deepEqual(Object.keys(result).sort(), ["content", "isError", "structuredContent"], `${label} result fields`)
    assert(Array.isArray(result.content) && result.content.length > 0, `${label} missing content`)
    assert(result.structuredContent?.exitStatus === 0, `${label} missing successful structuredContent`)
    deepEqual(result.structuredContent.diagnostics, [], `${label} success diagnostics`)
    assert(result.structuredContent.data && typeof result.structuredContent.data === "object",
      `${label} missing structured data`)
    if (requireYamlParity) {
      const text = result.content[0]?.text
      assert(typeof text === "string", `${label} missing YAML text content`)
      deepEqual(parseYamlDocument(text), result.structuredContent.data,
        `${label} content YAML must equal structured data`)
      summary.parityLabels.push(label)
    }
    return result.structuredContent.data
  }

  const actionableText = response => {
    const structured = response.result?.structuredContent || {}
    return [
      ...(structured.diagnostics || []).flatMap(item => [item.message, item.suggestedNextAction]),
      structured.recoveryGuidance,
    ].filter(Boolean).join(" ")
  }

  const requireToolError = (response, label) => {
    const result = response.result
    assert(result?.isError === true, `${label} unexpectedly succeeded`)
    assert(Array.isArray(result.content) && result.content.length > 0, `${label} missing content`)
    assert(result.structuredContent?.exitStatus !== 0, `${label} missing error structuredContent`)
    assert(Array.isArray(result.structuredContent?.diagnostics) && result.structuredContent.diagnostics.length > 0,
      `${label} missing diagnostics`)
    assert(actionableText(response).trim().length > 0, `${label} missing actionable error text`)
    return result
  }

  const requireUsageError = (response, label, fragments) => {
    const result = requireToolError(response, label)
    const diagnostic = result.structuredContent.diagnostics[0]
    equal(diagnostic.code, "USAGE_ERROR", `${label} diagnostic code`)
    equal(diagnostic.category, "usage", `${label} diagnostic category`)
    const text = actionableText(response)
    for (const fragment of fragments) {
      assert(text.includes(fragment), `${label} must mention ${fragment}: ${text}`)
    }
    assert(typeof (diagnostic.suggestedNextAction || result.structuredContent.recoveryGuidance) === "string",
      `${label} missing MCP-native next action`)
  }

  return { actionableText, requireToolError, requireUsageError, successData }
}
