import assert from "node:assert/strict"
import { readFile, realpath, stat } from "node:fs/promises"
import { resolve } from "node:path"
import { spawn } from "node:child_process"
import test from "node:test"

const verifier = resolve("scripts/verify-property-graph-gates.mjs")
const INPUTS = {
  root: "J4A_PROPERTY_GRAPH_EVIDENCE_ROOT",
  attempt: "J4A_PROPERTY_GRAPH_ATTEMPT",
  jar: "J4A_PROPERTY_GRAPH_JAR",
}

function usage(reason) {
  return `${reason}. Historical property-graph verification is manual and read-only; set explicit authoritative inputs ${INPUTS.root}=<evidence-root> ${INPUTS.attempt}=<attempt-N> ${INPUTS.jar}=<referenced-jar>`
}

function requiredInputs() {
  const values = Object.fromEntries(Object.entries(INPUTS).map(([key, name]) => [key, process.env[name]]))
  const missing = Object.entries(values).filter(([, value]) => !value).map(([key]) => INPUTS[key])
  if (missing.length) throw new Error(usage(`missing explicit authoritative input${missing.length === 1 ? "" : "s"}: ${missing.join(", ")}`))
  if (!/^attempt-\d+$/.test(values.attempt)) throw new Error(usage(`${INPUTS.attempt} must match attempt-N`))
  return values
}

async function requireDirectory(path, label) {
  let details
  try { details = await stat(path) }
  catch { throw new Error(usage(`${label} does not exist: ${path}`)) }
  if (!details.isDirectory()) throw new Error(usage(`${label} is not a directory: ${path}`))
}

async function requireFile(path, label) {
  let details
  try { details = await stat(path) }
  catch { throw new Error(usage(`${label} does not exist: ${path}`)) }
  if (!details.isFile()) throw new Error(usage(`${label} is not a file: ${path}`))
}

async function authoritativeInputs() {
  const inputs = requiredInputs()
  const root = resolve(inputs.root)
  const attemptPath = resolve(root, inputs.attempt)
  const jar = resolve(inputs.jar)
  await requireDirectory(root, INPUTS.root)
  await requireDirectory(attemptPath, `${INPUTS.root}/${INPUTS.attempt}`)
  await requireFile(jar, INPUTS.jar)

  const claimPath = resolve(attemptPath, "final-shadowjar/doneclaim.json")
  await requireFile(claimPath, "authoritative final-shadowjar claim")
  let claim
  try { claim = JSON.parse(await readFile(claimPath, "utf8")) }
  catch (error) { throw new Error(usage(`authoritative final-shadowjar claim is invalid JSON: ${error.message}`)) }
  const referencedJar = claim?.binary_observable?.jar?.absolute_path
  if (typeof referencedJar !== "string" || !referencedJar) throw new Error(usage("authoritative final-shadowjar claim has no referenced JAR"))
  await requireFile(resolve(referencedJar), "JAR referenced by authoritative final-shadowjar claim")
  if (await realpath(jar) !== await realpath(resolve(referencedJar))) {
    throw new Error(usage(`${INPUTS.jar} does not match the JAR referenced by the authoritative final-shadowjar claim`))
  }
  return { root, attempt: inputs.attempt }
}

function runVerifier(root, attempt) {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(process.execPath, [verifier, "--evidence-dir", root, "--attempt", attempt], {
      stdio: ["ignore", "pipe", "pipe"],
    })
    let stdout = "", stderr = ""
    child.stdout.on("data", chunk => { stdout += chunk })
    child.stderr.on("data", chunk => { stderr += chunk })
    child.once("error", rejectRun)
    child.once("close", code => resolveRun({ code, stdout, stderr }))
  })
}

test("explicit authoritative historical property-graph evidence passes the read-only verifier", async () => {
  const { root, attempt } = await authoritativeInputs()
  const result = await runVerifier(root, attempt)
  assert.equal(result.code, 0, result.stderr)
  assert.match(result.stdout, new RegExp(`GREEN: ${attempt} evidence`))
})
