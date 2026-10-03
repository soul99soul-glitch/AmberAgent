# P4 独立 UI 与调用链审查

范围：P4 专项操作提案与作者审批、结构合同与计划提案、作者brief返修、证据及人物身份、状态救援重建。只读生产代码；本报告由本review agent独占。最终结论：P4本范围源码、实际方法及设备UI gate通过，P4-UI01/02/03和候选标签语义问题均关闭。以下保留准备、反证及修复复验记录，准备阶段的pending不代表最终状态。

## 当前入口与证据边界

- 现有Workspace「项目控制」承载本章计划／未来计划／偏好及代笔；「设定→剧情」承载确认事实、连续性审计与查看原文。现有`NovelGhostwriteStartSheet`提供完整计划Scroll确认；GhostwriteCard为冻结计划、候选、审稿与durable状态的投影。P4应保持新能力的按钮能沿真实父页事件到creation/repository，不能只在新组件内部改变视觉状态。
- 现有全文审计通过真实progress callback更新已完成块／总块与字符数，失败或旧稿提示有明确入口；P4救援进度另核真实持久状态，不从计时器推断。
- P2已确认的跨branch pending提案与计数投影问题列入P4 backlog；待最终实现按expectedCas.branchId投影复核，不重复创建存储结构。
- 当前未审查冻结后的P4源码，也未获得P4最终安装图。现有组件的44vp／Scroll声明仅用于安排设备路径，不能关闭新能力视觉gate。

## 实现冻结后审查项

| 检查项 | 真实链与完成条件 | 验证方式 |
| --- | --- | --- |
| 专项改稿／范围删除／最近N章回退 | 工具结果先生成当前branch/CAS提案，作者确认后才提交；取消不改正文；最终批准目标与确认展示一致 | 实际方法+repository结果；设备操作前后 |
| 改稿确认全文 | 长source和replacement可完整滚到尾部，章节标题、范围和delete目标不被摘要代替；审批动作不被正文挤出不可达 | 源审；长正文新JPEG及AX；实际确认 |
| pending提案来源 | active branch列表及count一致，旧branch提案不混入；切branch迟到响应不能重开旧确认 | 实际方法／领域测试；设备切branch |
| structured合同 | draft/confirmed显示与提交一致；goal/must/mustNot/endingHook/visibleFacts完整保存，digest由真实提交产生；一键计划提案不绕过作者审批 | 实际方法及真实prompt／runner参数；设备输入保存返回 |
| upcomingArc | 最多8条由生产规则限制，显示与实际消费相同，不仅截断可见列表 | 源审／实际方法；长条目设备图 |
| 作者brief返修 | paused job入口可达；IME输入提交后同job返修真实消费brief，关闭/取消不提交，错误保留草稿 | 实际方法；键盘新截图及AX；实际状态结果 |
| 再生成语义 | 允许改事实的重生成与仅润色操作准确标明；按钮到正确mode，未误入仅润色job | 实际方法及provider最小请求证据 |
| 人物身份／结构证据 | 列表使用持久投影；证据能回查source与范围；身份选择到真实更新/澄清，有错误与未解决反馈 | 源审+实际方法；实际证据/选择/结果截图 |
| 状态救援 | started/running/completed/failed进度来源真实；失败可见并沿同任务重试；工作区/分支变动后不套用旧结果 | 实际方法；设备fixture成功/失败/重试截图及状态 |
| 局部UI | 关键动作至少44vp；长名/标签正常wrap；弹层主体滚动、footer及关闭可达；Keyboard RESIZE时输入/保存不被遮挡 | 最终新JPEG+AX bounds/enabled，并实际点击返回 |
| 按钮响应 | 文本/目标变更后enabled与颜色实时变化，不沿用P2 Action primitive变化谓词失去更新模式 | 源审+实际方法；输入前后AX及实际动作 |

## 给主agent的实际设备 cases

1. 自建验收项目，使用现本地fixture provider；保留清晰不同的source及replacement正文（至少两屏并有尾部标记）。从真实工具/审校入口产生proposal，打开确认、滚到尾部、先取消确认未改稿，再批准核对真实章节结果。保存范围名称、两处尾部标记、审批按钮完整可见的JPEG+AX。
2. 多章删除或最近N章回退预览核对章节范围与目标，取消保留；批准只执行预览范围。项目保留一条旧branch pending后切branch，核对当前列表与count。不要用仅文本Alert代替完整范围确认的设备证据。
3. 合同录入带长goal/约束、endingHook及visibleFacts；键盘开启截图确认输入/保存或可滚至保存，保存draft/确认后离页返回核对持久状态。计划提案先看预览/审批再核实际合同/Arc；设备仅需证明关键可达流，详细8条边界由方法测试覆盖。
4. 暂停实际代笔，输入带唯一标记作者brief，键盘下确认返修；记录同job后续stage与fixture实际接收到brief。失败分支仅在可控fixture下产生，保留错误、输入和可操作重试AX，不靠修改app sandbox造状态。
5. 有真实人物/事件后从状态救援入口启动，保留真实进行中进度与完成图；可控失败后重试沿同任务。身份澄清若实际出现，从真实列表选择并核持久结果；无法触发时明确设备未触发，用实际方法覆盖，不伪称视觉已验。
6. 每个新弹层至少留一张主体/正文长内容图与底部完整动作图；输入类留Keyboard图。AX记录目标bounds、enabled、opacity及pagePath。Scroll首帧裁剪需用滚到底及实际操作判定，不能仅凭单帧截断报错位。

## 正式冻结源码与实际方法复核

已只读核对`NovelOperationsPage`、`NovelStatePage`、`NovelChapterContractPage`、`NovelChapterReaderPage`、`NovelRevisionBriefSheet`、`NovelAuditReportView`、Workspace新增薄接线、生产`NovelWorkspaceService.proposeManuscriptOperation`与creation对应caller。三个新页面已在`main_pages.json`注册；Workspace项目控制入口实际push注册页面；paused/failed才显示brief入口；Reader两个语义入口均经过冻结目标的brief sheet。

独立运行`novel_operations_state_ui.test.cjs`、`novel_chapter_contract_ui.test.cjs`、`novel_revision_brief_ui.test.cjs`，当前30/30通过。覆盖真实UI方法，合同两项连接真实creation/repository并核draft/confirmed实际context消费及切分支CAS拒绝。下述额外组合检查暴露测试中resolve把reload stub掉遗漏的问题。

### 新确认问题

| ID / 严重度 | 触发与当前结果 | 定位 | 最小修复及复核要求 |
| --- | --- | --- | --- |
| P4-UI01 / P2，已关闭 | 修复前：本页输入replacement生成按段提案，再确认采纳成功。`resolve→reload`仍按replacement非空对比旧章与刚提交的新章，误判外部正文变化；旧project/CAS/pending提案未刷新，同时error称分支/原文变化、notice称已采纳。真实写入已完成但页面事实不闭环。 | `NovelOperationsPage.reload`原文变化保护及`resolve`成功分支 | 精确清理与已批准参数匹配的草稿，resolver真实返回独立更新提案状态；保留其他未提交草稿和外部branch/source变化保护。actual resolve+actual reload+真实repository组合测试通过；下述两轮反例均关闭。 |

复现直接提取当前`chapters/selectedChapter/reload/resolve`方法，模拟creation批准从OLD BODY提交NEW BODY并返回新CAS与accepted提案：`accepted=true`但可见正文OLD BODY、proposal pending、casHead before；error为“分支或改写原文已变化，输入已保留”，notice为“已采纳修改，正文与关联状态已更新”。未修改生产代码。

第一轮修复已仅清除与approved operation的chapter/new_text/expected_text完全匹配的replacement，delete仅清对应ID序列；原复现green，并新增realUI→actual service→repository→actual reload测试通过。同时source_digest改为生产`materialSuggestionChapterDigest(chapter)`，真实stage/approve不再仅靠mock成功。新P4测试32项+Reader version fork回归6项独立38/38通过。

第二轮同finding补充精确组合：提案A生成后继续将replacement编辑为B，再批准A。B应保留旧source/CAS供重读核对；修复前保护准确保留B，但在更新提案投影前throw，已经accepted的卡仍pending。独立actual方法复现`accepted=true / visibleProposal=pending / draft=UNSUBMITTED B / casHead=before`。最终修复直接使用resolver真实返回先更新对应提案事实，再执行原source/CAS保护，不重绑B，不清空其他草稿。新增真实repository组合测试已green，B和旧scope均保留，卡正确accepted。

最终独立重跑当前P4三组actual方法33项，另加Reader version fork回归6项，39/39通过。P4-UI01源码闭环已关闭。新生产`source_digest`与薄service活跃运行门禁亦由真实UI→actual service→真实repository测试核对。

### 内部已修项目的独立确认

- atomicStateCAS：`creation.readStructuredState`只读一次repository snapshot，并从同一project构造state、operation、cas及人物经历；UI `reload`一次性采用该结果，source与CAS未通过第二次读取拼接。
- 失败完成文案：State rebuild/sync仅status completed显示完成，failed保留operation.error；实际方法测试failed结果不宣称完成。
- proposal前置与全文：Operations `ProposalBody`展示全部oldText/newText及段落范围，没有maxLines截断，审批动作在同一外层Scroll；focused repair提案前置。完整可滚达仍需最终设备证明。
- hidden迟回：Workspace brief打开受openToken+samejob约束，hide/disappear使token失效；修复提案及查看审校证据受alive/hidden/token阻止迟回导航；State证据完成前核visible。相应实际方法已通过。
- Entry根节点：Operations/State/Contract的theme ForEach外有Column单根；未用ForEach直接作为Entry根。
- 暗色输入：Operations的count/start/end/replacement与合同全部输入明确INK/INK3/SURFACE2；此项只关闭源层漏设颜色，最终暗色可读性仍等图。
- ordinal fallback：Operations与State章标签使用project真实index+1作为`novelChapterOrdinal` fallback；Reader同样使用snapshot真实章index+1。
- 44minWidth：Operations拒绝动作与合同清除动作已加minWidth44；其他操作标签超过该长度并声明44/48最小高度。设备bounds需另验。

合同另独立核对：保存contract后只刷新contract，保留未保存arc；保存arc反之；确认清除冻结CAS；保存后若settings/branch变化则expectedCas=null而保留草稿；model proposal只填字段不persist，不标新建议confirmed。普通输入的IME实际提交和长文本下Scroll/保存可达仍归device gate。

## 最终构建前增量复核

第二批实施者报告的修复已独立逐项读源码，并重新运行P4三组actual methods及Reader fork，当前37项P4+6项fork **43/43通过**（是本review执行的组合；不借用实施者另外48项测试数）。

- 全部连续性修复：单条入口委托`repairAuditIssues([issue])`，全部入口传`auditReport.issues`，都到真实`creation.repairContinuityIssues`并只路由一个proposal。输入为空／审计仍进行／旧稿报告／活动任务会阻止启动。取消await真实`cancelContinuityRepair`后才释放busy，旧repair的catch/finally不能越token覆盖新操作。hide/disappear触发真实取消并invalidate token，不仅隐藏进度。实际methods验证全部issues同proposal、cancel尚未完成时busy保持、迟到结果不导航。
- State timeline：页面接收`readStructuredState`的领域prune结果后，`timelineEvents`按项目有效章顺序排列全部events，不筛`entityRefs`。领域prune已排除陈旧摘要、失效章/资料及无法逐字回查的事件，因此环境事件的空refs有效。Timeline与人物经历共用EventRow/evidence；实际builder测试将空refs环境事件和人物事件均呈现，丢弃废弃章事件且不改持久排序。
- 多处修复预览：`previewKey`带chapterId、label、start/end、段落范围及局部index，外层ForEach实际调用此函数。同章同label两处repair及无章重复label不会复用同一UI节点；实际方法测试验证两组key。
- 身份确认：create/merge明确保存人物身份／别名供后续状态提取与经历关联，没有宣称旧events立即改refs；实际dialog测试核对两种文案。现有ignored动作保持准确。
- State／Operations短内容Scroll显式`Alignment.TopStart`，匹配Contract既有布局。root旧设备图的顶部空白已作为修复依据；本review仅关闭源层缺漏，未把旧图当作最终视觉通过。新图到达后再核标题下起点及完整动作bounds。

此增量未发现新的已确认缺陷。最终重建截图未到达，device gate仍pending。

## 设备分项复核：按段操作

独立view_image目测root第一轮`device/p4/`四张未受后续State/合同修复影响的图，并遍历对应AX。此分项通过；不代表P4所有页面最终视觉gate通过。

- `p4-paragraph-keyboard`：窗口因键盘RESIZE到1757px，原文尾`P4-SOURCE-TAIL`可见，replacement TextArea `[70,1071][1250,1701]`显示`P4-REPLACEMENT-TAIL`，输入位于键盘上方，左右20vp，没有文字或父页标题重叠。生成动作当时bounds `[70,1750][1250,1757]`仅露7px，属于父Scroll下方内容，需要滚父容器；它不是固定footer。随后实际生成提案证明操作可达，不凭这一单帧裁剪报错位。
- `p4-paragraph-preview-tails`：长source尾、完整replacement及尾标记均可滚达，没有maxLines摘要截断。拒绝 `[128,2560][282,2714]`、确认采纳 `[352,2560][549,2714]`均154px高=44vp，enabled=true，底部完整可见且不覆盖正文。
- `p4-reader-approved-body`：批准后显示replacement尾标记，同时第二段`CaptainRhea closes the eastern gate.`保留；验证此次首段替换与范围展示一致。正文两段布局无重叠。
- `p4-reader-version-preserved`：真实版本历史sheet仍列三条手动保存记录、预览动作均154px高，无列表对齐/间距问题。这張图证明历史记录可见，不单凭列表宣称旧版本全文内容已经设备预览核对。

State短内容对齐旧图修复后需最终新图；合同model-draft失败图不列通过证据，等待fixedsystem修复后的真实成功链。当前整体device gate仍pending。

## State 真实刷新缺陷与最终源层修复

本review已独立目测四张设备反证图，确认以下两项不是推测性key设计：

| ID | 实际触发/反证 | 最小修复与当前状态 |
| --- | --- | --- |
| P4-UI02 / P2，已关闭 | 救援已完成timeline有事件，但同页人物卡仍“尚未提取”；merge Rider后仍只有CaptainRhea。`p4-final-identity-after-merge`对比`p4-final-identity-cold-alias`，重入后Rider别名与经历出现。 | `experienceKey = materialId + digest(JSON.stringify(experience DTO))`，实际ForEach使用它。新增方法测试events0→2、合并alias/title变化会重建，同DTO重读稳定。source及下方最终新包同页device复验通过。 |
| P4-UI03 / P2，已关闭 | same-event id/body digest重新救援，timeline仍Local verification；`p4-state-same-event-rebuild`对比`p4-state-same-event-cold`，重入后出现P4-LIVE-REFRESH摘要。 | `timelineEventKey = event.id + digest(JSON.stringify(event DTO))`，Timeline实际ForEach使用它。actual builder测试sameID新summary key变、同DTO key稳定。source及下方最终新包同页device复验通过。 |

两项仅使用现有digest，未添加全局缓存/随机key/重试机制，不修改领域持久模型。当前最终source独立运行OperationsState24+Contract12+Revision9+fork6 = **51/51通过**。

## 先文事实证据与 Reader 原子读取

增量source独立核对`CanonicalEvidence`实际builder：每条reference展示先文章ordinal/title/逐字quote及44vp“查看先文事实”；回调保留issue与reference，Workspace以同一snapshot核`verifyAuditCanonicalReferences`、activeBranch及CAS.branch、digest、整数范围和逐字切片后push既有Reader。Reader reload也改为同snapshot采用project与branch；旧branch或旧正文证据清除focus并显示提示。actual tests覆盖c2→c3证据回查、三种陈旧source/范围/branch拒绝、完整builder，以及Reader单快照/迟回失效。最终Reader实际设备证据仍待root提供。

## 设备分项复核：State 布局及合同/走向

对以下6张图独立view_image目测并遍历AX：`p4-final-state-top-alignment`、`p4-final-state-rescue-completed`、`p4-final-contract-model-success`、`p4-final-contract-confirmed`、`p4-final-arc-keyboard`、`p4-final-arc-saved`。这些部分source随后未变化，分项结论可保留，但不能替代上述State刷新修复的新包复验。

- State项目名起点 `[70,363][1250,429]`，标题下正常间距，旧短内容顶部大空白已消失；失败0/3及真实错误行和重试动作无重叠，重试热区154px=44vp。完成图进度3/3，完成提示与timeline显示，长事件自然进入Scroll；源层和该图共同证明有效事件可达，未凭该图关闭人物卡旧内容bug。
- 合同模型成功图实际字段填入，AX含“建议已填入草稿…才写入工作区”，两个保存/确认动作均168px=48vp完整可见；confirmed图出现“本章计划已确认”并有44vp宽清除动作。建议→作者确认没有直接persist假象，输入和操作行未重叠。notice位于滚动出屏位置，AX bounds0不是可见文本证明，字段与新增清除动作才是本帧视觉事实。
- Arc键盘图两行输入可见，Window RESIZE为1757px、TextArea底部按视口裁剪；保存位于父Scroll下方，非固定footer。保存后图显示同两行、2/8计数，保存 `[70,2624][885,2792]`及清除 `[955,2624][1250,2792]`完整168px=48vp，间距正常。实际saved AX notice提供提交结果，单凭输入截图不宣称持久已验。

本轮没有新错位finding。P4整体product/device gate仍pending，等待最终新包同页State刷新、Reader canonical回查及批量修复diff审批的新证据。

## 最终新包 State 同页验收

最终包构建日志由root提供为`/tmp/amber-p4-state-ui-final-build.log`。本review独立view_image目测4张新图并遍历AX：`p4-live-before-rebuild`→`p4-live-rebuilt-same-page`，`p4-live-courier-before-merge`→`p4-live-courier-after-merge`。root记录操作未重进页面；截图与AX均为同NovelStatePage，来源正文仍相同。

- 同ID/body的事件重新救援后，当页摘要从P4-LIVE-REFRESH变为P4-FINAL-REFRESH；第一章新摘要bounds `[70,1511][1250,1659]`，证据查看仍完整44vp，第二章新摘要也实际可见。不是冷重入掩盖旧节点。
- 同页Courier合并前卡别名为CaptainRhea、Rider；合并后为CaptainRhea、Rider、Courier `[112,1567][1208,1637]`，待确认项消失，同时人物经历的P4-FINAL-REFRESH正文继续真实显示，不再回落“尚未提取”。长经历卡自然占高，别名、章标签和summary无重叠。

P4-UI02/03 device闭环均关闭，没有新增错位。整体P4 product/device gate仍pending Reader canonical回查与批量修复diff审批最终证据；State刷新项不再pending。

## 最终设备分项：先文事实、双修复审批及 Reader brief

独立view_image和AX复核8张最终图：`p4-audit-pair-result`、`p4-canonical-reader-focus`、`p4-all-repair-proposal`、`p4-all-repair-approved`、`p4-all-repair-reader-body`、`p4-reader-brief-keyboard`、`p4-reader-revision-result-bottom`、`p4-reader-revision-actions`。未确认新的错位、对齐、间距或控件大小问题。

- 审校结果展示685/685检查、2条问题；当前问题quote Rider returns与应保留的第2章事实CaptainRhea closes the eastern gate均完整，先文查看154px=44vp。实际点击后进入第2章Reader，标示证据528–563字符并显示对应逐字事实；没有误跳晚章或仅展示摘要。这一实际链和此前source范围/branch核验闭合。
- 全部修复在一个proposal中前置展示：同第3章的Rider returns→The returning rider brings a sealed letter，以及The storm ends at dawn→The storm clears before sunrise两组预览同时可见，没有key复用漏项。拒绝／确认采纳 `[128,2180][282,2334]`、`[352,2180][549,2334]`完整154px=44vp且enabled。批准后同卡为已采纳，审批动作移除；实际Reader第3章两处新句均出现，Rhea brings the map home和Courier waits outside保留，显示与审批一致。
- Reader定向修订sheet显示真实branch及第3章范围，明确允许按要求调整事实、先候选再收录。键盘窗口1757px；作者要求TextArea `[70,922][1250,1460]`，取消与生成候选均y1505–1659、44vp且enabled；输入/长范围文案/Footer不重叠。提交后返回Workspace显示唯一作者要求标记P4_AUTHOR_DIRECTION、原章和新候选，底部“收进章节”实际可见；这里只关闭候选及审批入口，不把未提供收录后图的该定向候选声称已收录。

以上分项device gates通过。P4本review只等待最终ghostwrite authorpreview/paused brief图片，不再等待canonical或双修复链证据。

## 代笔作者计划预览精准源审

`ghostwriteAuthorPlanPreview`只有current project的typed chapterContract存在且status confirmed，并且`ghostwritePreview.planContent === confirmedChapterPlanText(settings)`时，显示“本章计划已确认”及`chapterContractMarkdown(contract)`。否则返回原始完整planContent，未使用正则删作者文本。字段内作者主动写的Digest仍在Markdown中；legacy Status/Digest及typed mismatch原样返回。

helper仅作为StartSheet的planContent展示参数；`confirmGhostwriteStart`继续读取原ghostwritePreview并传给creation.startGhostwrite，原preview.planContent、冻结计划、provider模型内容未因展示改变。独立运行当前OperationsState25/25通过，新增actualhelper测试验证作者Digest、legacy、mismatch及冻结字符串不被改变。新作者preview视觉与paused brief真实链仍待最后图片，不冒充通过。

## 最终设备分项：代笔作者计划及同任务 brief

独立view_image目测`p4-ghost-author-plan-final`、`p4-ghost-author-brief-open`、`p4-ghost-author-brief-keyboard`、`p4-ghost-author-brief-same-job`、`p4-ghostwrite-failed-controls`并遍历对应AX，分项通过。

- 新包作者计划页显示“本章计划已确认”，位置、目标与冲突、必须发生、不可发生、收束、可见事实六字段；未显示模型Status/Digest header。卡内Markdown为可滚动正文；本帧末条可见事实尾部位于Scroll边缘，未与底部操作行重叠。返回修改`[70,2576][639,2730]`、确认并开始`[681,2576][1250,2730]`完整154px=44vp且enabled，左右20vp、按钮间12vp。root记录此次最终预览仅打开，未确认启动新任务；不把这张预览图称为新任务已冻结/启动的设备证明。
- brief打开时真实branch与第4章范围完整，空输入的开始动作enabled=false；输入唯一作者要求`P4_GHOST_AUTHOR_DIRECTION`后enabled=true，未重现P2 primitive传参导致按钮不刷新的bug。键盘RESIZE后TextArea`[70,782][1250,1412]`完整，取消和开始定向返修均y1505–1659、44vp，位于1757px窗口底部上方，长范围换行、正文和footer不重叠。
- 提交前failed-controls与提交后same-job冻结计划标识均为`nv_mure5vmw_9_aecilkxn:plan:4 · 627c51a3`，保留相同冻结来源。后图实际failed原因“审核结果必须是严格JSON对象”，与root故意invalid reviewer fixture一致；重试`[617,2056][869,2210]`和取消`[897,2056][1149,2210]`均44vp、enabled。图片及AX没有完整独立job ID字段，因此严格表述为设备冻结计划身份一致，same-job完整身份由root真实请求/领域证据确认；不单凭截短卡片宣称已读取完整job记录。
- 本review随后独立读取新增`device/p4/request-receipts.json`：localLogLine33真实`/v1/chat/completions` ghostwrite_writer记录ghostwriteBrief=true、priorGhostwriteCandidate=true、frozenArc=true；line34进入review。这份精简receipt没有contract标志，contract进入请求仍归root另读完整HTTP内容的证据，不把精简receipt说成四项均独立可读。该本地fixture证明调用链和可重试失败，不证明云模型创作质量；root已取消本次owned job清理。

## 最终候选标签复验

root的增量两包build/sign日志为`/tmp/amber-p4-counter-label-final-build.log`，已覆盖安装并新建一次own1章fixture任务。本review独立view_image目测`p4-candidate-label-final.jpeg`并遍历AX：候选副标题为“第4章·本轮候选”，bounds`[171,1928][967,1973]`；查看`[967,1843][1149,1997]`、重试`[617,2056][869,2210]`、取消`[897,2056][1149,2210]`均完整154px=44vp、enabled，无文本重叠/错位。错误明确来自故意invalid JSON reviewer，未宣称已审核完成。root将取消本次owned job清理。

三处实际生产表达式（GhostwriteCard178、ChapterVerdictCard110、PolishJobCard113）分别独立执行attempt0/2共6组：0显示本轮候选，2显示自动返修2次，candidate对象不变；没有改attempt、任务或请求。设备本轮实际证明attempt0标签，attempt>0由实际表达式验证，不伪称拍过正计数新图。`final-artifact-fingerprints.json`的11个源码和两产品共22个生成副本已独立重新计算SHA256，当前源码/生成副本/记录三方全部相等；root两包build/sign与设备安装证据独立列明，不把源码hash等同于安装成功。

## 当前状态

**P4 UI及调用链独立review gate：通过，可推进下一phase。** P4-UI01（批准后旧投影）、P4-UI02（人物卡stale）、P4-UI03（事件卡stale）已由精准源码修复、actual方法及最终设备复验关闭；候选attempt错误称“第N稿”已用三处纯展示修复并最终新包图关闭。没有剩余已确认UI/caller缺陷，也没有本review范围内待交付设备项。

证据范围保持准确：此review最后组合实际methods51/51、之后authorpreview helper组25/25及三处label实际表达式6组通过；设备证明本地synthetic provider真实调用、作者审批及结果读取、同页刷新、键盘/滚动/44vp热区。未将云模型质量、正计数标签实拍、未提供收录后图的Reader定向候选、全主题/全字号P7验收宣称通过；它们不构成当前确认的P4缺陷或待修项。
