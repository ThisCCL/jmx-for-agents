import { existsSync, readdirSync, statSync } from "node:fs"
import { dirname, isAbsolute, join, resolve } from "node:path"
import { redactString } from "./qa-agent-facing-redaction.mjs"

const USAGE = "Usage: node scripts/qa-agent-facing-mcp.mjs --jar <path> --jmeter-home <path> --fixture <path> --work-dir <path> --case full|invalid-template|interaction-ux\n"

export function parseArguments(args) {
  const names = new Map([
    ["--jar", "jar"],
    ["--jmeter-home", "jmeterHome"],
    ["--fixture", "fixture"],
    ["--work-dir", "workDir"],
    ["--case", "case"],
  ])
  if (args.length !== names.size * 2) usage()
  const result = {}
  for (let index = 0; index < args.length; index += 2) {
    const key = names.get(args[index])
    if (!key || result[key] !== undefined || index + 1 >= args.length) {
      usage(`Unsupported or duplicate argument: ${args[index]}`)
    }
    result[key] = args[index + 1]
  }
  if (!Object.values(result).every(value => typeof value === "string" && value.length > 0)) {
    usage("Missing required argument")
  }
  if (!["full", "invalid-template", "interaction-ux"].includes(result.case)) usage(`Invalid case: ${result.case}`)
  return result
}

function usage(message) {
  if (message) process.stderr.write(`QA usage error code=QA_USAGE_ERROR detail=${redactString(message, "failure")}\n`)
  process.stderr.write(USAGE)
  process.exit(2)
}

export function absolute(root, path) {
  return isAbsolute(path) ? resolve(path) : resolve(root, path)
}

export function validateInputs(paths) {
  assertFile(paths.jar, "packaged jar")
  assertFile(paths.fixture, "fixture")
  for (const required of ["bin", "lib", "lib/ext", "bin/jmeter.properties", "bin/saveservice.properties", "bin/upgrade.properties"]) {
    if (!existsSync(join(paths.jmeterHome, required))) throw new Error(`Invalid JMeter home; missing ${required}`)
  }
  if (existsSync(paths.workDir)
    && (!statSync(paths.workDir).isDirectory() || readdirSync(paths.workDir).length > 0)) {
    throw new Error("work-dir must be absent or an empty directory")
  }
  if (paths.fixture === paths.workDir || paths.fixture.startsWith(`${paths.workDir}/`)) {
    throw new Error("fixture must be outside work-dir")
  }
}

export function javaExecutable() {
  const configured = process.env.JAVA_HOME
    ? resolve(process.cwd(), process.env.JAVA_HOME, "bin", "java")
    : ""
  if (configured && existsSync(configured)) return configured
  return "java"
}

export function inside(workDir, name) {
  const path = resolve(workDir, name)
  if (dirname(path) !== workDir) throw new Error(`refusing write outside work-dir: ${name}`)
  return path
}

function assertFile(path, label) {
  if (!existsSync(path) || !statSync(path).isFile()) throw new Error(`Missing required ${label}: ${path}`)
}
