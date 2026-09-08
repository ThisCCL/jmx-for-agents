import path from "node:path"

import { buildReleaseConfig } from "./build-release.mjs"

const args = process.argv.slice(2)
const values = args[0] === "--" ? args.slice(1) : args
if (values.length !== 1) {
  throw new Error("usage: pnpm run runtime:sync -- <runtime-jar>")
}

const releaseConfig = await buildReleaseConfig({ jarPath: path.resolve(values[0]) })
process.stdout.write(`${JSON.stringify(releaseConfig)}\n`)
