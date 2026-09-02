import { spawn } from "node:child_process"

import { killProcessTree } from "../../../scripts/qa-agent-facing-mcp-session.mjs"

export function runMcpStartup({ command, cwd, env }) {
  return new Promise((resolve, reject) => {
    const child = spawn(commandForPlatform(command), argsForPlatform(command, ["mcp"]), {
      cwd,
      detached: process.platform !== "win32",
      env,
      stdio: ["pipe", "pipe", "pipe"],
    })
    let stdout = ""
    let stderr = ""
    let failure
    const timeout = setTimeout(() => {
      terminate(new Error("j4a mcp did not complete the startup session within 45 seconds"))
    }, 45_000)
    child.stdout.setEncoding("utf8")
    child.stderr.setEncoding("utf8")
    child.stdout.on("data", chunk => { stdout += chunk })
    child.stderr.on("data", chunk => { stderr += chunk })
    child.stdin.once("error", error => terminate(error))
    child.once("error", error => terminate(error))
    child.once("close", status => {
      clearTimeout(timeout)
      if (failure) {
        reject(failure)
        return
      }
      try {
        const messages = stdout.trim().split("\n").filter(Boolean).map(JSON.parse)
        resolve({
          status: status ?? 1,
          stderr,
          initialize: messages.find(message => message.id === 1),
          shutdown: messages.find(message => message.id === 2),
        })
      } catch (error) {
        reject(error)
      }
    })
    child.stdin.end([initializeRequest(), initializedNotification(), shutdownRequest(), ""].join("\n"))

    function terminate(error) {
      failure ||= error
      try {
        killProcessTree(child.pid)
      } catch (cleanupError) {
        failure ||= cleanupError
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

function initializeRequest() {
  return '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"release-rehearsal","version":"1.0.0"}}}'
}

function initializedNotification() {
  return '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
}

function shutdownRequest() {
  return '{"jsonrpc":"2.0","id":2,"method":"shutdown","params":{}}'
}
