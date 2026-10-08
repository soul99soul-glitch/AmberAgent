# Jev / context / approval / generation 审查

审查时间：2026-10-02。范围：本仓 `core/jev`、`app/.../core/jev`、上下文准备与压缩、`DefaultRunKernel` 和 `ChatGenerationRoundEngine`，并追踪 `wm_run_goal` 的生产 bridge。基线规格：`docs/plans/2026-09-30-jev-v2-android-plan.md`。只读审查，未修改生产代码、未运行 Gradle。结论基于当前大量 WIP 的实际调用链；下述测试是待执行的复现/回归方案，不冒充已执行测试。

## 已二次复核的真实问题

### JEV-01 [P1 / Spec] 网页快循环承诺拒绝高风险操作，但普通 click 可以实际提交/支付/删除

- 位置：`app/src/main/java/app/amber/core/jev/JevWebGoalRunner.kt:259-264`、`:141-161`；生产候选收集在 `app/src/main/java/app/amber/feature/webmount/tools/WebMountGoalTool.kt:228-244`。
- 触发：当前页有 `Send` / `Pay` / `Delete` / 登录 / 账号设置按钮，Jev 给出 `action=click` 且该 target 分数达到阈值，快循环直接派发。目标匹配分数不代表低风险。
- 二次查证：`createGoalTool` 文案明确宣称 submissions/payments/account changes out of scope，整个外层调用虽 `mandatoryApproval=true`，批准的是该受限目标工具，并没有批准之后任意敏感点击。`BridgeGoalDriver.parseElements` 收集所有 ref，不过滤高风险标签，不保留 input 类型/role 以约束 type。`runVerifiedAction`（`WebMountInteractionTools.kt:618`）仅处理租约、快照、对话框和动作回执；`bridge.js:1104-1120` 的 `performClick` 直接 `target.click()`，注释明示可 form submit。没有更深层的高风险 gate，非假阳性。
- 与 screen 的核对：`JevScreenGoalRunner.candidates` 已有 `BLOCKED` 标签过滤，逐候选 `safe_aN` 安全题，低分 handback，并于动作前重核配置。网页没有复用这些限制，也没有等效逻辑。screen 的规则明确禁止发送/发布/点赞/关注/购买/删除/认证/账号设置。
- 最小修复：保持网页独立 driver；在已有候选判题流程中加入显式风险排除和所选 click/type 的只读安全复核，保留 DOM 候选的 role/type（type 不应选 button 或认证字段）。可提取一个小的共同风险标签判定供 screen/web 使用，勿新建通用权限框架；网络判分仍只是补充，不取代本地排除。
- 回归：伪 driver 暴露 `Pay` / `Send` / `Delete`，即便 target=0.99 也应 0 派发并 handback；普通内容链接/搜索输入仍可执行；button 被选 type 时 0 派发。补生产候选解析测试，确保元数据/过滤接线。

### JEV-02 [P1 / Spec] 网页 ACTIVE 改 SHADOW 后仍可能继续派发动作

- 位置：`JevWebGoalRunner.kt:79`、`:110`、`:154-161`、`:290-294`。
- 触发：运行从 ACTIVE 开始，首动作后用户将用途切到 SHADOW；下一步 `runtime.decide` 捕获新的 SHADOW 配置并正常返回 evaluated / stale=false；外层仍用最初 `config.mode=ACTIVE` 判断是否只产轨迹，继续真实动作。
- 二次查证：`JevRuntime.decide` 的 stale 只比较该单次请求前后配置，不比较整个 goal 的初始配置。`decideStep` 接受 shadow 的 evaluated 以生成轨迹，符合其独立语义，因此无法替外层守住配置变更。screen 在循环、评估后和派发前均与 initialConfig 比较，网页完全缺失。OFF/授权撤销会因 decide skipped 停止，不影响这个明确的 ACTIVE→SHADOW 缺口。
- 最小修复：goal 每步及派发前重核 `runtime.configFor(WEB_AUTOMATION) == config`；变化 handback，不延续旧执行授权。
- 回归：首个 `performAction` 改 settings 为 SHADOW，脚本第二次仍返回 click；assert 仅一次派发并 configuration_changed。另验证判断返回前变更、OFF 和 dataScopes 变更。

### JEV-03 [P2 / Spec] 网页 SHADOW / dry_run 的 done 分支误报 completed

- 位置：`JevWebGoalRunner.kt:117-125`，dry-run 检查晚至 `:154`。
- 触发：SHADOW 或 dry_run=true，Jev 返回 action=done、done_check 高分，直接返回 completed；工具包装将 `ok=true`，用户/模型得到真实完成信号。
- 二次查证：现有 `JevWebGoalRunnerTest.shadowModeProducesTraceOnly` 只覆盖 click，没有 done；screen 会在 done 分支前返回 shadow_trace，注释明确测试态不可声称 verified completion。不是正常“不必动作”的完成：shadow 的契约为只记轨迹不改行为。
- 最小修复：把 dry-run/shadow 的一次决策轨迹返回移到 done/handback/observe 业务分支之前（是否保留 handback 可按现有 trace 契约统一处理），测试态恒不 completed。
- 回归：SHADOW+done、ACTIVE+dry_run+done 均返回 shadow_trace、0 动作；ACTIVE+done 仍按 done_check 完成/交回。

### JEV-04 [P1 / Spec] 长工具结果省略提示的同参取回路径被 duplicate guard 阻断

- 位置：`JevToolOutputProjector.kt:64-77`、`:262-264`；`DefaultRunKernel.kt:74-83`、`:953-987`。
- 触发：成功 `terminal_execute` 返回超过 8k 的多段纯文本，active context selection 省略部分，模型按提示再次同参调用取回；kernel 把第二次同签名调用输出替成 duplicate skipped，第三次 GuardStopped，原文拿不到。
- 二次查证：projectTool 没有只读/可重新观察工具名限制，满足 completed/text/length 就可投影。kernel 只有显式 REPEATABLE_OBSERVATION_TOOLS / screen_* 可新 callId 同参重读；`terminal_execute`、`terminal_session_exec`、执行类/MCP 调用均不在白名单。投影中的 seenCalls 只保证“后一次已存在的输出不再投影”，无法让被 guard 拒绝的第二次取得输出。Phase 0 规格要求同参重读得到全文，真实不满足。
- 最小修复：只投影明确有安全、已接线的同参重读能力的观察工具，限制本地输出候选；不要为了这个功能放开任意执行工具重复，也不要提示重复副作用。若以后需要执行类日志取回，用已有历史工具输出读取能力另开明确契约。
- 回归：terminal_execute 长输出原样保留；file_read 的第一份被投影，第二份保持全文；kernel 集成回归验证 file_read 同参可重读而 terminal 同参依旧 guard。

### JEV-05 [P1 / Spec] 通过高风险设置恢复原始 Pending 时绕过 Jev 自动批准复核

- 位置：`DefaultRunKernel.kt:266-296`、`:332-340`、`:389-407`，对照正常新调用复核 `:627-641`。
- 触发：首次调用因高风险需人工而 Pending，之后开启 autoApproveHighRiskTools 恢复本轮；恢复分支以 transient Auto copy 调 resolver，来源 settings/settings_unattended 的 ALLOW 直接进入 dispatch，未调用已开启的 JevAutoApprovalGate。若 Jev 本应判 destructive/exfiltration/off_task 则该次无法收紧。
- 二次查证：元数据 `jev_auto_approval_escalation` 能阻止已经 Jev 收紧的 Pending 自动释放；但是首次 Pending 是 resolver ASK，根本没问 Jev，无此 metadata。恢复专用路径绕过正常 new-tool gate，并且 AgentToolDispatcher 内只有普通 PermissionDecisionResolver，不接 Jev。高风险开关的既定恢复行为本身可保留；缺的是“设置实际自动放行需审批调用”的共同复核规则。
- 最小修复：恢复分支在同一适用条件（Auto copy + ALLOW + settings 来源 + 原 needsApproval）调用 gate；风险命中仍 Pending 并保留 metadata。使用一个局部 helper 共享新调用/恢复判断，避免复制两大段状态处理，不改变用户已 Approved 调用。
- 回归：初始 Pending + 高风险开关启用 + destructive=0.99 → 未执行、WaitingUser、escalation metadata；低风险/失败/OFF 延续原恢复；人工 Approved 不被重新收紧。

### JEV-06 [P2 / Spec] 网页成功但无页面变化的动作没有计入 no-progress 上限

- 位置：`JevWebGoalRunner.kt:178-183`。
- 触发：在页面底部反复 scroll_down，回执 status=ok、page_changed=false、快照不变；每步都重置 noProgress=0，直到 100 次/600s 而非声明的 10 次无进展结束。
- 二次查证：生产 `runVerifiedAction` 已返回 page_changed/snapshot_id_before/after；JevWebGoalRunner 却只把 failed 当无进展，status ok 不代表目标或页面有进展。screen 已依据 before/after snapshot id 计无进展。不是简单调整默认预算，而是现有 MAX_NO_PROGRESS 控制无效。
- 最小修复：结合回执快照/页面变化计数；明确未变化时累加，有确实变化时清零。保留 unknown 永不重放分支。
- 回归：连续 10 次 ok 且 unchanged 应 handback/no_progress，确实 changed 之间可重置；failed 仍计数、unknown 一次即停止。

### JEV-07 [P2 / Spec] 完成检查将命令参数中的 test/build 字样当成已运行检查

- 位置：`JevCompletionCheck.kt:87-90`、`:111-112`。
- 触发：file_edit 成功后只执行 `cat build.gradle.kts` / `ls tests` / `echo test`，正则在整条 command 的任意位置命中 build/test，事实门返回 null、零网络，不再校验声称完成的回复。
- 二次查证：规格判断是否运行 test/build/lint/check 命令，不是任意命令包含字样。现有测试仅 ls -la 和真实 pytest/gradlew，不覆盖参数。失败的真实检查算“已运行”已在规格/测试说明，是 by design，不应混为失败。
- 最小修复：识别命令执行位置的常见检查工具/子命令，覆盖项目实际运行器即可；不要编写完整 shell parser，也不要把单纯目录/文件名列为检查。保持合法失败测试算已运行的现有语义。
- 回归：cat build.gradle / ls tests / echo test 仍 unverified；./gradlew test、pytest、npm test、pnpm lint、cargo check 被识别。

## 需要可执行复现后才可纳入修复的问题

- `OkHttpJevTransport`（`JevClient.kt:369-373`）在响应头到达后调用阻塞 `ResponseBody.bytes()`；取消 hook 只绑定 awaitResponse 期间的 continuation。推测慢响应体可能令 1.2s 协调器 deadline 等到客户端 read/call timeout，且尺寸是在全量读完后判断（256KB 上限不是读取上限）。确实能从源码证明全量内存读，但 deadline 的精确取消行为需要本地慢响应/MockWebServer 测试，不能仅凭推测认定实际时长。建议用“立即 headers + 慢 body”定点复现后，才决定是否一个有界 cancellable body read 修复。未请求外网/真实 Key。

## 已排除 / by design

- AUTO_APPROVAL_GATE 网络错误、超时、低置信维持原自动批准：明确 fail-open 规格，不能以通用安全偏好修改。
- 用户明确要求的 destructive/off_task 可豁免、exfiltration 不豁免、SubAgent 不享用户豁免：正常接线已存在；不能按“所有高风险都应提示”报 bug。
- Recipe 内嵌步骤不经 Jev：计划已明确遗留/by design，外层 recipe 仍审批，非本次新增问题。
- 记忆候选仍问 pinned：计划已明确撤回排除需求，基线依赖其分数决定全不相关回退，不提“优化”。
- MODEL_ROUTING shadow 等待：计划刻意保留，非首字路径；网页/屏幕 shadow 是 dry-run，不强行改成完全后台。
- tool retention 已固定保留后 ACTIVE→SHADOW 不清回去：源码明确为稳定请求前缀，off 快路径则忽略；不把它误报成 shadow 新应用。
- 每 run mutex unlock/remove 存在窗口：源码主动声明尽力串行，全局 semaphore 与预算仍约束。无状态损坏证据，暂不因纸面“每 run 1 个”注释做额外复杂调度器。
- Kotlin 文件行数/Fowler smells：未发现必须因风格改动的 correctness 证据，不列成 bug，也不趁修复机械重构巨大文件。

## 建议分阶段

1. Phase A：JEV-01/02/03/06，同一网页 runner/driver 边界，先独立失败回归，再有限修复；review 子代理核对敏感按钮、配置变更、测试态和 no-progress 四链。
2. Phase B：JEV-04，上下文投影与 kernel 重复守卫契约，定点 projector/kernel 测试；不改变执行类重复安全策略；review 子代理确认取回路径真实可用。
3. Phase C：JEV-05/07，审批恢复与完成事实门，kernel 回归 + completion 套件；review 子代理检查用户 Approved、不恢复的风险 Pending、真实检查失败和参数假命中。
4. 可选已复现后 Phase D：慢响应体/尺寸检查；未复现不作为修复完成门槛，不加入泛化重试/兜底。

Standards：本范围问题都以上面的触发/行为闭环为依据；没有仅因风格、类型名、注释形式提出的标准缺陷。尚无真实 Jev Key/provider/真机验收，JVM 测试通过后也不能声称这些外部门槛完成。
