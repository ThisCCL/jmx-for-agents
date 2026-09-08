import assert from "node:assert/strict"
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { createCliReporter, runJ4a } from "../src/main.mjs"
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
    assert.match(stdout.text(), /install --only-skills/)
    assert.match(stdout.text(), /runtime-info --json/)
    assert.equal(stderr.text(), "")
    assert.equal(existsSync(path.join(workDir, "cache", "j4a.jar")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("createCliReporter keeps progress on one TTY line and non-TTY logs quiet", () => {
  const tty = createRecorder()
  const ttyReporter = createCliReporter(tty.write, { interactive: true })
  ttyReporter("j4a: Downloading runtime...")
  ttyReporter("j4a: Downloading runtime [##------------------] 10% (1 MiB / 10 MiB)", { kind: "progress" })
  ttyReporter("j4a: Downloading runtime [####################] 100% (10 MiB / 10 MiB)", { kind: "progress" })
  ttyReporter("j4a: Runtime download complete.")
  assert.equal(
    tty.text(),
    "j4a: Downloading runtime...\n"
      + "\rj4a: Downloading runtime [##------------------] 10% (1 MiB / 10 MiB)"
      + "\rj4a: Downloading runtime [####################] 100% (10 MiB / 10 MiB)\n"
      + "j4a: Runtime download complete.\n",
  )

  const log = createRecorder()
  const logReporter = createCliReporter(log.write, { interactive: false })
  logReporter("j4a: Downloading runtime...")
  logReporter("transient progress", { kind: "progress" })
  logReporter("j4a: Runtime download complete.")
  assert.equal(log.text(), "j4a: Downloading runtime...\nj4a: Runtime download complete.\n")
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

test("runJ4a runtime-info reports independent wrapper and runtime identities without side effects", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-runtime-info-"))
  const packageVersion = JSON.parse(await readFile("package.json", "utf8")).version
  const cacheDir = path.join(workDir, "missing-cache")
  const stdout = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["runtime-info", "--json"],
      cacheDir,
      javaCommand: path.join(workDir, "missing-java"),
      stdout: stdout.write,
      releaseConfig: runtimeConfigFor("unused", { runtimeVersion: "7.6.5" }),
      requestImpl: async () => {
        assert.fail("runtime-info must not download the jar")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.deepEqual(JSON.parse(stdout.text()), {
      wrapperVersion: packageVersion,
      runtimeVersion: "7.6.5",
      launcherProtocol: 1,
      releaseTag: "runtime-v7.6.5",
      jarUrl: "https://downloads.example.test/j4a-7.6.5.jar",
      jarSha256: sha256Of("unused"),
      cacheRoot: cacheDir,
      jarPath: path.join(cacheDir, "runtimes", "7.6.5", "j4a.jar"),
    })
    assert.equal(existsSync(cacheDir), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a rejects an incompatible runtime launcher protocol before side effects", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-cli-runtime-info-"))
  const cacheDir = path.join(workDir, "missing-cache")

  try {
    await assert.rejects(runJ4a({
      argv: ["runtime-info", "--json"],
      cacheDir,
      javaCommand: path.join(workDir, "missing-java"),
      releaseConfig: runtimeConfigFor("unused", { launcherProtocol: 2 }),
      requestImpl: async () => {
        assert.fail("incompatible runtime metadata must not download the jar")
      },
    }), /launcher protocol 2.*supported protocol 1/i)
    assert.equal(existsSync(cacheDir), false)
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
    assert.match(stdout.text(), /install --only-skills/)
    assert.match(stdout.text(), /HTTPS_PROXY.*HTTP_PROXY/s)
    assert.match(stdout.text(), /scheme-less host:port/)
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
    const releaseConfig = runtimeConfigFor(jarBytes)
    const jarPath = path.join(workDir, "cache", "runtimes", releaseConfig.runtimeVersion, "j4a.jar")
    await mkdir(path.dirname(jarPath), { recursive: true })
    await writeFile(jarPath, jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["read", "sample.jmx"],
      env: {
        J4A_JAVA_COMMAND: fakeJava.command,
        PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
      },
      cacheDir: path.join(workDir, "cache"),
      javaCommand: fakeJava.command,
      releaseConfig,
    })

    assert.equal(result.exitCode, 0)
    assert.deepEqual((await readFile(fakeJava.logPath, "utf8")).trim().split(/\r?\n/), [
      "-jar",
      jarPath,
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

function runtimeConfigFor(jarBytes, overrides = {}) {
  const runtimeVersion = overrides.runtimeVersion ?? "9.8.7"
  return {
    runtimeVersion,
    releaseTag: `runtime-v${runtimeVersion}`,
    launcherProtocol: 1,
    jarUrl: `https://downloads.example.test/j4a-${runtimeVersion}.jar`,
    jarSha256: sha256Of(jarBytes),
    ...overrides,
  }
}
