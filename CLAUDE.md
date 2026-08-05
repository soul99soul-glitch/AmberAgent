# Claude Code Entry Point

本文件只为会自动读取 `CLAUDE.md` 的工具提供入口，不另存一套工程规则。

开始工作时按顺序读取：

1. [`AGENTS.md`](AGENTS.md)
2. [`docs/PROJECT_STATE.md`](docs/PROJECT_STATE.md)
3. [`docs/README.md`](docs/README.md) 中与任务相关的最少文档
4. 目标目录内更窄的 `AGENTS.md`

当前仓库同时包含 KMP、原生 iOS 和 Android 代码。任务未明确涉及 Android 时，不要把 `app/` 的 Compose 实现当成 iOS 运行时事实。

旧 handoff、session snapshot、日期化审计和 Git 历史只用于追溯；分支、路径、测试状态、设备状态和下一任务必须以实时检查及 `PROJECT_STATE.md` 为准。
