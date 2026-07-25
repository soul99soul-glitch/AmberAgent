All items verified. Here is the review.

## Review

### 条目10 — 确认成立 (P1)
- **UI 侧**：`ChatView.swift:1227-1234` `sendEnabled(for:)` 只要求 `!text.trimmingCharacters(...).isEmpty && !viewModel.isGenerationActive && ...`，完全不看 `viewModel.pendingImages`。
- **VM 侧**：`ChatViewModel.swift:588-592` `sendMessage()` 的守卫是 `guard (!text.isEmpty || hasImages), ..., !isRecognizingImages, ... else { return }` — VM 支持纯图片，且 OCR 进行中直接静默 `return`。
- **补充取证**：`isGenerationActive`（`ChatViewModel.swift:192-197`）= `generationCoordinator.isRunning || hasActiveBackgroundGenerationForCurrentConversation`，不含 `isRecognizingImages`，所以 OCR 期间 UI 按钮确实显示可发送，点击后被 VM 静默吞掉。两条子声明都成立。
- 修复方向合理：把 VM 的发送资格（含 `pendingImages` / `isRecognizingImages`）提取为 VM 上的单一计算属性供 UI 复用，消除双份判定。

### 条目11 — 确认成立 (P2)
- `ChatView.swift:465-477` `handlePhotoPickerSelection`：`Task { for item in items { ...loadTransferable... await MainActor.run { viewModel.addPendingImage(...) } } }`，无 conversationId 快照、无 request token、无 `Task.isCancelled` 检查，Task 句柄也未保存，切会话时不会取消。
- `ChatViewModel.swift:1232-1236` `addPendingImage` 直接 append 到 VM 全局 `pendingImages`；`discardSelectedFileContextForConversationChange()`（`ChatViewModel.swift:280-285`）只清 `pendingSelectedFilePreview`，不清 `pendingImages` — 加载窗口内切会话，图片会落进新会话的 composer。
- 影响比"串会话"略轻：图片只是出现在新会话输入区（不会自动发送），用户可删除；但确实是状态串线。修复方向合理：Task 开始时捕获 conversationId，回主线程前比对，或把 pending images 按会话归组。

### 条目12 — 确认成立 (P2)
- `ChatViewModel.swift:726-729`：成功路径 `guard shouldApplyVisionRecognitionResult(conversationId:userMessageId:) else { return }` — 静默丢弃，不写任何 pending 状态。
- 对比失败路径 `applyVisionRecognitionFailure`（`ChatViewModel.swift:755-766`）：非当前会话时会存 `pendingVisionFailures[...]` 供切回后 flush。**成功路径没有这个补偿**，原会话只剩用户图片、无回复/错误/重试入口。注意报告的"证据行 709"实际是 `startVisionFallbackAndSend` 入口；成功丢弃点在 726-729 行，结论不变。
- 修复方向合理：成功结果也可缓存到 `visionRecognitionTexts`（其实 730 行在 guard 之后才 cache——把 cache 挪到 guard 之前），切回时若有缓存识别文本可补触发或至少提供重试入口。

### 条目18 — 确认成立，且实测比报告更严重 (P1)
- 测试断言存在于当前代码：`ChatSwiftUIStreamReplayTests.swift:908-912`（paragraph max ≤40）、`:914-918`（content max ≤64）、`:919-923`（bottom debt ≤72）；24KB 版本在 `:1041-1045`。
- **我在当前工作区实测复跑 3 次全类（16 条）**：第 1 次 `testLongProseViewportFollowStaysLineSizedAtTwentyFourKB` 单跑失败（content growth 58.45>40，bottom debt **316.9pt>72**）；第 2 次全类 **3 条失败**（24KB debt **462.7pt**、`testContinuousProse` debt 154.2pt、`testStreamingFollowKeepsBottomWithoutBackjump` 回跳 108pt）；第 3 次全类仅 1 条失败。负载敏感抖动极大。
- 与 PROJECT_STATE 的关系：**不矛盾**。07-24 记录（`PROJECT_STATE.md:45`，893→6.7pt 通过门禁）只是单次幸运样本；同文件 `:54` 自己记录了同一测试后续两次隔离复跑仍报 ~893pt 失败并明确标注"负载敏感"。审计的 79.96pt 是同一已知 flaky 失败的新样本，量级随负载变化（79.96/154/317/463 都出现过）。
- 修复方向（把门禁改为负载归一化断言或修真实跟随逻辑）合理；当前状态是"门禁存在但默认路径不稳定通过"，不能算修复完成。

### 条目19 — 确认成立 (P3)
- `ChatMessageListSupport.swift:93-95`：注释自称"超长消息以 50 行为折叠上限"，实现是 `.lineLimit(50)`，无 `@State` 展开标记、无"展开全文"按钮。超过 50 行永久截断。
- 修复方向合理：加 expanded 状态 + 尾部渐变/"展开"按钮，是小改动。

### 条目20 — 确认成立 (P3)
- `ChatViewModel.swift:891-892` 首轮触发 `generateConversationTitle()`；`:947-962` Task 完成后 `await self.conversationStore?.renameConversation(id:conversationId, title:title)` 无条件执行。`IOSConversationStore.swift:490-501` `renameConversation` 是纯 partial update，无 baseline/CAS。标题 Task 在途期间用户手动改名会被覆盖。竞争窗口窄（仅首轮），但机制缺陷属实。
- 修复方向合理：Task 开始时读当前标题作为 baseline，应用前比对，或仅当标题仍是占位/自动值时才写。

### 条目21 — 确认成立 (P3)
- `ChatViewModel.swift:980-987`：应用守卫只有 `self.currentConversationId == conversationId && !self.isGenerationActive`，无 generation token — 同会话先发的慢任务会逆序覆盖后发任务的新建议。
- 输出 `.filter{!$0.isEmpty}.prefix(5)` 无去重；UI `ChatView.swift:1066` `ForEach(viewModel.chatSuggestions.prefix(4), id: \.self)` 以字符串自身为 identity，重复字符串会撞 identity。
- 修复方向合理：加单调递增 generation counter 只接受最新任务结果 + 输出去重，都是几行的改动。

### 条目22 — 确认成立 (P2 UX)
- 拒绝路径在 `ChatGenerationCoordinator.swift:779-797`：`hasPendingToolApproval`、前台 tool/image 任务在途、无 handoff/store、Grok web provider、transition 失败均 `return false`。
- 全部 5 个调用点静默吞掉：`PlaceholderViews.swift:818`、`:1004`、`:1401`、`ChatView.swift:758`、`AppShell.swift:239`，统一 `guard ... else { return }`，无任何提示。用户点会话/新建无响应且不知道为什么。
- 修复方向合理：把 Bool 换成带原因的 Result/枚举，UI 至少 toast 原因（如"先处理待确认的工具调用"）。

### 条目23 — 确认成立 (P2 性能）
- `project.yml:12` `SWIFT_VERSION: "6.1"` — Swift 6 语言模式下 SwiftUI `View` 协议为 `@MainActor`，`handlePhotoPickerSelection`（`ChatView.swift:465`）的 `Task {}` 继承 MainActor；`ChatImageEncoder.encode`（`ChatMiscViews.swift:525-532`）同步执行 `UIGraphicsImageRenderer` 位图绘制（`:542-544`）+ `jpegData` + `base64EncodedString`，全在主线程。`await MainActor.run` 的存在只是防御性的，实际整个 encode 已在主线程。
- 历史 data URL 解码：`MessageBubbleView.swift:2907-2912` `decodeDataURL`（base64 decode + `UIImage(data:)`）在 `.task(id:)` 中调用（`:2963-2967` 和 `:3251-3254`），`.task` 同样 MainActor 隔离。
- 修复方向合理：`ChatImageEncoder.encode` 声明为 `nonisolated` / 移到 `Task.detached`，data URL 解码放后台再回主线程赋 state。

### 总体评价
9 条全部在当前代码中核实成立，无过时条目。报告行号基本准确（条目12 的 709 略有偏移，实际丢弃点在 726-729）。严重度上条目10、18 是实质功能/门禁问题，其余多为边界竞争与 UX 静默失败，修复建议方向（单一发送资格源、token/baseline 防逆序、原因外化、移出 MainActor、门禁负载归一化）均合理且符合最小改动原则。