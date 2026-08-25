# Harmony vs Android 完成度差距 Backlog（2026-08-25 真机反馈驱动）

三路审计（功能接线 75 页全查 / 聊天页 UX 逐维 / 视觉设计系统）汇总。用户定性：功能完整性与 UI 样式完成度远低于 Android。

## 一、功能差距（按感知伤害排序）

### F1 假页面下线/重定向（S，最高优先）
18 个页面渲染了 UI 但数据伪造/保存假成功，与真实页面并存造成"设置保存了却不生效"：
- `SettingMcpPageFull`→重定向 `ChatMcpSettingsPage`
- `SettingExperimentalModelCouncilPageFull`→`SettingModelCouncilPage`
- `SettingExperimentalSubAgentPageFull`→`SettingSubAgentPage`
- `SettingAgentExecutionPageFull`→`ChatAgentExecutionPage`
- `SettingAgentPermissionsPageFull`→`ChatAgentPermissionsPage`
- `SettingTTSPage`（假引擎页）→`SettingTTSProviderPage`
- `SettingModelPage`（假模型库）→ 删除入口/重定向 `ChatModelPickerPage`
- `SettingSearchListCards`+`SettingSearchServiceEditorSheet`→`ChatSearchSettingsPage`
- `MemoryPage`（假数据）→ 本轮接真实 MemoryStore（F4）
- `SkillDetailPage`（假文件树）→ `SkillsPage` 或诚实标注
- `QuickMessagesPage`（不落盘）→ 本轮补 KV（F5）
- `AssistantPropertyEditor` 死入口已移除（上轮）
- `DeepReadTemplateSettingsPage`/`DeepReadTemplateWorkbenchPage`（内存模板）→ 下线
- `SettingExperimentalWebMountPage`/`SettingExperimentalOfficeProPage`（定时器假装连接）→ 下线或明确 Alpha 占位
- `SettingAgentExtensionsPageFull`→真实入口

### F2 SearchPage 假结果（M，本轮修）
固定 5 条模板结果 + 点击进断链 DeepRead。改为：复用 chat 域 `searchOrchestratorSearch`（完整存在：配置服务/内置源/编排），结果渲染真实标题/URL/摘要，点击复制 URL 或进浏览器；输入偏好取 `loadSearchPrefs`。

### F3 DeepRead 生成管线断链（L，最大单项）
`DeepReadArticlePage.generateArticle()` 写死失败文案；`RdbRepository` 全 no-op 内存 fallback；`OpenAiCompatibleAiClient` 未实现 deepread 工具/流式契约（PD 登记）。接线 = AppContainer 装配 run() + 页面调用 + observe 进度 + 渲染 markdown + 历史。多 session 工程。

### F4 MemoryPage 假数据（M，本轮修）
接 `MemoryStore`（`SettingAgentMemoryPageFull` 已有真实 CRUD 可复用/合并入口）。

### F5 QuickMessages 不落盘（S，本轮修）
KV schema + CRUD；后续接 Chat 输入快捷面板。

### F6 Assistant 假字段（M，本轮修）
- `AssistantBasicPage`：description/model 不进 patch；模型下拉是占位 → 真实 provider 模型列表
- `AssistantPromptPage`：世界书 entries 只改内存、save 只写 systemPrompt → lorebookIds+条目持久化（ExtensionPrefs.updateLorebooks 已有）
- `AssistantMemoryPage`：列表硬编码（同 F4 合并处理）

### F7 Cron 执行器（L，登记）
`AGENT_CRON_EXECUTION_AVAILABLE=false`；UI 已诚实禁用。需 WorkSchedulerExtensionAbility + 无头聊天运行基建。

### F8 聊天域功能缺口（L 为主，下阶段）
消息全文搜索（Android MessageSearch+FTS）、多选导出、图片外附件 picker、系统分享 intent、真 TTS（SDK 无 CoreSpeechKit → 需网络 TTS）、Markdown 链接可点/代码块操作栏/图片预览、工具卡片 favicon/文件预览。

## 二、聊天页 UX（parity 计划 P1-P6 大部分已落地；残余）
- 助手消息 bubble/header（showAssistantBubble 640dp/8dp 圆角）、用户消息 action footer 应隐藏（Android 靠单击编辑+长按菜单）（S-M）
- reasoning 自动折叠 + 耗时 ticker（M）
- context usage：5 段柱 → Android 圆环四态（组件 ContextRing 已存在未接线）（S/M）
- slash 面板应 Popup 化不挤压输入区（M）
- drawer 宽度/圆角对齐 336dp/24dp（S）

## 三、视觉（色板已与 Android AmberTokens 一致；差距在"令牌没落到组件"）
- V1 Unicode 图标（☰✎←›↑+）→ 统一 Icon 组件（S，感知最大）
- V2 按压态：BtnInk/BtnAccent/NavRow/列表行 0.975 scale+透明度（ChatInput 已实现可复制）（S）
- V3 TypeScale：补 family/weight/lineHeight 契约（AmberTextStyles 7 级对齐）+ Noto Sans SC 多字重（M）
- V4 主题令牌三处错：ACCENT_INK 固定白（Sage 应深字）、SIGNAL 不随 accent、ERROR 不分深浅（S，本轮修）
- V5 Dialog/Sheet 非真浮层（无统一遮罩/进出场）（M）
- V6 设置页 CardGroup 化（外 18/内 2/1dp 接缝/按压圆角）（M）
- V7 主题生命周期：启动浅色闪帧、无系统跟随模式（M）
- V8 双 DotLoading/EmptyState 尺寸不统一（S）
- V9 spacing scale（4/8/12/16/20/24/32）收敛散布值（M）

## 本轮执行
F1 重定向 + F2 SearchPage 真搜索 + F4 MemoryPage 真数据 + F5 QuickMessages 落盘 + F6 Assistant 假字段 + V1/V2/V4 视觉速效。F3/F7/F8 与 V3/V5-V9 列下阶段。
