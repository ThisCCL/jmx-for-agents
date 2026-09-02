# `components` MCP tool

Use `components` before every add and when `read` has not established a writable representation.

1. Inspect the live schema. For category discovery, follow the returned opaque cursor with the same runtime, category, projection, and limit until one exact component is selected or paging is exhausted.
2. Copy the selected runtime FQCN and request its details. If multiple candidates remain plausible, resolve them from current identities/labels or ask one narrow question.
3. Continue only when every add has an exact component and every property write has a complete reported representation. For non-scalars, follow [structured collections](structured-collections.md).

Ordinary details are the authoring surface. For opaque properties they point to focused `read`, which supplies the target-bound replacement document. Use `diagnostics: true` only to investigate non-writable or runtime-representation questions; diagnostic fields are evidence, not mutation input.

`runtime_metadata_status` describes metadata-source availability, not GUI completeness. `value_shape` is representation metadata, not the patch `type`; copy a `value_template` only for the exact component/property that emitted it.

Category pages contain complete exact-FQCN entries under both count and UTF-8 byte budgets. Reuse only a cursor from the same runtime/category/projection/limit/max-bytes request. Cursor v3 is rejected, so restart from the first page. When a row returns `componentToken`, call components with that token as its sole selector (plus the same explicit JMeter home, if used) to recover exact untruncated ordinary detail. Tamper or binding failure also restarts category paging.

`component` is the selected-runtime public identity. For generic TestBeans this is the exact class, such as `org.apache.jmeter.config.CSVDataSet`, not `org.apache.jmeter.testbeans.gui.TestBeanGUI`.
