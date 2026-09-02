# TestPlan - Disabled Component Test Plan
ref: jmx_d62893619b1f
component: org.apache.jmeter.control.gui.TestPlanGui
locator: jmx_d62893619b1f
enabled: true

Key fields:
- name: `Disabled Component Test Plan`
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

## ThreadGroup - Disabled Fixture Thread Group
ref: jmx_19871e6efa95
component: org.apache.jmeter.threads.gui.ThreadGroupGui
locator: jmx_19871e6efa95
enabled: true

Key fields:
- name: `Disabled Fixture Thread Group`
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

### HTTPSamplerProxy - Disabled HTTP Request
locator: jmx_330976848c8e
enabled: false

Summary:
- child components: 0
- editable properties hidden: 3
- rerun with `--include-disabled-details` to show details
