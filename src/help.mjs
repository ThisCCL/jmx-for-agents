const WRAPPER_HELP = [
  "j4a downloads and runs the JMX Agent CLI runtime.",
  "",
  "Usage:",
  "  j4a install",
  "  j4a install --force",
  "  j4a install --with-skills",
  "  j4a install --force --with-skills",
  "  j4a mcp",
  "  j4a <java-command> [args...]",
  "",
  "Commands:",
  "  install            Download or verify the cached j4a.jar runtime.",
  "  install --force    Download and overwrite the cached j4a.jar runtime.",
  "  install --with-skills",
  "                     Install the runtime, then copy j4a-master into <cwd>/.agents/skills.",
  "                     With --force, replace an existing j4a-master skill directory.",
  "  mcp                Starts the Java MCP server over stdio.",
  "                     If the runtime is missing, install it without --force before launch.",
  "",
  "Notes:",
  "  Run `j4a install` before ordinary commands such as read, set, validate, components, categories, or apply.",
  "  After installation ordinary commands forward your arguments to `java -jar <cached-j4a.jar> ...`.",
].join("\n")

const INSTALL_HELP = [
  "j4a install downloads the version-matched runtime jar.",
  "",
  "Usage:",
  "  j4a install",
  "  j4a install --force",
  "  j4a install --with-skills",
  "  j4a install --force --with-skills",
  "",
  "Options:",
  "  -f, --force        Download and overwrite the cached j4a.jar even when it is already valid.",
  "                     With --with-skills, also replace the existing j4a-master skill directory.",
  "  --with-skills      After the runtime jar is ready, copy j4a-master into <cwd>/.agents/skills/j4a-master.",
  "  --help             Show this install help.",
].join("\n")

export function writeWrapperHelp(write) {
  write(`${WRAPPER_HELP}\n`)
}

export function writeInstallHelp(write) {
  write(`${INSTALL_HELP}\n`)
}
