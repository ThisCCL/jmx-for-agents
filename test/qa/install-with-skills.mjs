import assert from "node:assert/strict"
import { access, readFile } from "node:fs/promises"
import path from "node:path"

import { createPackagedConsumer, runAsync } from "./helpers/package-smoke.mjs"

const context = await createPackagedConsumer()

try {
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
  await access(path.join(context.cacheDir, "j4a.jar"))
  assert.equal(
    await readFile(path.join(context.consumerDir, ".agents", "skills", "j4a-master", "SKILL.md"), "utf8"),
    await readFile(path.join(context.consumerDir, "node_modules", "@jmx-for-agents", "j4a", "dist", "skills", "j4a-master", "SKILL.md"), "utf8"),
  )
  assert.match(`${result.stdout}\n${result.stderr}`, /install.*with-skills|j4a-master/i)
  console.log("PASS install-with-skills")
  console.log(`requestCount=${context.requestCount}`)
  console.log(`consumerDir=${context.consumerDir}`)
} finally {
  await context.cleanup()
}
