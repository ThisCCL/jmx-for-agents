import { spawn } from "node:child_process"
import { createHash } from "node:crypto"
import {
  access,
  chmod,
  copyFile,
  cp,
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rm,
  writeFile,
} from "node:fs/promises"
import { homedir } from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

export async function installLocal({
  root = projectRoot,
  targetDir,
  jar,
  build = buildArtifacts,
  stdout = message => process.stdout.write(message),
} = {}) {
  const sourceRoot = path.resolve(root)
  const target = requireSafeTarget(targetDir ?? path.join(sourceRoot, "build", "local-install"), sourceRoot)
  const packageJson = JSON.parse(await readFile(path.join(sourceRoot, "package.json"), "utf8"))
  await assertWritableInstallTarget(target, requireString(packageJson.name, "package.json name"))
  await build(sourceRoot, { buildJar: jar === undefined })

  const version = requireString(packageJson.version, "package.json version")
  const sourceJar = jar === undefined
    ? await findShadowJar(sourceRoot, version)
    : path.resolve(requireString(jar, "local JAR path"))
  const jarSha256 = sha256(await readFile(sourceJar))

  await mkdir(path.dirname(target), { recursive: true })
  const staging = await mkdtemp(path.join(path.dirname(target), ".j4a-local-install-"))
  try {
    await stageInstall({ jarSha256, sourceJar, sourceRoot, staging })
    await publishInstall(staging, target)
  } finally {
    await rm(staging, { recursive: true, force: true })
  }

  const command = path.join(target, "bin", process.platform === "win32" ? "j4a.cmd" : "j4a")
  const jarPath = path.join(target, "cache", "j4a.jar")
  stdout(`j4a: local install ready at ${target}\n`)
  stdout(`j4a: command ${command}\n`)
  stdout(`j4a: cache ${jarPath}\n`)
  return { command, jarPath, jarSha256, targetDir: target }
}

async function stageInstall({ jarSha256, sourceJar, sourceRoot, staging }) {
  const packageDir = path.join(staging, "package")
  await Promise.all([
    mkdir(path.join(staging, "bin"), { recursive: true }),
    mkdir(path.join(staging, "cache"), { recursive: true }),
    mkdir(path.join(packageDir, "bin"), { recursive: true }),
  ])
  await Promise.all([
    cp(path.join(sourceRoot, "dist"), path.join(packageDir, "dist"), { recursive: true }),
    copyFile(path.join(sourceRoot, "bin", "j4a.js"), path.join(packageDir, "bin", "j4a.js")),
    copyFile(path.join(sourceRoot, "package.json"), path.join(packageDir, "package.json")),
    copyFile(path.join(sourceRoot, "README.md"), path.join(packageDir, "README.md")),
    copyFile(path.join(sourceRoot, "LICENSE"), path.join(packageDir, "LICENSE")),
    copyFile(sourceJar, path.join(staging, "cache", "j4a.jar")),
  ])
  await chmod(path.join(packageDir, "bin", "j4a.js"), 0o755)
  await writeFile(
    path.join(packageDir, "dist", "release-config.mjs"),
    releaseConfig(jarSha256),
    "utf8",
  )
  await writeLaunchers(staging)
}

async function writeLaunchers(staging) {
  const posix = `#!/bin/sh
set -eu
J4A_PREFIX=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
export J4A_CACHE_DIR=\${J4A_CACHE_DIR:-"$J4A_PREFIX/cache"}
exec node "$J4A_PREFIX/package/bin/j4a.js" "$@"
`
  const windows = `@echo off\r
setlocal\r
if not defined J4A_CACHE_DIR set "J4A_CACHE_DIR=%~dp0..\\cache"\r
node "%~dp0..\\package\\bin\\j4a.js" %*\r
`
  const posixPath = path.join(staging, "bin", "j4a")
  await Promise.all([
    writeFile(posixPath, posix, "utf8"),
    writeFile(path.join(staging, "bin", "j4a.cmd"), windows, "utf8"),
  ])
  await chmod(posixPath, 0o755)
}

async function publishInstall(staging, target) {
  await mkdir(target, { recursive: true })
  for (const directory of ["bin", "cache", "package"]) {
    await rm(path.join(target, directory), { recursive: true, force: true })
    await cp(path.join(staging, directory), path.join(target, directory), { recursive: true })
  }
}

async function assertWritableInstallTarget(target, packageName) {
  let stat
  try {
    stat = await lstat(target)
  } catch (error) {
    if (error?.code === "ENOENT") return
    throw error
  }
  if (!stat.isDirectory() || stat.isSymbolicLink()) {
    throw new Error(`local install target must be a directory, not ${target}`)
  }
  const entries = await readdir(target)
  if (entries.length === 0) return
  try {
    const installedPackage = JSON.parse(await readFile(path.join(target, "package", "package.json"), "utf8"))
    await Promise.all([
      access(path.join(target, "bin", "j4a")),
      access(path.join(target, "bin", "j4a.cmd")),
      access(path.join(target, "package", "bin", "j4a.js")),
      access(path.join(target, "cache", "j4a.jar")),
    ])
    if (installedPackage.name === packageName) return
  } catch {}
  throw new Error(`target is not an empty j4a local install directory: ${target}`)
}

async function findShadowJar(root, version) {
  const libs = path.join(root, "build", "libs")
  const expected = `j4a-${version}-all.jar`
  const files = await readdir(libs)
  const matches = files.filter(file => file === expected)
  if (matches.length !== 1) {
    throw new Error(`expected ${expected} under ${libs}`)
  }
  return path.join(libs, matches[0])
}

async function buildArtifacts(root, { buildJar }) {
  await runCommand(process.execPath, [path.join(root, "scripts", "build-dist.mjs")], root)
  if (!buildJar) return
  const javaHome = process.env.JAVA_HOME
  const javaToolchain = typeof javaHome === "string" && javaHome.length > 0
    ? [`-Porg.gradle.java.installations.paths=${javaHome}`]
    : []
  try {
    await runCommand(gradleCommand(), ["shadowJar", "--no-daemon", ...javaToolchain], root)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`${message}. Ensure JAVA_HOME points to a Java 8 JDK or pass --jar <path>.`)
  }
}

function runCommand(command, args, cwd) {
  return new Promise((resolve, reject) => {
    const child = spawn(commandForPlatform(command), argsForPlatform(command, args), {
      cwd,
      stdio: "inherit",
    })
    child.once("error", reject)
    child.once("exit", code => {
      if (code === 0) resolve()
      else reject(new Error(`${command} ${args.join(" ")} exited ${code ?? 1}`))
    })
  })
}

function requireSafeTarget(targetDir, root) {
  const target = path.resolve(requireString(targetDir, "local install directory"))
  if (target === path.parse(target).root || target === root || target === path.resolve(homedir())) {
    throw new Error(`refusing unsafe local install directory: ${target}`)
  }
  return target
}

function releaseConfig(jarSha256) {
  return [
    "export const releaseConfig = {",
    '  jarUrl: "https://local.invalid/j4a.jar",',
    `  jarSha256: "${jarSha256}",`,
    "}",
    "",
  ].join("\n")
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex")
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be provided`)
  }
  return value
}

function gradleCommand() {
  return process.platform === "win32" ? "gradlew.bat" : "./gradlew"
}

function commandForPlatform(command) {
  return process.platform === "win32" && /\.(cmd|bat)$/i.test(command) ? "cmd.exe" : command
}

function argsForPlatform(command, args) {
  return process.platform === "win32" && /\.(cmd|bat)$/i.test(command)
    ? ["/d", "/s", "/c", command, ...args]
    : args
}

function usage() {
  return [
    "Usage: pnpm local:install",
    "       pnpm local:install -- <directory> [--jar <path>]",
    "       pnpm local:install -- --dir <directory> [--jar <path>]",
    "",
    "Without --jar, the command builds the current shadow JAR.",
    "With --jar, it rebuilds the Node wrapper and installs the supplied JAR.",
  ].join("\n")
}

function parseOptions(args) {
  const values = args[0] === "--" ? args.slice(1) : args
  if (values.some(value => ["--help", "-h"].includes(value))) return undefined
  let targetDir
  let jar
  for (let index = 0; index < values.length; index += 1) {
    const value = values[index]
    if (["--dir", "--jar"].includes(value)) {
      const optionValue = values[index + 1]
      if (optionValue === undefined || optionValue.startsWith("-")) throw new Error(usage())
      if (value === "--dir") targetDir = optionValue
      else jar = optionValue
      index += 1
    } else if (!value.startsWith("-") && targetDir === undefined) {
      targetDir = value
    } else {
      throw new Error(usage())
    }
  }
  return { jar, targetDir }
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  try {
    const options = parseOptions(process.argv.slice(2))
    if (options === undefined) {
      console.log(usage())
    } else {
      await installLocal(options)
    }
  } catch (error) {
    console.error(`j4a: ${error instanceof Error ? error.message : String(error)}`)
    process.exitCode = 1
  }
}
