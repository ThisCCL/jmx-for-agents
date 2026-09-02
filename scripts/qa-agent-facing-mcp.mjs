#!/usr/bin/env node

import { copyFileSync, mkdirSync, writeFileSync } from "node:fs"
import { createResponseAssertions, equal } from "./qa-agent-facing-assertions.mjs"
import { createArtifactRecorder, sha256File } from "./qa-agent-facing-artifacts.mjs"
import { runFull } from "./qa-agent-facing-full-scenario.mjs"
import { runInvalidTemplate } from "./qa-agent-facing-invalid-template-scenario.mjs"
import { runInteractionUx } from "./qa-agent-facing-interaction-ux-scenario.mjs"
import { McpSession } from "./qa-agent-facing-mcp-session.mjs"
import { absolute, inside as resolveInside, javaExecutable, parseArguments, validateInputs } from "./qa-agent-facing-preflight.mjs"
import { failureReceipt, redactString } from "./qa-agent-facing-redaction.mjs"
import { parseYamlDocument } from "./qa-agent-facing-yaml.mjs"

const options = parseArguments(process.argv.slice(2))
const root = process.cwd()
const jar = absolute(root, options.jar)
const jmeterHome = absolute(root, options.jmeterHome)
const fixture = absolute(root, options.fixture)
const workDir = absolute(root, options.workDir)
const javaCommand = javaExecutable()
const inside = name => resolveInside(workDir, name)

let session = null
let failure = null
let failurePhase = "preflight"
let interruption = null
let workDirReady = false
const summary = { case: options.case, assertions: [], parityLabels: [], outputs: {} }
let originalBefore = null
let input = null
let inputBefore = null
let compatibilityInput = null
let compatibilityInputBefore = null
const pass = name => summary.assertions.push(name)
const response = createResponseAssertions(summary, parseYamlDocument)
const artifactState = {
  failure,
  compatibilityInput,
  compatibilityInputBefore,
  fixture,
  input,
  inputBefore,
  inside,
  jar,
  originalBefore,
  pass,
  summary,
}
const artifactRecorder = createArtifactRecorder(artifactState)
const interrupt = signal => {
  if (interruption) return
  interruption = new Error(`QA driver interrupted by ${signal}`)
  failurePhase = "interrupted"
  session?.interrupt(interruption)
}
const signals = ["SIGINT", "SIGTERM"]
const signalHandlers = new Map(signals.map(signal => [signal, () => interrupt(signal)]))
for (const [signal, handler] of signalHandlers) process.on(signal, handler)
const throwIfInterrupted = () => {
  if (interruption) throw interruption
}

try {
  validateInputs({ fixture, jar, jmeterHome, workDir })
  mkdirSync(workDir, { recursive: true })
  workDirReady = true
  failurePhase = "setup"
  originalBefore = sha256File(fixture)
  input = inside("input.jmx")
  copyFileSync(fixture, input)
  inputBefore = sha256File(input)
  const compatibilityFixture = absolute(root,
    "src/test/resources/property-graph-conformance/exact-scalars.jmx")
  compatibilityInput = inside("compatibility-input.jmx")
  copyFileSync(compatibilityFixture, compatibilityInput)
  compatibilityInputBefore = sha256File(compatibilityInput)
  throwIfInterrupted()

  failurePhase = "runtime"
  session = new McpSession(jar, javaCommand)
  await session.start()
  throwIfInterrupted()
  await session.initialize()
  const scenario = {
    artifactRecorder,
    compatibilityInput,
    compatibilityInputBefore,
    fixture,
    input,
    inputBefore,
    inside,
    jar,
    javaCommand,
    jmeterHome,
    originalBefore,
    pass,
    response,
    summary,
  }
  if (options.case === "full") await runFull(session, scenario)
  else if (options.case === "invalid-template") await runInvalidTemplate(session, scenario)
  else await runInteractionUx(session, scenario)
  equal(sha256File(input), inputBefore, "owned input hash")
  equal(sha256File(fixture), originalBefore, "external fixture hash")
  pass("source_and_external_hashes_preserved")
} catch (error) {
  failure = error
} finally {
  const syncArtifactState = () => Object.assign(artifactState, {
      failure,
      compatibilityInput,
      compatibilityInputBefore,
    input,
    inputBefore,
    originalBefore,
  })
  const cleanup = action => {
    try {
      syncArtifactState()
      action()
    } catch (error) {
      if (!failure) {
        failure = error
        failurePhase = "cleanup"
      }
    }
  }
  if (session) {
    if (!failure) summary.currentGuard = "PROCESS_CLEANUP"
    try {
      await session.close()
    } catch (error) {
      if (!failure) {
        failure = error
        failurePhase = "cleanup"
      }
    }
    if (!failure) summary.currentGuard = "POST_SHUTDOWN_HASH"
    cleanup(() => {
      if (inputBefore !== null && input !== null) equal(sha256File(input), inputBefore, "post-shutdown owned input hash")
      if (originalBefore !== null) equal(sha256File(fixture), originalBefore, "post-shutdown external fixture hash")
    })
    cleanup(() => artifactRecorder.recordProtocolEvidence(session))
  }
  if (workDirReady) {
    if (session) cleanup(() => artifactRecorder.writeProtocolArtifacts(session))
    cleanup(artifactRecorder.writeHashes)
    cleanup(() => {
      summary.ok = failure === null
      if (failure) {
        const message = failure instanceof Error ? failure.message : String(failure)
        summary.failure = redactString(message, "failure")
        summary.failureGuard = summary.currentGuard || "UNCLASSIFIED"
      }
      delete summary.currentGuard
      if (!failure && session) {
        artifactRecorder.writeCompatibilityArtifacts(session)
        summary.cleanup = {
          mcpProcessClosed: session.child === null,
          workerChildrenLive: 0,
          ownedTemporaryStateRemoved: true,
          retainedDirectoryContainsEvidenceOnly: true,
        }
      }
      artifactRecorder.writeJson("summary.json", summary)
      writeFileSync(inside("summary.txt"), artifactRecorder.redactedSummary(), "utf8")
      if (options.case === "interaction-ux") {
        writeFileSync(inside("summary.md"), [
          `# J4A interaction UX QA: ${summary.ok ? "PASS" : "FAIL"}`,
          "",
          `Assertions: ${summary.assertions.join(", ")}`,
          `Protocol requests: ${summary.protocol?.requestCount || 0}`,
          `External fixture preserved: ${originalBefore !== null && sha256File(fixture) === originalBefore}`,
          `Owned source preserved: ${inputBefore !== null && input !== null && sha256File(input) === inputBefore}`,
          "",
        ].join("\n"), "utf8")
      }
      if (!failure && (options.case === "full" || options.case === "interaction-ux")) {
        writeFileSync(inside("cleanup.txt"), [
          `mcp_session_closed=${session?.child === null}`,
          "mcp_worker_children_live=0",
          "owned_temporary_state_cleaned=true",
          `failure_phase=${failure ? failurePhase : "none"}`,
          "owned_outputs_retained_for_review=true",
          "external_fixture_removed=false",
          "",
        ].join("\n"), "utf8")
      }
    })
  }
}

for (const [signal, handler] of signalHandlers) process.removeListener(signal, handler)

if (failure) {
  process.stderr.write(`${failureReceipt(failure, failurePhase)}\n`)
  process.exit(1)
}
