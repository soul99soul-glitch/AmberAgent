# AmberAgent iOS Final Acceptance 2026-06-19

Branch: `codex/ios-port-wip`  
Baseline: `2802c7fb4` (handoff doc) + warning cleanup commits on same branch  
Scope: parity closure **验收与发布前收口**（不扩大功能范围）

## Automated verification (this session)

| Check | Command | Result |
| --- | --- | --- |
| Whitespace | `git diff --check` | Pass |
| iOS build | `xcodebuild … generic/platform=iOS Simulator ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build` | Pass (AmberNative 26.5→26.0 linker warnings only) |
| KMP iOS | `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:linkDebugFrameworkIosSimulatorArm64` | Pass |
| Full tests | `xcodebuild … -destination "platform=iOS Simulator,name=iPhone 17" … test` | Pass (before and after warning fixes) |

Gradle did not require elevated `~/.gradle` permissions on this machine.

## Code changes in this acceptance pass

Warning / hygiene only (no feature expansion):

- `ChatViewModel.swift`, `BoardView.swift`, `MiniAppRunnerView.swift`: `_ = try? …saveArtifact` to silence unused-result warnings.
- `IOSSyncBackup.swift`: `IOSDeviceLabel.current` reads `UIDevice` on main thread (main sync when called off-main) to avoid Swift 6 main-actor isolation error.

**Not changed (P2):** `WebMountView` / `McpServersView` Toggle non-Sendable closure warnings; `AmberNative` simulator version linker warnings (requires native rebuild or deployment target alignment).

## Static smoke (IA / copy / product decisions)

Aligned with handoff product decisions:

| Area | Evidence | Result |
| --- | --- | --- |
| Single Assistant | `AssistantsView` explains one Amber Assistant; no multi-assistant management on `SettingsHomeView` | Pass |
| Advanced features not “实验区” | `SettingsHomeView` section **高级功能** lists WebMount / SubAgent / Council / MiniApp / Deep Read as normal nav entries | Pass |
| Session shortcuts | `ConversationsView` shortcuts: 深度阅读、小应用、图片、核心记忆、WebMount、模型议会 | Pass |
| Pseudo settings | `BoardSettingsView` explicitly avoids fake API Key toggles; provider/search copy uses real Key states | Pass (no new pseudo toggles found) |
| `SearchView` | Conversation search over `IOSConversationStore`, not static fake web results | Pass |
| `WorkspaceView` | Real `IOSWorkspaceStore` UI (not placeholder list) | Pass |
| Redaction | Tests at `68192b345`: DeepRead multiline, AdvancedTaskStore Bearer, WebMount JSON URLs | Covered by XCTest |

**Note:** `docs/ios-release-readiness-plan.md` still describes an older “实验区 + 默认关闭 tool gate” model. Current parity branch intentionally treats advanced capabilities as **正式高级功能** per `IOS_PARITY_HANDOFF` / roadmap. Release checklist should be reconciled before App Store narrative, not treated as a P0 code defect for this parity closure.

## Manual simulator smoke (recommended checklist)

Automated launch: app installs/launches on **iPhone 17** simulator when built to `-derivedDataPath` (verify locally). Full UX still needs human pass:

1. **Fresh / no API Key** — chat composer guides to 服务商; no fake assistant message.
2. **Session home** — shortcuts open correct routes; titles without engineering suffixes.
3. **Settings home** — 高级功能 entries navigate; no dead “说明冒充设置”.
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

Record pass/fail per row before PR / push.

## Issue classification

### P0 (release blockers for parity closure)

None identified in automated verification or static IA review on current HEAD + warning fixes.

### P1 (should fix before external PR review)

- Reconcile **release readiness doc** vs **parity product decision** (formal advanced features vs experimental gates) in docs only or product sign-off.
- Human **manual simulator smoke** sheet above not yet filled in this session.

### P2 (backlog)

- Toggle non-Sendable warnings in `WebMountView.swift`, `McpServersView.swift`.
- AmberNative xcframework iOS-simulator 26.5 vs link 26.0 warnings.
- `WebMountView` static trailing label「可用」on info row (informational, not a fake toggle).
- Android parity gaps that require real API keys / accounts / SSH / OAuth (explicit unsupported paths).

## Next minimal work packet

**WP-FINAL-1: PR / push readiness**

1. Commit warning-hygiene changes; ensure `git status` clean.
2. Run full verification block once more on CI or local machine.
3. Complete manual smoke table (13 rows); file P1 if any route broken.
4. Open PR `codex/ios-port-wip` → target branch with link to `IOS_PARITY_HANDOFF_2026-06-19.md` + this acceptance doc.
5. Optional doc-only follow-up: addendum to `ios-release-readiness-plan.md` stating parity branch supersedes experimental gate matrix for advanced features.

**Pause / do not start without product or secrets**

- Real paid search/image providers, WebMount login, production SSH, App Store signing, entitlements, or expanding backup payload beyond settings.