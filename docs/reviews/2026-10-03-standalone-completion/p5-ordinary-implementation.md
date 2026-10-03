# P5 普通生成原请求恢复

本报告只覆盖普通生成原请求与 Responses 恢复；润色、连续代写、克隆和收录由各自 worker 记录。领域与 transport 生产源码已冻结，交独立 reviewer 最后复核；构建、签名和设备验收由主 agent 统一执行。

## 生产链和数据

`NovelWorkspacePage → creation.generate → 持久 ordinaryRun 原请求 → prepareOrdinaryRequest → 固定原模型 runtimeSnapshot → collectWithEvents → NovelInteractiveAdapter → 原 provider HTTP`。

- 当前分支 `ordinaryRun` 保存作者原输入、mode/granularity/runKind、候选原来源、system/context、canonical history、工具 profile、原模型设置快照、最新 canonical 部分结果及 Responses 游标。原请求和模型准备结果各通过已有 workspace commit 持久化；准备模型失败仍保留作者输入、上下文和错误，没有冻结模型的记录不会假装支持精确恢复。
- Repository 的原有显式 branch project decode 和 head snapshot records 保留该字段。运行状态属于 native 项目运行数据，公共正文交换不把它作为可跨项目复用的任务。
- Runtime snapshot 由主 agent 的 `novel_runtime_snapshot.ts` 冻结无认证的 params/assistant/contextWindow 和原 provider container/model UUID，并保存实际 effective endpoint/header 配置指纹。恢复沿同一个 provider/model 使用当前合法认证，拒绝换 endpoint/configured headers，不把 key 或 headers 内容放进快照。Responses cursor 使用 effective provider ID，避免 provider overwrite 和 container ID 混淆。
- `readOrdinaryRun/retryOrdinaryRun/resumeOrdinaryRun` 是 UI 唯一恢复入口。读取依据同一 workspace 的当前 branch/transcript/active driver，动作再次核验原 record ID 和完整 canonical 对话归属。设置/资料可以改变，但精确恢复不重建 prompt；原候选 provenance 也不重新绑定编辑后的正文，最终收录仍经过既有来源校验。

## 两个明确动作

“重试原请求”是用户明确启动一个新生成：发送冻结的原输入、原 history/context/model settings/tool profile。旧失败部分留在书的对话供复制，但不作为新 retry 的模型输入。新结果追加在旧失败对话后。

“继续原响应”只用于实际支持的官方 OpenAI Responses 生成，使用持久 response ID 和 sequence 发送 GET，不重新 POST 原 prompt。恢复原 canonical 部分消息的同一个 assistant ID，不添加第二个 user。过期 ID、来源变更、不支持 provider、无可恢复 cursor 都直接失败或禁用此入口，不偷偷改成新生成。普通 discussion/quick-start 不启用这条 Responses 恢复，实际写作的工具 profile 为 none；没有为假想跨工具响应增加 cursor 状态机。

服务端 validated `response.completed` 的 terminalStatus 与完整 raw canonical 正文同一次 checkpoint 保存。如果该 checkpoint 完成后、Creation 最终 complete commit 前客户端中断，冷恢复复用该完成证据和原部分正文，经原 runtime/owner 验证与收尾持久化，不 GET 已结束游标、更不 POST 新请求。

普通讨论的待答工具冷续接仍走既有 `continueTool` 调用。若它属于原 waiting ordinaryRun，续接复用原 system/context/runtime/tool profile/model target；不因用户修改资料和默认模型而改变旧工具链，完成后更新原任务记录。没有新增工具自动重试或替代审批路径。

## UI 与取消闭环

provider snapshot 是原模型 canonical 对话；UI 使用的是 `ordinaryRunMessages(record, snapshot)` 的当前呈现对话。retry 的 prefix 是保留失败部分后的当前对话；resume 的 prefix 是原任务的 prefix，因为更新同一个助手消息。`live.historyCount` 与这个 prefix 一致，UI 初次挂接和冷 active 挂接使用相同计数，避免切片把恢复正文隐藏。

每次正文/cursor checkpoint 同时核对 branch、record ID、running 状态，再以一个 workspace commit 保存 canonical 内容和游标。终止后迟到内容不能覆盖已保存结果。取消保留既有 adapter 最后 checkpoint 的顺序，等待它完成后标记 interrupted；没有即时拒绝 collector 抢先终止保存。存储失败仍通过现有 unsavedMessages 和 stopActiveRun 拒绝传播，避免切分支或删除时假装已经保存。

## 实际 iOS 依据

- `NovelModelAdapter.swift:167` 的 Responses resume descriptor 只持有原 run/model/purpose/cursor；resume 不是重发 prompt。
- `NovelGenerationLifecycle.swift:756` 核对 sidecar 的 project/run/branch/session/message/base revision；`:967` 从原 generation receipt 选择固定 provider/model/wire，并恢复 canonical 部分及 cursor。
- `NovelLiveModelAdapter.swift:984` 接到 KMP `OpenAIResponsesBackgroundTransport.kt:101` 的同 response ID GET 和 starting_after；cursor 与部分消息属于同一次 sidecar progress。
- 只有 prose/regenerate/polish 等持久生成支持该入口；discussion/quick-start 不以原请求新 POST 冒充服务端 resume。

## 验证

- `novel_ordinary_run.test.ts` **7/7**，真实 creation + file repository + cold repository：原设置/context/tool profile/候选来源保持、失败部分保留但不发送、同 ID 部分消息更新可见、模型准备失败保留输入、待答工具冷续接原 runtime、终止后迟到 checkpoint 拒绝、对话改变后不解析 provider。
- 与既有 creation / C5 工具续接 / AskUser 停止保存组合 **33/33**（`/tmp/amber-p5-ordinary-continuation-green.log`）；全 novel **501/501**，`/tmp/amber-p5-ordinary-final-all-novel.log`。
- DeepRead `tsc --noEmit` 通过；ordinary_run / creation / models / model_running / repository scoped ArkTS lint **0 error**。日志 `/tmp/amber-p5-ordinary-tsc.log`、`/tmp/amber-p5-ordinary-lint.log`；最后 ordinary/creation 两文件再次 lint **0 error**（`/tmp/amber-p5-ordinary-final-lint.log`）；纯 TS 仓库既有动态接口类型解析有 warn。
- 原模型准备失败实际 RED：没有持久 ordinaryRun → 先保存原请求后 GREEN；原待答工具续接实际 RED：重读当前模型 defaults → 原 runtime/context 固定后 GREEN。日志 `/tmp/amber-p5-ordinary-preparation-red.log`、`/tmp/amber-p5-ordinary-tool-red.log`、`/tmp/amber-p5-ordinary-tool-green.log`。

## 实际 HTTP 及独立 review 补齐

`novel_ordinary_http_recovery.test.ts` 穿过真实 Creation → file repository → adapter → OpenAIChatApi → 模拟 HttpClient 两项 **2/2**：首次 POST 断线后重建 repository/Creation，同 response ID GET `starting_after=1` 补齐正文，只有一个 user/assistant；validated completed 原文和 cursor 保存后注入最终 complete commit 故障，冷恢复零追加 HTTP、一个完整 candidate、没有重复收录章节。背景存储与 retrieve 协议依据 [OpenAI Background 指南](https://developers.openai.com/api/docs/guides/background) 和 [Retrieve API](https://developers.openai.com/api/reference/python/resources/responses/methods/retrieve)。这些是 transport 协议与模拟 HttpClient 验证，不冒充真实云服务质量或后台存活。

Transport 五测试文件 **77/77**，Chat tsc 通过，五 production source lint **0 error / 682 warn**。日志 `/tmp/amber-p5-cold-http-{red,green,tsc,lint}.log`；最新 RED 真正复现成功恢复后中断 annotation 未清及 completed 状态不匹配；两条完整 Creation HTTP 用例在父域实现下首次就是绿色，不伪称有它们的旧版 RED。较早 transport 与 adapter 缺口的 RED 在 `/tmp/amber-p5-responses-http-red.log`、`/tmp/amber-p5-novel-resume-red.log`。

独立 reviewer 另查到实际 AskUser 已答 checkpoint → 模型失败 → 记录仍 waiting_user，因此作者既不能再次回答，也不能恢复。新增 domain 实际 RED 后，续接开始将 frozen `tool_continuation` operation、verdict、runtime 和 canonical history 保存为新的 running 原请求；已有历史同 ID 工具的 answered/执行 output 随 checkpoint 更新，明确 retry 使用原工具续接及已完成 output，不重新 POST 原 turn 重问作者。失败/停止变 failed/interrupted，终止后的迟到 checkpoint 仍拒绝。呈现 helper 同时更新 prefix 内原工具消息的审批状态，并保留旧失败正文；取消重试工具不凭原 turn userText 再加一条用户消息。日志 `/tmp/amber-p5-ordinary-continuation-{red,green}.log`；真实 adapter continuation 复核已通过：新用例穿过 Creation、冷 file repository、实际 AskUser dispatcher 与 adapter，回答执行一次、模型失败后冷 retry，只重试 `tool_continuation`，唯一 user、唯一回答执行，原 runtime/context/system 内容一致，记录最终 completed。实际 toolloop 复用原 assistant ID 的后续正文已纳入结果，不再被 history ID 排除误判为空；`ordinaryRunHistoryCount` 给该工具消息所在 prefix index，实际 Workspace 切片能看见续写正文。普通 turn 仍按原 prefix 长度。

最新 Chat 五文件 **78/78**，tsc 通过，日志 `/tmp/amber-p5-askuser-cold-{red,green,tsc}.log`。AskUser 真实 RED 为重试已返回正文但被旧 history ID 过滤，精确修复后 GREEN；system 比较核验角色与内容，不把正常新建的随机系统消息 ID/createdAt 当成内容漂移。clone worker 按新项目身份清除 ordinaryRun，同身份 native 恢复保留原任务。

构建、设备 UI 和真实 provider 验收由主 agent 单独记录。

### 工具失败前可见部分正文的最终保存

独立 reviewer 在真实 AskUser provider 第二次发送中先回传 `PARTIAL_BEFORE_FAILURE` 再抛错，复现了 UI 可见而冷记录丢稿。现在 adapter 的失败收口只为非 Responses 请求保存尚未 checkpoint 的最新 raw canonical；已有保存拒绝后不重复写同一个 owner，Responses 仍仅使用协议正文与游标的原子 checkpoint。保存错误如实传播，没有额外生成重试。

领域同时区分两个真实用途：`checkpointMessages` 和 project transcript 保留失败时实际可见正文；原 `tool_continuation` retry history 只更新工具的审批与执行 output，保留原 text/reasoning。最终状态保存也按 canonical 历史 ID 合并当前消息，不因该助手已经存在于 prefix 而漏掉部分正文。这样冷读能复制失败部分，而明确工具重试发送与实际 provider 输入都不含该失败部分，答案执行仍只有一次。

最新 actual HTTP/AskUser 测试 **4/4**，Chat focused **79/79**，tsc 通过；adapter scoped lint **0 error / 206 warn**。日志 `/tmp/amber-p5-askuser-partial-{red,green,focused,tsc,lint}.log`。领域新增 partial seed 实际 RED→GREEN 为 `/tmp/amber-p5-ordinary-tool-partial-{red,green}.log`。两个保存拒绝分支验证只尝试一次失败写入、不假发 completed、不重新生成。独立 reviewer 原 partial 脚本已 **3/3** GREEN（`/tmp/amber-p5-askuser-partial-review-current.log`）。

当前交互边界明确：普通新 turn 的失败回复在重试后仍作为旧对话保留；工具续接复用同一 assistant，明确重试成功的新输出替换该助手的失败部分。复制入口在失败记录仍存在时可达，失败文本不会混入重试模型输入；没有另加失败版本 store。


最终 production freeze：DeepRead 全 novel **501/501**、tsc 通过；最后 creation/ordinary 两文件 lint **0 error / 1975 warn**。域 partial 冷重试用例另覆盖连续第二次失败后记录仍保存部分正文、重试输入的原 text 仍为空，随后第三次明确重试成功（7/7）。UI 复制投影的 before 来自 `originalRequest.history` 同 ID 原 text；该 text/reasoning 现在严格不变，只有工具 approval/output 更新，避免误用含上一次失败正文的 presentation prefix。最新日志沿上述 final 文件；独立 reviewer 已确认实际 failed raw record → ETS 复制得到仅本次部分正文及首次按钮同 ID 输出可见。
