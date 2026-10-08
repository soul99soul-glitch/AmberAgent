# Phase 5 A 独立 review：工具上下文筛选与保留

结论：本次 A 源码范围内未发现尚未关闭的具体缺陷。两项独立复现的问题已修复；随后真实工具 JSON schema 回归也通过。可以进入主 agent 的整体构建与集成验收。

本 review 只改本报告，未修改实现、运行构建、签名或操作设备。对照本轮 baseline 与最终增量，审查 `jev_context.ts`、`context_engine.ts`、`JevContextSupport.ets`、`ConversationRunDependencies.ets`、`ContextCompactionSupport.ets` 和相关 Jev 配置/执行许可入口；未把原有大类的无关逻辑纳入新增缺陷范围。

## 实际调用链

- 普通发送、工具继续与再生成：`chat_turn` / `tool_loop` / `regenerate` 的 `prepareContextMessages` → `ConversationRunDependencies.prepareMessages` → 当前会话/run 的 Jev runtime → `prepareContext` → 请求副本投影 → 既有 editor → provider。
- 手动压缩：现有 ChatPage/service 的压缩入口和会话写入 admission → `ContextCompactionSupport.compactConversationNow` → `compactConversation` → 同一个可选 `prepareToolResults`。
- 自动 force/precompact/model-window fit 也经过 `compactConversation` 的该入口。投影接收完整当前历史，再按原 source message IDs 取压缩源。原 canonical history、source IDs 与 compact 存储结构不变；off 不为压缩源额外应用 generic clear。
- retention 判断以后台 promise 运行；`prepare` 不等待 retention 网络完成。其结果只用于后续请求副本对既有 trim/clear 的豁免，不承诺绕过原有摘要压缩或模型窗口限制。

## 独立发现与闭环

1. **同一个 assistant message 内追加的重读被再次隐藏。** 生产 `tool_loop` 会复用 assistant message 追加 tool part，原判断只比较 messageIndex，无法识别这个实际调用形态。独立 probe `/tmp/amber-phase5-A-reread-probe.mts` 修复前输出 `evals:2, rereadFull:false, rereadHidden:true`；增加 partIndex 顺序后为 `evals:1, rereadFull:true, rereadHidden:false`。Worker 加入对应 RED→GREEN 回归，合法同参、不同 call ID 的新重读保持全文，并跨 turn 保留。
2. **显式全文要求改变了 off/shadow 的既有 editor 行为。** 原早返回无条件给出 retained IDs。独立 probe `/tmp/amber-phase5-A-fulltext-mode-probe.mts` 通过真实 `editPreparedContext` 复现 off/shadow 也跳过清空。修复后两者 retained IDs 为零、与原 editor 相同；只有有输出许可的 active 用途保留原文。所有模式的显式全文请求均不发 Jev。

另复验 worker 的三个实际生产补充：同 assistant 尾部追加文本算已使用结果、首次隐藏之前已存在的第二个调用不倒算隐藏后重读，以及 JSON envelope 的正文筛选。最后一项采用真实 `buildFileReadJson`，同时核对生产 `session_read` 的 `transcript` 字段：仅解包这两种已知正文，重新编码时保留 path/session/status/message_count/total_size_chars/truncated/max_chars 等外围字段；原 output 仍用于 identity 比较并保存在 canonical history。列表/搜索的 JSON 未引入通用递归拆解。

## 规则与生命周期核对

- selection 只对本地已执行且非拒绝的单 text 大结果启用，工具范围为 `file_read/file_search/file_list/session_read/session_search`。活网页、HTTP/MCP、写工具和混合内容不进入 selection，避免给出无法保证的同参原文恢复承诺。
- 正文块保持原范围，代码栅栏与 Markdown 表格作为整体。结构错误、未知动作/可能已执行、审批、TODO 和分页提示不隐藏；JSON envelope 自身不参与正文块替换。
- 输出许可为 A 用途的核心准入。task text 与工具 metadata 分别控制外发字段；output-only 模式的 payload 不含独立 task/outline/tool 字段。正文作为用户已许可的 tool output 发送。
- off 零 evaluate；shadow 仅观察并去重，既不投影文本，也不给 editor retained IDs。明确全文请求亦零 evaluate。
- 重读要求本地工具、合法 JSON object 参数的 canonical 等价、不同 call ID、在先已隐藏结果和新增结果；仅有同名或同 call ID 不构成恢复。
- retention 从近期窗口内且已被后续 assistant 使用的结果发起；`p >= 0.7` 才保留。首次出窗口仍未完成的判断固定沿原 clear，迟到结果不能反向恢复。新的 run、中止与该用途 consent/config 改变均阻止旧异步判断应用。
- 缓存按会话归属，原 text/input/tool 精确比较；输出变化或分支移除使对应决定失效。最多 16 个会话、每会话 256 个 result decisions，驱逐会话会 abort 在途工作；selection 与 retention 分别依据各自 consent key 失效。

## 独立验证范围

在 `harmony/chat` 独立执行：

```sh
./node_modules/.bin/tsx --test src/test/jev_context.test.ts src/test/context_engine.test.ts src/test/context_compact.test.ts src/test/jev_purposes.test.ts
node --test src/test/jev_runtime_purposes.test.cjs
./node_modules/.bin/tsx --test src/test/manual_compaction.test.ts
```

结果：受影响领域组合 **87/87**（其中 A 新增 **17** tests），Entry purposes 合计 **9/9**，既有手动压缩回归 **2/2**。日志分别为 `/tmp/phase5-A-review-focused.log`、`/tmp/phase5-A-entry-review.log`、`/tmp/phase5-A-manual-review.log`。Entry 9 个测试包含其他 Phase 5 用途，不将它们全部归为 A。

A 没有新增 UI 布局，本 review 不据此声称设置/notice 的设备布局通过。单元/转换后 Entry 测试和源码证明行为及接线，不证明真实 Jev 服务返回、设备运行时性能或付费 API 验收；整体 build/sign/install 与 UI 证据由主 agent 另记。

## 最终整合窄复核

冻结后的新增 consent race 已闭环：构造 payload 使用的设置与 `JevSupport` 首次读取设置之间撤销可选 task/metadata 许可，原本只核核心许可会外发旧字段。现在 A 的 `JevContextPolicy.settingsSnapshot`、B 的 `createJevAutoApprovalReview` 和 C 的 `evaluateJevCompletion` 均把构造时的完整设置传到 `jevEvaluatePurpose` 第五参数 `expectedSettings`。Entry 首次 load 即按同用途 scope 与全局 mode/apiMode/apiKey/baseUrl/model 比较；不一致在预算预留及 HTTP 前返回。预算等待之后再次比较，HTTP 返回后再次比较；A/B/C 应用结果前还各自核对用途设置及 run/abort 或 notice binding。没有通过只删除旧字段来猜测新的 payload 语义。

另外复核了公共 index 出口和实际集成：前台 service 经 `ConversationRunDependencies` 接 A，手动/自动压缩使用同入口；B 前台 dispatcher 动态读当前已保存消息，Council/子 agent 接各自消息范围，`ScopedRecipeLoop` 替换 dispatcher 时保留 review 和既有 terminal hooks/allowlist。C 完成判断只产生易失 notice；service 验证当前 run、completed 状态、无 pending tool 和最终文本/source 指纹后发布。没有 UI 接收者时不自动发起验证对话；只有用户点验证按钮才可能调用 `send`，有草稿/附件/编辑时先合并到 composer。

本次仅独立运行 18 个 A tests 和 3 个 Entry consent tests：

```sh
./node_modules/.bin/tsx --test src/test/jev_context.test.ts
node --test --test-name-pattern='consent revoked|disabled-late consent' src/test/jev_runtime_purposes.test.cjs
```

结果 **18/18、3/3**。覆盖构造快照透传、构造后撤销许可零 HTTP、HTTP 中撤销许可舍弃成功结果，以及完成判断 off/shadow/迟撤销不发布 notice。日志 `/tmp/phase5-A-final18-review.log` 和 `/tmp/phase5-integrated-consent-review.log`。未重复全量、构建或设备动作；原 87/87 是上节记录的较早组合，不将新增后的组合计数冒充独立重跑结果。

`provider_auxiliary_auth_entry.test.cjs` 的最小 diff 也已核对：只给旧 harness 注入真实领域 `JevContextRuntime` 的 off/无 scope 配置，evaluate 为 `assert.fail`，补当前 run IDs，并对新 seam 断言同一个 messages 引用、retained IDs 为零。原 OAuth/custom auth/signal 断言保留，没有跳过测试或改产品来迁就 harness。Worker 单文件 `/tmp/phase5-A-auth-harness-green.log` 为 **23/23**；本 reviewer 核对 diff 和日志，未声称自行重跑该文件。

最终整合范围内没有剩余具体阻断。整体 build/sign/install 和设备离线验收由主 agent 另记。
