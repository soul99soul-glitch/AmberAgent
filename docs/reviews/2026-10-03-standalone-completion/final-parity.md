# 最终产品对照

截至 2026-10-03 当前共享工作树。小说 80 项严格沿原 A01–F05 ID；深读 11 项沿本轮 backlog 的 DR-01–11 ID。P1–P8 已按本轮范围完成，逐阶段独立 review 已关闭，无已确认未修问题；P8 最终回归、类型检查、双包构建签名、源码指纹、当前包覆盖安装、实际强制停止后冷启动及集成独立 review 均通过。此表只更新当前能力判断，不将旧矩阵“后置”状态继续当成缺失。

iOS 参照为 `/Users/arquiel/Downloads/AI/amberagent-ios` 的当前生产工作树：独立 `NovelApp` 实际使用 `iosApp/NovelCreation` composition/工具宿主；独立 `DeepReadApp` 使用自己的 UI/runtime 与共享 `IOSDeepReadDraftGenerator`。没有比较另一个 island 工作树、旧同名未使用页面或 iOS 装机品质。Harmony 为当前 `harmony` 生产源码，独立 bundle 分别 `app.amber.novel` 和 `app.amber.deepread.reader`。两个产品各自沙盒配置，未复制宿主密钥。

证据层级：**S**＝生产入口/调用链只读核实；**T**＝真实领域/仓库/ETS方法及受控端口测试；**H**＝真实 adapter/HTTP 请求或本地 HTTP 服务，仍为合成 provider；**D**＝本阶段签名安装包的实际 JPEG/AX/系统交互，括号限定已观察部分。任意一行 D 都不表示该功能所有边界通过。云端模型品质、官方 Responses 服务、Google 外网结果质量、所有公共源长期在线及无限后台均未由这些证据证明。

阶段依据：[P1](p1-completion.md)、[P2](p2-completion.md)、[P3实际设备/请求](p3-device-cases.md)、[P4](p4-completion.md)、[P5](p5-completion.md)、[P6](p6-completion.md)、[P7](p7-completion.md)、[P8](p8-completion.md)；具体独立 review 及系统边界索引见文末。最终验收依据 [回归](p8-regression.md)、[当前双包指纹](device/p8/final-artifact-fingerprints.json)、[冷启动设备记录](p8-device-cases.md)和[集成独立 review](p8-final-integration-review.md)。

## 项目、分支与文件

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| A01 项目列表、打开、重命名 | [NovelProjectListView.swift][I-NovelProjectListView]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [NovelProjectsPage.ets][H-entry-src-main-ets-pages-NovelProjectsPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 列表、打开、改名真实提交，重读保留；错误项目仍占列表项。 | 等效；菜单代替侧滑。 | P2/P4 S/T/D(改名冷读) |
| A02 损坏项目显示与重新读取 | [NovelProjectListView.swift][I-NovelProjectListView]；[NovelProjectRepository.swift][I-NovelProjectRepository] | [NovelProjectsPage.ets][H-entry-src-main-ets-pages-NovelProjectsPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 逐项目 loadError 可见、重新读取与定向恢复；不静默跳过。 | 等效；未造损坏项目设备样本。 | P2 S/T |
| A03 空白项目创建 | [NovelProjectListView.swift][I-NovelProjectListView] | [NovelProjectsPage.ets][H-entry-src-main-ets-pages-NovelProjectsPage-ets]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | 校验名称，创建项目及主线；空白与快速开始分开。 | 等效。 | P1/P2 S/T/D(创建) |
| A04 快速开始种子与再生成 | [NovelProjectListView.swift][I-NovelProjectListView]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [NovelProjectSetupPage.ets][H-entry-src-main-ets-pages-NovelProjectSetupPage-ets]；[project_metadata.ts][H-deepread-src-main-ets-novel-project_metadata-ts] | creationMode、原 quickStartSeed 持久；再生成可复用原种子或本次覆盖，原种子不被覆盖。 | 等效。 | P2 S/T |
| A05 项目删除与恢复已删除项目 | [NovelProjectListView.swift][I-NovelProjectListView]；[NovelProjectRepository.swift][I-NovelProjectRepository] | [NovelProjectsPage.ets][H-entry-src-main-ets-pages-NovelProjectsPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 软删前取消普通及 durable 任务；按真实 deletedAt 找最近删除项恢复。 | 鸿蒙额外软删恢复；iOS删除为 tombstone/清理。 | P2 S/T |
| A06 损坏项目旧版本只读恢复 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView]；[NovelProjectLifecycle.swift][I-NovelProjectLifecycle] | [NovelRecoveryPreviewPage.ets][H-entry-src-main-ets-pages-NovelRecoveryPreviewPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 有效旧快照只读浏览完整章节，返回后作者确认恢复；源漂移拒绝、损坏原目录保留。 | 等效安全恢复；只读页设备造损坏入口未验。 | P2 S/T |
| A07 完整项目包与运行历史备份 | [NovelProjectSettingsDetailView.swift][I-NovelProjectSettingsDetailView]；[NovelProjectPackage.swift][I-NovelProjectPackage] | [NovelWorkspaceService.ets][H-entry-src-main-ets-platform_impl-NovelWorkspaceService-ets]；[native_backup.ts][H-deepread-src-main-ets-novel-native_backup-ts] | 本端完整备份保存全分支/历史/候选/提案/undo/任务/未知字节；同身份恢复保运行记录。 | 备份目的等效；不读 iOS 私有 amberNovelProject 包。 | P2/P5 S/T/D(保存) |
| A08 导入预览与冲突处理 | [NovelProjectListView.swift][I-NovelProjectListView]；[NovelProjectPackage.swift][I-NovelProjectPackage] | [NovelProjectsPage.ets][H-entry-src-main-ets-pages-NovelProjectsPage-ets]；[import_conflicts.ts][H-deepread-src-main-ets-novel-import_conflicts-ts] | 公开/native各自真实校验预览；reject/replace/keepBoth作者选择；替换先确认停止活动运行，新身份重绑定已知引用并清原 cursor。 | 等效；未知文件字节不泛替换。 | P2/P5 S/T |
| A09 两端公开工作区直接互导 | [NovelWorkspaceBackup.swift][I-NovelWorkspaceBackup]；[NovelWorkspaceImporter.swift][I-NovelWorkspaceImporter] | [workspace_interop.ts][H-deepread-src-main-ets-novel-workspace_interop-ts]；[NovelWorkspaceService.ets][H-entry-src-main-ets-platform_impl-NovelWorkspaceService-ets] | 支持 iOS manifest字段与旧鸿蒙字段；实际导出/解析/安装验证公共内容及扩展。 | 公开内容互通；ZIP↔folder须转换；非私有全历史互导。 | P2/P3/P5 S/T/D(公开ZIP保存) |
| A10 工作区导入后的领域恢复 | [NovelWorkspaceImporter.swift][I-NovelWorkspaceImporter] | [workspace_interop.ts][H-deepread-src-main-ets-novel-workspace_interop-ts]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 恢复各分支正文、共享/覆盖资料、种子/模式、合同/arc/状态/inbox及已知新字段，保未知wire。 | 鸿蒙领域化全部列出分支；iOS主要main且重建身份；public不带私有运行/历史。 | P3/P4/P5 S/T |
| A11 正文导出与系统分享 | [NovelProjectSettingsDetailView.swift][I-NovelProjectSettingsDetailView] | [NovelWorkspaceService.ets][H-entry-src-main-ets-platform_impl-NovelWorkspaceService-ets]；[NovelShareSupport.ets][H-entry-src-main-ets-platform_impl-NovelShareSupport-ets] | TXT/Markdown/EPUB、公开ZIP/native保存及系统分享接真实文件端口。 | 等效且额外TXT/EPUB；设备只证明系统接收/非空分享文件。 | P5 S/T/D(保存分享) |
| A12 分支创建与切换 | [NovelBranchesView.swift][I-NovelBranchesView]；[NovelProjectSettingsDetailView.swift][I-NovelProjectSettingsDetailView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[NovelBranchSheet.ets][H-entry-src-main-ets-components-NovelBranchSheet-ets] | 当前快照建分支、切分支；活动普通/代笔/润色先明确停止再切换。 | 等效安全流程。 | P2 S/T |
| A13 历史 Fork 与高级分支管理 | [NovelBranchesView.swift][I-NovelBranchesView]；[NovelBranchOperations.swift][I-NovelBranchOperations] | [NovelBranchHistoryPage.ets][H-entry-src-main-ets-pages-NovelBranchHistoryPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 从真实历史checkpoint fork；分支改名/设主线/删除可达且受CAS及任务门禁。 | 等效；历史分叉保存原有效稿与当前共享资料基线。 | P2/P3 S/T/D(历史分叉改名) |
| A14 工作区撤销与多步回退 | [NovelBranchesView.swift][I-NovelBranchesView]；[NovelBranchOperations.swift][I-NovelBranchOperations] | [NovelBranchHistoryPage.ets][H-entry-src-main-ets-pages-NovelBranchHistoryPage-ets]；[history_selection.ts][H-deepread-src-main-ets-novel-history_selection-ts] | 按真实head祖先多步撤回，恢复后保留checkpoint链；游标不是反复撤回同一更改。 | 等效。 | P2 S/T |
| A15 章节版本浏览与恢复 | [NovelChapterReaderView.swift][I-NovelChapterReaderView]；[NovelChapterViews.swift][I-NovelChapterViews] | [NovelChapterReaderPage.ets][H-entry-src-main-ets-pages-NovelChapterReaderPage-ets]；[NovelChapterVersionPreview.ets][H-entry-src-main-ets-components-NovelChapterVersionPreview-ets] | 历史全文先看再恢复；后章约束可另开分支后恢复；源、分支、CAS同快照。 | 等效；手改/收录等现版本规则不冒充所有调用必留无限历史。 | P2/P4 S/T/D(旧版分叉全文) |
| A16 快速开始结构化建议与分项确认 | [NovelPromptCatalog.swift][I-NovelPromptCatalog]；[NovelGenerationReducer.swift][I-NovelGenerationReducer] | [quick_start_proposals.ts][H-deepread-src-main-ets-novel-quick_start_proposals-ts]；[NovelMaterialAdoptionSheet.ets][H-entry-src-main-ets-components-NovelMaterialAdoptionSheet-ets] | 结构建议绑定真实消息/分支，逐项编辑类别、别名、标签、注入及目标，再作者采用/拒绝。 | 等效；普通围栏文本不自动改资料。 | P2/P3 S/T |

## 创作会话与正文

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| B01 章节编辑查找与替换 | [NovelChapterReaderView.swift][I-NovelChapterReaderView] | [NovelChapterReaderPage.ets][H-entry-src-main-ets-pages-NovelChapterReaderPage-ets]；[chapter_find_replace.ts][H-deepread-src-main-ets-novel-chapter_find_replace-ts] | 章内精确查找、上一/下一、替换当前/全部；更新编辑草稿后确认保存。 | 能力等效；P7六个查找控件44vp、150vp正文及键盘前固定footer均由新包AX/实屏关闭。 | P4/P7 S/T/D(查找键盘) |
| B02 三页签工作区与项目控制 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[NovelProjectSetupPage.ets][H-entry-src-main-ets-pages-NovelProjectSetupPage-ets] | 会话、正文、资料及项目控制共用同项目/分支；专项页真实路由返回刷新。 | 等效；页签/控制布局不同。 | P1-P6 S/T/D |
| B03 讨论、写一段、写整章与模式记忆 | [NovelSessionView.swift][I-NovelSessionView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[context_builder.ts][H-deepread-src-main-ets-novel-context_builder-ts] | 讨论/续写/整章由同runKind映射执行；草稿按project+branch持久模式。 | 等效；模式存储位置不同。 | P3/P5 S/T/H |
| B04 输入草稿持久化与启动失败恢复 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView]；[NovelSessionView.swift][I-NovelSessionView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[ordinary_run.ts][H-deepread-src-main-ets-novel-ordinary_run-ts] | 输入草稿owner隔离持久，启动失败准确反馈与恢复；新输入不被旧失败覆盖。 | 等效。 | P1/P5 S/T/D(冷恢复) |
| B05 Ask User 问答与同运行续接 | [NovelLiveModelAdapter.swift][I-NovelLiveModelAdapter]；[NovelSessionBubble.swift][I-NovelSessionBubble] | [novel_interactive_adapter.ts][H-chat-src-main-ets-chat-novel_interactive_adapter-ts]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | AskUser作者回答沿同工具运行续接；失败冷重试不再提问，答案执行一次，迟到checkpoint不写旧owner。 | 等效调用链；真实云问答品质未验。 | P5 S/T/H |
| B06 小说 Agent 专用操作工具目录 | [NovelAppToolHost.swift][I-NovelAppToolHost]；[IOSNovelProjectToolExecutor.swift][I-IOSNovelProjectToolExecutor] | [novel_workspace_tools.ts][H-chat-src-main-ets-chat-novel_workspace_tools-ts]；[specialized_operations.ts][H-deepread-src-main-ets-novel-specialized_operations-ts] | 22项小说项目工具加AskUser，专属读直接返回、写产生现CAS proposal；作者批准才事务写入。 | 等效；目录名称无需逐个同名。 | P4 S/T/H/D(改名审批) |
| B07 小说讨论联网搜索 | [NovelAppToolHost.swift][I-NovelAppToolHost]；[NovelLiveModelAdapter.swift][I-NovelLiveModelAdapter] | [NovelResearchSupport.ets][H-entry-src-main-ets-platform_impl-NovelResearchSupport-ets]；[NovelStandaloneSettingsPage.ets][H-entry-src-main-ets-pages-NovelStandaloneSettingsPage-ets] | 独立开关→实际search settings→SDK search tools加入讨论runtime→真实tool/provider续跑。 | 等效接线；正文/只读轮不开放研究工具；云搜索质量未验。 | P1/P4 S/T/H(受控HTTP) |
| B08 讨论与正文等模式的工具隔离 | [NovelLiveModelAdapter.swift][I-NovelLiveModelAdapter] | [novel_interactive_adapter.ts][H-chat-src-main-ets-chat-novel_interactive_adapter-ts]；[model_running.ts][H-deepread-src-main-ets-novel-model_running-ts] | 正文/快速开始/润色/重生成tools none，讨论all，审计/合同只读工具集合按任务限制。 | 等效；未提供的工具调用不能执行写port。 | P3/P4/P5 S/T/H |
| B09 Agent 按段修改正式章节与审批 | [IOSNovelProjectToolExecutor.swift][I-IOSNovelProjectToolExecutor]；[NovelSessionBubble.swift][I-NovelSessionBubble] | [NovelOperationsPage.ets][H-entry-src-main-ets-pages-NovelOperationsPage-ets]；[specialized_operations.ts][H-deepread-src-main-ets-novel-specialized_operations-ts] | 编号段落/CRLF/UTF16真实范围冻结；旧/新段预览，批准后只替换选中段并保存旧版。 | 等效；CAS与来源漂移拒绝。 | P4 S/T/H/D(段落审批) |
| B10 Agent 回退最近若干章与审批 | [IOSNovelProjectToolExecutor.swift][I-IOSNovelProjectToolExecutor]；[NovelLiveModelAdapter.swift][I-NovelLiveModelAdapter] | [NovelOperationsPage.ets][H-entry-src-main-ets-pages-NovelOperationsPage-ets]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 最近N章按章ID找真实历史目标；范围先确认，一次恢复该snapshot，非机械N次undo。 | 等效。 | P4 S/T |
| B11 Agent 抽删指定章和中间章与审批 | [IOSNovelProjectToolExecutor.swift][I-IOSNovelProjectToolExecutor]；[NovelSessionBubble.swift][I-NovelSessionBubble] | [NovelOperationsPage.ets][H-entry-src-main-ets-pages-NovelOperationsPage-ets]；[specialized_operations.ts][H-deepread-src-main-ets-novel-specialized_operations-ts] | 任意及中间多章去重、一提案范围确认、一事务删除；标记后续剧情待同步。 | 等效。 | P4 S/T |
| B12 候选正文四段式收录表单 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView]；[NovelSessionSheets.swift][I-NovelSessionSheets] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[candidate_provenance.ts][H-deepread-src-main-ets-novel-candidate_provenance-ts] | 四段式选择段落、目标位置、编辑稿、作者确认；真正可收录候选才打开表单。 | 等效。 | P2/P5 S/T/D(收录表单) |
| B13 段落勾选更新实际收录稿 | [NovelSessionSheets.swift][I-NovelSessionSheets] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[candidate_provenance.ts][H-deepread-src-main-ets-novel-candidate_provenance-ts] | 未手改稿随勾选更新；手改不被覆盖，可显式重建；零段不能提交。 | 等效。 | P1/P5 S/T |
| B14 收录默认位置与重生成目标 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelChapterReaderView.swift][I-NovelChapterReaderView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | 续写默认并入；润色/重生成默认替换真实源章；新章按最大实际ordinal+1。 | 等效且保留手选其他目标扩展。 | P2/P5 S/T/D(章号目标) |
| B15 收录编辑稿取消保护 | [NovelSessionSheets.swift][I-NovelSessionSheets] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[NovelApprovalShell.ets][H-entry-src-main-ets-components-NovelApprovalShell-ets] | 段落/位置/正文变化取消先提交IME再放弃确认，busy期间不能关提交表单。 | 等效；收录取消按P5本表单证据，P7其他编辑IME样本不代替此表单全场景验收。 | P1/P5 S/T/D |
| B16 候选正文基线与 HEAD 收录资格 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelFactTransactions.swift][I-NovelFactTransactions] | [candidate_provenance.ts][H-deepread-src-main-ets-novel-candidate_provenance-ts]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 候选绑定branch/source正文digest/计划digest/完成状态，采用再核CAS；旧稿不因获取新CAS合法化。 | 等效安全边界。 | P4/P5 S/T/D(旧候选拒绝) |
| B17 已收录内容克隆后再次收录 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelSessionView.swift][I-NovelSessionView] | [collected_candidates.ts][H-deepread-src-main-ets-novel-collected_candidates-ts]；[NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets] | 作者确认克隆已收录消息，保原收录记录及root lineage，建立fresh provenance/CAS新候选；非polish、防重复/旧源。 | 等效。 | P5 S/T/D(克隆打开新表单) |
| B18 讨论归档入口 | [NovelSessionView.swift][I-NovelSessionView]；[NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView] | [NovelDiscussionArchiveSheet.ets][H-entry-src-main-ets-components-NovelDiscussionArchiveSheet-ets]；[discussion_archive.ts][H-deepread-src-main-ets-novel-discussion_archive-ts] | 归档准备→作者编辑摘要/决定→确认提交；取消无写入，不只提取资料建议。 | 等效。 | P5 S/T/D |
| B19 归档摘要决定确认与讨论折叠 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelSessionViewModel.swift][I-NovelSessionViewModel] | [NovelDiscussionArchiveCard.ets][H-entry-src-main-ets-components-NovelDiscussionArchiveCard-ets]；[NovelArchivePresentation.ets][H-entry-src-main-ets-components-NovelArchivePresentation-ets] | 已确认讨论原文保留、可折叠/展开；发送副本用累计摘要及明确决定替代该讨论。 | 等效；正文消息不随讨论折叠。 | P5 S/T/D(原文展开) |
| B20 收录后提示归档的条件与同步反馈 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | 正文收录与后续资料分析分别反馈，分析失败不说正文保存失败；归档提示按真实可归档讨论投影。 | 等效；不强迫收录即归档。 | P5 S/T/D |
| B21 富消息思考工具显示与正文隔离 | [NovelSessionBubble.swift][I-NovelSessionBubble]；[NovelGenerationLifecycle.swift][I-NovelGenerationLifecycle] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[transcript.ts][H-deepread-src-main-ets-novel-transcript-ts] | 文本/思考/工具/审批分开呈现；正文收录只取实际prose，raw canonical另保存。 | 等效目的；Markdown呈现器/动画平台不同。 | P4/P5 S/T/H |
| B22 用户停止与部分稿持久保存 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelSessionView.swift][I-NovelSessionView] | [NovelRunRecoveryCard.ets][H-entry-src-main-ets-components-NovelRunRecoveryCard-ets]；[ordinary_run.ts][H-deepread-src-main-ets-novel-ordinary_run-ts] | 停止真实abort；保存partial和原输入，冷开可完整查看/复制，未完整内容不标完成候选。 | 等效。 | P5 S/T/H/D(冷部分稿) |
| B23 失败与中断生成的精确重试 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelSessionView.swift][I-NovelSessionView] | [ordinary_run.ts][H-deepread-src-main-ets-novel-ordinary_run-ts]；[novel_interactive_adapter.ts][H-chat-src-main-ets-chat-novel_interactive_adapter-ts] | 持久原request/runtime/history/context/tool profile；显式重试请求不混失败partial；工具续接沿原operation。 | 等效原请求重试；服务端游标另见F02。 | P5 S/T/H/D(原retry body相同) |
| B24 独立 Markdown 阅读器与上下章菜单 | [NovelChapterReaderView.swift][I-NovelChapterReaderView] | [NovelChapterReaderPage.ets][H-entry-src-main-ets-pages-NovelChapterReaderPage-ets]；[NovelChapterVersionPreview.ets][H-entry-src-main-ets-components-NovelChapterVersionPreview-ets] | 独立Markdown正文、真实ordinal上下章/菜单、历史与来源证据定位共存。 | 等效；P7长标题/正文及主题实屏样本通过。 | P2/P4 S/T/D |

## 资料、上下文与剧情

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| C01 人物与基础资料 CRUD | [NovelCompendiumView.swift][I-NovelCompendiumView]；[NovelSessionSheets.swift][I-NovelSessionSheets] | [NovelMaterialEditorSheet.ets][H-entry-src-main-ets-components-NovelMaterialEditorSheet-ets]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | 人物/世界/大纲/要求等真实CRUD，编辑与删除受来源/CAS；共享与本支线明确。 | 等效；当前iOS生产为Compendium/SessionSheets，非退休MaterialsView。 | P3 S/T/D(人物保存) |
| C02 人物别名与身份澄清 | [NovelSessionView.swift][I-NovelSessionView]；[NovelDomainModels.swift][I-NovelDomainModels] | [material_fields.ts][H-deepread-src-main-ets-novel-material_fields-ts]；[NovelStatePage.ets][H-entry-src-main-ets-pages-NovelStatePage-ets] | 人物aliases实际保存/匹配/发送；待澄清可创建、并入别名或忽略，冷读不重复，后续提取使用决定。 | 等效；不按名字虚构旧事件entityRefs。 | P3/P4 S/T/H/D(身份合并) |
| C03 人物相关经历自动聚合 | [NovelCharacterPagesView.swift][I-NovelCharacterPagesView] | [structured_state.ts][H-deepread-src-main-ets-novel-structured_state-ts]；[NovelStatePage.ets][H-entry-src-main-ets-pages-NovelStatePage-ets] | 从已校验本章digest/quote/range事件按entityRefs聚合人物经历；当前分支只显示有效事件。 | 等效；抽取模型品质及任意远章推断不作保证。 | P4 S/T/H/D |
| C04 关系资料与自定义分类标签 | [NovelCompendiumView.swift][I-NovelCompendiumView]；[NovelSessionSheets.swift][I-NovelSessionSheets] | [material_fields.ts][H-deepread-src-main-ets-novel-material_fields-ts]；[NovelMaterialMetadataFields.ets][H-entry-src-main-ets-components-NovelMaterialMetadataFields-ets] | relationship、自定义类别、aliases/tags/injection字段编辑、提案及本端交换实际消费。 | 本端等效；iOS公开包当前不输出/导入tags，跨端会丢该扩展。 | P3/P5 S/T/D(标签别名) |
| C05 资料修订与分支继承覆写 | [NovelReducer.swift][I-NovelReducer]；[NovelBranchesView.swift][I-NovelBranchesView] | [material_inheritance.ts][H-deepread-src-main-ets-novel-material_inheritance-ts]；[NovelMaterialEditorSheet.ets][H-entry-src-main-ets-components-NovelMaterialEditorSheet-ets] | 项目base+branch override/hidden，显式恢复继承；共享编辑原子传播，private不被污染；历史fork保旧稿。 | 目的等效；采用canonical快照而非复刻iOS逐资料revision图。 | P3/P4 S/T/D(恢复继承) |
| C06 资料建议采用前编辑与目标选择 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [material_adoption.ts][H-deepread-src-main-ets-novel-material_adoption-ts]；[NovelMaterialAdoptionSheet.ets][H-entry-src-main-ets-components-NovelMaterialAdoptionSheet-ets] | 采用前可改字段/内容及明确新增或目标；来源/目标digest含optional presence，缺字段保原值。 | 等效；未知wire不伪装领域支持。 | P3/P4 S/T |
| C07 常驻、智能、关闭资料注入 | [NovelDomainModels.swift][I-NovelDomainModels]；[NovelInjectionPlanner.swift][I-NovelInjectionPlanner] | [material_injection.ts][H-deepread-src-main-ets-novel-material_injection-ts]；[NovelMaterialMetadataFields.ets][H-entry-src-main-ets-components-NovelMaterialMetadataFields-ets] | always/smart/off；smart标题/别名100、去重tag20、正文查询词累计上限20，确定排序实际选资料。 | 等效确定规则；不新建LLM分类器。 | P3 S/T/H/D(智能角色入选) |
| C08 普通创作自动注入剧情约束 | [NovelInjectionPlanner.swift][I-NovelInjectionPlanner]；[NovelWorkspaceContextAssembler.swift][I-NovelWorkspaceContextAssembler] | [context_builder.ts][H-deepread-src-main-ets-novel-context_builder-ts]；[novel_context_policy.ts][H-chat-src-main-ets-chat-novel_context_policy-ts] | 普通写作必要注入本章计划、要求、偏好、明确决定、伏笔、作者剧情、有效结构状态与归档；可选近期正文整块裁剪。 | 等效；新必要内容超窗明确停止，非截断事实。 | P3/P4 S/T/H |
| C09 小说 token 预算与规则预览 | [NovelGenerationLifecycle.swift][I-NovelGenerationLifecycle]；[NovelSessionView.swift][I-NovelSessionView] | [NovelContextPreviewPage.ets][H-entry-src-main-ets-pages-NovelContextPreviewPage-ets]；[novel_context_policy.ts][H-chat-src-main-ets-chat-novel_context_policy-ts] | 真实模型窗口/输出、system+tools开销与整轮历史共同预算；下一次include/exclude按owner使用后清除。 | 等效规则；token为估算/保守预留，非云端实测精确值。 | P3 S/T/H/D(预览→排除→发送) |
| C10 本次注入凭据与占用面板 | [NovelGenerationLifecycle.swift][I-NovelGenerationLifecycle]；[NovelSessionView.swift][I-NovelSessionView] | [NovelContextPreviewPage.ets][H-entry-src-main-ets-pages-NovelContextPreviewPage-ets]；[material_injection.ts][H-deepread-src-main-ets-novel-material_injection-ts] | 列入选/排除原因、分数、token占用与预算错误；实际发送同planner/renderer。 | 等效；预览与wire估算边界记录在context review。 | P3 S/T/H/D |
| C11 逐章剧情模块与事件时间线 | [NovelCompendiumView.swift][I-NovelCompendiumView]；[NovelDomainModels.swift][I-NovelDomainModels] | [structured_state.ts][H-deepread-src-main-ets-novel-structured_state-ts]；[NovelStatePage.ets][H-entry-src-main-ets-pages-NovelStatePage-ets] | 逐章指针加来源校验事件时间线，含entityRefs为空的环境/情节事件；人物投影另列。 | 等效目的；无需额外graph store。 | P4 S/T/H/D(同页更新) |
| C12 手动改稿后剧情与正文共同更新 | [NovelChapterReaderView.swift][I-NovelChapterReaderView]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [plot_projection.ts][H-deepread-src-main-ets-novel-plot_projection-ts]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | 手改、删除、恢复、收录在同事务更新确定性章指针；前章变更标后章stale，作者plot保持独立owner。 | 对齐当前iOS；不复刻退休的常规model plot draft。 | P2/P4 S/T/D |
| C13 剧情同步与未完成影响恢复 | [NovelCreationViewModel.swift][I-NovelCreationViewModel]；[NovelFactTransactionLifecycle.swift][I-NovelFactTransactionLifecycle] | [state_rebuild.ts][H-deepread-src-main-ets-novel-state_rebuild-ts]；[NovelStatePage.ets][H-entry-src-main-ets-pages-NovelStatePage-ets] | 全本分块救援checkpoint/失败重试/取消持久；成功段冷恢复复用；全成功原子发布并清门禁。 | 等效救援；单章delta或失败不假清needsSync。 | P4/P6 S/T/H/D(503→stale→3/3) |
| C14 待同步时生成门禁 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelGenerationReducer.swift][I-NovelGenerationReducer] | [creation.ts][H-deepread-src-main-ets-novel-creation-ts]；[repository.ts][H-deepread-src-main-ets-novel-repository-ts] | stale/unresolved阻正文/代笔/润色新启动，讨论可用；已有冻结任务按合法阶段恢复。 | 等效安全门禁。 | P4/P5 S/T |
| C15 连续性审计覆盖、证据与控制 | [NovelContinuityAuditView.swift][I-NovelContinuityAuditView]；[NovelContinuityAuditLifecycle.swift][I-NovelContinuityAuditLifecycle] | [continuity_audit.ts][H-deepread-src-main-ets-novel-continuity_audit-ts]；[NovelAuditReportView.ets][H-entry-src-main-ets-components-NovelAuditReportView-ets] | 全部有效正文预算分块/覆盖/失败/取消；核真章ID、digest、UTF16范围与逐字quote，早章证据可定位。 | 等效审计链；不保证模型发现所有远距离矛盾。 | P4 S/T/H/D(早章原文定位) |
| C16 单条与一键连续性修复 | [NovelContinuityAuditView.swift][I-NovelContinuityAuditView]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [continuity_repair.ts][H-deepread-src-main-ets-novel-continuity_repair-ts]；[NovelOperationsPage.ets][H-entry-src-main-ets-pages-NovelOperationsPage-ets] | 单条/批量冻结核验的先文及目标范围，模型仅提patch，按同章倒序合并；批准才改较后目标、存旧版。 | 等效；过期/重叠/伪证据不发布部分提案。 | P4 S/T/H/D(两patch一批准) |
| C17 伏笔新增、已解与重开 | [IOSNovelWorkspaceTools.swift][I-IOSNovelWorkspaceTools]；[NovelWorkspaceContextAssembler.swift][I-NovelWorkspaceContextAssembler] | [mutations.ts][H-deepread-src-main-ets-novel-mutations-ts]；[context_builder.ts][H-deepread-src-main-ets-novel-context_builder-ts] | 作者新增、标已解、重开伏笔；未解项实际注入普通生成。 | 鸿蒙保留额外作者管理，不声称自动推断解决。 | P3 S/T |

## 计划、代笔与润色

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| D01 共创和代笔模式持久化 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [project_metadata.ts][H-deepread-src-main-ets-novel-project_metadata-ts]；[NovelProjectSetupPage.ets][H-entry-src-main-ets-pages-NovelProjectSetupPage-ets] | creationMode共创/代笔项目元数据持久，切换仍受任务约束；不是由历史job反推当前模式。 | 等效。 | P2 S/T |
| D02 本章计划结构草稿与作者确认 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelDomainModels.swift][I-NovelDomainModels] | [chapter_contract.ts][H-deepread-src-main-ets-novel-chapter_contract-ts]；[NovelChapterContractPage.ets][H-entry-src-main-ets-pages-NovelChapterContractPage-ets] | goal/mustHappen/mustNotHappen/endingHook/visibleFacts/digest及draft/confirmed，作者确认保存；原文编辑清旧合同。 | 等效；draft不冒充confirmed。 | P4 S/T/H/D(六字段确认) |
| D03 据前文生成本章计划与提案审批 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelChapterPlanProposalLifecycle.swift][I-NovelChapterPlanProposalLifecycle] | [creation.ts][H-deepread-src-main-ets-novel-creation-ts]；[NovelChapterContractPage.ets][H-entry-src-main-ets-pages-NovelChapterContractPage-ets] | 一键据当前上下文readonly模型提案，固定planner/JSON指令实际进入HTTP，结果仍是待作者确认draft。 | 等效。 | P4 S/T/H/D(一键草稿) |
| D04 计划绑定与章节合同验收裁定 | [NovelChapterPlanAcceptanceLifecycle.swift][I-NovelChapterPlanAcceptanceLifecycle]；[NovelGenerationReducer.swift][I-NovelGenerationReducer] | [ghostwrite_start_preview.ts][H-deepread-src-main-ets-novel-ghostwrite_start_preview-ts]；[ghostwrite_runner.ts][H-deepread-src-main-ets-novel-ghostwrite_runner-ts] | confirmed计划在CAS下冻结，writer及联合review真实消费；批准正文/状态原子收录并消费合同。 | 对齐当前联合裁定；不复活旧单独候选audit/自动削合同。 | P4 S/T/H |
| D05 往后几章分支方向 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelCreationViewModel.swift][I-NovelCreationViewModel] | [chapter_contract.ts][H-deepread-src-main-ets-novel-chapter_contract-ts]；[NovelChapterContractPage.ets][H-entry-src-main-ets-pages-NovelChapterContractPage-ets] | 最多8条upcomingArc，普通及代笔首章/nextPlan冻结，writer/reviewer/planner实际请求消费。 | 等效；作者后改arc不改变已冻结job。 | P4 S/T/H/D(2/8保存) |
| D06 批量代笔完整生产流水线 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelChapterPlanAcceptanceLifecycle.swift][I-NovelChapterPlanAcceptanceLifecycle] | [ghostwrite_runner.ts][H-deepread-src-main-ets-novel-ghostwrite_runner-ts]；[NovelGhostwriteCard.ets][H-entry-src-main-ets-components-NovelGhostwriteCard-ets] | writer→联合review/state→原子adopt→下一章durable；暂停/取消/失败恢复同job。 | 等效调用链；真实云5/10章品质、长期后台未验。 | P4/P5/P7 S/T/H/D(同卡更新、裁决44×44vp、第6章入目录) |
| D07 代笔失败后的作者定向润修 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelSessionViewModel.swift][I-NovelSessionViewModel] | [ghostwrite_runner.ts][H-deepread-src-main-ets-novel-ghostwrite_runner-ts]；[NovelRevisionBriefSheet.ets][H-entry-src-main-ets-components-NovelRevisionBriefSheet-ets] | 暂停/失败job作者brief与候选digest/CAS核验后回写writer参数，旧候选/合同/arc保留且不泄漏下一章。 | 等效作者介入；复用原job checkpoint。 | P4 S/T/H/D(brief真实请求) |
| D08 代笔完成报告与再开下一批 | [NovelSessionViewModel.swift][I-NovelSessionViewModel] | [job_report.ts][H-deepread-src-main-ets-novel-job_report-ts]；[NovelGhostwriteReportCard.ets][H-entry-src-main-ets-components-NovelGhostwriteReportCard-ets] | 最近完成/取消批次投影真实receipt；历史报告不当active锁，可重新预览开下一批。 | 等效。 | P4/P7 S/T/D(动态任务卡→完成→目录；第6章复验不混第4章润色) |
| D09 单章润色源版本和漂移采用事务 | [NovelChapterReaderView.swift][I-NovelChapterReaderView]；[NovelSessionViewModel.swift][I-NovelSessionViewModel] | [polish_runner.ts][H-deepread-src-main-ets-novel-polish_runner-ts]；[NovelChapterReaderPage.ets][H-entry-src-main-ets-pages-NovelChapterReaderPage-ets] | 单章复用durable目标润色，冻结真实源ordinal/digest、protected事实与偏好；漂移保原稿，合法审核后原子采用。 | 等效安全润色；不等于允许改事实重生成。 | P5 S/T/H/D |
| D10 整章重生成允许改事实的语义 | [NovelChapterReaderView.swift][I-NovelChapterReaderView]；[NovelSessionViewModel.swift][I-NovelSessionViewModel] | [creation.ts][H-deepread-src-main-ets-novel-creation-ts]；[NovelRevisionBriefSheet.ets][H-entry-src-main-ets-components-NovelRevisionBriefSheet-ets] | Reader显式重生成/定向重写携作者brief，允许修正事实；源基线/CAS绑定，先产生候选不覆盖原稿。 | 等效；polish仍要求保事实。 | P4 S/T/H/D |
| D11 批量润色任意选章与生产入口 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView]；[NovelBatchPolishSheet.swift][I-NovelBatchPolishSheet] | [NovelPolishPage.ets][H-entry-src-main-ets-pages-NovelPolishPage-ets]；[polish_outcomes.ts][H-deepread-src-main-ets-novel-polish_outcomes-ts] | 任意非连续章选择→当前CAS预览→同durable启动；单章/多章同一真实链。 | 等效；旧无caller BatchPolishSheet不复活。 | P5 S/T/D(1与3章) |
| D12 批量润色控制自动采用与逐章报告 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelBatchPolishSheet.swift][I-NovelBatchPolishSheet] | [NovelPolishJobCard.ets][H-entry-src-main-ets-components-NovelPolishJobCard-ets]；[polish_outcomes.ts][H-deepread-src-main-ets-novel-polish_outcomes-ts] | 自动采用合法审核稿；逐章成功/漂移跳过/失败/未处理汇总，停止控制与原稿保留。 | 等效逐章结果；没有把进度attempt当总稿数。 | P5 S/T/H/D(混合结果) |
| D13 批量润色失败继续与定向重试 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelBatchPolishSheet.swift][I-NovelBatchPolishSheet] | [NovelPolishPage.ets][H-entry-src-main-ets-pages-NovelPolishPage-ets]；[polish_runner.ts][H-deepread-src-main-ets-novel-polish_runner-ts] | 章级失败继续；失败/未处理集合作者选后同job重试，成功章不重复请求/采用，漂移保原稿。 | 失败集等效；漂移需Reader明确重写，不宣称iOS原漂移候选直接转采用按钮同形。 | P5 S/T/H/D(1章重试/3章仅一次) |

## 模型与任务设置

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| E01 项目三职责模型选择与全局继承 | [NovelCreation.swift][I-NovelCreation]；[NovelCreationComposition.swift][I-NovelCreationComposition] | [standalone_defaults.ts][H-deepread-src-main-ets-novel-standalone_defaults-ts]；[NovelSettingsPane.ets][H-entry-src-main-ets-components-NovelSettingsPane-ets] | 项目fixed优先→职责默认→null role继承writing；state/review/ghostwrite/polish各实际调用统一解析。 | 等效；独立应用沙盒Provider配置，不迁移宿主密钥。 | P1/P2 S/T/D(设置返回) |
| E02 小说默认职责模型与专用设置页 | [NovelCreationSettingsView.swift][I-NovelCreationSettingsView]；[NovelCreationModelPreferences.swift][I-NovelCreationModelPreferences] | [NovelStandaloneSettingsPage.ets][H-entry-src-main-ets-pages-NovelStandaloneSettingsPage-ets]；[NovelStandaloneDefaults.ets][H-entry-src-main-ets-platform_impl-NovelStandaloneDefaults-ets] | 独立小说默认三职责真实持久，项目与生成实际读取；模型override可用性与固定执行一致。 | 等效；配置宿主与iOS实现不同。 | P1 S/T/D |
| E03 剧情同步是否使用推理 | [NovelCreationSettingsView.swift][I-NovelCreationSettingsView]；[NovelCreationModelPreferences.swift][I-NovelCreationModelPreferences] | [NovelStandaloneSettingsPage.ets][H-entry-src-main-ets-pages-NovelStandaloneSettingsPage-ets]；[NovelChatRunAdapter.ets][H-entry-src-main-ets-platform_impl-NovelChatRunAdapter-ets] | 仅状态delta/rebuild消费独立推理偏好；实际各provider参数按明确能力发送，不污染普通创作/审稿。 | 等效意图；禁推理支持依模型/provider，未承诺任意模型。 | P4 S/T/H |
| E04 结构化任务协议与可追溯版本 | [NovelPromptCatalog.swift][I-NovelPromptCatalog]；[NovelStructuredModelExecutor.swift][I-NovelStructuredModelExecutor] | [prompt_catalog.ts][H-deepread-src-main-ets-novel-prompt_catalog-ts]；[structured_state.ts][H-deepread-src-main-ets-novel-structured_state-ts] | 任务类型/版本/输入来源凭据严格解析，坏JSON与空结果/失败区分；历史记录不因新task版本变坏。 | 目的等效；未复刻iOS私有全schema及遗留包兼容层。 | P4/P5 S/T/H |
| E05 独立整章润色偏好 | [NovelSessionSheets.swift][I-NovelSessionSheets]；[NovelPromptCatalog.swift][I-NovelPromptCatalog] | [project_metadata.ts][H-deepread-src-main-ets-novel-project_metadata-ts]；[polish_context.ts][H-deepread-src-main-ets-novel-polish_context-ts] | polishPreference独立设置/Agent修改/交换，job开始冻结；写作preferences不fallback润色偏好。 | 等效；冻结只影响该job。 | P2/P5 S/T/H/D(偏好保存及实际请求) |

## 恢复、后台与体验

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| F01 同进程离页继续与重挂 | [NovelProjectWorkspaceView.swift][I-NovelProjectWorkspaceView]；[NovelSessionViewModel.swift][I-NovelSessionViewModel] | [NovelWorkspacePage.ets][H-entry-src-main-ets-pages-NovelWorkspacePage-ets]；[creation.ts][H-deepread-src-main-ets-novel-creation-ts] | 离页detach消费者、同进程task继续，返回查询真实active运行并重挂；不靠重新启动生成。 | 等效。 | P5 S/T/H/D(离页完成/返回) |
| F02 普通生成远端游标持久恢复 | [NovelGenerationLifecycle.swift][I-NovelGenerationLifecycle]；[NovelLiveModelAdapter.swift][I-NovelLiveModelAdapter] | [ordinary_run.ts][H-deepread-src-main-ets-novel-ordinary_run-ts]；[novel_response_resume.ts][H-chat-src-main-ets-chat-novel_response_resume-ts] | 支持门限内保存responseID/sequence+canonical partial原子cursor；冷建Creation同ID GET/starting_after续取，不POST伪resume。 | 依provider等效；目前官方OpenAI API-key Responses限定；真实云服务未实测。 | P5 S/T/H(真实本地HTTP非官方云) |
| F03 系统后台持续生成与进度活动 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[BackgroundGenerationKeepAlive.swift][I-BackgroundGenerationKeepAlive] | [BackgroundGenerationKeepAlive.ets][H-entry-src-main-ets-platform_impl-BackgroundGenerationKeepAlive-ets]；[NovelChatRunAdapter.ets][H-entry-src-main-ets-platform_impl-NovelChatRunAdapter-ets] | 真实run/字节进度与DATA_TRANSFER/live-view申请、授予、终态停止；任务owner精确释放。 | 平台差异；有时限，不保证十分钟以上或无限后台。 | P5 S/T/D(有限后台及日志) |
| F04 作业冷启动系统调度与通知定位 | [NovelSessionViewModel.swift][I-NovelSessionViewModel]；[NovelPolishLifecycle.swift][I-NovelPolishLifecycle] | [NovelPolishWorkAbility.ets][H-entry-src-main-ets-works-NovelPolishWorkAbility-ets]；[NovelPolishWorkScheduler.ets][H-entry-src-main-ets-platform_impl-NovelPolishWorkScheduler-ets] | durable一章系统调度及通知project/branch/job定位；热/冷点击同独立bundle任务，恢复原driver。 | 鸿蒙完整job持久调度；系统择机/长期条件仍受OS限制。 | P5 S/T/D(真实通知冷/热定位) |
| F05 主题键盘长文与辅助功能体验验收 | [NovelChapterReaderView.swift][I-NovelChapterReaderView]；[NovelSessionSheets.swift][I-NovelSessionSheets] | [NovelChapterReaderPage.ets][H-entry-src-main-ets-pages-NovelChapterReaderPage-ets]；[NovelMaterialEditorSheet.ets][H-entry-src-main-ets-components-NovelMaterialEditorSheet-ets] | themeEpoch、IME提交、键盘RESIZE、安全区、长名与动态卡均有源码/实屏；P7查找编辑、资料编辑和44vp动作精准修复已复验。 | P7本次长文/浅暗主题样本通过；180%是深读阅读倍率，非小说UI全局倍率；未穷尽辅助功能/全部组合；P8最终安装和冷启动通过，范围见文末。 | P1-P7 S/T/D(范围按独立reviews限定) |

## 深度阅读（DR-01–11）

| ID / 能力 | iOS 实际生产位置 | Harmony 实际入口 / 领域 | 当前实际行为 | 等效判断 / 剩余差异 | 证据 |
| --- | --- | --- | --- | --- | --- |
| DR-01 发现来源、分类与设置 | [DeepReadDiscoveryView.swift][I-DeepReadDiscoveryView]；[IOSHotListProviders.swift][I-IOSHotListProviders] | [NewsNowProvider.ets][H-entry-src-main-ets-platform_impl-NewsNowProvider-ets]；[DeepReadDiscoverySettingsPage.ets][H-entry-src-main-ets-pages-DeepReadDiscoverySettingsPage-ets] | 20个真实来源/5分类，补HN/HF/GitHub AI和6中文源；开关/关注/刷新/翻译/缓存真实消费，空集不偷偷抓全源。 | 目录能力等效；保留鸿蒙既有默认开关，非iOS新装默认逐项同值；公共端点长期在线不保证。 | P6/P7 S/T/H/D(真实热点随机选择→来源→取消→Board清理) |
| DR-02 无链接热点可读与来源持久 | [DeepReadDiscoveryView.swift][I-DeepReadDiscoveryView]；[DeepReadSourceCollector.swift][I-DeepReadSourceCollector] | [DeepReadArticlePage.ets][H-entry-src-main-ets-pages-DeepReadArticlePage-ets]；[discovery_input.ts][H-deepread-src-main-ets-domain-discovery_input-ts] | 榜单provider/rank/summary保存真实inputSources；无URL不造URL；有URL仍抓正文，force更新当前来源但保旧正文至完整提交。 | 等效研究输入；ready排名背景不冒充已抓网页正文。 | P6 S/T/H/D(资料与四段完成) |
| DR-03 当次模板、文章归属与PDF同源 | [DeepReadComposerView.swift][I-DeepReadComposerView]；[DeepReadArticleRenderer.swift][I-DeepReadArticleRenderer] | [DeepReadComposePage.ets][H-entry-src-main-ets-pages-DeepReadComposePage-ets]；[DeepReadTemplate.ets][H-entry-src-main-ets-platform_impl-DeepReadTemplate-ets]；[DeepReadExportPanel.ets][H-entry-src-main-ets-components-DeepReadExportPanel-ets] | 创建捕获实际ID/名称/类型/HTML；RunAdapter/RDB每段保存；旧稿重读与PDF同快照；缺快照明确默认回退。 | 目的等效且鸿蒙冻结HTML；iOS按ID读可变库；默认PDF用全文HTML，API14+ArkWeb才开放。 | P6/P7 S/T/H/D(冷读A/改全局B、180%原生/HTML/PDF同源) |
| DR-04 Google补充开关与真实原生搜索 | [DeepReadSourceCollector.swift][I-DeepReadSourceCollector]；[IOSFreeSearchAggregator.swift][I-IOSFreeSearchAggregator] | [SearchRegistry.ets][H-entry-src-main-ets-platform_impl-SearchRegistry-ets]；[DeepReadGoogleSearch.ets][H-entry-src-main-ets-platform_impl-DeepReadGoogleSearch-ets]；[DeepReadGoogleSearchHost.ets][H-entry-src-main-ets-components-DeepReadGoogleSearchHost-ets] | 免费结果去重少于3条且开关启用才调用独立原生Web宿主；请求取消/owner隔离，不污染Chat宿主。 | 等效开关/采集调用；设备真实宿主挂载但Google外网deadline超时，成功结果/品质未验。 | P6 S/T/H/D(宿主挂载，结果未成功) |
| DR-05 首选搜索服务决定请求集合 | [DeepReadSettingsStore.swift][I-DeepReadSettingsStore]；[IOSSearchExecutor.swift][I-IOSSearchExecutor] | [DeepReadSearchSettingsPage.ets][H-entry-src-main-ets-pages-DeepReadSearchSettingsPage-ets]；[deepread_search_registry.ts][H-chat-src-main-ets-search-deepread_search_registry-ts] | 一个启用且可执行首选→一个其它可用配置→免费聚合；运行前冻结设置，非并发全部付费服务。 | 等效；仅免费引擎聚合并发；配置变更下一轮生效。 | P6 S/T/H/D(设置持久) |
| DR-06 普通非工具模型完整四段生成 | [DeepReadRuntime.swift][I-DeepReadRuntime]；[IOSDeepReadDraftGenerator.swift][I-IOSDeepReadDraftGenerator] | [run_manager.ts][H-deepread-src-main-ets-agent-run_manager-ts]；[structured_stage.ts][H-deepread-src-main-ets-agent-structured_stage-ts]；[DeepReadRunRepository.ets][H-entry-src-main-ets-platform_impl-DeepReadRunRepository-ets] | 能力分流structured/tools；普通模型规划+四段JSON真实5请求无tools，经原writer校验逐段保存；坏JSON仅既有一次纠正，冷续跑非READY段。 | 等效普通模型闭环；合法外链/候选头图/两节点图解支持纯文本输入，不编造链接；云品质未验。 | P6/P7 S/T/H/D(真实5请求/完整稿、当前阶段卡更新→完成移除) |
| DR-07 实时阅读库、检索与恢复 | [DeepReadLibraryView.swift][I-DeepReadLibraryView]；[DeepReadRuntime.swift][I-DeepReadRuntime] | [DeepReadHistoryPage.ets][H-entry-src-main-ets-pages-DeepReadHistoryPage-ets]；[library.ts][H-deepread-src-main-ets-domain-library-ts]；[scheduler.ts][H-deepread-src-main-ets-agent-scheduler-ts] | 真实仓库写入+活动准入/退出订阅；停留页面实时更新，未保存run entry:null；正文检索/状态过滤、返回重挂/离页释放。 | 等效；不新增iOS无可达caller的删除入口；force时旧缓存COMPLETE不代替当前任务阶段。 | P6/P7 S/T/D(实时→完成→冷读；force旧缓存不冒充当前阶段) |
| DR-08 来源内容、复制与浏览器打开 | [DeepReadDetailView.swift][I-DeepReadDetailView]；[DeepReadSourceCollector.swift][I-DeepReadSourceCollector] | [DeepReadSourcesPage.ets][H-entry-src-main-ets-pages-DeepReadSourcesPage-ets]；[DeepReadArticlePage.ets][H-entry-src-main-ets-pages-DeepReadArticlePage-ets] | 来源正文/抓取状态/错误真实展示及复制；绝对HTTP(S)交系统viewData，错误可见，旧initial读不覆盖新订阅。 | 等效；来源存在不保证网页始终可访问。 | P6 S/T/D(oracle.com系统浏览器) |
| DR-09 TXT文件保存分享及导出 | [DeepReadTextExporter.swift][I-DeepReadTextExporter]；[DeepReadDetailView.swift][I-DeepReadDetailView] | [DeepReadExportPanel.ets][H-entry-src-main-ets-components-DeepReadExportPanel-ets]；[DeepReadExportFiles.ets][H-entry-src-main-ets-platform_impl-DeepReadExportFiles-ets]；[export.ts][H-deepread-src-main-ets-domain-export-ts] | 单份已保存稿导出TXT/Markdown/PDF；TXT正文用实际Markdown AST转文本、字面元数据保留；UTF8文件保存/分享，partial标识/PDF完成门禁真实。 | 等效系统出口；实际系统保存/分享文件，未发送接收人、未读Download字节或逐页印刷品质。 | P6/P7 S/T/D(TXT1.32KB、P6 PDF468.57KB；P7 180% PDF469.53KB系统保存/分享) |
| DR-10 独立20MiB文件资料读取 | [DeepReadFileImporter.swift][I-DeepReadFileImporter] | [DeepReadSourceImporter.ets][H-entry-src-main-ets-platform_impl-DeepReadSourceImporter-ets]；[DeepReadComposePage.ets][H-entry-src-main-ets-pages-DeepReadComposePage-ets] | 深读专属20MiB，TXT/MD/CSV/JSON/PDF/DOCX按现解析器；原文件与错误/截断说明存成资料；聊天仍10MiB。 | 等效独立边界；两端扫描PDF均无OCR，非本轮遗漏。 | P6 S/T/D(20MiB说明，实际12MiB读取为T) |
| DR-11 合法HTML starter与保存选用 | [DeepReadTemplatesView.swift][I-DeepReadTemplatesView]；[DeepReadTemplateGeneratorView.swift][I-DeepReadTemplateGeneratorView] | [DeepReadTemplateWorkbenchPage.ets][H-entry-src-main-ets-pages-DeepReadTemplateWorkbenchPage-ets]；[DeepReadTemplateSettingsPage.ets][H-entry-src-main-ets-pages-DeepReadTemplateSettingsPage-ets]；[DeepReadTemplate.ets][H-entry-src-main-ets-platform_impl-DeepReadTemplate-ets] | 新建即合法starter；预览→保存→选用真实storage，createdAt保留/updatedAt更新；同IDkey刷新新名称及HTML，旧picker/AI回包不覆盖新稿。 | 等效；两端模板内置视觉设计不同，脚本/事件等不安全HTML不可用。 | P6/P7 S/T/D(starter预览保存、captured HTML浅暗主题180%样本) |

## 阶段证据与外部条件

| Gate | 当前证据 / 判定 | 明确保留边界 |
| --- | --- | --- |
| P1/P2 | [入口review](p1-entry-review.md)、[仓库/历史](p2-domain-review.md)、[交换](p2-exchange-review.md)、[UI](p2-ui-review.md)已关闭；阶段签名安装记录在对应completion | 损坏项目只读恢复以真实repository反例证明；未为设备写沙盒增加debug入口 |
| P3 | [字段](p3-fields-review.md)、[继承](p3-inheritance-review.md)、[上下文](p3-context-review.md)、[UI](p3-ui-review.md)及实际request receipts | 预览token为估算/保守开销，必要事实不截断；本地设备provider非云品质 |
| P4 | [工具](p4-tools-review.md)、[合同](p4-contracts-review.md)、[状态](p4-state-review.md)、[UI](p4-ui-review.md)独立PASS；[设备cases](device/p4/device-cases.md) | 真HTTP一键合同/作者批准、状态救援及修复闭环；不是模型语义质量穷尽 |
| P5 | [普通恢复](p5-ordinary-review.md)、[润色](p5-polish-review.md)、[交换](p5-exchange-review.md)、[UI](p5-ui-review.md)、[Workspace](p5-workspace-ui-independent-review.md)；[设备cases](device/p5/device-cases.md) | 同ID GET为localhost真实HTTP；官方OpenAI后台未用云凭据跑；保活有时限 |
| P6 | [发现](p6-discovery-review.md)、[生成/搜索](p6-runtime-search-review.md)、[模板/库/导出](p6-template-library-export-review.md)、[原生搜索](p6-native-search-review.md)、[设备UI](p6-ui-device-review.md)PASS；[设备cases](device/p6/device-cases.md) | Google宿主真实挂载但外网超时；TXT/PDF系统接收非空真实文件，不声称读取保存bytes |
| P7 | [完成记录](p7-completion.md)、[运行阶段](p7-runtime-progress.md)、[深读独立review](p7-deepread-ui-independent-review.md)、[小说动态卡](p7-novel-card-review.md)、[跨产品UI](p7-cross-product-ui-review.md)独立 source/device **PASS**；UI01/02/03与裁决44×44vp均closed，无已确认未修问题 | Newsroom真实阶段更新→完成移除；随机阅读真实热点→来源→取消并清理，未把取消样本说成全文完成。180%仅深读阅读倍率；未穷尽所有组合 |
| P8 回归 / 当前包 | [完成记录](p8-completion.md)、[回归](p8-regression.md) **4539/4539**、DeepRead/Chat tsc 0；最终UI调整后的Entry 241/241。`/tmp/amber-p8-final-build.log`两产品 BUILD SUCCESSFUL、签名与Verify成功；[指纹](device/p8/final-artifact-fingerprints.json) **804/804**生产源与两generated副本一致，实际HAP摘要独立重算一致；root记录当前双包覆盖安装成功 | 测试不代替设备/云品质；lint按所属阶段日志记录，不把4539项测试误称本轮全量lint。最终包SHA见下；正式分发签名/发布未经此报告执行 |
| P8 独立数据冷启动 | **PASS**；[设备记录](p8-device-cases.md)：两个bundle实际 `aa force-stop` 成功后 `aa start`；小说保留6章/原稿/完成记录，深读完稿库与旧captured A冷读保留；不同provider配置仍各自存在 | [安装身份](device/p8/installed-identities.json)小说UID 20020074、深读UID 20020075、宿主UID 20020073分别保留；结合应用context存储与实际分离数据核实沙盒，不仅比较标题 |
| P8 集成独立 review | [最终集成独立审核](p8-final-integration-review.md) **PASS**：产品壳/数据隔离/实际入口/source→generated→签名产物，无新生产阻塞 | 最终两产品使用本地debug provision；不是正式分发签名或商店发布，未复制宿主密钥 |

这91行没有采用“没找到同名API即缺能力”的判断。保留差异为真实容器/私有格式、对端parser限制、模型/系统支持范围与呈现策略；不追加退休旧链、重复任务存储或无证据fallback。P7新增随机阅读和Newsroom已分别映射到DR-01/DR-06/07，未改动原11项ID。

P7 第6章补充证据：[实际六章目录AX](device/p7/novel-six-chapters-final.json)及[JPEG](device/p7/novel-six-chapters-final.jpeg)显示“共6章”和第06章正文；44×44vp热区复验后同job重试已入目录。第4章整章润色完成卡是另一任务。这里的小说/深读模型输出均为受控本地fixture，不扩大为真实云品质结论。

P8 冷启动数据证据：[小说六章目录](device/p8/p8-novel-cold-chapters.json)、[深读完稿库](device/p8/p8-deepread-cold-library.json)及[旧模板A冷读](device/p8/p8-cold-captured-template-A.json)。小说原稿/完成润色记录和第5/6章仍在；深读随机项按主动取消保留未完成状态，未冒充完稿。深读“P6 Local Verification”与小说“Local-verification”[各自配置](p8-device-cases.md)独立保留，仅为合成测试配置。深读180%阅读倍率跨冷启动保留，验收后还原100%；最终两个产品恢复暗色。

当前已构建、签名并由root覆盖安装的HAP（独立重算SHA-256，与[指纹记录](device/p8/final-artifact-fingerprints.json)相符）：

| 产品 / bundle | 最终HAP SHA-256 | 字节数 |
| --- | --- | --- |
| 小说创作 / `app.amber.novel` | `8c47fa423d12975aa96c88c5f7208ca47ee6d36b2a5188b12ac00036757f6f8e` | 60,896,371 |
| 深度阅读 / `app.amber.deepread.reader` | `23109b82fe0429f1ce853a5e1456b634f3b3f8b6e760953eb5b8576bd2ae932e` | 60,907,516 |

当前读取基线HEAD：iOS `ede7cbd5b463240315256c4d6a2d492cccf58d70`；Harmony `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`。结论对应工作树生产文件及本阶段报告，包含本轮未提交实现；HEAD不是最终安装HAP内容证明。最终P8以本表HAP指纹、实际安装冷启动及独立集成审核关联；阶段验收全部关闭，外部及未穷尽边界保留。

## 生产文件链接索引

[H-chat-src-main-ets-chat-novel_context_policy-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/novel_context_policy.ts
[H-chat-src-main-ets-chat-novel_interactive_adapter-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/novel_interactive_adapter.ts
[H-chat-src-main-ets-chat-novel_response_resume-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/novel_response_resume.ts
[H-chat-src-main-ets-chat-novel_workspace_tools-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/novel_workspace_tools.ts
[H-deepread-src-main-ets-novel-candidate_provenance-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/candidate_provenance.ts
[H-deepread-src-main-ets-novel-chapter_contract-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/chapter_contract.ts
[H-deepread-src-main-ets-novel-chapter_find_replace-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/chapter_find_replace.ts
[H-deepread-src-main-ets-novel-collected_candidates-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/collected_candidates.ts
[H-deepread-src-main-ets-novel-context_builder-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/context_builder.ts
[H-deepread-src-main-ets-novel-continuity_audit-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/continuity_audit.ts
[H-deepread-src-main-ets-novel-continuity_repair-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/continuity_repair.ts
[H-deepread-src-main-ets-novel-creation-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/creation.ts
[H-deepread-src-main-ets-novel-discussion_archive-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/discussion_archive.ts
[H-deepread-src-main-ets-novel-ghostwrite_runner-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/ghostwrite_runner.ts
[H-deepread-src-main-ets-novel-ghostwrite_start_preview-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/ghostwrite_start_preview.ts
[H-deepread-src-main-ets-novel-history_selection-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/history_selection.ts
[H-deepread-src-main-ets-novel-import_conflicts-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/import_conflicts.ts
[H-deepread-src-main-ets-novel-job_report-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/job_report.ts
[H-deepread-src-main-ets-novel-material_adoption-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/material_adoption.ts
[H-deepread-src-main-ets-novel-material_fields-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/material_fields.ts
[H-deepread-src-main-ets-novel-material_inheritance-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/material_inheritance.ts
[H-deepread-src-main-ets-novel-material_injection-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/material_injection.ts
[H-deepread-src-main-ets-novel-model_running-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/model_running.ts
[H-deepread-src-main-ets-novel-mutations-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/mutations.ts
[H-deepread-src-main-ets-novel-native_backup-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/native_backup.ts
[H-deepread-src-main-ets-novel-ordinary_run-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/ordinary_run.ts
[H-deepread-src-main-ets-novel-plot_projection-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/plot_projection.ts
[H-deepread-src-main-ets-novel-polish_context-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/polish_context.ts
[H-deepread-src-main-ets-novel-polish_outcomes-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/polish_outcomes.ts
[H-deepread-src-main-ets-novel-polish_runner-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/polish_runner.ts
[H-deepread-src-main-ets-novel-project_metadata-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/project_metadata.ts
[H-deepread-src-main-ets-novel-prompt_catalog-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/prompt_catalog.ts
[H-deepread-src-main-ets-novel-quick_start_proposals-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/quick_start_proposals.ts
[H-deepread-src-main-ets-novel-repository-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/repository.ts
[H-deepread-src-main-ets-novel-specialized_operations-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/specialized_operations.ts
[H-deepread-src-main-ets-novel-standalone_defaults-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/standalone_defaults.ts
[H-deepread-src-main-ets-novel-state_rebuild-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/state_rebuild.ts
[H-deepread-src-main-ets-novel-structured_state-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/structured_state.ts
[H-deepread-src-main-ets-novel-transcript-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/transcript.ts
[H-deepread-src-main-ets-novel-workspace_interop-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/workspace_interop.ts
[H-entry-src-main-ets-components-NovelApprovalShell-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelApprovalShell.ets
[H-entry-src-main-ets-components-NovelArchivePresentation-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelArchivePresentation.ets
[H-entry-src-main-ets-components-NovelAuditReportView-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelAuditReportView.ets
[H-entry-src-main-ets-components-NovelBranchSheet-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelBranchSheet.ets
[H-entry-src-main-ets-components-NovelChapterVersionPreview-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelChapterVersionPreview.ets
[H-entry-src-main-ets-components-NovelDiscussionArchiveCard-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelDiscussionArchiveCard.ets
[H-entry-src-main-ets-components-NovelDiscussionArchiveSheet-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelDiscussionArchiveSheet.ets
[H-entry-src-main-ets-components-NovelGhostwriteCard-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelGhostwriteCard.ets
[H-entry-src-main-ets-components-NovelGhostwriteReportCard-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelGhostwriteReportCard.ets
[H-entry-src-main-ets-components-NovelMaterialAdoptionSheet-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelMaterialAdoptionSheet.ets
[H-entry-src-main-ets-components-NovelMaterialEditorSheet-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelMaterialEditorSheet.ets
[H-entry-src-main-ets-components-NovelMaterialMetadataFields-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelMaterialMetadataFields.ets
[H-entry-src-main-ets-components-NovelPolishJobCard-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelPolishJobCard.ets
[H-entry-src-main-ets-components-NovelRevisionBriefSheet-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelRevisionBriefSheet.ets
[H-entry-src-main-ets-components-NovelRunRecoveryCard-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelRunRecoveryCard.ets
[H-entry-src-main-ets-components-NovelSettingsPane-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NovelSettingsPane.ets
[H-entry-src-main-ets-pages-NovelBranchHistoryPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelBranchHistoryPage.ets
[H-entry-src-main-ets-pages-NovelChapterContractPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelChapterContractPage.ets
[H-entry-src-main-ets-pages-NovelChapterReaderPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelChapterReaderPage.ets
[H-entry-src-main-ets-pages-NovelContextPreviewPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelContextPreviewPage.ets
[H-entry-src-main-ets-pages-NovelOperationsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelOperationsPage.ets
[H-entry-src-main-ets-pages-NovelPolishPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelPolishPage.ets
[H-entry-src-main-ets-pages-NovelProjectSetupPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelProjectSetupPage.ets
[H-entry-src-main-ets-pages-NovelProjectsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelProjectsPage.ets
[H-entry-src-main-ets-pages-NovelRecoveryPreviewPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelRecoveryPreviewPage.ets
[H-entry-src-main-ets-pages-NovelStandaloneSettingsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelStandaloneSettingsPage.ets
[H-entry-src-main-ets-pages-NovelStatePage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelStatePage.ets
[H-entry-src-main-ets-pages-NovelWorkspacePage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets
[H-entry-src-main-ets-platform_impl-BackgroundGenerationKeepAlive-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/BackgroundGenerationKeepAlive.ets
[H-entry-src-main-ets-platform_impl-NovelChatRunAdapter-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelChatRunAdapter.ets
[H-entry-src-main-ets-platform_impl-NovelPolishWorkScheduler-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelPolishWorkScheduler.ets
[H-entry-src-main-ets-platform_impl-NovelResearchSupport-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelResearchSupport.ets
[H-entry-src-main-ets-platform_impl-NovelShareSupport-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelShareSupport.ets
[H-entry-src-main-ets-platform_impl-NovelStandaloneDefaults-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelStandaloneDefaults.ets
[H-entry-src-main-ets-platform_impl-NovelWorkspaceService-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NovelWorkspaceService.ets
[H-entry-src-main-ets-works-NovelPolishWorkAbility-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/works/NovelPolishWorkAbility.ets
[I-BackgroundGenerationKeepAlive]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/BackgroundGenerationKeepAlive.swift
[I-IOSNovelProjectToolExecutor]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/IOSNovelProjectToolExecutor.swift
[I-IOSNovelWorkspaceTools]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/IOSNovelWorkspaceTools.swift
[I-NovelAppToolHost]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/NovelApp/Sources/App/NovelAppToolHost.swift
[I-NovelBatchPolishSheet]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelBatchPolishSheet.swift
[I-NovelBranchOperations]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelBranchOperations.swift
[I-NovelBranchesView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelBranchesView.swift
[I-NovelChapterPlanAcceptanceLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelChapterPlanAcceptanceLifecycle.swift
[I-NovelChapterPlanProposalLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelChapterPlanProposalLifecycle.swift
[I-NovelChapterReaderView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelChapterReaderView.swift
[I-NovelChapterViews]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelChapterViews.swift
[I-NovelCharacterPagesView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCharacterPagesView.swift
[I-NovelCompendiumView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCompendiumView.swift
[I-NovelContinuityAuditLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelContinuityAuditLifecycle.swift
[I-NovelContinuityAuditView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelContinuityAuditView.swift
[I-NovelCreation]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCreation.swift
[I-NovelCreationComposition]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCreationComposition.swift
[I-NovelCreationModelPreferences]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCreationModelPreferences.swift
[I-NovelCreationSettingsView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCreationSettingsView.swift
[I-NovelCreationViewModel]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift
[I-NovelDomainModels]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelDomainModels.swift
[I-NovelFactTransactionLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelFactTransactionLifecycle.swift
[I-NovelFactTransactions]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelFactTransactions.swift
[I-NovelGenerationLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelGenerationLifecycle.swift
[I-NovelGenerationReducer]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelGenerationReducer.swift
[I-NovelInjectionPlanner]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelInjectionPlanner.swift
[I-NovelLiveModelAdapter]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelLiveModelAdapter.swift
[I-NovelPolishLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelPolishLifecycle.swift
[I-NovelProjectLifecycle]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectLifecycle.swift
[I-NovelProjectListView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectListView.swift
[I-NovelProjectPackage]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectPackage.swift
[I-NovelProjectRepository]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectRepository.swift
[I-NovelProjectSettingsDetailView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectSettingsDetailView.swift
[I-NovelProjectWorkspaceView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelProjectWorkspaceView.swift
[I-NovelPromptCatalog]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelPromptCatalog.swift
[I-NovelReducer]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelReducer.swift
[I-NovelSessionBubble]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelSessionBubble.swift
[I-NovelSessionSheets]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelSessionSheets.swift
[I-NovelSessionView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelSessionView.swift
[I-NovelSessionViewModel]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift
[I-NovelStructuredModelExecutor]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelStructuredModelExecutor.swift
[I-NovelWorkspaceBackup]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelWorkspaceBackup.swift
[I-NovelWorkspaceContextAssembler]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelWorkspaceContextAssembler.swift
[I-NovelWorkspaceImporter]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/NovelCreation/NovelWorkspaceImporter.swift

[H-chat-src-main-ets-search-deepread_search_registry-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/search/deepread_search_registry.ts
[H-deepread-src-main-ets-agent-run_manager-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/agent/run_manager.ts
[H-deepread-src-main-ets-agent-scheduler-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/agent/scheduler.ts
[H-deepread-src-main-ets-agent-structured_stage-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/agent/structured_stage.ts
[H-deepread-src-main-ets-domain-discovery_input-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/domain/discovery_input.ts
[H-deepread-src-main-ets-domain-export-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/domain/export.ts
[H-deepread-src-main-ets-domain-library-ts]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/domain/library.ts
[H-entry-src-main-ets-components-DeepReadExportPanel-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/DeepReadExportPanel.ets
[H-entry-src-main-ets-components-DeepReadGoogleSearchHost-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/DeepReadGoogleSearchHost.ets
[H-entry-src-main-ets-pages-DeepReadArticlePage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets
[H-entry-src-main-ets-pages-DeepReadComposePage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadComposePage.ets
[H-entry-src-main-ets-pages-DeepReadDiscoverySettingsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadDiscoverySettingsPage.ets
[H-entry-src-main-ets-pages-DeepReadHistoryPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadHistoryPage.ets
[H-entry-src-main-ets-pages-DeepReadSearchSettingsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadSearchSettingsPage.ets
[H-entry-src-main-ets-pages-DeepReadSourcesPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadSourcesPage.ets
[H-entry-src-main-ets-pages-DeepReadTemplateSettingsPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadTemplateSettingsPage.ets
[H-entry-src-main-ets-pages-DeepReadTemplateWorkbenchPage-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/DeepReadTemplateWorkbenchPage.ets
[H-entry-src-main-ets-platform_impl-DeepReadExportFiles-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadExportFiles.ets
[H-entry-src-main-ets-platform_impl-DeepReadGoogleSearch-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadGoogleSearch.ets
[H-entry-src-main-ets-platform_impl-DeepReadRunRepository-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadRunRepository.ets
[H-entry-src-main-ets-platform_impl-DeepReadSourceImporter-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadSourceImporter.ets
[H-entry-src-main-ets-platform_impl-DeepReadTemplate-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets
[H-entry-src-main-ets-platform_impl-NewsNowProvider-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NewsNowProvider.ets
[H-entry-src-main-ets-platform_impl-SearchRegistry-ets]: /Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/SearchRegistry.ets
[I-DeepReadArticleRenderer]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadArticleRenderer.swift
[I-DeepReadComposerView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadComposerView.swift
[I-DeepReadDetailView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadDetailView.swift
[I-DeepReadDiscoveryView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/Discovery/DeepReadDiscoveryView.swift
[I-DeepReadFileImporter]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/Runtime/DeepReadFileImporter.swift
[I-DeepReadLibraryView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadLibraryView.swift
[I-DeepReadRuntime]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/Runtime/DeepReadRuntime.swift
[I-DeepReadSettingsStore]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/Settings/DeepReadSettingsStore.swift
[I-DeepReadSourceCollector]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/Runtime/DeepReadSourceCollector.swift
[I-DeepReadTemplateGeneratorView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadTemplateGeneratorView.swift
[I-DeepReadTemplatesView]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadTemplatesView.swift
[I-DeepReadTextExporter]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/UI/DeepReadTextExporter.swift
[I-IOSDeepReadDraftGenerator]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/DeepReadCore/IOSDeepReadDraftGenerator.swift
[I-IOSFreeSearchAggregator]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/IOSFreeSearchAggregator.swift
[I-IOSHotListProviders]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/HotlistCore/IOSHotListProviders.swift
[I-IOSSearchExecutor]: /Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/IOSSearchExecutor.swift
