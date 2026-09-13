# 终轮集成 Review — 只读复审（Conversation guard / AppContainer / 模型 resolver / Want / DocumentSupport）

日期: 2026-09-13  分支: harmony-preview  基线: 未提交工作树（HEAD bdce551dd + 本轮修复）
范围: 只读复核最终集成；不改任何源码/测试（仅本文件）。签名已 offline 成功；无在线设备，**不声称 UI/真机已验证**。
方法: 逐调用链读源反证；对每个疑点给出文件:行与最小适配建议（不做全局新架构）。

> 增量复核 v2（本轮修复落地后重读 3 文件）：P1-1 / P1-2 已由 owner 修复，见下方「已修复核」；
> 「恢复版本」机制新增，见「有界复核」；其余状态同步。

---

## 结论摘要

- 生成三路径（`runRegenerate`/`resumeToolLoop`/`runSendParts`）+ `applyToolVerdict` + append + `maybeAutoTitle`/`maybeSuggestions` 均已纳入 `ConversationRunGuard`。
- **P1-1 已修**：`runSendEdit` 入口 `reserveConversationRun` + `.finally(releaseRun)`（`ChatPage.ets:2428-2454`）。
- **P1-2 已修（机制）**：恢复成功递增 per-id `restoredVersions`，ChatPage 以 `loadedRestoreVersion` 对比并在 `onPageShow` / `ensureFullConversationForMutation` 前重载；send/edit/regenerate/append 入口均经 `ensureFullConversationForMutation`。
- **P2-1/P2-3 已修**：未知助手/空 UUID 均抛错不写坏数据（`ChatModelSelectionSupport.ets:81,102-113`）。
- **P2-2 明确接受**：未绑定助手写全局，属原计划接受（非 Android `setChatModel` 跟随），非缺陷。
- **P2-4 已加 `await initMcpManager()`**；「是否需 wait helper」结论见下（不需要）。
- **P2-5 已修**：Want 分「无路由字段 preserve / 声明了路由字段但不完整 → invalidate」。
- 有界复核后仍有 **3 个残留 P2/P3**（均在 ChatPage 写路径绕过版本检查），见「有界复核」。

---

## 已修复核（本轮）

### P1-1 runSendEdit guard — 已修，复核通过
`ChatPage.ets:2428-2454`：入口 `reserveConversationRun`，`releaseRun === null` 即返回；`.finally(releaseRun)`。
链上下文：`sendEdit`（`:2419-2425`）已 await `ensureFullConversationForMutation`（内先 `refreshRestoredConversation`），再进 `runSendEdit`。编辑 save 与后续 `regenerate` 均在 guard 持有期内；`regenerate` 内部会再次 `ensureFullConversationForMutation`，同 tick 释放不给用户事件留宏任务窗口。无计数泄漏（guard 有 `released` 幂等位）。

### P1-2 恢复后失效存活会话 — 已修机制，复核见「有界复核」
- `ConversationRunGuard.ets:3-9`：`restoredVersions` + `conversationRestoreVersion(id)` + `markConversationsRestored(ids)`（每 id +1）。
- `SettingBackupPage.ets:283-284`：`await restoreConversations(convs)` **成功返回后**（已提交）才 `markConversationsRestored(ids)`；失败/降级抛错路径不 mark。mark 后仍在 `restoring` 占用内，`finally release()`（`:317`）。
- `ChatPage.ets:1319-1337` `refreshRestoredConversation`：版本不等则 `getById` 重载 + 重建 rows/timeline + 刷新模型态；`onPageShow:758` 与 `ensureFullConversationForMutation:1339-1340` 均调用。
- 入口覆盖：`sendEdit:2419`、`regenerate:1749`、append（`sendWithoutAnswer:3863`、`drainPendingQueue:3746`）、`sendParts:4019`、`deleteMessage:2473`、`switchBranch:1733` 全部经 `ensureFullConversationForMutation`。
- 排序正确性：版本递增在写事务之后，`restoring` 覆盖 mark→release 窗口，故恢复期间 `beginConversationRun` 一律被拒；恢复后重载以版本为唯一触发。`restoredVersions` 为进程内 Map，进程重启后页面本就从 DB 新装，无漏。

---

## 有界复核：恢复版本 / 多页过期（3 文件）

结论：主路径无「前后版本」颠倒；存在 3 处写路径绕过版本重载（P2×2 / P3×1）+ 1 处 UI 态残留（P3）。

### R1（P2）`applyToolVerdict` / `resumeToolLoop` 不查恢复版本
文件：`ChatPage.ets:3519-3548`、`:3552-3664`。
`advanceToolVerdict` 仅在入口 `reserveConversationRun`（`:3523`），该 guard 只在 `restoring` 期间拦截，**恢复 release 之后不再拦**。二者都不走 `ensureFullConversationForMutation`/`refreshRestoredConversation`。若恢复完成 → `onPageShow` 重载尚未 resolve 前用户点了审批，`this.conversation` 仍是恢复前快照，`save(out)`（`:3535`）捕获新代际被放行 → 覆盖恢复结果。`resumeToolLoop` 以 `out` 起跑后续整轮，同理。
部分自保：若恢复数据里无该 toolCallId，`applyToolApprovalToConversation` 返回同值 → 早退并提示（`:3528-3532`）；命中同 id 时才覆盖。
最小适配：`applyToolVerdict` 先 `await this.refreshRestoredConversation()`（把方法改 `async`，或在计算 `out` 前接 `ensureFullConversationForMutation().then(...)`）；`resumeToolLoop` 由调用方保证已 fresh，无需改。

### R2（P2）`saveTitle` 手动改名直写旧快照
文件：`ChatPage.ets:2098-2106`。
直接 `getChatRepository().save(updated)`，既无 guard 也无版本检查；`updated` 由 `this.conversation` patch。恢复后回页、重载未完成时快速改名 → 用旧会话覆盖恢复。
最小适配：保存前比对 `conversationRestoreVersion(updated.id) !== this.loadedRestoreVersion` → 先走重载再保存；或改走 `ensureFullConversationForMutation`。

### R3（P3）抽屉置顶 `onPin` 读-写窗口
文件：`ChatPage.ets:4348-4353`。
`getById(item.id).then(conv => save(patchConversation(conv,...)))`：`getById` 读与 `save` 之间若发生恢复（多页场景，抽屉在 ChatPage 内、设置在另一页不可同时操作，实际窗口极窄），会把恢复前 nodes 写回。`getById` 若在恢复后执行则读到新数据，安全。
最小适配：无需强改；或将 `onPin` 也并入版本重载门。

### R4（P3）`refreshRestoredConversation` 早退/抛错时 `conversationLoaded` 卡 false
文件：`ChatPage.ets:1324,1326-1327`；消费点 `:5008`（`ChatInput.disabled: this.storageDegraded || !this.conversationLoaded`，另 `:5045` 空态 hero 也依赖它）。
`:1324` 先置 `conversationLoaded=false`；`:1326` 若 `conversation?.id !== current.id` 直接 `return`，`:1327` 若 `restored === null` `throw` —— 两路都不会恢复 `true`，输入框可能永久禁用（直到页面重建）。概率窄（需 reload 期间会话 id 变化或恢复后会话缺失）。
最小适配：两路退出前补 `this.conversationLoaded = true`（或 try/finally 恢复）。

### R5（P3）`regenerate` / `switchBranch` 的 `.then` 无 `.catch`
文件：`ChatPage.ets:1747-1753`、`:1730-1743`。
`ensureFullConversationForMutation` 现在会因 `refreshRestoredConversation` 的 `throw`（`:1327`）而确定性 reject；这两处无 `.catch` → unhandled rejection（`sendEdit`/`sendParts`/append 均有 catch）。虽 `getById` 失败本就可 reject（既有），但新增了确定触发点。
最小适配：补 `.catch((e: Error) => { this.errorMsg = e.message; })`。

### 多页过期 — 未发现遗漏
- 每个 ChatPage 实例持有自己的 `loadedRestoreVersion`（`:355`），`aboutToAppear` 以 `conversationRestoreVersion(convId)` 初始化（`:1030`），全局 map 对多实例共享 → 同一会话在两个存活页各自能检测。
- 被 push 覆盖的 ChatPage：`onPageHide`（`pageVisible=false`）但仍 `pageAlive=true`，`loadedRestoreVersion` 停在旧值；返回时 `onPageShow:758` 重载。覆盖期间唯一可能写盘的路径是持有 guard 的 run/aux（有 guard 则恢复会被 `hasActiveConversationRuns` 拒绝），故无「后台页写旧快照」路径。
- 同 id 同时两页（非正常导航，抽屉/新会话均 `replaceUrl`）不在支持范围。
- 缓存态（`drawerConversations`/`rows`）在重载前可能短暂显示旧数据，属展示延迟，非数据覆盖。

---

## Cron — `initMcpManager` 已加；是否需要 wait helper？

结论：**不需要**新增 wait helper；保持现有 `await initMcpManager()` 即可。

依据（读源）：
- 工具**枚举**来自 settings 缓存，不依赖连接：`createMcpServerTools` → `manager.getAllAvailableTools` → `settings.getMcpServers()`（`mcp_manager.ts:233-242`）。`await initMcpManager()` 内 `initMcpSettingsStore()` 已同步 hydrate 缓存（`McpSettingsStore.ets:33-71`），故 `createMcpServerTools` 在 `initMcpManager` 之后立即能拿到持久化的 tool 定义。
- 服务器**连接**本就是懒/后台：`McpManager` 构造函数 `subscribeMcpServers` 立即发初值 → `reconcileConfigs` 经 `deps.launch` fire-and-forget（`mcp_manager.ts:188-196`）；`addClient` 在 `await client.connect` **之前**就 `this.clients.set(config.id, ...)`（`:400-410`）；`callTool` 在 `!client.hasTransport` 时再懒连（`:276`）。因此模型真正发起工具调用时 client entry 必已注册。
- 无公开 ready promise；`syncAll()`（`:522`）只遍历已注册 clients，也不能当「等待 reconcile 完成」用。新增 wait/`whenReady` 属新架构，且 Kotlin 基准同样是 `appScope.launch` fire-and-forget，无此 helper —— 不应引入。

残留（与既有 parity 一致，登记而非本轮改）：连接仍在途时 `callTool` 可能二次 `connect`；理论上首个 reconcile 微任务前到达的调用会看到 `entry===undefined` 返回 "no such mcp client"。二者都需抢在「模型一次网络往返之前」的极小窗口，实际不可达。

---

## 其余状态更新

- **P2-1 已修**：显式 `assistantId` 但助手不存在 → `throw '助手不存在,模型绑定未保存'`（`ChatModelSelectionSupport.ets:102-105`）；锁内发现已删 → `throw`（`:110-113`）。不再静默写全局。
- **P2-3 已修**：`model.id.trim().length === 0` → `resolveSelectableModel` 返回 null → save reject，不写空绑定（`:81`）。
- **P2-2 非缺陷**：未绑定助手写全局为原计划明确接受（注释 `:92,116`），保留分支语义。
- **P2-4 已修**：`AppContainer.ets:484` `await initMcpManager();` 已加在装配 MCP 工具前（且带 `signal?.aborted` 复检）。
- **P2-5 已修**：`EntryAbility.ets:120-146` 三类语义——完整目标落参+seq++；声明了路由字段但不完整 → `clearPendingWantRoute()`（seq=0）作废旧 pending；完全无路由字段 → preserve，不吞冷启动挂起目标。

---

## 仍为无问题（点名项，复核不变）

1. provider `enabled` 实校（`hasUsableAuth` 首行 `!setting.enabled`）；`ProviderModel` 无 `enabled` 字段（`provider_settings.ts:23-36` + Android `Model.kt:8-21`），注释指 provider 级，准确。
2. resolver 绑定 internalID 与菜单/Picker 读侧同源（`AppContainer.ets:1052-1095`；`ChatPage.ets:2925-2934,5040-5056`；`ChatModelPickerPage.ets:71-79`）。
3. DocumentSupport loader 失败全部 error 闭合（`DocumentSupport.ets:162-222`；域层 `document_transformer.ts:83-105`；ChatPage `:2217-2225`）。
4. UI-F44 能力门真门控（`AgentMessageRow.ets:62,107-108,435`；`NovelWorkspacePage.ets:68` false）。
5. data: 文档发送缺陷属既有 `statFile` 仅 file:// 旁支，已登记未扩范围。

---

## 未验证 / 设备项（不声称通过）

- 恢复版本重载的实际时序（返回页面后 `onPageShow` 重载是否先于用户可点操作）需模拟器手测：编辑/重命名/工具审批 × 恢复。
- F44 data:/file:// 预览成功路径、DOCX/PPTX/EPUB 真实解析、chip 手势是否被父级编辑手势抢走。
- Want 前台任意栈顶点击通知的 `onNewWant` 即时性、Ability 上下文 `pushUrl` reject 语义。
- Cron 冷启动首次无头运行的 MCP 工具可用性（日志目测）。

---

## 建议处理顺序（最小改动，均本文件内单点）

1. R1 `applyToolVerdict` 补恢复版本重载（最高，工具审批是恢复后正常可达路径）。
2. R2 `saveTitle` 保存前版本比对。
3. R5 两处补 `.catch`；R4 `conversationLoaded` 早退恢复。
4. R3 抽屉置顶窗口（可仅登记）。
