import assert from "node:assert/strict"
import { chmod, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { existsSync } from "node:fs"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import { runJ4a } from "../src/main.mjs"
import { responseFrom, sha256Of } from "./helpers/downloader.mjs"

test("runJ4a install prepares the cached jar without invoking java", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const fakeJava = await createFakeJava(workDir)

  try {
    const result = await runJ4a({
      argv: ["install"],
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
      requestImpl: async () => responseFrom(jarBytes, {
        headers: { "content-type": "application/java-archive" },
      }),
    })

    assert.equal(result.exitCode, 0)
    assert.equal(await readFile(path.join(workDir, "cache", "j4a.jar"), "utf8"), jarBytes)
    assert.equal(existsSync(fakeJava.logPath), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --force refreshes an already valid cached jar", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  let requests = 0

  try {
    await mkdir(path.join(workDir, "cache"), { recursive: true })
    await writeFile(path.join(workDir, "cache", "j4a.jar"), jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["install", "--force"],
      cacheDir: path.join(workDir, "cache"),
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
    assert.equal(await readFile(path.join(workDir, "cache", "j4a.jar"), "utf8"), jarBytes)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --with-skills copies the packaged skill into the caller workspace", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)
  const stdout = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["install", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
      stdout: stdout.write,
      releaseConfig: {
        jarUrl: "https://downloads.example.test/j4a.jar",
        jarSha256: sha256Of(jarBytes),
      },
      requestImpl: async () => responseFrom(jarBytes, {
        headers: { "content-type": "application/java-archive" },
      }),
    })

    assert.equal(result.exitCode, 0)
    assert.equal(
      await readFile(path.join(workDir, ".agents", "skills", "j4a-master", "SKILL.md"), "utf8"),
      "# packaged skill\n",
    )
    assert.match(stdout.text(), /j4a-master/)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install -f --with-skills refreshes the jar and installs packaged skills", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)
  const targetDir = path.join(workDir, ".agents", "skills", "j4a-master")
  let requests = 0

  try {
    await mkdir(path.join(workDir, "cache"), { recursive: true })
    await writeFile(path.join(workDir, "cache", "j4a.jar"), jarBytes, "utf8")
    await mkdir(targetDir, { recursive: true })
    await writeFile(path.join(targetDir, "sentinel.txt"), "replace me", "utf8")

    const result = await runJ4a({
      argv: ["install", "-f", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
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
    assert.equal(await readFile(path.join(targetDir, "SKILL.md"), "utf8"), "# packaged skill\n")
    assert.equal(existsSync(path.join(targetDir, "sentinel.txt")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --with-skills keeps an existing skill directory", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)
  const targetDir = path.join(workDir, ".agents", "skills", "j4a-master")
  const stdout = createRecorder()

  try {
    await mkdir(targetDir, { recursive: true })
    await writeFile(path.join(targetDir, "sentinel.txt"), "keep me", "utf8")

    const result = await runJ4a({
      argv: ["install", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
      stdout: stdout.write,
      releaseConfig: {
        jarUrl: "https://downloads.example.test/j4a.jar",
        jarSha256: sha256Of(jarBytes),
      },
      requestImpl: async () => responseFrom(jarBytes, {
        headers: { "content-type": "application/java-archive" },
      }),
    })

    assert.equal(result.exitCode, 0)
    assert.equal(await readFile(path.join(targetDir, "sentinel.txt"), "utf8"), "keep me")
    assert.equal(existsSync(path.join(targetDir, "SKILL.md")), false)
    assert.match(stdout.text(), /skip|existing/i)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --with-skills leaves the jar cached when skill copy fails", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)

  try {
    await writeFile(path.join(workDir, ".agents"), "not a directory", "utf8")

    await assert.rejects(
      runJ4a({
        argv: ["install", "--with-skills"],
        cacheDir: path.join(workDir, "cache"),
        cwd: workDir,
        skillSourceDir,
        releaseConfig: {
          jarUrl: "https://downloads.example.test/j4a.jar",
          jarSha256: sha256Of(jarBytes),
        },
        requestImpl: async () => responseFrom(jarBytes, {
          headers: { "content-type": "application/java-archive" },
        }),
      }),
      /skill|filesystem|directory/i,
    )

    assert.equal(await readFile(path.join(workDir, "cache", "j4a.jar"), "utf8"), jarBytes)
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

async function createSkillSource(workDir) {
  const skillSourceDir = path.join(workDir, "skill-source", "j4a-master")
  await mkdir(skillSourceDir, { recursive: true })
  await writeFile(path.join(skillSourceDir, "SKILL.md"), "# packaged skill\n", "utf8")
  return skillSourceDir
}
