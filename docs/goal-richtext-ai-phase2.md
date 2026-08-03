# 大阶段 G2:richtext streaming 逻辑 + ai/ 剩余子功能 + ChatInput 增强

> G1 已交付 ui/ 原语库 26 组件。G2 聚焦渲染管线流式逻辑 + 输入交互增强。

## 现状(G1 后)
- 组件 51 个 / 3698 行
- ui/ 模块覆盖大幅提升(原语库从 0→26)
- richtext 仍只有 markdown_html+code_highlight(无 streaming reveal)
- ai/ ChatInput 仍缺 skill 命令/FullScreen/动画/usage

## S1:StreamingCharReveal + StreamingDisplay 纯逻辑(chat HAR)
| 文件 | Android 行 | 内容 |
|---|---|---|
| StreamingCharReveal.kt | 95 | 逐字符出现时钟(HashMap+nanos,纯逻辑) |
| StreamingDisplay.kt(数学部分) | ~200 | pacing/buffer/safe-slice(提取纯函数,丢弃 Compose 驱动) |

验收:[ ] 纯逻辑+测试 [ ] tsc clean [ ] assembleHap SUCCESS

## S2:HighlightCodeBlock UI 壳(entry)
| 文件 | Android 行 | 内容 |
|---|---|---|
| HighlightCodeBlock.kt(UI 部分) | 517 | 语言标签/复制/折叠/行号(tokenizer 已有 code_highlight.ts) |

验收:[ ] 组件创建+消费者接入 [ ] 复制按钮工作 [ ] 长代码折叠

## S3:ChatInputUsage reasoning 逻辑 + ContextUsageIndicator(chat HAR + entry)
| 文件 | Android 行 | 内容 |
|---|---|---|
| ChatInputUsage.kt(逻辑部分) | ~300 | reasoningOptions/reasoningFamily + toComposerUsageStatus 映射 |
| ContextUsageIndicator | ~100 | 环形进度+文案(ChatPage 已有基础版,增强) |

验收:[ ] reasoning 逻辑移植+测试 [ ] ContextUsageIndicator 增强

## S4:ChatInput 子功能增强(entry)
| 功能 | 内容 |
|---|---|
| /skill-* 斜杠命令 | SkillManager.listSkills → 动态命令 |
| 空匹配行 | SlashCommandPanel 空态文案 |
| FullScreen 编辑器 | ChatInput 全屏 TextArea |
| 面板入场动画 | TransitionEffect |

验收:[ ] /skill-* 出现 [ ] 空匹配行 [ ] FullScreen [ ] 动画

## 不做
- Mermaid/LaTeX(卡 WebView)
- GenerativeWidgetCard(卡 WebView+Parser)
- ChatInputSandbox(卡 WebView)
- camera/file picker(卡平台)
- LazyForEach(卡设备验证)
