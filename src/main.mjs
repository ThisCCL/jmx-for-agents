import { spawn } from "node:child_process"
import { readFileSync } from "node:fs"

import { writeInstallHelp, writeWrapperHelp } from "./help.mjs"
import { defaultCacheDir } from "./paths.mjs"
import { releaseConfig as defaultReleaseConfig } from "./release-config.mjs"
import { installRuntime, requireInstalledJar, requireMcpRuntimeJar } from "./runtime.mjs"
import { defaultSkillSourceDir } from "./skills.mjs"

const MCP_MAIN_CLASS = "io.github.thisccl.j4a.mcp.J4aMcpServer"
const PACKAGE_VERSION = readPackageVersion(import.meta.url)

export async function runJ4a({
  argv = process.argv.slice(2),
  env = process.env,
  cwd = process.cwd(),
  cacheDir = defaultCacheDir(env),
  javaCommand = env.J4A_JAVA_COMMAND ?? "java",
  stdout = (message) => process.stdout.write(message),
  stderr = (message) => process.stderr.write(message),
  reporter,
  stderrInteractive = process.stderr.isTTY === true,
  releaseConfig = defaultReleaseConfig,
  requestImpl,
  skillSourceDir = defaultSkillSourceDir(import.meta.url),
} = {}) {
  const report = reporter ?? createCliReporter(stderr, { interactive: stderrInteractive })
  const command = parseWrapperCommand(argv)
  if (command.kind === "wrapper-help") {
    writeWrapperHelp(stdout)
    return { exitCode: 0 }
  }
  if (command.kind === "version") {
    stdout(`${PACKAGE_VERSION}\n`)
    return { exitCode: 0 }
  }
  if (command.kind === "version-usage-error") {
    stderr("j4a: --version does not accept additional arguments.\n")
    return { exitCode: 2 }
  }
  if (command.kind === "install-help") {
    writeInstallHelp(stdout)
    return { exitCode: 0 }
  }
  if (command.kind === "install") {
    return installRuntime({
      cacheDir,
      cwd,
      reporter: report,
      env,
      releaseConfig,
      requestImpl,
      skillSourceDir,
      stdout,
      force: command.force,
      withSkills: command.withSkills,
    })
  }
  if (command.kind === "mcp") {
    const jarPath = await requireMcpRuntimeJar({
      cacheDir,
      cwd,
      reporter: report,
      env,
      releaseConfig,
      requestImpl,
      skillSourceDir,
    })
    return spawnJava(javaCommand, ["-cp", jarPath, MCP_MAIN_CLASS], env)
  }

  const jarPath = await requireInstalledJar({
    cacheDir,
    releaseConfig,
  })

  return spawnJava(javaCommand, ["-jar", jarPath, ...argv], env)
}

export function createCliReporter(write, { interactive = false } = {}) {
  let progressActive = false
  let progressWidth = 0
  return (message, { kind = "status" } = {}) => {
    if (kind === "progress") {
      if (!interactive) return
      const padding = " ".repeat(Math.max(0, progressWidth - message.length))
      write(`\r${message}${padding}`)
      progressActive = true
      progressWidth = message.length
      return
    }
    if (progressActive) write("\n")
    write(`${message}\n`)
    progressActive = false
    progressWidth = 0
  }
}

function spawnJava(command, args, env) {
  return new Promise((resolve, reject) => {
    const child = spawn(commandForPlatform(command), argsForPlatform(command, args), {
      env,
      stdio: "inherit",
    })

    child.once("error", reject)
    child.once("exit", (code, signal) => {
      if (signal !== null) {
        reject(new Error(`java exited from signal ${signal}`))
        return
      }
      resolve({ exitCode: code ?? 1 })
    })
  })
}

function commandForPlatform(command) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return "cmd.exe"
  }
  return command
}

function argsForPlatform(command, args) {
  if (process.platform === "win32" && /\.(cmd|bat)$/i.test(command)) {
    return ["/d", "/s", "/c", command, ...args]
  }
  return args
}

export async function main() {
  try {
    const result = await runJ4a()
    process.exitCode = result.exitCode
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    console.error(`j4a: ${message}`)
    process.exitCode = 1
  }
}

function parseWrapperCommand(argv) {
  if (argv.includes("--version")) {
    return argv.length === 1 && argv[0] === "--version"
      ? { kind: "version" }
      : { kind: "version-usage-error" }
  }
  if (argv.length === 0 || isHelpFlag(argv[0]) || argv[0] === "help") {
    return { kind: "wrapper-help" }
  }
  if (argv[0] !== "install") {
    if (argv[0] === "mcp") {
      return { kind: "mcp" }
    }
    return { kind: "run" }
  }

  const flags = new Set(argv.slice(1))
  for (const flag of flags) {
    if (!isHelpFlag(flag) && flag !== "--with-skills" && !isForceFlag(flag)) {
      throw new Error(`unknown install option: ${flag}`)
    }
  }
  if (hasHelpFlag(flags)) {
    return { kind: "install-help" }
  }
  return {
    kind: "install",
    force: hasForceFlag(flags),
    withSkills: flags.has("--with-skills"),
  }
}

function readPackageVersion(metaUrl) {
  const packageJson = JSON.parse(readFileSync(new URL("../package.json", metaUrl), "utf8"))
  if (typeof packageJson.version !== "string" || packageJson.version.length === 0) {
    throw new TypeError("package.json version must be a non-empty string")
  }
  return packageJson.version
}

function hasHelpFlag(flags) {
  return flags.has("--help") || flags.has("-h")
}

function hasForceFlag(flags) {
  return flags.has("--force") || flags.has("-f")
}

function isHelpFlag(value) {
  return value === "--help" || value === "-h"
}

function isForceFlag(value) {
  return value === "--force" || value === "-f"
}
