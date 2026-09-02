# Property addresses

Use this reference when an address is nested, contains unusual segments, or is rejected.

- Copy the non-empty scalar array from current `read` or selected-component `components` output unchanged.
- String `"0"` is a property/map key; integer `0` is a collection index.
- Punctuation and empty strings inside a string segment are literal data. Keep each emitted segment intact; do not convert the array to XPath, dotted, bracket, or slash syntax.

```json
["HeaderManager.headers", 0, "Header.name"]
```

An address selects existing graph nodes. On type/range/resolution errors, refresh the exact target instead of translating or guessing the address.

## Address context

The outer `property` of `set`, an apply property record, or a row card is a graph address. It may traverse elements, collections, and maps, so the array above can contain string and integer segments.

An `element` replacement has an inner `value.properties` list. Those inner records belong to the replacement document and identify properties of that emitted nested element; they are not independent graph-address cards. Copy the complete inner records from the focused read. To change only one nested leaf, use that leaf's outer graph address in a separate `set` or apply property record instead of rebuilding the element value.
