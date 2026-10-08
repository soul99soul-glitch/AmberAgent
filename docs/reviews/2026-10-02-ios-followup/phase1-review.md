# Phase 1 独立 review

比较范围：`/tmp/amber-ios-followup-20261002-baseline` 与当前工作区的 Provider 增量。未改源码，未把开始时已有 WIP 算成本轮修改。依据 harmony-arkts skill 检查共享 schema、解析、合并、协议投影、工具循环、持久化及现有 UI 调用链；本 reviewer 不负责构建、签名和设备截图。

## 已确认发现与处理

1. **Responses 失败前已开始推测工具执行（高，已修复）**。`response.output_item.added` 给出完整 `{}` 参数后，adapter 即刻 `onChunk`，tool loop 的 raw flush 启动 speculative runner；随后 `response.failed` 才拒绝。真实 `createOpenAIResponsesApi → asResponsesChatStreamProvider → runChatTurnWithTools` 探针在两事件间隔 70 ms 时得到 `executed=1`。失败工具也进入 raw checkpoint，后续恢复可误认为可执行工具。实现者已将工具缓存到成功终态且传输成功后才发布，失败与取消丢弃。独立复验同一 70 ms 探针 `executed=0`，最终 raw 只有正文且无工具；成功并行调用、碎片参数、终态补 call ID、cancel／HTTP failure 回归均通过。

2. **Responses 流式失败终态独有正文丢失（中，已修复）**。`parseResponsesOutput` 新增的 `ProviderResponseError.partialChunk` 在 stream adapter 的 catch 只存入错误，未进入 reconciler 和 accumulator。探针得到 `terminalPartialPublished=false`；此前增量正文能保留，但 failed/incomplete envelope 里补全的正文不能保留。实现者已将失败 partial 先经原 reconciler 发布可显示内容再抛原错误，partial 中工具仍过滤。独立复验同一探针 `terminalPartialPublished=true`；envelope-only／重叠前缀回归均通过且正文不重复。

3. **失败后最新尾部未落库（中）**。raw 快照被 512 字符／10 秒 checkpoint 节流时，`ConversationRunService` 失败 catch 只等待写队列并 reload；finally 清空视觉尾部。真实 service fixture 的 RED 得到 `first`，预期 `first latest`。root 已在失败 catch 精确投影并保存同 run 的 raw，已有 write error 时不再尝试保存。独立复验 service 5/5 GREEN；复核原 `saveContext` conversation/run identity 保护、串行 write queue、regen 按目标节点投影和原存储错误优先级，未发现新问题。

## 通过的逻辑检查

- Gemini wire ID 留在 metadata 与工具 ID，两种调用结果回放保持原 opaque ID；合法无参调用用 `{}`。同名并行调用不按名字配对；重复原 ID 的完整参数走 replace。工具在传输成功后才暴露，SAFETY 等失败保留可显示正文而不执行工具；取消不发布缓存工具。
- Claude redacted 原块和仅签名块经 shared schema、coalesce、JSON 序列化保持；thinking delta/signature delta 按原块索引合并；已 finished 的旧块不被下一工具轮复用的 index=0 覆盖。目标协议投影不修改 canonical history，空签名和外来普通推理不构造 Claude thinking。
- Responses 原生 reasoning ID 和 encrypted payload 保留，空可见正文也不被持久化过滤；不同 reasoning ID 不合并；已流出的 summary 不因终态重发全文重复显示。缺 reasoning ID 的外来推理在 Responses request 投影时过滤。
- 非流式 partial 走 `generateAssistantOnce → onRawFlushSnapshot / onUpdate → ConversationRunService` 同 run 捕获与落库；普通错误继续沿既有错误路径显示。

## UI 检查边界

本 phase 无新布局或控件。`groupMessageParts` 仅给非空 reasoning 构造思考步骤，opaque／签名空正文不形成空卡；现有 `AgentMessageRow` 读取这些分组。`ConversationRunService.errorMsg → ChatPage.errorMsg → 底部错误行` 已完整接线。发现 1 的失败工具残留卡属于实际 UI 状态缺陷，已随工具延迟发布修复；尚不作设备像素对齐、暗色截图或真实 Provider 网络验收结论。

## 独立验证

- Provider/merge/grouping/display/API/resume focused suite：150/150 GREEN。
- 当前真实 service fixture：5/5 GREEN（最新失败尾部保存回归先 RED 后 GREEN）。
- 真实 Responses 工具循环探针：初轮复现 `executed=1` 与失败终态正文未发布；修复后同一探针 `executed=0`、终态正文已发布且最终 raw 无工具。

状态：主 agent 已确认 Phase 1 实现文件集 ready。三处具体发现均修复并独立复验通过，源码／调用链／持久化／既有 UI 状态 review 完成，没有未处理的具体发现。构建、签名与最终设备 UI 检查由主 agent 单独记录。
