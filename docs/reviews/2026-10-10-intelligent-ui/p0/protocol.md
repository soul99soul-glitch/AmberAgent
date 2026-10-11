# P0 智能 UI 协议原型复核

日期：2026-10-10

## 交付范围

`harmony/chat/src/main/ets/chat/smart_ui/protocol.ts` 提供纯 TypeScript 的 `amber-ui v=1` NDJSON 记录解析，不依赖 HarmonyOS SDK、WebView、SVG 或 Three.js。

对后续 router/native 的稳定接口为：

- `SmartUiDocument`：`id`、`nodes`、`states`、`status`、`diagnostics`、`ended`、`rootId`、`records`。
- `SmartUiNode`：扁平的 `id`、`parent`、`type`，以及按目录显式声明的 `text`、`state`、`options`、`placeholder`、`label`、`action`、`ref`、`visibleWhen`。
- `SmartUiState`：标量 `initial` 和推导后的 `valueType`。标量只有 `string`、`number`、`boolean`。
- `validateSmartUiNode`：不依赖文档上下文的单节点校验。
- `validateSmartUiState`：单状态声明校验。
- `parseSmartUi(body, id, closedOrTerminal)`：逐行解析和文档级约束校验。

## 已闭合的协议规则

- 每条非空 NDJSON 记录必须占完整一行；流式输入的最后无换行行只产生 `incomplete-line` 警告并等待后续内容。围栏关闭或消息终态时才解析尾行。
- 只有 `state`、`node`、`end` 三种 `op`。首批节点固定为 `column`、`text`、`choice`、`input`、`button`、`asset`。
- 根节点唯一且必须是 `column`。子节点必须在父节点之后声明，父节点也必须是 `column`；节点 ID 的后续声明只允许同 `type`、同 `parent`、同状态值类型的内容更新。
- 状态必须先于引用它的节点声明；缺失或后置状态不会进入待定队列，节点记录直接诊断为 `state-after-dependent-node` 并丢弃。状态重复声明不能改变初始值或值类型。状态字符串最多 512 字符；`input` 只绑定字符串状态，`choice` 的初始值必须出现在选项中。`choice`/`input`、`visibleWhen` 和 draft 占位符的状态引用在节点提交时检查。
- `button` 只支持无副作用的 `draft` 动作，文本只接受 `{{state_id}}` 占位符；HTML、脚本、表达式、未知字段和 SVG/Three.js 内嵌数据不会进入返回类型。
- `end` 后任何非空记录都会产生 `after-end` 错误。`ready` 只表示出现 `end` 且没有错误；未终止流为 `partial`，终态存在错误为 `error`。
- 终态空块明确产生 `missing-end` 和 `missing-root`。已有有效节点的后续 upsert 先做状态引用、`input` 类型和 `choice` 初始值语义校验；无效更新只记诊断并保留旧快照。解析器不做终态二次引用扫描。
- 宿主预算为 64 节点、6 层深度、256 条记录、单行 16 KiB UTF-8 字节、总块 128 KiB UTF-8 字节、32 个状态、32 个选项。超限会留下已接受节点并给出诊断。

## 验证

`harmony/chat/src/test/fixtures/intelligent-ui/p0-model-cases.json` 保存 20 个本地固定请求回放，分布为选择解释 8、输入草稿 4、独立 asset 4、组合 4。测试不会发起模型或收费调用。

`harmony/chat/src/test/smart_ui_protocol.test.ts` 覆盖：

- 20 个 fixture 的 ready、唯一 root、节点目录和状态断言；
- 流式完整行、终态无换行尾行和坏尾行；
- parent 先声明、唯一 root、兼容 upsert、type/parent 变更拒绝；
- 六类节点的单节点校验和脚本/HTML/Three.js 入口拒绝；
- 坏记录隔离、状态先声明约束、end 后记录和各项预算。

相关命令：

```text
npx tsx --test src/test/smart_ui_protocol.test.ts
./harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/chat/smart_ui/protocol.ts
```

协议测试 10/10 通过，codelinter 为 0 error（36 条 warning，均来自当前仓库对 `JsonObject` 索引的既有 unsafe 规则提示）。目标文件的定向 `tsc` 通过；全仓 `tsc` 仍有一个与本次文件无关的既有 `weather_tool.test.ts` JsonValue 类型错误，未修改该文件。

## 后续接入边界

P1 router 负责围栏头语法、消息块识别和 `id` 传入；本解析器不读完整消息、不解析 SVG/Three.js、不执行动作。P2 native 只能消费返回的显式类型和状态快照，不能把 `JsonObject` 或原始脚本文本重新解释为组件代码。
