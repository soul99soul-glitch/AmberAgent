# AmberAgent iOS Chat Handoff - 2026-07-01

## Current State

- Repo: `/Users/mi/Downloads/AI/AmberAgent-iOS`
- Branch: `feat/ios-provider-parity-claude`
- HEAD: `ab73ad88c Migrate chat list to ChatLayout collection view`
- Do not commit, push, reset, stash, or overwrite unrelated user changes unless explicitly asked.
- Existing untracked file `ZCODE_HANDOFF_CHAT_SCROLL_2026-06-30.md` was present before this pass and should be left alone unless the user asks.

## What Was Just Implemented

### User message insertion animation

Files touched:

- `iosApp/iosApp/ChatMessageProjection.swift`
- `iosApp/iosApp/ChatCollectionMessageList.swift`
- `iosApp/iosAppTests/ChatMessageProjectionTests.swift`

Behavior added:

- `ChatMessageProjector` already marks only the last real `.userMessageAppended` row as `canAnimateInsertion`.
- New `ChatInsertionAnimationPolicy` maps those rows to `message-\(messageId)` item ids only when the id was not in the previous diffable snapshot.
- `ChatCollectionViewController.applyCurrentSnapshot()` queues animated insertion ids before applying the snapshot.
- `collectionView(_:willDisplay:forItemAt:)` consumes queued ids and runs a small spring alpha/translation/scale animation on the cell.
- Diffable snapshot still uses `animatingDifferences: false`; scroll is kept separate from row insertion animation.

### Codex request diagnostic logging

Files touched:

- `iosApp/iosApp/IOSCodexProviderResolver.swift`
- `iosApp/iosApp/ChatGenerationCoordinator.swift`
- `iosApp/iosAppTests/IOSCodexProviderResolverTests.swift`

Behavior added:

- `IOSCodexProviderResolver.writeRequestDiagnostic(...)` writes a line to app cache `codex-debug.log` for Codex providers.
- It logs safe fields only: original auth mode, resolved base URL, final `/responses` URL, model, header names, and bearer type/length.
- It must not log token values, header values, or account ids.
- `ChatGenerationCoordinator.start(...)` currently calls the diagnostic before starting streaming.

### Device install script

File touched:

- `iosApp/scripts/install-device-experimental-gpl.sh`

Behavior added:

- Added `-skipMacroValidation -skipPackagePluginValidation` to the experimental GPL device build command.

Note:

- The experimental GPL scheme still failed on this machine due missing provisioning profiles for `app.amber.ios.experimental-gpl` and its activity extension.
- The main `iosApp` scheme built, installed, and launched successfully on the connected device.

## Verification Already Run

Xcode project regeneration:

```bash
cd iosApp
/opt/homebrew/bin/xcodegen generate
```

Focused tests:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/ChatMessageProjectionTests \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -only-testing:iosAppTests/IOSCodexProviderResolverTests \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentProjectionTestDerivedData
```

Result:

- 21 tests passed, 0 failures.

Whitespace check:

```bash
git diff --check
```

Result:

- Passed.

Main scheme device build:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=94918570-0680-5B93-8E38-7E6B355D4426' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build
```

Result:

- `** BUILD SUCCEEDED **`

Install and launch:

```bash
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app

xcrun devicectl device process launch \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  --terminate-existing app.amber.ios
```

Result:

- App installed and launched.

## Subagent Review Findings To Fix Next

No Critical findings.

### Important 1 - sending after scrolling away does not resume controller follow state

Files:

- `iosApp/iosApp/ChatView.swift`
- `iosApp/iosApp/ChatCollectionMessageList.swift`

Problem:

- Sending a message after the user has scrolled upward clears the SwiftUI outer `viewportState.followPaused`, but the internal `ChatCollectionViewController.store.viewportState.followPaused` remains `true`.
- Then `.userMessageAppended` reaches `ChatViewportReducer.reduce(...)` with `followPaused == true`, so the collection view may not perform the no-animation bottom follow.
- The newly appended user row can remain offscreen, and its queued insertion animation id can be consumed much later when the user scrolls to it, causing a delayed and wrong "new message" animation.

Suggested precise fix:

- Do not add a broad new scroll system.
- In the collection controller, treat `.userMessageAppended` as a local intent to resume follow before reducing viewport commands.
- Clear `store.viewportState.followPaused` and `store.viewportState.userDragging` before `ChatViewportReducer.reduce(...)` for `.userMessageAppended`.
- Keep the resulting user append scroll non-animated, matching existing `ChatViewportPolicy`.
- Consider clearing stale `animatedInsertionItemIDs` if a later non-user event arrives before the queued id is displayed.

Acceptance:

- If user is scrolled away and sends a new message, the new user message should appear at bottom immediately with row insertion animation.
- No delayed insertion animation should fire when browsing old messages later.

### Important 2 - animated cell state is not reset on reuse/configuration

File:

- `iosApp/iosApp/ChatCollectionMessageList.swift`

Problem:

- The insertion animation mutates `cell.alpha`, `cell.transform`, and layer animations.
- Normal cell configuration does not reset these properties.
- If a cell is refreshed/reused while the 0.34s animation is active, another row can inherit alpha/transform/animation state.

Suggested precise fix:

- At the start of `UICollectionView.CellRegistration` configuration, reset:

```swift
cell.layer.removeAllAnimations()
cell.alpha = 1
cell.transform = .identity
```

- Also consider the same cleanup in `didEndDisplaying` for a small extra guard.

Acceptance:

- Reused cells never carry previous insertion animation opacity/transform.
- The intended user insertion animation still runs when `willDisplay` consumes a queued id.

## Smaller Follow-Ups

### Test gap - duplicate animation id should not be queued

File:

- `iosApp/iosAppTests/ChatMessageProjectionTests.swift`

Add a test where `previousItemIDs` already contains the last user item id and assert `ChatInsertionAnimationPolicy.animatedInsertionItemIDs(...) == []`.

### Codex diagnostic should move to unified streaming entry

File:

- `iosApp/iosApp/ChatGenerationCoordinator.swift`

Current issue:

- Diagnostic logging is in `start(...)`.
- Some re-entry paths call `prepareAndStartStreaming(...)` directly, such as tool continuation/regeneration paths.
- If Codex 404 happens in those paths, `codex-debug.log` can miss the actual request and mislead debugging.

Suggested precise fix:

- Move `IOSCodexProviderResolver.writeRequestDiagnostic(...)` into `prepareAndStartStreaming(...)` after:

```swift
let effectiveParams = IOSCodexProviderResolver.augmentParamsForCodex(params, provider: effectiveProvider)
```

- Remove the earlier `start(...)` call to avoid duplicate logs for normal sends.

### Codex diagnostic should ignore blank header names

File:

- `iosApp/iosApp/IOSCodexProviderResolver.swift`

Current issue:

- Diagnostic header map records all custom header names.
- The KMP request path filters blank header names before sending.
- A blank leftover header row could show up in diagnostics even though it was not actually sent.

Suggested precise fix:

- When building the diagnostic header map, trim names and ignore blank names.
- Keep logging names only, not values.

## Suggested Next Verification

After fixing the items above:

```bash
cd iosApp
/opt/homebrew/bin/xcodegen generate
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild test -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -only-testing:iosAppTests/ChatMessageProjectionTests \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -only-testing:iosAppTests/IOSCodexProviderResolverTests \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentProjectionTestDerivedData
```

```bash
git diff --check
```

Then install to device using the main `iosApp` scheme if visual behavior changed:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=94918570-0680-5B93-8E38-7E6B355D4426' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation build
```

```bash
xcrun devicectl device install app \
  --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /Users/mi/Library/Developer/Xcode/DerivedData/AmberAgent-gfeldsdglrkbjmebgswcebkbpeiy/Build/Products/Debug-iphoneos/iosApp.app
```

## Manual QA To Ask User For

- Scroll up in a long session, send a new message, confirm it lands at bottom without delayed or missing user animation.
- Scroll up/down through history, confirm no sudden jump toward top and no delayed "new message" animation on old rows.
- Drag to top/bottom bounce, confirm rebound no longer shakes.
- Trigger Codex text/image generation once and inspect app cache `codex-debug.log` for the actual `/responses` request line without secrets.

## Prompt For Next Agent

```text
你现在接手 AmberAgent iOS/KMP 仓库，请全程中文交流。仓库路径：/Users/mi/Downloads/AI/AmberAgent-iOS，分支：feat/ios-provider-parity-claude。先阅读 AGENTS.md、CODEX_HANDOFF.md，以及 CODEX_HANDOFF_CHAT_REVIEW_2026-07-01.md。不要 commit、不要 push、不要 reset、不要覆盖用户改动。

当前工作区已有未提交改动：ChatCollectionMessageList、ChatMessageProjection、IOSCodexProviderResolver、ChatGenerationCoordinator、ChatMessageProjectionTests、install-device-experimental-gpl.sh，以及新文件 iosApp/iosAppTests/IOSCodexProviderResolverTests.swift。不要从头重写，精准修复 handoff 中 subagent review 指出的真实遗留问题：

1. 用户上滑后发送新消息时，collection controller 内部 followPaused 没恢复，导致 user append 可能不贴底、插入动画延迟触发。请在 ChatCollectionMessageList/相关最小范围内修，不要引入新滚动架构。
2. 插入动画修改了 cell alpha/transform，但 cell 配置/reuse 没复位。请在 CellRegistration 配置开头复位 layer animations、alpha、transform，必要时 didEndDisplaying 也清理。
3. 补 ChatInsertionAnimationPolicy 的测试：previousItemIDs 已包含同一个 user item 时不应再入队。
4. Codex 诊断日志从 start(...) 下沉到 prepareAndStartStreaming(...) 的 effectiveParams 生成之后，覆盖工具续写等 re-entry，并移除 start 中重复日志。
5. Codex 诊断构建 header map 时过滤空白 header name，继续保证不记录 token、header value、account id。

修完后运行 xcodegen generate、相关 xcodebuild tests、git diff --check。若改动影响真机体验，请按主 iosApp scheme 构建安装到设备。汇报时说明改了哪些文件、验证命令和结果、还剩哪些必须真机肉眼确认的体验点。
```
