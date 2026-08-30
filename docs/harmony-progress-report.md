# AmberAgent 鸿蒙迁移 — 历史工作进度详报（已停止维护）

> 本文冻结于 2026-07-29，只用于追溯当时的 source-only 迁移阶段；其中 Gate、设备、测试数、
> HEAD 和后续优先级均不得作为当前结论。当前状态见 2026-08-29/30 的完成计划与 workflow results。

> 更新时间：2026-07-29  
> 分支：`harmony-preview`  
> 仓库 HEAD：`14c9e2cd4`（D-132b state-pointer）  
> 最新 feature：`1db56d3c3`（D-132b subagent runner 与 manager）  
> 治理来源：`MIGRATION_STATE.yaml` + `DECISION_LOG.md` + `PARITY_DEBT.md`

---

## 0. 当前结论

D-131、D-132a、D-132b 均已完成、通过 source-only 门禁并按 feature/state-pointer 双提交落库。Subagent 已具备纯域协议、隔离 Runner 和 Manager 生命周期；尚未具备五件 public tools、entry 文件/设置水合 adapter 和 ChatPage 装配，这些属于下一片 D-132c。

设备端验证仍为 0：`hdc list targets` 为空，Gate 0 继续 `blocked_by_human`。该阻塞不影响 source-only 迁移继续推进。

不要用一个笼统百分比掩盖实际状态：

- 工程骨架、Chat MVP source-only、provider/tool/runtime 大量基础设施已落地；
- D-132 subagent 已完成 a/b 两个分片，c 分片尚未完成；
- Terminal/SystemAccess/WebView/WorkScheduler 等平台型大工具族仍未迁移；
- 全部设备端验证仍未开始。

---

## 1. Git 与治理状态

| 项 | 当前值 |
|---|---|
| 分支 | `harmony-preview` |
| 仓库 HEAD | `14c9e2cd4` state-pointer |
| `MIGRATION_STATE.yaml` feature head | `1db56d3c3` |
| 最新 feature | `feat(agent): D-132b subagent runner 与 manager` |
| DECISION_LOG | 已记录至 D-132b |
| PARITY_DEBT | PD-001..PD-011；10 open，PD-008 closed |
| 新增 D-132b debt | 无 |

当前业务工作树已收口。剩余两份用户原有治理文档是 untracked：

```text
?? docs/harmony-handoff-prompt.md
?? docs/harmony-progress-report.md
```

它们没有混入 D-132b feature/state 提交。

---

## 2. 里程碑状态

| 里程碑 | 状态 | 说明 |
|---|---|---|
| M0 工具链 | 部分完成 | CLI 构建已验证；无 hdc device target |
| M1 三个 P0 spike | 阻塞 | SSE / RDB / ArkUI+Markdown 均待设备 |
| M2 工程骨架 | 完成 | entry HAP + chat/deepread 双 HAR |
| M3 Chat MVP | source-only 完成 | `docs/harmony-m3-gap-audit.md` 6/6；设备验证待 G-D |
| M4+ P1 能力面 | 进行中 | 当前下一片 D-132c；其后仍有多个大工具族 |

### Gate 0

```text
status: blocked
reason: hdc list targets 为空
human action: DevEco Device Manager 下载/启动模拟器，或接入真机
```

设备解锁后的首轮验证顺序：SSE → RDB/Preferences → ArkUI/Markdown → picker/image → WorkScheduler/notification/background。

---

## 3. 最新质量门

D-132b 完成后的新鲜结果：

| 门禁 | 结果 |
|---|---|
| D-132b focused | 93/93 pass |
| chat HAR 单测 | 1542/1542 pass |
| chat TypeScript | `npx tsc --noEmit` clean |
| deepread HAR 单测 | 610/610 pass |
| deepread TypeScript | `npx tsc --noEmit` clean |
| HAR 构建 | `hvigorw assembleHar --no-daemon` success |
| HAP 构建 | `hvigorw assembleHap --no-daemon` success |
| diff 格式 | `git diff --check` clean |
| 阶段审查 | 最终 `APPROVED` |

测试总数：`1542 + 610 = 2152`。

构建仍输出仓库既有 ArkTS warnings，但命令成功退出，没有 D-132b 编译错误。

---

## 4. 最近完成的里程碑

### 4.1 D-131 — AgentTaskTools 六件 + Scheduler 装配

```text
94f89d897 feat(agent): D-131 AgentTaskTools 六件 + Scheduler 装配
60ed0fd0a chore(state): head 指针更新 → 94f89d897(D-131 AgentTaskTools)
```

交付：AgentTaskScheduler、`agent_task_list/read/cancel/retry/cleanup`、`agent_runtime_status`、output existence 异步 file port、AppContainer 单例和 ChatPage 无条件装配。

### 4.2 D-132a — Subagent 纯域基础

```text
c8cb1875a feat(agent): D-132a subagent 纯域基础
b8a0ae6a6 chore(state): head 指针更新 → c8cb1875a(D-132a subagent)
```

交付文件：

```text
subagent_models.ts
subagent_validator.ts
subagent_report_tool.ts
subagent_tool_scope.ts
subagent_transcript.ts
```

主要行为：

- TaskSpec/Result/Run/default constructors；
- context 6000 上限、raw tool-result marker 拒绝；
- built-in/custom/override/smart-dynamic resolution；
- UUID、Kotlin Float32、Int32/Int64、stable hash/name 语义；
- `subagent_report` schema 和截断；
- child-only ToolRegistry/discovery；
- transcript JSONL、canonical containment、regular file、256 KiB tail、UTF-8 和 Kotlin JsonPrimitive 语义。

明确没有加入通用 codec/decoder、metadata table、普通对象键序契约或无消费者 fallback。

### 4.3 D-132b — Isolated Runner + Manager

```text
1db56d3c3 feat(agent): D-132b subagent runner 与 manager
14c9e2cd4 chore(state): head 指针更新 → 1db56d3c3(D-132b subagent)
```

新增：

```text
harmony/chat/src/main/ets/chat/subagent_runner.ts
harmony/chat/src/main/ets/chat/subagent_manager.ts
harmony/chat/src/test/subagent_runner.test.ts
harmony/chat/src/test/subagent_manager.test.ts
```

修改 shared tool loop、session defaults、barrel 和 AppContainer 主生成参数。

#### Runner 已完成

- 共享既有 tool-loop core，不建立第二套循环或 dispatcher；
- main public path 继续 16..512 clamp，child 支持精确 1/2/4 step；
- isolated Assistant 禁用 memory/presets/regex/MCP/local/mode/lorebook/skills/time reminder/speculation；
- 保留 parent topP/maxTokens/custom headers/custom bodies；
- definition temperature/reasoning override；
- scoped tools + 内部 `subagent_report`；
- model override → stale fallback current model；
- model/assistant 缺失错误分离；
- inherited generation retry 和 tool retry；
- report retry 固定两步；
- approval required、partial generation 后 abort 复查、普通 provider failure；
- 窄 report argument fallback 和 text-only budget-truncated display。

#### Selected-model defaults

新增 `defaultReasoningLevelForModel(model)`：

- GPT / Codex / `\bo\d+` → `medium`；
- DeepSeek → `high`；
- 其他 → `auto`。

主 chat runtime 和 child provider 都按实际 selected model 调用 `resolveSessionDefaults`。没有为未来模型组默认值新建 settings registry。

#### Manager 已完成

- latest settings snapshot；
- 验证顺序和 history grant before admission；
- overall admission lock；
- registration before launch；
- cancel-before-registration race replay；
- started-before-finished transcript；
- terminal first-wins；
- cancel/wait/read/missing run；
- 0..60000 wait clamp + 200ms poll；
- AgentTask snapshot/status mapping；
- nonretryable task semantics；
- approval required → task RUNNING/cancel false；
- result JSON string；
- public payload 默认只含 `display_text_chars`，finished transcript 才含完整 display text；
- live text/parts immediate replay、unsubscribe、unknown→null；
- 64 soft cap，不驱逐 active run。

#### Public surface 收敛

最终公开具体 `SubAgentManager`、最小 `SubAgentManagerDeps`、Runner 当前消费者类型以及 `SubAgentTranscriptTail` 类型闭包。

未加入：Service barrel、独立 Settings Port barrel、manager council callback、retry callback、独立 dynamic counter、timeout correction、grant rollback，以及任何 D-132c tools/entry/ChatPage 泄漏。

#### 阶段复核

Reviewer 曾建议 task update error hook 和 pre-launch rollback。对照 Android 后两项均撤回：

- Android `AgentTaskStore.persist` 本身 `runCatching`，Harmony D-130 已逐字保留；
- Android Manager 同样先 admission 写入 run map，再 register/append，无 rollback；
- 新增 hook/rollback 会改变 parity，并违反“无 Android 依据和当前消费者时不加兜底”的约束。

最终结论：`APPROVED`。

---

## 5. 已交付能力全景

### Chat 核心

- UIMessage/MessageNode/Conversation；
- streaming accumulator/branch/regenerate/edit/fork/delete/stop；
- transformer pipeline；
- context compact engine；
- pending queue/persistence；
- reasoning display；
- OpenAI/Claude/Google request construction；
- generation retry/vision fallback；
- provider/model/assistant persistence；
- selected-model session defaults。

### 工具运行时

- AgentTool/ToolRegistry/dispatcher/policy/approval；
- tool loop、continuation、regenerate；
- lazy exposure/tool discovery；
- speculative execution和 tool activity；
- conversation/history/queue/task/cron/prompt/permission/skills/workspace/search/MCP 等工具；
- subagent child scope、runner、manager。

### MCP

配置/导入、JSON-RPC client、SSE + Streamable HTTP、manager lifecycle/reconnect、tool bridge、设置 UI 和助手选择器均已 source-only 落地。

### 文档

DOCX/PPTX/EPUB 已移植；PDF 因 MuPDF native 阻断，显式失败而非伪成功。

### Skills / Workspace

skills 域层和工具、workspace 域层、artifact tools、built-in skills rawfile 安装已落地。

### Deep Read / Council / Novel

独立 deepread HAR 的 agent/council/novel/research 域已落地，610 单测全绿。

---

## 6. 当前在途：D-132c

`MIGRATION_STATE.yaml` 已将 D-132c 标记为最高优先级 `in_progress`，但尚未写 D-132c 业务代码。

### Task 8：Public SubAgentTools

待新增：

```text
subagent_list
subagent_start
subagent_read
subagent_wait
subagent_cancel
```

约束：public tools 不得进入 child scope；`parentToolsProvider` 只提供 profile 后的 base tools；manager 不重新承担 council settings；schema、wait clamp、roster/smart prompt 对照 Android。

### Task 9：Settings hydration + entry adapters

必须复用：

```text
PromptConfigStore.ets
createEntryAgentPromptSettingsPort(getChatKvStore())
```

该文件已经是 `agent_runtime` 下 subAgent/modelCouncil parser、serializer、unknown-key-preserving RMW 的唯一事实源。

待新增 `SubAgentStore.ets`，并装配 shared settings port、薄 hydrated snapshot bridge、runner/manager/tools Promise、shared AgentTaskStore、SessionAccessGrantStore 和 provider/model/assistant/retry adapters。

明确禁止在 `AgentRuntimePrefs.ets` 新建第二套 subagent settings RMW。

### Task 10：ChatPage assembly

待按 Android ChatService 顺序动态挂载五件 supervisor tools。独立 `ChatMessageSubAgentStep` UI 不在 D-132c 范围内。

---

## 7. 未完成能力

### 可继续 source-only

- D-132c；
- generate_image；
- ExternalFileTools 的非设备部分；
- 部分设置/UI；
- ChatMessageSubAgentStep 聚合 UI。

### 平台/设备阻断

- SSE、RDB/Preferences、ArkUI/Markdown 设备验证；
- PDF MuPDF native；
- QuickJS/eval_javascript；
- Terminal shell；
- ScreenAutomation/无障碍；
- WorkSchedulerExtensionAbility；
- notification/background execution；
- picker/media 路径。

### 大工具族尚未迁移

SystemAccess、WebView/ArkWeb、Terminal、ScreenAutomation、ICloudDrive、WebMount、FeishuOffice、FTS、MiniApp、board opportunity tools、后台无头聊天链路。

### UI 缺口

系统权限设置页、cron/任务管理页、SkillsPage/SkillDetailPage、QuickMessagesPage、webview 子系统、文件 sheet、generative UI 完整 stream guard、subagent 专属聚合 UI。

---

## 8. Parity Debt

当前 PD-001..PD-011，10 open、PD-008 closed；D-132a/b 无新增 debt。

主要 open 项：

- PD-001：Deep Read 空 prefetch fallback 与 Android hard fail；
- PD-002：15s 与 Android 36s timeout；
- PD-003：Harmony target API 与 Android targetSdk；
- PD-004：UTC `Z` 与 Android LocalDateTime；
- PD-005：Java Pattern 与 JS RegExp；
- PD-006：generative UI stream guard；
- PD-007/009：图片 data/file/http 行为；
- PD-010：Harmony 权限模型；
- PD-011：cron 仅进程内 timer，无 WorkScheduler/通知/无头执行。

另有一条 Android 内部 streaming 合并语义待人工核实，不计为 Harmony 新偏差。

---

## 9. 下一 session 的直接顺序

1. 读 handoff/state/decision tail/parity debt/D-132 plan/AGENTS。
2. `git status`，确认业务树干净且只剩两份 untracked 治理文档。
3. D-132c Task 8 TDD：五件 public tools。
4. Task 9：复用 PromptConfigStore，新建 SubAgentStore，做薄 hydration bridge 和 AppContainer 单例。
5. Task 10：ChatPage 动态装配。
6. 一次阶段级 plan-drift/spec/quality/anti-overdesign review。
7. 全量 chat/deepread + 双 tsc + HAR/HAP。
8. D-132c decision + 仅真实 debt。
9. feature commit + MIGRATION_STATE state-pointer commit。
10. 设备仍不可用则继续下一 source-only slice。

不要重新做 D-132a/b；不要因 reviewer 建议而加入无 Android 依据、无当前消费者、无测试的防御性框架。

---

## 10. 工程与提交纪律

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat && npm test && npx tsc --noEmit
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread && npm test && npx tsc --noEmit
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony && hvigorw assembleHar --no-daemon
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony && hvigorw assembleHap --no-daemon
git -C /Users/mi/Downloads/AI/AmberAgent-harmony-preview diff --check
```

双提交：

```text
feat(...): 业务 + 测试 + DECISION_LOG + 必要 debt
chore(state): head 指针更新 → <feature-sha>(D-xxx ...)
```

ArkTS：命名 interface；禁 inline object type/indexed-access/`as unknown`；受影响代码不用 spread；union 检查后显式 cast；domain 不导入 SDK；平台缺失能力显式失败。

---

## 11. 最近提交

```text
14c9e2cd4 chore(state): head 指针更新 → 1db56d3c3(D-132b subagent)
1db56d3c3 feat(agent): D-132b subagent runner 与 manager
b8a0ae6a6 chore(state): head 指针更新 → c8cb1875a(D-132a subagent)
c8cb1875a feat(agent): D-132a subagent 纯域基础
60ed0fd0a chore(state): head 指针更新 → 94f89d897(D-131 AgentTaskTools)
94f89d897 feat(agent): D-131 AgentTaskTools 六件 + Scheduler 装配
84d9549d9 chore(state): head 指针更新 → 51ca9479c(D-130 agent_cron)
51ca9479c feat(agent): D-130 agent_cron 域面 + feature/task 三件套
ac5e170fd chore(state): head 指针更新 → 8068aad54(D-129 permissions_status)
8068aad54 feat(agent): D-129 permissions_status 工具 + AgentPermissionBroker/Registry
```
