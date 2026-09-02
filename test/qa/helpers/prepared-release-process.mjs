import { spawn } from "node:child_process"

import { killProcessTree } from "../../../scripts/qa-agent-facing-mcp-session.mjs"

export function runBounded(command, args, {
  cwd,
  env,
  allowNonZero = false,
  onSpawn = () => {},
  timeoutMs,
} = {}) {
  if (!Number.isInteger(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError("timeoutMs must be a positive integer")
  }
  return new Promise((resolve, reject) => {
    const child = spawn(commandForPlatform(command), argsForPlatform(command, args), {
      cwd,
      detached: process.platform !== "win32",
      env,
      stdio: ["ignore", "pipe", "pipe"],
    })
    let stdout = ""
    let stderr = ""
    let failure
    child.stdout.setEncoding("utf8")
    child.stderr.setEncoding("utf8")
    child.stdout.on("data", chunk => { stdout += chunk })
    child.stderr.on("data", chunk => { stderr += chunk })
    const timeout = setTimeout(() => terminate(new Error(`${command} timed out after ${timeoutMs}ms`)), timeoutMs)
    child.once("error", error => terminate(error))
    child.once("close", status => {
      clearTimeout(timeout)
      if (failure) {
        reject(failure)
        return
      }
      if (status !== 0 && !allowNonZero) {
        reject(new Error(`${command} ${args.join(" ")} exited ${status ?? 1}${stderr ? `: ${stderr.trim()}` : ""}`))
        return
      }
      resolve({ status: status ?? 1, stderr, stdout })
    })
    try {
      onSpawn(child.pid)
    } catch (error) {
      terminate(error)
    }

    function terminate(error) {
      failure ||= error instanceof Error ? error : new Error(String(error))
      try {
        killProcessTree(child.pid)
      } catch (cleanupError) {
        failure ||= cleanupError instanceof Error ? cleanupError : new Error(String(cleanupError))
      }
    }
  })
}

function commandForPlatform(command) {
  return process.platform === "win32" && /\.(cmd|bat)$/i.test(command) ? "cmd.exe" : command
}

function argsForPlatform(command, args) {
  return process.platform === "win32" && /\.(cmd|bat)$/i.test(command) ? ["/d", "/s", "/c", command, ...args] : args
}
