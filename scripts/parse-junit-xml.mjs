import { spawn } from "node:child_process"
import { createHash } from "node:crypto"
import { existsSync } from "node:fs"
import { mkdtemp, readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { basename, delimiter, dirname, join } from "node:path"
import { fileURLToPath } from "node:url"

const PROTOCOL = "J4A-JUNIT-V1"
const SOURCE = fileURLToPath(new URL("./JUnitXmlBatchParser.java", import.meta.url))
const OUTPUT_LIMIT = 16 * 1024 * 1024
const TIMEOUT_MS = 120000

function toolchainAt(home) {
  const java = join(home, "bin", "java"), javac = join(home, "bin", "javac")
  return existsSync(java) && existsSync(javac) ? { java, javac } : null
}

async function isJava8(toolchain) {
  const version = await run(toolchain.java, ["-version"])
  return version.code === 0 && /version "1\.8\./.test(`${version.stdout}${version.stderr}`)
}

async function selectToolchain() {
  const strictHome = process.env.J4A_JAVA_HOME
  if (strictHome) {
    const strict = toolchainAt(strictHome)
    if (!strict) throw new Error(`JUnit JAXP parser: J4A_JAVA_HOME is missing bin/java or bin/javac: ${strictHome}`)
    if (!await isJava8(strict)) throw new Error("JUnit JAXP parser: J4A_JAVA_HOME must select JDK 8")
    return strict
  }
  const candidates = [process.env.JAVA_HOME].filter(Boolean)
  for (const home of candidates) {
    const candidate = toolchainAt(home)
    if (candidate && await isJava8(candidate)) return candidate
  }
  for (const directory of (process.env.PATH ?? "").split(delimiter)) {
    const candidate = { java: join(directory, "java"), javac: join(directory, "javac") }
    if (existsSync(candidate.java) && existsSync(candidate.javac) && await isJava8(candidate)) return candidate
  }
  throw new Error("JUnit JAXP parser: JDK 8 not found; set J4A_JAVA_HOME or JAVA_HOME")
}

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: options.cwd, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = Buffer.alloc(0), stderr = Buffer.alloc(0), timedOut = false, interrupted = ""
    const append = (current, chunk) => {
      const next = Buffer.concat([current, chunk])
      if (next.length > OUTPUT_LIMIT) { child.kill("SIGKILL"); throw new Error("JUnit JAXP parser output exceeded 16 MiB") }
      return next
    }
    child.stdout.on("data", chunk => { try { stdout = append(stdout, chunk) } catch (error) { reject(error) } })
    child.stderr.on("data", chunk => { try { stderr = append(stderr, chunk) } catch (error) { reject(error) } })
    const handlers = Object.fromEntries(["SIGINT", "SIGTERM"].map(signal => [signal, () => {
      interrupted = signal
      child.kill(signal)
    }]))
    for (const [signal, handler] of Object.entries(handlers)) process.once(signal, handler)
    const timer = setTimeout(() => { timedOut = true; child.kill("SIGKILL") }, options.timeout ?? TIMEOUT_MS)
    child.once("error", reject)
    child.once("close", (code, signal) => {
      clearTimeout(timer)
      for (const [name, handler] of Object.entries(handlers)) process.removeListener(name, handler)
      resolve({ code, signal, stdout: stdout.toString("utf8"), stderr: stderr.toString("utf8"), timedOut, interrupted })
    })
  })
}

function natural(value, field) {
  if (!/^(?:0|[1-9]\d*)$/.test(value)) throw new Error(`JUnit JAXP protocol: invalid ${field} ${JSON.stringify(value)}`)
  const result = Number(value)
  if (!Number.isSafeInteger(result)) throw new Error(`JUnit JAXP protocol: unsafe ${field}`)
  return result
}

function decode(value, field) {
  if (!/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(value)) throw new Error(`JUnit JAXP protocol: invalid base64 ${field}`)
  return Buffer.from(value, "base64").toString("utf8")
}

/** Decode the complete, ordered Java helper response. */
export function decodeJUnitProtocol(stdout, paths) {
  const lines = stdout.split("\n")
  if (lines.at(-1) === "") lines.pop()
  if (lines.length !== paths.length + 2) throw new Error(`JUnit JAXP protocol: expected ${paths.length + 2} records, found ${lines.length}`)
  const begin = lines[0].split("\t"), end = lines.at(-1).split("\t")
  if (begin.length !== 3 || begin[0] !== PROTOCOL || begin[1] !== "BEGIN" || natural(begin[2], "begin count") !== paths.length) throw new Error("JUnit JAXP protocol: invalid BEGIN record")
  if (end.length !== 3 || end[0] !== PROTOCOL || end[1] !== "END" || natural(end[2], "end count") !== paths.length) throw new Error("JUnit JAXP protocol: invalid END record")
  const results = new Map()
  for (let row = 0; row < paths.length; row += 1) {
    const fields = lines[row + 1].split("\t")
    if (fields.length !== 10 || fields[0] !== PROTOCOL || fields[1] !== "ROW") throw new Error(`JUnit JAXP protocol: malformed row ${row}`)
    const index = natural(fields[2], "row index")
    if (index !== row || results.has(paths[index])) throw new Error(`JUnit JAXP protocol: duplicate or out-of-order row ${index}`)
    if (decode(fields[3], "path") !== paths[index]) throw new Error(`JUnit JAXP protocol: path mismatch at row ${index}`)
    const executed = natural(fields[4], "tests"), failures = natural(fields[5], "failures")
    const errors = natural(fields[6], "errors"), skipped = natural(fields[7], "skipped")
    const className = decode(fields[8], "class"), attempts = natural(fields[9], "entity attempts")
    if (!className || attempts !== 0) throw new Error(`JUnit JAXP protocol: invalid class or entity attempts at row ${index}`)
    results.set(paths[index], { executed, failures, errors, skipped, classes: { [className]: executed } })
  }
  return results
}

function helperError(stderr) {
  const line = stderr.trim(), fields = line.split("\t")
  if (fields.length !== 6 || fields[0] !== PROTOCOL || fields[1] !== "ERROR") return line || "helper exited without an error record"
  const index = /^-?\d+$/.test(fields[2]) ? Number(fields[2]) : NaN
  if (!Number.isSafeInteger(index)) return "malformed helper ERROR index"
  try {
    const path = decode(fields[3], "error path") || "<startup>"
    const reason = decode(fields[4], "error reason")
    const attempts = natural(fields[5], "error entity attempts")
    return `${path}: ${reason}; entity-resolution-attempts=${attempts}`
  } catch (error) { return `malformed helper ERROR record: ${error.message}` }
}

/** Compile the Java 8 boundary once and structurally parse every JUnit file in one batch. */
export async function parseJUnitXmlFiles(paths) {
  if (!Array.isArray(paths) || paths.length === 0 || paths.some(path => typeof path !== "string" || !path)) throw new Error("JUnit JAXP parser: expected nonempty path array")
  const { javac, java } = await selectToolchain()
  const temporary = await mkdtemp(join(tmpdir(), "j4a-junit-jaxp-"))
  let helperHash = ""
  try {
    const source = await readFile(SOURCE)
    helperHash = createHash("sha256").update(source).digest("hex")
    const compile = await run(javac, ["-source", "8", "-target", "8", "-encoding", "UTF-8", "-d", temporary, SOURCE])
    if (compile.timedOut || compile.interrupted || compile.code !== 0) throw new Error(`JUnit JAXP compile failed${compile.interrupted ? ` (${compile.interrupted})` : ""}: ${compile.stderr.trim()}`)
    const parsed = await run(java, ["-cp", temporary, "JUnitXmlBatchParser", ...paths])
    if (parsed.timedOut) throw new Error("JUnit JAXP parser timed out")
    if (parsed.interrupted) throw new Error(`JUnit JAXP parser interrupted by ${parsed.interrupted}`)
    if (parsed.code !== 0) throw new Error(`JUnit JAXP parser rejected input: ${helperError(parsed.stderr)}`)
    return { results: decodeJUnitProtocol(parsed.stdout, paths), receipt: { protocol: PROTOCOL, helper_sha256: helperHash, files: paths.length, cleanup: true } }
  } finally {
    await rm(temporary, { recursive: true, force: true })
  }
}
