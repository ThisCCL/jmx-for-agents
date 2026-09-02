# TestPlan - Synthetic Test Plan
ref: jmx_d62893619b1f
component: org.apache.jmeter.control.gui.TestPlanGui
locator: jmx_d62893619b1f
enabled: true

Key fields:
- name: `Synthetic Test Plan`
- comments: ``

Editable properties:
- property: [TestPlan.comments]
  value: ""
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.comments"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_d62893619b1f
    component: org.apache.jmeter.control.gui.TestPlanGui
    properties:
      - property: [TestPlan.comments]
        value: ""
        type: string
```

## ThreadGroup - Synthetic Thread Group
ref: jmx_19871e6efa95
component: org.apache.jmeter.threads.gui.ThreadGroupGui
locator: jmx_19871e6efa95
enabled: true

Key fields:
- name: `Synthetic Thread Group`
- on sample error: `continue`
- threads: `1`
- ramp time: `1`

Editable properties:
- property: [ThreadGroup.on_sample_error]
  value: continue
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.on_sample_error"]' --value "..." --out <file>`
- property: [ThreadGroup.num_threads]
  value: '1'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.num_threads"]' --value "..." --out <file>`
- property: [ThreadGroup.ramp_time]
  value: '1'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.ramp_time"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_19871e6efa95
    component: org.apache.jmeter.threads.gui.ThreadGroupGui
    properties:
      - property: [ThreadGroup.on_sample_error]
        value: continue
        type: string
      - property: [ThreadGroup.num_threads]
        value: '1'
        type: string
      - property: [ThreadGroup.ramp_time]
        value: '1'
        type: string
```

Placement:
- parent: jmx_d62893619b1f
- position: last

Move card:
```yaml
- move:
    ref: jmx_19871e6efa95
    component: org.apache.jmeter.threads.gui.ThreadGroupGui
    parent: jmx_d62893619b1f
    position: last
```

Delete card:
```yaml
- delete:
    ref: jmx_19871e6efa95
    component: org.apache.jmeter.threads.gui.ThreadGroupGui
```

### HTTPSamplerProxy - Synthetic HTTP Request
ref: jmx_330976848c8e
component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
locator: jmx_330976848c8e
enabled: true

Key fields:
- name: `Synthetic HTTP Request`
- domain: `example.test`
- path: `/status`
- method: `GET`

Editable properties:
- property: [HTTPSampler.domain]
  value: example.test
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.domain"]' --value "..." --out <file>`
- property: [HTTPSampler.path]
  value: /status
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.path"]' --value "..." --out <file>`
- property: [HTTPSampler.method]
  value: GET
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.method"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_330976848c8e
    component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
    properties:
      - property: [HTTPSampler.domain]
        value: example.test
        type: string
      - property: [HTTPSampler.path]
        value: /status
        type: string
      - property: [HTTPSampler.method]
        value: GET
        type: string
```

Placement:
- parent: jmx_19871e6efa95
- position: last

Move card:
```yaml
- move:
    ref: jmx_330976848c8e
    component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
    parent: jmx_19871e6efa95
    position: last
```

Delete card:
```yaml
- delete:
    ref: jmx_330976848c8e
    component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
```
