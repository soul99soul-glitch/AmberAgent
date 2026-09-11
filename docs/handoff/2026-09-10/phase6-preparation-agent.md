# Phase 6 Q08/Q10 准备 handoff

日期：2026-09-10  
范围：只读核查 Q08（OpenAI Responses 恢复）与 Q10（CapabilityFlags）。  
状态：因实施暂停，本轮只落盘事实、证据路径和未确认项；没有修改产品代码，没有运行 Gradle、测试或构建。

## 已确认的代码事实

### 十个 CapabilityFlags

当前枚举位于 `core/settings/src/main/kotlin/app/amber/core/settings/CapabilityFlags.kt`，十项及当前默认值如下：

| Flag | 当前默认 | 已定位的生产消费者 | 依赖/当前结论 |
| --- | ---: | --- | --- |
| `DurableToolEffects` | 开 | `app/src/main/java/app/amber/agent/AmberAgentApp.kt:176-183`；`app/src/main/java/app/amber/core/service/ChatService.kt:398-402`；`app/src/main/java/app/amber/core/ai/ChatRunCoordinator.kt:175-179` | durable runtime 启动、ChatService 和 coordinator 的持久工具效果路径；Phase 1-3 已使用并经阶段链路收口，不需在 Phase 6 重新“一键开放”。 |
| `TypedRunTerminal` | 开 | 同上三个文件/位置 | 与 `DurableToolEffects` 成对决定 typed terminal/recovery 路径；显式关闭仍应走旧路径。 |
| `CapabilityPermissions` | 关 | `app/src/main/java/app/amber/feature/miniapp/MiniAppSendGate.kt:41-47`；`app/src/main/java/app/amber/core/ai/ChatRunCoordinator.kt:181-187`；`app/src/main/java/app/amber/core/service/ChatService.kt:1646-1652,1897-1901,1930-1932` | 影响 MiniApp 发送确认、运行内权限快照和 CAS audit；本轮未扩大到其 owner/Phase 4 门，不作 Phase 6 开放结论。 |
| `WorkspaceArtifactsV2` | 关 | `app/src/main/java/app/amber/feature/ui/pages/chat/ChatPage.kt:200-205` | Chat 页面通过 flow 决定 Workspace artifact 入口；本轮只确认真实 consumer，不重新审其功能门。 |
| `RecipeRuntime` | 关 | `app/src/main/java/app/amber/core/service/ChatService.kt:1891-1900` | ChatService 仅在 dispatcher、registry 和 flag 同时满足时注入 recipe context；未在本轮决定正式开放。 |
| `ThreadGraphV2` | 开 | `feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentManager.kt:89-92`；`app/src/main/java/app/amber/core/service/ChatService.kt:1934-1936` | W07/Phase 3 已完成并独立 review 收口；显式 false 的升级保留已由 flags 回归覆盖。 |
| `OpenAIResponsesResume` | 关 | `app/src/main/java/app/amber/feature/runtime/RunRecoveryService.kt:104-112`；`app/src/main/java/app/amber/core/service/ChatService.kt:416-420,1970-1980` | Q08 仍保持关闭，原因见下文的混合状态缺口。 |
| `NovelPackageV2` | 关 | 未找到直接生产行为 consumer；通用枚举页面为 `app/src/main/java/app/amber/feature/ui/pages/debug/DebugPage.kt:222` 与 `app/src/main/java/app/amber/feature/ui/pages/setting/SettingCapabilityPermissionsPage.kt:80` | 只有通用展示/设置枚举，没有 owner 接线；不能把它计作“小说功能不可用”，也不能因出现在设置页就宣称功能已实现。 |
| `SyncProviderV2` | 开 | `app/src/main/java/app/amber/feature/ui/pages/backup/BackupVM.kt:73-77` | W06/Phase 2 已完成；DI→VM flag→WebDAV/本地文件夹 UI 与关闭时 VM guard 已复核。真实 WebDAV/Google 账号烟测仍未做。 |
| `JSCellRuntime` | 关 | `app/src/main/java/app/amber/agent/AmberAgentApp.kt:184-189`；`app/src/main/java/app/amber/core/service/ChatService.kt:1937-1939` | 消费者属于既有 JS cell runtime；旧任务编号 P4-03 不等于本次 MiniApp 系统能力 Phase 4，不能因本次 Phase 4 完成就自动开放。本轮没有重新验收该 owner。 |

`CapabilityFlags.isEnabled()` 对已写入的 `capability_<id>` 值优先使用持久值，缺失时才使用枚举默认值；`setEnabled(..., false)` 会写入显式 false。由此，默认值升级只影响从未写入的 key，显式 false 会继续保持关闭。禁用只关闭入口/新执行路径，已有 Room/ledger 数据仍可读。

相关回归位于 `core/settings/src/test/kotlin/app/amber/core/settings/CapabilityFlagsTest.kt`：默认集合目前是 `DurableToolEffects`、`TypedRunTerminal`、`ThreadGraphV2`、`SyncProviderV2`；覆盖了显式 false 重启后保留、SyncProvider/ThreadGraph 默认升级后仍保留 false，以及单 flag 不影响其他设置。该事实来自已有台账/源码，暂停期间未重新执行测试。

研究基线 `docs/audits/2026-09-09-android-ios-parity-research.md:240-242` 仍写着“8 个默认关闭”和 SyncProvider 入口被隐藏；这已被 W06/W07 的当前源码与执行台账更新取代。Phase 6 应以当前枚举、consumer 和执行台账为准，不能复用该两处旧默认描述。

## Q08 Responses 恢复边界

当前调用链是：应用启动在 `app/src/main/java/app/amber/agent/AmberAgentApp.kt:176-183` 先由 `DurableToolEffects` + `TypedRunTerminal` 决定是否调用 `RunRecoveryService.recover()`；`RunRecoveryService.recoverInternal()` 在 `RunRecoveryService.kt:104-164` 只对 `OpenAIResponsesResume` 开启、且存在 gateway/store、run 处于可恢复状态时进入 `resolveStoredResponse()`。`RunRecoveryService.kt:193-257` 随后读取服务端状态：

* `COMPLETED`：`fetchMissingEvents()` → 合并最终消息 → 清 cursor → `runTerminalStore.finish(...COMPLETED)` → `reconcileStartedEffects()` → 重放已完成工具结果。
* `CANCELLED`/`FAILED`：清 cursor、写 terminal、再 reconcile effects。
* `IN_PROGRESS`：保持同一 runId 为 `RESUMABLE`，保留 cursor。
* 服务端不可达时，暂停态保留可恢复性；`RUNNING` 回退到 Phase 1 的 `INTERRUPTED` 语义。

已存在的 Q08 测试证据：

* `app/src/test/java/app/amber/feature/runtime/RunRecoveryServiceResumeTest.kt:190-230` 覆盖服务端 `COMPLETED`、按 cursor 只拉缺失事件、同一 runId 完成及 cursor 清除。
* 同文件 `:474-553` 覆盖真实生成通过 fake SSE 先写入 Room cursor，模拟进程死亡后再由服务端 `COMPLETED` 恢复。
* 同文件 `:325-371` 覆盖 capability flag 关闭、或 provider 用户开关关闭时不查询服务端，并回到 Phase 1。
* `app/src/test/java/app/amber/feature/runtime/RunRecoveryServiceTest.kt:61-96,147-168` 覆盖本地 `STARTED` effect 的读/幂等写重试和非幂等写升级 `OUTCOME_UNKNOWN`；这是没有服务端 Responses `COMPLETED` 的旧恢复路径。

尚未找到把“同一个 run：服务端 `COMPLETED` + 本地 `ToolEffectStatus.STARTED`”放在同一恢复测试中的证据。当前 `COMPLETED` 分支在 `RunRecoveryService.kt:224-234` 先写 `RunTerminalState.COMPLETED`，再调用 `reconcileStartedEffects()`；后者在 `:326-350` 对非幂等 `STARTED` effect 只标记 `OUTCOME_UNKNOWN`，没有把已经完成的 run 改成可行动的暂停态。因而恢复后的 terminal 与 effect 状态可能不一致，且没有测试证明不会重复外部副作用或能给用户正确的下一步。

该缺口需要后续 Q08 owner 用真实 `RunRecoveryService`/Room 链路补最小混合状态测试，至少覆盖：同 run 的服务端 `COMPLETED` 与非幂等 `STARTED`、可安全重试的 read-only/idempotent effect，以及 cursor/responseId 不一致或缺失时的明确结果。测试应断言 terminal、ledger、conversation 和 cursor 的最终状态，确认不重复执行、不静默吞掉不确定副作用。完成前保持 `OpenAIResponsesResume` 关闭；现有单独的 server-completed 测试和单独的 local-started 测试不能替代该门。

## 本轮停止点

本轮没有创建原计划的 `docs/plans/2026-09-10-phase6-flags-recovery-boundaries.md`，因为暂停指令改为只落盘 handoff；当前事实已落在本文件：

`docs/handoff/2026-09-10/phase6-preparation-agent.md`

恢复实施时，先由主 agent 将本 handoff 转成 Phase 6 计划条目；不要把 Q08 的单独测试证据写成混合状态已闭合，也不要把旧研究里的默认 flag 描述当作当前运行时事实。
