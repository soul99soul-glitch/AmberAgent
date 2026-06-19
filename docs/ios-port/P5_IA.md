# P5-T1 信息架构与导航骨架

> 日期：2026-06-13
> 范围：iOS SwiftUI Surface
> 状态：首版完成

## 目标

P5-T1 只定义 iOS 端的产品信息架构和可扩展导航骨架，不做页面视觉重绘。

本阶段产物用于约束后续 P5 页面任务：

- P5-T2 会话列表 + 历史
- P5-T3 聊天页完整版
- P5-T4 助手管理
- P5-T5 Provider/模型设置
- P5-T6 平台能力 actual

## 设计边界

本任务和 UI 有关，但属于结构层，不属于视觉层。

已做：

- 顶层 Tab 信息架构
- 每个 Tab 独立 `NavigationStack`
- 统一 `Route` 枚举
- 统一 `SheetDestination` 枚举
- 页面级占位 View，用于后续任务替换

未做：

- 聊天页视觉重塑
- Liquid Glass 控件细节
- 会话历史真实数据接线
- 助手、工作区、权限、模型设置的完整页面
- 深链 URL 解析实现

## 顶层 Tab

首版采用四个顶层区：

| Tab | Swift case | 目的 | 后续任务 |
|---|---|---|---|
| Chat | `AppTab.chat` | 当前对话工作区，保留 P4 最小可用聊天链路 | P5-T3 |
| Workspace | `AppTab.workspace` | 承载任务、Artifacts、文件、Web 预览等 agent 工作产物 | P5-T6 及后续工作区任务 |
| Assistants | `AppTab.assistants` | 管理 Assistant 配置、提示词、默认模型、记忆偏好 | P5-T4 |
| Settings | `AppTab.settings` | Provider、模型、工具权限、同步、平台能力设置 | P5-T5 / P5-T6 |

保留 `Chat` 为第一入口，因为当前 iOS vertical slice 已经围绕聊天链路打通。

## 导航模型

根视图为 `AppShell`：

- `TabView(selection:)` 管理顶层区域。
- 每个 Tab 都由一个独立 `NavigationStack(path:)` 承载。
- `TabRouter` 持有每个 Tab 的 `RouterPath`。
- `RouterPath` 持有当前 Tab 的 `path` 与 `presentedSheet`。

这样做的原因：

- 切换 Tab 时保留各自导航历史。
- 后续页面可以通过注入的 `RouterPath` 做程序化导航。
- sheet 和 push 使用枚举而不是多个布尔状态，避免状态组合膨胀。

## Route 首版

`Route` 当前只保存轻量标识，不保存 View 或重对象。

| Route | 用途 |
|---|---|
| `conversation(id:)` | 会话详情、历史会话恢复 |
| `assistant(id:)` | Assistant 配置详情 |
| `workspaceItem(id:)` | 工作区产物、文件、任务或预览详情 |
| `providerSettings` | Provider/模型设置入口 |
| `toolPermissions` | 工具权限设置入口 |

后续新增页面时优先扩展 `Route`，再补 `withAppDestinations()` 中的映射。

## Sheet 首版

| Sheet | 用途 |
|---|---|
| `modelPicker` | 聊天页模型选择器 |
| `toolPermissions` | 工具权限临时面板 |

模型选择和工具权限都适合从聊天页或设置页临时打开，因此首版放在 centralized sheet routing 中。

## 文件落点

| 文件 | 职责 |
|---|---|
| `iosApp/iosApp/AppShell.swift` | Tab、Router、Route、Sheet、全局 destination 映射 |
| `iosApp/iosApp/PlaceholderViews.swift` | P5 页面占位壳 |
| `iosApp/iosApp/AmberAgentApp.swift` | App 入口，持有 `SettingsStore` 并挂载 `AppShell` |
| `iosApp/iosApp/ChatView.swift` | 保留聊天 vertical slice，由外层 `NavigationStack` 承载 |
| `iosApp/iosApp/SettingsView.swift` | 保留设置表单，由外层 `NavigationStack` 承载 |

## 后续页面任务规则

后续 P5 任务应遵守：

- 不再在页面内部重复包 `NavigationStack`，除非是 sheet 内部需要独立 push。
- 页面 push 通过 `Route`，sheet 通过 `SheetDestination`。
- 顶层 Tab 不轻易新增；新增前先确认它代表稳定的一等工作区。
- `Workspace` 和 `Assistants` 的占位页应在对应任务中替换，不在导航骨架任务里提前实现业务。
- Liquid Glass 只用于控制层和 transient surface；阅读内容保持实体背景。
