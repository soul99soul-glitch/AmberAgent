# Phase5 B — 自动批准复核

状态：领域逻辑与 Entry 装配完成；159/159 focused tests GREEN，chat `tsc --noEmit` GREEN。root 负责公共导出、原批准卡风险理由渲染、统一构建/签名/设备验收，独立 reviewer 正在收尾。

## 行为

- 同步 `AgentToolDispatcher.resolveDecision` 保留；新 `resolveReviewedDecision` 仅对原 `allow`、工具仍为 `auto` 的非静态低风险调用进行复核。原 `ask`/`deny`、用户已批准，以及 `mutates=false && risk=normal` 的读取均不评估。
- Jev 用途为 `auto_approval`，默认关闭。核心授权 `allowToolMetadata` 决定是否发送工具名称、实际有界参数/命令和权限信息；未授权不请求。`allowTaskText` 独立控制最近三条用户文本及 offTask/authorized 两题；未授权任务文本时只评估 destructive/exfiltration。该用途不发送工具输出、推理、页面或任意 metadata，即使配置存在 `allowToolOutput`。
- destructive/exfiltration/offTask 任一概率 >=0.8 可收紧到原人工批准路径。authorized>=0.8 仅豁免 destructive/offTask，外发数据仍需批准。任务文本未授权时不应用授权或任务对齐答案。
- off、shadow、请求失败、deadline、设置/数据授权变化均保持原本地决定。请求及副作用传递原 AbortSignal；取消后不执行。复核结果应用前重新读取当前设置并使用 C 的 `jevPurposeConsentUnchanged`；构造输入时的原 settings snapshot 同时经 Entry 传递给 C evaluator 的第五参数，在请求首读设置时复查，避免构造输入→派发期间撤销授权后旧内容仍外发。
- 每个 dispatcher 按 `[toolCallId,toolName,input]` 缓存复核 Promise，主循环预审和最终 execute 共用同一结果；不同输入和新运行重新评估。不建立跨会话/全局缓存，不增加重试或关闭全局 speculation。

## 实际链路

1. `tool_loop` 在生成消息保存后 await 复核；风险命中将原工具改 pending，`permission_trace` 含来源/中文原因，先保存后停住。最终 `dispatcher.execute` 也 await 复核，`ask` 返回 pending/零副作用，补齐原直接执行入口忽略 ask 的缺陷。
2. `recipe_loop` 与 `RecipeExecutionPort.decide` 支持异步决定。Recipe/plugin/网页 goal 的真实 runner 在 primitive 执行前 await 决定，将 child `permission_trace` 与原 target metadata 保存到既有 `awaiting_approval.pendingStep`。人工批准后的原 checkpoint 续跑，只执行一次；取消不会保存可重放的 started 并产生副作用。
3. 前台 `ConversationRunTools` 挂 run 专属 Entry 回调。该回调在首次实际调用时从当前 run 的 `ctx.conversation` 抽取已接受用户消息，依赖原 `saveContext/applyConversation` 保存屏障，解决建循环早于 send append 的时序问题；重生成只读保存后的实际前缀，避免未来 user 泄入授权判断。
4. `AppContainer.buildSubAgentGenerationPort` 在无 recipe/plugin 的 early-return **之前**挂回调；每个 `SubAgentGenerationRequest` 构建自己的 dispatcher/任务范围。`ScopedRecipeLoop` 新 optional callback 进入原带 terminal hooks 的 replacement dispatcher，避免 package 装配覆盖复核。两个子代理 factory 共用这一装配函数，不在 singleton port 中缓存调用结果。
5. `CouncilAssembly` 在建立 seat 回调前读取 seat store 已保存的用户请求；仍使用原席位 pending card/requestApproval、beforeEffect marker、live-scope/target guard。
6. `jevAutoApprovalReason` 仅投影 pending 的既有 `permission_trace.source=jev_auto_approval`；package 从实际保存的 recipe/plugin/goal awaiting child 读取原因。root 在原批准卡显示该字符串，不新增批准入口。

## RED → GREEN

- `/tmp/amber-phase5-b-red.log`：新增 direct dispatcher 两个回归均 RED（风险复核未接入；原 ask 仍执行）。最小修复后 2/2 GREEN。
- `/tmp/amber-phase5-b-metadata-only-red.log`：工具授权而任务文本未授权时被整体跳过，回归 RED；拆成独立授权后 GREEN，并断言不发送任务文本/不提 alignment/authorized 题。
- `/tmp/amber-phase5-b-scoped-red.log`：实际 `ScopedRecipeLoop.ets` 覆盖子代理 dispatcher 导致工具直接执行，回归 RED；optional callback 窄接入后 `/tmp/amber-phase5-b-scoped-green.log` 2/2 GREEN。
- 前台 scope 缺陷由独立 reviewer 在真实 Service 顺序中确认。新增实际 Service+领域 append/保存/Entry helper 测试证明：建循环时尚无当前 send（重生成时仍含 future），首次实际复核时包含 CURRENT_AUTHORIZED_REQUEST 且排除 FUTURE_UNRELATED_REQUEST。`/tmp/amber-phase5-b-frontend-scope.log` 9/9 GREEN。

## 验证与范围

组合 `/tmp/amber-phase5-b-tests.log` 159/159 GREEN，包括：

- 直接执行、本地 ask/deny/user approved、静态只读零评估；.8 边界与授权豁免；关闭/影子/deadline/授权撤销/取消/错误；重复调用及新 run 隔离。
- 实际前台 tool loop 风险 pending→已保存→0 effect→批准继续1 effect。
- 实际 recipe、plugin JS host call、网页 goal primitive 的 checkpoint→pending→批准继续，校验 target/文档 token、trace 原因及 started-before-effect。
- 实际 subagent generation/runner 返回原 `approval_required`；实际 Entry ScopedRecipeLoop dispatcher 保留复核。
- 实际 Entry CouncilAssembly→seat runner 保存 pending→原 requestApproval→单次写入，评估1次且含对应seat任务。
- 实际终端 guard：复核 pending 后修改 host，人工批准续跑仍由原 target guard 拒绝，0 effects、原 target 保留。
- 实际 ConversationRunService send/assistant regenerate 保存顺序与任务范围。
- 原 build-consent snapshot 经领域 gate→Entry→C evaluator 传递，派发前 scope 撤销由 C 首读设置拒绝（C 有实际零 HTTP 回归），B 域和 Entry 实参回归验证同一原 snapshot。

`/tmp/amber-phase5-b-tsc.log`：chat `tsc --noEmit --pretty false` EXIT0。

`/tmp/amber-phase5-b-lint.log`：14处 B source 定点 ArkTS lint EXIT0，0 errors / 2161 warnings（共享 TS/domain 类型导入推断现有警告）；后续 helper/前台 scope 三文件定点 `/tmp/amber-phase5-b-scope-lint.log` EXIT0、0 errors / 54 warnings；plugin import 的异步取消屏障最终复查 `/tmp/amber-phase5-b-plugin-final-lint.log` EXIT0、0 errors / 592 warnings。派发前 consent snapshot 参数最终复查 `/tmp/amber-phase5-b-consent-lint.log` EXIT0、0 errors / 54 warnings。未跑全仓 lint，没有与 root 并发构建/安装。

实现文件：chat `jev_auto_approval.ts`、`tool_dispatcher.ts`、`tool_loop.ts`、`recipe_loop.ts`、`recipes/ports.ts`、`recipes/runner.ts`、`plugins/runner.ts`、`webmount/goal.ts`、`subagent_runner.ts`；Entry `JevAutoApprovalSupport.ets`、`ConversationRunTools.ets`、`CouncilAssembly.ets`、`ScopedRecipeLoop.ets`、`di/AppContainer.ets`。保留所有其他 worker/pre-existing WIP，未改公共 index 或 Service source，未 commit。

证据边界：focused tests 使用实际领域/Entry 源码及受控 provider/HTTP/持久化端口，可证明调用链与 effect counts。root 尚需统一构建、签名、原批准卡浅暗布局和实际 UI 验收；本报告不把受控请求声称为云端 Jev 实测。
