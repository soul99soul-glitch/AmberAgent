<div align="center">
  <img src="docs/icon.png" alt="AmberAgent 应用图标" width="100" />
  <h1>AmberAgent</h1>

  <p>
    一个个人 Android Agent Runtime，用来探索移动端优先的 AI 工作流、深度阅读、SubAgent 与本地工具调用。
  </p>

  <p>
    <a href="README.md">English</a> | 简体中文 | <a href="README_ZH_TW.md">繁體中文</a>
  </p>
</div>

<div align="center">
  <img src="docs/img/amberagent-home.jpg" alt="AmberAgent 首页" width="240" />
  <img src="docs/img/amberagent-chat.jpg" alt="AmberAgent SubAgent 聊天界面" width="240" />
  <img src="docs/img/amberagent-board.jpg" alt="AmberAgent 今日看板" width="240" />
</div>

## AmberAgent 是什么？

AmberAgent 是一个个人开源 Android 项目，目标是探索手机上的 AI Agent Runtime 应该是什么样子。它最初源自
[RikkaHub](https://github.com/rikkahub/rikkahub) 的深度 fork，目前围绕 Agent 工作流独立维护和演进，包括工具调用、
SubAgent、深度阅读、本地优先状态、移动端 UI，以及外部运行时实验。

本项目不是 RikkaHub 官方版本，也不是官方继任项目。项目会保留上游来源说明和许可证义务，并保持个人非商业研究与学习项目的定位。

## 项目亮点

- **移动端 Agent Runtime**：聊天不是简单的消息列表，而是 Agent 执行界面，包含可见工具调用、可取消运行、生成物展示，以及真正保留在设备上的状态。
- **SubAgent 工作流**：固定角色和动态角色可以拆分任务、汇报工具进度，并把协作结果回到同一段对话里。
- **今日看板与深度阅读**：热点收集、来源抓取、结构规划、分节写作、证据记录和报告式阅读都作为一等 Android 流程来做。
- **工具与生成物 UI**：搜索、文件、本地设备能力、浏览器式卡片、PPT 风格预览和 live HTML 生成物都尽量以移动端原生交互承载。
- **外部运行时实验**：Gemini CLI、Antigravity CLI、Codex CLI、Claude Code、Kimi CLI 等本地 CLI 会在可探测、可验证运行时作为可选议会席位参与。
- **个人连续性**：设置、Provider、Prompt、工作区状态、同步和备份流程，都围绕长期个人 Agent 工作区设计。

## 项目状态

AmberAgent 仍然是一个快速演进的实验性代码库。它既包含从 RikkaHub 继承而来的基础能力，也包含大量独立重构和新的 Agent
Runtime 工作。它更适合作为个人研究应用和代码库，而不是已经打磨完成的终端用户发行版。

## 构建

使用 Android Studio，或在仓库根目录执行：

```bash
./gradlew :app:assembleNotion
```

`Notion` 是仓库里保留下来的历史 build type 名称；当前 AmberAgent 的目标包名是 `app.amber.agent`。
部分云端能力需要本地私有配置文件，例如 `app/google-services.json`。这些文件不会提交到仓库。缺少这些私有凭据时，应用仍可用于本地开发构建，
但 Firebase / Google 相关能力可能受限，取决于配置文件是否包含当前构建包名对应的 client。

## 贡献

欢迎小而聚焦的 issue 和 PR，尤其是可复现崩溃、bug 报告、文档和测试。由于项目仍在把 Agent 架构从继承的聊天客户端基础中逐步分离，
大规模顺手重写会比较难审查。

技术栈：

- [Kotlin](https://kotlinlang.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Koin](https://insert-koin.io/)
- [Room](https://developer.android.com/training/data-storage/room)
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- [OkHttp](https://square.github.io/okhttp/)
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization)
- [Coil](https://coil-kt.github.io/coil/)
- [Material You](https://m3.material.io/)

## 来源说明

AmberAgent 是 [RikkaHub](https://github.com/rikkahub/rikkahub) 的深度 fork。代码库中的部分代码、架构、资源和历史设计来源于
RikkaHub，并继续遵守原项目的许可证和署名要求。AmberAgent 特有的 Agent 能力与后续重构由本仓库独立维护。

## 许可证

请查看 [LICENSE](LICENSE)。本项目保留 RikkaHub 派生代码的上游许可证义务。AmberAgent 当前作为个人非商业开源项目维护。
