# 独立深读：当前 iOS 对比与本轮补齐

核对对象是当前本地 `amberagent-ios/iosApp/DeepReadApp` 源码，而非截图或旧版本宣传。iOS HEAD 为 `ede7cbd5b463240315256c4d6a2d492cccf58d70`，工作树包含未提交的拆分改动；本轮未运行 iOS 独立应用。因此下表中的 iOS 行为证据是源码，鸿蒙设备证据另列于 [交付记录](completion.md)。

| 能力 | iOS 位置与行为 | 鸿蒙位置与本轮结果 | 等效与证据边界 |
| --- | --- | --- | --- |
| 独立应用入口、配置与库 | `DeepReadApp/Sources/App/DeepReadApp.swift`、`Settings/DeepReadSettingsStore.swift`：发现、阅读库、设置及独立保存 | `ProductIdentity.ets`、`EntryAbility.ets`、`BoardPage.ets`、`StandaloneSettingsPage.ets`；独立 bundle `app.amber.deepread.reader` | 已构建、签名、安装、冷启动；服务配置和数据库属于新应用沙盒。原宿主已占用 `app.amber.deepread`，因此使用新 ID。 |
| 主题、粘贴文本、多 URL、多文件创作 | `UI/DeepReadComposerView.swift` | `DeepReadComposePage.ets`、`deepread/input_sources.ts`：新增完整输入表单，输入先提交仓库再进入生成页 | 文本输入及键盘已实测；文件选择器的各格式实机验收待做。 |
| 文本文件与 DOCX/PDF 提取 | `Runtime/DeepReadFileImporter.swift`、`DeepReadDocumentZipReader.swift`：UTF-8、UTF-16、GB18030、文本 PDF、DOCX | `DeepReadSourceImporter.ets`：复用既有 PDF/ZIP 能力；DOCX 正文、脚注、尾注；相同文本编码 | 解析、格式错误、空文本、边界行为已测试。鸿蒙单文件 10 MiB，iOS 20 MiB；扫描 PDF 均不等于具备 OCR。 |
| 来源、采集状态及失败查看 | `Runtime/DeepReadSourceCollector.swift`、`UI/DeepReadDetailView.swift` | `source_prefetcher.ts`、`run_manager.ts`、`DeepReadSourcesPage.ets`：保存正文、链接、状态和失败原因；搜索服务真实错误回调 | 数据行为测试通过；粘贴来源、展开正文及重启保存已实测。网页正文读取与云生成组合待真实服务验收。 |
| 来源数量与原始输入保存 | Composer/Collector 保存输入，限制生成使用量 | 保存全部输入与来源条目；每来源生成正文最多 40,000 字符、生成最多使用 10 个可用来源；原始粘贴文本独立保存 | 超限与失败行为有测试；页面明确显示限制。不能将生成截断宣称为用户输入丢弃。 |
| 重试、取消与旧文章保护 | `Runtime/DeepReadRuntime.swift` 持有任务与已保存文章 | `run_manager.ts`：页面离开不取消所有权；重试使用适合的来源；旧文章在新生成成功前保留 | 全领域回归包含旧文章、部分正文、取消及迟到写入。未验收系统长期后台生存及真实云模型。 |
| 阅读库搜索和状态筛选 | `UI/DeepReadLibraryView.swift` | `deepread/library.ts`、`DeepReadHistoryPage.ets`：完整历史、标题及正文搜索、全部/生成中/完成/失败/未完成 | 领域测试及界面搜索、完成筛选已验证。实际任务状态由运行时判定，旧 writing phase 不冒充仍在生成。 |
| 字号范围 | `Settings/DeepReadSettingsView.swift`、ArticleRenderer | 设置、文章、模板工作台和渲染器一致支持 70%–180% | 边界及渲染行为测试通过；没有仅放开 Slider 而保留正文旧上限。 |
| 查询结果数量 | Settings/Provider/Collector 使用配置 | `SearchProvider.ets`、`SearchProviderRegistry.ets`、`search_provider.ts`：按当前搜索配置 resultSize 获取查询结果 | 真实回调、参数传递及取消测试通过；未进行付费搜索服务调用。 |
| 文本/Markdown 与 PDF 导出 | `UI/DeepReadTextExporter.swift`、ArticleWebView：部分草稿可文字导出，PDF 使用当前排版 | `article_export.ts`、`DeepReadArticlePage.ets`、`DeepReadPdfExporter.ets`：部分稿标记、PDF 使用当前模板/字体/字号/色彩 | 行为测试通过。PDF 仍受 API 14 能力限制，API 12 不承诺可用；本轮未验收系统保存面板和 PDF 成品。 |
| 浅色、深色与输入体验 | SwiftUI 独立设计及键盘布局 | 四个新滚动页面明确顶部对齐，新输入框显式主题占位色；Compose 使用 RESIZE | 现有模拟器截图发现问题后修正，并重新构建。截图不能证明全部系统版本的布局与性能。 |

以上是本轮直接核对并补齐的主要差异，不是整个 iOS 独立深读所有页面、云端结果与系统服务的全面等效认证。交付包保留共享页面和原生依赖用于编译、路由兼容；导航入口已独立，包体裁剪尚未作为本轮完成条件。
