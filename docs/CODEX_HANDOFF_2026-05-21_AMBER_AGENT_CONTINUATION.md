# Codex Handoff: AmberAgent / RikkaHub Main Continuation, 2026-05-21

## TL;DR

This handoff is for a fresh Codex session taking over AmberAgent mainline development.

Repo:

```text
/Users/arquiel/Downloads/AI/rikkashit/rikkahub
```

Current branch:

```text
main
```

Product remote:

```text
github-private  https://github.com/soul99soul-glitch/AmberAgent.git
```

Upstream reference remote:

```text
origin          https://github.com/rikkahub/rikkahub.git
```

Current pushed HEAD at handoff creation:

```text
470d6f29 Improve deep read templates and board model requests
```

The latest local `main` has already been rebased onto `github-private/main` and pushed successfully.

## Current Working Tree

At handoff creation, the only intentional local change is this handoff file itself.

Run first:

```bash
git status --short
git branch --show-current
git log --oneline -12
```

Do not assume older handoff docs are current. Prefer this document plus the live repo state.

## Recent Development Context

The last several sessions focused on 今日看板 / 深度阅读 / 小应用设置, especially after the Today Board refactor exposed unstable hot-list and deep-read behavior.

Important user direction:

- Main board should remain dense and mostly text-only.
- Deep Read should be the magazine-like, image-rich reading surface.
- Deep Read output must be genuinely useful. A low-information fallback article is not acceptable as “deep reading”.
- English sources are allowed, but the rendered dashboard/deep-read content should be Chinese. Original links may still point to English pages.
- Deep Read templates should be controlled/static templates, not arbitrary miniapps with JS or bridge access.
- Visual polish matters. The user strongly dislikes rough layout, oversized typography, broken image placeholders, duplicated images, and chaotic settings UI.
- Do not mix unrelated chat timeline animation work into Today Board / Deep Read patches.

## What Was Just Shipped

Commit:

```text
470d6f29 Improve deep read templates and board model requests
```

Main changes in that commit:

- Bumped local Notion build version to `2.2.5` / versionCode `384`.
- Reworked Deep Read template rendering and settings polish.
- Added Deep Read template runtime CSS injection so selected fonts also affect custom templates.
- Fixed selected Slides / built-in font routing into template CSS variables.
- Added broken image re-render handling in Deep Read WebView templates.
- Added image deduping so timeline/body images do not repeat several times.
- Improved title localization for cached/stale hot-list snapshots.
- Improved Deep Read empty-output diagnostics and retry messaging.
- Improved Deep Read template generation validation/repair flow.
- Reorganized miniapp settings into cleaner grouped secondary pages.
- Added Today Board model request option helper:
  - `app/src/main/java/me/rerere/rikkahub/data/agent/board/BoardModelRequestOptions.kt`
  - It rehydrates headers/bodies from the canonical model stored under `settings.providers`.
  - It is used by board summary, daily review, hot-list title localization, deep read generation, and deep-read template generation.

Why the model request helper exists:

The user reported that the model selected in Today Board seemed not to carry the Provider-model Header parameters, causing some Coding IDE / Coding Plan models to be rejected. In this codebase there is no separate `ProviderSetting.headers` field; the per-provider-model headers live on `Model.customHeaders`. The board code was making direct model calls, so the safer path is to resolve the canonical model from `settings.providers` at request time and merge headers/bodies from there.

## Build / Install State

Already verified before this handoff:

```bash
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:compileNotionKotlin
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:assembleNotion
```

Both passed.

The APK was installed successfully via:

```bash
/Users/arquiel/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s c9a8a837 install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
```

Last visible connected device during handoff:

```text
c9a8a837  OPD2515 / OP6548L1
```

APK path:

```text
app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
```

Copy target used in previous packaging runs:

```text
/Users/arquiel/Downloads/APK/AmberAgent-2.2.5-notion-arm64-v8a.apk
```

## Key Files To Know

Today Board / dashboard:

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/board/BoardPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/board/SettingTodayBoardPage.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/BoardSettings.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/agent/BoardAgent.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/agent/DailyReviewAgent.kt
```

Hot list:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/HotListRepository.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/HotListTitleLocalizer.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/providers/
```

Deep Read generation:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/DeepReadAgent.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/DeepReadPrompt.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/DeepReadOutputParser.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/DeepReadSanitizer.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/board/DeepReadScreen.kt
```

Deep Read templates:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/template/DeepReadTemplateAgent.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/template/DeepReadTemplateRenderer.kt
app/src/main/java/me/rerere/rikkahub/data/agent/board/hotlist/deepread/template/DeepReadTemplateValidator.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/board/DeepReadTemplateSettings.kt
```

Miniapp settings:

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/miniapp/MiniAppSettingsPage.kt
```

Board model request headers/bodies:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/board/BoardModelRequestOptions.kt
```

Tests touched recently:

```text
app/src/test/java/me/rerere/rikkahub/data/agent/board/hotlist/DeepReadSanitizerTest.kt
```

## Known Issues / Likely Next Work

### 1. Deep Read quality still needs real-device validation

The latest code has been built and installed, but the user may still find that Deep Read content is not informative enough for major topics like Gemini / Google I/O / Qwen / AI industry news.

Do not treat “model returned some Chinese text” as success. The success bar is:

- enough facts,
- timeline/background/causal story,
- clear “why it matters” analysis,
- source links clickable,
- Chinese presentation,
- no low-information source-list filler.

If quality is poor, inspect:

- `DeepReadPrompt.kt`
- source collection/enrichment in `DeepReadAgent.kt`
- staged generation prompts and token/time limits
- whether search results are actually relevant and rich
- model max-token rejection / empty-output diagnostics

### 2. Deep Read streaming/template generation UX is still experimental

The user wanted the final magazine template itself to appear immediately and fill in progressively, not a separate generic loading checklist. Some pieces exist, but the current experience may still not match that ideal.

If continuing this:

- Prefer rendering the chosen template skeleton first.
- Fill sections as overview/timeline/analysis/extended reading arrive.
- Keep layout stable during staged generation.
- Avoid huge placeholder cards that look like error cards.

### 3. Template typography/layout can still regress

The user called out:

- font size too large,
- low information density,
- selected fonts not taking effect,
- broken image placeholders,
- duplicate images,
- timeline text collapsing into one-character vertical columns,
- chaotic template settings controls.

Recent fixes address these, but validate on device before assuming solved.

### 4. Hot-list English display titles

Dashboard items can still surface English titles if localization is skipped, times out, or cannot repair stale cache. User wants displayed board titles to be Chinese when possible. Original URL can remain English.

### 5. Old handoff hygiene

Older handoff docs are often stale. If creating another handoff, either name it clearly with the current date or replace/remove obsolete untracked handoffs.

## Constraints / Preferences

- Do not push unless the user explicitly asks.
- Do not reopen large chat streaming/reveal experiments while working on Today Board.
- Keep patches scoped and simple.
- Do not over-engineer template runtime into unrestricted miniapps.
- Use subagents only when explicitly asked or when the task has clear independent review value. The user recently complained about too many open subagents, so close any subagents when done.
- Preserve unrelated user changes.
- Prefer `rg` for search.
- Use `apply_patch` for manual edits.

## Commands

Build:

```bash
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:compileNotionKotlin
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:assembleNotion
```

Install:

```bash
/Users/arquiel/Library/Android/sdk/platform-tools/adb devices -l
/Users/arquiel/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
```

Copy APK:

```bash
mkdir -p /Users/arquiel/Downloads/APK
cp app/build/outputs/apk/notion/app-arm64-v8a-notion.apk /Users/arquiel/Downloads/APK/AmberAgent-2.2.5-notion-arm64-v8a.apk
```

Useful checks:

```bash
git diff --check
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.agent.board.hotlist.*'
```

## Bootstrap Prompt For New Session

Use this prompt to start the next Codex session:

```text
你接手 AmberAgent / RikkaHub Android Kotlin/Compose 项目主线开发。

项目路径：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

当前产品线分支：
main

产品远端：
github-private = https://github.com/soul99soul-glitch/AmberAgent.git

请先按顺序读取：
1. docs/AI_HANDOFF_ZONES.md
2. CLAUDE.md
3. docs/CODEX_HANDOFF_2026-05-21_AMBER_AGENT_CONTINUATION.md

然后运行：
git status --short
git branch --show-current
git log --oneline -12

当前最新已推送提交：
470d6f29 Improve deep read templates and board model requests

当前重点方向：
今日看板 / 深度阅读 / 深度阅读模板 / 小应用设置。最近刚修了：
- 深读模板字体与图片链路
- 图片去重
- 模板生成校验/修复
- 小应用设置分组页面
- 看板模型请求携带 Provider 模型里的 customHeaders/customBodies

请不要直接猜补丁。先基于真机复现、logcat、实际搜索结果和模型响应判断问题在：
1. 热榜源抓取
2. 搜索/来源收集
3. DeepRead prompt 与 staged generation
4. JSON/parser/repair
5. 模板渲染/WebView/CSS
6. 设置/模型请求参数
哪一层。

用户核心要求：
- 主看板不需要图片，保持高密度中文热点列表。
- 深度阅读必须有真正信息量，不接受低质量“来源列表式兜底稿”。
- 英文源可以抓，但 UI 呈现尽量中文；原文链接保留跳转。
- 深读要有杂志感，但不能牺牲可读性和信息密度。
- 模板系统 v1 必须受控：静态 HTML/CSS，不开放 JS/bridge/任意网络。
- 不要混入聊天发送动画、蓝点等待态、bottom-follow 等无关实验。
- 不 push，除非我明确要求。

构建命令：
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:compileNotionKotlin
JAVA_HOME=$HOME/.gradle/jdks/jetbrains_s_r_o_-21-aarch64-os_x.2/jbrsdk_jcef-21.0.10-osx-aarch64-b1163.110/Contents/Home ./gradlew --offline -Pksp.incremental=false :app:assembleNotion

安装命令：
/Users/arquiel/Library/Android/sdk/platform-tools/adb devices -l
/Users/arquiel/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
```
