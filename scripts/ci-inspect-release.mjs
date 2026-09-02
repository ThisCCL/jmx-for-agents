import { fileURLToPath } from "node:url"
import path from "node:path"

import { assertPreparedRelease } from "./release.mjs"

export async function inspectPreparedRelease() {
  const manifest = await assertPreparedRelease()
  return {
    jar: manifest.jar.file,
    jarSha256: manifest.jar.sha256,
    tarball: manifest.tarball.file,
    tarballEntries: manifest.tarball.inventory.length,
    tarballSha512: manifest.tarball.sha512,
  }
}

if (fileURLToPath(import.meta.url) === path.resolve(process.argv[1] ?? "")) {
  process.stdout.write(`${JSON.stringify(await inspectPreparedRelease())}\n`)
}
