import { cp, lstat, mkdir, rm } from "node:fs/promises"
import { existsSync } from "node:fs"
import path from "node:path"

export function defaultSkillSourceDir(moduleUrl = import.meta.url) {
  const distPath = new URL("./skills/j4a-master/", moduleUrl)
  if (existsSync(distPath)) {
    return distPath
  }
  return new URL("../skills/j4a-master/", moduleUrl)
}

export async function installPackagedSkill({
  cwd,
  force = false,
  skillSourceDir,
}) {
  const targetDir = path.join(cwd, ".agents", "skills", "j4a-master")
  const existing = await readExistingTarget(targetDir)
  if (existing.kind === "directory" && !force) {
    return { status: "skipped", targetDir }
  }
  if (existing.kind === "file" && !force) {
    throw new Error(
      `skill installation failed: ${targetDir} exists but is not a directory. `
        + "Fix the workspace path and rerun `j4a install --with-skills`.",
    )
  }

  try {
    await mkdir(path.dirname(targetDir), { recursive: true })
    if (existing.kind !== "missing") {
      await rm(targetDir, { recursive: true, force: true })
    }
    await cp(pathFromUrl(skillSourceDir), targetDir, { recursive: true, force: false, errorOnExist: true })
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    throw new Error(
      `skill installation failed: ${message}. `
        + "Fix the workspace filesystem state and rerun `j4a install --with-skills`.",
    )
  }

  return { status: existing.kind === "missing" ? "installed" : "replaced", targetDir }
}

function pathFromUrl(value) {
  if (value instanceof URL) {
    return value
  }
  return path.resolve(value)
}

async function readExistingTarget(targetDir) {
  try {
    const stat = await lstat(targetDir)
    return { kind: stat.isDirectory() ? "directory" : "file" }
  } catch (error) {
    if (error instanceof Error && error.code === "ENOENT") {
      return { kind: "missing" }
    }
    throw error
  }
}
