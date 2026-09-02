# `validate` MCP tool

Use `validate` only for an explicit independent validation request. Successful write receipts already prove their owned candidate was saved, reloaded, and validated.

1. Inspect the live schema and validate the requested file under the workflow's resolved JMeter home.
2. Continue only when validation succeeds.

This loads and traverses JMX with JMeter semantics; it does not run samplers or a load test. Follow current error fields and recovery actions. Environment changes require user authorization.
