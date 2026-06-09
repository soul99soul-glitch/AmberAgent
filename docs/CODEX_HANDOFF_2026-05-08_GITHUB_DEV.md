# AmberAgent Codex Handoff - 2026-05-08 - GitHub Dev Continuation

This handoff is for continuing AmberAgent development from another computer.

## Current Repo State

- Local repo: `/Users/arquiel/Downloads/AI/rikkashit/rikkahub`
- Current branch: `experiment/ui-redesign-20260505`
- Current commit: `444b25fb feat(chat): stabilize scroll and codex oauth flows`
- Working tree at handoff time: clean
- Private GitHub repo: `https://github.com/soul99soul-glitch/AmberAgent`
- Private GitHub visibility: `PRIVATE`
- Current local remotes:
  - `github-private`: `https://github.com/soul99soul-glitch/AmberAgent.git`
  - `origin`: `https://github.com/rikkahub/rikkahub.git`
- Current branch tracks: `github-private/experiment/ui-redesign-20260505`

The GitHub repo being private does not make the project public or open-source. If it is later made public, that still does not by itself define an open-source license; the license file does.

## New Machine Bootstrap

Clone the private repo and check out the active branch:

```sh
git clone https://github.com/soul99soul-glitch/AmberAgent.git
cd AmberAgent
git checkout experiment/ui-redesign-20260505
```

If you also want to keep the upstream RikkaHub remote:

```sh
git remote rename origin github-private
git remote add origin https://github.com/rikkahub/rikkahub.git
git fetch --all --prune
git branch --set-upstream-to=github-private/experiment/ui-redesign-20260505 experiment/ui-redesign-20260505
```

Useful sanity checks:

```sh
git status --short
git branch --show-current
git log -5 --oneline
git remote -v
```

## Build / Install State

- App namespace still: `me.rerere.rikkahub`
- Base application id: `me.rerere.amberagent`
- Notion flavor package: `me.rerere.amberagent.notion`
- Current version: `1.1.34`
- Current versionCode: `221`
- Latest APK artifact on this Mac: `/Users/arquiel/Downloads/AmberAgent-v1.1.34-notion.apk`

Required compile command used in this project:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileNotionKotlin --no-daemon --stacktrace
```

Build Notion APK:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleNotion --no-daemon --stacktrace
```

Install to the connected device:

```sh
adb install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
adb shell dumpsys package me.rerere.amberagent.notion | rg "versionCode|versionName|DEBUGGABLE"
```

If an APK is produced for user handoff, continue incrementing version name/code from `1.1.34 / 221`.

## Verification Rule

After every code edit, run at least:

```sh
git diff --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileNotionKotlin --no-daemon --stacktrace
```

Do not revert existing user changes. Do not use `git reset --hard` or checkout-away local work unless the user explicitly asks.

## Recent Work Summary

The latest committed state includes:

- Chat scroll stabilization after long performance work.
- Long assistant Markdown item virtualization / debug probes from the jank investigation path.
- Codex OAuth experimental OpenAI provider path.
- Codex model fetch improvements, including filtering non-chat review model entries.
- Codex `/usage` style quota lookup integration.
- Fixes for bottom-follow / near-bottom follow after message item virtualization.
- Context compression fallback improvements when switching to a smaller-context model.
- Model settings behavior where title/suggestion/compression task models can follow the current chat model by default.

The user accepted the current chat scrolling state as "roughly enough" for now. Do not reopen the scroll-performance loop unless they explicitly ask.

## Known Product / Architecture Direction

The user wants AmberAgent to eventually become independent from RikkaHub. Current audit findings:

- It is still a strong RikkaHub fork at code-identity level.
- `me.rerere.rikkahub` still appears across hundreds of files.
- Android namespace is still `me.rerere.rikkahub`.
- `RikkaHubApp`, `RikkahubTheme`, backup paths, README links, RikkaHub search service, and upstream license text still exist.
- The Agent runtime does not import LangChain / AutoGen / CrewAI / LlamaIndex as its core framework. More precisely, it is:
  - RikkaHub chat/provider/message foundation, including the original basic tool-call loop shape in `GenerationHandler`.
  - AmberAgent extensions on top of that loop: permission decisions, tool dispatch policy, speculative read-only tool execution, task runtime, terminal/workspace tools, subagents, cron, context compression, and long-running activity records.
  - Project-local `Tool` abstraction and `AgentToolDispatcher` implementation.
  - MCP as an external tool protocol via `io.modelcontextprotocol:kotlin-sdk`.
  - Open-source agent products such as Codex/opencode are reference points for OAuth/tooling behavior, not vendored core Agent framework code in this repo.

If continuing the "detach from RikkaHub" work, split it into stages:

1. Surface cleanup: README links, RikkaHub naming, search service naming, backup paths, theme/app class names.
2. Engineering identity migration: package namespace, app namespace, tests, Room schemas, backup compatibility.
3. True architectural replacement: replace inherited chat/provider/conversation/storage modules with AmberAgent-owned architecture.

Do not treat package renaming as legal independence. If original RikkaHub code remains, the project is still likely a derivative work and must respect the current license or a separate authorization.

## Important Open-Source Components

High-signal components to track for license/compliance:

- RikkaHub upstream codebase.
- MuPDF Java bindings and `libmupdf_java.so`.
- PrismJS 1.30.0.
- Jetpack Compose / AndroidX / Kotlin / Koin / OkHttp / Retrofit / Ktor / Room / DataStore / WorkManager.
- MCP Kotlin SDK.
- QuickJS Android wrapper.
- Coil, uCrop, JLatexMath Android fork, jsoup, Apache Commons Text, metadata-extractor.
- Web UI stack: React, React Router, TanStack Query, Streamdown, KaTeX, Shiki, remark/rehype.
- Built-in terminal runtime downloads: PRoot, libtalloc, Alpine minirootfs.
- Provider icon assets should be checked separately; they are not automatically safe just because they are in repo assets.

## Guardrails

- Read relevant code before modifying it.
- Keep changes narrowly scoped.
- Do not refactor provider / terminal / workspace / timeline safety paths unless the task directly requires it.
- Preserve Markdown rendering fidelity, table behavior, text selection, links, code blocks, LaTeX, generation follow, and message actions.
- If touching chat list virtualization, remember that message count is no longer equal to LazyList item count.
- Prefer evidence-based fixes over broad rewrites.

## Useful Files

- `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`
- `app/src/main/java/me/rerere/rikkahub/data/agent/runtime/AgentToolDispatcher.kt`
- `app/src/main/java/me/rerere/rikkahub/data/agent/tools/ToolRegistry.kt`
- `ai/src/main/java/me/rerere/ai/core/Tool.kt`
- `ai/src/main/java/me/rerere/ai/provider/Provider.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/OpenAIProvider.kt`
- `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAICodexOAuth.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- `app/build.gradle.kts`
- `gradle/libs.versions.toml`

## Last Good Commands

```sh
gh auth status
gh repo view soul99soul-glitch/AmberAgent --json nameWithOwner,visibility,isPrivate,url,defaultBranchRef
git status --short
git branch -vv --no-abbrev
```

At handoff time, `gh` was authenticated as `soul99soul-glitch` on this Mac.
