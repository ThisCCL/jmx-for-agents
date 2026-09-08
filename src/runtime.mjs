import { constants } from "node:fs"
import { copyFile, mkdir } from "node:fs/promises"

import { downloadJar, requireCachedJar } from "./downloader.mjs"
import { resolveRuntimeConfig, runtimeCacheDir, runtimeJarPath } from "./runtime-config.mjs"
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
  onlySkills = false,
  withSkills,
  env,
}) {
  if (onlySkills) {
    return installSkill({ cwd, force, skillSourceDir, stdout })
  }

  const config = resolveRuntimeConfig(releaseConfig)
  const versionedCacheDir = runtimeCacheDir(cacheDir, config.runtimeVersion)
  const migratedJar = force
    ? undefined
    : await migrateLegacyRuntimeJar({ cacheDir, config, versionedCacheDir })
  const jarPath = migratedJar ?? await downloadJar({
    jarUrl: config.jarUrl,
    sha256: config.jarSha256,
    cacheDir: versionedCacheDir,
    force,
    reporter,
    requestImpl,
    env,
  })
  stdout(`j4a: runtime ready at ${jarPath}\n`)
  if (!withSkills) {
    return { exitCode: 0 }
  }

  return installSkill({ cwd, force, skillSourceDir, stdout })
}

async function installSkill({ cwd, force, skillSourceDir, stdout }) {
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
  const config = resolveRuntimeConfig(releaseConfig)
  const versionedCacheDir = runtimeCacheDir(cacheDir, config.runtimeVersion)
  const jarPath = runtimeJarPath(cacheDir, config.runtimeVersion)
  try {
    return await requireCachedJar({
      cacheDir: versionedCacheDir,
      sha256: config.jarSha256,
    })
  } catch (error) {
    if (error instanceof Error && error.code === "ENOENT") {
      const migratedJar = await migrateLegacyRuntimeJar({ cacheDir, config, versionedCacheDir })
      if (migratedJar !== undefined) {
        return migratedJar
      }
      const missingRuntime = new Error(
        `runtime jar is missing at ${jarPath}. Run \`j4a install\` first or place the verified jar at that path.`,
      )
      missingRuntime.code = "ENOENT"
      throw missingRuntime
    }
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(`runtime jar at ${jarPath} is invalid: ${message}. Run \`j4a install\` to reinstall it.`)
  }
}

async function migrateLegacyRuntimeJar({ cacheDir, config, versionedCacheDir }) {
  let legacyJar
  try {
    legacyJar = await requireCachedJar({ cacheDir, sha256: config.jarSha256 })
  } catch (error) {
    if (error instanceof Error && (error.code === "ENOENT" || /sha256 mismatch/.test(error.message))) {
      return undefined
    }
    throw error
  }

  await mkdir(versionedCacheDir, { recursive: true })
  try {
    await copyFile(legacyJar, runtimeJarPath(cacheDir, config.runtimeVersion), constants.COPYFILE_EXCL)
  } catch (error) {
    if (!(error instanceof Error) || error.code !== "EEXIST") {
      throw error
    }
  }

  try {
    return await requireCachedJar({ cacheDir: versionedCacheDir, sha256: config.jarSha256 })
  } catch (error) {
    if (error instanceof Error && /sha256 mismatch/.test(error.message)) {
      return undefined
    }
    throw error
  }
}
