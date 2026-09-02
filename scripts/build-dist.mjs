import path from "node:path"
import { fileURLToPath } from "node:url"

import { buildDist } from "./build-release.mjs"

const projectRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

await buildDist({
  sourceDir: path.join(projectRoot, "src"),
  outputDir: path.join(projectRoot, "dist"),
})
