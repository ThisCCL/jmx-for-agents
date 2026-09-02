#!/usr/bin/env node

import { lstat, readdir, readFile, realpath } from "node:fs/promises"
import { createHash } from "node:crypto"
import path from "node:path"
import { fileURLToPath } from "node:url"

const PUBLIC_DIRECTORIES = [
  ".github", ".omo/rules", "bin", "config", "docs", "gradle", "scripts", "skills", "src", "test",
]
const PUBLIC_ROOT_FILES = [
  ".gitignore", "LICENSE", "README.md", "README.zh-CN.md", "RELEASE.md", "build.gradle", "gradlew", "gradlew.bat",
  "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "settings.gradle",
]
const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const fixtureMarker = ["todo", "5-test", "-fixture-v", "1\n"].join("")
const suppressibleFixtureCategories = new Set([
  "company-identity", "legacy-java-identity", "legacy-package-identity", "legacy-artifact-path", "former-private-path",
])
const literal = (category, ...parts) => ({ category, bytes: Buffer.from(parts.join(""), "utf8") })
const fixedRules = [
  literal("legacy-java-identity", "cn", ".com", ".z", "ts"),
  literal("legacy-package-identity", "@", "z", "ts", "-tester", "/"),
  literal("private-host", "packhub", "-zt", ".z", "ts", ".com", ".cn"),
  literal("private-host", "z", "ts", ".com", ".cn"),
  literal("former-private-path", "z", "ts", "/auto", "test"),
  literal("former-private-path", "z", "ts", ".auto", "test"),
  literal("former-private-path", "z", "ts", "-kingdom", "-protocol", "-plugin"),
  literal("private-infrastructure", "Arti", "factory"),
  literal("private-infrastructure", "arti", "factory"),
  literal("private-infrastructure", "Min", "IO"),
  literal("private-infrastructure", "mi", "nio"),
  literal("private-infrastructure", "object", " store"),
  literal("private-infrastructure", "object", "-store"),
  literal("private-infrastructure", "create", "Min", "io", "Client"),
  literal("private-infrastructure", "fPut", "Object"),
  literal("private-infrastructure", "MIN", "IO", "_ACCESS", "_KEY"),
  literal("private-infrastructure", "MIN", "IO", "_BUCKET"),
  literal("private-infrastructure", "MIN", "IO", "_ENDPOINT"),
  literal("private-infrastructure", "MIN", "IO", "_PORT"),
  literal("private-infrastructure", "MIN", "IO", "_SECRET", "_KEY"),
  literal("private-infrastructure", "MIN", "IO", "_USE", "_SSL"),
]
const ipv4Octet = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"
const regexRules = [
  regexRule("company-identity", ["(?<![A-Za-z0-9])", "z", "ts", "(?![A-Za-z0-9])"], "gi"),
  regexRule("private-infrastructure", ["(?<![A-Za-z0-9])", "S", "3", "(?![A-Za-z0-9])"], "g", { textOnly: true }),
  regexRule("legacy-artifact-path", [
    "(?<![^/\\n])", "agent", "-tools", "/j4a/",
  ], "g"),
  regexRule("credential-key", [
    "\\b[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*_(?:",
    "ACCESS", "_KEY|", "SECRET", "_KEY|", "API", "_KEY|", "AUTH", "_TOKEN|",
    "PASSWORD|", "PRIVATE", "_KEY|", "CLIENT", "_SECRET)\\b",
  ], "g"),
  regexRule("rfc1918-ipv4", [
    "(?<![0-9.])(?:10\\.", ipv4Octet, "\\.", ipv4Octet, "\\.", ipv4Octet, "|",
    "172\\.(?:1[6-9]|2[0-9]|3[01])\\.", ipv4Octet, "\\.", ipv4Octet, "|",
    "192\\.168\\.", ipv4Octet, "\\.", ipv4Octet, ")(?![0-9.])",
  ], "g"),
  regexRule("user-absolute-path", ["(?:/", "home", "|/", "Users", ")/[^/\\s]+(?:/|$)"], "g"),
  regexRule("user-absolute-path", ["[A-Za-z]:\\\\", "Users", "\\\\[^\\\\\\r\\n]+\\\\"], "g"),
  regexRule("private-host", ["(?<![A-Za-z0-9.-])[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.(?:", "internal", "|", "corp", "|", "lan", ")(?![A-Za-z0-9.-])"], "gi"),
]

main().catch(error => {
  process.stderr.write(`Public sanitization failed closed: ${error.message}\n`)
  process.exitCode = 2
})

async function main() {
  const { root, fixtureAllowlist } = parseArguments(process.argv.slice(2))
  const result = await scanPublicRoot(root, fixtureAllowlist)
  if (result.findings.length > 0) {
    process.stderr.write(`${result.findings.map(formatFinding).join("\n")}\n`)
    process.exitCode = 1
  } else {
    process.stdout.write(`Public sanitization passed: ${result.fileCount} files scanned.\n`)
  }
}

async function scanPublicRoot(rootArgument, fixtureAllowlist) {
  const rootPath = path.resolve(rootArgument)
  const rootStat = await safeLstat(rootPath, "root")
  if (rootStat.isSymbolicLink() || !rootStat.isDirectory()) {
    throw new Error("--root must name a real directory, not a symlink")
  }
  if (await realpath(rootPath) !== rootPath) {
    throw new Error("--root must use its canonical path")
  }
  const allowlist = await validateFixtureAllowlist(rootPath, fixtureAllowlist)

  const files = []
  const directories = []
  for (const relativePath of PUBLIC_ROOT_FILES) {
    const absolutePath = path.join(rootPath, relativePath)
    const stat = await lstat(absolutePath).catch(error => error.code === "ENOENT" ? null : Promise.reject(error))
    if (stat) await collectEntry(rootPath, relativePath, stat, files, directories)
  }
  for (const relativePath of PUBLIC_DIRECTORIES) {
    const absolutePath = path.join(rootPath, relativePath)
    const stat = await lstat(absolutePath).catch(error => error.code === "ENOENT" ? null : Promise.reject(error))
    if (stat) await collectEntry(rootPath, relativePath, stat, files, directories)
  }

  files.sort(compareText)
  directories.sort(compareText)
  const findings = []
  for (const relativePath of directories) {
    findings.push(...findInValue(relativePath, Buffer.from(relativePath, "utf8"), true))
  }
  for (const relativePath of files) {
    const bytes = await readFile(path.join(rootPath, relativePath))
    const digest = createHash("sha256").update(bytes).digest("hex")
    findings.push(...findInValue(relativePath, Buffer.from(relativePath, "utf8"), true)
      .filter(finding => !isAllowedFixtureFinding(finding, digest, allowlist)))
    findings.push(...findInValue(relativePath, bytes, false)
      .filter(finding => !isAllowedFixtureFinding(finding, digest, allowlist)))
  }
  return { fileCount: files.length, findings: deduplicate(findings).sort(compareFindings) }
}

async function collectEntry(rootPath, relativePath, stat, files, directories) {
  const normalizedPath = relativePath.split(path.sep).join("/")
  if (stat.isSymbolicLink()) throw new Error(`unsafe symbolic link: ${normalizedPath}`)
  if (stat.isFile()) {
    if ((stat.mode & 0o444) === 0) throw new Error(`unreadable file: ${normalizedPath}`)
    files.push(normalizedPath)
    return
  }
  if (!stat.isDirectory()) throw new Error(`unsafe filesystem entry: ${normalizedPath}`)
  if ((stat.mode & 0o555) === 0) throw new Error(`unreadable directory: ${normalizedPath}`)
  directories.push(normalizedPath)
  const entries = await readdir(path.join(rootPath, relativePath), { withFileTypes: true })
  entries.sort((left, right) => compareText(left.name, right.name))
  for (const entry of entries) {
    const child = path.posix.join(normalizedPath, entry.name)
    const childStat = await safeLstat(path.join(rootPath, child), child)
    await collectEntry(rootPath, child, childStat, files, directories)
  }
}

async function safeLstat(target, label) {
  try {
    return await lstat(target)
  } catch (error) {
    throw new Error(`cannot inspect ${label}: ${error.message}`)
  }
}

function lineAt(text, offset) {
  let line = 1
  for (let index = 0; index < offset; index += 1) if (text.charCodeAt(index) === 10) line += 1
  return line
}

function findInValue(relativePath, bytes, pathname) {
  const findings = []
  const textState = pathname ? { binary: false, text: bytes.toString("utf8") } : decodeBytes(bytes)
  for (const rule of fixedRules) {
    for (let offset = bytes.indexOf(rule.bytes); offset !== -1; offset = bytes.indexOf(rule.bytes, offset + 1)) {
      findings.push(location(rule.category, relativePath, textState, offset, pathname))
    }
  }
  for (const rule of regexRules) {
    if (textState.binary && rule.textOnly) continue
    const expression = new RegExp(rule.source, rule.flags)
    for (let match = expression.exec(textState.text); match; match = expression.exec(textState.text)) {
      findings.push(location(rule.category, relativePath, textState, match.index, pathname))
      if (match[0].length === 0) expression.lastIndex += 1
    }
  }
  return findings
}

function decodeBytes(bytes) {
  try {
    const text = new TextDecoder("utf-8", { fatal: true }).decode(bytes)
    if (text.includes("\0")) return { binary: true, text: bytes.toString("latin1") }
    return { binary: false, text }
  } catch {
    return { binary: true, text: bytes.toString("latin1") }
  }
}

function location(category, relativePath, textState, offset, pathname) {
  if (pathname) return { category, relativePath, pathname: true }
  if (textState.binary) return { category, relativePath, byteOffset: offset }
  return { category, relativePath, line: lineAt(textState.text, offset) }
}

function deduplicate(findings) {
  const seen = new Set()
  return findings.filter(finding => {
    const key = [finding.category, finding.relativePath, finding.pathname, finding.line, finding.byteOffset].join("\0")
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

function regexRule(category, parts, flags, options = {}) {
  return { category, source: parts.join(""), flags, ...options }
}

async function validateFixtureAllowlist(rootPath, entries) {
  if (entries.length === 0) return new Set()
  if (rootPath === projectRoot) {
    throw new Error("test-fixture allowlists are unavailable for the repository root")
  }
  const markerPath = path.join(rootPath, ".public-sanitization-fixture")
  const marker = await readFile(markerPath, "utf8").catch(() => "")
  if (marker !== fixtureMarker) throw new Error("test-fixture allowlist requires the exact fixture marker")
  const allowlist = new Set()
  for (const entry of entries) {
    const fields = entry.split("=")
    if (fields.length !== 3) throw new Error("invalid test-fixture allowlist entry")
    const [relativePath, category, digest] = fields
    if (path.isAbsolute(relativePath) || relativePath.includes("\\") || relativePath.split("/").includes("..") || relativePath === "") {
      throw new Error("invalid test-fixture allowlist path")
    }
    if (!suppressibleFixtureCategories.has(category)) throw new Error(`test-fixture allowlist cannot suppress category: ${category}`)
    if (!/^[a-f0-9]{64}$/.test(digest)) throw new Error("invalid test-fixture allowlist digest")
    allowlist.add([relativePath, category, digest].join("\0"))
  }
  return allowlist
}

function isAllowedFixtureFinding(finding, digest, allowlist) {
  return allowlist.has([finding.relativePath, finding.category, digest].join("\0"))
}

function compareFindings(left, right) {
  return compareText(left.relativePath, right.relativePath)
    || Number(Boolean(left.pathname)) - Number(Boolean(right.pathname))
    || (left.line ?? left.byteOffset ?? 0) - (right.line ?? right.byteOffset ?? 0)
    || compareText(left.category, right.category)
}

function compareText(left, right) {
  return left < right ? -1 : left > right ? 1 : 0
}

function formatFinding(finding) {
  if (finding.pathname) return `[${finding.category}] ${finding.relativePath} (pathname)`
  if (finding.byteOffset !== undefined) return `[${finding.category}] ${finding.relativePath}@byte:${finding.byteOffset}`
  return `[${finding.category}] ${finding.relativePath}:${finding.line}`
}

function parseArguments(argumentsList) {
  let root = "."
  let rootSpecified = false
  const fixtureAllowlist = []
  for (let index = 0; index < argumentsList.length; index += 1) {
    const argument = argumentsList[index]
    if (argument === "--root" && index + 1 < argumentsList.length) {
      if (rootSpecified) throw new Error("--root may be specified only once")
      root = argumentsList[index + 1]
      rootSpecified = true
      index += 1
    } else if (argument === "--allow-test-fixture" && index + 1 < argumentsList.length) {
      fixtureAllowlist.push(argumentsList[index + 1])
      index += 1
    } else {
      throw new Error(`unknown or incomplete argument: ${argument}`)
    }
  }
  return { root, fixtureAllowlist }
}
