import assert from "node:assert/strict"
import { exec, execFile } from "node:child_process"
import { createHash } from "node:crypto"
import { access, chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { promisify } from "node:util"

import { installLocal } from "../scripts/local-install.mjs"

const execFileAsync = promisify(execFile)
const execAsync = promisify(exec)

test("package exposes the local install command", async () => {
  const packageJson = JSON.parse(await readFile(new URL("../package.json", import.meta.url), "utf8"))
  assert.equal(packageJson.scripts["local:install"], "node scripts/local-install.mjs")
})

test("local install creates a self-contained j4a prefix with the matching runtime jar", async () => {
  const root = await createFixtureRoot("first jar")
  const targetDir = path.join(root, "installed prefix")

  try {
    const result = await installLocal({ root, targetDir, build: async () => {} })

    assert.equal(await readFile(path.join(targetDir, "cache", "j4a.jar"), "utf8"), "first jar")
    assert.match(
      await readFile(path.join(targetDir, "package", "dist", "release-config.mjs"), "utf8"),
      new RegExp(sha256("first jar")),
    )
    assert.equal(result.jarSha256, sha256("first jar"))
    await assert.rejects(access(path.join(targetDir, "local-install.json")), { code: "ENOENT" })

    const command = path.join(targetDir, "bin", process.platform === "win32" ? "j4a.cmd" : "j4a")
    const { stdout } = await runInstalledCommand(command, ["probe"])
    const observed = JSON.parse(stdout)
    assert.deepEqual(observed.argv, ["probe"])
    assert.equal(path.resolve(observed.cacheDir), path.join(targetDir, "cache"))
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("local install needs no arguments and defaults to build/local-install", async () => {
  const root = await createFixtureRoot("default jar")

  try {
    const result = await installLocal({ root, build: async () => {} })

    assert.equal(result.targetDir, path.join(root, "build", "local-install"))
    assert.equal(
      await readFile(path.join(result.targetDir, "cache", "j4a.jar"), "utf8"),
      "default jar",
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("local install updates only an owned prefix and preserves unrelated files", async () => {
  const root = await createFixtureRoot("first jar")
  const targetDir = path.join(root, "installed")

  try {
    await installLocal({ root, targetDir, build: async () => {} })
    await writeFile(path.join(targetDir, "notes.txt"), "keep me", "utf8")
    await writeFile(path.join(root, "build", "libs", "j4a-1.0.0-all.jar"), "second jar", "utf8")

    await installLocal({ root, targetDir, build: async () => {} })

    assert.equal(await readFile(path.join(targetDir, "cache", "j4a.jar"), "utf8"), "second jar")
    assert.equal(await readFile(path.join(targetDir, "notes.txt"), "utf8"), "keep me")
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("local install can use a supplied jar without invoking the Gradle build", async () => {
  const root = await createFixtureRoot("default jar")
  const targetDir = path.join(root, "installed")
  const suppliedJar = path.join(root, "supplied.jar")
  const buildModes = []

  try {
    await writeFile(suppliedJar, "supplied jar", "utf8")
    await installLocal({
      root,
      targetDir,
      jar: suppliedJar,
      build: async (_root, options) => { buildModes.push(options) },
    })

    assert.deepEqual(buildModes, [{ buildJar: false }])
    assert.equal(await readFile(path.join(targetDir, "cache", "j4a.jar"), "utf8"), "supplied jar")
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("local install refuses a non-empty directory it does not own", async () => {
  const root = await createFixtureRoot("first jar")
  const targetDir = path.join(root, "existing")

  try {
    await mkdir(targetDir)
    await writeFile(path.join(targetDir, "user-file.txt"), "do not replace", "utf8")

    await assert.rejects(
      installLocal({ root, targetDir, build: async () => {} }),
      /not an empty j4a local install directory/,
    )
    assert.equal(await readFile(path.join(targetDir, "user-file.txt"), "utf8"), "do not replace")
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

async function createFixtureRoot(jarBytes) {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-local-install-test-"))
  await Promise.all([
    mkdir(path.join(root, "bin"), { recursive: true }),
    mkdir(path.join(root, "dist"), { recursive: true }),
    mkdir(path.join(root, "build", "libs"), { recursive: true }),
  ])
  await writeFile(path.join(root, "package.json"), JSON.stringify({
    name: "@jmx-for-agents/j4a",
    version: "1.0.0",
    type: "module",
  }), "utf8")
  await writeFile(path.join(root, "README.md"), "# fixture\n", "utf8")
  await writeFile(path.join(root, "LICENSE"), "fixture license\n", "utf8")
  await writeFile(path.join(root, "dist", "main.mjs"), "export const main = true\n", "utf8")
  await writeFile(path.join(root, "dist", "release-config.mjs"), "export const releaseConfig = {}\n", "utf8")
  const binPath = path.join(root, "bin", "j4a.js")
  await writeFile(
    binPath,
    "#!/usr/bin/env node\nprocess.stdout.write(JSON.stringify({ argv: process.argv.slice(2), cacheDir: process.env.J4A_CACHE_DIR }))\n",
    "utf8",
  )
  await chmod(binPath, 0o755)
  await writeFile(path.join(root, "build", "libs", "j4a-1.0.0-all.jar"), jarBytes, "utf8")
  return root
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex")
}

function runInstalledCommand(command, args) {
  return process.platform === "win32"
    ? execAsync([command, ...args].map(value => `"${value.replaceAll('"', '""')}"`).join(" "))
    : execFileAsync(command, args)
}
