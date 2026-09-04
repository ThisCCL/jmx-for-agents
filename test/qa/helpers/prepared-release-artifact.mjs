import assert from "node:assert/strict"
import { createHash } from "node:crypto"
import { readFile, readdir } from "node:fs/promises"
import path from "node:path"

function expectedReleaseFiles(version) {
  return [`j4a-${version}.jar`, `j4a-${version}.jar.sha256`, `jmx-for-agents-j4a-${version}.tgz`]
}
const REQUIRED_PACKAGE_ENTRIES = [
  "package/LICENSE",
  "package/README.md",
  "package/bin/j4a.js",
  "package/dist/main.mjs",
  "package/dist/release-config.mjs",
  "package/dist/runtime.mjs",
  "package/dist/skills/j4a-master/SKILL.md",
  "package/package.json",
]
const FORBIDDEN_ROOT_ENTRIES = new Set([
  "package/build.gradle",
  "package/gradle.properties",
  "package/gradlew",
  "package/gradlew.bat",
  "package/settings.gradle",
])

export async function readPreparedRelease(projectDir) {
  const manifest = JSON.parse(await readFile(path.join(projectDir, "build", "release-manifest.json"), "utf8"))
  const releaseDir = path.join(projectDir, "build", "release")
  const releaseFiles = (await readdir(releaseDir)).sort()
  const jarPath = path.join(projectDir, manifest.jar.file)
  const tarballPath = path.join(projectDir, manifest.tarball.file)
  const jarSha256 = hash("sha256", await readFile(jarPath))
  const tarballSha512 = hash("sha512", await readFile(tarballPath))
  const checksum = await readFile(path.join(projectDir, manifest.jar.checksumFile), "utf8")
  assert.equal(checksum, `${jarSha256}  ${path.basename(jarPath)}\n`)
  assertPreparedReleaseContract({ manifest, releaseFiles, jarSha256, tarballSha512 })
  return { jarPath, jarSha256, manifest, releaseDir, releaseFiles, tarballPath, tarballSha512 }
}

export function assertPreparedReleaseContract({ manifest, releaseFiles, jarSha256, tarballSha512 }) {
  assert.deepEqual([...releaseFiles].sort(), expectedReleaseFiles(manifest.version))
  assert.equal(manifest.tag, `v${manifest.version}`)
  assert.equal(manifest.jar.file, `build/release/j4a-${manifest.version}.jar`)
  assert.equal(manifest.jar.checksumFile, `${manifest.jar.file}.sha256`)
  assert.equal(manifest.jar.sha256, jarSha256)
  assert.equal(manifest.tarball.file, `build/release/jmx-for-agents-j4a-${manifest.version}.tgz`)
  assert.equal(manifest.tarball.sha512, tarballSha512)
  assert.equal(manifest.smoke.tarballFile, manifest.tarball.file)
  assert.equal(manifest.smoke.tarballSha512, tarballSha512)
  assert.equal(manifest.tarball.integrity, `sha512-${Buffer.from(tarballSha512, "hex").toString("base64")}`)
  for (const entry of REQUIRED_PACKAGE_ENTRIES) assert.ok(manifest.tarball.inventory.includes(entry), `missing npm package entry: ${entry}`)
  for (const entry of manifest.tarball.inventory) {
    if (/^package\/(?:src|build|gradle|\.omo|\.gradle|node_modules)\//.test(entry)
      || /^package\/(?:build|settings)\.gradle(?:\.kts)?$/.test(entry)
      || FORBIDDEN_ROOT_ENTRIES.has(entry)
      || entry.endsWith(".jar")
      || /credential|token|secret/i.test(entry)) {
      throw new Error(`forbidden npm package entry: ${entry}`)
    }
  }
}

export async function assertInstalledPackageIdentity({ consumerDir, inventory, projectDir }) {
  const packageDir = path.join(consumerDir, "node_modules", "@jmx-for-agents", "j4a")
  await assertSameFile(path.join(projectDir, "LICENSE"), path.join(packageDir, "LICENSE"))
  await assertSameFile(path.join(projectDir, "README.md"), path.join(packageDir, "README.md"))
  await assertSameFile(path.join(projectDir, "src", "runtime.mjs"), path.join(packageDir, "dist", "runtime.mjs"))
  const sourceSkills = path.join(projectDir, "skills", "j4a-master")
  const packagedSkills = path.join(packageDir, "dist", "skills", "j4a-master")
  const installedSkills = path.join(consumerDir, ".agents", "skills", "j4a-master")
  const skillFiles = await assertSameTree(sourceSkills, packagedSkills)
  for (const relativePath of skillFiles) {
    assert.ok(inventory.includes(`package/dist/skills/j4a-master/${normalizeInventoryPath(relativePath)}`), `missing skill package entry: ${relativePath}`)
  }
  await assertSameTree(packagedSkills, installedSkills)
  return { packageDir, skillFiles }
}

export function normalizeInventoryPath(relativePath, separator = path.sep) {
  return relativePath.split(separator).join("/")
}

async function assertSameFile(left, right) {
  assert.deepEqual(await readFile(right), await readFile(left), `byte mismatch: ${right}`)
}

async function assertSameTree(leftRoot, rightRoot) {
  const leftFiles = await treeFiles(leftRoot)
  const rightFiles = await treeFiles(rightRoot)
  assert.deepEqual(rightFiles, leftFiles, `tree mismatch: ${rightRoot}`)
  for (const relativePath of leftFiles) await assertSameFile(path.join(leftRoot, relativePath), path.join(rightRoot, relativePath))
  return leftFiles
}

async function treeFiles(root, relative = "") {
  const entries = await readdir(path.join(root, relative), { withFileTypes: true })
  const files = []
  for (const entry of entries.sort((left, right) => left.name.localeCompare(right.name))) {
    const child = path.join(relative, entry.name)
    if (entry.isDirectory()) files.push(...await treeFiles(root, child))
    else if (entry.isFile()) files.push(child)
    else throw new Error(`unsupported skill entry: ${child}`)
  }
  return files
}

function hash(algorithm, bytes) {
  return createHash(algorithm).update(bytes).digest("hex")
}
