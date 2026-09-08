import path from "node:path"
import { fileURLToPath } from "node:url"

import { downloadJar } from "../src/downloader.mjs"
import { defaultCacheDir } from "../src/paths.mjs"
import { releaseConfig } from "../src/release-config.mjs"
import { resolveRuntimeConfig, runtimeCacheDir } from "../src/runtime-config.mjs"

export async function runPostinstall({
  env = process.env,
  config = releaseConfig,
  reporter = (message) => console.error(message),
  download = downloadJar,
  requestImpl,
} = {}) {
  try {
    const runtime = resolveRuntimeConfig(config)
    const jarPath = await download({
      jarUrl: runtime.jarUrl,
      sha256: runtime.jarSha256,
      cacheDir: runtimeCacheDir(defaultCacheDir(env), runtime.runtimeVersion),
      reporter,
      requestImpl,
      env,
    })
    reporter(`j4a: Runtime cached at ${jarPath}`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    reporter(`j4a: Post-install download did not complete: ${message}`)
    reporter("j4a: The download will be retried the next time j4a starts.")
  }
}

export function isDirectRun(metaUrl, argv1 = process.argv[1]) {
  if (argv1 === undefined) {
    return false
  }
  return fileURLToPath(metaUrl) === path.resolve(argv1)
}

if (isDirectRun(import.meta.url)) {
  await runPostinstall()
}
