import path from "node:path"
import { fileURLToPath } from "node:url"

import { downloadJar } from "../src/downloader.mjs"
import { defaultCacheDir } from "../src/paths.mjs"
import { releaseConfig } from "../src/release-config.mjs"

export async function runPostinstall({
  env = process.env,
  config = releaseConfig,
  reporter = (message) => console.error(message),
  download = downloadJar,
  requestImpl,
} = {}) {
  try {
    const jarPath = await download({
      jarUrl: config.jarUrl,
      sha256: config.jarSha256,
      cacheDir: defaultCacheDir(env),
      reporter,
      requestImpl,
    })
    reporter(`j4a: jar 已缓存到 ${jarPath}`)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    reporter(`j4a: 安装后下载未完成：${message}`)
    reporter("j4a: 首次运行 j4a 时会再次尝试下载。")
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
