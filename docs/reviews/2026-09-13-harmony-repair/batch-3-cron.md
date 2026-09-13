# Batch 3 实施结果(Cron:R12 / R16 / R17;R13 接口面)

日期: 2026-09-13  分支: harmony-preview  基线: bdce551dd

范围: `harmony/chat/src/main/ets/chat/agent_cron.ts`、`agent_cron_run_executor.ts`、
`harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets`、
`harmony/entry/src/main/ets/pages/SettingCronTasksPage.ets` 及相关 cron 测试。
未改 AppContainer/ChatPage/index/agent_task(他 owner)。

## 一、修改文件与修复 ID

| 文件 | 修复 ID | 变更概要 |
|---|---|---|
| `harmony/chat/src/main/ets/chat/agent_cron.ts` | R12/R16/R17 | `AsyncMutex` 包裹 `replaceTasks` RMW;`prepareTriggeredRun` 入口同步原子 claim(`claimedRuns` Set);`markRun*` 释放 claim;新增 `getTask`(原始 due 快照)、`cancelRun`、`markRunCancelled`、`markRunSuspend`、`releaseRunClaim`、`cancelCallbackFor`;状态联合加 `Cancelled`/`Suspend`;`toAgentTaskSnapshot` 的 `cancelCapability` 由硬编码 false 改为 `lastStatus==='Running'`;register/upsert 挂取消回调 |
| `harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets` | R12/R16/R17 | `AgentCronRunExecutor` 改 `(taskId, signal)`;新增 `EntryCronTaskView`/`getTask`/`markRunCancelled`/`markRunSuspend`/`releaseRunClaim` 视图;`createEntryCronSchedulerPort` 新增 `createAbortController`/`nowMs` 注入;同步 `inFlight` Set 防双 fire;`activeAborts` map + `cancel` abort 在途;`fire` 定时路径先 `getTask` 复核原始 due 后再 `prepare`;`finalizeRun` 四态收口 |
| `harmony/chat/src/main/ets/chat/agent_cron_run_executor.ts` | R17 | `runCronTaskOnce(deps, task, signal?)` 透传 `abortSignal`;新增 `CronRunExecutor` 类型 |
| `harmony/entry/src/main/ets/pages/SettingCronTasksPage.ets` | R17 | 运行中显示「停止本轮」按钮(`cancelRun`);状态标签区分 Cancelled/Suspend/Succeeded/Failed;删除运行中任务确认文案说明会停当前生成;lastError 展示 |
| `harmony/chat/src/test/agent_cron_regression.test.ts` | R12/R17 | 6 个域层回归(见三) |
| `docs/.../probes/probe-batch3-cron.cjs` | R12/R16/R17 | entry 控制流回归(真实 `.ets` + 假时钟,17 断言) |

## 二、最小机制与关键决策

1. **R12 运行锁(两层)**
   - entry 侧:`inFlight: Set<taskId>` 在 `fire` 内**首个 await 前同步占位**,覆盖「同一 tick 内定时器到点 + 手动 runNow 双触发」——这是 manager 异步 claim 覆盖不到的即时竞态。
   - 域侧:`AgentCronManager.claimedRuns` 在 `prepareTriggeredRun` 函数入口同步 `add`,并发/重入第二次直接返回 `null`;`markRunCompleted/Failed/Cancelled/Suspend` 删除 claim,entry `startRun` 用 `finally` 兜底 `releaseRunClaim`(仅当本次真正 `prepared!==null` 才释放,不误删他人 claim)。
   - `replaceTasks` 整个读-改-写放进 promise 链 `AsyncMutex`(复用 agent_task/agent_prompt_config 既有模式),消除跨任务并发丢更新。
2. **R16 长 delay(不静默截断 + 原始 due 复核)**
   - `schedule` 仍 clamp 到 `2^31-1`(ArkTS setTimeout 上限),但到期回调改为经 `fire` 复核。
   - `fire(manual=false)` 先 `manager.getTask(taskId)` 取**原始持久化 nextRunAtMs**,`now < due` 时只 `scheduleNextRun` 重排、**不** `prepare`/不执行。关键:绝不「先 prepare 再比」——`prepareTriggeredRun` 会把 `nextRunAtMs` 重算成未来值,再比永远不到期。链式重排直到真正到点。
   - `nowMs` 可注入,测试共享假时钟。
3. **R17 取消通道**
   - `AgentCronRunExecutor = (taskId, signal) => Promise<void>`;entry `startRun` 用注入的 `createAbortController()` 建 run 级 controller,存入 `activeAborts`,signal 传 executor(coordinator 已透传 `ChatTurnDeps.abortSignal` 与工具循环)。
   - `scheduler.cancel(workName)`:清待触发 timer + 若有在途 run 则 `controller.abort()`。经 `workNameToTaskId` 回找,不硬编码前缀。
   - manager `toAgentTaskSnapshot` 仅 `Running` 置 `cancelCapability:true`,`createTask/updateTask/runTaskNow/prepare` 挂 `cancelCallbackFor(id)`(内部 `scheduler.cancel`)——`agent_task_cancel` 因此对运行中 cron 可用。
   - `cancelRun(id)`:仅当持久化 `lastStatus==='Running'` 才转发;否则 false,不清待触发定时器(不与「停后续调度」混用)。
   - `SettingCronTasksPage` 增「停止本轮」入口:明示「停当前生成,下一周期仍保留」;删除运行中任务文案说明会停当前生成。
4. **终态不误标成功**
   - entry `finalizeRun` 优先级:**已删除(null)→ 直接返回**(不 markCompleted/不重排/不复活);**!enabled → markRunSuspend**(停当前、不重排);**abort → markRunCancelled + scheduleNextRun**(下一周期明示保留);否则 `markRunCompleted + scheduleNextRun`。
   - abort 引发的 executor 异常走同一 `finalizeRun`,不落 markRunFailed;非 abort 异常仍 `markRunFailed + scheduleNextRun`。
   - `markRunCancelled` 记 `Cancelled` + `lastError='Run cancelled; next cycle remains scheduled.'`,不复用 `Succeeded`。
5. **R13 边界**:本批只定义接口与取消/审批前置语义;**未**在 scheduler 内新增工具循环,工具 loop 由 coordinator 在 `AppContainer.createEntryCronRunExecutor` 接(已确认接线:`runChatTurnWithTools` + `invocationContext:'cron'` + auto flags false,pending/unexecuted 抛错不 mark success)。「仅文本」不再等同 R13 完成。

## 三、回归测试与结果

```bash
# 域层(node:test,真实 agent_cron.ts/run_executor.ts + 内存 mock)
cd harmony/chat && npx tsc --noEmit -p tsconfig.json          # 0 error
npx tsx --test src/test/agent_cron.test.ts \
  src/test/agent_cron_run_executor.test.ts \
  src/test/agent_cron_regression.test.ts                      # 28 tests / 28 pass / 0 fail

# entry 控制流(真实 AgentCronStore.ets + EntryAbortController.ets,假 setTimeout/时钟)
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch3-cron.cjs   # 17/17 PASS, exit 0
```

新增回归覆盖(共用假时钟,少量 3–4 组):

- 域层 `agent_cron_regression.test.ts`(6 例):
  - R12 同任务并发 prepare 只放行一次、runCount 不丢更新;收口后可再 claim;
  - R12 并发不同任务 RMW 都持久化;
  - R17 Running 快照可取消 / 回调走 scheduler.cancel;非 Running `cancelRun=false` 且不触碰 cancel;
  - R17 `markRunCancelled` 记 Cancelled 非 Succeeded;
  - R17 已删除任务 markRunCancelled/scheduleNextRun 不复活;
  - R17 `runCronTaskOnce` signal 透传 provider。
- entry probe `probe-batch3-cron.cjs`(CT1–CT5,17 断言):
  - CT1 R12 inFlight 折叠同 tick 双 runNow(executor 仅 1 次);
  - CT2 R16 一年期 delay clamp 到 2^31-1、第 24.855 天提前触发**不执行**只重排、重排再次 clamp、due 不变;近任务对照按时执行;
  - CT3 R17 cancel abort 在途 signal,Cancelled 非 Succeeded,下一周期保留(rearm);
  - CT4 R17 删除运行中任务 abort、不 completed、不 rearm、不复活;
  - CT5 R17 运行中禁用 → Suspend、不 rearm。

静态检查(`harmony/scripts/lint-arkts.sh` 逐文件):`AgentCronStore.ets`/`SettingCronTasksPage.ets`/`agent_cron_run_executor.ts` = No defects;`agent_cron.ts` 仅 1 条**既有** warn(`reuse-date-instances-check`,line 168 属原有 `zonedPartsOf`,非本批引入),0 error。未运行 hvigor/全仓 lint(按契约由主代理统一构建)。

## 四、共享接线(需主代理确认)

1. **已对齐的 runExecutor 接口**(coordinator 已按此接线,无需再改):
   ```ts
   // AgentCronStore.ets
   export type AgentCronRunExecutor = (taskId: string, signal: AbortSignalLike) => Promise<void>;
   ```
   `AppContainer.createEntryCronRunExecutor` 现返回 `(taskId, signal?: AbortSignalLike)`,与 required 参数兼容;`AppContainer` 透传 `abortSignal: signal` 给 `runChatTurnWithTools`。
2. **scheduler 需注入 AbortController 工厂**(必做,当前 `getAgentCronManager` 尚未传 `createAbortController`,缺省走 `NeverAbortSignal` → 取消无效):
   ```ts
   import { createEntryAbortController } from '../platform_impl/EntryAbortController.ets';
   // createEntryCronSchedulerPort({ manager, runExecutor, createAbortController: createEntryAbortController })
   ```
3. **manager 视图新增方法**:`getTask` / `markRunCancelled` / `markRunSuspend` / `releaseRunClaim`(真实 `AgentCronManager` 已实现,`EntryCronManagerView` 结构匹配)。
4. **`cancelRun` 供 UI**:`SettingCronTasksPage` 已用 `manager.cancelRun(t.id)`;`agent_task_cancel`(工具/看板)经已注册回调自动可用,无需另接。
5. **R13 工具 loop**:已由 coordinator 接;本批仅保证取消 signal 贯穿 + 取消态不 success + pending/unexecuted 不当成功。scheduler 侧不新增工具装配。

## 五、未解决点 / 明确不做

- **PD-011 系统级唤醒不做**:仍为应用存活期 `setTimeout`;进程死后不唤醒、不补跑,与本批 R16 链式重排无关(链式仅作用于存活进程内)。
- **未做无头(不新建无头框架)**:按契约沿用 `AppContainer` 现有装配,不造新框架。
- **UI 未做设备截图目测**:本批未运行模拟器,不声称 UI 验证通过;`SettingCronTasksPage` 的「停止本轮」/状态标签需主代理在模拟器暗色主题下目测。
- **`markRunSuspend` 看板置 COMPLETED**:选择以「执行已收口」表示禁用停跑(区别于 CANCELLED);若主代理希望新增 INTERRUPTED 映射可再调,当前不改 agent_task 状态枚举。
- **cron 任务态 `Cancelled`/`Suspend` 为域内扩展**:Android 枚举仅五态;`decodeStrict` 会接受(已加入 `AGENT_CRON_STATUS_NAMES`),Android 端不会读这两个值,无互操作影响。
