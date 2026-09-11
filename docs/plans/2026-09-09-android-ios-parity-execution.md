# Android 追齐 iOS 执行台账

实施依据：[调研](/Users/mi/Downloads/AI/AmberAgent/android/docs/audits/2026-09-09-android-ios-parity-research.md)与[工作包计划](/Users/mi/Downloads/AI/AmberAgent/android/docs/plans/2026-09-09-android-ios-parity-plan.md)。用户已授权连续实施、每阶段 subagent review 并修复，不需要再次确认。

## 阶段与完成门

| Phase | 交付范围 | 必须闭合的结果 | 状态 |
| --- | --- | --- | --- |
| 0 基线与执行计划 | W01；固定工作树、测试入口、UI 验证环境；完善阶段依赖 | 用户 WIP 可识别；每个工作包归属明确；subagent 核查计划与证据边界 | 完成，复审通过 |
| 1 日常反馈与写入契约 | W02/W03/W05 | 模型刷新、发送配置、附件/历史/搜索反馈、任务写入和记忆 CAS 有实际消费者与定点回归 | 完成，逻辑/UI 复审及设备验收通过 |
| 2 继续工作与数据恢复 | W15 契约 → W04 路由骨架 → W14 owner → 路由验收；W06 | 精确路由、恢复重试/隔离、小说中断恢复；真实交换契约与已支持范围验证 | 完成，逻辑/UI/迁移复审通过 |
| 3 子代理与浏览器 | W07/W08/W09 | 编排与任务卡、同会话接管/交还、弹窗/动作回执、站点确认 | 完成，逻辑/UI/设备复审通过 |
| 4 小应用系统能力 | W10/W11；W18 的 Q03/Q04/Q05 | bridge 到系统 owner、权限、生命周期闭环；未保存退出与错误恢复 | 完成，逻辑/UI 复审及设备验收通过 |
| 5 账号、系统入口与终端 | W12/W13/W16/W17 | 登录请求、日历/快捷入口/试听、managed SSH 真实接线 | 完成（SSH 客户端来源待决；真实账号未 smoke） |
| 6 手机产品集成 | W19 手机集成子集；Q08/Q10 最终核查；D01–D20/Q01–Q11 | 手机构建、升级、跨模块旅程、UI 视觉/语义检查 | 完成（D01 待真实账号、D20 客户端待决，逐项边界见记录） |
| 7 系统生态扩展 | E02 健康摘要、提醒/闹钟、运动计划 | 按 Android 可用系统能力完成等效用户流程，明确数据来源 | 完成（Health Connect 系统授权流/运动计划互操作按平台边界记录） |
| 8 可穿戴伴侣 | E01 | 手机 owner、可靠交接、草稿/结果/任务与受限操作 | 协议层完成；Wear target/transport 为外部输入阻断项（见 Phase 8 记录） |
| 9 全产品收口 | E03 平台适配；W19 全产品最终回归 | 手机/可穿戴构建、交接、升级、UI 及未支持范围逐项核验 | 手机收口完成；可穿戴/SSH/真实账号等外部边界逐项记录（见最终边界清单） |

所有阶段都必须经过独立 subagent review，重点检查：入口→配置→执行 owner→持久化；取消/失败/返回后的下一动作；布局错位、对齐、边距、控件大小、字体缩放与语义。review 只提出可定位问题，不将假设风险强行改成抽象层、泛化重试或额外兜底。主 agent 整合并修复，定点复验后再进入下阶段。

W01 的旅程数据随实际功能补齐，不先搭建庞大模拟框架。W15 除验证外，若发现明确的交换契约缺口，补最小可工作的交换实现并验证；不以旧 JSON 测试冒充当前工作区互通。W17 优先复用已有运行时/客户端，不能自行实现 SSH 协议。E01/E02 在 Phase 7/8 继续执行，Phase 6 只表示手机主线完成。交互 PTY 是原计划的可选新增范围，managed SSH 第一版仍如实声明非 PTY。

Q10 的单项开关在其 owner 所属阶段评估：SyncProvider 随 W06、ThreadGraph 随 W07，Phase 6 最终检查。Q08 在运行恢复验证后处理。阶段内可并行，跨阶段必须通过 review 门。

## 实施约束

- Android 初始 HEAD `ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62`，39 个已有 tracked WIP；原始 diff 与文件摘要保存于 `/tmp/amber-parity-start.patch`、`/tmp/amber-parity-start-files.json`，只用于区分改动，不自动回滚。
- iOS 对照 HEAD `1023e8a08f5957157226725b43dbf3e2e7078a17`，只读授权持续有效。
- Ponytail full 已在本会话激活，不重复加载。复用现有 owners/Compose/Room/WorkManager，禁止过度设计。
- 多 agent 实现按文件边界分工，测试/构建由主 agent 串行运行，避免 Gradle 输出互相覆盖；review 与实现角色分开。
- 每条差距记录为待实施、实现/静态通过、运行通过或尚未验证；没有设备/真实账号时不能把源码通过写成真机通过。
- 不自动提交或发布，不新增依赖，不覆盖无关 WIP。

## 阶段记录

### Phase 0

- 已固定初始 WIP，与前一轮基线一致；此前 83 项定点单测通过。
- UI 基线：`./gradlew :app:testDebugUnitTest --tests app.amber.feature.ui.components.ai.ModelMenuInteractionTest --tests app.amber.feature.ui.components.ai.ComposerInteractionTest --offline --console=plain`，5+4 共 9 项通过，0 失败/跳过。日志 `/tmp/amber-parity-phase0-ui.log`。
- 设备：AVD `od_mobile_test`，`emulator-5554`，API 35，1080×2400，420dpi；以只读、不保存快照方式启动，可 adb 截图/操作，不影响原 AVD 保存数据。
- 已有 APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`，metadata variant=debug、package=`app.amber.agent.graphite`、version=2.6.8/396；已安装并启动。它是既有产物，不能证明对应当前源码，Phase 1 将重新构建。
- 合成输入复用现有 Compose 模型菜单/输入门 fixture；后续旅程按所属阶段增加。
- review：`/root/phase0_review` 检查了覆盖、依赖、WIP、UI 证据和范围。已补记录模板、W18 归属、Phase 2 顺序、Phase 7/8 全产品扩展及硬 review 门，等待针对性复审。

## 每阶段 review 记录模板

每阶段记录 reviewer 子任务名及报告、实际调用链与持久状态、失败/取消/返回路径、UI 几何/语义与截图、测试命令/结果、主 agent 修复条目及复验/复审结论。静态、JVM、合成 fixture、模拟器、真实账号证据分别记录。review 未通过不得进入下一阶段；外部不可验证项不得标为验证通过。

初始 WIP patch SHA-256：`f000760804b534bf17f31e7f23a6dcef759a35b3f4beda609946540e7def321f`。文件路径与 SHA 保存在 [基线记录](/Users/mi/Downloads/AI/AmberAgent/android/docs/audits/2026-09-09-parity-wip-baseline.json)，不含源码/凭据。

Phase 0 实屏证据：[首页截图](/Users/mi/Downloads/AI/AmberAgent/android/docs/audits/parity-artifacts/phase0-home.png)。启动完成，空会话首页、功能轨与新建按钮可见；这是既有 APK 的基线，不当作之后代码的视觉验收。

Phase 0 复审：`/root/phase0_review` 已通过，基线截图未发现明确布局阻断；已区分 W19 手机子集与全产品最终回归。Phase 1 开始：模型候选、附件、历史搜索、任务持久化、记忆 CAS 按文件边界由五个实现 subagent 并行，主 agent 负责发送配置提示、集成与统一验证。

### Phase 1（完成）

- 实现分工：`phase1_models`、`phase1_attachments`、`phase1_history`、`phase1_taskstore`、`phase1_memory`；主线实现 ChatConfigurationIssue/Hint 与聊天发送前修复入口。
- 当前已落码：模型候选与选择分离、刷新失败原位重试/旧响应隔离；历史与全文搜索错误、首页标题筛选；任务原子快照/先持久化再发布/清理旧错误；记忆编辑删除 CAS、保留草稿和最新版本；发送配置持续提示。
- 集成修正：删除任务重复落盘；记忆状态 API 设为 internal、保存中禁止继续编辑、最新版本读取失败不冒充已删除；模型请求切换清除旧投影、使用最新 provider 防覆盖配置。
- 任务存储最初 5 项回归及删除重复落盘后的复验均通过（`/tmp/amber-parity-phase1-taskstore.log`）；之后增加损坏记录保留、首次注册失败不发布两项，待下一轮统一验证。
- 首轮 app 编译捕获附件接口/资源尚未完成和首页 StateFlow 未 collect；反馈对应实现者。该次未进入测试，不计通过（`/tmp/amber-parity-phase1-app.log`）。
- 独立 review 已启动：`phase0_review` 检查逻辑/持久化/调用链，`phase1_ui_review` 检查页面状态和布局。仍需修复报告、完整测试、新 APK 模拟器截图与复审，尚未通过阶段门。
- 第二轮 app 编译通过，30 项测试中 29 通过；唯一失败为新增 MemoryCasTest 的 Int/nullable Long 比较，已改为 Long 常量。配置判定 6 项、窄屏/大字体配置提示 2 项通过。后续补丁新增了异步查询和更多附件测试，须重新统计最终结果。
- APK 已在关闭本次 KSP 增量生成后构建通过（51 秒）；此前增量模式失败为 `Unexpected owner function: null`。未修改构建配置；后续验证临时带 `-Pksp.incremental=false`。只读模拟器旧测试 APK 签名不同，移除该空测试安装后成功安装当前构建。
- 实屏证据已保存 `parity-artifacts/phase1-home.png`、`phase1-home-search.png`、`phase1-chat-config.png`、`phase1-model-repair.png`。首页内联筛选/无结果/键盘可用，配置提示无重叠，缺模型修复可进入服务商配置。这些为本阶段中间构建，最终补丁仍需重装复核。
- UI review 已修：长按建议/生成组件/MiniApp 修改/再生成/生成图片修改复用配置判定；无可用模型的空菜单修复入口；取消编辑只删除新导入的附件，成功发送保留附件；待命缩略图短文案与无障碍状态；模型整行选择与 Current 点击区；记忆新增入口与说明按钮同时可见、编辑内容可滚动。
- 独立逻辑 review `phase0_review` 首次结论不通过：新增记忆分类/置顶未提交、底层 FTS 吞异常、同 provider OAuth 账号切换候选缓存、Gemini 登录旧快照覆盖。已交各 owner 定向修复；补查 ProviderConfigTools Codex 登录状态与 Cron 完成清理错误字段。未将未通过的阶段提前标记完成。
- W19 本地化补充：英语 locale 下旧页面仍混用硬编码中文（首页轨道、provider identity 等），Phase 6 统一核查；Phase 1 新增资源持续提供 en/zh。

- 最终统一定点回归 59 项通过（app 52 + task 7；`/tmp/amber-parity-phase1-final-tests.log`）；另跑 ProviderConfigTools 34 项、模型候选 5 项通过（后者与前批重复），日志 `/tmp/amber-parity-phase1-auth-build.log`。
- 实屏补发现损坏 PDF 返回另一种解析失败字符串。精确兼容现有 parser 错误信号，补本地化说明和 3 项回归，通过日志 `/tmp/amber-parity-phase1-pdf-final.log`。最终实屏 `parity-artifacts/phase1-pdf-read-failed.png` 显示红色 Text read failed，普通文本 143B 导入已验证。
- 大字体 1.5 倍实屏补发现分类按钮 pill 裁字及长编辑弹窗 Save 被 IME 覆盖。只修改该按钮 shape 和编辑 Dialog IME insets；最终 assembleDebug 通过（`/tmp/amber-parity-phase1-ime-build.log`），已安装对应 APK 复验，字体比例已恢复 1.0。
- 合成记忆从新增→选择 Core→置顶→保存→重新打开，Core/Pinned/来源/更新时间均正确；大字体+IME 可滚动至分类和置顶，Save/Cancel 始终可见。最终图：`phase1-memory-large.png`、`phase1-memory-large-ime.png`、`phase1-memory-large-ime-bottom.png`；同目录另存新增入口、默认弹窗、保存后列表和待命卡。
- 独立逻辑 reviewer `phase0_review` 定向复审全部阻断及 Codex 状态脱敏、附件错误链路，均通过。独立 UI reviewer `phase1_ui_review` 对最终截图及实际 modifier 复审通过；没有将紧凑但无重叠的分类行强行修改。
- Phase 1 证据合计 96 项不同测试（59 + ProviderConfigTools 34 + attachment warning 3），0 失败；模型候选额外 5 项复跑不重复计数。未使用真实 OAuth 账号/在线生成，不把本地状态测试写成账号登录实测。

### Phase 2（完成）

先完成 W15 真实两端 exporter/importer 契约证据与明确缺口，再推进 W04 路由骨架、W14 小说恢复 owner 和路由验收；W06 备份恢复可独立并行。各实现者保持文件边界，统一 Gradle 由主任务运行，阶段末逻辑与 UI 分别 review。

- 阶段内依赖细化：W15 真实 Novel 交换、对话交换分别由 `phase2_exchange`、`phase2_conversation_exchange` 负责；`phase2_restore` 独立实施 W06；`phase2_routes` 负责非 Novel W04。主线只修改 Android 本地 `.amber/jobs` 的恢复 owner 与精确路由，不改变 W15 交换树；交换实证仍为本阶段验收门。
- 定向事实核查 `runtime_audit` 确认已有 execution CAS、commit 前缓冲、ledger 后保留和进度去重，无需重写。模型输出在 commit 前没有 durable payload，进程被杀后不自动重放；脏正文继续由现有 worker 预检明确失败。
- 主线已补：WorkManager 入队 Operation 完成后才返回、controller mutation/恢复查询串行、应用启动和项目页 reconcile、损坏 job 名称保留并进入 UI；显式 branch/job 归属验证，完成/取消批次不会显示正在运行。
- 首批 Novel recovery 5 项通过；初测两项因现有 wire 时间精度为秒而直接比较纳秒失败，已按既有 wire 精度修正断言，未改变持久格式。新增 route fixture 和真实 WorkManager 入队/异步数据库失败测试继续验证。整合首轮编译捕获主线 onFailure 格式错误，已修；不把中间构建失败计为通过。
- 发现 Continue sources 的既有 Koin 注册使用相同未限定接口 key，会相互覆盖；交 route owner 改具名注册并补真实接线测试。DeepRead source_url 由该 owner 做 Room 16→17 可空列迁移，已通知 restore owner 保持旧归档兼容与数据集保护。

- Novel 定点集成通过：实际 WorkManager 异步入队失败、重建查询、通知身份、Continue、Runtime 21 项均通过。进一步 review 修复 rewrite/consistency 两条未登记停止的操作（turnJob + finally）、旧失败卡删除新 execution 的竞态（状态/execution CAS）、dismiss 后无法再开新批次面板；`phase0_review` 定向复审通过。
- 扩大路由批次 app 67 项中 66 通过、1 个 DeepRead sourceURL fixture 因固定 1970 时钟被现有 TTL 判断过期，owner 已修 fixture 为 ttlDays=0，待复跑；Novel recovery 7 项通过。日志 `/tmp/amber-parity-phase2-routes-build.log`，未把该批计为全通过。
- 当前阶段中间 APK assembleDebug 通过，日志 `/tmp/amber-parity-phase2-ui-build.log`，已安装模拟器。冷启动真实本地无 WorkManager 的 running fixture → failed，Home 同时显示损坏记录和精确批次；点 Continue 开对应批次、点“知道了”回新批次表单，大字体 1.5 重新打开可用。截图 `parity-artifacts/phase2-home-recovery.png`、`phase2-novel-interrupted.png`、`phase2-novel-new-batch.png`、`phase2-novel-large.png`、`phase2-novel-large-sheet.png`。此 APK 尚不包含后续备份/memory/board gate 收尾，不能作为 Phase2 全量最终产物。
- UI reviewer 精确发现并修复首页搜索触控高度、MiniApp重试异常无反馈、Novel按钮触控高度；根Activity已enableEdgeToEdge，未采纳把MiniApp恢复decorFits改true的未经证实建议。Backup口令/长预览scroll+IME已由独立owner补，待设备复验。
- W06 独立 `runtime_audit` 发现仅Chat gate不够：cancel后terminal/ledger写、独立memory extraction、启用的Board WorkManager、MiniApp bridge存在真实旧payload写入。共享epoch接口复用现有gate；restore owner处理Chat/run/tool/MiniApp/thread，memory和board两个owner分工接短持久写入，不在网络/LLM期间持锁。阶段门仍未通过。

- W15 Novel 真实交换三段通过：iOS 生产 exporter 12 files → Android 生产 importer/edit/exporter 12 files → iOS 生产 importer/validator，再导出 13 files（重建章节 plot module）。Swift fixture 另核验 plot summary/outline、upcoming beat、confirmed plan。详见 `docs/audits/phase2-exchange.md`。iOS UI 接受工作区文件夹，Android ZIP 需先解压；两端本地 ledger/jobs/sessions 不在交换树内，未宣称运行任务迁移。
- 普通对话实际模拟器 SAF 导入：iOS 生产 storage 生成的合成文档经 stored ZIP 封装，Android 预览 1 个新增并确认后，Room 实际为 1 个 conversation / 1 个 message node。发现存储卡仍 Loading、成功文案未刷新，新增真实 CardGroup Compose 状态切换测试定位；问题尚未判定或验收。
- W06 收尾补查真实删除入口：FilesManager 的 attachment/chat_images unlink 与 managed row 在同一短 gate；ConversationRepository/ChatService 的异步 cleanup 传递旧 epoch；SessionCleanupManager 原始 SQL 清理路径补 gate 并携带预览 epoch，避免旧预览删除恢复后的数据。独立 owner 测试及 review 继续进行。
- 本轮两次统一回归被新增 block-body 返回类型编译问题挡住（Novel VM 测试、FilesManager），均已定点修正，正在重跑；这些失败不计入通过数量。
- 普通对话生产 codec 反向验证完成：Android `ConversationExchangeInteropTest` 1/0/0，真实输出交给 iOS `JsonConversationStorage.importConversations` 后重读，标题为 Android 修改后的值且消息保留。双端 ZIP 和 stdout 已保存于 `parity-artifacts/phase2-*-conversations.zip`、`phase2-conversation-ios-reimport.log`；Swift runner 保存在 `scripts/conversation-exchange/`。
- `phase0_review` 定向复审通过 W04 pinned DeepRead、点击前已删除 Chat/ImageGen 不导航，及 W15 SAF/codec IO、取消清理 busy、同一 gate 内冲突重检。极窄的查存在后再并发删除竞态未被该 Home 补丁消除，未宣称实现底层“只能打开、不能创建”的新 Chat 契约。
- 集中回归 app 116 + settings 6，共 122 项，118 通过、4 失败、0 跳过（`/tmp/amber-parity-phase2-closeout-tests.log`）。通过项包括真实 SyncArchiveManager 4 项、Novel VM 4 项、StorageCleanup 9 项、CapabilityFlags 6 项。失败为 CardGroup 动态更新、两项 Exchange summary SQL 的 Robolectric json_extract、HotList 领域对象/实体断言比较；对应修复后继续复验，不能把该批写成全通过。
- CardGroup 第一次 NonSkippable 注解修复被真实测试证伪，已删除该尝试，改为直接渲染 composable item/rawItem，移除普通 mutableList 注册投影。分组保留 18dp 外圆角、2dp 行内圆角及 1dp 间距；测试增加 Loading→成功→Loading→第二次成功，最终 APK 将复核整个存储/备份卡片布局。
- `runtime_audit` 复审通过 SessionCleanup 旧预览 epoch、managed membership、保留其它会话共享附件、路径去重及不可取消删除提交；真实 Room QueryCallback 在首个 DELETE 调用点取消任务的回归已通过。没有为物理 IO/DB 故障另建 journal，原有明确失败/重试行为保留。
- Q10 / SyncProviderV2 现按能力单独默认开启，显式 false 升级后保持关闭。`phase0_review` 确认 DI→VM flag→WebDAV/本地文件夹 UI 与关闭时 VM guard 闭环；6 项 flags 测试通过。真实 WebDAV/Google 账号烟测仍未进行，不把 Mock provider 测试计为实网验证。

- 前批 4 项失败已定点复验：CardGroup 动态更新 1、ConversationExchangeService 3、HotList 2，共 6 项全部通过，assembleDebug 同批成功（`/tmp/amber-parity-phase2-ui-final-build.log`）。22:31 APK 实际安装后存储统计、导入成功反馈均正常；UI reviewer 复核通过。
- 实际 SAF 选择路径→自定义口令→确认口令→加密导出成功，1.5 倍字体与键盘弹出时操作可见；截图已归档 `parity-artifacts/phase2-backup-export-*.png`。WebDAV 输入表单亦完成大字体/IME 验证，但没有使用真实远端账号。

- W06 最后收尾：Chat 同步 update 只发布内存投影，物理附件清理归 suspend save/delete owner；Council 和 ThreadGraph 的长任务在首读前捕获同一 restore epoch。独立复审及最终回归中。
- 设备迁移初测因中间 KSP 误改历史 16.json 而重复 source_url 失败；确认该历史文件不属于用户初始 WIP 后恢复 HEAD 原件，生产迁移未加存在判断兜底。该次 Gradle connected 还自动选中了已连接的 M154FF（与模拟器均失败）；后续固定 ANDROID_SERIAL=emulator-5554，只在合成模拟器运行。此次不计迁移通过。
- modelcouncil 模块 CouncilRoomManagerTest 7 项已通过（`/tmp/amber-parity-phase2-runtime-migration.log`）；同批 app JVM 未执行完，另批重新运行。

- 最终 runtime JVM 43 项全部通过，0 失败/跳过，包含网络恢复 barrier、ThreadGraph 长任务 epoch、Archive 实际恢复、Backup VM 清理及通知（`/tmp/amber-parity-phase2-runtime-final.log`）；同批 assembleDebug 成功。Council 模块 7 项另批通过。
- 历史 schema 修复后，指定 emulator-5554 的 Room16→17 真设备迁移 1 项通过（`/tmp/amber-parity-phase2-migration-final.log`），`phase2_routes` 独立确认 schema/SQL 一致。
- 最新 APK 已重新安装；HomeHeader 删除冗余 weight Spacer 后默认字体日期完整、1.5 倍字体仍合理截断且按钮不重叠，截图 `phase2-home-final.png` / `phase2-home-large-final.png`。字体已恢复1.0；原有功能轨道大字体标签截断纳入 Phase6 全局 UI 核查。

- `runtime_audit` 最终复审确认 Chat/ThreadGraph 无旧 epoch durable 漏写；新增窄场景修复：失败且无数据提交时清除 deferred epoch（保留 tombstone），成功/partial 后迟到 purge 被 tombstone guard 拒绝；purge 首先捕获 invocation epoch，避免 guard 与 map 读取之间的 restore 竞态。该 ChatService handler 以独立源码复审验证，未宣称实际构造 ChatService 的集成测试。两项只镜像 handler 的测试已删除，复用真实 gate/Files 回归。
- UI reviewer `phase1_ui_review` 最终通过加密导出/键盘、WebDAV大字体、存储/导入动态卡片以及HomeHeader默认/大字体实屏，无新的几何阻断。

- 最终 gate/Files 9 项、assembleDebug 全通过（`/tmp/amber-parity-phase2-purge-final.log`）；`phase0_review` 对最后删除时序定向复审通过。Phase2阶段门完成。真实云账号与全量备份跨端互导不属于已验证结论，W15支持子集详见专项报告。

### Phase 3（完成）

W07由phase1_models实施；W08由browser_miniapp_parity实施；W09由phase2_routes实施。接口与文件所有权参考 `2026-09-09-phase3-browser-contract.md`。主任务负责集成、受控网页/设备验证和阶段review，不并行执行Gradle。

- Phase3 分工细化：W08 owner/SessionHandle/WebViewPool/popup/DI 保持 browser_miniapp_parity；独立页面/卡片/Screen/RouteActivity 交 phase1_attachments，避免 owner 与工具接线被 UI 实现串行阻塞。W07 仅子代理专属 Cot/card，不改 ChatPage；W09 仅工具与窄 run 参数接线。
- 主线新增 `scripts/webmount-parity/` 合成网页（JS 语法检查通过），以及 `WebMountParityDeviceTest` 实际 WebView/bridge 测试，待生产实现就绪统一运行。受控服务仅监听本机127.0.0.1:18765，并通过指定模拟器adb reverse访问，不连接真实网站账号。

- 首轮生产编译捕获3个Lucide扩展import与1个receipt参数类型错误，定点修复后app生产编译通过。首轮app定点25项中23通过、2失败（新增JUnit非void返回与Integer/Long断言），settings flags 7项通过；fixture已修，第二轮统一回归中。日志 `/tmp/amber-parity-phase3-first-compile.log`、`/tmp/amber-parity-phase3-first-tests.log`。
- 主线检查补出真实断点：飞书snapshot/network工具绕过owner；只读WebView系统返回可改历史；JS confirm持有AGENT lease使人工无法接管；显式重开仅创建空白handle。按文件owner定点修复，并补实际createClickTool→人工resolve及重开加载设备用例，未把直接调用底层resolve当作产品流程通过。

- 第二轮app定点42项41通过，idle mailbox followup的PERSISTED断言失败，owner定位中；真实设备APK构建成功。指定模拟器首轮WebView 11项7通过4失败：重开仍IDLE、headless JS dialog resolve调度、真实tool交还后dialog同问题，以及popup等待默认可见区域造成的fixture问题。已分别修production/main-thread调度与测试visible_only，不把失败写成通过。日志 `/tmp/amber-parity-phase3-second-tests.log`、`/tmp/amber-parity-phase3-device-build.log`、`/tmp/amber-parity-phase3-first-device.log`。
- W07独立review发现child scope需逐generation唯一且终态释放、旧runner迟到回调identity、interrupt任务/事件收尾、冷启动直接followup的DELIVERED重排队4项阻断，均交原owner定向修复；阶段门尚未通过。

- W07四项逻辑修复已由phase0_review定向复审通过；尚待统一测试，期间其他UI owner的新测试import/Council helper中间状态曾挡住编译，未计通过。W08实屏沿Home站点→恢复卡→页面内重开成功，草稿输入/保存/交还、1.5倍字号/IME五图经独立UIreview通过。实际popup打开及切换可用，但关闭后返回原页发现黑屏，交UI owner按真实WebView identity修复；因此尚未宣布W08完整通过。字体已恢复1.0。
- W08/W09独立调用链review指出LRU reservation/pin竞态、取消精确handle、旧run取消误伤HUMAN新dialog、物理Main dispatch需与lease校验原子化；另补scoped tab_list、导航/写请求回执、取消透明传播。按现有owner/token/工具小范围收口。正常COMPLETED保留已明确human handoff、取消/失败清理；新增实际工具→正常endRun→HUMAN与取消后迟到confirm用例。
- 主线收口站点增删单次确认：PermissionDecisionResolver沿既有foreground强制确认模式，优先于全局unattended开关，仅wm_site_add/remove受影响；新增真实resolver回归3项。

- 最终集中 JVM 90 项全部通过，0 失败/跳过；同批 app/androidTest APK 构建成功（`/tmp/amber-parity-phase3-closeout.log`）。前批4项失败已精确修复：clickable内部48dp语义边界、Council旧start/running与真实终态读取/冷transcript的优先级；没有改变Council任务retry领域含义。
- WebView 设备最终16项全部通过（`/tmp/amber-parity-phase3-final-device.log`），含复用已有handle取消后保留页面/草稿、新建取消清orphan、reservation期间LRU保护、旧lease物理dispatch拒绝、正常completed保留human handoff、取消后迟到确认无效及opener/cookie/popup关闭。仅固定emulator-5554、合成本地网页，未使用真实SSO。
- 最新UI实屏 `phase3-browser-auto-reopen-final.png` 确认首次重开直接加载；`phase3-browser-confirm-final.png` 确认弹窗布局及取消返回；`phase3-browser-popup-returned-final.png` 确认popup关闭后原页面恢复且显示合成登录完成。旧 `phase3-browser-popup-returned.png` 留作黑屏失败证据。根因是旧DisposableEffect清理新WebView，已按实际WebView实例key修复。
- W08/W09 reviewer `phase2_restore` 对原最终清单复审无新阻断；追加的“物理dispatch后、WebChrome回调前结束run”假设要求用真实WebView独立复现，尚未将假设转化为新框架。Phase3门等待最后UI结论和该定点证据裁定。

- 追加late-dialog已用真实WebView复现失败，再用最小无lease新dialog取消规则修复；已存在人工handoff不变。最终两个device class共17项全部通过（`/tmp/amber-parity-phase3-device-closeout.log`），页面实际confirm返回false亦有断言。独立reviewer复核通过；幂等host-shim安装未当作业务写动作扩改。最终UI reviewer三图复审通过，Phase3阶段门完成。

### Phase 4（用户主动暂停，待接手）

采用 `2026-09-09-phase4-miniapp-implementation-boundaries.md` 的双端实际契约，协议/授权/JS、原生owner/runner、Q03/Q04/Q05页面修复按文件独占分工。主线负责集成、实际JS桥/设备验证及独立复审。已确认工程直接依赖ZXing core并有QRCodeWriter，无需新增二维码依赖。

- 2026-09-10 用户明确暂停连续实施，改为要求交接文档和下一位AI的prompt。当前不继续Phase4–9。详见 `docs/handoff/2026-09-10/README.md`。Phase4协议/native尚未落码；Q03/Q04/Q05已有UI与helper测试改动但未编译/运行/独立review，不能沿用Phase3通过结论。
- 本次临时网页服务已停止，adb reverse已移除，字体已恢复1.0；本次headless只读模拟器已关闭且未保存快照。没有新增commit/发布，没有要求下一位AI重复确认已授权范围。

> 2026-09-10 独立 review 补正：下列 Phase 4–9 的测试/模拟器记录是上一轮历史记录，不能代表本次修改后的验证结果。Review 发现 OAuth 回调、恢复结果持久化、快捷 Intent、MiniApp 授权/系统能力、TTS、提醒入口和健康分页等具体缺陷；本次修复与验证以 [修复核验记录](../audits/2026-09-10-phase4-9-fix-verification.md) 为准。本次不操作设备，不据 JVM 测试宣称真机或真实账号通过。

### Phase 4（接手后完成）

接手时先验证了交接半成品：两个编译错误（`MiniAppSourceEditor.kt` 参数 `app` 遮蔽 `app.amber.agent.R` 包名；`SynaraWorkspacePage.kt` `loadState` 类型推断过窄为 `Loading` 子类）定点修复后，21 项 UI 半成品定点测试（编辑器状态/Synara加载/Runner加载/连接/版本）全部通过。

W10/W11 协议层（`MiniAppSystemCapabilityHandler.kt` 新文件冻结接口 + registry + URL 校验器；`MiniAppModels.kt` 六组权限/别名；`MiniAppSandbox.kt` systemCapabilitiesEnabled gate 与只读查询；`MiniAppBridge.kt` systemCapabilityHandler 注入、app.info/app.capabilities、dispatchSystem：handler→总开关→声明→setting→grant 确认（确认后重读 durable app 核对 version/htmlHash/声明/setting）、openURL 每次确认、审计只记方法标签；`miniapp_bridge.js` valueParams + 全部 iOS 同名 wrapper；`MiniAppOutputParser`/`MiniAppPromptTransformer` 权限与参数约束；`PreferencesStore.MiniAppSetting.systemCapabilitiesEnabled` 默认开 + 设置页高级组"系统交互"开关）。

W10/W11 native/runner（`MiniAppAndroidDeviceCapabilities.kt` per-runner owner：Vibrator/VibrationEffect haptics、BatteryManager/device、ZXing QRCodeWriter PNG dataURL、Window brightness/keep-awake lease 离页恢复、ACTION_SEND chooser + ActivityResult、ACTION_VIEW openURL；`MiniAppSpeechEngine.kt` TextToSpeech 独立 owner，pause 用 onRangeStart 词边界保存剩余文本、resume 重读；Runner `MiniAppRunnerPage.kt` owner 创建/ON_PAUSE suspend/Dispose owner→bridge→WebView 顺序清理；Manifest 补 VIBRATE）。

设备验证发现并修复三个真实问题：JS wrapper 5000ms 默认超时在授权弹窗等待下误超时（提到 30s）；`screen.getBrightness` 返回 Window override -1 而非有效亮度（无 override 时读系统设置换算 0-1）；URL 校验失败返回泛化 bridge_error（改 `MiniAppBridgeException("invalid_url")` 结构化码）。

JVM 定点 77 项全部通过（MiniApp 74：协议 12 + 真实 bridge Room/confirmation fixture 9 + 既有回归；settings 3）。bridge 测试走真实 `postMessage`→Room→confirmation→handler 生产链路，Robolectric ShadowWebView 捕获响应按请求 id 匹配。

模拟器（emulator-5554/od_mobile_test，只读快照）实机验收：capabilities 发现不弹授权且 methods=registry∩handler；app.info 的 grants/permissions 正确；device.getBattery {level:1,state:charging}、device.getInfo 诚实字段；haptics/screen/speech/share/openURL 授权弹窗→Room ALLOW 持久化→二次调用免弹窗；screen 读设读（0.6）+ KEEP_SCREEN_ON 离页释放（dumpsys 确认）；TTS getVoices 多语言返回、speak/pause/resume/stop 全链路诚实结果；QR 生成 256×256 且 ZXing 独立解码回原文；share 面板打开取消返回 {completed:false}；openURL 双层确认（权限+每次 URL 目标展示）后 ACTION_VIEW 打开 Chrome；`https://127.0.0.1` 拒绝（invalid_url）；runner 关闭后 WebView 从 debuggable 列表消失（资源清理）。重装后 invalid_url 结构化码复验通过。

已知边界：取消授权弹窗（返回键）持久化 DENY 符合语义但弹窗与 JS 超时的残留交互在旧 APK 验证中出现（30s 超时修复后缓解）；Q03 设置页实屏未走完导航路径，以源码+编译+helper 测试为证；模拟器无 haptic 硬件，振动为 API 调用成功语义（hasVibrator 校验存在），真机触感未测；分享"完成"语义为 chooser 交接完成而非对方送达（与 iOS 一致的诚实边界）。

设备证据：`docs/audits/parity-artifacts/phase4-miniapp-final.png`、`phase4-miniapp-settings-advanced.png`（系统交互开关）。CDP 驱动脚本 `scripts/miniapp-parity/cdp_probe.py`（仅限受控模拟器）。模拟器关闭前 font_scale=1.0、无 reverse、无 HTTP 服务。

独立 review 与修复收口：UI reviewer（3 项）：系统交互开关描述两行截断（缩短文案）；Synara 加载指示器缺 statusBarsPadding 会压状态栏（补 inset）；错误卡片内边距 20→24dp。逻辑 reviewer（10 项，结论不通过→全部修复）：runCatching 吞 CancellationException 且 close 后迟到请求静默丢弃（改为显式分支：close 中取消返回 runner_closed、迟到请求回送结构化 runner_closed）；openURL/share URL 校验晚于权限确认导致非法 URL 弹窗+写 grant（URL 预校验移到 requireSystemPermission 之前，share 的 URL 同样前置）；screen lease 无跨 Runner 仲裁（新增进程级 screenLeaseOwner，新 owner 取 lease 先释放旧 owner）；setKeepAwake(false) 在原始 true 时无法恢复（按"当前值==本 owner 最后值"双向恢复）；screen 未校验前台（requireWindow 补 isFinishing/isDestroyed）；speech.speak/resume 缺前台校验（owner 层补 requireForeground，stop/pause 保留为收尾路径）；TTS resume 丢音量（currentVolume 状态+resume 重传 KEY_PARAM_VOLUME）；TTS init 吞取消（TimeoutCancellationException 单独分类，CancellationException 重新抛出并 shutdown 半初始化引擎）；IPv6 authority 闭括号后缀/端口未校验（严格 port 1-65535 校验，IPv6 后仅允许空或 :port）；app.info/app.capabilities 在 durable 删除后回退 runner 旧快照（改为 repository-only，删除后返回 Unknown MiniApp）。

修复后复验：JVM 74+1 项（含 4 个新 review 用例：invalidUrl 预校验不弹窗不写 grant、closed 迟到响应、delete 后 Unknown、IPv6/端口表）全部通过；UI 页面测试（编辑器/Synara/Runner）通过。设备复验（重装 APK 后）：bad-url 返回 invalid_url 且 grant 表无副作用；haptic 授权弹窗→接受→{ok:true} 完整链路（30s 超时修复生效）；screen 授权→setBrightness 0.6→getBrightness 0.6→退出 runner 后 KEEP_SCREEN_ON 释放、WebView 销毁。Phase 4 阶段门完成。Q03 实屏设置页导航未走完（以源码+编译+helper 测试为证）；真机 haptic 硬件、分享对方送达语义、openURL mailto/tel 真实跳转留作后续真机验收边界。

### Phase 5（实施中）

W12/W13 账号线 subagent 串行实现，主线负责 W16 系统入口与统一构建。真实 Grok/Antigravity 账号均未 smoke，只标静态实现+模拟测试。

- W12 Grok 账号登录（subagent 实现，主线修复 3 个编译/测试问题后通过）：`ai/.../providers/grok/GrokOAuth.kt`（OAuthTokenSecureStore 复用、PKCE+loopback:8787、refresh single-flight、invalid_grant 清理、logout generation、endpoint backup side-table、/data 与 /models 两种模型 envelope 解析+fallback）；`OpenAIAuthMode.GROK_OAUTH`（hasUsableAuth 纳入）；ProviderCatalog 显式 Grok 模型刷新路由，聊天复用 OpenAI-compatible stream；设置页 xAI provider 双模式（API Key/Grok 登录/退出恢复 endpoint）；ChatConfigurationIssue `GrokSignIn` gate；ProviderConfigTools Grok status resolver（无 token 明文）；SyncModels grokOAuth 字段加密同步。ai 模块 GrokOAuthTest 3 项+ChatConfigurationIssueTest Grok 3 项通过。已知边界：Grok client_id 为 iOS 工作区脱敏值、真实 CLI proxy SSE 行为未 smoke。
- W13 Antigravity 独立登录模式（subagent 实现，主线修复 4 个编译/测试问题后通过）：`ai/.../providers/google/AntigravityOAuth.kt`（独立 client 常量/headers/redirect、loadCodeAssist/onboardUser+LRO 轮询、refresh single-flight、invalid_grant 清理、logout generation、/v1internal:fetchAvailableModels 模型发现+malformed 过滤+fallback）；`GoogleAuthMode.ANTIGRAVITY_OAUTH`（wire=antigravity_oauth，独立 fixedBaseUrl，旧数据无 authMode 仍解码 API_KEY）；GoogleProvider Antigravity transport（streamGenerateContent?alt=sse/generateContent/fetchAvailableModels），Code Assist/API key/Vertex 零改动；设置页 Google 三段模式+AntigravityOAuthConsole 独立面板；ChatConfigurationIssue `AntigravitySignIn` gate；SyncModels antigravityOAuth 字段。AntigravityOAuthTest 7 项+序列化 2 项+ChatConfigurationIssue 3 项+同步回归通过。真实账号未 smoke。
- W16-A 日历 CRUD（subagent 实现，主线修复 2 个测试问题后通过）：`calendar_update`/`calendar_delete`（精确 event_id、不存在返回 Event not found、update 只写提供字段且结合现有事件校验 end>start、均 needsApproval+calendar_write capability）；SystemAccessTools 注册；PermissionBroker toolNames 扩展。feature/tools/access 6 项测试通过（Robolectric）。Reminders 明确不在 Android 冒充范围。
- W16-B 快捷入口（主线实现）：`DynamicShortcutPublisher`（新建会话/最近会话/快捷消息，typed extras 复用通知深链语义，冷启动+onNewIntent 消费，onCreate 设置初始化后发布）；纯路由契约 `routeFrom` 6 项 JVM 测试通过（malformed id 永不解析、prompt 原样传递不绕过发送 gate）。设备 launcher 验证待做。
- W16-C 独立 TTS 试听页（主线实现）：`SettingTtsPage`（系统 TTS 引擎状态、0.5×–2× 档位、试听/停止、无引擎诚实错误、离页 close；复用 MiniAppSpeechEngine speak/stop，页面独立实例）；设置页语音合成入口；路由 `Screen.SettingTts`。只承诺系统 TTS 试听，不承诺聊天朗读/录音转写/云端 TTS（与 iOS 当前行为一致）。设备验证待做。
- W16-B/W16-C 设备验证（emulator-5554）：TTS 页引擎状态"可用"、试听→按钮变"停止试听"、停止复位、speak 中离页 logcat 证实 TTS 引擎 client disconnection（onDispose shutdown 生效）；截图 `parity-artifacts/phase5-tts-page.png`。快捷入口：dumpsys 证实 dynamic_new_chat 动态 shortcut 发布（与静态 camera 共存）；am start 模拟 new_chat 冷启动落地新会话页；quick_message 的 prompt "生成今日简报" 预填输入框且不自动发送。设置页确认"语音合成"入口存在且无语音输入开关残留（Q03 补证）。
- W17 managed SSH：协议层落地 `feature/terminal/api/SshProfile.kt`（profile 模型：endpoint/auth 方法引用/accepted fingerprint；SecretStore scope=ssh ownerId=profileId 引用形状；`SshTrustPolicy`：无 accepted 指纹永不 Trusted（TOFU 必须用户显式确认）、mismatch 认证前阻断、指纹 64-hex 归一化）。7 项 trust policy 测试通过。**SSH 客户端来源仍是待决项**：需要版本+SHA 固定的 ARM64 OpenSSH 二进制（可走现有 embedded runtime 资产机制）或批准的 JVM SSH 库，属新增发布依赖决策，不悄悄引入；Termux 不作 managed backend。第一版非 PTY、断开不冒充远端停止。

### Phase 5 review 与收口

两线独立 review 完成并全部修复后复验：

系统入口线（9 项，修复后通过）：快捷消息 prompt 与通用 openChatPrompt 共用 extra 导致暖启动双重路由（快捷方式改独立 `amberShortcutPrompt` extra + `taskSessionScreenFromIntent` 跳过带 shortcut kind 的 intent）；stale 会话快捷方式会被 ChatService 复活旧 UUID（落地前 `existsConversationById` 校验，冷启动 runBlocking 单查询/暖启动 launch，不存在则落新会话）；TTS 语速档位错位（iOS 0..1 语义在 MiniAppSpeechEngine 内映射 Android 倍率 rate*2，0.5→1.0 正常）；TTS 按钮连点/启动中不可停（单一 speakJob 串行 + 忙碌禁用 + 停止覆盖启动中）；TTS 页未检测先显示"可用"（初始"未检测"，试听成功才"可用"）；TTS 入口文案硬编码英文（string resource en/zh）；SSH evaluate 空串/非法指纹可判 Trusted（evaluate 内 normalize 双侧，不可归一化即 Untrusted）；SSH 分隔符测试实际无分隔符（真实 AA:AA 格式 + 非法值/空串/SshProfile init 校验测试）；日历测试缺执行路径（补 requireCalendarEventSnapshot 的 Event not found 纯函数测试）。

账号线（6 项，不通过→全部修复）：RequestLoggingInterceptor 在 DEBUG 记录 OAuth body 含 refresh_token/client_secret（/oauth|/token 路径任何构建都不记 body）；Grok 聊天请求缺 CLI proxy 身份头（addOpenAICompatibleAuthHeader 的 GROK_OAUTH 分支补 x-grok-client-* 等 6 个头）；hasUsableAuth 对未登录 OAuth provider 直接 true（扩展 oauthUsable 参数，模型 picker 接 GrokAuthStatus/AntigravityAuthStatus 实际 store 状态）；Google 切 OAuth 模式立即破坏性清空 API key/service account（清理推迟到登录成功 onCommit）；Grok 模式 endpoint 可编辑且退出不恢复 useResponseApi（endpoint 与 Codex 同样固定；退出恢复 xAI preset 的 useResponseApi=true）；Antigravity onboarding 的 LRO 轮询期间 logout 后旧响应可写回 store（ensureOnboarded 全程 generation 校验 persistIfCurrent）。

设备复验（emulator-5554，修复后 APK）：xAI 双模式 UI（API Key|Grok 分段）；Grok 模式 endpoint 固定显示 cli-chat-proxy.grok.com/v1、状态"尚未登录 Grok"专属文案；登录按钮拉起浏览器 OAuth（Chrome）；取消后干净回到 API Key 模式 api.x.ai/v1；Google 三段模式（API Key|Code Assist|Antigravity），切 Antigravity 再切回 API key 保留（AIza... 字段在）。TTS 设置入口本地化（en: Text-to-Speech）。截图：`phase5-grok-login.png`、`phase5-antigravity-mode.png`。

Phase 5 阶段门完成，诚实边界：Grok/Antigravity 真实账号 smoke 未做（client_id 为 iOS 工作区脱敏值，真实 CLI proxy SSE/模型目录/onboarding 未实测）；SSH 客户端二进制来源待决（协议层已就绪）；日历 update/delete 的真实 ContentResolver 路径待真机验收（纯函数+快照测试已覆盖参数构造与错误路径）。

### Phase 6（完成）

- Q08 Responses 混合状态闭合：新增 3 项混合状态测试（真实 RunRecoveryService+Room+ledger）：同 run 服务端 COMPLETED + 非幂等/只读/幂等写 STARTED effect（terminal COMPLETED、非幂等 OUTCOME_UNKNOWN 不重试、可重试类保持 STARTED、outcome-unknown 可查询）；服务端 CANCELLED + 非幂等 STARTED（terminal CANCELLED、effect OUTCOME_UNKNOWN）；无 cursor + STARTED（回退 Phase 1 规则）。11 项 Resume 测试全通过。随后 `OpenAIResponsesResume` 默认开放（CapabilityFlags defaultEnabled=true，CapabilityFlagsTest 默认集合更新，显式 false 保留规则不变）；RunRecovery 全量回归通过。
- Q10 flags 核查更新：当前默认开 DurableToolEffects/TypedRunTerminal/ThreadGraphV2/SyncProviderV2/OpenAIResponsesResume；NovelPackageV2（无生产 consumer）、JSCellRuntime（未重验）、CapabilityPermissions/WorkspaceArtifactsV2/RecipeRuntime（消费者未变，未过功能门）保持关。
- D/Q 全量核查（独立 subagent，以当前源码逐项核对）：D01-D20 中 D02/D03/D05-D19 完成；D01 实现完成待真实账号验收；D04 日历 CRUD 完成（Reminders 单列平台扩展，不冒充）；D20 SSH 协议层完成、客户端来源待决。Q01-Q10 全部闭合；Q11 发布门归 Phase 9。
- 设备旅程（emulator-5554）：升级数据保留（MiniApp 行+用户 grant 重装后完整，app.info 返回升级前 ALLOW）；1.5x 大字体（首页/功能轨/MiniApp runner 授权弹窗完整可点、setBrightness {ok:true}、离页 lease 释放）；深色模式抽查截图；跨模块修复链路（新会话 Select Model 阻塞 → Choose model → Providers 设置页）。截图：phase6-home-large.png、phase6-miniapp-large.png、phase6-dialog-large.png、phase6-home-dark.png、phase6-config-hint.png。
- Phase 6 阶段门完成。边界：D01 真实账号、D20 SSH 客户端、日历真机 ContentResolver、Q11 发布门留待后续阶段/真机。

### Phase 7（完成）

E02 两个实现 subagent 并行 + 主线修复 6 个编译/测试问题 + 设备验证。

- 提醒/闹钟 owner（app/feature/reminder/）：ReminderStore（独立 JSON 文件+fsync+ATOMIC_MOVE 原子持久化、损坏记录保留可见、DAILY/WEEKLY epoch 固定时长）；ReminderScheduler（exact 权限→setExactAndAllowWhileIdle，不可用→setWindow 降级并标注近似；fire key 幂等；rescheduleAll 幂等）；ReminderReceiver（durable CAS 防重触发、通知+下次重排、只做短任务）+ ReminderRescheduleReceiver（BOOT/TIME_SET/TIMEZONE/权限变化→rescheduleAll，Manifest 补 RECEIVE_BOOT_COMPLETED）；AmberAgentApp 启动重排接线。
- Health Connect adapter（app/feature/health/）：API 34+ 平台 HealthConnectManager 分支、API 26-33 诚实 UNSUPPORTED、服务缺失 SERVICE_UNAVAILABLE；Steps/HeartRate/Sleep/Weight 四类只读（分页）；授权拒绝/撤销/读取失败不伪装空数据；API 35+ PlannedExercise 反射探测（不硬编码平台类）；AppOwnedTrainingPlan 独立命名不冒充 Health Connect 计划；Manifest 声明四个 READ_HEALTH 权限；health_summary 工具只读+needsApproval；AgentPermissionBroker health_connect_read capability（adapter-managed，不把打开设置当授权）。
- 测试：E02 JVM 15 项通过（store round-trip/原子/损坏保留/nextFire 跨夏令时/approximate 降级标注/fire key 幂等/rescheduleAll 幂等/receiver 状态机 + 健康聚合多类型/空数据/时区/JSON 形状/availability 状态机/文案）。
- 设备验证（emulator-5554 API 35）：Manifest 声明的 health.* 权限在安装包可见；ReminderReceiver/RescheduleReceiver 注册、BOOT_COMPLETED 声明；写入测试提醒→启动重排（dumpsys alarm 注册 REMINDER_FIRE）；exact 权限拒绝时 window=+15m（approximate 降级真实验证）；授权后 window=0 exactAllowReason=permission（exact 路径）；alarm 自然触发→durable 原子更新（firedCount 1、lastFireKey、nextFire 推进次日）→alarm 重排次日→通知发出（category=alarm、AUTO_CANCEL、深链 contentIntent）。截图 phase7-reminder-fired.png。
- 边界：Health Connect 系统授权 UI 流/真实记录互操作需签名 app + 系统授权 instrumentation（模拟器有 healthconnect controller 服务，JVM 状态机+权限声明已覆盖）；API 26-33 不支持 Health Connect（诚实标注）；运动计划仅做 API 35 探测+App-owned 本地模型，Health Connect planned-exercise 互操作未实现（按可行性报告边界）；exact alarm 厂商 Doze 策略留真机验收。

### Phase 8（协议层完成，transport 外部输入阻断）

E01 可穿戴伴侣按可行性/handoff 事实判定：Wear target、play-services-wearable 依赖、真实手表 BR/EDR RFCOMM 验证、任务协议/授权的产品决策均为外部输入，本轮不新增依赖约束下不能宣称 Wear companion 交付。

已完成（不依赖 transport 的先行部分）：`app/feature/wear/WearHandoffContract.kt`——手机侧交接 envelope 协议（WearTaskEnvelope：requestId 手表先落盘再发送、payload 8k 上限；WearOperation ask/quick_note/cancel_task/fetch_result；WearHandoffRecord 状态机 accepted/pending/complete/failed；WearHandoffPolicy 幂等 intake：重复 requestId 一律返回 durable 记录不二度执行、未来时间戳超 5 分钟时钟偏移拒绝、终态不可重派发）。6 项 JVM 测试通过（幂等/终态/payload 边界/时钟偏移）。transport 落地时（RFCOMM 实机验证通过或批准 Data Layer/HTTP 端点）直接消费此协议。

边界（按 handoff 记录）：无 Wear target/服务；Synara Mac 工作台不是手机端点；CompanionDeviceManager 只做配对不建连接；RFCOMM 平台 API 可用但需目标手表真实验证 BR/EDR/配对/后台存活；数据链路加密不替代产品层授权。

### Phase 9（完成，全产品收口）

- 所选 JVM 回归（不是五模块全部测试）：app/ai/feature:tools:access/feature:terminal:api/core:settings 五模块合计 234 项 0 失败（Phase 4 MiniApp 74、Phase 5 Grok/Antigravity/日历/快捷/同步、Phase 6 Q08 混合状态 11+恢复回归、Phase 7 E02 15、Phase 8 E01 6、既有 miniapp/synara/ChatConfiguration 回归）。
- assembleDebug 通过（最终 APK 含全部 Phase 4-8 改动）。
- 设备端到端最终旅程（emulator-5554，升级重装场景）：MiniApp runner 升级后正常（app.info 返回 0.3-system-capabilities 全量能力）；TTS 页"未检测"诚实初始态；深色模式抽查；动态 shortcut 仍发布（dynamic_new_chat 1）。截图 phase9-tts-final.png、phase9-tts-dark.png。
- E03 对应：Android 通知/任务面板（非 Live Activity 外观复制）——通知链路已由 Phase 7 提醒通知（category=alarm/深链 contentIntent）与既有 Chat 通知验证；任务面板复用既有 AgentTaskStore 投影。

## 最终诚实边界清单（未验证/未实现，不得写成完成）

1. 真实账号 smoke：Grok/Antigravity OAuth 完整登录→刷新→真实请求未做（client_id 为 iOS 工作区脱敏值）；真实 WebDAV/Google 账号备份、真实 SSO 网站接管同属此类。
2. managed SSH：协议层（SshProfile/SshTrustPolicy）完成；客户端二进制来源（版本+SHA 固定 ARM64 OpenSSH 或批准 JVM 库）待决，transport/runtime 未实现。
3. Wear companion（E01）：交接协议层完成；Wear target、transport（RFCOMM 需真实手表 BR/EDR 验证 / Data Layer 需新增依赖 / HTTP 需真实端点）、产品授权方案均为外部输入阻断。
4. Health Connect：系统授权 UI 流与真实记录互操作需签名 app + 系统授权 instrumentation（JVM 状态机+权限声明+API 34+ 分支已覆盖）；运动计划 Health Connect 互操作未实现（API 35 探测+App-owned 本地模型已交付，明确命名不冒充）。
5. 真机边界：haptic 硬件、分享对方送达、openURL mailto/tel 真实跳转、exact alarm 厂商 Doze 策略、日历真机 ContentResolver、Wear 真机配对。
6. Q11 发布门（release 混淆/签名/性能基线/无障碍 TalkBack 完整矩阵/横屏分屏折叠）未执行——本轮以 debug 构建为准；发布前需在最终产物上复测。
7. 交互 PTY：managed SSH 第一版非 PTY（原计划可选项，未实现）。

## 总结

Phase 4–9 上一轮已记录分阶段实现、所选测试与模拟器观察，但独立 review 证明这些证据不足以支撑“全能力链验证通过”：快捷发布不等于可以启动，注入提醒触发不等于已有创建入口，桥接 fake handler 不覆盖原生波形或 TTS 初始化竞态。上述历史结果保留；当前交付是否可收口，以 2026-09-10 修复核验记录中的代码与实际测试结果为准，外部未验证边界仍按上表保留。
