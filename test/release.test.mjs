import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { createHash } from "node:crypto"
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { pathToFileURL } from "node:url"
import { promisify } from "node:util"

import {
  assertPreparedTarball,
  isDirectRun,
  rejectOrdinaryPack,
  verifyBuiltVersionSurfaces,
  verifyVersionSync,
} from "../scripts/release.mjs"

const execFileAsync = promisify(execFile)

test("version authority derives release tags from package.json alone", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-version-"))
  try {
    const packageJsonPath = path.join(root, "package.json")
    await writeFile(packageJsonPath, JSON.stringify({ version: "9.8.7" }))

    assert.equal(await verifyVersionSync({ tag: "v9.8.7", packageJsonPath }), "9.8.7")
    await assert.rejects(
      verifyVersionSync({ tag: "9.8.7", packageJsonPath }),
      /tag must equal v9\.8\.7/,
    )
    await assert.rejects(
      verifyVersionSync({ tag: "v1.0.0", packageJsonPath }),
      /tag must equal v9\.8\.7/,
    )
    await writeFile(packageJsonPath, JSON.stringify({ version: "not-semver" }))
    await assert.rejects(
      verifyVersionSync({ tag: "vnot-semver", packageJsonPath }),
      /package\.json version must be SemVer-compatible/,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("release verification rejects wrapper, Java, or MCP version drift", async () => {
  const fixtureVersion = "7.6.5"
  const consistent = {
    wrapper: { stdout: `${fixtureVersion}\n`, stderr: "" },
    java: { stdout: `${fixtureVersion}\n`, stderr: "" },
    mcp: {
      stdout: `${JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        result: { serverInfo: { name: "j4a", version: fixtureVersion } },
      })}\n`,
      stderr: "",
    },
  }

  await verifyBuiltVersionSurfaces({
    root: process.cwd(),
    jarPath: path.join(process.cwd(), "build", "libs", `j4a-${fixtureVersion}-all.jar`),
    version: fixtureVersion,
    runSurface: async surface => consistent[surface],
  })

  for (const surface of ["wrapper", "java", "mcp"]) {
    const divergent = structuredClone(consistent)
    if (surface === "mcp") {
      divergent.mcp.stdout = divergent.mcp.stdout.replace(
        `"version":"${fixtureVersion}"`,
        '"version":"9.9.9"',
      )
    } else {
      divergent[surface].stdout = "9.9.9\n"
    }
    await assert.rejects(
      verifyBuiltVersionSurfaces({
        root: process.cwd(),
        jarPath: path.join(process.cwd(), "build", "libs", `j4a-${fixtureVersion}-all.jar`),
        version: fixtureVersion,
        runSurface: async candidate => divergent[candidate],
      }),
      new RegExp(`${surface} version mismatch`, "i"),
    )
  }
})

test("ordinary pack fails without the prepared release seam", () => {
  assert.throws(
    () => rejectOrdinaryPack(),
    /ordinary npm pack is disabled; use pnpm run release:prepare -- --tag v<version>/,
  )
})

test("ordinary npm and pnpm pack fail before creating a second tarball", async () => {
  for (const [command, args] of [
    [process.platform === "win32" ? "npm.cmd" : "npm", ["pack", "--dry-run"]],
    [process.platform === "win32" ? "pnpm.cmd" : "pnpm", ["pack", "--dry-run"]],
  ]) {
    await assert.rejects(
      execFileAsync(command, args, { maxBuffer: 1024 * 1024 }),
      error => {
        assert.match(`${error.stdout ?? ""}\n${error.stderr ?? ""}`, /ordinary npm pack is disabled/)
        return true
      },
    )
  }
})

test("publish input must be the byte-identical prebuilt tarball", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-publish-input-"))
  try {
    const tarballPath = path.join(root, "build", "release", "jmx-for-agents-j4a-1.0.0.tgz")
    const bytes = Buffer.from("exact smoke-tested tarball")
    const sha512 = createHash("sha512").update(bytes).digest("hex")
    await mkdir(path.dirname(tarballPath), { recursive: true })
    await writeFile(tarballPath, bytes)
    const manifestPath = path.join(root, "release-manifest.json")
    await writeFile(manifestPath, `${JSON.stringify({
      tarball: {
        file: "build/release/jmx-for-agents-j4a-1.0.0.tgz",
        sha512,
      },
      smoke: { tarballSha512: sha512 },
    })}\n`)

    assert.equal(await assertPreparedTarball({ root, manifestPath, tarballPath }), tarballPath)
    await writeFile(tarballPath, "modified bytes")
    await assert.rejects(
      assertPreparedTarball({ root, manifestPath, tarballPath }),
      /prebuilt tarball SHA-512 mismatch/,
    )
    await assert.rejects(
      assertPreparedTarball({ root, manifestPath, tarballPath: path.join(root, "second.tgz") }),
      /publish input must be jmx-for-agents-j4a-1\.0\.0\.tgz/,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("release direct-run guard recognizes Windows and POSIX argv paths", () => {
  const scriptPath = path.resolve("scripts", "release.mjs")
  const metaUrl = pathToFileURL(scriptPath).href

  assert.equal(isDirectRun(metaUrl, scriptPath), true)
  assert.equal(isDirectRun(metaUrl, path.join("scripts", "release.mjs")), true)
  assert.equal(isDirectRun(metaUrl, path.resolve("scripts", "build-release.mjs")), false)
})
