#!/usr/bin/env node

import { readdir, readFile } from "node:fs/promises"
import { resolve } from "node:path"

import { parseJUnitXmlFiles } from "./parse-junit-xml.mjs"

function parseOptions(values) {
  if (values.length !== 4) throw new Error("usage: verify-junit-xml --junit-dir <dir> --expected-classes <json-file>")
  const options = {}
  for (let index = 0; index < values.length; index += 2) {
    const name = values[index]
    const value = values[index + 1]
    if ((name !== "--junit-dir" && name !== "--expected-classes") || !value || name in options) throw new Error("usage: verify-junit-xml --junit-dir <dir> --expected-classes <json-file>")
    options[name] = value
  }
  if (!("--junit-dir" in options) || !("--expected-classes" in options)) throw new Error("usage: verify-junit-xml --junit-dir <dir> --expected-classes <json-file>")
  return options
}

async function expectedClasses(path) {
  let parsed
  try { parsed = JSON.parse(await readFile(path, "utf8")) }
  catch (error) { throw new Error(`expected classes file ${path} is unreadable JSON: ${error instanceof Error ? error.message : "unknown error"}`) }
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed) || Object.keys(parsed).length !== 1 || !Array.isArray(parsed.expected_classes)) throw new Error(`expected classes file ${path} must be {"expected_classes":[...]}`)
  const classes = parsed.expected_classes
  if (!classes.length || classes.some(name => typeof name !== "string" || !name)) throw new Error(`expected classes file ${path} must contain nonempty class names`)
  const sorted = [...classes].sort()
  if (new Set(sorted).size !== sorted.length) throw new Error(`expected classes file ${path} contains duplicate class names`)
  return sorted
}

async function xmlPaths(directory) {
  let entries
  try { entries = await readdir(directory, { withFileTypes: true }) }
  catch (error) { throw new Error(`JUnit directory ${directory} is unreadable: ${error instanceof Error ? error.message : "unknown error"}`) }
  const paths = entries.filter(entry => entry.isFile() && entry.name.endsWith(".xml")).map(entry => resolve(directory, entry.name)).sort()
  if (!paths.length) throw new Error(`JUnit directory ${directory} contains no XML suites`)
  return paths
}

function compareClasses(expected, actual) {
  const actualClasses = [...actual.keys()].sort()
  const missing = expected.filter(name => !actual.has(name))
  const unexpected = actualClasses.filter(name => !expected.includes(name))
  if (missing.length || unexpected.length) throw new Error(`JUnit class inventory mismatch: missing=${JSON.stringify(missing)} unexpected=${JSON.stringify(unexpected)}`)
}

export async function verifyJUnitXmlInventory({ junitDir, expectedClassesFile }) {
  const [expected, paths] = await Promise.all([expectedClasses(expectedClassesFile), xmlPaths(junitDir)])
  const { results, receipt } = await parseJUnitXmlFiles(paths)
  let executed = 0, failures = 0, errors = 0, skipped = 0
  const classes = new Map()
  for (const path of paths) {
    const result = results.get(path)
    if (!result) throw new Error(`JUnit parser omitted ${path}`)
    const names = Object.keys(result.classes)
    if (names.length !== 1) throw new Error(`JUnit parser returned invalid class result for ${path}`)
    const name = names[0]
    if (classes.has(name)) throw new Error(`JUnit class inventory contains duplicate suite ${name}`)
    classes.set(name, result.classes[name])
    executed += result.executed
    failures += result.failures
    errors += result.errors
    skipped += result.skipped
  }
  compareClasses(expected, classes)
  if (executed === 0) throw new Error("JUnit inventory executed zero tests")
  if (failures || errors) throw new Error(`JUnit inventory has failures=${failures} errors=${errors}`)
  return {
    status: "PASS",
    classes: Object.fromEntries([...classes.entries()].sort(([left], [right]) => left.localeCompare(right))),
    expected_classes: expected,
    files: paths.length,
    tests: { executed, failures, errors, skipped },
    receipt,
  }
}

async function main() {
  try {
    const options = parseOptions(process.argv.slice(2))
    const result = await verifyJUnitXmlInventory({
      junitDir: resolve(options["--junit-dir"]),
      expectedClassesFile: resolve(options["--expected-classes"]),
    })
    console.log(JSON.stringify(result, null, 2))
  } catch (error) {
    console.error(`FAIL ${error instanceof Error ? error.message : "unknown verification error"}`)
    process.exitCode = 1
  }
}

await main()
