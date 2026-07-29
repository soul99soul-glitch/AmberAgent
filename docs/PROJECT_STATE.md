# AmberAgent Current Project State

Last updated: 2026-07-28

本文件只记录当前可操作事实。开始任务时先结合真实 git 状态核对；状态变化后原地更新，不为普通 session 继续新增 handoff。

## Repository

- Repo: `/Users/arquiel/Downloads/AI/amberagent-ios`
- Branch: `feat/ios-provider-parity-claude`
- Remote tracking: `origin/feat/ios-provider-parity-claude`
- Worktree: 2026-07-16 小说创作连续工作、流式展示/滚动收敛与 vendor TextKit 增量布局已完成整合并纳入当前分支；开始新任务仍以实时 `git status` 为准。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

## Current Product Focus

本仓是 iOS 主线（`amberagent-ios`）。曾在本仓误开工 Android「小说创作」Phase 0，**已全部撤销**（`:feature:novel`、`test-fixtures/novel-v1`、`settings.gradle.kts` include）。Android 复刻应改到 Android 主仓执行；`docs/NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅作计划参考，本仓不落地 Android production。

iOS Phase A-F 与架构精简 S1-S3 仍是领域基线；UX 简化 S1-S7 的三路 review 确认项已完成修复并通过自动化门禁。Quick Start 现按主要角色生成独立建议，导入/Fork/撤销/分支切换等调用链不再靠隐式状态猜成功。S4 持久化压缩等待 V2 项目 schema。iOS 真实 provider、真机交互和系统 Files 交互仍是外部运行证据缺口。

默认可用路径是 `ChatSwiftUIMessageList`。Native Timeline / UICollectionView 仍属于实验或 fallback 路径，不能用其测试结果替代默认路径验证。

## Latest Completed Slices

### 2026-07-29 模型议会 grok 伪搜索文本：显示兜底 + 席位联网查证 + 后台/退页续跑核实

- 现象:自由群聊里 grok-4.5 席位把联网搜索冲动写成纯文本 `web_search / query … / num_results …` 并常被其包进误标的 ```html 围栏,被当发言渲染;deepseek 同场正常。根因(代码事实,非 App 漏执行工具):席位发言请求 `tools: []`、xAI 走通用 OpenAI 适配器且议会席位从不挂 web_search 工具,grok 训练里的搜索调用格式无处落地→**模型幻觉**成纯文本;`seatSystemPrompt` 无任何「你没有联网能力」护栏;全仓无把工具调用拼成该文本的代码,真 tool call 转气泡文本会映射成空串,故显示字 100% 是模型生成。`extractJSONObject` 本就支持围栏/散文,故非解析问题。
- 三层修复(TDD 红→绿):①**显示兜底** `sanitizeSeatOutput`(逐行状态机:围栏内整块缓冲、闭合时看含 `web_search`/`num_results` 决定丢或留,围栏外逐行删裸伪搜索行,正常代码块与 prose 不误伤)接席位流式 `body` 与终态 `output`(顺带清洗写进 transcript/log 的文本,防污染下一轮 prompt);②**prompt 护栏** `seatSystemPrompt` 明确「你没有联网/工具能力,勿输出 web_search/function call/搜索参数,勿用代码围栏包结构化内容,直接 prose 给判断」;③**席位联网查证**(治本,满足 grok 搜索冲动):新增设置开关 `seatWebSearch`(独立 UserDefaults 键、默认关)+ request 字段 + `seatPrompt` 注入「本席联网查证」段 + 席位循环按 gate(`seatWebSearch && researchConsent==.allowed && continuation==nil`)复用主持同一条 `researcher.research` 链路(每席 maxSearches/Scrapes=2 控成本,追问不查),gate 尊重全局联网开关与主持一致。
- **后台/退页续跑核实=已满足,本轮零代码**:经状态/生命周期审计 + 已有测试确认——generation Task 挂在 `CouncilChatViewModel`(`@Observable final class`,与 struct View 同文件)的 `discussionTask`,该 VM 由 `AppShell` 的 `@State` 持有(app 根级常驻),struct View 随页面销毁不影响 VM/Task;`runtimeDidDisappear` 只 checkpoint 不 cancel;退页续跑**已有测试背书**(`testDetachedCouncilRuntimeSkipsMandatoryAskAndFinishesInBackground` 断言 `send→runtimeDidDisappear` 后 run 仍跑完到「主持总结」);退后台由 `runtimeWillEnterBackground`→`BackgroundGenerationKeepAlive` 租约罩整场(含 B 新增每席调研,自动落在租约内),council 路径无 background interrupt。退后台无自动化测试 = `BGTaskScheduler`/`UIApplication` 系统 API 难 mock 的已知缺口(71cc64f77 当时靠真机验证),非疏漏。
- 验证:新增 `sanitizeSeatOutput` 3 项 + 席位联网 3 项红测试先看红(失败信息复现 bug 活体:append 里出现「已组建…工程、风险」、`callCount=1≠3`),实现后转绿;`IOSCouncilRunnerMechanicsTests` 全套 **63 项 0 失败**(iPhone 17 Pro / iOS 26.5 Simulator),含中途弄破又修好的失败兜底测试。真机 grok 开「允许席位联网搜索」后应真查证、关时由 sanitize+护栏兜底,退页/退后台续跑均待真机目测。
- subagent 对抗性 review(逻辑闭环 + 调用链路断裂)结论 **yes-with-caveats、无 CRITICAL/MAJOR**:①我担心的「调研抛错拖垮席位」被证伪——`IOSCouncilResearching.research` 签名 `async -> Bundle` 无 throws,实现把网络错误全吞进 `bundle.failures`,搜索抖动时席位照常发言;②「全伪搜索输出崩整场」被证伪——安全降级为单席失败 + 继续 + 综合照跑(有既有测试实证);③流式逐帧核心伪文本任何帧不泄漏,仅空围栏标记可能闪一瞬(无敏感载荷,终态干净);④调研无整体超时(S4)、取消不提前 break(S6)为 MINOR 残留,与主持调研既有模式对等(逐请求 8/10s 超时,非无限挂起),B 仅把 1 次放大到 N 次。review 抓出唯一真实精度缺陷 S3:sanitize 删行首 `query ` 行规则过宽,会误删英文正常发言(如 `Query the archive…`),且 sanitize 无条件运行、与开关无关→默认路径也中招。修复:状态机加 `sawBareWebSearch` 上下文态,`query`/`num_results` 行删除须依赖前序出现过裸 `web_search` 行,遇非空普通行复位;补 2 条红测试(英文 query 保留 / 上下文复位后 query 保留)先红后绿,全套 **65 项 0 失败**。明确不修(记残余,代价可接受):调研全失败时把 "Research failures" 当材料喂模型(不丢内容不泄漏)、流式空围栏闪烁(无敏感载荷)。

### 2026-07-29 模型议会动态组席静默回退默认席位修复

- 现象:辩论(及自由群聊)开「动态席位生成」问具体议题(如历史题)时,议会仍用默认「工程/产品/风险」,且 UI 打印「已组建 N 位议员:工程、产品、风险」像组席成功,用户被误导。先排除直觉:runner 里**没有辩论/自由群聊的席位分叉**——动态组席只由 `dynamicSeatGeneration` 门控,与 mode 无关(`CouncilRunner.swift` 的 mode 仅用于「辩论开始/自由群聊开始」分隔文案与发言温度);议题也确实传进了 `seatPlanPrompt`。
- 根因(代码事实):动态组席调用被 `(try? await streamWithTimeout(...)) ?? ""` 包住,**超时/抛错被静默吞成空串**;`plannedSeatsFromJSON` 用 `extractJSONObject` 只取**首个**顶层 `{...}`,主持人在真 JSON 前用了带花括号的列举(如 `{历史,政治}`)时会抓错干扰对象而返回 `[]`;而 `if plannedSeats.count >= 2 { activeSeats = plannedSeats }` 在 `<2` 时**保留默认席位且不报任何错**,紧接的「已组建…」divider 把回退伪装成成功。`extractJSONObject` 本就支持代码围栏/前后散文,故真机回退主因是「调用超时被吞 + 干扰对象 + 静默回退」三者,而非围栏解析。
- 三处最小修复(均 TDD 红→绿):①`plannedSeatsFromJSON` 改扫描所有顶层 JSON 对象、取首个含 `seats` 的(新增 `topLevelJSONObjects`,跳过干扰对象);②组席调用失败时**重试一次**(调用成功但解析不出不重试,走显式回退,避免混淆「调用挂了」与「没给 JSON」);③解析/重试后仍无有效席位时发**显式回退 divider**「动态组席未返回有效席位,已沿用默认席位:…」并把 raw 写 task log,且**回退时不再打印误导的「已组建」**。
- 验证:新增 3 项红测试(`testPlannedSeatsFromJSONSkipsLeadingBraceNoiseToFindSeats` / `testDynamicSeatPlanParseFailureSurfacesFallbackDivider` / `testDynamicSeatPlanRetriesOnceAfterFailure`)先确认全红(失败信息直接复现 bug 活体:append 里出现「已组建 2 位议员:工程、风险」),实现后转绿;`IOSCouncilRunnerMechanicsTests` 全套 **57 项 0 失败**(iPhone 17 Pro / iOS 26.5 Simulator),既有动态席位/辩论/自由群聊/跨 provider 路由用例无回归。真机辩论动态组席的实际观感仍待设备目测。

### 2026-07-28 小说批量整章润色

- 正文目录新增「批量润色」入口:选中多章(默认全选)后按目录顺序逐章「生成候选 → 采用(含剧情漂移校验)」,省去逐章手动点击。通过校验的章自动采用为新版本;漂移判定改了剧情的章自动跳过(原文不变);失败章记录在案。选择/运行/报告三态共用一个 sheet(`NovelBatchPolishSheet`),实时显示第 X/Y 章与进度条,可随时停止;已采用章都保留旧版本,可在该章版本历史撤销。文风方向仍来自项目级「整章润色偏好」,sheet 在未设置时给出跳转。
- 链路由两条硬约束决定:一个项目同一时刻只能跑一个 run(lifecycle actor 强制 `projectBusy`),且采用会推进分支 head 使其他已生成候选作废(`NovelPolishTransactions` `staleBranchHeadRevision`),因此只能串行「生成一章 → 采用一章 → 下一章」,做成全自动 sweep 而非「批量生成后逐个审」。驱动仿连续性审计的「VM 存 Task + 串行 for + 每项 `checkCancellation` + 容错计数 + 汇总报告」,但不全程持锁——每章的 start/adopt 各自 `acquireSessionOperation`。
- 关键修复:`isBatchPolishing` 折进 `isBusy` 能挡住外部并发,但也会经 `canStart` 的 `!isBusy` 把批量自己的 start 挡掉(首测全部章 failed=3/adopted=0 暴露);加 `isBatchStartingRun` 重入标志(仅 start 握手期间为真)让 `canStart` 放行批量自身启动,外部启动仍被 `isBusy` 挡住。等待候选以「出现新的可用候选」为成功主信号、「run 落定 + 宽限窗 + 超时」为失败兜底;宽限窗与超时可经 init 注入(仿 `terminalQuietDelay`)加快失败用例。
- 验证:新增 `NovelBatchPolishTests` 5 项(全兼容采用 / 漂移跳过续跑 / 生成失败续跑 / 取消停在下一章 / 门禁与 isBusy)通过;回归 `NovelPolishTests` 6、`NovelPolishIntegrityTests` 15、`NovelContinuityAuditTests` 25、`NovelSessionViewModelTests` 63 全绿(合计 109 项,iPhone 17 Pro / iOS 26.5 Simulator)。CLI 构建需 `-skipMacroValidation`(传递依赖 Equatable 宏插件校验;`xcodegen generate` 重生成 gitignore 的 `.xcodeproj` 纳入新文件)。真机 11 章实际润色手感、停止/撤销与后台行为仍待设备目测。
- subagent 对抗性 review 确认逻辑闭环(C2 顺序、C3 不持锁、章间快照新鲜、6 种事务状态全处理、无重复启动、sheet 状态机全射、VM 释放不泄漏;最担心的「永久卡 running / isBatchStartingRun 毒化」「成功采用误判为 failed」均不成立),并据其发现两处加固:①`awaitPolishCandidate` 落定判定去掉 `!terminalAwaitingRefresh`(该窗口 activeRunID 本就为 nil,真正防误判的是宽限窗),避免终态 refreshDurable 失败时白等到 900s 超时;②删去 adopt 后的 `checkCancellation`,先读真实事务状态再返回,避免把漂移期间被取消但实际已采用的章误报为 cancelled。修复后 89 项(批量 5 + Polish 6 + Integrity 15 + SessionViewModel 63)复跑全绿。残余:zombie run(lifecycle 接受但无终态事件)仍有 900s 有界等待;批量进行中切换项目/分支会逐章失败收口(闭环但不优雅),均未额外处理。

### 2026-07-28 Chat review 复核与状态闭环修复

- 按默认生产路径 `ChatView -> ChatSwiftUIMessageList` 复核外部 review 后，只修复可由测试或真实持久化失败证明的问题：发送前统一校验 provider/model/base URL/API key/附件与 OCR 状态，`sendMessage()` 和 Watch 回传真实成功结果；前台、后台、审批、取消、会话切换和 terminal save 由各自 run/conversation owner 收口，不再让旧快照或后台 job 串入当前会话。标题与后台合并使用 compare-and-set，suggestion/OCR/PhotosPicker 结果绑定启动它们的会话。
- 工具审批在释放 keepalive 前先落 durable pause snapshot，恢复异步工具时重新取得 keepalive；取消会解除 continuation，不再悬挂。provider 输出限制与 unresolved tool failure 变为结构化终态；本地 generation error 由 KMP `localGenerationErrorTextPart` 构造合法 `JsonObject`，修复了 Swift Dictionary metadata 导致的真实 conversation encode 失败，并且不会再上传给 provider。
- UI 只补真实可达性与所有权缺口：当前后台/审批 run 可 Stop，后台 terminal 强制刷新解锁输入；data URL/多图 loading、tool detail identity、Markdown 开关与 URL policy、Reduce Motion、44pt target、Dynamic Type 和 disabled-provider 状态均有生产路径 canary。流式追底最终保留一个 measured-growth revision drain；无 content-type 分支、debt 阈值、offset 补偿或第二滚动状态机。
- 最终验证：410 项功能/状态 suite（含 2 个既有 skip）通过；iPhone 17e 上表格 display-link、7.4KB prose、24KB prose 与结构 canary 同批通过。完整 411 项批次为 408 passed / 2 skipped / 1 fixture failed，唯一失败是共享 test host 中 `testLongProseMeasuredGrowthDoesNotPublishSeveralLinesAtOnce` 未制造任何高度（`totalGrowth=0`），该 fixture 在独立 runner 原样通过；未放宽断言。`git diff --check` 与本轮 32 个 Swift 文件语法解析通过。
- Debug 真机包以命令行临时 Team `89QRFX9548` override 构建，未改工程签名配置；`codesign --verify --deep --strict` 通过，bundle `app.amber.ios` 1.0 (1)，主二进制 SHA-256 `25f0db830c921154a4340d6eb75f98fe90551dfaff600160f14f7a9a3db9f369`。02:40 已覆盖安装到 iPhone Air / iOS 27.0，设备端应用记录确认落盘；自动启动仅被锁屏拒绝，尚未把真机发送、真实 provider、WatchConnectivity 或 120Hz 手感记为通过。
- 后续三路 subagent 对调用链与终态所有权再审后，精确收口外部 review 的 8 项：后台 handoff 不再重复执行工具；截断重生成不再把本地 output-limit notice 当候选且保存失败不报成功；审批/`ask_user` 可从 durable `awaiting_permission` 冷启动恢复；后台取消补齐 unresolved tool；Files/iCloud 与 Photos 结果绑定 picker 启动会话；标题改为存储层原子 CAS；标题/建议/OCR 合并 Assistant headers/body；OCR 可直接 Stop 且迟到结果不能串会话。移除了审查过程中暴露的未消费 revision ledger、伪 OCR test hook、重复 failure JSON wrapper，没有新增第二状态机或通用重试层。
- 最终增量门禁：受影响 9 类 Swift suite 234/234；残余修正后的 4 类 suite 138/138；后台/恢复末轮 66/66；conversation-storage JVM 测试、KMP iOS 编译与无签名 generic iOS arm64 整包构建通过。默认 Chat 功能 replay 通过；`testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` 当前独立复跑仍为 p95 `43.7803ms > 40ms`（另一次 `43.4206ms`），属于未触碰的流式表格性能缺口，未放宽阈值。本次八项最终包尚未装机：主 App provisioning profile 已于 2026-07-19 过期，Xcode 当前无 Team `89QRFX9548` 登录账号，iPhone Air 也显示 `unavailable`；此前 02:40 已安装的是本轮最终残余修正前的包。

### 2026-07-27 Chat 发送 SIGABRT：Continued Processing 注册修复

- 真机 iPhone Air / iOS 27.0 beta 在 22:39:54、22:40:07 连续产生同栈崩溃：`ComposerDockSendButton → ChatGenerationCoordinator.start → BackgroundGenerationKeepAlive.begin → BGTaskScheduler._handleSubmissionWithoutRegistrationForTaskRequest → SIGABRT`。真机控制台与带有效 `BGTaskSchedulerPermittedIdentifiers` 的 iOS 26.5 Simulator 均确认启动期注册 `app.amber.ios.keepalive.*` 返回 false；根因是把 Info.plist 的通配许可模式误当成运行时 handler identifier，随后仍提交具体 run identifier，Objective-C assertion 无法由 Swift `do/catch` 捕获。
- 最小修复只落在执行权所有者：删除 `AppShell` 的启动期通配注册；每轮在 submit 前注册同一个具体 identifier，注册失败时不 submit、仍保留既有 UIKit 约 30 秒短保活；进程内记录已成功注册的具体 identifier，供议会固定 `council` lease 与 Chat 审批恢复复用，避免第二次注册被系统终止。未改 provider、消息、输入框、持久化或新增重试/状态机。
- 红测试先同时命中「注册了通配符」与「注册失败仍 submit」两处断言，再转绿。`BackgroundGenerationKeepAliveTests` 16 条 + `IOSChatBackgroundSuspensionTests` 9 条在 iPhone 17 Pro / iOS 26.5 Simulator **25 passed / 0 failed**（`Test-iosApp-2026.07.27_23-14-28-+0800.xcresult`）；最新测试构建启动日志不再出现通配注册拒绝，`git diff --check` 通过。真机 Debug 包以 Team `89QRFX9548` 全新 DerivedData 构建，`codesign --verify --deep --strict` 通过，包内保留 `processing` 与 `app.amber.ios.keepalive.*`，23:26 覆盖安装到 iPhone Air 并成功启动；主进程 PID 19778 与 Activity Widget 持续存活，安装后未新增 `iosApp` 崩溃报告。模拟器真实发送因无 API Key 且 CuaDriver 在 provider 导航中丢失 Simulator 窗口未完成；设备端是否实际点击发送无法从外部独立确认，Continued Processing 的系统接管仍待一次可观察的真机生成验证。

### 2026-07-27 模型议会动态席位跨 provider 路由与双模式默认轮数

- 根因确认：Room 请求此前只携带当前 `providerSetting`，动态候选池也只读取该 provider 的模型，最终所有席位即使模型 ID 不同仍会通过当前 provider 发出；自由群聊分支则把新议会轮数硬编码为 1，绕过了已正确持久化的 `defaultRounds`。
- 最小修复沿用现有 provider 配置链：UI 请求传入当前 Settings 的 providers；每个运行席位新增可持久化的 `providerId + modelId` 路由，动态池只纳入已启用、配置有效且支持 Chat streaming 的精确路由，并优先从不同于主持人的 provider 各取一个模型。联通探测和正式发言都按席位路由选择 provider，不再把别家模型兜回当前 provider；历史归档与追问续轮保留同一路由，旧归档缺 providerId 时仍按原有当前模型路径加载。
- 新议会的自由群聊和辩论现在都执行 `limits.defaultRounds`；追问保持每次只追加一轮。两个设置入口的步进范围与 normalized 契约统一为 1...5，说明文案同步更新。
- 最终回归：`IOSCouncilRunnerMechanicsTests` + `IOSCouncilRoomArchiveStoreTests` **71 passed / 0 failed / 0 skipped**（iPhone 17 Pro / iOS 26.5 Simulator，`Test-iosApp-2026.07.27_14-29-11-+0800.xcresult`）；覆盖跨 provider 探测/发言顺序、两种模式两轮执行、续轮、不可达模型替换、归档 route 往返。`git diff --check` 通过。
- 真机 Debug 包使用 Team `89QRFX9548` 构建并通过 `codesign --verify --deep --strict`；14:27 覆盖安装到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`（安装路径 `D8F0251C-3D85-4B16-A611-66541DD5D600/iosApp.app`），14:28 启动成功。因本机仍无 Watch App profile，安装包未嵌入 Watch companion；完整 Watch target 已在生成工程中恢复。真实服务商混合调用的额度/网络结果仍需在设备上发起一场动态议会目测确认。

### 2026-07-27 top-bar Liquid Glass glyph contrast and accent restoration

- 真机截图确认首页设置键与议会返回/历史/设置键一起变成近白，不是单个 SF Symbol 或禁用态；回归由 `e091530d6` 把 `AmberGlassCircleButton` 从「图标标签承载 glass」改成同一 ZStack 内「独立 glass Circle + 图标」引入。iOS 26 的 glass 独立合成层会在真机上洗淡同级 glyph。
- 共享圆形按钮现在把 `.glassEffect(.regular.interactive(), in: Circle())` 重新附着到含图标的标签本体，并新增局部 `tint` 输入；默认仍为中性 `foreground2`。首页右上设置、议会右上历史/设置显式使用当前强调色，议会左上返回使用高对比 `foreground`；尺寸、布局、点击行为和其他调用点默认配色不变。
- `IOSCouncilRunnerMechanicsTests.testTopBarCircleButtonsKeepGlyphsAboveNativeGlassAndTintTrailingActions` 在旧实现上 7 处断言实测全红，修复后定点绿测通过；`git diff --check` 通过。设备 Debug 包使用现有 Team `89QRFX9548` 的主 App/Activity Widget profiles 完成通用 iOS 构建并通过 `codesign --verify --deep --strict`。本机缺少新 Watch App profile 且 Xcode 账户凭据不可用，因此本次待装包未嵌入 Watch companion；本地生成工程已恢复原始 `project.yml` 的完整 Watch target。
- 13:32 设备恢复 connected 后，已把验签包覆盖安装到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；CoreDevice 返回新安装路径 `08DDAB0D-F01B-4F3B-9AC6-0992D2A98699/iosApp.app`，13:33 以 bundle id `app.amber.ios` 启动成功。以上证明本次包已安装并运行；首页与议会顶栏的最终颜色观感仍需设备目测。

### 2026-07-27 模型议会圆形玻璃、动态模型预检与同题追问

- 议会顶栏圆形按钮改用 `Circle().glassEffect(..., in: Circle())` 的原生圆形玻璃轮廓；顶栏 `GlassEffectContainer` 间距收口为 0，避免相隔 10pt 的历史/设置按钮被容器吸附融合成非圆轮廓。静态契约已锁定原生 Circle shape 与容器零融合间距。
- 动态席位不再全部复制主持模型：从当前 provider 已配置的 chat 模型中去重，优先分配非主持模型，模型池耗尽后才轮转复用；带 `providerOverwrite` 的模型不进入这个单-provider 候选池，避免拿主持人的 endpoint/auth 误路由。正式发言前，对实际分配到的每个非主持模型做一次最小联通探测（temperature 0、reasoning off、最多 16 tokens、15 秒静默超时）；主持模型已通过议题整理/组席调用视为可用。探测失败的模型不会进入席位发言，而会改派到主持模型或已通过探测的候选模型，并在议会分隔提示与 task log 中记录替换。
- 正常完成后的输入框改为「输入补充或追问，再讨论一轮」：追问沿用同一 task/archive、原始议题、主持人完善后的 `finalTopic`、既有席位和历史转录，只追加一轮席位发言与主持更新总结。追加轮失败、取消或进程中断只记录本轮终态，保留此前已完成议会并允许继续追问；首次议会的失败/取消仍不误续接。task 被 80 条保留上限淘汰时以原 taskId 恢复，不再静默换身份。
- review 后四项定点修复均走红→绿，未加入跨-provider 调度、泛化重试或第二状态机。`IOSCouncilRunnerMechanicsTests` + `IOSCouncilRoomArchiveStoreTests` 在 iPhone 17 Pro Max / iOS 26.5 Simulator **70 passed / 0 failed**；覆盖 override 排除、坏模型替换、finalTopic 往返、失败续轮后的第三轮、进程中断与 task 身份恢复；`git diff --check` 通过。真实 provider 的模型可用性、探测延迟，以及真机两颗按钮的最终 Liquid Glass 视觉仍待外部复验。

### 2026-07-27 系统灵动岛收紧宽度、接入 Chat 实时状态

- 真机桌面截图确认上一版修改重心错位：用户要改的是系统 Live Activity，而非只是 Chat 页内胶囊。compact 右侧在没有真实 metric 时退化为 `.timer`，动态计时文本让系统预留了过长宽度，且只显示「星核 + 时间」，没有任务状态。
- compact 改为「20pt 星核 + 有界短状态」，用「连接中 / 思考中 / 回复中 / 检索中 / 待确认」等取代计时器；minimal 仍只保留星核。expanded/锁屏改为「状态主标题 + 工具任务次级类型 + 真实 metric/最后更新时间 + 打开对话」，普通回复不重复显示任务类型；移除内层 44pt 按钮占位，整个 Live Activity 仍由现有 `widgetURL` 打开对话。
- 状态链共享 `AgentActivityStage` 文案：普通 Chat 首轮按「连接模型 → 思考中 → 生成回复」更新；同一 delta 仍含 reasoning 时保持思考态，工具完成/审批恢复后继续生成态，不再与 Chat 分叉。工具开始、等待确认、取消/完成仍更新或清理同一个 Live Activity 阶段。继续遵守隐私门禁，不在锁屏编码模型名、prompt 或回复内容。
- review 后精准收紧：删除 expanded 计时器的 `fixedSize`、无法证明的「Amber 会继续在后台处理」、phase/stage 非 stale 终态的静默纠错；source canary 不再锁死私有 View 名和 56pt 数值，仅保留「compact 无 timer / 状态文案共享 / initialStage 只出现一次」行为契约；未新增协议、状态框架或 fallback。
- 验证：定点 `AgentActivityPresentationTests` + 系统岛 wiring + 辉光默认关闭 + `ChatIslandPresentationTests` 合跑 **41 passed / 0 failed**（iPhone 17 Pro / iOS 26.5 Simulator，`Test-iosApp-2026.07.27_01-53-01-+0800.xcresult`）；强制 `ChatStreamReplayTests` **16 passed / 1 skipped / 0 failed**（`Test-iosApp-2026.07.27_01-56-45-+0800.xcresult`）；两个 ActivityWidget target 编译通过；`iosApp` 整体 scheme 在 arm64 Simulator 编译通过。系统实际 compact 宽度、长按 expanded 布局与后台更新频率仍需安装新包后真机复验，本轮未安装。

### 2026-07-27 活动岛彩色边缘辉光改为可选且默认关闭

- 真机截图确认彩色边光视觉权重过高；把它从活动岛必选壳层收敛为
  「显示与字体 → 活动状态 → 彩色边缘辉光」可选项，默认关闭。
- 偏好由单一 `IOSDisplayPreferenceKeys.activityIslandEdgeGlow` 持久化；设置页写入，
  `ChatActivityIslandView` 用 `@AppStorage` 实时消费。关闭时 `IslandEdgeGlowView` 不进入视图树，
  其 `CADisplayLink` 随移除失效；orb、状态文案、标题 glint、presentation reducer 与 Live Activity 均不变。
- 先以 `IOSSettingsWiringTests.testActivityIslandEdgeGlowIsOptionalAndDefaultsOff` 复现 6 处接线缺失，
  再落最小实现；`IOSSettingsWiringTests` + `ChatIslandPresentationTests` 合跑 **55 passed / 0 failed**
  （iPhone 17 Pro / iOS 26.5 Simulator），`git diff --check` 通过。真机设置页位置、即时开关和关闭后的最终观感
  仍待安装后目视复验。

### 2026-07-26 灵动岛重设计实施：核壳文三层落地（S1-S3 全部切片）

- 按 `docs/ACTIVITY_ISLAND_REDESIGN_PLAN.md` 一口气完成 S1（壳+核）/S2（文）/S3（岛），随后双路 subagent review（逻辑闭环 + 调用链路）并精准修复确认项。
- **核**：orb 六态全上岗（搜索工具→`.searching`、复杂工具→`.solving`、图片→`.shaping`），新增 `.awaitingUser` 态（八项 pending 聚合 `hasPendingUserGate`，orb 冻结 + amber 静态边光，「等待确认 / 回答问题·工具审批」）；SF Symbol 与 tint 底圈从岛上退役。
- **壳**：新组件 `IslandEdgeGlowView`（UIKit CADisplayLink 24–30fps，与 orb 同 `CACurrentMediaTime` 时钟）；光谱三态（等待 8s/思考 14s/生成 20s+呼吸）、工具单 hue 常亮、失败静态红；衰减描边阶梯代替逐帧模糊；退役 3D 翻转/halo/SoftField，idle↔active 改几何连续 morph。
- **文**：AI 三态标题生成微光（phaseAnimator 2.4s，reduceMotion/终态摘除）；文案表落地（等待连接副标题=模型 displayName，识别图片=「共 m 张」（m>1 时），词边界截断（≥60% 最后边界，尾部标点清理，无边界硬切））；工具失败 terminalHold 红边光 2s +「未完成」（`.tool` 与 `.image` 均匹配）。
- **岛（Live Activity）**：compact/minimal glyph running 相位改 orb 静帧三帧 1.5s 轮换（引擎入两个 widget target，离线渲染缓存，系统限频退首帧）；`stage.failed`/`fact.failed` 中英对齐「未完成/Incomplete」。
- **review 修复（精准，不扩）**：①调用链 P1——`ChatListSummarySnapshot ==` 纳入 `activeToolStep?.id`（id 稳定不含流式载荷），修复「视觉相同工具替换」时 terminalHold 断链；②闭环 P2——`.image` 类工具失败纳入 terminalHold（vision 识别路径 toolID=nil 不受影响）；③测试用例名不副实修正（补真正命中词边界的用例）；④两份文档与实现漂移处就地更新。
- 验证：新定点 `ChatIslandPresentationTests` 26 条 + `ThinkingOrbEngineTests` 8 + `IOSSettingsWiringTests` 28（含两条既有 island canary）+ 强制 `ChatStreamReplayTests` 17 = **79 passed / 1 skipped / 0 failed**（iPhone 17 Pro / iOS 26.5 Simulator，`Test-iosApp-2026.07.26_22-11-08-+0800.xcresult`，隔离 derivedData 避开并发构建）；`iosAppExperimentalGPL` scheme 模拟器构建通过；`git diff --check` 干净；新符号无旧实现可红，按「断言先行」一次转绿（同 07-26 事件循环先例）。
- 装机：22:23 真机 Debug 包（Team `89QRFX9548`，含全部工作区未提交改动）**BUILD SUCCEEDED** + `codesign --verify --deep --strict` 通过，已覆盖安装到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动因设备锁定被拒（同 07-18 环境限制），需解锁后手动启动目测六态观感。
- **既有基线非本轮回归**：`ChatSwiftUIStreamReplayTests` 两条贴底跟随用例（07-23/07-25 已记录的负载敏感项）在本机仍失败（debt 97–183pt 逐次不定），其测试路径直驱 `ChatSwiftUIMessageList`，不经过本轮任何改动文件；未放宽断言、未随本轮处理。
- 残余缺口：辉光/glint/morph/静帧轮换只有代码与编译证据，模拟器与真机视觉未验证（含 Live Activity 更新频率与电量）；`.large` 主角时刻（计划可选项）未实施；pi-lens 对 ChatView/ChatViewModel/project.yml 的长度告警为既有基线，未随本轮扩大。

### 2026-07-26 灵动岛重设计方案与实施计划（未动代码）

- 产出 `docs/ACTIVITY_ISLAND_REDESIGN.md`（设计权威）与 `docs/ACTIVITY_ISLAND_REDESIGN_PLAN.md`（实施计划），零代码改动、零测试运行。
- 设计：参照 iOS 27 Siri「药丸吃岛」与 Apple Intelligence 辉光/glint 语法（多源搜索合成，原文抓取被本机代理拦截），收敛为核（ThinkingOrb 六态全上岗）/壳（三层锥形辉光，转速即状态）/文（标题生成微光）三层各一个运动系统；退役 3D 翻转、halo、SoftField、岛上 SF Symbol；色彩分层纪律（orb 永为灰阶墨，颜色只属于边缘辉光）；克制文案表（去机制词，副标题只留可验证事实或 nil）。
- 计划：S1 壳+核（新组件 `IslandEdgeGlowView` 走 UIKit CADisplayLink、orb 六态映射、`ChatIslandPresentation` settle reducer、morph 过渡）→ S2 文（glint + 文案表 + 词边界截断）→ S3 岛（Live Activity 对齐、静帧轮换、可选 awaitingUser）。门禁：三条既有 canary（岛宽度、glass 源断言、Orb 引擎 8 条）+ 强制 `ChatStreamReplayTests`；新增文件需重跑 `xcodegen`。
- 下一步：待确认后从 S1 T1.1 红测试切入。

### 2026-07-26 embedded iSH 运行内核修复：流式输出、真取消、超时强杀

- 针对 `ISH-INTEGRATION-OPENMINIS-ANALYSIS.md` 指认的三条运行内核缺陷完成红→绿修复（用户未选定 P0-P3 全案，本轮只做最小可验证切片；供应链自主化/PTY 终端 UI/自建 rootfs/淘汰 ish_handoff 均未动）。
- `IOSEmbeddedIshRuntime.run` 从 `runOneshot` 改为 `spawn()` + detached 阻塞读事件循环（0.2s 轮询，`ISH_ERR_TIMEOUT=-12` 为轮询节拍，与包自身 oneshot 循环同模式）；新增 `onOutput` 流式回调与 `IOSEmbeddedIshOutputChunk`；Swift Task 取消经 `withTaskCancellationHandler` → `session.terminate()`（SIGTERM→SIGKILL）落到 guest 进程；guest 侧 deadline 独立兜底，`IOSEmbeddedIshCommandResult` 形状不变，`ios_ish_execute` 工具执行器零改动。
- `IOSTerminalRuntime` 新增 `IOSEmbeddedIshJobBackend` 注入缝（生产见证 `IOSEmbeddedIshRuntime.shared`）与 `experimentalRuntimesLinked` 实例属性（默认仍取 `IOSTerminalBuildPolicy`，生产门禁语义不变）；ish job 流式输出增量进入 `outputTail`；删除 `stopJob`/`waitJob` 的 ish 特判（旧行为只写"cannot be interrupted"文案放任跑完），统一为 cancel task → cancelled/timedOut，错误文案按 runtime 区分；ishExperimental 能力表 summary 更新为"流式输出+超时+立即取消，仍无 PTY/stdin"。
- 顺带把 `startEmbeddedIshJob`/`embeddedIshOutput` 移入同文件 extension 分区（pi-lens type_body_length 越限修复，纯搬移）；`IOSEmbeddedIshRuntime` 拆出 `runSpawned`/`emit` 使函数体 ≤50 行。
- **双路对抗性 review 后的二次修复**：
  - P1-1（内存安全）：`onCancel→session.terminate()` 与读循环 `defer session.close()` 跨线程交错存在 C 层 UAF（`IshSession.currentRaw()` 锁内取裸指针、锁外解引用，close 锁外 free）。修复：新增 `IshSessionLifecycleBox` 用每-session NSLock 把 terminate/close 整体串行化（terminate 要么先于 close 落地、要么 close 后 no-op；C 侧 terminate 为非阻塞发帧，持锁安全），所有终止路径统一走 box。未改 vendor 包；后续 PTY 切片若引入 signal/write/resize 需复用同一 box 或推动上游修复。
  - P1-2（测试缺口）：事件循环依赖抽成稳定层 `IOSEmbeddedIshSessionHooks`（read/terminate/close/isReadTimeout/describeFailure）+ `IOSEmbeddedIshSessionEvent`，`readSessionEvents`/`emit` 移出 GPL 宏改 internal；新增 `IOSEmbeddedIshEventLoopTests` 5 条覆盖正常退出/deadline 强杀/致命 read 错误/信号退出（143）/空 data 事件，并断言 close 恰好一次、terminate 调用次数。
  - 调用链路 review 确认零断裂：`IOSEmbeddedIshRuntime.shared` 全部 2 个调用点、`IOSTerminalRuntime` 构造/.shared 全部 15 个使用点、静态门禁 9 个消费点逐一验证；KMP `ios_ish_execute` 声明与 ≤180s 上限自洽；Watch/审批链不经 job 状态机零影响。
- 验证：新 job 测试 3 条先在保留旧语义的注入缝上实测全红（7 处断言失败）再转绿；事件循环 5 条为新缝纯覆盖（无行为红可打）；受影响套件（SSH runtime/profile、LocalToolExecutor、CapabilityRegistry、PermissionsStatus、GenerationParams、ish job/loop 两组新套件）合跑 **88 passed / 0 failed**（iPhone 17 Pro / iOS 26.5 Simulator，`Test-iosApp-2026.07.26_16-08-48-+0800.xcresult`）；`iosAppExperimentalGPL` scheme 模拟器 **BUILD SUCCEEDED**；`git diff --check` 与 pi-lens 诊断干净。
- 残余缺口：guest 进程真实 terminate/streaming 仍只有编译证据+缝上契约，ExperimentalGPL 真机或模拟器跑 `ios_ish_execute`（echo + sleep 60 取消 + 超时）未验证；UAF 修复为构造性正确（锁串行化），未做对抗性线程压测；`String(decoding:)` 逐块有损解码在多字节 UTF-8 跨块边界可能出现 U+FFFD（cosmetic）；`ensureDefaultVM` 每次 run 都走一次 guest `ls`（P2 性能项，未动）；P0 供应链自主化（Lolendor 预编译包仍在）与 PTY 交互终端留待后续切片。

### 2026-07-26 embedded iSH P2 定点修复轮（review 后续）

- 对双路 review 的 P2 项逐项精准闭环，未扩大范围：
  - **boot 路径**（P2-1）：`bootIfNeeded` 改 async + `bootTask` 在飞去重（并发首跑共享一次 rootfs 拷贝+内核引导，rootfs 准备改 static 不依赖 actor 状态，拷贝/引导移出协作线程）；boot 后 `Task.checkCancellation()` 早收口；`CancellationError` 显式映射为 "Embedded iSH command was cancelled."；`resolvedDefaultVM()` 缓存 VM（每次 run 省一次 guest `ls`，缓存生命周期与一次性 boot 一致）。
  - **UTF-8 跨块**（P2-3b）：新增 `IOSEmbeddedIshUTF8StreamDecoder`（保留至多 3 字节不完整序列，腐败字节即时有损放行防 pending 无界），事件泵双流各持一个 decoder，`finish()` 流末冲刷；`readSessionEvents`/`emit`/`flushDecoders` 下沉为同文件 extension（actor 主体回到 350 行门禁内）。
  - **stderr 预览标记**（P2-3a）：`IOSEmbeddedIshStreamMarks`（MainActor 串行化）在流式预览 stdout→stderr 转移处补一次性 `[stderr]` 标记，终态输出布局不变。
  - **waitJob 调用方取消**（P2-4）：轮询循环检查 `Task.isCancelled` 提前返回当前快照，不改 job 状态（等待被取消≠job 被取消），SSH 同构受益。
  - **工具取消诚实化**（reviewer2-note1）：executor 在 `Task.isCancelled && result.error == nil` 时报 `status: "cancelled"` + 取消文案；timeline 失败集合未加 `"cancelled"`——`ok: false` 已主导失败渲染，该行为冗余防御且触发 ChatToolTimelineView 既有 545 行 struct 越限告警，按最小改动回退。
  - **文档**：`run` 注释补充 <1s 钳制与 128+signal 退出码约定；`-12` 常量注释改引 `include/ishembed.h` + host/ishembed.c（P1 轮已落）。
  - KMP `ios_ish_execute` 描述未改：≤180s 上限与"不做交互式长会话"仍准确，reviewer 亦仅列可选。
- 验证：**解码器算法独立运行时证据**——提取至 /tmp 以 swiftc 运行 22 项断言 ALL PASS（含逐字节 drip CJK、4 字节 emoji 跨三块、腐败输入 pending 无界、截断序列 flush 单 U+FFFD  maximal-subpart）；**GPL-only 路径独立 typecheck**——`swiftc -typecheck -D ENABLE_EXPERIMENTAL_TERMINAL_RUNTIMES`（真实 IshEmbed.swiftmodule + CIshEmbed 模块图，Swift 6 mode）exit 0；稳定层文件在模块编译中全部通过（错误仅出现在 Novel 文件）；pi-lens 9 文件无告警、`git diff --check` 通过。
- **整包 XCTest 与 ExperimentalGPL 构建被并发工作阻塞**：另一活跃会话自 16:23 起批量修改 NovelCreation（至少 7 个文件），当前存在真实编译错——`NovelSessionBubble.swift:232`（多语句 getter 隐式返回不成立）与 `NovelSessionView.swift:1291-1299`（`activeRun` fileprivate、`.regenerate` 成员缺失，设计中途状态）。曾对其 getter 打一词 `return` 解锁验证，旋即暴露 4 个设计期错误，判断越界后**已回退该临时修复**，对方工作区恢复原样。其 16:09 前的脏树状态曾被 88 测试全绿证明自洽。待 Novel 树编译恢复后需补跑：本轮新增 14 条 XCTest（事件循环 7 + 解码器 6 + job stderr 标记 1 + waitJob 取消 1）与受影响套件、`iosAppExperimentalGPL` 构建。

### 2026-07-25 流式展示节奏与 Responses 终态三项定点修复

- 三项均按红→绿验证,每项先在未修复状态复现失败再翻绿:
  - **Chat 流式分帧自适应**(`ChatStreamPresentationPacer`)。原为固定 12 字符/拍 × 48ms = 恒定 250 字符/秒,模型更快时积压持续累积,且 `drainStreamPresentation` 在流式终态后仍按同一节奏逐拍追平——4000 字回复实测需 334 拍(≈16s)才显示完,期间 `isLoading` 保持 true。改为与 `NovelSessionPresentationPacer` 同构的积压自适应(下限 12 / 上限 64 / 目标 16 拍清空当前积压),同一 fixture 降到 88 拍(≈4.2s),首拍 12→64。原 burst 测试的断言常量由 `maximumTextAdvance` 改到 `minimumTextAdvance`(常量语义从"固定预算"变为"下限"),改后仍绿。
  - **OpenAI Responses 上限截断不再报错**(`OpenAIKmpProvider` / `SseEvent`)。`response.incomplete` + `incomplete_details.reason == "max_output_tokens"` 是协议正常终态,原实现无条件抛 `IllegalStateException`,会在一段成功的长回复后追加红色错误气泡并把 run 记成 failed。现改为放行并转成 `finishReason="length"`(与 Claude `stop_reason="max_tokens"` 对称,交给既有的 `reachedOutputLimit` / `outputLimitFailure`);其余 incomplete 原因(内容过滤等)继续抛。同时把 `response.incomplete` 纳入 `OpenAIStreamTerminalState` 的终态集合,否则正常截断会在连接关闭时再被误报成"流在终态前断开"。
  - **思考面板可见性判断零分配**(`ChatReasoningCard.hasVisibleText`)。`hasBodyText` 经 `showsBody` 在一次 body 求值中被求值约 10 次,原实现 `trimmingCharacters(in:).isEmpty` 的成本取决于首字符:非空白时 Foundation 走零拷贝快路径,而正文以空白/换行开头(模型 thinking 常见)时会真的分配全文副本。1M 字符 × 50 次实测 26.69ms → 0.30ms。
  - **凭据删除的 generic 条目残留**(`IOSSharedSettingsStore.genericCredentialRefs`)。`IOSCredentialRedactor.arrayItemPath` 把数组项标成 `[<identity>#<index>]`(索引后缀防止同名项塌到同一路径),而按稳定 ID 反查的一侧写的是 `"[\(stableId)]"`,对真实路径恒为 false ——`genericCredentialRefs` 永远返回空,provider/model/TTS 删除时 typed ref 被清而 generic side-table 条目**全部残留**。运行时取证:`root.providers[bf5082ca-…-acccaa93e9e6#9].apiKey`。改为匹配到 `#` 为止,并新增 `test_credentialPathsIdentifyArrayItemsWithIndexSuffix` 锁定两处的路径格式约定(同时断言不存在无后缀的 `[<id>]` 形态)。这修复了本轮随改动一起加入、但从未通过过的 4 条 `test_remove*CredentialRefs`。
- 验证:iPhone 17 Pro 模拟器上 `ChatStreamReplayTests` + `ChatMessageProjectionTests` + 新增 `ChatReasoningCardTests` 合跑 **106 passed / 0 failed**;`IOSParityRedLightTests` 全套 **52 passed / 1 failed**(唯一失败是下方记录的既有 canary);`:ai-provider-openai:jvmTest` 与 `:ai-provider-claude:jvmTest` 全套 `BUILD SUCCESSFUL`。真实 provider 的长回复观感、真机 120Hz 追平手感、真机 Keychain 删除仍未验证。
- 本轮只改这四处,未动 vendor `ParagraphUIView`、Council/Novel 的 `.sizeChanges` 底锚,以及其余 review 项。

### 2026-07-24 evidence-backed bug review remediation

- 二次复核后的修复继续沿既有 owner 收口，没有新增轮询或第二套状态机：SubAgent 的 Codex 流式请求先走现有 OAuth/headers resolver，Grok Web 走现有原生会话；MiniApp AI 复用当前 provider/model。会话备份恢复不再直接覆盖 `conversations/`，而是把完整 JSON 批量交给 `JsonConversationStorage` 在同一 mutex 内先校验、覆盖同 ID 并重建 index；`index.json` 不再被当成会话文档导入。
- 后台/Watch 的真实终态缺口已闭环：保存已被 completion 占有时 cancel 不再反抢；action 失败/超时按 requestId 解除 sending；nil conversation 不再假报 open-phone 成功；后台完成携带 assistant 摘要，无 retry owner 的失败 payload 会删除，冷恢复仅从原 provider 当前模型重建已剥离 secret 的 params，并投影 reconnecting。Provider/model/TTS/MCP 删除只在明确删除入口按稳定 ID 清理对应 Keychain ref；普通 settings 更新不做全树差集删除。
- 安全边界现在拒绝 alternate IP、DNS 解析为私网及重定向转私网，`scrape_web`/MiniApp fetch 在流式接收阶段分别硬限 512/256 KiB，MiniApp 请求体限 64 KiB。该 URLSession 方案是请求前解析与逐跳复检，不是连接级 DNS pinning，仍有解析到连接之间的标准 rebinding 窗口；要彻底关闭需替换为可绑定已验证地址且保留 TLS hostname 的 transport，本轮没有为此引入自写 HTTP 栈。
- 当前验证：`:core:conversation-storage:jvmTest :shared:jvmTest` `BUILD SUCCESSFUL`；Search+MiniApp 37 条、凭据删除 5 条、Backup+Settings+MCP 36 条、Watch/background 29 条、`IOSAgentToolEngineTests` 全套及公开 conversation import 定点均通过；AmberWatchApp target 构建通过，`git diff --check` 通过。真实 provider、真机 WatchConnectivity、后台系统调度和 DNS rebinding 对抗环境仍未验证。
- 对复核后确认的真实问题做了局部修复，没有处理仅靠源码猜测的 Watch 多任务仲裁、Claude `trySend`、Keychain 删除竞态、部署 warning、文件行数和源码字符串 canary 等假阳性/风险项。Watch decision 现在绑定精确 `decisionId/type`，取消只承认对应前台或后台 run，切换 run 会清掉旧 summary/decision。
- OpenAI/Responses/Claude 流式只有收到协议终态才完成，异常 EOF、Responses failed/incomplete 与 Claude `stop_reason` 都诚实收口。Codex 登录/刷新不再改写 endpoint、模型 UUID/metadata 或私有模型；模型列表和连接测试区分真实错误，Codex/Grok 使用各自登录态。Deep Read 与 MiniApp AI 复用当前 provider/model，不再伪造 OpenAI。
- 同步备份恢复会同时恢复 conversations 并重新 bootstrap；不可读会话导出改为明确失败，本地损坏包不再阻塞其余列表。凭据 side-table 扩到 WebDAV/S3/TTS/自定义 header/body，未能 rehydrate 的 mask 不再进入运行时；带 Basic 凭据的 WebDAV 拒绝 HTTP，MiniApp HTML 注入禁网 CSP。Conversation index 会发现并修复中断写留下的孤儿文件。
- SubAgent 把 provider failure、调用方取消、真实秒级超时和轮次耗尽分别落到诚实终态；Council 工具返回实际动态席位与失败席位。Council 归档写失败成为 store/ViewModel 可观察告警但不打断正文；相关测试夹具改用真实测试 key，不再依赖把 credential mask 当 API key 的旧漏洞。
- 默认 Chat 24KB canary 在同一 iPhone 17 Pro / iOS 26.5 Simulator 上修复前稳定累积约 `893pt` bottom debt；撤销无效的 `.sizeChanges` 唯一写者假设、交回现有 measured-geometry owner 后，采样为 `23.3 → 21.3 → 6.7pt` 并通过 `≤72pt` 门禁。最终 Swift 受影响组合门禁为 148 passed、0 failed；`ai-provider-openai`、`ai-provider-claude`、`shared`、`core:conversation-storage` 四个 JVM 模块在单 worker、关闭 incremental cache 后 `BUILD SUCCESSFUL`。命令行显式传 `-sdk iphonesimulator` 会把 Swift 宏错误编成 simulator 可执行文件，验证命令必须只用 `-destination`；真实 provider、Watch 真机连接与 120Hz 视觉仍未验证。

### 2026-07-23 three-surface streaming follow-up closure

- 对上一轮 Chat / 模型议会 / 小说创作流式审查重新按生产调用链核对后，只修复有证据的缺口：议会阶段占位文案不再进入增量 Markdown（包括失败和冷恢复），`followPaused` 只控制自动滚动、不再冻结或触发行级 Markdown 重渲染；vendor 全量替换按真实公共前缀确定淡入范围，append 快路径未改。
- 小说 projection cache 不再因每个 `renderRevision` 重排、重建索引和重算 durable rows；content-only tick 只更新 transient row。terminal tail 在静窗内继续遮住同 ID durable row，静窗结束后再无换 ID 接管；新 run 启动失败会恢复上一条 terminal tail 的退役任务，避免永久停在“正在保存”且遮住 durable actions。当前单行更新仍会因 Swift Array COW 扫描/复制 rows，是否继续拆 immutable history/tail 需由真机大历史基准驱动，本轮未扩第二套存储结构。
- 议会归档同步终态写与延迟写的“代数检查 + atomic replace”现共享一个临界区，关闭旧延迟快照在检查后反超终态写的 TOCTOU 窗口；归档任务和注释按真实语义统一为 throttle。同步 save 极端情况下会等待一个正在提交的后台 atomic write，尚待真机慢盘取证。
- 原审查把标准 Chat 的 live-render `true` 直接判成 LOD 失效不成立：默认 SwiftUI 路径已有行可见性、`updatesSuspended` 和 digest 等值门控，且现有测试明确禁止用“离底距离”冻结仍可见的超长尾行；因此没有为 Chat 新增第二套可见性状态或改生产代码。其余缺少生产证据/属于产品差异的条目也未扩大修复。
- 红→绿判别覆盖 vendor replacement fade、议会占位/跟随、小说 content-only projection、terminal quiet-window 接管、新 run 失败恢复退役和归档提交顺序。最终 vendor `SwiftStreamingMarkdown` 为 94 passed、0 failed；iPhone 17 Pro / iOS 26.5 Simulator 上 Council runner/archive、Novel ViewModel/replay/presentation 与强制 `ChatStreamReplayTests` 合跑为 193 passed、1 skipped、0 failed（`Test-iosApp-2026.07.23_23-21-35-+0800.xcresult`），`git diff --check` 通过。
- 额外默认 Chat 长文回放中，`testLongProseViewportFollowStaysLineSizedAtTwentyFourKB` 本轮隔离两次仍报告约 893pt bottom debt；与本文件先前记录的同测试约 889pt 负载敏感失败一致，本轮没有修改 Chat 生产滚动代码，未把它冒充本补丁回归或通过。真实 provider、真机 120Hz 长流、terminal row 从 eager 区回到 history 区的重挂载视觉仍待人工验证。

### 2026-07-23 three-surface streaming audit: 6-item precise fixes

- 对标准 Chat / 模型议会 / 小说创作三处流式做审计(向上滚动、长文性能、抖动/闪烁),复核后锁定 6 个真问题并以红→绿测试逐项修复;未触碰任务无关的未提交改动。
- P1-1 议会归档:`CouncilChatRuntimeView` 跑中 checkpoint 由逐事件主线程同步写盘改为 300ms debounce + 主线程外 latest-wins 串行写泵;`CouncilArchiveWriteGeneration` 代际门让同步终态写盘作废在途的陈旧延迟写;终态路径(进后台/恢复中断/取消/checkpoint/开归档/run 结束)先 flush 或取消 debounce。红测:旧实现单 run 23 次主线程同步写,新实现 2 次。
- P1-2 小说完成闪烁(A+B'):A — `MessageBubbleView` 完成态解析立即执行(非动画 config 走立即解析分支),identity 缓存 64→256、renderable 缓存 12→24,完成段落重挂载/LOD 翻转时命中缓存得 `suppressesInitialFade=true`,消除整屏闪烁;B' — `NovelSessionViewModel.retireTerminalTransientTail` 在完成时立即清 `terminalAwaitingRefresh`(输入区解锁),把 transient tail 退役延后到与 `terminalQuietDelay`(0.4s)对齐的静窗(避免 durable 正文接管瞬间整屏「跳一下」),静窗内开新 run 或切 binding 取消退役任务。测试注入 0 走立即退役快路径,保留旧「完成即清空」契约;两条判别测试旧实现转红、修复转绿。
- P1-3 Chat 投影瘦身:`ChatTimelinePlanner.build` 新增 `includeRenderTokens`——只有 native mirror diff 消费 renderToken,SwiftUI clean list / collection 路径传 false,跳过尾行每 chunk 的 O(文本长度) token 扫描;`messageId` 由 `String(describing:)`(~25µs/次)改为直接访问器 `toHexDashString()`(8-4-4-4-12 格式等价由 canary 锁定)。
- P1-4 vendor fade:`ParagraphUIView` 全量替换时先 `activeAnimations.removeAll()` 再追加新尾 fade,防止旧 range 把错误透明度套到新文本的无关字符上(vendor 局部规则:外科修改 + `// Vendored fix (AmberAgent):` 注解 + `private(set)` 测试缝,默认行为不变)。
- P1-5 表格流式止血:表格尾块发布档位 <12K 保持原档,≥12K utf16 降到 0.22s,减少整表布局频率;抽成可测 static + `ChatStreamingMarkdownThrottleTestSupport` seam。
- P2-15 Reduce Motion:回底动画(`ChatCollectionMessageList`)与消息 part 插入动画(`MessageBubbleView`)读取 `\.accessibilityReduceMotion`,减弱动态时降级为无动画。
- 验证:iPhone 17 Pro / iOS 26.5 Simulator 上每项均先旧实现复现红再转绿。8 套件合跑(Novel VM/Replay、ChatMessageProjection、Council archive/runner、ChatStreamReplay、ChatSwiftUIStreamReplay、ThinkingOrbEngine)为 269 passed;`ChatSwiftUIStreamReplayTests` 2 条贴底跟随用例在高并发负载下偶发超时(889pt 未跟随),隔离重跑通过且在同代码的上一次合跑亦通过,判定为负载敏感 flake,未改阈值。vendor `SwiftStreamingMarkdown` 全套 82 passed / 0 failed。
- 两个既有测试文件做最小编译修复以让测试 bundle 可编译(非本轮产品改动):`WatchTaskSnapshotTests` 补 `import Shared` + `@MainActor`;`ThinkingOrbEngineTests.testReduceMotionStaticFrame` 补 `@MainActor`(OrbCanvasView.configure 为 MainActor 隔离)。后者使 `testDarkLightInkInversion` 首次可运行并暴露其既有过度断言:`.working`(orbits)画面由 ~480 条 white=0.72 亮墨 ghost 轨迹主导,dark 镜像(g=1-w)合法把 dark 均值压到 light 之下;ink inversion 真契约(两主题亮度不同)仍成立,只是朴素方向断言不适用于亮墨主导模式,已按证据修正(产品代码 ThinkingOrbEngine 未改)。
- 既有 flake 稳定化:`testWholeChapterBurstCoalescesUIPublicationsWithoutChangingDurableFinalText` 原只等 `!isRunning`,而 isRunning 在终态 presentation 即转假、早于 durable 落盘,负载下偶发把用户消息读成 durable 末条;改为等 durable 末条真正落盘再断言(与本文件 prose 用例一致),未放宽断言,修复后 5/5。同文件其余 13 处 `eventually { !isRunning }` 有同一潜在竞态,非本轮范围,仅记录。
- 残余缺口:真实 provider、真机 120Hz 长流手感与议会/小说完成闪烁仍需装机人工复验;Fix 1 debounce 的状态读取与写泵入队理论上存在微秒级竞态窗口(终态 flush 由代际门保护);`make lint` 因本机未装 swiftlint 无法执行;`MemoryToolApprovalCard.swift:848` 文末空行是既有未提交改动,本轮未触碰。

### 2026-07-23 ask_user P1/P2 precise closure

- 最小修复，不扩第二状态机：`finishPendingAskUserAnswer` 先校验 `currentRunId` 再 clear pending，避免竞态“清卡不写 tool output”。
- Watch 回包：`submitWatchUserAnswer` / `answerPendingAskUser` 改为 `Bool` 成功语义；只有 finish+resume 真正接住后才 accepted；失败 rejected，不再本地先清 decision。
- 产品对齐：Watch 选项扩到 schema 上限 6，并加 `skip`（复用 `.deny` style）对应 iPhone 跳过 / denied JSON。
- 验证：`xcodebuild -target AmberWatchApp -sdk watchsimulator` **BUILD SUCCEEDED**。

### 2026-07-23 standard Chat ask_user first-class pending + watch answer path

- 标准 Chat 把 `ask_user` 接成与 tool approval 同型的一等 pending 节点：`ChatToolRuntime` 识别/暂停，`ChatGenerationCoordinator` 持有权威 pending，`ChatViewModel.pendingAskUser` 只是 UI 镜像，Watch 只渲染 snapshot 并回传意图。
- 工具声明：`makeTextGenerationParams()` 无条件挂载 `ToolKt.createAskUserToolDeclaration()`；iPhone composer 增加 `ChatAskUserCard`（选项/短文本/跳过）；Watch 选项与语音走 `submitWatchUserAnswer` → `answerPendingAskUser` → `finishAskUserAnswer` → `resumeAfterApproval`，不会新开用户轮。
- review 后最小修复：后台 `backgroundToolExecutors` 登记 `ask_user` 为 `.denied("后台生成期间需要回到 App 回答问题。")`，避免 no-executor error-fill 续跑；`cancel()` 对 unresolved tool 写 `User cancelled` output，防止后续再次拾起；`autoGenerateResponses == false` 的 answer 分支补 `publishCompleted` + end Live Activity；`openOnPhone` 对 askUser/voiceReply 使用 `focus=confirmation`。
- 验证：`xcodebuild -target AmberWatchApp -sdk watchsimulator` 再次编译。`iosApp` scheme / 全量 iOS 构建仍受本机 MarkdownView/Markdown 依赖解析与 watchOS platform support 限制，未作为本切片产品失败。

### 2026-07-23 watchOS review-chain closure

- 按 review 的 P0/P1 做最小闭环，不扩第二套状态机：前台补齐 `presentStreamError`、图片完成、工具完成回 generating 的 Watch 发布；后台 `IOSChatBackgroundGenerationCoordinator` 的 running/completed/failed/cancelled 与 Live Activity 对称推送 `WatchTaskCoordinator`。
- cancel 竞态收口：`cancelGeneration` 不再先 `clear()` 成 idle；`cancel()` 同步 publish `.cancelled`，再异步 end Live Activity / 落盘。
- 审批回包：手表 approve/deny 改为 await 既有 `approvePending*` / `denyPending*`，等 resume 路径发布新 snapshot 后再回结果。
- openOnPhone focus 按 decision/phase 选择 `confirmation|result|task`；不可达时不再假发 `transferUserInfo` requestSnapshot，只保留本地/applicationContext；decode 校验 `protocolVersion`。
- `xcodebuild -target AmberWatchApp -sdk watchsimulator` 修复后再次 **BUILD SUCCEEDED**。标准 Chat `ask_user` 生产节点已在后续切片接线；`iosApp` scheme 测试仍受本机 watchOS platform support 限制。

### 2026-07-22 watchOS companion task console V1-V3 scaffold

- 新增 watchOS companion 主线：`iosApp/SharedWatch` 定义 `WatchTaskSnapshot` / decision / action 契约与 `WatchConnectivity` 编解码；iPhone 侧 `WatchTaskCoordinator` 是唯一权威投影与动作落地入口，Watch 只渲染快照并回传意图。
- iPhone 生成生命周期已接入：`startLiveActivity`、工具执行、审批暂停、完成/失败/取消都会推送手表快照；完成态附带 assistant 短总结（截断到 280 字）。审批节点支持 memory/search/workspace/webmount/mcp/council/ish 的允许/拒绝，并复用既有 `approvePending*` / `denyPending*`。
- Watch App（`iosApp/WatchApp`）提供状态台、审批卡、Ask User 选项、语音/短文本回答入口、取消与“在 iPhone 打开”。`openOnPhone` 通过 `Notification.Name.amberWatchOpenTask` 复用既有 `AgentActivityDeepLink` 会话跳转。
- XcodeGen 已生成 `AmberWatchApp` target 并 embed 进 `iosApp` / `iosAppExperimentalGPL`。`xcodebuild -target AmberWatchApp -sdk watchsimulator` 已 **BUILD SUCCEEDED**（Swift 6 / arm64+x86_64）。Xcode scheme destination 仍提示 host 未完整安装 watchOS 26.5 platform support，因此 `iosApp` scheme 的 destination 构建/模拟器联调会受阻；`WatchTaskSnapshotTests` 与 iPhone 主 App 全量编译待 platform 组件齐全后复验。

### 2026-07-22 thinking-orb activity island integration

- 将 Jakub Antalik 的 `thinking-orbs`（MIT）六态点阵动画引擎 1:1 移植为纯函数 Swift 模块 `ThinkingOrbEngine.swift`（CoreGraphics，无 UI 依赖），渲染层 `ThinkingOrbView.swift` 使用 UIKit `CADisplayLink` + `CGContext` 手绘，避开 SwiftUI 永动动画的 ViewGraph 60fps CPU 陷阱。
- 顶部活动岛 `ChatActivityIslandView` 的 `waiting`/`thinking`/`generating` 三种活跃状态 glyph 替换为 orb 动画（分别映射 `listening`/`working`/`composing` 形态）；`tool`/`image` 保留 SF Symbol 语义图标。
- 经三路并行 review（逻辑闭环 / 性能资源 / 移植保真度）后修复全部 P0 与 P1：`CADisplayLink` 加 weak proxy 断 retain cycle；`configure()` 修正 dark/paused 变更检测死代码；`orbResolvePreset` 裸强解包改 `guard ... fatalError`；`orbPaint` 每帧 per-dot 三次堆分配换零分配 `setFillColor(red:green:blue:alpha:)` 并改 `inout` 原地排序；displayLink 加 `preferredFrameRateRange(24...30)` 与 `isHidden`/`alpha` 暂停门控。移植数学逐行比对零 P0 偏差。
- `ThinkingOrbEngineTests` 扩到 8 条：在原 5 条基础上新增 dark/light ink inversion 像素断言、`.small` 预设最小密度 guard、reduceMotion 静态帧非空白断言；`collectDots` 重命名为 `assertRenderDeterministic`。review 修复前 5 条全绿，修复后 8 条需在 Xcode 内复跑确认（命令行 `-scheme iosApp` 因本机缺 watchOS 26.5 SDK 被 xcodebuild 拦在 watch target，与本轮代码无关）。
- `swiftc -typecheck`（Swift 6 strict concurrency）零错误零警告；真机视觉手感待安装后人工验证。

### 2026-07-22 streaming growth single-writer and novel presentation pacing

- 标准 Chat 与模型议会在 `.sizeChanges` 底锚持有流式高度增长时，不再对同一 assistant delta / measured growth 额外发 `scrollTo`；键盘等 viewport shrink 与原生 driver 持有路径继续使用显式写入，避免底锚和追赶命令双写造成连续跳动或闪烁。
- 小说流式展示缓冲改为保存单一权威 `targetContent`，新增 48ms 自适应 presentation pacer：轻积压每拍至少 12 字符，大积压最多 64 字符并以约 16 拍为软排空目标；append 保持前缀单调推进，replacement 分叉立即采用权威文本，complete/error/cancel 仍直接收口到完整终态，不让展示积压延迟持久化结果。
- iPhone 17 Pro / iOS 26.5 Simulator 上，`ChatViewportPolicyTests`、`IOSCouncilRunnerMechanicsTests`、`NovelSessionReplayTests`、`NovelSessionViewModelTests` 与强制 `ChatStreamReplayTests` 合跑为 190 passed、1 skipped、0 failed（`Test-iosApp-2026.07.22_22-08-43-+0800.xcresult`）；`git diff --check` 通过。首次沙箱内执行因 CoreSimulator/缓存权限未进入测试，首次沙箱外执行被第三方 `EquatableMacros` 信任门取消，使用 `-skipMacroValidation` 后同一门禁通过。真实 provider 下的 CJK 长章节奏与真机 120Hz 滚动手感仍待人工验证。

### 2026-07-21 novel whole-chapter streaming anchor stabilization

- 两张真机截图的坐标对比确认：导航、既有可见正文与 composer 均未移动，只有正文候选状态行在新 Markdown 高度发布后下移约 `53pt`。当前生产路径会在布局完成后才收到 measured growth，并对语义为 `animated: false` 的自动跟随再执行 `0.08s` 线性动画，形成可见的「先欠账、再追回」。
- 小说会话 ScrollView 现在使用原生 `.defaultScrollAnchor(.bottom, for: .sizeChanges)` 在同一布局事务内吸收流式高度变化；measured stream growth 不再发第二次滚动命令，其他自动回底也统一为无动画。现有 `NovelSessionBottomFollowState` 仍唯一负责跟随/浏览历史语义；没有移动状态行、增加 overlay、几何补偿、第二套状态机或修改 Markdown vendor。
- iPhone 17 Pro / iOS 26.5 Simulator 上，size-change 增长、收缩与浏览历史兼容探针为 3 passed；小说 replay 与 wiring 为 57 passed；强制 `ChatStreamReplayTests`、Native driver 定点回归及小说长会话布局 canary 为 56 passed、1 expected skip。`git diff --check` 通过。以上只证明模拟器布局与回归门禁，真实 provider 下的真机 120Hz 连续流式视觉仍待本包安装后人工复验。
- 二次审查补齐两个所有权缺口：`.sizeChanges` 底锚原无条件启用，会在「跟随生成」关闭时违背不自动跟随语义、并在原生滚动驱动持有容器时与 driver 双写同一偏移；现按 `followGeneration && !isNativeScrollDriverDesired` 门控（`NovelSessionSizeChangesPinModifier`），wiring canary 锁定门控条件。行锚定位置不被钉住拽走已由探针实证，无需再按跟随模式动态开关。
- `NovelSessionBottomAnchorProbeTests` 固化为 7 条永久 canary：旧追赶算法在突发增长下必留瞬态欠账（机制证据）；无钉住时持续增长欠账逐拍累积（证伪「依赖天然锚定」的简化方向并锁定）；收缩停底只是 UIKit clamp；sizeChanges 在 33/120pt 突发增长与收缩下逐帧零欠账；行锚定位置不被拽走；显式回底后钉住恢复。合并门禁：探针、replay、wiring、ViewModel、presentation、Native 核心与强制 `ChatStreamReplayTests` 合计 198 passed、1 expected skip、0 failed（`/tmp/anchor-gate-run.log`）。
- 当前工作区 Debug 包已使用 Team `89QRFX9548` 完成真机构建；App 与 Activity Widget 均通过 `codesign --verify --deep --strict`，新描述文件包含目标设备并有效至 2026-07-28。09:36 已覆盖安装并成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`，设备进程回读同时确认主 App 与 Widget 来自本次安装路径。以上证明构建、签名、安装与启动，不等同于真实 provider 下的长章节连续流式视觉验收。

### 2026-07-20 legacy interrupted-prose project compatibility

- `dfe4f2c83` 把中断正文 partial 升级为可收录 candidate 后，validator 同时要求历史 interrupted prose run 具备 candidate 与 message 关联；旧版本合法落盘但没有这两项派生记录的项目因此被误判为 primary/previous 同时损坏，列表的 epoch 占位又显示成“56 年前”。设备上的“大名大明”两份 JSON 均完整，安装过程没有改写项目数据。
- 解码边界现在只迁移这一种精确旧形状：run 为 interrupted prose、partial 与 assistant interruptedDraft 完全一致、run 自带未冲突 candidate ID、message 尚未关联且 document 无对应 candidate。迁移复用原 ID/内容/基线并生成 `.interrupted` candidate；任何不完全匹配的数据仍交给原 validator 严格拒绝。当前生成与写盘规则未放宽。
- 本地仓库和项目包导入复用同一迁移。回归测试先在空实现上按预期转红，最终生成 reducer/lifecycle、仓储、项目包与文档校验 108 passed、0 failed（`/tmp/amber-legacy-prose-red/Logs/Test/Test-iosApp-2026.07.20_19-36-36-+0800.xcresult`）；真实设备只读副本经生产仓库读取器确认“大名大明” revision 109 为 read-write。
- 当前 Debug 包完成严格验签、覆盖安装并成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`。设备上的旧 `index.json` 是页面扫描前的派生缓存；重新进入小说项目列表会按项目文件重建，不需要手工编辑或恢复 previous。

### 2026-07-20 focused native SwiftUI interaction refinements

- 小说项目/分支重命名与讨论归档等短 Sheet 改用内容自适应的 `.presentationSizing(.fitted)`；为避免 `Form` 的滚动容器理想高度干扰，两个单字段重命名页收敛为局部 `VStack`，原有焦点、提交与输入法收口保持不变。
- 写作上下文预算保留 2K...64K、2K 步进与原有绑定，改用原生 `Slider` 并只标出 8K/16K/32K 三个有意义的刻度；章节编辑器接入系统 `.findNavigator`，提供原生查找与替换入口。
- 顶部三段 Tab 没有叠加第二层 glass：现有实现已经是单个 regular glass 平面、半透明选中填充和内容下穿，符合 Liquid Glass 不混用 regular/clear、不做 glass-on-glass 的约束。
- `IOSNovelCreationWiringTests` 与 `NovelCreationPresentationTests` 在 iPhone 17 Pro / iOS 26.5 Simulator 合跑为 51 passed、0 failed（`/tmp/amber-ui-tips-derived/Logs/Test/Test-iosApp-2026.07.20_18-28-28-+0800.xcresult`）；改动 Swift 文件 parse 与 `git diff --check` 通过。Sheet 键盘高度、Slider 手感和查找栏视觉仍待真机目测，本切片未安装真机包。

### 2026-07-20 novel session memory S1-S3 adversarial closure

- 对 Grok review 修复做二次生产链核对后，补齐四个局部缺口，没有增加轮询、fallback、滚动补偿或第二套状态机。
- 讨论归档 checkpoint 现在保存提交时的完整 Session 游标，归档自身的 `throughSequence` 仍只界定被摘要替换的讨论边界；因此从该 checkpoint Fork 会保留其后已存在的正文/其他消息。validator 接受覆盖归档边界的 checkpoint 游标，同时兼容旧文档中两者相等的记录。
- 归档摘要只替换边界内 `.discussPlan` 的 user input / discussion 原文；交错的正文候选继续进入近期会话注入。结构化模型的连续无输出 heartbeat 只由非空 text delta / replacement 刷新，usage 不再延长等待窗口。
- 归档卡展开时，历史窗口按投影中实际由隐藏变为可见的行数增长，不再使用包含仍外露正文候选的 `messageCount`；现有窗口策略与归档展开状态保持不变。
- 四条契约均完成旧实现红测与最终绿测。与上一轮相同的 11 套件组合门禁在 iPhone 17 Pro Simulator 为 244 passed、1 expected skip、0 failed（`/tmp/amber-s123-precise-fixes-2026-07-20-rerun.xcresult`）。第一次 iPhone 17e 组合尝试在测试启动前遇到 SpringBoard preflight Busy，未计为产品失败；`git diff --check` 通过。真实 provider 与真机归档手感仍待外部验证。

### 2026-07-19 novel session memory S1-S3 review fixes

- 独立对抗性 review 的 P1/P2 已最小修复，未加第二套状态机或兜底轮询。
- P1：整章收录后的归档 Offer 与菜单入口在 `needsSync` 或 busy 时禁用；Offer 明示「剧情状态同步完成后可归档」，避免与自动同步抢锁导致 distill 首试失败。
- P2：中断文案仅在 actions 含 `collectProse` 时写「可收录」；Fork 从 discussionArchive 谱系继承 `archiveCursor`/`discussionArchives`（id 重映射），validator 允许创建分支残留或 head 谱系内的 archive checkpoint；折叠卡收起时仍露出 available/interrupted 正文候选以便收录。
- 定点 + 组合门禁：`NovelReducerTests`、`NovelSessionReplayTests`、`NovelSessionViewModelTests`、注入/文档校验、结构化输出/执行器、wiring、`NativeTimelineScrollCoreTests`、强制 `ChatStreamReplayTests` 为 243 passed、1 expected skip、0 failed（`/tmp/amber-s123-review-fix-combined.xcresult`）；`git diff --check` 通过。真实 provider 与真机归档手感仍待外部验证。

### 2026-07-19 novel session memory S1-S3

- 中断正文候选现在可收录其已持久化 partial 内容；原有 needsSync、stale candidate、source chapter drift 等 blocker 保持不变，收录与撤销仍走既有事务链。
- 注入 receipt 增补资料清单、剧情状态、近期消息轮数和预算排除计数；小说输入区只读面板展示这些事实，不提供 pin、排除或修改入口，也不改变 S2 前的注入选择。
- 讨论归档只由整章收录后的可跳过步骤或会话菜单触发。单次结构化模型调用使用连续无输出超时，用户可逐项确认、编辑或删除决定；全拒不落盘。确认后决定以 branch override 的 `.decisionLog` material 落盘，摘要与游标进入可选归档字段并随 checkpoint/undo 语义生效。
- 投影层以稳定 archive ID 把有效 checkpoint 谱系中游标前的行折叠为一张归档卡；展开复用既有 `historyWindowLimit`，原始 row ID 不变，未新增滚动状态机或几何补偿。注入层用有效归档摘要和 decisionLog 替换游标前原始消息；无 archive cursor 的旧文档保持旧投影与旧注入行为。
- 最终组合门禁覆盖 reducer、文档/项目包兼容、注入、Session 投影/ViewModel、结构化输出/执行器、Prompt catalog、UI wiring、`NativeTimelineScrollCoreTests` 与强制 `ChatStreamReplayTests`：251 passed、1 expected skip、0 failed（`Test-iosApp-2026.07.19_13-55-28-+0800.xcresult`）。真实 provider 的蒸馏质量、网络超时表现与真机折叠/展开手感仍待外部验证；按计划停在 S3，S4-S7 未启动。

### 2026-07-18 novel review fix batch: cold-row placeholder metrics, completion window continuity, static-growth affordance

- 独立对抗性 review 判定"轻拖飞出"的最强残余根因是历史行冷实例化链路：LazyVStack 首次实例化时 `ChatStableStreamingMarkdownView` 的纯文本占位把每个空行渲染成整行文本高度，与异步解析后 blockSpacing 分段布局差出百 pt 级，解析落地瞬间整章收缩造成位移。占位现按空行拆段（vendor `RenderableDocument(plainText:id:config:splittingParagraphsOnBlankLines:)` 新增 opt-in 参数，默认单段行为不变，仅 AmberAgent fallback 调用点启用；chunk id 按索引稳定，append-only 更新不重建前缀段）。review 同时证伪了两条此前嫌疑：非动画 config 下占位→解析替换不会重淡入（`ParagraphInitialFadePolicy` 已由 `configShouldAnimateText` 门控），行距也已在视图层对占位生效——均未额外修改。
- 完成瞬间 `historyWindowLimit` 原按合并后行集裁剪，会把完成前已可见的旧行裁出视图树造成位移。`NovelSessionHistoryWindowPolicy.limitAfterActiveRunReturnsToHistory` 现按快照差只吸收真正转入历史的行数（启动失败整体消失的行不扩窗），在 `activeTailID` 清除分支接线；`NovelSessionListSignal` 增加 `activeRunRowCount` 供该推导。投影层无消费者的 `activeUserMessageID` 字段已删除。
- 静态排版增长把用户推离底部时原先完全静默（SwiftUI 回退路径无任何补偿），新增 `.staticContentGrowth` follow 事件：只按真实到底状态刷新底部按钮，不发滚动命令、不改模式，静态增长仍不拥有滚动所有权。原生 driver 底部收敛耗尽保持所有权语义不变，未到底时补一条 `bottomConvergenceExhausted` 诊断日志；对应 fallback 枚举 case 标注为无生产构造路径。
- 跨 speculative 模式复用 renderable 的取舍新增未闭合语法种子契约（中断/超时停在半截表格/强调时仍复用流式渲染保持连续、抑制重淡入，由立即重解析纠正）；vendor `reusingUnchangedPrefix` 补单段落增长用例（首段变化时不复用、内容取新值），占位拆段行为有默认/opt-in 双向断言。明确未修：`resolution()` 主线程 `hasPrefix` 前缀扫描（量级未采样）、`ParagraphUIView` 宽度猜测（proposal 正常路径不触发）、按钮回底动画取消瞬跳（无运行证据）。
- `NovelSessionReplayTests`、`NovelSessionViewModelTests`、`ChatMessageProjectionTests`、`NativeTimelineScrollCoreTests` 与强制 `ChatStreamReplayTests` 合跑 211 passed、1 expected skip、0 failed；vendor `make test` 全套（含新增布局实验）81 passed、0 failed；`git diff --check` 通过。新契约测试（几何策略静态分支、窗口吸收算术、reducer 按钮语义、wiring source 断言）按构造对旧实现必红，未执行单独红跑。
- 占位高度差已用布局级实验坐实为 E1：真实 `DocumentView` + TextKit 测高（生产等价参数 blockSpacing 8 / 行距 4 / collapsesSoftBreaks，24 段 CJK 散文、360pt 宽）下解析 1840pt、单段占位 2304pt（偏高 464pt）、拆段占位 1840pt（误差 0）；`testSplitPlaceholderTracksParsedLayoutHeightFarCloserThanLegacyPlaceholder` 以机器无关比值断言留作永久 canary。注意该差距只在生产 blockSpacing=8 下显现，vendor 默认 blockSpacing=30 与空行渲染高度相消（默认参数实测差仅 4pt），实验必须用生产参数。
- 外部（codex）复审确认两个 P2 并已红→绿修复：①`staticContentGrowth` 在跟随态亮按钮后，`viewportChanged(true)` 原不收按钮导致"已到底仍显示箭头"——reducer 的 followingBottom/settlingTerminal 回底分支现同步收按钮（`testViewportReturnToBottomClearsStaleBottomButtonWhileFollowing` 旧代码必红已实测）；②占位拆段原只识别严格 `\n\n`，CRLF 与仅含空白的空行（cmark 均视为段落分隔）会退回单段占位——改为按行扫描空白行分组（`testPlainTextPlaceholderSplitsOnCRLFAndWhitespaceOnlyBlankLines` 旧代码必红已实测）。复跑 `NovelSessionReplayTests` + 强制 `ChatStreamReplayTests` 43 passed、1 expected skip、0 failed；vendor 全套 82 passed、0 failed，布局实验数值不变（splitDelta 仍为 0）。
- 含全部修复的 Debug 真机包已用 Team `89QRFX9548` 构建、`codesign --verify --deep --strict` 通过并覆盖安装到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动因设备锁定被拒（RequestDenied）。待人工复验三个窗口：长章节轻拖上滑（飞出应显著减弱或消失）、生成完成瞬间（完成前可见旧行不再被裁掉）、冷进入后静止位置与底部按钮状态（含回底后按钮收起）。

### 2026-07-18 model council question, timeout, and connectivity closure

- 议会原先在每轮末无条件把一段“输入问题，或留空跳过”的流程说明当成 Ask User 问题交给 Sheet；这不是模型生成的问题，也没有可回答的结构。该伪提问已删除，当前运行时不会再假装存在模型提问能力；未新增问题解析协议、自动追问或第二套暂停状态机。
- 主持人最终综合等单次模型超时由绝对墙钟改为“连续无输出”超时，任意有效流式增量都会刷新计时；超时仍会取消当前 provider 流，没有自动重试或摘要续写。Grok Web 的终止帧现在会主动结束 browser transport，不再等待 HTTP EOF；Provider 详情的连接测试也只在真实模型列表请求返回后报告成功或失败。
- 「设置 → 模型议会」新增当前议会模型连通性测试：按实际主持/默认席位路由发送极短生成请求，逐项显示可用、失败和“配置模型不存在而回退到当前模型”。普通 Chat 的 `model_council_run` 现读取同一份 Room 设置，审批后的执行任务也进入 coordinator 取消所有权。议会运行器、工具运行时和设置接线合跑为 73 passed、0 failed；强制 `ChatStreamReplayTests` 为 17 passed、1 expected skip、0 failed，`git diff --check` 通过。更大合跑在未触及的 search cancellation canary 等待 continuation 时卡住并人工中止，未把它记为议会产品失败；真实 provider 账号下的各模型结果仍需在设置页实际点击验证。

### 2026-07-18 novel scroll ownership and completion identity correction

- 真机视频中的进入过程不是正常导航动画：已有长章节先到达底部，随后静态 Markdown/TextKit 首次排版继续增加 `contentSize`。首次收紧 measured-growth 后，静态增长虽然不再产生 terminal event，却仍会从相邻的 `measuredEvent == nil` 分支漏成 `.viewportChanged`，在 `.followingBottom` 下继续发起到底命令。几何分类现由一个策略一次性返回事件；live tail、terminal settle、静态增长和纯 viewport 变化互斥，冷进入的静态历史排版不再绕路取得滚动所有权。
- 最新一轮 user/assistant 行只在存在真实 transient tail 时留在普通 `VStack`，保证发送等待与流式阶段身份连续；耐久刷新清除 `activeTailID` 后，该 run 必须回到历史 `LazyVStack`。此前按“最后一个 run ID”把已完成整章永久留在 eager 尾部的尝试已被真机证伪：长章节进入后轻微拖动会触发超长 eager 子树重新核算，表现为大幅飞出并需要长距离滚回；该永久保留已删除。进入页的静态增长隔离、活动 run 的 user 气泡连续性和原生滚动跟随均未改，也没有增加偏移补偿。
- 共享 `NativeTimelineScrollDriver` 在 SwiftUI 更换底层 `UIScrollView` 时会清除旧容器的运动状态，并明确通知 Chat、小说和模型议会重新投放各自的跟随/暂停语义；同一容器的重复 resolve 仍是幂等，attach 本身不改 replacement 的位置。没有增加几何补偿、重试循环或新状态机。
- 三条生产契约 canary 在旧实现上分别因静态增长漏发 viewport follow、完成态行换父容器、replacement 继承旧 motion state 转红，修复后转绿。`NativeTimelineScrollCoreTests`、`NovelSessionReplayTests`、`NovelSessionViewModelTests`、强制 `ChatStreamReplayTests`、SwiftUI stream replay 与 viewport 合跑为 189 passed、1 expected skip、0 failed；额外 production wiring 为 44 passed、0 failed，`git diff --check` 通过。最新 Debug 包已使用 Team `89QRFX9548` 构建、严格验签并覆盖安装到 iPhone Air；自动启动仅因设备锁定被拒绝，视频场景仍需解锁后目视复验。
- 永久 eager 尾部的回归 canary 已在旧实现上转红：`activeTailID == nil` 后 `activeRunRows` 仍错误包含完整 run；收紧后定点转绿。`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NativeTimelineScrollCoreTests` 与强制 `ChatStreamReplayTests` 组合门禁通过，`git diff --check` 通过；修正包已重新签名、覆盖安装并成功启动到 iPhone Air，轻拖手感仍以本次真机操作复验为准。

### 2026-07-18 novel native-scroll layout feedback closure

- 小说长文越生成越卡的根因位于原生滚动观测回路，而不是本轮先怀疑的 Markdown 全文解析：resolver 把随滚动变化的 `contentOffset` / `bounds.origin` 当作布局变化，并由 driver 对已完成的布局再次调用 `layoutIfNeeded()`，导致长列表在拖动和内容增长期间反复重入全量布局。无效的段落合并与 Markdown environment 实验均已撤除。
- resolver 现在只观测 `contentSize`、viewport size 与 inset；纯 offset 变化继续由既有 `onScrollPhaseChange` 和跟随 driver 处理，不再触发布局回调。布局观测回调也不再强制第二次 layout pass；显式回底与 convergence 中原有的必要布局、120Hz frame driver 和屏内动画保持不变，没有增加几何补偿或 fallback。
- 两个 canary 分别对“观测回调不得再次 layout”与“纯 offset 不得改变 resolver metrics”完成红到绿。原生滚动核心、小说长会话/回放、`ChatStreamReplayTests`、SwiftUI stream replay、viewport、projection 和 Paragraph append 合跑为 221 passed、1 expected skip、0 failed；`git diff --check` 通过。Debug device build 使用 Team `89QRFX9548` 自动签名成功并通过 `codesign --deep --strict`，已覆盖安装到 iPhone Air；自动启动仅因设备锁定被拒绝，长文手感与完成标识最终稳定性仍需解锁后真机目视确认。

### 2026-07-18 novel send-start prompt continuity

- 小说会话发送后、provider 尚未完成连接的窗口期不再只投影 assistant 等待胶囊。`NovelSessionTransientTail` 现在携带同一 run 的临时 user message identity 与输入正文；若耐久 session 快照尚未包含该 message ID，投影先显示 user 气泡，再紧接等待尾行，因此长连接等待时不会只剩胶囊和大片空白。
- provider 启动成功后的 `.started` 刷新仍是耐久事实来源；耐久 user message 到达后按同一 message ID、同一 row digest 原位接管临时行，不重复、不重建。启动失败仍恢复发送前 tail，输入框沿原有 `send == false` 语义保留内容。没有修改滚动几何、bottom target、动画节拍或 provider 生命周期。
- 新增阻塞 `NovelCreation.start` 的生产链 canary，覆盖上一轮长内容、新 user 气泡、等待尾行、耐久接管无重复与 digest 连续。`NovelSessionReplayTests`、`NovelSessionViewModelTests`、强制 `ChatStreamReplayTests` 合跑为 92 passed、1 expected skip、0 failed；`git diff --check` 通过。09:00 全新 Debug 包完成严格签名校验、覆盖安装并成功启动到 iPhone Air；Grok 长连接等待下的最终位置仍需设备人工复验。

### 2026-07-18 chat terminal, re-entry, and long-stream stability closure

- 终态事件现在由 `ChatGenerationCoordinator.finishStreaming` 在清空 run/consumer/loading/approval 所有权后统一发布，完成、失败、工具循环上限、审批结束和用户图片工具不再让滚动层在 coordinator 仍 active 时先执行 terminal settle；图片工具失败也不再误报 `generationCompleted`。
- Native 重入只在拿到本轮真实几何后才把“未到底”解释为历史浏览，测量标记会在离页、关闭 driver 和切会话时复位；viewport 的暂停状态同时读取 driver 的 `pausedForUser`。底部 convergence 次数耗尽不再把健康的原生容器交给 SwiftUI fallback，默认 clean-list 的会话入场重试在真实到达底部后立即停止。
- 完成态解析期间继续复用同视觉配置的既有 Markdown renderable，不再先退回 raw/plain text；append-only 文档复用未变化的 renderable 前缀，表格块的全文比较移到 detached 任务，主线程只发布最终合并结果。端到端 24KB 矩阵通过，当前采样为表格 `79.3ms/delta`、散文 `44.7ms/delta`；相较旧采样有有限改善，但仍高于 120Hz 帧预算，未用嵌套懒加载或降低屏内动画冒充闭环。
- `ChatStreamReplayTests`、`ChatSwiftUIStreamReplayTests`、`ChatMessageProjectionTests`、`ChatViewportPolicyTests`、`NativeTimelineScrollCoreTests` 和新增终态/缓存定点均通过，`git diff --check` 通过。`ChatViewModelSelectedFileContextTests` 全类与多类联跑遇到 Xcode test worker `waiting for workers to materialize` 基础设施卡死；新增终态用例单跑通过。真机长流、回底和完成瞬间视觉尚未复验。

### 2026-07-17 novel workspace native glass section navigation

- 小说工作区顶部「创作 / 正文 / 设定」不再使用灰色 `Picker(.segmented)`，改为单一原生交互式 Liquid Glass Capsule；容器固定为紧凑的 `44pt`，避免 `safeAreaBar` 的纵向提案经 Tab 内部 `maxHeight` 传播后把玻璃拉伸到整页。三个按钮保持等宽、原有 `NovelWorkspaceSection` 状态与页面切换不变；选中项的 matched-geometry 动画只作用于 Tab 子树，不再把三个页面的切换纳入同一动画事务。保留 `16pt` 页面边距、动态提案数量、Reduce Motion、禁用态、选择触感和 VoiceOver 选中语义。iOS 26 以下提供 `ultraThinMaterial` fallback。
- 19:19 真机截图暴露浅色模式选中填充使用白色透明层，在白色玻璃上几乎不可见。选中游标已改为同一玻璃平面内的 `6.5%` 黑色透明胶囊，并增加 `0.5pt` 高光描边和轻阴影；仍只有外层一处 `glassEffect`，不是玻璃叠玻璃。
- 参考图复核进一步确认透底差异来自布局而非透明度：Tab 原先是普通 `VStack` 子项，会把三个页面的滚动内容整体向下顶开，玻璃后方只有空白画布。Tab 现通过 `.safeAreaBar(edge: .top)` 成为顶部 chrome；「设定」页的分类 Picker 也从包住 List 的固定 `VStack` 移为 List 自身的顶部 safe-area inset，使三个页面的滚动容器都能直接进入外层玻璃后方。没有新增固定 top padding 或第二套滚动状态。
- arm64 generic iOS Simulator Debug 构建通过；完整 `IOSNovelCreationWiringTests` 当前为 23 passed、0 failed。此前唯一失败是同一文件中仍要求冷进入 `.followingBottom` 的过时字符串断言；生产契约与 `NovelSessionReplayTests` 均已使用 `awaitingInitialRows`，现已统一为默认状态断言。当前没有可直接进入目标小说项目的视觉 fixture，因此参考图级运行截图仍待真机页面复验。
- 悬浮透底修复后的 wiring 定点与 `NovelCreationPresentationTests` 为 24 passed、0 failed，`swiftc -parse` 与 `git diff --check` 通过。该修复已包含在 19:45 的最新完整真机包中，完成严格签名校验、覆盖安装和启动。最终滚动透射视觉仍以设备人工复验为准。
- 22:37 真机截图发现 Tab 容器从固定 `44pt` 改成 `minHeight: 44` 后，会接收 `.safeAreaBar` 的整页纵向提案；内部标签的 `maxHeight: .infinity` 随即把 Liquid Glass 拉成全屏巨型胶囊。已恢复外层固定 `44pt`，没有增加高度探针或兜底状态；小说 wiring、呈现与完整 `ChatStreamReplayTests` 为 62 passed、1 fixture skip、0 failed。22:49 全新 Debug 包完成严格签名校验、覆盖安装并成功启动到 iPhone Air。

### 2026-07-17 chat top glass capsule and system-owned soft edge

- 标准 Chat 顶部中间的会话标题与活动状态不再悬空显示纯文字，统一使用非交互的原生 Liquid Glass Capsule；左右按钮与中间胶囊在 iOS 26+ 进入同一个 `GlassEffectContainer`，低版本保留 `ultraThinMaterial`、轻描边与阴影 fallback。标题仍不可点击，现有活动状态、翻转动画、文字截断和左右按钮行为未改。
- 首轮自绘 `ultraThinMaterial + gradient mask` 真机验证失败：材质取代系统 edge effect 后只覆盖 `safeAreaBar` 内部，正文直接漏进状态栏，同时与标题/按钮玻璃形成发白的双层材质。后续增加 `.safeAreaBar` bottom padding 同样无效，因为系统 `.soft` 的采样曲线不读取该 padding。20:03 真机图又证明“从按钮底部向下增加 40pt 材质”的方向理解错误：它产生了一条突兀的白色横带。该附加材质、额外 padding 和几何补偿现已全部删除；顶部只保留三条列表的系统 `.soft`，覆盖区从系统状态栏开始，`54pt` 顶栏内的控件改为底对齐，使 soft edge 的下边界与按钮底部重合。
- 返回与新会话的圆形玻璃仍在 `GlassEffectContainer` 内统一折射，但 monochrome `UIColor.label` glyph 保持为容器外独立 sibling overlay；底层透明按钮继续负责点击、按压动画和 VoiceOver。overlay 现在同步读取对应 Button 的按压态，玻璃圆形和 glyph 以同一曲线缩放；顶栏内外两层 ZStack 均按底部对齐，活动岛在 `34/42pt` 间切换时不再带动左右按钮上下跳。这样既保留浅色模式真黑 glyph，也不牺牲按压反馈。iPhone 17 Pro / iOS 26.5 Simulator 定点门禁加完整 `ChatStreamReplayTests` 当前为 18 passed、0 failed、1 fixture skip，`git diff --check` 通过；22:24 全新 Debug 真机包完成签名校验、覆盖安装并成功启动到 iPhone Air，设备进程列表确认主进程运行中。
- 22:24 之后的连续真机复验确认：把 `.safeAreaBar` 改挂到 `messageList`、给 bar 增加 `16/32pt` padding，以及用第二层 frame 增加 `32pt` edge-effect 尾部，都没有让系统 `.soft` 的可见渐隐稳定下探到按钮下边界。以上实验已全部撤销，不保留失效常量、额外 bar 高度、自绘材质、正文 padding 或滚动补偿。顶部回到单一 `54pt` 控件平面与系统原生 `.soft`；标题/活动胶囊按固有宽度布局并保留 `230/268pt` 碰撞上限，按钮 glyph 对比度与按压同步修复继续保留。撤除后的 `IOSSettingsWiringTests` 与完整 `ChatStreamReplayTests` 为 37 passed、1 fixture skip、0 failed；22:54 全新 Debug 包完成严格签名校验、覆盖安装并成功启动到 iPhone Air。该视觉缺口暂缓，不再继续堆叠策略。

### 2026-07-17 novel discussion completion and streaming presentation correction

- 讨论模式不再向 provider 人为设置最大输出 token；provider 若仍明确以 `length` / `max_tokens` / `max_output_tokens` 结束，当前半截内容会保留，并进入原有可重试失败态，不再被误记为一次完整回复。普通 KMP 流和讨论工具循环共用这一终态语义，没有增加内容猜测、自动续写或第二次请求。
- 小说会话冷进入恢复为 `awaitingInitialRows`，已经加载好的历史行会在页面出现后只做一次底部呈现；发送开始时同一状态机立即锚定新用户消息和等待尾部，并交回既有 Native scroll driver 处理后续实测增长，避免初始空屏、用户气泡飞出视野以及从 idle 状态开始的流式跳动。没有新增几何补偿或第二套滚动驱动。
- `NovelGenerationLifecycleTests`、`NovelLiveModelAdapterTests`、`IOSAgentToolEngineTests`、`NovelSessionReplayTests` 与强制 `ChatStreamReplayTests` 在 iPhone 17 Pro Simulator 通过；`git diff --check` 通过。真实 provider 的长回复与真机 120Hz 视觉仍需安装后人工复验，本切片未执行真机安装。

### 2026-07-17 model council execution-chain closure

- 模型议会统一读取当前共享 `Provider` / `Model` / generation params，不再克隆 provider identity 或从全局设置补猜调用参数；Codex 继续经过既有 header resolver，Grok Web 经过既有 web client，OpenAI-compatible / Claude 复用现有 KMP streamer。模型与 provider 不匹配时在发起任务前明确阻止，搜索能力严格跟随现有 Web Search 开关。
- 工具调用补齐既有审批策略、拒绝审计与结构化失败语义；`max_seats` 明确表示非主持席且限制为 2...8。主持定题、席位输出和最终汇总共享单次模型超时，空定题/空汇总终止整轮，单席空输出只失败该席，合法的 `Error:` 正文不再被误判。第二次发送创建独立 room/task，后台、取消、进程终止和启动恢复都收口到可持久化的终态，没有新增自动重试或第二套 provider 引擎。
- iPhone 17 Pro Simulator 上模型议会、归档、设置、工具与 provider wiring 的组合门禁为 80 passed、0 failed；强制 `ChatStreamReplayTests` 最终为 16 passed、1 expected skip、0 failed（`/tmp/amber-council-dd/Logs/Test/Test-iosApp-2026.07.17_00-24-00-+0800.xcresult`）。`:ai-core:compileKotlinIosSimulatorArm64`、改动 Swift 文件 parse 与 `git diff --check` 通过。真实 provider 登录态、网络调用与真机交互尚未执行，不能仅凭 canary 宣称外部链路已验证。

### 2026-07-16 novel Ask User and character alias closure

- 小说讨论模式复用现有 Agent tool loop，新增持久化的行内 Ask User：模型每轮最多提出一个关键问题，用户可单选建议或直接输入，回答作为下一条普通讨论消息继续生成；搜索关闭时 Ask User 仍可用，搜索开启时继续复用 Chat 的 `search_web` / `scrape_web`。Grok Web 仅保留严格整对象 JSON fallback，没有新增第二套 Agent 引擎。
- 角色资料新增项目级别名；Quick Start 可给出别名，人物设定页可直接编辑。注入始终携带规范名与别名映射，剧情状态校验按精确归一化匹配同一人物；无法唯一匹配的 `character.*` 引用才在会话末尾提示用户关联到已有角色，关联复用原资料修订链路。
- 为避免无用泛化，Ask User 已收紧为单轮单问题，仅支持单选或自由输入，没有多问、多选、模糊人物合并或通用表单框架。KMP iOS Simulator 编译与 Shared framework 链接通过；Ask User 生命周期、持久化/恢复、模型适配、注入、文档兼容、项目配置、提示词、搜索开关、小说 wiring 和 `ChatStreamReplayTests` 等受影响定点回归通过；`git diff --check` 通过。尚未在真实 provider 和真机上完成交互目测。

### 2026-07-16 iOS 27 soft scroll-edge restoration

- Chat 顶部自定义 `safeAreaBar` 不再依赖系统 `.automatic` scroll-edge 选择：Native Timeline、默认 SwiftUI clean list 与 legacy `UICollectionView` 三条消息列表路径均显式使用官方 `.soft` 顶部边缘效果，恢复渐变模糊并避免 iOS 27 Beta 自动切成带硬分界线的 `.hard`。同样使用自定义顶部安全区栏的「外观」页也显式固定为 `.soft`；会话列表原有 `.soft` 保持不变。没有添加手写渐变、遮罩或模糊参数。
- iOS 27 真机截图确认 `.soft` 默认衰减区过短，滚动正文仍会穿到会话标题后方。此处曾用 `.padding(.bottom, 16/32)` 尝试延长顶栏，但后续真机证明 padding 并不能稳定控制系统 soft-edge 曲线；该实验已被 2026-07-17 的真实 bar 高度方案取代，不能再作为当前实现或装机状态读取。

### 2026-07-16 Native Timeline blank-space and displacement-flash correction

- 三路独立 review 从滚动几何、事件所有权和渲染生命周期交叉定位到同一主因：Chat 已通过 `safeAreaInset` 让 UIKit 的 `bounds + adjustedContentInset` 表达键盘和输入框后的真实 viewport，Native driver 却又把 `keyboardOverlap + composerHeight` 叠加进 bottom target。真实 `UIWindow` 红测试中，UIKit 合法底端是 `880pt`，旧 driver 会写到 `1172pt`，多出的 `292pt` 正好是重复遮挡量，直接解释发送后空屏、输入框上方大空白和大位移闪烁。
- 修复收回所有权而非添加几何补偿：Native bottom target 只认 UIKit `adjustedContentInset.bottom`；键盘通知、SwiftUI viewport shrink 与 layout frame 不再各自发起独立到底动画，viewport 改变统一由 resolver 转成一次可重定位的 `viewportChanged`，继续使用既有 120Hz frame driver；用户正在拖动时仍立即暂停。resolver teardown 也关闭了异步回调在离页后复活 display link 的窗口。
- Native Timeline 现在与 clean-list 一样，仅把稳定历史放进 `LazyVStack`，动态流式尾部和末尾锚点留在普通 `VStack`，避免靠近底部时 live/static Markdown 子树反复卸载重建；同一 signal revision 的 live-tail model 更新改为幂等，减少无新内容时的重复发布。没有改 provider 展示节拍、屏幕内动画等级或使用 `contentOffset` 补偿。
- 定点 `NativeTimelineScrollCoreTests` 与 live-tail revision canary 通过；强制 `ChatStreamReplayTests` 通过；`git diff --check` 通过。组合门禁中只有默认 clean-list 的 `testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` 独立重跑仍失败（一次证据为 p95 `127.38ms`、max `175.14ms`，阈值未放宽），与本次 Native 路由修复分开记录。Debug 真机包已完成构建、严格签名校验、覆盖安装并于 23:22 成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；发送时键盘转场、长流回底与终态的实际视觉仍需设备人工复验，不能仅凭装机宣称闭环。
- 2026-07-17 的真机视频进一步确认，正常拖动跨越远底阈值时最后一条超长 assistant 曾在 live/frozen Markdown 子树间切换，造成内容高度短暂塌陷、整屏空白后由底部重入。当前修复保持尾行渲染树与 live-tail model 身份稳定，远离底部时只暂停 model 发布，历史行仍维持 lazy/frozen。强制 `ChatStreamReplayTests` 为 16 passed、1 expected skip、0 failed（`/tmp/amber-chat-blank-replay/Logs/Test/Test-iosApp-2026.07.17_00-35-57-+0800.xcresult`）；当前完整工作区的 Debug 真机包已通过构建和严格签名校验，并于 00:42 覆盖安装、成功启动到同一 iPhone Air。视频症状是否消失仍需本包人工复测。

### 2026-07-16 shared native streaming-scroll ownership closure

- 「原生滚动容器（实验性）」不再只影响标准 Chat。原先内嵌在 `ChatCollectionMessageList.swift` 的 SwiftUI `UIScrollView` resolver 已移动到 `NativeTimelineScrollDriver.swift`，与既有 120Hz frame driver 形成可被不同会话页面复用的底层能力；小说创作和模型议会只把 measured growth、拖动、显式到底、会话重置与终态 settle 翻译成共享语义 intent，没有复用 Chat 的消息投影或行视觉。
- 对抗性 review 找到的所有 P1 已在所有权层收口：driver `attach` 只连接、不再隐式抢到底部；`followGeneration=false` 会停止自动跟随并屏蔽后续流式增长/布局增长；拖动结束统一以当前原生容器距底距离为准，只有原生容器缺席才退回 SwiftUI 缓存几何；页面离开先标记不可见再 invalidate，异步 resolver 不会在离页后重新 attach。重新 attach 时 Chat 还会把“当前不在底部”视为历史浏览，小说/议会沿用各自持久的 pause 状态，不会因生命周期重连把用户拉到底部。
- P2 的设置耦合已拆开：「原生滚动容器」只控制三页面共享 driver；「Chat 原生时间线」只成对控制 Chat 的静态列表与流式尾部路由。两者不再用三开关 AND 绑定，关闭 Chat 实验渲染不会让小说/议会丢失底层原生滚动能力。共享 driver 行为新增 attach、禁用跟随、重连保位与实时距底判定测试，production wiring canary 覆盖三页面的 follow、生命周期和拖动接线。
- 受影响的七个套件合跑为 203 tests、1 expected skip、0 failed（`/tmp/amber-native-scroll-red/Logs/Test/Test-iosApp-2026.07.16_22-33-46-+0800.xcresult`）；最终生命周期条件落地后，强制 `ChatStreamReplayTests` 与 `IOSSettingsWiringTests` 再跑为 35 tests、1 expected skip、0 failed（`/tmp/amber-native-scroll-red/Logs/Test/Test-iosApp-2026.07.16_22-37-50-+0800.xcresult`）。Debug 真机包使用 Team `89QRFX9548` 构建成功并通过 `codesign --verify --deep --strict`，22:39 已覆盖安装且成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；真实 provider 下 Chat、小说与议会的长流手感仍需设备人工验证。

### 2026-07-16 Native Timeline production-route regression correction

- 真机反馈确认 21:00 后重新出现「四五行一抽一抽上移」不是 provider、TextKit 或滚动状态机的新回归，而是提交 `4b2a1c8e8` 移除了「原生滚动容器（实验性）」设置与 `ChatView` 的 Native Timeline 生产分支；用户昨日已开启的持久化开关因此被忽略，标准 Chat 实际回退到默认 `ChatSwiftUIMessageList`。
- 最小修复只恢复三项 Native Timeline 开关的联动设置、`ChatView` 对两项路由开关的读取和既有 `ChatMessageListRoutePolicy` / `NativeChatTimelineView` 分支。开关关闭时仍走默认 SwiftUI clean list；没有改 provider 展示节拍、Markdown/TextKit、滚动几何、动画参数或今天其余 novel/council/vendor 修复。
- 两条 production wiring canary 先在入口缺失时稳定失败，恢复后转绿；`IOSSettingsWiringTests`、`ChatMessageProjectionTests`、`NativeTimelineScrollCoreTests`、强制 `ChatStreamReplayTests` 与默认路径 `ChatSwiftUIStreamReplayTests` 合跑为 150 passed、1 skipped、0 failed（`/tmp/amber-native-route-regression/Logs/Test/Test-iosApp-2026.07.16_21-27-07-+0800.xcresult`），`git diff --check` 通过。Debug 真机包完成自动签名构建与 `codesign --verify --deep --strict`，21:33 已覆盖安装并成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`。原生容器与表格流式块渲染同时开启的既知兼容问题仍未在本切片扩修；真实 provider 逐行手感仍需设备人工复验。

### 2026-07-16 production streaming-scroll ownership correction

- 标准 Chat 不再向用户暴露或读取「原生滚动容器（实验性）」开关，生产路由稳定收敛到默认 `ChatSwiftUIMessageList`（原生 SwiftUI `ScrollView`），既有 collection fallback 保留；未完成的 Native Timeline 实现与定点测试仅作为 dormant code 留存，不再参与生产分流。Native 生命周期顺手补齐了无单一文本快照时的 freeze 收口，避免隐藏路径留下 live render state。
- 小说页把「流式快照变化」与「实测内容高度增长」分开：raw delta 只更新状态，live/terminal measured growth 是唯一自动到底写入者，同轮 geometry 不再重复触发 viewport 写入。现有 `followGeneration` 设置现在真实控制 live 和 terminal 自动跟随；关闭时保持历史浏览语义，显式到底仍可用。显式到底动画由单一短窗口持有，期间增长只合并为一次 pending replay，拖动、重置和离页沿同一任务取消。
- 模型会议的 `CouncilChatViewModel` 改为由 `AppShell` 唯一持有并注入唯一 `.council` destination；重复的 `.councilChat` route 已删除，避免两个页面生命周期竞争同一个 runtime attachment。离页待提问解除、后台讨论完成和归档语义未改。
- Markdown 仍实时解析并保留逐词淡入。无附件 TextKit 1 append 只更新纯文本 accessibility label，不再逐次扫描 attributed ranges；首次段落淡入继续保留，只有同一 block 因 TextKit 1/2 engine flip 重建时抑制整段重复淡入。冻结快照统一使用「恰好一个非空 Text part」规则，不再用 `toText()` 重建空行。
- 修复后的 Novel/Markdown/projection/wiring 定点套件为 116 passed、0 failed（`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-37-28-+0800.xcresult`），Council 为 26 passed、0 failed（`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-39-28-+0800.xcresult`），最终默认 Chat 门禁为 73 passed、1 expected skip、0 failed（`/tmp/amber-stream-scroll-audit/Logs/Test/Test-iosApp-2026.07.16_14-52-00-+0800.xcresult`）。长表格帧间隔 canary 在宿主负载约 24 时出现一次 outlier；未调整阈值，负载恢复后的隔离重跑与完整门禁均通过。Stable Debug generic iOS Simulator arm64 构建和 `git diff --check` 通过；三路只读复审均无 P0-P2。19:33 使用 Team `89QRFX9548` 完成当前工作区的 Debug 真机构建，已覆盖安装并由 `devicectl` 成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`，设备回读确认 `app.amber.ios` 1.0 (1) 已安装；本机 `codesign --verify --deep --strict` 对 Personal Team 证书链返回 `CSSMERR_TP_NOT_TRUSTED`，但设备安装与启动校验均通过。真机 120Hz、键盘/安全区转场与真实 provider 长输出仍需设备人工验证。

### 2026-07-16 Native Timeline terminal render continuity

- 真机确认逐行跟随主要来自「原生滚动容器」后，进一步定位到生成结束瞬间的独立跳变：最后一条 assistant 在流式中由缓存的 `ChatLiveTailModel` 承载，但 `isStreaming/isGenerationActive` 同时变 false 时，旧 guard 会先返回 nil，导致同一消息行从 live-tail 子树切换成普通 bubble 子树。外层 message id 没变，内部 Markdown/UIKit 渲染树仍会被替换一次。
- 修复只调整现有 model cache 的读取顺序：完成态若已有同 message id 的 live-tail model 就继续复用，并通过原有 `update` 写入最终消息、终态 generation 状态与 render state；只有从未创建过 model 的非流式完成行仍返回 nil。没有新增滚动几何补偿、节拍、动画或 terminal 延时。
- 新增 `testNativeTimelineKeepsLiveTailModelAcrossGenerationCompletion`。旧顺序下稳定失败，修复后与 `ChatMessageProjectionTests`、`NativeTimelineScrollCoreTests`、强制 `ChatStreamReplayTests` 合跑为 116 passed、1 expected skip、0 failed（`/tmp/amber-native-terminal-final/Logs/Test/Test-iosApp-2026.07.16_09-24-05-+0800.xcresult`）；默认路径 `ChatSwiftUIStreamReplayTests` 另跑 16 passed、0 failed（`/tmp/amber-native-terminal-final/Logs/Test/Test-iosApp-2026.07.16_09-27-24-+0800.xcresult`）；`git diff --check` 通过。当前 iPhone Air 在 `devicectl` 中为 unavailable，因此“生成结束不再跳一下”仍需下一次设备在线后装机目测；原生容器与表格流式块渲染同时开启的问题是另一条边界，本切片未宣称解决。

### 2026-07-16 streaming batch-scroll root cause correction and incremental TextKit repair

- 「四五行攒一批再上移」的根因已用可复现证据修正:不是 Markdown 解析节拍(8KB 全文 parse+convert 实测仅 ~4ms),也不是底部跟随状态机(事件驱动、无合并),而是**每次流式发布的主线程成本 O(全文)**:`ParagraphUIView` 整段替换 `attributedText` + `UITextView.sizeThatFits` 每次改动 text container 尺寸,把 TextKit 布局缓存全部作废。规模递增回放(真实 `ChatSwiftUIMessageList` + 12 字/48ms)证明批量随文档长度线性出现:模拟器 8KB 完美逐行、24KB 起 offset 推进次数减半、48KB 合并 2-3 行;真机快 2-4 倍到达,恰在 8-16K 字真实回答区间。走 `setParagraphContents` 的旧路径在 24K 单段落时每次发布 61.7ms(模拟器),真机即 4-5 行一批。
- 修复全部收在 vendor TextKit 1 路径内(vendor 默认 TK2 行为不变,仅 AmberAgent 的 `usesTextKit1ForAttachmentFreeText` 命中):TK1 视图创建时固定测量容器(`heightTracksTextView = false` + 有限大高度哨兵,`.greatestFiniteMagnitude` 是 TK1 病理路径);`sizeThatFits` 改为稳定容器 `glyphRange(for:) + usedRect` 增量测量;`setParagraphContents` 新增流式 append 快路径——新内容是旧内容的 attributed 前缀扩展时只把尾部 delta 插入 `textStorage`,进行中的逐词淡入不再被每次发布打断(动画只升不降);字符或属性被 speculative rewrite 改写时自动回退整段替换。24K 单段落每次发布从 61.7ms 降到 ~10ms,增量测量与全量布局结果逐点相等。
- 红→绿门禁:`ParagraphStreamingAppendTests` 用机器无关的成本比值断言(增量发布必须比同内容全量布局快 2.5 倍以上;修复关闭时实测比值 1.87 必红,修复后 ~4x),另含 append 前缀判定、rewrite 回退与增量==全量等价契约。`ChatSwiftUIStreamReplayTests` 新增 24KB viewport 跟随节拍 canary(直接断言 offset 推进次数与幅度,不再只看字符长度);此前发现 24KB 阈值在 M 系列模拟器上对旧代码不构成稳定红区,故机制红绿判定以比值门禁为准。
- 最终验证:`ParagraphStreamingAppendTests` 7 passed、`ChatSwiftUIStreamReplayTests` 16 passed、强制 `ChatStreamReplayTests` 16 passed + 1 expected skip、`ChatMessageProjectionTests` 71 passed、vendor `SwiftStreamingMarkdownTests` 全套 68 passed(含快照),`git diff --check` 通过。真机 120Hz 长文手感仍需装机后人工观察;此前 TextKit 1 切换只降低了单次全量测量常数、未改变 O(全文)/次的量级,故真机无感——本轮把量级降为 O(delta)/次。

### 2026-07-16 long-prose streaming line-growth repair

- 真机“四五行攒成一批再上移”的根因不在 provider 节拍或底部跟随动画，而在增长中的长段落布局：约 8KB 的同一段落每次更新走 TextKit 2 全高测量时，主线程单次约需 47ms；对照 TextKit 1 约 15ms，且高度始终按 24-25pt 单行增长。
- 最终方案没有手写第二套 Markdown 分块或语法判定。真实 Markdown parser、`48ms / 12 Character` 展示节拍、`80ms` 语义底锚动画和逐词淡入均保持不变；AmberAgent 仅对无 `NSTextAttachment` 的 heading/paragraph 显式使用 TextKit 1，引用附件与行内公式继续 TextKit 2，vendor 默认仍为关闭。fallback 使用与 parser 首段一致的稳定 id，避免冷启动随机 remount。
- 生产链回放使用 12 段稳定前缀加约 7.5KB 的增长尾段，连续 60 次按 `12 字 / 48ms` 更新；可见字符发布次数、合并上限、单行高度、底部欠账和横向漂移均有直接断言。`ChatMessageProjectionTests` 71 passed，完整 `ChatSwiftUIStreamReplayTests` 15 passed，强制 `ChatStreamReplayTests` 16 passed、1 expected skip；性能基线长矩阵曾被系统 signal kill，隔离重跑其唯一未完成的端到端项通过。当前精简方案已完成真机签名校验、覆盖安装并成功启动；真实 provider 的长文本手感仍需人工观察。

### 2026-07-15 remote streaming baseline and local novel integration

- 当前分支已从 `7e0d6a798` 原地 fast-forward 到远端 `9773cb802`，没有 commit、push、stash、reset 或 checkout。合并前 52 个本地修改路径均保留为 unstaged；9 个同路径改动以旧 HEAD 为 base 做三方整合，只有 `PROJECT_STATE.md` 与 `NovelCreationViewModel.selectProject` 需要文本裁决。
- `selectProject` 继续先拒绝 operation/自动同步期间的跨项目选择，再发布远端 `selectionIntentToken`；远端 `selectBranch` 的目标预检、active run 中断、owner/intent 复验和失败恢复完整保留。同项目刷新 race canary 验证旧 branch intent 会在中断前失效，同时不放开跨项目并发切换。
- 组合态的 `NovelCreationViewModelTests`、`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NovelCreationPresentationTests`、`IOSNovelCreationWiringTests`、`IOSAgentToolEngineTests` 与 `IOSSearchExecutorTests` 在 iPhone 17 Pro Simulator 为 168 passed、0 failed、0 skipped（`/tmp/amberagent-9773-novel-combined.xcresult`）；强制 `ChatStreamReplayTests` 为 16 passed、1 expected skip、0 failed（`/tmp/amberagent-9773-chat-replay.xcresult`）。三路只读复审没有发现 P0/P1，`git diff --check` 通过。
- 最终 Stable Debug arm64 真机包使用 Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，`codesign --deep --strict` 通过，已覆盖安装并由 `devicectl` 成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`；进程回读确认主应用与 Activity Widget 均在运行。真实 120Hz 长流、复杂 Markdown、底部跟随和小说 provider E2E 仍需当前整合包的人工操作证据。

### 2026-07-15 iOS Live Activity / Dynamic Island task beacon

- Live Activity payload 已从原始状态文案、工具名、三步轨道和推测权重收敛为有限语义：任务类型、phase、stage、可选真实 metric、可选安全 action 与 run/conversation 归属。不可测任务不再显示百分比或进度环，Activity payload 不携带 prompt、模型名、URL、文件路径或工具参数。
- Minimal、Compact、Expanded 与 Lock Screen 已按各自信息容量独立重组：Compact 只保留身份与一个最高优先级事实；Expanded/Lock Screen 最多一个导航链接；等待、重连、过期、完成、失败、取消均有非动画、非仅颜色的明确状态。中英文资源、VoiceOver 摘要和 4×8 Preview 状态矩阵已加入，两套 Widget target 编译通过。
- Activity 深链限定为 `amber://activity/<run-id>?conversation=<id>&focus=<task|confirmation|result>`。URL 只会选择本地存在的 run-owned conversation 并进入现有 Chat，不批准或执行操作；缺失归属、未知会话、额外参数与非法 focus 均 fail closed。控制器统一 staleDate、relevance、相同更新节流、启动恢复、重复 Activity 收口和终态锁屏保留策略。
- 运行所有权已经闭环：启动恢复每个 AppShell 只执行一次，只保留具有持久后台任务 owner 的 Activity；普通结束在 await 前进入 ending 集合，后台任务冷启动可从 payload 恢复并 adopt。success、provider/save failure、expiration、cancel 与 missing-payload 共享排他的 terminal owner；系统过期先释放 task map/active owner 并完成 BGTask，若保存已在途则以真实落盘结果决定 run/Activity 终态，不再出现“答案已保存但灵动岛失败”的双终态。删除会话只在磁盘提交后清理 owner；派生 index 会过滤并尽力修复已无实体文件的摘要。
- Activity/deep-link/selected-file/recovery 的 24 项闭环套件通过；最终 expiration、save-failure ownership、BGTask 单次完成与删除失败 6 项定点通过，`JsonConversationStorageTest` 全类通过。Stable 测试目标和 Experimental GPL generic Simulator arm64 构建通过；`git diff --check` 通过。更宽聚合曾出现一个测试 worker 被系统终止，以及一条与本轮无关的既有后台 completion/前台消息合并断言失败，未伪装为全量绿。当前 Debug 包已于 19:30 覆盖安装并成功启动到连接的 iPhone Air；这只证明签名、装机和启动，32 组预览视觉检查、真机 Dynamic Island、Always-On、VoiceOver、Reduce Motion 与跨进程点击仍需设备证据。服务端 push-to-start 仍是需要独立 ActivityKit token 协议的后续项目。

### 2026-07-15 iOS streaming presentation and scroll convergence

- 标准 Chat 只补齐一条缺失契约：用户主动拖动后，距底部 `96pt` 内恢复跟随；真实“已到底”仍使用既有 `40pt` 阈值。现有 `48ms / 12 Character` 展示节拍、Markdown 增量渲染、逐字淡入和 measured-growth 滚动调度未改。曾对几何任务做合并实验，但长表格回放出现可重复性能回退，已撤销该实验，避免用新调度层掩盖问题。
- 模型会议改为 FIFO `AsyncStream` 事件入口与单一 `48ms` 主线程展示会话，完成、失败、取消都以权威快照精确收尾；首个 assistant chunk 前不再把内部 user prompt 当成生成文本。每轮使用独立 generation；离页会解除既有/未来的提问 continuation，后台讨论仍可正常完成归档。消息区继续使用原生 `ScrollView`、永久底部 sentinel 和 `80ms` 线性跟随；异步 Markdown 增高、键盘导致的 viewport 缩小、用户近底松手及 canceled single-flight owner 的 pending 接力都走同一个语义底锚，不新增滚动协调器。活动讨论被归档或重置前会按 drain → terminalize → persist/archive → replace 收口。
- 小说 Session 增加 keyed `48ms` 展示缓冲，delta、replacement、Quick Start 隐藏 JSON 与 terminal snapshot 共用同一所有权边界；终态始终回到领域层权威文本。实时与终态 Markdown 的异步正向增高分别进入既有 live/terminal 跟随事件；用户在 `96pt` 近底区松手会立即提交一次语义到底。跨分支时先只读验证目标，再线性化检查 project/source/run ownership 后中断旧 durable run；目标快照只读一次，中断后只刷新 project metadata，刷新失败则一次性恢复源分支权威快照。独立的 selection intent token 保证后发项目选择不会被内部 terminal refresh 或旧 branch 请求覆盖。
- 最终绿色门禁拆为两个分片：其余 6 个受影响套件 161 passed、0 failed（`Test-iosApp-2026.07.15_19-18-09-+0800.xcresult`），强制 `ChatStreamReplayTests` 16 passed、1 expected skip、0 failed（`Test-iosApp-2026.07.15_19-16-06-+0800.xcresult`），合计 177 passed、1 expected skip。一次 178 项长进程在 176 passed 后由系统 signal kill 单个 Chat 回放，独立完整重跑已通过，未改 Chat 或放宽断言。三路最终只读审查均为 PASS、无剩余 P0-P2；Stable Debug generic iOS Simulator arm64 构建和 `git diff --check` 通过。当前 Debug 包已使用 Personal Team `89QRFX9548` 完成真机签名、覆盖安装并由 `devicectl` 成功启动到 iPhone Air；120Hz 长流手感、键盘/安全区、复杂 Markdown 异步增高、横向代码/表格手势与真实 provider 长输出仍需实际操作验证。

### 2026-07-15 novel P1/P2 review closure

- 分支状态改由 checkpoint 语义推导：即时收录 checkpoint 与从它 Fork 的分支保持 `needsSync`；读取旧项目或项目包时只单向归一化这类历史错误状态，不改 schema，也不放宽新写入的 staged equality。旧版 direct-parent undo 仍可读取，新产生的 undo 强制走当前语义目标；收录后自动同步在气泡与分支页表现为一次「撤销上一次收录」，手动改写漂移仍明确阻止。
- 候选正文补齐 retryable sync bridge：同步重试成功后仍可收录此前生成的正文；收录、同步、撤销后可 clone 并再次收录，同时继续拒绝早于同步游标或真实 base 漂移的候选。Quick Start v2 固定 Prompt receipt 恢复历史校验；手动剧情同步在没有可靠事实时沿用原摘要/大纲，所有 finalize 入口使用同一 derived-state 校验，不能借零事实改写派生状态。
- 通用工具循环只过滤已经完成工具的空回显，保留同回合正文和新工具调用；自动剧情同步的 activity、busy、选择、新建、导入与后台租约按真实项目生命周期收口。完整项目包补回系统导出入口，Markdown 导出不再中断生成，项目加载失败页提供原因与重试。
- 三路 subagent 对领域/历史兼容、模型工具循环、UI 生命周期复审后均未发现剩余 P0-P2。最终 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests`、`IOSAgentToolEngineTests`、`IOSSearchExecutorTests` 在 iPhone 17 Pro Simulator 为 446 passed、0 failed、0 skipped；结果位于 `/tmp/amber-novel-p1p2-integrated/Logs/Test/Test-iosApp-2026.07.15_01-34-32-+0800.xcresult`，`git diff --check` 通过。Stable Debug arm64 真机包随后使用 Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，`codesign --deep --strict` 通过，已覆盖安装并由 `devicectl` 成功启动到 iPhone Air `94918570-0680-5B93-8E38-7E6B355D4426`。真实 provider 与交互 E2E 仍待用户操作，P3 明确未纳入。

### 2026-07-15 novel reader and project list detail polish

- 章节阅读器顶栏把字数收回到「第 N 章」后方并保留 12pt 间距，不再用弹性空白把两项拉到标题区两端。右上角菜单在 iOS 26 隐藏系统 toolbar 的共享跑道背景，仅保留现有 40x40 圆形 Liquid Glass 层；低版本继续使用同一圆形 fallback，没有增加第二层玻璃。
- 小说项目列表的左右内容 inset 从 16pt 收到 22pt，项目图标由 32/16pt 调到 36/18pt，并删除整行已经可点击时多余的右侧箭头；侧滑、长按重命名和删除入口保持不变。
- 项目重命名保存前先结束输入法组合态，并在主线程下一轮读取 SwiftUI 已提交的最终文本再执行原有 rename action；保存按钮不再因旧名称判断而阻止用户提交尚在组合中的最后一个拼音候选。没有改项目 schema、reducer 或持久化链路。
- 红测试先复现旧顶栏、列表和重命名接线不满足契约；修复后 `IOSNovelCreationWiringTests` 与 `NovelCreationPresentationTests` 在 iPhone 17 Pro Simulator 为 41 passed、0 failed、0 skipped，`git diff --check` 通过。Stable Debug arm64 真机包已自动签名并明确 `BUILD SUCCEEDED`，`codesign --deep --strict` 通过，已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝。最终圆形、列表光学对齐和中文拼音末字提交仍需解锁后真机目视/输入确认。

### 2026-07-14 model-independent novel discussion search

- 搜索能力现在属于小说「讨论」Agent，而不是某个模型的特例。启用全局「网页搜索」后，Grok Web 继续使用原生搜索；OpenAI、Claude 与 Codex 的讨论请求声明 Chat 同款 `search_web` / `scrape_web`，进入现有 `IOSAgentToolEngine`，执行 Chat 的真实搜索器并把结果回填给模型继续回答。
- `AppShell` 把同一个 `ChatToolRuntime` 注入小说 composition，没有复制搜索 provider、fallback 或第二套网络实现。全局关闭网页搜索时，所有模型都回到无工具讨论；写一段、写整章、Quick Start、润色和剧情状态同步始终不声明搜索工具。
- 工具循环的文本、完成、上游失败、取消和后台继续仍收口到既有 `NovelLiveModelAdapter` 与 durable generation lifecycle；最多 4 轮，避免模型反复搜索不结束。搜索执行失败会作为工具结果返回模型，上游模型失败则进入小说原有可重试失败终态。
- 红测试先确认非 Grok 讨论的工具声明为 0；修复后适配器、真实 Chat 搜索执行器、通用工具循环、App 装配与小说生成生命周期共 96 tests passed、0 failed。`git diff --check` 通过。Stable Debug arm64 真机包已使用 Personal Team `89QRFX9548` 完成自动签名并明确 `BUILD SUCCEEDED`，`codesign --deep --strict` 通过，已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝。真实 provider 搜索仍需解锁后分别用已配置的 OpenAI、Claude、Codex 与 Grok 模型验证。

### 2026-07-14 manuscript reader title deduplication

- 章节阅读器不再在正文 Markdown 上方重复渲染一组章节名与字数；正文现在直接从自身保存的 Markdown 内容开始，因此带 H1 的正式正文只在正文内保留一次标题，同时顶栏继续保留便于导航识别的章节名。
- 字数移入顶栏第一行，与左侧「第 N 章」同排并在 180-220pt 的中心标题区域右对齐；第二行仍显示章节名。前后章、版本、编辑、润色和正文内容均未改变。
- 红测试先确认 `currentChapterTitle` 在 Reader 内被渲染两次，修复后转绿；`IOSNovelCreationWiringTests` 与 `NovelCreationPresentationTests` 在 iPhone 17 Pro Simulator 为 39 passed、0 failed、0 skipped，`git diff --check` 通过。Stable Debug arm64 真机包增量构建成功，`codesign --deep --strict` 通过并已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝，顶栏最终宽度待解锁后目视确认。

### 2026-07-14 Grok discussion web search and actionable stream failure

- 真机项目「重回大唐」的失败 run 已从项目文件取证：模型先输出「我先查核名单与关键人物背景」，约 6 分钟后以 `provider_stream_failed / upstream request failed` 结束；没有工具拒绝事件，partial 回复已按既有 durable run 路径保存。根因边界是小说 Grok 请求此前始终传 `disableSearch: true`，所以该句只是模型描述计划，并未实际获得搜索能力。
- 该切口先接通了小说「讨论」的 Grok Web 原生搜索并继续关闭 Grok Web 记忆；随后已由上方 model-independent slice 扩展为 OpenAI、Claude、Codex 复用 Chat 搜索工具循环。写一段、写整章、Quick Start、润色和剧情状态提取/重建仍保持搜索关闭。
- `provider_stream_failed` 与 `grok_web_stream_failed` 现在明确显示「模型上游服务在生成过程中中断，已保留当前回复，可以重试」，不再把真实上游中断泛化成无原因的暂时失败。
- `NovelLiveModelAdapterTests` 与 `NovelCreationPresentationTests` 定点为 35 passed；加上生成生命周期、Session ViewModel 和设置回归共 122 passed、1 failed，唯一失败是既有 `IOSSettingsWiringTests.testStreamingBlockMarkdownToggleIsConsumedByTableBlockRenderer` 仍查找旧 Markdown 表格渲染源码字符串，单独重跑同样失败，与小说/Grok 改动无关；相关 Grok payload test 单独通过。`git diff --check` 通过。Stable Debug arm64 真机包自动签名构建明确 `BUILD SUCCEEDED`，`codesign --deep --strict` 通过并已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝。真实 Grok 搜索结果仍需解锁后在讨论模式发起需要时效知识的问题验证。

### 2026-07-14 manuscript directory framing and chapter titles

- 「正文」目录不再依赖 system plain List 的行背景，改为页面背景上的单个紧凑章节组：使用现有 `surface` 与 `borderSoft` 形成明确外框，行高收紧、分隔线内缩，并删除整行可点击时重复出现的右侧箭头。
- 章节显示标题优先保留用户明确编辑过的标题；旧章节若仍是「第 N 章」占位名，会从正文首个 Markdown/章节标题行识别真实题名，因此现有「第一章 破庙里的活人气」可直接显示为「破庙里的活人气」。同一显示规则也用于章节阅读器和收录目标名称，不迁移或重写旧正文。
- 整章 Prompt 升级为 v3，要求在同一次正文生成中给出一个 Markdown H1 章节题名；收录 Sheet 直接从候选正文预填章节标题，仍允许用户编辑。没有新增标题总结请求、provider 调用、后台任务或第二条持久化链路；v1/v2 Prompt 文本继续保留供历史 receipt 校验。
- `NovelCreationPresentationTests`、`IOSNovelCreationWiringTests`、`NovelPromptCatalogTests` 为 43 passed；`NovelGenerationReducerTests`、`NovelDocumentValidationTests`、`NovelCollectionTests`、`NovelGenerationLifecycleTests` 为 80 passed，均 0 failed、0 skipped；`git diff --check` 通过。Stable Debug arm64 真机包自动签名构建成功并通过 `codesign --deep --strict`，已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝，最终目录视觉待解锁后确认。

### 2026-07-14 manuscript reader directory simplification

- 「正文」Tab 现在只承担阅读入口：首屏直接显示「目录」和当前分支的正式章节，点击章节沿既有原生 push 进入阅读器；不再展示分支同步卡片、Quick Start 状态、技术性正文说明或不可交互的创作统计。
- 「主线已同步」「对话消息」「存档点」等内部状态已从正文页移除。同步失败与重试继续由创作页现有 Banner 负责，版本历史、编辑和整章润色继续留在章节阅读器右上角菜单，没有删除领域能力。
- 目录改为紧凑的原生 plain List，章节标题直接使用保存的标题，不再拼成「第 1 章 · 第 1 章」；保留章节序号、字数和进入提示。新增 reader-first wiring 契约，`IOSNovelCreationWiringTests` 在 iPhone 17 Pro Simulator 为 15 passed、0 failed、0 skipped，`git diff --check` 通过。
- 章节阅读器右上角菜单删除叠在系统 toolbar glass 内部的第二层 `ComposerDockCircleGlass`，只保留一层圆形 toolbar 按钮。底部整块相连的章节导航拆为两个独立 40pt 高玻璃胶囊，取消中间分隔线与 360pt 底板，水平分置并将上下额外留白从 16pt 收至 2pt，使控件更小、更靠近底部安全区。
- Stable Debug arm64 真机包完成自动签名构建和 `codesign --deep --strict` 校验，已覆盖安装并由 `devicectl` 成功启动到设备 `94918570-0680-5B93-8E38-7E6B355D4426`。

### 2026-07-14 retryable state sync no longer freezes drafting

- 剧情同步的 `.pending` 与已经失败等待重试的 `.retryable` 现在有明确不同的创作语义：真正运行的同步继续保持单项目事务串行，并展示阶段、等待秒数和已提交分段进度；失败态不再伪装成仍在运行。
- 同分支只有 retryable `manualSync` 时，用户可以继续讨论、生成一段或生成整章候选；生成链路的 ViewModel、注入规划、领域 reducer 与历史气泡重试投影使用同一条阻塞规则，不只是在 UI 上放开按钮。候选正文仍不能收录或润色，直到剧情状态重试成功，避免正式正文与派生状态继续分叉。
- 失败提示保留模型错误原因，并明确说明「仍可继续讨论或生成正文；收录前请重试」。生产调用链红测试先复现 `canSend == false`，修复后验证新候选可完整生成、原 retryable pending 保留且收录仍被阻止；相关 6 个测试类在 iPhone 17 Pro Simulator 为 121 passed、0 failed、0 skipped。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名构建成功，`codesign --deep --strict` 通过，已覆盖安装并由 `devicectl` 成功启动到设备 `94918570-0680-5B93-8E38-7E6B355D4426`。

### 2026-07-14 novel discussion and prose intent differentiation

- 输入区仍只保留一个原生创作方式 `Menu`，菜单按「构思 / 写正文」分组，用户可一步选择「讨论 / 写一段 / 写整章」；没有增加常驻分段控件、第二个玻璃按钮或 Sheet。三种状态分别使用对应占位文案，切到讨论不会覆盖上次正文颗粒度。
- 讨论 Prompt 升级为小说策划与编辑行为：针对剧情因果、人物欲望与动机、关系、世界规则和节奏给出具体判断；信息确实影响建议时只问一个关键问题，提供 2-4 个有取舍的选择、明确推荐项，并允许用户直接输入自己的方案。讨论现在同时注入当前正文尾部，不再只凭资料和剧情状态泛谈。
- 「写一段」明确只完成一个场景或局部剧情节拍，不擅自收束整章；「写整章」明确要求完整章节弧线、持续推进和结尾落点。候选气泡与主操作分别显示「正文片段 / 完整章节」和「收录到本章 / 作为新章收录」，但两者仍复用同一生成、后台持久化、候选与收录事务链路。
- 新 Prompt 使用 v2，文档校验按 receipt 中的历史版本解析固定 Prompt 证据，保留 discussion、continuation 和 whole-chapter v1 文本，因此现有项目不会因升级 Prompt 被判为损坏。12 个相关测试类在 iPhone 17 Pro Simulator 为 210 passed、0 failed、0 skipped，`git diff --check` 通过。
- Android 普通 Chat 已有完整 `ask_user` 工具与选项/自由输入卡片；iOS 普通 Chat 和小说模型请求尚无通用工具调用链，只有模型议会专用的自由文本提问 Sheet。本轮没有复制一套伪工具或引入暂停/恢复状态机，讨论问题继续通过普通聊天回复与输入框闭环。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名构建成功，第二次传输已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝。

### 2026-07-14 novel state sync leniency and first-chunk feedback

- 真机项目「大明」再次取证确认：最新重试已创建新的模型请求 receipt，但第一段结果因模型改写了 evidence、未能逐字出现在正文中而在提交前失败，所以 `manualSyncProgress` 仍为空；界面显示 0% 并非请求未启动，而是此前百分比只能来自已经原子提交的正文分段。
- 手动剧情同步不再因一条不匹配的事实依据否决整次重建。能够在当前正文分段中定位的事件、人物状态、关系、伏笔和设定建议继续进入正式事实；模型改写或杜撰依据的单条记录会被丢弃，剧情摘要和分支大纲仍可更新。未解析人物也按保留下来的事实重新计算，避免模型冗余字段再次阻断同步；正式正文始终不被模型结果改写。
- 结构化输出只放宽模型常见外壳：接受单个 JSON 对象外的 Markdown 代码围栏、思考/说明文字，仍拒绝多对象、截断 JSON、重复键、未知/缺失字段、错误类型和语义冲突，不做内容修补或二次模型请求。
- 首段尚未提交时不再展示会长期停住的 0% 进度条，改为明确显示「正在分析第 N 段」、真实等待秒数和单段 60 秒上限；有耐久分段进度后才显示真实正文百分比。相关 structured output、manual sync、fact lifecycle、document validation、Prompt、presentation 和 session 回归在 iPhone 17 Pro Simulator 为 132 passed、0 failed、0 skipped，`git diff --check` 通过。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，已覆盖安装到设备 `94918570-0680-5B93-8E38-7E6B355D4426`；自动启动仅因设备锁定被系统拒绝，解锁后可在「大明」项目直接重试现有待同步操作。

### 2026-07-14 novel state sync progress visibility

- 创作页在剧情状态同步实际运行时展示紧凑进度区：百分比来自 durable `manualSyncProgress.consumedCharacterCount` 与正式正文总字符数，不使用随时间增长的虚假完成度；分块完成后同时显示已完成段数。
- 当前模型分段尚未提交时，每秒显示该段真实等待时间，并明确提示单段请求最长 60 秒。重试只读取当前 attempt 的 generation receipt 作为计时起点，不会把上一次失败的旧时间带进来；终止后进度观察任务随原操作 owner 一起清除。
- 没有改变同步 Prompt、分块算法、provider、项目 schema、重试或 60 秒超时。自动同步与显式重试两条生产路径均有进度发布契约；6 个受影响测试类在 iPhone 17 Pro Simulator 为 115 passed、0 failed、0 skipped，`git diff --check` 通过。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，已覆盖安装到 iPhone Air；自动启动只因设备锁定被系统拒绝，因此进度区最终视觉与真实 provider 百分比变化仍需解锁后操作确认。

### 2026-07-14 retryable novel state sync entry repair

- 真机项目文件确认进入「大明」时没有活跃正文 run，只有一条 `manualSync`、`retryable` 的失败记录，最近错误为模型返回 malformed JSON。此前 workspace appearance 把 `pending` 和已经失败的 `retryable` 都自动恢复，导致每次进入都重新请求，并把阻塞原因泛化成「有正文操作正在处理」。
- 自动恢复现在只接续真正未完成的 `pending`；`retryable` 保留原错误并等待用户明确点击「重试」，不再因进入项目而重复请求。Session 投影把 `manualSync` pending 显示为「剧情状态同步未完成」，collection pending 才继续使用正文操作提示；现有 malformed JSON 失败显示可执行的中文说明。
- 没有修改项目 schema、pending 记录、provider、60 秒上限或手动重试调用链。红测试先复现了自动重试与错误 blocker；`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NovelFactTransactionLifecycleTests`、`IOSNovelCreationWiringTests` 及新增 presentation 定点均在 iPhone 17 Pro Simulator 通过，`git diff --check` 通过。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，第二次设备传输成功覆盖安装到 iPhone Air；自动启动因设备锁定被系统拒绝，因此进入项目后的最终提示仍需解锁后人工确认。

### 2026-07-14 novel state synchronization end-to-end closure

- 真机项目「大明」的反复提示已用持久化文件定位：分支是 `needsSync`，同一条 `manualSync` 已落成 `retryable`，最近五次请求都在 60 秒内没有首个有效输出；单次估算输入 17,712 tokens，其中最近 12 条讨论约 14,195 tokens，而正式正文约 2,904 tokens。界面每次进入展示的是同一条遗留任务，不是每次新建失败。
- 手动剧情状态重建现在只使用正式正文、现有剧情状态和项目资料，不再把最近聊天消息重复注入；durable pending 的 session cursor 仍原样保留给最终 checkpoint，未改变项目格式、提示词、结构化输出或 60 秒超时。
- workspace 现有单飞调度器只自动恢复同一分支唯一且真正未完成的 `.pending` `manualSync`，直接走既有 `retryPending`，不会制造第二条任务；失败后的 `.retryable` 等待用户明确重试。项目首次真实出现、切分支和从后台回到 active 都走同一入口。每次触发最多一次 provider 请求，没有计时器、退避器或内部重试循环，legacy `collection` pending 仍不自动执行。
- 后台 expiration 现在会同时取消润色、手动状态同步和 pending 重试；同步任务沿既有失败收口把 pending 持久化为 `retryable`。成功仍删除 pending 并发布 `synchronized`，失败保留正文和精确 `lastError`；自动执行期间隐藏无意义的重试 Banner，执行失败后再显示真实原因与手动重试。
- `NovelFactTransactionLifecycleTests`、`NovelSessionViewModelTests`、`IOSNovelCreationWiringTests` 三组闭环回归，以及 `NovelManualEditSyncTests`、`NovelGenerationLifecycleTests`、`NovelCreationViewModelTests`、`NovelSessionReplayTests`、`NovelInjectionPlannerTests`、`NovelPolishTests` 六组扩展回归均在 iPhone 17 Pro Simulator 通过；`git diff --check`、Stable Debug generic iOS arm64 自动签名构建和 `codesign --deep --strict` 均通过。最终 `app.amber.ios` 已于 20:05 覆盖安装并由 `devicectl` 成功启动到连接的 iPhone Air。真实 provider 对设备上现存 pending 的最终成功结果仍需用户进入「大明」触发后确认，不把构建/模拟器证据冒充真机 E2E。

### 2026-07-14 novel navigation and long-session projection performance

- 真正的冷路径不对称已经用 160 条长会话样本复现：项目文件约 572 KiB、消息正文约 196k 字符。旧实现先在列表点击任务内读取项目和分支、发布完整 selection，再 append `NavigationStack` path；逐帧录制在系统转场刚开始后出现约 `251ms` 无画面更新，而 pop 不需要再次读取与排版，所以返回一直更顺。
- 项目行现在立即进入原生 `NavigationStack`。目标页在真实 UIKit `viewDidAppear` 前只呈现轻量读取壳，并沿用列表 summary 显示项目名；项目/分支读取、完整 selection 发布和长 Markdown 树挂载都延后到系统 push 完成后。该 `.task(id: hasCompletedInitialNavigation)` 随页面生命周期自动取消，不使用固定延时、自定义转场或几何补偿；两页设置按钮仍共享同一 `ToolbarItem` ID 和组件身份，保留 iOS 26 原生 toolbar morph。
- 自动剧情状态恢复不再与 push 争抢主线程：会话绑定不主动调度同步，workspace 只在真实 appearance 且 selection 完整后启动现有的单飞同步。分支变化仍走同一调度器，没有增加第二套恢复状态。
- `NovelSessionPresentation` 的逐消息重复扫描改为一次局部索引；500 条消息/候选/pending 的 Debug 模拟器投影约 `0.004s`。`NovelSessionViewModel` 进一步按项目、分支、会话 revision 与 transient tail 建立内存投影缓存，未变状态不再重复投影；它不是持久缓存，不改变领域权威数据。
- 冷进入默认只挂载最近 4 条历史消息（约两轮对话），更早记录由一个明确入口每次追加 24 条；用户正在阅读旧记录时保持语义锚点，不用 y 坐标补偿。该窗口只减少 SwiftUI/Markdown 首屏布局，完整会话仍保存在项目并可逐页展开。
- 相同 160 条样本的最终冷启动录制中，原生 push 从首个画面变化起连续执行，轻量壳随转场进入，长正文在转场结束后出现；旧路径的早期 `251ms` 静止段不再出现。Simulator 的 SwiftUI/Hitches Instruments 明确不支持该运行目标，因此未把录屏时间戳伪装成真机 CPU trace；120Hz 最终手感仍以连接设备复测为准。
- 最终 `IOSNovelCreationWiringTests`、`NovelCreationViewModelTests`、`NovelSessionViewModelTests`、`NovelSessionReplayTests` 与完整 `ChatStreamReplayTests` 在 iPhone 17 Pro Simulator 为 98 executed、1 个缺少真实录制夹具的预期 skip、0 failures；最终结果包为 `Test-iosApp-2026.07.14_18-24-55-+0800.xcresult`。Stable Debug arm64 真机包已使用 Personal Team `89QRFX9548` 完成自动签名并明确 `BUILD SUCCEEDED`；最终 `app.amber.ios` 已于 19:35 覆盖安装到连接的 iPhone Air，并由 `devicectl` 明确返回启动成功。真实 120Hz toolbar morph 手感仍需用户在当前包内操作确认。

### 2026-07-14 novel settings root and native toolbar convergence

- Session 快捷入口与系统「设置 > 高级功能 > 小说创作」现在都进入同一个小说项目列表，不再让高级功能入口绕过主界面直达模型设置。
- 小说项目列表统一承担三项一级动作：右上角导入与设置，右下角带文字的原生玻璃「新建」主按钮；空列表继续直接展示新建和导入，不重复显示悬浮按钮。
- 项目列表与项目工作区的齿轮现在都进入完全相同的 `NovelCreationSettingsView` 根页：只展示全局创作模型、剧情同步模型和「项目管理」。之前按入口切换 global/project scope 的分支已删除，不再出现同名设置页内容不一致。
- 设置页恢复全应用一致的 `NavigationStack` 默认 push；已删除齿轮 `matchedTransitionSource`、跨页 namespace 和整页 `.navigationTransition(.zoom(...))`，不再从小齿轮突兀放大整张设置页。
- 项目列表的导入与设置从一个 `ToolbarItemGroup` 拆成两个原生 `ToolbarItem`，中间使用 iOS 26 的 `ToolbarSpacer(.fixed)` 划分 Liquid Glass 表面；项目内齿轮保持同样的位置、图标和可用状态，让系统在列表 push 到项目时自动完成 toolbar morph，导入按钮独立退场。项目载入期间齿轮也不再短暂变灰后跳回可用态。
- 单项目模型覆盖、重命名、分支管理和正文导出统一下沉到「项目管理 > 具体项目」二级页，复用既有模型选择器与项目编辑组件，没有新增另一套设置 Sheet。
- XcodeGen 已为新增二级页重新生成工程。最终入口、设置、项目配置、ViewModel 和项目包 48 项回归在 iPhone 17 Pro Simulator 为 48 passed、0 failed、0 skipped；`git diff --check`、Stable Debug generic iOS arm64 自动签名构建与 `codesign --deep --strict` 均通过。最终 `app.amber.ios` 已覆盖安装并启动到连接的 iPhone Air；系统 toolbar morph 的最终弹性节奏、普通设置 push 手感和项目导出仍需用户在当前包内操作确认。

### 2026-07-14 novel creation dual-model settings

- 小说项目工作区右上角新增系统齿轮设置入口，项目设置集中「创作模型 / 剧情同步模型」、项目名称、当前分支和正文导出；点击顶部项目名仍只进入写作偏好与本次上下文，不再承担项目管理。
- 系统「设置 > 高级功能」新增「小说创作」，可分别设置全局创作模型和剧情同步模型；创作模型提示优先长文与创造力，剧情同步模型提示优先稳定、便宜和结构化输出。项目内可分别覆盖，也可回退为「跟随小说默认」。
- 领域运行时真正区分两类请求：讨论、续写、整章和润色使用创作模型；正文收录后的剧情状态重建与手动同步使用剧情同步模型。旧项目缺少同步模型字段时按全局默认读取，不迁移、不改写既有项目数据。
- 本次相关 9 组回归在 iPhone 17 Pro Simulator 为 149 passed、0 failed、0 skipped，剧情同步补充定点为 13 passed、0 failed；Stable Debug generic iOS arm64 自动签名构建和 `git diff --check` 通过。最终 `app.amber.ios` 包已覆盖安装到连接的 iPhone Air，并由 `devicectl` 明确返回启动成功；双模型设置的真实选择与请求分流仍需用户在当前包内操作确认。扩展设置回归另发现一条既有 Markdown 渲染设置 wiring 断言失败，与本轮文件和行为无关，未在本轮顺手修改。

### 2026-07-14 novel context controls and hierarchical writing sheet

- 输入聚焦后的创作方式 `Menu` 移到右侧，与 `ContextRingButton` 组成紧邻控件组；按钮只显示当前「讨论 / 续写 / 整章」文字，不再添加会改变视觉重心的下箭头。模型选择仍单独留在左侧。
- 小说 Context Ring 改为与标准 Chat 完全相同的 `ComposerContextPanel` 原生 Popover，只展示消息数、token、速度和缓存等上下文统计；不再打开小说资料注入 Sheet。
- 点击顶部项目名改为打开「创作设置」，以「写作偏好 / 上下文注入」两个 Tab 合并原写作要求、章节风格和本次注入设置。项目名称、当前分支和导出正文不再出现在这个 Sheet。
- 上下文注入首页只展示人物角色、世界观、剧情大纲、其他资料四个分类与数量；具体资料通过 `NavigationStack` 进入二级列表后选择「按默认 / 本次加入 / 本次排除」，避免人物和设定增多后把根 Sheet 拉成长列表。预计上下文也下钻到独立页面。
- `IOSNovelCreationWiringTests`、`NovelSessionViewModelTests` 与 `NovelCreationViewModelTests` 在 iPhone 17 Pro Simulator 为 60 passed、0 failed、0 skipped；`git diff --check` 与 Stable Debug arm64 真机构建通过。最终包已覆盖安装并由 `devicectl` 成功启动到配对的 iPhone Air；Popover 锚点、两档 Sheet 和二级列表的真机手感仍需用户操作确认。

### 2026-07-14 automatic novel state synchronization

- 正文收录和手动改写仍先完成本地原子保存并立即返回；保存成功后由 workspace 自动启动同一分支的剧情状态重建，不再要求用户理解或点击「整理资料」。重新进入一个已经标记 `needsSync`、且没有真实 pending 的旧分支时，也会自动接续同步。
- 自动任务按单一 workspace 串行并合并重复目标，先让保存后的界面刷新收口，再读取项目和分支的权威快照；已有生成、写入或 durable pending 时不制造第二个并发事务。成功后静默恢复 `synchronized`；失败不回滚正文、不弹全局错误，只保留既有 retryable pending 供一个明确的「重试」入口处理。
- 创作输入区和章节管理删除普通 `needsSync` 的常驻提示与「整理资料」按钮；只有真实失败/遗留 pending 才显示剧情状态同步提示。现有结构化请求、checkpoint、state snapshot、重试和超时语义均复用，没有增加新的恢复层或存储格式。
- 新增收录、手动改写、重新进入旧待同步分支三条自动调度契约，并更新失败持久化和异步刷新测试。`NovelSessionViewModelTests`、`NovelCreationViewModelTests` 与 `IOSNovelCreationWiringTests` 在 iPhone 17 Pro Simulator 为 60 passed、0 failed、0 skipped；`git diff --check` 通过。Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 构建成功，主应用/Widget 包名核对为 `app.amber.ios` / `app.amber.ios.activity`，已覆盖安装并由 `devicectl` 成功启动到配对的 iPhone Air；真实 provider 成功与失败后的真机提示仍需用户操作确认。

### 2026-07-14 novel composer and project panel simplification

- 创作输入区删除常驻「讨论 / 续写 / 整章」分段控件和省略号菜单。输入框聚焦、已有输入、本次上下文已定制或正在生成时，才按标准 Chat 模式显示模型、创作方式和上下文控件；生成期间控件保留位置但不可切换。创作方式使用原生 `Menu + Picker`：材质、阴影、内边距和选中 action state 全部交回系统，不再用手写 Popover 仿制原生菜单，也不在单独一行手工插入 checkmark 造成文字横移。原 `scope` 悬浮按钮已替换为普通 Chat 共用的 `ContextRingButton`，圆环只读取当前分支最近一次真实 injection receipt 的估算占用，没有记录时显示空环；点击仍进入既有的单次资料调整，不改生成参数语义。
- 点击顶部项目名不再进入单一分支列表，而是打开项目面板；面板集中项目重命名、当前分支、写作要求、章节风格和正文导出。原分支管理继续复用现有页面，重命名继续复用统一 Sheet，未引入第二套状态。
- 项目面板继续保留 `.medium / .large` 两档高度，但 5 个操作行显式使用统一 `AmberTheme.surface`，不再让系统在 detent 切换时把圆角分组表面改成白底列表；拖拽、系统圆角和数据状态均未改变。
- 工作区顶部不再重复显示普通生成和资料整理状态，只保留重新载入、只读恢复等真正阻断操作的异常；已收录气泡删除第二套「正文与剧情状态已更新」详情，只保留一条轻量收录结果。
- 「设定 > 更多」删除重复的项目名、模型、润色、注入诊断、分支和项目包入口，只保留自定义资料与待确认建议；workspace 内已失去入口的项目包、注入预览和重复正文管理 sheet 路由同步删除，领域数据和存储格式未改。
- XcodeGen 已按当前分支重新生成工程，清除了切分支遗留的旧小说文件引用。`IOSNovelCreationWiringTests` 与 `NovelSessionReplayTests` 在 iPhone 17 Pro Simulator 均通过；两档样式、context ring 与原生创作方式菜单收口后 `IOSNovelCreationWiringTests` 再次为 9/9，`git diff --check` 通过。Stable Debug arm64 包使用 Personal Team `89QRFX9548` 自动签名并明确构建成功，已覆盖安装到配对的 iPhone Air，`devicectl` 明确返回启动成功；输入聚焦控件、context ring、原生 `Menu + Picker` 选中态、两档项目面板和发送流程的真实手感仍需用户在当前包内操作确认。

### 2026-07-14 novel send watchdog layout repair

- 真机复现确认“点击发送卡死”不是 provider 等待：发送后新 run 尚未写入项目文件，应用即被 `scene-update` watchdog 以 `0x8BADF00D` / `SIGKILL` 终止；崩溃栈停在 SwiftUI `LazyViewGeometry` / `ViewLayoutEngine.sizeThatFits`。最新安装包再次复现为 signal 9。
- 小说消息区改为与普通 Chat 相同的结构：稳定历史继续留在 `LazyVStack`，正在启动或流式增长的尾气泡移到 lazy history 之外。历史 Markdown 不再通过全局 `.disabled` 注入环境失效；按钮阻塞由 presentation 的 transient-tail 状态决定，View 只关闭命中测试。
- 新增长历史真实 `NovelSessionView` 布局回放，以及“durable run 写入前 transient tail 必须阻止历史动作”的契约测试。`NovelSessionReplayTests` 与 `NovelSessionViewModelTests` 两个完整测试类在 iPhone 17 Pro Simulator 均通过；`git diff --check` 通过。
- Stable Debug arm64 真机包使用 Personal Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，已覆盖安装到配对的 iPhone Air。自动启动因设备锁定被系统拒绝，因此同一步骤的最终发送与 watchdog 控制台验证仍未完成；不把构建、安装和模拟器证据表述为真机修复已闭环。

### 2026-07-14 instant novel collection and deferred material organization

- 新收录链路不再创建 durable pending，也不再调用结构化模型后才提交正文。选中的候选正文、章节版本、collection checkpoint、候选状态和 operation ledger 现在一次本地原子提交；成功后正文立即可见，分支仅标记为 `needsSync`，没有第二段等待或“收录到一半”的中间状态。
- `needsSync` 现在表示“正文已保存，人物与剧情资料尚未整理”，不再阻止继续讨论、续写、整章生成或再次收录。生成仍注入当前章节尾部，并明确把派生状态视为可能过期；整章润色和撤销等依赖完整状态的操作仍要求先整理。真实 legacy collection pending 仍会暂时阻止新写入，但点击重试后直接用已持久化的正文完成本地收录，不再发起旧的隐藏模型请求；历史失败 attempt 继续保留幂等占位并被收口到同一成功结果。
- 「同步」入口改为「整理资料」。多次本地收录和手动改写可在稍后一次重建资料；整理失败不回滚已经保存的正文。Banner 区分“正文已保存，可稍后整理”和“确有旧版/手动资料任务未完成”，只在真实任务执行时显示进度。
- 顶部工作区固定为「创作 / 正文 / 设定」，正文从设定内的二级分类提升为一级入口，设定内部保留角色、世界观、剧情和更多。章节阅读底部改为带安全区占位的 Liquid Glass 悬浮前后章控件，右上角菜单使用固定 `40x40` 圆形 Glass，不再被工具栏拉成椭圆。
- 新增收录不启动 provider、legacy collection 无 provider 恢复、延后整理覆盖即时收录章节、顶部入口顺序和阅读器接线等回归。全部 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests` 在 `/Users/arquiel/Library/Developer/Xcode/DerivedData/AmberAgent-bjiohjyzqxbgaocvnuyszcwtjbyd/Logs/Test/Test-iosApp-2026.07.14_00-36-38-+0800.xcresult` 为 351 passed、0 failed、0 skipped；`git diff --check` 通过。
- 最终 Stable Debug arm64 包使用 Personal Team `89QRFX9548` 自动签名并明确 `BUILD SUCCEEDED`，已覆盖安装到配对的 iPhone Air；`devicectl` 明确返回 `Launched application with app.amber.ios bundle identifier.`。这证明最终代码已构建、安装且启动请求成功，不等同于顶部 Tab、阅读安全区和真实 provider 整理流程已经人工完成真机 E2E。

### 2026-07-13 novel project list density and compatibility recovery

- 项目首页保留系统 SwiftUI `List`、侧滑操作和长按菜单，但从卡片式 `.insetGrouped` 收紧为无卡片的 `.plain` 列表，标题改为 inline，行高、图标和间距同步缩小。损坏项目行尾不再常驻红色垃圾桶；正常项目只保留进入箭头，删除仍统一经过侧滑/长按与确认框。
- 一次未兼容历史 receipt 的 Prompt 文本/版本改动曾让原本可读的「大明」被扫描成损坏项目。该 Prompt 改动已完整撤回到原有 `novel.state-delta.v1` / `novel.manual-sync.v2`，没有改写或删除真机项目文件；此前已经因旧 fixed Prompt evidence 损坏的另一个项目不据此宣称已恢复。
- `IOSNovelCreationWiringTests` 定点测试通过，`git diff --check` 通过。Stable Debug arm64 包使用 Personal Team `89QRFX9548` 自动签名构建，`codesign --deep --strict` 校验通过，已覆盖安装并成功启动到配对的 iPhone Air。列表视觉密度和项目重新扫描结果仍需用户在真机进入「小说创作」后确认。

### 2026-07-13 casual-first novel collection validation repair

- 真机项目数据确认当前 retryable collection 的候选正文、章节版本、checkpoint 和 state snapshot 预留均完整，失败发生在 provider 已返回后的状态语义校验。当前正文使用人物早期称谓「朱重八 / 朱重九」，而资料标题与隐藏校验要求未写入 prompt，导致模型输出容易在别名、逐字 evidence 和 unresolved 列表之间不一致。
- 收录现在按「正文优先、状态尽力同步」处理模型语义瑕疵：人物早期名、昵称和称号会自动进入待识别称谓；单条 evidence 不在正文中的派生事实或设定建议只丢弃该条；完全没有可靠事实时沿用上一个状态摘要、剧情走向和待识别列表，正文仍可完成收录。项目/分支 guard、候选归属、JSON 结构、provider 超时和持久化错误仍保持硬失败与可重试，不放宽正文安全边界。
- 为避免 Prompt 版本变化破坏历史 receipt，本轮没有改变状态提取与手动同步 Prompt；别名和 evidence 的容错落在提交前的本地语义整理。残余语义错误通过中文原因呈现，不再只显示泛化的「输入内容或当前状态不符合要求」。这一步没有引入专业/休闲模式开关，也尚未把正文提交与 provider 状态请求拆成两个独立事务。
- 新增别名补全、坏 evidence 局部丢弃、无可靠事实沿用基线和错误文案回归。全部 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests` 在 `/tmp/amber-novel-casual-red-20260713/Logs/Test/Test-iosApp-2026.07.13_22-48-32-+0800.xcresult` 为 351 passed、0 failed；`git diff --check` 通过。最终 Stable Debug arm64 包使用 Personal Team `89QRFX9548` 自动签名构建，`codesign --deep --strict` 校验通过，已覆盖安装并成功启动到配对的 iPhone Air；真实 provider 对现存 pending 的重试结果仍需用户点击验证。

### 2026-07-13 unavailable project deletion and retry timeout repair

- 真机数据取证确认 `Unavailable Project` 的 primary/previous 都因旧 `fixed Prompt receipt evidence` 校验失败而无法加载。列表删除原先错误地要求先成功选中并加载项目，因此确认框和 repository 删除链路都无法到达。现在损坏项目可直接点击或侧滑进入专用删除确认；repository 删除前重新扫描并确认它仍不可读，再沿用既有 deletion tombstone 与清理路径，拒绝误删已恢复为可读状态的项目。
- `正文状态尚未完整提交` 对应的 pending collection 已持久化为 retryable；最后一次重试已创建 durable attempt，但真实 provider stream 没有终止事件，结构化状态请求又没有 app 层 deadline，导致 UI busy 永不释放。事实同步与手动同步现在统一使用 60 秒请求上限；超时取消 provider run、把 pending 收回 retryable，并返回 `structured_request_timeout`。同步期间 Banner 显示 ProgressView 和「正在同步」，不再像无响应的灰色按钮。
- 新增 repository、ViewModel 和 fact retry 三层回归测试。全部 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests` 在 `/tmp/amber-novel-recovery-all-20260713-2.xcresult` 为 348 passed、0 failed；Stable 测试构建与 `iosAppExperimentalGPL` iPhone 17 Pro arm64 Simulator build 均成功，`git diff --check` 通过。
- 最终 Stable Debug arm64 包使用 Personal Team `89QRFX9548` 自动签名构建，`codesign --deep --strict` 校验通过，已覆盖安装并成功启动到配对的 iPhone Air；`devicectl` 进程表回读到主应用和 Activity Widget。设备中的候选正文与 retryable pending 未被手工改写；损坏项目删除手势和真实 provider 超时后的 UI 回落仍需用户按原步骤做真机交互确认。

### 2026-07-13 novel creation navigation, keyboard, and generation lifetime repair

- 小说项目 workspace 恢复原生导航栏与系统返回按钮，章节阅读从 `fullScreenCover` 改为同一 `NavigationStack` 内的 push；项目页和阅读页均重新获得原生边缘滑动返回。编辑、分支、资料等模态 Sheet 仍保持系统下拉关闭语义，没有另造导航手势。
- 创作输入区复用 Chat 的强制收键盘方式：点击消息区、开始拖动和发送有效内容都会同时清 SwiftUI focus 并 resign UIKit first responder；发送失败仍保留输入内容。
- 普通离开 workspace 现在只 detach UI consumer，不再把 route exit 当作取消。共享 `DefaultNovelCreation` actor 继续生成并按既有 sidecar/terminal 路径持久化；重新进入项目后 Session 会从 durable run 重新 attach。切分支、导入/替换项目等真实所有权变化仍保留 `interruptForRouteExit()` gate。
- 小说后台租约从 workspace 上移到 `AppShell`。进入后台先让所有项目中的活跃生成继续执行，完成后主动结束 `UIBackgroundTask`；只有系统 expiration 才调用既有 background interruption 收口并保存 partial。回到前台只释放后台租约，不取消生成。该实现闭合页面退出和系统授予后台执行时间内的持续生成，不把它表述为可跨进程重启的 `BGContinuedProcessingTask`。
- 新增 detach 后继续完成、切到另一项目后 expiration 仍能收口原项目、后台租约 exactly-once、原生导航和键盘接线 canary。全部 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests` 在 iPhone 17 Pro Simulator 为 345 passed、0 failed、0 skipped；Stable `iosApp` 与 `iosAppExperimentalGPL` Simulator build 均成功。最终 Stable Debug arm64 包已用 Personal Team `89QRFX9548` 自动签名构建，覆盖安装并成功启动到配对的 iPhone Air；`devicectl` 进程表回读到主应用和 Activity Widget。真实边缘手势、键盘和长生成后台时限仍需人工真机交互验证。

### 2026-07-12 iOS novel creation UX review fixes

- Quick Start Prompt 升级为 `novel.quick-start.v3`：模型输出使用 `schemaVersion: 2` 的具名角色数组，每个主要角色形成独立 `.character` proposal；旧 `schemaVersion: 1` 单对象输出继续可解码。完成态校验改为世界观/总纲/写作要求各一条、角色至少一条；`NovelProjectDocumentV1` 与项目包 schema 未变。
- 导入返回明确的 `.selected` / `.committedNeedsReload` 结果，Fork 只在新 branch ID 同时命中 selection 与 branch snapshot 时报告“已切换”；commit 成功但 reload 失败不再用旧 selection 冒充成功。资料 > 更多补回导入入口，导入前先收口当前 Session 和项目 active run；导入导出在 busy 时显示原因，失败不再静默。
- Workspace 在分支 snapshot 变化时主动 rebind 隐藏的 Session；全屏 Reader 呈现期间不再触发 route-exit 取消。角色详情本地持有编辑/建议 Sheet，并统一用当前分支 effective revision 展示和匹配经历。
- 气泡与分支管理撤销均绑定用户看到的 checkpoint，并在 `needsSync`、pending fact/polish 或 head 已变化时阻止；derived proposal 正确标记 `.derivedState`、归入“更多”且确认前必须选择资料类型。总纲、写作要求和自定义资料的删除入口已恢复。
- Fork 起点章号改为比较父子 checkpoint 的实际章节版本差异，并按当前分支章节顺序编号；Fresh Fork 的可操作历史截止 fork origin，不再暴露必然失败的撤销或更早父分支检查点。clone 候选按 `sourceMessageID -> message.runID -> run.granularity` 恢复原生成粒度。
- 注入预览改为返回本次请求的精确结果，失败会清除旧值，两个 Sheet 只接受仍匹配当前输入签名的成功结果；Quick Start 的普通失败和中断后 reconcile 统一走中文呈现。领域层仍保留原始 failure evidence。
- 三位独立 reviewer 对最终逻辑闭环、生产调用链和规格回归均复审 PASS，无剩余 P0-P2。全部 28 个 `Novel*Tests` 加 `IOSNovelCreationWiringTests` 在 `/tmp/amber-novel-review-final-all-20260713.xcresult` 为 342 passed、0 failed、0 skipped。Stable `iosApp` 测试构建与 `iosAppExperimentalGPL` Simulator build 均使用最终代码；本轮未把真实 provider、Files 系统交互或真机手感表述为 UI E2E 已验证。
- 最终 Stable Debug arm64 真机包已用 Personal Team `89QRFX9548` 自动签名构建，覆盖安装到配对的 iPhone Air，并由 `devicectl` 成功启动；进程表回读到主应用和 Activity Widget。该证据闭合构建、签名、安装和启动，不等同于小说生成、导入导出或交互手感的真机 E2E。

### 2026-07-12 撤销误开工的 Android novel Phase 0（本 iOS 仓）

- 用户确认开错项目文件夹：Android 功能复刻不应在 `amberagent-ios` 落地。
- 已删除 `feature/novel/`、`test-fixtures/`（含 novel-v1 fixture），并还原 `settings.gradle.kts` 的 `include(":feature:novel")`。
- 未动 iOS `NovelCreation`、普通 Chat、以及其他既有 staged/unstaged 工作。
- Android 计划文档仍保留在 `docs/` 供跨仓参考；状态改回「Proposed — 勿在本仓执行」。

### 2026-07-12 iOS novel creation UX post-implementation review

- 三位独立只读 reviewer 分别审查逻辑闭环、生产调用链和计划符合性；均未发现候选绕过收录进入正文、事务绕过原子提交或旧 snapshot 回写领域层，也未发现 P0。
- 当前高优先级缺口：Quick Start 的单个聚合人物 proposal 与“每个 character material = 单一角色”的经历匹配模型不一致；在资料页通过分支管理 Fork/删除后，隐藏的 `NovelSessionViewModel` 未立即 rebind，直接从阅读页发起润色会读取旧分支 binding；导入 commit 后 reload 失败时 UI 可能用旧 `selectedProjectID` 误判导航成功。
- 其他确定性缺口包括：needsSync 时撤销入口仍可点但领域层必然拒绝、Fork reload 失败会误报“已切换”、derived proposal 在剧情页展示却默认写入世界观、总纲/写作要求/自定义资料删除入口丢失、Fork 章号用总章数代替实际触碰章节、clone 候选丢失原生成粒度。
- 仍需运行时 canary 的高风险路径：角色详情 Sheet 向 ancestor Sheet 同步跳转；`fullScreenCover` 阅读器与 workspace `onDisappear` 的生成取消所有权。Review 本身未修改生产代码。

### 2026-07-12 Android novel creation replication planning

- 新增 `docs/NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md`，把 iOS V1 领域闭环、已验证的 Fable UX S1-S7 与 Android 真实架构收敛为 Phase 0-7 计划。**实现必须在 Android 主仓进行，不在本 iOS 仓执行。**
- 计划固定单一 `:feature:novel` 领域模块与 app-local Compose 页面，复用 `ProviderManager`、`SettingsAggregator`、`ModelSelector`、`MarkdownBlock`、Navigation3、Koin 和 SAF，不复用普通 Chat/Council 的状态、消息或 store。
- 当前代码核对确认 active V3 Drawer 只有「新聊天 / 今日看板 / 小应用」，Android 小说入口应加在「小应用」之后；真实 build types 为 `debug / graphite / release / baseline`，`:app:assembleGraphite` 存在，`assembleNotion` 与 `testGraphiteUnitTest` 不存在。
- 两个 fresh 只读 reviewer 分别审查了 V1/跨端领域闭环和 Android production 调用链。计划已吸收 Swift Codable 兼容默认值、人物经历只按标题匹配、active-run 导出 gate、Settings dummy 过滤、application lifecycle bridge、Navigation3 参数、SAF MIME fallback 与 `allowBackup=false` 等 P1；未发现 P0。

### 2026-07-12 novel creation UX simplification S1-S7

- 工作区从「创作 / 设定 / 分支」收敛为「创作 / 资料」；资料区按正文、角色、世界观、剧情、更多分层，项目模型、润色偏好、分支管理、注入预览、导入导出和重命名均迁入可读路径，原领域层与 schema 未改。
- 创作 composer 收敛为「讨论 / 续写 / 整章 + 更多」；正文新增全屏阅读、前后章、版本、编辑和整章润色。整章候选默认新开下一章，片段默认并入当前章。
- 角色页合并档案、待确认建议与按名字关联的当前分支经历；Quick Start 气泡可直达对应建议。仅当前 head 的已提交候选显示带确认的撤销，Fork 成功显示临时分支切换提示。
- 用户可见错误已中文化，禁用气泡动作显示原因；新建项目固定默认「主线」，主界面不再暴露 `revision/head/token` 等工程术语。
- XcodeGen 已重建工程；三个新 production View 均进入 stable 与 Experimental app target、不进 test target。全部 `Novel*Tests` 在 iPhone 17 Pro arm64 Simulator 为 329 passed、0 failed、0 skipped；Stable `iosApp` 和 `iosAppExperimentalGPL` 定向 Simulator build 均成功。
- 最新 Stable 包已覆盖安装并启动到 iPhone 17 Pro Simulator，首页「小说创作」入口位置与首屏布局正常。由于模拟器未配置可用模型，本轮没有把真实生成、收录、润色、Fork 和 Files 系统交互表述为 UI E2E 已验证。

### 2026-07-12 novel creation architecture simplification S1-S3

- 生产源码从审计时 31,915 行降至 31,376 行。`ScriptedNovelModelAdapter`、`InMemoryNovelProjectRepository` 及其辅助类型移入 `iosAppTests/NovelTestSupport.swift`；删除旧 `NovelCreationFeatureRoot` wrapper、确定死状态和仅供测试读取的 polish 决策镜像。Preview 只保留 DEBUG stub。
- Workspace 现在以单一 exact-owner token 管理所有 busy 状态；普通 mutation、Quick Start、Session start/action、route exit 和 background 只能释放自己的 owner。`isPerforming` 已只读暴露，Session 不再直接赋值，旧 terminal/refresh 回调不能清掉后来操作的 busy。
- 删除 fact/polish 的进程级全局 lease registry。生产 composition 已明确只创建一个共享 `DefaultNovelCreation`；第二 module actor 属于未支持拓扑，不再用全局状态为它增加复杂度。actor 内单飞、pending work、terminal CAS、commit authorization 和文件原子写仍保留。
- 删除两处在 `validateTransition` 前重复执行的完整文档校验；普通与结构化模型管线共享同一 `NovelModelRequest` canonical SHA-256 和参数 evidence。另删除 6 个已有行为覆盖的源码字符串 canary，保留 3 个目前仍是唯一 UI 接线证据的 canary。
- 最终调用链 review 发现并修复 start 尚未进入 actor 时 route exit/background 先到的竞争窗口：取消 tombstone 在首次 suspension 前发布，background 显式携带 UI 已知 runID；迟到 start 只会收到 cancellation，不能落盘或启动 provider。actor blocked-load、显式取消、background 和 Session gate 均有确定性 canary。
- 全部 29 个 Novel 测试类 `/tmp/amber-novel-simplify-all-20260712-4.xcresult` 为 327 passed、0 failed、0 skipped；Stable `iosApp` 与 `iosAppExperimentalGPL` 在 iPhone 17 Pro arm64 Simulator build 成功。两位独立 reviewer 最终均 PASS，无剩余 P0/P1。V1 schema、项目包、operation ledger、lifecycle journal、checkpoint、pending work 和润色/Fork 语义未改。
- 当前 S1-S3 混合工作区的普通 Stable `iosApp` Debug 真机包已用自动签名明确 `BUILD SUCCEEDED`，覆盖安装到 iPhone Air；设备安装表回读为 `Amber / app.amber.ios / 1.0 (1)`。自动启动仅因设备锁定被系统拒绝，因此这只闭合构建、签名和安装证据，不代表小说 UI 或 provider 真机交互已验证。

### 2026-07-12 novel creation Phase F: conversational creation and product wiring

- WP10 以独立小说 Session 实现聊天式创作：Agent 一次输出整块候选正文气泡，支持片段/整章颗粒度、讨论/正文模式、流式取消与恢复、段落选择收录、事实同步、Fork 和整章“不改变剧情”润色；候选在用户收录前不会改写正式正文或项目状态。
- WP11 将「小说创作」接入 Session 首页，位置保持在「小应用」与 `WebMount` 之间；设置页核心记忆入口不变。`AppShell` 复用一个进程级 `DefaultNovelCreation` actor，列表和项目 workspace 使用同一实例，不嵌套第二套导航栈。
- background、route exit、删除、替换导入和导出均先中断 active run，再刷新并确认 durable terminal；background lease 的 normal completion、timeout 和 expiration 都恰好一次释放。最终两位 fresh reviewer 在修复 session-start owner 与并发 background busy ownership 后复审 PASS，无剩余 P0/P1 或生产调用链断裂。
- WP11 定点 `/tmp/amber-novel-wp11-focused-20260712-2.xcresult` 为 83 passed；最终 session owner/lifecycle 修复 `/tmp/amber-novel-start-owner-fix-20260712-2.xcresult` 为 54 passed，均 0 failed。Stable `iosApp` 与 `iosAppExperimentalGPL` 在 iPhone 17 Pro arm64 Simulator 构建成功；48 个 Novel production 文件进入两个 app target，Novel tests 进入 test target，两套构建产物均包含 `.ambernovel` UTI/document type。
- 模拟器已实际验证入口顺序、创建 `Mist Harbor`、workspace 三页、保存世界观资料、重启后项目与资料回显。干净模拟器没有配置全局聊天模型，因此真实生成、收录、Fork、润色和 provider 请求无法做 UI E2E；项目包领域 round-trip 测试通过，但自动化点击导出后系统 Files picker 未出现，仍记录为外部交互待验证而不是继续扩张实现。

### 2026-07-12 novel creation Phase G: final code acceptance

- 两位 fresh、独立、只读 reviewer 分别完成逻辑闭环与真实调用链审查，结论均为 PASS：没有会造成数据丢失、跨项目串线、重复正式提交、不可恢复、核心功能失效或双 target 断链的 P0/P1。
- 代码验收已收口；普通 Stable 包的真机构建、签名和安装已验证，真实 provider、真机交互和系统 Files picker 仍是运行证据缺口，不是已证实的代码 finding。原 Goal 的严格外部完成条件因此仍未满足，不能把这些路径表述为已验证。

### 2026-07-12 novel creation Phase E: package and non-chat workspace

- WP8 项目包、Markdown、UTI/FileDocument、导入/替换/keep-both/删除和外置 lifecycle ledger 已闭合。WP9 已实现薄 `NovelCreationViewModel`、项目列表，以及「创作 / 设定 / 分支」workspace；资料 CRUD、三态注入、模型策略、上下文预览、全部分支操作、章节版本、手动改写/同步、导入导出均只走 `NovelCreation` 三入口。
- Quick Start 只收名称、题材和核心想法，复用既有 generation runtime；`novel.quick-start.v2` 严格解码一次模型返回的四类 JSON 建议，并在同一 terminal commit 写入人类可读消息和 4 条 typed unresolved proposals。确认前不创建资料，失败/重启/持久化阻塞/刷新失败均有真实重试或重新载入路径。
- degraded previous 的显式恢复经 `.restorePreviousProject`、外置 pending/completed receipt、target hash 与 repository expected hash 完成；不兼容章节版本不能直接 restore，只能生成新 manual-edit compatibility lineage，再显式 sync/retry。Fork UI 隐藏内部 initial checkpoint，degraded 项目不暴露只会失败的 rename。
- generation recovery 与并发 mutation、pending/corrupt lifecycle receipt、summary 后删除等链路已补 barrier/canary；`perform` 抛错后 ViewModel 会 best-effort 重载可能已持久化的 pending/恢复结果，避免 UI 停留在旧快照。
- 全 Novel 26 类回归 `/tmp/amber-novel-phase-e-broad-20260712-1.xcresult` 为 275 passed、0 failed、0 skipped；最终 reviewer 修复定点 `/tmp/amber-novel-phase-e-review-fixes-20260712-1.xcresult` 为 70/70。stable test/build 与 `iosAppExperimentalGPL` iPhone 17 Pro arm64 Simulator build 成功；fresh logic 与 call-path reviewer 对最终 exactly-four、previous restore、manual-sync retry、Fork/degraded access 和 recovery 链路均明确 PASS。`AppShell.swift`、`PlaceholderViews.swift` 与 Session 首页入口仍未改动。

### 2026-07-12 novel creation WP8: project package and file lifecycle

- `.ambernovel` 使用单 JSON envelope，校验 Base64 解码后的原始 project bytes、byte count、SHA-256、envelope/project schema 与完整文档；higher schema 在任何 repository 调用前拒绝。Markdown 只读取分支 head checkpoint，不导出 working/pending 草稿。
- reject、replace、keep-both 和 delete 走既有 `NovelCreation.snapshot/perform`；keep-both 结构化重映射全部 project ID，imported running run 确定性归一为 interrupted。导出使用 actor read reservation，active/pending mutation 与 generation 均返回 `projectBusy`。
- import/delete 的外置 lifecycle ledger 保留项目包 exact snapshot，同时闭合进程内与重启幂等、response-loss、pending gate、source/target hash ABA、防跨 document/ledger operation ID 冲突和损坏 receipt 项目级 fail-closed。删除 tombstone 持续存在到明确重新导入；replace marker 禁止旧 previous 回退。
- 新增固定 UTI `app.amber.ios.novel-project`、`.ambernovel`、专用 MIME、`FileDocument` 与带 140 MiB envelope 预检的 security-scoped reader；shared `Info.plist` 同时注册 exported type 和 Editor document type。stable 与 Experimental 实际构建产物均核对到完全一致的注册。
- 最终 WP8 四类 focused gate 为 `/tmp/amber-novel-wp8-final/Logs/Test/Test-iosApp-2026.07.12_12-24-43-+0800.xcresult`：47 passed、0 failed、0 skipped；stable test/build 与 `iosAppExperimentalGPL` Simulator build 成功。`project.yml`、`AppShell.swift`、`PlaceholderViews.swift` 保护 hash 未变，首页入口仍隐藏。

### 2026-07-12 novel creation Phase D: branch lifecycle and whole-chapter polish

- WP6 已闭合精确 checkpoint Fork、全新消息/候选身份、继承候选只读、分支重命名/删除/设主线、仅 head undo、clone/recollect 和 `factCompatibilityID` lineage；分支状态、章节选择与不可变历史均由 reducer/validator/operation ledger 共同约束。
- WP7 已闭合固定“不改变剧情”整章润色 Prompt、完成 sentinel、完整 source/candidate request evidence、严格漂移检查、采用/恢复版本、超时/取消/重启/reconcile 和显式 abandon；安全润色复用状态，剧情不兼容或手动恢复 fail closed 到 `needsSync`。
- 后台与 caller 取消会先 revoke 最终 adoption commit permit，再取消任务；File repository 在全部 validate/stage/failpoint 之后、`replaceItemAt` 紧前原子 claim，InMemory repository 在字典安装紧前 claim。取消先赢时不会产生正式章节，claim 先赢时由现有 applied-operation reconcile 收口，避免最终提交 await 窗口的迟到采用。
- file-backed gate canary 会在最终 adoption install 前挂起、触发后台取消再放行，验证 transaction 落为 `retryable/cancelled`，candidate/正文/checkpoint 不变且重启一致；joined waiter 取消只 detach、不撤销 owner permit，fallback cancellation write-after-install indeterminate 会 freeze 并让同进程 cache 与磁盘/重启重新一致。provider 忽略取消、cancel/timeout 后 abandon、幂等 replay、undo/delete 解锁和 after-write adoption reconcile 也有独立覆盖。
- 最终 Phase D 20 类 focused 回归为 `/tmp/amber-novel-phase-d-final-closed.xcresult`：212 passed、0 failed、0 skipped；stable `iosApp` 与 `iosAppExperimentalGPL` iPhone 17 Pro arm64 Simulator build 均成功。两位 fresh logic/call-path reviewer 对最终 permit、background、joined waiter、fallback freeze、abandon、ledger 和 restart 链路复审 PASS，无剩余 P0/P1。
- WP8 前保护文件仍保持基线 SHA-256：`AppShell.swift=99f879dad8fcce7994d3db13bcc14ae9dcb26617f0c0be20497697795062acef`、`PlaceholderViews.swift=d7432ce92cbed5e2ea2dcd8106da80953c354e3ed8bd55c798b35c9c0910189a`、`Info.plist=dcd987768bcfa73cf92fbff5a9388c115157bb177110ce3f79ae1c58f16749a6`、`iosApp/project.yml=206bd6e50dc8dab73ac9680113d8bc082c2d77e922cf2ebbf8a2542b11355e94`；生成 PBX 为 `8c2174f3ea3b8070e7f74fd8b9db5fee40b419f8cdebd20bb314a35e336bdef3`，产品入口仍隐藏。

### 2026-07-12 novel creation Phase C: collection and fact transactions

- WP5 已闭合 paragraph selection、两阶段收录、状态提取、manual edit `needsSync`、从基准检查点重建完整状态、项目设定建议和失败重试；正文、事件、摘要、检查点和候选状态只在最终原子提交中一起成为正式事实。
- 每个实际结构化模型请求在 provider dispatch 前原子保存 injection/generation request receipt，记录真实 owner/effective provider、stable/wire model、Prompt、参数、预算、canonical input 和 request SHA-256；失败、非法 JSON、取消和重启仍保留可审计请求证据，未进入 provider 的预算失败不伪造 receipt。
- 手动同步按上下文预算持久分块，模型与参数在首块后锁定；每块保留独立请求证据、稳定定长 ID 和字符游标，重启从首个未完成块继续。事件顺序按 chunk 再按原文 evidence 偏移确定，重复 evidence 不会折叠跨块时序。
- retry operation 先持久化不可变 `factAttempts` 再越过 provider 边界；已完成全部 chunk 但只差最终 checkpoint 写入的 retry 不伪造新 attempt/request。collection/manual 的 cancel、invalid output、final-write failure、同 retry 重启和无 provider 最终发布均有定点 canary。
- Phase C 当时曾用共享 registry 防止两个 `DefaultNovelCreation` actor 重复越过 provider；S1-S3 精简已删除该机制。当前 V1 composition 只支持并要求一个共享 repository/module actor，不为未支持的第二 actor 拓扑维持全局状态。
- `NovelFactTransactions.swift` 的纯输出/evidence 校验已机械拆到 `NovelFactOutputValidation.swift`；所有 Novel 文件仍由 XcodeGen 递归进入 stable、Experimental 和 test targets，`AppShell.swift`、`PlaceholderViews.swift`、`Info.plist` 与 `project.yml` 保护 hash 未变，产品入口仍未暴露。
- 最终 focused 结果为 `/tmp/amber-novel-phase-c-final/Logs/Test/Test-iosApp-2026.07.12_08-58-49-+0800.xcresult`：176 executed、1 个既有环境 skip、0 failures。stable build/test 和 `iosAppExperimentalGPL` iPhone 17 Pro arm64 Simulator build 成功；fresh logic reviewer 对 finalization-only retry 与跨 actor lease 复审 PASS，call-path final review 结果记录在下一次状态更新。

### 2026-07-12 novel creation Phase B: Prompt, injection, and generation runtime

- WP3 已闭合版本化 Prompt、确定性注入预算/材料/分支事件选择、canonical input 与 receipts；片段和整章生成共享运行时但保持不同 Prompt/输出预算，候选完成不会写正文或分支状态。
- WP4 已闭合 scripted/live adapter、全局/项目固定模型、owner/effective provider 与 stable/wire model 身份、OpenAI/Claude/Codex/Grok dispatch、两层 tools 清空和 custom-body 危险能力过滤。结构化 executor 真实消费 state-delta/manual-rebuild/drift Prompt，经 provider stream 唯一 terminal 后才进入 strict decoder；重复键、未知字段和非法 schema fail closed。
- generation lifecycle 持久化 user/run/receipts 后再启动 provider；complete/error/cancel/background/expiration/retry/restart/degraded recovery 由 terminal CAS 恰好收口一次。损坏 sidecar 只隔离自身项目，terminal/sidecar 两个崩溃点不会重复消息；failed/interrupted 输出不可收藏。
- 最终 focused 证据为 `/tmp/amber-novel-phase-b5-green.xcresult`：138 executed、1 个 Grok Keychain 环境 skip、0 failures；provider canary 2/2。stable `build-for-testing` 与 `iosAppExperimentalGPL` arm64 Simulator build 均成功，新增 executor 已进入 stable、Experimental 和 test target。
- fresh logic/call-path reviewer 首轮发现 structured decoder 无生产消费者、模型窗口把请求上限误当实际 required、custom body 可通过别名/plugin 重开搜索等问题；修复并复审后两位均明确 PASS。保护文件 `AppShell.swift`、`PlaceholderViews.swift`、`Info.plist`、`project.yml` 的 SHA-256 仍与 Phase A 基线一致，首页入口仍未暴露。

### 2026-07-12 novel creation Phase A: baseline, domain, and persistence

- WP0 基线 HEAD 为 `32da3dbd632376dc2d40e44fa7950b53e4c3570e`。保护文件全文件 SHA-256 保持不变：`AppShell.swift=99f879dad8fcce7994d3db13bcc14ae9dcb26617f0c0be20497697795062acef`、`PlaceholderViews.swift=d7432ce92cbed5e2ea2dcd8106da80953c354e3ed8bd55c798b35c9c0910189a`、`Info.plist=dcd987768bcfa73cf92fbff5a9388c115157bb177110ce3f79ae1c58f16749a6`、`iosApp/project.yml=206bd6e50dc8dab73ac9680113d8bc082c2d77e922cf2ebbf8a2542b11355e94`。对应 staged/unstaged patch SHA-256 也与开工基线逐项相同：AppShell `7e1a47417aad0737efec406b574f8385294e4ef91af0fec3889675118e55f12c/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`、PlaceholderViews `72dd615995ea5eea7e2c4ba3e5db55b11b9f875f7651495e43ef13d7fadb0227/7a73af8e13bc9f9be5836b3b83e0fcb8a814da7e0a587a030bd01ceb446be150`、Info.plist `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855/ba69183e15f7360e3ff6c2723ce418d5d3dfb10c3618e41d1322dece5b44cc15`、project.yml `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。被忽略的生成 `project.pbxproj` 基线为 `bacea868830b54c504e34f8903f7c58b513dbb7295e89d10085a944665fad0b3`，按当前 XcodeGen 源重建后为 `1cce48199625cc44e3db160b26eb78c070e47b1675ad6c21ddd7325a0706ffb6`；受控 `project.yml` 未变。完整 patch 与生成工程副本仍保存在 `/private/tmp/amber-novel-phase-a-baseline`，但上述永久记录不依赖临时目录。
- Phase A owned paths 是 `iosApp/iosApp/NovelCreation/{NovelDomainModels,NovelActions,NovelCreation,NovelReducer,NovelDocumentValidator,NovelProjectRepository}.swift`、五个 `Novel*Tests.swift`/fixture，以及小说规格、ADR、计划和本状态文件。`AppShell.swift`、`PlaceholderViews.swift`、`Info.plist`、普通 Chat/Memory/Workspace/provider runtime 均不属于本阶段修改面；忽略的 `project.pbxproj` 只由 XcodeGen 2.45.4 重建，不手改。
- WP1 已建立强类型 IDs、项目级 operation ledger、三入口 `NovelCreation`、actor + pure reducer、独立 project/config/head/working/session revisions、内部 initial checkpoint、共享不可变资料 revisions、分支 lineage、durable pending payload 和 transition immutability。operation ID 只在目标小说项目内唯一，避免损坏项目或并发扫描污染其他项目缓存。
- WP2 已建立 Application Support 单项目 JSON、derived index、validated previous、degraded read-only、explicit restore、recovery sidecar、100 MB 上限、temp decode validation、atomic replace、CAS、失败注入和损坏/更高 schema 防护。previous-only 与不可读项目仍保留列表身份；健康项目读写不被另一项目损坏阻断。
- 当前定点证据：`NovelCreationModuleTests` 14、`NovelDocumentValidationTests` 21、`NovelProjectRepositoryTests` 13、`NovelReducerTests` 8，共 56/56 通过；最新结果为 `/tmp/amber-novel-phase-a/Logs/Test/Test-iosApp-2026.07.12_03-40-00-+0800.xcresult`。production-only `swiftc -typecheck` 通过。覆盖真实 actor + file create/restart、suspended publish、late read、same/different operation、post-install reconcile、degraded replay、immutable transition、previous/index/recovery、branch/cursor/pending graph和跨分支 candidate base 防护。
- XcodeGen membership 已核对：六个 production 文件各有 6 个生成工程引用，进入 stable 与 Experimental app targets；五个测试文件各有 4 个引用，进入 `iosAppTests`。没有小说导航或首页入口，符合 WP11 前不暴露的边界。
- 两类 fresh Phase A reviewer 首轮发现的 file-backed create 断链、post-install/cache 分裂、全库 operation 扫描串扰、degraded/index/previous-only 不一致、immutable history、recovery hash、pending payload、checkpoint/candidate lineage 与 cursor 等问题均已补实现和失败 canary；最终 logic 与 call-path reviewer 均明确 PASS。stable scheme 的最终 focused test build 为 56/56，`iosAppExperimentalGPL` arm64 Simulator build 通过；现有 warning 属于工作区基线，没有 Phase A 编译或链接失败。

### 2026-07-12 novel creation product and implementation planning

- 明确「小说创作」替换 Session 首页“核心记忆”快捷入口、位于“小应用”和 `WebMount` 之间，但设置页核心记忆功能保留。
- 产品契约采用聊天式候选、用户收录后才进入正式章节与状态、项目设定/分支状态分权、不可变检查点、精确 Fork、整章润色版本和完整项目包。
- `CONTEXT.md`、`docs/NOVEL_CREATION_SPEC.md`、`docs/adr/0007-novel-creation-owns-project-state.md` 与 `docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md` 是该功能的当前权威文档。
- 实施计划分 WP0-WP11；WP11 前不接 Session 首页入口。Phase A 代码不代表完整产品，模型、事实事务、Fork/润色、项目包和 UI 仍必须按后续 gate 完成。

### 2026-07-11 provider detail motion and custom-provider deletion

- Provider 详情页“配置 / 模型”切换使用 0.2 秒平滑选中块移动；点“模型”时模型页从右向左进入、配置页向左退出，点“配置”时配置页从左向右进入、模型页向右退出。每个 tab 固定自己的 transition，避免旧页沿错误方向退出。
- Provider 配置页右上角使用圆形 Liquid Glass 保存按钮，底部只保留横向并排的“测试连接 / 删除”；模型获取只留在模型页的“自动获取 / 手动添加”。
- 服务商选择页改由原生 `List` 承载；用户自建 provider 支持尾部左滑显示红色“删除”并二次确认，内置模板不可滑删。内置模板按 `DEFAULT_PROVIDERS` 稳定 ID 识别，不依赖序列化后不可靠的 `builtIn` 临时字段。
- 原生 `List` 行显式移除系统额外上下 inset，恢复原先紧凑的 provider 行密度，同时保留原生左滑操作。
- 服务商列表不再展示由当前聊天模型推导出的“当前 provider”选中态；自建项统一标记为“自定义”并可左滑删除，内置项只展示配置状态。
- 删除会同时移除 provider、模型、对应 Keychain API Key 和旧 custom-model mirror；若被删模型正被全局或 assistant 使用，共享设置会切到仍存在的聊天模型，不留下当前模型悬空引用。
- `IOSSharedSettingsStoreProvidersWriteBackTests` 全类通过；模拟器完成新增自建 provider、配置/模型切换、显示删除确认、确认删除并返回列表的实际 UI 流程。
- 包含上述 provider UI 的普通 `iosApp` Debug 包已于 2026-07-11 覆盖安装到真机并成功启动；首次安装传输被设备端 `IXRemoteErrorDomain 6` 中断，原包直接重试后安装成功，不属于代码或签名失败。

### 2026-07-11 Release chat performance replay and signposts

- 新增 Release 可用、默认关闭的 `ChatPerfTrace`，只记录数量和阶段，不记录消息正文、消息 ID 或 run ID。当前覆盖 stream flush、默认 clean-list timeline projection、Markdown block split/parse/convert/publish、显式回底和 terminal settle；不做 per-chunk、per-row 或几何帧打点。
- 新增长散文与增长表格两份 bundle JSONL fixture，以及只在 `CHAT_PERF_REPLAY` 编译条件下替换首屏的固定回放入口。入口走真实 `ChatSwiftUIMessageList`，使用隔离且显式固定的字体/Markdown 设置，不读取用户现有聊天配置；普通 Debug/Release 启动路径不变。
- 对抗性 review 后收紧三个证据口径：`ReplayStreamInput` 不再混入历史加载和固定 idle；terminal settle 使用独立成对区间；首 token 前点击回底后若 terminal 时仍无 assistant tail，会立即释放显式回底和 trace 所有权。
- 修订后的 `Release -O` 性能包已签名构建，fixture 已进入 app bundle，app 与 dSYM UUID 一致。该诊断包曾短暂覆盖主应用，因没有 session 返回入口造成真机不可用，已立即用无 `CHAT_PERF_REPLAY` 条件的普通 Debug 包覆盖恢复并成功启动。后续不得再把固定回放包安装到主 bundle ID。
- USB 解锁后实际尝试 SwiftUI/Time Profiler 的 launch 与 PID attach，但产物只有 `RunIssues.storedata`，`xctrace export` 报 `Document Missing Template Error`；两次 recorder 还超过 time limit 不收口并已精准终止。当前有效真机 Instruments trace 数量仍为 0，不能据此推进增量 timeline/RenderSnapshot 架构。

### 2026-07-11 user/assistant turn spacing polish

- 默认 `ChatSwiftUIMessageList` 只给 user 消息整行增加上下各 `10pt` 的外部留白；结合列表原有 `14pt` 行间距，相邻 assistant 内容与 user 气泡的视觉间隔约为 `24pt`。
- user 气泡内部 padding、assistant Markdown 排版和现有发送/流式动画均未改变。

### 2026-07-11 real-device Native Timeline rollback

- 真机偏好回读确认三个 Native Timeline 实验开关均为 `true`，因此实际运行的是 `NativeChatTimelineView + NativeTimelineScrollDriver`，不是默认 clean-list。
- Native driver 把已由 `safeAreaInset` 排除在 ScrollView 外的 composer/keyboard 高度再次加入 bottom target，并在 terminal 后继续保留数值跟随所有权；这会把系统真实底部误判为未到底，用户松手后再次写回错误目标，表现为内容上移和反复弹回。
- 真机三个实验开关已全部关闭并回读确认，恢复当前正式的 `ChatSwiftUIMessageList`。这不关闭 Markdown 或逐词动画，只移除未闭环且直接写 `contentOffset` 的实验滚动写者；Native 路径在补齐 terminal、height-shrink、safe-area 和真实手势回放门禁前不再用于真机灰度。

### 2026-07-11 clean-list upward-growth transition restoration

- 默认 `ChatSwiftUIMessageList` 的真实 Markdown 高度增长此前最终走无动画 bottom-anchor 写；48ms 快照与 Markdown 节流会把增量合成较大的布局步，因此视觉上退化成整行向上跳，逐词 fade 本身并未关闭。
- 现在只在贴底跟随、无用户手势、无显式回底所有权时，把实测高度增长延后一轮主线程事务后用 `0.08s` 线性 bottom-anchor 过渡；Reduce Motion 和“底部跟随动画”关闭仍按原契约立即到位。没有启用 Native driver，也没有写 `contentOffset` 或 `scrollTo(y:)`。
- 真实 `.assistantStreamDelta` 执行层 canary 会联合采样 `contentHeight + offset + timestamp`，要求至少两个中间 offset 且不得反向回跳；该 canary 与接线测试连续 10 轮通过。完整 `ChatStreamReplayTests` 通过。
- 三类联合回归当前为 `66 passed / 1 skipped / 1 failed`；唯一失败是既有巨型尾行显式回底 canary 的 `LazyVStack` 偶发卸载，和本轮 measured-growth 动画 A/B 解耦，不能记成整组全绿。
- 包含本轮最终过渡代码的普通 Debug 包已签名构建、覆盖安装并成功启动到 iPhone Air；真实生成手感仍需用户在当前真机包复验。

### 2026-07-12 long-prose visible growth layout repair

- 真机复验否定了“只靠 presentation pacing 即可闭环”的判断：recorder 已确认前台可见快照被限制为每次最多 12 字，但默认列表录屏仍表现为长静止段后整屏位移。独立 `ParagraphUIView` 按单行高度增长；生产 `LazyVStack` 则在动态尾行增长和重复 bottom target 写入下反复修正历史估算，旧路径单次 `contentSize` 跳变最高 `232pt`、底部欠账 `379pt`，根因不在 `20Hz` 屏幕刷新。
- 默认 clean-list 现在只把稳定历史放在 `LazyVStack`，唯一动态尾行在其外精确测量；历史仍保留 `.equatable()` render gate，不改成全量 eager 列表。底部跟随改写物理 `.bottom` edge，动态消息行不登记为 scroll target；会话初始归位也直接发语义 edge 写，不再等待一份可能尚未发布的 scrollability 几何。没有写 `contentOffset` 或 `scrollTo(y:)`，屏内 Markdown、逐词和 `0.08s` 上移过渡均未降级。
- 新增 display-link canary 同时采样段落高度、列表 `contentSize`、底部欠账和横向 offset：至少 95% 的增长必须在单行 `40pt` 内，60Hz 模拟器即使漏采一个中间帧也不得累计三行，底部欠账不得超过 `72pt`，重复 edge 写不得产生横向漂移。逐行版连续 5 次通过，加入横向断言后再次通过；长表格帧响应、逐段上移动画和长会话初始底锚通过。
- 独立 edge spike 真正显式运行后，短内容用例通过；“只写一次 edge 后永久保持底部”的实验契约失败（最大欠账 `91pt`、横向差 `16pt`）。生产 clean-list 不依赖一次性永久钉底，而是在实测增长时重复发语义 edge；完整生产回放为绿。不得把此前两项 skip 误记为 edge spike 通过。
- `ChatViewportPolicyTests`、`ChatSwiftUIStreamReplayTests`、`ChatStreamReplayTests` 联合回归通过。测试构建仅临时排除了与聊天无关且当前自身编译失败的 `NovelSessionReplayTests.swift`；没有修改该文件或放宽聊天断言。
- 最终普通 Debug 包已签名构建并覆盖安装到 iPhone Air；自动启动仅因设备锁定被系统拒绝。当前只能记为代码、模拟器和安装闭环，真实 Grok 逐行观感仍待用户在这版真机复验。

### 2026-07-11 Grok 4.5 streaming transport closure

- 真机选中的 Grok 4.5 原先经过 `trycloudflare.com` Quick Tunnel；Cloudflare 官方明确该入口不支持 SSE。其源站前置代理还会先 `response.read()` 完整响应再一次性写回，两层缓冲共同造成约 30 秒静默后整批出字。
- 日本 VPS 已改用逐块读取、HTTP chunked 写出并立即 flush 的 SSE 代理，公开入口切到 `https://103.201.130.63:9443/v1`。入口使用有效的 Let's Encrypt IP 证书，已配置自动续期和续期后服务重启。
- 2026-07-11 复发的公网 9443 timeout 定位为 Python TLS 包裹监听 socket 后在主 accept 线程执行握手；扫描器不完成握手会把 `5/5` backlog 塞满。代理现将 TLS 握手移入 worker、握手超时 10 秒并把 backlog 提到 128；20 个停滞握手并发下正常请求仍可响应。
- 真机 Grok Provider 已持久化为新 HTTPS 入口，重新启动应用后回读仍为该地址；旧 Quick Tunnel 进程已精确停止，不再保留会把流式请求重新带回缓冲路径的入口。
- 外部认证 SSE 探针曾收到 211 个事件，其中 159 个正文事件在响应期间持续到达；TLS accept 修复后再次收到 HTTP 200、30 行流事件和 `[DONE]`。公开 `/admin` 仍返回 404。Grok 本身仍可能在服务端思考后高速生成；不在客户端添加假打字节奏掩盖真实到达时序。

### 2026-07-10 explicit-bottom phantom-space repair

- 真机视频确认点击回底后并非文字延迟渲染，而是最终 Markdown 高度收缩后仍停在旧 provisional 高度：实测 `offsetY=13230`、最终 `contentHeight` 约 `9900-10600`，但截断后的 `distanceToBottom=0` 把数千点向下越界伪装成已到底。
- 默认 SwiftUI clean list 只保留一个物理 bottom target；共享 timeline plan 的逻辑 bottom entry 不再与其形成重复 ID。
- 显式回底在尾行首次可见后继续持有一个有界 settle 所有权。期间真实内容高度无论增长或收缩，都只重发同一个 bottom-anchor ID；用户手势立即取消，既不写 `contentOffset`，也不降低现有回底和 Markdown 动画。
- 默认 SwiftUI 回放 canary 现在会在首次到达后持续采样尾部 marker，并直接拒绝 `visibleBottom > contentSize.height` 的假底状态。

### 2026-07-10 Grok-compatible SSE and explicit-bottom catch-up closure

- 真机当前选中的 Grok 4.5 走 OpenAI-compatible Chat Completions，不走 Grok Web；SSE 现在能逐行产出完整 JSON / `[DONE]` / NDJSON，并对残缺多行事件保留标准空行收口。
- Chat Completions 与 Responses 不再只保留同一 SSE event 的最后一个 payload；所有 chunk 按原顺序进入流式链路，callbackFlow 使用可背压发送。
- 默认 SwiftUI 列表不再把 LazyVStack `onAppear` 或未知坐标误判为尾行可见。显式回底只在当前尾行真实相交且 measured distance 到底后释放实时尾行。
- active generation、terminal settle 和显式回底后的 terminal catch-up 共用真实 content-height growth 跟随；用户拖拽、暂停和显式动画仍阻断竞争写入。
- `ParagraphUIViewCache` 改为 checkout 时移出、representable dismantle 后归还，避免同一个尚未 attach 或暂时 detach 的 UIKit 文本视图被两个段落同时复用并清空。

### 2026-07-10 Grok Web 403 credential handoff repair

- Grok Web 运行时现在从 Keychain session 恢复 `sso` / `sso-rw` 到请求用 `WKWebView`，不再偶然依赖登录 Sheet 遗留的 WebKit Cookie。
- 401/403 会使失效登录态可见；网页模型改为显式映射，`grok-4.5` 等 xAI API 模型不再静默冒充旧 Web 模型发送。
- Grok 4.5 应通过独立 xAI API 或 OpenAI-compatible sub2api provider 使用，不属于当前 grok.com 私有网页链路。

### C5: Streaming event consumption

- 前台 chunk、complete、error 进入单一 `AsyncStream<ChatStreamEvent>` FIFO consumer。
- snapshot 只在节流 flush、cancel、background handoff 和终止点获取。
- `IOSAgentToolEngine.streamStep` 不再每 chunk 执行 `snapshot()+join`，改为累计 assistant text delta。

### C6: SwiftUI row-level render gating

- 默认 SwiftUI 列表接入 row digest/cache 和 `.equatable()` 门控。
- 目标是尾部 delta 不再让历史 Markdown/表格行整屏重建，同时保持非尾部可见渲染效果。

### Chat streaming UX review WP1: renderer recovery closure

- 已修复 `ChatStableStreamingMarkdownController` 的解析任务所有权：非动画解析完成后会释放任务，动画与非动画任务只能清理自己的 generation。
- 非动画解析期间到达的流式增量会保留为 pending，并在当前解析完成后继续消费，不再依赖后续 chunk 偶然唤醒。
- `IOSSettingsWiringTests` 的表格渲染 canary 已更新为当前 vendor Markdown renderer 接线。

### Chat streaming UX review P1: animation, settle, and table stability

- delta 的无动画 transaction 已限定为 signal 变化事务，不再持续覆盖整个 ScrollView 子树的其他显式动画。
- 工具事件、terminal settle、viewport command 和几何重锚的即时底锚写会避让显式回底动画。
- terminal settle 改为 0.4s rolling quiet-window，真实高度增长会重置窗口，并保留 1s 绝对上限。
- 表格探测保留 fence/行状态，首次扫描后只消费新增 UTF-8；生产 body 不再逐 chunk 全文调用 `containsTable`。
- 陈旧 renderable 只允许用于真实前缀增长，表格拆块导致文本收缩时不再复用含旧表头的结果。

### 2026-07-10 streaming performance adversarial review closure

- `MarkdownRenderConfig` 缓存键补齐 `paper + accentHex`；主题变化会重建 config，同一主题的流式 delta 继续复用实例。
- 移除不满足 CommonMark 文档级语义的 heading 拆块，仅保留表格边界拆块；link reference、HTML block 和半截 `#` 不再被人为断开。
- widget 探测保持 chunk 热路径 O(delta)，只在 streaming completion 对 final full-message 做一次重扫，覆盖等长同尾替换。
- active-stream cancel 会把 drain 后快照同步发布到 UI 再持久化；complete/error 后立即释放 accumulator 权威，避免工具阶段取消回退到旧快照。
- 默认 SwiftUI 回放门禁改为约 10ms 采样真实 offset，terminal 用例会制造并等待真实 late growth；不再用宽松的 300pt 回跳容差掩盖问题。

### 2026-07-10 real-device long-stream follow and rendering repair

- 真机复现长内容持续生成后两类问题：用户从历史区回到底部附近会白闪；继续生成后表格/块 Markdown 可能停止实时发布，点回底按钮只短暂补出一批内容。
- 根因之一是块解析完成时只要又有 pending delta 就丢弃结果；长内容解析慢于 chunk 间隔后会永久饥饿。现在串行控制器发布每个已完成的累计前缀，再继续消费最新 pending。
- 默认 `ChatSwiftUIMessageList` 的流式尾行不再因离底 LOD 切换 renderer/config；renderer 身份和屏内动画保持不变。
- 用户查看历史时，尾行由自身与 ScrollView viewport 的真实相交状态判断是否可见；只有当前最后一条 assistant、曾经进入过流式态且已确认离屏时才使用 suspended token。冻结会跨 tool/terminal 保留，避免冷重入白闪；追加新行后旧 assistant 不再是尾行，会恢复正常渲染。
- 显式回底会在命令发出时临时强制尾行实时发布，直到尾行被实际观测为可见；因此点击箭头后下一批 delta 不会再次被离屏冻结。用户手势、会话切换和 Reduce Motion 路径会清理同一所有权状态。
- 会话入场/切换的底锚重试遇到短暂滚动状态只跳过当次尝试，不再中断整个 retry ladder。

### 2026-07-10 focused streaming performance closure

- `MessageBubbleView` 不再把每个 Markdown delta 镜像进 SwiftUI `@State`；只在 live/frozen 边界和 terminal 捕获快照。
- 生产表格探测仍保留精确 Markdown block，但不再拆分、缓存渲染链路未消费的 header/row 单元格；AttributedString 和 attachment row conversion 已移到预渲染路径。
- 冷重入表格与段落统一遵守 `animateInitialText`，已显示内容不会整块重新淡入；新 token 的屏内逐词动画不降级。
- 前台流事件 sink 改为 head index 的摊还 O(1) FIFO claim；逐帧完成动画清理由 O(A^2) 收敛为 O(A)，并允许 120Hz display-link 调度。
- 曾实现并基准验证 TableLayout append-only cache；未产生可重复收益后已完整移除，不保留无证据复杂度。

### 2026-07-12 layer-backed streaming table entrance

- 缩放基准确认长表格掉帧来自 SwiftUI `TextRenderer` 的逐帧失效：即使只保留一个动画单元格，整张 80 行表格仍会随每个动画帧重新提交；TableLayout、块拆分和解析节流均不是这一轮的主瓶颈。
- AmberAgent 的实时流式表格改为 opt-in 的 Core Animation 图层入场，vendor 默认值仍保持旧行为。最终 SwiftUI `Text` 始终负责布局、链接与辅助功能，动画只做 0.34s 渐进显现和轻微上移，不动画高度，也不改变完成态渲染。
- 新增长表格 display-link canary：80 个稳定行后连续追加 12 行，旧实现 p95 约 50-65ms；图层实现多次运行 p95 约 16.67-35.38ms，门禁为 p95 <= 40ms、max <= 80ms。另有动画前后高度一致 canary，防止覆盖层卸载造成列表跳变。
- 定点动画/性能门禁通过；`ChatStreamReplayTests`、`ChatViewportPolicyTests`、`ChatMessageProjectionTests` 在联合运行中通过。完整 `ChatSwiftUIStreamReplayTests` 仍会复现既有的巨型尾行偶发卸载：显式回底后几何已到底但 UIKit 文本子树为空；该用例单跑通过、完整类连续两次失败，未放宽断言，也未混入本轮表格修复。
- 普通 `app.amber.ios` Debug 包已签名构建、覆盖安装并启动到 iPhone Air；产物未包含 `CHAT_PERF_REPLAY` 或固定性能回放入口。长表格动画观感与真实滚动手感仍需当前真机交互复验。

### 2026-07-10 cold-reentry rendering and lifecycle closure

- 已流式消息冷重入时按消息块 identity 复用同视觉配置、同 speculative 模式的已解析前缀，不再先显示 raw Markdown 或让整段内容重新从 alpha 0 淡入；新 token 仍保留逐词动画。
- 流式与完成态 renderable 严格按视觉配置和 speculative 模式分区；已流式消息完成/回收后保持 block renderer 拓扑，正文、标题和链接字体跟随 `chatFont`。
- 后台 provider 接入真实流式回调；expiration 原子取得 terminal 并取消 KMP stream，provider failure 保留已完成 suffix 与当前 partial，保存失败不再误报完成或清理恢复 payload。
- 前台取消与 pending-tool failure 会在异步 terminal 工作前冻结消息快照和 write baseline，避免较晚的取消落盘覆盖较新的会话写入。

### 2026-07-10 ProMotion access and wide-table self-sizing repair

- iPhone target 的 `Info.plist` 已启用 `CADisableMinimumFrameDurationOnPhone`；此前系统不会向应用开放高于默认 60Hz 的刷新率。该配置只开放 ProMotion 能力，不承诺系统始终维持 120Hz。
- 高刷新率时序暴露出 fallback UICollectionView 尾行回收问题：`UIHostingConfiguration` 会把长表格的无约束固有宽度（实测 1426pt）写入 fitting 结果，代码又按该宽度测高，导致窄屏行回收后塌缩。现在始终按 ChatLayout 提议的屏幕行宽测高，不做 offset 或几何补偿。
- diffable snapshot 进行中若尾行回到可见区，解冻刷新会在当前 apply completion 后收口，不再丢弃这次唯一刷新机会。

当前工作区还包含 Grok Web provider/auth 相关的未跟踪和修改文件。它不属于 C5/C6 handoff；触碰 provider 前先重新核对该 slice 的真实实现和测试状态。

## Last Documented Verification

当前混合工作区最近已通过：

- 小说架构精简 S1-S3：29 个 Novel 测试类 `/tmp/amber-novel-simplify-all-20260712-4.xcresult` 为 327/327；Stable 和 Experimental arm64 Simulator build 成功。当前 Stable 普通 Debug 包真机构建、签名、安装成功，安装记录已回读；启动因设备锁定未完成，尚无当前包的真机交互结论。
- 小说 Phase F 最终定点：`/tmp/amber-novel-wp11-focused-20260712-2.xcresult` 83/83，`/tmp/amber-novel-start-owner-fix-20260712-2.xcresult` 54/54；Stable 和 Experimental 的 iPhone 17 Pro arm64 Simulator build 均成功，Phase F 当时的 Stable 包已安装并启动。`git diff --check` 与 `git diff --cached --check` 通过。
- 普通聊天联合门禁 `/tmp/amber-novel-final-chat-gates-20260712-1.xcresult` 共 62 项：60 passed、1 fixture skip、1 failed。唯一失败是既有脏工作区的 `IOSSettingsWiringTests/testStreamingBlockMarkdownToggleIsConsumedByTableBlockRenderer`：当前其他工作只留下 preference key，没有设置控件和 renderer consumer；小说改动未触碰对应聊天实现，不能记成小说回归全绿。
- 原样 generic Simulator build 会在本机缺少 KMP x86_64 simulator slice 时链接失败；同一源码的 Stable 与 Experimental arm64 named-simulator build 均通过，归类为本地架构产物限制，不包装成全平台构建通过。

- 2026-07-12 动态尾行/惰性历史分离后，逐行增长 display-link canary 连续 5 次通过，加入横向漂移断言后再次通过；`ChatViewportPolicyTests`、`ChatSwiftUIStreamReplayTests`、`ChatStreamReplayTests` 联合回归为 71 passed / 1 fixture skip / 0 failed。显式 edge spike 的短内容项通过，一次性永久钉底项按预期证伪，生产路径不依赖该实验契约。因当前无关的 `NovelSessionReplayTests.swift` 自身存在作用域编译错误，聊天测试 bundle 使用命令行 `EXCLUDED_SOURCE_FILE_NAMES` 临时排除该文件；普通 iPhone Debug build 不受影响并已覆盖安装，启动仅被设备锁定阻止。
- 2026-07-11 性能回放批次的 `ChatViewportPolicyTests`、`ChatSwiftUIStreamReplayTests`、`ChatStreamReplayTests` 联合回归通过；`Release -O + CHAT_PERF_REPLAY` 明确 `BUILD SUCCEEDED`，固定 fixture 与 dSYM 校验通过，但真机 Instruments 尝试均未形成有效 trace。随后普通 Debug 包重新 `BUILD SUCCEEDED`、覆盖安装并成功启动，控制台持续运行超过 10 秒且加载键盘扩展，主应用会话入口已恢复。
- user/assistant 轮次留白调整后，`ChatStreamReplayTests`、`ChatSwiftUIStreamReplayTests`、`ChatViewportPolicyTests` 联合回归通过；stable `iosApp` Debug 明确 `BUILD SUCCEEDED`，已覆盖安装并成功启动到 iPhone Air。
- 2026-07-11 真机 Native Timeline 实验开关关闭后，`ChatStreamReplayTests`、`ChatSwiftUIStreamReplayTests`、`ChatViewportPolicyTests` 联合回归通过；应用已成功启动，启动后再次回读确认三个开关均为 `false`。交互手感仍需当前真机操作复验。

- phantom-space 修复前，强化后的显式回底 canary 连续 10 次全部复现“先见末尾、随后进入空白”；加入真实 content bounds 越界断言并完成修复后连续 10 次通过，补齐动画完成时的 quiet-window 起点后再次连续 10 次通过。
- `ChatSwiftUIStreamReplayTests`、`ChatViewportPolicyTests`、`ChatStreamReplayTests` 联合回归：62 passed / 1 fixture skip / 0 failed。
- 最新 iPhone Debug 包已签名构建并覆盖安装到 iPhone Air；自动启动仅因设备锁定被系统拒绝，尚未把真机视觉复测记为通过。

- `ChatMessageProjectionTests` 全类（新增解析恢复与 pending 接续契约测试）
- `IOSAgentToolEngineTests` 全类
- `IOSGenerativeWidgetPayloadDetectorTests` 全类
- `IOSSettingsWiringTests` 全类
- `ChatStreamReplayTests` 全类
- `ChatSwiftUIStreamReplayTests` 全类
- `ChatViewportPolicyTests` 全类
- `ChatRowContentHashCacheTests` 全类
- 显式回底后下一 delta 继续实时跟随的默认 SwiftUI 执行层回放用例连续运行 10 次通过；移除无必要的 trailing-follow pending queue 后再次连续运行 10 次通过。
- `ChatStreamReplayTests`、`ChatSwiftUIStreamReplayTests`、`ChatViewportPolicyTests`、`ChatRowContentHashCacheTests`、`ChatMessageProjectionTests` 最终联合回归通过。
- `IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush`
- `IOSParityRedLightTests` 的 background expiration ownership、真实 streaming provider、failure suffix/partial 和 save-failure terminal 定点项
- `IOSConversationStoreTests/testBackgroundSaveReportsFailureAfterConversationWasDeleted`
- `ChatViewModelSelectedFileContextTests/testCancelPersistsCancellationSnapshotInsteadOfLaterActiveMessages`
- `ChatViewModelSelectedFileContextTests/testCancelCapturesWriteBaselineBeforeDelayedTerminalWork`
- `ChatStreamingPerfBaselineTests/testPerf_endToEnd_streamingBubbleDeltas`：24KB 表格 `78.1ms/delta`，24KB 散文 `43.3ms/delta`；分别未劣于本轮修改前约 `82.0/46.1ms/delta`
- 长流修复后重新通过 `ChatStreamingPerfBaselineTests/testPerf_endToEnd_streamingBubbleDeltas` 和 `testPerf_timelinePlannerBuild_longSession`
- 当前稳定 message identity 的 growing-single-table 定点基准通过：20 个 delta 合计 `2008ms`、`100.42ms/delta`；该口径包含每次 350ms 等待窗口内的可见字形动画，不等同于单帧耗时。
- 本轮重跑完整 24KB end-to-end performance stress 时测试 runner 被系统 `SIGKILL`，未产出当前批次指标，不能记为本轮通过；此前历史通过记录保留。
- vendor `SwiftStreamingMarkdownTests/TableViewTests` 通过。
- `git diff --check`
- Grok Web 403 修复后通过 `IOSSettingsWiringTests`、`ChatStreamReplayTests` 全类，并完成 iPhone Debug 覆盖安装；真实 Grok 请求仍需用户账号复测。
- 包含长流修复的 iPhone Air 真机 Debug 构建、安装并启动成功；尚未把本轮白闪/卡住/历史滚动手感标记为已验证。
- ProMotion 配置和宽表格 self-sizing 修复后，`ChatStreamReplayTests`、`ChatSwiftUIStreamReplayTests`、`ChatViewportPolicyTests` 及 ProMotion plist canary 通过；Debug 包重新构建、确认产物 plist 含该键，并覆盖安装、启动到 iPhone Air。实际滚动帧率仍需真机 trace，不把“已开放 120Hz”表述成“恒定 120fps”。
- 本轮 viewport/cold-reentry/lifecycle 合并工作区于 2026-07-10 重新完成 iPhone Air Debug 构建和覆盖安装；自动启动仅因设备锁定被系统拒绝，因此最新包尚未完成真机交互复验。
- 本轮 focused performance/显式回底修复再次完成 iPhone Air Debug 签名构建与覆盖安装；自动启动被系统以设备锁定拒绝，构建和安装成功不等同于真机手感已验证。
- Grok SSE / explicit-bottom catch-up 修复通过 `ai-provider-openai:jvmTest`、iOS Simulator KMP 重编译、`ChatViewportPolicyTests` 38/38，以及 `ChatSwiftUIStreamReplayTests + ChatStreamReplayTests` 23 passed / 1 fixture skip / 0 failed。
- `ChatMessageProjectionTests/testCheckedOutParagraphViewCannotBeIssuedTwice` 通过；全工作区 `git diff --check` 通过。
- 最新 `iosApp` Debug 已于 2026-07-10 22:34 签名构建、覆盖安装并成功启动到 iPhone Air；聊天手势视觉仍需用户复测。
- Grok 4.5 新 HTTPS 入口已从 Mac 完成证书校验，未认证请求按预期返回 401；认证 SSE 外部探针确认 211 个事件增量到达。真机回读确认 Provider 地址持久化，应用终止重启后启动成功；最终视觉节奏仍需一条真实 Grok 长回答复验。

其中解析恢复测试先红后绿。P1 最终五类联合回归曾因 Simulator `Busy / Application failed preflight checks` 未进入测试；显式 boot 到完成态后原样重跑通过。以下仍是 `HANDOFF_STREAM_PERF_C5_C6_2026-07-10.md` 的较早记录：

- `IOSParityRedLightTests/testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush`
- `IOSParityRedLightTests/testBackgroundExpirationFailureMergesPartialAndFailureIntoSingleAssistantMessage`
- `IOSAgentToolEngineTests/testStreamingAssistantTextDoesNotSnapshotOnEveryChunk`
- `ChatRowContentHashCacheTests`
- `ChatStreamReplayTests`
- `git diff --check`

上述 foreground FIFO、row/render cache、默认 SwiftUI replay、完整 `ChatStreamReplayTests`、background expiration 与 `IOSAgentToolEngine` 均已在当前合并工作区重跑。

## Current Priorities

1. Android 复刻当前只有已 review 的实施计划，尚未开始；Grok 真正执行时从 Phase 0 的 V1 双向 fixture 和基线开始，不提前暴露 Drawer 入口。
2. iOS 小说功能不再扩张 P2 矩阵；有可用全局模型和设备时，再补真实 provider 的生成 -> 收录 -> Fork -> 润色，以及真机后台、键盘和 Files 导入导出证据。
3. 两端都保持普通 Chat、Memory、Workspace、provider 和 Markdown 权威路径不变；既有聊天测试红单独归因，不借小说工作顺手修复。

Do not prioritize C7 multi-tool batching unless the user explicitly changes direction.

## Known Open Items

- `testForegroundStreamingChunksDoNotSnapshotBeforeThrottledFlush` 仍红,且是**既有**失败:它是源码字符串 canary,期望 `cancel()` 里出现 `setMessages(pendingStreamSnapshotAtCancellation)`,而 HEAD 起该处已是 `setMessages(messagesAtCancellation)`(引入 `messagesByFailingPendingToolCalls` 时改的),canary 未跟着更新。要么按新变量名更新 canary,要么改成行为断言——源码字符串 canary 会随无关重命名假红。
- 本机没有系统 JDK,Gradle 需显式指定:`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew ...`。首次跑 `:ai-core:compileKotlinJvm` 可能因增量缓存报 `MessageStreamAccumulator.kt` 类型不匹配,重跑即过。
- `.sizeChanges` 底锚的所有权在三个表面不一致:标准 Chat 默认路径已改为 measured-geometry 唯一驱动并移除 pin,而 Council(`sizeChangesPinOwnsGrowth` 门掉 measured growth)、Novel(`followingBottom` 分支显式 `break`)与 Chat 的 native 实验路径仍依赖 pin。支撑 Novel 这一选择的 `NovelSessionBottomAnchorProbeTests` 用的是显式高度的 `Color` 块,不是真实 `ParagraphUIView` 的 UIKit 增量布局,未覆盖 Chat 上观测到 bottom debt 的条件。动手前应先把探针换成真实长 Markdown 取证。

- 小说项目选择仍有两个非阻塞 P2 边界：缺少“被 busy guard 拒绝的跨项目选择不会取消已挂起 branch intent”的直接 canary；同项目 `selectProject` 当前可在任意 `isPerforming` 期间刷新并改变 selection token，若与普通 mutation 并发，可能跳过其终态 reload。现有 branch preflight race test 有意依赖同项目刷新，不能粗暴改成 busy 时全部禁止，需另行收窄契约。
- 小说 48ms 展示缓冲已通过 burst/replacement/FIFO 门禁，但 transient tail 经 flush 后继续保留 `granularity` 目前只有代码链路证据，缺一条直接行为断言；不影响当前组合态构建与装机结论。
- Native Timeline scroll-driver 仍缺 safe-area composer 防双算、terminal 释放、最终高度收缩、rubber-band 手势所有权和真实窗口执行层回放；这些闭环完成前保持实验开关关闭。
- 默认 clean-list 的动态尾行已从历史 `LazyVStack` 分离，模拟器回放不再复现巨型尾行卸载或数行估算跳变；真机逐行观感与长历史滑动性能尚待当前安装包交互确认，不得用全量 `VStack` 或 offset 补偿替代该结构。
- `IOS_FIX_PLAN_2026-07-08.md` still marks B3b inline math as `BLOCKED-DESIGN`.
- B17b 的单轮累计 partial 与 expiration terminal 已有定点覆盖；仍缺“至少完成一轮 assistant/tool 后在下一轮 expiration”时完整保留既有 suffix/tool output 的端到端测试与实现。
- 删除会话会清理后台 job/payload，但已开始运行的本地 operation task 目前没有 coordinator 级取消句柄；tombstone 会拒绝落盘，付费/副作用工具仍可能继续执行，需独立生命周期切片处理。
- Some DeepRead workspace dedup/upsert and history warning-state work remains P2.
- Current visual/performance improvements still require real-device evidence; simulator/unit tests are necessary but insufficient.
- `ChatViewModelSelectedFileContextTests/testCancelledApprovedSearchDoesNotReplayStaleMessages` 当前在模拟器中单独运行会卡在 XCTest async expectation；已确认不是 CPU 忙循环，本轮未改这条既有测试或 transport harness。
- 小说创作 Phase A-F 已完成代码与首页接线，模拟器验证了入口、创建、资料和重启回显，当前 Stable 普通 Debug 包也已在真机完成构建、签名、覆盖安装和启动；真实 provider 的完整生成流、真机交互和系统 Files picker 仍无当前运行证据，不得表述成这些外部验收已经通过。

## Active References

Read only the references relevant to the task:

- Android novel replication: `docs/NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md`
- Novel creation implementation: `docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`
- Novel creation product contract: `docs/NOVEL_CREATION_SPEC.md`
- Novel creation UX simplification: `docs/NOVEL_UX_SIMPLIFICATION_PLAN.md`
- Novel creation ownership decision: `docs/adr/0007-novel-creation-owns-project-state.md`
- Domain language: `CONTEXT.md`
- Current streaming handoff: `HANDOFF_STREAM_PERF_C5_C6_2026-07-10.md`
- Current repair ledger: `IOS_FIX_PLAN_2026-07-08.md`
- Streaming architecture: `STREAMING_CHAT_ARCHITECTURE_AUDIT.md`
- Upward-growth decision history, including disproved paths: `STREAM_SCROLL_UPWARD_GROWTH_DECISION_2026-07-05.md`

Older handoff files are historical snapshots. Do not read or extend them unless the current task specifically depends on them.

## Update Contract

Update this file only when one of these changes:

- active branch or git policy;
- completed/abandoned work slice;
- documented verification baseline;
- current priority or known blocker;
- canonical reference that supersedes an older handoff.

Keep it concise and overwrite stale facts instead of appending a diary.
