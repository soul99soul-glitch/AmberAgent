# Phase 3 独立复审

2026-10-02；reviewer phase3_review。只读审阅生产代码、真实调用接线、测试及修复前 baseline；未运行 Gradle、未改生产或测试文件。本文是独立审查结果，测试 GREEN 由 root 单独记录。

## 结论

主要修复方向精确，未引入重试、统一权限框架、全局终态锁死、额外网络层或任意终端重复执行豁免。发现两个需要进一步核对/修正的局部边界：普通 input button 被错误硬拦（worker 已修，待 root 测试），引号内含分号的说明命令误判检查执行（worker 已局部修正并补测试，待 root GREEN）。除这两个边界外，下列修复本身未发现阻塞问题。

## 按问题二次复核

| ID | 独立结论 | 生产和测试证据 |
|---|---|---|
| JEV-01 | 真问题，当前修复合理；另有 input button 误拦已纠正 | WebMountGoalTool 的 parseWebGoalElements 保留生产 bridge 的 input_type/role/tag 等；runner 的 decideStep 本地硬拦敏感目标，派发前 selectedActionIsReadOnly 必须满足 ACTIVE/nonstale/0.9。测试同时覆盖密码类型、敏感按钮、低安全分不派发、搜索TYPE、非editable不TYPE。没有用主模型评分覆盖本地硬拦。|
| JEV-02 | 真问题，修复闭合 | config 在循环起点、decision返回后、具体安全题返回后和派发前重查；JevRuntime 自身还重验判断期间变化。旧ACTIVE结果不会在后续SHADOW模式派发。|
| JEV-03 | 真问题，修复闭合 | shadow/dryRun 分支位于 done/observe/action分支之前；即使done核验高分，也只返回 shadow_trace，零派发。测试同时覆盖shadow和active dryrun done。|
| JEV-04 | 真问题，修复边界合理 | projector只过滤显式可重复观察工具；名单与DefaultRunKernel.REPEATABLE_OBSERVATION_TOOLS一致，另外列出的screen观察本就受screen前缀许可。terminal_execute/session_exec/wm_eval不投影，kernel guard未改。测试断言原文及零网络判题。|
| JEV-05 | 真问题，两个实际kernel入口共用风险函数 | 新调用和persisted Pending transient Auto都经过autoApprovalEscalation，相同source/ALLOW/needsApproval约束。风险metadata会留在ASK/Pending保存快照，后续setting不重复放行。人工Approved不复核符合明确设计；新增高风险、低风险、人批准三组正反控制。|
| JEV-06 | 真问题，修复合理 | receipt.page_changed=false或before/after snapshot相同计入无进展；成功但没变不能一直清零。failed、observe继续原有计数，人接管/unknown在计数前停止。真实bridge runVerifiedAction生产回执有这些字段。|
| JEV-07 | 真问题，有限runner与引号边界修复已独立闭合 | 有限常见runner/任务名避免cat/ls/echo参数误命中；失败检查算已运行是现有明确设计。新增commandSegments只在单/双引号外拆命令分隔符，并正确保留转义字符，不扩大runner支持。真实unverifiedWrites负例及引号结束后实际pytest正例覆盖本次边界。|
| JEV-08 | 真问题，最小修复闭合 | Call cancellation hook在enqueue之前安装，continuation直到body读取结束才resume；response.use保证关闭。已知contentLength超限立即拒绝，未知长度source.request(limit+1)受限，正常<=limit读取。测试是真实Source读/阻塞有限本地Call，非源字符串断言。|
| RCI-01 | 真问题，修复精确 | only reply mutable=true；PendingIntent仍显式指向receiver，token绑定和验证未动。approve/deny/stop/retry默认为immutable。测试检查实际Notification action RemoteInput及PendingIntent属性。|
| RCI-02 | 真问题，修复精确 | ChatService实际调用NotificationApprovalDispatch helper；一个LAZY appScope job先注册session owner后start，receiver只await acceptance。callback位于saveConversation及approval audit之后、inline continuation之前。restoreWriteContext使用captureRestoreEpoch继承原验证epoch。真实ConversationSession测试证明owner已安装、Stop/新操作取消、前失败false/后失败仍true、未启动即取消settlefalse。|
| RCI-03 | 真问题，公版接线闭合 | running工具phaseStatus另传toolTitle(active,true)，实际publicVersion和Xiaomi island读取同一publicTitle。unlocked title保持用户原配置。测试构造实际通知和miui.focus.param验证command不泄漏。|
| RCI-04 | 真问题，CAS责任边界精确 | appendTurn仅自身progress update传expectedStatus=RUNNING；AgentTaskStore mutex内先比status再persist/publish。COMPLETED/CANCELLED后的迟到projection被拒，正常显式terminal→RUNNING仍可。未加全局非法transition框架。|
| RCI-05 | 基线验证缺陷，测试修复符合生产 | AmberAgentApp.kt:83确实启动jevModule，测试baseline列表漏了它；当前列表补回真实启动模块。追加的per-definition injectedParameters已逐个对照实际factory/constructor：RecapStore的File/默认Json、JevClient的内建transport/默认Json、Coordinator的内建usageStore/默认metrics、Runtime的默认backgroundScope、ScreenRunner的默认mainDispatcher均非get()；DI-owned JevClient/coordinator/policy/calibration/runtime仍由graph要求，没有全局类型豁免或生产重复binding。|

## 独立发现和处置

### P2：普通 input type=button 候选被禁用（已修，待 root GREEN）

生产bridge describeNode对普通 inputbutton提供input_type=button，原hard set把该类型和submit/password一起全拒，导致Next/Open article等只读按钮根本进不了选中风险复核。相比普通button元素，这是没有行为依据的不一致。worker已仅移除button类型硬拦，保留敏感label与submit等类型，且button仍不可TYPE。

已读新增两个真实行为测试：parser保留Next、排除Send及submit；runner确实选中Next ref且经过choice和safe_action两题后派发。代码修正合理，不能在未取得root运行结果前称GREEN。

### P2：引号内分号后的检查文字被当实际执行（已修，待 root GREEN）

具体输入：`echo "Suggested checks; pytest tests"`。修复前runsCheck不区分shell引号，split得到第二段`pytest tests"`，首词pytest命中CHECK_EXECUTABLES，从而unverifiedWrites返回null。实际shell只echo文字，无检查执行。worker已加入小型commandSegments扫描器：只在quotes外拆分;、换行、&&、||，双引号和无引号的转义字符跳过下一字符，单引号内反斜线保持字面。独立复看quoted/escaped suggestedchecks负例与引号闭合后的真实&&pytest正例，实际调用unverifiedWrites而非测重建算法。实现局部闭合，未做完整shell解析或扩大runner名单。

## 已排除/证据边界

- ChatVM.setConversationAutoApproveToolCalls(true)的legacy helper可直接Pending→Approved，从而绕过新auto gate；但全src/main查证该VM方法目前没有调用/引用，helper也仅它调用。没有可达生产UI触发证据，降为未接线候选，未把它作为blocker要求改写ChatService。
- HTML disabled/readonly并非所有普通元素都由当前bridge describeNode输出；bridge真正click/type会再通过isDisabled/isReadOnly拒绝，因此parser测试证明的是它收到metadata后的处理，不能宣传为所有DOM元素已经前置排除。没有据此扩大修复面。
- 授权用户人工批准后不再Jev复核、Jev AutoApprovalGate失败维持原自动批准、失败的真实测试命令也算已运行，均为已记录设计，不是假装bug。
- JVM行为测试不能证明真实Jev云服务判题质量、真实OEM通知点击/RemoteInput、设备DOM现场或广播耗时。编译/执行结果等待root统一提供。

## 最终复看

inputbutton与quote-aware scanner两个独立复审项已经完成局部代码修正及实际API正反测试；没有残留产品代码blocker。Koin的新增声明均限定到具体definition且与真实工厂一致。root统一运行测试/构建后应追加运行证据；本reviewer没有把静态复看写成测试通过。
