# Phase 5 B 独立 review

范围：自动批准动态复核 helper／dispatcher／主工具循环与 recipe／plugin／goal primitives、实际 Entry front／subagent／council 接线；只写本报告，未改实现。实现最终 ready 后完成独立复核；本报告区分源码／真实执行计数与后续统一设备视觉验收。

## 当前具体发现

1. **主聊天 run 的任务快照缺少本次 send 用户请求（已修复并独立复验）**。ConversationRunService 在构建 loop 时调用 buildConversationToolLoop(context)，ConversationRunTools 立即以 currentMessages(ctx.conversation) 固定 userTexts；本次 send 的用户 parts 直到后续 host.run 内 runChatTurnWithTools 才追加。TaskText 同意开启时，review 读取上轮历史，当前明确授权无法进入 authorized／offTask 判断，可能额外要求审批或错误评估任务关系。相同构建点的 regenerate 应按目标 prefix 取任务快照，不能将目标之后用户消息作为授权。最终窄修复为 Entry helper 接受 optional currentMessagesProvider，仅主 run 传该 context 的 getter，首次实际评估时读取已有 save barrier 更新后的上下文；其余 seat／generation 的固定 scope 保持。未改 Service 或新增字段。实际 Service＋domain loop＋Entry helper 回归证实 send 初始 snapshot 未含新请求、regen 初始含 future，评估时均含 CURRENT_AUTHORIZED_REQUEST 且不含 FUTURE_UNRELATED_REQUEST，授权豁免后的effect=1。
2. **动态暂停的原因在原审批 UI 不可见（已修复并源码／projection复验）**。helper 在 permission_trace.reason 保存“自动批准复核：…”；pending 没有 output。ChatMessageTools、详情 sheet 和 JevApprovalFacts 当前未读取或渲染该 trace；包内暂停原因位于 checkpoint.pendingStep.metadata。真实风险暂停只能显示待授权，缺少复核原因。最终纯 projection jevAutoApprovalReason 支持 direct 与 recipe／plugin／goal awaiting_approval pendingStep，实际三runner中文理由测试通过。root 已在原 ChatMessageTools 的胶囊／JevFacts 后添加同理由的自然换行文本，以原理由区32缩进显示，只有 pending 才返回内容，公共导出完整。独立源码复核没有第二套批准入口或裸 metadata UI；该新增文字的设备视觉由 Phase 5 统一新包验收。

## 已通过的逻辑检查

- 仅静态 allow 且 approvalState=auto 的风险调用进入复核；普通 normal 只读、已有 ask／deny、显式 approved 跳过评估。final dispatcher 的 ask 返回 pending 并且不启动 before hooks／primitive effect。
- 风险阈值0.8；明确授权0.8只免除破坏／离题，外发数据始终保留审批。allowTaskText=false 的同意仍可仅凭工具元数据评估破坏／外发两题，不发送 user_requests 或任务相关题。
- off／不具备toolmetadata同意零网络；shadow／评估失败／超时保持原静态行为；取消后不启动工具。构造输入的原 settings snapshot 经 evaluate 第四参数传 Entry purpose evaluator 第五参数，首次请求前复查 consent；独立实际 HTTP fixture 证明构造后撤销可选 scope 时不发送旧 payload。复核缓存按每个 dispatcher 的 toolCallId＋name＋input，preflight 与 finalexecute不重复评估；输入改变或新dispatcher重新评估。
- 真实 frontend loop、Recipe、JS plugin、goal child 测试在 pending 时effect=0，原 approved continuation 后effect=1；暂停 checkpoint 保留原 target／permission_trace，started checkpoint 在effect前持久化，没有添加重试。
- 主循环未统一关闭 speculative read path，静态门槛继续保留；recipe/plugin/goal runner 的实际决定点 await 异步复核，取消在 started/effect 前复查。
- subagent configure callback 在 recipe/plugin early-return 之前建立请求局部dispatcher，纯工具 child也会 review；实际 child 返回原 approval_required结果，没有新执行入口。

## 实际 Entry 接缝复验

worker识别并定点修复 ScopedRecipeLoop 重建 dispatcher 丢 review：optional callback 携带同一 generation review 到 replacement dispatcher，并保留原 terminal hooks／capture。实际 Entry factory 测试 effect=0、manual approved后effect=1、eval=1，target保留。CouncilAssembly 从 seat store 已保存用户 prompt 后读取 currentMessages，真实 runner 测试 eval state含该 prompt，风险pending→approved单effect。原terminal approval guard独立回归确认复核pending保留目标，用户批准后host变化仍拒绝，effect=0、原目标metadata未改。

## 独立验证

已独立运行以下实际 focused 文件，83/83 GREEN：jev_auto_approval.test.ts、tool_dispatcher.test.ts、recipe_loop.test.ts、plugin_runner.test.ts、webmount_goal_loop.test.cjs、subagent_runner.test.ts、council_tool_runner.test.cjs。含 effect-count 与真实循环／checkpoint测试，不靠源码字符串断言代替执行。后续独立组合加入 jev_auto_approval_entry.test.cjs、conversation_run_service.test.cjs、tool_invocation_metadata.test.ts，10文件124/124 GREEN；实际scope／factory／terminal metadata发现均闭环。B helper／actual Entry加 jev_runtime_purposes.test.cjs 的3文件25/25 GREEN，含 expectedSettings 传递和构造payload后撤销同意的zero HTTP实际回归。测试重叠，不将124与25相加成新的总数。

状态：本 B review 的两项具体发现及 worker 的 ScopedRecipeLoop／council接缝已修并完成独立复验。源码／实际执行链没有未处理具体问题，原审批卡理由渲染源码通过。未进行 build／lint／设备操作，避免与root并发；新增理由文字的真实浅暗布局待root统一新包视觉验收，本报告不以源码替代截图或声称真实付费Jev云端验收。
