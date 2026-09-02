import { createHash } from "node:crypto"
import { existsSync, readFileSync, writeFileSync } from "node:fs"
import { deepEqual, equal } from "./qa-agent-facing-assertions.mjs"
import { redactRecord, redactString } from "./qa-agent-facing-redaction.mjs"

export function sha256File(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex")
}

export function createArtifactRecorder(state) {
  const writeJson = (name, value) => {
    writeFileSync(state.inside(name), `${JSON.stringify(value, null, 2)}\n`, "utf8")
  }

  const writeProtocolArtifacts = mcp => {
    writeFileSync(state.inside("requests.jsonl"),
      `${mcp.requests.map(redactRecord).map(JSON.stringify).join("\n")}\n`, "utf8")
    writeFileSync(state.inside("responses.jsonl"),
      `${mcp.responses.map(redactRecord).map(JSON.stringify).join("\n")}\n`, "utf8")
  }

  const writeCompatibilityArtifacts = mcp => {
    const protocol = [
      ...mcp.requests.map(record => ({ direction: "request", ...redactRecord(record) })),
      ...mcp.responses.map(record => ({ direction: "response", ...redactRecord(record) })),
    ]
    writeFileSync(state.inside("protocol.jsonl"), `${protocol.map(JSON.stringify).join("\n")}\n`, "utf8")
    const hashes = {
      external: state.originalBefore === null ? null : {
        before: state.originalBefore,
        after: sha256File(state.fixture),
        preserved: sha256File(state.fixture) === state.originalBefore,
      },
      ownedInput: state.inputBefore === null || state.input === null ? null : {
        before: state.inputBefore,
        after: sha256File(state.input),
        preserved: sha256File(state.input) === state.inputBefore,
      },
      compatibilityInput: state.compatibilityInputBefore === null || state.compatibilityInput === null ? null : {
        before: state.compatibilityInputBefore,
        after: sha256File(state.compatibilityInput),
        preserved: sha256File(state.compatibilityInput) === state.compatibilityInputBefore,
      },
      failurePaths: state.summary.failureHashes || null,
      jar: sha256File(state.jar),
    }
    writeJson("hashes.json", hashes)
  }

  const writeHashes = () => {
    const records = [`jar ${sha256File(state.jar)} ${redactString(state.jar, "path")}`]
    if (state.originalBefore !== null) {
      records.unshift(
        `external_before ${state.originalBefore} ${redactString(state.fixture, "path")}`,
        `external_after ${sha256File(state.fixture)} ${redactString(state.fixture, "path")}`,
      )
    }
    if (state.inputBefore !== null && state.input !== null) {
      records.splice(2, 0,
        `owned_input_before ${state.inputBefore} input.jmx`,
        `owned_input_after ${sha256File(state.input)} input.jmx`)
    }
    for (const name of [
      "add-with-properties.jmx", "add-then-alias-set.jmx", "real-copy-write.jmx",
      "set-copy.jmx", "in-place.jmx", "apply-copy.jmx", "initialized.jmx",
    ]) {
      const path = state.inside(name)
      if (existsSync(path)) records.push(`output ${sha256File(path)} ${name}`)
    }
    writeFileSync(state.inside("hashes.txt"), `${records.join("\n")}\n`, "utf8")
  }

  const redactedSummary = () => {
    const lines = [
      `case=${state.summary.case}`,
      `ok=${state.summary.ok}`,
      `assertions=${state.summary.assertions.join(",")}`,
      `external_hash_preserved=${state.originalBefore !== null && sha256File(state.fixture) === state.originalBefore}`,
      `owned_input_hash_preserved=${state.inputBefore !== null && state.input !== null
        && sha256File(state.input) === state.inputBefore}`,
    ]
    if (state.failure) {
      const message = state.failure instanceof Error ? state.failure.message : String(state.failure)
      lines.push(`failure=${redactString(message, "failure")}`)
    }
    return `${lines.join("\n")}\n`
  }

  const recordMutationEvidence = (mcp, expectedLabels, postMutationState) => {
    const mutations = mcp.requests.filter(record => record.message.params?.name === "apply"
      || record.message.params?.name === "set")
    const labels = mutations.map(record => record.label)
    deepEqual(labels, expectedLabels, "exact intended mutation request labels")
    const requestIds = mutations.map(record => record.message.id)
    equal(new Set(requestIds).size, requestIds.length, "mutation request IDs must be unique")
    state.summary.mutations = {
      count: mutations.length,
      requestIds,
      labels,
      oneRequestPerIntendedMutation: true,
      noClientRetry: true,
      postMutationState,
    }
  }

  const recordProtocolEvidence = mcp => {
    const requests = mcp.requests.filter(record => record.message.id !== undefined)
    const requestIds = requests.map(record => record.message.id)
    const responseIds = mcp.responses.map(record => record.message.id)
    equal(new Set(requestIds).size, requestIds.length, "request IDs must be unique")
    equal(new Set(responseIds).size, responseIds.length, "response IDs must be unique")
    equal(responseIds.length, requestIds.length, "every request must have exactly one response")
    deepEqual([...responseIds].sort((left, right) => left - right),
      [...requestIds].sort((left, right) => left - right), "response IDs must match request IDs exactly")
    state.summary.protocol = {
      requestCount: requestIds.length,
      responseCount: responseIds.length,
      requestIds,
      responseIds,
      requestIdsUnique: true,
      oneResponsePerRequestId: true,
      singleRequestSingleResponse: true,
      serverInternalReplay: "not observable by the stdio driver",
      serverExecutionCountBoundary: "J4aMcpProtocolTest.failedMutationIsExecutedOnceAndNeverReplayed",
    }
    state.pass("single_request_single_response")
  }

  return {
    recordMutationEvidence,
    recordProtocolEvidence,
    redactedSummary,
    writeHashes,
    writeCompatibilityArtifacts,
    writeJson,
    writeProtocolArtifacts,
  }
}
