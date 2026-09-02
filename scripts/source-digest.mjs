import { spawnSync } from "node:child_process"
import { createHash } from "node:crypto"
import { existsSync, readFileSync, statSync } from "node:fs"
import { isAbsolute, relative, resolve } from "node:path"

export const SOURCE_DIGEST_ALGORITHM = "j4a-property-graph-source-digest-v2"
export const SOURCE_DIGEST_SCHEMA_VERSION = 2
const DIFF_ARGS = ["diff", "--binary", "--no-ext-diff", "HEAD", "--", ".", ":(exclude).omo/**"]
const UNTRACKED_ARGS = ["ls-files", "--others", "--exclude-standard", "-z", "--", ".", ":(exclude).omo/**"]

export class SourceDigestError extends Error {
  constructor(path, reason) {
    super(`${path}: ${reason}`)
    this.path = path
    this.reason = reason
  }
}

function git(root, args) {
  const result = spawnSync("git", args, {
    cwd: root,
    encoding: null,
    env: { ...process.env, LANG: "C", LC_ALL: "C", GIT_OPTIONAL_LOCKS: "0" },
    maxBuffer: 64 * 1024 * 1024,
    timeout: 60_000,
  })
  if (result.error) throw new SourceDigestError("git", result.error.message)
  if (result.status !== 0) throw new SourceDigestError("git", `${args.join(" ")} exited ${result.status}: ${result.stderr.toString("utf8").trim()}`)
  return result.stdout
}

function fileSha256(path) {
  return createHash("sha256").update(readFileSync(path)).digest("hex")
}

function canonicalSort(paths) {
  return [...paths].sort((left, right) => Buffer.compare(Buffer.from(left), Buffer.from(right)))
}

function decodeGitPaths(bytes, label) {
  try { return new TextDecoder("utf-8", { fatal: true }).decode(bytes).split("\0").filter(Boolean) }
  catch { throw new SourceDigestError(label, "git returned a non-UTF-8 path; intended inventory is UTF-8 only") }
}

function readInventory(root, evidenceDir) {
  const listPath = resolve(evidenceDir, "intended-untracked.txt")
  if (!existsSync(listPath)) throw new SourceDigestError(listPath, "missing file")
  const source = readFileSync(listPath, "utf8")
  if (source.includes("\0")) throw new SourceDigestError(listPath, "NUL is forbidden")
  const body = source.slice(0, -1)
  if (!source.endsWith("\n") || source.includes("\r") || body.split("\n").some(row => row.length === 0)) throw new SourceDigestError(listPath, "expected nonempty LF-terminated rows")
  const paths = body.split("\n")
  const sorted = canonicalSort(paths)
  if (new Set(paths).size !== paths.length || paths.join("\n") !== sorted.join("\n")) throw new SourceDigestError(listPath, "paths must be unique and sorted by UTF-8 bytes")
  for (const path of paths) {
    if (path === ".omo" || path.startsWith(".omo/")) throw new SourceDigestError(listPath, `.omo orchestration path is forbidden: ${path}`)
    if (isAbsolute(path) || path.includes("\\") || path.split("/").includes("..") || path.split("/").includes(".")) throw new SourceDigestError(listPath, `invalid repository-relative path: ${path}`)
    const absolute = resolve(root, path)
    if (relative(root, absolute).startsWith("..") || !existsSync(absolute) || !statSync(absolute).isFile()) throw new SourceDigestError(listPath, `listed path is missing or not a file: ${path}`)
  }
  return { listPath, paths }
}

export function computeSourceDigest({ root, evidenceDir }) {
  const { listPath, paths } = readInventory(root, evidenceDir)
  const untracked = decodeGitPaths(git(root, UNTRACKED_ARGS), "git ls-files")
  const allowedNoise = path => path.startsWith("build/") || path.startsWith(".gradle/")
  for (const path of untracked) if (!paths.includes(path) && !allowedNoise(path)) throw new SourceDigestError(listPath, `unexpected non-evidence untracked path: ${path}`)
  for (const path of paths) if (!untracked.includes(path)) throw new SourceDigestError(listPath, `approved path is not untracked: ${path}`)
  const diff = git(root, DIFF_ARGS)
  const hash = createHash("sha256").update(Buffer.from(`${SOURCE_DIGEST_ALGORITHM}\0`)).update(diff)
  hash.update(Buffer.from("\0intended-untracked-v2\0"))
  const records = []
  for (const path of paths) {
    const pathBytes = Buffer.from(path)
    const sha256 = fileSha256(resolve(root, path))
    hash.update(Buffer.from(`${pathBytes.length}:`)).update(pathBytes).update(Buffer.from("\0"))
    hash.update(Buffer.from(`${sha256}\n`))
    records.push({ path, sha256 })
  }
  return {
    schema_version: SOURCE_DIGEST_SCHEMA_VERSION,
    algorithm: SOURCE_DIGEST_ALGORITHM,
    git_command: "git diff --binary --no-ext-diff HEAD -- . ':(exclude).omo/**'",
    git_diff_bytes: diff.length,
    git_diff_sha256: createHash("sha256").update(diff).digest("hex"),
    intended_untracked_count: records.length,
    intended_untracked: records,
    digest: hash.digest("hex"),
  }
}

export function assertSourceDigestReceipt(value, path = "source-digest receipt") {
  if (value === null || typeof value !== "object" || Array.isArray(value)) throw new SourceDigestError(path, "expected object")
  if (value.schema_version !== SOURCE_DIGEST_SCHEMA_VERSION) throw new SourceDigestError(path, `expected schema_version ${SOURCE_DIGEST_SCHEMA_VERSION}`)
  if (value.algorithm !== SOURCE_DIGEST_ALGORITHM) throw new SourceDigestError(path, `expected algorithm ${SOURCE_DIGEST_ALGORITHM}`)
  if (!/^[a-f0-9]{64}$/.test(value.digest)) throw new SourceDigestError(path, "invalid digest")
  return value
}
