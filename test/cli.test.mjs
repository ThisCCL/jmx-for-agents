import assert from "node:assert/strict"
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { runJ4a } from "../src/main.mjs"
import { responseFrom, sha256Of } from "./helpers/downloader.mjs"

test("runJ4a prints wrapper help without an installed jar", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-"))
  const stdout = createRecorder()
  const stderr = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["--help"],
      cacheDir: path.join(workDir, "cache"),
      stdout: stdout.write,
      stderr: stderr.write,
      requestImpl: async () => {
        assert.fail("wrapper help must not download the jar")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.match(stdout.text(), /j4a install/)
    assert.match(stdout.text(), /install --with-skills/)
    assert.equal(stderr.text(), "")
    assert.equal(existsSync(path.join(workDir, "cache", "j4a.jar")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a reports the package version without cache, download, or Java", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-version-"))
  const packageVersion = JSON.parse(await readFile("package.json", "utf8")).version
  const stdout = createRecorder()
  const stderr = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["--version"],
      cacheDir: path.join(workDir, "missing-cache"),
      javaCommand: path.join(workDir, "missing-java"),
      stdout: stdout.write,
      stderr: stderr.write,
      requestImpl: async () => {
        assert.fail("version must not download the jar")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(stdout.text(), `${packageVersion}\n`)
    assert.equal(stderr.text(), "")
    assert.equal(existsSync(path.join(workDir, "missing-cache")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a rejects arguments after --version without touching the runtime", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-version-"))
  const stdout = createRecorder()
  const stderr = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["--version", "read"],
      cacheDir: path.join(workDir, "missing-cache"),
      javaCommand: path.join(workDir, "missing-java"),
      stdout: stdout.write,
      stderr: stderr.write,
      requestImpl: async () => {
        assert.fail("invalid version usage must not download the jar")
      },
    })

    assert.equal(result.exitCode, 2)
    assert.equal(stdout.text(), "")
    assert.match(stderr.text(), /--version.*additional arguments/i)
    assert.equal(existsSync(path.join(workDir, "missing-cache")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a prints install help without an installed jar", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-"))
  const stdout = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["install", "--help"],
      cacheDir: path.join(workDir, "cache"),
      stdout: stdout.write,
      requestImpl: async () => {
        assert.fail("install help must not download the jar")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.match(stdout.text(), /j4a install/)
    assert.match(stdout.text(), /install --with-skills/)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a fails fast with install guidance when the runtime is missing", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-"))
  let requested = false

  try {
    await assert.rejects(
      runJ4a({
        argv: ["read", "sample.jmx"],
        cacheDir: path.join(workDir, "cache"),
        requestImpl: async () => {
          requested = true
          return responseFrom("unused", {
            headers: { "content-type": "application/java-archive" },
          })
        },
      }),
      /j4a install/,
    )

    assert.equal(requested, false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a keeps ordinary Java forwarding behavior for installed runtimes", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-"))
  const fakeJava = await createFakeJava(workDir)

  try {
    await mkdir(path.join(workDir, "cache"), { recursive: true })
    await writeFile(path.join(workDir, "cache", "j4a.jar"), jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["read", "sample.jmx"],
      env: {
        J4A_JAVA_COMMAND: fakeJava.command,
        PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
      },
      cacheDir: path.join(workDir, "cache"),
      javaCommand: fakeJava.command,
      releaseConfig: {
        jarUrl: "https://downloads.example.test/j4a.jar",
        jarSha256: sha256Of(jarBytes),
      },
    })

    assert.equal(result.exitCode, 0)
    assert.deepEqual((await readFile(fakeJava.logPath, "utf8")).trim().split(/\r?\n/), [
      "-jar",
      path.join(workDir, "cache", "j4a.jar"),
      "read",
      "sample.jmx",
    ])
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

function createRecorder() {
  let buffer = ""
  return {
    write(chunk) {
      buffer += String(chunk)
    },
    text() {
      return buffer
    },
  }
}

async function createFakeJava(workDir) {
  const binDir = path.join(workDir, "bin")
  const command = path.join(binDir, process.platform === "win32" ? "java.cmd" : "java")
  const logPath = path.join(workDir, "java.log")

  await mkdir(binDir, { recursive: true })
  await writeFile(
    command,
    process.platform === "win32"
      ? `@echo off\r\n(for %%A in (%*) do @echo %%~A) > "${logPath}"\r\nexit /b 0\r\n`
      : `#!/usr/bin/env sh\nprintf '%s\\n' "$@" > "${logPath.replaceAll("\\", "/")}"\n`,
    "utf8",
  )
  await chmod(command, 0o755)

  return { binDir, command, logPath }
}
