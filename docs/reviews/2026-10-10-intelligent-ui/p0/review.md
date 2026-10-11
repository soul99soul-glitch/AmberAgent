# P0 独立 Review / Closure

日期：2026-10-10（Asia/Shanghai）

审查范围：`smart_ui/protocol.ts`、对应的协议测试和 20 条固定 fixture、`openai_parse.ts` 的 finish-only choice 修复、`ConversationRunDependencies.ets` 的 provider 诊断包装，以及 `provider_stream_diagnostics.test.cjs`。本轮没有新增原生智能 UI 组件，因此没有做 native 卡片的错位、对齐、边距、字号或真机目测；这些检查应放到 P2 原生渲染落地后。

## 结论

本次只复核上一版报告的三项发现。协议测试 10/10、OpenAI 解析相关测试 27/27、provider 诊断测试 3/3 通过；改动文件 lint 仍为 0 error。`tsc` 的唯一失败仍是既有的 `weather_tool.test.ts` JsonValue 类型错误。

三项均已关闭。计划已明确 state-first 契约：状态必须先于任何引用它的节点，不建立后置 state 的悬挂节点队列。

## 发现 1：已闭合空块没有终态诊断（已关闭）

严重度：中

`parseSmartUi('', 'empty', true)` 和只包含空行的闭合块均返回：

```text
status: partial
ended: false
diagnostics: []
```

实现现在在终态且 `!ended` 时无条件产生 `missing-end`，同时对缺 root 产生 `missing-root`；空字符串回归测试确认状态为 `error`。

## 发现 2：语义无效的 upsert 会丢掉上一个有效节点（已关闭）

严重度：中

以下输入可复现：

```text
{"op":"state","id":"selected","initial":"root"}
{"op":"node","id":"root","type":"column"}
{"op":"node","id":"choice","parent":"root","type":"choice","state":"selected","options":[{"value":"root","label":"根"}]}
{"op":"node","id":"choice","parent":"root","type":"choice","state":"missing","options":[{"value":"root","label":"根"}]}
{"op":"end"}
```

实现现在对 new node 和 upsert 都在提交时校验引用；后置 state 引用被明确转换为 `state-after-dependent-node` 并拒绝，旧节点保持不变。计划已同步 state-first 契约，新增 node、upsert 和后置 state 回归均通过，原问题及其边界回归已关闭。

## 发现 3：非流式主生成绕过诊断 wrapper（已关闭）

严重度：中

`generateText` 现在和 `streamText` 共用 `logProviderCompletion`，记录返回 chunk 的文本字符数、finish reason 和失败/取消 outcome；回归测试确认返回对象不变、错误原样传播、无正文写入，并确认没有 `generateText` 的 provider 仍返回 `undefined`。

## 已确认的闭环

- `finish_reason` 非空而 `delta/message` 为空的 OpenAI 流块现在保留 choice，不凭空生成消息正文；无 finish reason 的 keep-alive/usage-only choice 仍被跳过，usage 仍保留。
- 主会话正常发送、工具循环、再生和 Responses 恢复路径都能从 `ConversationRunDependencies` 取得 wrapper；`interactive_turn_runtime` 只在其外层增加传输诊断，不会绕过已有 wrapper。
- wrapper 的当前日志只含 run/conversation ID、文本字符数、finish reason 和终态，不含测试中的 `PRIVATE OUTPUT` 正文、请求头或密钥；stream 和 non-stream 两条路径均已验证。
- Smart UI 只接受有限节点、标量状态、受限 draft 文本和合法 ID；脚本、HTML、SVG 原文和 Three.js 字符串不能作为 asset ref 进入协议对象。root、父节点顺序、父节点类型、choice 初始值、input 字符串状态和 UTF-8 字节预算均有测试或代码约束。
- P0 20 条 fixture 是本地合成回放，不是实际模型生成结果。它证明 parser 能消费预期格式，不能代替计划要求的模型 18/20 生成门槛；该门槛仍由模型评估结果决定。

## P0 UI 范围

本阶段没有 `SmartUiDocument` 的 ArkUI renderer、状态 owner、原生卡片布局或设备交互，所以不能声称已完成 native UI 的尺寸、热区、对齐、间距、滚动、主题重挂载或截图检查。模型 20 条 fixture 仍是合成回放，真实模型 18/20 门槛没有在本报告中标为通过；真机截图留到 P2。

