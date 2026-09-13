# 终审质量复核（read-only）

- 复核对象：`findings.md`、`adjudication.md`、`coverage-final.md`、`validation.md` 及必要源码/探针证据。
- 基线：分支 `harmony-preview`，HEAD `bdce551`，工作树 52 个跟踪文件已修改（与 `baseline.txt` 一致）。本次只读，未改主报告、未改业务代码，仅新增本文件。
- 方法：对 27 项 finding 的行号锚点与关键前提做源码抽验；独立重跑 R05/R10/R07/H10 相关探针；对"用户承诺"类前提核 UI 文案与设置面归属。

## 结论

**总体通过。** 未发现覆盖虚报、批量错行号或 P0/P1 级别的严重误述；27 项计数自洽（现 27 = 7 P1 + 16 P2 + 4 P3，另 L01/L02 潜伏），行号抽验均命中，建议无危险动作。有 1 处需要修正的**证据标签错误**（会影响 R05 的因果表述）、1 处**前提偏弱**（R21）、1 处**建议收窄**（R10 定级）、1 处**描述不精确**（R18 google），逐项列在下方。

## PASS（已独立核验）

- **计数与结构**：R01–R27 共 27 项；P1×7（R01–R07）、P2×16（R08–R23）、P3×4（R24–R27）；L01/L02 单列不混计。README/pending 链接后结构自洽。
- **行号抽验**：R01（build.log 锚点 21/28/32/90 + 38 + 154/157/182 与源码逐条吻合）、R02（sync_snapshot:181-190 / conversation_export:133-150 / SettingBackupPage:216-234）、R03（sync_snapshot:277-302 强转无 validator / SettingBackupPage:233 逐条 save、254-256 只显示错误）、R04（`chatStorageDegraded` 全仓仅 178/182 写，确无 UI 消费者；`save` 283-300）、R09（`deletedIds.delete` 仅在 509 失败分支，成功删除确实永久留墓碑）、R11（`EntryCompactStore.deleteByConversation` 仅定义无调用方；`ChatPage` 抽屉 2727-2752 只 delete）、R13（`di/AppContainer.ets:441-491` 不传 tools，482 `runChatTurn`）、R17/R16（`AgentCronStore` clamp 2147483647 在 211；`cancelCapability:false` 在 agent_cron:743）、R23（`EntryAbility:84-116` 只写 AppStorage，消费仅 `ChatListPage:68,81,85-111`）、R25（`agent_task_tools:219` 描述 vs `agent_task:755-770` 无 status 门）、R24（`article_plan:31-34,258-263` fallback；`evidence_pack` cardsFor 全局池补满）、R26（`SettingAppearancePage:37-45` 无 await/catch，与 47-56 对照）、L01/L02（`WebMountTools:120-143` 135 行未转义插值；`WebMountSites:73-88` `split(':')[0]` + 不限定 scheme）。均命中。
- **构建/测试证据**：`build.log` 实测 `COMPILE RESULT:FAIL {ERROR:12 WARN:502}` 且列出 11 条带路径诊断——报告"计数差不解释、不推断隐藏错误"的处理正确；`tests-chat.log` 2097 + `tests-deepread.log` 775 = 2872，与 coverage 一致；`hdc list targets` 空、lint 工具内部错误、devecocli MODULE_NOT_FOUND 均如实登记，无冒充通过。
- **覆盖地图**：`main_pages.json` 实测 74 条注册、75 个页面源文件，Index 未注册，与 coverage-final 一致；"完成≠逐行审遍/≠设备通过"的免责声明到位。
- **H10 真实证据**：`probes-xag/probe-h10-real.mjs` 确为**转译执行真实 `NotificationNotifier.ets`**（仅 mock `@kit.*`，queue 控制平台落地顺序），重跑输出证实两种落地顺序结果都出现；findings R22"手写转写 probe 不作证据、不说每次必消失"表述准确。
- **R21 接线事实**：`SearchRegistry.MultiProviderRegistry` 确实无条件 push NewsNow/DDG/HN/Jina（337-382），`AppContainer:129` 不注入启用集合；聊天侧 `search_orchestrator:178-190` 消费 `searchBuiltin*Enabled` 形成对照，接线差异属实。
- **R27 代码链**：`runSendEdit`（ChatPage:2301-2333）确无 operationToken/pageAlive 门，await 后仍调 `regenerate(nodeId)`；`runRegenerate` 新建 controller（1687）不受离页 abort 约束。代码链静态成立，报告以"设备时序未测"限定影响（无意后台生成）恰当中立，**无需改 pending**。

## ISSUES（需修订）

### I1（重要）R05 探针标签错误，导致"真实取消丢 user"的因果表述不成立
- `probes-b/probe_b3_chain.mjs` 第 4 例 `run('named AbortError (genuine user stop)', ...)` 及 `review-b.md:72`「named AbortError（真实停止）」**并未传入/触发 `abortSignal`**——它测的是"抛 AbortError 但 signal 未 abort"，不是真实用户 Stop 链。真实 Stop 会走 `ChatPage.requestStop → kernel.cancel() + abortController.abort()`（:1108-1117），`deps.abortSignal` 已 aborted。
- 独立实证（真实 `createChatKernelHost` + `runChatTurnWithTools`，仅 provider 抛错）：
  - `AbortError` + **signal 未 abort**（即 probe 实际场景）→ kernel `cancelled` 返回入参会话 → 页面终态保存后 `nodes=1`，user 丢失。**该路径成立**。
  - `AbortError` + **signal 已 abort**（真实 Stop）→ `chat_turn` catch 内 `isAborted()` 命中 break 吞掉（chat_turn.ts:358/385），正常返回带 user+部分 assistant 的会话 → kernel `completed`，`nodes=2`，**user 不丢**。
- 因此 findings R05 主前提（非 AbortError 但 message 含 `abort`；以及抛 AbortError 而用户未 abort）**确认无误**；但 R05 修复建议中"**取消**也不能返回早于已持久化 user 的快照"与"真实取消发生在 user 落盘后的回归"会被读成"真实 Stop 会丢 user"，**不成立**。协调者收尾的"真实取消同样旧会话回退"结论亦因此不准确。
- 精确修订：保留 R05 主证据；删除/改写"真实取消丢 user"的暗示，把回归项表述为"provider 在用户未取消时抛 name=AbortError 或 message 含 abort → 断言 user 保留"；如需覆盖真实 Stop，应断言**保留** user（当前通过），不是修复目标。同时更正 probe 第 4 例标签为"AbortError without signal abort"。

### I2（中）R21"用户的内置搜索源开关"承诺偏弱，应降前提
- `searchBuiltin*Enabled` 开关位于 `ChatSearchSettingsPage`（页面标题"搜索设置"），其**主开关行文案明确写"允许聊天调用 search_web 和 scrape_web，使用已启用服务"**（:426-428），作用域指向聊天工具；深度阅读另有自己的设置面 `SettingDeepReadPage`（`active_search_source` + NewsNow 热榜源开关），并不提供这六项内置源的开关。
- 故"深读不消费用户开关"里"用户开关"并非一个对深读明确承诺过的设置；更准确的根因是**同一设置面/两套搜索配置未统一**（SearchRegistry 硬编码内置源，chat 走 search_prefs）。建议将 R21 前提改写为"内置源开关只在聊天搜索面暴露，deepread 的 SearchRegistry 未共享该策略"，并按"用户承诺不足"考虑降至 P3，或明确保留 P2 时补一句"开关文案未显式覆盖 deepread"。计数不变。

### I3（中）R10 上调 P1 属定级判断，证据成立但需加触发/可恢复限定
- 证据面通过：重跑 `probes-e/probe-e-io.cjs` 复现三例——一次 `readText` 异常后 `addMemory` 只剩 gamma、`insert` 只剩 upload/c.txt、KV 收藏 JSON 损坏后只剩 new-1，调用均"成功"返回；源码 `MemoryStore.readJsonArray:39-46` IO catch 直接 `return []`（**不经 quarantine**，只有解析损坏才走二次确认隔离），随后 read-modify-write 以空基覆盖；`FavoriteStore.decode` 解析失败返回 `[]`。属不可逆整份丢失。
- 与 R04 同类（静默降级→数据损失）且更直接（R04 尚有回灌窗口），上调 P1 与既有分级一致，可接受。但两点限定建议写入 findings：触发需"一次瞬时读失败/损坏 **且** 随后发生一次写"，属低概率；损坏变体在收藏侧依赖 KV 非原子写。
- 计数：上调后 8 P1 / 15 P2 / 4 P3 = 27，与现状总数一致，无重复计数。

### I4（轻）R18 对 google 的描述不精确
- `google_chat_api.ts` 的 `state` 只有 `{ error }`（:134），**没有 `done` 标志**，也没有协议终止帧处理分支——它是"完全没有终态收口"而非"只置 done/error 回调 return"。findings R18 把 google 与另三家并列成"只置done/error回调return"不准确。建议改为"google 连 done 标志都没有，终止帧仅靠流自然结束"。不影响结论与定级。

### I5（轻）R01 行号区间可对齐
- findings R01 写 `SyncCryptoPort.ets:21,28,31-32,90`，build.log 精确锚点为 `32:26`（`CipherSpec`）；31 行是 `key:` 参数行。区间无害，但若要求逐条对应可写成 `28-32`。非错误，可不改。

## 未发现的问题（明确通过项）

- 未发现"推测当确认"的因果：R13（无工具循环）、R12（无 in-flight 锁）、R16（clamp 后 fire 不复核 due）、R25（cleanup 无 status 门）均为源码直证；R08/R15 明确限定"非合规 Provider/主动发话"前提，无泛化。
- 未发现不安全建议：各"最小修复/回归"均为校验、事务、token 贯穿、显式审批等收敛方向，无删除安全门/放宽权限类反向建议；L01/L02 明确要求"R07/R01 修复前必须同步修"，未建议先解锁挂载。
- 潜伏项分级克制：L01/L02 均声明"源码级证实、端到端受阻（R07/R01 遮蔽）、未称已泄露 Cookie/外网"，与本机无法构建/无设备的实际一致。
- 无覆盖虚报：明确列出未跑区域（设备 UI、真机 lint、真实 Provider/WebDAV/OAuth、压力），未把静态证据冒充设备验证。

## 复跑记录

- `node probes-e/probe-e-io.cjs` → 三例 CONFIRMED，输出与 findings R10 一致。
- `node probes-xag/probe-h10-real.mjs` → 两种 publish/cancel 落地顺序均复现，证实未 await、同 id 互相覆盖。
- `node probe-f-mount.cjs` → 50 tick 后 reject、loadUrl 0 次，R07 证据成立。
- 自建独立 stop-chain 对照（真实 kernel+tool_loop，signal aborted vs 未 aborted）→ 见 I1。
