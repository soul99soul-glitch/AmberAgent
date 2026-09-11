# UI 审计复核（2026-09-12）

复核对象：`docs/ui-audit-2026-09-12.md`。以当前 Android 工作树为准，HEAD `d752f90`；存在大量暂存修改及 23 个未合并文件。未修改产品代码、未处理冲突，未运行整仓构建或设备视觉测试。本文件的“确认”指源码和生产调用链确认，不代表设备验收。

## 判定口径与覆盖

- 逐项复核报告 F1–F18、P0 九项和 S1–S8 的主要论据；另核查分模块清单中若干行为断言。
- “真问题”须有可达调用和具体错误行为；未消费组件的缺陷、纯设计一致性、重构建议与性能风险分别记录。
- 原报告没有展开约 650 条独立问题，也未给出各批次完整条目，不能确认其问题总数、严重性统计或全部 120 个文件的视觉健康度。
- 不将文件长度、参数数量、同构代码、Composable 内定义回调、使用 Material 组件、文档标题比页面标题大，直接认定为功能 bug。它们至多是维护或设计评估输入。

## F1–F18 功能项复核

路径缩写：`UI/` = `app/src/main/java/app/amber/feature/ui/`。严重性按实际用户后果判断，不沿用原报告的 P0 标签。

| 编号 | 判定 | 触发条件、证据与修正 |
|---|---|---|
| F1 | 真问题，P1 | `UI/pages/chat/Export.kt:269` 多行 reasoning 各行加 `> ` 后直接 append，没有换行，导出变成 `> a> b`。`ChatExportSheet:142` → `exportToMarkdown:356` 可达。只影响含多行思考内容的 Markdown 导出。 |
| F2 | 未消费组件的潜在缺陷；当前生产不可达 | `UI/components/ai/McpPicker.kt:115` 的 removeIf 确实捕获外层 server，谓词恒 false；但全仓没有该组件的生产调用。不能计作当前功能故障。 |
| F3 | 未触发的组件契约缺陷；当前报告是假阳性 | `UI/components/ui/Form.kt:30`、`:37` 确实重复应用 modifier；当前 42 个生产调用未传外层 modifier，只有 Preview `:81` 传 padding。不是 46 个页面都存在双倍 padding。 |
| F4 | 条件性真问题，P2 | `UI/components/ui/ShareSheet.kt:131` 不 remember；`UI/pages/setting/SettingProviderDetailPage.kt:61` 创建、`:85` 消费、`:148` show。点击可以使读取状态的子组件重组并显示；父页面因设置或模型数据变化重组时，新状态会让弹窗消失。撤销“永远弹不出”。 |
| F5 | 潜在多权限缺陷；当前生产不可达 | `UI/components/ui/permission/RememberPermissionState.kt:73` 确实猜测单权限结果归属；但当前调用方只有 `SettingDisplayPage.kt:143` 的通知单权限及 `ChatInput.kt:1161`、`ChatInputAttachments.kt:691` 的相机单权限。没有多权限下请求非首项的生产链。 |
| F6 | 过期项 | 当前 `UI/pages/setting/SettingExperimentalOfficeProPage.kt` 已暂存删除，路由也移除。HEAD 旧文件 `:83`、`:316` 的开关仅存本地 remember，历史问题属实；原报告的 extensions 路径不正确。 |
| F7 | 过期项 | 同一已删除页面的 HEAD `:329` 忽略 interval，旧 DocRadar 固定 90 分钟；历史问题属实，但不能当作当前页面修复项。 |
| F8 | 真问题，P2 | `UI/pages/setting/SettingMcpPage.kt:591` 保存按钮始终可点击，`:595` 只在名称非空时 confirm；新建配置空名称时静默无操作。需要最低限度的禁用或校验反馈。 |
| F16 | 假阳性；去设置功能正常接线 | `UI/components/ui/permission/PermissionRationaleDialog.kt:117` → `PermissionManager.kt:29` → `PermissionState.kt:176`。proceedFromRationale 检查永久拒绝并调用 openAppSettings。未使用 onOpenSettings 参数只是冗余接口。 |

## 分模块断言补查

下列路径均相对于仓库根目录。

| 原断言 | 复核结论 | 当前源码依据与边界 |
|---|---|---|
| SkillDetail 文件名未拦 `/`、`..`，可能路径穿越 | 安全缺陷假阳性；子目录为明确支持的行为 | `app/src/main/java/app/amber/feature/ui/pages/extensions/SkillDetailPage.kt:546` 起的对话框示例为 `examples/basic.md`；`SkillDetailVM.kt:127` → `core/files/SkillManager.kt:143` → `core/files/SkillPaths.kt:20`，对 canonical 路径执行目录边界检查，越界返回失败。UI 无须重复禁止合法子目录。 |
| BackupDialog 点击 `exitProcess(0)` | 有意行为，不能仅凭退出进程判错 | `app/src/main/java/app/amber/feature/ui/pages/backup/BackupPage.kt:859` 明确说明整量恢复后退出是为了避免旧内存回写；`backup/components/BackupDialog.kt:12` 提供用户确认。是否应自动重启是另一个 UX 需求。 |
| Board 下拉刷新固定 15 秒，不随请求状态 | 表述失实；有超时兜底，未证实错误 | `app/src/main/java/app/amber/feature/ui/pages/board/BoardPage.kt:360` 附近的 effect 监听更新时间及各来源结果，收到变化可提前结束；15 秒延时仍存在。不能描述成每次必等 15 秒。 |
| TodayBoard 权限 remember 不刷新 | 条件性真问题，限描述文案陈旧 | `app/src/main/java/app/amber/feature/ui/pages/board/SettingTodayBoardPage.kt:380`、`:389` 只在当前组合首次读权限。页面组合保留期间在系统设置修改权限并返回，说明文案可能仍为旧值。该布尔值不控制采集器权限授权，不能升级成权限越权或采集失效。 |
| Log 开关首帧恒亮 | 初始显示竞态属实，降为低等级 UX 问题 | `app/src/main/java/app/amber/feature/ui/pages/log/LogPage.kt:139` 默认 true，随后收集设置更正。只在已保存 false 且首个值尚未到达时显示不符；不能保证用户每次能看见闪动，也不能据此断言实际日志功能被打开。 |
| ModelList 字距乘字符串长度 | 真正的视觉公式错误，P2 | `app/src/main/java/app/amber/feature/ui/components/ai/ModelList.kt:739` 把 `0.15.sp` 乘本地化字符串长度；`:730` 注释目标为 `0.15em`。同字体下字距随语言和文案长度变化，不符合自身约定。 |
| Live 气泡长结果无界撑出屏幕 | 无滚动属实；无界文本推断遗漏上游限制 | `app/src/main/java/app/amber/feature/live/bubble/LiveBubbleContent.kt:156` 起无滚动；但 `LiveAnalyzer.kt:180`、`:288` 经 `feature/live/api/src/main/kotlin/app/amber/feature/live/LiveUiTreeProcessor.kt:119` 将每项裁至 90 字符，关键点最多三项。小屏、大字体、横屏仍有裁切风险，需要实际布局验证。 |
| Live 用当前时间判断 retrying，因此必不更新 | 证据不足，不能直接成立 | `app/src/main/java/app/amber/feature/ui/pages/live/LiveCompanionPage.kt:84` 收集 manager 状态；`feature/live/LiveModeManager.kt:190` 起循环重试并更新状态。暂停/手动模式可能有陈旧提示，但需要具体状态序列，单看 `currentTimeMillis()` 不足以证明常规重试 UI 卡死。 |
| SearXNG 密码字段无掩码 | 真问题，限屏幕明文暴露 | `app/src/main/java/app/amber/feature/ui/pages/setting/SettingSearchServiceEditorSheet.kt:430` 密码 OutlinedTextField 没有视觉掩码，且该分支由 `:172` 实际调用。不能扩写成网络或存储泄漏。 |
| 混合 HTML 消息段距消失 | 普通段落场景成立，P2 | `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt:1593` 转交整个含 HTML 的消息而不传段距；`MarkdownNew.kt:175` 默认 0，`:345` 普通段落无额外段距；AST 路径 `Markdown.kt:2818` 在后续段落存在时增加底部段距。具体富媒体/自带 CSS 段落需分别判断。 |
| HTML 支持 Color.White/Black/Red 是“注入能力” | 不能当作安全 bug | `app/src/main/java/app/amber/feature/ui/components/richtext/MarkdownNew.kt:1535` 是颜色解析器，渲染作者提供的样式是现有 HTML 能力。可评估对比度或主题策略，但仅凭支持颜色不足以构成漏洞。 |
