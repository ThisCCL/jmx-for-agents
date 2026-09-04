const REQUIRED_ACTIONS = Object.freeze([
  "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
  "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38",
  "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
  "actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16",
])

const REQUIRED_COMMANDS = Object.freeze([
  "corepack prepare pnpm@11.5.1 --activate",
  "pnpm install --frozen-lockfile",
  "go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/ci.yml",
  "./gradlew clean test",
  "pnpm test",
  "pnpm run verify:public",
  "pnpm run release:prepare -- --tag v$(node -p \"require('./package.json').version\")",
  "node scripts/ci-inspect-release.mjs",
  "node test/qa/release-rehearsal.mjs",
  "git diff --exit-code",
])

const JMETER_ARCHIVE = "apache-jmeter-5.6.3.tgz"
const JMETER_SHA512 = "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
const CANONICAL_JMETER_RUN = [
  `curl --fail --location --retry 3 --output "$RUNNER_TEMP/${JMETER_ARCHIVE}" https://archive.apache.org/dist/jmeter/binaries/${JMETER_ARCHIVE}`,
  `printf '%s  %s\\n' '${JMETER_SHA512}' "$RUNNER_TEMP/${JMETER_ARCHIVE}" | sha512sum --check --strict`,
  `tar -xzf "$RUNNER_TEMP/${JMETER_ARCHIVE}" -C "$RUNNER_TEMP"`,
  `echo "JMETER_HOME=$RUNNER_TEMP/${JMETER_ARCHIVE.slice(0, -4)}" >> "$GITHUB_ENV"`,
].join("\n")

export function parseCiWorkflow(source) {
  const workflow = {
    actions: [],
    actionInputs: {},
    events: [],
    permissions: {},
    permissionScopes: [],
    pushBranches: [],
    runs: [],
  }
  let section = ""
  let event = ""
  let activeAction = ""
  let activePermissions
  let permissionIndent = -1
  let runIndent = -1
  let runLines = []

  const finishRun = () => {
    if (runIndent >= 0) workflow.runs.push(runLines.join("\n"))
    runIndent = -1
    runLines = []
  }

  for (const rawLine of source.split(/\r?\n/)) {
    const indent = rawLine.match(/^\s*/)[0].length
    const line = rawLine.trim()
    if (runIndent >= 0 && (line === "" || indent > runIndent)) {
      if (line !== "" && !line.startsWith("#")) runLines.push(line)
      continue
    }
    finishRun()
    if (permissionIndent >= 0 && indent <= permissionIndent) {
      activePermissions = undefined
      permissionIndent = -1
    }
    if (line === "permissions:") {
      activePermissions = {}
      workflow.permissionScopes.push(activePermissions)
      if (indent === 0) workflow.permissions = activePermissions
      permissionIndent = indent
      continue
    }
    if (activePermissions !== undefined && indent > permissionIndent) {
      const match = line.match(/^([a-z-]+):\s*(\S+)$/)
      if (match !== null) activePermissions[match[1]] = match[2]
      continue
    }
    if (line === "" || line.startsWith("#")) continue
    if (indent === 0) {
      section = line.endsWith(":") ? line.slice(0, -1) : ""
      event = ""
      activeAction = ""
      continue
    }
    if (section === "on" && indent === 2 && line.endsWith(":")) {
      event = line.slice(0, -1)
      workflow.events.push(event)
      continue
    }
    if (section === "on" && event === "push" && line.startsWith("branches:")) {
      const branches = line.slice("branches:".length).trim().replace(/^\[/, "").replace(/\]$/, "")
      workflow.pushBranches = branches.split(",").map(value => value.trim()).filter(Boolean)
      continue
    }
    const uses = line.match(/^-\s+uses:\s*(\S+)$/)
    if (uses !== null) {
      activeAction = uses[1]
      workflow.actions.push(activeAction)
      workflow.actionInputs[activeAction] = {}
      continue
    }
    const input = line.match(/^([a-z-]+):\s*(\S.*)$/)
    if (activeAction !== "" && input !== null && indent >= 8) {
      workflow.actionInputs[activeAction][input[1]] = input[2].replace(/^['"]|['"]$/g, "")
    }
    const run = line.match(/^run:\s*(.*)$/)
    if (run !== null) {
      if (run[1] === "|") {
        runIndent = indent
      } else {
        workflow.runs.push(run[1])
      }
    }
  }
  finishRun()
  return workflow
}

export function validateCiWorkflow(source) {
  const workflow = parseCiWorkflow(source)
  const errors = []
  const commands = workflow.runs.flatMap(run => run.split("\n").map(line => line.trim()).filter(Boolean))
  const actionByName = Object.fromEntries(workflow.actions.map(action => [action.split("@")[0], action]))
  const setupNode = actionByName["actions/setup-node"]
  const setupJava = actionByName["actions/setup-java"]
  const setupGo = actionByName["actions/setup-go"]

  if (JSON.stringify(workflow.events) !== JSON.stringify(["pull_request", "push"]) || JSON.stringify(workflow.pushBranches) !== JSON.stringify(["main"])) {
    errors.push("triggers")
  }
  if (workflow.permissionScopes.length !== 1 || JSON.stringify(workflow.permissions) !== JSON.stringify({ contents: "read" })) errors.push("permissions")
  for (const required of REQUIRED_ACTIONS) {
    const name = required.split("@")[0]
    if (actionByName[name] !== required) errors.push(`action-pin:${name}`)
  }
  if (workflow.actions.some(action => !/@[a-f0-9]{40}$/.test(action))) errors.push("action-sha")
  if (workflow.actionInputs[setupNode]?.["node-version"] !== "24.11.1") errors.push("node-version")
  if (workflow.actionInputs[setupJava]?.distribution !== "temurin" || workflow.actionInputs[setupJava]?.["java-version"] !== "8") errors.push("java-version")
  if (workflow.actionInputs[setupGo]?.["go-version"] !== "1.26") errors.push("go-version")
  if (workflow.actionInputs[setupGo]?.cache !== "false") errors.push("go-cache")
  for (const command of REQUIRED_COMMANDS) {
    if (!commands.includes(command)) errors.push(`command:${command}`)
  }
  const jmeterArchives = new Set(
    Array.from(source.matchAll(/apache-jmeter-[0-9.]+\.tgz/g), match => match[0]),
  )
  const jmeterRun = workflow.runs.find(run => run.includes(JMETER_ARCHIVE))
  const jmeterRuns = workflow.runs.filter(run => /jmeter/i.test(run))
  const hasEnvironmentMap = source.split(/\r?\n/).some(line => line.trim() === "env:")
  if (jmeterArchives.size !== 1
    || !jmeterArchives.has(JMETER_ARCHIVE)
    || jmeterRuns.length !== 1
    || jmeterRuns[0] !== jmeterRun
    || jmeterRun !== CANONICAL_JMETER_RUN
    || hasEnvironmentMap) {
    errors.push("jmeter-runtime-set")
  }
  const checksumIndex = jmeterRun?.indexOf("sha512sum --check --strict") ?? -1
  const extractIndex = jmeterRun?.indexOf("tar -xzf") ?? -1
  if (jmeterRun === undefined
    || !jmeterRun.includes(`https://archive.apache.org/dist/jmeter/binaries/${JMETER_ARCHIVE}`)
    || !jmeterRun.includes(JMETER_SHA512)
    || checksumIndex < 0
    || extractIndex < 0
    || checksumIndex > extractIndex) {
    errors.push("jmeter-checksum-before-extract")
  }
  return errors
}

async function main() {
  const args = process.argv.slice(2)
  if (args.length !== 1) {
    process.stderr.write(`${JSON.stringify({ error: "usage: node scripts/ci-workflow-contract.mjs <workflow.yml>" })}\n`)
    process.exitCode = 2
    return
  }
  let source
  try {
    source = await readFile(path.resolve(args[0]), "utf8")
  } catch (error) {
    const message = error instanceof Error ? error.message : "unable to read workflow"
    process.stderr.write(`${JSON.stringify({ error: message })}\n`)
    process.exitCode = 2
    return
  }
  const errors = validateCiWorkflow(source)
  const output = `${JSON.stringify({ errors })}\n`
  if (errors.length === 0) {
    process.stdout.write(output)
    return
  }
  process.stderr.write(output)
  process.exitCode = 1
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  await main()
}
import { readFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"
