# P3 独立 Review：持久草稿与真实数据绑定

日期：2026-10-10（Asia/Shanghai）

范围：`smart_ui/persistence.ts`、`smart_ui/store.ts`、`smart_ui/binding.ts`、协议中的 `text.bind`，以及当前 `ChatPage → AgentMessageRow → WidgetMessageContent → NativeSmartUiCard` 和 `ConversationRunDependencies` 接线。

## Review Convergence Gate

Latest acceptance target：离开/重开能恢复合法 UI 草稿，编辑、删除、重新生成、分支切换和备份恢复不套错状态；天气绑定只投影四个允许字段，模型上下文 descriptor 与当前选中分支渲染使用同一 ref，缺失/失败数据不伪造值。

### Verification

- `./node_modules/.bin/tsx --test src/test/smart_ui_persistence.test.ts src/test/smart_ui_binding.test.ts src/test/smart_ui_interaction.test.ts` — **PASS，24/24**。
- `./node_modules/.bin/tsx --test src/test/widget_message_content.test.cjs src/test/message_row_response_assets.test.cjs` — **PASS，3/3**（最新 source lifecycle 改动后重跑）。
- `./node_modules/.bin/tsc --noEmit --pretty false`（`harmony/chat`）— **FAIL，1 个既有 `src/test/weather_tool.test.ts:79` 的 `JsonValue` 类型错误；未见 P3 文件错误**。
- 已用纯域最小复现验证 `saveDebounced()` 后立即 `load()` 返回旧值：`load missing` 先于 `save saved`。
- 已验证 `weatherBindingRef('call.a') === weatherBindingRef('call_a') === 'weather_call_a'`。
- 尚未执行本阶段设备截图/AX；当前结论以源码和纯域证据为准。

## Finding Ledger

| ID | 状态 | 风险 | 发现与证据 | 最小关闭动作 |
| --- | --- | --- | --- | --- |
| P3-01 | **Closed（域层 + 回归）** | 高 | `SmartUiDraftStore.load()` 现在先 `flushPendingKey(smartUiDraftKey(source))` 再读 KV（`store.ts:111-115`）；新增 persistence 回归验证 pending debounce 在 load 前落盘。 | 保留 24/24 纯域结果。 |
| P3-02 | **Closed（源码闭环，设备待验收）** | 高 | `onSmartUiDocument()` 只在两个非空 node id 真正变化时视为 branch change（`ChatPage.ets:4303-4313`）；流式空 node → 终态 node 保留 `userChanged`，终态立即保存。迟到 load 仍受 `current.userChanged` 守卫。 | 保留现有 source/fingerprint 守卫；设备验证流式选择后终态重挂载。 |
| P3-03 | **Closed（恢复接线，设备待验收）** | 高 | `SettingBackupPage.doRestore()` 在 `restoreConversations()` 成功后用共享 store 按会话清理 UI 草稿（`:349-354`）；ChatPage 通过 `conversationRestoreVersion` 在重载前清空 runtime、values、projections（`:1531-1534`）。清理失败会明确进入附属状态失败文案。 | 设备验证恢复后显示定义默认值。 |
| P3-04 | **Closed（当前消息生命周期）** | 中 | `confirmDeleteMessage()` 成功删除后调用 `cleanupSmartUiMessage(conversationId,messageId)`（`:2851-2854`），删除 runtime、内存值并按精确 source `remove()`；会话重载还会用 `reconcileSmartUiSources()` 清理失效 node/message。 | 保留 source 精确匹配，不扩大为全局清空。 |
| P3-05 | **Closed（域 + native 回归）** | 高 | 非安全 ASCII ref 现在始终带原始 tool id hash；`call.a` 与 `call_a` 已区分。Native 节点遇到重复 ref 显式显示“数据不可用”，不取第一份。`smart_ui_binding` 与 `native_smart_ui_card` 覆盖该边界。 | 保留 5 项 native targeted tests。 |
| P3-09 | **Closed（共享队列 + 回归）** | 高 | 新 `SmartUiDraftSupport.ets` 为 ChatPage、ManagedFileStore、备份恢复提供同一 KV draft store；所有会话删除入口都能取消 pending 写。`smart_ui_draft_support.test.cjs` 实际证明两个 owner 取到同一 store，删除后 pending 返回 `cancelled` 且 KV 为空。 | 生产 AppContainer 保证传入同一 `ChatKvStore`；不新增 tombstone/全局 UI 值。 |
| P3-10 | **Closed（内容接线，设备待验收）** | 高 | 复核发现终态若只改变 `messageStreaming/nodeId` 而 UI 文档未变，旧 fingerprint 不会通知 Page，导致 `nodeId=''` 阶段不能落盘。现 `WidgetMessageContent` 的 watched props 与 definition fingerprint 同时包含 `messageStreaming`、`smartUiNodeId`（`:31-33,96-103`），终态 callback 传入真实 node 后 `ChatPage` 以 `ready + userChanged` 立即保存。`widget_message_content` / `message_row_response_assets` 最新 3/3 通过。 | 设备上用真实流确认选择后终态重挂载仍保存并可重开恢复。 |
| P3-06 | **Closed（域层）** | 低 | `serializeSmartUiDraft/restoreSmartUiDraft` 只接受 `ready + ended`、schema/protocol=1、精确 source、definition fingerprint 和合法 state 值；单块 8 KiB、会话 128 KiB 按 UTF-8 字节核算。 | 保留现有 10 组 persistence 测试，接线层仍需覆盖 hydration 竞态。 |
| P3-07 | **Closed（当前接线）** | 低 | `currentSmartUiWeatherProjections()`（`ChatPage.ets:4152-4171`）从每个 `MessageNode` 的 `nodeCurrentMessage()` 构造 source，故只取当前选中分支；`ConversationRunDependencies.prepareMessages()`（`:280-298`）只在模型可见 prepared context 的副本上加 descriptor，不改持久消息。 | 继续保持 descriptor 与 renderer 共用 `weatherBindingRef`；P3-05 修复后再关闭整体绑定门槛。 |
| P3-08 | **Closed（当前接线）** | 低 | `projectWeatherReadTool()` 只输出 `location`、`temperature_celsius`、`condition`、`observed_at` 四个标量；missing/failed/malformed 返回 null 占位，`NativeSmartUiNode.boundText()` 对未知值显示“未提供/数据不可用”，不会把 fallback 当真实值。 | 保留 `smart_ui_binding.test.ts` 的字段与失败样例。 |

## Current verdict

**PASS（源码与 targeted tests）；设备门槛待 root 收口。** 六项原始 P3 风险及新增的 source lifecycle 风险均已有最小修复和针对性证据：24 项纯域测试、1 项共享队列测试、5 项 native 天气/歧义测试、3 项消息内容/来源回归，以及协议路由回归均通过。当前未重复执行完整 build、签名、设备截图/AX，因为 root 正在运行完整构建；因此不能把 P3 的设备验收写成已通过。

剩余动作只有 root 的完整 build/lint/签名，以及设备上的离开/重开、选择后继续流式、分支切换、备份恢复和天气卡截图/AX 验收。源码层未发现需要再扩大的新风险。
