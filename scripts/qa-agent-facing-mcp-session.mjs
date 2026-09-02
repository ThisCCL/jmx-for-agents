import { spawn, spawnSync } from "node:child_process"
import { equal } from "./qa-agent-facing-assertions.mjs"

const MCP_MAIN = "io.github.thisccl.j4a.mcp.J4aMcpServer"
const TIMEOUT_MS = 60_000
const EXIT_GRACE_MS = 10_000

export class McpSession {
  constructor(jar, javaCommand, options = {}) {
    this.jar = jar
    this.javaCommand = javaCommand
    this.processControl = options.processControl || createProcessControl()
    this.exitGraceMs = options.exitGraceMs ?? EXIT_GRACE_MS
    this.child = null
    this.stderr = ""
    this.partial = ""
    this.nextId = 1
    this.pending = new Map()
    this.requests = []
    this.responses = []
    this.responseIds = new Set()
    this.protocolError = null
    this.lifecycleError = null
    this.exit = null
    this.exitResult = null
    this.closing = false
    this.closePromise = null
    this.cleanupError = null
  }

  async start() {
    this.child = spawn(this.javaCommand, ["-cp", this.jar, MCP_MAIN], {
      cwd: process.cwd(),
      env: { ...process.env, LC_ALL: "C.UTF-8" },
      detached: true,
      stdio: ["pipe", "pipe", "pipe"],
    })
    this.child.stdin.on("error", error => this.failLifecycle(error))
    this.child.stdout.setEncoding("utf8")
    this.child.stderr.setEncoding("utf8")
    this.child.stdout.on("data", chunk => this.consume(chunk))
    this.child.stderr.on("data", chunk => { this.stderr += chunk })
    this.exit = new Promise(resolveExit => {
      const finish = result => {
        if (this.exitResult) return
        this.exitResult = result
        if (result.error) this.failLifecycle(result.error)
        else if (!this.closing || result.code !== 0 || this.pending.size > 0) {
          this.failLifecycle(new Error(`MCP exited ${result.code} signal ${result.signal}`))
        }
        resolveExit(result)
      }
      this.child.once("error", error => finish({ code: null, signal: null, error }))
      this.child.once("close", (code, signal) => finish({ code, signal, error: null }))
    })
  }

  consume(chunk) {
    this.partial += chunk
    for (;;) {
      const newline = this.partial.indexOf("\n")
      if (newline < 0) return
      const line = this.partial.slice(0, newline).replace(/\r$/, "")
      this.partial = this.partial.slice(newline + 1)
      if (!line) continue
      let message
      try {
        message = JSON.parse(line)
      } catch {
        this.rejectAll(new Error(`MCP emitted non-JSON stdout: ${line.slice(0, 160)}`))
        continue
      }
      const id = String(message.id)
      const pending = this.pending.get(id)
      this.responses.push({ label: pending?.label || "unmatched-response", message })
      if (this.responseIds.has(id)) {
        this.failProtocol(new Error(`duplicate MCP response ID: ${id}`))
        continue
      }
      if (!pending) {
        this.failProtocol(new Error(`unmatched MCP response ID: ${id}`))
        continue
      }
      clearTimeout(pending.timer)
      this.pending.delete(id)
      this.responseIds.add(id)
      pending.resolve(message)
    }
  }

  rejectAll(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timer)
      pending.reject(error)
    }
    this.pending.clear()
  }

  failProtocol(error) {
    this.protocolError ||= error
    this.rejectAll(this.protocolError)
  }

  failLifecycle(error) {
    this.lifecycleError ||= error instanceof Error ? error : new Error(String(error))
    this.rejectAll(this.lifecycleError)
  }

  write(message) {
    const error = this.protocolError || this.lifecycleError
    if (error) return Promise.reject(error)
    const stdin = this.child?.stdin
    if (!stdin || stdin.destroyed || !stdin.writable || stdin.writableEnded) {
      const closed = new Error("MCP stdin is closed")
      this.failLifecycle(closed)
      return Promise.reject(this.lifecycleError)
    }
    return new Promise((resolveWrite, rejectWrite) => {
      try {
        stdin.write(`${JSON.stringify(message)}\n`, "utf8", writeError => {
          if (!writeError) {
            resolveWrite()
            return
          }
          this.failLifecycle(writeError)
          rejectWrite(this.lifecycleError)
        })
      } catch (writeError) {
        this.failLifecycle(writeError)
        rejectWrite(this.lifecycleError)
      }
    })
  }

  request(method, params, label) {
    const error = this.protocolError || this.lifecycleError
    if (error) return Promise.reject(error)
    const id = this.nextId++
    const message = { jsonrpc: "2.0", id, method, ...(params === undefined ? {} : { params }) }
    this.requests.push({ label, message })
    return new Promise((resolveResponse, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(String(id))
        this.tryKillProcessTree(this.child?.pid)
        reject(new Error(`MCP request timed out: ${label}`))
      }, TIMEOUT_MS)
      this.pending.set(String(id), { label, resolve: resolveResponse, reject, timer })
      this.write(message).catch(writeError => {
        const pending = this.pending.get(String(id))
        if (!pending) return
        clearTimeout(pending.timer)
        this.pending.delete(String(id))
        pending.reject(writeError)
      })
    })
  }

  notify(method, params) {
    const message = { jsonrpc: "2.0", method, params }
    this.requests.push({ label: method, message })
    return this.write(message)
  }

  async initialize() {
    const response = await this.request("initialize", {
      protocolVersion: "2025-06-18",
      capabilities: {},
      clientInfo: { name: "j4a-agent-facing-qa", version: "1.0.0" },
    }, "initialize")
    equal(response.result?.protocolVersion, "2025-06-18", "protocol version")
    equal(response.result?.serverInfo?.name, "j4a", "server name")
    await this.notify("notifications/initialized", {})
  }

  call(name, args, label) {
    return this.request("tools/call", { name, arguments: args }, label)
  }

  interrupt(error) {
    this.failLifecycle(error)
    const child = this.child
    if (!child) return
    if (!child.stdin.destroyed) child.stdin.destroy()
    this.tryKillProcessTree(child.pid)
  }

  async close() {
    if (this.closePromise) return this.closePromise
    this.closePromise = this.closeSession()
    return this.closePromise
  }

  async closeSession() {
    if (!this.child) return
    this.closing = true
    const child = this.child
    const processGroup = child.pid
    let closeError = null
    if (!this.lifecycleError && !this.protocolError && !child.stdin.destroyed && child.stdin.writable) {
      try {
        const response = await this.request("shutdown", {}, "shutdown")
        equal(response.result, null, "shutdown result")
      } catch (error) {
        closeError = error
      }
    }
    if (!child.stdin.destroyed && !child.stdin.writableEnded) child.stdin.end()
    if (this.lifecycleError || this.protocolError) this.tryKillProcessTree(child.pid)
    const exit = await this.waitForExit(child.pid)
    this.rejectAll(new Error("MCP session closed"))
    this.child = null
    if (this.cleanupError) throw this.cleanupError
    if (exit.error) throw exit.error
    if (exit.code !== 0) throw this.lifecycleError || new Error(`MCP exited ${exit.code} signal ${exit.signal}`)
    if (this.stderr.trim()) throw new Error(`MCP stderr was not empty: ${this.stderr.trim()}`)
    if (closeError) throw closeError
    if (this.protocolError) throw this.protocolError
    if (this.lifecycleError) throw this.lifecycleError
    const residual = processGroupMembers(processGroup)
    if (residual.length > 0) {
      this.tryKillProcessTree(processGroup)
      if (this.cleanupError) throw this.cleanupError
      throw new Error(`MCP process group leaked after shutdown: ${residual.join(",")}`)
    }
  }

  async waitForExit(pid) {
    let timer
    const deadline = new Promise(resolve => {
      timer = setTimeout(() => resolve({ timedOut: true, cleanupError: this.tryKillProcessTree(pid) }), this.exitGraceMs)
    })
    const outcome = await Promise.race([this.exit.then(exit => ({ exit })), deadline])
    clearTimeout(timer)
    if (!outcome.timedOut) return outcome.exit
    this.rejectAll(new Error("MCP session close timed out"))
    this.child = null
    throw outcome.cleanupError || this.cleanupError || new Error(`MCP did not exit within ${this.exitGraceMs}ms after shutdown`)
  }

  tryKillProcessTree(pid) {
    try {
      killProcessTree(pid, this.processControl)
      return null
    } catch (error) {
      const cleanupError = error instanceof Error ? error : new Error(String(error))
      this.cleanupError ||= cleanupError
      this.failLifecycle(cleanupError)
      return cleanupError
    }
  }
}

function processGroupMembers(processGroup) {
  if (process.platform === "win32") return []
  const result = spawnSync("ps", ["-eo", "pid=,pgid=,comm="], { encoding: "utf8" })
  if (result.status !== 0) throw new Error("unable to scan MCP process group after shutdown")
  return result.stdout.split(/\r?\n/).map(line => line.trim().split(/\s+/, 3))
    .filter(parts => parts.length === 3 && Number(parts[1]) === processGroup)
    .map(parts => `${parts[0]}:${parts[2]}`)
}

export function createProcessControl(overrides = {}) {
  return {
    platform: overrides.platform ?? process.platform,
    spawnSync: overrides.spawnSync ?? spawnSync,
    kill: overrides.kill ?? process.kill,
  }
}

export function killProcessTree(pid, control = createProcessControl(), signal = "SIGKILL") {
  if (!pid) return
  try {
    if (control.platform === "win32") {
      const result = control.spawnSync("taskkill", ["/pid", String(pid), "/t", "/f"], { stdio: "ignore" })
      if (result.error?.code === "ENOENT") {
        control.kill(pid, signal)
        return
      }
      if (result.error) throw new Error(`taskkill failed for pid ${pid}: ${result.error.message || String(result.error)}`)
      if (result.status !== 0 || result.signal) {
        throw new Error(`taskkill failed for pid ${pid}: status ${result.status} signal ${result.signal}`)
      }
      return
    }
    control.kill(-pid, signal)
  } catch (error) {
    if (error?.code !== "ESRCH") throw error
  }
}
