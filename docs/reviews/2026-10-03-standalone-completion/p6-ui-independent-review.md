# P6 创建、阅读、来源与导出独立审查

结论：本次审查范围的源码与实际方法调用链通过。P6-SOURCE01、P6-READER01 已复现、由各自 owner 精准修复并独立复验关闭；缓存热点的三个行为分支也已独立复验。最终构建、设备布局、系统浏览器、文档选择器、分享面板和 ArkWeb PDF 验收由 root 执行，本报告不把 Node 边界测试当成设备证明。

审查范围为 `DeepReadComposePage.ets`、`DeepReadArticlePage.ets`、`DeepReadSourceImporter.ets`、`DeepReadSourcesPage.ets`、`DeepReadExportPanel.ets`、`DeepReadExportFiles.ets`，以及相关领域导出和捕获版式 helper。审查期间仅由各模块 owner 修改生产代码；本 reviewer 负责本报告。阅读库由另一 reviewer 独立审查，未在本报告中自审。DR04 Google Host 仅核对 Article 挂载调用点，没有修改其内部实现。

## 实际调用链与 iOS 对照

| 能力 | iOS 当前代码 | Harmony 当前代码及行为 | 证据 |
|---|---|---|---|
| 创建时选择版式 | `DeepReadComposerView.swift` 选择 `settings.templateId`；`DeepReadRuntime.create` 把 ID 写入任务 | Compose 的 `loadTemplates` 读取内置/自定义选项；`createArticle` 捕获所选 ID，通过 `captureDeepReadTemplate` 保存实际版式快照，再 `repository.upsert`，最后导航 Article | 实际 Compose 方法、模板 helper 和存储边界测试 |
| 阅读与 PDF 版式一致 | `DeepReadDetailView.render` 和 PDF 导出均调用 `DeepReadArticleRenderer.html` | Article `editorialHtml` 和 Export `openPdfPreview` 均调用 `renderCapturedDeepReadTemplateHtml`，使用持久快照和实际字号/字体/颜色；自定义模板删除或全局设置变化不替换本篇快照 | 实际 Article/PDF 方法及完整 renderer 测试 |
| 版式归属边界 | 当前 iOS renderer 按任务 ID 查询现有模板仓库，并在模板变化时重新渲染 | Harmony 本次额外明确冻结实际模板 HTML；旧文章缺快照时显示具体默认排版说明 | 源码对照；没有把 Harmony 的冻结语义声称为当前 iOS 的完全相同行为 |
| 无 URL 热点资料 | iOS Runtime 保留真实输入来源；空来源时主题只是搜索种子 | Article `saveDiscoveryInputs` 保留 `inputSourcesJson` 的实际文本和来源/排名；没有假 URL；新任务保存版式归属后才交给 scheduler | 实际保存方法与新热点回归 |
| 导入大小与正文 | iOS `DeepReadFileImporter`：20 MiB；文本/PDF/DOCX；40,000 字符截断说明 | DeepRead importer 自己读取选中文件，20 MiB 上限；使用既有 PDF/DOCX 解码及来源规范化；聊天附件仍为独立 10 MiB 上限 | 12 MiB 允许、20 MiB+1 拒绝、文件关闭及聊天常量回归 |
| 来源追溯 | iOS Sources 展示来源内容、状态和 HTTP 链接 | Sources 从真实 repository 订阅来源，正文可展开/复制，合法绝对 HTTP(S) 地址通过系统 `viewData` Want 打开；浏览器错误可见 | 实际来源方法和 URL/生命周期回归 |
| 文本与文件导出 | iOS TXT 使用 AST 提取正文；Markdown/PDF 使用已生成内容 | Export 面板读取并冻结一份已保存文章；TXT 对正文 Markdown 字段做 AST 提取，字面元数据和 URL 原样保留；TXT/Markdown/PDF 通过实际保存/分享 helper 接线 | 实际面板、字节写入和领域导出测试 |

## 精准发现与关闭证据

**P6-SOURCE01 — 初始查询覆盖新来源事件，已关闭。** Sources 先订阅再独立 `get`，原代码只有页面生命周期 token。实际方法复现了“观察事件先给出 ready 和新正文，旧 get 后返回 pending 和空正文”后可见来源退回旧状态。RDB 的 `get` 是独立查询，写入串行化不提供此读取优先关系。owner 仅增加本次加载局部 `sourceVersion`；非空观察事件推进版本，初始查询及错误只在版本相同期间消费，仍兼容同步观察当前值。独立重跑 Sources **4/4**，包含这一同页面顺序反例，确认修复关闭。

**P6-READER01 — 设置和首用确认的迟到结果写入旧页面，已关闭。** 原 `loadDisplaySettings` 在两个 await 后直接赋字体 State，调用者返回后的 token 检查无法阻止已发生的赋值。独立方法复现了离页后迟到 scale/font 仍写入。owner 将两个结果先读成局部值，再按传入 token 和 pageAlive 一起应用，并在首用确认 await 后补同一门禁。原反例独立重跑确认两个 State 均未改变；新增设置/首用确认两条实际异步测试通过，迟到确认不初始化 scheduler。

**缓存热点来源更新 — 三个分支已确认闭环。** 显式 force、已有缓存、带新输入且没有活动任务时，先保存最新 provider/排名资料，再运行生成；保留已有正文、版式快照和 createdAt，原缓存对象没有被原地改写。普通缓存重开不修改来源且不重跑。已有活动任务时，拒绝这次新来源和重跑，并显示先取消的操作说明。这三个实际 `loadArticle → saveDiscoveryInputs → runNow` 边界测试已独立通过。

最终 caller 契约还明确由 `runNow` 把 `this.output?.templateSnapshot` 传给 `scheduler.run` 的 `templateSnapshot` 选项，使 force 的当前生成依赖不能覆盖已有捕获版式。新增这一字段后，独立定向再跑上述三个热点用例 **3/3**，确认实际 caller options 包含保存的自定义 HTML。

## UI 与生命周期源审

Compose 使用 V1 与主题 ForEach，Scroll 弹性占据剩余空间，键盘采用已有 API12 `RESIZE` 并在离页恢复。返回、文件移除为 44vp，导入 48vp，生成 52vp；来源及版式说明按可用宽度换行。模板加载与文件选择使用分开的请求 token，模板捕获迟到时没有持久写入或导航；开始后保存完整输入再导航。

Article 的原生阅读 Scroll 为 TopStart，工具栏资料/导出/字体控制为 44vp。原生和 Web 阅读共用恢复操作，部分稿保留已保存正文；只有实际当前 Web owner 的主文档事件影响错误状态。捕获版式损坏或缺失时显示明确归属说明，手动切回原生阅读保留文章和原版式归属。

Sources 长标题和 URL 使用换行；打开浏览器及正文展开控制至少 44vp；页面请求和浏览器错误按本次 owner 隔离。Export 控制为 44/48vp，长文章标题允许三行，菜单 Scroll 为 TopStart；面板关闭或重开后旧选择器、快照和 PDF 回调不能重新写入或打开文件 UI。PDF 仅在 API14+ 且有 Webview capability 时启用，部分稿显示明确状态，保留 TXT/Markdown 路径。

Article 根 Stack 中实际挂载 `DeepReadGoogleSearchHost({ topicId: this.topicId })`。本报告只确认此调用点存在；DR04 内部运行、搜索结果质量及设备表现属于 root 的独立证据。

源审没有发现尚未关闭的本范围逻辑问题。控件尺寸、对齐、长文本约束和 keyboard/Scroll 接线已检查；实际设备是否错位、遮挡或发生 ArkUI 重绘异常仍须 root 截图与 UI 树确认。

## 独立验证

一次最终独立组合运行：**83/83**，包括 Compose/capture **13**、Reader **18**、Workspace **4**、Sources **4**、Export files **16**、Export panel **25**、Library entry/render **3**。另独立运行领域导出 **5/5**；本报告范围累计 **88/88**。

```sh
node --test harmony/deepread/src/test/deepread_compose_capture_ui.test.cjs \
  harmony/deepread/src/test/deepread_reader.test.cjs \
  harmony/deepread/src/test/deepread_workspace_ui.test.cjs \
  harmony/deepread/src/test/deepread_sources_ui.test.cjs \
  harmony/deepread/src/test/deepread_export_files.test.cjs \
  harmony/deepread/src/test/deepread_export_panel.test.cjs \
  harmony/deepread/src/test/deepread_library_entry.test.cjs
harmony/deepread/node_modules/.bin/tsx --test harmony/deepread/src/test/deepread_export.test.ts
```

owner 提供的最终 lint：Compose/Article/Importer **0 errors / 3 warnings**（两处 DTO JSON clone 性能建议和既有文件循环建议）；Sources 单文件 **No defects found**。root/parent 的整体 UI 组合 **124/124** 与双 tsc 通过属于他们的集成记录，不混入本 reviewer 的独立测试数量。
