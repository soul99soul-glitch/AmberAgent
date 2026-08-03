# 大阶段 G5: Deep Functional Push (~37% → ~45%)

> G4 交付 61 页 + 71 组件 + P0 修复。G5 聚焦核心功能缺口:DeepRead 文章详情 + 会话列表增强 + 大设置页。

## 过滤掉的(过度工程,明确不做)
| 类别 | 行数 | 原因 |
|---|---|---|
| Markdown AST 系列 | 4,032 | 已有 MarkdownText |
| WebView 依赖 | 1,998 | Mermaid/LaTeX/Sandbox |
| 平台专属 | 1,290 | permission/icons/相机 |
| 性能优化 | 917 | VirtualItems/StreamingDisplay |
| 已内联 | 1,834 | Renderers/PartsBlock/Cot/Composers |
| 小众页面 | 2,890 | Live/ImgGen/Backup/Stats |
| **合计跳过** | **~13,000** | |

## S1: DeepReadScreen(~1,200 行)
S2: ChatList 分组增强(~800 行)
S3: ModelList + ProviderPage(~1,000 行)
S4a: Memory/MCP/Extensions 设置页(~1,000 行)
S4b: Council/SubAgent 设置页(~600 行)
S5: PromptPage + AssistantPage(~900 行)
S6: ToolResultPreview + TTSProvider + DeepReadWorkbench(~600 行)

## 预期: 37% → 45%
