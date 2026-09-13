# 合并报告终审质量复核（read-only）

日期：2026-09-13
复核对象：`docs/reviews/2026-09-13-harmony-consolidated-review.md`、`docs/reviews/2026-09-13-harmony-merged-evidence/ui-adjudication.json`、`ui-risk-recheck.md` / `ui-interaction-recheck.md` / `ui-visual-recheck.md`，以及 F20/X01/F43 探针与日志。
方法：只读。交叉核对编号去向与计数；对 F20/F30/F35/X01/R11 亲读源；重跑并比对 F20/X01 探针（内存 mock，无设备/网络/真实文件）；校验全部相对链接；抓取 `未验证当确认` / `建议当bug` 两方向。
边界：未改业务、原报告、UI 报告；未全仓再审；未提交；未连设备。本文件只新增，不覆盖任何原文件。

## 结论

**合并结构、编号去向与计数全部闭合，未发现编号丢失、重复或错挂；未发现"未验证当确认"的升级，也未发现把确认故障当建议回退的风险。** 命中 5 处应在交付前修的问题，集中在 **证据文件与合并裁决的状态自相矛盾**、**§5/§6/§7 表格被空行截断**、**UI 锚点路径不完整**、**R11 降级依据不完整**、**F30 保留 P1 的依据未随收窄重述**。均为文档层问题，不涉及业务代码。

### 已核对的通过项

- **44+F16a+X01 共 46 个编号全部有去向**：§7 表 43 行覆盖 46 id（F16/F16a、F38/F40、F41/F42 合一），无缺号、无重号；去向与 §3/§5/§6 及 `ui-adjudication.json` 一致。
- **计数自洽**：§3 实际 10 P1 + 27 P2 + 11 P3 = 48；其中 UI 合并条目 22 条（F16a/F40/F42/F16 并入）= P1 2（F20/F30）+ P2 14 + P3 6，深审 26 = 8 P1 + 13 P2 + 5 P3（R11 由 P2 移入 P3，R21 移入待验）；48 = 26 + 22，另 L01/L02 两项独立不计入，与"48=26+22（10/27/11）+2 latent"完全吻合。`ui-adjudication.json` 22 confirmed（P1 2/P2 14/P3 6）+ 10 runtime + 9 debt + 2 merged = 43 条目 ↔ 46 id，自洽。
- **相对链接无失效**：consolidated 108 个相对链接、visual-recheck 2 个链接全部解析到真实文件，无指向错误。recheck 三份使用行内代码路径而非链接，不产生断链。
- **R11 死 drawer 事实正确**：`ChatPage.ets:2704` `openDrawer` 全仓唯一出现即定义，零调用者；`drawerOpen` 置 true 仅 L2713（在 `openDrawer` 内）；`deleteDrawerConversation` L2737-2753 直接 `getChatRepository().delete` 无 cleanup。抽屉删除路径确不可达，撤回其"当前可达泄漏"主张正确。活跃面（compact/events 残留）核实成立：`cleanupDeletedConversation`（`ManagedFileStore.ets:208-227`）只做 deleteChatFiles + clearPendingQueueStorage + removeFavoritesOfConversation，`EntryCompactStore.deleteByConversation`（L53）仅被 `invalidateCompacts`（`ChatPage.ets:2410`）调用，删除入口从不调用。
- **F35 锁缺失为真**：`kv_store.ts` `saveAssistants`/`loadAssistants`（L59/L63-67）直接 put/get，无 `mutateAssistants`（全仓 0 命中）；`KeyedAsyncMutex`/`sharedKvMutex` 只用于 `mode_injections`/`lorebooks`/`agent_runtime`，不覆盖 `assistants` 键。`ChatAssistantExtensionsPage.saveAssistant` 以旧的 `this.assistant` 算 next，await 后才回写，快速连点覆盖成立。
- **X01 早退为真**：`DeepReadArticlePage.loadArticle` L129-188，`isComplete` 命中的 return 在 L137-140，显示设置读取在 L144-150 之后；重跑探针输出与保存日志逐字一致（`loadArticle sha256/16: 5b79c94353aeac4c`，T1 KV 读取 `[]`、T2 读取四键）。
- **F20 为真且探针可信**：亲读 `SettingFilesPage.reload`(L111-128)/`cleanOrphans`(L157-195)；重跑探针与日志一致（`cleanOrphans sha256/16: 8fb8c79007222e6b`），T1/T2/T3 三类空快照均删除仍被引用的 mock 文件，T4 健康库负例保留，T5 证明引用源仅 `this.rows`。提取方式为 TS AST 原样抽方法（`struct`→`class` 等长替换、偏移不变、provenance 断言），仅 mock 平台边界与内存 FS，方法可信。
- **F30 无并发夸大**：`aboutToDisappear` 不 abort、页面无 `scheduler.isRunning(` 调用、`loadArticle` 仅完整缓存短路，REPLACE 段在 `withAdmissionLock` 内、执行在 `withTopicLock` 内仍串行；报告明确"不声称进度清零或锁内并发写"，与原报告旧措辞的收窄一致。
- **F39/F43 合并正确**：F43 与 R07 同文件同 setter 零调用＝同一条；F39 主消费者问题与 R23 同根，两处均保留来源映射、未新增计数。

## 需要修订的问题（最少必要）

### MQ1（高，审计链自相矛盾）证据文件的"确认 bug"标签与合并降级不一致

三份 recheck 是被 §3/§5/§6 每行引用的"验证及来源"，但它们自身对以下项标注为**确认 bug**，而合并报告/`ui-adjudication.json` 已按 merge-policy 改判为建议或运行时：

| 编号 | recheck 原文判定 | 合并后状态 | 位置 |
|---|---|---|---|
| F18 | 确认 bug · P3 | debt（§6） | ui-interaction-recheck.md L91、L264 |
| F22 | 确认 bug（不可逆删除无保护）· P2 | debt（§6） | ui-interaction-recheck.md L125、L268 |
| F26 | 确认 bug · P2 | debt（§6） | ui-interaction-recheck.md L154、L271 |
| F27 | 确认 P2 | runtime（§5，且无 severity） | ui-risk-recheck.md L19、L86 |
| F28 | 确认 P2 | debt（§6） | ui-risk-recheck.md L20、L105-117 |
| F33 | 确认 bug · P2 | debt（§6） | ui-interaction-recheck.md L204、L275 |

后果：读者按 §6 的来源链接打开证据，会看到与合并结论相反的定级，等于交付物内部同时存在"建议"与"bug"两种定性。

**精确改法（二选一）**
1. 在三份 recheck 的汇总表各加一列 `合并处置`，逐行填 `§6 建议（merge-policy：无额外确认/审美，不计 Bug）` 或 `§5 运行时（副作用/设备待验）`；并在各条正文判定行后追加同句。
2. 或在 consolidated §6 表每行说明列补一句：`（来源复核判为确认 bug/F27；本报告按 merge-policy 降为建议 / 归运行时，定级以本节为准）`。

最小落地：改 2 更难、改 1 更完整。至少覆盖上表 6 项，避免同一编号两种定性。

### MQ2（中，主表不可读）§5/§6/§7 表格被空行截断

`consolidated-review.md` 的表格数据行之间夹了空行，在 GitHub/GFM 下空行会终止表格，`全部UI来源编号去向` 将渲染成散落的段落行而非表格。

- §5：起始 L430，需删空行 **L432-450**（每行一行）。
- §6：起始 L456，需删空行 **L458-476**。
- §7：起始 L480，需删空行 **L482-568**（45 行）。
- §8 覆盖表（L587-595）正常，仅表后保留 1 空行即可。

**精确改法**：仅删除相邻两个 `|...|` 数据行之间的空行（表头/分隔行下方那一个空行 L432/L458/L482 也要删），保留整张表结束后的空行。不重排、不改内容。

### MQ3（中，来源路径错误/不完整）UI 锚点用统一占位路径，4 处文件不在其覆盖范围

§3 所有 UI 条目的"源码锚点"写成 `（路径在 harmony/entry/src/main/ets/pages/ 或 components/ ...）`，但被引用文件中以下**不在** pages/ 或 components/：

- `ManagedFileStore.ets` → `harmony/entry/src/main/ets/platform_impl/`（F20）
- `DeepReadTemplate.ets` → `harmony/entry/src/main/ets/platform_impl/`（F29）
- `AppContainer.ets` → `harmony/entry/src/main/ets/di/`（F34）
- `kv_store.ts` → `harmony/chat/src/main/ets/chat/`（F35）
- `scheduler.ts` / `run_manager.ts` → `harmony/deepread/src/main/ets/agent/`（F30）

其余（Setting*Page.ets、ChatMessage*.ets、AgentMessageRow.ets、ContentAttachmentChip.ets、AgentToolCapsule.ets 等）确实在 pages/ 或 components/。

**精确改法**：把统一占位说明换成逐条全路径，或保留占位但在 §7 表后追加"文件名→相对路径"附录（至少列出上述 5 个域层文件）；不改行号。

### MQ4（中，降级依据不完整）R11 P2→P3 的理由与保留面不匹配

死 drawer 的纠正本身正确（见通过项），但它拆除的是 R11 的**死路径子命题**；合并后保留的"所有可达删除漏清理 compact/events"才是原始 P2 的根因与可达面。该残留对**每个**可达删除入口（ChatListPage L248/L267、ChatAssistantsPage L331、AssistantListPage L131）都成立，符合本报告自定的 P2 门槛"可达且成立"。

**精确改法**：二选一——(a) R11 回到 P2（死路径仅作说明，不影响可达残留定级）；或 (b) 保持 P3 但补一句明确的降级理由，例如"compact 残留删除后无任何读取回链（`getCompacts` 仅在会话存续期消费），纯孤儿 KV，无外泄/功能路径，故 P3"。当前 §3 R11 只写了"残留数据、不声称外部泄露"，未给出与 P2 门槛对齐的说明。

### MQ5（低，定级依据未随收窄重述）F30 保留 P1 但理由只剩"隐式新轮 + REPLACE"

F30 原 P1 部分建立在已撤回的"5s 超时后两轮并发写/进度清零"之上。收窄后正文只剩"重进不查 isRunning、新 run 触发 REPLACE、取消意图不持久化"，却仍标 P1，且未对抗本报告 P1 定义（"真数据损坏或高成本核心流程整块不可用"）说明依据。

**精确改法**：在 F30"影响/前提"补一句保留 P1 的理由（重进触发一次完整 deepread 全流程的真实模型成本 + REPLACE 直接 abort 丢弃在途轮次），或按剩余影响改判 P2。并发夸大已消除，无需再动正文其他部分。

### MQ6（低，判定口径未写明）"确认"与"建议"的分界没有落纸

merge-policy 只说"不机械称 Bug"，但未给出可复用的判据。结果是 F22/F28（不可逆删除无二次确认）→ 建议，而 F23（空名称保存静默 no-op、按钮仍可点）→ 确认 Bug P3、F36（显式"暂不可用"项无反馈）→ 建议。三者本质都是"控件无反馈/无保护"，却被分入不同桶，单看报告无法复现该判断。

**精确改法**：在 §6 表头或 merge-policy 补一句判据，如"控件**显式标注不可用/需用户已完成明确手势**者计建议；按钮外观可点但静默无效、使用户误判成功者计 P3 Bug"，并按此复核 F23 归类是否随之统一。不改变计数即可。

## 复核范围与未做

- 未逐条重算 26 项深审（沿用会话 2 指纹一致的结论），仅抽验其与 UI 合并相关的计数/引用；未重跑 build、全测、lint。
- 未连设备/模拟器；所有"待设备"项维持原文，未升级为实测。
- F20/X01 探针为复核目的重跑一次（内存 mock，无副作用），`.probe-tmp/` 为本目录内既有临时产物。
- 未修改 `2026-09-13-harmony-consolidated-review.md`、三份 recheck、`ui-adjudication.json`、`2026-09-13-harmony-ui-ux-motion-review.md` 及任何业务源码。
