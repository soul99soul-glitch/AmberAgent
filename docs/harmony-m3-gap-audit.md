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
| 错误恢复 | 🟡 偏差 open | PD-006：retry/vision fallback/工具审批未落地 |

## 3. 已知偏差汇总（PARITY_DEBT.md 为准）

- **open（7 条）**：PD-001 空 prefetch 降级（deepread，待 G1 确认）；PD-002 prefetch 预算 15s vs 36s；PD-003 targetSdk 定档；PD-004 时间戳 UTC+Z vs LocalDateTime；PD-005 JS RegExp vs Java Pattern；PD-006 生成错误恢复缺失；PD-007 图片不压缩 + data: URL 直存消息体。
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
| ~~生成错误恢复（retry/vision fallback）~~ **retry 已完成(D-050)** | GenerationRetry.kt + GenerationHandler.kt:774-820 | classifier+循环+状态文案忠实;残余:vision fallback(image→text transformer 链依赖)/生成守卫 = P1(PD-006 更新) |
| 工具执行 + 审批循环（执行核心 + **循环集成+审批闭环已完成(D-056/D-057)**） | Tool.kt + ToolRegistry.kt 策略 + PermissionDecisionResolver.kt + AgentToolDispatcher.kt + ToolInvocationHooks.kt + ToolFailure.kt + AgentLoopBudgetPrompt.kt + GenerationHandler.kt:161-391 + ChatService.kt:869-975/:1122-1190 + TimeTool.kt | 模型/策略/权限链/调度/预算提示 + agentic 循环(同消息写回/预算 dynamic 块/steer 消费)+ 审批 UI+续跑+idle 阻塞+「继续」+ 分支再生工具化 + 首个内置工具 get_time_info 端到端完成;残余:内置工具批量(D-058)/autoApprove 设置面/SpeculativeToolRunner/ToolExposureState 懒暴露/工具卡片完整视觉 = P1 |
| ~~FOLLOWUP 发送排队~~ **已完成(D-048)**；~~answer=false 发送路径~~ **已完成(D-051)**；~~队列持久化~~ **已完成(D-052)** | ChatService.kt:687-867；ConversationSession.kt:110-232；ChatInput.kt:272；PendingMessageStore.kt | 队列纯逻辑+chips UI+循环派发完成;长按发送=只记录不生成(STEER/FOLLOWUP 逐字忠实)+停止时 STEER→FOLLOWUP 完成;KV 持久化+进程死亡恢复(launchPendingMessageLoopIfNeeded 逐字)+审计 jsonl 完成;残余:STEER 进行中注入 = P1 |
| ~~ContextEngine + compact（上下文压缩）~~ **已完成(D-054 纯逻辑 + D-055 引擎执行+集成)** | ConversationContextEngine.kt(806 行) + PreparedContextEditor.kt + CompressPrompt.kt + ChatService invalidateCompacts/copyValidCompacts | prepareContext 管线(编辑→规划→force/precompact→注入→fit)+compactConversation(质量重试+fallback)+四失效钩子+fork 复制+KV 存储完成;残余:compact 设置面/compress 任务模型/ContextUsageIndicator 环/repository search-expand = P1 |
| 图片压缩 + file:// 私有目录存储 | FileEncoder.kt compressAndEncode | PD-007；PixelMap 管线 |
| ~~Google 请求构建~~ **已完成(D-045)** | ai/ GoogleProvider.kt(949 行) | Claude(D-044)/Google(D-045)均完成;**entry 运行时接线已完成(D-046)**;entry 运行时接线(D-046)与三变体设置编辑 UI(D-047)均完成;残余:BuiltInTools/Vertex service account/Code Assist OAuth 传输/authMode-brand 编辑面 = P1 |
| 动态 system prompt blocks（time/date/lorebook/mode 注入） | Assistant.kt modeInjectionIds/lorebookIds | 依赖 lorebook/mode 存储 |
| ~~customHeaders/customBodies 编辑 UI~~ **已完成(D-049)** | AssistantRequestPage.kt:78-95 + PropertyEditor.kt | 助手级编辑+运行时注入完成;model 级自定义参数编辑面 = P1(数据已被 mergeCustomParams 消费) |
| memory（全局/助手级） | AssistantVM.kt:89-94 getMemories | 依赖 memory 存储设计 |
| ~~reasoning 折叠/展开交互~~ **已完成(D-053)** | ChatMessageReasoning.kt(452 行) + MarkdownUtils.kt:35-55 | 三态卡片+流式迁移+秒数头部+尾窗截断完成;残余:budgetLabel 后缀/title 右侧 duration/shimmer+Markdown 富渲染/100dp 滚动窗 = P1(Spike C 视觉) |

## 6. 结论

M3（Chat MVP）source-only 阶段 **完成**，进入设备验证等待态（G-C blocked_by_human）。
source-only 可做的 chat 主线功能面已基本穷尽；后续 source-only 切片转向：
P1 清单中无设备依赖的纯逻辑项（~~FOLLOWUP 排队~~ **已完成 D-048**、~~Google/Claude 请求构建~~ **已完成 D-044/D-045/D-046**、
~~customHeaders/customBodies 编辑~~ **已完成 D-049**、~~google/claude 变体设置编辑 UI~~ **已完成 D-047**）与 deepread/novel/council 域的剩余差距。
