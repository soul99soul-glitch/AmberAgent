# P2 阶段审查 Ledger

日期：2026-10-10（Asia/Shanghai）

审查范围：`smart_ui/protocol.ts`、`smart_ui/interaction.ts`、`NativeSmartUiCard.ets`、`NativeSmartUiNode.ets`，以及 `AgentMessageRow → WidgetMessageContent → ChatPage` 的状态、草稿、asset 定位调用链。重点核对六类原生节点、状态优先级、同一流生命周期、局部资源失败隔离、草稿动作、ForEach 稳定 key 和单列布局。

本轮只写审查记录，没有修改生产文件。当前没有设备截图或 AX 树，因此所有设备/无障碍结论都保留为待验收，不以源码推断替代设备证据。

## 验证记录

- `smart_ui_interaction.test.ts`：6/6 通过。
- `smart_ui_protocol.test.ts`：12/12 通过。
- `native_smart_ui_card.test.cjs`：2/2 通过；该测试主要是源码级约束，不等于设备交互证据。
- `message_row_response_assets.test.cjs`：1/1 通过。
- `smart_ui_persistence.test.ts`：10/10 通过；这是域层序列化/恢复测试，不覆盖 ArkUI 异步挂载时序。
- 实际 `ChatPage` 私有方法抽取 VM 回放通过：UI `sourceStart=0/part=1` 与图形 `sourceStart=1200/part=99` 仍按 `[messageId, ref]` 命中，调用 `scroller.scrollBy`，未走行级回退。
- 读取主线提供的 HarmonyOS 模拟器 `server-selected.jpeg/json`：原生 choice、asset、draft 与 SVG 均可见；本轮未执行 build、lint、安装或新的设备回放。

## Finding Ledger

| ID | 状态 | Owner | 结论与关闭条件 |
| --- | --- | --- | --- |
| P2-01 | **逻辑已修，设备待验收** | AgentMessageRow / ChatPage | 流式期间 `smartUiSourcePrefix` 已改为 `conversationId + messageId + blockIndex`，不再携带会在终态补齐的 `nodeId`。之前 `nodeId='' → 真实 nodeId` 会触发 `WidgetMessageContent.prepare()` 清理旧状态，导致生成期间的选择丢失。需用真实流式回放确认选择在终态仍保留。 |
| P2-02 | **逻辑已关闭，设备点击证据待补** | ChatPage / WidgetMessageContent | ChatPage 已把区域 key 统一为 `JSON.stringify([messageId, ref])`，不再使用 UI 与 SVG/Three 之间不同的 `sourceStart`。实际 `ChatPage` 私有方法 VM 回放以 UI `sourceStart=0/part=1` 注册、以图形 `sourceStart=1200/part=99` 点击，确实调用 `scroller.scrollBy(0, 806)`，没有走 `applyInitialScroll` 回退；证据见 [`asset-area-vm.json`](./asset-area-vm.json)。仍需设备上实际点击 asset 后截一次滚动结果。 |
| P2-03 | **逻辑已关闭，p95 待补** | NativeSmartUiCard / ChatPage | 页面 hook 已改为只触发 debounced persistence，状态唯一写入路径是 `stateAccessor.set()`；不再重复执行 `setSmartUiValue()`。仍需按 P2 门槛记录真实设备 50 次输入/选择的交互 p95。 |
| P2-04 | **Closed（逻辑、单测与模拟器草稿证据）** | smart_ui/interaction | `appendSmartUiDraft()` 已从裸 `endsWith(next)` 改为仅在完整输入或 `\n\n${next}` 段落尾部去重；`已有背景继续解释 + 继续解释` 会追加新段落。`draft-append.json` 的 TextArea 实际包含根证书草稿和服务器证书草稿，中间为空行，重复点击没有再次追加。 |
| P2-05 | **Closed（协议与单测）** | smart_ui/protocol | v1 已限制一个 state 只能绑定一个 `choice`/`input` 控件，并允许同 ID upsert 释放旧绑定。此前多个 choice 共用 state 会产生并集校验但各控件选中状态不一致；新增 `state-control-duplicate` 规则和回归测试已覆盖。 |
| P2-06 | **Closed（实际 Builder 回放 + 模拟器）** | NativeSmartUiNode / WidgetMessageContent | `NativeSmartUiNode` 已改为 ForEach 只保留 `nodeId`/`optionKey`，Builder 内从当前 `document` 读取定义；`native_smart_ui_card.test.cjs` 的 current-upsert 检查和 `widget_message_content.test.cjs` 的实际生产 Builder 回放均通过。`server-selected.jpeg/json` 也显示更新后的服务器证书状态和可点击原生控件。 |
| P2-07 | **逻辑已补强，P3 恢复回放待补** | NativeSmartUiCard / ChatPage | `smartUiRuntime` 已加入 `userChanged`、`loaded`、`loadEpoch`、definition fingerprint 和 source 比对；迟到 KV 结果会在 source、定义或用户编辑变化后丢弃。纯域恢复测试通过，但 P3 仍需离开/重开和用户先编辑再迟到恢复的真实回放。 |
| P2-08 | **Open（AX 状态语义）** | NativeSmartUiNode | 模拟器 AX 能读到 `根证书`、`服务器证书`、`中间证书`、`查看图示`、`继续解释`，choice 行均为 clickable；但三个 choice 行的 `selected=false`、`checkable=false`，`description` 为空，AX 没有反映服务器证书已选。需补可读的选中状态语义，并再次 dump AX。 |
| P2-09 | **Open（真实可读性）** | protocol / NativeSmartUiNode | 模拟器截图和 AX 实际显示 asset 的内部 ref `ca-chain`，用户看到了技术 ID。建议复用已有 `SmartUiNode.label` 字段：asset 允许可选 `label`（沿用 120 字符限制），有 label 显示 label，无 label 显示“关联图示”或“图示”，`ref` 只用于内部定位和诊断。 |
| P2-10 | **Open（布局细节）** | NativeSmartUiCard / WidgetMessageContent | AX 显示原生卡片外框 `[70,444][1250,1678]`，紧接 SVG 卡片外框 `[70,1677][1250,2299]`，两者出现 1px 重叠/零间距。choice 内部热区高度约 154px、横向边距和 8vp 行间距正常；只需给原生卡片增加明确的底部 8vp 间距，再复测截图，避免改动整套布局。 |

## 当前已确认的边界

- `NativeSmartUiCard` 持有局部 `@State`，页面通过 `SmartUiStateAccessor` 持有跨重建值；没有新增 WebView、网络请求或自动发送路径。
- `ready` 仍由完整、闭合且通过协议校验的 UI 文档决定；资源节点的 SVG/Three 失败不会禁用合法 UI 的 draft 按钮。
- `input` 的 512 字符上限、choice 选项类型、`visibleWhen` 字面量比较和 draft 占位符替换均在域层有测试。
- `Column` 嵌套深度、六类节点分支、44vp 最小热区和卡片 16vp 内边距已有源码约束；本次模拟器 AX 证实 choice 行热区和内部间距正常，但暴露了原生卡片与 SVG 卡片的零间距、asset 技术 ID 和 choice AX 状态缺失。

## P2 收口条件

P2 仍需完成：asset 点击后的设备定位、交互 p95、choice 的 AX 选中状态、asset 人类可读标签和原生卡片与图形卡片的 8vp 间距。P2-07 的恢复竞态已具备 source/epoch/userChanged 防护，完整离开/重开回放转入 P3。设备证据应注明是 HarmonyOS 模拟器还是真机，AX 证据不能用源码正则替代。
