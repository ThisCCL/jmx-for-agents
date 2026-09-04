import { readFile } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

export function planReleaseOperations(input) {
  const release = requireReleaseInput(input)
  assertPreflight(release)
  assertNpmIdentity(release)
  assertPublishAuthentication(release)
  const operations = []
  const existing = release.release
  if (existing === null) {
    operations.push("create-draft-release")
  } else {
    if (existing.tag !== release.tag) throw new Error("release tag identity mismatch")
    if (typeof existing.draft !== "boolean") throw new TypeError("release draft state must be boolean")
  }

  const existingAssets = new Map((existing?.assets ?? []).map(asset => [asset.name, asset.sha256]))
  for (const asset of release.assets) {
    const remoteDigest = existingAssets.get(asset.name)
    if (remoteDigest === undefined) {
      operations.push(`upload:${asset.name}`)
    } else if (remoteDigest !== asset.sha256) {
      throw new Error(`release asset identity mismatch: ${asset.name}`)
    }
  }
  if (release.attestationExists !== true) operations.push("attest-jar")
  operations.push("verify-authenticated-assets")
  if (existing === null || existing.draft) operations.push("publish-release")
  operations.push("verify-public-download")
  if (release.npmIntegrity === null) {
    operations.push(`publish-npm:${release.tarball.name}`)
  } else {
    operations.push("verify-npm-integrity")
  }
  return operations
}

export async function executeReleaseOperations(operations, run) {
  if (!Array.isArray(operations)) throw new TypeError("operations must be an array")
  if (typeof run !== "function") throw new TypeError("run must be a function")
  for (const operation of operations) await run(operation)
}

export function assertSha256Digest(expectedRecord, actualRecord) {
  if (sha256Digest(expectedRecord) !== sha256Digest(actualRecord)) throw new Error("SHA-256 mismatch")
}

export function assertReleasePreflight({ tag, version, mainReachable, tagProtected, lockAcquired }) {
  assertTagVersion(tag, version)
  if (mainReachable !== true) throw new Error("tag commit is not reachable from origin/main")
  if (tagProtected !== true) throw new Error("active tag ruleset must protect v*")
  if (lockAcquired !== true) throw new Error("concurrent release already owns this tag")
}

export function findActiveTagRuleset(rulesets) {
  if (!Array.isArray(rulesets)) throw new TypeError("rulesets must be an array")
  return rulesets.some(ruleset => {
    const include = ruleset?.conditions?.ref_name?.include
    return ruleset?.enforcement === "active"
      && Array.isArray(include)
      && include.includes("refs/tags/v*")
  })
}

export function selectReleaseByTag(releases, tag) {
  if (!Array.isArray(releases)) throw new TypeError("releases must be an array")
  if (typeof tag !== "string" || !/^v\d+\.\d+\.\d+$/.test(tag)) {
    throw new TypeError("tag must be a v<SemVer> release tag")
  }
  const matches = releases.filter(release => release?.tag_name === tag)
  if (matches.length > 1) throw new Error(`multiple releases match tag ${tag}`)
  return matches[0] ?? null
}

function requireReleaseInput(input) {
  if (input === null || typeof input !== "object") throw new TypeError("release input must be an object")
  const release = input
  if (!Array.isArray(release.assets) || release.assets.length === 0) throw new TypeError("assets must be non-empty")
  for (const asset of release.assets) {
    if (!isDigest(asset?.sha256) || typeof asset?.name !== "string" || asset.name.length === 0) {
      throw new TypeError("each asset needs a name and SHA-256")
    }
  }
  if (typeof release.tarball?.name !== "string" || typeof release.tarball?.integrity !== "string") {
    throw new TypeError("tarball identity is required")
  }
  return release
}

function assertPreflight(release) {
  assertReleasePreflight(release)
}

function assertTagVersion(tag, version) {
  if (typeof version !== "string" || !/^\d+\.\d+\.\d+$/.test(version)) throw new TypeError("version must be SemVer")
  if (tag !== `v${version}`) throw new Error(`tag must equal v${version}`)
}

function assertNpmIdentity(release) {
  if (release.npmIntegrity !== null && release.npmIntegrity !== release.tarball.integrity) {
    throw new Error("npm integrity mismatch")
  }
}

function assertPublishAuthentication(release) {
  if (release.npmIntegrity === null && release.npmAuthentication === "unavailable") {
    throw new Error("npm publish authentication is unavailable")
  }
}

function isDigest(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value)
}

function sha256Digest(record) {
  const match = typeof record === "string" ? record.match(/^([a-f0-9]{64})\s{2,}\S/) : null
  if (match === null) throw new TypeError("SHA-256 record must contain a digest and filename")
  return match[1]
}

async function main(args) {
  if (args[0] === "assert-sha256" && args.length === 3) {
    assertSha256Digest(args[1], args[2])
    process.stdout.write("{\"sha256Match\":true}\n")
    return
  }
  if (args[0] === "select-release" && args.length === 3) {
    const releases = JSON.parse(await readFile(path.resolve(args[1]), "utf8"))
    process.stdout.write(`${JSON.stringify(selectReleaseByTag(releases, args[2]))}\n`)
    return
  }
  if (args.length !== 3 || args[0] !== "assert-preflight") {
    throw new Error("usage: node scripts/release-state-machine.mjs <assert-preflight|select-release> <input.json> <tag>")
  }
  const rulesets = JSON.parse(await readFile(path.resolve(args[1]), "utf8"))
  const tag = args[2]
  if (!/^v\d+\.\d+\.\d+$/.test(tag)) throw new Error("tag must be a v<SemVer> release tag")
  if (!findActiveTagRuleset(rulesets)) throw new Error("active tag ruleset must protect v*")
  process.stdout.write(`${JSON.stringify({ tag, tagProtected: true })}\n`)
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  try {
    await main(process.argv.slice(2))
  } catch (error) {
    process.stderr.write(`release state machine: ${error instanceof Error ? error.message : String(error)}\n`)
    process.exitCode = 1
  }
}
