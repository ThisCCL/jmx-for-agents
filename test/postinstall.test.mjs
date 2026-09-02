import assert from "node:assert/strict"
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { fileURLToPath } from "node:url"

import { buildDist } from "../scripts/build-release.mjs"

const POST_WRITE_REFERENCES = [
  "apply",
  "components",
  "init",
  "opaque-values",
  "property-addresses",
  "read",
  "set",
  "structured-collections",
  "validate",
  "write-modes",
]
const PROJECT_ROOT = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const GUIDANCE_PATH = path.join(
  PROJECT_ROOT,
  "src/main/resources/io/github/thisccl/j4a/guidance/agent-guidance.json",
)

test("buildDist copies wrapper modules and skill routing metadata", async () => {
  const { root, outputDir } = await createPackagedFixture()

  try {
    assert.equal(await readFile(path.join(outputDir, "main.mjs"), "utf8"), "export const main = true\n")
    assert.equal(await readFile(path.join(outputDir, "help.mjs"), "utf8"), "export const help = true\n")

    const packagedSkill = await readFile(path.join(outputDir, "skills", "j4a-master", "SKILL.md"), "utf8")
    assert.equal(packagedSkill, await readFile(path.join(PROJECT_ROOT, "skills", "j4a-master", "SKILL.md"), "utf8"))
    const frontmatter = frontmatterOf(packagedSkill)
    assert.equal(frontmatter.name, "j4a-master")
    assert.equal(typeof frontmatter.description, "string")
    assert.match(frontmatter.description, /\bJMeter\b.*\.jmx\b/)

    for (const reference of POST_WRITE_REFERENCES) {
      const markdown = await readFile(path.join(outputDir, "skills", "j4a-master", "references", `${reference}.md`), "utf8")
      assert.equal(
        markdown,
        await readFile(path.join(PROJECT_ROOT, "skills", "j4a-master", "references", `${reference}.md`), "utf8"),
      )
    }

    const guidance = JSON.parse(await readFile(GUIDANCE_PATH, "utf8"))
    await assertPackagedGuidance(outputDir, guidance)

    await assert.rejects(readFile(path.join(outputDir, "postinstall.mjs"), "utf8"), /ENOENT/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test("packaged guidance rejects stale enum and structured examples", async () => {
  const { root, outputDir } = await createPackagedFixture()

  try {
    const guidance = JSON.parse(await readFile(GUIDANCE_PATH, "utf8"))
    const readReference = path.join(outputDir, "skills", "j4a-master", "references", "read.md")
    const collectionsReference = path.join(
      outputDir,
      "skills",
      "j4a-master",
      "references",
      "structured-collections.md",
    )
    const originalRead = await readFile(readReference, "utf8")
    const originalCollections = await readFile(collectionsReference, "utf8")

    await writeFile(readReference, originalRead.replace("none|key|all|writable", "none|key|all"), "utf8")
    await assert.rejects(assertPackagedGuidance(outputDir, guidance), /stale MCP read enum/)

    await writeFile(readReference, originalRead, "utf8")
    await writeFile(
      collectionsReference,
      originalCollections.replace("CollectionProperty", "ObjectProperty"),
      "utf8",
    )
    await assert.rejects(assertPackagedGuidance(outputDir, guidance), /stale generic collection example/)

    await writeFile(
      collectionsReference,
      originalCollections.replace("<copy-from-focused-read>", "<guessed-row-type>"),
      "utf8",
    )
    await assert.rejects(assertPackagedGuidance(outputDir, guidance), /stale semantic row example/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

async function createPackagedFixture() {
  const root = await mkdtemp(path.join(tmpdir(), "j4a-dist-"))
  const sourceDir = path.join(root, "src")
  const outputDir = path.join(root, "dist")

  await mkdir(sourceDir, { recursive: true })
  await writeFile(path.join(sourceDir, "main.mjs"), "export const main = true\n", "utf8")
  await writeFile(path.join(sourceDir, "help.mjs"), "export const help = true\n", "utf8")
  await writeFile(path.join(sourceDir, "release-config.mjs"), "export const releaseConfig = {}\n", "utf8")
  await buildDist({ sourceDir, outputDir })

  return { root, outputDir }
}

function frontmatterOf(markdown) {
  const match = markdown.match(/^---\n(?<frontmatter>[\s\S]*?)\n---\n/)

  assert.ok(match?.groups?.frontmatter, "missing skill frontmatter")
  return Object.fromEntries(match.groups.frontmatter.split("\n").map((line) => {
    const separator = line.indexOf(":")
    assert.notEqual(separator, -1, "invalid skill frontmatter entry")
    return [line.slice(0, separator), line.slice(separator + 1).trim()]
  }))
}

async function assertPackagedGuidance(outputDir, guidance) {
  const references = path.join(outputDir, "skills", "j4a-master", "references")
  const readReference = await readFile(path.join(references, "read.md"), "utf8")
  const collectionsReference = await readFile(path.join(references, "structured-collections.md"), "utf8")
  const documentedReadProperties = readPropertyEnumOf(readReference)
  const examples = jsonCodeBlocksOf(collectionsReference)
  const collection = examples.find((example) => example.type === "collection")
  const rows = examples.find((example) => example.type === "rows")

  assert.deepEqual(documentedReadProperties, guidance.mcp_read_properties, "stale MCP read enum")
  assert.equal(collection?.value?.presence, "present", "stale generic collection example")
  assert.equal(
    collection?.value?.property_class,
    "org.apache.jmeter.testelement.property.CollectionProperty",
    "stale generic collection example",
  )
  assert.equal(collection?.value?.items?.[0]?.type, "string")
  assert.equal(rows?.value?.row_type, "<copy-from-focused-read>", "stale semantic row example")
  assert.deepEqual(
    rows?.value?.row_properties,
    [{ name: "<reported-row-property>", type: "string", required: false, default: "" }],
    "stale semantic row example",
  )
  assert.deepEqual(
    Object.keys(rows?.value?.rows?.[0] ?? {}),
    ["<reported-row-property>"],
    "row example must point to emitted row_properties names",
  )
}

function readPropertyEnumOf(markdown) {
  const match = markdown.match(/properties:\s*`?([a-z|]+)`?/)
  assert.ok(match?.[1], "missing MCP read enum")
  return match[1].split("|")
}

function jsonCodeBlocksOf(markdown) {
  return [...markdown.matchAll(/```json\n(?<json>[\s\S]*?)\n```/g)].map(({ groups }) => JSON.parse(groups.json))
}
