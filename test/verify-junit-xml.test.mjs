import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"
import { spawn } from "node:child_process"
import test from "node:test"

const verifier = resolve("scripts/verify-junit-xml.mjs")
const jaxpQa = resolve("scripts/qa-junit-jaxp.mjs")
const suite = ({ name, tests, failures = 0, errors = 0, body = "" }) =>
  `<testsuite name="${name}" tests="${tests}" failures="${failures}" errors="${errors}" skipped="0">${body}</testsuite>`
const testCase = (className, name, body = "") => `<testcase name="${name}" classname="${className}">${body}</testcase>`

function run(script, arguments_) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(process.execPath, [script, ...arguments_], {
      env: process.env,
      stdio: ["ignore", "pipe", "pipe"],
    })
    let stdout = "", stderr = ""
    child.stdout.on("data", chunk => { stdout += chunk })
    child.stderr.on("data", chunk => { stderr += chunk })
    child.once("error", rejectRun)
    child.once("close", code => resolveRun({ code, stdout, stderr }))
  })
}

async function fixture({ expected, files }) {
  const root = await mkdtemp(join(tmpdir(), "j4a-verify-junit-"))
  const junit = join(root, "junit")
  await mkdir(junit)
  await writeFile(join(root, "expected-classes.json"), JSON.stringify({ expected_classes: expected }), "utf8")
  await Promise.all(files.map(({ file, source }) => writeFile(join(junit, file), source, "utf8")))
  return {
    arguments: ["--junit-dir", junit, "--expected-classes", join(root, "expected-classes.json")],
    root,
  }
}

async function verify(input) {
  try {
    return await run(verifier, input.arguments)
  } finally {
    await rm(input.root, { recursive: true, force: true })
  }
}

async function provenanceResult(mutate) {
  const root = await mkdtemp(join(tmpdir(), "j4a-junit-pin-"))
  try {
    const candidate = join(root, "candidate")
    const rows = []
    for (let gate = 1; gate <= 7; gate += 1) {
      const directory = join(candidate, `focus-9.${gate}`, "junit")
      const path = join(directory, `TEST-focus-${gate}.xml`)
      await mkdir(directory, { recursive: true })
      await writeFile(path, `focus-${gate}`)
      rows.push(path)
    }
    const full = join(candidate, "full-suite", "junit")
    await mkdir(full, { recursive: true })
    for (let index = 0; index < 15; index += 1) {
      const path = join(full, `TEST-full-${String(index).padStart(2, "0")}.xml`)
      await writeFile(path, `full-${index}`)
      rows.push(path)
    }
    const pin = {
      schema_version: 1,
      candidate_dir: candidate,
      total: rows.length,
      rows: await Promise.all(rows.map(async path => {
        const digest = createHash("sha256").update(await readFile(path)).digest("hex")
        return { path, source_sha256: digest, xml_sha256: digest, sha256: digest, observed_sha256: digest }
      })),
    }
    mutate(pin)
    const pinPath = join(root, "pin.json")
    await writeFile(pinPath, JSON.stringify(pin), "utf8")
    return await run(jaxpQa, ["--candidate-dir", candidate, "--pin", pinPath, "--provenance-only"])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

test("Test_exactInventory_when_expectedClassesMatch", async () => {
  const input = await fixture({
    expected: ["qa.AlphaTest", "qa.BetaTest"],
    files: [
      { file: "TEST-qa.AlphaTest.xml", source: suite({ name: "qa.AlphaTest", tests: 1, body: testCase("qa.AlphaTest", "alpha") }) },
      { file: "TEST-qa.BetaTest.xml", source: suite({ name: "qa.BetaTest", tests: 1, body: testCase("qa.BetaTest", "beta") }) },
    ],
  })

  const result = await verify(input)

  assert.equal(result.code, 0, result.stderr)
})

test("Test_missingSuite_whenBuildOutputClaimsSuccess", async () => {
  const input = await fixture({ expected: ["qa.AlphaTest"], files: [] })
  await writeFile(join(input.root, "build.log"), "BUILD SUCCESSFUL\n", "utf8")

  const result = await verify(input)

  assert.notEqual(result.code, 0)
})

test("Test_zeroTests_whenSuiteClassExists", async () => {
  const input = await fixture({
    expected: ["qa.AlphaTest"],
    files: [{ file: "TEST-qa.AlphaTest.xml", source: suite({ name: "qa.AlphaTest", tests: 0 }) }],
  })

  const result = await verify(input)

  assert.notEqual(result.code, 0)
})

test("Test_failureOrError_whenSuiteReportsOutcome", async () => {
  const failure = await fixture({
    expected: ["qa.AlphaTest"],
    files: [{ file: "TEST-qa.AlphaTest.xml", source: suite({ name: "qa.AlphaTest", tests: 1, failures: 1, body: testCase("qa.AlphaTest", "alpha", "<failure/>") }) }],
  })
  const error = await fixture({
    expected: ["qa.AlphaTest"],
    files: [{ file: "TEST-qa.AlphaTest.xml", source: suite({ name: "qa.AlphaTest", tests: 1, errors: 1, body: testCase("qa.AlphaTest", "alpha", "<error/>") }) }],
  })

  assert.notEqual((await verify(failure)).code, 0)
  assert.notEqual((await verify(error)).code, 0)
})

test("Test_unexpectedSuite_whenInventoryContainsStaleClass", async () => {
  const input = await fixture({
    expected: ["qa.AlphaTest"],
    files: [
      { file: "TEST-qa.AlphaTest.xml", source: suite({ name: "qa.AlphaTest", tests: 1, body: testCase("qa.AlphaTest", "alpha") }) },
      { file: "TEST-qa.StaleTest.xml", source: suite({ name: "qa.StaleTest", tests: 1, body: testCase("qa.StaleTest", "stale") }) },
    ],
  })

  const result = await verify(input)

  assert.notEqual(result.code, 0)
})

test("Test_pinProvenance_whenPinnedPathDiffers", async () => {
  const result = await provenanceResult(pin => { pin.rows[0].path = "different.xml" })

  assert.notEqual(result.code, 0)
})

test("Test_pinProvenance_whenPinnedHashDiffers", async () => {
  const result = await provenanceResult(pin => { pin.rows[0].sha256 = "0".repeat(64) })

  assert.notEqual(result.code, 0)
})
