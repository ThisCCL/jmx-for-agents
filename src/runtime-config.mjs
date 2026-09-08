import path from "node:path"

export const SUPPORTED_LAUNCHER_PROTOCOL = 1

const SEMVER_PATTERN = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/

export function resolveRuntimeConfig(config) {
  if (config === null || typeof config !== "object" || Array.isArray(config)) {
    throw new TypeError("runtime config must be an object")
  }

  const runtimeVersion = requireString(config.runtimeVersion, "runtimeVersion")
  if (!SEMVER_PATTERN.test(runtimeVersion)) {
    throw new TypeError("runtimeVersion must be SemVer-compatible")
  }

  const releaseTag = requireString(config.releaseTag, "releaseTag")
  if (releaseTag !== `v${runtimeVersion}` && releaseTag !== `runtime-v${runtimeVersion}`) {
    throw new TypeError(`releaseTag must equal v${runtimeVersion} or runtime-v${runtimeVersion}`)
  }

  const launcherProtocol = config.launcherProtocol
  if (!Number.isSafeInteger(launcherProtocol) || launcherProtocol < 1) {
    throw new TypeError("launcherProtocol must be a positive integer")
  }
  if (launcherProtocol !== SUPPORTED_LAUNCHER_PROTOCOL) {
    throw new Error(
      `runtime launcher protocol ${launcherProtocol} is incompatible with supported protocol ${SUPPORTED_LAUNCHER_PROTOCOL}`,
    )
  }

  const jarUrl = requireHttpsUrl(config.jarUrl)
  const jarSha256 = requireSha256(config.jarSha256)
  return { runtimeVersion, releaseTag, launcherProtocol, jarUrl, jarSha256 }
}

export function runtimeCacheDir(cacheRoot, runtimeVersion) {
  const root = requireString(cacheRoot, "cacheRoot")
  const version = requireString(runtimeVersion, "runtimeVersion")
  if (!SEMVER_PATTERN.test(version)) {
    throw new TypeError("runtimeVersion must be SemVer-compatible")
  }
  return path.join(root, "runtimes", version)
}

export function runtimeJarPath(cacheRoot, runtimeVersion) {
  return path.join(runtimeCacheDir(cacheRoot, runtimeVersion), "j4a.jar")
}

export function describeRuntime({ cacheRoot, config, wrapperVersion }) {
  const runtime = resolveRuntimeConfig(config)
  return {
    wrapperVersion: requireString(wrapperVersion, "wrapperVersion"),
    runtimeVersion: runtime.runtimeVersion,
    launcherProtocol: runtime.launcherProtocol,
    releaseTag: runtime.releaseTag,
    jarUrl: runtime.jarUrl,
    jarSha256: runtime.jarSha256,
    cacheRoot: requireString(cacheRoot, "cacheRoot"),
    jarPath: runtimeJarPath(cacheRoot, runtime.runtimeVersion),
  }
}

function requireHttpsUrl(value) {
  const candidate = requireString(value, "jarUrl")
  let url
  try {
    url = new URL(candidate)
  } catch {
    throw new TypeError("jarUrl must be a valid HTTPS URL")
  }
  if (url.protocol !== "https:") {
    throw new TypeError("jarUrl must use HTTPS")
  }
  return url.toString()
}

function requireSha256(value) {
  if (typeof value !== "string" || !/^[a-fA-F0-9]{64}$/.test(value)) {
    throw new TypeError("jarSha256 must be a SHA-256 hex digest")
  }
  return value.toLowerCase()
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new TypeError(`${name} must be a non-empty string`)
  }
  return value
}
