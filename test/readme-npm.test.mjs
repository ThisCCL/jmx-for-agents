import assert from "node:assert/strict"
import { readFile } from "node:fs/promises"
import test from "node:test"

const readmeFiles = await Promise.all([
  readFile("README.md", "utf8"),
  readFile("README.zh-CN.md", "utf8"),
  readFile("CONTRIBUTING.md", "utf8"),
])

function section(readme, heading) {
  const marker = `## ${heading}`
  const start = readme.indexOf(`${marker}\n`)
  assert.notEqual(start, -1, `missing README section: ${heading}`)
  const contentStart = start + marker.length + 1
  const nextHeading = readme.indexOf("\n## ", contentStart)
  return readme.slice(contentStart, nextHeading === -1 ? readme.length : nextHeading)
}

test("the bilingual READMEs are linked and describe the user-facing package", () => {
  const [english, chinese] = readmeFiles

  assert.match(english, /\[简体中文\]\(README\.zh-CN\.md\)/)
  assert.match(chinese, /\[English\]\(README\.md\)/)
  assert.match(english, /@jmx-for-agents\/j4a/)
  assert.match(chinese, /@jmx-for-agents\/j4a/)
  assert.match(english, /Apache-2\.0.*LICENSE|LICENSE.*Apache-2\.0/s)
  assert.match(chinese, /Apache-2\.0.*LICENSE|LICENSE.*Apache-2\.0/s)
  for (const readme of [english, chinese]) {
    assert.doesNotMatch(readme, /img\.shields\.io/)
  }
})

test("README gives users one install, an MCP-first quickstart, and a CLI alternative", () => {
  const [english, chinese] = readmeFiles

  for (const [readme, quickstartHeading, cliHeading] of [
    [english, "Quickstart", "CLI alternative"],
    [chinese, "快速开始", "CLI 替代方案"],
  ]) {
    const quickstart = section(readme, quickstartHeading)
    const cliAlternative = section(readme, cliHeading)

    assert.ok(
      readme.indexOf(`## ${quickstartHeading}`) < readme.indexOf(`## ${cliHeading}`),
      "the CLI alternative must follow Quickstart",
    )
    assert.match(readme, /npx -y @jmx-for-agents\/j4a install --with-skills/)
    assert.match(quickstart, /npx -y @jmx-for-agents\/j4a mcp/)
    assert.doesNotMatch(quickstart, /Codex|codex/)
    assert.doesNotMatch(quickstart, /npx -y @jmx-for-agents\/j4a (?:init|validate)\b/)
    assert.match(cliAlternative, /npx -y @jmx-for-agents\/j4a init j4a-first-plan\.jmx/)
    assert.match(cliAlternative, /npx -y @jmx-for-agents\/j4a validate j4a-first-plan\.jmx/)
    assert.ok(
      readme.indexOf("npx -y @jmx-for-agents/j4a install --with-skills")
        < readme.indexOf("npx -y @jmx-for-agents/j4a mcp"),
      "skill installation must appear before the MCP launch command",
    )
    assert.match(quickstart, /j4a-first-plan\.jmx/)
    assert.match(readme, /JMX_AGENT_JMETER_HOME|JMETER_HOME/)
    assert.match(readme, /Node(?:\.js)?(?: 18| 18 或更高版本)|Node\.js 18 or later/i)
    assert.match(readme, /Java 8/)
    assert.match(readme, /JMeter 5\.6\.3/)
    for (const command of ["read", "components", "categories", "init", "set", "apply", "validate"]) {
      assert.match(readme, new RegExp(`\\b${command}\\b`), `missing documented command: ${command}`)
    }
    assert.doesNotMatch(readme, /npm install -g @jmx-for-agents\/j4a/)
  }
})

test("maintainer details live in CONTRIBUTING and exact authoring details stay out of README", () => {
  const [english, chinese, contributing] = readmeFiles

  for (const readme of [english, chinese]) {
    assert.match(readme, /\[j4a-master .+\]\(skills\/j4a-master\/SKILL\.md\)/)
    assert.match(readme, /\[CONTRIBUTING\.md\]\(CONTRIBUTING\.md\)/)
    assert.match(readme, /\[RELEASE\.md\]\(RELEASE\.md\)/)
    assert.doesNotMatch(readme, /J4A_CACHE_DIR|J4A_JAVA_COMMAND|SHA-256|postinstall/)
    assert.doesNotMatch(readme, /## (?:Project layout|项目结构|Development|开发|Release|发布)/)
    assert.doesNotMatch(readme, /properties: writable|none\|key\|all\|writable|cursor v[34]|退出 [234]/i)
  }

  assert.match(contributing, /## Development requirements/)
  assert.match(contributing, /## Project layout/)
  assert.match(contributing, /## Runtime delivery/)
  assert.match(contributing, /JDK 8 toolchain/)
  assert.match(contributing, /J4A_CACHE_DIR/)
  assert.match(contributing, /J4A_JAVA_COMMAND/)
  assert.match(contributing, /SHA-256/)
  assert.match(contributing, /postinstall/)
  assert.match(contributing, /\[RELEASE\.md\]\(RELEASE\.md\)/)
})
