import assert from "node:assert/strict"
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { runJ4a } from "../src/main.mjs"
import { responseFrom, sha256Of } from "./helpers/downloader.mjs"

test("runJ4a mcp starts the MCP Java entrypoint from an existing runtime jar", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-mcp-"))
  const fakeJava = await createFakeJava(workDir)
  const stdout = createRecorder()

  try {
    await mkdir(path.join(workDir, "cache"), { recursive: true })
    await writeFile(path.join(workDir, "cache", "j4a.jar"), jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["mcp"],
      env: {
        J4A_JAVA_COMMAND: fakeJava.command,
        PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
      },
      cacheDir: path.join(workDir, "cache"),
      javaCommand: fakeJava.command,
      stdout: stdout.write,
      releaseConfig: {
        jarUrl: "https://downloads.example.test/j4a.jar",
        jarSha256: sha256Of(jarBytes),
      },
      requestImpl: async () => {
        assert.fail("mcp must not download when the cached runtime jar is valid")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(stdout.text(), "")
    assert.deepEqual((await readFile(fakeJava.logPath, "utf8")).trim().split(/\r?\n/), [
      "-cp",
      path.join(workDir, "cache", "j4a.jar"),
      "io.github.thisccl.j4a.mcp.J4aMcpServer",
    ])
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a mcp installs a missing runtime without force before starting Java", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-mcp-"))
  const fakeJava = await createFakeJava(workDir)
  const stdout = createRecorder()
  const stderr = createRecorder()
  let requests = 0

  try {
    const result = await runJ4a({
      argv: ["mcp"],
      env: {
        J4A_JAVA_COMMAND: fakeJava.command,
        PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
      },
      cacheDir: path.join(workDir, "cache"),
      javaCommand: fakeJava.command,
      stdout: stdout.write,
      stderr: stderr.write,
      reporter: (message) => stderr.write(`${message}\n`),
      releaseConfig: {
        jarUrl: "https://downloads.example.test/j4a.jar",
        jarSha256: sha256Of(jarBytes),
      },
      requestImpl: async () => {
        requests += 1
        return responseFrom(jarBytes, {
          headers: { "content-type": "application/java-archive" },
        })
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(requests, 1)
    assert.equal(stdout.text(), "")
    assert.match(stderr.text(), /j4a:/)
    assert.equal(await readFile(path.join(workDir, "cache", "j4a.jar"), "utf8"), jarBytes)
    assert.deepEqual((await readFile(fakeJava.logPath, "utf8")).trim().split(/\r?\n/), [
      "-cp",
      path.join(workDir, "cache", "j4a.jar"),
      "io.github.thisccl.j4a.mcp.J4aMcpServer",
    ])
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a mcp does not start Java when runtime installation fails", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-mcp-"))
  const fakeJava = await createFakeJava(workDir)
  const stdout = createRecorder()
  const stderr = createRecorder()

  try {
    await assert.rejects(
      runJ4a({
        argv: ["mcp"],
        env: {
          J4A_JAVA_COMMAND: fakeJava.command,
          PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
        },
        cacheDir: path.join(workDir, "cache"),
        javaCommand: fakeJava.command,
        stdout: stdout.write,
        stderr: stderr.write,
        releaseConfig: {
          jarUrl: "https://downloads.example.test/j4a.jar",
          jarSha256: sha256Of("expected jar"),
        },
        requestImpl: async () => responseFrom("wrong jar", {
          headers: { "content-type": "application/java-archive" },
        }),
      }),
      /sha256 mismatch.*j4a install --force/is,
    )

    assert.equal(stdout.text(), "")
    assert.match(stderr.text(), /j4a:/)
    assert.equal(existsSync(fakeJava.logPath), false)
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
