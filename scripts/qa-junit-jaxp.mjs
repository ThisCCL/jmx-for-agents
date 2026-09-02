#!/usr/bin/env node

import { createHash } from "node:crypto"
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { basename, join } from "node:path"
import { decodeJUnitProtocol, parseJUnitXmlFiles } from "./parse-junit-xml.mjs"

const sha = value => createHash("sha256").update(value).digest("hex")
const canonical = value => Array.isArray(value) ? value.map(canonical) : value && typeof value === "object" ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value
const same = (left, right) => JSON.stringify(canonical(left)) === JSON.stringify(canonical(right))
const legacyClassNames = result => ({ ...result, classes: Object.fromEntries(Object.entries(result.classes).map(([name, count]) => [name.slice(name.lastIndexOf(".") + 1), count])) })
const suite = (counts, body = "", extra = "") => `<testsuite name="qa.SampleTest" tests="${counts.tests}" failures="${counts.failures}" errors="${counts.errors}" skipped="${counts.skipped}"${extra}>${body}</testsuite>`
const testcase = (body = "", extra = "") => `<testcase name="works" classname="qa.SampleTest"${extra}>${body}</testcase>`
const zero = { tests: 1, failures: 0, errors: 0, skipped: 0 }
const empty = { tests: 0, failures: 0, errors: 0, skipped: 0 }

function options(values) {
  const result = {}
  for (let index = 0; index < values.length;) {
    const option = values[index]
    if (option === "--provenance-only") {
      if (result["provenance-only"]) throw new Error("usage: qa-junit-jaxp --candidate-dir <dir> --pin <pin.json> [--provenance-only]")
      result["provenance-only"] = true
      index += 1
      continue
    }
    if ((option !== "--candidate-dir" && option !== "--pin") || !values[index + 1] || result[option.slice(2)]) throw new Error("usage: qa-junit-jaxp --candidate-dir <dir> --pin <pin.json> [--provenance-only]")
    result[option.slice(2)] = values[index + 1]
    index += 2
  }
  if (!result["candidate-dir"] || !result.pin) throw new Error("usage: qa-junit-jaxp --candidate-dir <dir> --pin <valid-22-normalized.json>")
  return result
}

export async function assertPinProvenance({ candidate, paths, pin }) {
  if (pin === null || typeof pin !== "object" || Array.isArray(pin) || !Array.isArray(pin.rows)) throw new Error("JUnit pin: expected object with rows array")
  if (pin.candidate_dir !== undefined && pin.candidate_dir !== candidate) throw new Error(`JUnit pin: candidate_dir mismatch ${JSON.stringify(pin.candidate_dir)}`)
  if (pin.total !== undefined && pin.total !== paths.length) throw new Error(`JUnit pin: total mismatch ${pin.total} != ${paths.length}`)
  if (pin.rows.length !== paths.length) throw new Error(`JUnit pin: row count mismatch ${pin.rows.length} != ${paths.length}`)
  for (let index = 0; index < paths.length; index += 1) {
    const row = pin.rows[index]
    const path = paths[index]
    if (row === null || typeof row !== "object" || Array.isArray(row)) throw new Error(`JUnit pin: row ${index} is not an object`)
    if (row.path !== path) throw new Error(`JUnit pin: path mismatch at row ${index}`)
    const digest = sha(await readFile(path))
    for (const field of ["sha256", "observed_sha256"]) if (row[field] !== digest) throw new Error(`JUnit pin: ${field} mismatch at row ${index}`)
    for (const field of ["source_sha256", "xml_sha256"]) if (field in row && row[field] !== digest) throw new Error(`JUnit pin: ${field} mismatch at row ${index}`)
  }
}

const validSynthetic = [
  ["paired-failure", suite({ tests: 1, failures: 1, errors: 0, skipped: 0 }, testcase("<failure>message</failure>"))],
  ["self-closing-failure", suite({ tests: 1, failures: 1, errors: 0, skipped: 0 }, testcase("<failure/>"))],
  ["paired-error", suite({ tests: 1, failures: 0, errors: 1, skipped: 0 }, testcase("<error>message</error>"))],
  ["self-closing-error", suite({ tests: 1, failures: 0, errors: 1, skipped: 0 }, testcase("<error/>"))],
  ["paired-skipped", suite({ tests: 1, failures: 0, errors: 0, skipped: 1 }, testcase("<skipped>reason</skipped>"))],
  ["self-closing-skipped", suite({ tests: 1, failures: 0, errors: 0, skipped: 1 }, testcase("<skipped/>"))],
  ["properties", suite(zero, `<properties><property name="key" value="value"/></properties>${testcase()}`)],
  ["outputs", suite(zero, `${testcase()}<system-out>line</system-out><system-err><![CDATA[error <text>]]></system-err>`) ],
  ["declaration", `<?xml version="1.0" encoding="UTF-8"?>${suite(zero, testcase())}`],
  ["comments", `<!--before-->${suite(zero, `<!--inside-->${testcase()}`)}<!--after-->`],
  ["cdata", suite(zero, `${testcase()}<system-out><![CDATA[a < b && c]]></system-out>`) ],
  ["entities", suite(zero, `${testcase()}<system-out>&amp;&lt;&gt;&apos;&quot;</system-out>`) ],
  ["numeric", suite(zero, `${testcase()}<system-out>&#9;&#65;&#x1F642;</system-out>`) ],
]

const R = (name, source, reason) => ({ name, source, expected: "REJECT", reason })
const A = (name, source, reason) => ({ name, source, expected: "ACCEPT", reason })
const matrix = [
  R("unclosed-root", `<testsuite name="qa.SampleTest" tests="0" failures="0" errors="0" skipped="0">`, "complete root"),
  R("unclosed-start", `<testsuite name="qa.SampleTest" tests="0" failures="0" errors="0" skipped="0"`, "closed start tag"),
  R("mismatched", suite(zero, `<testcase name="works" classname="qa.SampleTest"></testsuite></testcase>`), "nesting"),
  R("duplicate-root", `${suite(empty)}${suite(empty)}`, "single root"), R("nested-root", suite(empty, suite(empty)), "no nested suite"),
  R("outside-text", `outside${suite(empty)}`, "outside text"), R("test-count", suite({ ...zero, tests: 2 }, testcase()), "test count"),
  R("failure-zero", suite(zero, testcase("<failure/>")), "failure count"), R("error-zero", suite(zero, testcase("<error/>")), "error count"),
  R("skipped-zero", suite(zero, testcase("<skipped/>")), "skipped count"),
  A("failure-one", suite({ ...zero, failures: 1 }, testcase("<failure/>")), "matching failure"),
  A("error-one", suite({ ...zero, errors: 1 }, testcase("<error/>")), "matching error"),
  A("skipped-one", suite({ ...zero, skipped: 1 }, testcase("<skipped/>")), "matching skipped"),
  R("failure-without-child", suite({ ...zero, failures: 1 }, testcase()), "counter child"),
  R("error-without-child", suite({ ...zero, errors: 1 }, testcase()), "counter child"),
  R("skipped-without-child", suite({ ...zero, skipped: 1 }, testcase()), "counter child"),
  R("multiple-outcomes", suite({ ...zero, failures: 1, errors: 1 }, testcase("<failure/><error/>")), "one outcome"),
  R("outcome-wrong-parent", suite({ ...empty, failures: 1 }, "<failure/>"), "outcome parent"),
  R("unknown-suite", suite(empty, "<bogus/>"), "closed suite vocabulary"), R("unknown-testcase", suite(zero, testcase("<bogus/>")), "closed testcase vocabulary"),
  R("unknown-properties", suite(zero, `<properties><bogus/></properties>${testcase()}`), "closed properties vocabulary"),
  R("unknown-output", suite(empty, "<system-out><bogus/></system-out>"), "text-only output"),
  R("unknown-outcome", suite({ ...zero, failures: 1 }, testcase("<failure><bogus/></failure>")), "text-only outcome"),
  R("property-wrong-parent", suite(zero, `<property name="x" value="y"/>${testcase()}`), "property parent"),
  R("output-wrong-parent", suite(zero, testcase("<system-out>x</system-out>")), "output parent"),
  R("malformed-attribute", `<testsuite name="qa.SampleTest" tests=1 failures="0" errors="0" skipped="0">${testcase()}</testsuite>`, "quoted attribute"),
  R("duplicate-attribute", `<testsuite name="qa.SampleTest" name="again" tests="0" failures="0" errors="0" skipped="0"/>`, "duplicate attribute"),
  R("unknown-suite-attribute", `<testsuite name="qa.SampleTest" tests="0" failures="0" errors="0" skipped="0" alien="x"/>`, "closed suite attributes"),
  R("unknown-testcase-attribute", suite(zero, `<testcase name="works" classname="qa.SampleTest" alien="x"/>`), "closed testcase attributes"),
  R("malformed-declaration", `<?xml garbage?>${suite(empty)}`, "XML declaration"), R("processing-instruction", `<?probe value?>${suite(empty)}`, "unsupported PI"),
  R("doctype", `<!DOCTYPE testsuite>${suite(empty)}`, "DOCTYPE"),
  R("xxe-file", `<!DOCTYPE testsuite [<!ENTITY x SYSTEM "file:///etc/passwd">]>${suite(empty, "<system-out>&x;</system-out>")}`, "file XXE"),
  R("xxe-http", `<!DOCTYPE testsuite [<!ENTITY x SYSTEM "http://127.0.0.1:9/x">]>${suite(empty, "<system-out>&x;</system-out>")}`, "HTTP XXE"),
  R("general-entity", `<!DOCTYPE testsuite [<!ENTITY x "value">]>${suite(empty, "<system-out>&x;</system-out>")}`, "general entity"),
  R("invalid-entity", suite(empty, "<system-out>&invalid;</system-out>"), "named entity"), R("numeric-zero", suite(empty, "<system-out>&#0;</system-out>"), "numeric zero"),
  R("numeric-surrogate", suite(empty, "<system-out>&#xD800;</system-out>"), "surrogate"), R("numeric-large", suite(empty, "<system-out>&#x110000;</system-out>"), "large code point"),
  R("raw-control", suite(empty, "<system-out>\u0001</system-out>"), "raw control"), R("normal-cdata-close", suite(empty, "<system-out>text ]]> more</system-out>"), "]]> in text"),
  R("unclosed-comment", `<!-- open${suite(empty)}`, "comment close"), R("malformed-comment", `<!-- bad -- comment -->${suite(empty)}`, "comment grammar"),
  R("unclosed-cdata", suite(empty, "<system-out><![CDATA[open</system-out>"), "CDATA close"), R("malformed-cdata", suite(empty, "<system-out><![CDATX[text]]></system-out>"), "CDATA grammar"),
  R("duplicate-properties", suite(zero, `<properties/><properties/>${testcase()}`), "single properties"),
  R("duplicate-system-out", suite(zero, `${testcase()}<system-out/><system-out/>`), "single system-out"),
  R("duplicate-system-err", suite(zero, `${testcase()}<system-err/><system-err/>`), "single system-err"),
  R("reviewer-property-parent", suite(zero, testcase(`<property name="x" value="y"/>`)), "known wrong parent"),
  R("reviewer-control-boundary", suite(empty, "<system-out>&#x1F;</system-out>"), "XML control"),
  R("reviewer-nested-output", suite(empty, "<system-err><reviewerNested/></system-err>"), "nested output"),
  R("reviewer-parameter-entity", `<!DOCTYPE testsuite [<!ENTITY % ext SYSTEM "http://127.0.0.1:9/x">%ext;]>${suite(empty)}`, "parameter entity"),
]

async function select(candidate) {
  const paths = []
  for (let gate = 1; gate <= 7; gate += 1) {
    const dir = join(candidate, `focus-9.${gate}`, "junit")
    paths.push(join(dir, (await readdir(dir)).filter(name => name.endsWith(".xml")).sort()[0]))
  }
  const dir = join(candidate, "full-suite", "junit"), files = (await readdir(dir)).filter(name => name.endsWith(".xml")).sort()
  const middle = Math.floor(files.length / 2), indexes = [0, 1, 2, 3, 4, middle - 2, middle - 1, middle, middle + 1, middle + 2, files.length - 5, files.length - 4, files.length - 3, files.length - 2, files.length - 1]
  return [...paths, ...indexes.map(index => join(dir, files[index]))]
}

async function rejected(path) {
  try { await parseJUnitXmlFiles([path]); return { rejected: false, reason: "accepted" } }
  catch (error) { return { rejected: true, reason: error.message } }
}

const input = options(process.argv.slice(2)), temporary = await mkdtemp(join(tmpdir(), "j4a-junit-qa-"))
try {
  const actual = await select(input["candidate-dir"]), pin = JSON.parse(await readFile(input.pin, "utf8"))
  await assertPinProvenance({ candidate: input["candidate-dir"], paths: actual, pin })
  if (input["provenance-only"]) {
    console.log(JSON.stringify({ status: "PASS", provenance: "verified", files: actual.length }))
    process.exitCode = 0
  } else {
  const syntheticPaths = []
  for (const [name, source] of validSynthetic) { const path = join(temporary, `valid-${name}.xml`); await writeFile(path, source); syntheticPaths.push(path) }
  const valid = await parseJUnitXmlFiles([...actual, ...syntheticPaths]), parity = []
  for (let index = 0; index < actual.length; index += 1) parity.push({ name: pin.rows[index].path, pass: same(legacyClassNames(valid.results.get(actual[index])), pin.rows[index].result) })
  for (let index = 0; index < validSynthetic.length; index += 1) parity.push({ name: validSynthetic[index][0], pass: valid.results.has(syntheticPaths[index]) })
  const rows = []
  for (const entry of matrix) {
    const path = join(temporary, `matrix-${entry.name}.xml`); await writeFile(path, entry.source)
    if (entry.expected === "REJECT") { const result = await rejected(path); rows.push({ name: entry.name, expected: entry.expected, reason: entry.reason, ...result, pass: result.rejected }) }
    else { try { const result = await parseJUnitXmlFiles([path]); rows.push({ name: entry.name, expected: entry.expected, reason: entry.reason, rejected: false, pass: result.results.has(path) }) } catch (error) { rows.push({ name: entry.name, expected: entry.expected, reason: error.message, rejected: true, pass: false }) } }
  }
  const p = actual[0], encodedPath = Buffer.from(p).toString("base64"), encodedClass = Buffer.from("SampleTest").toString("base64")
  const protocol = `J4A-JUNIT-V1\tBEGIN\t1\nJ4A-JUNIT-V1\tROW\t0\t${encodedPath}\t1\t0\t0\t0\t${encodedClass}\t0\nJ4A-JUNIT-V1\tEND\t1\n`
  const malformed = [protocol.split("\n").slice(0, -2).join("\n"), `${protocol}trailing\n`, protocol.replace("\tEND\t1", `\tROW\t0\t${encodedPath}\t1\t0\t0\t0\t${encodedClass}\t0\nJ4A-JUNIT-V1\tEND\t1`)]
  const protocolRows = malformed.map((value, index) => { try { decodeJUnitProtocol(value, [p]); return { index, rejected: false } } catch (error) { return { index, rejected: true, reason: error.message } } })
  const result = { valid: { total: parity.length, passed: parity.filter(row => row.pass).length, rows: parity }, matrix: { total: rows.length, passed: rows.filter(row => row.pass).length, rejected: rows.filter(row => row.expected === "REJECT" && row.rejected).length, accepted_controls: rows.filter(row => row.expected === "ACCEPT" && row.pass).length, rows }, protocol: { total: protocolRows.length, passed: protocolRows.filter(row => row.rejected).length, rows: protocolRows }, receipt: valid.receipt, matrix_sha256: sha(Buffer.from(JSON.stringify(matrix.map(({ name, expected, reason }) => ({ name, expected, reason }))))) }
  result.pass = result.valid.total === result.valid.passed && result.matrix.total === result.matrix.passed && result.protocol.total === result.protocol.passed
  console.log(`${JSON.stringify(canonical(result), null, 2)}\n`)
  if (!result.pass) process.exitCode = 1
  }
} finally { await rm(temporary, { recursive: true, force: true }) }
