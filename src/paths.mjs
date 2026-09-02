import { homedir, platform } from "node:os"
import path from "node:path"

export function defaultCacheDir(env = process.env) {
  if (typeof env.J4A_CACHE_DIR === "string" && env.J4A_CACHE_DIR.length > 0) {
    return env.J4A_CACHE_DIR
  }

  switch (platform()) {
    case "win32":
      return path.join(env.LOCALAPPDATA ?? path.join(homedir(), "AppData", "Local"), "j4a")
    case "darwin":
      return path.join(homedir(), "Library", "Application Support", "j4a")
    default:
      return path.join(env.XDG_DATA_HOME ?? path.join(homedir(), ".local", "share"), "j4a")
  }
}
