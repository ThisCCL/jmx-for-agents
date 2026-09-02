# `apply` MCP tool

Use `apply` for adds, moves, deletes, related property changes, or any write requiring dry-run.

## Procedure

1. Inspect the live schema. Provide one patch source: `patchYaml` for generated work or `patchFile` for retained YAML.
2. Collect current refs/property addresses with `read`; before every add, discover the exact component through `categories` and paged `components`.
3. Put related cards in one ordered `changes` list. Copy component ids, property arrays, types, and structured values from current results.
4. Give every add/move one placement selector: `position`, `before`, or `after`.
5. Dry-run the identical patch when required by the main skill, then choose exactly one destination intent using [write modes](write-modes.md).
6. Require the successful receipt and perform only the focused proof required by the main skill.

An add may declare `as: name`; later cards in the same patch can use `$name` in ref-bearing fields. Use returned `createdRefs` for surviving additions. MCP refs remain session- and target-bound. A CLI created ref is valid only against the exact unchanged target; every other ref, stale target, or possibly changed target requires a fresh read. Dry-run creates no durable refs.

## Operation shape

```yaml
changes:
  - set:
      ref: <ref>
      component: <component-id>
      properties:
        - property: [<segments-copied-from-read>]
          value: <value>
          type: <reported-type>
  - add:
      parent: <parent-ref>
      position: <first|last|integer>
      component: <component-id>
      as: <alias>
  - move:
      ref: <ref>
      component: <component-id>
      parent: <new-parent-ref>
      position: <first|last|integer>
  - delete:
      ref: <ref>
      component: <component-id>
  - append:
      ref: <ref>
      property: [<segments-copied-from-read>]
      row: {<fields-copied-from-row-properties>}
  - insert:
      ref: <ref>
      property: [<segments-copied-from-read>]
      index: <index>
      row: {<fields-copied-from-row-properties>}
  - remove:
      ref: <ref>
      property: [<segments-copied-from-read>]
      index: <index>
```

For relative placement, replace `position` with `before: <sibling-ref>` or `after: <sibling-ref>`. `append` writes at the current row count; `insert` accepts `0..row-count`; `remove` accepts `0..row-count-1`. Cards run in declaration order, so each bound observes earlier cards. Any failed card rejects the candidate; correct the reported cause and retry the whole ordered patch.

Successful receipts contain declaration-ordered, value-free `changeResults`; its length equals `appliedCount`, and only a committed surviving aliased add has `resultRef`. Exit classes are stable: schema/usage is 2; an accepted patch's locator, component, placement, materialization, property, candidate, reload, or preservation semantic failure is 3; filesystem, worker, timeout, atomic-write, and fatal failure is 4. Fatal always means inspect/read before any retry and no replay.
