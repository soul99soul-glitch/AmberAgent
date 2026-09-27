# 鸿蒙设置页重排 — 追齐 iOS/Android 新版 IA(2026-09-27)

基准：用户真机截图(Android 新版,6 组卡片 IA)+ iOS `SettingsHomeView`(amberagent-ios PlaceholderViews.swift:3815)。
视觉：奶油画布 + 圆角卡片分组 + 着色图标座 + 单行标题行 + 内缩发丝线 + `//` mono 组标 + 顶栏右侧头像。

## 目标 IA(六组卡片)

| 组 | 行 | 路由 | 图标 |
|---|---|---|---|
| 通用设置 | 外观 [值:跟随系统] | SettingAppearancePage | ph_sun |
| | 显示与字体 | SettingDisplayPage | gear |
| AGENT 设置 | 灵魂与记忆 | SettingAgentMemoryPageFull | brain |
| | 运行环境 | SettingSandboxPage | ph_code |
| | 运行与显示 | ChatAgentExecutionPage | eye |
| | 技能 | SkillsPage | puzzle |
| | 权限与批准 | ChatAgentPermissionsPage | shield |
| | 系统权限 | SettingSystemAccessPage | ph_lock |
| 模型与服务 | 服务商 | ChatProviderSettingsPage | key |
| | 模型与提示词 [值:模型标签] | ChatModelPickerPage | spark |
| | 任务模型 | ChatTaskModelsPage | cpu |
| | 提示词 | ChatPromptsPage | doc |
| | 搜索服务 | ChatSearchSettingsPage | search |
| 高级功能 | WebMount | SettingExperimentalWebMountPage | globe |
| | 子代理 | SettingSubAgentPage | ph_users |
| | 模型议会 | SettingModelCouncilPage | ph_quotes |
| | 小应用 | MiniAppSettingsPage | box |
| | 小说创作 | NovelProjectsPage | ph_pencil |
| | 深度阅读 | SettingDeepReadPage | book |
| 数据设置 | 同步与备份 | SettingBackupPage | hi_app_archive |
| | 聊天记录存储 [值:N · X.XX MB] | SettingFilesPage | hi_files |
| | 历史记录 | HistoryPage | history |
| | 收藏 | FavoritePage | star |
| AMBER | 关于 | SettingAboutPage | info |

页脚保留 `$ amber vX.Y.Z` mono 行;顶栏右侧头像 → ProfilePage(复用 ChatListPage 头像模式)。

## 对截图的取舍(鸿蒙现实)

- **语音合成:不加**。2026-08-29 用户明确删除 TTS(产品取舍),重建是整个特性工程,不在设置重排范围。
- **Jev 判断服务 / Synara / ZCode / iCloud WebMount:不加**。鸿蒙无此特性;仓库规矩 = 不放占位入口。
- **存储占用与清理:不单列**。鸿蒙 SettingFilesPage(浏览/删除/孤儿清理)已覆盖,以「聊天记录存储 + 实时统计值」呈现。
- **运行与显示、任务模型、提示词:保留**。真实页面,Android 有对应物;鸿蒙拆分更细属审计已接受的 IA 差异。
- **历史/收藏:保留在数据设置组**。审查实查:两页在全仓唯一入口就是旧设置页(ChatListSnackbar 仅是注释提及),移出即成孤儿页;基准截图虽无,鸿蒙不能静默丢功能。
- **系统权限:保留**(原仅在实验页可达),AGENT 组尾。

## 联动改动

1. 删 `SettingExperimentalPage.ets` + main_pages.json 条目(被高级功能组整体收编,否则成死路由)。
2. ~~SettingDeepReadPage 增模板行~~ 不需要:DeepReadTemplateSettingsPage 是兼容壳(replaceUrl→SettingDeepReadPage),模板选择已内联在 SettingDeepReadPage「文章模板」区。
3. `PageTemplate.ets` 新增 `CardGroup`/`CardRow` 卡片分组组件(其余页面不受影响,增量添加)。

## Phase

1. CardGroup/CardRow 组件(卡片容器 + 着色图标座 + 内缩分隔线)
2. SettingPage 重写(六组 IA + 头像顶栏)+ 删实验页 + 深读模板行
3. 数据装配(外观回显/模型标签/聊天记录存储统计 N · MB/头像)
4. lint → 构建 → 签名 → 模拟器截图(亮/暗)
5. subagent 审查(逻辑闭环/调用链/UI 细节)+ 精准修复
