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
  const releaseConfig = runtimeConfigFor(jarBytes)

  try {
    const result = await runJ4a({
      argv: ["install"],
      env: {
        J4A_JAVA_COMMAND: fakeJava.command,
        PATH: `${fakeJava.binDir}${path.delimiter}${process.env.PATH ?? ""}`,
      },
      cacheDir: path.join(workDir, "cache"),
      javaCommand: fakeJava.command,
      releaseConfig,
      requestImpl: async () => responseFrom(jarBytes, {
        headers: { "content-type": "application/java-archive" },
      }),
    })

    assert.equal(result.exitCode, 0)
    assert.equal(await readFile(runtimeJarIn(workDir, releaseConfig), "utf8"), jarBytes)
    assert.equal(existsSync(fakeJava.logPath), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --force refreshes an already valid cached jar", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const releaseConfig = runtimeConfigFor(jarBytes)
  let requests = 0

  try {
    await mkdir(path.dirname(runtimeJarIn(workDir, releaseConfig)), { recursive: true })
    await writeFile(runtimeJarIn(workDir, releaseConfig), jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["install", "--force"],
      cacheDir: path.join(workDir, "cache"),
      releaseConfig,
      requestImpl: async () => {
        requests += 1
        return responseFrom(jarBytes, {
          headers: { "content-type": "application/java-archive" },
        })
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(requests, 1)
    assert.equal(await readFile(runtimeJarIn(workDir, releaseConfig), "utf8"), jarBytes)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install migrates a verified legacy jar into the versioned cache without network", async () => {
  const jarBytes = "legacy jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const cacheDir = path.join(workDir, "cache")
  const runtimeVersion = "7.6.5"
  const versionedJar = path.join(cacheDir, "runtimes", runtimeVersion, "j4a.jar")

  try {
    await mkdir(cacheDir, { recursive: true })
    await writeFile(path.join(cacheDir, "j4a.jar"), jarBytes, "utf8")

    const result = await runJ4a({
      argv: ["install"],
      cacheDir,
      releaseConfig: runtimeConfigFor(jarBytes, { runtimeVersion }),
      requestImpl: async () => {
        assert.fail("a verified legacy jar must migrate without a network request")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(await readFile(versionedJar, "utf8"), jarBytes)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --with-skills copies the packaged skill into the caller workspace", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)
  const stdout = createRecorder()
  const releaseConfig = runtimeConfigFor(jarBytes)

  try {
    const result = await runJ4a({
      argv: ["install", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
      stdout: stdout.write,
      releaseConfig,
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

test("runJ4a install --only-skills copies the packaged skill without touching the runtime", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const cacheDir = path.join(workDir, "cache")
  const skillSourceDir = await createSkillSource(workDir)
  const stdout = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["install", "--only-skills"],
      cacheDir,
      cwd: workDir,
      javaCommand: path.join(workDir, "missing-java"),
      skillSourceDir,
      stdout: stdout.write,
      requestImpl: async () => {
        assert.fail("skills-only installation must not request the runtime")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(
      await readFile(path.join(workDir, ".agents", "skills", "j4a-master", "SKILL.md"), "utf8"),
      "# packaged skill\n",
    )
    assert.equal(existsSync(cacheDir), false)
    assert.match(stdout.text(), /installed j4a-master/i)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install --only-skills --force replaces only the packaged skill", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const cacheDir = path.join(workDir, "cache")
  const skillSourceDir = await createSkillSource(workDir)
  const targetDir = path.join(workDir, ".agents", "skills", "j4a-master")

  try {
    await mkdir(targetDir, { recursive: true })
    await writeFile(path.join(targetDir, "sentinel.txt"), "replace me", "utf8")

    const result = await runJ4a({
      argv: ["install", "--only-skills", "--force"],
      cacheDir,
      cwd: workDir,
      skillSourceDir,
      requestImpl: async () => {
        assert.fail("skills-only installation must not request the runtime")
      },
    })

    assert.equal(result.exitCode, 0)
    assert.equal(await readFile(path.join(targetDir, "SKILL.md"), "utf8"), "# packaged skill\n")
    assert.equal(existsSync(path.join(targetDir, "sentinel.txt")), false)
    assert.equal(existsSync(cacheDir), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a rejects conflicting skill install modes before side effects", async () => {
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const cacheDir = path.join(workDir, "cache")
  const skillSourceDir = await createSkillSource(workDir)
  const stderr = createRecorder()

  try {
    const result = await runJ4a({
      argv: ["install", "--only-skills", "--with-skills"],
      cacheDir,
      cwd: workDir,
      skillSourceDir,
      stderr: stderr.write,
      requestImpl: async () => {
        assert.fail("invalid install options must not request the runtime")
      },
    })

    assert.equal(result.exitCode, 2)
    assert.match(stderr.text(), /--only-skills.*--with-skills.*mutually exclusive/i)
    assert.equal(existsSync(cacheDir), false)
    assert.equal(existsSync(path.join(workDir, ".agents")), false)
  } finally {
    await rm(workDir, { recursive: true, force: true })
  }
})

test("runJ4a install -f --with-skills refreshes the jar and installs packaged skills", async () => {
  const jarBytes = "fake jar"
  const workDir = await mkdtemp(path.join(tmpdir(), "j4a-install-"))
  const skillSourceDir = await createSkillSource(workDir)
  const targetDir = path.join(workDir, ".agents", "skills", "j4a-master")
  const releaseConfig = runtimeConfigFor(jarBytes)
  let requests = 0

  try {
    await mkdir(path.dirname(runtimeJarIn(workDir, releaseConfig)), { recursive: true })
    await writeFile(runtimeJarIn(workDir, releaseConfig), jarBytes, "utf8")
    await mkdir(targetDir, { recursive: true })
    await writeFile(path.join(targetDir, "sentinel.txt"), "replace me", "utf8")

    const result = await runJ4a({
      argv: ["install", "-f", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
      releaseConfig,
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
  const releaseConfig = runtimeConfigFor(jarBytes)

  try {
    await mkdir(targetDir, { recursive: true })
    await writeFile(path.join(targetDir, "sentinel.txt"), "keep me", "utf8")

    const result = await runJ4a({
      argv: ["install", "--with-skills"],
      cacheDir: path.join(workDir, "cache"),
      cwd: workDir,
      skillSourceDir,
      stdout: stdout.write,
      releaseConfig,
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
  const releaseConfig = runtimeConfigFor(jarBytes)

  try {
    await writeFile(path.join(workDir, ".agents"), "not a directory", "utf8")

    await assert.rejects(
      runJ4a({
        argv: ["install", "--with-skills"],
        cacheDir: path.join(workDir, "cache"),
        cwd: workDir,
        skillSourceDir,
        releaseConfig,
        requestImpl: async () => responseFrom(jarBytes, {
          headers: { "content-type": "application/java-archive" },
        }),
      }),
      /skill|filesystem|directory/i,
    )

    assert.equal(await readFile(runtimeJarIn(workDir, releaseConfig), "utf8"), jarBytes)
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

function runtimeJarIn(workDir, config) {
  return path.join(workDir, "cache", "runtimes", config.runtimeVersion, "j4a.jar")
}
