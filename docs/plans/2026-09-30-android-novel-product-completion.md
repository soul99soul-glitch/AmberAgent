# Android 小说创作必要功能补齐计划

日期：2026-09-30。基线：`ff1b70f3dbfccc5073206cf2d046fd6e52f69061`，当前工作区。用户授权按阶段连续实现、审查并修复，直到本计划闭环；不提交或推送其他工作。

## 产品目标与范围

作者能够可靠地讨论、确认计划、生成和修改正文、审阅改动、管理资料、撤回错误改动、备份恢复，并了解故事的一致性问题。复用已有 Markdown 工作区、Ledger、owner/CAS、WorkManager、联合审核和分支机制。

不照搬 iOS 废弃页面、旧 JSON stateDelta 普通收录流程、私有事实兼容图、平台后台协议。人物时间线、自动跨章修复、复杂资料覆盖编辑器、完整 iOS 私有历史互导本次不作为必需项。现有导入数据的覆盖/注入语义必须正确消费，不能静默忽略。

## 与 iOS 的功能判断及实现对应

只读 iOS 例外已由用户批准。iOS 根为 `/Users/arquiel/Downloads/AI/amberagent-ios`，下列 iOS 文件在 `iosApp/iosApp/NovelCreation/`；判断以当前生产入口为依据，不用代码规模或工具名数量代替功能比较。

| 能力 | iOS 当前状态与位置 | Android 对应位置 | 判断 |
| --- | --- | --- | --- |
| 创作讨论、模型、计划、代笔、分支 | `IOSNovelProjectToolExecutor.swift:26` 实际注册工具，`ChatToolRuntime.swift:722` 接入讨论 | `NovelWorkspaceRuntime`、`NovelWorkspaceTools`、`NovelWorkspaceGhostwriteCoordinator/Controller`、`NovelWorkspaceBranches`、工作区页面 | 已有主链，补边界和作者操作；无需复制同名工具 |
| 改章审批及安全润色 | 当前工具注册与 owner/审核链 | `NovelWorkspacePolish`、`NovelWorkspaceProposalOwner`、`NovelWorkspaceAuthorEdits`、全文审批面板 | 必须，Phase 1–2 完成 |
| 停止/失败候选、审批重开、全文编辑、历史撤回 | 当前 workspace 宿主状态及历史 | `NovelTurnOutputCapture`、`NovelWorkspaceTurnOutputs/ProposalStore/History`、候选及历史 sheets | 必须，Phase 2 完成 |
| 资料、人物、世界、关系、大纲、偏好 | `NovelProjectWorkspaceView.swift:528` 进入 `NovelCompendiumView`，后者 `:86` 进入人物页，仍实际在用 | `NovelWorkspaceMaterialActions/EffectiveMaterials/Catalog`、`NovelMarkdownMaterialSheets`、`NovelMarkdownWorkspaceCatalog` | 能力必须，Phase 1/3 完成；不照搬页面布局 |
| 章节移除、废稿恢复、讨论结论归档 | 当前章节及资料工具 | `NovelWorkspaceDiscardedChapters/MaterialActions`、工作区废稿/决定入口 | 必须，Phase 3 完成 |
| 原生完整备份、安全恢复、交换导出 | 当前 workspace 与宿主状态 | `SyncArchiveManager`、`NovelWorkspaceRestoreBoundary/Bridge`、系统 backup XML、`NovelWorkspaceExchange` 与导出确认 | 必须，Phase 4 完成 |
| 主动全书检查及可定位报告 | `NovelCompendiumView.swift:315` 挂审计区域 | `NovelWorkspaceContinuityAudit`、VM、进度/报告 UI | 必须，Phase 5 已完成并通过本次验收 |
| 旧资料页面 | `NovelMaterialsView.swift:3` 明确当前无生产调用，全仓未找到调用 | 新资料目录已提供等价能力 | 旧页面无需移植，不代表资料功能废弃 |
| 旧 JSON 存储布局 | `NovelWorkspaceProjectStore.swift:616` 迁移封存旧 layout/blobs/monofile；新建 `NovelCreation.swift:845` 为 workspace-native | 原 Android 旧项目迁移、新项目 native repository | 只保留原迁移入口，不新增 iOS 旧存储链 |
| 普通收录 JSON stateDelta | `NovelFactTransactionLifecycle.swift:21` 已用确定性 workspace 收录；`:38` 的手改事实同步仍在用 | Ledger/剧情同步及失效门禁 | 旧普通收录抽取流程无需移植；不能称全部 stateDelta 已废弃 |
| entity/fact/document/reducer 私有图 | `NovelCreationComposition.swift:39`、`NovelCreation.swift:844/877`、`NovelDocumentValidator.swift:29` 均生产在用 | Markdown + Ledger + owner/CAS + 审核/剧情失效 | 无须照搬架构，属于等价设计选择，不是死代码 |
| 人物经历时间线、自动跨章修复、私有历史互导 | 人物页 `:129` 读 events；`NovelContinuityAuditView.swift:124` 有修复入口，repair lifecycle `:33` 可达 | 当前资料/审稿→手改或审批→历史/Undo 链 | 有价值的增强，延期；不能称已废弃。下一弧专用便利工具同属增强 |

实现文件路径见各 phase 的证据及源目录。当前 iOS 调用存在只证明可达，不代表已完成设备或 provider 验收。

## Phase 1：修正写入边界和状态判断

- 润色工具仅允许目标章节，资料、计划和其他章节均不可写。
- 单章润色先出候选，由作者确认；无人值守批量润色在提交前比较原稿和候选，事实变化或审核失败不提交。
- 正文变化触发剧情/后续章节门禁；仅标题变化不触发。删除路径参与 Ledger 变更计算。
- 分支资料覆盖及旧格式真实的 `always/smart/off` 注入策略进入真实上下文链。
- workspace status 返回真实剧情新鲜度与未解决修改；失败通知带完整项目/分支/job 路由。

完成条件：定点测试覆盖越界写入拒绝、润色审核提交、标题与正文区别、删除 diff、覆盖/注入策略和通知路由；生产调用完整。

## Phase 2：保存作者成果和可审阅恢复

- 普通生成部分文本按有限频率保存；停止、失败、离页和重新打开可读，不自动收录；重试生成新候选。
- 待审批改动持久化，重开恢复并保留原 head/tree CAS；审批前可查看全部文件内容、编辑或拒绝。
- 候选可全文编辑，并选择新章、追加或替换指定章节。
- 章节正文历史可浏览、预览和恢复；恢复走当前手改状态门禁，禁止跨分支/任务占用绕过。

完成条件：停止/重建/过期审批/历史恢复定点测试；UI 完整内容可滚动编辑，操作有可达入口。

## Phase 3：完成日常创作管理闭环

- 资料可创建、编辑、重命名和删除；确认后落盘，关联上下文与目录同步。
- 章节可移入废稿并恢复；正文移除与剧情/后章失效准确接线，历史可恢复。
- 讨论确认的结论可人工编辑并存为决策资料，详情可阅读、修改；不增加必经模型调用。

完成条件：创建→显示→注入→修改/删除、正文章节→废稿→恢复、讨论→决策→上下文链均有验收；宽窄布局、长文案、键盘和触控目标审查通过。

## Phase 4：完整备份与安全恢复

- 原生小说目录进入应用备份/恢复和 Android 系统备份范围，包括隐藏宿主状态、会话、历史和工作任务。
- 恢复与小说写入互斥，运行任务不得在恢复后继续用旧绑定写入；恢复后重新载入并显式恢复暂停任务。
- 工作区 ZIP 继续用于跨端交换，UI 清楚区分交换导出与完整备份。

完成条件：完整小说文件备份往返与恢复写入门禁测试；验证现有同步入口复用同一归档实现，不能只修改白名单。

## Phase 5：全书检查与整体产品验收

- 作者可主动发起当前分支全书只读一致性审稿；报告含可定位章号/问题/建议，失败保留正文，不自动修复或提交。
- 汇总所有阶段的逻辑、调用链和 UI 审查，修复确认问题，运行必要回归与 app 编译/打包。
- 有设备则检查实际页面、长文案、键盘、间距和操作可达性；provider、后台/杀进程和设备证据分别记录，不能用编译冒充。

完成条件：计划内功能全部接入生产入口，相关回归通过；不存在未修复的阻断性审查发现。无法执行的外部验收明确标注。

## 每阶段执行与审查

先完成本阶段并跑定点 JVM 验证。独立 subagent 分别检查业务与调用链、规范与回归、Compose UI 的对齐/边距/间距/尺寸/长文案/键盘。审查范围使用本次文件基线差异，避免混入用户原有 WIP。仅修可定位问题，修复后重跑受影响验证，再推进下一阶段。新增职责落在小文件；不扩大既有大 ViewModel/页面为通用框架。

## 进度与证据

- Phase 1–5 全部完成；各阶段实现、独立审查、确认问题修复及必要回归已闭环。外部 provider/后台验收边界见末尾。
- 初始基线副本：`/tmp/amber-novel-20260930-baseline`。原有脏文件清单保存在该目录，未回退其他改动。
- Phase 1：润色精确写入限制、单章作者确认、批量独立事实审核及原稿 CAS、真实状态/通知路由、标题与正文变化区分、删除 diff、分支有效资料与 `always/smart/off` 全部接入。新批次按 job/ordinal 持久进度，原稿无改动也可完成；仅真实悬挂润色可修指针，准备/提交均检查占用与 owner。
- Phase 1 review：Standards 0 个未关闭发现；Spec 0 个未关闭发现；UI 0 个未关闭发现。修复了 dirty 正文被纯标题提交掩盖、单章入口停留批量面板、章号空洞无反馈和审核阶段不可见。
- Phase 1 验证：`:feature:novel-workspace:testDebugUnitTest` 124 项、0 失败、1 项跨端环境跳过；app 定点 7 套件 99 项、0 失败；app 主/测试 Kotlin 编译通过。日志 `/tmp/amber-novel-phase1-final-tests.log`。
- 全仓本地化检查发现其他功能既有缺键/内联文案，未扩大本次修改范围；本次新增事实审核标签和两条润色说明均覆盖六套语言。设备实际显示仍待最终阶段验证。
- Phase 2：普通输出 2 秒/8 KiB 有界快照与停止/失败/离页留存、稳定 ID 会话去重、持久化提案编辑及原 CAS、全文审阅、候选任意章追加/替换、原稿 blob 历史与恢复完成。历史只展示当前分支保留提交中有实际稿本的版本；没有虚构旧稿或新增多级 undo/redo。
- Phase 2 review：Standards/Spec/UI 均 0 个未关闭发现。修复普通发送失败后未即时显示部分输出、异步切文件沿用旧预览、长标题挤占面板。停止测试等待实际收到文字后取消，未放宽生产时序或超时。
- Phase 2 验证：feature 全套 JVM 通过；app `*Novel*` 全套通过，含真实 Launcher/Agent 取消、草稿保存后收录与 Compose 全文/异步切文件回归。日志 `/tmp/amber-novel-phase2-feature-tests.log`、`/tmp/amber-novel-phase2-final-tests.log`；主/测试编译通过。
- Phase 3：资料六类型增删改名、完整原元数据保留、分支决定归档与编辑、完整原章移入废稿和原位置恢复完成；确认冻结 head/tree，章号冲突拒绝覆盖，进入现有 Ledger/Undo/正文失效链。当前剧情可人工补充且拒绝空白，编辑核对打开时原文。
- Phase 3 review：Standards/Spec/UI 均 0 个未关闭发现；修复多行资料标题不能回读、删除失败未中断提交、空剧情错误清门禁、非资料编辑丢失原文校验及长文案确认不能滚动。资料目录和编辑器已从大页面提取。
- Phase 3 验证：feature JVM 与 app `*Novel*` 回归通过；新增资料上下文/覆盖/决定/回滚、废稿分支/冲突/Undo/嵌套路径、窄屏删除确认测试。日志 `/tmp/amber-novel-phase3-final-tests.log`；两个失败测试夹具已按实际 YAML 空格解析和真实文件阻塞修正，没有放宽生产逻辑。
- Phase 4 细化：新 FULL 归档使用小说数据集标记，精确替换嵌套小说目录；STANDARD、CONFIG_ONLY 和不含该标记的旧备份保留本地小说。恢复共用现有 restore lifecycle，小说短事务互斥且旧回合 epoch 失效，导入运行任务转暂停，不持有网络调用锁。

- Phase 4：完整 native 小说隐藏/二进制文件进入 FULL 和系统备份；数据集标记支持空快照并保留旧备份兼容。共享恢复边界拒绝旧 provider、排队作者操作和 controller 请求；恢复前后运行任务暂停并换执行 ID。实际采用 native 快照才记录自动迁移策略，保留旧源供显式迁移，未提交 journal 回滚不误改策略。
- Phase 4 review：Standards/Spec/UI 均 0 个未关闭发现；修复了恢复后旧排队 UI 自锁、自动迁移复活、通用已提交 journal 被误判成 native 已采用，以及恢复加载期间残留编辑器与操作门禁。全局锁不跨 provider/WorkManager 等待。
- Phase 4 验证：feature 142 项（0 失败、1 项跨端环境跳过）及 app 定点 180 项（0 失败）通过；日志 `/tmp/amber-novel-phase4-final-tests.log`。最终采用状态窄回归通过（16 项归档集成 + restore bridge/write gate），日志 `/tmp/amber-novel-phase4-restore-isolated.log`。前次结果目录被并行 Gradle 任务覆盖导致环境失败；隔离测试报告输出后重跑成功。
- Phase 5 细化：捕获当前分支全部实际章节完整正文，按章审核并累计带原句的事实笔记；校验引用属于真实快照，章前及末尾检查原 epoch/head/tree。停止、解析/provider 失败和源稿变化保留部分报告并明确未完成；报告作为当前分支会话保存，不收录、不自动修复、不静默截断正文或事实笔记。

- Phase 5 实现：独立约 200 行全书协调器、当前分支完整正文与累计原句笔记、严格引用校验、完成状态独立于已检查章数、IO 快照及终态来源校验、停止/失败会话报告留存完成；进度/停止在各 tab 可达，报告可反复打开、完整滚动复制，六套语言补齐。
- Phase 5 review 修复：实际 ContextEngine 会按全局/模型组的消息数丢失 SYSTEM，涉及全书审稿、联合审核、润色及下一章规划，已在小说 Runtime 统一用临时设置副本保留完整输入；不新增 API 或持久设置。真实 Runtime→Kernel→RoundEngine→ContextEngine→TokenBudgetFitter→provider 双回合测试覆盖原文尾部、早期事实、实际只读工具和普通聊天对照。停止保存采用同 dispatcher 外层 NonCancellable + 内层 IO，取消不能误报保存失败。
- 真机初审确认并修复：短分支 chip 强行占满 110dp 挤压书名；reverseLayout 草稿标题显示在草稿下方。审批正文编辑还改用既有 withBody，保留原 front matter 的列表/maps/未知字段/注释，新增实际编辑→保存→审批 Compose 回归。


## 最终验收（2026-09-30）

- 最终 app 回归：26 套件、198 项、0 失败、0 错误、0 跳过。执行 `:app:testDebugUnitTest --tests '*Novel*' --tests '*SyncArchiveManagerIntegrationTest' --tests '*SyncRestoreWriteGateTest'`；同轮 `:app:assembleDebug` 成功。日志 `/tmp/amber-novel-final-verified.log`，冻结 XML `/tmp/amber-novel-phase5-final-result-xml/`。
- feature 全套最终结果：142 项、0 失败、0 错误、1 跳过。跳过的是依赖 `AMBER_W15_ROOT` 的跨端交换 runner，不是本次 Android JVM 用例失败。Phase 5 未再修改 feature 生产源。
- 最终独立 Standards、Spec/调用链、UI 复核均 0 个未关闭发现。真实 Runtime 双回合 provider 边界测试 2 项、提案全文编辑及元数据保留 Compose 3 项、一致性报告窄屏/滚动/停止及手动重开 Compose 用例均通过。
- 上一轮 VM fixture 等待 SettingsFlow 的 5 秒超时发生在 VM 创建前；原样重跑 16 项全部通过。未放宽断言、未按未证实推测修改生产逻辑。隔离报告目录只使用 `/tmp/amber-novel-test-results.gradle`，未改项目 Gradle 配置。
- 六套语言的 241 个 `novel_*` 资源键集合一致；本次源码 `git diff --check` 通过。
- Debug APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，包名 `app.amber.agent.graphite`，114203443 bytes；SHA256 `d7aa07afb8e9c70b24061dc34d9f6ad54535017b695d9d3a5143d4080c7a3bfc`。已安装到 OPPO PMA110；未覆盖主应用 `app.amber.agent`，未清空应用数据。
- 真机为 360dp 竖屏。只在新建的合成验收小说内操作：长标题与路径目录、审批全文滚动至原稿末尾、候选编辑及确认、键盘上方审批按钮、资料标题/正文输入与创建。新资料创建后目录可见，并读取该合成项目自己的 native 文件核对标题、`smart` 注入元数据和正文落盘一致。
- 真机确认并修复短分支占满固定宽度挤压书名、倒序列表草稿分组标题在卡片下方。最终截图 `17-final-workspace.png/xml` 已独立复核，两项关闭。其他已复核截图：`09-proposal-review.png`、`12-proposal-candidate-tail.png`、`13-materials.png`；材料输入及创建最终截图 `18/19/22/24-final-*.png` 已独立复核，0 个未关闭发现。证据目录 `/tmp/amber-novel-device-evidence/`。
- 真机原旋转设置已恢复 `accelerometer_rotation=1`、`user_rotation=0`。用户原有其他功能 WIP 保留；本次没有提交或推送。

### 验收边界

真实外部 provider 的长链生成、真实 provider 的全书检查、设备后台/杀进程后恢复，以及 Android 系统云备份实际还原，本次未验收。provider 边界有实际 Runtime/Kernel/上下文组装/假 gateway 测试，恢复有归档真实文件与数据库往返、旧回合失效、迁移及 UI 门禁回归；这些证据不能冒充外部服务或系统运行证据。真机未逐一执行资料改名/删除、废稿恢复及章节历史操作，其业务和 UI 调用有 JVM/Compose 验证。

### 功能判断结论

必要作者闭环已实现：讨论/计划→候选→全文审阅/编辑→作者确认收录→资料管理/剧情同步→历史/Undo/废稿→完整备份恢复→主动一致性报告。自动跨章修复、人物时间线与 iOS 私有历史互导仍列为增强，不能称 iOS 废弃或两端完全等同。没有复制无生产调用的旧资料页面，也没有新增旧存储/普通 JSON 收录链或通用事实图。

## UI 修正：以用户提供的五个 iOS 实际界面为准

用户指出先前 Android 页面信息堆叠。此前 UI 验收主要证明局部控件可达与没有遮挡，不能证明整体层级清晰；本节取代此前对整体界面完成度的判断。保留已验证的领域能力与写入门禁，重整生产入口。

1. 主页面：居中书名与单行模式/分支/模型摘要，书名打开项目控制；右侧齿轮进入独立设置。创作/正文/设定三个主 tab 共用稳定顶部。
2. 项目控制：模式与偏好、上下文注入两个 tab；共创/代笔选择持久化到项目设置。现有计划、偏好、分支、批次进度和开始操作复用既有 owner/CAS 链。切换 tab 保留未保存的编辑内容。
3. 设置：只展示 Android 已接通的创作/审稿模型与项目管理，模型选择和恢复全局设置可用，返回行为正确。不伪造 iOS 独立剧情抽取模型或人物经历能力。
4. 创作：消息、候选和审批共用时间序列，助手正文直接排版；单文件审批展示实际原稿/拟稿与确认/拒绝，长正文在预览内滚动，多文件仍进入完整审阅。原稿读取未完成/失败不得直接确认。
5. 正文与设定：目录以章号/标题/字数组合行集中展示；资料按角色/世界观/剧情/更多分类，显示实际资料时间。保留润色、编辑、历史、废稿、检查、撤销及创建入口。分类新增预选对应类型，成功后定位实际保存分类。

完成条件：独立 Standards 与 Spec/调用链审查没有未关闭的可定位问题；设置兼容/持久化、时间顺序、审批读取与写入门禁定点测试通过；app 编译和打包成功；在独立 Graphite 测试包中拍摄并审查五个实际界面，同时检查长标题、实际审批与键盘下方操作。截图使用新建合成小说，不覆盖用户作品或主应用。

修正前基线：`/tmp/amber-novel-ui-before-20260930`。

- 结构修正已接入生产入口，独立 Standards/Spec 审查发现的审批真实目标缺失、设置返回位置丢失、同名草稿复用时间错位均已修复并复核。App 定点 7 套件 29 项全部通过，feature 项目设置旧数据兼容与模式持久化 1 项通过；日志 `/tmp/amber-novel-ui-final-all-tests.log`、`/tmp/amber-novel-ui-settings-test.log`。审批长文在 546dp 实际可用时间线高度下，标题、目标、完整审阅与按钮边界回归通过。
- 结构版 APK 已编译打包并安装独立 Graphite 包，SHA256 `9009d85b626b182dce38542354312f20e5d074d36faa1799fcb6f17815c36532`。实际合成小说五个入口已查看，最终审批图标题裁切关闭；立即硬件 Back 从设置返回工作区已实际验证。模型菜单无 provider 的提示属真实配置，不冒充真实模型选择证据。
- 用户随后指出：大多数按钮和选中块太高，主 tab、点击及设置进出缺少连贯动效。**上述功能/布局证据不等于审美验收通过。** 下一修正继续以用户反馈作为完成标准。

### UI 视觉与动效修正（2026-10-01）

1. 可见按钮/分段轨道收敛至约 36–38dp，选中背景约 30–32dp；触控区域保留 48dp，动作按钮的两行标签自然换行；分段标签保持单行省略，并按实际字体行高扩展轨道。书名、导航图标和表单动作减轻视觉重量，不用巨大填色胶囊占据整行高度。
2. 主 tab 与资料分类使用短淡入/淡出和小距离位移；选中背景平滑移动，点击反馈统一，去掉双重缩放/波纹。设置进入/返回与顶部一起过渡，无整页缩放。
3. 过渡期间已退出的页面不能接收写入点击、键盘输入或无障碍焦点；保留现有页面级输入、滚动、选章与分支/恢复作用域。不新增 domain 状态或持久化方案。
4. 按精确基线独立审查，再跑受影响 Compose 回归、编译/打包；真机重新检查可见尺寸及录屏中切换、点击、设置往返，不能用静态截图证明动效。

视觉修正前基线：`/tmp/amber-novel-aesthetic-before-20261001`。本轮实现、回归及模拟器视觉核验已完成；真机验收边界见末尾。仅修改 Novel UI，原有其他功能 WIP 保留。


### 独立章节阅读与前后章切换（2026-10-01）

用户追加 iOS 真实阅读截图，并明确章节应全屏阅读、进入/退出与前后章均有符合阅读的过渡。本节沿用紧凑按钮与纸面排版，继续完成 UI 修正。

- 点章进入独立阅读页，仅保留返回、章号/字数、章节名及更多；工作区标题、三个主标签、输入区退出阅读画面。
- 正文完整 Markdown，约 17sp 基准、舒适行距与横向边距；底部前后章按钮有安全区和正文空间，末段可读。读取中可返回；失败明确显示，不当作空章。
- 上/下一章取当前分支真实章序列相邻项，首末对应动作禁用。方向一致的轻横移与淡入同步切换标题与正文；另一章回顶部，返回目录保留位置。
- 编辑、重写、历史、移入废稿复用既有生产回调及 owner/CAS 门禁；系统返回优先菜单/编辑/阅读，不改全局导航。仅修改 Novel UI、相关资源和必要定点回归。
- 前基线 `/tmp/amber-novel-reader-before-20261001`。完成条件：独立调用链/UI review闭环，异步切章与真实邻居/边界/全文可达性测试通过，编译打包完成；实际全屏与前后章录像核验，不借用之前错误命名的图。

视觉/主标签动效阶段最终回归为 8 套件 33 项、0 失败/错误/跳过，日志 `/tmp/amber-novel-aesthetic-final-verified.log`；APK `dd8aaaf1ea93cd459de8d2c8a9f707cf215a3172729e670a602fa3c22ff5e217` 已安装 Graphite。首次设备录制途中发生额外界面变动，后续截图名称与实际页面不一致并中止，整组不作为五屏/动作链验收通过证据；仅已明确复核的创作和目录局部尺寸可保留。

章节阅读实现、回归和模拟器界面录像核验已完成：

- 独立 Reader 约 300 行；工作区页仅连接既有回调。全屏进出 12dp 轻横移，翻章 32dp 方向横移，标题及正文共同淡入 240ms / 淡出 160ms，无正文缩放。正文视口终止于前后章栏上方，留出导航安全区。
- 独立 Standards / Spec 源码审查均 0 个未关闭发现。修复了加载/失败时编辑抢走返回入口、取消异常被吞、长翻章标签挤压箭头、快速返章滚动 owner 脱节、重写占用期间误入编辑，以及等长批量润色后的正文刷新；保存/重写/还原/废稿沿既有 owner/CAS。
- 最终相关回归 **9 套件 38 项，0 失败/错误/跳过**，含 5 项新 Reader 用例及 4 项快速主标签/退出页输入回归。Reader 覆盖真实相邻章及边界、异步迟到读取、失败退出/重试/编辑、等长正文刷新、长正文末段可达及底栏不遮挡。
- 日志 `/tmp/amber-novel-reader-final-verified.log`，冻结 XML `/tmp/amber-novel-reader-final-result-xml`。首轮缺少 Markdown 必需 `LocalSettings`，之后等长正文后台 AST 解析未受 Compose idle 跟踪；仅修测试环境和等待实际新正文可见的条件，未改生产时序或放宽原断言。
- `:app:assembleDebug` 通过；当前 APK SHA256 `90b69959fe3fa5ad7f578d0df9be1d3020a209ab1188640d3d77c1242b0cd9e3` 已安装独立 `app.amber.agent.graphite`。六套语言 Novel 键均 269 项，无缺键。
- 新版真机录制遇到系统安全锁屏，需要图案/指纹，未进入 Reader，不能作为视觉验收证据。临时常亮已恢复原值 `0`，旋转保持原值 `0/0`。相同 APK 已在隔离 Android 模拟器完成以下验证，模拟器和真机证据分别记录。
- API 34、360×792dp 模拟器实际操作：创作→正文目录→全屏首章→下一章→上一章→返回目录；目录第二章及系统 Back；设定人物/世界观；设置进入/返回；项目控制及上下文注入。所有截图先核当前标签与实际页面匹配，整条脚本完成。仅使用本次合成小说，不调用 provider、不修改作者正文。
- Standards / Spec 均独立查看当前 APK 五屏、Reader 及八组连续帧，**0 个未关闭具体缺陷**。下一章标题与正文一起从右入，上一章从左入；阅读和设置进退、主标签切换均有短过渡，末态没有残留重影或缩放。长正文实际滑到最末段，最后一行 bottom=2040px，翻章点击区 top=2136px，间隔 32dp；换到不同章节回到开头。
- 证据目录 `/tmp/amber-novel-emulator-evidence`：`reader-final-device-run.log`、五屏及 Reader PNG/XML、完整 `reader-final-motion.mp4` 与事件 JSON、八组连续帧及分组 JSON。`reader-final-tail-max.png` 是末行可读证据；较早的 `reader-final-tail.png` 仅到末段开头，不作末行证据。`reader-navigation-demo.mp4` 从同一原始录像裁出 24 秒目录/阅读/前后章/返回片段；`reader-final-ui-overview.jpg` 为当前界面截图总览。
- 本轮未修改生产代码来适配测试等待，最终 APK 与冻结回归结果对应。隔离模拟器已关闭；真实设备 Reader 动效、帧率和触感尚未验收，真实 provider、后台和杀进程等原外部验收边界仍有效。没有提交或推送本地 WIP。

### 主应用同签名覆盖安装（2026-10-01）

用户追加授权覆盖手机主应用。先导出已安装主包公开 APK，校验其包名、版本及证书；本机默认 debug 和 release 证书均不匹配，找到既有签名材料后，导出的公开证书与手机主包精确一致。

- `:app:assembleGraphite` 完成，包含 Rust native 库检查；Novel UI 与已回归的 debug 版使用同一 `src/main`。用手机主应用现有签名身份签署主包，没有修改仓库长期签名配置。
- 安装 APK：`/tmp/amber-novel-main-package-install/Amber-2.6.8-main.apk`，包名 `app.amber.agent`，版本 `2.6.8 / 396`，114232556 bytes。
- 证书 SHA256：`30929ee5479b4ae117ee36c5c706cd6e1f9e536569ff7b00e09d44493d67f5ed`，与覆盖前主包一致；APK 签名验证及 16KB ZIP 对齐通过。
- APK SHA256：`101f54ce5ff4871c64e7667c241960a64f7a4abe589c26bf5fbbc45503c793a8`。OPPO PMA110 的 `adb install -r` 返回 Success；重新导出手机主包，其 SHA256 与该产物一致。
- 更新后 UID 仍为 `10406`，首次安装时间仍为 `2026-09-26 01:57:41`，数据目录仍为 `/data/user/0/app.amber.agent`；未卸载或清空应用数据。更新时间为 `2026-10-01 01:56:16`。
- 主应用启动返回 `Status: ok`，进程运行，crash buffer 未见新增崩溃。本步骤验证构建、签名、覆盖安装及启动，没有追加界面或 provider 验收。

### 完整分支整合与项目入口核验（2026-10-01）

用户指出项目列表旧顶栏与重复新建入口仍存在。查证这两处已在 `fix/streaming-render` 的 `da67ca3` 修复，但此前构建所用本地 main `ff1b70f` 未包含该历史；工作区和 Reader 的前轮五屏检查没有覆盖项目列表入口。随后用户要求完整合并该分支，已以 `6dfb01a` 合并完整 3 条分支提交并保留所有当前开发内容；140 个非重叠文件逐字保留，14 个重叠文件按职责三方融合。

本轮 532 项通过、0 失败/错误，1 项外部跨端夹具测试跳过；完整 Standards/Spec 与实际 UI review 无待修复问题。项目列表空态与有项目态均仅一个新建入口，顶部背景连续；真实 UI 新建、工作区/设置、全屏阅读与上/下一章/返回目录均已在隔离模拟器核验。合并后主包同包名、同签名覆盖安装到手机，回读 APK 与新产物一致，主包 UID/首次安装/数据目录保持，测试包仍未安装。

来源、冲突处理、回归和交付证据详见 [分支整合核验](../reviews/2026-10-01-android-branch-integration.md)。本轮没有推送，原未提交开发仍保留。
- 构建、安装、前后包信息、证书及公开 APK 核验证据保存在 `/tmp/amber-novel-main-package-install`；覆盖前公开 APK 保留用于回溯。本节是最新主包安装记录，前文独立测试包记录保留作为历史验证证据。
