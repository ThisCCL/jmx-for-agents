#!/usr/bin/env node

import { createHash } from "node:crypto"
import { existsSync, readdirSync, readFileSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { basename, resolve } from "node:path"
import { parseJUnitXmlFiles } from "./parse-junit-xml.mjs"

const SHA256 = /^[a-f0-9]{64}$/

class VerificationError extends Error {
  constructor(path, reason) { super(`${path}: ${reason}`); this.path = path; this.reason = reason }
}

function parseArguments(values) {
  if (values[0] !== "--evidence-dir" || !values[1]) throw new VerificationError("arguments", "usage: --evidence-dir <root> [--attempt <attempt-N>]")
  if (values.length === 2) return { root: resolve(values[1]), requestedAttempt: undefined }
  if (values.length === 4 && values[2] === "--attempt" && /^attempt-\d+$/.test(values[3])) return { root: resolve(values[1]), requestedAttempt: values[3] }
  throw new VerificationError("arguments", "usage: --evidence-dir <root> [--attempt <attempt-N>]")
}

function text(path) {
  if (!existsSync(path)) throw new VerificationError(path, "missing file")
  const value = readFileSync(path, "utf8")
  if (!value) throw new VerificationError(path, "empty file")
  return value
}

function json(path) {
  try { return JSON.parse(text(path)) }
  catch (error) { if (error instanceof VerificationError) throw error; throw new VerificationError(path, `invalid JSON: ${error.message}`) }
}

function object(value, path) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new VerificationError(path, "expected object")
  return value
}

function equal(actual, expected, path) {
  if (actual !== expected) throw new VerificationError(path, `expected ${JSON.stringify(expected)}, found ${JSON.stringify(actual)}`)
}

function sha256File(path) { return createHash("sha256").update(readFileSync(path)).digest("hex") }
function currentHead() { return execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim() }

function selectedAttempt(root, requestedAttempt) {
  if (!existsSync(root)) throw new VerificationError(root, "missing evidence root")
  const attempts = readdirSync(root, { withFileTypes: true })
    .filter(entry => entry.isDirectory() && /^attempt-\d+$/.test(entry.name))
    .map(entry => ({ name: entry.name, number: Number(entry.name.slice("attempt-".length)) }))
    .sort((left, right) => right.number - left.number)
  if (requestedAttempt) {
    if (!attempts.some(attempt => attempt.name === requestedAttempt)) throw new VerificationError(resolve(root, requestedAttempt), "missing selected attempt directory")
    return resolve(root, requestedAttempt)
  }
  if (!attempts.length) throw new VerificationError(root, "missing attempt directory")
  if (attempts.length !== 1) throw new VerificationError(root, "ambiguous attempt directories; pass --attempt <attempt-N>")
  return resolve(root, attempts[0].name)
}

function doneclaimHead(value, path) { return value.sourceHead ?? value.source_sha ?? value.head ?? value.expected_head ?? value.actual_head ?? (() => { throw new VerificationError(path, "missing source HEAD") })() }

async function verifyJUnit(directory, expectedClasses, label) {
  const files = readdirSync(directory).filter(name => name.endsWith(".xml")).sort()
  const expectedFiles = Object.keys(expectedClasses).sort().map(name => `TEST-${name}.xml`)
  equal(files.join("\0"), expectedFiles.join("\0"), `${directory} XML inventory`)
  const paths = expectedFiles.map(name => resolve(directory, name))
  let parsed
  try { parsed = await parseJUnitXmlFiles(paths) }
  catch (error) { throw new VerificationError(directory, error instanceof Error ? error.message : "JUnit parse failed") }
  const actual = {}
  let executed = 0
  for (const path of paths) {
    const counts = parsed.results.get(path)
    if (!counts) throw new VerificationError(path, "missing structural JUnit result")
    const classes = Object.keys(counts.classes)
    if (classes.length !== 1) throw new VerificationError(path, "expected exactly one suite class")
    const className = classes[0]
    equal(basename(path), `TEST-${className}.xml`, `${path} class`)
    for (const field of ["failures", "errors", "skipped"]) equal(counts[field], 0, `${path}#${field}`)
    if (counts.executed <= 0) throw new VerificationError(path, "zero tests executed")
    actual[className] = (actual[className] ?? 0) + counts.executed
    executed += counts.executed
  }
  for (const className of Object.keys(expectedClasses)) equal(actual[className], expectedClasses[className], `${label}.${className}`)
  equal(Object.keys(actual).sort().join("\0"), Object.keys(expectedClasses).sort().join("\0"), `${label} class inventory`)
  console.log(`JUNIT ${label}: ${paths.length} suites, ${executed} tests, failures=0 errors=0 skipped=0`)
  return { files: paths.length, tests: executed }
}

async function main() {
  const errors = []
  let root, requestedAttempt, attempt, head
  try {
    ({ root, requestedAttempt } = parseArguments(process.argv.slice(2)))
    attempt = selectedAttempt(root, requestedAttempt)
    head = currentHead()
  }
  catch (error) { console.error(`FAIL ${error.message}`); process.exitCode = 1; return }
  const check = (fn) => { try { fn() } catch (error) { errors.push(error) } }
  const checkAsync = async (fn) => { try { await fn() } catch (error) { errors.push(error) } }

  const javaDir = resolve(attempt, "java")
  let aggregate, expected
  check(() => {
    aggregate = object(json(resolve(javaDir, "aggregate-result.json")), "java/aggregate-result.json")
    expected = object(json(resolve(javaDir, "expected.json")), "java/expected.json")
    equal(aggregate.status, "PASS", "java.aggregate.status")
    equal(aggregate.files, 26, "java.aggregate.files")
    equal(aggregate.tests.executed, 230, "java.aggregate.tests.executed")
    for (const field of ["failures", "errors", "skipped"]) equal(aggregate.tests[field], 0, `java.aggregate.tests.${field}`)
    const classes = object(aggregate.classes, "java.aggregate.classes")
    for (const [name, count] of [
      ["io.github.thisccl.j4a.validation.LocalJMeterFileCommitterAtomicMoveTest", 6],
      ["io.github.thisccl.j4a.validation.LocalJMeterSharedWorkersTest", 36],
      ["io.github.thisccl.j4a.validation.StandaloneWorkerLifecycleTest", 10],
      ["io.github.thisccl.j4a.validation.LocalMutationTransactionAtomicityTest", 10]
    ]) equal(classes[name], count, `java.aggregate.classes.${name}`)
    equal(Object.keys(classes).sort().join("\0"), expected.expected_classes.slice().sort().join("\0"), "java expected selector union")
    for (const [name, count] of Object.entries(classes)) if (!Number.isInteger(count) || count <= 0) throw new VerificationError(`java.aggregate.classes.${name}`, "expected positive test count")
  })
  if (aggregate && expected) await checkAsync(async () => {
    const junit = await verifyJUnit(resolve(javaDir, "junit-observed"), aggregate.classes, "java")
    equal(junit.tests, aggregate.tests.executed, "java XML total")
  })
  check(() => {
    const claim = object(json(resolve(javaDir, "doneclaim.json")), "java/doneclaim.json")
    equal(claim.verdict, "PASS", "java.doneclaim.verdict"); equal(doneclaimHead(claim, "java.doneclaim"), head, "java.doneclaim.head")
    for (const [key, value] of [["expectedSelectors", 26], ["observedXmlFiles", 26], ["tests", 230], ["failures", 0], ["errors", 0], ["skips", 0], ["sharedWorkersTests", 36], ["architectureGuardTests", 5], ["mutationPreflightTests", 1]]) equal(claim[key], value, `java.doneclaim.${key}`)
    for (const key of ["exactUnion", "exactXml", "noDuplicates", "allClassesTestsPositive", "sharedWorkersWholeClass", "headUnchanged", "statusUnchanged", "sourceUnchanged", "cleanup"]) equal(claim[key], true, `java.doneclaim.${key}`)
    equal(claim.methodSplitting, false, "java.doneclaim.methodSplitting"); equal(claim.retryAsPass, false, "java.doneclaim.retryAsPass")
    text(resolve(javaDir, "cleanup-receipt.txt")); text(resolve(javaDir, "baseline-observable.txt"))
  })

  await checkAsync(async () => {
    const conformanceDir = resolve(attempt, "conformance")
    const result = object(json(resolve(conformanceDir, "result.json")), "conformance/result.json")
    equal(result.verdict, "PASS", "conformance.verdict"); equal(result.head, head, "conformance.head"); equal(result.binary_observable.exit_code, 0, "conformance.exit_code")
    const junit = object(result.binary_observable.junit, "conformance.junit")
    for (const [key, value] of [["files", 3], ["fresh_files", 3], ["tests", 6], ["failures", 0], ["errors", 0], ["skips", 0]]) equal(junit[key], value, `conformance.junit.${key}`)
    const inventory = object(result.binary_observable.inventory, "conformance.inventory")
    for (const [key, value] of [["denominator", 916], ["proof", 916], ["representation_gaps", 0], ["unclassified", 0]]) equal(inventory[key], value, `conformance.inventory.${key}`)
    for (const token of ["persisted_user=916", "persisted_proof=916", "unclassified=0"]) if (!String(inventory.runtime_counts).includes(token)) throw new VerificationError("conformance.inventory.runtime_counts", `missing ${token}`)
    const files = readdirSync(resolve(conformanceDir, "junit")).filter(name => name.endsWith(".xml"))
    const classes = Object.fromEntries(files.map(name => [name.slice(5, -4), 0]))
    equal(Object.keys(classes).length, 3, "conformance XML inventory")
    await verifyJUnit(resolve(conformanceDir, "junit"), Object.fromEntries(Object.keys(classes).map(name => [name, name.includes("Inputs") ? 2 : name.includes("Generator") ? 1 : 3])), "conformance")
  })

  check(() => {
    const result = object(json(resolve(attempt, "node/results.json")), "node/results.json")
    for (const [key, value] of [["tests", 97], ["pass", 97], ["fail", 0], ["cancelled", 0], ["skipped", 0], ["todo", 0], ["exit_code", 0]]) equal(result[key], value, `node.results.${key}`)
    const claim = object(json(resolve(attempt, "node/doneclaim.json")), "node/doneclaim.json")
    equal(claim.status, "PASS", "node.doneclaim.status"); equal(claim.source_sha, head, "node.doneclaim.source_sha"); equal(claim.tests, 97, "node.doneclaim.tests"); equal(claim.pass, 97, "node.doneclaim.pass"); equal(claim.verifier_test_count, 6, "node.doneclaim.verifier_test_count"); equal(claim.owned_process_residue, 0, "node.doneclaim.owned_process_residue"); equal(claim.temporary_resource_residue, 0, "node.doneclaim.temporary_resource_residue")
  })

  let jarSha = "", jarSize
  check(() => {
    const claim = object(json(resolve(attempt, "final-shadowjar/doneclaim.json")), "final-shadowjar/doneclaim.json")
    equal(claim.verdict, "PASS", "final-shadowjar.verdict"); equal(claim.head, head, "final-shadowjar.head")
    const jar = object(claim.binary_observable?.jar, "final-shadowjar.jar")
    if (!SHA256.test(jar.sha256)) throw new VerificationError("final-shadowjar.jar.sha256", "invalid SHA-256")
    if (!existsSync(jar.absolute_path)) throw new VerificationError(jar.absolute_path, "missing JAR")
    jarSha = sha256File(jar.absolute_path); jarSize = readFileSync(jar.absolute_path).byteLength
    equal(jarSha, jar.sha256, "final-shadowjar.jar.sha256"); equal(jarSize, jar.size_bytes, "final-shadowjar.jar.size_bytes"); equal(text(resolve(attempt, "final-shadowjar/exit-code.txt")).trim(), "0", "final-shadowjar.exit-code")
    equal(text(resolve(attempt, "final-shadowjar/source-manifest-before.sha256")), text(resolve(attempt, "final-shadowjar/source-manifest-after.sha256")), "final-shadowjar.source-manifest")
    for (const key of ["no_retry", "no_tests", "no_later_build_or_qa_from_lane"]) equal(claim[key], true, `final-shadowjar.${key}`)
    equal(claim.binary_observable.shadowjar_tasks, 1, "final-shadowjar.shadowjar_tasks"); equal(claim.binary_observable.test_tasks, 0, "final-shadowjar.test_tasks")
    const independent = object(json(resolve(attempt, "final-shadowjar/independent-verification.json")), "final-shadowjar.independent-verification.json")
    equal(independent.verdict, "confirmed", "final-shadowjar.independent.verdict"); equal(independent.active_writers, 0, "final-shadowjar.independent.active_writers")
  })
  check(() => {
    const qa = object(json(resolve(attempt, "qa-property-graph-final/result.json")), "qa result")
    equal(qa.status, "PASS", "qa.status"); equal(Object.keys(qa.scenarios).length, 23, "qa.scenarios.length"); if (Object.values(qa.scenarios).some(value => value !== "PASS")) throw new VerificationError("qa.scenarios", "not all PASS"); equal(qa.observables.cleanup_complete, true, "qa.cleanup"); equal(qa.observables.jar_sha256, jarSha, "qa.jar.sha256")
    const cli = object(json(resolve(attempt, "cli-final/independent-verification.json")), "cli independent")
    equal(cli.lane_status, "PASS", "cli.status"); equal(cli.head, head, "cli.head"); equal(cli.jar_sha256, jarSha, "cli.jar.sha256"); equal(cli.command_count, 24, "cli.command_count"); equal(cli.owned_process_count, 0, "cli.owned_process_count"); equal(cli.temp_residue_count, 0, "cli.temp_residue_count"); equal(cli.cleanup, "removed=true", "cli.cleanup")
    const containment = object(json(resolve(attempt, "cli-final/containment-final.json")), "cli containment")
    equal(containment.driver_exit, 0, "containment.driver_exit"); equal(containment.jar_sha256, jarSha, "containment.authoritative_package.jar_sha256"); equal(containment.owned_process_count, 0, "containment.owned_process_count"); equal(containment.temp_residue_count, 0, "containment.temp_residue_count"); equal(containment.status_compare_exit, 0, "containment.status_compare_exit"); equal(containment.source_manifest_compare_exit, 0, "containment.source_manifest_compare_exit"); equal(containment.source_vs_head_exit, 0, "containment.source_vs_head_exit"); equal(containment.timed_out.every(value => value === false), true, "containment.timed_out"); equal(containment.exit_vector.length, 24, "containment.exit_vector.length"); if (containment.exit_vector.some(value => ![0, 1, 3, 4].includes(value))) throw new VerificationError("containment.exit_vector", "unexpected scenario exit")
    const mcp = object(json(resolve(attempt, "mcp-final/result.json")), "mcp result")
    equal(mcp.status, "PASS", "mcp.status"); equal(mcp.head, head, "mcp.head"); equal(mcp.jar.sha256, jarSha, "mcp.jar.sha256"); for (const [name, requests] of [["full", 19], ["invalid_focused", 6], ["interaction", 18]]) { equal(mcp.official[name].exit, 0, `mcp.${name}.exit`); equal(mcp.official[name].requests, requests, `mcp.${name}.requests`); equal(mcp.official[name].responses, requests, `mcp.${name}.responses`) }; equal(mcp.cleanup.process_residue, 0, "mcp.cleanup")
    const negative = object(json(resolve(attempt, "negative/negative-results.json")), "negative results")
    equal(negative.status, "PASS", "negative.status"); equal(negative.final_jar_unchanged, true, "negative.jar_unchanged"); equal(negative.cases.length, 8, "negative.cases.length"); if (negative.cases.some(value => value.verdict !== true || value.actual_exit !== value.expected_exit || value.actual_reason !== value.expected_reason)) throw new VerificationError("negative.cases", "negative fixture mismatch")
    equal(negative.preflight.expected_jar_sha256, jarSha, "negative.preflight.jar.sha256"); equal(negative.preflight.actual_jar_sha256, jarSha, "negative.preflight.actual_jar.sha256"); equal(negative.preflight.expected_head, head, "negative.preflight.head"); equal(negative.preflight.actual_head, head, "negative.preflight.actual_head")
    const negativeIndependent = object(json(resolve(attempt, "negative/independent-verification.json")), "negative independent")
    equal(negativeIndependent.status, "PASS", "negative.independent.status"); equal(negativeIndependent.expected_jar_sha256, jarSha, "negative.independent.jar.sha256"); equal(negativeIndependent.expected_head, head, "negative.independent.head"); if (Object.values(negativeIndependent.checks).some(value => value !== true)) throw new VerificationError("negative.independent.checks", "a negative containment check failed")
    const negativeClaim = object(json(resolve(attempt, "negative/doneclaim.json")), "negative/doneclaim.json")
    object(negativeClaim.DoneClaim, "negative.DoneClaim"); object(negativeClaim.AdversarialVerify, "negative.AdversarialVerify"); if (!Array.isArray(negativeClaim.DoneClaim.changed_files) || negativeClaim.DoneClaim.changed_files.length !== 0) throw new VerificationError("negative.DoneClaim.changed_files", "expected no changed files"); if (!Array.isArray(negativeClaim.DoneClaim.manual_qa) || negativeClaim.DoneClaim.manual_qa.length === 0) throw new VerificationError("negative.DoneClaim.manual_qa", "missing manual QA evidence"); equal(negativeClaim.AdversarialVerify.verdict, "confirmed", "negative.AdversarialVerify.verdict"); equal(negativeClaim.AdversarialVerify.confidence, 1, "negative.AdversarialVerify.confidence")
  })
  check(() => {
    const runtime = object(json(resolve(attempt, "runtime-safety/result.json")), "runtime safety")
    equal(runtime.status, "PASS", "runtime.status"); equal(runtime.head, head, "runtime.head"); equal(runtime.aggregate.tests, 12, "runtime.tests"); for (const key of ["failures", "errors", "skipped"]) equal(runtime.aggregate[key], 0, `runtime.${key}`); equal(runtime.retry_as_pass, false, "runtime.retry_as_pass"); equal(runtime.shadow_jar_invoked, false, "runtime.shadow_jar_invoked"); equal(runtime.integrity.new_temp_residue, 0, "runtime.temp_residue"); equal(runtime.integrity.active_runtime_writers, 0, "runtime.active_writers")
  })
  check(() => {
    const strict = object(json(resolve(attempt, "source-strict/doneclaim.json")), "source-strict/doneclaim.json")
    equal(strict.verdict, "PASS", "source-strict.verdict"); equal(strict.head, head, "source-strict.head"); equal(strict.proofs.containment.active_relevant_writers_after, 0, "source-strict.containment.writers"); equal(strict.proofs.cleanup.verdict, "PASS", "source-strict.cleanup")
    for (const key of ["head_unchanged", "status_unchanged", "tracked_fileset_unchanged", "untracked_fileset_unchanged", "tracked_source_bytes_unchanged"]) equal(strict.proofs.containment[key], true, `source-strict.containment.${key}`)
    const observable = text(resolve(attempt, "source-strict/containment-observable.txt")); for (const line of ["HEAD_UNCHANGED=PASS", "STATUS_UNCHANGED=PASS", "TRACKED_FILESET_UNCHANGED=PASS", "UNTRACKED_FILESET_UNCHANGED=PASS", "TRACKED_SOURCE_BYTES_UNCHANGED=PASS"]) if (!observable.includes(line)) throw new VerificationError("source-strict/containment-observable.txt", `missing ${line}`)
    const review = object(json(resolve(attempt, "qa-property-graph-final-review/verdict.json")), "qa final review")
    equal(review.verdict, "confirmed", "qa review.verdict"); equal(review.recommendation, "APPROVE", "qa review.recommendation"); equal(review.head, head, "qa review.head"); equal(review.jar_sha256, jarSha, "qa review.jar.sha256"); equal(review.comprehensive_scenarios, 23, "qa review.scenarios"); equal(review.comprehensive_passes, 23, "qa review.passes"); equal(review.cleanup_complete, true, "qa review.cleanup"); equal(review.evidence_gaps.length, 0, "qa review.evidence_gaps")
  })

  if (errors.length) { for (const error of errors) console.error(`FAIL ${error.message}`); console.error(`RED: ${errors.length} verification error(s)`); process.exitCode = 1; return }
  console.log(`GREEN: ${basename(attempt)} evidence, exact Java 26/230, Node 97, conformance 916/916, package binding, negative fixtures, and cleanup verified`)
}

await main()
