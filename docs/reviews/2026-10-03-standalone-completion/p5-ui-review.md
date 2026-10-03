# P5 独立 native / UI 调用链审查

范围：普通生成稳定快照与背景保活、通知冷/热入口、整章/批量润色UI与任务恢复。生产代码只读；本报告由独立review agent拥有。**P5 source 与本阶段 product/device gate PASS**。已完成最终新包截图、AX、真实本地请求收据及系统保活日志独立检查；没有剩余已确认 UI/native/caller 缺陷。下文保留分轮审查记录，最后一节为最终结论与证据边界。

## Native基线与官方合同

- 官方离线文档`/tmp/amber-p5-background-doc.md:431`明确`startBackgroundRunning(context, bgModes: string[], wantAgent)`从API12支持，返回`ContinuousTaskNotification`。当前`['dataTransfer']`合法；Stage申请方仅UIAbility。同一UIAbility的chat/novel/deepread应共享一个活动集与租期，而非另开framework。
- `module.json5`已声明EntryAbility的dataTransfer后台模式、KEEP_BACKGROUND_RUNNING权限及NovelPolishWorkAbility workScheduler extension。原生申请/发布仍可能因设备系统合同拒绝，manifest和测试不等于系统已授予。
- `ProductIdentity.getAppBundleName`取实际Context的applicationInfo.name；普通通知、保活Want和work注册均用它。novel=`app.amber.novel`，独立deepread=`app.amber.deepread.reader`。现有actual product tests覆盖两个独立bundle与宿主，无Context时明确throw，不把通知投到宿主。
- `NotificationNotifier.buildNovelJobWantAgent`携带project/branch/job三键且同job对应稳定requestCode/notification ID；`EntryAbility.routeFromWant→routePendingWant`完整校验、暂存，在真实根页UIContext ready后路由Workspace chapters。NovelProjects与Board独立根页均调用markWantRoutingReady；等待/导航失败保留pending，新seq防旧导航清除新目标。Workspace再切请求branch、读取精确job，对陈旧job已有新活跃job显示明确提示，不仅凭通知文案定位。
- `NovelPolishWorkAbility.onWorkStart→creation.drivePolishInBackground(project,branch,job,1)`核branch并使用真实领域driver；onWorkStop沿yieldPolishForSystem转waiting_system并撤当前runner。系统择机WorkScheduler是持久恢复信号，不宣称常驻/立即触发。AppContainer的polishEffects真实连接notify/schedule/cancel；当前领域变化由相应worker审查，此处不重复或抢改。

## P5-NATIVE01：保活owner Context混用

**P2，已关闭源层缺陷；最终设备证实普通生成及润色正常授予/释放租期，UI/Extension重叠的精确owner合同由actual平台回归验证。** 修复前WorkAbility onWorkStart/onWorkStop无条件写`AppStorage.abilityContext=this.context`；NovelChatRunAdapter每次report读取该槽，BackgroundGenerationKeepAlive.reportGenerationProgress再把它写入contextRef。WorkAbility与UI重叠时，已有UI租期的stop或后续start可用WorkSchedulerExtensionContext，违反官方Stage仅UIAbility合同。

独立执行当前实际WorkAbility及NovelChatRunAdapter源码（仅平台依赖stub）得到：`before=UIAbilityContext / after=WorkSchedulerExtensionContext / actualAdapterReport.context=WorkSchedulerExtensionContext`。现有平台测试未覆盖该owner切换。没有造实机401/9800005，不把具体平台错误码声称已实测。

root最终最小修复：report不再覆盖contextRef，参数改_context；Context仅由UI EntryAbility的setGenerationKeepAliveBackground/dispose控制。WorkExtension冷跑无UI后台租期，不增加AppStorage owner槽，不改其file/bundle初始化。独立读当前方法确认contextRef只在set/dispose写入，责任边界充分。新增两条已有actual平台loader回归覆盖UI启动→Extension进度及末run删除→stop仍用原UIContext，以及冷Extension只report不申请租期。独立读root RED日志确认实际stop错用ExtensionContext，当前独立重跑相同native/routing/product组合37/37通过，其中两个owner回归通过；source gate关闭，未把平台stub称为实机系统租期通过。

## 已独立执行的现有actual测试

`generation_keepalive.test.cjs`、`deepread_keepalive_native_contract.test.cjs`、`deepread_want_route.test.cjs`、`product_identity.test.cjs`初始组合**35/35通过**，owner修复后当前组合**37/37通过**；另独立运行现有DeepRead真实scheduler背景生命周期9/9通过。这些loader执行真实生产源码，不是实现镜像。

涵盖A/B活动精确run移除、同ID真实字节增长、静默不造增长、pending start期间回前台停止迟到租期；同步publish抛错及异步拒绝可观测且保留真实持有事实；设备能力/枚举/slot不匹配不错误发布；cold根页ready、hot栈顶、三键job目标、workspace单键目标、新旧seq及productbundle。平台stubs只验证传参与状态，不能据此宣称系统通知或胶囊视觉通过。

## 最终设备必要cases

1. 独立novel用本地延迟分块fixture启动普通生成，前台→Home/锁屏期间持续真实delta；截任务通知/返回后完整末尾，保留实际hilog start/notificationId/stop。无新增内容时不造进度，无活动时不申请。胶囊与系统通知分开报告；WorkScheduler系统择机触发不以短等待未触发判bug。
2. 真实润色任务通知点击：先在另一页面热点击，再冷启动点击，核实际bundle、project、branch、job及返回栈。至少一次完整三键job；已有新任务的陈旧通知应显示当前任务及说明。实际点击系统通知与直接注入Want是不同证据，不混称。
3. 任务未结束时关闭润色页面/回前台，末活动结束后系统租期停止；如果系统触发work在UI活动时重叠，确认stop仍使用原UIowner。source actual owner回归可覆盖不易人为触发的系统重叠，明确实机边界。
4. 冻结后的整章/批量润色入口、target选择与范围确认、长原文/候选尾、progress/error/retry/pause/cancel；在真实branch/job下核最终正文及关联结果，只有真实提交后才显示已收录。
5. 普通生成DTO冻结后，核工具审批/收录入口是否随最新快照可达，长正文可滚、按钮enabled实时更新；键盘RESIZE后输入和footer、44vp热区、长标签换行/左右20vp逐图与AX核对。P5最终新包图到达后再正式视觉审查，不把P4截图当P5证据。

## 冻结UI、设置与通知独立源审

本轮独立读当前NovelPolishPage/NovelPolishJobCard、NovelRunRecoveryCard、归档呈现/Card、StandaloneSettings及Workspace对应实际caller；只读复核实施者与另一reviewer的修复，没有修改生产代码。

- 设置实际消费者闭合：StandaloneSettings不再声明/读取/写入无任务消费者的全局润色文本，只保存includePlot/includeForeshadows/includeCharacters/includeDecisions四项；成功提示明确“润色上下文默认项已保存”。首次读取四项、返回provider页后刷新职责模型但保留未保存context选择，未重引入P1覆盖草稿问题。帮助文本指向项目创建设置，真实Workspace入口可达NovelProjectSetupPage的setProjectPolishPreference；PolishPage与CreateSheet实际读取四项默认，preview/start冻结项目偏好。旧getter/setter模块没有随手删除。
- DI通知是真实outcomes投影：生产getNovelCreation工厂回调调用projectPolishOutcomes，对success/failed/driftSkipped/unprocessed独立计数；混合completed称“处理结束”，全成功才称“完成”。选定重试cursor回0时仍计保留success，cursor只用于当前目标定位，未将它当成功数。实际通知三键及120字符截短维持原链。
- 润色页可达：Workspace项目控制“润色与结果”→openPolishPage(projectId)→pages/NovelPolishPage；查看任务入口不因活跃普通run而隐藏，页内写入按真实ordinaryRunning/busy/当前job状态约束。任意章节集合及四context变化使preview失效，预览使用精确IDs与CAS；start只用作者看过的preview IDs/options/CAS。真实UI→creation→repository测试证明稀疏IDs、项目偏好持久冻结和预览后偏好修改拒绝启动。
- 润色投影与操作：逐章outcomes包含成功/失败/漂移跳过/未处理；selectedretry只传failed/unprocessed集合，取消勾选不被reload恢复。failed卡retryReady随集合变、jobKey包含集合，避免P2 primitive enabled旧值；实际actionRow清空禁用、重选启用、busy禁用。poll只读相同jobId并受visible/alive/token限制；completed/cancelled重读workspace新CAS供下一批，跨branch清选择与旧preview；model失效仍保留真实任务及配置入口。
- 恢复入口：仅当前branch、无active driver且非completed/waiting_user记录进入恢复Card；partial排除原历史assistant IDs，完整复制携带精确record ID。retry/resume分别调用领域入口，不静默互相降级；Card作者确认再次核记录、资格及alive/actionToken。页面使用retry当前持久历史长度、resume原transcriptPrefix长度接入现有attach，已修WUI01 raw/outward前缀问题。
- 再次收录：原已收录回复仍保留标签/receipt，作者确认冻结CAS后clone→reload；只有当前project真实含返回新candidate才openCollect，修复WUI02读取失败仍打开空表单。另支确认与迟回拒绝，不重clone、不增加补偿机制。
- 归档与反馈：只折叠已确认archive中的discussion source IDs，write/prose和其他讨论仍可见；资料提案渲染在折叠条件外，orphan提案仍有尾入口。展开状态只在当前页，不创建持久偏好。collectionFeedback区分正文/确定性同步点已保存与后续资料建议分析警告，project/branch/token拒绝旧回调；只有新整章收录邀请归档，不把追加/替换混为新章。
- UI源码核对：新PolishPage为单根Column包theme ForEach，Scroll TopStart；正文、冻结context和错误行自然占高，左右20vp、底部系统安全区padding。选择行48vp、主要动作44/48vp；恢复Card长原请求/partial可滚且有完整复制，明暗色使用当前tokens。以上仅为源层声明，仍等最终JPEG与AX验证实际热区/间距/长文本/键盘，未凭源尺寸关闭视觉gate。

## 本轮独立验证

执行当前actual生产方法测试：polish_notification 2、standalone_entry_lifecycle 3、polish_page_ui 15、ordinary_recovery_ui 5、run_recovery_card 5、archive_clone_ui 6，共**36/36通过**。没有新写实现镜像测试。独立执行现有`/tmp/amber-p5-workspace-reviewed-outbound.cjs`真实core collectWithEvents→Workspace recover/attach联合脚本，retry historyCount5、resume historyCount3均显示“新流正文及完整尾部”。它证明前缀调用链，不把stub snapshot称为provider恢复实测。

实施者WUI01/WUI02/WUI03的精准修复已独立源码与actual链复核，没有新确认UI/settings/notify/caller blocker。通知计数和设置保存范围均与实际consumer一致，不扩展为新默认策略或新计数模型。

## 构建前 gate 记录

Native owner Context及冻结UI/settings/notify **source gate通过**。root报告统一Novel501/501、Chat2645/2645及两tsc通过，完整两包正在build/sign；这些总数归root执行的统一门禁，未冒充本review重跑。新包JPEG/AX和实际设备系统行为仍待root提供，因此P5最终product/device gate继续pending；不声称云质量或系统实际授予。

## 最终构建前精准增量复核

独立只读复核最新Card及工具恢复caller，并重跑本报告相同六组实际生产方法：新增润色进度1项、同assistant工具续接显示/复制2项，当前共**39/39通过**。

- Card processedCount与progressBar统一取projectPolishOutcomes非unprocessed数量，remaining取unprocessed；cursor只用于当前章定位。实际builder证明cursor0但success/failed保留时2/3（66%）且剩余1；“已处理”涵盖失败/跳过，没有宣称成功收录。
- 工具首次续接通过resolveNovelToolId精确定位实际project消息及工具part，attach从目标assistant消息索引开始，保留同ID更新后的新增正文；cold工具retry/resume均取ordinaryRunHistoryCount，与领域ordinaryRunMessages的同assistant投影一致。普通turn仍用既定当前/原前缀规则，没有泛化为全局切片fallback。
- 工具partial从真实ordinary投影找本轮assistant，用冻结originalRequest.history中的同ID旧正文作比较，仅剥离未变旧前缀；不使用已含failed partial的presentation transcriptPrefix充当旧输入。实际方法验证第二次同partial失败仍显示完整新增尾，newID即使文字共享旧前缀仍保留全文。copy实际读取同一partial并传精确record ID，不复制先前讨论；普通turn仍排除原历史IDs。
- 恢复按钮工具场景为“重试工具续接”，detail明确沿用已保存的回答与工具结果、不会重新发起讨论，并提示本轮partial会被替换、需保留可先复制。原请求/partial可展开与完整复制，不添加隐式新请求降级。

这一增量没有新确认源层缺陷。最终视觉、实际provider恢复请求和native start/stop/通知入口仍按devicecases待新包证据。

## 第一批设备图：模式选择文字对比缺陷

独立view_image目测已经落盘的`p5-final-novel-root`、`p5-composer-keyboard`、`p5-mode-menu`并读模式菜单AX。根页及键盘输入本帧无重叠错位；尚不凭这三帧关闭全部P5视觉gate。

**P5-UI01 / P2，已关闭。** 修复前`p5-mode-menu`的“创作方式”原生菜单浅灰/白底，四个有效主选项讨论、写一段、写整章、归档当前讨论却以近白文字呈现，肉眼几乎不可读。真实调用是`NovelWorkspacePage.showComposeModeMenu:2762`的`promptAction.showActionMenu`，四buttons显式color:INK；深色页面INK与本帧原生菜单浅底未配对。标题黑色、取消蓝色正常；AX这四项enabled=true、opacity1，不是禁用样式。文字bounds分别y1208–1274、1348–1414、1488–1554、1628–1694。影响实际选择模式与归档入口。

精准修复限定Workspace四个原生showActionMenu（创作方式/Export/Share/ChapterTools）的选项前景/背景配对。此前建议省略color需修正：root核API12官方Button.color必填，最终保持显式color，改用`$r('sys.color.ohos_id_color_text_primary')`，次要不可用项使用同系统tertiary，随系统原生菜单配置配对；未改页面tokens、动作索引、consumer或新框架。

## 原生菜单修复与TXT系统交付分项复验

root重新两包build/sign/install，日志`/tmp/amber-p5-native-menu-build.log`及单页lint`/tmp/amber-p5-native-menu-lint.log`。本review只读核上述四调用当前颜色与原索引行为，独立view_image目测`p5-mode-menu-fixed`、`p5-export-menu-fixed`、`p5-share-menu-fixed`并读取AX：四创作选项黑字浅底、全部可读且enabled；导出六项与分享五项完整可读，长中文/ZIP标签未错位或重叠，取消仍有清晰独立入口。P5-UI01 source与实际设备分项关闭。

独立目测`p5-txt-system-saved`及`p5-txt-share-system-panel`与AX：实际系统保存返回后有“已导出P4-Renamed-Verification.txt”Toast；ShareKit原生面板显示该文件名及798B、关闭入口和复制/另存为/打印/中转站操作，无文字重叠。本分项证明TXT保存操作完成反馈及真实文件分享面板打开，不宣称已发送给外部接收人或已设备读回文件全部字节；ZIP/native与其他格式交付仍按随后真实证据分别验收。

当前没有剩余已确认菜单/UI缺陷。整体P5 gate仍待润色混合结果/定向重试、恢复、系统通知及保活的最终设备证据，不因此菜单分项提前PASS。

## 最终新包设备独立审查：PASS

最终构建签名日志为`/tmp/amber-p5-card-final-build.log`。独立读取`device/p5/final-artifact-fingerprints.json`：35个生产源文件、两个产品生成副本全部匹配，无discrepancies；其构建证据记录两次成功构建与两次签名校验。完整源副本哈希计算由exchange reviewer执行，本review读取证据而未重复构建或操作设备。

| 分项 | 独立 JPEG / AX / 收据观察 | 结论 |
| --- | --- | --- |
| 混合结果与定向重试 | `p5-polish-latest-result-page`显示1收录/1失败、严格JSON受控错误与具体章节；`p5-polish-retry-real-outcomes`保留成功章并运行失败章；`polish-retry-completed-notification`与`polish-retry-completed-workspace`一致显示2/2成功，逐章结果均收录。request-receipts的同job总5请求，章1 writer初始/重试body相同、冻结偏好及上下文一致，章3 writer/reviewer各一次，没有重复处理成功章。长内容正常Scroll，未出现卡片重叠。 | PASS |
| 原请求恢复 | `p5-recovery-card-top-final`显示当前branch、保存的原请求、失败部分稿、完整查看/复制/重试原请求入口；AX三个动作高度154px，即本机44vp。`p5-recovery-full-partial`扩展正文为独立可滚区域，复制仍可达；不以该单帧声称部分稿尾已滚到。`p5-original-retry-completed`实际完整末尾`P5_FULL_OUTPUT_TAIL`可见，收录按钮与固定输入区分隔正常。真实收据两次原请求body完全相同，旧history保持且失败partial未加入输入。 | PASS |
| 正文收录与分析反馈 | `fresh-collect-success`明确“正文已收录，剧情同步点已保存”及后续资料建议无完整JSON的独立警告，目录真实增至4章；邀请整理讨论的系统确认可读。受控无效分析fixture没有被伪装为未保存正文或分析成功。 | PASS |
| 再次收录 | `clone-confirmation`解释新候选、作者选段/位置、原记录保留；背景仍有已收进章节标签。`clone-new-candidate-form`真实打开新候选1/1段、可编辑正文、新开第5章位置，底部取消/收录AX各154px，距系统手势区有留白。这是新候选表单成功打开，不宣称又提交第5章。 | PASS |
| 讨论归档 | `archive-strict-draft`显示8条原讨论、可编辑82/2000摘要、创作决定默认不选/无决定、确认归档与取消均154px。`archive-folded-original`真实显示8条归档及摘要，另一个正文/改稿消息仍可见；`archive-expanded-original`显示原讨论，动作变收起，长正文可滚。没有误把归档原文删除或将正文折叠。 | PASS |
| 热通知及同卡刷新 | 实际通知shade中的当前章4任务与旧2/2完成通知区分可读，root操作记录为点击当前任务返回正确project/branch/job；`card-final-hot-workspace-writing → card-final-paused → card-final-resumed → card-final-automatic-completed`独立逐帧目测与AX，进度0/1→1/1，状态写作→暂停→写作→完成，结果为章4已润色收录。进度accessibilityId5043及状态5048四帧保持一致，支持原卡原位更新，未以重入页面代替刷新。暂停/继续/取消均154px；执行阶段左边界108px与正文卡内边距一致。最终自动完成图补齐，刷新gate关闭。 | PASS |
| 冷通知与后台 | `p5-polish-cold-notification-return`独立目测正确项目/branch和混合结果；root设备记录为force-stop后点击真实完成通知冷启，通知三键路由source/actual测试已独立通过。该早轮图的阶段居中问题由最终同卡新图左对齐修复验证，未重复算成新缺陷。`p5-ordinary-background-completed`实际输出收录入口可见，键盘下输入区完整。ordinary-background-hilog真实started notification0→stopped，polish-background-hilog真实started notification1→stopped，证明本设备曾授予并释放租期。 | PASS |
| 菜单与安全区 | 已关闭四系统菜单颜色缺陷；追加独立目测`chapter-tools-menu-fixed`，三动作黑字浅底完整可读。恢复、候选收录、归档与润色操作的长标签、对齐、间距和44vp自定义热区均无新确认缺陷。受限高Scroll/列表的一帧下端截断是常规可滚内容，不凭单帧裁切制造错位finding。 | PASS |

当前生产`NovelPolishJobCard`各Builder直接读取`this.job`/state，阶段Column显式Start，卡Scroll为TopStart；没有依赖旧按值primitive参数重建来刷新文案。WUI06的最终同页四帧证据与这一精确修复一致。该增量确认不扩展为全局重构。

最终结论：**P5在本报告范围的实现、调用链与产品设备门禁通过，无待修确认缺陷。** 已独立执行的39个UI/settings/notify实际方法测试和37个平台/routing/product测试结果仍有效；本轮仅收口新设备证据，未重复大范围测试。云模型质量、各厂商无限期后台存活、WorkScheduler系统择机重叠、真实云Responses GET恢复与外部文件接收均没有被本阶段本地fixture/设备图证明。普通通知错过点击时机的尝试不计成功；热/冷三键通知由真实润色任务覆盖。精确Extension owner重叠以实际模块回归证明，当前设备日志不冒充人为触发过该重叠。以上是证据边界，不是遗留已确认产品缺陷。
