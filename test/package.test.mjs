import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { createHash } from "node:crypto"
import { chmod, cp, mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"
import { promisify } from "node:util"
import test from "node:test"

import { buildDist } from "../scripts/build-release.mjs"
import { prepareRelease } from "../scripts/release.mjs"

const execFileAsync = promisify(execFile)
const PUBLIC_REGISTRY = "https://registry.npmjs.org/"
const PACKAGE_FILES = ["bin/j4a.js", "dist/**/*", "package.json", "README.md", "LICENSE"]
const PACKAGED_INVENTORY = [
  "LICENSE",
  "README.md",
  "bin/j4a.js",
  "dist/downloader.mjs",
  "dist/help.mjs",
  "dist/main.mjs",
  "dist/paths.mjs",
  "dist/proxy.mjs",
  "dist/release-config.mjs",
  "dist/runtime.mjs",
  "dist/skills.mjs",
  "dist/skills/j4a-master/SKILL.md",
  "dist/skills/j4a-master/references/apply.md",
  "dist/skills/j4a-master/references/categories.md",
  "dist/skills/j4a-master/references/components.md",
  "dist/skills/j4a-master/references/init.md",
  "dist/skills/j4a-master/references/opaque-values.md",
  "dist/skills/j4a-master/references/property-addresses.md",
  "dist/skills/j4a-master/references/read.md",
  "dist/skills/j4a-master/references/set.md",
  "dist/skills/j4a-master/references/structured-collections.md",
  "dist/skills/j4a-master/references/validate.md",
  "dist/skills/j4a-master/references/write-modes.md",
  "package.json",
]
const STABLE_GUARDS = ["LICENSE", "pnpm-lock.yaml", "build.gradle", "settings.gradle", "RELEASE.md"]

test("package manifest defines the exact public j4a contract", async () => {
  const packageJson = await readPackageJson()
  const workspaceYaml = await readFile("pnpm-workspace.yaml", "utf8")

  assert.equal(packageJson.name, "@jmx-for-agents/j4a")
  assert.equal(packageJson.version, "1.0.1")
  assert.equal(packageJson.private, false)
  assert.equal(packageJson.type, "module")
  assert.equal(packageJson.packageManager, "pnpm@11.5.1")
  assert.deepEqual(packageJson.bin, { j4a: "./bin/j4a.js" })
  assert.deepEqual(packageJson.engines, { node: ">=18" })
  assert.equal(packageJson.license, "Apache-2.0")
  assert.equal(packageJson.description, "Agent-facing JMX and Apache JMeter CLI with MCP support")
  assert.equal(packageJson.repository, "git+https://github.com/ThisCCL/jmx-for-agents.git")
  assert.equal(packageJson.homepage, "https://github.com/ThisCCL/jmx-for-agents#readme")
  assert.deepEqual(packageJson.bugs, { url: "https://github.com/ThisCCL/jmx-for-agents/issues" })
  assert.deepEqual(packageJson.publishConfig, { registry: PUBLIC_REGISTRY, access: "public" })
  assert.deepEqual(packageJson.files, PACKAGE_FILES)
  assert.equal(packageJson.dependencies, undefined)
  assert.equal(packageJson.scripts["release:prepare"], "node scripts/release.mjs")
  assert.equal(packageJson.scripts["release:verify-prebuilt"], "node scripts/release.mjs verify-prebuilt")
  assert.equal(packageJson.scripts["verify:licenses"], "node scripts/verify-release-licenses.mjs")
  assert.equal(packageJson.scripts.prepack, "node scripts/release.mjs reject-pack")
  assert.equal(packageJson.scripts.postinstall, undefined)
  assert.match(workspaceYaml, /useNodeVersion:\s*24\.11\.1/)
})

test("public package policy rejects private registry and missing access independently", async () => {
  const packageJson = await readPackageJson()

  assertPublicPublishPolicy(packageJson)
  const privateRegistry = structuredClone(packageJson)
  privateRegistry.publishConfig.registry = "https://registry.example.test/"
  assert.throws(() => assertPublicPublishPolicy(privateRegistry), /publishConfig\.registry/)
  const missingAccess = structuredClone(packageJson)
  delete missingAccess.publishConfig.access
  assert.throws(() => assertPublicPublishPolicy(missingAccess), /publishConfig\.access/)
})

test("package bin help documents mcp as the only auto-bootstrap command", async () => {
  const { stdout, stderr } = await execFileAsync(process.execPath, ["bin/j4a.js", "--help"], {
    maxBuffer: 1024 * 1024,
  })

  assert.match(stdout, /j4a mcp/)
  assert.match(stdout, /starts the Java MCP server/i)
  assert.match(stdout, /ordinary commands such as read, set, validate, components, categories, or apply/)
  assert.equal(stderr, "")
})

test("package bin reports the exact package version without an installed runtime", async () => {
  const packageJson = await readPackageJson()
  const { stdout, stderr } = await execFileAsync(process.execPath, ["bin/j4a.js", "--version"], {
    maxBuffer: 1024 * 1024,
  })

  assert.equal(stdout, `${packageJson.version}\n`)
  assert.equal(stderr, "")
})

test("Gradle derives project version and generated Java resource from package.json", async () => {
  const root = await createGradleVersionFixture("9.8.7")

  try {
    const command = path.join(root, process.platform === "win32" ? "gradlew.bat" : "gradlew")
    const { stdout, stderr } = await execFileAsync(command, ["--quiet", "--no-daemon", "properties", "processResources"], {
      cwd: root,
      maxBuffer: 4 * 1024 * 1024,
    })

    assert.match(stdout, /^version: 9\.8\.7$/m)
    assert.equal(stderr, "")
    assert.equal(
      await readFile(path.join(root, "build", "resources", "main", "META-INF", "j4a", "version.properties"), "utf8"),
      "version=9.8.7\n",
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("release preparation creates the one public tarball with allowlisted bytes and Apache license", async () => {
  const guards = await snapshotStableGuards()
  const trackedDiff = await snapshotTrackedDiff()
  const preparedRoot = await createPreparedPackageRoot()
  const extractDir = await mkdtemp(path.join(tmpdir(), "j4a-package-extract-"))
  const { version } = await readPackageJson()

  try {
    const tarballName = `jmx-for-agents-j4a-${version}.tgz`
    let smokeSha512
    const prepared = await prepareRelease({
      root: preparedRoot,
      tag: `v${version}`,
      runGradle: async () => {
        const libs = path.join(preparedRoot, "build", "libs")
        await mkdir(libs, { recursive: true })
        await writeFile(path.join(libs, `j4a-${version}-all.jar`), "package-test-jar")
      },
      verifyLicenses: async () => {},
      verifyVersions: async () => {},
      smoke: async tarballPath => {
        smokeSha512 = createHash("sha512").update(await readFile(tarballPath)).digest("hex")
      },
    })
    const tarball = prepared.tarballPath
    const verified = await execFileAsync(process.platform === "win32" ? "pnpm.cmd" : "pnpm", [
      "run",
      "release:verify-prebuilt",
      "--",
      tarball,
    ], { cwd: preparedRoot, maxBuffer: 1024 * 1024 })
    assert.deepEqual(JSON.parse(verified.stdout.trim().split("\n").at(-1)), { prebuiltTarball: tarball })

    assert.deepEqual((await readdir(path.dirname(tarball))).sort(), [
      `j4a-${version}.jar`,
      `j4a-${version}.jar.sha256`,
      tarballName,
    ])
    assert.ok((await readFile(tarball)).byteLength > 0)
    assert.match(sha256(await readFile(tarball)), /^[a-f0-9]{64}$/)
    assert.equal(prepared.manifest.tarball.sha512, smokeSha512)
    await execFileAsync("tar", ["-xzf", tarball, "-C", extractDir], { maxBuffer: 1024 * 1024 })

    const inventory = await tarInventory(tarball)
    assert.deepEqual(inventory, PACKAGED_INVENTORY)
    assert.equal(inventory.some(file => file.startsWith("src/") || file.startsWith("build/")), false)
    assert.equal(inventory.some(file => file.endsWith(".jar") || file.includes("postinstall")), false)
    const embedded = JSON.parse(await readFile(path.join(extractDir, "package", "package.json"), "utf8"))
    const mutation = process.env.J4A_PACKAGE_EMBEDDED_MUTATION
    if (mutation) delete embedded[mutation]
    assert.equal(embedded.name, "@jmx-for-agents/j4a")
    assert.equal(embedded.version, version)
    assert.equal(embedded.private, false, "embedded.private must remain false")
    assert.equal(embedded.type, "module", "embedded.type must remain module")
    assert.equal(embedded.packageManager, undefined)
    assert.equal(embedded.license, "Apache-2.0")
    assert.equal(embedded.description, "Agent-facing JMX and Apache JMeter CLI with MCP support")
    assert.deepEqual(embedded.repository, { type: "git", url: "git+https://github.com/ThisCCL/jmx-for-agents.git" })
    assert.equal(embedded.homepage, "https://github.com/ThisCCL/jmx-for-agents#readme")
    assert.deepEqual(embedded.bugs, { url: "https://github.com/ThisCCL/jmx-for-agents/issues" })
    assert.deepEqual(embedded.bin, { j4a: "./bin/j4a.js" })
    assert.deepEqual(embedded.engines, { node: ">=18" })
    assert.equal(embedded.dependencies, undefined)
    assert.deepEqual(embedded.files, PACKAGE_FILES, "embedded.files must retain the exact allowlist")
    assert.deepEqual(embedded.publishConfig, {
      registry: PUBLIC_REGISTRY,
      access: "public",
    })
    assert.equal(embedded.scripts?.postinstall, undefined)
    const packagedVersion = await execFileAsync(process.execPath, [
      path.join(extractDir, "package", "bin", "j4a.js"),
      "--version",
    ], { maxBuffer: 1024 * 1024 })
    assert.equal(packagedVersion.stdout, `${embedded.version}\n`)
    assert.equal(packagedVersion.stderr, "")
    const packagedLicense = await readFile(path.join(extractDir, "package", "LICENSE"))
    assert.deepEqual(packagedLicense, guards.LICENSE.bytes)
    assert.equal(sha256(packagedLicense), guards.LICENSE.sha256)
    await assertStableGuards(guards)
    await assertTrackedDiffUnchanged(trackedDiff)
  } finally {
    await rm(preparedRoot, { recursive: true, force: true })
    await rm(extractDir, { recursive: true, force: true })
  }
})

test("buildDist does not invoke Gradle or package a runtime jar", async () => {
  const workDir = await createBuildDistProject("9.9.9")

  try {
    await buildDist({ rootDir: workDir, gradleCommand: fakeGradleCommand(workDir) })
    await assert.rejects(readFile(path.join(workDir, "dist", "runtime", "j4a.jar"), "utf8"), /ENOENT/)
    assert.equal(existsSync(path.join(workDir, "gradle.log")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

async function readPackageJson() {
  return JSON.parse(await readFile("package.json", "utf8"))
}

function assertPublicPublishPolicy(packageJson) {
  assert.equal(packageJson.private, false, "package must be publishable")
  assert.equal(packageJson.publishConfig?.registry, PUBLIC_REGISTRY, "publishConfig.registry must target public npm")
  assert.equal(packageJson.publishConfig?.access, "public", "publishConfig.access must be public")
}

async function tarInventory(tarball) {
  const { stdout } = await execFileAsync("tar", ["-tzf", tarball], { maxBuffer: 1024 * 1024 })
  return stdout.trim().split("\n").map(file => file.replace(/^package\//, "")).filter(Boolean).sort()
}

async function snapshotStableGuards() {
  const records = await Promise.all(STABLE_GUARDS.map(async file => {
    const bytes = await readFile(file)
    return [file, { bytes, sha256: sha256(bytes) }]
  }))
  return Object.fromEntries(records)
}

async function assertStableGuards(guards) {
  for (const [file, expected] of Object.entries(guards)) {
    const actual = await readFile(file)
    assert.deepEqual(actual, expected.bytes, `${file} bytes changed during package test`)
    assert.equal(sha256(actual), expected.sha256, `${file} sha256 changed during package test`)
  }
}

async function snapshotTrackedDiff() {
  const options = { encoding: "buffer", maxBuffer: 4 * 1024 * 1024 }
  const [unstaged, staged] = await Promise.all([
    execFileAsync("git", ["diff", "--binary"], options),
    execFileAsync("git", ["diff", "--cached", "--binary"], options),
  ])
  return { unstaged: unstaged.stdout, staged: staged.stdout }
}

async function assertTrackedDiffUnchanged(expected) {
  const actual = await snapshotTrackedDiff()
  assert.deepEqual(actual.unstaged, expected.unstaged, "unstaged tracked diff changed during package test")
  assert.deepEqual(actual.staged, expected.staged, "staged tracked diff changed during package test")
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex")
}

async function createPreparedPackageRoot() {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-package-prepared-"))
  for (const file of ["package.json", "build.gradle", "README.md", "LICENSE"]) {
    await cp(file, path.join(root, file))
  }
  for (const directory of ["bin", "config", "scripts", "src", "skills"]) {
    await cp(directory, path.join(root, directory), { recursive: true })
  }
  return root
}

async function createBuildDistProject(version) {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-build-dist-"))
  await mkdir(path.join(workDir, "config"), { recursive: true })
  await mkdir(path.join(workDir, "src"), { recursive: true })
  await mkdir(path.join(workDir, "skills", "j4a-master"), { recursive: true })
  await writeFile(path.join(workDir, "package.json"), JSON.stringify({ version }), "utf8")
  await writeFile(path.join(workDir, "src", "main.mjs"), "export const value = 1\n", "utf8")
  await writeFile(path.join(workDir, "src", "runtime.mjs"), "export const runtime = 1\n", "utf8")
  await writeFile(path.join(workDir, "src", "release-config.mjs"), "export const releaseConfig = {}\n", "utf8")
  await writeFile(path.join(workDir, "build.gradle"), "plugins { id 'java' }\n", "utf8")
  await writeFile(path.join(workDir, "settings.gradle"), "rootProject.name = 'j4a-test'\n", "utf8")
  await writeFile(path.join(workDir, "skills", "j4a-master", "SKILL.md"), "# packaged skill\n", "utf8")
  await createFakeGradle(workDir)
  return workDir
}

async function createGradleVersionFixture(version) {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-gradle-version-"))
  await cp("build.gradle", path.join(root, "build.gradle"))
  await cp("settings.gradle", path.join(root, "settings.gradle"))
  await cp("gradle", path.join(root, "gradle"), { recursive: true })
  await cp("gradlew", path.join(root, "gradlew"))
  await cp("gradlew.bat", path.join(root, "gradlew.bat"))
  await chmod(path.join(root, "gradlew"), 0o755)
  await writeFile(path.join(root, "package.json"), `${JSON.stringify({ version })}\n`, "utf8")
  return root
}

async function createFakeGradle(workDir) {
  const command = fakeGradleCommand(workDir)
  const script = process.platform === "win32"
    ? `@echo off\r\n> "${path.join(workDir, "gradle.log")}" echo invoked\r\n`
    : `#!/usr/bin/env sh\nprintf '%s\\n' "$*" > "${path.join(workDir, "gradle.log").replaceAll("\\", "/")}"\n`
  await writeFile(command, script, "utf8")
  await chmod(command, 0o755)
}

function fakeGradleCommand(workDir) {
  return path.join(workDir, process.platform === "win32" ? "gradlew.bat" : "gradlew")
}
