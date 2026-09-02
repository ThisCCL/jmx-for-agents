import { runApplyFailureContract } from "./qa-agent-facing-apply-failure-contract.mjs"
import { CATEGORY, MAX_BYTES, runCategoryContract } from "./qa-agent-facing-category-contract.mjs"

export async function runInteractionContract(mcp, context, commands, sizes) {
  const { artifactRecorder, pass, response, summary } = context
  const execution = { ...context, commands }
  summary.currentGuard = "CATEGORY_PAGING"
  const { expected, mcpJourney, cliJourney } = await runCategoryContract(mcp, response, execution, pass)
  summary.currentGuard = "APPLY_FAILURE_MATRIX"
  await runApplyFailureContract(mcp, response, execution)

  sizes.category = {
    category: CATEGORY,
    expectedEntries: expected.length,
    mcpPages: mcpJourney.pageBytes,
    cliPages: cliJourney.pageBytes,
    cursorMax: Math.max(...mcpJourney.cursorLengths, ...cliJourney.cursorLengths),
    componentTokenMax: Math.max(...mcpJourney.tokenLengths, ...cliJourney.tokenLengths),
    everyPageWithin4096: true,
    exactOrderNoGapsOrDuplicates: true,
    tokenRecovery: true,
  }
  artifactRecorder.writeJson("size-budget.json", {
    toolsListChars: sizes.toolsListChars,
    toolDescriptionChars: sizes.toolDescriptionChars,
    category: sizes.category,
    limits: { toolDescriptionCharsExclusive: 4096, toolsListCharsInclusive: 40_000,
      cursorAsciiBytesInclusive: 768, componentTokenAsciiBytesInclusive: 512,
      pageUtf8BytesInclusive: MAX_BYTES },
  })
  summary.interactionContract = {
    categoryPaging: sizes.category,
    cursorV3WireVersionRejectedWithRestart: true,
    tamperAndBindingRejected: true,
    exits: { schemaUsage: 2, acceptedSemantic: 3, infrastructure: 4, fatalUnknownOutcome: 4 },
    fatal: { oneRequestOneResponse: true, inspectBeforeRetry: true, replayed: false,
      runtimeAndRefsEvicted: true, sourceAndTargetHashesPreserved: true },
  }
  pass("CLI_and_MCP_category_pages_are_exact_FQCN_ordered_complete_and_within_4096_UTF8_bytes")
  pass("cursor_v3_tamper_binding_and_componentToken_recovery_contracts_are_live")
  pass("apply_schema_exit2_semantic_exit3_infrastructure_exit4_and_fatal_exit4_are_distinct")
  pass("fatal_apply_worker_kill_returned_one_response_required_inspect_before_retry_and_never_replayed")
  pass("fatal_runtime_and_session_refs_were_evicted_with_source_and_target_hashes_unchanged")
}
