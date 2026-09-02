import { spawnSync } from "node:child_process"

import { assert, deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { findNode, findNodes } from "./qa-agent-facing-model.mjs"
import { parseYamlDocument } from "./qa-agent-facing-yaml.mjs"

export const CATEGORY = "sampler"
export const MAX_BYTES = 4096

export async function runCategoryContract(mcp, response, context, pass) {
  const expected = await categoryIdentities(mcp, response, context.jmeterHome)
  const mcpJourney = await mcpPages(mcp, response, context.jmeterHome, expected, pass)
  const cliJourney = cliPages(context, expected)
  deepEqual(cliJourney.identities, mcpJourney.identities, "CLI/MCP page identity order")
  assert(mcpJourney.sawBudgetContinuation && cliJourney.sawBudgetContinuation,
    "CLI/MCP journeys did not exercise byte continuation")
  assert(mcpJourney.sawToken && cliJourney.sawToken, "componentToken recovery was not exercised")
  await rejectionMatrix(mcp, response, { ...context, expected }, mcpJourney, cliJourney)
  return { expected, mcpJourney, cliJourney }
}

async function categoryIdentities(mcp, response, home) {
  const listed = response.successData(await mcp.call("components", {
    jmeter_home: home,
  }, "components-list-for-category"), "components list", true)
  const category = findNode(listed, row => row.category === CATEGORY && Array.isArray(row.components))
  const identities = (category ? category.components
    : findNodes(listed, row => row.category === CATEGORY && typeof row.component === "string"))
    .map(row => row.component).sort()
  assert(identities.length > 1, "sampler catalog is unexpectedly empty")
  equal(new Set(identities).size, identities.length, "sampler catalog duplicate identities")
  return identities
}

async function mcpPages(mcp, response, home, expected, pass) {
  const state = pageState()
  let cursor
  do {
    const raw = await mcp.call("components", {
      category: CATEGORY, details: true, limit: 50, maxBytes: MAX_BYTES,
      ...(cursor ? { cursor } : {}), jmeter_home: home,
    }, `mcp-category-page-${state.pageBytes.length + 1}`)
    const page = response.successData(raw, `MCP category page ${state.pageBytes.length + 1}`, true)
    const bytes = Buffer.byteLength(raw.result.content[0].text, "utf8")
    assert(bytes <= MAX_BYTES, `MCP category page is ${bytes} bytes`)
    state.pageBytes.push(bytes)
    let pageFailure = false
    for (const entry of page.components) {
      if (entry.component) {
        assert(Array.isArray(entry.properties), "ordinary page entry is incomplete")
        state.identities.push(entry.component)
        continue
      }
      const token = exactTokenEntry(entry, state)
      const recovered = response.successData(await mcp.call("components", {
        componentToken: token, jmeter_home: home,
      }, `mcp-component-token-${state.tokenLengths.length}`), "MCP token recovery", true)
      assert(typeof recovered.component === "string" && Array.isArray(recovered.properties),
        "token recovery incomplete")
      state.identities.push(recovered.component)
      state.sawToken = true
      pageFailure = true
    }
    exactContinuation(page, pageFailure, state, "MCP")
    cursor = page.next_cursor
  } while (cursor)
  exactIdentityJourney(state, expected, "MCP")
  pass("MCP_componentToken_recovered_exact_ordinary_detail")
  return state
}

function cliPages(context, expected) {
  const state = pageState()
  let cursor
  do {
    const result = runCli(["components", "--category", CATEGORY, "--details", "true", "--limit", "50",
      "--max-bytes", String(MAX_BYTES), ...(cursor ? ["--cursor", cursor] : []),
      "--jmeter-home", context.jmeterHome], context, `cli-category-page-${state.pageBytes.length + 1}`)
    equal(result.status, 0, "CLI category page exit")
    equal(result.stderr, "", "CLI category page stderr")
    const bytes = Buffer.byteLength(result.stdout, "utf8")
    assert(bytes <= MAX_BYTES, `CLI category page is ${bytes} bytes`)
    state.pageBytes.push(bytes)
    const page = parseYamlDocument(result.stdout)
    let pageFailure = false
    for (const entry of page.components) {
      if (entry.component) {
        assert(Array.isArray(entry.properties), "CLI ordinary page entry is incomplete")
        state.identities.push(entry.component)
        continue
      }
      const token = exactTokenEntry(entry, state)
      const recoveredResult = runCli(["components", "--component-token", token,
        "--jmeter-home", context.jmeterHome], context, `cli-component-token-${state.tokenLengths.length}`)
      equal(recoveredResult.status, 0, "CLI component token recovery exit")
      equal(recoveredResult.stderr, "", "CLI component token recovery stderr")
      const recovered = parseYamlDocument(recoveredResult.stdout)
      assert(typeof recovered.component === "string" && Array.isArray(recovered.properties),
        "CLI token recovery incomplete")
      state.identities.push(recovered.component)
      state.sawToken = true
      pageFailure = true
    }
    exactContinuation(page, pageFailure, state, "CLI")
    cursor = page.next_cursor
  } while (cursor)
  exactIdentityJourney(state, expected, "CLI")
  return state
}

async function rejectionMatrix(mcp, response, context, mcpJourney, cliJourney) {
  assert(mcpJourney.firstCursor && cliJourney.firstCursor && mcpJourney.firstToken && cliJourney.firstToken,
    "paging recovery artifacts missing")
  const v3 = legacyV3Cursor(context.expected[0])
  const v3Response = await mcp.call("components", {
    category: CATEGORY, details: true, limit: 50, maxBytes: MAX_BYTES, cursor: v3,
    jmeter_home: context.jmeterHome,
  }, "reject-mcp-cursor-v3")
  response.requireUsageError(v3Response, "MCP cursor v3", ["start", "without cursor"])
  assert(JSON.stringify(v3Response).toLowerCase().includes("cursor"), "MCP v3 failure omitted cursor")
  response.requireUsageError(await mcp.call("components", {
    category: CATEGORY, details: true, limit: 50, maxBytes: 8192, cursor: mcpJourney.firstCursor,
    jmeter_home: context.jmeterHome,
  }, "reject-mcp-cursor-binding"), "MCP cursor binding", ["category"])
  response.requireUsageError(await mcp.call("components", {
    componentToken: tamper(mcpJourney.firstToken), jmeter_home: context.jmeterHome,
  }, "reject-mcp-token-tamper"), "MCP token tamper", ["category"])
  const cliV3 = runCli(["components", "--category", CATEGORY, "--details", "true", "--limit", "50",
    "--max-bytes", String(MAX_BYTES), "--cursor",
    legacyV3Cursor(context.expected[0]),
    "--jmeter-home", context.jmeterHome], context, "reject-cli-cursor-v3")
  equal(cliV3.status, 2, "CLI cursor v3 exit")
  assert(/start.*without cursor|restart|first page/i.test(cliV3.stderr), "CLI cursor v3 restart guidance missing")
  const cliTamper = runCli(["components", "--component-token", tamper(cliJourney.firstToken),
    "--jmeter-home", context.jmeterHome], context, "reject-cli-token-tamper")
  equal(cliTamper.status, 2, "CLI token tamper exit")
}

function pageState() {
  return { identities: [], pageBytes: [], cursorLengths: [], tokenLengths: [], firstCursor: undefined,
    firstToken: undefined, sawToken: false, sawBudgetContinuation: false }
}

function exactTokenEntry(entry, state) {
  const token = entry.recovery?.componentToken
  equal(entry.error?.code, "COMPONENT_DETAIL_TOO_LARGE", "oversized entry code")
  assert(typeof token === "string" && token.length > 0 && token.length <= 512, "component token bound")
  state.firstToken ||= token
  state.tokenLengths.push(token.length)
  return token
}

function exactContinuation(page, pageFailure, state, surface) {
  equal(page.partial, pageFailure, `${surface} partial semantics`)
  if (!page.next_cursor) return
  state.firstCursor ||= page.next_cursor
  assert(page.next_cursor.length <= 768, `${surface} cursor exceeds 768 ASCII bytes`)
  state.cursorLengths.push(page.next_cursor.length)
  if (!pageFailure) state.sawBudgetContinuation = true
}

function exactIdentityJourney(state, expected, surface) {
  deepEqual(state.identities, expected, `${surface} category order/gap/duplicate contract`)
  equal(new Set(state.identities).size, state.identities.length, `${surface} category duplicates`)
}

function runCli(args, context, label) {
  const command = [context.javaCommand, "-jar", context.jar, ...args]
  context.commands.push(`${label}: ${command.map(shellQuote).join(" ")}`)
  return spawnSync(context.javaCommand, ["-jar", context.jar, ...args], {
    encoding: "utf8", timeout: 120_000,
  })
}

function tamper(token) {
  const last = token.at(-1)
  return `${token.slice(0, -1)}${last === "A" ? "B" : "A"}`
}

function legacyV3Cursor(lastComponent) {
  const payload = ["legacy-runtime-digest", CATEGORY, "authoring:scalar-array-v1", "50", lastComponent].join("\n")
  return `v3.${Buffer.from(payload, "utf8").toString("base64url")}.legacy-signature`
}

function shellQuote(value) {
  return `'${String(value).replaceAll("'", "'\\''")}'`
}
