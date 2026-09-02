import { copyFile, cp, mkdir, readdir, rm } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

export async function buildDist({
  rootDir = projectRoot,
  sourceDir = path.join(rootDir, "src"),
  outputDir = path.join(rootDir, "dist"),
} = {}) {
  await rm(outputDir, { recursive: true, force: true })
  await mkdir(outputDir, { recursive: true })
  for (const fileName of await distModuleFiles(sourceDir)) {
    await copyFile(path.join(sourceDir, fileName), path.join(outputDir, fileName))
  }
  await cp(path.join(rootDir, "skills", "j4a-master"), path.join(outputDir, "skills", "j4a-master"), {
    recursive: true,
    force: true,
  })
}

async function distModuleFiles(sourceDir) {
  const entries = await readdir(sourceDir, { withFileTypes: true })
  return entries
    .filter((entry) => entry.isFile() && entry.name.endsWith(".mjs"))
    .map((entry) => entry.name)
}
