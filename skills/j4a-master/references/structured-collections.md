# Structured collection values

Use this reference when selected-component `components` reports `collection|map|element|rows|opaque`.

## Recursive values

Copy the complete value from focused `read` or an exact-property `value_template`. Preserve `presence`, ordering, and every emitted class. A present empty value differs from an absent value; a bare list is not a generic collection.

```json
{"type":"collection","value":{"presence":"present","property_class":"org.apache.jmeter.testelement.property.CollectionProperty","items":[{"type":"string","presence":"present","property_class":"org.apache.jmeter.testelement.property.StringProperty","value":"canonical-guidance"}]}}
```

### Elements: leaf edit or replace-whole

Treat `type: element` as a replace-whole value, not a partial merge. When replacing it, copy every emitted user property under `value.properties`, including ordinary properties such as `TestElement.name` when present, then change only the intended values. Preserve the emitted property arrays; their inner address context is explained in [property addresses](property-addresses.md).

For one nested leaf, prefer `set` or one apply property record using the complete leaf address returned by focused `read`. This avoids submitting the rest of the element. System identity properties such as `TestElement.gui_class` and `TestElement.test_class` are runtime-owned: keep them out of authored replacement records when they are not emitted.

## Rows

Use rows only when the exact target emits `type: rows`. Copy `row_type` and ordered `row_properties`. Every row key must exactly match a current `row_properties[].name`; for example, use emitted `Argument.name`, not a shortened `name`. Omit a field only when its descriptor provides a default, and keep every supplied value at its reported native scalar type. Unknown fields, missing required fields, descriptor drift, or type mismatch require a fresh focused read.

```json
{"type":"rows","value":{"row_type":"<copy-from-focused-read>","row_properties":[{"name":"<reported-row-property>","type":"string","required":false,"default":""}],"rows":[{"<reported-row-property>":"<native-scalar-value>"}]}}
```

A bare row list is also accepted when emitted metadata establishes rows. Incremental `append`, `insert`, and `remove` use the same field rules and declaration order.

On rejection, rerun focused `read` for the exact target and preserve its representation: recursive stays recursive, rows stays rows, and opaque follows [opaque values](opaque-values.md). Never reuse a target-bound value on another property or guess a Java class.
