# iOS Port Goal Plan 2026-06-18

本文档用于下一轮 Codex `/goal` 执行。它把当前代码事实、计划修正、执行边界、验证命令和暂停条件收敛成一个可复制的目标合同。

## 推荐执行版（中文，可直接复制）

```text
/goal 推进 AmberAgent iOS port 的下一阶段收口：先基于当前代码重新校准 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md，再清理已完成能力对应的过期待接文案，并完成 Search 与 Skill detail 两个低风险可验证小闭环；不要把 Board、MiniApp、Sync、WebMount 的大工程一次性混入本轮。
验证：先运行 git status --short --branch 和 git log --oneline -25 确认起点；运行 rg -o "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift" | wc -l 记录 marker 基线；修改后再次运行同一 marker 扫描并给出按文件变化；运行 rg 检查 Search、Memory、MiniApp、Board、Council、SettingsHome 的过期状态文案已被修正；尽可能运行 ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon 和 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build。若本机缺 Java、iOS simulator runtime 或 Xcode 组件，必须记录精确错误，不得声称验证通过。
约束：遵守 AGENTS.md，先读实际代码再改；不删除用户已有改动，不回滚未理解的文件；不做无关重构；不伪造能力状态；不把 v1 明确不做的能力重新包装成待接；不接触密钥、账号、云服务、发布配置或生产数据；不把未跟踪的 iosApp/iosApp/CouncilChatRuntimeView.swift 纳入本轮，除非用户另行要求。
边界：允许修改 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md、docs/ios-port/IOS_PORT_GOAL_PLAN_2026-06-18.md，以及 iosApp/iosApp 内与状态文案、Search 设置状态、Skill 只读详情路由直接相关的 Swift 文件；允许新增或更新最小相关测试。禁止修改 Android 业务逻辑、Gradle 配置、Xcode project 生成物、发布脚本、google-services.json、证书、私有配置和无关文档。
迭代策略：先做事实复核并更新 plan，再做 stale marker cleanup，最后只做 Search 与 Skill detail 的小闭环；每次聚焦修改后运行最小相关检查；同一构建或测试错误连续失败 2 次后必须换证据来源，例如读日志、缩小到单文件 typecheck、查询项目文档或暂停说明环境阻塞；最多做 3 轮聚焦修正后报告剩余风险。
完成条件：旧 plan 的现状、真缺口和 Slice 7-9 已按当前 HEAD 修正；SettingsHome、Search、Memory、MiniApp、Board、Council 等入口不再展示已完成能力的假待接；Search 页面能准确表达 search_web 已接、scrape_web 和多 provider orchestration 未接；Skill 列表能进入真实只读详情或 plan 明确说明为何保留为后续；验证命令通过，或环境阻塞被精确记录并附上可复现命令。
暂停条件：需要安装 Java 或 iOS simulator runtime、登录 Apple 账号、接入真实云同步账号、使用真实 API Key 做联网验证、修改 Xcode project 结构、删除用户未跟踪文件、决定 Board collector 数据源优先级、决定远端 Sync provider、或连续 3 次遇到同一外部环境阻塞时暂停。
```

## Goal Draft (English-compatible)

```text
/goal Advance the next AmberAgent iOS port closure stage: first recalibrate docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md against the current code, then remove stale pending copy for capabilities that are already wired, and complete two low-risk verifiable loops for Search status and read-only Skill detail. Do not mix the larger Board, MiniApp, Sync, or WebMount projects into this run.
Verification: run git status --short --branch and git log --oneline -25 to confirm the starting point; run rg -o "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift" | wc -l to record the marker baseline; rerun the same scan after changes and report per-file movement; use rg to confirm stale status copy in Search, Memory, MiniApp, Board, Council, and SettingsHome has been corrected; when possible run ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon and xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build. If Java, simulator runtimes, or Xcode components are missing, record the exact error instead of claiming success.
Constraints: follow AGENTS.md, inspect real code before changing it, never delete or revert user changes, avoid unrelated refactors, do not fake capability status, do not turn v1 explicit non-goals back into pending features, and do not touch secrets, accounts, cloud services, release config, or production data. Do not include the untracked iosApp/iosApp/CouncilChatRuntimeView.swift in this run unless the user explicitly asks.
Boundaries: writes are allowed only in docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md, docs/ios-port/IOS_PORT_GOAL_PLAN_2026-06-18.md, and directly related Swift files under iosApp/iosApp for status copy, Search state, and read-only Skill detail routing; minimal related tests may be added or updated. Do not modify Android business logic, Gradle configuration, generated Xcode project files, release scripts, google-services.json, certificates, private config, or unrelated docs.
Iteration policy: first recheck facts and update the plan, then clean stale markers, then implement only the Search and Skill detail small loops; after each focused change run the smallest relevant check; after the same build or test failure repeats twice, change evidence source by reading logs, reducing to single-file typecheck, inspecting project docs, or pausing for environment blockers; make at most 3 focused correction rounds before reporting remaining risks.
Stop when: the old plan's current-state table, true-gap list, and Slice 7-9 definitions are corrected for current HEAD; SettingsHome, Search, Memory, MiniApp, Board, and Council no longer show false pending copy for already-wired capabilities; Search accurately states that search_web is wired while scrape_web and multi-provider orchestration are not; Skill list can open real read-only detail or the plan clearly records why that remains follow-up; checks pass or environment blockers are recorded with reproducible commands.
Pause if: the work requires installing Java or iOS simulator runtimes, signing into an Apple account, connecting a real cloud sync account, using real API keys for live network verification, changing Xcode project structure, deleting user-untracked files, making product decisions about Board collector priority or remote Sync provider, or the same external environment blocker repeats 3 times.
```

## 默认选择理由

本轮先做状态清账和两个小闭环，因为当前最大的风险不是代码完全没有，而是文档和 UI 状态已经落后于实现；先把事实对齐，后面的 Board、MiniApp、Sync、WebMount 才能按真实缺口拆分。

## 当前事实基线

仓库路径：

```text
/Users/arquiel/Downloads/AI/amberagent-ios
```

当前分支和远端：

```text
codex/ios-port-wip
origin/codex/ios-port-wip
```

当前 HEAD：

```text
81c7cf3ca Board 今日看板内容 persistence (narrowed scope: content only, no task-flow)
```

本轮调研得到的关键事实：

- 旧 plan 仍以 2026-06-17 和 114 个 marker 为基线，已过期。
- 当前 Swift 原始 marker 扫描命中为 121，但这不是 121 个真实 blocker，因为里面包含过期注释、已完成能力的旧提示和 v1 明确不做项。
- Settings 写回桥已完成主要闭环：provider、TTS OpenAI、search service、council seat、subAgent override 都通过 `IOSSharedSettingsStore` 和 `IosSettingsMutations` 写回真实 Settings snapshot。
- Chat tool injection 已进入 `ChatViewModel`：`search_web`、`mcp_call`、`subagent_dispatch`、`model_council_run` 都有声明和 Swift dispatch 路径。
- 对话存储和会话列表已是真实链路：`IOSConversationStore` 已接 AppShell、列表、搜索、新建、选择、重命名、删除和置顶。
- Memory 已有本地 JSON 持久化到 Documents，但 recall、tool、chat injection、Room parity 仍未完成。
- MiniApp 已有 WKWebView MVP：HTML 校验、AmberNative bridge 注入、加载和日志闭环已存在；repository、grant store、完整 bridge capability 仍未完成。
- Board 已有时间锚点采集、手动模型生成和 Markdown 内容按日期持久化；日历、通知、飞书、聊天历史、热榜、深度阅读和后台 scheduler 仍未完成。
- Sync 已有本地 Settings AES-GCM `.amberbackup` 导出导入；远端 Google Drive、WebDAV、S3、Room tables、文件树和冲突处理仍未完成。
- Skill 扫描已有真实 `Documents/skills/*/SKILL.md` 读取；列表尚未进入真实 detail，detail 页面仍是占位。

需要保护的本地状态：

- 不要回滚 `.DS_Store` 删除、Android 文件修改、旧 docs 修改或其他用户已有工作区状态。
- 不要自动处理未跟踪的 `iosApp/iosApp/CouncilChatRuntimeView.swift`。它看起来是本地 Council Chat 实验，不属于本轮收口范围。

## 本轮目标范围

本轮是一个可完成的 goal，不是“把 iOS App 全部做完”。

必须完成：

- 修正 `docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md` 的基线、现状分类和 Slice 7-9 描述。
- 清理或改写已完成能力对应的过期“待接”文案，尤其是 SettingsHome、Search、Memory、MiniApp、Board、Council。
- 完成 Search 状态收口：页面必须准确表达 `search_web` 已经接入 ChatViewModel 和 IOSSearchExecutor，同时 `scrape_web`、多 provider orchestration、服务选择消费仍未完成。
- 完成 Skill read-only detail 小闭环，优先把真实扫描结果从 SkillsView 路由到只读 detail 页面；如果路由受当前 Router 结构阻塞，必须更新 plan 并留下最小后续切片。
- 给出验证证据和剩余真实缺口列表。

不得在本轮做：

- 不做 Board 真实日历、通知、飞书、热榜 collector。
- 不做 MiniApp repository、grant store、完整 AI/search/fetch bridge。
- 不做 WebMount。
- 不做远端 Sync provider。
- 不做 App Store、签名、账号、发布和生产配置。
- 不重构 KMP shared export 架构。
- 不删除死代码或未跟踪实验文件。

## 执行阶段

### Phase 0：起点确认

目的：防止在脏工作区里误删用户改动。

操作：

- 运行 `git status --short --branch`。
- 运行 `git log --oneline --decorate -25`。
- 运行 marker 基线扫描。

命令：

```bash
git status --short --branch
git log --oneline --decorate -25
rg -o "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift" | wc -l
rg -n "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift"
```

完成标准：

- 明确当前 branch、HEAD、dirty 文件和 marker 基线。
- 将用户已有改动列为保护对象，不回滚、不清理。

### Phase 1：修正旧 plan 的事实基线

目标文件：

```text
docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md
```

必须修正：

- 顶部日期改为 2026-06-18，并标注 HEAD `81c7cf3ca`。
- 旧的 114 marker 基线改为当前扫描结果，并说明 raw count 不是真实 blocker 数。
- 将 Settings write-back、Chat tool injection、Conversation storage、Memory persistence、MiniApp WebView MVP、Board content persistence、local Settings backup 从“真缺口”移出。
- Slice 7 改成 Remote Sync only：本地 `.amberbackup` 已完成，远端 provider 和数据范围未完成。
- Slice 8 改成 Board collectors phase 2：时间锚点、手动生成、内容持久化已完成；剩日历、通知、飞书、聊天历史、热榜、深度阅读、后台 scheduler。
- Slice 9 拆成 MiniApp full runner 和 WebMount：MiniApp 不是零基础，WebMount 仍是大缺口。
- “明确不做”更新为真实非目标：Board task-flow、opportunity、dispatch 仍不做；Terminal PTY 仍不做；Skill edit/delete/import 仍不做，但 Skill read-only detail 可作为本轮小闭环。

完成标准：

- 文档读起来不会让下一位 agent 重做已完成的 Slice。
- 每个真缺口都有清晰剩余能力，不使用笼统“全待接”。

### Phase 2：清理过期 UI 状态文案

优先文件：

```text
iosApp/iosApp/PlaceholderViews.swift
iosApp/iosApp/SearchServicesView.swift
iosApp/iosApp/SearchProviderView.swift
iosApp/iosApp/CouncilSettingsView.swift
iosApp/iosApp/MemoryOverviewView.swift
iosApp/iosApp/MemoryEditView.swift
iosApp/iosApp/MiniAppRunnerView.swift
iosApp/iosApp/BoardSettingsView.swift
```

修正原则：

- 已接能力写“已接”或“部分接”，不要继续写“待接”。
- 部分接能力必须写清楚已接范围和剩余范围。
- 明确不做能力写“不做”，不要伪装成未来待接。
- 不为了降低 marker count 而删掉有价值的真实缺口说明。
- 不改无关布局，不做视觉大改。

重点修正：

- SettingsHome 的 Memory、Skill、Sync、ConversationStorage、Board、Council、SubAgent、MiniApp 状态。
- SearchServicesView 和 SearchProviderView 对 `search_web` 的过期描述。
- MemoryEditView 中“重启后清空”的过期注释和用户提示。
- MiniAppRunnerView 中“没有 WebView、bridge、HTML 渲染”的过期描述。
- BoardSettingsView 中“模型生成和内容持久化仍待接”的过期描述。
- CouncilSettingsView 中“ChatViewModel 不会注入 model_council”和“只 stub”的过期描述。

完成标准：

- 用户打开设置和能力页时看到的状态与底层实现一致。
- marker count 下降不是唯一目标，关键是剩余 marker 都是真实缺口或明确非目标。

### Phase 3：Search 状态小闭环

当前事实：

- `ChatViewModel` 已在 `sharedSettings.snapshot.enableWebSearch` 为 true 时注入 `search_web`。
- `IOSSearchExecutor` 已能走 DuckDuckGo Lite URLSession 搜索。
- Search service snapshot 写回已存在。
- `scrape_web`、多 provider selection、SearchOrchestrator parity、搜索服务选择消费仍未完成。

本轮建议实现：

- 让 Search 页面准确展示 `enableWebSearch` 对 ChatViewModel 的影响。
- 如果现有 `IOSSharedSettingsStore` 已有安全 mutation，则增加最小开关写回；若没有，先只修文案并在 plan 标记需要新增 mutation。
- 不实现 `scrape_web`。
- 不把 Tavily、Exa、Jina 等 provider 真正接入执行器。

完成标准：

- 页面不再声称 iOS generation chain 未接 search_web。
- 若启用开关可写，则重启后状态保留；若本轮不写开关，文档要说明阻塞点和最小后续 slice。

### Phase 4：Skill read-only detail 小闭环

当前事实：

- `IosSkillFactory.listSkills(documentsDir:)` 已真实扫描 `Documents/skills/` 下的 `SKILL.md`。
- `SkillsView` 显示列表，但行不能进入真实 detail。
- `SkillDetailView` 仍以 skillName 占位，没有读取扫描结果或文件内容。

本轮建议实现：

- 给扫描到的 skill row 加只读详情入口。
- 详情页展示 name、description、dirName、enabled、issues，以及必要时展示 SKILL.md 的只读片段。
- 不实现启用、禁用、删除、导入、编辑。
- 如果 Router 现有 route 只能传 skillName，则用最小方式传递 dirName 或在详情页重新扫描定位，不引入复杂全局状态。

完成标准：

- 有真实 SKILL.md 时，用户能从列表进入详情并看到真实 metadata。
- 没有技能时，空状态仍诚实提示。

### Phase 5：验证和审计记录

必须运行：

```bash
rg -o "执行待接|待接|尚未接|未接线" iosApp/iosApp --glob "*.swift" | wc -l
rg -n "search_web|scrape_web|MiniApp Runner|记忆|重启后清空|model_council|待接" iosApp/iosApp --glob "*.swift"
```

尽可能运行：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build
```

如果本地环境支持模拟器，再运行：

```bash
xcrun simctl list runtimes
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17" build
```

完成标准：

- 给出命令结果摘要。
- 如果构建阻塞，保留原始错误要点和下一步环境动作。
- 不把环境阻塞描述为代码失败，除非日志明确指向代码。

## 后续 Goal 队列

以下不是本轮推荐 goal 的执行范围，但应作为后续独立 goal。

### 后续 Goal 1：Board collectors phase 2

目标：

- 基于已存在的 `IOSConversationStore` 优先接聊天历史 collector。
- 再评估 EventKit 日历和提醒事项权限。
- 最后再考虑通知、飞书、热榜和深度阅读。

暂停条件：

- 需要用户决定隐私边界、授权弹窗文案、是否读取真实日历或通知。
- 需要 Feishu 账号、MCP 配置或外部 API 凭证。

### 后续 Goal 2：MiniApp full runner phase 1

目标：

- 在已有 WKWebView MVP 上补 MiniApp repository、appId、版本选择、grant store 和 bridge capability 分层。
- 保持 `ai`、`search`、`fetch`、`clipboard`、`sharedStore`、`eventBus` 等 capability 的权限和错误返回诚实。

暂停条件：

- 需要决定 MiniApp 文件格式、权限模型、持久化位置或与 Android repository 的兼容级别。

### 后续 Goal 3：Remote Sync provider prototype

目标：

- 在本地 `.amberbackup` 已完成的基础上，选择一个远端 provider 做 prototype。
- 推荐先选 WebDAV 或 S3，因为它们比 Google Drive OAuth 更少账号和审核复杂度。

暂停条件：

- 用户必须选择 provider，并提供测试账号或明确只做 mock。

### 后续 Goal 4：WebMount

目标：

- 单独设计 WebMount 的 WKWebView、navigation policy、cookie/storage、权限和 bridge 边界。
- 不与 MiniApp runner 混做。

暂停条件：

- 需要决定 WebMount 是外部网页容器、内部工具 iframe 替代、还是 MiniApp 的特殊模式。

## 文件边界清单

本轮允许写入：

```text
docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md
docs/ios-port/IOS_PORT_GOAL_PLAN_2026-06-18.md
iosApp/iosApp/PlaceholderViews.swift
iosApp/iosApp/SearchServicesView.swift
iosApp/iosApp/SearchProviderView.swift
iosApp/iosApp/CouncilSettingsView.swift
iosApp/iosApp/MemoryOverviewView.swift
iosApp/iosApp/MemoryEditView.swift
iosApp/iosApp/MiniAppRunnerView.swift
iosApp/iosApp/BoardSettingsView.swift
iosApp/iosApp/SkillsView.swift
iosApp/iosApp/SkillDetailView.swift
iosApp/iosApp/RouterPath.swift
```

只有确实需要 Search 开关写回或 Skill detail 数据模型时才允许触碰：

```text
iosApp/iosApp/IOSSharedSettingsStore.swift
shared/src/commonMain/kotlin/shared/IosSettingsMutations.kt
shared/src/commonMain/kotlin/shared/IosSkillFactory.kt
```

本轮禁止写入：

```text
app/
ai/
common/
document/
highlight/
search/
tts/
web/
web-ui/
iosApp/AmberAgent.xcodeproj/
google-services.json
native/
```

例外：

- 如果构建错误明确来自本轮修改且必须改 shared bridge 才能编译，可以触碰最小相关 shared 文件，并在最终报告说明原因。

## 完成报告格式

最终报告应包含：

- 改了哪些文件。
- 哪些 marker 是 stale cleanup，哪些仍是真缺口。
- Search 小闭环完成到什么程度。
- Skill detail 小闭环完成到什么程度。
- 运行了哪些命令，结果是什么。
- 哪些命令因为 Java、Simulator runtime、Xcode 组件或外部凭证无法运行。
- 后续建议的第一个独立 goal。
