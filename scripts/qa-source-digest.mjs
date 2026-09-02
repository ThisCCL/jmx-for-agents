#!/usr/bin/env node

import { spawnSync } from "node:child_process"
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { assertSourceDigestReceipt, computeSourceDigest, SOURCE_DIGEST_ALGORITHM } from "./source-digest.mjs"

const root = mkdtempSync(join(tmpdir(), "j4a-source-digest-qa-"))
const evidenceDir = join(root, ".omo/evidence/qa")
const listPath = join(evidenceDir, "intended-untracked.txt")
const run = (command, args) => {
  const result = spawnSync(command, args, { cwd: root, encoding: "utf8" })
  if (result.status !== 0) throw new Error(`${command} ${args.join(" ")}: ${result.stderr.trim()}`)
}
const put = (path, value) => {
  mkdirSync(join(root, path, ".."), { recursive: true })
  writeFileSync(join(root, path), value)
}
const inventory = paths => {
  mkdirSync(evidenceDir, { recursive: true })
  writeFileSync(listPath, `${[...paths].sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))).join("\n")}\n`)
}
const receipt = () => computeSourceDigest({ root, evidenceDir })
const rejection = (name, action, pattern) => {
  try { action() } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!pattern.test(message)) throw new Error(`${name}: wrong rejection: ${message}`)
    return { name, status: "REJECTED", reason: message.replace(root, "<tmp>") }
  }
  throw new Error(`${name}: unexpectedly accepted`)
}

try {
  run("git", ["init", "-q"])
  run("git", ["config", "user.email", "digest@example.invalid"])
  run("git", ["config", "user.name", "Digest QA"])
  const tracked = {
    "build.gradle": "plugins {}\n",
    "openspec/changes/change/tasks.md": "- [ ] 1.1 task\n",
    "scripts/tool.mjs": "export const tool = true\n",
    "src/main/java/A.java": "class A {}\n",
    "src/main/resources/a.bin": Buffer.from([1, 2, 3]),
    "src/test/java/ATest.java": "class ATest {}\n",
    ".omo/plans/change.md": "- [ ] 17. Final gates\n",
    ".omo/start-work/ledger.jsonl": "{\"event\":\"start\"}\n",
  }
  for (const [path, value] of Object.entries(tracked)) put(path, value)
  run("git", ["add", "."])
  run("git", ["commit", "-qm", "baseline"])
  put("scripts/new.mjs", "export const fresh = 1\n")
  inventory(["scripts/new.mjs"])
  const baseline = receipt()
  put(".omo/plans/change.md", "- [x] 17. Final gates\n")
  put(".omo/start-work/ledger.jsonl", "{\"event\":\"root-update\"}\n")
  put(".omo/evidence/root/receipt.json", "{}\n")
  const orchestration = receipt()
  if (orchestration.digest !== baseline.digest) throw new Error(".omo orchestration changed v2 digest")
  put(".omo/plans/change.md", tracked[".omo/plans/change.md"])
  put(".omo/start-work/ledger.jsonl", tracked[".omo/start-work/ledger.jsonl"])
  const trackedMutations = {
    source: ["src/main/java/A.java", "class A { int x; }\n"],
    build: ["build.gradle", "plugins { id 'java' }\n"],
    test: ["src/test/java/ATest.java", "class ATest { int x; }\n"],
    resource: ["src/main/resources/a.bin", Buffer.from([0, 255, 1])],
    script: ["scripts/tool.mjs", "export const tool = false\n"],
    openspec: ["openspec/changes/change/tasks.md", "- [x] 1.1 task\n"],
  }
  const changed = {}
  for (const [name, [path, value]] of Object.entries(trackedMutations)) {
    put(path, value)
    changed[name] = receipt().digest
    if (changed[name] === baseline.digest) throw new Error(`${name} mutation did not change digest`)
    put(path, tracked[path])
  }
  put("scripts/new.mjs", "export const fresh = 2\n")
  const intendedContent = receipt().digest
  if (intendedContent === baseline.digest) throw new Error("intended content did not change digest")
  put("scripts/new.mjs", "export const fresh = 1\n")
  rmSync(join(root, "scripts/new.mjs"))
  put("scripts/é.mjs", "export const fresh = 1\n")
  inventory(["scripts/é.mjs"])
  const intendedPath = receipt().digest
  if (intendedPath === baseline.digest) throw new Error("intended path did not change digest")
  rmSync(join(root, "scripts/é.mjs"))
  put("scripts/new.mjs", "export const fresh = 1\n")
  inventory(["scripts/new.mjs"])
  const rejected = []
  inventory([".omo/evidence/qa/ghost.mjs"])
  rejected.push(rejection("omo-intended-row", receipt, /\.omo orchestration path is forbidden/))
  inventory(["scripts/new.mjs", "scripts/new.mjs"])
  rejected.push(rejection("duplicate-row", receipt, /unique and sorted/))
  inventory(["../escape.mjs"])
  rejected.push(rejection("invalid-row", receipt, /invalid repository-relative path/))
  writeFileSync(listPath, "scripts/new.mjs\n\n")
  rejected.push(rejection("blank-row", receipt, /LF-terminated rows/))
  inventory(["scripts/missing.mjs"])
  rejected.push(rejection("missing-file", receipt, /missing or not a file/))
  inventory(["scripts/new.mjs"])
  put("docs/unexpected.txt", "unexpected\n")
  rejected.push(rejection("unexpected-peer", receipt, /unexpected non-evidence untracked path/))
  rmSync(join(root, "docs"), { recursive: true })
  put("src/main/resources/a.bin", Buffer.from([0, 1, 0, 255]))
  const binaryFirst = receipt().digest
  const binarySecond = receipt().digest
  if (binaryFirst !== binarySecond) throw new Error("binary diff digest is nondeterministic")
  put("src/main/resources/a.bin", tracked["src/main/resources/a.bin"])
  rejected.push(rejection("v1-receipt", () => assertSourceDigestReceipt({ schema_version: 1, algorithm: "j4a-property-graph-source-digest-v1", digest: baseline.digest }), /schema_version 2/))
  const report = {
    algorithm: SOURCE_DIGEST_ALGORITHM,
    status: "PASS",
    orchestration_invariant: orchestration.digest === baseline.digest,
    tracked_mutations: changed,
    intended_content_changed: intendedContent !== baseline.digest,
    intended_path_changed: intendedPath !== baseline.digest,
    binary_deterministic: binaryFirst === binarySecond,
    rejections: rejected,
    cleanup_complete: true,
  }
  process.stdout.write(`${JSON.stringify(report, null, 2)}\n`)
} finally {
  rmSync(root, { recursive: true, force: true })
}
