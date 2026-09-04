import http from "node:http"
import https from "node:https"
import net from "node:net"
import tls from "node:tls"

const MAX_PROXY_RESPONSE_BYTES = 64 * 1024

export function requestUrl(targetUrl, timeoutMs, env = process.env) {
  return new Promise((resolve, reject) => {
    const target = new URL(targetUrl)
    const proxy = proxyUrlFor(target, env)
    const agent = proxy === null ? undefined : new HttpsProxyAgent(proxy, timeoutMs)
    const client = target.protocol === "https:" ? https : http
    let response
    const request = client.get(target, { agent }, (receivedResponse) => {
      response = receivedResponse
      receivedResponse.once("close", () => agent?.destroy())
      resolve(receivedResponse)
    })
    request.once("timeout", () => {
      const error = new Error("download failed: timeout")
      response?.destroy(error)
      request.destroy(error)
    })
    request.once("error", (error) => {
      agent?.destroy()
      reject(error)
    })
    request.setTimeout(timeoutMs)
  })
}

export function proxyUrlFor(target, env = process.env) {
  if (matchesNoProxy(target, firstEnvironmentValue(env, ["NO_PROXY", "no_proxy"]))) {
    return null
  }
  const names = target.protocol === "https:"
    ? ["HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"]
    : ["HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"]
  const configured = firstEnvironmentValue(env, names)
  if (configured === undefined) return null

  let proxy
  try {
    proxy = new URL(hasScheme(configured) ? configured : `http://${configured}`)
  } catch {
    throw new Error("download failed: proxy must be a valid host:port or URL")
  }
  if (proxy.protocol !== "http:" && proxy.protocol !== "https:") {
    throw new Error("download failed: proxy URL must use HTTP or HTTPS")
  }
  return proxy
}

class HttpsProxyAgent extends https.Agent {
  constructor(proxy, timeoutMs) {
    super({ keepAlive: false })
    this.proxy = proxy
    this.timeoutMs = timeoutMs
  }

  createConnection(options, callback) {
    const targetHost = stripBrackets(options.host)
    const targetPort = Number(options.port ?? 443)
    const authority = formatAuthority(targetHost, targetPort)
    let settled = false
    let proxyResponse = Buffer.alloc(0)
    let socket

    const finish = (error, connectedSocket) => {
      if (settled) {
        connectedSocket?.destroy()
        return
      }
      settled = true
      callback(error, connectedSocket)
    }
    const fail = (error) => {
      socket?.destroy()
      finish(error)
    }
    const connectOptions = {
      host: stripBrackets(this.proxy.hostname),
      port: Number(this.proxy.port || (this.proxy.protocol === "https:" ? 443 : 80)),
    }
    const connected = () => {
      const authentication = proxyAuthorization(this.proxy)
      socket.write([
        `CONNECT ${authority} HTTP/1.1`,
        `Host: ${authority}`,
        "Proxy-Connection: Keep-Alive",
        ...(authentication === null ? [] : [`Proxy-Authorization: ${authentication}`]),
        "",
        "",
      ].join("\r\n"))
    }
    const receiveProxyResponse = (chunk) => {
      proxyResponse = Buffer.concat([proxyResponse, chunk])
      if (proxyResponse.length > MAX_PROXY_RESPONSE_BYTES) {
        fail(new Error("download failed: proxy response headers are too large"))
        return
      }
      const headerEnd = proxyResponse.indexOf("\r\n\r\n")
      if (headerEnd < 0) return
      socket.removeListener("data", receiveProxyResponse)
      const statusLine = proxyResponse.subarray(0, headerEnd).toString("latin1").split("\r\n", 1)[0]
      const status = statusLine.match(/^HTTP\/1\.[01] (?<status>[0-9]{3})(?: |$)/)?.groups?.status
      if (status === undefined) {
        fail(new Error("download failed: invalid proxy CONNECT response"))
        return
      }
      if (status !== "200") {
        fail(new Error(`download failed: proxy CONNECT returned HTTP ${status}`))
        return
      }
      const remaining = proxyResponse.subarray(headerEnd + 4)
      if (remaining.length > 0) socket.unshift(remaining)
      const secureSocket = tls.connect({
        socket,
        servername: net.isIP(targetHost) === 0 ? targetHost : undefined,
        ALPNProtocols: ["http/1.1"],
      })
      secureSocket.setTimeout(this.timeoutMs, () => fail(new Error("download failed: timeout")))
      secureSocket.once("error", fail)
      secureSocket.once("secureConnect", () => {
        secureSocket.setTimeout(0)
        finish(undefined, secureSocket)
      })
    }
    socket = this.proxy.protocol === "https:"
      ? tls.connect({
        ...connectOptions,
        servername: net.isIP(connectOptions.host) === 0 ? connectOptions.host : undefined,
      }, connected)
      : net.connect(connectOptions, connected)
    socket.setTimeout(this.timeoutMs, () => fail(new Error("download failed: proxy timeout")))
    socket.once("error", fail)
    socket.on("data", receiveProxyResponse)
  }
}

function matchesNoProxy(target, configured) {
  if (configured === undefined) return false
  const targetHost = stripBrackets(target.hostname).toLowerCase()
  const targetPort = target.port || (target.protocol === "https:" ? "443" : "80")
  return configured.split(",").some((rawRule) => {
    let rule = rawRule.trim().toLowerCase()
    if (rule === "*") return true
    if (rule === "") return false
    let rulePort
    if (rule.startsWith("[")) {
      const bracket = rule.indexOf("]")
      if (bracket >= 0 && rule[bracket + 1] === ":") {
        rulePort = rule.slice(bracket + 2)
        rule = rule.slice(0, bracket + 1)
      }
    } else {
      const colon = rule.lastIndexOf(":")
      if (colon >= 0 && rule.indexOf(":") === colon && /^[0-9]+$/.test(rule.slice(colon + 1))) {
        rulePort = rule.slice(colon + 1)
        rule = rule.slice(0, colon)
      }
    }
    if (rulePort !== undefined && rulePort !== targetPort) return false
    const ruleHost = stripBrackets(rule.replace(/^\*?\./, ""))
    return targetHost === ruleHost || targetHost.endsWith(`.${ruleHost}`)
  })
}

function proxyAuthorization(proxy) {
  if (proxy.username === "" && proxy.password === "") return null
  const credentials = `${decodeURIComponent(proxy.username)}:${decodeURIComponent(proxy.password)}`
  return `Basic ${Buffer.from(credentials).toString("base64")}`
}

function firstEnvironmentValue(env, names) {
  for (const name of names) {
    const value = env?.[name]
    if (typeof value === "string" && value.trim() !== "") return value.trim()
  }
  return undefined
}

function hasScheme(value) {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(value)
}

function formatAuthority(host, port) {
  return net.isIP(host) === 6 ? `[${host}]:${port}` : `${host}:${port}`
}

function stripBrackets(host) {
  return host.startsWith("[") && host.endsWith("]") ? host.slice(1, -1) : host
}
