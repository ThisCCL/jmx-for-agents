<div align="center">

# j4a

**让 AI 智能体更安全地编辑 Apache JMeter 测试计划**

通过 CLI 或 MCP 使用 j4a，依据本地 JMeter 安装检查、创建、修改和验证 `.jmx` 文件。

[English](README.md)

</div>

## 安装

```sh
npx -y @jmx-for-agents/j4a install --with-skills
```

需要 Node.js 18 或更高版本、Java 8 或更高版本，以及本地 Apache JMeter 5.6.3 安装。运行 j4a 前请设置 `JMX_AGENT_JMETER_HOME` 或 `JMETER_HOME`。

运行时下载支持 `HTTPS_PROXY`、`HTTP_PROXY` 和 `NO_PROXY`（包括对应的小写变量）。代理值既可以是完整 URL，也可以是不带协议的 `host:port`，因此 `http://127.0.0.1:18888` 与 `127.0.0.1:18888` 均可使用。

## 快速开始

j4a 同时提供 CLI 和 MCP 两种使用界面。需要多次迭代时，推荐使用 MCP，因为长期运行的 MCP 进程可以保持同一个 JVM，并在多次工具调用之间持续加载选定的 JMeter 库和运行时。这可以避免反复启动 Java 进程、加载 JMeter 库和初始化运行时；结构化的工具发现和调用结果也会让每次调用更加明确。

请按照智能体框架自身的 MCP 配置规则注册 MCP stdio 服务，然后在 `JMX_AGENT_JMETER_HOME` 或 `JMETER_HOME` 可用的环境中启动或重启智能体：

```sh
npx -y @jmx-for-agents/j4a mcp
```

注册只会修改智能体框架的 MCP 设置，不会修改项目文件。然后向智能体提出请求：

> 创建 `j4a-first-plan.jmx`，添加一个请求 `https://example.com/` 的 HTTP Request，然后验证该文件。不要运行测试计划。

已安装的 `j4a-master` skill 会引导智能体完成发现、编辑和验证。

## 为什么需要 j4a

直接编辑 XML 会让智能体反复遇到四类问题：

- **序列化格式脆弱：** JMeter 依赖成对的元素与树节点、运行时类名和嵌套属性结构。XML 语法正确，并不代表它是有效的 JMX。
- **上下文开销高：** 大型测试计划会重复大量标签、类名和空结构。反复发送整份 XML 会消耗本可用于推理的上下文 token。
- **大文件补丁风险高：** 重复节点和深层嵌套使大范围文本补丁更容易选错目标、丢失相邻节点或破坏成对树结构。
- **运行时能力不同：** 可用组件、插件和属性取决于选定的 JMeter 安装，智能体无法只根据 XML 安全推断。

j4a 用聚焦的结构化读取、运行时生成的引用、定向操作和 JMeter 保存后重载验证，替代对整份 XML 的盲目重写。

## 可以做什么

- **检查测试计划：** `read` 返回结构化 YAML，而不是原始 XML。
- **发现运行时能力：** `components` 和 `categories` 展示选定 JMeter 安装实际支持的内容。
- **创建和编辑：** `init` 创建基线；`set` 和 `apply` 完成单项或结构性变更。
- **本地验证：** `validate` 检查测试计划，但不运行负载测试。

## CLI 替代方案

CLI 是一次性、可脚本化的使用界面，适合命令、脚本、CI 和自动化：

```sh
npx -y @jmx-for-agents/j4a init j4a-first-plan.jmx
npx -y @jmx-for-agents/j4a validate j4a-first-plan.jmx
```

第一条命令创建 `j4a-first-plan.jmx`；第二条命令通过 JMeter 加载、保存和重新加载该文件，并报告验证结果。

## 安全边界

- **运行时决定能力：** 选定的 JMeter 安装始终是可用组件和属性的事实来源。
- **验证后写入：** 写入命令会先通过 JMeter 保存并重新加载候选文件，再提交结果。
- **显式恢复：** 遇到致命写入失败后，先检查目标再决定是否重试；j4a 不会自动重放请求。
- **不执行负载：** j4a 只在本地编辑和验证测试计划，不运行 sampler，也不连接测试目标。

精确编写规则请参阅 [j4a-master 工作流](skills/j4a-master/SKILL.md)。修改 j4a 本身请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)；发布维护者请阅读 [RELEASE.md](RELEASE.md)。

## 许可证

[Apache-2.0](LICENSE)
