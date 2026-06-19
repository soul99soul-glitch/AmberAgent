# iOS WebMount Aggressive Goal Plan 2026-06-19

本文档用于并行 Session D：AmberAgent iOS WebMount 大工程。

> **状态（2026-06-19）：核心闭环已落地，剩余为 parity/账号类缺口。** 当前 worktree 已有 Swift 原生 registry/settings/URLPolicy/WKWebView runtime/cookie summary/受限 bridge/8 个最小 `wm_*` 工具、ChatViewModel tool declaration + dispatch 接线、以及 mock XCTest 覆盖；后续仍缺 OAuth、signed fetch、站点 adapter、Android 23 primitive tools parity、真实账号登录链路与 simulator 真机视觉验证。

目标是在一个独立 session 内激进推进 iOS WebMount：从当前“一个可加载网页的 WKWebView 预览页”，推进到 Swift 原生站点注册表、WebMount 设置、真实 WKWebView 站点运行时、导航安全策略、cookie/session 管理、受限 JS bridge、最小 wm 工具执行器、WebMount 页面和站点详情 UI、以及可验证测试。它不追求一次复制 Android 全部 23 个 primitive tools 和所有站点 adapter，但必须把 iOS 上能独立闭环的核心 WebMount runtime 做出来，不能只继续写“执行待接”说明。

## 推荐执行版（中文，可直接复制）

下面版本约 2,824 个字符，适合 4,000 字符限制的 Goal 输入框。

```text
/goal 激进推进 AmberAgent iOS WebMount 大工程，在独立 session 把当前 WebMountView 的 WKWebView 预览推进到可用闭环：Swift 原生 IOSWebMountSite/Registry/Settings/URLPolicy/Runtime/CookieStore/Bridge/ToolExecutor，真实 WebMountView/WebMountSiteView，最小 wm 工具和 tests。先对照 Android WebMountManager、UserSiteRegistry、WebViewPool、SessionHandle、JsBridge、ProfileBridge、WebMountPrimitiveTools、WebMountSiteTools；优先实现 seed 站点、add/remove/restore、enable/disable、globalEnabled 默认关、evalEnabled 独立关、allowlist、真实 WKWebView 加载、status/title/currentURL/error、cookie summary/clear、受限 bridge state/extract/get，以及 wm_stations/wm_open/wm_state/wm_extract/wm_get/wm_back/wm_forward/wm_clear_session。尽量接 IOSLocalToolExecutor/IOSPermissionModels/ChatViewModel 安全工具声明；wm_eval/OAuth/signed fetch/站点 adapter 返回诚实 unsupported。
验证：先跑 git status --short --branch、git log --oneline --decorate -12；读取 iosApp/iosApp/WebMountView.swift、IOSLocalToolExecutor.swift、IOSPermissionModels.swift、ChatViewModel.swift、AppShell.swift 和上述 Android WebMount 文件；新增/更新 XCTest 覆盖 registry seed/load/save、add/remove/restore、URL scheme/host allowlist、settings、cookie redaction/clear、bridge gating、tool catalog、wm_stations、wm_open、wm_extract mock、executor allow/deny；尽量跑 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build/test；可用 build-ios-apps 时用 simulator 验证 WebMount 页面和 WKWebView 真加载；环境缺失则记录精确错误，并用 xcrun swiftc -parse、纯 Swift/mock tests 继续。
约束：遵守 AGENTS.md；保护已有改动，不回滚 MiniApp、Board、Remote Sync、Search、Skill、plan；不处理 iosApp/iosApp/CouncilChatRuntimeView.swift；不碰密钥、真实账号、生产数据、证书、google-services.json、真实 OAuth；不默认开启任意 JS；不输出 cookie 值、token、Authorization header 或完整敏感 URL；无用户明确动作不登录、不清 cookie、不上传数据。
边界：允许改 WebMountView.swift、AppShell 的 WebMount 路由小适配、IOSLocalToolExecutor.swift、IOSPermissionModels.swift、ChatViewModel.swift 的最小 WebMount 接线、新增 IOSWebMount*.swift、小 SwiftUI 组件和 iosAppTests；可小改 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md。禁止改 MiniApp、Board、Sync、Android 业务逻辑、Gradle、Xcode project 生成物、发布脚本和无关重构；若 ChatViewModel/权限模型冲突，先完成独立 executor/UI/tests 并报告接线点。
迭代策略：不要在 registry 或第一屏 UI 后结束；按 registry、settings、URLPolicy、WKWebView runtime、cookie、bridge、tool executor、权限和 ChatViewModel 接线、UI、tests 连续推进。每个泳道跑最小验证；某泳道被 WebKit/simulator/接线同类问题卡 2 次就降级为 mockable adapter 或诚实后续项，继续别的泳道。优先真实本地 runtime 和安全边界，其次 agent 工具接线，再次 adapter，最后 polish。
完成条件：registry 持久化并 seed HN/Reddit/GitHub/Bilibili/X/微博/掘金/知乎/飞书，支持 add/remove/restore/enable/disable；settings 和 URL policy 可验证；站点详情能真开 WKWebView 并显示状态、bridge、cookie summary、clear session；bridge 提供只读 state/extract/get 且无 arbitrary eval；executor 至少支持上述 8 个 wm 工具并对未实现项诚实 unsupported；权限页展示风险；若接入 ChatViewModel，开启后能声明安全工具并回填结果；测试或构建通过，或环境阻塞精确记录。
暂停条件：需要真实 OAuth/账号密码/cookie/生产数据/Apple 登录、改 Xcode project、删除用户未跟踪文件、绕过 WebKit 安全、默认开启 eval、产品决定 agent 自动网页登录或跨站操作、与 MiniApp/Board/Sync 文件冲突无法安全合并，或同一外部阻塞连续 3 次。
```

## 详细执行参考

```text
/goal 激进推进 AmberAgent iOS WebMount 大工程：在独立 session 中尽可能完成 iOS WebMount 可用闭环，而不是停在当前 WKWebView 预览和说明文案。基于当前 WebMountView.swift 中的 SimpleWebView 预览、WebMountSiteView 占位详情、iOS 本地工具权限模型和 ChatViewModel 工具调用路径，对照 Android WebMountManager、UserSiteRegistry、WebViewPool、SessionHandle、JsBridge、ProfileBridge、WebMountCookieProvider、WebMountPrimitiveTools 和 WebMountSiteTools，继续实现 Swift 原生 IOSWebMountSite、IOSWebMountRegistry、IOSWebMountSettingsStore、IOSWebMountRuntime、IOSWebMountCookieStore、IOSWebMountBridge、IOSWebMountToolExecutor、WebMountView 和 WebMountSiteView 真实 UI、以及相关 XCTest。优先完成站点列表、seed restore、add/remove、enable/disable、安全 URL allowlist、真实 WKWebView 加载、页面状态、文本和链接提取、cookie 查看和清除、bridge probe、wm_stations、wm_open、wm_state、wm_extract、wm_get、wm_back、wm_forward、wm_clear_session；尽可能把这些工具接入 IOSLocalToolExecutor 和现有 tool declaration 路径。wm_eval、OAuth token、signed fetch、站点专用 adapter 可以做安全骨架和诚实降级，但不要因为它们没完成而停止本地可验证闭环。
验证：开始先运行 git status --short --branch、git log --oneline --decorate -12，并读取 iosApp/iosApp/WebMountView.swift、iosApp/iosApp/IOSLocalToolExecutor.swift、iosApp/iosApp/IOSPermissionModels.swift、iosApp/iosApp/ChatViewModel.swift、iosApp/iosApp/AppShell.swift，以及 Android 侧 WebMountModels.kt、WebMountManager.kt、UserSite.kt、UserSiteRegistry.kt、WebViewPool.kt、SessionHandle.kt、JsBridge.kt、ProfileBridge.kt、WebMountPrimitiveTools.kt、WebMountNavigationTools.kt、WebMountSiteTools.kt 作为对照；实现后新增或更新 Swift XCTest 覆盖 registry seed/load/save、add/remove/restore、URL allowlist、blocked scheme、host matching、settings globalEnabled/evalEnabled、cookie clear request planning、bridge permission gating、tool catalog、wm_stations、wm_open 状态返回、wm_extract mock、tool executor denial 和允许路径；尽可能运行 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build 和相关 iosAppTests。若当前 session 可用 build-ios-apps 插件，优先用它启动 iOS Simulator 验证 WebMount 页面、站点详情和 WKWebView 实际加载；若缺 iOS simulator runtime、Java、Xcode 组件、插件能力或 test target 配置，记录精确错误和可复现命令，并继续用 xcrun swiftc -parse、纯 Swift tests、mock runtime tests 或静态检查推进可验证部分。
约束：遵守 AGENTS.md；先读实际代码再改；保护当前工作区已有改动，尤其不要回滚 MiniApp、Board、Remote Sync、Search、Skill、plan 相关修改；不处理未跟踪的 iosApp/iosApp/CouncilChatRuntimeView.swift；不触碰密钥、真实账号、生产数据、发布配置、证书、google-services.json 或真实 OAuth 凭证；不把 mock runtime 伪装成真实 WebMount；不默认开启任意 JavaScript 执行；不在日志、测试失败、UI 或 agent 输出里打印 cookie 值、OAuth token、Authorization header 或完整敏感 URL；不上传用户数据到任何外部站点；不实现跨站 credential 转发；没有用户明确触发时不自动请求登录或清除 cookie。
边界：允许修改 iosApp/iosApp/WebMountView.swift、AppShell 中 WebMount 路由的最小类型适配、IOSLocalToolExecutor.swift、IOSPermissionModels.swift、ChatViewModel.swift 的最小 WebMount tool declaration 和 dispatch 接线、以及新增 IOSWebMountModels.swift、IOSWebMountRegistry.swift、IOSWebMountSettingsStore.swift、IOSWebMountRuntime.swift、IOSWebMountBridge.swift、IOSWebMountCookieStore.swift、IOSWebMountToolExecutor.swift、IOSWebMountURLPolicy.swift、IOSWebMountProfile.swift、小型 SwiftUI 组件和相关 iosAppTests；允许小幅更新 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md 和本 plan 的 WebMount 状态说明。禁止修改 MiniApp 文件、Board 文件、Sync 文件、Android 业务逻辑、Gradle 配置、Xcode project 生成物、发布脚本、证书、google-services.json 和无关重构。若 ChatViewModel 或权限模型已有他人改动导致冲突，先完成独立 WebMount executor、tests 和 UI，再把接线点写成明确后续项，不要强行覆盖。
迭代策略：用激进单目标方式推进，不在 registry 或 UI 第一屏完成后结束。先审计 iOS 起点和 Android 对照，再按依赖顺序连续实现：站点模型和注册表、设置 store、URL 安全策略、WKWebView runtime、cookie/session store、JS bridge bootstrap、tool executor、IOSLocalToolExecutor 和权限注册接线、WebMountView 和 WebMountSiteView 真实 UI、tests 和状态文案。每完成一个泳道运行最小验证；如果某个泳道连续 2 次被 WebKit、simulator 或 ChatViewModel 接线环境卡住，降级为 mockable adapter、诚实错误或后续标记，并继续下一个泳道，不要整体停止。优先真实本地 runtime 和安全边界，其次 agent 工具接线，再次站点专用 adapter，最后视觉 polish。
完成条件：iOS 有持久化 WebMount registry，能 seed Hacker News、Reddit、GitHub、Bilibili、X.com、微博、掘金、知乎、飞书云文档，并支持 add、remove、restoreMissingSeeds、enable 和 disable；iOS 有 WebMount settings，globalEnabled 默认关闭，evalEnabled 独立关闭且关闭 global 时会清 eval；URL policy 能阻止 file、javascript、data、非 allowlist host 和跨站跳转；WebMountSiteView 能打开真实 WKWebView 并展示 load status、title、current URL、error、bridge status、cookie summary 和 session clear；JS bridge 能提供受限 state、extract readable、extract links、get text 或 attr，不暴露任意 eval；tool executor 至少可运行 wm_stations、wm_open、wm_state、wm_extract、wm_get、wm_back、wm_forward、wm_clear_session，并对未实现的 wm_eval、signed fetch、OAuth 或 adapter tools 返回诚实 unsupported；IOSPermissionModels 和 ToolPermissionsView 能诚实展示 WebMount 工具风险；若接入 ChatViewModel，模型开启 WebMount 后能声明安全工具并在 tool call 后回填结果；测试或构建通过，或环境阻塞被精确记录且所有可静态验证部分已完成。
暂停条件：需要真实 OAuth app credentials、真实站点账号密码、真实 cookie 值、外部生产数据、Apple 账号登录、修改 Xcode project 结构、删除用户未跟踪文件、越过 iOS WebKit 安全限制、默认开启 arbitrary JS eval、产品决定是否允许 agent 自动网页登录或跨站操作、需要和 MiniApp/Board/Sync 大改同一文件导致无法安全合并，或同一外部环境阻塞连续出现 3 次时暂停。
```

## Goal Draft English Compatible

```text
/goal Aggressively advance AmberAgent iOS WebMount in an independent session: complete as much of the iOS WebMount usable loop as possible instead of stopping at the current WKWebView preview and explanatory copy. Starting from SimpleWebView preview and placeholder WebMountSiteView in WebMountView.swift, plus the iOS local tool permission model and ChatViewModel tool-call path, compare Android WebMountManager, UserSiteRegistry, WebViewPool, SessionHandle, JsBridge, ProfileBridge, WebMountCookieProvider, WebMountPrimitiveTools, and WebMountSiteTools, then implement Swift-native IOSWebMountSite, IOSWebMountRegistry, IOSWebMountSettingsStore, IOSWebMountRuntime, IOSWebMountCookieStore, IOSWebMountBridge, IOSWebMountToolExecutor, real WebMountView and WebMountSiteView UI, and XCTest coverage. Prioritize site list, seed restore, add/remove, enable/disable, safe URL allowlist, real WKWebView loading, page state, text and link extraction, cookie viewing and clearing, bridge probe, wm_stations, wm_open, wm_state, wm_extract, wm_get, wm_back, wm_forward, and wm_clear_session; wire these into IOSLocalToolExecutor and the existing tool declaration path where possible. wm_eval, OAuth tokens, signed fetch, and site-specific adapters may be safe skeletons with honest degradation, but do not let them block the locally verifiable loop.
Verification: first run git status --short --branch and git log --oneline --decorate -12, then inspect iosApp/iosApp/WebMountView.swift, iosApp/iosApp/IOSLocalToolExecutor.swift, iosApp/iosApp/IOSPermissionModels.swift, iosApp/iosApp/ChatViewModel.swift, iosApp/iosApp/AppShell.swift, plus Android WebMountModels.kt, WebMountManager.kt, UserSite.kt, UserSiteRegistry.kt, WebViewPool.kt, SessionHandle.kt, JsBridge.kt, ProfileBridge.kt, WebMountPrimitiveTools.kt, WebMountNavigationTools.kt, and WebMountSiteTools.kt for parity guidance; add or update Swift XCTest coverage for registry seed/load/save, add/remove/restore, URL allowlist, blocked schemes, host matching, globalEnabled/evalEnabled settings, cookie clear request planning, bridge permission gating, tool catalog, wm_stations, wm_open state output, wm_extract mock output, tool executor denial, and allowed execution paths; when possible run xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build and related iosAppTests. If the build-ios-apps plugin is available in the current session, use it to launch iOS Simulator and verify the WebMount page, site detail page, and real WKWebView loading. If simulator runtimes, Java, Xcode components, plugin capability, or test target setup block verification, record exact errors and reproducible commands, and continue with xcrun swiftc -parse, pure Swift tests, mock runtime tests, or static checks for verifiable parts.
Constraints: follow AGENTS.md; inspect real code before editing; protect existing worktree changes, especially MiniApp, Board, Remote Sync, Search, Skill, and plan edits; do not touch untracked iosApp/iosApp/CouncilChatRuntimeView.swift; do not touch secrets, real accounts, production data, release config, certificates, google-services.json, or real OAuth credentials; do not present a mock runtime as real WebMount; do not enable arbitrary JavaScript execution by default; do not print cookie values, OAuth tokens, Authorization headers, or full sensitive URLs in logs, test failures, UI, or agent output; do not upload user data to any external site; do not implement cross-site credential forwarding; do not automatically request login or clear cookies without an explicit user action.
Boundaries: edits are allowed in iosApp/iosApp/WebMountView.swift, minimal WebMount route type adaptation in AppShell, IOSLocalToolExecutor.swift, IOSPermissionModels.swift, tiny WebMount tool declaration and dispatch wiring in ChatViewModel.swift, plus new IOSWebMountModels.swift, IOSWebMountRegistry.swift, IOSWebMountSettingsStore.swift, IOSWebMountRuntime.swift, IOSWebMountBridge.swift, IOSWebMountCookieStore.swift, IOSWebMountToolExecutor.swift, IOSWebMountURLPolicy.swift, IOSWebMountProfile.swift, small SwiftUI components, and related iosAppTests; small updates to docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md and this plan are allowed. Do not modify MiniApp files, Board files, Sync files, Android business logic, Gradle config, generated Xcode project files, release scripts, certificates, google-services.json, or unrelated refactors. If ChatViewModel or the permission model already has conflicting user changes, finish the standalone WebMount executor, tests, and UI first, then document the exact wiring follow-up instead of overwriting.
Iteration policy: work as one aggressive goal and do not stop after the registry or first UI screen. Audit the iOS starting point and Android parity first, then implement continuously in dependency order: site models and registry, settings store, URL safety policy, WKWebView runtime, cookie and session store, JS bridge bootstrap, tool executor, IOSLocalToolExecutor and permission registry wiring, real WebMountView and WebMountSiteView UI, tests, and status copy. Run the smallest relevant verification after each lane. If one lane repeats the same WebKit, simulator, or ChatViewModel wiring failure twice, downgrade that lane to a mockable adapter, honest error, or follow-up marker and continue to the next lane instead of stopping everything. Prioritize a real local runtime and safety boundary first, agent tool wiring second, site-specific adapters third, and visual polish last.
Stop when: iOS has a persistent WebMount registry that seeds Hacker News, Reddit, GitHub, Bilibili, X.com, Weibo, Juejin, Zhihu, and Feishu Docs, and supports add, remove, restoreMissingSeeds, enable, and disable; iOS has WebMount settings with globalEnabled off by default, evalEnabled independently off, and global disable clearing eval; URL policy blocks file, javascript, data, non-allowlisted hosts, and unsafe cross-site navigation; WebMountSiteView opens a real WKWebView and shows load status, title, current URL, error, bridge status, cookie summary, and session clear; JS bridge provides restricted state, readable extraction, link extraction, and get text or attr without arbitrary eval; tool executor can at least run wm_stations, wm_open, wm_state, wm_extract, wm_get, wm_back, wm_forward, and wm_clear_session, and returns honest unsupported output for unimplemented wm_eval, signed fetch, OAuth, or adapter tools; IOSPermissionModels and ToolPermissionsView honestly show WebMount tool risk; if ChatViewModel wiring is added, enabling WebMount declares safe tools and tool call results are fed back into the model; tests or builds pass, or environment blockers are recorded exactly and all statically verifiable parts are complete.
Pause if: real OAuth app credentials, real site account passwords, real cookie values, external production data, Apple login, Xcode project restructuring, deleting user-untracked files, bypassing iOS WebKit safety limits, default arbitrary JavaScript eval, product decisions about agent-driven web login or cross-site actions, unsafe conflicts with MiniApp/Board/Sync edits in the same files, or the same external blocker repeats three times.
```

## 并行 Session 建议

推荐分支：

```text
codex/ios-webmount
```

推荐模型：

```text
GPT-5.5 xhigh
```

原因：D 会同时碰 WebKit、cookie/session、安全 allowlist、JS bridge、local tool 执行、权限展示和 SwiftUI 状态。这里最容易出错的是安全边界和工具接线，不建议用 medium 硬扛。

推荐插件：

```text
build-ios-apps
```

用途：如果当前 session 能使用该插件，优先用它跑 simulator、查看 WebMount 页面、验证 WKWebView 真加载、截取运行证据。插件不是写代码的前置条件；没有插件时仍可用 xcodebuild、XCTest、swiftc parse 和 mock runtime 推进。

## 当前 iOS 起点

当前已具备：

- `WebMountView.swift` 已有 `SimpleWebView`，可以用真实 `WKWebView` 加载任意 URL。
- `WebMountView` 已列出 Android WebMountManager、UserSiteRegistry、Cookie/OAuth、WebView runtime、Tool adapters 的存在证据。
- `WebMountSiteView` 已存在路由页，但只接收 `name` 和 `host`。
- `AppShell` 已有 `.webMountSite` 路由类型。
- `IOSLocalToolExecutor` 已有 iOS 工具执行入口和权限状态输出。
- `IOSPermissionModels` 已能表达 supported、degraded、unsupported、risk、gate 和 tool name。
- `ChatViewModel` 已有 `search_web` 工具声明和执行回填路径，可作为最小接线参考。

当前缺口：

- 没有 iOS `UserSiteRegistry` 等价物。
- 没有 iOS WebMount 全局开关和 eval 独立开关。
- 没有站点 add、remove、restoreMissingSeeds。
- 没有站点持久化和 seed migration。
- 没有 URL allowlist 或 scheme policy。
- 没有 per-site WKWebView runtime 状态模型。
- 没有 JS bridge bootstrap。
- 没有 bridge permission gating。
- 没有 cookie snapshot 或 host-scoped clear。
- 没有 iOS WebMount tool executor。
- 没有 `wm_stations`、`wm_open`、`wm_state`、`wm_extract` 等工具实现。
- 没有 WebMount 工具权限展示。
- 没有真实站点详情 UI。

## Android 对照锚点

Android 侧已经有：

- `WebMountStatus`、`WebMountCapability`、`WebMountAuthMethod` 和 `WebMountStationState`。
- `WebMountManager` 管理 global enabled、eval enabled、station state、probe、adapter tool catalog。
- `UserSiteRegistry` 持久化用户站点，默认 seed 包括 Hacker News、Reddit、GitHub、Bilibili、X.com、微博、掘金、知乎、飞书云文档。
- `WebViewPool` 创建 headless WebView，启用 DOM storage、cookies、bridge injection、network log 和 LRU session。
- `JsBridge` 和 `SessionHandle` 承载 state、extract、get、click、type、eval、screenshot 等 primitive。
- `ProfileBridge` 做 origin allowlist、permission check、call_page_fn、rate limit 和 signed fetch gating。
- `WebMountPrimitiveTools` 聚合 23 个 wm 工具。
- `WebMountSiteTools` 负责 wm_stations、wm_site_add、wm_site_remove、wm_profile_synthesize。

iOS 不需要一次完整复制 Android 全量能力，但应该复刻核心产品语义：用户有一组可管理站点，WebMount 默认关闭，高风险 eval 独立关闭，安全工具能在 agent 环境中访问用户明确配置的站点页面。

## 一口气完成清单

### Site Registry

新增 Swift Codable 模型：

- id
- displayName
- homepageURL
- authKind
- loginCookieName
- nativeAdapterId
- iconKey
- addedAt
- enabled
- allowedHosts

要求：

- 默认 seed 对齐 Android。
- 数据保存到 Documents 或 Application Support 下的 WebMount JSON 文件。
- 解码失败时保留损坏文件备份，不清空用户数据。
- `add` 自动生成 `user_` 前缀稳定 id。
- `remove` 不默认清 cookie，除非调用者明确要求清 session。
- `restoreMissingSeeds` 不覆盖用户已有站点。
- tests 覆盖 seed、add、remove、restore、migration-friendly decode。

### Settings Store

新增 `IOSWebMountSettingsStore`：

- `globalEnabled` 默认 false。
- `evalEnabled` 默认 false。
- 关闭 global 时自动清 eval。
- 可选 `maxSessions` 和 `defaultWaitMode`，不要过早做复杂设置页。

UI 要明确：

- WebMount 没开启时 agent 不声明工具。
- `wm_eval` 默认永远不声明。
- 开启 eval 需要独立显式动作，本 session 可只做数据模型和禁用文案。

### URL Safety Policy

新增 `IOSWebMountURLPolicy`：

- 只允许 http 和 https。
- 默认阻止 file、javascript、data、blob 起始导航。
- 对站点详情页，用站点 `allowedHosts` 限制主框架导航。
- 支持 www 子域匹配，但不要把 `evil-example.com` 当成 `example.com`。
- 跳转到非 allowlist host 时显示 blocked 状态。
- tests 覆盖 scheme、host、subdomain、redirect policy。

### WKWebView Runtime

新增 `IOSWebMountRuntime` 或小型 main-actor coordinator：

- 按 site 或 session id 管理 WKWebView。
- 暴露 load status、progress、title、currentURL、error、canGoBack、canGoForward。
- 注入 bridge bootstrap。
- 使用 `WKWebsiteDataStore.default()` 复用用户登录态，但在 UI 里提示这是 app 内 WebKit cookie jar。
- 可选 nonPersistent session 用于测试或隐私模式。
- 支持 goBack、goForward、reload、stop、clearSession。

不要为了模拟 Android headless pool 过度抽象；iOS 第一版以可见 WebMountSiteView runtime 为验收锚点，tool executor 可以复用当前或 mock session。

### Cookie And Session

新增 `IOSWebMountCookieStore`：

- 从 `WKHTTPCookieStore` 获取指定 host 的 cookie summary。
- UI 和 tool output 只显示 cookie name、domain、path、expires、isSecure、isHTTPOnly，不显示 value。
- 支持按站点 host 清除 cookies。
- 清除 cookie 需要 UI 明确动作或工具调用通过权限 gate。
- tests 用 fake cookie store 覆盖 summary 和 clear planning。

### JS Bridge

新增 `IOSWebMountBridge`：

- 用 `WKUserScript` 注入受限 helper。
- 用 `WKScriptMessageHandler` 接收 bridge ready 或 log。
- 支持 state、extract readable、extract links、get text、get attr。
- 输出有字符上限和节点数量上限。
- 不暴露任意 eval。
- 所有 bridge 方法都要受 session 和 URL policy 约束。

可选能力：

- `wm_find`。
- `wm_click` 和 `wm_type` 的安全 selector 或 ref 版本。

如果时间不够，优先做 read-only bridge，不要为了 click/type 牺牲安全边界。

### Tool Executor

新增 `IOSWebMountToolExecutor`：

- `wm_stations` 返回 registry、enabled、authKind、capability、login hint、cookie summary availability。
- `wm_open` 创建或复用 session，加载 URL，返回 session id、status、title、current URL、blocked reason。
- `wm_state` 返回当前 session 状态。
- `wm_extract` 返回 readable text 和 links。
- `wm_get` 根据 selector 或 ref 返回 text、attr 或 html 的受限版本。
- `wm_back` 和 `wm_forward` 操作 session history。
- `wm_clear_session` 清指定站点 cookie 或 session。
- `wm_eval` 返回 disabled unless evalEnabled，但本 session 不建议接真实执行。
- signed fetch、OAuth、站点专用工具返回 unsupported 或 not configured。

接线策略：

- 先让 executor 在 tests 中独立可用。
- 再把安全工具注册到 `IOSPermissionModels`。
- 再接 `IOSLocalToolExecutor`。
- 最后小幅接 `ChatViewModel` tool declaration 和 dispatch。
- 如果 ChatViewModel 冲突大，停在 executor 和权限层，报告精确接线点。

### UI

`WebMountView` 改成真实控制台：

- 全局 WebMount 开关。
- eval 独立禁用行。
- seed 和用户站点列表。
- 每站点显示 enabled、auth kind、host、cookie summary 状态。
- Add site。
- Restore default sites。
- 删除站点。
- 明确哪些 adapter 还未接。

`WebMountSiteView` 改成真实详情：

- 顶部状态和当前 URL。
- WKWebView 主区域。
- back、forward、reload、stop。
- bridge probe。
- extract preview。
- cookie summary。
- clear session。
- blocked navigation 提示。
- unsupported adapter tools 诚实展示。

UI 不要继续把主要内容写成“执行待接”；未实现能力只在对应能力行诚实降级。

### Tests

新增或更新：

- `IOSWebMountRegistryTests`
- `IOSWebMountSettingsStoreTests`
- `IOSWebMountURLPolicyTests`
- `IOSWebMountCookieStoreTests`
- `IOSWebMountBridgeTests`
- `IOSWebMountToolExecutorTests`
- `IOSCapabilityRegistryTests`
- `IOSLocalToolExecutorTests`

测试优先覆盖纯 Swift 逻辑和 mock runtime。真实 WebKit 运行证据可通过 simulator 截图、日志、或手动 page load 验证。

## 明确不做

本 session 不做：

- MiniApp。
- Board。
- Remote Sync。
- Android 业务逻辑改动。
- 全量 23 个 Android wm primitive tools。
- 全量 Feishu Docs、GitHub、Bilibili、Zhihu、Juejin native adapter 复制。
- 真实 Feishu OAuth。
- 真实 GitHub OAuth。
- 自动收集或导出用户 cookie value。
- 默认开启 `wm_eval`。
- 跨站 credential forwarding。
- 绕过 iOS WebKit 安全限制。
- 生产站点写操作。

## 验证命令

起点：

```bash
git status --short --branch
git log --oneline --decorate -12
```

定位：

```bash
rg -n "WebMount|IOSLocalToolExecutor|IOSPermissionModels|ChatViewModel|WKWebView|WKScriptMessageHandler|HTTPCookie" iosApp app
```

优先构建：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build
```

优先测试：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" test
```

回退检查：

```bash
xcrun swiftc -parse iosApp/iosApp/IOSWebMountModels.swift
xcrun swiftc -parse iosApp/iosApp/IOSWebMountRegistry.swift
xcrun swiftc -parse iosApp/iosApp/IOSWebMountToolExecutor.swift
xcrun swiftc -parse iosApp/iosApp/IOSWebMountURLPolicy.swift
```

如需要 Android 对照：

```bash
./gradlew test --tests "*WebMount*"
```

## 完成报告要求

最终报告要包含：

- 修改了哪些文件。
- 实现了哪些 WebMount 能力。
- 哪些 wm 工具已可执行，哪些诚实 unsupported。
- 是否接入了 ChatViewModel；如果没有，说明精确阻塞和接线点。
- 安全边界：globalEnabled、evalEnabled、URL allowlist、cookie redaction。
- 测试和构建命令结果。
- simulator 或 WKWebView 运行证据。
- 未完成项按风险排序。
