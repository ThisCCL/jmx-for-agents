import assert from "node:assert/strict"
import { access, readFile } from "node:fs/promises"
import path from "node:path"

import { createPackagedConsumer, runAsync } from "./helpers/package-smoke.mjs"

const context = await createPackagedConsumer()

try {
  const preInstall = await runAsync(context.installedCommand, ["read", "sample.jmx"], {
    cwd: context.consumerDir,
    env: {
      ...process.env,
      J4A_CACHE_DIR: context.cacheDir,
      J4A_JAVA_COMMAND: context.fakeJava,
      NODE_EXTRA_CA_CERTS: context.certificatePath,
      PATH: `${context.fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
    },
  })

  assert.notEqual(preInstall.status, 0)
  assert.match(preInstall.stderr, /j4a install/)
  assert.equal(context.requestCount, 0)

  const install = await runAsync(context.installedCommand, ["install"], {
    cwd: context.consumerDir,
    env: {
      ...process.env,
      J4A_CACHE_DIR: context.cacheDir,
      J4A_JAVA_COMMAND: context.fakeJava,
      NODE_EXTRA_CA_CERTS: context.certificatePath,
      PATH: `${context.fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
    },
  })
  assert.equal(install.status, 0)

  const postInstall = await runAsync(context.installedCommand, ["read", "sample.jmx"], {
    cwd: context.consumerDir,
    env: {
      ...process.env,
      J4A_CACHE_DIR: context.cacheDir,
      J4A_JAVA_COMMAND: context.fakeJava,
      NODE_EXTRA_CA_CERTS: context.certificatePath,
      PATH: `${context.fakeBin}${path.delimiter}${process.env.PATH ?? ""}`,
    },
  })

  assert.equal(postInstall.status, 0)
  await access(path.join(context.cacheDir, "j4a.jar"))
  assert.match(await readFile(context.javaLog, "utf8"), /-jar[\r\n]+.*j4a\.jar[\r\n]+read[\r\n]+sample\.jmx/)
  assert.equal(context.requestCount, 1)
  console.log("PASS installed-command-contract")
  console.log(`requestCount=${context.requestCount}`)
  console.log(`consumerDir=${context.consumerDir}`)
} finally {
  await context.cleanup()
}
