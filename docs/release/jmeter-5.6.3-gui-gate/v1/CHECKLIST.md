# JMeter 5.6.3 真实 GUI 发布检查清单（v1）

本清单供 OpenSpec 任务 8.5 使用。任务 8.4 只准备夹具和预期结果；本版本未启动 JMeter GUI、未执行采样器、未生成 JTL，也未进行压测。

## 固定环境

- [ ] 将 `JMETER_HOME` 指向 JMeter 5.6.3，并使用 `${JMETER_HOME}/bin/jmeter` 启动真实桌面 GUI。
- [ ] 确认 JMeter 显示版本为 5.6.3，并可加载已安装的 `jp@gc - Stepping Thread Group`。
- [ ] 记录 J4A all.jar SHA-256 为 `27cb5646fa96a325690d24bc459987e04a33689648d6a8b34ed74b2f3eb7839c`。
- [ ] 禁止点击 Start/Remote Start；本门禁只检查打开、面板绑定、编辑、另存和重开。
- [ ] 将 GUI 日志、截图、另存副本和复验输出写入独立的 8.5 证据目录，不覆盖本目录夹具。

## 每个夹具的通用步骤

- [ ] 从 `fixtures/` 打开目标 JMX，确认无 `Failure loading test file`、ClassCastException 或缺失插件错误。
- [ ] 展开完整树，逐项对照 `manifest.yaml` 的 `expected_gui`。
- [ ] 点击涉及的组件，确认普通字段、表格行、嵌套面板和启用状态可见且值一致。
- [ ] 对一个非敏感字段做可逆编辑，撤销后再另存为新文件；不得修改版本化原件。
- [ ] 关闭并重新打开另存文件，确认树结构、行具体类型对应的字段和数值仍存在。
- [ ] 用同一 JMeter home 对另存文件执行 packaged J4A `read` 与 `validate`，保存输出。
- [ ] 对比原件哈希，确认 GUI 操作未覆盖版本化原件。

## 场景夹具

- [ ] `debug.jmx`：原生 Thread Group 为 1 线程 × 1 循环；存在两个 HTTP Request、公共 JMESPath 断言和 `View Results Tree - debug only`。
- [ ] `master.jmx`：存在已安装的 Stepping Thread Group；两个业务及参数行存在；不存在 View Results Tree。
- [ ] `baseline.jmx`：仅 Business A 存在/启用，Business B 已由 J4A 删除。
- [ ] `capacity.jmx`：两个 Percent Executions 吞吐量控制器分别为 70.0 和 30.0，业务 A/B 位于对应控制器下，总和为 100%。
- [ ] `stability.jmx`：Stepping Thread Group 显示 80 线程、启动批次 80、`flighttime=18000` 秒；70/30 模型保持不变。
- [ ] `http-fields.jmx`：HTTP Request 显示 `https`、`api.example.invalid`、`8443`、`/api/orders`、`POST` 和一行 HTTP 参数。

## 菜单类别、结构化属性和嵌套面板

- [ ] `catalog-core-categories.jmx` 覆盖 manifest 中 11 个核心菜单类别的运行时发现组件。
- [ ] `Structured - Empty Header Manager` 的 headers 表为空但可正常显示。
- [ ] `Structured - Populated Header Manager` 显示 `X-J4A-GUI-Gate=sanitized`。
- [ ] `Category - HTTP Request` 下可见 Regular Expression Extractor、User Parameters 和 Response Assertion 嵌套组件。
- [ ] `debug.jmx`、`master.jmx`、`http-fields.jmx` 的 HTTP 参数表加载后字段完整，无基础 `Argument` 替换专用 HTTP 行造成的 GUI 异常。

## 发布判定

- [ ] PASS：所有夹具可打开、相关面板可交互、另存/重开成功、packaged J4A read/validate 通过、原件哈希不变。
- [ ] BLOCK：任何可复现的 J4A 生成结构导致打开失败、面板绑定失败、具体行类型丢失、另存后结构损坏或重开失败。
- [ ] 若失败，只记录最小复现、JMeter 日志、截图、输入/输出哈希和受影响组件；不得以手工修 XML 作为通过依据。
