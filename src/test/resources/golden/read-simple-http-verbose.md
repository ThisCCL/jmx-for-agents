# TestPlan - Synthetic Test Plan
ref: jmx_d62893619b1f
component: org.apache.jmeter.control.gui.TestPlanGui
locator: jmx_d62893619b1f
enabled: true

Key fields:
- name: `Synthetic Test Plan`
- comments: ``

Editable properties:
- property: [TestElement.name]
  value: 'Synthetic Test Plan'
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestElement.name"]' --value "..." --out <file>`
- property: [TestElement.enabled]
  value: 'true'
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestElement.enabled"]' --value "..." --out <file>`
- property: [TestPlan.comments]
  value: ""
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.comments"]' --value "..." --out <file>`
- property: [TestPlan.functional_mode]
  value: false
  type: boolean
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.functional_mode"]' --value "..." --out <file>`
- property: [TestPlan.tearDown_on_shutdown]
  value: true
  type: boolean
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.tearDown_on_shutdown"]' --value "..." --out <file>`
- property: [TestPlan.serialize_threadgroups]
  value: false
  type: boolean
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.serialize_threadgroups"]' --value "..." --out <file>`
- property: [TestPlan.user_defined_variables.TestElement.name]
  value: 'User Defined Variables'
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.user_defined_variables.TestElement.name"]' --value "..." --out <file>`
- property: [TestPlan.user_defined_variables.TestElement.enabled]
  value: 'true'
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.user_defined_variables.TestElement.enabled"]' --value "..." --out <file>`
- property: [TestPlan.user_define_classpath]
  value: ""
  type: string
  example: `set <file> --locator jmx_d62893619b1f --property '["TestPlan.user_define_classpath"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_d62893619b1f
    component: org.apache.jmeter.control.gui.TestPlanGui
    properties:
      - property: [TestElement.name]
        value: 'Synthetic Test Plan'
        type: string
      - property: [TestElement.enabled]
        value: 'true'
        type: string
      - property: [TestPlan.comments]
        value: ""
        type: string
      - property: [TestPlan.functional_mode]
        value: false
        type: boolean
      - property: [TestPlan.tearDown_on_shutdown]
        value: true
        type: boolean
      - property: [TestPlan.serialize_threadgroups]
        value: false
        type: boolean
      - property: [TestPlan.user_defined_variables.TestElement.name]
        value: 'User Defined Variables'
        type: string
      - property: [TestPlan.user_defined_variables.TestElement.enabled]
        value: 'true'
        type: string
      - property: [TestPlan.user_define_classpath]
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
- property: [TestElement.name]
  value: 'Synthetic Thread Group'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["TestElement.name"]' --value "..." --out <file>`
- property: [TestElement.enabled]
  value: 'true'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["TestElement.enabled"]' --value "..." --out <file>`
- property: [ThreadGroup.on_sample_error]
  value: continue
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.on_sample_error"]' --value "..." --out <file>`
- property: [ThreadGroup.main_controller.TestElement.name]
  value: 'Loop Controller'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.main_controller.TestElement.name"]' --value "..." --out <file>`
- property: [ThreadGroup.main_controller.TestElement.enabled]
  value: 'true'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.main_controller.TestElement.enabled"]' --value "..." --out <file>`
- property: [ThreadGroup.main_controller.LoopController.continue_forever]
  value: false
  type: boolean
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.main_controller.LoopController.continue_forever"]' --value "..." --out <file>`
- property: [ThreadGroup.main_controller.LoopController.loops]
  value: '1'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.main_controller.LoopController.loops"]' --value "..." --out <file>`
- property: [ThreadGroup.num_threads]
  value: '1'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.num_threads"]' --value "..." --out <file>`
- property: [ThreadGroup.ramp_time]
  value: '1'
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.ramp_time"]' --value "..." --out <file>`
- property: [ThreadGroup.same_user_on_next_iteration]
  value: true
  type: boolean
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.same_user_on_next_iteration"]' --value "..." --out <file>`
- property: [ThreadGroup.scheduler]
  value: false
  type: boolean
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.scheduler"]' --value "..." --out <file>`
- property: [ThreadGroup.duration]
  value: ""
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.duration"]' --value "..." --out <file>`
- property: [ThreadGroup.delay]
  value: ""
  type: string
  example: `set <file> --locator jmx_19871e6efa95 --property '["ThreadGroup.delay"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_19871e6efa95
    component: org.apache.jmeter.threads.gui.ThreadGroupGui
    properties:
      - property: [TestElement.name]
        value: 'Synthetic Thread Group'
        type: string
      - property: [TestElement.enabled]
        value: 'true'
        type: string
      - property: [ThreadGroup.on_sample_error]
        value: continue
        type: string
      - property: [ThreadGroup.main_controller.TestElement.name]
        value: 'Loop Controller'
        type: string
      - property: [ThreadGroup.main_controller.TestElement.enabled]
        value: 'true'
        type: string
      - property: [ThreadGroup.main_controller.LoopController.continue_forever]
        value: false
        type: boolean
      - property: [ThreadGroup.main_controller.LoopController.loops]
        value: '1'
        type: string
      - property: [ThreadGroup.num_threads]
        value: '1'
        type: string
      - property: [ThreadGroup.ramp_time]
        value: '1'
        type: string
      - property: [ThreadGroup.same_user_on_next_iteration]
        value: true
        type: boolean
      - property: [ThreadGroup.scheduler]
        value: false
        type: boolean
      - property: [ThreadGroup.duration]
        value: ""
        type: string
      - property: [ThreadGroup.delay]
        value: ""
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
- property: [TestElement.name]
  value: 'Synthetic HTTP Request'
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["TestElement.name"]' --value "..." --out <file>`
- property: [TestElement.enabled]
  value: 'true'
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["TestElement.enabled"]' --value "..." --out <file>`
- property: [HTTPSampler.domain]
  value: example.test
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.domain"]' --value "..." --out <file>`
- property: [HTTPSampler.port]
  value: ""
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.port"]' --value "..." --out <file>`
- property: [HTTPSampler.protocol]
  value: https
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.protocol"]' --value "..." --out <file>`
- property: [HTTPSampler.contentEncoding]
  value: ""
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.contentEncoding"]' --value "..." --out <file>`
- property: [HTTPSampler.path]
  value: /status
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.path"]' --value "..." --out <file>`
- property: [HTTPSampler.method]
  value: GET
  type: string
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.method"]' --value "..." --out <file>`
- property: [HTTPSampler.follow_redirects]
  value: true
  type: boolean
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.follow_redirects"]' --value "..." --out <file>`
- property: [HTTPSampler.auto_redirects]
  value: false
  type: boolean
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.auto_redirects"]' --value "..." --out <file>`
- property: [HTTPSampler.use_keepalive]
  value: true
  type: boolean
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.use_keepalive"]' --value "..." --out <file>`
- property: [HTTPSampler.DO_MULTIPART_POST]
  value: false
  type: boolean
  example: `set <file> --locator jmx_330976848c8e --property '["HTTPSampler.DO_MULTIPART_POST"]' --value "..." --out <file>`

Set card:
```yaml
- set:
    ref: jmx_330976848c8e
    component: org.apache.jmeter.protocol.http.control.gui.HttpTestSampleGui
    properties:
      - property: [TestElement.name]
        value: 'Synthetic HTTP Request'
        type: string
      - property: [TestElement.enabled]
        value: 'true'
        type: string
      - property: [HTTPSampler.domain]
        value: example.test
        type: string
      - property: [HTTPSampler.port]
        value: ""
        type: string
      - property: [HTTPSampler.protocol]
        value: https
        type: string
      - property: [HTTPSampler.contentEncoding]
        value: ""
        type: string
      - property: [HTTPSampler.path]
        value: /status
        type: string
      - property: [HTTPSampler.method]
        value: GET
        type: string
      - property: [HTTPSampler.follow_redirects]
        value: true
        type: boolean
      - property: [HTTPSampler.auto_redirects]
        value: false
        type: boolean
      - property: [HTTPSampler.use_keepalive]
        value: true
        type: boolean
      - property: [HTTPSampler.DO_MULTIPART_POST]
        value: false
        type: boolean
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
