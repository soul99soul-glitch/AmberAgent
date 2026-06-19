# Final Report: AmberAgent iOS WebMount advanced closure

## Outcome

Implemented the iOS WebMount advanced feature closure for the mockable/static surface: formal advanced entry, site registry/enable-disable, registry-backed URL allowlist, WKWebView runtime state, safe read-only page extraction, navigation, cookie summary/clear-session path, WebMount permission previews, redacted chat timeline summaries, and content handoff into chat/deep-read fallback.

Full Xcode build and iosAppTests are blocked by an existing out-of-scope `SubAgentRunner.swift` syntax error, so this run records exact commands/errors and relies on targeted Swift parse checks plus unit-test source updates for WebMount-specific validation.

## Accepted Results

- Capability gate matrix created at `results/capability-gate-matrix.md`.
- iOS intentionally treats WebMount as a formal advanced feature without a global experimental kill switch.
- `wm_open` now requires a registered enabled station and fresh allowlist synchronization, blocking stale direct-host opens after site deletion.
- Default URL policy allows registered `http` and `https` hosts, while blocking `file:`, `javascript:`, `data:`, and unregistered hosts.
- Model-initiated `wm_*` tools default to explicit user approval; user-initiated foreground UI actions can execute directly.
- `wm_clear_session` remains foreground-user-action only.
- Raw HTML reads are denied on iOS; sensitive value selectors are refused.
- Tool input/output and chat timeline summaries redact query strings, fragments, bearer tokens, authorization-like key/value strings, cookie values, tokens, and secrets.
- DOM extraction supports readable text, interactive candidates, and snapshot candidates with rect metadata for visual review.
- WebMount detail UI exposes open/state/extract/snapshot/back/forward/clear-session plus chat/deep-read handoff actions.
- Deep-read handoff uses a local `webmount` board signal fallback and does not alter DeepRead mainline generation logic.
- Updated tests cover settings defaults, allowlist denial, registered/enabled station enforcement, redaction, permission denial/approval path, denied raw HTML/sensitive selectors, chat timeline redaction, and handoff payloads.

## Rejected Results

- Did not enable arbitrary JS eval.
- Did not implement OAuth, signed fetch, site-specific adapters, profile synthesis, real screenshot capture, or real visual-read tooling.
- Did not touch real accounts, real cookies, production data, certificates, release configuration, Android business logic, MiniApp mainline, Workspace/File mainline, SubAgent mainline, or model council logic.
- Did not edit the Xcode project file or add new Swift files that would require project membership changes.

## Conflicts Resolved

- Android's global WebMount toggle was not ported because the goal explicitly requires WebMount to be a formal advanced feature, not hidden as an experimental/static entry.
- Android raw DOM and richer visual/fetch/profile tools were narrowed on iOS: read-only text/value/attribute extraction is available, raw HTML and high-risk tool names return denied/unsupported.
- Persistent WebKit session use is explicit through `WKWebsiteDataStore.default`; cookie summaries expose only counts/domains, never values.
- Content handoff avoids modifying the DeepRead mainline by injecting a board signal source that the user can generate from manually.

## Verification Evidence

- `git status --short --branch`: initial branch `codex/ios-port-wip...origin/codex/ios-port-wip [ahead 16]`; `.workflow/` untracked.
- `git log --oneline --decorate -12`: captured before implementation, latest commit `dd0f86c6f Consolidate iOS capability parity work`.
- `git diff --check`: passed.
- `xcrun swiftc -parse iosApp/iosApp/IOSLocalToolExecutor.swift iosApp/iosApp/WebMountView.swift`: passed.
- `xcrun swiftc -parse -F shared/build/bin/iosSimulatorArm64/debugFramework iosApp/iosApp/ChatView.swift iosApp/iosApp/BoardView.swift`: passed.
- `xcrun swiftc -parse -F shared/build/bin/iosSimulatorArm64/debugFramework iosApp/iosAppTests/IOSLocalToolExecutorTests.swift iosApp/iosAppTests/ChatViewModelSelectedFileContextTests.swift iosApp/iosAppTests/IOSCapabilityRegistryTests.swift`: passed.
- `xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" -derivedDataPath /tmp/amberagent-ios-derived CODE_SIGNING_ALLOWED=NO build`: blocked with `Unable to locate a Java Runtime`.
- `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" -derivedDataPath /tmp/amberagent-ios-derived CODE_SIGNING_ALLOWED=NO build`: KMP shared build succeeded, then iosApp compile failed in out-of-scope `iosApp/iosApp/SubAgentRunner.swift:321:20` (`Static methods may only be declared on a type`) and `iosApp/iosApp/SubAgentRunner.swift:329:1` (`Extraneous '}' at top level`).

## Remaining Risks

- Live WKWebView behavior, simulator UI navigation, and real cookie clearing could not be validated until the unrelated SubAgent compile blocker is fixed.
- Real login/OAuth/account flows remain intentionally untested and unsupported.
- DOM snapshot is a safe visual-candidate approximation, not native screenshot capture.
- Existing unrelated worktree changes are present in many files; this closure only claims the WebMount-related files listed in the final user response.

## Reusable Follow-up

- After the SubAgent compile blocker is resolved, rerun `env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" -derivedDataPath /tmp/amberagent-ios-derived CODE_SIGNING_ALLOWED=NO build`.
- Then run targeted iosAppTests for `IOSLocalToolExecutorTests`, `ChatViewModelSelectedFileContextTests`, and `IOSCapabilityRegistryTests`.
- If product later wants real screenshot/visual-read/OAuth/signed-fetch/site-adapter support, treat each as a separate high-risk capability with explicit permissions and dedicated tests.
