# E11 独立 B UI / 调用链只读审查

2026-09-28。已读 frozen E11-contract、E11-jev-council-design 与 harmony-arkts skill。只写本报告，不修改产品/测试，不运行 tests、lint、build、sign、device 或真实 Jev/席位 provider 请求。

**进行中：D 卡片/议会页面、SettingJev 与 root 共享 Row/Page/triage DI/Service/配置存储局部 ready 已审；Council DI 仍在装配，等待 source-ready 后补审，当前不宣称完整调用链 PASS。设备亮暗截图未提供。**

## 具体发现

1. **P2，问答交互点击区不足 44vp，已精准修复、源码关闭。** 新 CouncilSeatTools `:68–73` 真正复用 ChatMessageAskUserStep 处理普通及 Recipe/Plugin child ask_user。原 interactive option 为 font11+上下8，清空为 font11+上下4，提交为 font12+上下6，均没有 44vp minHeight。D 获 root 授权只在共享表单 `:64/140/146` 补 interactive option/清空/提交的 minHeight 44，清空另 minWidth 44；callback、表单载荷、parent locator 没有改变。批准/拒绝 AgentToolCapsule `:151–164` 原已为 44×44，没有重做或扩大 UI 重构。
2. **P2，真实完成结果被显示为无裁决，已精准修复、源码关闭。** 原 CouncilRunPage synth 分支仅显示 live/error，没有消费 retained snapshot.result.finalRecommendation。manager `:360–362` 终态删除 live；实际 ChatList 继续卡 `:461/524` 在 active 时获取 runId，完成后点击仍会打开同 run，原页面错误显示“无裁决内容”。D 在 `:207` 仅补 live 为空时显示实际 finalRecommendation，`:203–205` 仍错误优先。未新增持久恢复或历史系统，也未跑 UI 镜像测试。
3. **P2，普通工具唯一预览入口仅 32vp，已精准修复、源码关闭。** 新 CouncilSeatTools `:63–67` 经 ChatMessageTools 使用 AgentToolCapsule canOpen Row；没有 Recipe/SSH 摘要的普通工具，其唯一详情入口原 `:127` 明确 minHeight32。D 获 root 授权只将这一可点击分支改为 minHeight44；不可点击 else `:139` 仍32，批准/拒绝原44、状态/文字/回调保持，没有扩大胶囊重做或新增测试。

早期读源曾看到来源 key 仅 URL，给 root 提醒同页 search→scrape 的 `(url,service)` 身份接缝；D 最终 ready 时此前已改为 index-URL、能够唯一，并非新确认的终态碰撞缺陷。root 之后授权最窄统一成 `CouncilSeatTools :130` 的 `JSON.stringify([source.url,source.service])`，当前源已核，data/display 保持。**此提醒不计入上述已确认 P2 数量，也不据旧 read snapshot 宣称最终源有缺陷。**

## 已核源码路径

- JevApprovalFacts `:16–18` 仅 pending 且 caller 给出的当前 subjectHash 非空时读取事实，readJevApprovalTriage 校验 metadata 哈希及严格 yes/no/unknown 三态；三项同 muted 色，Flex wrap，无风险分数、推荐批准或新审批状态。root ready 的 AgentMessageRow `:192–207/389` 取实际 parent message.parts 索引再交 hash callback；ChatPage `:2888–2892` 先用 same-Service canonical、无快照时用当前 loaded conversation，preview `:2894–2897/3566` 缓存实际 locator。删除 emit 的 `conversationDeleted` 经 Page `:600–604` 同步清 conversation/rows，在 service map 移除后 viewed 已 null，因此实际删除路径没有旧 loaded fallback 复活标签。
- CouncilToolCard `:23–32/49–73` 将 Recipe/Plugin ask_user 只用于显示/questions/payload；提交仍携带 snapshot.pending 的父 CouncilApprovalRequest。普通批准/拒绝也传同一 request，未以 projected child toolCallId 定位 parent。CouncilSeatTools `:93–100` 按 messageId/partIndex/toolCallId 获取 request，child subjectHash 加入 card key，以免下一包步骤继续复用上一问答草稿。
- CouncilRunPage `:141–148` 回调实际 manager.submitToolVerdict；manager `:242–247` 对 run 状态与当前完整 request 做匹配，approval_wait `:18–24/94–104` 绑定 run/round/seat/conversation/message/part/toolCallId/subjectHash 和 saved part，并拒绝已提交/失效/取消请求。UI 不替代 C 的 durable verdict write。
- CouncilToolModePicker `:6–9` 明确 off/search/full、默认助手首个真实配置已启用且可用范围、保留原审批设置。CouncilPage `:350` 等实际 settings 加载后才允许选择，`:239/247/254` 固定实际本轮 toolMode 再 prepare/start，不让迟到初始化覆盖用户功率选择；动态席位规划与连通性请求显式 off。SettingModelCouncil 保存 await updateCouncilSetting 成功才清 dirty，失败留原草稿与错误。root PromptConfigStore `:113/333/362` 的 withSeats/parse/serialize 全部保留 toolMode，旧 JSON 规范为 off。
- CouncilRunPage 读 task.toolMode，不读后来修改的全局设置；订阅 live/tools 立即回放，离页与终态 stopTimers 退订/清 timer；取消调用 manager.cancel(runId)。manager seat finally 清 waiter 并发空 pending snapshot；pending source 没有活 manager 时 UI 说明已中断、不自动恢复工具。
- 新来源展示是实际 CouncilSource 列表，title/url 全宽 BREAK_ALL、44vp 最小行高，只有 http(s) 调系统浏览器；工具状态与轮次来自实际 turn/run，失败和中断不文案成完成。真实来源提取与 C saved-seat continuation 最终装配待补审。
- SettingJev `:100–154` 所有 makeJevSettings 重建都显式保留 approvalTriage/webAutomation；新增用途模式/各 allowance 分开更新，global OFF / SHADOW cap 以 resolveJevPurposeMode 实际显示。`:74–87` 用每次完整不可变草稿排 persistTail，await save 后才清错误，失败留下当前草稿与 retry `:364–368`，不再 save catch 静默吞掉。加载/状态回调有 alive/revision 门，旧四用途沿全局模式；Web 文案明确仅接缝、未接真实目标循环，不称 wired。
- Service `:621–644` 完成 pending 的真实 writeTail、失败/abort 门后才 fire-and-forget triage；reserve `:356`、delete `:290`、cancel `:654` 取消其旧任务。triage host AppContainer `:163–170` 注入实际 repository、settings/evaluate 与 same service queued mutate，无新 helper 反向 DI；`mutateStoredConversation :436–443` await update 后 await repository.save 再 apply/emit，供 A 在同队列内完成最后 settings guard。未把 UI publication 当耐久成功证据。

## 实际席位消费者补审

- 当前 AppContainer `:946–972` 对 search/full 注入真正 prepareCouncilToolRunner，OFF 仍原 runner；UI task.mode/独立 ModelConfig 原样进入 manager，没有当前 Chat UUID/provider/Google 凭据解析作为替代。RunPreparation `:98–112/144–169` 冻结首个真实 Assistant、原运行设置/skills/native ports/固定 MCP aliases，创建每席独立 activity 和 keyed FileStore source；makeLoop 消费 C 提供的 provider/model/beforeEffect，不调用 Chat seed/context/Page executor。
- C CouncilToolRunner `:84–92` 按 req.model 创建固定 OpenAI provider、tool capability 和每步实际 definitions；`:155–160` 从已保存 seat source 建 snapshot 再 publication。UI 保存的 CouncilApprovalRequest 经 manager waiter 返回 C 后，`:166–187` 重读同 store、重新核对 exact subjectHash/parent locator，apply 原批准/拒绝/回答并 await save，再发布并 continuation；包 child ask_user 不改为 child locator。缺 signal/取消/终态/冷 source 复用都不创建 UI 自动恢复路径。
- CouncilSeatStore `:51–61` serial 完整 JSON 写入并读取同值才 resolve，C save Promise 拒绝不会当可恢复审批成功。UI callback 的 boolean 只代表 waiter 接收，不代表 verdict 耐久已完成；随后 snapshot/真实 turn 负责反映状态。
- 来源 helper 消费真实 search_web `items`、scrape_web `urls`，排除 error/failed/denied/cancelled/outcome_unknown 和非法 URL，UI 不从最终文字猜链接。当前联合来源身份已核；C 输出反馈保留到同席位 context 后才产 final text/turn sources，后续轮次/裁判使用真实归档结果。
- 以上当前接线已读；仍等待 root 最终 integration-ready 通知确认源冻结，再收口总结果。域策略、pending before-effect/store-failure 与包完整执行证据由独立 A/生产 owner 负责，本审查未重跑其 fixtures，也未把源阅读当 provider 或设备接受。

## 布局和证据边界

新 mode、事实、来源使用实际主题颜色观察器；事实换行，来源长标题/URL 换行，Council 内容外边距 16vp，mode/source/cancel/tab/back 44vp，原页系统安全区沿现有结构。尚无截图，不能以源码证明亮暗即时刷新、窄屏、IME、安全区或实际点击；本报告不作视觉 PASS。其他 owner 的 controlled fixtures、类型/lint、root 构建签名与真实 provider/设备验收是不同证据。
