import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { access, readFile, rm } from "node:fs/promises"
import path from "node:path"

import { assertInstalledPackageIdentity } from "./helpers/prepared-release-artifact.mjs"
import { createPreparedReleaseConsumer } from "./helpers/prepared-release-consumer.mjs"
import { runMcpStartup } from "./helpers/prepared-release-mcp.mjs"
import { runBounded } from "./helpers/prepared-release-process.mjs"

const context = await createPreparedReleaseConsumer()
let result

try {
  const runtimeEnv = {
    ...process.env,
    J4A_CACHE_DIR: context.cacheDir,
    NODE_EXTRA_CA_CERTS: context.certificatePath,
  }
  const fixturePath = path.join(context.projectDir, "src", "test", "resources", "fixtures", "simple-http.jmx")

  const packageVersion = await runBounded(context.installedCommand, ["--version"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(packageVersion.status, 0)
  assert.equal(packageVersion.stderr, "")
  assert.equal(packageVersion.stdout.trim(), context.manifest.version)

  const packageHelp = await runBounded(context.installedCommand, ["--help"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(packageHelp.status, 0)
  assert.match(packageHelp.stdout, /j4a mcp/)

  const missingCache = await runBounded(context.installedCommand, ["read", fixturePath], {
    allowNonZero: true,
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.notEqual(missingCache.status, 0)
  assert.match(missingCache.stderr, /j4a install/)
  assert.equal(context.directRequests, 0)
  assert.equal(context.redirectRequests, 0)

  const directInstall = await runBounded(context.installedCommand, ["install"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(directInstall.status, 0)
  const cachedJar = path.join(context.cacheDir, "j4a.jar")
  assert.equal(sha256(await readFile(cachedJar)), context.jarSha256)
  assert.equal(context.directRequests, 1)

  const realJavaHelp = await runBounded(process.env.J4A_JAVA_COMMAND ?? "java", ["-jar", cachedJar, "--help"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(realJavaHelp.status, 0)
  assert.match(realJavaHelp.stdout, /JMX Agent CLI/)
  const javaVersion = await runBounded(process.env.J4A_JAVA_COMMAND ?? "java", ["-jar", cachedJar, "--version"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(javaVersion.status, 0)
  assert.equal(javaVersion.stderr, "")
  assert.equal(javaVersion.stdout.trim(), context.manifest.version)

  const cacheReuse = await runBounded(context.installedCommand, ["install"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(cacheReuse.status, 0)
  assert.equal(context.directRequests, 1)

  const installSkills = await runBounded(context.installedCommand, ["install", "--with-skills"], {
    cwd: context.consumerDir,
    env: runtimeEnv,
    timeoutMs: 120_000,
  })
  assert.equal(installSkills.status, 0)
  const identity = await assertInstalledPackageIdentity({
    consumerDir: context.consumerDir,
    inventory: context.manifest.tarball.inventory,
    projectDir: context.projectDir,
  })
  const installedSkill = await readFile(path.join(context.consumerDir, ".agents", "skills", "j4a-master", "SKILL.md"), "utf8")
  for (const compatibility of [
    "none|key|all|writable",
    "Cursor v3 is rejected",
    "accepted apply semantic failures exit 3",
    "exact selected-runtime FQCN",
    "exact unchanged target",
    "inspect/read the target before any retry",
  ]) assert.match(installedSkill, new RegExp(escapeRegExp(compatibility)))
  assert.equal(context.directRequests, 1)

  await rm(context.cacheDir, { recursive: true, force: true })
  await context.patchRedirectConfig()
  const mcp = await runMcpStartup({ command: context.installedCommand, cwd: context.consumerDir, env: runtimeEnv })
  assert.equal(mcp.status, 0)
  assert.match(mcp.stderr, /j4a: runtime ready at .*j4a\.jar/)
  assert.doesNotMatch(mcp.stderr, /MCP runtime installation failed|download failed|MCP server I\/O failure/)
  assert.equal(mcp.initialize?.result?.serverInfo?.name, "j4a")
  assert.equal(mcp.initialize?.result?.serverInfo?.version, context.manifest.version)
  assert.equal(mcp.shutdown?.result, null)
  await access(cachedJar)
  assert.equal(sha256(await readFile(cachedJar)), context.jarSha256)
  assert.equal(context.redirectRequests, 1)
  assert.equal(context.assetRequests, 1)

  result = {
    status: "PASS",
    jarSha256: context.jarSha256,
    tarballSha512: context.tarballSha512,
    manifest: context.manifest,
    packageFiles: context.manifest.tarball.inventory,
    packagedSkillFiles: identity.skillFiles,
    installedCommand: context.installedCommand,
    javaHelp: "PASS",
    versionAgreement: {
      package: packageVersion.stdout.trim(),
      java: javaVersion.stdout.trim(),
      mcp: mcp.initialize.result.serverInfo.version,
    },
    packagedCompatibilityGuidance: "PASS",
    mcpStartup: "PASS",
    cacheReuse: "PASS",
    directRequests: context.directRequests,
    redirectRequests: context.redirectRequests,
    assetRequests: context.assetRequests,
    patchedConfig: context.installedConfigPath,
  }
} finally {
  await context.cleanup()
  await assert.rejects(access(context.workDir), { code: "ENOENT" })
}

console.log(JSON.stringify({
  ...result,
  cleanup: { removed: true, workDir: context.workDir },
}, null, 2))

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex")
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
}
