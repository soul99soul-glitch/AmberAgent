# Phase 3 Jev：失败回归准备

2026-10-02。已为 JEV-01 至 JEV-07 添加现有 API 的定点行为测试，尚未修改生产代码、尚未运行 Gradle。root 会在 Phase 1/2 完成后验证 RED。

## 测试落点

- `JevWebGoalRunnerTest`：Send/Pay/Delete/Sign in/Account settings 高 target 分不应派发；首动作后 ACTIVE→SHADOW 必须停止；SHADOW/dry_run + done 只能 trace；成功却快照 unchanged 的 scroll 在 10 次 no-progress 结束。原公共 FakeDriver 的普通目标由 Sign in button 改为 Open article，使旧的正常动作回归不再隐含批准认证动作。
- `WebMountGoalCandidateTest`：通过真实生产 BridgeGoalDriver 私有 deterministic parser（反射，不打开网页、不获取租约）喂真实 bridge 节点结构，验证 password input_type 即使名称 Enter value 也排除、Send 排除、disabled 排除，Search 和普通链接保留。密码类型/disabled 属于生产 metadata，不能只靠标签或模型分数。
- `JevToolOutputProjectorTest`：terminal_execute / terminal_session_exec / wm_eval 等不可安全同参取回的长输出保持原文且零网络投影，避免与 duplicate guard 矛盾。
- `DefaultRunKernelTest`：原始 Pending 经高风险 setting 放行时仍必须询问 Jev；高 destructive 分保持 Pending/WaitingUser/escalation metadata，零模型轮、零执行。
- `JevCompletionCheckTest`：cat build.gradle.kts / ls tests / echo test / git diff -- tests 不算执行检查；./gradlew test / pytest / npm test / pnpm lint / cargo check / python -m pytest 算真实检查。
- `core/jev/JevTransportCancellationTest`：纯本地假 Call 立即交付 headers，ResponseBody 的 Source 首次读只阻塞 300ms，50ms deadline 后必须调用 Call.cancel。测试不访问网络、不使用真实 Key、不会遗留无界阻塞。这个执行结果尚未取得，transport 问题仍为候选，等 root 实跑后决定是否接受。

## 约束

尚无测试运行结果，不能称 RED 已确认。`git diff --check` 定点检查无空白错误。
生产改动待 root 后续分派；不添加权限框架、不为上下文投影放开终端 duplicate guard。
当前 metadata parser 私有，无 public 测试 seam；用了仅限测试的反射。修复若提取小的 deterministic 解析函数，可把测试转为直接调用，避免保留反射。

## JEV-08 transport 已确认 RED

root 运行 `/tmp/amber-review-ns8-transport-red.log`；`core/jev/build/test-results/testDebugUnitTest/TEST-app.amber.core.jev.JevTransportCancellationTest.xml` 显示 `deadlineCancelsTheHttpCallWhileItsResponseBodyIsBeingRead` 失败，唯一断言为 `deadline after headers must still propagate to Call.cancel`（测试行75），测试耗时0.417s。前一独立 `bodyReadStarted` 断言已经通过，确认进入 body 阶段后取消 hook 不再覆盖 Call，并非请求未开始或同步 callback 吞异常。JEV-08 正式接受：响应 headers 之后的 body IO 没有保持 coroutine→Call 的取消链，1.2s timeout 会等到 read/call timeout 才实际结束。

新增第二个 RED 待 root 跑：`oversizedUnknownLengthBodyStopsReadingAtResponseBudget` 提供真实 BufferedSource 的 4×256KB payload，记录 source 实际交付字节，断言最多读取 256KB+一段Okio缓冲、返回 oversize Failure 并关闭source。没有外网、没有额外依赖。

最小修复预备：将 response body 的有界读取移入已有 enqueue callback 的 `suspendCancellableCoroutine` 生命周期，并把 invokeOnCancellation 的 Call.cancel 安装在 enqueue 之前；这样 headers 与 body 处理共用同一 pending continuation，取消直到 body 处理完都有效。读取用 source.request(MAX_RESPONSE_BODY_BYTES+1) 检查可用长度，超过上限直接 Failure 并关闭，不调用无界 bytes()；未知长度同样受限。保持现有一次 transport分类，不新增通用retry、fallback、线程池或HTTP抽象。生产尚未修改。

## Phase 3 已授权实现，等待 root GREEN / 独立 review

JEV-01/02/03/06：WebGoalElement 保留 bridge 的 role/tag/input_type/disabled/readonly。生产 parser 明确排除敏感标签、密码/邮箱/提交等类型、禁用控件；runner 对选中目标再次执行本地硬检查，TYPE 只能选择真实可编辑字段。在本地检查通过后，对具体选中 click/type 做一题只读风险复核（阈值0.9），OFF/失败/低分交回。整个外层工具的 mandatoryApproval 保留。goal 每轮、主判断返回后、选中安全判断后和派发前重新核对初始用途配置；shadow/dry_run 在 done/observe 等分支之前产一次 trace。无进展计数现在使用 page_changed / before-after snapshot，而不把所有 status=ok 当作进展。

JEV-04：投影只处理与 kernel 允许 fresh callId 同参重读相一致的明确观察工具；终端执行/MCP任意调用/网页eval 等输出不省略，也不鼓励重新产生副作用。重复执行守卫本身未放宽。

JEV-05：两个审批放行入口共用一个局部 autoApprovalEscalation 函数；Pending 设置释放以 transient Auto 判断也经过 Jev。高风险结果保持 Pending 并记录 escalation metadata；人工 Approved 从不送复核。新增低风险恢复与已人工 Approved 的正向控制测试。

JEV-07：有限 runner/任务名识别替代整条command任意字样命中；只识别实际执行位置的 pytest/jest/vitest/tsc/eslint/ruff/mypy、Gradle/Cargo任务、Maven生命周期、Make任务、npm/pnpm/yarn/bun/npx、python -m pytest。没有写完整 shell parser。文件路径 build.gradle 参数不会成为检查任务。

JEV-08：enqueue continuation覆盖headers与有界body读取；取消 hook 在enqueue前安装，迟到响应立即关闭。对已声明超上限长度直接拒绝；未知长度 source.request(256KB+1) 判断，超过上限不再读取全量并关闭。仅修改已有 transport，不增加重试/线程池/网络层。增补恰好256KB响应的正向测试。

生产候选解析提取为 `parseWebGoalElements` 小型 deterministic 函数，测试已改直接调用；不保留反射/Robolectric fixture。新增选中低安全分交回、正常搜索输入可TYPE、非编辑button不可TYPE三项正反控制。

定点 `git diff --check` 通过；本 worker 未跑Gradle，编译/运行结果和独立review由root串行完成，不能提前称GREEN。

### 独立 review 修正：普通 input button 可导航

review 发现 `input_type=button` 曾被一律本地排除，导致 `<input type="button" value="Next">` 与正常 `<button>Next</button>` 不一致。已仅移除 button 类型的硬排除；敏感标签、password/email/submit/其他禁止类型仍排除，button 仍非 editable、不能 TYPE。新增 production parser 的 Next 正例（Send / submit 对照仍排除），及 runner 实际 choice + selected safety 两次判题后准确 click Next ref 的正向用例。没有绕过选中安全复核。未跑Gradle，等待root定点重验。

### 独立 review 修正：引号内建议不算实际检查

review 发现 `echo "Suggested checks; pytest tests"` 会因简单分号分段误命中 pytest。已用一个局部扫描器仅在引号外切分 `;`/换行/`&&`/`||`，遇转义跳过下一字符，保留有限 runner 判定不变；没有扩展到完整 shell 解析。actual unverifiedWrites 新增双引号/单引号/转义引号/转义分号反例，另有引号结束后真实 `&& pytest` 正例；原 pytest/Gradle/npm 等正向回归保留。未跑Gradle，定点diff--check通过，等待root重验与review复核。
