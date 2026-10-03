# P5 Workspace 接线独立审查

范围：Workspace 普通生成冷恢复、归档投影与再次收录、收录后分析反馈，以及对应实际方法测试。生产代码只读，无设备操作。结论以各条状态为准；设备视觉 gate 由 root 完成。

## P5-WUI01：普通恢复的流式快照与 UI 历史前缀长度不一致

**P1，源码已修复并独立复核关闭。** 修复前 `recoverOrdinaryRun` 使用当前 `project.messages.length` 接入 `attachNovelRun`；后者对每个 snapshot 直接 `slice(activeHistoryCount)`。领域 `collectWithEvents` 此时仍直接发布 provider 的 raw `evt.messages`，retry 请求的历史来自原请求，不含已保存的失败片段；resume 则更新已有 assistant ID。当前书稿前缀比这份 raw snapshot 更长，导致新的流正文被全部切掉。完成后刷新能显示结果，但生成期间只剩状态提示。

独立执行实际生产 `recoverOrdinaryRun` 和 `attachNovelRun`：原历史 3 条、持久历史含失败 user/assistant 共 5 条，retry/resume 的 raw snapshot 都为 5 条。结果两者 `activeHistoryCount=5`、`visibleMessages=[]`。

修复前复现脚本：`/tmp/amber-p5-workspace-review-repro.cjs`，该脚本注入 raw provider snapshot，不能用来代表修复后 core 的 outward 事件。

修复后 `collectWithEvents` 对 ordinary snapshot 使用 `ordinaryRunMessages(live.ordinary, evt.messages)`；持久 request 建立后 `live.historyCount=ordinary.transcriptPrefix.length`。Workspace 初次接入 resume 使用旧 record 的 transcriptPrefix 长度，fresh retry 使用当前持久消息长度。

独立联合脚本 `/tmp/amber-p5-workspace-reviewed-outbound.cjs` 执行实际 production core `collectWithEvents` → actual Workspace `recoverOrdinaryRun`／`attachNovelRun`，包含真实 ordinaryRunMessages helper：retry historyCount=5、resume historyCount=3，两者均显示完整「新流正文及完整尾部」。fresh retry 与 server resume 语义保持不同，非普通工具续跑不变。

## P5-WUI02：再次收录候选创建成功但刷新失败时仍打开空表单

**P2，源码已修复并独立复核关闭。** 修复前 `cloneCollectedForCollection` 在 clone 后 `await reload()`，但 `reload()` 自己捕获读取失败并 resolve。随后继续 `openCollect(cloned.id)`；旧的 `project.messages` 没有这份 clone，`openCollect` 将其当缺失消息并打开空的段落／正文表单。

上述复现脚本执行实际 `cloneCollectedForCollection` 与 `openCollect`，刷新模拟实际的 catch-and-resolve 语义：输出 `openedMessage="clone"`、`draft=""`，同时已有“项目无法打开：读取失败”错误。当前成功测试把 `reload` 和 `openCollect` 都 mock 成无效果/spy，未覆盖该失败链。

Owner 已在刷新后增加当前项目实际含新候选的条件。独立重跑原生产组合复现，结果 `openedMessage=""`，保留读取失败错误，没有打开空稿表单；没有重试、重新 clone 或新增状态机。

## 已核对闭环

- 普通冷恢复只展示当前分支、无活跃 driver 且未 completed/waiting_user 的记录；partial 用原历史 ID 排除旧 assistant 上下文。复制保留完整真实 partial，并核 record ID。
- retry/resume 调用各自专属领域入口并接入同一现有 run handle；禁用 resume 不会暗中转 fresh retry。事件前缀问题已通过 core outward → UI 联合复核关闭。
- clone 作者确认冻结 CAS，异分支确认拒绝，原收录 receipt 不变。创建后刷新失败不开空稿表单，已独立复核。
- 归档只折叠已确认记录中的 discussion source IDs。write/prose 永远可见，其他 discussion 不受影响；展开 ID 只在当前页赋新数组，不写数据。归档卡的原文展开操作有至少 44 vp 高度，长摘要／决定自然换行。
- 设定提案渲染在折叠消息条件之外，orphan proposal 也有独立尾部入口，折叠不吞待采纳资料。
- 收录分析回调校验 projectId、branchId、collectionFeedbackToken；确认正文已落盘与资料分析失败分别提示，旧 owner/token 不能更新反馈。

## 验证和限制

现有 archive_clone_ui、ordinary_recovery_ui、恢复卡片 actual-method 组合 **14/14 通过**，日志 `/tmp/amber-p5-workspace-independent-tests.log`。测试通过不覆盖两项独立复现问题，不能作为整体闭环通过。

这是源码与实际方法层审查。未执行 provider、构建或设备操作，未把声明的尺寸当作实机视觉验收；新包明暗主题、长文本、键盘与实际边界由 root 统一截图检查。


## P5-WUI03：清空失败章节选择后卡片重试入口仍可点且静默无效果

**P3，源码已修复并独立复核关闭。** 修复前 `NovelPolishPage` 的底部“重试所选失败与未处理章节”按钮正确依赖 `retrySelectedIds.length>0`；但 failed job 的 `NovelPolishJobCard.actionRow` 仍仅依赖 `!busy` 显示可点“重试”。用户清空选择后点击此卡片入口，`retryFailed` 直接 return，没有重试也没有提示。Owner 已增加卡片 `retryReady`，failed 卡片取选中集合是否非空，jobKey 同步包含 retrySelectedIds；smallButton 的 enabled 属性与点击检查同时消费该资格。独立实际 actionRow 测试确认清空后 disabled、重选后 enabled、busy 时 disabled，没有修改重试事务。

## 独立润色页追加审查

核查文件：`NovelPolishPage.ets`、`NovelPolishJobCard.ets`、`NovelPolishCreateSheet.ets`、`novel_polish_page_ui.test.cjs` 及 production creation/repository/polish caller。

- active job 按原 jobId 读取；读进度、暂停、继续与取消分别调用现有领域方法，不创建额外任务状态机。
- failed／completed／cancelled 的 per-chapter outcomes 区分 success、driftSkipped、failed、unprocessed；只勾选 failed/unprocessed，保留用户取消勾选，不重做成功或漂移跳过章节。
- 创建先预览 exact chapter IDs 与项目偏好、冻结上下文及 CAS；修改选章／context 清除旧预览。start 只使用预览 receipt 的 IDs、contextOptions、CAS。
- 完成和取消会 reload 当前工作区 snapshot，下一批使用新的 CAS；跨分支重载清空选择及预览。model 不可用时仍显示任务投影与错误，不隐藏项目。
- job card key 包含 jobId、updatedAt、stage、cursor、outcomes 与 busy；result keys 包含同 ID status/message/time，章节与选择 keys 含 title/update/selection，避免结果沿用旧身份。
- per-chapter 长标题允许两行并省略，结果／提示行不强行固定总高；交互控件至少 44 vp，章节行至少 48 vp；上下文长正文位于页面滚动区。Sheet 的正文滚动与 footer 分离，已有正确 TopStart alignment。
- 未发现新的写入逻辑或 CAS 闭环缺陷；卡片空选择重试入口已关闭，见 P5-WUI03。视觉结论仍待 root 新包截图，源码尺寸声明不能证明实机无错位。

独立运行 `novel_polish_page_ui.test.cjs` **14/14 通过**，其中两项真实 UI-method → creation → repository 案例分别证明稀疏 IDs／项目偏好持久冻结、预览后偏好修改拒绝启动且不创建 job。日志 `/tmp/amber-p5-polish-independent-tests.log`。

恢复续接文案按作者可理解语义微改为“使用上次保存的响应继续生成”，保留“不重新发送全新请求”；未改变按钮与资格。原 resume behavior tests **5/5 通过**，窄 lint 无缺陷，日志 `/tmp/amber-p5-recovery-resume-copy-tests.log`、`/tmp/amber-p5-recovery-resume-copy-lint.log`。


## 最终源码 gate

本 reviewer 发现的 WUI01／WUI02／WUI03 均已精准修复并独立复核关闭，当前审查范围无剩余源码 blocker。

- 普通恢复＋clone actual-method tests **11/11 通过**（ordinary recovery 5 项、archive/clone 6 项），日志 `/tmp/amber-p5-recovery-clone-reviewed-tests.log`。
- 润色 UI actual-method tests **15/15 通过**，包含新增清空／重选失败集合卡片资格及原 2 项真实事务测试，日志 `/tmp/amber-p5-polish-reviewed-final-tests.log`。
- 实际 core collectWithEvents outward → 实际 Workspace recover/attach 的 retry/resume 联合反例转绿，脚本 `/tmp/amber-p5-workspace-reviewed-outbound.cjs`。

设备视觉、真实 provider 响应恢复、后台系统行为仍由 root 相应 gate 分别记录。本报告没有执行设备操作，也没有将源码闭环等同于这些实测结果。

## 最终独立设置页窄修改复核

只读核对 `NovelStandaloneSettingsPage.ets` 与 `standalone_entry_lifecycle.test.cjs`：该页不再声明全局润色文字 State／TextArea，也不再调用全局润色文字 getter/setter。cold reload 只读取真实四项 context defaults，savePolish 只写 includePlot、includeForeshadows、includeCharacters、includeDecisions，成功提示与实际保存范围一致。

四项 defaults 在 `NovelPolishCreateSheet` 及 `NovelPolishPage` 仍有实际读取；项目润色要求由 `NovelProjectSetupPage` 的 `setProjectPolishPreference` 写入项目，在 Workspace 可达“项目创建设置”入口编辑，与帮助文案一致。旧 getter/setter 模块与 AppContainer DI 接线保留，未借此删除死代码或改写其余职责模型／联网研究设置。

独立执行现有 actual-method tests **3/3 通过**，日志 `/tmp/amber-p5-settings-independent-tests.log`。包括 cold defaults 不依赖无 consumer 全局文字、保存只写四项 context，及 provider picker／实际 fixed-model 执行资格一致。此窄修范围没有新的源码 blocker；单页 lint 及最终包视觉 gate 由 owner/root 记录。

## P5-WUI04：定向重试后的已处理数量与进度条

**源码已修复并独立复核关闭。** root 发现并由原 owner 完成的 count-only 修复：`NovelPolishJobCard.progressSection` 的“已处理 / 目标”与进度条不再把执行 cursor 当作已处理数量，统一使用真实 `projectPolishOutcomes(job)` 中 status 非 unprocessed 的数量。`incompleteCount` 对应真实 unprocessed 数量；`currentChapterLabel` 继续按原 cursor 定位当前章，未改变执行事务、cursor 或重试选择语义。

独立检查实际 progressSection 执行测试：cursor=0，但保留 success、unprocessed、failed 三项结果时，显示 2/3，bar 参数为 2/3（66%），剩余 1；所有结果已处理时计数 3/3、剩余 0，失败和漂移跳过仍属于“已处理”，没有混为成功。

独立再次运行 `novel_polish_page_ui.test.cjs` **16/16 通过**，日志 `/tmp/amber-p5-polish-count-independent-tests.log`。本轮只读核查、更新本报告，没有生产代码或设备改动；单文件 lint／最终构建和视觉 gate 由 owner/root 记录。当前审查范围仍无剩余源码 blocker。

## P5-WUI05：工具续接同 ID 正文与二次失败 partial 联合复核

**源码已修复并独立真链复核关闭。** 最终 Workspace 首次续接定位实际 tool 所在 assistant index；cold tool retry/resume 使用真实 ordinaryRunHistoryCount，同 ID 后追加的正文不会被历史长度切掉。普通 fresh retry/current prefix 与 Responses resume/original prefix 的原规则保留。

最新部分内容展示基于真实 ordinaryRunMessages 和 helper history count，仅在 tool_continuation 下剥离 immutable `originalRequest.history` 同 ID 已有文字；不能以 presentation `transcriptPrefix` 中的上一轮失败 partial 为基准。新 message ID 保留完整正文。工具恢复文案说明保存的回答／工具结果不会重发原讨论，并明确重试会替换本次续接的部分内容，可先复制；通用确认不再声称部分内容永久留在记录。

独立联合 fixture：`/tmp/amber-p5-actual-adapter-workspace-review.test.mts`，由当前 `harmony/chat/src/test/novel_ordinary_http_recovery.test.ts` 复制到临时目录，仅注入实际 Workspace continue／recover／attach／partial／copy 方法作为消费者。未仿写 core 或 adapter，调用真实 Creation、互动适配器、AskUser tool executor/dispatcher、FileRepository 与 ordinary helpers。

真实调用验证：

- 为 AskUser 的同 ID assistant 加入已保存旧讨论，再回答工具；首次 provider 失败前的流尾可见，新增 partial 与回答结果持久保存。
- 冷恢复后展示和复制只含新增 `PARTIAL_BEFORE_FAILURE`，不包含旧讨论。
- 第一次 cold retry 再次失败，新的 partial 以第一次失败文字为前缀 `PARTIAL_BEFORE_FAILURE_SECOND`；最新实际 UI 方法仍显示及复制完整新 partial，没有错误剥去第一次失败前缀。
- 再次明确 retry 成功，同 ID UI 流尾与最终持久正文包含完整新回应；用户请求只保留一份，AskUser 回答执行仅一次，后改模型／分支偏好不进入冻结续接请求，失败 partial 不进入实际 provider retry 输入。
- 实际 tool record 未开放 server resume 时，Workspace resume 入口不能静默变为 fresh retry。另一个真实 Responses HTTP adapter fixture 则验证同 response ID 的 GET 恢复通过实际 Workspace 消费者显示完整 `Hello there`。

独立联合 fixture **4/4 通过**，日志 `/tmp/amber-p5-actual-adapter-workspace-review.log`；最新 ordinary UI 7 项＋RecoveryCard 5 项 **12/12 通过**，日志 `/tmp/amber-p5-askuser-ui-final-independent-tests.log`。HTTP/provider 使用确定性 fixture 响应，验证真实生产适配器和调用链，没有访问真实云模型或操作设备。当前该范围无剩余源码 blocker。

## P5-WUI06：Workspace 同 ID 润色卡片停在旧投影

**源码、实际方法及 root 新签名安装包同页设备自动刷新复验均通过，WUI06 已关闭。** 原设备案例为 Workspace 卡片停留 0/2／章 1，而独立润色页同任务实际为 2/2 混合结果；修复后以下同页连续设备证据确认卡片会随任务刷新。审查没有扩散到其他任务卡。

独立重跑实际 Workspace schedulePolishPoll／pollPolish 方法复现 `/tmp/amber-p5-workspace-polish-poll-review.cjs`：cursor 0→1→completed 2，State 分别赋新 job，两个请求均按原 jobId，token 正常续排，最终不留 timer，错误为空。此证据排除当前案例的 poll 没运行，不能证明 ArkUI 卡片已刷新。

核对 root 离线官方文档 `/tmp/amber-p5-builder-doc.txt`：私有 Builder 推荐直接通过 this 访问所属组件状态；默认参数按值传递时，状态改变不会引起 Builder UI 刷新；多层 Builder 的每一层均需保留引用才能动态刷新。最新 Card 的 jobBody、header、progressSection、stageSection、warningSection、candidateSection、reviewSection、failureSection、resultSection、actionRow 以及 progressBar 均无参，实际 UI 表达式直接消费 this.job；detailToggle 仅传静态 section，smallButton 仅传静态 actionKind，动态 busy／retryReady／展开状态直接读 Prop／State。outcomeRow 只接收既有 ForEach result，沿用 status/message/time 内容 key，章名直接读 this.job。没有 job 层 ForEach 重挂、额外 DTO 或 poll 修改；三个详情展开 State 保留在同一组件实例。stage／warning 左对齐、根 Scroll TopStart，交互最小 44 vp。

独立再次执行 `novel_polish_page_ui.test.cjs` **17/17 通过**，日志 `/tmp/amber-p5-polish-builder-independent-tests.log`。新增测试转译并调用实际嵌套 Builder 整链：同一实例、同一 jobId 从 cursor 0／0/2，更新到 cursor 1／1/2 与新候选、新审稿、新失败和提醒，再到 completed／2/2 混合结果；文字及控件读取最新值，已展开详情保持展开，点击仍可收起。清空选择／重选／busy 控件测试与两项真实 Creation／Repository 事务测试同时通过。

上述方法测试证明生产方法消费最新值、同实例详情状态保留及参数合同符合官方说明；ArkUI 自动重绘由下述新增设备证据证明。单 Card lint 由 owner 报告 NoDefects；本 reviewer 未修改生产源码或操作设备。

### root 新包同页连续设备证据独立复核

实际目录 `docs/reviews/2026-10-03-standalone-completion/device/p5/`，逐一读取 AX JSON 并目视对应 JPEG：

| 同页序列 | 精确证据文件（该目录下） | 可见内容与坐标（设备原始 px） |
| --- | --- | --- |
| 热通知进入 Workspace，任务运行 | `card-final-hot-workspace-writing.json`、`card-final-hot-workspace-writing.jpeg` | 0/1、第 4 章、正在润色；暂停／取消。执行阶段 `[108,1300][263,1345]`，流程行从 x=108 开始。 |
| 原页暂停 | `card-final-paused.json`、`card-final-paused.jpeg` | 仍为 0/1、第 4 章，状态变为已暂停，流程增加“（已暂停）”；继续／取消。阶段标题 x=108，流程自然换行。 |
| 原页继续 | `card-final-resumed.json`、`card-final-resumed.jpeg` | 回到正在润色、暂停／取消，0/1 与第 4 章保留；阶段标题 x=108。 |
| 原页自动完成 | `card-final-automatic-completed.json`、`card-final-automatic-completed.jpeg` | 1/1、第 4 章、已完成，满进度条；“1 已收录 · 0 漂移跳过 · 0 失败 · 0 未处理”、第 4 章结果“已润色并收录”可见，结果为展开状态并有“收起”。执行阶段 `[108,1363][263,1408]`，流程与结果左边均为 x=108。 |

四份 AX 的 pagePath 均为 `pages/NovelWorkspacePage`，root/window 为 `134:0`，卡片标题 accessibilityId=5039、进度=5043、当前章=5047、阶段=5052／5053 均沿用同一身份并更新文字；JPEG 与 AX 状态一致，阶段及结果左边对齐，暂停／完成长流程正常换行，无可见重叠或卡片内部裁切。

root 明确提供本轮执行事实：新签名包已安装，热通知进入后保持同一 uninterrupted Workspace，暂停、继续、等待完成期间没有 navigation 或组件重挂。本 reviewer 只读取既有 AX/JPEG，不自行操作设备；静态截图本身不能证明拍摄间没有导航，此连续性结论由 root 实际操作记录支持，稳定 AX 身份为补充佐证。该序列证明此新包／设备上的同页自动刷新和完成结果展开；用户主动展开的 candidate/review 在每次任务更新后保持展开仍由同实例实际方法测试验证，不把本序列扩展为所有设备或后台情境的证明。WUI06 的 pending device gate 已通过，当前审查范围无剩余 blocker。
