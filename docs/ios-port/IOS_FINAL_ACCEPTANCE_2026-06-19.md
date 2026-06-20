# AmberAgent iOS Final Acceptance 2026-06-19

Branch: `codex/ios-port-wip`
Baseline HEAD: `70e1a7260` — docs(ios): re-verify parity closure on HEAD a81876885
Current tool-closure recheck: `HEAD 476f8f1ec` plus uncommitted working-tree changes from 2026-06-20.
Prior baseline: `a81876885` (warning cleanup), `68192b345` (test regressions), `2802c7fb4` (handoff doc)
Scope: parity closure **验收与发布前收口**（不扩大功能范围）

## Tool Closure re-verification (2026-06-20)

This pass supersedes the older warning/test-count statements below for the current working tree. Scope was limited to the Tool Closure plan: KMP tool declarations, iOS permission/tool catalog consistency, Chat tool declaration wiring, SubAgent engine execution, assistant regenerate branching, targeted warning cleanup, and docs calibration.

| Check | Command | Result |
| --- | --- | --- |
| Whitespace | `git diff --check` | **Pass** (exit 0) |
| KMP iOS | `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon` | **Pass** — `BUILD SUCCESSFUL` |
| Full tests | `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/amberagent-tool-closure-test-3 ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO test` | **Pass** — **352 passed, 1 skipped, 0 failed** |

Warning status for this pass:

- Cleaned targeted Swift warnings in scope: Toggle Sendable closures, Kotlin numeric bridging via `init(truncating:)`, unused binding/node, and `IOSSyncBackup` UIDevice main-actor access.
- Remaining warnings observed in earlier full build output are AmberNative simulator deployment-target linker warnings (`libamber_ffi.a` built for iOS-sim 26.5 vs linked 26.0). These require a native rebuild or deployment-target decision and are not fixed by the iOS tool-closure work.
- Real API key/account/manual smoke remains out of autonomous scope: SubAgent true-model quality, Council true-model debate, DeepRead true-model synthesis, MCP real server reconnect, and WebMount logged-in page interaction.

## Automated verification (re-run 2026-06-19, acceptance agent)

Historical baseline: all four gate commands ran fresh in that session. Working tree was clean before
and after (no code changes this session — warning cleanup confirmed already
done on `a81876885`, only AmberNative native-lib warnings remained, which require
a native rebuild and are out of parity scope). For current working-tree status, use the 2026-06-20 section above.

| Check | Command | Result |
| --- | --- | --- |
| Whitespace | `git diff --check` | **Pass** (exit 0) |
| iOS build | `xcodebuild … generic/platform=iOS Simulator ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build` | **Pass** — `BUILD SUCCEEDED`; targeted Swift warnings were clean at that baseline; 34 `ld` warnings all `libamber_ffi.a` object files built for iOS-sim 26.5 vs linked 26.0 (native, out of scope) |
| KMP iOS | `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64` | **Pass** — `BUILD SUCCESSFUL`, exit 0 |
| Full tests | `xcodebuild … -destination "platform=iOS Simulator,name=iPhone 17" -derivedDataPath /tmp/amberagent-test-full-final … test` | **Pass** — `TEST SUCCEEDED`; **290 passed, 1 skipped, 0 failed** |

Gradle did not require elevated `~/.gradle` permissions on this machine.

**Warning status re-confirmed this session:**

- `ChatViewModel` / `BoardView` / `MiniAppRunnerView` unused `try? saveArtifact` — already fixed on `a81876885` (`_ = try? …`).
- `IOSSyncBackup` `UIDevice` main-actor warning — already fixed on `a81876885` (`IOSDeviceLabel.current` main-sync).
- `WebMountView` / `McpServersView` Toggle non-Sendable closure — fixed in the current 2026-06-20 tool-closure pass.
- Remaining: `libamber_ffi.a` iOS-sim 26.5 vs link 26.0 linker warnings (native rebuild required — **do not** address without a native build + product decision on deployment target).

Simulator launch smoke: build to `/tmp/amberagent-smoke-launch`, install + `simctl launch` on **iPhone 17** → `app.amber.ios` started (no crash on cold launch).

## Code changes in this acceptance pass

Warning / hygiene only (no feature expansion):

- `ChatViewModel.swift`, `BoardView.swift`, `MiniAppRunnerView.swift`: `_ = try? …saveArtifact` to silence unused-result warnings.
- `IOSSyncBackup.swift`: `IOSDeviceLabel.current` reads `UIDevice` on main thread (main sync when called off-main) to avoid Swift 6 main-actor isolation error.

**Not changed (P2):** `WebMountView` / `McpServersView` Toggle non-Sendable closure warnings; `AmberNative` simulator version linker warnings (requires native rebuild or deployment target alignment).

## Static smoke (IA / copy / product decisions)

Re-reviewed this session by reading the actual surface code (`PlaceholderViews.swift`, `ExecutionSettingsView.swift`, `WebMountView.swift`, `IOSLocalToolExecutor.swift`, `AgentActivityModels.swift`), aligned with handoff product decisions:

| Area | Evidence | Result |
| --- | --- | --- |
| Single Assistant | `AssistantsView` (`PlaceholderViews.swift:1427`) explains one Amber Assistant; no multi-assistant management on `SettingsHomeView` | Pass |
| Advanced features not “实验区” | `SettingsHomeView` (`:1053`) sections are 通用设置 / Agent 设置 / 模型与服务 / **高级功能** / 数据设置; advanced entries (WebMount / SubAgent / Council / MiniApp / Deep Read) are normal nav rows with no 实验区/实验性 suffix | Pass |
| Session shortcuts | `ConversationsView.shortcuts` (`:278`): 深度阅读、小应用、图片、核心记忆、WebMount、模型议会 — all formal, no 可用/已接 engineering suffixes | Pass |
| Pseudo settings | No fake API Key/status toggles found; settings rows all route to real views | Pass |
| `SearchView` | (`:637`) real conversation search over `IOSConversationStore.searchConversations`, not static fake web results | Pass |
| `WorkspaceView` | (`:1191`) real `IOSWorkspaceStore` UI (import / preview / reparse / artifact delete), not placeholder list | Pass |
| `ExecutionSettingsView` copy | No blanket “搜索/记忆/网页/MCP/模型议会/子代理工具可用”; only 远程执行 nav + 灵动岛 toggle + real recent tasks (release-plan P0.1 concern already resolved) | Pass |
| Redaction | `IOSWebMountRedactor` applied to all wm_* URLs/JSON (verified across `IOSLocalToolExecutor.swift`); Bearer handling is redaction logic (`AgentActivityModels.swift:300`) or real HTTP header (`IOSImageGenerationRepository.swift:113`), not UI leak. Tests at `68192b345`: DeepRead multiline, AdvancedTaskStore Bearer, WebMount JSON URLs | Pass (covered by XCTest) |
| WebMount info row “可用” | `WebMountView.swift:242` info block accurately states 正式能力=可用 / wm_eval=关闭 / URL allowlist=count — honest security-boundary explanation, not a fake toggle | Pass (kept as informational, P2 noted only) |

**Note:** `docs/ios-release-readiness-plan.md` still describes an older “实验区 + 默认关闭 tool gate” model. Current parity branch intentionally treats advanced capabilities as **正式高级功能** per `IOS_PARITY_HANDOFF` / roadmap. Release checklist should be reconciled before App Store narrative, not treated as a P0 code defect for this parity closure.

## Manual simulator smoke (recommended checklist)

**This session: cold-launch smoke executed on iPhone 17 (booted, UDID 293252D5).**

- `simctl install` of the test-build `.app` → exit 0.
- `simctl launch app.amber.ios` → started **PID 53661, exit 0** (cold launch, no crash).
- After 7s: process still registered in `launchctl list` (alive, healthy); screenshot captured (315 KB, non-blank, `/tmp/amber-smoke-01-home.png`).
- App launch log scan (`log show --predicate 'process == "iosApp"'`): no error/fault/crash/exception/fatal entries — only expected XPC / Security / RunningBoard / BackgroundTask lifecycle lines.

The remaining rows below need real model/search/image/SSH/cookie/network state and therefore require human pass with secrets; they are **out of scope for autonomous execution** per pause conditions (real API keys, paid services, real accounts).

1. **Fresh / no API Key** — chat composer guides to 服务商; no fake assistant message.
2. **Session home** — shortcuts open correct routes; titles without engineering suffixes. *(routes verified via static review this session)*
3. **Settings home** — 高级功能 entries navigate; no dead “说明冒充设置”. *(verified via static review this session)*
4. **Chat** — send with mock/stub key if available; tool approvals for memory / file / WebMount.
5. **Deep Read** — manual text task → history → retry; artifact save.
6. **Memory** — search, edit, delete, write approval card.
7. **Search services** — `search_web` / `scrape_web` rejection of localhost/private URL (tests).
8. **Image generation** — missing key state; history card.
9. **Mini App** — list, runner, bridge permission errors.
10. **Workspace** — import, preview, artifact delete.
11. **WebMount** — station toggle, open, extract handoff; timeline redaction.
12. **SubAgent / Council / Remote SSH** — task records, mock/stub paths, dangerous command rejection (tests).
13. **Permissions** — `PermissionsApprovalView` surfaces file / memory / WebMount.

| # | Path | This session |
| --- | --- | --- |
| Launch | Install + cold launch on iPhone 17 | **Pass** — PID alive, no crash, clean logs |
| Routes (2,3) | Session shortcuts + Settings home IA | **Pass** — static code review of `PlaceholderViews.swift` |
| 1, 4–13 | Key/account/network-dependent UX | **Not run** — requires real secrets (pause condition); covered by XCTest where no secret needed |

Static checks: `SettingsHomeView` uses **高级功能** (not 实验区); `ConversationsView` shortcuts match product list; no `实验区`/`实验性` strings under `iosApp/iosApp/*.swift`.

## Issue classification

### P0 (release blockers for parity closure)

None identified in the 2026-06-19 automated verification, static IA review, or cold-launch smoke on baseline HEAD (`70e1a7260`). Current tool-closure working tree also passes the automated gates listed at the top of this document.

### P1 (should fix before external PR review)

- Reconcile **release readiness doc** vs **parity product decision** (formal advanced features vs experimental gates). This is a docs-only addendum to `ios-release-readiness-plan.md` or a product sign-off — **not a code change**.
- Human **manual simulator smoke** of key/account/network-dependent paths (rows 1, 4–13) that cannot be run autonomously without real secrets. Pause condition.

### P2 (backlog)

- AmberNative xcframework iOS-simulator 26.5 vs link 26.0 `ld` warnings — requires a native rebuild or a product decision to raise the iOS deployment target; **do not touch without native build**.
- `WebMountView` info-row「可用」trailing label — confirmed informational status summary (正式能力=可用 / wm_eval=关闭 / allowlist=count), not a fake toggle; keep as-is unless product wants it reworded.
- Android parity gaps that require real API keys / accounts / SSH / OAuth (kept as explicit unsupported paths on iOS).

**Resolved this / prior pass (no longer open):**

- ~~Toggle non-Sendable warnings in `WebMountView.swift`, `McpServersView.swift`~~ — fixed by the 2026-06-20 tool-closure pass.
- ~~`ChatViewModel`/`BoardView`/`MiniAppRunnerView` unused `try? saveArtifact`~~ — fixed on `a81876885`.
- ~~`IOSSyncBackup` `UIDevice` main-actor warning~~ — fixed on `a81876885`.

## Next minimal work packet

**WP-FINAL-2: PR / push readiness (code is ready)**

1. Working tree is clean; warning cleanup is already committed on `a81876885`. No further code change needed for parity closure.
2. Verification block re-run this session: all four gates pass (see table above).
3. Open PR `codex/ios-port-wip` → `main` with links to `IOS_PARITY_HANDOFF_2026-06-19.md` + this acceptance doc.
4. Optional docs-only follow-up: addendum to `ios-release-readiness-plan.md` stating the parity branch treats advanced capabilities as formal features (supersedes the experimental-gate matrix for those capabilities).
5. Human smoke (rows 1, 4–13) to run before external review, with real-but-sandboxed keys if available; file any regression found as a separate P1.

**Pause / do not start without product or secrets**

- Real paid search/image providers, WebMount login, production SSH, App Store signing, entitlements, or expanding backup payload beyond settings.
- AmberNative deployment-target alignment (needs native rebuild + product decision).
