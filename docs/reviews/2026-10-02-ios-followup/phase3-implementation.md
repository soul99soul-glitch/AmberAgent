# Phase 3：正文生成式产物架

## 实现

- `conversation_artifacts.ts` 从完整当前选中消息分支派生图片、文档、mini app 和 assistant 正文中的已闭合生成式组件，原始历史不改、不建立重复持久化注册表。
- widget 索引使用与正文相同的 `groupMessageParts` 相邻 text 合并口径，再交给已有 `parseGenerativeWidgets`。ID 使用来源 message ID、起始 part index 和 fence offset；source 保留 conversation/node/message 三个 ID。
- 图片按 URL 去重；文档保留各版本并维护原同名差分入口；同 mini app ID 保留最新标题、继续走已有运行页。
- 新增 `DisplaySetting.enableGenerativeWidgets`，默认 true 保留既有体验；显示设置中可关闭，KV 保存后正文与产物架共同消费该字段。关闭后正文仍以已有 MarkdownText 显示完整原文。
- 原产物架陈列移入独立 `ChatArtifactShelf.ets`，ChatPage 只保留数据、sheet 开关和来源/文档/差分/mini app 回调。预览复用 `GenerativeWidgetCard` 的本地禁 JS、文件、网络沙箱，在原 sheet 内切换列表与预览。
- 来源定位读取最新已提交会话（活动 run 使用其最新 canonical snapshot），验证当前分支的 message ID，扩展完整 rows 后走已有 node 定位。来源已删除/不在当前分支时明确报错。
- 来源按钮点击先关闭产物架，确保来源读取/验证失败时聊天页原有错误提示可见，不会被 sheet 遮住；未增加错误弹窗或重试。
- HTML 导出复用 picker 文本保存接口，输出与预览共用的 `widgetDocument` 净化/CSP 文档；导出单独补不透明主题底色，文件名去路径和控制字符并限制长度。取消 picker 不另写文件。

## 回归与独立 review 修复

初始产物索引/导出/设置测试对空实现产生 4 个失败；补完实现后 GREEN。测试 fixture 按实际 `makeConversation(id, nodes)` 和完整 mini app schema 修正。

独立 reviewer 又复现两处实际问题，均先加入失败回归再局部修复：

1. fence 跨相邻 text parts 时正文可显示、产物架没有条目。回归 RED：body widget 1 / shelf widget 0；复用正文分组后 GREEN：二者均为 1。
2. 暗色导出的独立 HTML 文字近白、页面背景透明；外部默认白色画布不可读。回归 RED 缺少不透明背景；导出专用主题底色后 GREEN，不改聊天预览。

随后实际设备验收复现长幻灯片预览的操作可达性问题：`WidgetPreview` 把来源/导出放在外层 Scroll 内容里，`GenerativeWidgetCard` 的内层 Scroll/Web 吃掉滚动手势，AX 及标题区域 fling 均无法到达底部操作。原图 `/tmp/amber-followup-ui/phase3-preview-slides-light.jpeg` 和 `phase3-slides-bottom-native-title.jpeg` 已检查。最小调整为外层 Column 包含占剩余空间的内容 Scroll，来源/导出与错误反馈固定在 Column 下方；保留原 44vp 操作区、padding、净化和 Web 沙箱，没有新增滚动协调器或 fallback。修复后的设备复验由 root 重新构建安装后完成。

设备图表截图 `/tmp/amber-followup-ui/phase3-preview-chart-light.jpeg` 还确认两组稀疏图的 x 标签/图例只有约 5/6 CSS px，难以阅读。共享 `generative_widget_specs.ts` 只调整非 pie 图现有文本字号：x 根据现有 570/count 槽宽、原已截断 10 字符标签的最大长度，在 11..24 SVG units 间取值；槽宽小于 48（12..24 标签）保持原 11。图例按现有 145 宽槽和名称长度限在原 13..24 之间。原坐标、标签、截断、SVG escaping 与结构不改，不增加 responsive/zoom/JS 框架；正文和产物架共同使用此 renderer，均受益。新增稀疏/密集/长标签与 escape 3 个测试，初始 2 RED、1 既有 dense GREEN，修复后全部 GREEN。

字号修复后设备暗色图 `/tmp/amber-followup-ui/phase3-final-chart-dark.jpeg` 仍确认原固定灰色 x 标签和深蓝图例对比不足。仅生成的非 pie 图文本加保留 class 标记，`widgetDocument(dark=true)` 对这些标记添加浅灰 axis、原四系列同色系的浅色 legend；浅色文档、bar/line 图形及没有保留标记的普通 HTML/SVG 不受覆盖。不使用全局 svg text 选择器；主题隔离回归 RED→GREEN，确认四色映射、普通 SVG 原颜色、light 文档无覆盖。

最终 focused：`conversation_artifacts`、`generative_widget`、`generative_widget_chart_labels`、`display_setting`、`message_grouping` 共 **38/38 通过**。chat `tsc --noEmit` 通过。

定点 ArkTS lint 初轮 8 个文件 **0 errors / 82 warnings**；主要为 pure TS 路径别名的 no-unsafe 推断警告，未采用 any 规避。索引 review 修复后再次单文件检查 **0 errors / 81 warnings**。

来源回调关闭 sheet 的两行 UI 修复后，ChatPage 单文件 lint **0 errors / 3 warnings**，日志 `/tmp/phase3-source-callback-lint.log`；既有聊天页性能警告未扩大为无关重构。

长预览底部操作固定后，ChatArtifactShelf 单文件 lint **0 errors / 0 warnings**，日志 `/tmp/phase3-fixed-preview-actions-lint.log`。

共享图表字号修复后，generative_widget_specs 单文件 lint **0 errors / 48 warnings**（纯 TS 类型导入推断），日志 `/tmp/phase3-chart-labels-lint.log`。

最后暗色图表标签 scopedCSS 后，generative_widget_specs + generative_widget 两文件 lint **0 errors / 64 warnings**（纯 TS 类型导入推断），日志 `/tmp/phase3-dark-chart-lint.log`。

日志：`/tmp/phase3-focused.log`、`/tmp/phase3-cross-part-red.log`、`/tmp/phase3-export-red.log`、`/tmp/phase3-lint.log`、`/tmp/phase3-lint-final-index.log`。

## 验证边界

Worker 已验证领域索引、持久化设置、同正文解析口径、source 分支匹配和安全导出内容。root 串行执行产品构建/签名/安装；实际设备浅暗截图、预览、来源按钮和 picker 保存验收记录在对应整合/UI报告中。本报告不把单元测试视为设备验收，也未调用真实 Provider。
