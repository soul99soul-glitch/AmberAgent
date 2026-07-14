# AmberAgent Current Project State

Last updated: 2026-07-14

本文件只记录当前可操作事实。开始任务时先结合真实 git 状态核对；状态变化后原地更新，不为普通 session 继续新增 handoff。

## Repository

- Repo: `/Users/arquiel/Downloads/AI/amberagent-ios`
- Branch: `feat/ios-provider-parity-claude`
- Remote tracking: `origin/feat/ios-provider-parity-claude`
- Worktree: 本轮小说创作导航、键盘、后台生命周期、损坏项目删除、收录简化、旧任务恢复、正文入口与发送卡死修复已纳入当前提交；提交后工作区无 staged、unstaged 或 untracked 文件。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

## Current Product Focus

本仓是 iOS 主线（`amberagent-ios`）。曾在本仓误开工 Android「小说创作」Phase 0，**已全部撤销**（`:feature:novel`、`test-fixtures/novel-v1`、`settings.gradle.kts` include）。Android 复刻应改到 Android 主仓执行；`docs/NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅作计划参考，本仓不落地 Android production。

iOS Phase A-F 与架构精简 S1-S3 仍是领域基线；UX 简化 S1-S7 的三路 review 确认项已完成修复并通过自动化门禁。Quick Start 现按主要角色生成独立建议，导入/Fork/撤销/分支切换等调用链不再靠隐式状态猜成功。S4 持久化压缩等待 V2 项目 schema。iOS 真实 provider、真机交互和系统 Files 交互仍是外部运行证据缺口。

默认可用路径是 `ChatSwiftUIMessageList`。Native Timeline / UICollectionView 仍属于实验或 fallback 路径，不能用其测试结果替代默认路径验证。

## Latest Completed Slices

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
