import { spawnSync } from "node:child_process"
import { readdir } from "node:fs/promises"
import path from "node:path"
import { fileURLToPath } from "node:url"

const excludedTests = new Set([
  "downloader-security.test.mjs",
  "verify-property-graph-gates.test.mjs",
])

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))

export async function discoverDefaultTestFiles({ testDirectory }) {
  const entries = await readdir(testDirectory, { withFileTypes: true })
  return entries
    .filter(entry => entry.isFile() && entry.name.endsWith(".test.mjs") && !excludedTests.has(entry.name))
    .map(entry => path.relative(root, path.join(testDirectory, entry.name)).split(path.sep).join("/"))
    .sort()
}

async function main() {
  const files = await discoverDefaultTestFiles({ testDirectory: path.join(root, "test") })
  if (process.argv.slice(2).includes("--list")) {
    process.stdout.write(`${JSON.stringify(files)}\n`)
    return
  }
  const result = spawnSync(process.execPath, ["--test", ...files], { cwd: root, stdio: "inherit" })
  process.exitCode = result.status ?? 1
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  await main()
}
