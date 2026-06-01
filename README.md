<div align="center">
  <img src="docs/icon.png" alt="AmberAgent app icon" width="100" />
  <h1>AmberAgent</h1>

  <p>
    A personal, non-commercial Android agent runtime for mobile-first AI workflows.
  </p>

  <p>
    English | <a href="README_ZH_CN.md">简体中文</a> | <a href="README_ZH_TW.md">繁體中文</a>
  </p>
</div>

<div align="center">
  <img src="docs/img/amberagent-chat.jpg" alt="AmberAgent chat with subagents" width="240" />
  <img src="docs/img/amberagent-board.jpg" alt="AmberAgent Today Board" width="240" />
  <img src="docs/img/amberagent-settings.jpg" alt="AmberAgent display settings" width="240" />
</div>

## What Is AmberAgent?

AmberAgent is a personal open-source Android project exploring what an AI agent runtime can feel like on a phone. It
started as a deep fork of [RikkaHub](https://github.com/rikkahub/rikkahub) and is now independently maintained around
agent-oriented workflows: tool use, subagents, deep reading, local-first state, mobile UI, and external runtime
experiments.

This repository is not an official RikkaHub release or successor. Upstream attribution and license obligations are
preserved, and the project remains a non-commercial personal research and learning project.

## Current Focus

- Mobile-first chat and agent execution on Android.
- Subagents and role-based multi-agent workflows.
- Deep reading, source collection, section writing, and report-style output.
- Tool execution surfaces for search, files, browser-like cards, and local app capabilities.
- Rich generated artifacts, including live HTML/PPT-style previews.
- External CLI seats for local developer tools where they can be safely probed and run.
- Sync and backup flows for preserving personal configuration and workspace state.

## Project Status

AmberAgent is an experimental, fast-moving codebase. It contains substantial inherited RikkaHub foundations alongside
large independent changes and new agent-runtime work. Expect sharp edges, local configuration requirements, and rapid
iteration.

## Building

Use Android Studio or Gradle from the repository root:

```bash
./gradlew :app:assembleNotion
```

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

See [LICENSE](LICENSE). This project preserves upstream licensing obligations for RikkaHub-derived code. AmberAgent is
currently maintained as a personal, non-commercial open-source project.
