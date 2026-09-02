# Write modes

Use this reference before every `set`, `apply`, or `init` mutation. Choose by destination intent, then send only the fields shown for that row.

| Intent | MCP fields | Result |
|---|---|---|
| Safest default: write a new copy | `out: <different-new-file.jmx>` | Input stays unchanged; the new output must not already exist. |
| Replace an existing different-path copy | `out: <different-existing-file.jmx>`, `forceOut: true` | Input stays unchanged; the existing output is replaced. |
| Edit the input file itself | `override: true` | The input path is the destination. Omit `out` and `forceOut`. |
| Preview an `apply` patch | `dryRun: true` | Nothing is written. Omit `override` and `forceOut`; omit `out` for clarity because it is ignored. |
| Create with `init` | `out: <destination.jmx>` | Creates a new plan; add `forceOut: true` only to replace that existing destination. `init` has no `override`. |

## Path identity

For `set` and committed `apply`, `out` always means a copy and must identify a different file from the input. J4A compares resolved file identity, not only the spelling: an equal path, relative/absolute spelling of the same path, symlink, junction, or other alias to the input still requires `override: true`. `forceOut` authorizes replacement only for an existing different file; it never turns same-file `out` into an in-place write.

## Selection rule

Start with a new different `out`. Add `forceOut: true` only after deciding to replace an existing different output. Use `override: true` only after deciding that changing the input itself is intentional. After writing a copy, use that output as the next call's `file` and refresh its refs with `read` before another staged edit.

MCP uses `forceOut` and `override`; CLI spells them `--force-out` and `--override`.
