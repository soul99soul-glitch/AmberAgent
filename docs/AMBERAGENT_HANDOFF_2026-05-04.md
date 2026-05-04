# AmberAgent Handoff - 2026-05-04

This is the continuation note for the AmberAgent fork of RikkaHub.

Repo:

```text
/Users/arquiel/Downloads/AI/rikkashit/rikkahub
```

Current branch:

```text
amberagent/v0.5-wip
```

Latest verified code commit at handoff:

```text
7a724625 fix(agent): harden terminal output and tool introspection
```

Current packaged APK:

```text
/Users/arquiel/Downloads/AmberAgent-v0.8.0-arm64-debug.apk
```

## Product Direction

AmberAgent is no longer a normal chatbot fork. It is an Agent-only Android app built on top of RikkaHub's provider, MCP, chat UI, streaming, and settings foundation.

Key product meanings:

- AmberAgent itself is the application-level Agent.
- Old RikkaHub "Assistant = Agent" semantics should not appear as a primary product concept.
- Old Assistant data structures can remain for compatibility, but the user should not be asked to pick/switch assistants in normal flows.
- Agent capabilities should be visible, inspectable, and callable through tools.
- Do not fake capabilities. If something is stage0/stage1, label it honestly.

## Working Rules

- Before editing, run:

```bash
git status --short
git diff --stat
git diff --check
```

- Use the existing repo style. Do not rewrite the UI architecture.
- Do not whole-copy OpenOmniBot.
- Do not default to All Files Access; SAF workspace remains the main file path.
- Do not remove old compatibility data models unless explicitly requested.
- Use `apply_patch` for manual source edits.
- After code changes, run at minimum:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

- For workspace/path safety, also run:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.agent.workspace.WorkspacePathsTest' --no-daemon --stacktrace
```

- Avoid running two Gradle builds that both touch app resources at the same time. A prior parallel test/build produced a transient AAPT resource-link failure while `assembleDebug` succeeded. Run these checks sequentially when possible.

## Fixed Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/arquiel/Library/Android/sdk
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
AAPT=/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt
```

## Recent Verified State

Last successful checks:

```bash
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.agent.workspace.WorkspacePathsTest' --no-daemon --stacktrace
./gradlew :app:assembleDebug --no-daemon --stacktrace
git diff --check
```

Additional recheck from this continuation:

```bash
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.agent.workspace.WorkspacePathsTest' --no-daemon --stacktrace
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Smoke target:

```text
medium_phone AVD via adb emulator-5554
```

Smoke results:

- `terminal_execute` long-output smoke used Alpine/proot with a temporary AVD workspace. It ran 20,000 output lines, returned `exit_code=0`, `runtime=alpine-proot-stage1`, `aarch64`, Alpine `3.21.0`, and `/sbin/apk`. Output was intentionally capped with `Output truncated to the last 262144 characters...`. No app crash markers or `read interrupted` markers appeared in logcat.
- Safe Mode was forced by setting the crash flag, then launching `RouteActivity`. UI tree showed `Safe Mode`, `Current Agent: AmberAgent`, `Crash Report`, and `Copy`; `Switch Assistant` / `切换助手` was absent.
- `apps_list` returned 22 launcher apps on the AVD.
- `apps_installed_list` default returned 2 non-system apps; with `include_system=true, limit=1000`, it returned 248 packages.
- `tools_list` via debug smoke reported `available_tool_count=90`, `enabled_count=89`; it included `apps_list`, `apps_installed_list`, `permissions_status`, `skills_list`, `memory_list`, `memory_write`, and `memory_delete`.
- `permissions_status` returned 24 capabilities, including granted `apps` and `installed_apps_full_access`, mixed runtime permission states, and special-access-needed entries.
- `skills_list` returned `installed_count=1`, `enabled_count=1`, with built-in `skill-creator` available and no missing enabled skills.

APK metadata checked with `aapt`:

```text
package: me.rerere.amberagent.debug
versionCode: 170
versionName: 0.8.0
targetSdkVersion: 37
```

Important permissions confirmed in APK:

```text
android.permission.READ_SMS
android.permission.READ_CONTACTS
android.permission.QUERY_ALL_PACKAGES
android.permission.POST_NOTIFICATIONS
android.permission.MANAGE_EXTERNAL_STORAGE
```

At the last check, no adb device was online:

```text
adb devices -> List of devices attached
```

So v0.8.0 has not yet been installed from this session onto a physical phone after the last commit.

## What Has Been Implemented

### Identity / Build

- App identity is AmberAgent.
- Debug package is `me.rerere.amberagent.debug`.
- Current version is `0.8.0`.
- APK path: `/Users/arquiel/Downloads/AmberAgent-v0.8.0-arm64-debug.apk`.

### Agent Runtime Baseline

- Minimum Agent models exist:
  - `AgentRun`
  - `AgentEvent`
  - `ToolInvocation`
  - `ArtifactRef`
- Tool timeline/capsule work exists from earlier rounds.
- Bottom operation/sandbox preview work exists from earlier rounds.
- Auto-approval and permission-broker work exists from earlier rounds.

### Workspace / Terminal

- SAF workspace flow exists.
- Workspace path normalization exists and is tested by `WorkspacePathsTest`.
- SAF to app-private mirror sync exists.
- `terminal_execute` runs Alpine/proot stage1.
- Alpine/proot was previously verified on arm64 emulator:
  - `runtime=alpine-proot-stage1`
  - `uname` reported `aarch64`
  - Alpine `3.21.0`
  - `pwd=/workspace`
  - `command -v apk=/sbin/apk`
  - file created inside `/workspace` synced back to SAF workspace.

### Terminal Long Output Hardening

User saw Safe Mode after dependency installation with stack:

```text
Thread: amberagent-terminal-output-...
java.io.InterruptedIOException: read interrupted by close() on another thread
...
TerminalRuntime$execute...
```

Fix:

- `TerminalRuntime` output thread now catches `IOException`.
- If the process stream closes while the process is ending or being destroyed, this no longer becomes an uncaught background-thread crash.
- `onOutputLine` callback is wrapped with `runCatching` so UI callback failure cannot crash the terminal worker.
- UI activity output append is also wrapped so status UI failures do not stop output draining.
- `terminal_execute` result output is capped to the last 256 KiB with an explicit truncation notice, preventing long install logs from bloating memory and model/tool output.

File:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/terminal/TerminalRuntime.kt
```

### Safe Mode Old Assistant UI Removed

User reported Safe Mode still offered "切换助手".

Fix:

- Removed the Safe Mode "switch assistant" button and picker sheet.
- Updated copy from "Current assistant" to "Current Agent".
- Updated Chinese copy to say AmberAgent crashed and the user should inspect the crash report.

Files:

```text
app/src/main/java/me/rerere/rikkahub/ui/activity/SafeModeActivity.kt
app/src/main/res/values/strings.xml
app/src/main/res/values-zh/strings.xml
```

### P0-P2 Tool Surface

Latest commit added a broad stage1 tool expansion.

#### P0

- `tools_list`
- `permissions_status`
- Launcher app visibility via `<queries>` for `ACTION_MAIN` + `CATEGORY_LAUNCHER`
- `QUERY_ALL_PACKAGES` for experimental full installed-app listing
- `apps_installed_list`
- `app_info`

#### P1

- WebView helpers:
  - `webview_find_text`
  - `webview_links`
  - `webview_open_link`
- HTTP/download:
  - `http_request`
  - `download_file`
- Archive:
  - `archive_list`
  - `archive_extract`
  - `archive_create`
- Documents/images:
  - `pdf_read`
  - `pdf_render_page`
  - `office_read`
  - `image_info`
  - `image_convert`
  - `ocr_image`
- Screen helpers:
  - `screen_find_text`
  - `screen_tap_text`
  - `screen_wait_for_text`
  - `screen_scroll_until`
  - `screen_open_url`

#### P2

- System status:
  - `battery_status`
  - `network_status`
  - `wifi_status`
  - `device_info`
- Intent/settings/share/notification:
  - `settings_open`
  - `intent_open`
  - `share_text`
  - `share_file`
  - `notification_post`
- Memory:
  - `memory_list`
  - `memory_write`
  - `memory_delete`
- Skill management:
  - `skill_validate`
  - `skill_import`
  - `skill_enable`
  - `skill_disable`
- MCP management:
  - `mcp_list`
  - `mcp_test`
  - `mcp_import_from_skill`
- Run status:
  - `run_plan_update`

Important new files:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/tools/WorkspaceArtifactTools.kt
app/src/main/java/me/rerere/rikkahub/data/ai/tools/McpManagementTools.kt
```

Important modified integration files:

```text
app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt
app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt
app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt
app/src/main/java/me/rerere/rikkahub/di/AppModule.kt
app/src/main/java/me/rerere/rikkahub/data/agent/tools/SystemAccessTools.kt
app/src/main/java/me/rerere/rikkahub/data/agent/tools/ScreenAutomationTools.kt
app/src/main/java/me/rerere/rikkahub/data/automation/AmberAccessibilityService.kt
app/src/main/java/me/rerere/rikkahub/data/agent/workspace/WorkspaceManager.kt
```

## Known Limitations / Do Not Overclaim

- `ocr_image` is stage1 and currently does not provide a real local OCR runtime unless a dependency is later wired in. It should clearly say no local OCR runtime or use a future VLM fallback.
- `terminal_session_*` remains `android-shell-stage0`; do not describe it as Alpine/proot session.
- `tools_list` is now built from the same ChatService runtime tool set that is passed into generation, including memory tools when memory is enabled. It still reports enabled/generated tools only; disabled-tool enumeration remains stage1-limited.
- `skill_import` is conservative and mostly imports text-like Skill files. Binary assets in skills may need follow-up support.
- `apps_installed_list` uses `QUERY_ALL_PACKAGES`, which is acceptable for an experimental sideload APK but may not be Play-compliant.
- WebView read/preview had prior work, but the user still reported issues with WebView readability, screenshot thumbnail cropping, and repeated MediaProjection approval. Verify current behavior before claiming it is solved.
- OPPO Fluid Cloud adaptation plan exists conceptually, but current implementation state should be verified before making claims.

## User-Reported Open Issues To Recheck

These are the best next test targets:

1. Dependency install crash:
   - Rechecked with a 20,000-line `terminal_execute` smoke on AVD. No Safe Mode or app crash markers appeared.
   - Still worth repeating with a real dependency install on a user's configured physical device workspace.

2. Safe Mode:
   - Forced Safe Mode on AVD. No "切换助手" / "Switch Assistant" entry was present.

3. App list:
   - `apps_list` returned 22 launcher apps on AVD.
   - `apps_installed_list` returned 248 packages when `include_system=true`.

4. Tool discovery:
   - Debug smoke executed the same ChatService runtime tool set. `tools_list` reported 90 available tools and included system, skill, permission, and memory tools.
   - Still worth asking a configured model "你有哪些工具？" to verify model behavior, not just tool availability.

5. Skill visibility:
   - `skills_list` sees the built-in `skill-creator` as installed/enabled on fresh AVD.
   - Importing the user's Minis Skill package still needs a real package/workspace test.
   - Confirm `skill_import` can handle the user's Minis Skill package if it is text-based enough.

6. WebView:
   - Ask "打开 ifanr.com 看首页".
   - Confirm Agent prefers `webview_open`.
   - Confirm `webview_read`, `webview_links`, and `webview_open_link` are callable.

7. Permissions:
   - Confirm `permissions_status` reports runtime and special permissions accurately.

8. Memory:
   - Confirm `memory_list/write/delete` work for core, short-term, long-term and respect approval.

## Useful Commands

Build:

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Package to Downloads:

```bash
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk \
  /Users/arquiel/Downloads/AmberAgent-v0.8.0-arm64-debug.apk
```

Verify APK:

```bash
/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt dump badging \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | sed -n '1,8p'

/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt dump permissions \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk | rg 'QUERY_ALL_PACKAGES|READ_SMS|READ_CONTACTS|POST_NOTIFICATIONS'
```

ADB:

```bash
/Users/arquiel/Library/Android/sdk/platform-tools/adb devices

/Users/arquiel/Library/Android/sdk/platform-tools/adb install -r \
  /Users/arquiel/Downloads/AmberAgent-v0.8.0-arm64-debug.apk

/Users/arquiel/Library/Android/sdk/platform-tools/adb shell pm list packages | rg amberagent
```

## Suggested Context-Preservation Methods

Use several layers, not just one:

1. Local Git commits
   - Commit after each coherent stage.
   - Keep commit messages descriptive.
   - Current verified code anchor is `7a724625`.

2. Handoff markdown
   - This file is the human-readable bridge for the next session.
   - Update it after major milestones or crashes.

3. APK artifacts
   - Keep versioned APKs in `/Users/arquiel/Downloads`.
   - Include the version in the filename.

4. Evidence snippets
   - Save exact command outputs for build, aapt, adb, and crash traces.
   - Put key results in the handoff.

5. One prompt per new session
   - Start new sessions with one explicit continuation prompt.
   - Include the repo path, latest commit, current APK path, and next goal.

6. Avoid relying on screenshots alone
   - Screenshots are useful for UI bugs, but pair them with logs or a reproducible adb/app action.

## New Session Prompt

Copy this into the next Codex session:

```text
继续接手 AmberAgent 项目。

仓库：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

请先阅读：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub/docs/AMBERAGENT_HANDOFF_2026-05-04.md

当前本地 Git 锚点：
7a724625 fix(agent): harden terminal output and tool introspection

当前 APK：
/Users/arquiel/Downloads/AmberAgent-v0.8.0-arm64-debug.apk

当前目标：
不要重开大坑。上轮已经在 AVD 上验证：
- 长输出 terminal_execute 不触发 Safe Mode，输出被截断到 256KiB。
- Safe Mode 没有“切换助手”入口。
- apps_list 返回 22 个 launcher apps；apps_installed_list include_system=true 返回 248 个包。
- tools_list / permissions_status / skills_list 可从 Agent 运行时工具集看到系统、权限、Skill 和 memory 工具。

下一步优先补真实用户环境验证：
1. 在已配置 SAF workspace 的真机上跑一次真实依赖安装类 terminal_execute。
2. 问配置好的 Agent “你有哪些工具？”确认模型会主动调用 tools_list。
3. 导入用户的 Minis Skill 包，确认 skill_import / skills_list / skill_enable 路径。
4. 继续 WebView、Permissions、Memory 后续 open issues。

固定环境：
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/Users/arquiel/Library/Android/sdk
adb=/Users/arquiel/Library/Android/sdk/platform-tools/adb
aapt=/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt

执行规则：
- 开始先跑 git status --short、git diff --stat、git diff --check。
- 修改前先读相关代码，不猜架构。
- 不破坏 RikkaHub/AmberAgent 现有 UI 架构。
- 不整块搬 OpenOmniBot。
- 不用占位工具冒充完成。
- 每次修改后至少跑 compileDebugKotlin、assembleDebug；路径安全相关继续跑 WorkspacePathsTest。
- 如果有真机或 AndDrive 可用，用 adb/Computer Use 做真机 smoke test。
- 完成后打包到 /Users/arquiel/Downloads，必要时做本地 Git commit。

请先给一个非常短的状态确认，然后直接开始验证，不要只停在计划。
```
