# Claude Handoff - iOS Capability Wiring - 2026-06-15

This document is a handoff packet for continuing the AmberAgent iOS UI capability wiring task in `/Users/arquiel/Downloads/AI/amberagent-ios`.

The current branch is `codex/ios-port-wip`. The work is about connecting the existing SwiftUI iOS interface to real, repository-backed AmberAgent capabilities and clearly downgrading UI that is only prototype or not bridged yet. Do not make fake backend flows, fake provider state, fake permission state, fake statistics, fake Keychain storage, or fake imports just to make the UI look complete.

## 1. Current Outcome And Status

Estimated completion is about 84 percent.

The iOS UI is no longer just a pretty shell for many major Settings areas. Most high-risk fake precise states have either been wired to real local state or downgraded to explicit not-wired/draft UI. Provider/Model is now partially real beyond the original scalar config: iOS reads KMP `DEFAULT_PROVIDERS`, shows seeded preset models, and has a local provider-selection projection layer. The remaining important work is not visual polish; it is bridging missing iOS capability stores and runtime paths, especially editable Provider/Model settings, TTS, Skill/MCP, stats/storage, and account profile persistence.

Latest committed work:

- `51c420098 wire iOS provider registry projection`
- `b410e1dd1 docs: record slice 22 provider models-tab commit in audit log`
- `51acf875e show real KMP seeded models in iOS provider detail models tab`
- `95e606894 docs: record slice 21 provider-preset commit in audit log`
- `74ea99f55 read real KMP DEFAULT_PROVIDERS for iOS provider presets`
- `54aba37e0 wire iOS appearance display preferences`
- `78cb8dcc4 wire iOS execution live activity setting`
- `829b2ce14 downgrade iOS webmount pages to real not-wired state`
- `b7a356d05 downgrade iOS miniapp pages to real not-wired state`
- `4dd2cb61a downgrade iOS board pages to real not-wired state`
- `f8b36578f downgrade iOS sync backup page to real not-wired state`
- `892c6fc5b downgrade iOS subagent pages to real not-wired state`
- `9334dfea6 downgrade iOS model council pages to real not-wired state`
- `abfaf73f5 downgrade iOS memory pages to real not-wired state`
- `b1a245da4 downgrade iOS search services to real not-wired state`
- `0877ec9c9 downgrade iOS model defaults to real chat setting`
- `8b21e96a3 wire iOS settings home status sources`

The main audit file is `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`.

Important current uncommitted state:

- `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md` is the source of truth for completed slices through Slice 23. Run `git status --short` before continuing because unrelated dirty files remain in this workspace.
- There are unrelated or pre-existing dirty files and generated folders. Do not stage them unless the user explicitly asks. Examples include `.DS_Store`, Android files under `app/src/...`, older docs, `ai-provider-openai/build/`, Xcode caches, and `index.html`.
- Special unresolved local file: `iosApp/iosApp/CouncilChatRuntimeView.swift` is untracked and must not be treated as part of the completed Slice 13 downgrade. The local ignored Xcode project at `iosApp/AmberAgent.xcodeproj/` references it in Sources, but `.gitignore` ignores `iosApp/**/*.xcodeproj/`, so that project reference is local workspace state rather than tracked HEAD evidence. The file appears to be a 1257-line Council Chat runtime experiment using the existing iOS scalar OpenAI-compatible provider path (`OpenAIKmpProvider`, `SettingsStore.baseUrl/apiKey/modelId`, `MessageStreamAccumulator`) to run host/guest turns. It is not the real Android/KMP `ModelCouncilManager` bridge and it names guests such as DeepSeek/Gemini/GLM while all turns still use the single current iOS provider/model. Treat it as an unresolved state conflict: do not stage, delete, route to, or build a slice around it unless the user explicitly asks for a Council Chat experiment or a dedicated Council bridge slice.

## 2. Why The Work Looks Like This

The user's core rule is strict:

- Only wire capabilities that actually exist and can be verified in this repo.
- If Android/KMP has a capability but iOS lacks the bridge, show the evidence and downgrade the iOS UI to not-wired or draft-only.
- Do not invent storage, credentials, network calls, provider config, import success, or statistics.
- Keep the Open Design visual structure and recent SwiftUI layout; make only small UI changes needed to make capability truthfulness clear.

The authority order is:

1. `DESIGN_SYSTEM.md`
2. Open Design `index.html` prototype
3. `REDESIGN_DELTAS.md`
4. Android/KMP source for behavior

Visuals should stay close to the Open Design prototype. Logic and behavior should follow existing Android/KMP source where an iOS bridge exists or can be safely introduced.

## 3. Completed Wiring And Downgrades

Real wiring completed:

- App root injects the real `SettingsStore`, `IOSPermissionStore`, `DocumentAccessStore`, `IOSSystemPermissionCoordinator`, and `IOSLocalToolExecutor`.
- Core OpenAI-compatible chat is wired through `ChatViewModel` to `SettingsStore.baseUrl`, `SettingsStore.apiKey`, `SettingsStore.modelId`, `ProviderSetting.OpenAI`, `OpenAIKmpProvider.streamTextCancellable`, streaming accumulation, cancellation, and `AgentRuntimeDatabase` run recording.
- Chat reasoning level is gated by KMP `ModelRegistry.MODEL_ABILITIES`, so reasoning is sent only for models that actually advertise reasoning ability.
- Chat selected-file context uses real SwiftUI file import, `DocumentAccessStore`, `IOSLocalToolExecutor`, `IOSToolRuntime`, and bounded file preview injection.
- Permission policy UI edits the app-level `IOSPermissionStore` for the implemented `ios.files.selected_read` tool policy.
- iOS capability/permission status reads `IOSCapabilityRegistry` and `IOSSystemPermissionCoordinator`.
- Remote SSH runtime settings use `SettingsStore`, `IOSSSHSecretStore`, trusted fingerprint handling, and `IOSTerminalRuntime`.
- Live Activity switch in Execution settings gates Chat generation and selected-file attach activity starts, and disabling it stops existing ActivityKit activities.
- Chat model picker and Model Defaults edit the same scalar `SettingsStore.modelId` that generation actually uses.
- Settings home now reads real local state for Appearance, Display, Runtime, Provider current config, TTS default, Execution Live Activity, and other available preference summaries.
- Appearance mode is wired to root `AppShell.preferredColorScheme`.
- Display font scale, chat font, Agent name, and follow-generation scrolling are wired into Chat rendering and scroll behavior.

Honesty downgrades completed:

- Provider list reads real KMP default provider templates, shows real seeded preset models, and can select a safe keyed OpenAI-compatible template by projecting base URL/key into the scalar `SettingsStore` path that Chat already consumes. Key-less templates remain visible but cannot be activated as current.
- Provider add/model edit/custom headers/body remain draft-only.
- TTS cloud engines, preview, delete, and add flows are draft-only with no save and no network preview.
- Skill and MCP pages no longer claim installed skill counts, connected servers, or persisted toggles.
- Search, Memory, Model Council, SubAgent, Sync/Backup, Board, MiniApp, and WebMount pages were downgraded from fake live/sample state to Android/KMP evidence plus explicit iOS bridge missing state.
- Account statistics and conversation storage no longer show fake exact counts, tokens, MB totals, cache savings, heatmaps, cleanup, or delete behavior.
- Execution settings only keeps Live Activity mutable; operation preview, generative UI, tool loop, retry, and keepalive are read-only not-wired rows.
- Appearance/Display unsupported controls are read-only not-wired rows until the renderer or runtime consumers exist.

## 4. Key User Clarifications

The user explicitly said provider presets are acceptable if they do not contain API keys, because the Android version also presets mainstream providers. Treat provider preset templates as real default templates, not fake configured providers.

This means it is valid to show OpenAI, DeepSeek, OpenRouter, Gemini, xAI, MiniMax, Kimi, Zhipu, Xiaomi MiMo, or other templates if they are aligned with Android/KMP defaults. It is not valid to show them as connected, saved, authenticated, balance-refreshable, model-fetchable, or carrying masked keys.

The user asked why Model Council was only a draft. The answer is: Android/KMP has real council logic, but iOS currently lacks the bridge to `Settings.agentRuntime.modelCouncil`, `ModelCouncilManager`, tool execution, live transcript state, `AgentTaskStore`, and prompt-file sync. The iOS page was therefore downgraded rather than fake-wired. Future work can bridge those layers, but must not merely re-enable the old local-only settings.

Important nuance for the next agent: the untracked `CouncilChatRuntimeView.swift` may look like "real" execution because it streams through the existing iOS OpenAI-compatible provider. That does not make it the real Model Council capability described in Slice 13. It is a separate possible product direction: "Council-style multi-turn chat using the current scalar provider." If the user wants that, it needs its own route decision, honest labeling, audit entry, build/run/screenshots, and subagent review. If the user wants parity with Android/KMP Model Council, the correct target remains an iOS bridge to the KMP settings/runtime/tool chain, not this local experiment.

## 5. Next Recommended Slice

Recommended next slice: editable Provider/Model registry follow-up, or TTS settings bridge if product priority moves away from providers.

Why this is the next best cut:

- Provider/Model is central to real chat usage and is now partially wired through KMP defaults, seeded models, and registry projection.
- The current iOS chat path still consumes scalar `SettingsStore.baseUrl/apiKey/modelId`, so a real editable provider registry must avoid pretending Chat can consume unsupported provider identity.
- The next provider work should focus on safe Key editing/writeback, mutable registry design, and whether to bridge common `Settings.providers`; otherwise TTS remains the next large P1 settings bridge.

Pre-flight state check before starting Provider: acknowledge `CouncilChatRuntimeView.swift` as an unresolved untracked local experiment and leave it untouched. If the local Xcode build depends on it because the ignored `.xcodeproj` references it, use the existing file for the build but do not stage it. If it causes build or routing confusion, pause and ask whether to keep it as a dedicated Council Chat experiment, remove the local project reference, or fold it into a future real Council slice with honest labels.

Suggested provider follow-up steps:

1. Decide whether the next slice is provider Key editing/writeback or full mutable registry design. Do not silently expand to model fetch/balance/network tests.
2. Keep key-less presets visible but not activatable as current until a real Key editor writes the matching per-provider Keychain slot.
3. Do not reintroduce startup decoding of mutable KMP provider JSON unless a safe KMP wrapper catches serialization failures before Swift app init can crash.
4. Keep Gemini, xAI Response API, Xiaomi MiMo placeholder, Claude, and other unsupported provider types blocked from scalar projection until Chat can represent them.
5. Update the audit file with exact evidence and current handling.
6. Build, run, screenshot Settings to Provider list/detail if tooling allows, review with a subagent, then commit only the touched files and the audit doc.

## 6. Remaining Implementation Queue

P0 or high-value P1:

- Real Provider registry bridge to `Settings.providers`, including provider-specific secure key schema, auth mode, Response API, model list, custom headers/body, and balance settings.
- Real Chat provider/model picker once the provider registry exists. The current picker must stay scalar and honest until then.
- TTS settings bridge and real iOS TTS execution path before enabling cloud save/delete/preview or system preview controls.
- Skill/MCP bridge to real local Skill scan/import and `Settings.mcpServers` persistence before enabling import success, server connect, headers, or tool approval persistence.
- Real iOS stats and conversation storage bridge before exact usage/storage numbers or cleanup/delete controls return.
- Model Council bridge only after the iOS side can read/write runtime settings and execute the real council manager/tool chain.

P2:

- Local account display name/avatar persistence if product wants a local-only profile.
- Clean up or deprecate legacy `SettingsView` only after confirming no route depends on it.
- Extend dynamic Appearance/Display only after actual renderers or theme tokens consume the settings.

Blocked unless new real capability is added:

- Provider-specific secrets beyond the single current OpenAI key.
- Provider model fetching, balance refresh, or network connection tests without explicit credentials and approval.
- Cloud TTS network preview.
- MCP live connection tests.
- Destructive conversation cleanup/delete.
- Login, subscription, or account backend.

## 7. Required Workflow For Each Slice

Work one screen, one component, or one capability line at a time.

Before editing:

- Re-read the current files, Android/KMP evidence, and audit entries for the target screen.
- Identify the full call chain: UI event, state source, store or coordinator, runtime/provider executor, persistence, error state, and user-visible result.
- If any link is missing, downgrade or document rather than fake it.

While editing:

- Keep SwiftUI visual changes minimal.
- Reuse existing app patterns and helper components.
- Do not delete code you do not understand.
- Use `apply_patch` for manual edits.
- Do not touch unrelated dirty files.

Verification per slice:

- Run `git diff --check` for touched files.
- Run targeted `rg` checks for removed fake states or orphan storage keys when relevant.
- Use XcodeBuildMCP. First call `session_show_defaults`; then use `build_run_sim` for scheme `iosApp` on simulator `iPhone 17` if defaults are still configured.
- Manually navigate to the affected page in the simulator.
- Capture screenshots when possible.
- If a control writes state, verify the same state is consumed by the runtime or visible downstream. If testing would modify user settings, restore the original state or document the exact side effect.

Subagent review is required after each slice before commit:

- Ask the subagent to review only the touched slice.
- The review focus must include logic closure and broken call-chain risk.
- Ask it to check whether UI writes are consumed, whether read state is authoritative, whether persistence is real, whether disabled/draft states are truthful, and whether any fake backend, fake Keychain, fake network, fake permission, fake stats, or fake import path slipped in.
- Apply P0/P1 findings before commit. P2 can be fixed or documented.

Commit rules:

- Commit only after build/run, manual page check, screenshot if available, and subagent review.
- Commit message should say what real capability was wired or what fake state was downgraded.
- Update `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md` with scope, evidence, verification, screenshots, subagent review result, and remaining risk.
- If Open Design docs cannot be written due to sandbox or approval limits, record that in the audit. Do not invent a workaround.

## 8. Xcode And Test Notes

Known XcodeBuildMCP defaults from the prior run:

- Project: `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/AmberAgent.xcodeproj`
- Scheme: `iosApp`
- Simulator: `iPhone 17`
- Bundle id: `app.amber.ios`

Recent `build_run_sim` checks have succeeded with no warnings or errors.

Known test limitation:

- Targeted `iosAppTests` could not run earlier because the existing test target has no Info.plist or generated Info.plist setting. Do not spend a slice solving that unless the user asks or it blocks the current verification.

## 9. Important Files To Read First

Core iOS:

- `iosApp/iosApp/AppShell.swift`
- `iosApp/iosApp/SettingsStore.swift`
- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/ChatViewModel.swift`
- `iosApp/iosApp/ProvidersView.swift`
- `iosApp/iosApp/ProviderDetailView.swift`
- `iosApp/iosApp/ModelDefaultsView.swift`
- `iosApp/iosApp/TTSSettingsView.swift`
- `iosApp/iosApp/ToolPermissionsView.swift`
- `iosApp/iosApp/PermissionsApprovalView.swift`
- `iosApp/iosApp/RuntimeEnvironmentView.swift`
- `iosApp/iosApp/ExecutionSettingsView.swift`
- `iosApp/iosApp/AppearanceSettingsView.swift`
- `iosApp/iosApp/DisplayFontSettingsView.swift`
- `iosApp/iosApp/PlaceholderViews.swift`

Audit and design:

- `AGENTS.md`
- `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`
- Open Design project path: `/Users/arquiel/Library/Application Support/Open Design/namespaces/release-stable/data/projects/1410e6f2-26ef-45a9-aafc-74be11ff864d`
- Open Design files to read if accessible: `DESIGN_SYSTEM.md`, `REDESIGN_DELTAS.md`, `QUESTIONS.md`, `index.html`

Provider and model evidence:

- Search with `rg -n "DEFAULT_PROVIDERS|ProviderPrefs|ProviderSetting|ModelSetting|ResponseApi|customHeaders|customBody|authMode" core ai app iosApp -S`

## 10. Suggested Claude `/goal` Prompt

推荐执行版（中文，可直接复制）

```text
/goal 在 `/Users/arquiel/Downloads/AI/amberagent-ios` 继续 AmberAgent iOS UI 能力接线任务，把现有 SwiftUI 界面尽可能接到真实存在、可验证、仓库已有的业务能力、状态来源和存储逻辑；当前优先从 Provider/Model 预置 provider 与真实设置来源开始，后续按一个屏幕、一个组件或一条能力线推进，并持续维护 `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`。
验证：每个阶段先读取 `AGENTS.md`、相关 Swift 文件、Android/KMP 源码和现有审计；运行 `git diff --check` 和针对性 `rg` 检查；使用 XcodeBuildMCP 先 `session_show_defaults` 再 `build_run_sim` 启动 `iosApp`；手动跑到对应页面并尽量截图；每个阶段完成后启动 subagent review，重点审查逻辑是否闭环、UI 到 store/viewmodel 到 runtime/provider/coordinator 到 persistence/error 的调用链有没有断裂风险；提交前把验证命令、截图路径、subagent 结论和剩余风险写入审计。
约束：只接仓库里已经存在且可验证的能力；Provider 预置模板可以展示但不能预填 API Key 或伪装成已配置账号；不要伪造后端、假网络、假 Keychain、假 Provider、假权限、假统计、假 import success 或假 tool call；不要为了 UI 完整写本地孤儿保存；不要发外部网络请求测试 Provider、MCP 或 TTS；不要改 CI、发布配置、数据库迁移、远端分支；不要删除不理解的代码；不要 git reset、force push 或删除分支。
边界：主要写入 `iosApp/iosApp` 中与当前切片直接相关的 Swift 文件，以及 `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`；必要时只读取 Android/KMP、Open Design 和 docs 作为证据；不要 stage 或修改当前工作区里无关脏文件、Xcode 缓存、构建产物或用户未要求的 Android 文件。
迭代策略：一次只做一个聚焦切片；先画出 UI 入口、当前状态来源、真实能力、缺失链路和处理方式，再实现；如果能力存在但 UI 未接，优先接真实状态或真实动作；如果 iOS 缺桥但 Android/KMP 有能力，页面应显示证据和 not-wired 状态；如果能力不存在，记录为 blocked 而不是实现假流程；同一问题连续失败两次后换证据来源，例如读调用方、读生成接口、跑更小的验证或让 subagent review。
完成条件：所有高优先级 Settings、Provider/Model、Chat、权限、TTS、Skill/MCP、运行环境、Account/Stats 入口都已经真实接线或明确降级为 not-wired/draft，并且审计文档列清楚已接能力、仍是草稿、孤儿入口、高风险区域、UI Entry Map、Implementation Queue 和 Commit Log；最后一次相关构建运行通过，手动页面检查和截图证据完整，subagent review 没有未处理 P0/P1 调用链断裂问题。
暂停条件：需要真实用户密钥、账号登录、生产数据、外部网络副作用、破坏性数据操作、核心数据模型重设、远端分支操作、CI/发布配置变更，或发现 Android/KMP 与 iOS 设计存在必须由产品决定的冲突时暂停并向用户说明选项。
```

默认选择理由：这是现有仓库里的中高风险接线任务，先做 Provider/Model 这一条真实能力链，最能提升核心使用价值，同时把 fake UI 回潮风险压低。

可选调整：

1. 下一切片：A Provider/Model 预置 provider 与设置来源，推荐 / B TTS 设置桥 / C Skill/MCP 桥
2. 提交节奏：A 每个小切片 build、review、commit，推荐 / B 多个相关小切片合并一个 commit
3. 验证强度：A build_run_sim 加手动页面截图加 subagent review，推荐 / B 仅构建和审计，不推荐

Goal Draft English-compatible:

```text
/goal Continue the AmberAgent iOS UI capability wiring task in `/Users/arquiel/Downloads/AI/amberagent-ios` by connecting existing SwiftUI surfaces to real, verifiable, repository-backed business capabilities, state sources, and storage paths; prioritize the Provider/Model preset provider and settings-source slice first, then proceed one screen, one component, or one capability line at a time while maintaining `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`.
Verification: for each stage, first inspect `AGENTS.md`, the relevant Swift files, Android/KMP source, and the current audit; run `git diff --check` and targeted `rg` checks; use XcodeBuildMCP with `session_show_defaults` followed by `build_run_sim` for `iosApp`; manually navigate to the affected page and capture screenshots when possible; after each stage, run a subagent review focused on closed logic and broken call-chain risk from UI to store/viewmodel to runtime/provider/coordinator to persistence/error handling; before each commit, record verification commands, screenshot paths, subagent findings, and remaining risks in the audit.
Constraints: wire only capabilities that actually exist and can be verified in the repository; provider preset templates may be displayed but must not prefill API keys or pretend to be configured accounts; do not fake backends, network calls, Keychain storage, providers, permissions, statistics, import success, or tool calls; do not add orphan local saves just to complete UI; do not send external network requests to test Provider, MCP, or TTS; do not modify CI, release config, database migrations, remote branches, or code you do not understand; do not use git reset, force push, or branch deletion.
Boundaries: primarily write only directly related Swift files under `iosApp/iosApp` and `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`; read Android/KMP, Open Design, and docs only as evidence when needed; do not stage or modify unrelated dirty files, Xcode caches, build outputs, or Android files unless the current slice directly requires it.
Iteration policy: make one focused slice at a time; first map UI entry, current state source, real capability, missing link, and chosen handling; if a capability exists but UI is not wired, connect the real state or real action first; if Android/KMP has the capability but iOS lacks the bridge, show evidence and not-wired state; if the capability does not exist, record it as blocked instead of implementing a fake flow; after two repeated failures on the same problem, switch evidence sources by reading callers, generated interfaces, narrower checks, or asking a subagent to review.
Stop when: every high-priority Settings, Provider/Model, Chat, permissions, TTS, Skill/MCP, runtime, and Account/Stats entry is either wired to real capability or explicitly downgraded to not-wired/draft state, and the audit clearly lists wired capabilities, remaining drafts, orphan entries, high-risk areas, UI Entry Map, Implementation Queue, and Commit Log; the last relevant build/run passes, manual page checks and screenshot evidence are present, and subagent review has no unresolved P0/P1 broken-call-chain findings.
Pause if: the work requires real user secrets, account login, production data, external network side effects, destructive data operations, core data model redesign, remote branch operations, CI/release config changes, or a product decision because Android/KMP behavior conflicts with iOS design.
```

## 11. Quick Start Checklist For Claude

1. Read this handoff file.
2. Read `AGENTS.md`.
3. Read `docs/ios-port/IOS_CAPABILITY_WIRING_AUDIT_2026-06-15.md`.
4. Run `git status --short` and do not stage unrelated dirty files.
5. Inspect the next slice source with `rg`, starting from Provider/KMP defaults.
6. Make one narrow change.
7. Run diff checks and `build_run_sim`.
8. Navigate the simulator page and capture screenshots.
9. Spawn subagent review for logic closure and call-chain risk.
10. Fix P0/P1 findings.
11. Update audit.
12. Commit only the touched slice files.
