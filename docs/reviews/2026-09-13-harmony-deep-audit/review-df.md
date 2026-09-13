# 独立反证复核：专题 D（cron/subagent/授权）与专题 F（WebMount 安全）

复核者：独立 agent（非 D/F owner）。
基线：`harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，当前工作树。
方法：亲读源与调用上下文；用真实 `.ts` 逻辑 + 内存假端口构造隔离 probe，不复制业务实现；F 的 `.ets` 工具层用 TypeScript 转译 + `vm` 假 session 调用真实 `createWebMountTools`。全程未联网、未开浏览器、未读 cookie/storage、未调模型、未改设备或业务代码。
本人新增证据（均在本目录，未覆盖他人文件）：
- `probes-df/probe-f-independent.cjs`（19 断言，exit 0）
- `probes-df/probe-d-independent.ts`（18 断言，exit 0，`cd harmony/chat && npx tsx <路径>`）

复核结论总览：

| 编号 | 原判 | 本复核 | 一句话 |
|---|---|---|---|
| F-01 | P1 高 | **确认（边界缺陷成立）/ 端到端待验证** | 真实工具注入汇成立；受 F-03 与不可编译遮蔽，不得称已泄露 |
| F-02 | P1 高 | **确认（精确边界）/ 端到端待验证** | userinfo+冒号截断绕过成立；另发现不限定 scheme |
| F-03 | P1 高 | **确认** | 确定性代码证明 + probe；是 F-01/F-02 的遮蔽主因 |
| D-01 | P1（并入 F-01） | **确认** | wm_click 审批档位确为移植降级（Android 为 mandatory） |
| D-02 | P2 高 | **确认** | 真实双重执行 + runCount 丢失更新 |
| D-03 | P2 高 | **确认** | cron 执行体确为无工具单轮，静默成功 |
| D-04 | P2 高 | **确认核心 / 降级一子命题** | 真实可达且 cancel 无效；但“占并发额度”为**假子命题** |
| D-05 | P2 高 | **降级（近似假阳性）** | run_trust 是死分支，但 Android 唯一填充点是未移植的 screen 工具，对已移植工具无行为回归 |
| D-06 | P2 中 | **待产品确认（pending）** | 契约确认且有界，双端同构；非漏洞，属产品选择 |

---

## F-01 wm_click 参数逃逸 → 任意脚本（含关闭 wm_eval）

**结论：确认（“真实工具逻辑成立”）；端到端利用未验证。定级 P1 潜伏（blocked），置信度高。**

证据链（全部亲读，行号为当前工作树 1-based）：
- `harmony/entry/src/main/ets/platform_impl/WebMountTools.ets:120-143`。`wm_click` 仅 `needsApproval: true`（:131 前的协议位），无 `allowsAutoApproval:false / mandatoryApproval:true`；对照 `wm_eval` :197-206 明确带 `allowsAutoApproval:false / mandatoryApproval:true`。
- 注入点精确为 **error 文案的单引号字符串插值**：`:135`
  `if (el === null) return { clicked: false, error: 'element not found: ${target}' };`
  同一 `target` 在 `querySelector(${JSON.stringify(selector)})`（:134）被正确 JSON 编码，但 :135 是第二处、未编码的插值点；`evalJson` 把它包进可执行 IIFE 后经 `runJavaScript` 落到页面 origin（`WebMountSession.ets:181-187`）。
- `createWebMountTools(..., includeEval=false)` 只在返回数组里移除 `wm_eval`（`WebMountTools.ets:455-459`），**不移除 wm_click**。
- 授权链：`tool_policy.ts:473-479` 给 wm_click `mutates=true / risk=sensitive / autoApprovable=tool.allowsAutoApproval`；类目 `webmount`（:188）不在 `FAIL_CLOSED_AUTO_APPROVAL_CATEGORIES`（:63-65），且 `riskExplicit=true` 使 `requiresFailClosedAutoApproval` 返回 false（:312-315）→ `autoApprovable` 保持 true；`tool_permission.ts:256-258` 单开 `autoApproveTools` 即 `allow/settings`。

独立验证（`probe-f-independent.cjs`，全部 PASS）：
- `F01a`：`includeEval=false` 后 `wm_eval` 确不存在。
- `F01b`：三种裸单引号载荷（`...x'+(window.__p1=1)+'...` 等）在 eval 关闭下真实写入 `window`；**负例** `F01b-neg`（反斜杠转义的引号）不逃逸 —— 证明这是未编码插值边界，而非“任意字符串都注入”。
- `F01c`：`wm_type` 的 selector/text 均经 `JSON.stringify`，无可注入点（反证同类问题仅 wm_click 有）。
- `F01d`：`wm_click.execute` **无 `isReady()` 门**，session 未就绪也会调用 `evalJson`；对照 `F01d2` `wm_extract` 有 `no page loaded` 门。这使 click 路径在“刚挂载、仍是 about:blank”时也会尝试执行，扩大落点不确定性（需设备确认 about:blank 的 `runJavaScript` 行为）。

F-01 vs 端到端利用的边界（不夸大）：
- 成立：**工具执行边界缺陷**真实存在于当前源码，且可经单开自动批准静默触发（D 的 P1b/P1d 与本人 F01 一致）。
- 不成立/未验证：攻击者需先让 `evalJson` 有一次可执行页面。当前 `wm_open` 因 **F-03 恒失败**，会话从未 `loadUrl`，因此**没有设备端“打开恶意页 → 执行 → 外泄”的实证**；且当前工作树 `BUILD FAILED`（12 error，见 `build.log`），无法端到端运行。故只能表述为“潜伏已证实的边界缺陷”，不得表述为已发生泄露。D 与 F 报告此点克制，予以确认。
- 修复不得只做 F-03：挂载修好后 wm_click 仍是注入汇。最小修复：:135 改为 `JSON.stringify('element not found: ' + target)`（或整段只经 JSON 参数编码）；同时给 wm_click 补 `allowsAutoApproval:false / mandatoryApproval:true`（= D-01，与 Android `WebMountInteractionTools.kt:94-96` 对齐）。回归：对含单/双引号、反斜杠、换行、CSS escape 的 target 断言不产生额外表达式，且 `includeEval=false`。

---

## F-02 站点白名单把 URL userinfo 当 hostname

**结论：确认（精确边界更窄，但实务可利用）；端到端未验证。P1 潜伏，置信度高。另扩展：不限定 scheme。**

- 位置：`WebMountSites.ets:73-78`（`hostOf`）、`:81-90`（`siteAllowsUrl`）；消费点 `WebMountTools.ets:87-92`（wm_open 只此一道门，通过后原样 `session.open(url)`）。
- 机制精确定位：`hostOf` 用 `^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)` 取 authority，然后 `split(':')[0]`。因此只有当 userinfo 段**含冒号**时，冒号前的 `example.com` 才被当 host；**无冒号的 userinfo（`https://example.com@outside.invalid/`）会被拒**。这比“把 userinfo 一律当 host”更窄。
- 独立验证（`probe-f-independent.cjs`，PASS）：`F02 gate(userinfo with colon)=true`、`gate(empty password `example.com:@outside.invalid`)=true`、`gate(numeric port-like)=true`；`F02b` 标准 `new URL(...).hostname` 为 `outside.invalid`，gate 却给出 `example.com`；`gate(userinfo WITHOUT colon)=false`（负例）。授权站点用合成 `example.com`，载荷为合成 `u:p` 形状，未用真实凭据。
- 实务影响仍成立：URL 由模型/攻击者自选，插入一个冒号即可（`https://example.com:x@evil.invalid/`），故不是“需特定形态”，而是可稳定构造。
- 扩展发现（本人，未在 F 报告）：`hostOf` 的正则不限定 scheme。实测 `javascript://example.com/...` 与 `file://example.com/...` 经 gate 均返回 **true**。即 gate 只做 host 匹配、不要求 `http(s)`。`session.open` 直接 `controller.loadUrl(url)`，ArkWeb 对各 scheme 的处理未知，属待设备验证的同类边界（F 修复建议“限定 http(s)”正确）。
- 未见任何逐导航/重定向再授权：全仓 `WebMountSession.ets`/ChatPage 无 `onLoadIntercept`/`onInterceptRequest`/`onUrlLoadIntercept`（grep 仅命中 `onPageEnd`）。且 `WebMountHost.onPageEnd`（`WebMountSession.ets:235-236`）用 `session.getUrl()`（请求 URL）而非事件真实 URL → 重定向后元数据仍是旧地址。与 F 报告的“同根因扩展”一致。
- 端到端未验证：同 F-03 遮蔽；`loadUrl` 对 userinfo/`javascript:` 的真实行为需设备确认。不夸大。

---

## F-03 WebMount 从未标记宿主已挂载，首次打开恒失败

**结论：确认（确定性代码证明 + 隔离 probe，exit 0）。P1，高置信度。**

- 状态机：`hostMounted` 初值 false（`WebMountSession.ets:36`）；唯一 setter `markHostMounted()`（:109-111）**全仓只有定义、无调用**（`grep -rn markHostMounted harmony/*/src` 仅命中定义处）。`open`（:139-147）先调 `onMountNeeded`，再 50×100ms 轮询等待 `hostMounted`，超时抛 `WebMount host not mounted`，`loadUrl`（:151）在其后，永不执行。
- 宿主侧：`ChatPage.ets:875-877` 的 `onMountNeeded` 只把 `webmountHostVisible=true`；`WebMountHost.aboutToAppear()`（`WebMountSession.ets:225-227`）只 `attachUiContext`，`onPageEnd`（:235-236）只 `markPageEnd`，都不置 `hostMounted`。
- 独立/preexisting 验证：`probe-f-mount.cjs` 转译真实 class（仅略去 TS 不能解析的 ArkUI struct 尾），加速假时钟：mounts=1、ticks=50、loads=0、按预期 reject，exit 0。本人未改写该文件，独立重跑 exit 0。
- 修正 F 报告两处副点：(a) `waitForPageEnd` 超时分支用 `indexOf(resolve)` 删除但实际推入的是 `wrapped`（`WebMountSession.ets:123-135`），超时 waiter **确实泄漏**，属真实小缺陷；(b) “initial about:blank 可 mark ready” 未在设备验证 —— `Web({src:'about:blank'})` 是否对初始 blank 触发一次 `onPageEnd` 取决于 ArkWeb 实现，本人**无法离线证实**，故 F-01 攻击前提中的“about:blank 就绪”仍属待验证。请勿据此声称利用。
- 影响与优先：这是用户可见的功能阻断（WebMount 核心旅程不可用），同时是 F-01/F-02 攻击链的**前置遮蔽**。修复后才谈 F-01/F-02 的可利用性。

---

## D-01 wm_click 审批档位（并入 F-01）

**结论：确认（移植降级）。** Android `app/src/main/java/app/amber/feature/webmount/tools/WebMountInteractionTools.kt:94-96` 对 `wm_click` 设有 `allowsAutoApproval=false, mandatoryApproval=true`；Harmony `WebMountTools.ets:120-143` 缺失，`makeAgentTool` 回落 `allowsAutoApproval=true / mandatoryApproval=false`（`tool.ts:62-63`）。已由 preexisting probe P1a/P1b/P1d、D 的 P1 与本人 F01 交叉确认。**它不是双端同构，是移植偏差。**

---

## D-02 cron 无运行锁：重叠触发导致双重执行 + runCount 丢失

**结论：确认。P2，高置信度。前提真实且已接线。**

- 源码：`AgentCronStore.ets:186-204`（`fire` 无“在跑”判定；IIFE 异步，`runNow`/定时器都不等待）；`:207-214`（`schedule` 只 `clearTimer` 待触发定时器，不能取消在跑的 `fire`）；`agent_cron.ts:456-495`（`prepareTriggeredRun` 只过滤 `!manual && !enabled`，不查 `lastStatus==='Running'`，重算并置 `Queued`）；`:505-520`（`markRunStarted` 无状态门）；`:583-593`（`replaceTasks` 读-改-写无互斥）。
- 前提链真实：`AGENT_CRON_EXECUTION_AVAILABLE=true`（`AgentCronStore.ets:48`）、执行器已接线（`AppContainer.ets:500`）；用户可在 `SettingCronTasksPage.ets:129` 点“立即运行”触发 `runTaskNow`（`agent_cron.ts:446-453`）；而一次 cron 运行含一次完整模型调用（`AppContainer.ets:482`），时间窗远大于点击间隔。
- 独立验证（`probe-d-independent.ts`）：`D02a` 首个 Running 未结束时第二次 `prepareTriggeredRun` 仍返回非 null；`D02b` `runCount` 递增到 2（两次执行被排入）；`D02c` 状态在首个仍运行时回落 `Queued`；`D02d` 并发两次 prepare → 两次执行但 `runCount` 只 +1（lost update）。
- 反证（不成立）：Android 用 `WorkManager.enqueueUniqueWork(name, REPLACE)` 互斥（`AgentCronWorker.kt` 注释明确“同 workName REPLACE 会取消在跑”），Harmony 的 `setTimeout` 只能取消待触发。非双端同构，是移植缺口。
- 历史：round3 曾以“执行器未接线”判假阳性豁免；该前提已消失（AVAILABLE=true 且接线），重新成立。

---

## D-03 cron 执行体是无工具单轮，静默成功

**结论：确认。P2，高置信度。**

- `AppContainer.ets:441-491`：`createEntryCronRunExecutor` 组 `ChatTurnDeps`（:474-482）时未传任何 tools/loop，直接 `runChatTurn(conversation, task.prompt, deps)`（:482）。`runChatTurn`（`chat_turn.ts:177`）签名无 `ToolLoopOptions`，是单次生成；`runChatTurnWithTools` 才走工具循环（`tool_loop.ts:383`）。
- Android 对照：`AgentCronWorker.doWork` 调 `ChatService.sendMessage`（完整工具循环 + 审批 + 取消），并加“这是 AmberAgent 手机端 Cron 定时任务触发…”前缀（`AgentCronWorker.kt:44-63`）。Harmony 两者皆无。
- 独立验证（`probe-d-independent.ts`）：`D03a` entry 只调 `runChatTurn`、无 `runChatTurnWithTools`；`D03b` entry 执行体不构造 `ToolLoopOptions`；`D03c` `runChatTurn` 无 loop 参数；`D03d` 真正含 notifier 的 `runCronTaskOnce`（`agent_cron_run_executor.ts`）存在但 entry 从不引用（死代码）。
- 静默性确认：`markRunCompleted` 照常置 `Succeeded`（`agent_cron.ts:526-540`），用户看不到“无工具”降级。另 `ChatTurnDeps` 还缺 `inputTransformers`/memory/`prepareContextMessages`，即连上下文压缩与记忆块也未接入，降级比 F 报告所述更彻底（属同一根因，不另计）。
- cron 工具确实对模型暴露（`ChatPage.ets:917` `getAgentCronTools`），因此“模型可创建定时任务但任务运行时不带工具”这一闭环落差真实。

---

## D-04 subagent `approval_required` → 任务永久 RUNNING、cancel 无效

**结论：核心确认；**“占并发额度”子命题降级为假。**P2，高置信度。**

- 路径：`subagent_runner.ts:376-386`（子循环末条消息存在 `approvalState=pending` 的工具 → 返回 `status:'approval_required'`）；`subagent_manager.ts:207-214`（映射 `RUNNING`）；`:593-608`（`finish` 以 `!subAgentRunStatusRunning(current.status)` 为门直接 return null，`subAgentRunStatusRunning` 仅 `status==='running'`，`subagent_models.ts:58-59`）；`:445-454`（`cancel` 先 abort 再 `finish`，对非 running 无效）；`:735-762`（`cancelCapability = subAgentRunStatusRunning(...)` → false）。
- 独立验证（`probe-d-independent.ts`，用**真实 `tool_loop` + 真实 `GenerationSubAgentRunner` + 真实 `SubAgentManager`**，仅 provider 为脚本化假流、工具为合成 `screen_click` 常量返回，零副作用）：
  - `D04b` 真实闭环产出 `approval_required`（可达，非仅 mock 单函数）。
  - `D04c` 任务映射 `RUNNING` 且 `cancelCapability=false`。
  - `D04d` `cancel()` 后 run/task 仍 `approval_required`/`RUNNING`（no-op）。
  - `D04e` `wait()` 无恢复通道。
  - `D04f` 对照：同类 runner 下**只读工具正常完成**（证明触发来自审批门，而非 runner 卡死）。
  - `D04g` 即便 `autoApproveAllToolCalls=true`，subagent 上下文的敏感类目仍恒 `ask`（`tool_permission.ts:217-232`），故默认配置即可命中该状态。
- **降级子命题**：D 报告称“占并发额度（admissionMutex 计数，:347-352）”。实际 admission 计数 `subAgentRunStatusRunning(candidate.status)`（`subagent_manager.ts:350`），而该判定只认真 `running`，`approval_required` **不计入**；`runtimeSummary` 的 `running` 同样排除。因此 approval_required **不消耗并发槽**，该子命题为假（属夸大，应删）。真实危害仍是：任务板永久 `RUNNING`、无任何恢复/取消通道、无 UI 审批续跑入口。
- 双端同构确认：Android `SubAgentManager.kt:311-334`（finish 同 running 门）、`:396`（APPROVAL_REQUIRED→RUNNING）、`SubAgentRunner.kt:116-124`（同返回）。故属“双端同构的产品语义缺陷”，非移植偏差 —— 仍应修，但不是 Harmony 独有。
- 最小修复：`finish` 允许 `approval_required` 终态化（如 `INTERRUPTED`）或持久化为可恢复态并提供父侧 approve 续跑；至少让 `cancel()` 对 approval_required 生效并释放/不占用额度。回归：真实 loop 返回 approval_required → cancel 后 `cancelled/CANCELLED`。

---

## D-05 `autoApprovedToolNames`（run-trust）未接线

**结论：降级（近似假阳性）。**死分支确认，但“行为缺失”对已移植工具不成立。P3，中置信度。**

- 事实确认：`tool_permission.ts:249-252` 的 `run_trust` 分支存在；`tool_loop.ts:70-71/168` 有选项且默认 `[]`；`entry` 全仓无 Chat 工具循环写入点（`grep autoApprovedToolNames entry/src/main/ets` 仅命中 `AppContainer.ets:260` 的 DeepRead 客户端与 `OpenAiCompatibleAiClient.ets`，与 Chat 循环无关）；`ChatPage.ets` 的 `buildToolLoop`（:2929-3151）从不设置该字段。故该分支在生产对 Chat 主循环不可达（死代码）。
- 关键反证（D 报告未展开）：Android 唯一填充点 `ChatService.kt:1142` 是 `if (approved && approvedToolName in screenSessionTrustTools()) { trustedRunToolNames[conversationId] = screenSessionTrustTools() }`，而 `screenSessionTrustTools()`（:1514-1524）只含 9 个 `screen_*` 工具。即 Android 的 run-trust **仅为 screen 工具在单轮内免重复弹审批**设计，并非通用“已批准工具不再问”的用户承诺。
- 结论：Harmony 未移植 `screen_*`（已在 `ChatPage.ets:2940` 登记为未移植项），因此 run-trust 未接线对**任何已移植工具都没有行为回归**；它只是“未移植功能的伴生死代码”。D 报告“已批准工具每步重复弹审批”的表述会误导读者以为存在通用用户承诺 —— 应降级为“死分支登记”，不列为独立活跃缺陷。若产品确实想要通用 run-trust，那是新需求，而非移植缺失。

---

## D-06 subagent history grant 由模型自供 scope 自动铸造

**结论：待产品确认（pending）。契约确认、有界；非漏洞。P2 残余风险，中置信度。**

- 铸造链：`subagent_manager.ts:295-320`（`isHistoryReader && task.sourceSessionIds.length>0` → `sessionAccessGrantStore.create(task.sourceSessionIds, outputBudgetChars*4, ...)`；`isHistoryReader` 定义 `:201-205`：id==='historian' 或 allowlist 命中 `session_read/session_expand`）。`source_session_ids` 是 `subagent_start` 的**工具入参**（`subagent_tools.ts:268-272`），模型可自填。
- 消费者：`tool_permission.ts:217-228` 在 `invocationContext==='subagent'` 且 `grant_id` 非空时对 `session_read/session_expand` 预批准；工具实现 `builtin_session_tools.ts:275-360` 对 `grant_id` 做 `grantStore.validate`，grant 边界 = TTL≤30min、maxChars≤120k、sessionIds≤24（`session_grant_store.ts:31-33,50-83`）。
- 独立验证（`probe-d-independent.ts`）：`D06a` 模型自供 `source_session_ids` 即自动铸造（无用户提示）；`D06b` grant scope 与模型入参一致；`D06c` 边界确为有界（≤120k / ≤24 / TTL≤30min）。
- 反证/边界：非 history reader 不铸造；admission 拒绝或初始化失败会 `revoke`（`subagent_manager.ts:368-373/400-403`）；grant 内存态、单次 start 内有效。Android `SubAgentManager.kt:94-108` 逐字同构。故**不是无界授权**，是刻意的有界设计。
- 补充（支持 pending 而非缺陷）：父会话本身**无条件**暴露 `session_read/session_expand`（`ChatPage.ets:3035` `createConversationHistoryTools`，不在 `localTools` 门内），模型在主会话即可读历史（经常规审批，`session_read` 为 sensitive/`needsApproval` 但 `autoApprovable=true`，单开全局自动批准即预批准）。因此授予 historian 的并非“新能力”，只是免去逐次确认。是否要用户逐次签发 grant 属产品选择，非安全漏洞。维持 pending。

---

## 其他反证/死角（勿重复报告）

- **D-07**（`agent_cron_run_executor.ts:39-99` `fireDueCronTasks` 无 claim/不清 due）：本人确认当前 entry 不引用该导出（`grep` 无调用），属死代码潜伏，无现网影响；接线前须补原子 claim。维持登记，不计缺陷。
- **D-08**（`prepareTriggeredRun` 不校验 due）：当前控制流只在定时器到期或手动 runNow 调用，366 天窗口内不可达；降级 P3 正确。
- **F 的 `onPageEnd` 用 `session.getUrl()`**：确认无逐导航/重定向核验，作为 F-02 扩展；但无设备证据证明重定向后的实际行为，仍是待验证。
- **F-03 副点 waiter 泄漏**：`WebMountSession.ets:123-135` 确认（`indexOf(resolve)` 与推入的 `wrapped` 不匹配），挂载修复后需覆盖。
- **工作树不可编译**：`build.log` 末 `COMPILE RESULT:FAIL {ERROR:12 WARN:502}`，错误集中在 `SettingExperimentalWebMountPage.ets`（arkts-no-obj-literals-as-types 等）与 `SyncCryptoPort.ets`/`WebMountOAuthSupport.ets`（cryptoFramework API 名不匹配）。这不是 F-01/F-02 的反证，但它与 F-03 共同构成“端到端利用未验证”的必要说明。

## 待验证/盲区

- 无在线设备（`hdc list targets` 空）：`loadUrl` 对 userinfo、`javascript:`/`file:` scheme、重定向的真实处理；`Web({src:'about:blank'})` 是否触发初始 `onPageEnd`（决定 F-01 在挂载后是否仍受 pageReady 影响）。均需设备。
- 未能编译，故无法跑 entry 层端到端；D-02/D-03 的 entry 侧结论为静态调用链 + chat 层隔离 probe。
- 未实测 cron `setTimeout` 在前后台/低电量下的漂移与暂停（属既有 P1 登记）。

## 建议给主报告的处理

- F-01、F-02：保留为 **P1 潜伏（blocked by F-03 + 不可编译）**，措辞限定为“工具/门禁边界缺陷已证实，端到端利用未验证”；不降为纯待验证（代码缺陷客观存在），但明确标注前置遮蔽。F-03 单列为 P1 功能阻断。
- D-01 与 F-01 合并计数，不重复。
- D-04 采纳但**删除“占并发额度”子命题**。
- D-05 降级为 P3 死分支登记（并说明 Android 仅为 screen 工具设计），不作为独立缺陷计入。
- D-06 列为“待产品确认”，非缺陷。
- D-02、D-03 维持 P2 确认。
