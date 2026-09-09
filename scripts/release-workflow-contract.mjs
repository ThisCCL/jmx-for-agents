import { readFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const PINNED_ACTIONS = Object.freeze({
  attest: "actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6",
  checkout: "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
  java: "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
  node: "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38",
  go: "actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16",
})

export function validateReleaseWorkflows({ wrapperSource, runtimeSource }) {
  const wrapper = executableSource(wrapperSource)
  const runtime = executableSource(runtimeSource)
  return [
    ...validateWrapperWorkflow(wrapper).map(error => `wrapper:${error}`),
    ...validateRuntimeWorkflow(runtime).map(error => `runtime:${error}`),
  ]
}

function validateWrapperWorkflow(source) {
  const errors = []
  if (!source.includes("tags: ['v*.*.*']") || source.includes("tags: ['runtime-v*.*.*']")) {
    errors.push("tag-trigger")
  }
  if (!source.includes("environment: npm-release")) errors.push("environment")
  if (!hasTopLevelReadPermission(source)
    || source.includes("attestations: write")
    || source.includes("contents: write")) {
    errors.push("permissions")
  }
  if (/(?:actions\/attest@|\bgh release (?:create|upload|edit|delete)\b)/.test(source)) {
    errors.push("npm-only")
  }
  for (const action of [PINNED_ACTIONS.checkout, PINNED_ACTIONS.node, PINNED_ACTIONS.java, PINNED_ACTIONS.go]) {
    if (!source.includes(`uses: ${action}`)) errors.push(`action-pin:${action.split("@")[0]}`)
  }
  if (unpinnedAction(source)) errors.push("action-sha")
  if (!source.includes("Validate the configured public runtime")
    || !source.includes('curl --fail --location --max-redirs 5 --output "$RUNNER_TEMP/configured-runtime.jar" "$RUNTIME_JAR_URL"')
    || !source.includes('"$RUNTIME_JAR_SHA256" "$RUNNER_TEMP/configured-runtime.jar" | sha256sum --check --strict')
    || !source.includes('java -jar "$RUNNER_TEMP/configured-runtime.jar" --version')) {
    errors.push("runtime-verification")
  }
  for (const required of [
    "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/main",
    "rulesets?includes_parents=true&targets=tag",
    "node scripts/release-state-machine.mjs assert-preflight",
    "go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/*.yml",
    "pnpm test",
    "pnpm run verify:public",
    "pnpm run release:prepare -- --tag \"$WRAPPER_TAG\"",
    "npm publish \"$RELEASE_TARBALL\" --access public --provenance",
  ]) {
    if (!source.includes(required)) errors.push(`command:${required}`)
  }
  if (!npmPublicationIsTerminal(source)) errors.push("npm-publication-terminal")
  return errors
}

function validateRuntimeWorkflow(source) {
  const errors = []
  if (!source.includes("tags: ['runtime-v*.*.*']") || source.includes("tags: ['v*.*.*']")) {
    errors.push("tag-trigger")
  }
  if (!hasTopLevelReadPermission(source)
    || !source.includes("attestations: write")
    || !source.includes("contents: write")
    || !source.includes("id-token: write")) {
    errors.push("permissions")
  }
  if (/\bnpm (?:publish|view|deprecate|unpublish|dist-tag)\b|NPM_BOOTSTRAP|environment:\s*npm-release/.test(source)) {
    errors.push("jar-only")
  }
  for (const action of Object.values(PINNED_ACTIONS)) {
    if (!source.includes(`uses: ${action}`)) errors.push(`action-pin:${action.split("@")[0]}`)
  }
  if (unpinnedAction(source)) errors.push("action-sha")
  if (!source.includes("apache-jmeter-5.6.3.tgz")
    || !source.includes("5978a1a35edb5a7d428e270564ff49d2b1b257a65e17a759d259a9283fc17093e522fe46f474a043864aea6910683486340706d745fcdf3db1505fd71e689083")
    || !source.includes("sha512sum --check --strict")) {
    errors.push("jmeter-runtime")
  }
  for (const required of [
    "git merge-base --is-ancestor \"$GITHUB_SHA\" origin/main",
    "rulesets?includes_parents=true&targets=tag",
    "node scripts/release-state-machine.mjs assert-preflight",
    "go run github.com/rhysd/actionlint/cmd/actionlint@v1.7.12 .github/workflows/*.yml",
    "./gradlew clean test",
    "pnpm test",
    "pnpm run verify:public",
    "./gradlew clean shadowJar",
    "sha256sum --check --strict",
    "gh api --method POST \"repos/$GITHUB_REPOSITORY/releases\"",
    "--jq '.id'",
    "gh release upload \"$RUNTIME_TAG\"",
    "gh attestation verify \"$RELEASE_JAR\" --repo \"$GITHUB_REPOSITORY\"",
    "gh release download \"$RUNTIME_TAG\"",
    "node scripts/release-state-machine.mjs assert-sha256",
  ]) {
    if (!source.includes(required)) errors.push(`command:${required}`)
  }
  if (!source.includes(`uses: ${PINNED_ACTIONS.attest}`)
    || !source.includes("subject-path: ${{ steps.producer.outputs.jar }}")
    || !source.includes("if: steps.attestation.outputs.required == 'true'")) {
    errors.push("attestation")
  }
  return errors
}

function executableSource(source) {
  if (typeof source !== "string") throw new TypeError("workflow source must be a string")
  return source
    .split(/\r?\n/)
    .filter(line => !line.trimStart().startsWith("#"))
    .join("\n")
}

function hasTopLevelReadPermission(source) {
  return /^permissions:\s*\n  contents: read$/m.test(source)
}

function unpinnedAction(source) {
  return [...source.matchAll(/^\s*uses:\s*(\S+)$/gm)].some(([, action]) => !/@[a-f0-9]{40}$/.test(action))
}

function npmPublicationIsTerminal(source) {
  const lines = source.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  const publishIndex = lines.findIndex(line => line.includes('npm publish "$RELEASE_TARBALL"'))
  if (publishIndex < 0) return false
  return lines.slice(publishIndex + 1).every(line => line === "fi")
}

async function main(args) {
  if (args.length !== 2) {
    throw new Error("usage: node scripts/release-workflow-contract.mjs <wrapper-workflow.yml> <runtime-workflow.yml>")
  }
  const [wrapperSource, runtimeSource] = await Promise.all(args.map(file => readFile(path.resolve(file), "utf8")))
  const errors = validateReleaseWorkflows({ wrapperSource, runtimeSource })
  const output = `${JSON.stringify({ errors })}\n`
  if (errors.length === 0) {
    process.stdout.write(output)
    return
  }
  process.stderr.write(output)
  process.exitCode = 1
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  try {
    await main(process.argv.slice(2))
  } catch (error) {
    process.stderr.write(`${JSON.stringify({ error: error instanceof Error ? error.message : String(error) })}\n`)
    process.exitCode = 2
  }
}
