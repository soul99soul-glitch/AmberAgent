# AmberAgent Current Project State

Last updated: 2026-08-06

本文件只记录当前可操作事实。开始任务时仍需核对真实 Git、代码、测试和设备状态；历史过程从 Git 追溯，不在这里追加会话日记。

## Repository

- Repo: `/Users/arquiel/Downloads/AI/amberagent-ios`
- Branch: `feat/ios-provider-parity-claude`
- Tracking: `origin/feat/ios-provider-parity-claude`；当前本地包含尚未 push 的 review fixes 提交。
- Current committed HEAD: 以实时 `git rev-parse HEAD` 为准；`61c3b4e46` 是本轮 review fixes 之前的远端基线。
- Worktree: 本轮 MiniApp 持久化闭环、卡片交互、SVG 保存命中区、回归测试和文档修订已提交；是否干净以实时 `git status --short` 为准。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

## Current Product Truth

- 当前工作主线是原生 iOS + KMP 共享能力。`app/` 是 Android 应用，不代表 iOS 运行时。
- 默认且唯一生产 Chat 列表路径是 `NativeChatTimelineView`。`ChatSwiftUIMessageList` 只在 `CHAT_PERF_REPLAY` 下保留，UICollectionView 路径只作非默认回归。
- 小说创作已经具备创作 / 正文 / 设定三入口、独立创作与剧情同步模型、Quick Start、资料建议、收录、编辑、剧情同步、分支/Fork、整章润色、导入导出和中断恢复。小说项目文档是领域权威，普通 Chat/Memory/Workspace 不是小说存储。
- 共创模式现已与规格对齐：`needsSync` 时仍可讨论，但正式正文生成、整章重写、润色和对应 retry 均失败闭锁。共创 / 代笔双模式计划见 `docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`（Active；Phase 0–3c 完成，下一刀真机验收）。
- Chat、小说和模型议会在页面退出后由 App 级 owner 继续持有运行；iOS 本地后台仍受系统调度约束，不等于无限后台。
- 只有官方 OpenAI Responses API 的小说正文、重新生成和单章润色已接入服务端 background response + cursor 恢复。Quick Start、讨论工具循环、Chat、模型议会及其他 provider 仍是本地 best-effort。
- Live Activity、锁屏卡和 continued-processing task 按 `runId` 独立管理；旧 run 的完成、取消、深链和系统移除回调不得作用于新 run。

## Novel Collaboration Mode Phase 0 / 1 / 2

- Phase 0：`canStart(.prose)`、`NovelGenerationReducer`、injection planner 与 prose retry 投影均要求分支 `synchronized`；同步横幅与写正文占位对齐；精确重试同步 composer mode/granularity。
- Phase 1：项目 `collaborationMode`；分支级 `NovelChapterPlanRecord`（草稿/确认 + digest）；顶栏「项目控制」面板可切共创/代笔（切代笔做策划包就绪检查，不强制本章合同）；确认合同注入整章 prose；代笔写整章无确认合同则 UI/`canStart`/reducer/retry 挡；收录仍人手。
- Phase 2（验收深度 B）：候选/run 绑定 `chapterPlanDigest`；收录 digest 不匹配则拒；`systemAutoCollect` 来源；结构化 `chapterPlanAcceptance`（stateSync 模型）；SessionViewModel 单章 pipeline（写→验收→自动收录→同步→暂停）；面板开始/暂停/继续；进行中硬切共创挡。
- Phase 2 review 修复：复用候选只认 `progress.candidateID`；收录成功即清合同；继续按钮严格按 `canStart`；代笔中禁用合同编辑与模式 Picker；合同错误提示就近显示；清除需确认；合同字段常驻标签。
- Review 修复：协作模式/清除合同的历史账本校验不再断言当前态；`canStart` 按 run 粒度判断；面板 sheet 改为 large、合同按钮 44pt、IME commit、Picker 本地回滚。
- 完成计划后的生产 review 修复：代笔开始时重新校验策划包、本章合同与当前主分支；项目级模型 Picker 等待持久化成功后再关闭，失败留在原位并提示；小说 composer 输入命中区不低于 44pt；元信息切换遵循 Reduce Motion。
- Watch `WKCompanionAppBundleIdentifier` 改为字面量 `app.amber.ios`；`IOSMiniAppBridgeRuntimeTests` 的 `async let` 断言改为先 await 再 XCTAssert。

### Phase 3a — 跨章防复读（薄回执）

- Snapshot 携带有界 `recentWrittenHighlights`；收录后的 `finalizeCollection` / 手动同步 finalize 用新事件 summary 合并截断；旧文档缺字段 decode 为空。
- 整章 prose 注入 `RECENT WRITTEN BEATS`（计入 required 预算预检）；续写不注入。
- 验收 prompt `novel.chapter-plan-acceptance.v2`：必填 `obviousRepetition`；decoder 兼容 schemaVersion 1。
- Pipeline：合同通过但 `obviousRepetition` 非空 → 暂停不自动收录，继续时重写不复用同稿；收录后按 binding 清合同，失败则停；完成后 detail 提示已记入要点条数。
- Review 修复：失败 detail 用红色 Label；代笔中合同字段禁用；workspace 清合同后字段回填；代笔按钮补 contentShape。

### Phase 3b — 连续性软门

- 项目偏好 `pauseGhostwriteOnBlockingContinuity`（默认 true；旧文档缺字段 decode 为 true）；面板 Toggle「连续性出现「严重」问题时暂停」。
- 代笔：合同验收通过且无复读后、自动收录前调用 `auditContinuityIncludingCandidate`（已有正文 + 候选下一章）。
- 仅 `blocking`（界面「严重」）暂停不收录；`failedChunkCount > 0` 亦暂停（审计未结论不得放行）；`major`/`minor` 不挡。
- 继续：同 binding 保留 `autoCollectedCandidateIDs` 与可复用候选；复读/严重连续性强制重写；已 collected 同 digest 不再写第二遍。
- Review 修复：`ghostwriteProgressStorage` 可观察；owned-run 取消；blocker 文案与 Toggle 错误就近显示；`.cancelled` 非红错。

### Phase 3c — 审稿模型 / 下一弧 / 代笔看板（薄可交付）

- `NovelModelRole.review`：项目 `reviewModelPolicy` + App 默认偏好；设置页与项目设置暴露「审稿模型」；合同验收与连续性审计走 `.review`。
- 分支级 `NovelUpcomingArcRecord`（最多 8 条）；整章 prose 注入 `UPCOMING ARC`；面板「下一弧」保存/清除；续写不注入。
- 面板拆「代笔看板」（只读回执）与「代笔推进」（Toggle/按钮）；审稿显示走 effectivePolicy + displayName；步骤回执用短码，暂停原因只在 detail。

### Verification

- `NovelCollaborationModeTests` **22/22 PASSED**（含下一弧 upsert/clear、整章注入、看板步骤回执）。
- `NovelProjectConfigurationTests` 审稿模型独立持久化 + 偏好三角色、相关 wiring 两项 **PASSED**。
- `NovelPromptCatalogTests/testCatalogSnapshot` 已随 acceptance v2 更新哈希。
- 完成后 review 定点与配置/协作回归 **158/158 PASSED**；Session、reducer、注入、连续性、生命周期、恢复、文档与 prompt 扩大回归 **296/296 PASSED**，两组共 **454 passed / 0 failed**。
- 最新 Simulator Debug 包已重新安装并成功启动；当前自动化无法稳定进入小说深层页面，且随后 CoreSimulatorService 连接失效，因此这里不把启动或静态读码当成深层视觉验收。
- 真机：代笔单章闭环、审稿模型生效、下一弧注入、看板回执 — 仍待设备验收。
- 下一刀：真机 Phase 2/3a/3b/3c 验收。

## Current Review Fixes

- MiniApp 生成/修订现在以目标 app 的状态切片记录本次 mutation；前台或后台 conversation 保存失败时，只在该 app 未被后续修改的前提下恢复原记录与版本，不影响其他小应用。
- Workspace artifact 改为 conversation 首次保存成功后再同步；同步失败会保留可运行的 MiniApp 与已落盘聊天卡片，并在当前消息中显示失败原因后尝试补存提示。
- MiniApp 卡片导出缺失记录或临时文件写入失败时显示 alert；操作按钮保持原 bordered 视觉，提供 44pt 命中区，并在横向空间不足时切换纵向布局。
- SVG“保存”胶囊保持 28pt 视觉，只把交互命中形状扩大到 44pt。
- iOS 核心记忆在 `AppShell` 首次渲染前同步加载；损坏文件会保留原件并停止写入，缺失文件清空内存态，所有新增/编辑/删除在原子写失败时回滚完整快照并返回失败。
- Chat 召回统一使用运行时 `maxItems` / `maxPromptChars`、scope、归档/过期与相关性策略；实际进入 provider 请求的记录会只更新 `lastUsedAt` 并持久化，不再把编辑时间误当使用时间。
- 模型写入审批会区分保存、编辑、删除；删除展示目标正文并使用 destructive 语义，被拒绝或 scope 禁用的正文不落审批历史。记忆页补齐可观察刷新、陈旧编辑保护、错误反馈、审批历史清除和 44pt 交互区域。
- 核心记忆专用测试 **14 passed / 0 failed**，工具写入/审批链路定点 **5 passed / 0 failed**；iPhone 17 Pro Simulator 已实屏核对记忆主页首屏，审批卡真实触发态与真机 Dynamic Type 仍待设备验收。

## Generative UI Current State

目标是在 iOS 原生 timeline 中复用现有流式 `show-widget` 解析与安全 SVG 渲染，补齐 Android 已有的视觉意图路由、模型提示和终态兜底，不新增第二套渲染器或滚动 owner。

本轮新增：完成态且安全净化后的 SVG 卡片右上角提供 44pt“保存 SVG”命中区；通过系统 Files 导出从净化 HTML 中提取的 `.svg`；不导出 raw `widgetCode`、slides 或 full_html 封面预览；导出失败显示 alert。相关契约测试覆盖净化提取、完成态/安全门控和安全文件名。

- `GenerativeUiPlanner` 已从 Android app 下移到 `core:ai:generation:api`，并与共享 show-widget prompt/protocol 一起导出给 Swift；Android 保持原 FQN 调用。
- iOS 请求入口按真实用户意图注入共享 prompt：直接流程图/架构图/PPT 请求不暴露无关工具；图片生成和需要搜索、文件、skill、subagent 的请求保留工具路径。
- 直接 SVG/PPT 请求的流终态必须含完整可解析的 show-widget；缺失时清掉坏的可见 assistant 草稿，关闭工具与 reasoning 后最多重试一次。第二次仍失败就保留真实模型结果，不伪造本地 SVG，也不循环重试。
- 需要搜索、文件或其他工具的画图请求仍保留工具链，但最终 assistant 同样必须交付图；工具执行前后的 background handoff 会重新注入同一视觉协议 prompt。
- 这份要求和“是否已重试”随 iOS 前后台 handoff 持久化；后台补绘开始前原子写入补绘请求和 attempted checkpoint，输出上限截断但没有未闭合工具调用时仍可补绘一次。
- foreground terminal 以 `runId` 校验所有权，旧 run 的异步保存/Live Activity 回调不能清掉新 run。后台 expiration 在异步 finalize 前即暴露 terminal owner，save continuation 不再漏掉已抢占的终态。
- full_html 只通过专用 validator 成为完成卡片；共享 `amberagent.local/full-html` runtime URL 会改写到本地资源，非法 deck 不再靠静态封面伪装成功。
- `[ROUTE:image|svg|diagram|slides]` 仍参与路由，但原生用户气泡不再显示该元数据。partial/complete 卡片 identity 保持稳定；卡片初始/最小高度为 96pt，操作按钮 44pt 且窄屏纵向排列，WebView 流更新按一帧合并。结构化折线不再越过标签，单节点 flow 居中。

### Verification

- “保存 SVG”导出 helper 的 Python 镜像契约已核对：只提取净化 SVG，partial/unsafe/非 SVG 均被门控；`git diff --check` 通过。
- 本机定点 `xcodebuild -only-testing:iosAppTests/IOSGenerativeWidgetParserTests/testSVGExport*` 未能编译：沙箱无法写 `~/.cache/clang/ModuleCache` 与 SwiftPM ManifestLoading 诊断文件，且 CoreSimulatorService 不可用；这不是本次按钮逻辑失败。
- `GenerativeUiPlannerTest` 与 `IosChatBackgroundPayloadJsonBridgeTest` 的 JVM 定点测试均 **BUILD SUCCESSFUL**。
- SVG/parser、full_html runtime、前台 stale-run 和后台 expiration 定点：**22 passed / 0 failed / 0 skipped**；完整 `ChatViewModelSelectedFileContextTests`：**63 passed / 0 failed / 0 skipped**。
- 扩大到 `ChatSwiftUIStreamReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、`IOSParityRedLightTests`：**170 passed / 1 failed / 0 skipped**。唯一失败是范围外的 24KB 纯文本 pacing 契约冲突：当前实现允许单拍 36 字，测试要求绝大多数更新不超过 24 字；隔离复跑仍失败。该用例不经过 widget parser/card，也未放宽阈值。
- Android app 的定点 `GenerativeUiPlannerTest` / `GenerationPromptsTest` 被当前工作区中范围外的 Model Council 缺失符号阻断在 app 编译阶段；共享 planner 自身的 JVM 测试已通过。

### Remaining Acceptance

- 真机使用至少一个 OpenAI/Claude-compatible provider 发起“画流程图/架构图”请求，确认 SVG 在同一条 assistant timeline card 中随流逐步出现，结束后无需重试或至多自动重试一次；完成后点右上角“保存 SVG”，确认 Files 导出可用、文件名安全、失败有提示。
- 再验证图片请求仍调用 `generate_image`、需要外部上下文的视觉请求仍先完成工具链、PPT 最终落为完整 `full_html` deck。
- Simulator 性能探针不能替代 ProMotion 真机的卡片高度增长和滚动手感验收。

## Novel Streaming Current State

目标是消除小说长文流式生成中数次大幅高度跳变及终态最后一拍闪烁，不新增滚动 owner 或几何补偿。

- Chat/小说共享 pacer 的单拍上限从 64 收紧到 36 个字符，仍沿用 48ms 节拍。
- 小说 completed、interrupted、failed、persistence-blocked 先按现有节拍排空可见正文，再切 durable terminal；排空期间关闭 Stop，并继续用既有 busy gate 阻止下一次 mutation。
- 终态规范化从当前可见正文前缀继续 pacing，避免模型误包 Markdown 围栏时整章一次替换。
- 小说 Native Timeline 复用 Chat 已验证的 UIKit 手势判定，程序化 `.interacting` 不再被误判成用户上滑。
- cancel 或 binding 失效会撤销排空标记；persistence-blocked 排空后仍回到原有重试入口。

### Verification

- 相关门禁：`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、4 条 SwiftUI 长文/终态回放及共享 pacing，共 **206 passed / 0 failed / 0 skipped**。
- 扩大到完整 Chat SwiftUI：**223 passed / 1 failed**。唯一失败是独立完整 Markdown 表格 display-link probe 的 simulator 时序阈值（p95 约 40.6ms 对 40ms）；隔离复跑有过有败，未放宽阈值，也没有据此修改产品路径。
- 最新产品包已用 Team `89QRFX9548` 完成 iPhone Air Debug arm64 构建，`codesign --verify --deep --strict` 通过。
- 含「保存 SVG」的主包已于 2026-08-05 19:41 覆盖安装并启动 `app.amber.ios`；安装容器 `0693C392-DCFC-4B56-8DE1-EE37945B4DFF/iosApp.app`；主 Debug dylib SHA-256：`e4062b24644f92abc199f50dd84562ec349d868c11113c45df498eb0ba51a8a6`（二进制含「保存 SVG」）。覆盖安装未卸载，应保留既有数据。Files 导出与 SVG 卡按钮可见性仍待手测。

### Remaining Acceptance

- 在真机用真实 provider 生成长章节，观察中途高度增长、终态切换和轻拖上滑期间是否仍闪烁或被拉回底部。
- 真机 ProMotion、rubber-band、键盘安全区和后台系统到期行为不能由 Simulator/单测替代。
- 该 slice 已随 `61c3b4e46` commit/push；本页顶部列出的当前 review fixes 尚未安装到真机。

## Recently Landed Baseline

- `da71c8597`：修复 Quick Start 宽容解析边界、失败草稿污染、上下文人物建议和小说流式滚动；受影响小说回归 **439 passed**。
- `086e57525`：补齐长时间流式任务的本地后台所有权和官方 OpenAI 小说生成的服务端恢复路径。
- `5a767e480`：完善小说流式生成、Ask User、资料与设置交互。
- `db7371fcb`：修复交互终态串线、中文输入最后一拍和小说编辑内容失真。

## Current Priorities

1. 先完成 Generative UI 的真机真实 provider 验收，重点看同卡片渐进 SVG、一次兜底边界、右上角“保存 SVG”导出、图片工具路由和 full_html deck 完整性。
2. 完成当前流式实现的真机长文手感验收；若仍跳变，记录发生阶段、是否触摸屏幕、是否终态以及可见内容变化，再沿现有 owner 定位。
3. 只在真实复现支持时继续调整 pacer、终态排空或 Native Timeline 手势判定，不加第二套状态机或 offset 补偿。
4. 后台能力下一步优先补真实 OpenAI 账号下的 expiration / 强杀 / 冷启动恢复证据；其他 provider 不伪装成服务端 durable job。
5. 小说共创 / 代笔：Phase 0–3c 已落地；下一刀真机验收（单章闭环、审稿模型、下一弧注入、看板回执）。
6. Android 小说复刻属于 Android 主仓；本仓的 `NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅是跨仓草案。

## Known Risks

- 当前产品改动与文档整理共处一个脏工作区；修改重叠文件前必须先读单文件 diff。
- “保存 SVG”契约 helper 已覆盖，但 Files picker / 真机导出体验与完整 `IOSGenerativeWidgetParserTests` 编译运行仍缺本机沙箱外证据。
- Generative UI 的终态契约已经自动化验证，但模型是否能在真实 provider 的 token/window 限制内稳定输出完整 SVG/full_html 仍需真机与真实账号证据。
- 当前 Chat pacer 上限 36 与 24KB 长文门禁的 24 字要求冲突；这是与 widget 无关的既有问题，需决定恢复 24、调整发布策略或同步契约。
- Android app 回归当前受范围外 Model Council 编译缺口阻断；不能把共享 planner 测试通过等同于 Android app 全门禁通过。
- 长表格 display-link 性能探针在 Simulator 40ms 边界附近波动，尚无足够证据修改 renderer identity、发布 owner 或测试阈值。
- iOS continued-processing 由系统决定调度与终止；用户从 App Switcher 强制结束后，本地 SSE 无法继续。
- 服务端恢复在首个 `response.created` cursor 落盘前遭进程终止时没有 response ID，无法恢复；还受服务端保留窗口和账户数据策略约束。
- 历史消息行内公式 `mathInline` 仍缺已接受的渲染设计；不要用字符串替换或额外 layout 分支硬补。
- 后台到期已覆盖单轮累计 partial，但仍缺“完成至少一轮 assistant/tool 后，下一轮 expiration”完整保留既有 suffix/tool output 的端到端契约。
- 小说真实 provider 全流程、长文手势、后台到期、Files picker 和最终视觉仍需设备证据；构建、安装和单测通过不等于这些体验已验收。

## Canonical References

先看 [`README.md`](README.md) 的主题地图。当前常用权威入口：

- 工程规则：`AGENTS.md`、`iosApp/AGENTS.md`
- 小说局部规则：`iosApp/iosApp/NovelCreation/AGENTS.md`
- 小说产品与领域：`docs/NOVEL_CREATION_SPEC.md`、`CONTEXT.md`
- 小说所有权 ADR：`docs/adr/0007-novel-creation-owns-project-state.md`
- 小说实现基线：`docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`
- 共创 / 代笔计划：`docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`
- Live Activity 视觉：`docs/ACTIVITY_ISLAND_REDESIGN.md`

## Update Contract

仅在分支/工作区、当前产品事实、最近验证、优先级、已知阻塞或权威入口变化时原地更新。删除已失效内容，保持可在几分钟内读完；不要恢复按日期无限追加的日志结构。
