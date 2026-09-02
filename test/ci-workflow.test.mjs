import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"
import { promisify } from "node:util"

import { parseCiWorkflow, validateCiWorkflow } from "../scripts/ci-workflow-contract.mjs"

const workflowPath = path.resolve(".github", "workflows", "ci.yml")
const execFileAsync = promisify(execFile)

async function workflowSource() {
  return readFile(workflowPath, "utf8")
}

function commentChecksum(source, archive) {
  return source.replace(
    new RegExp(`^(\\s*)(printf .*${archive.replaceAll(".", "\\.")}.*sha512sum --check --strict)$`, "m"),
    "$1# $2",
  )
}

function addUnexpectedJMeterRuntime(source) {
  return source
    .replace(
      "permissions:\n  contents: read\n",
      "permissions:\n  contents: read\n\nenv:\n  UNEXPECTED_JMETER_ARCHIVE: apache-jmeter-5.7.0.tgz\n",
    )
    .replace(
      "      - name: Lint the workflow",
      "      - name: Provision unexpected JMeter runtime\n"
        + "        run: echo \"$UNEXPECTED_JMETER_ARCHIVE\"\n"
        + "      - name: Lint the workflow",
    )
}

function addUnexpectedJMeterVersion(source) {
  return source
    .replace(
      "permissions:\n  contents: read\n",
      "permissions:\n  contents: read\n\nenv:\n  UNEXPECTED_JMETER_VERSION: 5.7.0\n",
    )
    .replace(
      "      - name: Lint the workflow",
      "      - name: Provision unexpected JMeter runtime\n"
        + "        run: echo apache-jmeter-$UNEXPECTED_JMETER_VERSION.tgz\n"
        + "      - name: Lint the workflow",
    )
}

function addSplitJMeterEnvironment(source) {
  return source
    .replace(
      "permissions:\n  contents: read\n",
      "permissions:\n  contents: read\n\nenv:\n  EXTRA_VERSION: 5.7.0\n  EXTRA_URL: https://archive.apache.org/dist/jmeter/binaries\n  EXTRA_ARCHIVE: apache-jmeter-$EXTRA_VERSION.tgz\n",
    )
    .replace(
      "      - name: Lint the workflow",
      "      - name: Provision extra runtime\n"
        + "        run: curl --output \"$RUNNER_TEMP/runtime.tgz\" \"$EXTRA_URL/$EXTRA_ARCHIVE\"\n"
        + "      - name: Lint the workflow",
    )
}

function addJMeterProvisionCommand(source) {
  return source.replace(
    "          echo \"JMETER_HOME=$RUNNER_TEMP/apache-jmeter-5.6.3\" >> \"$GITHUB_ENV\"\n",
    "          echo \"JMETER_HOME=$RUNNER_TEMP/apache-jmeter-5.6.3\" >> \"$GITHUB_ENV\"\n"
      + "          EXTRA_VERSION=5.7.0\n"
      + "          echo apache-jmeter-$EXTRA_VERSION.tgz\n",
  )
}

test("CI workflow has the exact public build contract", async () => {
  const workflow = parseCiWorkflow(await workflowSource())

  assert.deepEqual(workflow.events, ["pull_request", "push"])
  assert.deepEqual(workflow.pushBranches, ["main"])
  assert.deepEqual(workflow.permissions, { contents: "read" })
  assert.deepEqual(workflow.actions, [
    "actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803",
    "actions/setup-node@249970729cb0ef3589644e2896645e5dc5ba9c38",
    "actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961",
    "actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16",
  ])
  assert.equal(
    workflow.actionInputs["actions/setup-go@924ae3a1cded613372ab5595356fb5720e22ba16"].cache,
    "false",
  )
  assert.deepEqual(validateCiWorkflow(await workflowSource()), [])
})

test("CI workflow validator rejects unsafe or incomplete contract variants", async t => {
  const source = await workflowSource()
  const cases = [
    {
      name: "unpinned action",
      source: source.replace("actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803", "actions/checkout@v4"),
      error: "action-pin:actions/checkout",
    },
    {
      name: "write permission",
      source: source.replace("contents: read", "contents: write"),
      error: "permissions",
    },
    {
      name: "job-level token permission",
      source: source.replace("    runs-on: ubuntu-latest", "    permissions:\n      id-token: write\n    runs-on: ubuntu-latest"),
      error: "permissions",
    },
    {
      name: "wrong Node version",
      source: source.replace("node-version: 24.11.1", "node-version: 20"),
      error: "node-version",
    },
    {
      name: "wrong Java version",
      source: source.replace("java-version: 8", "java-version: 17"),
      error: "java-version",
    },
    {
      name: "Go dependency cache without a module",
      source: source.replace("          cache: false\n", "          cache: true\n"),
      error: "go-cache",
    },
    {
      name: "missing public gate",
      source: source.replace("        run: pnpm run verify:public\n", ""),
      error: "command:pnpm run verify:public",
    },
    {
      name: "JMeter archive without checksum validation",
      source: source.replace(" | sha512sum --check --strict", ""),
      error: "jmeter-checksum-before-extract",
    },
    {
      name: "commented JMeter 5.6.3 checksum is not executable",
      source: commentChecksum(source, "apache-jmeter-5.6.3.tgz"),
      error: "jmeter-checksum-before-extract",
    },
    {
      name: "unexpected additional JMeter runtime",
      source: addUnexpectedJMeterRuntime(source),
      error: "jmeter-runtime-set",
    },
    {
      name: "unexpected JMeter version variable",
      source: addUnexpectedJMeterVersion(source),
      error: "jmeter-runtime-set",
    },
    {
      name: "split JMeter environment",
      source: addSplitJMeterEnvironment(source),
      error: "jmeter-runtime-set",
    },
    {
      name: "extra JMeter provision command",
      source: addJMeterProvisionCommand(source),
      error: "jmeter-runtime-set",
    },
  ]

  for (const fixture of cases) {
    await t.test(fixture.name, () => {
      assert.ok(validateCiWorkflow(fixture.source).includes(fixture.error))
    })
  }
})

test("workflow validator CLI reports valid and invalid workflow files", async () => {
  const valid = await execFileAsync(process.execPath, ["scripts/ci-workflow-contract.mjs", workflowPath])
  assert.deepEqual(JSON.parse(valid.stdout), { errors: [] })

  const root = await mkdtemp(path.join(tmpdir(), "j4a-ci-workflow-"))
  try {
    const source = await workflowSource()
    const fixtures = [
      {
        name: "unpinned",
        source: source.replace("actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803", "actions/checkout@v4"),
        error: "action-pin:actions/checkout",
      },
      {
        name: "commented-5.6.3-checksum",
        source: commentChecksum(source, "apache-jmeter-5.6.3.tgz"),
        error: "jmeter-checksum-before-extract",
      },
      {
        name: "unexpected-jmeter-runtime",
        source: addUnexpectedJMeterRuntime(source),
        error: "jmeter-runtime-set",
      },
      {
        name: "unexpected-jmeter-version",
        source: addUnexpectedJMeterVersion(source),
        error: "jmeter-runtime-set",
      },
      {
        name: "split-jmeter-environment",
        source: addSplitJMeterEnvironment(source),
        error: "jmeter-runtime-set",
      },
      {
        name: "extra-jmeter-provision-command",
        source: addJMeterProvisionCommand(source),
        error: "jmeter-runtime-set",
      },
    ]
    for (const fixture of fixtures) {
      const invalidPath = path.join(root, `${fixture.name}.yml`)
      await writeFile(invalidPath, fixture.source, "utf8")
      await assert.rejects(execFileAsync(process.execPath, ["scripts/ci-workflow-contract.mjs", invalidPath]), error => {
        assert.equal(error.code, 1)
        assert.ok(JSON.parse(error.stderr).errors.includes(fixture.error))
        return true
      })
    }
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
