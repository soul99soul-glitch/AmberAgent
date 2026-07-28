# M3 Chat MVP 收尾盘点（source-only 阶段）

> 日期：2026-07-28 ｜ 分支：harmony-preview ｜ head：48cfd49e0
> 权威里程碑定义：docs/harmony-detailed-porting-plan.md §M3（991-1000 行）
> 本文回答三件事：M3 交付物状态 / 已知偏差汇总 / G-D 设备验证与 P1 清单。
> 治理索引：DECISION_LOG.md（D-001..D-042）、PARITY_DEBT.md（PD-001..007）。

## 1. M3 交付物逐条状态

| 交付物（权威计划） | 状态 | 证据锚点 |
|---|---|---|
| Provider 设置 | ✅ source-only 完成（设备验证待 G-D） | D-019 设置页 + resolveChatProviderChoice；D-017 ProviderSetting 3 变体 + Model 全量；KV 持久化（ChatKvStore） |
| 会话列表 | ✅ 完成 | D-022 ChatListPage：搜索（title LIKE）/置顶/删除（AlertDialog 确认）；RDB ORDER BY is_pinned DESC, update_at DESC |
| 聊天页 | ✅ 完成 | ChatPage：节点行渲染（buildRows）+ 分支 ◀n/m▶（D-024）+ 编辑（D-031）+ 删除（D-029）+ fork（D-033）+ 手动改名（D-034）+ 建议 chips（D-026/D-042）+ 图片输入（D-038） |
| 流式回复 | ✅ source-only 完成（SSE 真机验证待 Spike A） | D-012 chat_turn + accumulator；D-035/D-036 停止生成全路径；D-040 非流式；D-041 regenerate 流式预览 |
| 本地保存 | ✅ source-only 完成（设备验证待 Spike B） | D-021 ChatRdbRepository（relationalStore，schema 复用 chat-domain persistence.ts） |
| App 重启恢复 | ✅ source-only 完成（设备验证待 Spike B） | RDB 读取路径 + ChatListPage 进入即 list；会话打开 by id |

**M3 source-only 阶段退出判定：全部 6 项交付物达成。** 设备端验证统一收敛到 G-D（下方 §4）。

## 2. 提前完成的 M4 项（超出 M3 范围）

| M4 交付物 | 状态 | 锚点 |
|---|---|---|
| Assistant 配置 | ✅ 大部分 | 列表/编辑/复制/删除级联（D-027/D-037）；参数组 temperature/topP/maxTokens/contextSize/推理力度/streamOutput（D-039/D-040）；wire 字节级（D-016） |
| System prompt | ✅ | assembleInternalMessages 前置（D-018）；动态块（time/date 等注入）= P1 |
| 基础 transformers | ✅ | Template/ThinkTag/RegexOutput + 管线（D-012，visual/finish 双钩子） |
| Reasoning 展示 | 🟡 部分 | reasoning part 渲染已备；折叠/展开交互 = P1 |
| 错误恢复 | 🟡 偏差 open | PD-006：retry(D-050)+vision fallback(D-071a/D-071b)已落地;残余:设置面 OCR UI/生成守卫 |

## 3. 已知偏差汇总（PARITY_DEBT.md 为准）

- **open（7 条）**：PD-001 空 prefetch 降级（deepread，待 G1 确认）；PD-002 prefetch 预算 15s vs 36s；PD-003 targetSdk 定档；PD-004 时间戳 UTC+Z vs LocalDateTime；PD-005 JS RegExp vs Java Pattern；PD-006 生成错误恢复缺失（retry D-050 + vision fallback D-071a/D-071b 已完成，残余设置面 OCR UI/生成守卫）；PD-007 图片不压缩 + data: URL 直存消息体。
- **待 Android 侧核实（1 条）**：流式合并 reasoning finishedAt 两条路径内部不一致（非鸿蒙偏差，鸿蒙两条均按各自语义忠实）。
- **已消除**：regenerate 流式预览（D-041）。

## 4. G-D 设备验证清单（G-C 解锁后按序执行）

1. **Spike A（SSE）**：RCP requestInStream 流式增量到达 + abort destroy 收尾 → chat 流式回复真机确认。
2. **Spike B（RDB）**：ChatRdbRepository 建库/upsert/级联删除/重启恢复；ChatKvStore preferences 持久化。
3. **Spike C（ArkUI + 渲染）**：流式列表增量刷新性能；Markdown 策略；**ArkUI Image 对 data: URL 的支持**（D-038 登记项）。
4. **PhotoViewPicker 链路**：选图 → fileIo 读 → base64 物化（D-038 全链路真机确认）。

## 5. 剩余 P1 功能清单（按优先级，含 Android 锚点）

| 项 | Android 锚点 | 依赖/说明 |
|---|---|---|
| ~~生成错误恢复（retry/vision fallback）~~ **已完成(D-050 retry + D-071a/D-071b vision fallback)** | GenerationRetry.kt + GenerationHandler.kt:774-820/:425-470/:595-665/:822-823/:1122-1138 + OcrTransformer.kt + OcrPrompt.kt + LruCache.kt + ChatPrefs.kt:34-37,80-83 + FileEncoder.kt:97-99 | classifier+循环+状态文案忠实;分类器/LruCache 64-3d/OcrTransformer 全文/三路径生成接线 + entry OCR prefs/模型查找/generateText 三变体/ChatPage 注册/ImageEncodingError 包装完成;残余:设置面 OCR UI/生成守卫 = P1(PD-006 更新) |
| 工具执行 + 审批循环（执行核心 + 循环集成+审批闭环 + **内置工具首批已完成(D-056/D-057/D-058)**） | Tool.kt + ToolRegistry.kt 策略 + PermissionDecisionResolver.kt + AgentToolDispatcher.kt + ToolInvocationHooks.kt + ToolFailure.kt + AgentLoopBudgetPrompt.kt + GenerationHandler.kt:161-391 + ChatService.kt:869-975/:1122-1190/:2326-2329/:2457-2544 + ToolRegistry.kt:31-161 + ToolsListTool.kt + ToolPolicyExplainTool.kt + ToolSearch.kt 全文(含 ToolExposureState:270-380) + SpeculativeToolRunner.kt + RunPlanUpdateTool.kt + ClipboardTool.kt + DeepReadOpenTool.kt + DeepReadOpenRequest.kt:13-107 + TimeTool.kt + AskUserTool.kt + ConversationContextTools.kt + ConversationHistoryTools.kt + SessionAccessGrantStore.kt + SessionHistoryModels.kt:6-27 + ConversationContextRepository.kt:85-170 | 模型/策略/权限链/调度/预算提示 + agentic 循环(同消息写回/预算 dynamic 块/steer 消费)+ 审批 UI+续跑+idle 阻塞+「继续」+ 分支再生工具化 + ToolRegistry 注册/包装层 + 首批内置工具(get_time_info/ask_user/conversation_*4/session_*4)端到端完成(D-058)+ conversation_queue_*2(D-059)+ 自省/发现三件 tools_list/tool_policy_explain/tool_search+isResidentTool(D-060)+ 本地三件 run_plan_update/clipboard_tool/deep_read_open(D-061)+ ToolExposureState 懒暴露+循环四接线(D-062)+ SpeculativeToolRunner 推测执行+流式观察点(D-063)+ /deepread 斜杠命令+发送路径拦截(D-064);残余:autoApprove 设置面/推测执行设置面(默认禁用,Android 同)/FTS 索引/~~ToolProfileFilter~~(D-082 已落地,设置面选择 UI 除外)/grant 签发(history subagent)/~~LocalToolOption 门控~~(D-083 已落地,编辑面 UI 除外)/~~canSendWithoutChatModel 模型空值门~~(D-080 已落地)/ask_user 问题 UI/工具卡片完整视觉 = P1 |
| ~~FOLLOWUP 发送排队~~ **已完成(D-048)**；~~answer=false 发送路径~~ **已完成(D-051)**；~~队列持久化~~ **已完成(D-052)** | ChatService.kt:687-867；ConversationSession.kt:110-232；ChatInput.kt:272；PendingMessageStore.kt | 队列纯逻辑+chips UI+循环派发完成;长按发送=只记录不生成(STEER/FOLLOWUP 逐字忠实)+停止时 STEER→FOLLOWUP 完成;KV 持久化+进程死亡恢复(launchPendingMessageLoopIfNeeded 逐字)+审计 jsonl 完成;残余:STEER 进行中注入 ~~= P1~~ 已落地(D-081 修正 answer 标志 + mode/answer 解耦) |
| ~~ContextEngine + compact（上下文压缩）~~ **已完成(D-054 纯逻辑 + D-055 引擎执行+集成)** | ConversationContextEngine.kt(806 行) + PreparedContextEditor.kt + CompressPrompt.kt + ChatService invalidateCompacts/copyValidCompacts | prepareContext 管线(编辑→规划→force/precompact→注入→fit)+compactConversation(质量重试+fallback)+四失效钩子+fork 复制+KV 存储完成;残余:compact 设置面/compress 任务模型/ContextUsageIndicator 环/repository search-expand = P1 |
| ~~搜索 SDK + search 工具组~~ **已完成(D-065..D-070)** | search/ 全模块(SearchService.kt:1-314 + 17 provider 实现 ~2500 行) + SearchAggregator.kt(384) + SearchOrchestrator.kt(848) + SearchTools.kt(256) + SearchPrefs + KeyRoulette.kt | **全部完成(D-070 收官)**:D-065 核心+D-066 内置 JSON 源+D-067 mini-DOM+Bing/DDG+D-068 17 provider+getService+D-069 Aggregator+Orchestrator+D-070 SearchTools 四件+SearchPrefs 持久化+entry 接线(ensureSearchSdk/ChatPage 工具注册);残余:搜索设置面 UI(服务 CRUD/开关,webview_search_open 工具)= P1 |
| 图片压缩 + file:// 私有目录存储 | FileEncoder.kt compressAndEncode | PD-007；PixelMap 管线；落盘记库已完成(D-086 FilesRepository,deleteChatFiles 纯逻辑备);会话删除/附件移除清理接线依附撤销窗 = P1 |
| ~~Google 请求构建~~ **已完成(D-045)** | ai/ GoogleProvider.kt(949 行) | Claude(D-044)/Google(D-045)均完成;**entry 运行时接线已完成(D-046)**;entry 运行时接线(D-046)与三变体设置编辑 UI(D-047)均完成;残余:BuiltInTools/Vertex service account/Code Assist OAuth 传输/authMode-brand 编辑面 = P1 |
| 动态 system prompt blocks（time/date/lorebook/mode 注入） | Assistant.kt modeInjectionIds/lorebookIds | 依赖 lorebook/mode 存储 |
| ~~customHeaders/customBodies 编辑 UI~~ **已完成(D-049)** | AssistantRequestPage.kt:78-95 + PropertyEditor.kt | 助手级编辑+运行时注入完成;model 级自定义参数编辑面 = P1(数据已被 mergeCustomParams 消费) |
| ~~memory 召回+写路径+工具+抽取+dream 核心~~ **已完成(D-085a/b/c/d/e/f)** | memory/ 目录 19 文件(recall/prompt/time/models/safety/store 读写/tools/extraction×2/telemetry/prompt×2/import-export×2/dream×2+gate) | 读路径+动态块+写路径纯函数+memory_* 四件+extraction 五门逐字/去抖/配额/autoWrite 分流+导出导入+dream Planner/Applier/Gate/Prompt 核心完成;偏差:worker/daydream 模型=chat 模型顶替(任务模型设置未移植,D-025 同类);daydreamReasoningLevel 未移植;PlanStore 持久化+RunCoordinator+Scheduler 纯逻辑+Worker 编排+Notifier 适配已完成(D-085g);残余:鸿蒙 workScheduler 夜窗调度环/真实通知 publish/手动触发 UI+pending 审查 UI/候选操作面/设置面 memory UI(含导出导入触发)= P1 |
| ~~reasoning 折叠/展开交互~~ **已完成(D-053)** | ChatMessageReasoning.kt(452 行) + MarkdownUtils.kt:35-55 | 三态卡片+流式迁移+秒数头部+尾窗截断完成;残余:budgetLabel 后缀/title 右侧 duration/shimmer+Markdown 富渲染/100dp 滚动窗 = P1(Spike C 视觉) |

## 6. 结论

M3（Chat MVP）source-only 阶段 **完成**，进入设备验证等待态（G-C blocked_by_human）。
source-only 可做的 chat 主线功能面已基本穷尽；后续 source-only 切片转向：
P1 清单中无设备依赖的纯逻辑项（~~FOLLOWUP 排队~~ **已完成 D-048**、~~Google/Claude 请求构建~~ **已完成 D-044/D-045/D-046**、
~~customHeaders/customBodies 编辑~~ **已完成 D-049**、~~google/claude 变体设置编辑 UI~~ **已完成 D-047**）与 deepread/novel/council 域的剩余差距。
