import assert from "node:assert/strict"
import { mkdir, readFile, writeFile } from "node:fs/promises"
import path from "node:path"

import { createPackagedConsumer, runAsync } from "./helpers/package-smoke.mjs"

const context = await createPackagedConsumer()

try {
  const targetDir = path.join(context.consumerDir, ".agents", "skills", "j4a-master")
  await mkdir(targetDir, { recursive: true })
  await writeFile(path.join(targetDir, "sentinel.txt"), "keep me", "utf8")

  const result = await runAsync(context.installedCommand, ["install", "--with-skills"], {
    cwd: context.consumerDir,
    env: {
      ...process.env,
      J4A_CACHE_DIR: context.cacheDir,
      J4A_JAVA_COMMAND: context.fakeJava,
      NODE_EXTRA_CA_CERTS: context.certificatePath,
      PATH: `${context.fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
    },
  })

  assert.equal(result.status, 0)
  assert.equal(await readFile(path.join(targetDir, "sentinel.txt"), "utf8"), "keep me")
  assert.match(`${result.stdout}\n${result.stderr}`, /skip|existing/i)
  console.log("PASS install-existing-skill-skip")
  console.log(`requestCount=${context.requestCount}`)
  console.log(`consumerDir=${context.consumerDir}`)
} finally {
  await context.cleanup()
}
