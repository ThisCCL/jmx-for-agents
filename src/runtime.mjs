import path from "node:path"

import { downloadJar, requireCachedJar } from "./downloader.mjs"
import { installPackagedSkill } from "./skills.mjs"

export async function installRuntime({
  cacheDir,
  cwd,
  reporter,
  releaseConfig,
  requestImpl,
  skillSourceDir,
  stdout,
  force,
  withSkills,
  env,
}) {
  const jarPath = await downloadJar({
    jarUrl: releaseConfig.jarUrl,
    sha256: releaseConfig.jarSha256,
    cacheDir,
    force,
    reporter,
    requestImpl,
    env,
  })
  stdout(`j4a: runtime ready at ${jarPath}\n`)
  if (!withSkills) {
    return { exitCode: 0 }
  }

  const result = await installPackagedSkill({
    cwd,
    force,
    skillSourceDir,
  })
  if (result.status === "skipped") {
    stdout(`j4a: j4a-master already exists at ${result.targetDir}; skipping.\n`)
    return { exitCode: 0 }
  }
  if (result.status === "replaced") {
    stdout(`j4a: replaced j4a-master at ${result.targetDir}\n`)
    return { exitCode: 0 }
  }
  stdout(`j4a: installed j4a-master into ${result.targetDir}\n`)
  return { exitCode: 0 }
}

export async function requireMcpRuntimeJar({
  cacheDir,
  cwd,
  reporter,
  releaseConfig,
  requestImpl,
  skillSourceDir,
  env,
}) {
  try {
    return await requireInstalledJar({
      cacheDir,
      releaseConfig,
    })
  } catch (error) {
    if (!(error instanceof Error) || error.code !== "ENOENT") {
      throw error
    }
  }

  try {
    await installRuntime({
      cacheDir,
      cwd,
      reporter,
      releaseConfig,
      requestImpl,
      skillSourceDir,
      stdout: (message) => reporter(message.trimEnd()),
      force: false,
      withSkills: false,
      env,
    })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`MCP runtime installation failed: ${message}. Run \`j4a install --force\` and retry \`j4a mcp\`.`)
  }
  return requireInstalledJar({
    cacheDir,
    releaseConfig,
  })
}

export async function requireInstalledJar({ cacheDir, releaseConfig }) {
  const jarPath = path.join(cacheDir, "j4a.jar")
  try {
    return await requireCachedJar({
      cacheDir,
      sha256: releaseConfig.jarSha256,
    })
  } catch (error) {
    if (error instanceof Error && error.code === "ENOENT") {
      const missingRuntime = new Error(`runtime jar is missing at ${jarPath}. Run \`j4a install\` first.`)
      missingRuntime.code = "ENOENT"
      throw missingRuntime
    }
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`runtime jar at ${jarPath} is invalid: ${message}. Run \`j4a install\` to reinstall it.`)
  }
}
