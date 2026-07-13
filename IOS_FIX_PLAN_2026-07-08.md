# AmberAgent iOS 综合修复计划（2026-07-08）

> 本计划综合了 2026-07-06 ~ 07-08 四轮审查的全部坐实发现：
> 第一轮（UI/滚动/性能）、第二轮（持久化/并发/后台）、第三轮（逻辑/链路/工具/provider）、
> 第四轮（对 GPT 修复批次的对抗性复审）。
> 执行者按阶段顺序推进：**R（本轮回归）→ A（存量 P0）→ B（存量 P1）→ C（结构性）**。
> 每一项先写红测试（能复现问题的失败测试），再修，再验证红→绿。

## 0. 硬性原则（违反任何一条即停手）

1. 禁止 `git commit / push / stash / reset / checkout`。只在工作区修改。
2. 禁止回到已被真机否证的 scrollTo(y:) / contentOffset 几何补偿路线来"掩盖"滚动异常；
   几何纠偏最多只能作为降级前的一次性尝试，且必须保留降级出口。
3. 屏幕内动画只能升级不能降级。任何以性能为由砍掉可见动画的改动都需要用户显式拍板。
4. 修 bug 先用测试复现（红），修完跑同一测试（绿）。回放/单测绿 ≠ 真机安全，
   涉及滚动/流式渲染的改动必须在"真机验证清单"（§6）中登记。
5. 只改与该项直接相关的代码，不顺手重构、不动不理解的逻辑；发现死代码只标记不删除。
6. 每完成一项，在本文档对应条目打勾 `[x]` 并在行尾追加改动文件清单。

---

## 阶段 R：先修本轮（GPT 批次）引入的回归 —— 最高优先级

### [x] R1. 流式表格半截行"泄漏"为裸 pipe 文本（P0，必现） —— 改动文件：`iosApp/iosApp/MessageBubbleView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
- **文件**：`iosApp/iosApp/MessageBubbleView.swift`
- **问题 1（off-by-one）**：`parseTable`(:806-844) 在流式态执行 `rows.removeLast()`(:835)
  裁掉半截尾行，但调用方 `parseBlocks`(:794) 用 `index += table.rows.count + 2` 推进游标，
  该 count 是裁剪**后**的值，游标正好停在被裁掉的半截行上 → 下一轮循环把它 flush 成
  表格下方独立文本块，裸 pipe 字符随流式逐字符增长。这正是本想修掉的"过线/错位"回归。
- **问题 2（单行表格）**：header+delimiter+仅 1 行半截数据时 `removeLast()` 后 rows 为空，
  `guard !rows.isEmpty`(:838) 让整个 parseTable 返回 nil，保护机制在表格起始阶段完全失效。
- **修法**：让 parseTable 返回"实际消费的原始行数"（扫描循环停止位置减 start），
  parseBlocks 按该真实值推进 index，与是否裁剪无关；rows 裁空时仍返回
  只有 header+delimiter 的表格（rows 允许为空），而不是 nil。
- **红测试**：新增单测——输入 `header/delimiter/row1/半截row2(无\n)`，断言
  blocks 输出中**不存在**含 `|` 的 text block，且表格 rows==[row1]；
  输入单行半截表格，断言输出为 rows 为空的 table block 而非 text。
- **已加测试**：`testStreamingTableParserDoesNotLeakTrimmedTrailingRowAsPipeText`
  断言半截尾行不泄漏为含 `|` 的 text block，表格 rows 只保留完整行；
  `testStreamingTableParserKeepsSinglePartialRowAsEmptyTableBlock`
  断言单行半截表格仍输出 table block 且 rows 为空。
- **验证**：真机流式生成含表格回复，录屏确认尾行无裸文本闪现。

### [x] R2. renderable 缓存跨结构污染（P0）—— 改动文件：`iosApp/iosApp/MessageBubbleView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
- **文件**：`iosApp/iosApp/MessageBubbleView.swift`
- **问题**：`parseNow`(:1158) 用 `speculativeRewrite: config.shouldAnimateText` 解析——
  该选项会**结构性改写 AST**（半截表格/强调降级为段落）；而缓存键
  `visualConfigHash`(:1210-1212) 剥离了 shouldAnimateText 位，存(:1199)取(:1182)共用。
  结果：流式态（speculative=true）产出的"降级段落" renderable 会被完成态
  （speculative=false 语义）命中复用，完成态首帧显示错误结构，随后 scheduleParse
  重解析替换 → "完成态跳变"没根除，只是换成了更窄触发条件的变种，
  且与未修的 PartialTableScanner 过匹配 bug 耦合。
- **附加问题**：`cachedRenderable`(:1186-1191) 的 prefix 匹配兜底
  （`text.hasPrefix(key.text)` 就拿旧短文本 renderable 顶上）在完成态会先显示
  缺尾巴的旧内容再替换，放大跳变。
- **修法**：`RenderableCacheKey` 增加 `speculative: Bool` 字段（直接取
  `config.shouldAnimateText`），流式/完成态各自命中各自的结构；
  prefix 兜底仅在流式态（shouldAnimateText==true）允许，完成态只允许精确命中。
- **红测试**：构造尾部命中 PartialTableScanner 模式的文本（如以 `|` 开头的单行结尾），
  以 animate=true 解析并入缓存，再以 animate=false 查询，断言 miss（修前会 hit）。
- **已加测试**：`testCompletedMarkdownRenderableCacheDoesNotReuseSpeculativeStreamingEntry`
  断言完成态不会复用流式 speculative renderable；`testCompletedMarkdownRenderableCacheDoesNotUsePrefixFallback`
  断言完成态不会使用流式 prefix 兜底，流式态仍保留 prefix 兜底。

### [x] R3. system 合并破坏 compact 摘要收缩（P1）—— 改动文件：`iosApp/iosApp/ChatContextSupport.swift`、`iosApp/iosApp/ChatGenerationCoordinator.swift`、`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosApp/IOSContextCompactionCoordinator.swift`、`iosApp/iosAppTests/IOSSkillInjectionAndIOErrorTests.swift`
- **文件**：`iosApp/iosApp/ChatContextSupport.swift`、`iosApp/iosApp/ChatGenerationCoordinator.swift`、`iosApp/iosApp/IOSContextCompactionCoordinator.swift`
- **问题**：`coalescingSystemMessages` 在 `messagesByInjectingRuntimeContext`
  （ChatGenerationCoordinator.swift:572）内执行，把 compact handoff 摘要 system 消息
  揉进合并大 system；随后 `finalizedMessagesForRequest`(:575) →
  `isCompactHandoffSystemMessage`（IOSContextCompactionCoordinator.swift:818-822）靠
  `text.hasPrefix("[Conversation compact handoff:")` 识别摘要做超预算收缩——合并后
  前缀必然失配，收缩兜底失效，超预算直接抛"上下文压缩后仍超过模型窗口预算"。
- **修法**（二选一，推荐前者）：
  a) 把合并挪到 `finalizedMessagesForRequest` **之后**、dispatch 之前执行；
  b) `isCompactHandoffSystemMessage`/`trimmingCompactHandoffSystemMessages` 改为
     在合并文本内按子串定位收缩。
- **红测试**：构造"runtime 注入 + compact 摘要 + 超预算"消息组，断言收缩逻辑仍能
  找到并裁剪摘要段（修前抛错）。
- **已加测试**：`testCompactHandoffIsTrimmedBeforeRuntimeSystemMessagesAreCoalesced`
  断言 runtime system 注入后先裁剪 compact handoff，最后再合并为单条 system。

### [x] R4. 滚动驱动删除 horizontalOffsetDrift fallback（方向性回滚，P1）—— 改动文件：`iosApp/iosApp/NativeTimelineScrollCore.swift`、`iosApp/iosApp/NativeTimelineScrollDriver.swift`、`iosApp/iosAppTests/NativeTimelineScrollCoreTests.swift`
- **文件**：`iosApp/iosApp/NativeTimelineScrollCore.swift:9`、`iosApp/iosApp/NativeTimelineScrollDriver.swift:346-352`、`iosApp/iosAppTests/NativeTimelineScrollCoreTests.swift`
- **问题**：本轮**未申报**地删除了 `horizontalOffsetDrift` fallback 枚举，把
  "横向漂移 → 降级到已验证安全的 SwiftUI 路径"改成每帧静默 `contentOffset.x = 0`
  并永远返回健康；测试同步改名（FallsBack→Clamps）适配。这是用几何纠偏掩盖
  真机验证过的失败模式，根因（漂移来源）未消除，只有 mock 单测背书。
- **修法**：恢复 `horizontalOffsetDrift` 枚举与 fallback 上报路径。可保留一次
  clamp 作为降级前的宽限：首次漂移 clamp 并计数，同一 attach 生命周期内再次
  超阈值 → 上报 fallback 降级。测试恢复 `testDriverFallsBackOnHorizontalOffsetDrift`
  并新增"首次 clamp、二次 fallback"用例。
- **已加测试**：`testDriverFallsBackOnRepeatedHorizontalOffsetDrift`
  断言同一 attach 生命周期内首次横向漂移只 clamp，第二次漂移上报 `.horizontalOffsetDrift`
  并 detach fallback；`testDriverClampsHorizontalOffsetDriftWithoutFallingBack` 覆盖首次宽限。
- **验证**：真机横向拖拽、键盘联动、含横向滚动表格的消息三个场景录屏。

### [x] R5. vendor 表格动画降级违反铁律（P1，需用户拍板）—— 改动文件：`iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/TableView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
- **文件**：`iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/TableView.swift:155-159`
- **问题**：新增 `shouldAnimateTableText` 把"单元格数>16 或文本>48 字符"的单元格
  fade-in 动画砍成静态跳变——屏幕内动画降级，违反"只能升级"铁律；16/48 魔数
  无注释、无测试、无配置。
- **修法**：默认**回退**该改动，恢复所有单元格 fade-in。若流式大表格确有卡顿实measure，
  向用户提交数据后再决定降级策略（例如只对"新追加的行"做动画、老单元格不重放动画——
  这是升级不是降级）。
- **已加测试**：`testTableCellFadeInPolicyDoesNotDisableVisibleAnimationForLargeTables`
  断言大表格/长单元格在 `shouldAnimateText == true` 时仍保留 fade-in 动画。
- **验证**：真机流式生成 20+ 行表格，确认动画完整且无掉帧（Instruments 采样）。

### [x] R6. cancel 快照的同会话覆盖窄窗口（P1，并入 A7 一起修）—— 改动文件：`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/IOSConversationStoreTests.swift`
- **文件**：`iosApp/iosApp/ChatGenerationCoordinator.swift:311-350`、`iosApp/iosApp/IOSConversationStore.swift:182-215`
- **问题**：cancel 修复冻结了取消瞬间快照（跨会话覆盖已修对），但若用户 cancel 后
  在**同一会话**立即发新消息并先落盘，延迟任务会用旧快照覆盖新消息。
  根因是 `save()` 无基线校验（last-writer-wins），快照修复只治"写错内容"没治"并发谁赢"。
- **修法**：见 A7（写入序列号/基线校验）。短期可在延迟持久化前比对 store 中该会话的
  写入序列号是否已推进，推进则放弃本次快照写入。
- **红测试**：cancel → 同会话 append 新消息并落盘 → 触发延迟任务 → 断言新消息仍在。
  - 已加测试：`testStaleSnapshotWriteBaselineDoesNotOverwriteNewerForegroundSave`，序列号推进后延迟快照写入必须返回 false，且前台新消息仍在。
  - 验证：测试先红（缺少 `writeBaseline` / baseline save API）后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/IOSConversationStoreTests/testStaleSnapshotWriteBaselineDoesNotOverwriteNewerForegroundSave test` 通过。

---

## 阶段 A：存量 P0（数据完整性 / 安全）

### [x] A1. 存储错误三层失效——底层与中层（P0，本轮只修了最上层）—— 改动文件：`core/conversation-storage/src/iosMain/kotlin/app/amber/core/storage/conversation/ConversationFile.ios.kt`、`core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/ConversationStorageInterface.kt`、`core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt`、`core/conversation-storage/src/iosSimulatorArm64Test/kotlin/app/amber/core/storage/conversation/ConversationFileIosTest.kt`、`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosAppTests/IOSSkillInjectionAndIOErrorTests.swift`
- **文件**：`core/conversation-storage/src/iosMain/kotlin/.../ConversationFile.ios.kt`、`core/conversation-storage/src/commonMain/kotlin/.../JsonConversationStorage.kt`、`ConversationStorageInterface.kt`、`iosApp/iosApp/IOSConversationStore.swift`
- **问题**：`writeText` 把 `writeToFile` 的 Bool 返回值丢弃、`error` 传 null——磁盘写
  失败完全检测不到；存储接口全部 suspend 方法缺 `@Throws`（encodeToString 异常直接
  SIGABRT 崩进程，Provider 层有标注、存储层漏了）；`delete()` 返回值同样被丢弃。
  已修的 `lastIOError` 不再被 refreshSummaries 误清（保留），但对"写失败"场景它
  永远不会被设置——收益为零，必须补齐底层。
- **修法**：
  1. `writeText` 接收 error 出参并读取返回 Bool，失败时抛 Kotlin 异常（含 NSError 描述）；
     `delete` 同理。
  2. 存储接口所有 suspend 方法加 `@Throws(Throwable::class)`（对照 KMP Provider 层写法）。
  3. `JsonConversationStorage` 内 `encodeToString`/`decodeFromString` 包 runCatching，
     包装为带上下文的存储异常向上抛。
  4. 同步更新 `IOSConversationStore.swift:91-92` 的过时注释
     （"成功的 refreshSummaries 会清空它"已不成立）。
- **红测试**：Kotlin 单测——mock/注入不可写路径，断言 saveConversation 抛异常；
  Swift 单测——存储抛错时 `lastIOError` 被设置且 refreshSummaries 成功后仍保留。
  - 已加测试：`writeTextToDirectoryThrows`，iOS actual 写目录必须抛 `IllegalStateException`。
  - 已加测试：`testIOErrorIsSetWhenSaveFails`，bad base path bootstrap/save 失败必须设置 `lastIOError`，手动 clear 后才清空。
  - 验证：`JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :core:conversation-storage:iosSimulatorArm64Test --no-daemon` 通过；`xcodebuild ... -only-testing:iosAppTests/IOSSkillInjectionAndIOErrorTests/testIOErrorIsSetWhenSaveFails test` 通过。

### [x] A2. 删除会话无门控 + 复活（P0，三轮未修）—— 改动文件：`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift`、`iosApp/iosApp/PlaceholderViews.swift`、`iosApp/iosAppTests/IOSConversationStoreTests.swift`
- **文件**：`iosApp/iosApp/PlaceholderViews.swift:850-862`、`iosApp/iosApp/IOSConversationStore.swift:313-328`（save :182-215）、`ConversationStorageView.swift` 删除入口
- **问题**：删除会话不检查前台/后台生成、不取消在途后台任务；`save()` 在
  loadConversation 与 persist 两个 await 之间被删除穿插时把 `{id}.json`+index 重新写出
  （复活）。
- **修法**：
  1. 删除入口先查 `isGenerationActive(conversationId:)`（含后台），活跃则取消前台生成、
     调后台协调器取消该会话 job，再删除。
  2. store 维护"已删除 id 墓碑集合"（会话生命周期内即可），`save`/`saveBackgroundCompletion`
     等写入口在 persist 前检查墓碑，命中则丢弃写入。
- **红测试**：删除后立刻触发延迟持久化/后台完成写入，断言文件与 index 均不复活。
  - 已加测试：`testDeleteTombstonePreventsInFlightForegroundSaveFromResurrectingConversation`，前台 save 卡在 persist 前窗口时删除同会话，恢复后不得复活文件。
  - 已加测试：`testDeleteTombstonePreventsInFlightBackgroundCompletionFromResurrectingConversation`，后台 completion 卡在 persist 前窗口时删除同会话，恢复后不得复活文件。
  - 验证：上述两个测试先红后绿，修复后 `xcodebuild ... -only-testing:iosAppTests/IOSConversationStoreTests/testDeleteTombstonePreventsInFlightForegroundSaveFromResurrectingConversation -only-testing:iosAppTests/IOSConversationStoreTests/testDeleteTombstonePreventsInFlightBackgroundCompletionFromResurrectingConversation test` 通过。

### [x] A3. 生成中判据不统一——双生成口子（P0）—— 改动文件：`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
- **文件**：`iosApp/iosApp/ChatViewModel.swift:478（sendMessage）、:516（modifyGeneratedImage）、:1257（generateResponse）`
- **问题**：UI 层与 regenerate/edit/delete/selectVariant 已改用 `isGenerationActive`
  （含后台 job），但上述三个 ViewModel 方法守卫仍是裸 `generationCoordinator.isRunning`——
  任何绕过 UI 网关的调用路径都会同会话双生成，前台无基线覆盖后台落盘回复。
- **修法**：三处统一改为 `isGenerationActive(conversationId:)` 判据；
  `generateResponse` 内部"要不要先 cancel"的判断同步覆盖后台 job（后台活跃时
  要么拒绝要么先取消后台 job，语义与 sendMessage 一致）。
- **红测试**：mock 后台 job 活跃，直接调 sendMessage/generateResponse，断言被拒绝。
  - 已加测试：`testSendMessageIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation`，后台活跃时 `sendMessage` 不得追加用户消息、不清空输入。
  - 已加测试：`testGenerateResponseIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation`，后台活跃时 `generateResponse` 不得进入 provider 配置/启动生成。
  - 验证：上述测试先红后绿，修复后 `xcodebuild ... -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests/testSendMessageIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests/testGenerateResponseIsRejectedWhenBackgroundGenerationIsActiveForCurrentConversation test` 通过。

### [x] A4. 多工具并行调用空结果污染（P0）—— 改动文件：`ai-provider-claude/src/commonMain/kotlin/app/amber/ai/provider/claude/ClaudeKmpProvider.kt`、`ai-provider-claude/src/jvmTest/kotlin/app/amber/ai/provider/claude/ClaudeKmpProviderMessageTest.kt`、`ai-provider-openai/src/commonMain/kotlin/app/amber/ai/provider/openai/OpenAIKmpProvider.kt`、`ai-provider-openai/src/commonTest/kotlin/app/amber/ai/provider/openai/OpenAIKmpProviderRequestTest.kt`
- **文件**：`iosApp/iosApp/ChatToolRuntime.swift:237-261（nextPendingToolCall）`、`iosApp/iosApp/ChatGenerationCoordinator.swift:905-976（executeToolCall）`、`ai-provider-claude/.../ClaudeKmpProvider.kt`、`ai-provider-openai/.../OpenAIKmpProvider.kt`
- **问题**：每轮只执行一个 pending tool，同轮其余 tool call 带空 output 随 resume 请求
  序列化为空 tool_result 静默喂给模型 → 模型基于假空结果生成整轮已展示的回复。
- **修法**（短期+长期两步，本计划先做短期）：
  1. **短期**：请求侧禁用并行工具调用——Claude 请求加
     `tool_choice: {"disable_parallel_tool_use": true}`；OpenAI 加
     `parallel_tool_calls: false`（两个 KMP provider 各自的请求构造处）。
  2. **长期（阶段 C 排期）**：`nextPendingToolCalls(in:)` 返回同一 assistant 消息内
     全部 pending，批量执行/批量审批后一次 resume。
- **红测试**：KMP 层请求序列化单测，断言两 provider 请求体含禁并行字段。
  - 已加测试：`tools disable parallel tool use`，Claude tools 请求必须写入 `tool_choice.disable_parallel_tool_use=true`。
  - 已加测试：`chatCompletionsToolsDisableParallelToolCalls`，OpenAI Chat Completions tools 请求必须写入 `parallel_tool_calls=false`。
  - 已加测试：`responsesToolsDisableParallelToolCalls`，OpenAI Responses tools 请求必须写入 `parallel_tool_calls=false`。
  - 验证：上述测试先红后绿，修复后 `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :ai-provider-claude:jvmTest :ai-provider-openai:allTests --no-daemon` 通过。

### [x] A5. 门控关闭的工具静默 completed（P0，本轮修复未覆盖的陷阱）—— 改动文件：`iosApp/iosApp/ChatToolRuntime.swift`、`iosApp/iosApp/ChatGenerationCoordinator.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
- **文件**：`iosApp/iosApp/ChatToolRuntime.swift`、`iosApp/iosApp/ChatGenerationCoordinator.swift:839-881`
- **问题**：`handleCompletedStream` 用 `nextPendingToolCall` 判"是否存在 pending"，
  而该函数受 enableWebSearch / localToolExecutor / memory 开关门控——门控关闭时
  pending 工具"不可见"，直接落入 completed 分支，空 output tool part 静默落盘
  （与 R 阶段已修的预算耗尽路径同病不同枝）。
- **修法**：新增**不带门控**的判据 `hasUnresolvedToolCall(in:)`（存在 output 为空的
  tool part 即真）。handleCompletedStream 改为：`hasUnresolvedToolCall` 为真但
  `nextPendingToolCall` 为 nil（即全部不可执行）时，走与预算耗尽同构的收尾——
  用 `messagesByFailingPendingToolCalls` 填"该工具当前未启用"失败输出后 failed 收尾。
- **红测试**：构造 enableWebSearch=false + 含 search tool call 的完成流，
  断言 tool part 被填失败输出而非空 output 落盘。
  - 已加测试：`testFailingPendingToolCallsSeesSearchToolWhenSearchGateDisabled`，搜索门控关闭时仍要发现未解决 search tool 并填失败输出。
  - 回归测试：`testFailingPendingToolCallsFillsAllUnfinishedOutputs`，预算耗尽/多 tool 填充语义仍保持。
  - 验证：上述测试先红后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/ChatViewModelSelectedFileContextTests/... test` 通过。

### [x] A6. 链接 scheme 白名单未覆盖主力渲染路径（P0 安全）—— 改动文件：`iosApp/iosApp/MessageBubbleView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
- **文件**：`iosApp/iosApp/MessageBubbleView.swift`（ChatStableStreamingMarkdownView :1030-1048 等 vendor 渲染入口）、vendor `Markdown+InlineConvertible.swift`
- **问题**：本轮白名单只加在 citation Link 和 `AmberMarkdownView`（从未流式过的
  历史消息 fallback），而几乎所有实时流式消息走 vendor `DocumentView`
  （`shouldUseFadeStreamingRenderer` 恒真于流式过的消息），该路径 `container[.link] = url`
  无任何校验，应用侧也无 `\.openURL` 覆盖——模型诱导打开任意 scheme 的 P1 在
  主力路径上原样存在。
- **修法**：在聊天气泡渲染层统一挂
  `.environment(\.openURL, OpenURLAction { url in ... })`——一处拦截对所有渲染器
  （vendor/host/未来新增）生效，白名单 http/https；`mailto:` 是否放行由用户定夺
  （默认放行 mailto 但不放行其余，模型输出邮箱链接是常见合法场景）。
  同时可移除两处分散的白名单判断或保留作纵深。
- **红测试**：UI 逻辑单测困难，至少加 OpenURLAction 处理函数的纯函数单测
  （输入各 scheme 断言 allow/deny）；真机验证点击 `shortcuts://` 链接无反应。
  - 已加测试：`testChatMarkdownOpenURLPolicyAllowsOnlyWebAndMailtoSchemes`，允许 `http/https/mailto`，拒绝 `shortcuts/file/javascript`。
  - 验证：测试先红后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/ChatMessageProjectionTests/testChatMarkdownOpenURLPolicyAllowsOnlyWebAndMailtoSchemes test` 通过。

### [x] A7. 写盘基线校验——持久化并发的最小防线（P0 根因，最小版）—— 改动文件：`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/IOSConversationStoreTests.swift`
- **文件**：`iosApp/iosApp/IOSConversationStore.swift`
- **问题**：`save(messages:to:)` 是 last-writer-wins 无条件覆盖，第二轮三个 P0 与
  R6 的共同根因。完整"单一事实来源"是 C4，这里先做最小防线。
- **修法**：store 为每个会话维护单调递增写入序列号（内存即可）。
  所有"延迟/异步快照写入"（cancel 延迟任务、后台完成回写）在捕获快照时记录序列号，
  执行写入前比对：序列号已推进则丢弃本次写入并记日志+设置可见提示。
  前台即时写入（用户操作直接触发）不受影响。
- **红测试**：R6 的测试 + 后台完成 vs 前台新消息竞态测试（断言后写的前台内容不被
  旧快照覆盖、后台完成不被无脑丢弃——序列号未推进时正常写入）。
  - 已加测试：`testStaleSnapshotWriteBaselineDoesNotOverwriteNewerForegroundSave`，覆盖 R6 的同会话过期快照覆盖窗口。
  - 已加测试：`testBackgroundCompletionWriteBaselineDoesNotOverwriteForegroundMessage`，后台完成进入延迟窗口后，前台新消息推进序列号，后台旧快照被拒绝且不产生后台内容通知。
  - 回归测试：`testBackgroundCompletionBumpsDirectedSignalWithoutTouchingSwitchRevision`，序列号未推进时后台完成仍正常落盘并发通知。
  - 验证：新增测试先红后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/IOSConversationStoreTests/... test` 通过。

---

## 阶段 B：存量 P1

### [x] B1. Native fallback 单向棘轮 —— 改动文件：`iosApp/iosApp/ChatCollectionMessageList.swift`、`iosApp/iosApp/ChatView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
`ChatCollectionMessageList.swift:981`（唯一赋值点）无置 nil 路径；`NativeChatTimelineView`
无会话级 `.id()`，@State 跨会话残留。**修法**：给 NativeChatTimelineView 加
`.id(conversationId)` 隔离会话状态，或在 conversationLoaded/Switched 时显式
`nativeScrollFallbackReason = nil` 并重新 attach driver。红测试：模拟 fallback 后切会话，
断言新会话 driver 重新激活。
  - 已加测试：`testNativeTimelineSessionIdentityChangesAcrossConversations`，会话 id 必须进入 native timeline view identity。
  - 验证：测试先红（缺少 `NativeChatTimelineSessionIdentity`）后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/ChatMessageProjectionTests/testNativeTimelineSessionIdentityChangesAcrossConversations test` 通过。

### [x] B2. Native viewport 策略违约的根因修复 —— 改动文件：`iosApp/iosApp/ChatCollectionMessageList.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
`NativeStaticTimelineViewportPolicy`（ChatCollectionMessageList.swift:236）
`followPaused = showScrollToBottom` 违反"只有用户拖拽才 pause"契约；本轮的
`normalizedNativeViewportState`(:756 起) 是症状补丁且 `followGeneration=false` 时
主动保留违约行为。**修法**：改 policy 本体——followPaused 只由用户拖拽/交互状态驱动；
然后删除 normalizedNativeViewportState 归一化层（症状层随根因一起撤）。
红测试：followGeneration=false + 生成中 + 未拖拽，断言 followPaused==false。
  - 已更新测试：`testNativeStaticTimelineViewportPolicyPublishesBottomButtonAndLODState`，有底部按钮但未交互时 `followPaused=false`，用户交互离底时才为 true。
  - 验证：测试先红（缺少 `userInteracting` 参数/旧语义）后绿，修复后对应 `xcodebuild ... -only-testing:iosAppTests/ChatMessageProjectionTests/testNativeStaticTimelineViewportPolicyPublishesBottomButtonAndLODState test` 通过。

### B3. 历史行 LaTeX 不渲染（已拆分为 B3a/B3b，2026-07-08 Fable5 复审裁决）
> 原文案"补两个分支"不准确：对 mathBlock 成立；对 mathInline 不成立——vendor 的
> inline math 走 TextKit2 `NSTextAttachmentViewProvider`(LatexViewProvider→MTMathUILabel)，
> 无法进 `AmberMarkdownView` 的纯 `AttributedString → Text` 管线。
> 另一关键事实：`renderBlock`/`renderInlineAttr` 的 default 分支均 `sliceSource` 回退，
> **当前行为是显示 LaTeX 字面文本而非空白**——B3b 暂缓不构成视觉降级。

### [x] B3a. 历史行块级公式（mathBlock）接线 —— 本轮执行 —— 改动文件：`iosApp/iosApp/MarkdownView.swift`、`iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/BlockMathView.swift`、`iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/UI/BlockView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
- **修法**：
  1. vendor `BlockMathView`（`.../UI/BlockMathView.swift:9`）为 internal——把 struct 与
     init 加 `public`（仅可见性，不改逻辑）。
  2. `MarkdownView.swift` 新增纯函数 `AmberMarkdownMath.blockLatex(from:source:)`：
     从 mathBlock 节点 sliceSource 提取 latex，剥离 `$$` 定界符与首尾空白
     （先用临时 print/单测确认 Rust 节点 offset 是否含定界符，按实测写剥离逻辑）。
  3. `renderBlock` 加 `case .mathBlock:`：提取 latex 非空 → `BlockMathView(latex:color:pointSize:)`，
     color/pointSize 取该渲染器现用主题前景色与正文字号；
     `frame(maxWidth: .infinity, alignment: .leading)`；latex 为空 → 保持现状回退。
     不改任何其他分支、不动段落渲染模型。
- **红测试**（不依赖 UI 截图）：
  a) PackedAstReader 解析 `"$$E=mc^2$$"` 产出 `.mathBlock` 节点；
  b) `blockLatex(from:)` 返回 `"E=mc^2"`（先红：函数不存在）；
  c) 多行公式与前后空白剥离用例。
- **验证**：真机历史行显示 `$$...$$` 消息，块级公式渲染且 dark/light 主题色正确；
  同一消息流式态（vendor 渲染）与历史态（本渲染）视觉一致性目测。
  - 红测试：`testBlockMathLatexExtraction` 先因 `AmberMarkdownMath` 不存在编译红。
  - 绿测试：`testPackedAstReaderParsesBlockMathNode`、`testBlockMathLatexExtraction`、
    `testKnownGap_B3b_inlineMathRendersAsLiteralText` 通过。实测 `mathBlock` 节点 offset
    包含 `$$` 定界符，因此提取函数剥离定界符与首尾空白。
  - 备注：`BlockMathView` 公开后 Swift 要求 `UIViewRepresentable` 方法同步 public，且 public
    init 不能引用 internal 默认参数；因此 vendor 内部调用显式补 `pointSize`，逻辑不变。

### [ ] B3b. 历史行行内公式（mathInline）—— BLOCKED-DESIGN，本轮跳过，勿硬做
- **裁决**：不允许为此改 `AmberMarkdownView` 段落渲染模型（Text→UIView 会波及
  表格/引用/列表/测高/选择/主题，违反"不引入大范围重构"）。
- **推荐设计（另行排期）**：`Text(Image)` 插值路线——mathInline 片段用 MTMathUILabel
  离屏渲染成 UIImage（cache key: latex+colorHex+pointSize+scale），段落 Text 改为
  `Text(attr片段) + Text(Image(uiImage:)).baselineOffset(≈(capHeight-图高)/2) + ...` 拼接；
  仅含 mathInline 的段落走拼接分支，其余段落零改动；主题切换经 body 重算自然重建。
  备选（次优）：仅对含 mathInline 的段落整段换 app-local UIViewRepresentable——
  测高与 cell 复用风险高（见记忆 hosting-cell 陷阱），须先真机验证再采纳。
- **防遗忘 canary 测试（本轮必须加）**：
  `testKnownGap_B3b_inlineMathRendersAsLiteralText`——断言当前 mathInline 渲染为
  字面文本，注释注明"B3b 实现时本测试应转红并被替换"。
- **计划状态**：B4-B18 不被 B3b 阻塞，继续推进。

### [x] B4. useResponseApi 门禁滞后 —— 改动文件：`iosApp/iosApp/ChatProviderConfiguration.swift`、`iosApp/iosApp/ProviderRegistryStore.swift`、`iosApp/iosApp/ProvidersView.swift`、`iosApp/iosAppTests/ProviderRegistryStoreTests.swift`
执行器已实现，门禁只白名单 Codex；内置 xAI 预设（useResponseApi=true）配完即死路。
**修法**：门禁改为按 provider 能力/配置判断而非硬编码白名单；错误文案改为指向真实原因。
  - 红测试：`testResponseApiOpenAIProviderIsSupportedAndActivatable` 先失败，确认
    `ChatProviderConfiguration.supportsChatStreaming`、`ProviderRegistryStore.canActivate`、
    `ProviderRouteKind.isEditablePreset` 三处仍把 Response API OpenAI provider 排除。
  - 绿测试：同一测试通过；OpenAI sealed provider 交给 KMP OpenAI 执行器按配置路由，
    只保留 MiMo placeholder 的禁用。

### [x] B5. MCP SSE 无响应超时 —— 改动文件：`iosApp/iosApp/IOSMcpClient.swift`、`iosApp/iosAppTests/IOSMcpClientTests.swift`
心跳使 30s 网络超时永不触发，慢服务器可无限悬挂。**修法**：MCP 请求加应用层
整体超时（如 120s 无终态即失败），失败走工具失败输出路径（配合 A5 的失败填充）。
  - 红测试：`testCallToolTimesOutWhenTransportNeverProducesTerminalResponse` 先失败，确认
    `IOSMcpClient` 没有请求级应用层超时，`tools/call` 可被永不返回终态的 transport 挂住。
  - 绿测试：同一测试通过；`IOSMcpClientTests` 全类通过。实现为 JSON-RPC 请求/通知统一
    120s 默认超时（测试可注入 0.01s），超时抛 `requestTimedOut`，并取消未完成 operation。

### [x] B6. contextCompactState 永久卡"压缩中" —— 改动文件：`iosApp/iosApp/IOSContextCompactionCoordinator.swift`、`iosApp/iosApp/ChatGenerationCoordinator.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
终态事件用请求级 runId 路由，后台预压缩任务跨请求存活 → 事件被 guard 丢弃，
composer 图标永久转圈。**修法**：压缩状态改为任务生命周期级路由（以压缩任务自身 id
为键），或任务终态时无条件复位该会话的 compactState。红测试：请求先于压缩结束，
断言 compactState 复位。
  - 红测试：`testStaleCompactTerminalEventIsAppliedAfterRunFinishes` 先失败，确认没有独立
    路由语义，后台 precompact 的终态事件会被请求 runId guard 吃掉。
  - 绿测试：同一测试通过。新增 `ChatContextCompactEventRouter`：当前 run 事件全部放行；
    run 已结束且没有新 run 时，只放行 `.idle/.completed/.failed` 终态，拒绝过期
    `.planning/.compacting`；若已有新 run，过期终态不覆盖新请求状态。

### [x] B7. 工具卡片假成功 —— 改动文件：`iosApp/iosApp/ChatToolSupport.swift`、`iosApp/iosApp/ChatToolTimelineView.swift`、`iosApp/iosAppTests/ChatMessageProjectionTests.swift`
状态判定 `output.isEmpty ? .active : .done`，失败/拒绝产出的非空 `{"ok":false}` JSON
全显绿勾。**修法**：复用 generate_image 分支已有的失败解析逻辑到全部工具卡片分支，
失败态显示失败样式。红测试：拒绝审批后断言卡片状态为 failed。
  - 红测试：`testToolStepModelMarksStructuredFailureOutputAsFailed` 先失败，确认
    `search_web` 的 `{"ok":false,"denied":true}` 非空输出被旧逻辑判成 `.done`。
  - 绿测试：同一测试通过。新增通用 `ChatToolOutputFormatter.failureReason(from:)`，
    解析 `ok=false`、`denied=true`、失败/超时 status、非零 exit_code；除生图/iSH 已有
    特化分支外，搜索、scrape、memory、MCP、模型议会、WebMount、Workspace 与兜底工具
    都用该结果决定 `.failed/.done/.active`。

### [x] B8. 编辑图文消息静默丢图 —— 改动文件：`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
编辑链路只取 `message.toText()`。**修法**：编辑提交时保留原消息非文本 part
（图片/文档），只替换 text part；或编辑 UI 明示"将移除附件"。红测试：编辑含图消息，
断言新 variant 仍含 image part。
  - 红测试：`testEditingUserMessagePreservesImageParts` 先失败，确认编辑图文用户消息时
    只有文本被重建，image part 会丢失。
  - 绿测试：同一测试通过。新增 `editedUserMessage(original:newText:)`，只替换首个文本
    part、移除重复文本 part、保留图片/文档等非文本 part；编辑路径从当前消息或当前 node
    variant 取原消息，避免 fallback 再走 `toText()`。

### [x] B9. 空会话垃圾 —— 改动文件：`iosApp/iosApp/IOSConversationStore.swift`、`iosApp/iosApp/ChatView.swift`、`iosApp/iosApp/PlaceholderViews.swift`、`iosApp/iosAppTests/IOSConversationStoreTests.swift`
两处"新建对话"入口无空会话复用。**修法**：新建前检查当前/最近会话是否为空
（无用户消息），为空则复用不落盘新文件。
  - 红测试：`testStartNewConversationReusesCurrentEmptyConversation` 与
    `testStartNewConversationReusesMostRecentEmptyConversation` 先失败，确认没有用户入口级的
    空会话复用 API。
  - 绿测试：同组测试通过。新增 `startNewConversationReusingEmpty()`，当前会话无用户消息时
    原地复用，最近会话无用户消息时切回该会话，否则才调用底层强制 `newConversation()`；
    Chat 顶栏与首页浮动新建入口均改接该智能入口。

### [x] B10. vision 识别缓存 key 改摘要 —— 改动文件：`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
LRU 已正确，但 key 是完整 base64 dataUrl（单 key 可达数 MB，16 条仍几十 MB）。
**修法**：key 改为 dataUrl 的 SHA256（前 16 字节 hex 足够），命中语义不变。
  - 红测试：`testVisionRecognitionCacheKeyUsesDigestInsteadOfDataUrl` 先失败，确认没有摘要
    key 生成入口。
  - 绿测试：同一测试通过。视觉识别缓存内部 key 改为 `vision:` + SHA256(dataUrl) 前
    16 字节 hex，LRU 顺序仍按摘要维护，外部结果字典继续以原 dataUrl 传递，命中语义不变。

### [x] B11. 后台生成的注入契约显式化 —— 改动文件：`iosApp/iosApp/ChatGenerationCoordinator.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
后台引擎不调 `injectingRuntimeContext`，靠 handoff 快照"恰好"拿到已合并消息
（隐式正确）；`executeToolCall` 内 `refreshBackgroundHandoff`(:934) 用的是未注入的
baseMessages（当前仅 .singleToolOnly 路径无害）。**修法**：handoff 采集点统一
显式走注入+合并管线，或加契约测试锁住"handoff 快照必含单条合并 system"。
  - 红测试：`testBackgroundToolHandoffUploadMessagesInjectsRuntimeContextAndCoalescesSystem`
    先失败，确认工具期 handoff 没有显式的注入/合并契约入口。
  - 绿测试：同一测试通过。新增工具期 handoff 上传快照构造函数：先走
    `messagesByInjectingRuntimeContext`，再 `coalescingSystemMessages` 合成单条 system；
    `executeToolCall` 的 handoff 刷新改用该函数。注：当前活跃 `startStreaming` handoff
    已接收主请求管线产出的 final messages，本项未改其运行时语义。

### [x] B12. 密钥迁移不闭环 —— 改动文件：`iosApp/iosApp/IOSSharedSettingsStore.swift`、`iosApp/iosAppTests/IOSParityRedLightTests.swift`
Scheme B 迁移后不回写 redact JSON，升级用户明文滞留 UserDefaults 直到下次设置写入。
**修法**：init 迁移成功后立即回写掩码后的 JSON 到 UserDefaults。
  - 红测试：`test_providerApiKey_plaintextMigrationRedactsDefaultsImmediately` 先失败，确认旧版
    明文 provider key 迁移到 side-table 后，UserDefaults 中的原 JSON 仍保留明文。
  - 绿测试：同一测试通过。`rehydrateProviderApiKeys` 与
    `rehydrateSearchServiceApiKeys` 返回是否成功迁移明文；初始化完成 rebrand 后，如发生迁移，
    立即 `restoreSnapshot`，把持久 JSON 改写为 redacted 版本，同时内存/重载仍可从 side-table
    取回真实 key。

### [x] B13. release 敏感日志清理 —— 改动文件：`iosApp/iosApp/IOSBoardPersistence.swift`、`iosApp/iosApp/DeepReadCreateView.swift`、`iosApp/iosAppTests/IOSParityRedLightTests.swift`
`IOSBoardPersistence.swift:1845`（LLM 输出前 120 字符）、:1807/:2038/:3332、
`DeepReadCreateView.swift:267/:271`（搜索词）——统一包 `#if DEBUG`。
  - 红测试：`test_releaseSensitiveLogsAreDebugGuarded` 先失败，确认点名敏感日志 marker
    不在 `#if DEBUG ... #endif` 块内。
  - 绿测试：同一测试通过。翻译请求统计、翻译异常、LLM 输出 head、翻译 apply 统计、
    DeepRead sources 统计、topic-search query/error 与 topic-search 统计均只在 DEBUG 构建记录。

### [x] B14. iPad 多窗口短期止血 —— 改动文件：`iosApp/iosApp/Info.plist`、`iosApp/iosAppTests/IOSParityRedLightTests.swift`
store/viewModel 每窗口一份互不知情、后台协调器单槽位覆盖。**短期修法**：Info.plist
设 `UIApplicationSupportsMultipleScenes=false`；完整多窗口支持进 C 阶段另立项。
  - 红测试：`test_iPadMultipleScenesDisabledUntilMultiWindowCoordinatorExists` 先失败，确认
    Info.plist 没有关闭多 scene。
  - 绿测试：同一测试通过。Info.plist 顶层加入
    `UIApplicationSupportsMultipleScenes=false`，先阻断 iPad 多窗口下多份 store/viewModel
    与后台单槽位互相覆盖的问题；完整多窗口仍留 C 阶段。

### [x] B15. 标题 rename 读改写竞态（先测后修） —— 改动文件：`core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt`、`core/conversation-storage/src/jvmTest/kotlin/app/amber/core/storage/conversation/JsonConversationStorageTest.kt`
Kotlin `updateMetadata` load→save 整份重写。**先写并发单测实锤**（rename 与对话落盘
交错），复现后修法：metadata 更新走细粒度合并而非整份覆盖（或纳入 A7 序列号体系）。
  - 红测试：`updateMetadataSerializesAgainstConcurrentSaveWithoutOverwritingMessages` 先编译红，
    确认存储层缺少可观测的 metadata 读改写窗口与原子化保护。
  - 绿测试：同一测试通过。`JsonConversationStorage` 增加单写者 `Mutex`，把
    `list/load/save/delete/updateMetadata` 的文件与 index 访问串行化；`updateMetadata`
    不再调用公开 `load/save` 形成可穿插窗口，而是在同一锁内 load→copy→save，
    避免 rename 的旧快照覆盖并发落盘的新消息。

### [x] B16. OCR 兜底窗口竞态 —— 改动文件：`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`
`isRecognizingImages && !isGenerationActive` 窗口内 regenerate/edit/delete/selectVariant
不设防，OCR 完成回调的 generateResponse 会取代用户操作。**修法**：这些入口的守卫
纳入 `isRecognizingImages`；OCR 回调启动生成前校验会话与消息基线未变。
  - 红测试：`testBranchMutationsAreRejectedWhileVisionRecognitionIsPending` 先失败/编译红，
    确认 OCR pending 窗口内分支编辑、重生成、删除、切 variant 没有统一门禁；
    `testVisionRecognitionResultIsRejectedAfterMessageBaselineChanges` 先编译红，确认缺少
    OCR 回调消息基线判据。
  - 绿测试：上述两个测试通过。分支操作入口统一增加 `!isRecognizingImages`；OCR fallback
    在发出用户消息后捕获 `messageRevision` 与会话 id，识别成功回主线程时必须仍是同会话、
    同消息基线且无活跃生成，才缓存识别文本并启动主模型生成。

### [x] B17. BGTask 到期丢弃已生成正文 —— 改动文件：`iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift`、`iosApp/iosAppTests/IOSParityRedLightTests.swift`
引擎有 onAssistantText 回调但到期 fail() 路径没接。**修法**：到期时先把已积累正文
走 saveBackgroundCompletion 落盘再 fail。
  - 红测试：`testBackgroundExpirationFailurePreservesPartialAssistantText` 先编译红，确认
    后台失败收尾没有“部分 assistant 正文 + 失败提示”的构造出口。
  - 绿测试：同一测试通过。`IOSChatBackgroundGenerationCoordinator` 在 continued-processing
    运行中用线程安全 snapshot 接住 `IOSAgentToolEngine.run(onAssistantText:)` 的最新正文；
    BGTask 到期进入 `fail` 时，若已有非空正文，先作为 assistant 消息并入后台完成落盘，
    再追加用户可见失败提示。

### [x] B18. Workspace 假"已保存" —— 改动文件：`iosApp/iosApp/DeepReadCreateView.swift`、`iosApp/iosApp/ChatViewModel.swift`、`iosApp/iosApp/MiniAppRunnerView.swift`、`iosApp/iosAppTests/IOSParityRedLightTests.swift`
三处 `_ = try? saveArtifact` 后无条件成功文案；`IOSWorkspaceStore.errorMessage` 零读取。
**修法**：失败时提示失败并保留重试入口。
  - 红测试：`testWorkspaceArtifactSavesDoNotUseTryOptionalSuccessPath` 先失败，确认
    DeepRead、MiniApp 保存、MiniApp host createArtifact 三处仍用 `try?` 吞掉 Workspace
    保存失败并继续展示成功文案。
  - 绿测试：同一测试通过。DeepRead 生成结果仍完成保存到 DeepRead store，但 Workspace
    artifact 失败会显示失败状态；MiniApp 生成通知会明确标注 Workspace 同步保存成功或失败；
    MiniApp host createArtifact 只有 Workspace 保存成功后才更新 board summary 并返回 accepted，
    保存失败时通过 host error 与 `actionMessage` 暴露给用户。

### B 阶段验证记录
- `xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -only-testing:iosAppTests test`
  已启动，但卡在 Xcode `_IDETestingFinalizeLogsOperation` / `Finalize test log`
  约 293 秒后中断；未观察到具体测试断言失败。该现象与 Fable5 复审提到的全量
  iosAppTests log finalize 卡住一致。
- 分项验证已通过：B15 `:core:conversation-storage:jvmTest --tests ...updateMetadataSerializesAgainstConcurrentSaveWithoutOverwritingMessages`；
  B16 `testBranchMutationsAreRejectedWhileVisionRecognitionIsPending`、
  `testVisionRecognitionResultIsRejectedAfterMessageBaselineChanges`；
  B17 `testBackgroundExpirationFailurePreservesPartialAssistantText`；
  B18 `testWorkspaceArtifactSavesDoNotUseTryOptionalSuccessPath`。

---

## 阶段 C：结构性任务（按需排期，不与 R/A/B 混做）

- [ ] **C1. 错误总线**：统一"persist/decode 失败必须上报用户可见错误总线"的机制，
  收编全仓 9+ 处解码静默清零（MCP 配置/服务商注册表/Codex OAuth token/审批记录等）
  与 6+ 处 persist 仅日志（Memory/Board×4/MCP）。`lastIOError` 修好后即总线雏形。
  - 2026-07-09 第一切口已完成：新增通用 `IOSUserVisibleError` /
    `IOSUserVisibleErrorSeverity`，`IOSConversationStore` 增加
    `lastUserVisibleError`、`publishUserVisibleError`、`clearUserVisibleError`；
    现有 `lastIOError` 保留兼容，但所有会话 I/O 错误统一经 `publishIOError`
    同步进入用户可见错误总线，`ChatView` alert 改为读取通用总线。
    红测试：`testIOErrorIsSetWhenSaveFails` 扩展为断言 IO 错误同步进入总线，
    新增 `testUserVisibleErrorBusCanPublishNonConversationErrors` 验证非会话错误也可复用总线。
    已通过 `IOSSkillInjectionAndIOErrorTests` 全类与 `ChatStreamReplayTests`。
    改动文件：
    `iosApp/iosApp/IOSConversationStore.swift`、
    `iosApp/iosApp/ChatView.swift`、
    `iosApp/iosAppTests/IOSSkillInjectionAndIOErrorTests.swift`。
    剩余：Memory/Board/MCP/Codex OAuth 等静默 decode/persist 点逐步接入该总线。
- [ ] **C2. 双实现 parity 测试**：前台/后台工具循环收尾语义、host/vendor 渲染器能力
  矩阵、iOS/Android deleteMessage 粒度（iOS 删整 node 连坐 variant）——各补一组
  同输入同输出契约测试。
  - 2026-07-09 第一切口已完成：补三条契约测试，不改生产逻辑。
    1) `testBackgroundToolCompletionPatchesExistingToolCallAndPreservesForegroundMessages`
    锁定后台 tool completion 在前台已插入新消息时应原地补齐 pending tool output，
    保留前台消息且不追加 notice 气泡；
    2) `testDeleteMessageRemovesWholeNodeIncludingSiblingVariants` 锁定 iOS 当前
    deleteMessage 粒度为删除整 node，包含 sibling variants；
    3) `testNativeTimelineRendererMatrixMatchesTimelinePlanner` 锁定 native timeline
    projection 与旧 timeline planner 在历史/流式/刚完成三种 renderer 状态上的矩阵一致。
    已通过三条新增 targeted tests、`IOSConversationStoreBranchingTests` 全类、
    `ChatMessageProjectionTests` 全类。改动文件：
    `iosApp/iosAppTests/IOSConversationStoreTests.swift`、
    `iosApp/iosAppTests/IOSConversationStoreBranchingTests.swift`、
    `iosApp/iosAppTests/ChatMessageProjectionTests.swift`。
    剩余：host/vendor 渲染器能力矩阵仍需更细的 Markdown feature canary；
    前台/后台工具循环完整 e2e 可随 B17b/C5 继续补。
- [ ] **C3. 接线完整性审计**：脚本列出所有 @AppStorage/settings 字段 → grep 消费点 →
  零消费报警。已知哑接线：pasteLongTextAsFile、mirror flag、
  ChatSelfSizingInvalidationBridge（死代码，只标记）、liyanan/microsoft 渲染分支不可达。
  - 2026-07-09 第一切口已完成：新增 `IOSSettingsWiringTests` 静态接线 canary，
    先覆盖本轮最容易复发的“原生滚动容器（实验性）”三联开关：
    设置页必须写 `NativeChatTimelineStaticRenderFeatureFlags.key`、
    `NativeChatTimelineStreamingTailFeatureFlags.key`、`NativeTimelineScrollFeatureFlags.key`；
    `ChatView` 必须读取 static/streaming 两个 key 并传入 `ChatMessageListRoutePolicy`；
    `NativeChatTimelineView` 必须消费 scroll driver key 并接到 `NativeTimelineScrollDriver`。
    已通过 `IOSSettingsWiringTests` 全类。改动文件：
    `iosApp/iosAppTests/IOSSettingsWiringTests.swift`。
    剩余：把 `@AppStorage`/settings 字段列表自动化生成并覆盖更多已知哑接线；
    当前只做高风险手写 canary，避免一次性大脚本误伤。
  - 2026-07-09 第二切口已完成：继续扩展 `IOSSettingsWiringTests`，覆盖流式
    Markdown 渲染库开关接线。新增
    `testStreamingMarkdownRendererTogglesAreWiredAndMutuallyExclusive`，
    锁定微软/MarkdownView 两个实验渲染器开关在设置页互斥、且 `MessageBubbleView`
    同时消费两个 key 并接入对应渲染器；新增
    `testStreamingBlockMarkdownToggleIsConsumedByTableBlockRenderer`，锁定
    “表格流式块渲染”开关真正进入 `shouldUseBlockStreamingRenderer` 和
    `ChatStreamingMarkdownTableView` 路径。已通过 `IOSSettingsWiringTests` 全类。
    改动文件：`iosApp/iosAppTests/IOSSettingsWiringTests.swift`。
- [ ] **C4. 持久化单一事实来源**：A7 序列号升级为完整"写入者注册表 + 基线合并写"
  （对齐 saveBackgroundCompletion 的 sameMessageIdentity 思路，推广到所有写入口）。
  - 2026-07-08 第一切口已完成：Kotlin `JsonConversationStorage.saveConversation`
    对已存在会话保留 metadata owner 字段 `title/isPinned`，`updateMetadata` 作为
    metadata owner 走 raw write；Swift `IOSConversationStore.persist` 删除
    `preservingLatestMetadata` 补偿层，落盘后重读实际持久化对象。红测试：
    `saveConversationMessageWritePreservesExistingMetadataOwnerFields` 先红后绿；
    同步调整 `saveUpsertsExistingConversation`/metadata 并发测试到新 owner 语义。
    改动文件：
    `core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/ConversationStorageInterface.kt`、
    `core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt`、
    `core/conversation-storage/src/jvmTest/kotlin/app/amber/core/storage/conversation/JsonConversationStorageTest.kt`、
    `iosApp/iosApp/IOSConversationStore.swift`。
    剩余：完整写入者注册表/其他 metadata 字段所有权（`chatSuggestions`、
    `autoApproveToolCalls` 等）继续留在 C4 后续切口。
  - 2026-07-08 第二切口已完成：未强造 `chatSuggestions`/`autoApproveToolCalls`
    的写入口（iOS 侧当前无独立持久写入者，`chatSuggestions` 主要是 VM 内存态），
    但把它们也纳入已有会话的非消息 owner 字段保护：message-write 不再覆盖
    `chatSuggestions` 与 `autoApproveToolCalls`。红测试扩展
    `saveConversationMessageWritePreservesExistingMetadataOwnerFields`，先红后绿。
    改动文件：
    `core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt`、
    `core/conversation-storage/src/jvmTest/kotlin/app/amber/core/storage/conversation/JsonConversationStorageTest.kt`。
    剩余：如果未来要持久化建议/工具审批，需要新增明确 partial-update owner API，
    不能借 message-write 顺手写。
- [x] **C5. chunk 消费队列**：onChunk 每 chunk 独立 `Task{@MainActor}` 无 FIFO 保证 +
  per-chunk snapshot O(n²)——改单一 AsyncStream 消费队列，snapshot 推迟到 16ms flush。
  - 2026-07-09 第一切口已完成：前台 streaming onChunk 不再每块调用
    `accumulator.snapshot()`；改为登记 `snapshotProvider`，只在 16ms flush、
    cancel、background handoff 需要快照时取最新 accumulator snapshot。红测：
    `IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush`
    先红后绿。同步修复已存在的 Grok Web 半成品编译断裂：
    `IOSGrokWebProvider.swift`/`GrokWebLoginView.swift` 加入 Xcode target，
    `IOSGrokWebClient.streamText` 标注 `@MainActor`，`ChatView` 显式覆盖
    `.grokNotSignedIn`。改动文件：
    `iosApp/iosApp/ChatGenerationCoordinator.swift`、
    `iosApp/iosAppTests/IOSParityRedLightTests.swift`、
    `iosApp/AmberAgent.xcodeproj/project.pbxproj`、
    `iosApp/iosApp/IOSGrokWebProvider.swift`、
    `iosApp/iosApp/ChatView.swift`。
  - 2026-07-09 第二切口已完成：前台 provider 回调只向
    `AsyncStream<ChatStreamEvent>` 投递 chunk/complete/error，由单一
    MainActor FIFO consumer 串行 append accumulator、探测工具调用、登记
    16ms snapshot provider；不再每 chunk 创建一个 `Task { @MainActor }`。
    后台/子代理 `IOSAgentToolEngine.streamStep` 也去掉每 chunk
    `snapshot()+join`，改为 `StreamStepState.appendAssistantTextDelta`
    增量维护累计 assistant 文本，完成时才 snapshot 生成最终 chunk。
    红测：
    `IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush`
    和
    `IOSAgentToolEngineTests/testStreamingAssistantTextDoesNotSnapshotOnEveryChunk`
    均已先红后绿。验证：上述两项 + B17 partial failure targeted tests 绿，
    `ChatStreamReplayTests` 全类绿。改动文件：
    `iosApp/iosApp/ChatGenerationCoordinator.swift`、
    `iosApp/iosApp/IOSAgentToolEngine.swift`、
    `iosApp/iosAppTests/IOSParityRedLightTests.swift`、
    `iosApp/iosAppTests/IOSAgentToolEngineTests.swift`。
- [x] **C6. 默认路径行级 digest 门控**：本轮 NativeTimelineProjectionCache 只惠及
  实验路径；默认路径（ChatSwiftUIMessageList）仍无行级门控、历史行 AttributedString
  每 flush 全量重建。把增量投影/门控思路移植到默认路径。
  - 2026-07-09 已完成：默认 SwiftUI clean-list 接入
    `swiftUIRenderStateStore` + `swiftUIContentHashCache`，消息行改走
    `ChatSwiftUIMessageBubble(...).equatable()`，用共享 `ChatRowDigests`
    做行级门控；历史行不比较整条 `UIMessage`，尾部流式 delta 不再让整屏历史
    Markdown 子树一起重建。为避免视觉降级，默认路径对非尾部已流式 assistant
    保持 `liveRenderingEnabled=true`，不复用 native/collection 的 frozen 历史态。
    红测：`ChatRowContentHashCacheTests/testSwiftUICleanListRowsUseDigestEquatableWrapper`
    先红后绿。验证：该 canary 绿，`ChatStreamReplayTests` 全类绿。改动文件：
    `iosApp/iosApp/ChatCollectionMessageList.swift`、
    `iosApp/iosAppTests/ChatRowContentHashCacheTests.swift`。
- [ ] **C7. 多工具批量执行**（A4 长期部分）。

## P2/P3 附录（择要，随相关文件顺带修或独立小 PR）

- 10.1 表格探测判据与真解析器不一致：`isDelimiterLine` 要求 ≥3 个 `-`（GFM 只要 1）、
  fence 只识别 ``` 不识别 ~~~ ——统一为与消费侧一致的判据。
- blocks() NSCache 缓存收益存疑：key 是全文本，跨 delta 必 miss——加 hit/miss 计数
  实测后决定保留或简化；@unchecked Sendable 补注释说明动机。
- CJK 软换行折叠只看前字符（中文结尾+英文开头粘连）——补"后字符也是 CJK"判断，双渲染器同步。
- PartialTableScanner 单 `|` 前缀过匹配；```blockmath 语言标签与 LaTeX 哨兵冲突。
- 静态渲染器长单行代码块无横向滚动兜底（codeBlockAutoWrap=false 时）。
- mailto 误杀决策（见 A6）；无 scheme 裸域名链接的 UX。
- 透明 PNG 转 JPEG 黑底；相册多选失败静默 continue；纯图片消息无长按菜单；粘贴图片未拦截。
- 思考胶囊 userToggled 永不复位；reasoning 不走 markdown 管线。
- 429/5xx 无专门文案；错误气泡与正常回复无视觉区分。
- ClaudeKmpProvider 头注释"不支持图片"与实现不符（改注释+补测试）。
- variantBadge（已修 ✅）、IOSSearchExecutor 日志（已修 ✅）保留。
- 冷启动 bootstrap 与手动新建的双空会话竞态（随 B9 一起看）。
- Google/Gemini 设置已接 UI 未接主聊天（产品决策后排期）。

## §6 真机验证清单（每项完成后勾选，模拟器绿不算过）

- [ ] 流式生成含表格回复：尾行无裸 pipe 文本、无过线/错位/闪白（R1/R2）
- [ ] 完成态瞬间：无"未渲染→渲染"或"降级段落→表格"跳变（R2）
- [ ] 大表格流式动画完整、Instruments 无掉帧尖峰（R5）
- [ ] 横向拖拽/键盘联动/表格横向滚动：无漂移，触发时正确降级而非死扛（R4）
- [ ] 工具循环上限：卡片显示失败输出、run 记 failed、无空 output 落盘（R 阶段已修项回归验证）
- [ ] cancel → 同会话立即发新消息：新消息不被旧快照覆盖（R6/A7）
- [ ] cancel → 切会话：旧会话文件不含新会话内容（第二轮 P0 回归验证）
- [ ] 后台生成中删除该会话：文件不复活（A2）
- [ ] 后台生成中发消息被拒（A3）；Claude 路径记忆/技能/MCP 目录生效（合并修复回归验证）
- [ ] 长 session 滑动历史消息帧率（C6 之前记录基线）
- [ ] 点击模型输出的 `shortcuts://` 链接无反应、http 链接正常（A6）
- [ ] 历史行 `$$...$$` 块级公式渲染、dark/light 主题色正确，流式态与历史态（cell 回收后）视觉一致（B3a）

---

## 阶段 B'：收口返工（2026-07-08 Fable5 复审 B15-B18 后新增，收口前必须完成）

### [x] B15b. rename 竞态的另一半：Swift 层旧快照反向覆盖新标题（P1）
- Kotlin Mutex 只保证单次 CRUD 原子；`IOSConversationStore` 的 `save`/`saveBackgroundCompletion`/
  `saveBackgroundToolCompletion`/`appendVariant`/`appendVariantAndTruncateAfter`/
  `deleteMessage`/`truncateAfter` 都是"load→改→save"跨多次顶层调用的复合操作，
  持有的内存 `conversation`（含旧 title/isPinned）会把并发 rename/togglePin
  刚写盘的新值覆盖回旧值；`writeSequences/acceptsWrite` 只校验消息版本，
  `renameConversation`/`togglePin` 从不 `advanceWriteSequence`。
- **修法**：让 `updateMetadata` 类变更也推进该会话的写序列号，复合写路径落盘前
  经 `acceptsWrite` 校验（失败则重读合并再写）；或在 store 层为"同一会话的
  复合读改写"加互斥。**不要**试图在 Kotlin 层扩大锁范围解决（锁不住 Swift 两次调用之间）。
- **红测试**：save() 捕获旧快照 → 期间 rename 落盘新标题 → save 执行 →
  断言磁盘标题是新标题（当前会被覆盖回旧标题，测试先红）。
- 已完成：新增 `testInFlightSaveDoesNotOverwriteConcurrentRenameTitle`，先红后绿；
  `renameConversation`/`togglePin` 推进写序列，复合落盘前合并最新 title/isPinned，
  并让调用方使用实际落盘后的 conversation。改动文件：
  `iosApp/iosApp/IOSConversationStore.swift`、
  `iosApp/iosAppTests/IOSConversationStoreTests.swift`。

### [x] B16b. OCR 竞态修复的四个缺口（P1）
1. **误杀**：`messageRevision` 是 VM 级全局计数器，其他会话流式事件、设置变更、
   切走再切回同会话都会 bump → `shouldApplyVisionRecognitionResult` 硬相等判据
   在 OCR 数秒窗口内极易失配 → 用户发的图片消息**永久无回复且零提示**。
   修法：判据改为"发起 OCR 时的那条 user 消息仍在当前会话消息树中"（按消息 id 查），
   替代全局 revision 相等；revision 保留作辅助也必须 per-conversation 化。
2. **失败路径无基线**：`ChatViewModel.swift:617-618` 失败分支无条件写
   `selectedFileContextError`（VM 级），切会话后旧会话错误串到新会话。
   修法：失败分支复用同一会话基线校验，不同会话则丢弃。
3. **漏门禁**：`modifyGeneratedImage`(:527) 只查 `!isGenerationActive`，
   补 `!isRecognizingImages`。
4. **静默拒绝**：四个分支操作被 OCR 门禁拒绝时零反馈（按钮不禁用、无 toast）——
   把"OCR 覆盖用户操作"换成了"用户操作被静默吞"。修法：拒绝时 toast
   "图片识别中，请稍候"，UI 层按 `isRecognizingImages` 禁用相关按钮。
- **红测试**：a) OCR pending 中触发无关 bump（设置变更/其他会话事件模拟），
  断言 OCR 完成后 generateResponse **仍被调用**（当前实现会误拒，测试先红）；
  b) OCR 失败 + 切会话，断言 selectedFileContextError 不写入新会话；
  c) isRecognizingImages=true 时 modifyGeneratedImage 不触发 runImageTool。
- 已完成：新增
  `testVisionRecognitionResultSurvivesUnrelatedRevisionBumpWhenUserMessageStillExists`、
  `testVisionRecognitionResultIsRejectedAfterUserMessageDisappears`、
  `testVisionRecognitionFailureIsIgnoredAfterConversationChanges`、
  `testModifyGeneratedImageIsRejectedWhileVisionRecognitionIsPending`，先红后绿；
  OCR 判据改为按 user message id 查当前会话，失败路径复用同一基线，
  改图/分支操作 OCR pending 时给出 `"图片识别中，请稍候"`。改动文件：
  `iosApp/iosApp/ChatViewModel.swift`、
  `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`。

### [ ] B17b. 后台到期 partial 的三个跟进（P2，可与 B' 其他项并行）
1. [x] partial 正文 + 失败提示合并为**单条** assistant 消息（对齐前台 cancel 的
   单气泡语义，避免"正文后紧跟报错气泡"）。红测：
   `IOSParityRedLightTests/testBackgroundExpirationFailureMergesPartialAndFailureIntoSingleAssistantMessage`。
   改动文件：`iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift`、
   `iosApp/iosAppTests/IOSParityRedLightTests.swift`。
2. 补端到端红测试：驱动引擎测试替身多次 chunk 回调 → 中途 expire →
   断言落盘含到期时刻累计全文（现测试只测 failedMessages 纯函数拼装）。
3. 已知残留（记录，随 C 阶段修）：多步工具循环中已完成步骤存于 run() 内部
   working 数组，到期只保留当前 streamStep 文本；后台 onChunk 每块做
   snapshot()+join 是 O(n²)（与前台第一轮发现同构）。

### [x] B18b. DeepRead 真后台路径 Workspace 失败不可见（P1）
- `DeepReadCreateView.swift:472-477` 的 `handle(_ backgroundTask:)` 调
  `runExistingTask` 未传 `onStatus`（默认 nil）→ 本轮新增的失败文案在
  **系统后台执行路径**（恰是产品引导用户走的主路径）是 no-op；
  且 `store.complete()` 无条件钉死 `.succeeded` 并清 failureMessage，
  重入 guard 永久拒绝重跑——用户无从得知 Workspace 缺文件、无法补救。
- **修法**：`IOSDeepReadTask` 增加持久化字段 `workspaceSyncFailed: String?`，
  Workspace 失败时写入该字段（不依赖易失闭包）；DeepRead 详情/历史页渲染
  该警示并提供"仅重试保存到 Workspace"按钮（不重新生成）。
- **红测试**：mock saveArtifact 抛错，走 `handle(_:)` 全路径（不经 View），
  断言任务上有持久化失败标记。
- 次要（排期）：MiniAppRunner saveArtifact 成功但 updateBoardSummary 失败的
  孤儿态——回滚 artifact 或文案改为"已保存但摘要更新失败"；
  三处补行为级测试（注入抛错 store 断言用户可见状态），
  现有源码文本断言测试保留但只作防复发底线。
- 已完成：新增 `testRunExistingTaskPersistsWorkspaceSyncFailureWithoutStatusHandler`，
  先红后绿；`IOSDeepReadTask.workspaceSyncFailed` 持久化 Workspace 同步失败，
  `runExistingTask` 不依赖 `onStatus` 写入失败标记，详情页显示警示并提供
  "仅重试保存到 Workspace"。改动文件：
  `iosApp/iosApp/IOSBoardPersistence.swift`、
  `iosApp/iosApp/DeepReadCreateView.swift`、
  `iosApp/iosApp/BoardView.swift`、
  `iosApp/iosAppTests/IOSDeepReadPipelineTests.swift`。

---

## 阶段 B''：最后一轮定点小补（2026-07-08 Fable5 复审 B' 后新增；完成即收口）

### [x] B16c. OCR 失败分支"串会话"被修成"彻底静默"（P1，约 30 行）
- `applyVisionRecognitionFailure` 复用 `shouldApplyVisionRecognitionResult` 守卫，会话不匹配时
  直接 return **不留任何痕迹**——用户切走后 OCR 失败被无声吞掉，assistant 永不回复且零提示
  （正是上轮警告过的"别把串会话修成彻底静默"）。
- **修法**：新增 `pendingVisionFailures: [String(会话id): String]`；守卫拒绝时存入而非丢弃；
  切回该会话（reloadFromStore/selectConversation 钩子）时 flush 到 `selectedFileContextError`。
- 连带：`"图片识别中，请稍候"` 写入常驻 `selectedFileContextError` 后 **OCR 完成不清除**——
  在 OCR 成功/失败回调里若该字段仍等于此提示文案则置 nil。
- **红测试**：a) 发图→切会话→OCR 失败→切回，断言失败提示出现在原会话；
  b) OCR pending 中触发提示→OCR 完成，断言提示被清除；
  c) 补 regenerate/editMessage/deleteMessage/selectVariant 被拒时提示文案断言（现仅
  modifyGeneratedImage 有此断言）。
- 已完成：新增
  `testVisionRecognitionFailureIsFlushedWhenOriginalConversationReturns`、
  `testVisionRecognitionSuccessClearsTransientPendingPrompt`，并补
  `testBranchMutationsAreRejectedWhileVisionRecognitionIsPending` 的提示断言；
  `pendingVisionFailures` 按会话暂存失败，`reloadFromStore` 切回时 flush，
  OCR 完成清理 `"图片识别中，请稍候"` 临时提示。改动文件：
  `iosApp/iosApp/ChatViewModel.swift`、
  `iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift`。

### [x] B15c. baseline 机制的两个连带问题（P1，约 30 行）
1. **后台回复被丢弃**：`saveBackgroundCompletion`/`saveBackgroundToolCompletion` 的
  baseline 被拒时 `guard else return` 直接丢弃整条后台生成内容且不重试——改造前是
  last-writer-wins 覆盖前台（旧 P0），改造后变成后台内容凭空消失（新数据丢失路径）。
  **修法**：拒绝时用新 baseline 重试（重新 load + 重新走 notice 合并分支，上限 3 次）。
  **红测试**：baseline 捕获后前台写入推进序列号→saveBackgroundCompletion 执行→
  断言后台内容最终仍落盘（notice 合并态），而非消失。
2. **预期拒绝弹错误 alert**：`acceptsWrite` 对设计内的过期快照跳过（R6 场景：cancel 后
  快速发新消息）也设置 `lastIOError` 弹 alert——"正常操作弹错误"会让用户对真 IO 错误
  脱敏。**修法**：stale-skip 只 print 日志不设 lastIOError（数据被正确保护，不是错误）。
- 已完成：新增 `testBackgroundCompletionRetriesAfterForegroundWriteAndKeepsResult`，
  并在 `testStaleSnapshotWriteBaselineDoesNotOverwriteNewerForegroundSave` 增加
  `lastIOError == nil` 断言；后台 completion/tool completion 在 baseline 被拒后
  最多用新 baseline 重试 3 次并重新合并 notice，stale-skip 降为 log-only。
  改动文件：
  `iosApp/iosApp/IOSConversationStore.swift`、
  `iosApp/iosAppTests/IOSConversationStoreTests.swift`。

### [x] B18c. 重试按钮防连点（P1 前半）+ artifact 去重等（P2 后半）
- **必做**："仅重试保存到 Workspace"按钮无 disabled/loading 态，`saveArtifact` 是纯 insert
  （UUID 新 id）——连点必产生重复 artifact。加 `isRetryingWorkspaceSync` 状态 +
  `.disabled`，进入置 true、defer 复位。
- **可排期 P2**：saveArtifact 按 sourceId upsert（防"insert 成功 persist 失败后重试"重复）；
  历史列表行对 `workspaceSyncFailed != nil` 任务加琥珀警示态（现列表仍绿勾，仅详情页有
  banner）；补"重试成功清标记""旧 JSON 解码兼容"两个测试。
- 已完成 P1 前半：`IOSDeepReadTaskDetailView` 增加 `isRetryingWorkspaceSync`，
  "仅重试保存到 Workspace" 进入后显示 loading 并禁用，结束后复位。
  P2 去重/upsert、历史列表警示态与补测试保留排期。改动文件：
  `iosApp/iosApp/BoardView.swift`。

### 记录项（不阻塞收口，转入 C 阶段）
- `preservingLatestMetadata` 的 load→save 之间仍有毫秒级残留窗口（比原秒级窗口小几个
  数量级，可接受）；`autoApproveToolCalls`/`chatSuggestions` 不在 metadata 合并范围
  （无独立写入者，理论风险）——两者根治都指向 C4：Kotlin 层拆分 partial-update API，
  让 saveConversation 不再携带/覆盖 metadata 字段。
- B16b 的 shouldApply 系列测试是 DEBUG 纯函数自证，未走 startVisionFallbackAndSend
  真实 Task 链路（与 replay 盲区同模式）——C2 补行为级。
- OCR pending 时按钮不置灰（仅 VM 层拦截+提示）——UX 加固，排期。

---

## §7 交付要求

1. 按 R → A → B → B' → B'' 顺序执行；C 阶段单独立项不混入。
2. 每项：红测试 → 修复 → 绿 → 在本文档勾选并附改动文件。
3. 全部 R+A 完成后跑一次全量 iosAppTests + ChatStreamReplayTests。
   全量卡 Finalize test log 时允许按测试类分批跑，但批次并集必须覆盖全部测试类，
   并在此记录分批清单。
   - 2026-07-08 B' 收口：B16b/B18b/B15b 新增定点测试分别通过；
     尝试组合跑 `ChatViewModelSelectedFileContextTests + IOSDeepReadPipelineTests +
     IOSConversationStoreTests + ChatStreamReplayTests`，以及单跑
     `ChatViewModelSelectedFileContextTests`，均在测试已启动后卡
     `_IDETestingFinalizeLogsOperation / Finalize test log`，已中断，未观察到断言失败。
   - 2026-07-08 B'' 收口：B16c 新增/补充定点测试通过；B15c 定点测试通过；
     B18c 触碰详情页 UI，复跑
     `IOSDeepReadPipelineTests/testRunExistingTaskPersistsWorkspaceSyncFailureWithoutStatusHandler`
     通过；`ChatStreamReplayTests` 通过。首次并发跑 B16c/B15c 时撞 Xcode build.db lock，
     随后改串行通过。
   - 2026-07-08 C4 第一切口：`JsonConversationStorageTest` 全类通过；
     `IOSConversationStoreTests` 中 B15/B15c 相关定点测试通过；`ChatStreamReplayTests`
     第一次全类跑 `testRendererHeightComparisonByGenre` 单例失败，随后单跑该用例通过，
     第二次全类通过；`git diff --check` 通过。
  - 2026-07-08 C4 第二切口：扩展后的 `JsonConversationStorageTest` 全类通过；
    `IOSConversationStoreTests` 中 B15/B15c 相关定点测试通过；`ChatStreamReplayTests`
    通过；`git diff --check` 通过。
   - 2026-07-09 B17b/C5 第一切口：`IOSParityRedLightTests/
     testBackgroundExpirationFailureMergesPartialAndFailureIntoSingleAssistantMessage`
     通过；`IOSParityRedLightTests/
     testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush` 先红后绿并通过；
     `ChatStreamReplayTests` 全类首次跑出现 3 个失败用例，随后
     `testArbiterConversationEntryAnchorsToBottom`、`testDiagnoseOffscreenTailFreezeAtGenerationEnd`、
     `testRendererHeightComparisonByGenre` 串行单跑均通过；再次跑全类未进入断言，
     Simulator launch 失败 `Mach error -308 (ipc/mig) server died`，需后续在干净模拟器状态重跑全类。
4. 真机验证项无法自动化的，在 §6 留待用户手动验证，不得标"已验证"。
