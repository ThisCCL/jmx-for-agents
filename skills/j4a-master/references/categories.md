# `categories` MCP tool

Use `categories` to narrow component discovery with a category id supplied by the selected runtime.

1. Inspect the live schema and call it under the workflow's resolved JMeter home.
2. Copy a returned `category` id; do not derive one from a label or remembered list.
3. Pass that id to `components`. If rejected, refresh categories and retry with a current id.

Labels, counts, and visible categories are runtime projections; only the returned id is an identifier.
