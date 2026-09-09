import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { promisify } from "node:util"

import {
  assertSha256Digest,
  findActiveTagRuleset,
  planRuntimeReleaseOperations,
  planWrapperReleaseOperations,
  selectReleaseByTag,
} from "../scripts/release-state-machine.mjs"
import { validateReleaseWorkflows } from "../scripts/release-workflow-contract.mjs"

const execFileAsync = promisify(execFile)
const wrapperWorkflowPath = path.resolve(".github", "workflows", "release.yml")
const runtimeWorkflowPath = path.resolve(".github", "workflows", "runtime-release.yml")

async function workflowSources() {
  const [wrapper, runtime] = await Promise.all([
    readFile(wrapperWorkflowPath, "utf8"),
    readFile(runtimeWorkflowPath, "utf8"),
  ])
  return { wrapper, runtime }
}

test("wrapper release can publish npm only after the configured runtime is verified", () => {
  const base = {
    tag: "v2.0.0",
    version: "2.0.0",
    mainReachable: true,
    tagProtected: true,
    lockAcquired: true,
    runtimeVerified: true,
    npmIntegrity: null,
    npmAuthentication: "trusted",
    tarball: { name: "jmx-for-agents-j4a-2.0.0.tgz", integrity: "sha512-local" },
  }

  assert.deepEqual(planWrapperReleaseOperations(base), [
    "publish-npm:jmx-for-agents-j4a-2.0.0.tgz",
  ])
  assert.deepEqual(planWrapperReleaseOperations({ ...base, npmIntegrity: "sha512-local" }), [
    "verify-npm-integrity",
  ])
  assert.throws(() => planWrapperReleaseOperations({ ...base, runtimeVerified: false }), /public runtime/i)
  assert.throws(() => planWrapperReleaseOperations({ ...base, npmIntegrity: "sha512-other" }), /npm integrity mismatch/)
})

test("runtime release manages only JAR assets, attestation, and public download", () => {
  const base = {
    tag: "runtime-v1.2.3",
    version: "1.2.3",
    mainReachable: true,
    tagProtected: true,
    lockAcquired: true,
    release: null,
    attestationExists: false,
    assets: [
      { name: "j4a-1.2.3.jar", sha256: "a".repeat(64) },
      { name: "j4a-1.2.3.jar.sha256", sha256: "b".repeat(64) },
    ],
  }

  assert.deepEqual(planRuntimeReleaseOperations(base), [
    "create-draft-release",
    "upload:j4a-1.2.3.jar",
    "upload:j4a-1.2.3.jar.sha256",
    "attest-jar",
    "verify-authenticated-assets",
    "publish-release",
    "verify-public-download",
  ])
  const resumed = planRuntimeReleaseOperations({
    ...base,
    attestationExists: true,
    release: { tag: base.tag, draft: false, assets: base.assets },
  })
  assert.deepEqual(resumed, ["verify-authenticated-assets", "verify-public-download"])
})

test("tag rulesets and release selection distinguish wrapper and runtime namespaces", () => {
  const rulesets = [{
    enforcement: "active",
    conditions: { ref_name: { include: ["refs/tags/v*", "refs/tags/runtime-v*"] } },
  }]
  assert.equal(findActiveTagRuleset(rulesets, "v2.0.0"), true)
  assert.equal(findActiveTagRuleset(rulesets, "runtime-v1.2.3"), true)
  assert.equal(findActiveTagRuleset(rulesets, "other-v1.2.3"), false)

  const runtimeRelease = { id: 7, tag_name: "runtime-v1.2.3", draft: true, assets: [] }
  assert.deepEqual(selectReleaseByTag([runtimeRelease], "runtime-v1.2.3"), runtimeRelease)
  assert.equal(selectReleaseByTag([], "v2.0.0"), null)
})

test("release byte comparison ignores paths but never digest drift", () => {
  const digest = "d".repeat(64)
  assert.doesNotThrow(() => assertSha256Digest(
    `${digest}  build/runtime-release/j4a-1.2.3.jar`,
    `${digest}  authenticated-release/j4a-1.2.3.jar`,
  ))
  assert.throws(
    () => assertSha256Digest(`${digest}  local.jar`, `${"e".repeat(64)}  remote.jar`),
    /SHA-256 mismatch/,
  )
})

test("wrapper and runtime workflows have disjoint publication authority", async () => {
  const { wrapper, runtime } = await workflowSources()

  assert.deepEqual(validateReleaseWorkflows({ wrapperSource: wrapper, runtimeSource: runtime }), [])
  assert.match(wrapper, /tags:\s*\['v\*\.\*\.\*'\]/)
  assert.match(wrapper, /environment:\s*npm-release/)
  assert.match(wrapper, /npm publish/)
  assert.doesNotMatch(wrapper, /actions\/attest@|gh release (?:create|upload|edit|delete)|attestations:\s*write/)
  assert.match(wrapper, /Validate the configured public runtime/)

  assert.match(runtime, /tags:\s*\['runtime-v\*\.\*\.\*'\]/)
  assert.match(runtime, /actions\/attest@[a-f0-9]{40}/)
  assert.match(runtime, /gh release upload/)
  assert.doesNotMatch(runtime, /npm publish|npm view|NPM_BOOTSTRAP|environment:\s*npm-release/)
})

test("runtime release uses the create response id without rediscovering the draft", async () => {
  const { runtime } = await workflowSources()
  const createStart = runtime.indexOf("      - name: Create or reuse the identity-matching draft runtime release")
  const uploadStart = runtime.indexOf("      - name: Upload only missing canonical runtime assets")
  assert.ok(createStart >= 0 && uploadStart > createStart, "runtime release create step is missing")

  const createStep = runtime.slice(createStart, uploadStart)
  assert.match(createStep, /RELEASE_ID="\$\(gh api --method POST [\s\S]*--jq '\.id'\)"/)
  assert.doesNotMatch(createStep, /releases\?per_page|select-release|gh release create/)
})

test("release workflow validator rejects cross-publication and unsafe trigger changes", async () => {
  const { wrapper, runtime } = await workflowSources()
  const cases = [
    {
      expected: "wrapper:npm-only",
      wrapper: `${wrapper}\n      - name: Forbidden cross-publication\n        run: gh release upload v2.0.0 forbidden.jar\n`,
      runtime,
    },
    {
      expected: "runtime:jar-only",
      wrapper,
      runtime: `${runtime}\n      - name: Forbidden cross-publication\n        run: npm publish forbidden.tgz\n`,
    },
    {
      expected: "wrapper:tag-trigger",
      wrapper: wrapper.replace("tags: ['v*.*.*']", "branches: [main]"),
      runtime,
    },
    {
      expected: "runtime:tag-trigger",
      wrapper,
      runtime: runtime.replace("tags: ['runtime-v*.*.*']", "tags: ['v*.*.*']"),
    },
    {
      expected: "wrapper:runtime-verification",
      wrapper: wrapper.replace("Validate the configured public runtime", "Skip runtime validation"),
      runtime,
    },
  ]

  for (const fixture of cases) {
    const errors = validateReleaseWorkflows({
      wrapperSource: fixture.wrapper,
      runtimeSource: fixture.runtime,
    })
    assert.ok(errors.includes(fixture.expected), `${fixture.expected}: ${errors.join(", ")}`)
  }
})

test("release workflow validator CLI checks both workflow files together", async () => {
  const valid = await execFileAsync(process.execPath, [
    "scripts/release-workflow-contract.mjs",
    wrapperWorkflowPath,
    runtimeWorkflowPath,
  ])
  assert.deepEqual(JSON.parse(valid.stdout), { errors: [] })

  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-workflow-"))
  try {
    const { wrapper, runtime } = await workflowSources()
    const wrapperPath = path.join(root, "wrapper.yml")
    const runtimePath = path.join(root, "runtime.yml")
    await writeFile(wrapperPath, wrapper.replace("environment: npm-release\n", ""), "utf8")
    await writeFile(runtimePath, runtime, "utf8")
    await assert.rejects(
      execFileAsync(process.execPath, ["scripts/release-workflow-contract.mjs", wrapperPath, runtimePath]),
      error => {
        assert.equal(error.code, 1)
        assert.ok(JSON.parse(error.stderr).errors.includes("wrapper:environment"))
        return true
      },
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
