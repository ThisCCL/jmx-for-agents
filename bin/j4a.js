#!/usr/bin/env node
import { existsSync } from "node:fs"

const runtimeEntrypoint = existsSync(new URL("../dist/main.mjs", import.meta.url))
  ? "../dist/main.mjs"
  : "../src/main.mjs"
const { main } = await import(runtimeEntrypoint)

await main()
