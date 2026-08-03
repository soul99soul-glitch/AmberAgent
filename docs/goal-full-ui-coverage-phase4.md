# 大阶段 G4: Full UI Coverage Push (~20% → ~38%)

> G1-G3 交付 53 组件 + 32 页面 + P0 修复(MarkdownText 原生渲染)。
> G4 目标:批量推进前端覆盖,从 ~20% 推到 ~38%。

## 过滤掉的(过度工程,明确不做)

| 类别 | 行数 | 不做原因 |
|---|---|---|
| 完整 Markdown AST (Markdown.kt + tree/* + nativebridge/*) | 6,516 | 已有原生 MarkdownText |
| WebView 依赖 (Mermaid/LaTeX/SimpleHtmlBlock/WidgetCard/Sandbox) | 4,381 | 需 Web 组件,defer |
| 平台专属 (permission/icons/TTS/FloatingWindow/BitmapComposer) | 1,997 | 鸿蒙无等价 API |
| 小众页面 (live/miniapp/backup/imggen/debug/stats/log/developer/share) | ~5,400 | 低用户可见度 |
| 性能优化 (StreamingDisplay/CharReveal/JankProbe/VirtualItems) | 1,302 | defer 到设备验证 |
| 搜索图片渲染 | 966 | 需图片画廊 |
| Apple 专属 (iCloud) | 271 | Apple only |

**合计跳过: ~21,000 行(23%)**

## S1: UI 原语补全(~1,500 新行)
15 个缺失原语: AmberPrimitives/Input/Select/Switch/Tag/TagList/JsonTree/ErrorCard/Form/ViewText/ConfirmDialog/DotLoading/ToggleSurface/ImagePreviewDialog/TextArea(增强)/WorkspaceSearchField

## S2: 消息组件升级(~1,800 新行)
6 个 stub 升级: Tools(985→完整)/Reasoning(451→三态)/Council(705→轮播)/AskUser(补弹窗)/ToolPreview(JSON高亮)/SubAgent(phase轮播)

## S3: ChatInput 扩展(~1,400 新行)
Composers(1030)/Usage(663)/CompressContext(升级)
跳过: Sandbox(WebView)/Attachments(相机)

## S4: ChatDrawer + 会话列表(~2,200 新行)
ChatDrawer/ConversationList/ChatListNormalSection/ChatListIndicators/ContextRing/Export

## S5: 设置页批量(~5,000 新行)
12 页: Model/ProviderConfig/ProviderModelTab/ProviderModelForm/TTS/SystemAccess/Experimental/SearchServiceEditor/SearchListCards/SlidesFont/WebMount/OfficePro
跳过: iCloud(271, Apple)

## S6: 助手详情页(~1,800 新行)
9 页: Detail/Basic/Prompt/Request/Extensions/Memory/Importer/PropertyEditor/BackgroundPicker

## S7: Extensions + Board + Misc(~2,500 新行)
8 页: Skills/SkillDetail/QuickMessages/Board/DeepReadHistory/HotListSource/Search/Favorite

## 预期结果
| 指标 | 当前 | G4 后 |
|---|---|---|
| 页面数 | 32 | ~58 |
| 组件数 | 53 | ~74 |
| UI 总行数 | ~18,000 | ~34,200 |
| 覆盖率 | ~20% | ~38% |

## 工作方式
- 每个 slice: 实现 → 门禁(test+tsc+assembleHap) → 设备验证 → commit
- S1 先做(其他 slice 依赖原语)
- S5 最大,可拆 5a/5b 两次 commit
