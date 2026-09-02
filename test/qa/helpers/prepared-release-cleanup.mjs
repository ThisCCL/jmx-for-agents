import { rm } from "node:fs/promises"

export async function cleanupOwnedRelease({ close, workDir }) {
  let closeError
  try {
    await close()
  } catch (error) {
    closeError = asError(error)
  }
  try {
    await rm(workDir, { recursive: true, force: true })
  } catch (removeError) {
    if (closeError) preserveFailureAfterCleanup(closeError, asError(removeError))
    throw asError(removeError)
  }
  if (closeError) throw closeError
}

export function preserveFailureAfterCleanup(primary, cleanup) {
  throw new AggregateError([primary, cleanup], `${primary.message}; cleanup also failed: ${cleanup.message}`)
}

function asError(error) {
  return error instanceof Error ? error : new Error(String(error))
}
