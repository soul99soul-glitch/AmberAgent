# E5 runner / broker / tools 独立只读审查

2026-09-28，UI owner 转独立只读审。范围为 `chat/plugins/{runner,broker,tools}.ts` 的真实调用链，并追到既有 recipe_loop/tool_loop/dispatcher/permission 和 root 接线确认消费者。未修改这些源码、未写/运行额外测试、未 build/设备操作。native/store 实现不重复其它 reviewer 的责任范围；仅读取必要端口返回形态确认传播。

## 确认真问题：P1，远端已发出副作用未知被取消/超时覆盖（已修复）

原调用链：OpenAPI 固定 POST 经平台 HTTP port 发出后，取消或到时会抛 `PluginHttpError(mayHaveApplied=true)`；httpDefinition 包成 child `status=outcome_unknown/may_have_applied=true`。但 dispatchChild 在 await dispatch 返回后先判断 timedOut/signal.aborted，提前抛 failed timed_out 或 cancelled，hostResult 根本没有机会读取明确的未知副作用。父结果因此丢失“远端可能已应用”的真实事实，模型可能据普通失败重新提交写请求。这违反 frozen packet 的已发出非 GET/HEAD→outcome_unknown。

已向 root/runner owner 回喂。当前 `runner.ts:137–153` 精准修复：先检查已返回 envelope 的 outcome_unknown/may_have_applied 并保留；catch 中 typed PluginHttpError.mayHaveApplied 或已标 unknown 的 PluginRunError 不被外层 deadline/abort 改写。随后 hostResult→finish 保留父 outcome_unknown，failureKind=null，不把未知副作用当代码故障记健康。

验证区分：本 reviewer 只读复核新源码；runner owner 在既有 remote fixture 内对 POST 取消、25ms timeout 做了最窄红→绿，报告受影响 focused **1/1 PASS / health0**（证据见 `E5-plugin-runner.md`），没有由 reviewer 重跑或创建另一套 fixture。

## 其余已核对调用链

- 新 run/test prepare 深固定 descriptor/input/test，已有 plugin_v1 不从当前包重取。固定 Python source/stdin/timeout、MCP 身份/inputs、OpenAPI url/method/input/domains，恢复的 saved child 通过 fixedChild 比较 name/id/JSON 参数；OpenAPI execute 实际读 descriptor，不用输入替换 endpoint/header。
- JS host_call 验 executionId/callId，broker 检查当前允许 primitive、按完整 Workspace path segment/网络域/动作授权；再 capture/decide，exact pending 保存先于返回、STARTED 保存先于真实 dispatch。父批准只用于当前 child，下个 host call 重新走 decision；ask_user 只 answered，普通 approved 重回 pending。保存失败关闭当前 VM 并抛出，不改成重试/重播。
- known child 收口到 running checkpoint 后保存，再 reply 同 executionId/callId 原栈；超过64KiB UTF8 bridge bound拒绝，不截断成功。ready 已 aborted 不 start；拒绝/取消关闭 gate，缺 VM 或 STARTED 无结果不重新执行源码。**续跑时新 AbortSignal 的原生绑定竞态由 e2_review_domain_ui 独立核，未在本报告重复或宣称该额外门已通过。**
- pure cold recovery 仅非 active conversation：STARTED 或 JS running/awaiting 且 VM 丢失→finished/outcome_unknown；ready、非 JS pending、同进程 live JS pending保留，未变化 part/message/node/conv 保留引用；不 dispatch/save/健康写入。root 消费者有 active run 门与恢复短锁。
- candidate_test 固定 candidate hash、成员与 inputs；expected_result 使用 own-key presence区分明确 null/缺省，成功结果做 typed深比较，不匹配 test_failed/expected_match=false；候选试跑不触碰 live健康。finish 对实际结果统一 coarse/schema/完整字符预算检查。
- 导入固定 candidate hash，并在 expected_base_hash present 时核重新准备的基线，最终把同 preview 交 Store 双 CAS；不能wrapper.execute直装。runner 明确只接受 parent.approved 后才 apply，两个自动批准开关无法代替人工。trust 不用于免审批或扩大能力，实际来源仍当前助手 primitive/policy。
- root 已澄清严格 human 门**仅 import**；enable/disable/delete/rollback/restore 等 Agent 生命周期沿冻结 design 的既有高风险策略/用户自动设置，中心按钮则是明确人类动作+expected hash CAS。没有依据本轮过宽措辞改生命周期默认策略，也未把该既有行为报告为缺陷。

本责任范围当前未发现额外未解决 P1/P2。该结论只覆盖上述源码/调用链，不等价于 native、Store、真实服务器、编译或设备完整验收。
