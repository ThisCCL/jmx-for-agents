import { readFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const ACTIONS = Object.freeze({
  attest: "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6",
  checkout: "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
  java: "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
  node: "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38",
  go: "actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16",
})
const JMETER_ARCHIVE = "apache-jmeter-5.6.3.tgz"
const JMETER_SHA512 = "5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083"
const JMETER_PRIMARY_URL = `https://downloads.apache.org/jmeter/binaries/${JMETER_ARCHIVE}`
const JMETER_FALLBACK_URL = `https://archive.apache.org/dist/jmeter/binaries/${JMETER_ARCHIVE}`
const PUBLIC_RELEASE_DOWNLOAD = 'curl --fail --location --max-redirs 5 --output "$RUNNER_TEMP/public-release.jar"'

export function parseReleaseWorkflow(source) {
  const workflow = {
    actions: [],
    actionInputs: {},
    concurrency: {},
    events: [],
    permissions: {},
    pushTags: [],
    release: { permissions: {}, runs: [], steps: [] },
  }
  let section = ""
  let event = ""
  let activeAction = ""
  let activePermissions
  let permissionIndent = -1
  let activeRun
  let runIndent = -1
  let runLines = []
  let activeStep

  const finishRun = () => {
    if (activeRun !== undefined) {
      activeRun.command = runLines.join("\n")
      workflow.release.runs.push(activeRun)
    }
    activeRun = undefined
    runIndent = -1
    runLines = []
  }
  const parseMapLine = line => {
    const match = line.match(/^([a-z-]+):\s*(\S.*)$/)
    return match === null ? null : [match[1], match[2].replace(/^['"]|['"]$/g, "")]
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
    if (line === "" || line.startsWith("#")) continue
    if (indent === 0) {
      if (line === "permissions:") {
        activePermissions = {}
        workflow.permissions = activePermissions
        permissionIndent = indent
        continue
      }
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
    if (section === "on" && event === "push" && line.startsWith("tags:")) {
      workflow.pushTags = parseList(line.slice("tags:".length))
      continue
    }
    if (line === "permissions:") {
      activePermissions = {}
      if (indent === 0) workflow.permissions = activePermissions
      if (indent === 4) workflow.release.permissions = activePermissions
      permissionIndent = indent
      continue
    }
    if (activePermissions !== undefined && indent > permissionIndent) {
      const entry = parseMapLine(line)
      if (entry !== null) activePermissions[entry[0]] = entry[1]
      continue
    }
    if (section === "concurrency") {
      const entry = parseMapLine(line)
      if (entry !== null) workflow.concurrency[toCamel(entry[0])] = entry[1] === "false" ? false : entry[1]
      continue
    }
    if (section === "jobs" && indent === 4 && line.startsWith("environment:")) {
      workflow.release.environment = line.slice("environment:".length).trim()
      continue
    }
    if (section === "jobs" && indent === 6 && line.startsWith("- name:")) {
      activeStep = { name: line.slice("- name:".length).trim(), command: "" }
      workflow.release.steps.push(activeStep)
      continue
    }
    if (section === "jobs" && indent === 8 && line.startsWith("if:") && activeStep !== undefined) {
      activeStep.condition = line.slice("if:".length).trim()
      continue
    }
    const uses = line.match(/^uses:\s*(\S+)$/)
    if (uses !== null) {
      activeAction = uses[1]
      workflow.actions.push(activeAction)
      workflow.actionInputs[activeAction] = {}
      if (activeStep !== undefined) activeStep.action = activeAction
      continue
    }
    const input = parseMapLine(line)
    if (activeAction !== "" && input !== null && indent >= 8) {
      workflow.actionInputs[activeAction][input[0]] = input[1]
    }
    const run = line.match(/^run:\s*(.*)$/)
    if (run !== null) {
      activeRun = activeStep ?? { name: "", command: "" }
      if (run[1] === "|") {
        runIndent = indent
      } else {
        activeRun.command = run[1]
        workflow.release.runs.push(activeRun)
        activeRun = undefined
      }
    }
  }
  finishRun()
  return workflow
}

export function validateReleaseWorkflow(source) {
  const workflow = parseReleaseWorkflow(source)
  const errors = []
  const commands = workflow.release.runs.flatMap(run => run.command.split("\n").map(line => line.trim()).filter(Boolean))
  const actionByName = Object.fromEntries(workflow.actions.map(action => [action.split("@")[0], action]))
  const command = text => commands.some(line => line.includes(text))
  const index = text => workflow.release.steps.findIndex(step => step.command.includes(text) || step.action === text)
  const releaseQueryStep = workflow.release.steps.find(step => step.command.includes("npm view \"$PACKAGE_NAME@$RELEASE_VERSION\""))
  const releaseCreateStep = workflow.release.steps.find(step => step.command.includes("gh release create \"$RELEASE_TAG\" --draft"))

  if (JSON.stringify(workflow.events) !== JSON.stringify(["push"]) || JSON.stringify(workflow.pushTags) !== JSON.stringify(["v*.*.*"])) errors.push("tag-trigger")
  if (JSON.stringify(workflow.permissions) !== JSON.stringify({ contents: "read" })) errors.push("default-permissions")
  if (workflow.concurrency.group !== "release-${{ github.ref_name }}" || workflow.concurrency.cancelInProgress !== false) errors.push("concurrency")
  if (workflow.release.environment !== "npm-release") errors.push("environment")
  if (JSON.stringify(workflow.release.permissions) !== JSON.stringify({ attestations: "write", contents: "write", "id-token": "write" })) errors.push("release-permissions")
  for (const expected of Object.values(ACTIONS)) {
    const name = expected.split("@")[0]
    if (actionByName[name] !== expected) errors.push(`action-pin:${name}`)
  }
  if (workflow.actions.some(action => !/@[a-f0-9]{40}$/.test(action))) errors.push("action-sha")
  if (workflow.actionInputs[ACTIONS.node]?.["node-version"] !== "24.11.1") errors.push("node-version")
  if (workflow.actionInputs[ACTIONS.java]?.distribution !== "temurin" || workflow.actionInputs[ACTIONS.java]?.["java-version"] !== "8") errors.push("java-version")
  if (workflow.actionInputs[ACTIONS.go]?.["go-version"] !== "1.26") errors.push("go-version")
  if (workflow.actionInputs[ACTIONS.go]?.cache !== "false") errors.push("go-cache")
  if (workflow.actionInputs[ACTIONS.checkout]?.["fetch-depth"] !== "0") errors.push("checkout-history")
  if (workflow.actionInputs[ACTIONS.attest]?.["subject-path"] !== "${{ steps.producer.outputs.jar }}") errors.push("attestation-subject")
  const attestStep = workflow.release.steps.find(step => step.action === ACTIONS.attest)
  const attestationCheck = workflow.release.steps.find(step => step.name === "Detect an existing JAR attestation")
  if (attestStep?.condition !== "steps.attestation.outputs.required == 'true'" || attestationCheck === undefined
    || !attestationCheck.command.includes("No attestations found for subject")
    || !attestationCheck.command.includes('HTTP 404: Not Found (https://api.github.com/repos/$GITHUB_REPOSITORY/attestations/sha256:')) {
    errors.push("attestation-reuse")
  }
  if (!command("git merge-base --is-ancestor \"$GITHUB_SHA\" origin/main")) errors.push("main-reachability")
  if (!command("./gradlew properties --quiet") || command("sed -n \"s/^version =")) errors.push("gradle-version-source")
  if (!command("preflight_fail()")
    || commands.filter(line => line.includes("|| preflight_fail") || line.includes("preflight_fail \"")).length < 8) {
    errors.push("preflight-diagnostics")
  }
  const jmeterArchives = [...new Set(commands.flatMap(line => line.match(/apache-jmeter-[0-9.]+\.tgz/g) ?? []))]
  if (JSON.stringify(jmeterArchives) !== JSON.stringify([JMETER_ARCHIVE])
    || !command(JMETER_PRIMARY_URL)
    || !command(JMETER_FALLBACK_URL)
    || !command(JMETER_SHA512)
    || !command('echo "JMETER_HOME=$RUNNER_TEMP/apache-jmeter-5.6.3" >> "$GITHUB_ENV"')) {
    errors.push("jmeter-runtime")
  }
  if (!command("rulesets?includes_parents=true&targets=tag") || !command("node scripts/release-state-machine.mjs assert-preflight")) errors.push("ruleset-preflight")
  if (!command('gh api "repos/$GITHUB_REPOSITORY/rulesets/$RULESET_ID"')
    || !command("jq --slurp '.' > \"$RUNNER_TEMP/rulesets.json\"")) errors.push("ruleset-details")
  for (const required of [
    "corepack prepare pnpm@11.5.1 --activate",
    "pnpm install --frozen-lockfile",
    "go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/*.yml",
    "./gradlew clean test",
    "pnpm test",
    "pnpm run verify:public",
    "pnpm run release:prepare -- --tag \"$RELEASE_TAG\"",
    "node scripts/ci-inspect-release.mjs",
    "npm view \"$PACKAGE_NAME@$RELEASE_VERSION\" dist.integrity --json",
    "gh release create \"$RELEASE_TAG\" --draft",
    "gh release upload \"$RELEASE_TAG\"",
    "gh attestation verify \"$RELEASE_JAR\" --repo \"$GITHUB_REPOSITORY\"",
    "gh release download \"$RELEASE_TAG\"",
    PUBLIC_RELEASE_DOWNLOAD,
    "npm publish \"$RELEASE_TARBALL\" --access public --provenance",
    "printf '%s%s' 'NODE_AUTH_' 'TOKEN'",
  ]) {
    if (!command(required)) errors.push(`command:${required}`)
  }
  for (const step of [releaseQueryStep, releaseCreateStep]) {
    if (step === undefined
      || !step.command.includes('repos/$GITHUB_REPOSITORY/releases?per_page=100')
      || !step.command.includes("node scripts/release-state-machine.mjs select-release")
      || step.command.includes("releases/tags/$RELEASE_TAG")) {
      errors.push("draft-release-discovery")
      break
    }
  }
  if (commands.filter(line => line.includes("node scripts/release-state-machine.mjs assert-sha256")).length !== 2) {
    errors.push("digest-comparison")
  }
  if (commands.some(line => line.includes("--clobber"))) errors.push("no-clobber")
  const publicIndex = index(PUBLIC_RELEASE_DOWNLOAD)
  const publishIndex = index("npm publish \"$RELEASE_TARBALL\"")
  const attestIndex = index(ACTIONS.attest)
  if (publicIndex < 0 || publishIndex < 0 || publicIndex >= publishIndex || attestIndex < 0 || attestIndex >= publishIndex) errors.push("publication-order")
  const publishStep = workflow.release.steps[publishIndex]
  const publishLines = publishStep?.command.split("\n") ?? []
  const publishLineIndex = publishLines.findIndex(line => line.includes('npm publish "$RELEASE_TARBALL"'))
  const commandsAfterPublish = publishLines.slice(publishLineIndex + 1).filter(line => line !== "fi")
  if (publishIndex !== workflow.release.steps.length - 1
    || publishLineIndex < 0
    || commandsAfterPublish.length > 0) {
    errors.push("npm-publication-terminal")
  }
  return errors
}

function parseList(value) {
  return value.trim().replace(/^\[/, "").replace(/\]$/, "").split(",").map(item => item.trim().replace(/^['"]|['"]$/g, "")).filter(Boolean)
}

function toCamel(value) {
  return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())
}

async function main(args) {
  if (args.length !== 1) throw new Error("usage: node scripts/release-workflow-contract.mjs <workflow.yml>")
  const errors = validateReleaseWorkflow(await readFile(path.resolve(args[0]), "utf8"))
  const output = `${JSON.stringify({ errors })}\n`
  if (errors.length > 0) {
    process.stderr.write(output)
    process.exitCode = 1
    return
  }
  process.stdout.write(output)
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  try {
    await main(process.argv.slice(2))
  } catch (error) {
    process.stderr.write(`${JSON.stringify({ error: error instanceof Error ? error.message : String(error) })}\n`)
    process.exitCode = 2
  }
}
