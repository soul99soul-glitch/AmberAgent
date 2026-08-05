# AmberAgent Current Project State

Last updated: 2026-08-05

本文件只记录当前可操作事实。开始任务时仍需核对真实 Git、代码、测试和设备状态；历史过程从 Git 追溯，不在这里追加会话日记。

## Repository

- Repo: `/Users/mi/Downloads/AI/AmberAgent-iOS`
- Branch: `feat/ios-provider-parity-claude`
- Tracking: `origin/feat/ios-provider-parity-claude`
- Current committed HEAD: `3549d0c3c`（让 iOS timeline 的实时 SVG 在前后台可靠收口）；分支仍 `ahead 1`，尚未 push。
- Worktree: 有未提交的 Chat/小说流式节拍与小说终态排空修复、Generative UI 卡片“保存 SVG”入口、本轮文档治理改动，以及其他并发工作；以实时 `git status --short` 为准。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

当前 iOS 流式 slice 的代码改动范围：

- `iosApp/iosApp/ChatGenerationCoordinator.swift`
- `iosApp/iosApp/NovelCreation/NovelSessionView.swift`
- `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift`
- `iosApp/iosAppTests/IOSSettingsWiringTests.swift`
- `iosApp/iosAppTests/NovelSessionReplayTests.swift`
- `iosApp/iosAppTests/NovelSessionViewModelTests.swift`

## Current Product Truth

- 当前工作主线是原生 iOS + KMP 共享能力。`app/` 是 Android 应用，不代表 iOS 运行时。
- 默认且唯一生产 Chat 列表路径是 `NativeChatTimelineView`。`ChatSwiftUIMessageList` 只在 `CHAT_PERF_REPLAY` 下保留，UICollectionView 路径只作非默认回归。
- 小说创作已经具备创作 / 正文 / 设定三入口、独立创作与剧情同步模型、Quick Start、资料建议、收录、编辑、剧情同步、分支/Fork、整章润色、导入导出和中断恢复。小说项目文档是领域权威，普通 Chat/Memory/Workspace 不是小说存储。
- Chat、小说和模型议会在页面退出后由 App 级 owner 继续持有运行；iOS 本地后台仍受系统调度约束，不等于无限后台。
- 只有官方 OpenAI Responses API 的小说正文、重新生成和单章润色已接入服务端 background response + cursor 恢复。Quick Start、讨论工具循环、Chat、模型议会及其他 provider 仍是本地 best-effort。
- Live Activity、锁屏卡和 continued-processing task 按 `runId` 独立管理；旧 run 的完成、取消、深链和系统移除回调不得作用于新 run。

## Generative UI Uncommitted Slice

目标是在 iOS 原生 timeline 中复用现有流式 `show-widget` 解析与安全 SVG 渲染，补齐 Android 已有的视觉意图路由、模型提示和终态兜底，不新增第二套渲染器或滚动 owner。

本轮新增：完成态、安全净化后的 SVG 卡片右上角提供 44pt“保存 SVG”入口；通过系统 Files 导出 `.svg`，优先导出净化 HTML 中的 SVG 片段，必要时回退原始 SVG；导出失败显示 alert。相关契约测试覆盖净化优先、原始回退、完成态/安全门控和安全文件名。

- `GenerativeUiPlanner` 已从 Android app 下移到 `core:ai:generation:api`，并与共享 show-widget prompt/protocol 一起导出给 Swift；Android 保持原 FQN 调用。
- iOS 请求入口按真实用户意图注入共享 prompt：直接流程图/架构图/PPT 请求不暴露无关工具；图片生成和需要搜索、文件、skill、subagent 的请求保留工具路径。
- 直接 SVG/PPT 请求的流终态必须含完整可解析的 show-widget；缺失时清掉坏的可见 assistant 草稿，关闭工具与 reasoning 后最多重试一次。第二次仍失败就保留真实模型结果，不伪造本地 SVG，也不循环重试。
- 需要搜索、文件或其他工具的画图请求仍保留工具链，但最终 assistant 同样必须交付图；工具执行前后的 background handoff 会重新注入同一视觉协议 prompt。
- 这份要求和“是否已重试”随 iOS 前后台 handoff 持久化；后台补绘开始前原子写入补绘请求和 attempted checkpoint，输出上限截断但没有未闭合工具调用时仍可补绘一次。
- foreground terminal 以 `runId` 校验所有权，旧 run 的异步保存/Live Activity 回调不能清掉新 run。后台 expiration 在异步 finalize 前即暴露 terminal owner，save continuation 不再漏掉已抢占的终态。
- full_html 只通过专用 validator 成为完成卡片；共享 `amberagent.local/full-html` runtime URL 会改写到本地资源，非法 deck 不再靠静态封面伪装成功。
- `[ROUTE:image|svg|diagram|slides]` 仍参与路由，但原生用户气泡不再显示该元数据。partial/complete 卡片 identity 保持稳定；卡片初始/最小高度为 96pt，操作按钮 44pt 且窄屏纵向排列，WebView 流更新按一帧合并。结构化折线不再越过标签，单节点 flow 居中。

### Verification

- “保存 SVG”导出 helper 的 Python 镜像契约已核对：净化 SVG 优先、原始 SVG 回退、partial/unsafe/非 SVG 均被门控；`git diff --check` 通过。
- 本机定点 `xcodebuild -only-testing:iosAppTests/IOSGenerativeWidgetParserTests/testSVGExport*` 未能编译：沙箱无法写 `~/.cache/clang/ModuleCache` 与 SwiftPM ManifestLoading 诊断文件，且 CoreSimulatorService 不可用；这不是本次按钮逻辑失败。
- `GenerativeUiPlannerTest` 与 `IosChatBackgroundPayloadJsonBridgeTest` 的 JVM 定点测试均 **BUILD SUCCESSFUL**。
- SVG/parser、full_html runtime、前台 stale-run 和后台 expiration 定点：**22 passed / 0 failed / 0 skipped**；完整 `ChatViewModelSelectedFileContextTests`：**63 passed / 0 failed / 0 skipped**。
- 扩大到 `ChatSwiftUIStreamReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、`IOSParityRedLightTests`：**170 passed / 1 failed / 0 skipped**。唯一失败是范围外的 24KB 纯文本 pacing 契约冲突：当前未提交代码允许单拍 36 字，测试要求绝大多数更新不超过 24 字；隔离复跑仍失败。该用例不经过 widget parser/card，本轮未覆盖用户现有 pacing 改动，也未放宽阈值。
- Android app 的定点 `GenerativeUiPlannerTest` / `GenerationPromptsTest` 被当前工作区中范围外的 Model Council 缺失符号阻断在 app 编译阶段；共享 planner 自身的 JVM 测试已通过。

### Remaining Acceptance

- 真机使用至少一个 OpenAI/Claude-compatible provider 发起“画流程图/架构图”请求，确认 SVG 在同一条 assistant timeline card 中随流逐步出现，结束后无需重试或至多自动重试一次；完成后点右上角“保存 SVG”，确认 Files 导出可用、文件名安全、失败有提示。
- 再验证图片请求仍调用 `generate_image`、需要外部上下文的视觉请求仍先完成工具链、PPT 最终落为完整 `full_html` deck。
- Simulator 性能探针不能替代 ProMotion 真机的卡片高度增长和滚动手感验收。

## Active Uncommitted Slice

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
- 当前 slice 尚未 commit/push；不要把已安装包误认为远端分支内容。

## Recently Landed Baseline

- `da71c8597`：修复 Quick Start 宽容解析边界、失败草稿污染、上下文人物建议和小说流式滚动；受影响小说回归 **439 passed**。
- `086e57525`：补齐长时间流式任务的本地后台所有权和官方 OpenAI 小说生成的服务端恢复路径。
- `5a767e480`：完善小说流式生成、Ask User、资料与设置交互。
- `db7371fcb`：修复交互终态串线、中文输入最后一拍和小说编辑内容失真。

## Current Priorities

1. 先完成 Generative UI 的真机真实 provider 验收，重点看同卡片渐进 SVG、一次兜底边界、右上角“保存 SVG”导出、图片工具路由和 full_html deck 完整性。
2. 完成当前未提交流式 slice 的真机长文手感验收；若仍跳变，记录发生阶段、是否触摸屏幕、是否终态以及可见内容变化，再沿现有 owner 定位。
3. 只在真实复现支持时继续调整 pacer、终态排空或 Native Timeline 手势判定，不加第二套状态机或 offset 补偿。
4. 后台能力下一步优先补真实 OpenAI 账号下的 expiration / 强杀 / 冷启动恢复证据；其他 provider 不伪装成服务端 durable job。
5. Android 小说复刻属于 Android 主仓；本仓的 `NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅是跨仓草案。

## Known Risks

- 当前产品改动与文档整理共处一个脏工作区；修改重叠文件前必须先读单文件 diff。
- “保存 SVG”契约 helper 已覆盖，但 Files picker / 真机导出体验与完整 `IOSGenerativeWidgetParserTests` 编译运行仍缺本机沙箱外证据。
- Generative UI 的终态契约已经自动化验证，但模型是否能在真实 provider 的 token/window 限制内稳定输出完整 SVG/full_html 仍需真机与真实账号证据。
- 当前未提交的 Chat pacer 上限 36 与 24KB 长文门禁的 24 字要求冲突；这是与 widget 无关的现有工作，需由该 slice 的 owner 决定恢复 24、调整发布策略或同步契约。
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
- Live Activity 视觉：`docs/ACTIVITY_ISLAND_REDESIGN.md`

## Update Contract

仅在分支/工作区、当前产品事实、最近验证、优先级、已知阻塞或权威入口变化时原地更新。删除已失效内容，保持可在几分钟内读完；不要恢复按日期无限追加的日志结构。
