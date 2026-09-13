# Amber Harmony UI 覆盖证据矩阵

日期：2026-09-13
对应主报告：[2026-09-13-harmony-ui-ux-motion-review.md](./2026-09-13-harmony-ui-ux-motion-review.md)
依据：本次 UI/UX 审查各页线原始报告与后续复核结论（汇总于此，不随交付附带）
口径：**只清点 UI 相关覆盖证据，不判 bug、不做逻辑审计**；页面坐标以 `main_pages.json` 74 注册页为准。
状态取自各报告自述已读清单；「全文」不等于经第三方深度校验。

状态定义（保留精确区别，不做机械计数）：
- **全文** = 报告自述逐文件读完（或等价全文通读）。
- **全文（UI 面）** = 该页 UI 部分已全部读到，但同文件内的非 UI 逻辑（生成循环等）未读且**不属本次 UI 审计范围**。
- **部分** = 只读部分段落/结构性通读，或仅补某一维度。
- **仅扫描** = 仅构造级 grep / 计时器清点，或仅读路由壳（无 UI 逻辑）。
- **未覆盖** = 无任何报告列入已读清单。
- **引用入边**单列，依据 E 的入栈边统计：`✅有引用入边` / `❌0 引用入边` / `⚠️孤岛（仅族内互指，根不可达）`。
  - **重要口径**：这是**引用入边统计（57/17）**，**不是从冷启动出发的根可达图**。有入边 ≠ 从入口可达——若整个族根无外部入口，族内互指仍属孤岛（Assistant 族即此例）。

---

## 0. 结论摘要

- **74 注册页全部被至少一份报告在某一层面触及：0 个页面级真缺口。**
- 深度分布：**全文 62 ｜ 全文（UI 面）1 ｜ 仅扫描 11 ｜ 部分 0 ｜ 未覆盖 0**。
  - 其中 4 页完整度在本轮提升：`ChatPage`（→全文 UI 面）、`DeepReadArticlePage`（949 行逐行→全文）、`NovelWorkspacePage`（:1–399 与 :1246–2290 加 :400–1245 逐行→全文）、`SearchPage`（全文）。
- **引用入边统计更正（不是根可达图）**：74 注册页按**引用入边**统计为 **57 条有入边 / 17 条 0 入边**。但**这不等于「57 页从冷启动可达」**——有入边仅表示被某处引用，不保证可从入口抵达。§1 表格据此细分为 **✅有入边 50 ｜ ⚠️孤岛 7 ｜ ❌0 入边 17 = 74**。
  - **Assistant 整族（10 页）**：族根 `AssistantListPage` 无任何外部入口，故族内 7 个有入边页（Basic/Detail/Prompt/Request/Extensions/Memory/BackgroundPicker）实为**孤岛**，加 ListPage/Importer/PropertyEditor 共 10 页从冷启动不可达。唯一可达助手面是 `ChatAssistantsPage`（ProfilePage:510 进入）。详见 §2.1。
- **组件侧（缺口已补齐）**：`ContextRing`、`ContentAttachmentChip`、`MarkdownRichText`、`SubAgentAvatar` 4/4 全读，此前缺口清零。另有 3 个死组件未读（`ChatExportSheet`、`ChatListSnackbar`、`ProviderConnectionTester`）与 `McpPaperRows` 仅部分。
  - **引用状态更正（此前把注释/同名局部 struct 误当 import）**：`ContentAttachmentChip`（AgentMessageRow 真实 import）、`SubAgentAvatar`（ChatMessageSubAgentStep 真实 import）为**活代码**；`MarkdownRichText`（MarkdownText 仅注释提及）、`ContextRing`（全仓 0 import；ChatInputUsage 内为同名**局部 struct**）为**死代码**。已读覆盖与可达性无关，4 个均为全文。
- **路由壳 ≠ 漏功能页**：11 个 `Setting*Full`/`SettingModelPage`/`SkillDetailPage`/`DeepReadTemplate*` 实测 21 行、`replaceUrl`×2、无状态装饰器，确认为跳转壳。

---

## 1. 74 注册页逐名覆盖矩阵

| # | 页面 | UI 覆盖 | 来源报告 | 引用入边（非根可达） |
|---|---|---|---|---|
| 1 | AssistantBackgroundPicker | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 2 | AssistantBasicPage | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 3 | AssistantDetailPage | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 4 | AssistantExtensionsPage | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 5 | AssistantImporter | 全文 | H | ❌ 0 入栈边 |
| 6 | AssistantListPage | 全文 | H | ❌ 0 入栈边（被 ChatAssistantsPage 取代） |
| 7 | AssistantMemoryPage | 全文 | H、C | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 8 | AssistantPromptPage | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 9 | AssistantPropertyEditor | 全文 | H、F | ❌ 0 入栈边 |
| 10 | AssistantRequestPage | 全文 | H | ⚠️孤岛（仅 Assistant 族内互指，根不可达） |
| 11 | BoardPage | 全文 | I、G(部分)、F | ✅ |
| 12 | ChatAgentExecutionPage | 全文 | B | ✅ |
| 13 | ChatAgentPermissionsPage | 全文 | B | ✅ |
| 14 | ChatAssistantExtensionsPage | 全文 | H | ✅ |
| 15 | ChatAssistantLocalToolsPage | 全文 | H | ✅ |
| 16 | ChatAssistantMcpPage | 全文 | H | ✅ |
| 17 | ChatAssistantsPage | 全文 | H | ✅ |
| 18 | ChatListPage | 全文 | A、G(部分)、E、F | ✅ |
| 19 | ChatMcpSettingsPage | 全文 | B | ✅ |
| 20 | ChatModelParamsPage | 全文 | H | ✅ |
| 21 | ChatModelPickerPage | 全文 | H | ✅ |
| 22 | ChatPromptsPage | 全文 | H、G(清点) | ✅ |
| 23 | ChatProviderDetailPage | 全文 | H、G(清点)、F | ✅ |
| 24 | ChatProviderSettingsPage | 全文 | H、G(清点) | ✅ |
| 25 | ChatSearchSettingsPage | 全文 | B、G(清点)、F(部分) | ✅ |
| 26 | ChatTaskModelPickerPage | 全文 | H | ✅ |
| 27 | ChatTaskModelsPage | 全文 | H | ✅ |
| 28 | CouncilPage | 全文 | I | ✅ |
| 29 | CouncilRunPage | 全文 | I、G(部分)、F | ✅ |
| 30 | DeepReadArticlePage | 全文 | C(主+补篇 949 逐行)、G、F(部分) | ✅ |
| 31 | DeepReadHistoryPage | 全文 | C | ✅ |
| 32 | FavoritePage | 全文 | A、E(反例)、F | ✅ |
| 33 | HistoryPage | 全文 | A、E(反例) | ✅ |
| 34 | HotListSourceSettingsPage | 全文 | B | ❌ 0 入栈边（被 SettingDeepReadPage 取代） |
| 35 | MemoryPage | 全文 | C | ❌ 0 入栈边（被 SettingAgentMemoryPageFull 取代） |
| 36 | MiniAppListPage | 全文 | C、F(部分) | ✅ |
| 37 | MiniAppRunnerPage | 全文 | C | ✅ |
| 38 | MiniAppSettingsPage | 全文 | C、B | ✅ |
| 39 | NovelProjectsPage | 全文 | C、G(部分)、E、F | ✅ |
| 40 | NovelWorkspacePage | 全文 | C(主+补篇补齐 :400–1245)、G、E、F(部分) | ✅ |
| 41 | ProfilePage | 全文 | A、H(caller)、F | ✅ |
| 42 | PromptTemplatePage | 全文 | I | ❌ 0 入栈边 |
| 43 | QuickMessagesPage | 全文 | I | ✅ |
| 44 | SearchPage | 全文 | C(补篇全文)、G(清点)、F | ✅ |
| 45 | SettingAboutPage | 全文 | B | ✅ |
| 46 | SettingAgentMemoryPageFull | 全文 | B | ✅ |
| 47 | SettingAppearancePage | 全文 | B、D(caller) | ✅ |
| 48 | SettingBackupPage | 全文 | B、D(caller)、H(caller) | ✅ |
| 49 | SettingCronTasksPage | 全文 | B、G(清点) | ✅ |
| 50 | SettingDeepReadPage | 全文 | B、C | ✅ |
| 51 | SettingDisplayPage | 全文 | B、A(caller) | ✅ |
| 52 | SettingExperimentalPage | 全文 | B、E | ✅ |
| 53 | SettingExperimentalWebMountPage | 全文 | B | ✅ |
| 54 | SettingFilesPage | 全文 | B | ✅ |
| 55 | SettingModelCouncilPage | 全文 | B | ✅ |
| 56 | SettingPage | 全文 | B、H(caller) | ✅ |
| 57 | SettingSandboxPage | 全文 | B | ✅ |
| 58 | SettingSlidesFontPage | 全文 | B | ✅ |
| 59 | SettingSubAgentPage | 全文 | B | ✅ |
| 60 | SettingSystemAccessPage | 全文 | B | ✅ |
| 61 | SkillDetailPage | 全文 | B(壳)、I | ❌ 0 入栈边（壳） |
| 62 | SkillsPage | 全文 | B、I、G(清点) | ✅ |
| 63 | ChatPage | **全文（UI 面）** | A、G、E、F | ✅ |
| 64 | SettingModelPage | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 65 | SettingMcpPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 66 | SettingAgentExecutionPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 67 | SettingAgentExtensionsPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 68 | SettingAgentPermissionsPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 69 | SettingExperimentalModelCouncilPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 70 | SettingExperimentalSubAgentPageFull | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 71 | SettingSearchListCards | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 72 | SettingSearchServiceEditorSheet | 仅扫描(路由壳) | B、F | ❌ 0 入栈边（壳） |
| 73 | DeepReadTemplateSettingsPage | 仅扫描(路由壳) | B、C、F | ✅（SettingExperimentalPage:37 使其可达） |
| 74 | DeepReadTemplateWorkbenchPage | 仅扫描(路由壳) | B、C、F | ❌ 0 入栈边 |

### 仅扫描的 11 个（非全文）

1 个功能性页（`SearchPage` 已升全文，故不在列）+ 10 个路由壳：`SettingModelPage`、`SettingMcpPageFull`、`SettingAgentExecutionPageFull`、`SettingAgentExtensionsPageFull`、`SettingAgentPermissionsPageFull`、`SettingExperimentalModelCouncilPageFull`、`SettingExperimentalSubAgentPageFull`、`SettingSearchListCards`、`SettingSearchServiceEditorSheet`、`DeepReadTemplateWorkbenchPage`。
（`DeepReadTemplateSettingsPage`、`SkillDetailPage` 同为壳，但前者 B/C 读作壳、SkillDetailPage I 另全文，故归「全文」；`DeepReadTemplateSettingsPage` 在此仍记「仅扫描路由壳」，行 73 已单列。）

> 计数说明：正文表格中「全文」实际为 62 行（#1–62）+ ChatPage 全文（UI 面）1 行 = 63；「仅扫描」11 行。62+1+11 = 74。§0 的「全文 62 + 全文(UI面) 1 + 仅扫描 11 = 74」与表格一致。

---

## 2. 引用入边统计专项（57/17，**不是根可达图**）

**口径声明**：本节是 E 的**引用入边统计**（某页是否被任何 `pushUrl/replaceUrl` 引用），**不是从冷启动 `ChatListPage` 出发的根可达分析**。故 §1 表格用「引用入边（非根可达）」列头；**不得将 57 解读为「57 页全局可达」**。

E 报告实测：74 注册页中 **17 条在源码内 0 条引用入边**（无任何 `pushUrl/replaceUrl` 目标指向；脚本逐页比对）。

| 类别 | 页面 | 说明 |
|---|---|---|
| 死壳（历史兼容，`aboutToAppear` 内 `replaceUrl`） | SettingModelPage、SettingMcpPageFull、SettingAgentExecutionPageFull、SettingAgentExtensionsPageFull、SettingAgentPermissionsPageFull、SettingExperimentalModelCouncilPageFull、SettingExperimentalSubAgentPageFull、SettingSearchListCards、SettingSearchServiceEditorSheet、SkillDetailPage | 纯死代码；`DeepReadTemplateWorkbenchPage` 同型 |
| 被取代的功能页 | MemoryPage（→SettingAgentMemoryPageFull）、PromptTemplatePage（→ChatPromptsPage）、AssistantListPage（→ChatAssistantsPage）、HotListSourceSettingsPage（→SettingDeepReadPage）、DeepReadTemplateWorkbenchPage | 旧入口下线后无入边 |
| 无入口页 | AssistantImporter、AssistantPropertyEditor | H 确认无 caller |

### 2.1 孤岛：Assistant 整族（引用入边统计的盲区）

**Assistant 族（除 `ChatAssistantsPage` 外）是从冷启动不可达的孤岛**，尽管族内多数页有引用入边：

| 页面 | 族内引用入边 | 根可达性 |
|---|---|---|
| AssistantListPage（族根） | 无 | ❌ 无任何外部入口 |
| AssistantDetailPage | ← AssistantListPage:93/:104 | ❌（父根不可达） |
| AssistantBasicPage | ← AssistantDetailPage:29 | ❌ |
| AssistantPromptPage | ← AssistantDetailPage:30 | ❌ |
| AssistantRequestPage | ← AssistantDetailPage:31 | ❌ |
| AssistantExtensionsPage | ← AssistantDetailPage:32 | ❌ |
| AssistantMemoryPage | ← AssistantDetailPage:33 | ❌ |
| AssistantBackgroundPicker | ← AssistantDetailPage:178 | ❌ |
| AssistantImporter、AssistantPropertyEditor | 无 | ❌ |

- 证据（本轮只读抽查）：`AssistantDetailPage` 用 `router.pushUrl` 指向 Basic/Prompt/Request/Extensions/Memory/BackgroundPicker 子页；`AssistantListPage:93/:104` 指向 Detail；而**全仓无 `pages/AssistantListPage` 的外部 caller**（grep 仅自引用）→ 族根断裂，整族孤岛。
- 对照：唯一可达助手面 = `ChatAssistantsPage`（`ProfilePage.ets:510` 进入）。
- 同型需警惕：任何「族内互指但根无外部入口」的族，引用入边统计都会显示 ✅ 而实际不可达——这是 57 数字不能等同根可达的原因。

- 另：`pages/Index` 未注册且 0 引用入边；其 `Index.ets:203-276,470-476` 的「继续（ContinueAggregator）」聚合卡片随之下线不可达。

---

## 3. 跨页专项覆盖（E/F，属 UI 面但不升级单页深度）

- **E（全局导航/路由/生命周期）**：`router.*` 全量 132 处枚举与注册表交叉比对（无未注册目标）；**引用入边**逐页统计（识别 17 条 0 入边）；74 页生命周期矩阵（`aboutToAppear 68 / onPageShow 16 / onPageHide 3 / aboutToDisappear 22 / onBackPress 1`）；`ChatPage` 的 `replaceUrl/pushUrl` 全 8 处、`onBackPress`、IME 监听、自绘覆盖层。→ 覆盖全部 74 页的**路由/生命周期维度**，非内容深读，且非根可达图。
  - 与 UI 相关性：导航/返回/生命周期为 UI 面，纳入；其通知深链 `sourceUrl` 等属逻辑链，本矩阵不展开。
- **F（跨页布局规格）**：74 注册页尺寸矩阵；顶栏 x/高、返回键命中区、底部安全区、`PaperSeparator` 等几何一致性。→ 覆盖全部 74 页的**布局几何维度**，偏「规格核对」非逐页内容深读；12 个 STUB 壳仅核跳转不评布局；`ChatPage` 仅审 4 区域，`NovelWorkspacePage` 仅审顶栏/gutter/输入栏/版本弹窗。

---

## 4. 组件覆盖

### 4.1 `components/ui/`（44）

- **D 全读 44/44**（清洁全文声明）。I 另读 6 个（ExtensionSelector/ProviderBalanceText/McpPicker/StickyHeader/Tooltip/SelectableItem），H 另读 3 个（Select/FlatTextField/NotionSlider），B 仅 grep 级引用。→ **ui/ 44 全覆盖**。

### 4.2 `components/` 顶层（58）

**全文/大段已读（54）**：
AgentMessageRow、AgentToolCapsule、ArticleRenderer、ChatDrawer、ChatInput、ChatInputUsage、ChatListGrouped、ChatListIndicators、ChatMessageActionFooter、ChatMessageActions、ChatMessageActionsSheet、ChatMessageAskUserStep、ChatMessageAvatar、ChatMessageBranch、ChatMessageCommon、ChatMessageCopySheet、ChatMessageCouncilStep、ChatMessageReasoning、ChatMessageSubAgentStep、ChatMessageToolPreviewSheet、ChatMessageTools、**ContentAttachmentChip**、**ContextRing**、CoreScreenPreview、DeepReadConfirmationDialog、ExtensionContent、GeneratedImageCarousel、GenerativeWidgetCard(死)、InteractiveMessageTimeline、**MarkdownRichText**、MarkdownText、MiniAppChatCard、ModelList、NovelBatchPolishSheet、NovelBranchSheet、NovelGhostwriteCard、NovelPolishCreateSheet、NovelPolishJobCard、NovelSettingProposalCard、NovelSettingsPane、PageTemplate、ProviderCard、ProviderTemplatePickerSheet、ReasoningPicker、SlashCommandPanel、StageProgressCard(死)、**SubAgentAvatar**、SubAgentRunSheet、ToolResultPreview(死)、TopModelMenu、UIAvatar、WorkspaceFilePreview、WorkspaceFileSheet、WorkspaceFileVM
（加粗 = 本轮补齐的 4 个）

**说明：引用状态更正（此前把注释/同名局部 struct 误当 import）**
| 组件 | 真实引用情况 | 状态 |
|---|---|---|
| `ContentAttachmentChip` | `AgentMessageRow.ets:21` 真实 import + :358/:364/:371 使用 | **活代码** |
| `SubAgentAvatar` | `ChatMessageSubAgentStep.ets:12` 真实 import + :143 使用 | **活代码** |
| `MarkdownRichText` | `MarkdownText.ets:3` **仅注释**提及，无 import | **死代码**（A 更正，文件实存 97 行） |
| `ContextRing` | 全仓 0 import；`ChatInputUsage.ets:33/101` 是同名**局部 struct**，非本组件 | **死代码** |

**抽样/部分（1）**：`McpPaperRows`（B 部分，内联开关复用证据）

**未读（3，均死码）**：
| 组件 | 引用方 | 性质 |
|---|---|---|
| `ChatExportSheet` | 无（0 import） | 死码，未读 |
| `ChatListSnackbar` | 无（0 import） | 死码，未读 |
| `ProviderConnectionTester` | 无（0 import） | 死码，未读 |

- 顶层 58 = 已读 54 + 抽样 1 + 未读 3；**4 个原缺口已补齐（4/4 全读）**，剩余 3 个为死码，不再补读。

### 4.3 `design/`（5）

- `tokens.ets`（201 行）**全文**；`fonts.ets`、`ChatTheme.ets` **已读（未加限定词）**；`components.ets`、`mock_data.ets` **抽样**（D 自述多为死码排版原语，不强求）。→ 精确为 1 全文 + 2 已读 + 2 抽样，不按「5 全覆盖」。

---

## 5. 尚未闭环 / 建议补派（不阻塞页面矩阵）

1. ~~4 个有引用、会被渲染但未读的组件~~ **已闭环**：`ContextRing`、`ContentAttachmentChip`、`MarkdownRichText`、`SubAgentAvatar` 4/4 全读；引用状态更正见 §4.2。
2. **剩余 3 个死组件不补读**：`ChatExportSheet`、`ChatListSnackbar`、`ProviderConnectionTester`（0 引用）；另有 `ToolResultPreview`/`GenerativeWidgetCard`/`StageProgressCard`/`UnreadBadge` 同样为死码。
3. **运行时证据全程缺失**：`hdc list targets` 为空，全部布局/IME/动效/截图项无设备证据。
4. **D 补篇更正**（非 UI，仅登记不影响本矩阵）：D1-1「INK 未导入致编译阻塞」更正为「当前构建不阻塞 / 死码维护债」；D1-2 主题刷新降级为 D2 待设备证实。

---

## 6. 方法学与边界

- 页面集合比对：`main_pages.json` 74 条 × 各报告显式已读清单做名字集合运算（`comm` 双向 diff），非前缀匹配；注册 74 = 分类 74，0 幻名、0 漏名，矩阵行号 1–74 连续。
- 引用入边来源为 E 的脚本统计；未重跑广泛图算法，**该统计不是根可达分析**（孤岛需另判，见 §2.1）。
- 「全文」为报告自述，未经第三方深度复读；本矩阵只清覆盖证据，不对任何 bug 结论背书。
- 未运行构建、未连设备、未改任何产品代码。
