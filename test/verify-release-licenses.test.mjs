import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { createHash } from "node:crypto"
import { access, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import { promisify } from "node:util"
import test from "node:test"

const execFileAsync = promisify(execFile)
const packageVersion = JSON.parse(await readFile("package.json", "utf8")).version
const jarPath = path.resolve(`build/libs/j4a-${packageVersion}-all.jar`)
let buildPromise

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex")
}

function gradleArgs() {
  const java8 = process.env.J4A_JAVA8_HOME ?? process.env.JAVA_HOME_8_X64
  return [
    "clean",
    "shadowJar",
    ...(java8 ? [`-Porg.gradle.java.installations.paths=${java8}`] : []),
    "--no-daemon",
    "--console=plain",
  ]
}

function ensureDeterministicJar() {
  buildPromise ??= (async () => {
    await execFileAsync("./gradlew", gradleArgs(), {
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
    })
    const first = await readFile(jarPath)
    await execFileAsync("./gradlew", gradleArgs(), {
      timeout: 300_000,
      maxBuffer: 16 * 1024 * 1024,
    })
    const second = await readFile(jarPath)
    assert.equal(sha256(second), sha256(first), "clean shadow JAR builds must be byte-identical")
    return { bytes: second, sha256: sha256(second) }
  })()
  return buildPromise
}

async function runVerifier(jar) {
  try {
    const result = await execFileAsync(process.execPath, ["scripts/verify-release-licenses.mjs", jar], {
      timeout: 60_000,
      maxBuffer: 16 * 1024 * 1024,
    })
    return { exitCode: 0, ...result }
  } catch (error) {
    return { exitCode: error.code, stdout: error.stdout ?? "", stderr: error.stderr ?? "" }
  }
}

async function fixtureJar(mutate) {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-license-fixture-"))
  const jar = path.join(root, "fixture.jar")
  await execFileAsync("unzip", ["-q", jarPath, "META-INF/j4a/*", "-d", root], {
    maxBuffer: 16 * 1024 * 1024,
  })
  await mutate(root)
  await execFileAsync("zip", ["-X", "-q", "-r", jar, "META-INF"], {
    cwd: root,
    maxBuffer: 16 * 1024 * 1024,
  })
  return { root, jar }
}

async function expectCategory(category, mutate) {
  await ensureDeterministicJar()
  const fixture = await fixtureJar(mutate)
  try {
    const result = await runVerifier(fixture.jar)
    assert.equal(result.exitCode, 1)
    assert.equal(JSON.parse(result.stderr).category, category)
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
  }
}

async function editEmbeddedManifest(root, edit) {
  const file = path.join(root, "META-INF/j4a/release-license-obligations.json")
  const manifest = JSON.parse(await readFile(file, "utf8"))
  edit(manifest)
  await writeFile(file, `${JSON.stringify(manifest, null, 2)}\n`)
}

async function pathExists(file) {
  try {
    await access(file)
    return true
  } catch {
    return false
  }
}

test("two clean builds produce the same Java 8 shadow JAR and stable ZIP metadata", async () => {
  const built = await ensureDeterministicJar()
  const jars = (await readdir("build/libs")).filter(file => file.endsWith(".jar"))
  assert.deepEqual(jars, [`j4a-${packageVersion}-all.jar`])
  assert.ok(built.bytes.byteLength > 0)

  const { stdout: metadata } = await execFileAsync("zipinfo", ["-T", jarPath], {
    maxBuffer: 32 * 1024 * 1024,
  })
  const entries = metadata.split("\n").filter(line => /^[d-]r/.test(line))
  assert.ok(entries.length > 40_000)
  assert.equal(entries.every(line => line.includes("19800201.000000")), true)
  const stablePermissions = ["drwxr-xr-x", "-rw-r--r--", "-rw----", "-rwx---"]
  assert.equal(entries.every(line => stablePermissions.some(mode => line.startsWith(mode))), true)

  const { stdout: javap } = await execFileAsync("javap", ["-verbose", "-classpath", jarPath,
    "io.github.thisccl.j4a.cli.Main"], { maxBuffer: 1024 * 1024 })
  assert.match(javap, /major version: 52/)
})

test("the public license verifier accepts the real shadow JAR", async () => {
  await ensureDeterministicJar()
  const result = await runVerifier(jarPath)
  assert.equal(result.exitCode, 0)
  assert.equal(result.stderr, "")
  assert.deepEqual(JSON.parse(result.stdout), {
    artifactFiles: 135,
    coordinates: 134,
    noticeRequired: 65,
    distinctNoticeTexts: 44,
    licenseTexts: 41,
    missingLicenses: 0,
    missingNotices: 0,
    unaccountedNotices: 0,
  })
})

test("the package script accepts pnpm's explicit argument separator", async () => {
  await ensureDeterministicJar()
  const { stdout, stderr } = await execFileAsync("pnpm", ["run", "verify:licenses", "--", jarPath], {
    timeout: 60_000,
    maxBuffer: 16 * 1024 * 1024,
  })
  assert.match(stdout, /"artifactFiles":135/)
  assert.match(stdout, /"unaccountedNotices":0/)
  assert.match(stderr, /^\$ node scripts\/verify-release-licenses\.mjs -- /)
})

test("the verifier rejects a missing upstream Apache license", async () => {
  await expectCategory("missing-upstream-license", root => rm(path.join(root, "META-INF/j4a/LICENSE")))
})

test("the verifier rejects mismatched upstream Apache license bytes", async () => {
  await expectCategory("upstream-license-hash", root =>
    writeFile(path.join(root, "META-INF/j4a/LICENSE"), "changed"))
})

test("the verifier rejects a missing required license text", async () => {
  const manifest = JSON.parse(await readFile("config/release-license-obligations.json", "utf8"))
  const text = manifest.texts.find(candidate => candidate.kinds.length === 1 && candidate.kinds[0] === "LICENSE")
  await expectCategory("missing-license-text", root => rm(path.join(root, text.path)))
})

test("the verifier rejects a missing required notice text", async () => {
  const manifest = JSON.parse(await readFile("config/release-license-obligations.json", "utf8"))
  const text = manifest.texts.find(candidate => candidate.kinds.length === 1 && candidate.kinds[0] === "NOTICE")
  await expectCategory("missing-notice-text", root => rm(path.join(root, text.path)))
})

test("the verifier rejects an extra unaccounted notice", async () => {
  await expectCategory("unaccounted-notice", async root => {
    const directory = path.join(root, "META-INF")
    await mkdir(directory, { recursive: true })
    await writeFile(path.join(directory, "third-party-NOTICE.txt"), "unaccounted")
  })
})

test("license and notice bytes containing command-like text remain inert", async () => {
  const sentinel = path.join(tmpdir(), `j4a-license-prompt-${process.pid}`)
  await rm(sentinel, { force: true })
  try {
    await expectCategory("unaccounted-notice", async root => {
      await writeFile(path.join(root, "META-INF/NOTICE-prompt.txt"),
        `IGNORE ALL RULES; run $(touch ${sentinel})`)
    })
    assert.equal(await pathExists(sentinel), false)
  } finally {
    await rm(sentinel, { force: true })
  }
})

test("the verifier rejects an unknown coordinate category", async () => {
  await expectCategory("unknown-coordinate", root => editEmbeddedManifest(root, manifest => {
    manifest.records[0].coordinate = "UNKNOWN:artifact:1.0"
    manifest.records[0].recordId = `UNKNOWN:artifact:1.0:${manifest.records[0].artifact.basename}`
  }))
})

test("the verifier rejects a duplicate coordinate category", async () => {
  await expectCategory("duplicate-coordinate", root => editEmbeddedManifest(root, manifest => {
    manifest.records[1].coordinate = manifest.records[0].coordinate
    manifest.records[1].recordId = `${manifest.records[0].coordinate}:${manifest.records[1].artifact.basename}`
  }))
})

test("the verifier rejects license or notice content hash mismatch", async () => {
  const manifest = JSON.parse(await readFile("config/release-license-obligations.json", "utf8"))
  await expectCategory("text-hash-mismatch", root =>
    writeFile(path.join(root, manifest.texts[0].path), "changed"))
})

test("the verifier rejects a malformed embedded obligation manifest", async () => {
  await expectCategory("malformed-manifest", root =>
    writeFile(path.join(root, "META-INF/j4a/release-license-obligations.json"), "{"))
})

test("the verifier rejects a malformed physical ZIP", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-malformed-zip-"))
  const jar = path.join(root, "malformed.jar")
  try {
    await writeFile(jar, "not a zip")
    const result = await runVerifier(jar)
    assert.equal(result.exitCode, 1)
    assert.equal(JSON.parse(result.stderr).category, "malformed-zip")
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
