<div align="center">
  <img src="docs/icon.png" alt="AmberAgent app icon" width="100" />
  <h1>AmberAgent</h1>

  <p>
    A personal Android app for agent-style chat, deep reading, SubAgents, and local tools on a phone.
  </p>

  <p>
    English | <a href="README_ZH_CN.md">简体中文</a> | <a href="README_ZH_TW.md">繁體中文</a>
  </p>
</div>

<div align="center">
  <img src="docs/img/amberagent-home-blue.jpg" alt="AmberAgent home screen" width="240" />
  <img src="docs/img/amberagent-chat-blue.jpg" alt="AmberAgent chat with subagents" width="240" />
  <img src="docs/img/amberagent-board-blue.jpg" alt="AmberAgent Today Board" width="240" />
</div>

## What Is AmberAgent?

AmberAgent is a personal open-source Android project for exploring what AI agent workflows can feel like when they are
designed for a phone first. It started as a deep fork of [RikkaHub](https://github.com/rikkahub/rikkahub) and is now
maintained around tool use, SubAgents, deep reading, local state, mobile UI, and experiments with local developer
runtimes.

This repository is not an official RikkaHub release or successor. Upstream attribution and license obligations are
preserved, and the project remains a non-commercial personal research and learning project.

## Highlights

- **Agent chat that shows its work**: tool calls, cancellations, generated cards, and run state stay visible in the
  conversation instead of being hidden behind a plain message bubble.
- **SubAgents for role-based work**: fixed or dynamic roles can split a task, report progress, and bring their results
  back into one chat.
- **Today Board and deep reading**: AmberAgent can collect hot topics, gather sources, plan a reading structure, write
  sections, and keep evidence attached to the report.
- **Mobile tool surfaces**: search results, files, local device actions, browser-like cards, slide previews, and live
  HTML are shown in forms that make sense on Android.
- **Optional local CLI seats**: Gemini CLI, Antigravity CLI, Codex CLI, Claude Code, and Kimi CLI are being tested as
  council participants when they can be detected, authenticated, and run in a controlled way.
- **A long-lived personal workspace**: providers, prompts, settings, workspace state, sync, and backups are treated as
  part of the app, not throwaway session data.

## Project Status

AmberAgent is experimental and moves quickly. It still contains substantial RikkaHub-derived foundations alongside
large independent changes. Expect rough edges, local configuration requirements, and occasional breakage. It is a
personal research app and codebase, not a polished end-user distribution.

## Building

Use Android Studio or Gradle from the repository root:

```bash
./gradlew :app:assembleGraphite
```

`Graphite` is the canonical AmberAgent build type and targets the `app.amber.agent` package.
For local development, some cloud-backed features require private configuration files that are intentionally not
committed, such as `app/google-services.json`. The app can be built for local development without shipping those
private credentials, but Firebase/Google-related features may be limited unless the file contains clients for the
package you build.

## Contributing

Small, focused issues and PRs are welcome, especially around bug reports, reproducible crashes, documentation, and
tests. Large drive-by rewrites are hard to review in this repository because the project is still actively separating
its agent architecture from inherited chat-client foundations.

Technology stack:

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Koin](https://insert-koin.io/)
- [Room](https://developer.android.com/training/data-storage/room)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [OkHttp](https://square.github.io/okhttp/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Coil](https://coil-kt.github.io/coil/)
- [Material You](https://m3.material.io/)

## Attribution

AmberAgent is a deep fork of [RikkaHub](https://github.com/rikkahub/rikkahub). Portions of the codebase, architecture,
resources, and historical design are derived from RikkaHub and remain subject to the original project's license and
attribution requirements. New AmberAgent-specific agent features and refactors are maintained in this repository.

## License

See [LICENSE](LICENSE). AmberAgent preserves upstream licensing obligations for RikkaHub-derived code and is licensed
under AGPL v3 with a commercial dual-license path. It is currently maintained as a personal, non-commercial open-source
project.
