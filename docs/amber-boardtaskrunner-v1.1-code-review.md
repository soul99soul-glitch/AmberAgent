# Code Review：BoardTaskRunner v1.1（已落地实现）

> reviewer：资深 Android/Kotlin 架构 + Agent 安全
> 方法：所有判断对照实读源码核对，引用真实行号。范围限定本轮 runner 相关文件 + 安全上下文。
> 结论先行，原因+最小修复随后。

---

## 1. Verdict：**Approve with fixes**

核心安全姿态成立、且经我实读验证是**真的**生效，不是纸面声明：

- ✅ **工具面是真白名单，不是黑名单兜底**。`GenerationHandler.generateText` 的模型工具集来自 `ToolExposureState.from(tools)` → `toolsForStep()`（`GenerationHandler.kt:159,183`），**不 merge 全局 LocalTools**。所以 runner 传入的 8 个工具就是模型能看到的全部。这条是整个方案的地基，它真的牢。
- ✅ **`board_task_record` 终态越权已封死**——runner 用独立包装工具 `boardTaskRecordTool`（`BoardTaskRunner.kt:277-324`），`done/dismissed/cancelled` 走 `action !in BOARD_TASK_RECORD_ALLOWED_ACTIONS` 直接 deny（`:296`）。聊天面的原 `taskRecordTool` 保持不动。两个实例分离，正确。
- ✅ **模型事件不能扩大飞书范围**——`boardTaskAllowedDocScopeParts` 只取 evidence + `USER_REPLIED` 事件（`:450-457`），模型写的 PROGRESS/WAITING_USER/BLOCKED 不进 scope。有测试覆盖（`allowed_doc_scope_ignores_model_written_events`）。这是上一轮我最担心的注入面，**已正确堵死**。
- ✅ `streamOutput=false` 确实关掉投机执行（`GenerationHandler.kt:187` 要求 `&& assistant.streamOutput`）。

但有 **2 个 High（一个真漏洞、一个真卡死）** 和几个 Medium 必须修，故不是直接 Approve。

---

## 2. Critical / High findings

### H1 —— `feishu_docs_resolve` 未被 doc-guard，是 allowlist 里的范围旁路
**文件**：`BoardTaskRunner.kt:263-272`（`buildSafeTools` 的 `when`），`FeishuDocsTools.kt:55-72`（resolve 实现）

**问题**：`buildSafeTools` 只对 `feishu_docs_read/blocks/markdown_pack` 套 `guardFeishuDocRead`；`feishu_docs_resolve` 落进 `else` 分支**原样透传**（`:271`）。而 resolve 接受任意 `url`/`document_id` 并返回 `doc_ref/document_id/url`（`FeishuDocsTools.kt:65-71`），**不校验是否在 allowedDocs 内**。

**为什么会出事**：v1 的安全契约是"飞书读取只允许 evidence/用户回复里的文档"。resolve 虽不返回正文，但它对**任意** doc 做存在性确认 + 规范化 token 解析——这本身是越界的元数据读取（确认某文档是否存在、拿到其 canonical id），属于范围外飞书访问。它也让 prompt 注入多一个"先 resolve 看看"的探测点。read 仍会拦正文，所以不是正文泄漏，但**违反了"只接触 allowed 文档"这条边界**，且 `describeForPrompt` 把 allowed 集合喂给了模型，模型完全可以拿集合外的 URL 去 resolve。

**最小修复**：把 `feishu_docs_resolve` 也纳入 `guardFeishuDocRead` 的同一 `when` 分支（与 read/blocks/markdown_pack 并列），用同一套 `allowedDocs.allows(input)` 校验。一行改动：

```kotlin
"feishu_docs_resolve",
"feishu_docs_read",
"feishu_docs_blocks",
"feishu_docs_markdown_pack" -> rawTools[name]?.guardFeishuDocRead(allowedDocs) ?: unavailableTool(name)
```

（注意：resolve 的 guard 也要能处理"只传 url、未传 document_id"的输入——`allows()` 已覆盖 url 分支，`:474-479`，所以现成可用。）

### H2 —— runner 运行中到达的 USER_REPLY / CONTINUE 被静默吞掉，任务“卡在等待”
**文件**：`AgentNotificationActionReceiver.kt:64-68, 88-99`；`BoardTaskRunner.kt:112-127`；`BoardViewModel.kt:121-133`

**问题**：`start()` 在已有 job 时返回 `false`（`:113`），但**所有调用方都忽略返回值**。USER_REPLY 路径先 `recordUserReply`（写事件 + 置 IN_PROGRESS）再 `runner.start(USER_REPLY)`（`receiver:95-97`）。若此时上一轮 runner 仍在跑：
- `start` 返回 false，不开新轮；
- 当前在跑的 runner 早已在 `runTask` 开头一次性算好 `allowedDocs` 和 prompt（`BoardTaskRunner.kt:153-178`），**看不到这条新 reply**；
- 它结束时把任务又置回 `waiting_user`（`:217-218`）。

**为什么会出事**：用户在通知里补了"继续，但别写回飞书"，看到的结果是任务**又回到等待确认**、自己的指令像没生效。虽然该 reply 作为事件留存、下一次手动触发的 run 会通过 `recentEvents` 读到（不是永久丢失），但**当轮无响应**=用户感知为"卡死/不理我"。这是主动智能体验里最伤信任的一类 bug。

**最小修复**（二选一，建议 a）：
- (a) `start()` 返回 false 时，置一个 per-task 的 `rerunRequested` 标志；当前 job 在 `invokeOnCompletion`（`:122-124`）里检查该标志，若置位则自动再 `start(USER_REPLY)`。约 10 行。
- (b) 退而求其次：调用方在 `start` 返回 false 时，给用户一条明确通知"上一轮还在进行，您的补充已记录，将在本轮结束后处理"，避免"静默无响应"。

### （记录，非新问题）autoApproveTools=true 的语义已被白名单收敛
`BoardTaskRunner.kt:206` 传 `autoApproveTools = true` + `autoApprovedToolNames = 安全集`。我核对了上一轮担心的 `PermissionDecisionResolver.kt:178` in-run 直放——这里它**不再是漏洞**：因为工具面已被白名单限定为 8 个只读/记账工具，且 `autoApproveHighRiskTools=false` 仍拦 High。doc-scope 由 execute 层 guard 兜底（即便 auto-approve 也先跑 guard）。**结论：当前组合安全**。但见 M1：这层耦合很脆，值得加一条断言测试钉住。

---

## 3. Medium findings

**M1 — 白名单安全依赖"generateText 不 merge 全局工具"这一隐含前提，但无测试钉住。** 一旦将来有人在 `generateInternal` 里改成"补充默认工具"，runner 的白名单会被悄悄击穿，且没有任何测试会红。建议加一个集成向测试：用 runner 的 tools 调 generateText（fake provider 请求一个不在白名单的工具名），断言模型拿不到该工具 / 调用被 DENY。

**M2 — `cancel()` 与运行中 job 的状态竞态。** `cancel()`（`BoardTaskRunner.kt:129-137`）先 `job.cancel()` 再在另一协程 `taskRepository.cancel` 置 DISMISSED；而被取消的 job 可能正好走到 `:216-220` 把状态写成 `waiting_user`。窗口小、影响低（最终态可能短暂错乱），但建议 cancel 时也设一个 tombstone，让 runTask 收尾前检查 `isActive`/任务是否已 DISMISSED 再写状态。

**M3 — BLOCKED 不在 `terminal` 集合，re-dispatch 行为要确认是有意的。** `BoardTaskState.terminal = {DONE, DISMISSED}`（`BoardTaskEntity.kt:16`），BLOCKED 属 `rolling`。`runTask` 开头 `if (task.state in terminal) return`（`:144`）放行 BLOCKED 重跑——这对 RETRY 合理，但要确认产品上"blocked 任务被 CONTINUE/DISPATCH 重新触发"是预期，否则一个永久 blocked（如"不支持的类型"）会被反复重启。建议：`playbook==null` 这类**结构性 blocked** 用 `dismissed` 或加一个 `blocked_permanent` 标记，避免无效重跑。

**M4 — `dependency_stale` 未限定只用 confirmed 锚点。** 上一轮已指出：`BoardOpportunityTools` 会自动建 proposed 陈述锚点。dependency_stale 的 playbook prompt（`BoardTaskRunner.kt:89-93`）只说"基于漂移证据"，没约束证据只来自 confirmed 锚点。若 evidence 里混入 proposed 锚点，会放大误报。建议在生成 dependency_stale 的 Opportunity evidence 时就过滤 confirmed（这在 scanner 侧，不在 runner，但要确认）。

**M5 — `search_web`/`scrape_web` 无每任务调用预算。** 网络出口在白名单内、autoApprove=true，受 `maxToolIterations`(10/12) 间接限制，但没有独立的 `maxWebCalls`。注入证据可诱导模型多次外联把 evidence 内容带出去。`maxToolIterations` 是软约束（一轮可多工具）。建议加一个轻量计数器，超额返回 deny。非阻塞，但 v1 该有。

---

## 4. Security boundary audit（逐条）

| 边界 | 结论 | 证据 |
|---|---|---|
| 工具暴露面是否真安全 | **是**，白名单为唯一来源，不 merge 全局 | `GenerationHandler.kt:159,183,253`；`buildSafeTools:258-275` |
| PermissionDecisionResolver / autoApprovedToolNames 还能绕过吗 | 在此配置下**不能**——白名单已限面 + High 仍拦 + doc-scope execute 层兜底 | `BoardTaskRunner.kt:206-207`；`PermissionDecisionResolver.kt:175,178` |
| Feishu allowed docs 能被注入/模型事件扩大吗 | **不能**（模型事件被过滤）；**但 resolve 未 guard = 范围旁路** | scope：`:450-457` + 测试；漏洞：H1 |
| board_task_record 还能越权改终态吗 | **不能** | `BoardTaskRunner.kt:296`；测试 `board_task_record_model_actions_are_lifecycle_limited` |
| streamOutput=false 是否真避免 speculative | **是** | `GenerationHandler.kt:187` |

净评：5 条边界里 4 条牢、1 条有 H1 的旁路。修了 H1 即闭合。

---

## 5. Product flow audit

闭环主路径成立：`dispatchOpportunity → createDispatched(IN_PROGRESS) → runner.start(DISPATCH) → board_task_record(progress) + notifyTask → 正常结束转 waiting_user → notifyBoardTask("等待用户确认") → 通知 CONTINUE/CANCEL/INLINE_REPLY`（`BoardViewModel:128-132`、`BoardTaskRunner:168-220`、`receiver:64-99`）。

发现的体验问题：
- **P-High = H2**：运行中收到的 reply/continue 被吞，用户感知卡死。最伤主动体验，必修。
- **P-Med**：`playbook==null` → blocked "暂不支持该任务类型"（`:147-150`），但若该 blocked 任务能被 CONTINUE 反复重启（M3），用户会反复收到同一条无效通知。
- **通知不误导**：state→content 映射正确（`defaultLiveContent`），waiting_user/blocked/in_progress 文案分明 ✅。
- **无 UI 跳转强制**：dispatch 不跳聊天，符合预期 ✅；VIEW 才打开 session（`receiver:59-61`）。

---

## 6. Minimal patch recommendations（只列必要）

1. **H1**：`feishu_docs_resolve` 并入 `guardFeishuDocRead` 分支（`BoardTaskRunner.kt:266`）。**1 行。**
2. **H2**：`start()` 返回 false 时记 `rerunRequested`，`invokeOnCompletion` 里消费并自动重跑一轮（`BoardTaskRunner.kt:112-127`）。**~10 行。**
3. **M3**：结构性 blocked（不支持类型）改走 dismissed 或加永久标记，避免无效重启。**~3 行。**
4. **M5**：runner 内加 per-task `maxWebCalls` 计数，超额 deny。**~10 行。**

M1/M2/M4 建议做但不阻塞合并；M2 可排到下一轮。**不建议**引入 CapabilityRegistry / step 表 / 通用权限框架——当前白名单 + execute 层 guard 的组合对 v1 足够，加抽象是过度工程。

---

## 7. Tests to add（最少但关键）

1. **H1 回归**：`feishu_docs_resolve(url=集合外URL)` 返回 `document_scope_denied`，不调底层 resolve。
2. **H1 正路**：evidence 内文档的 resolve 正常返回。
3. **M1 钉边界**：用 runner 的 8 工具构造 generateText 的工具集，断言一个不在白名单的工具名（如 `feishu_docs_append_block`）在该 run 内不可见 / 被 DENY——防止将来有人改 `generateInternal` 击穿白名单。
4. **H2 行为**：runner 运行中再次 `start(USER_REPLY)` → 当前实现返回 false（先用测试钉住现状），修复后改为断言"完成后自动重跑一次且新 run 的 allowedDocs 含新 reply 里的文档"。
5. **doc-scope + resolve 链**：用户 reply 里补一个新飞书 URL → 该 URL 的 resolve+read 均放行；evidence/ reply 之外的 URL 全 deny。
6. **terminal 防重**：DONE/DISMISSED 任务 `start()` 后 runTask 直接 return，不写新事件（`:144`）。

---

## 8. 待你拍板

1. H1（resolve 并入 guard）+ H2（reply 不被吞）这两条我建议本轮必修后再合并——是否同意？
2. M3 的"结构性 blocked 反复重启"——你倾向改 dismissed，还是加 `blocked_permanent` 标记？
3. 这 4 份 review 文档（含本篇）都还没 git 提交。要不要我开个分支一并提交（需你授权 commit；在 main 上我会先 branch）。
