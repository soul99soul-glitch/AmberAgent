# 大阶段 G1:ui/ 通用原语库 + ai/ 高优缺失组件

> **执行 agent**:按小阶段顺序推进。每个小阶段完成后 subagent 四维 review + 门禁 + commit。全部完成后进入 AUTO-NEXT。
>
> **防挑软柿子约束**:每个小阶段对应 Android 基准 ≥500 行(S1=865 / S2=1715 / S3=822 / S4=1580)。
>
> **UX review 诚实边界**:源码层可验证(数值一致性/token 使用/layoutWeight/嵌套合法性/key 唯一性/分支完备性)→ 改。需设备验证(像素对齐/RichText 渲染效果/动画手感)→ 登记不做。

## 现状基线
- ui/ 模块:Android 48 文件 7814 行 → Harmony 23 原语 ~600 行(~6% 覆盖)
- ai/ 模块:Android 15 文件 7518 行 → Harmony 5/15 部分移植(~5% 覆盖)
- 缺失文件中 16 个(ui/) + 9 个(ai/) 不卡设备,source-only 可做

## 不做(明确排除)
- richtext/ 大块(AST renderer / Mermaid / LaTeX / JNI bridge)——卡 Compose/WebView/平台
- permission/ 5 文件——卡 Android Activity Result API
- FloatingWindow / BitmapComposer / ViewText / TTSController——卡平台 API
- ChatInputSandbox(1161 行)——卡 WebView
- ChatInputAttachments 相机/crop/FileProvider 路径——卡平台

---

## S1:表单控件族(Android 865 行)

| 文件 | 行 | 功能 | Harmony 现状 |
|---|---|---|---|
| Input.kt | 202 | FlatTextField/NumberInput/OutlinedNumberInput | MISSING |
| TextArea.kt | 254 | 多行 TextArea + FullScreenTextEditor | MISSING(用内置 TextArea) |
| NotionSlider.kt | 147 | NotionSlider + PercentLabel/IntLabel | MISSING |
| TagList.kt | 209 | TagsInput 可编辑 chip 列表 | MISSING |
| WorkspaceSearchField.kt | 153 | 带图标/清除的搜索框 | MISSING(TopModelMenu 内联) |
| ListSelectableItem.kt | 39 | 带前导图标+选中态的列表行 | MISSING |

**验收标准**:
- [ ] 6 个组件创建到 `harmony/entry/src/main/ets/components/ui/`(新建 ui/ 子目录)
- [ ] 每个组件有真实消费者(至少 1 个页面/组件 import 使用,非死代码)
- [ ] 数值对齐 Android(padding/margin/fontSize/颜色用 token)
- [ ] ArkTS 合规(无禁忌)
- [ ] assembleHap SUCCESS + subagent review 通过

---

## S2:数据展示族(Android 1715 行)

| 文件 | 行 | 功能 | Harmony 现状 |
|---|---|---|---|
| CardGroup.kt | 192 | CardGroup/CardGroupScope 列表构建器 | MISSING |
| NotionListRow.kt | 124 | Notion 风格列表行 | MISSING |
| ChainOfThought.kt | 600 | 泛型 <T> 思维链流式渲染器 | MISSING |
| ExtensionSelector.kt | 197 | 模式注入/Lorebook/Skill 选择器 | MISSING |
| QRCode.kt | 46 | 二维码生成器(zxing) | MISSING |
| Favicon.kt | 95 | Favicon 图片+fallback | MISSING |
| Emoji.kt | 319 | EmojiPicker + EmojiItem | MISSING |
| Greeting.kt | 36 | 时段问候语 | MISSING(可合并) |

**验收标准**:
- [ ] 组件创建到 `components/ui/`
- [ ] CardGroup + NotionListRow 有消费者(至少 MemoryPage 或 ChatListPage 用)
- [ ] ChainOfThought 接入 ChatMessageReasoning(或登记为何处用)
- [ ] ExtensionSelector 接入 ChatAssistantExtensionsPage
- [ ] EmojiPicker 有消费者(ChatInput 或独立入口)
- [ ] assembleHap SUCCESS + subagent review 通过

---

## S3:反馈+功能族(Android 822 行)

| 文件 | 行 | 功能 | Harmony 现状 |
|---|---|---|---|
| WorkspaceStyle.kt | 354 | workspaceColors + TopBar/IconButton/TextButton/StatusPill/Divider | MISSING(tokens.ets 只有颜色) |
| Tooltip.kt | 28 | 长按提示气泡 | MISSING |
| Export.kt | 99 | ExportDialog 通用导出 sheet | MISSING |
| BackupReminderCard.kt | 85 | 备份提醒卡 | MISSING |
| StickyHeader.kt | 23 | LazyColumn 固定头 | MISSING |
| RabbitLoading.kt | 35 | 兔子加载动画 | MISSING |
| CompressContextDialog.kt(ai/) | 175 | 上下文压缩确认弹窗 | MISSING |

**验收标准**:
- [ ] WorkspaceStyle 组件族创建(TopBar/IconButton/TextButton/StatusPill)
- [ ] Tooltip 有消费者(至少 1 个组件的长按)
- [ ] CompressContextDialog 接入 ChatInput.onCompactContext 路径
- [ ] assembleHap SUCCESS + subagent review 通过

---

## S4:ai/ 高优缺失(Android 1580 行)

| 文件 | 行 | 功能 | Harmony 现状 |
|---|---|---|---|
| ChatInputUsage.kt | 663 | ContextUsageIndicator + reasoningOptions 逻辑 + ComposerUsageSheet | MISSING(逻辑部分可移植) |
| MentionRoles.kt | 66 | @mention 角色列表逻辑 | MISSING(纯逻辑) |
| ChatInputComposers.kt(MentionPanel 部分) | ~300(提取) | @mention 面板 UI | MISSING |
| ProviderBalanceText.kt | 84 | Provider 余额显示 | MISSING |
| SearchPicker.kt | 340 | 搜索服务选择器 | MISSING |
| McpPicker.kt | 251 | MCP 服务器选择器 | MISSING |
| AssistantPicker.kt | 246 | 助手切换 sheet | MISSING(有独立页) |

**验收标准**:
- [ ] ChatInputUsage:reasoningOptions/reasoningFamily 纯逻辑移植到 chat HAR + 测试;ContextUsageIndicator 组件接入 ChatPage
- [ ] MentionRoles + MentionPanel:逻辑移植 + UI 面板接入 ChatInput
- [ ] ProviderBalanceText:接入 Provider 设置页
- [ ] SearchPicker:接入 ChatSearchSettingsPage 或 ChatInput
- [ ] McpPicker:接入 ChatMcpSettingsPage
- [ ] assembleHap SUCCESS + subagent review 通过

---

## 全大阶段退出条件
- [ ] S1-S4 各自验收标准全勾
- [ ] chat 测试全绿 + tsc clean
- [ ] deepread 测试全绿 + tsc clean
- [ ] assembleHar + assembleHap SUCCESS
- [ ] git diff --check clean
- [ ] ui/ 覆盖率从 ~6% 提升到 ≥30%(~2400 行新增)
- [ ] ai/ 覆盖率从 ~5% 提升到 ≥25%(~1900 行新增)
- [ ] frontend-coverage-matrix.md 创建 + 更新
- [ ] 诚实标注所有"效果待设备"项
