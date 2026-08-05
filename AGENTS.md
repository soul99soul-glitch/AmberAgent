# AmberAgent Repository Instructions

本文件定义在本仓库内长期生效的开发习惯。它只保存稳定规则；当前进度、有效工作区状态和下一切口记录在 `docs/PROJECT_STATE.md`。

## Project Scope

- 当前产品主线包含 KMP 共享层和原生 iOS 应用。
- iOS 应用位于 `iosApp/`；共享 provider、存储和业务能力位于 `ai-*`、`core/`、`feature/` 等模块。
- `app/` 是 Android 应用；除非任务明确涉及 Android 或跨端一致性，不要把 Android 入口当成 iOS 运行时事实。
- 深入 `iosApp/`、`docs/` 或 `iosApp/iosApp/NovelCreation/` 前，继续读取该目录内更窄的 `AGENTS.md`。
- `iosApp/vendor/SwiftStreamingMarkdown/` 是仓库内 vendor 依赖，另有更深层 `AGENTS.md`；修改时同时遵守其局部规则。

## Automatic Start Protocol

进入本仓库处理实质任务时，自动执行以下检查，不需要用户额外提醒：

1. 读取 `docs/PROJECT_STATE.md`；需要更深背景时按 `docs/README.md` 的主题地图选择文档，不要遍历全部 Markdown 或历史 handoff。
2. 运行 `git status --short --branch`，确认 repo、branch、staged/unstaged/untracked 状态。工作区很脏时先划定本轮文件范围。
3. 不把 handoff、记忆或计划中的 commit、行号、测试状态当作当前事实；成本低时用真实代码、git 状态和测试重新核对。
4. 用一句话定义可验证的成功标准，再定位最小相关代码。不要先写超细执行手册。
5. 发现任务前提与代码事实矛盾时，停下并明确报告；不要为了继续执行而补造假设。

## Git And Existing Work

- 除非用户明确要求，不执行 `git commit`、`push`、`stash`、`reset`、`checkout`、rebase 或清理工作区。
- 现有未提交改动默认是有效工作。不要回滚、覆盖或格式化与当前任务无关的改动。
- 同一文件混有其他工作时，先读 diff 并在现状上做最小修改；无法安全隔离时先说明冲突。
- 不主动同步 upstream。需要比较、拉取或合并远端时，先确认目标 remote 和 branch。

## Development Method

- 一次只推进一个清楚的小闭环：读码和证据 -> 测试或契约 -> 最小实现 -> 验证。
- 修 bug 先争取复现红测试；无法自动化时，先写清可证伪预言和运行时取证办法。
- 读代码只能产生嫌疑。宣布根因前，应有测试、日志、插桩、对照实验或真实运行路径证明该代码确实生效。
- 修复应落在产生错误事实的层，不用魔法数、几何补偿或额外状态掩盖下层错误。
- 只改与任务直接相关的代码。发现死代码只记录，不擅自删除。
- 新抽象必须消除真实重复或复杂度；优先显式、局部可读、可测试的数据流。
- 单个类、文件或 View 持续膨胀时，按职责和变化原因提取小模块，但不要把机械拆分与大行为改动混在一起。

## Code Style

- 遵循仓库 `.editorconfig` 和现有文件风格，不做无关格式化。
- Kotlin/Gradle 默认 4 空格、最大行长 120；XML/JSON/Markdown/YAML 默认 2 空格。
- 类型使用 PascalCase，测试文件以 `*Test` 结尾；Swift 命名遵循现有 Apple API 风格。
- 新增用户文案时沿用所在平台的本地化机制；未要求的任务不要顺手扩大翻译范围。

## Automatic State-Flow Audit

出现以下症状时，不需要用户点名，修改代码前自动执行状态链路审计：

- 设置已经修改但请求或 UI 没有生效。
- 回调、按钮、工具卡片或导航入口存在但行为无响应。
- 切会话、进入后台、取消、重试或恢复后状态丢失、串线或重复。
- metadata、消息、artifact、provider 配置被旧快照覆盖。
- 流式态与完成态、前台与后台、Swift 与 KMP 路径行为不一致。

审计至少回答：

1. 行为契约是什么，哪个对象是权威所有者。
2. 谁读取、谁写入，是否存在多个并发写入者或旧快照回写。
3. 数据跨越了哪些 View、ViewModel、store、KMP、文件、任务和生命周期边界。
4. complete、error、cancel、background、retry 等终止路径是否全部收口。
5. 当前测试是否覆盖真实生产路径；缺口应补成契约测试或 canary。

审计结果用于定位最小修复，不要求额外生成长篇文档。

## Verification

- 测试范围随风险扩大：先跑最小定点测试，再跑受影响模块的回归门禁。
- 测试绿不等于真机安全。涉及动画、滚动、布局、后台执行、系统权限或设备能力时，明确区分代码证据、模拟器证据和真机证据。
- 不静默放宽断言或阈值。若断言过时，先用证据说明行为契约为何改变。
- 验证命令失败时，如实区分产品失败、既有基线、环境限制和沙箱/设备问题。

常用共享层命令按任务收窄：

```bash
./gradlew test
./gradlew assembleNotion
```

iOS 的具体门禁见 `iosApp/AGENTS.md`。

## Automatic Finish Protocol

完成实质改动后：

1. 检查本轮 diff，只确认自己触及的文件和意外副作用。
2. 运行适用的定点测试和回归门禁，并记录未能完成的真机或外部验证。
3. 当当前事实、已完成阶段、验证状态或下一优先级发生变化时，更新 `docs/PROJECT_STATE.md`；不要为每个 session 新建 handoff。
4. 只有用户明确要求完整交接时才新增 handoff；新文档必须说明它取代或补充哪份旧文档。
5. 最终汇报保持简短：改了什么、行为变化、验证结果、残余风险。

## Documentation Boundaries

- `AGENTS.md`：稳定工作方式和长期门禁。
- `docs/PROJECT_STATE.md`：当前事实、有效工作区状态、最近证据和下一切口。
- `docs/README.md`：权威顺序、主题入口和文档状态地图。
- `*PLAN*.md`：尚未完成的阶段计划和验收标准；完成或被取代后不继续冒充当前入口。
- `*HANDOFF*.md`：特定时点快照，仅在明确交接时创建，不作为永久事实源。
- Codex Memory：检索辅助，不作为代码、git 状态或验证结果的权威来源。
