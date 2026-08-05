# AmberAgent Documentation Instructions

本文件适用于 `docs/` 下的规格、ADR、计划、审计与状态记录。先遵守仓库根 `AGENTS.md`。

## Authority And Entry Points

发生冲突时按以下顺序核对：真实代码与测试、适用范围内的 `AGENTS.md`、`PROJECT_STATE.md`、已接受的规格/ADR、仍开放的计划、历史审计。Git 历史和对话可辅助定位，但不是当前运行时事实。

- `README.md` 只做文档地图，不复制进度。
- `PROJECT_STATE.md` 只保留当前分支、工作区、最近验证、当前风险和下一切口；覆盖旧事实，不追加会话日记。
- 规格描述长期产品契约；ADR 记录已接受且仍有效的所有权或架构决定。
- 新增或实质更新计划时，必须在开头标明 `Proposed`、`Active`、`Paused`、`Completed` 或 `Superseded`。完成计划不再列为当前入口。
- 时间戳审计和报告是历史证据，不得用其中的路径、行号、分支、测试数量或“当前”字样覆盖实时检查。

## Lifecycle

- 普通工作完成后更新 `PROJECT_STATE.md`，不要新建 session snapshot、交接 prompt 或滚动 handoff。
- 只有用户明确要求完整交接时才创建 handoff；必须写明日期、基线、适用范围和它取代的旧文件。
- 当任务明确包含文档清理时，已被代码、ADR、规格或当前状态完全取代的 tracked 文档可直接从工作树删除；Git 历史就是归档，不另建 `archive/` 复制一份。
- 删除或改名之前用 `rg` 查引用，并修正仍保留文档里的有效入口。
- 不在长期文档里固化临时 DerivedData、模拟器容器、失效绝对路径或一次性命令输出；设备证据只在当前仍有验收价值时保留在 `PROJECT_STATE.md`。

## Writing Rules

- 先写结论、状态和适用边界，再写细节。
- 区分代码证据、自动化测试、模拟器、真机和真实 provider 证据。
- 不把“build-for-testing 通过”写成“测试通过”，不把安装成功写成视觉或后台行为已验收。
- 链接使用仓库相对路径；新增入口后同步更新 `docs/README.md`。
