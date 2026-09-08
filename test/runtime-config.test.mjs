import assert from "node:assert/strict"
import path from "node:path"
import test from "node:test"

import {
  describeRuntime,
  resolveRuntimeConfig,
  runtimeJarPath,
  SUPPORTED_LAUNCHER_PROTOCOL,
} from "../src/runtime-config.mjs"

const VALID_CONFIG = {
  runtimeVersion: "1.2.3",
  releaseTag: "runtime-v1.2.3",
  launcherProtocol: SUPPORTED_LAUNCHER_PROTOCOL,
  jarUrl: "https://downloads.example.test/j4a-1.2.3.jar",
  jarSha256: "a".repeat(64),
}

test("runtime metadata is independent from the wrapper and selects a versioned cache path", () => {
  assert.deepEqual(describeRuntime({
    cacheRoot: path.join("tmp", "j4a-cache"),
    config: VALID_CONFIG,
    wrapperVersion: "9.8.7",
  }), {
    wrapperVersion: "9.8.7",
    runtimeVersion: "1.2.3",
    releaseTag: "runtime-v1.2.3",
    launcherProtocol: 1,
    jarUrl: "https://downloads.example.test/j4a-1.2.3.jar",
    jarSha256: "a".repeat(64),
    cacheRoot: path.join("tmp", "j4a-cache"),
    jarPath: path.join("tmp", "j4a-cache", "runtimes", "1.2.3", "j4a.jar"),
  })
})

test("runtime metadata rejects malformed or unsafe artifact identities", () => {
  const cases = [
    [{ ...VALID_CONFIG, runtimeVersion: "../escape" }, /runtimeVersion must be SemVer-compatible/],
    [{ ...VALID_CONFIG, releaseTag: "v9.9.9" }, /releaseTag must equal/],
    [{ ...VALID_CONFIG, launcherProtocol: 0 }, /launcherProtocol must be a positive integer/],
    [{ ...VALID_CONFIG, launcherProtocol: 2 }, /launcher protocol 2.*supported protocol 1/i],
    [{ ...VALID_CONFIG, jarUrl: "http://downloads.example.test/j4a.jar" }, /jarUrl must use HTTPS/],
    [{ ...VALID_CONFIG, jarSha256: "not-a-digest" }, /jarSha256 must be a SHA-256 hex digest/],
  ]

  for (const [candidate, expected] of cases) {
    assert.throws(() => resolveRuntimeConfig(candidate), expected)
  }
  assert.equal(
    runtimeJarPath(path.join("tmp", "cache"), "1.2.3"),
    path.join("tmp", "cache", "runtimes", "1.2.3", "j4a.jar"),
  )
  assert.throws(() => runtimeJarPath(path.join("tmp", "cache"), "../escape"), /SemVer-compatible/)
})
