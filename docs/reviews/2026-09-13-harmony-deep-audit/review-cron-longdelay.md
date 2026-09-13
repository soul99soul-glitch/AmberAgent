# 独立复核：cron 远期任务 delay 截断导致提前执行（常驻进程）

复核者：独立 agent（非 D owner）。基线：`harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，当前工作树。
新候选 ID：**CRON-LONGDELAY（建议 P2，高置信度；与 D-02 不同根因，不合并）**
方法：真实 `AgentCronManager`(.ts) + 真实 `createEntryCronSchedulerPort`（转译 `AgentCronStore.ets`）+ 注入假 `setTimeout/clearTimeout`（记录 delay，不真等 25 天）+ 受控 `Date.now`。`runExecutor` 为纯记录桩，未调用真实 chat 执行体；无网络/模型/文件/真实定时器副作用。
新增证据：`probes-df/probe-cron-longdelay.ts`（**13 断言全 PASS，exit 0**）。

---

## 1. 代码事实

- `harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets:207-213`：

  ```ts
  schedule: (workName, taskId, delayMs) => {
    clearTimer(workName);
    // setTimeout 上限 2^31-1ms;超出 → 截断(远期任务由 rescheduleAll
    //   启动重排兜底,登记)
    const clamped = Math.min(Math.max(delayMs, 0), 2147483647);   // :211
    const handle = setTimeout(() => { fire(workName, taskId, false); }, clamped);
    timers.set(workName, handle);
  }
  ```

  delay 被**静默截断**到 2^31-1 ms（= 24.855 天），并直接用该截断值排定时器。

- `chat/src/main/ets/chat/agent_cron.ts:601`：`delayMs = Math.max(task.nextRunAtMs - Date.now(), 0)`。`createTask`（:340-372）允许 `cronNextRunAfter` 在**366 天**内任意时刻；故 delay 可合法远超 2^31-1。
- `agent_cron.ts:456-495` `prepareTriggeredRun` 只过滤 `!manual && !enabled`，**不比较 `now` 与 `nextRunAtMs`**（D-08 已登记）。
- `fire`（`AgentCronStore.ets:186-204`）对定时器到期路径无“是否真的到点”的复核，直接 `prepareTriggeredRun → markRunStarted → runExecutor → markRunCompleted → scheduleNextRun`。
- 注释声称的兜底 `rescheduleAll`（`agent_cron.ts:560-580`）**只在启动时**被调用：唯一调用者 `EntryAbility.ets:81`（经 `AppContainer.ets:518-528`）。常驻进程不再调用。而且 `rescheduleAll` 自身也走同一个 `schedule` → 同样被截断，**无法修复远期任务**（只是再排一个提前触发的定时器）。
- 对比 D-08 的降级依据：「entry 仅在定时器到期（delay=nextRunAtMs-now）或 manual runNow 时调用 prepareTriggeredRun，故非 due 调用不可达」。该依据在 delay 被截断时不成立——定时器会在**未到期**时回调 `fire`。

## 2. 独立验证（真实代码 + mock 时钟）

`probes-df/probe-cron-longdelay.ts`，全部 PASS：

- 纯 scheduler 层 clamp 边界：
  - `CL1` 1 小时 delay 不被截断（正常）。
  - `CL2` delay=3e9 被截断为 `2147483647`。
  - `CL3` 负 delay 截为 0。
  - `CL4` 丢失时间 `3e9 - 2^31-1 = 852516353 ms ≈ 9.87 天`。
- 真实 manager + 真实 entry scheduler，年度任务（`0 0 1 1 *`，受控 now=2026-01-02T00:00:00Z，下次约 364 天后）：
  - `CL5` 前提成立：`nextRunAtMs - now > 2^31-1`。
  - `CL6` `createTask` 排出的定时器 delay 已是 `2147483647`（创建时即提前）。
  - `CL7` 模拟活进程推进 2^31-1 ms（第 24.855 天）触发回调时，`now` 仍**远早于** due（剩余约 339 天）。
  - `CL8` `runExecutor` **真实被调用**（提前执行，不是只重排）。
  - `CL9` 任务被标记 `Succeeded`、`runCount=1`（提前运行被当作正常成功，用户不可见）。
  - `CL10` 提前执行后再 `scheduleNextRun`，新定时器 delay **再次被截断**为 2^31-1 → 常驻进程将**每约 24.855 天周期性提前触发**（放大）。
  - `CL11` 联动证据 `prepareTriggeredRun` 在 `now << dueAt` 时仍返回非 null 并置 Queued（无 due 门，是把提前定时器变成提前执行的使能条件）。
- 对照：`CT1/CT2` clamp 窗口内的近任务 delay 不被截断、按时执行（非普遍缺陷，只有 > 2^31-1 的远期任务受影响）。

## 3. 判定：P2（高置信度）

**确认。** 根因是 entry 调度原语的 32-bit delay 截断；`prepareTriggeredRun` 缺 due 门把它转化为“提前执行”；`rescheduleAll` 兜底对常驻进程无效且自身同样截断。

- 用户影响（前提：存在 enabled 且下次执行 > 24.855 天的 cron 任务，且 App 进程连续存活 ≥ 24.855 天）：
  - 任务在第 ~24.855 天**提前执行**，之后每 ~24.855 天再次提前执行；一年期/季度任务会多跑若干次。
  - 外部副作用在错误时间发生：一次完整模型调用；D-03 修复后还会带上工具面（网络/文件等）。
  - `runCount` 与 `lastStatus` 记录为正常 `Succeeded`，无任何“提前”标记，用户不可见。
- 前提强度：核心弱点是**连续存活 24.855 天**。移动端普通使用几乎不满足；但在鸿蒙桌面/常驻卡片、长时前台、或系统未回收等场景可满足。属“确定但前提苛刻”，故 P2 而非 P1。**不得**宣称无需长存活即可触发。
- 与 D-08 的关系：本候选**否定了 D-08 降级所依赖的“定时器只在到期回调”前提**（对 > 24.855 天的任务）。D-08 的“无 due 校验”是使能条件，但根因是截断，二者仍分列。
- 与 D-02 的关系：**不合并**。D-02 根因是缺运行锁（并发/重叠触发）；本项根因是调度延迟截断 + 无 due 门，即使完全不重叠也会发生。
- 与 Android 关系（中置信度）：Android `AgentCronWorker` 经 `WorkManager` 以 `long` 毫秒级 initial delay 调度，无 JS `setTimeout` 的 2^31-1 上限问题；属**调度原语移植偏差**。未逐字比对 WorkManager 对超长 delay 的处理，标注为中置信度。

## 4. 最小修复

1. `schedule` 不静默截断：若 `delayMs > 2147483647`，改用**链式定时器**（先排 2^31-1，到期后按剩余时间再排），或拒绝并显式报错/标记该任务不可靠。
2. `fire` 增加 due 复核：定时器路径 `prepareTriggeredRun` 前比较 `now >= nextRunAtMs`（用手头快照/持久化值），未到点则重排而非执行。这同时堵住 D-08。
3. 删除或修正 `AgentCronStore.ets:209-210` 的错误注释（`rescheduleAll` 不能兜常驻进程，且自身截断）。
4. 回归测试：在 `AgentCronStore` 调度层断言 `delay>2^31-1` 的定时器在到期回调时**不执行**（未到 due 应重排），近任务行为不变。

## 5. 盲区

- 未在线设备验证 ArkTS `setTimeout` 对 > 2^31-1 的真实行为（本实现已显式截断，故按截断建模；若平台因 32 位溢出改为立即触发，则问题更严重，同为提前执行）。
- “进程连续存活 24.855 天”无法在测试环境真实等待，属静态 + 逻辑时移证明（mock 时钟）。
- `rescheduleAll` 的调用面仅静态确认（`EntryAbility.ets:81`）；未排除运行期其他地方经反射/间接调用。
