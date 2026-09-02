#!/usr/bin/env node
import { execFileSync } from "node:child_process"
import { createHash } from "node:crypto"
import { readFileSync } from "node:fs"
import path from "node:path"
import { fileURLToPath } from "node:url"

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..")
const manifestPath = path.join(root, "config", "release-license-obligations.json")
const embeddedManifestPath = "META-INF/j4a/release-license-obligations.json"

class LicenseVerificationError extends Error {
  constructor(category, detail) {
    super(detail)
    this.category = category
  }
}

function fail(category, detail) {
  throw new LicenseVerificationError(category, detail)
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex")
}

function parseManifest(bytes, label) {
  try {
    return JSON.parse(bytes.toString("utf8"))
  } catch {
    fail("malformed-manifest", label)
  }
}

function validateManifest(manifest) {
  if (manifest?.schemaVersion !== 1 || !Array.isArray(manifest.records) || !Array.isArray(manifest.texts)) {
    fail("malformed-manifest", "schema")
  }
  const ids = manifest.records.map(record => record.recordId)
  if (new Set(ids).size !== ids.length) fail("duplicate-record", "recordId")
  if (manifest.records.length !== 135 || manifest.artifactFiles !== 135) {
    fail("artifact-count", String(manifest.records.length))
  }
  const coordinates = new Set()
  for (const record of manifest.records) {
    if (!/^[^:\s]+:[^:\s]+:[^:\s]+$/.test(record.coordinate ?? "") || /unknown/i.test(record.coordinate)) {
      fail("unknown-coordinate", record.recordId ?? "missing")
    }
    coordinates.add(record.coordinate)
    if (record.recordId !== `${record.coordinate}:${record.artifact?.basename}`) {
      fail("record-identity", record.recordId ?? "missing")
    }
    if (!/^[a-f0-9]{64}$/.test(record.artifact?.sha256 ?? "")) {
      fail("artifact-hash", record.recordId)
    }
  }
  if (coordinates.size !== 134 || manifest.coordinates !== 134) {
    fail(coordinates.size < 134 ? "duplicate-coordinate" : "coordinate-count", String(coordinates.size))
  }

  const texts = new Map()
  for (const text of manifest.texts) {
    if (!/^[a-f0-9]{64}$/.test(text.sha256 ?? "") || texts.has(text.sha256)) {
      fail("duplicate-text", text.sha256 ?? "missing")
    }
    if (text.path !== `META-INF/j4a/release-licenses/${text.sha256}.txt`) {
      fail("text-path", text.sha256)
    }
    if (!Array.isArray(text.kinds)
        || text.kinds.some(kind => !["LICENSE", "NOTICE", "SUPPORTING"].includes(kind))) {
      fail("text-kind", text.sha256)
    }
    texts.set(text.sha256, text)
  }

  for (const record of manifest.records) {
    if (!record.license?.expression || /unknown/i.test(record.license.expression)) {
      fail("unknown-license", record.recordId)
    }
    if (!Array.isArray(record.license.files) || record.license.files.length === 0) {
      fail("missing-license-obligation", record.recordId)
    }
    for (const hash of record.license.files) {
      if (!texts.get(hash)?.kinds.includes("LICENSE")) fail("license-text-reference", record.recordId)
    }
    if (typeof record.notice?.required !== "boolean" || !Array.isArray(record.notice.files)) {
      fail("notice-obligation", record.recordId)
    }
    if (record.notice.required !== (record.notice.files.length > 0)) {
      fail("notice-obligation", record.recordId)
    }
    for (const hash of record.notice.files) {
      if (!texts.get(hash)?.kinds.includes("NOTICE")) fail("notice-text-reference", record.recordId)
    }
    for (const hash of record.supplemental?.files ?? []) {
      if (!texts.get(hash)?.kinds.includes("SUPPORTING")) fail("supplemental-text-reference", record.recordId)
    }
  }

  const noticeRequired = manifest.records.filter(record => record.notice.required).length
  const distinctNotices = new Set(manifest.records.flatMap(record => record.notice.files)).size
  if (noticeRequired !== 65 || manifest.noticeRequiredRecords !== 65) {
    fail("notice-record-count", String(noticeRequired))
  }
  if (distinctNotices !== 44 || manifest.distinctNoticeTexts !== 44) {
    fail("notice-text-count", String(distinctNotices))
  }
  if (manifest.upstreamLicense?.path !== "META-INF/j4a/LICENSE"
      || !/^[a-f0-9]{64}$/.test(manifest.upstreamLicense?.sha256 ?? "")) {
    fail("upstream-license-manifest", "invalid")
  }
  return { texts, coordinates: coordinates.size, noticeRequired, distinctNotices }
}

function zipEntries(jar) {
  try {
    return execFileSync("unzip", ["-Z1", jar], {
      encoding: "utf8",
      maxBuffer: 32 * 1024 * 1024,
      stdio: ["ignore", "pipe", "pipe"],
    }).split("\n").filter(Boolean)
  } catch {
    fail("malformed-zip", jar)
  }
}

function zipEntry(jar, entry) {
  try {
    return execFileSync("unzip", ["-p", jar, entry], {
      maxBuffer: 32 * 1024 * 1024,
      stdio: ["ignore", "pipe", "pipe"],
    })
  } catch {
    fail("missing-zip-entry", entry)
  }
}

function isLicenseOrNoticeEntry(entry) {
  if (entry.endsWith("/")) return false
  const basename = entry.slice(entry.lastIndexOf("/") + 1)
  return /(^|\/)(?:licenses?|notices?)(?:[._/-]|$)/i.test(entry)
    || /(^|[-_.])(?:license|notice)(?:[-_.]|$)/i.test(basename)
}

function verifyJar(jar) {
  const sourceBytes = readFileSync(manifestPath)
  const sourceManifest = parseManifest(sourceBytes, "source")
  const sourceSummary = validateManifest(sourceManifest)
  const entries = zipEntries(jar)
  if (new Set(entries).size !== entries.length) fail("duplicate-zip-entry", jar)
  const entrySet = new Set(entries)
  if (!entrySet.has(embeddedManifestPath)) fail("missing-manifest", embeddedManifestPath)

  const embeddedBytes = zipEntry(jar, embeddedManifestPath)
  const embeddedManifest = parseManifest(embeddedBytes, "embedded")
  validateManifest(embeddedManifest)
  if (!embeddedBytes.equals(sourceBytes)) fail("manifest-mismatch", embeddedManifestPath)

  if (!entrySet.has(sourceManifest.upstreamLicense.path)) {
    fail("missing-upstream-license", sourceManifest.upstreamLicense.path)
  }
  if (sha256(zipEntry(jar, sourceManifest.upstreamLicense.path)) !== sourceManifest.upstreamLicense.sha256) {
    fail("upstream-license-hash", sourceManifest.upstreamLicense.path)
  }

  const licenseHashes = new Set(sourceManifest.records.flatMap(record => record.license.files))
  const noticeHashes = new Set(sourceManifest.records.flatMap(record => record.notice.files))
  for (const text of sourceManifest.texts) {
    if (!entrySet.has(text.path)) {
      fail(licenseHashes.has(text.sha256) ? "missing-license-text" : "missing-notice-text", text.sha256)
    }
    if (sha256(zipEntry(jar, text.path)) !== text.sha256) {
      fail("text-hash-mismatch", text.sha256)
    }
  }

  const expectedLegalEntries = new Set([
    embeddedManifestPath,
    sourceManifest.upstreamLicense.path,
    ...sourceManifest.texts.map(text => text.path),
  ])
  const unaccounted = entries.filter(entry => isLicenseOrNoticeEntry(entry) && !expectedLegalEntries.has(entry))
  if (unaccounted.length) fail("unaccounted-notice", unaccounted.join(","))

  return {
    artifactFiles: sourceManifest.records.length,
    coordinates: sourceSummary.coordinates,
    noticeRequired: sourceSummary.noticeRequired,
    distinctNoticeTexts: sourceSummary.distinctNotices,
    licenseTexts: licenseHashes.size,
    missingLicenses: 0,
    missingNotices: 0,
    unaccountedNotices: 0,
  }
}

try {
  const args = process.argv.slice(2)
  if (args[0] === "--") args.shift()
  const [jar] = args
  if (!jar || args.length !== 1) fail("usage", "verify-release-licenses.mjs <jar>")
  process.stdout.write(`${JSON.stringify(verifyJar(path.resolve(jar)))}\n`)
} catch (error) {
  const category = error instanceof LicenseVerificationError ? error.category : "io-error"
  process.stderr.write(`${JSON.stringify({ category, detail: error.message })}\n`)
  process.exitCode = 1
}
