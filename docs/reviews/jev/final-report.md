# Jev Android 接入 — 最终报告

日期：2026-09-17。范围：三阶段全部实施 + 三轮对抗性审查 + 修复回归。

## 实现范围

| 阶段 | 交付 | 状态 |
|---|---|---|
| 基建 | `core/jev` 模块（Client/Coordinator/Budget/Metrics）+ Settings.jev 全链 + SecretStore Key + SettingJevPage（6 语言）+ Koin | 完成，离线验证 |
| Phase 1 | 工具发现语义重排（lazy 目录）+ 记忆召回语义重排（shadow/active/回退） | 完成，离线验证 |
| Phase 2 | 长工具结果块级投影（must-keep/省略标记/conversation_expand 恢复引用；压缩源保持原文） | 完成，离线验证 |
| Phase 3a | 议会默认席次池排序（显式 seats/agent_planned 不覆盖） | 完成，离线验证 |
| Phase 3b | `wm_run_goal` 有界快循环（6 步/15s/3 无进展；DONE 独立核验；unknown 不重放；dry-run/shadow 零执行） | 完成，离线验证 |

## 验证证据（命令与计数）

- `:core:jev:testDebugUnitTest`：19 通过（编解码/缺题/值域/Retry-After/预算/缓存/401 暂停/冷却/超时/**解码失败不重试**）。
- `:feature:tools:api:testDebugUnitTest`：7 通过（语义 5 + 既有 2）。
- `:feature:modelcouncil:testDebugUnitTest`：24 通过（含 parseTask 向后兼容回归）。
- `:app:testDebugUnitTest` 定点：JevProjector 10、WebGoalRunner 7、CouncilRanker 5、MemoryRecallSemantic 4、PreparedContextEditor 4、ChatGenerationRoundEngine 4、MemoryRecallStore/ToolSearch 既有回归全绿。
- `:app:compileDebugKotlin` 成功。合计新增/定点 84+ 用例全绿。

## 三轮对抗性审查（checker）与处置

- **Phase 1**：0 P0；P1-1（解码失败被当暂时性错误重试且误报 NETWORK）**已修**（新增 Decode 终态 + INVALID_RESPONSE，补回归测试）。P2 修复：OkHttp 迟到响应竞态泄漏（resume onCancellation 关闭）、runLocks 无界增长（unlock 后移除）、RouteActivity 整文件 CRLF→LF 污染（已还原 CRLF，diff 回到 3 处 hunk）、语义类别分支漏排除 tool_search 自身、API Key 行 settingTwoLine、DEBUG 构建下 Jev 请求体（记忆文本）日志脱敏（typesafe.ai 全构建不记 body）。不修（记录在案）：>24 条置顶记忆的候选池截断边界、usage 部分缺失严格解码。
- **Phase 2**：0 P0/P1；P2 修复：注释承诺的"工具级失败信号不筛"未实现（补 hasToolFailureSignal，与 PreparedContextEditor 同款模式，补测试）、>1500 字符 bundle 判题盲区（超长包强制保留，补测试）、>32 bundle 静默 TOO_MANY_QUESTIONS 失效（target 加倍重打包直到 ≤32）。不修：run 内 6 次出站与记忆重排共享预算的最坏 ~7s 冷启动延迟（回退安全，记录权衡）；测试过滤器 FQN 勘误（app.amber.core.context.PreparedContextEditorTest）。
- **Phase 3**：0 P0/P1；P2 修复：空白 goal 结构化 handback、pageState 合并窗口状态使 dialog→needs_user_action 生产可达、回执归类顺序（requires_human 先于 unknown）、时长上界动作派发前复检 + extract 超时 12s→6s、议会 gate+rank 包 runCatching（非对象 task 不再抛出）、council 缓存锚改全文（去 32 位哈希碰撞）。不修：`seats: []` 边缘 gate 与 parseSeats 的口径差（保守方向）；测试缺口清单（usesDefaultSeatStrategy 等）留待后续。

## 逐用途启用状态

| 用途 | 模式 | 说明 |
|---|---|---|
| TOOL_DISCOVERY / MEMORY_RECALL / CONTEXT_SELECTION / MODEL_ROUTING / WEB_AUTOMATION | **全部 OFF**（默认） | 需用户配置 Key + 连接测试 + 显式开启；shadow 也外发数据已在设置页声明 |

## 终态复审（第二轮，逻辑+UI 两路 checker）

- **逻辑路**：0 P0 / 1 P1 / 3 P2，全部处置——P1 `ModelCouncilManager` 的 runCatching 吞 CancellationException（取消落在 rank 挂起点会留下 RUNNING 僵尸议会 run）**已修**（CE 显式重抛）；P2 议会缓存锚补 context、wm_run_goal 在 WEB_AUTOMATION=OFF 时不注册（消除"审批一次注定 disabled 的调用"，沿 modelCouncil 设置门控惯例）**已修**；P2 per-run 互斥的 remove 窗口理论双在途**已注释为尽力串行**（实际调用点无同 run 并发，全局信号量+预算兜底）。五用途三态闭环、Koin 无环、runKey 三源归一、取消传播——复核通过。
- **UI 路**：0 P0 / 1 P1 / 5 P2，全部处置——P1 purposes 区自绘 Column 双重水平内边距（组内左缘 24dp vs 12dp 错位）**已修**（改 SettingAgentExecutionPage 的 supportingContent 槽模式）；P2 三档分段标签加 Ellipsis（大字号 ru 截断）、连接行去掉与 trailing 按钮重复的整行 onClick、对话框"清除 Key"移出 dismissButton（破坏性操作不与取消并排）、ru 文案语法、最近结果本地化映射（新增 outcome_* 6 键 ×6 语言）+ 连接测试预算耗尽分支——**均已修**。
- 修复后全量定点重跑：core:jev 19、tools:api 7、modelcouncil 24、app 侧 jev/recall/PreparedContextEditor 全绿，`:app:compileDebugKotlin` 成功。

## 真实验证缺口（不冒充已验证）

- 真实 API：无 Key，blocked。首次真实调用需复核线格式（已按 2026-09-17 官方文档实现）。
- 冻结评估集对照（Recall@5/token 节省/耗时收益）：blocked 同上。
- 真机：弱网 p50/p95、锁屏/后台取消、wm_run_goal 受控页面真实操作：未执行。

## 剩余工作

1. 用户在设置页配置 Key → 连接测试 → shadow 观测指标 → 冻结集对照达标后逐用途开 active。
2. 真机清单（见 phase-3-report）。
3. 提交需与本工作区既有 WIP（记忆系统/气泡/Live）分组隔离；本次改动独立成组。
