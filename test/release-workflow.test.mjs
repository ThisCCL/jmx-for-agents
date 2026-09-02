import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { promisify } from "node:util"

import {
  assertSha256Digest,
  executeReleaseOperations,
  planReleaseOperations,
} from "../scripts/release-state-machine.mjs"
import {
  parseReleaseWorkflow,
  validateReleaseWorkflow,
} from "../scripts/release-workflow-contract.mjs"

const execFileAsync = promisify(execFile)
const workflowPath = path.resolve(".github", "workflows", "release.yml")

async function workflowSource() {
  return readFile(workflowPath, "utf8")
}

function releaseInput(overrides = {}) {
  return {
    tag: "v1.0.0",
    version: "1.0.0",
    mainReachable: true,
    tagProtected: true,
    lockAcquired: true,
    release: null,
    npmIntegrity: null,
    npmAuthentication: "trusted",
    assets: [
      { name: "j4a-1.0.0.jar", sha256: "a".repeat(64) },
      { name: "j4a-1.0.0.jar.sha256", sha256: "b".repeat(64) },
    ],
    tarball: { name: "jmx-for-agents-j4a-1.0.0.tgz", integrity: "sha512-local" },
    ...overrides,
  }
}

async function runAttestationDetection(result) {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-attestation-detection-"))
  const bin = path.join(root, "bin")
  const output = path.join(root, "github-output")
  const jar = path.join(root, "j4a-1.0.0.jar")
  await mkdir(bin)
  await writeFile(jar, "release bytes")
  const gh = path.join(bin, "gh")
  await writeFile(gh, `#!/usr/bin/env bash
set -euo pipefail
test "$1" = attestation
test "$2" = verify
case "\${FAKE_ATTESTATION_RESULT:?}" in
  present) printf 'verification succeeded\\n' ;;
  absent) printf 'HTTP 404: Not Found (https://api.github.com/repos/ThisCCL/jmx-for-agents/attestations/sha256:${"a".repeat(64)}?per_page=30)\\n' >&2; exit 1 ;;
  denied) printf 'HTTP 403: Resource not accessible by integration\\n' >&2; exit 1 ;;
esac
`)
  await chmod(gh, 0o755)
  const source = await workflowSource()
  const command = parseReleaseWorkflow(source).release.steps.find(
    step => step.name === "Detect an existing JAR attestation",
  )?.command
  assert.ok(command)
  try {
    await execFileAsync("bash", ["-c", command], {
      env: {
        ...process.env,
        FAKE_ATTESTATION_RESULT: result,
        GH_AUTH: "test-token",
        GITHUB_OUTPUT: output,
        GITHUB_REPOSITORY: "ThisCCL/jmx-for-agents",
        PATH: `${bin}:${process.env.PATH}`,
        RELEASE_JAR: jar,
        RUNNER_TEMP: root,
      },
    })
    return await readFile(output, "utf8")
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

test("release state machine creates all absent objects in the only permitted order", () => {
  const operations = planReleaseOperations(releaseInput())

  assert.deepEqual(operations, [
    "create-draft-release",
    "upload:j4a-1.0.0.jar",
    "upload:j4a-1.0.0.jar.sha256",
    "attest-jar",
    "verify-authenticated-assets",
    "publish-release",
    "verify-public-download",
    "publish-npm:jmx-for-agents-j4a-1.0.0.tgz",
    "verify-npm-integrity",
  ])
})

test("attestation detection treats the repository subject 404 as an absent attestation", async () => {
  assert.equal(await runAttestationDetection("absent"), "required=true\n")
})

test("attestation detection reuses a verified subject and rejects unrelated failures", async () => {
  assert.equal(await runAttestationDetection("present"), "required=false\n")
  await assert.rejects(runAttestationDetection("denied"), /HTTP 403: Resource not accessible by integration/)
})

test("release state machine selects one matching draft from the authenticated inventory", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-inventory-"))
  const inventoryPath = path.join(root, "releases.json")
  const draft = { id: 143, tag_name: "v1.0.0", draft: true, prerelease: false, assets: [] }
  try {
    await writeFile(inventoryPath, JSON.stringify([
      { id: 142, tag_name: "v0.9.0", draft: false, prerelease: false, assets: [] },
      draft,
    ]))
    const selected = await execFileAsync(process.execPath, [
      "scripts/release-state-machine.mjs", "select-release", inventoryPath, "v1.0.0",
    ])
    assert.deepEqual(JSON.parse(selected.stdout), draft)

    await writeFile(inventoryPath, "[]")
    const absent = await execFileAsync(process.execPath, [
      "scripts/release-state-machine.mjs", "select-release", inventoryPath, "v1.0.0",
    ])
    assert.equal(JSON.parse(absent.stdout), null)

    await writeFile(inventoryPath, JSON.stringify([draft, { ...draft, id: 144 }]))
    await assert.rejects(
      execFileAsync(process.execPath, [
        "scripts/release-state-machine.mjs", "select-release", inventoryPath, "v1.0.0",
      ]),
      /multiple releases match tag v1\.0\.0/,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("release byte comparison accepts different paths only when the SHA-256 digest matches", () => {
  const digest = "d".repeat(64)
  assert.doesNotThrow(() => assertSha256Digest(
    `${digest}  build/release/j4a-1.0.0.jar`,
    `${digest}  authenticated-release/j4a-1.0.0.jar`,
  ))
  assert.throws(
    () => assertSha256Digest(`${digest}  local.jar`, `${"e".repeat(64)}  downloaded.jar`),
    /SHA-256 mismatch/,
  )
})

test("release state machine resumes only matching partial or completed identities", () => {
  const partial = planReleaseOperations(releaseInput({
    release: {
      tag: "v1.0.0",
      draft: true,
      assets: [{ name: "j4a-1.0.0.jar", sha256: "a".repeat(64) }],
    },
  }))
  assert.deepEqual(partial.slice(0, 2), [
    "upload:j4a-1.0.0.jar.sha256",
    "attest-jar",
  ])

  const completeDraft = planReleaseOperations(releaseInput({
    release: {
      tag: "v1.0.0",
      draft: true,
      assets: [
        { name: "j4a-1.0.0.jar", sha256: "a".repeat(64) },
        { name: "j4a-1.0.0.jar.sha256", sha256: "b".repeat(64) },
      ],
    },
  }))
  assert.equal(completeDraft.includes("create-draft-release"), false)
  assert.equal(completeDraft.some(operation => operation.startsWith("upload:")), false)

  const published = planReleaseOperations(releaseInput({
    release: {
      tag: "v1.0.0",
      draft: false,
      assets: [
        { name: "j4a-1.0.0.jar", sha256: "a".repeat(64) },
        { name: "j4a-1.0.0.jar.sha256", sha256: "b".repeat(64) },
      ],
    },
  }))
  assert.equal(published.includes("publish-release"), false)
  assert.equal(published.includes("verify-public-download"), true)

  const npmPresent = planReleaseOperations(releaseInput({
    release: {
      tag: "v1.0.0",
      draft: false,
      assets: [
        { name: "j4a-1.0.0.jar", sha256: "a".repeat(64) },
        { name: "j4a-1.0.0.jar.sha256", sha256: "b".repeat(64) },
      ],
    },
    npmIntegrity: "sha512-local",
    attestationExists: true,
  }))
  assert.deepEqual(npmPresent, [
    "verify-authenticated-assets",
    "verify-public-download",
    "verify-npm-integrity",
  ])
})

test("release state machine rejects conflicts before mutation and stops after public failure", async () => {
  await assert.rejects(
    async () => planReleaseOperations(releaseInput({
      release: { tag: "v1.0.0", draft: true, assets: [{ name: "j4a-1.0.0.jar", sha256: "c".repeat(64) }] },
    })),
    /release asset identity mismatch: j4a-1\.0\.0\.jar/,
  )
  await assert.rejects(
    async () => planReleaseOperations(releaseInput({ npmIntegrity: "sha512-remote" })),
    /npm integrity mismatch/,
  )
  await assert.rejects(
    async () => planReleaseOperations(releaseInput({ npmAuthentication: "unavailable" })),
    /npm publish authentication is unavailable/,
  )
  for (const [name, input, error] of [
    ["tag", releaseInput({ tag: "v1.0.1" }), /tag must equal v1\.0\.0/],
    ["main", releaseInput({ mainReachable: false }), /origin\/main/],
    ["ruleset", releaseInput({ tagProtected: false }), /v\*/],
    ["concurrency", releaseInput({ lockAcquired: false }), /concurrent/],
  ]) {
    await assert.rejects(async () => planReleaseOperations(input), error, name)
  }

  const observed = []
  await assert.rejects(
    executeReleaseOperations(planReleaseOperations(releaseInput()), async operation => {
      observed.push(operation)
      if (operation === "verify-public-download") throw new Error("redirect download failed")
    }),
    /redirect download failed/,
  )
  assert.equal(observed.includes("publish-npm:jmx-for-agents-j4a-1.0.0.tgz"), false)

  const publishFailure = []
  await assert.rejects(
    executeReleaseOperations(planReleaseOperations(releaseInput()), async operation => {
      publishFailure.push(operation)
      if (operation.startsWith("publish-npm:")) throw new Error("publish command failed")
    }),
    /publish command failed/,
  )
  assert.equal(publishFailure.includes("verify-npm-integrity"), false)
})

test("release workflow has the pinned tag-publication contract", async () => {
  const source = await workflowSource()
  const workflow = parseReleaseWorkflow(source)
  const preflight = workflow.release.steps.find(
    step => step.name === "Prove this tag is an approved release candidate",
  )
  const jmeterProvision = workflow.release.steps.find(
    step => step.name === "Provision Apache JMeter 5.6.3",
  )

  assert.deepEqual(workflow.events, ["push"])
  assert.deepEqual(workflow.pushTags, ["v*.*.*"])
  assert.deepEqual(workflow.permissions, { contents: "read" })
  assert.equal(workflow.concurrency.group, "release-${{ github.ref_name }}")
  assert.equal(workflow.concurrency.cancelInProgress, false)
  assert.equal(workflow.release.environment, "npm-release")
  assert.deepEqual(workflow.release.permissions, {
    attestations: "write",
    contents: "write",
    "id-token": "write",
  })
  assert.equal(
    workflow.actionInputs["actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16"].cache,
    "false",
  )
  assert.ok(preflight.command.includes("./gradlew properties --quiet"))
  assert.ok(preflight.command.includes('gh api "repos/$GITHUB_REPOSITORY/rulesets/$RULESET_ID"'))
  assert.ok(jmeterProvision, "release workflow must provision exact JMeter 5.6.3")
  assert.ok(jmeterProvision.command.includes("apache-jmeter-5.6.3.tgz"))
  assert.ok(jmeterProvision.command.includes('echo "JMETER_HOME=$RUNNER_TEMP/apache-jmeter-5.6.3"'))
  assert.equal(
    workflow.release.steps.find(step => step.action?.startsWith("actions/attest@"))?.condition,
    "steps.attestation.outputs.required == 'true'",
  )
  assert.deepEqual(validateReleaseWorkflow(source), [])
})

test("release workflow validator rejects trigger, permission, pin, and ordering drift", async t => {
  const source = await workflowSource()
  const cases = [
    ["branch trigger", source.replace("tags: ['v*.*.*']", "branches: [main]"), "tag-trigger"],
    ["missing environment", source.replace("environment: npm-release\n", ""), "environment"],
    ["write default", source.replace("contents: read", "contents: write"), "default-permissions"],
    ["unpinned action", source.replace(/actions\/checkout@[a-f0-9]{40}/, "actions/checkout@v4"), "action-pin:actions/checkout"],
    ["missing ruleset", source.replace("node scripts/release-state-machine.mjs assert-preflight", "node scripts/release-state-machine.mjs skipped-preflight"), "ruleset-preflight"],
    ["ruleset summary without details", source.replace('gh api "repos/$GITHUB_REPOSITORY/rulesets/$RULESET_ID"', "printf '{}'"), "ruleset-details"],
    ["source-text Gradle version", source.replace("./gradlew properties --quiet | sed -n 's/^version: //p'", "sed -n \"s/^version = '\\\\([^']*\\\\)'$/\\\\1/p\" build.gradle"), "gradle-version-source"],
    ["silent preflight", source.replaceAll("preflight_fail", "silent_failure"), "preflight-diagnostics"],
    ["missing JMeter runtime export", source.replace('echo "JMETER_HOME=$RUNNER_TEMP/apache-jmeter-5.6.3"', 'echo "JMETER_HOME=missing"'), "jmeter-runtime"],
    ["missing Java test gate", source.replace("          ./gradlew clean test\n", ""), "command:./gradlew clean test"],
    ["Go dependency cache without a module", source.replace("          cache: false\n", "          cache: true\n"), "go-cache"],
    ["draft lookup uses the published-only tag endpoint", source.replaceAll("node scripts/release-state-machine.mjs select-release", "gh api repos/example/releases/tags/v1.0.0"), "draft-release-discovery"],
    ["attestation subject 404 is not recognized", source.replace("HTTP 404: Not Found (https://api.github.com/repos/$GITHUB_REPOSITORY/attestations/sha256:", "unhandled attestation 404"), "attestation-reuse"],
    ["path-sensitive checksum comparison", source.replace("node scripts/release-state-machine.mjs assert-sha256", "node scripts/release-state-machine.mjs skipped-sha256"), "digest-comparison"],
    ["unconditional attestation", source.replace("if: steps.attestation.outputs.required == 'true'\n        uses: actions/attest", "uses: actions/attest"), "attestation-reuse"],
    ["publishes npm early", source.replace('curl --fail --location --max-redirs 5 --output "$RUNNER_TEMP/public-release.jar"', 'npm publish "$RELEASE_TARBALL" --access public --provenance\n          curl --fail --location --max-redirs 5 --output "$RUNNER_TEMP/public-release.jar"'), "publication-order"],
  ]
  for (const [name, fixture, error] of cases) {
    await t.test(name, () => assert.ok(validateReleaseWorkflow(fixture).includes(error)))
  }
  for (const mutation of [
    "gh api --method POST repos/example/releases",
    "gh api --method PUT repos/example/releases",
    "gh api --method PATCH repos/example/releases",
    "gh api --method DELETE repos/example/releases",
    "gh release create v1.0.0",
    "gh release upload v1.0.0 local.jar",
    "gh release edit v1.0.0 --title changed",
    "gh release delete v1.0.0",
    "npm publish other.tgz --access public --provenance",
    "npm deprecate @jmx-for-agents/j4a@1.0.0 moved",
    "npm unpublish @jmx-for-agents/j4a@1.0.0",
    "npm dist-tag add @jmx-for-agents/j4a@1.0.0 latest",
    "npm dist-tag rm @jmx-for-agents/j4a latest",
    "npm view @jmx-for-agents/j4a@1.0.0 dist.integrity --json",
  ]) {
    await t.test(`post-registry mutation: ${mutation}`, () => {
      const fixture = `${source}\n      - name: forbidden post-registry mutation\n        run: ${mutation}\n`
      assert.ok(validateReleaseWorkflow(fixture).includes("final-registry-terminal"))
    })
  }
  await t.test("same-step npm publication after final verification", () => {
    const assertion = 'test "$ACTUAL_INTEGRITY" = "$RELEASE_INTEGRITY"'
    const index = source.lastIndexOf(assertion)
    const fixture = `${source.slice(0, index)}${assertion}\n          npm publish other.tgz --access public --provenance${source.slice(index + assertion.length)}`
    assert.ok(validateReleaseWorkflow(fixture).includes("final-registry-terminal"))
  })
})

test("release workflow validator CLI reports valid and invalid files", async () => {
  const valid = await execFileAsync(process.execPath, ["scripts/release-workflow-contract.mjs", workflowPath])
  assert.deepEqual(JSON.parse(valid.stdout), { errors: [] })

  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-workflow-"))
  try {
    const invalidPath = path.join(root, "invalid.yml")
    await writeFile(invalidPath, (await workflowSource()).replace("environment: npm-release\n", ""), "utf8")
    await assert.rejects(execFileAsync(process.execPath, ["scripts/release-workflow-contract.mjs", invalidPath]), error => {
      assert.equal(error.code, 1)
      assert.ok(JSON.parse(error.stderr).errors.includes("environment"))
      return true
    })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
