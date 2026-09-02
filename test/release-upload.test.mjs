import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { assertPreparedRelease, prepareRelease } from "../scripts/release.mjs"

test("prepareRelease assembles one local JAR, checksum, and exact prebuilt npm tarball", async () => {
  const root = await createReleaseFixture()
  const fixtureVersion = JSON.parse(await readFile(path.join(root, "package.json"), "utf8")).version
  const jarBytes = Buffer.from("deterministic shadow jar")
  const jarSha256 = createHash("sha256").update(jarBytes).digest("hex")
  const calls = []
  let smokeTarball
  try {
    const result = await prepareRelease({
      root,
      tag: "v1.0.0",
      runGradle: async ({ command, args, cwd }) => {
        calls.push({ surface: "gradle", command, args, cwd })
        await mkdir(path.join(root, "build", "libs"), { recursive: true })
        await writeFile(path.join(root, "build", "libs", "j4a-1.0.0-all.jar"), jarBytes)
      },
      verifyLicenses: async (jarPath) => {
        calls.push({ surface: "licenses", jarPath })
      },
      verifyVersions: async details => {
        calls.push({ surface: "versions", ...details })
      },
      smoke: async (tarballPath) => {
        smokeTarball = tarballPath
        calls.push({ surface: "smoke", sha512: sha512(await readFile(tarballPath)) })
      },
    })

    const releaseDir = path.join(root, "build", "release")
    assert.deepEqual((await readdir(releaseDir)).sort(), [
      "j4a-1.0.0.jar",
      "j4a-1.0.0.jar.sha256",
      "jmx-for-agents-j4a-1.0.0.tgz",
    ])
    assert.deepEqual(await readFile(result.jarPath), jarBytes)
    assert.equal(
      await readFile(result.checksumPath, "utf8"),
      `${jarSha256}  j4a-1.0.0.jar\n`,
    )
    assert.equal(result.tarballPath, smokeTarball)
    assert.equal(result.manifest.jar.url, "https://github.com/ThisCCL/jmx-for-agents/releases/download/v1.0.0/j4a-1.0.0.jar")
    assert.equal(result.manifest.jar.sha256, jarSha256)
    assert.equal(result.manifest.tarball.sha512, calls.find(call => call.surface === "smoke").sha512)
    assert.equal(result.manifest.tarball.integrity, `sha512-${Buffer.from(result.manifest.tarball.sha512, "hex").toString("base64")}`)
    assert.deepEqual(result.manifest.smoke, {
      tarballFile: "build/release/jmx-for-agents-j4a-1.0.0.tgz",
      tarballSha512: result.manifest.tarball.sha512,
    })
    assert.equal(result.manifest.tarball.inventory.includes("package/package.json"), true)
    assert.deepEqual(calls.slice(0, 3), [
      { surface: "gradle", command: "./gradlew", args: ["clean", "shadowJar"], cwd: root },
      { surface: "licenses", jarPath: result.jarPath },
      { surface: "versions", root, jarPath: result.jarPath, version: fixtureVersion },
    ])
    assert.deepEqual(
      JSON.parse(await readFile(path.join(root, "build", "release-manifest.json"), "utf8")),
      result.manifest,
    )
    assert.match(await readFile(path.join(root, "src", "release-config.mjs"), "utf8"), new RegExp(jarSha256))
    assert.equal((await readdir(root)).includes("dist"), true)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("prepareRelease fails closed for missing/multiple JARs and a second preparation", async () => {
  const missing = await createReleaseFixture()
  const multiple = await createReleaseFixture()
  const repeated = await createReleaseFixture()
  const noop = async () => {}
  try {
    await assert.rejects(prepareRelease({
      root: missing,
      tag: "v1.0.0",
      runGradle: noop,
      verifyLicenses: noop,
      verifyVersions: noop,
      smoke: noop,
    }), /expected exactly one shadow JAR, found 0/)

    await assert.rejects(prepareRelease({
      root: multiple,
      tag: "v1.0.0",
      runGradle: async () => {
        await mkdir(path.join(multiple, "build", "libs"), { recursive: true })
        await writeFile(path.join(multiple, "build", "libs", "first-1.0.0-all.jar"), "first")
        await writeFile(path.join(multiple, "build", "libs", "second-1.0.0-all.jar"), "second")
      },
      verifyLicenses: noop,
      verifyVersions: noop,
      smoke: noop,
    }), /expected exactly one shadow JAR, found 2/)

    const options = {
      root: repeated,
      tag: "v1.0.0",
      runGradle: async () => {
        await mkdir(path.join(repeated, "build", "libs"), { recursive: true })
        await writeFile(path.join(repeated, "build", "libs", "j4a-1.0.0-all.jar"), "jar")
      },
      verifyLicenses: noop,
      verifyVersions: noop,
      smoke: noop,
    }
    const prepared = await prepareRelease(options)
    await assertPreparedRelease({ root: repeated, manifestPath: prepared.manifestPath })
    await assert.rejects(prepareRelease(options), /release output already exists; refusing a second pack/)
    await writeFile(prepared.jarPath, "modified jar")
    await assert.rejects(
      assertPreparedRelease({ root: repeated, manifestPath: prepared.manifestPath }),
      /prepared JAR SHA-256 mismatch/,
    )
    await writeFile(prepared.jarPath, "jar")
    await writeFile(path.join(repeated, "src", "release-config.mjs"), "stale template\n")
    await assert.rejects(
      assertPreparedRelease({ root: repeated, manifestPath: prepared.manifestPath }),
      /generated release config is stale/,
    )
  } finally {
    await rm(missing, { recursive: true, force: true })
    await rm(multiple, { recursive: true, force: true })
    await rm(repeated, { recursive: true, force: true })
  }
})

test("prepareRelease rejects tag and malformed package version before invoking the build", async () => {
  const root = await createReleaseFixture()
  let buildCalls = 0
  const options = {
    root,
    runGradle: async () => { buildCalls += 1 },
    verifyLicenses: async () => {},
    verifyVersions: async () => {},
    smoke: async () => {},
  }
  try {
    await assert.rejects(prepareRelease({ ...options, tag: "v9.9.9" }), /tag must equal v1\.0\.0/)
    assert.equal(buildCalls, 0)
    const packageJson = JSON.parse(await readFile(path.join(root, "package.json"), "utf8"))
    packageJson.version = "invalid"
    await writeFile(path.join(root, "package.json"), `${JSON.stringify(packageJson)}\n`)
    await assert.rejects(
      prepareRelease({ ...options, tag: "v1.0.0" }),
      /package\.json version must be SemVer-compatible/,
    )
    assert.equal(buildCalls, 0)
    await assert.rejects(readFile(path.join(root, "build", "release-manifest.json")), /ENOENT/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("prepareRelease stops at the license gate before config, dist, or tar assembly", async () => {
  const root = await createReleaseFixture()
  try {
    await assert.rejects(prepareRelease({
      root,
      tag: "v1.0.0",
      runGradle: async () => {
        await mkdir(path.join(root, "build", "libs"), { recursive: true })
        await writeFile(path.join(root, "build", "libs", "j4a-1.0.0-all.jar"), "jar")
      },
      verifyLicenses: async () => { throw new Error("license obligations missing") },
      verifyVersions: async () => {},
      smoke: async () => {},
    }), /license obligations missing/)
    assert.deepEqual(await readdir(path.join(root, "build", "release")), ["j4a-1.0.0.jar"])
    await assert.rejects(readFile(path.join(root, "src", "release-config.mjs")), /ENOENT/)
    await assert.rejects(readdir(path.join(root, "dist")), /ENOENT/)
    await assert.rejects(readFile(path.join(root, "build", "release-manifest.json")), /ENOENT/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

async function createReleaseFixture() {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-release-prepare-"))
  await mkdir(path.join(root, "config"), { recursive: true })
  await mkdir(path.join(root, "src"), { recursive: true })
  await mkdir(path.join(root, "skills", "j4a-master"), { recursive: true })
  await mkdir(path.join(root, "bin"), { recursive: true })
  await writeFile(path.join(root, "package.json"), `${JSON.stringify({
    name: "@jmx-for-agents/j4a",
    version: "1.0.0",
    private: false,
    type: "module",
    bin: { j4a: "./bin/j4a.js" },
    license: "Apache-2.0",
    files: ["bin/j4a.js", "dist/**/*", "package.json", "README.md", "LICENSE"],
  }, null, 2)}\n`)
  await writeFile(path.join(root, "build.gradle"), "version = '1.0.0'\n")
  await writeFile(path.join(root, "config", "release.json"), `${JSON.stringify({
    owner: "ThisCCL",
    repository: "jmx-for-agents",
    artifactBase: "j4a",
  }, null, 2)}\n`)
  await writeFile(path.join(root, "src", "main.mjs"), "export const main = async () => {}\n")
  await writeFile(path.join(root, "skills", "j4a-master", "SKILL.md"), "# j4a\n")
  await writeFile(path.join(root, "bin", "j4a.js"), "#!/usr/bin/env node\n")
  await writeFile(path.join(root, "README.md"), "# j4a\n")
  await writeFile(path.join(root, "LICENSE"), "Apache License\n")
  return root
}

function sha512(bytes) {
  return createHash("sha512").update(bytes).digest("hex")
}
