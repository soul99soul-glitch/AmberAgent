# 鸿蒙二级设置页 iOS 对齐计划(2026-09-27)

基准:12 张 iOS 截图(/Users/arquiel/Downloads/Amber 截图/IMG_1983–1994)+ iOS 仓库组件规格
(amberagent-ios PlaceholderViews.swift: AmberGlassCircleButton/AmberSectionLabel/AmberFormGroup/AmberFormRow)。
根页保持已交付的 Android 风格(// 组标),二级页全面切 iOS 语言。

## iOS 二级页设计规范(从仓库源码提取的精确值)

| 元素 | 规格 |
|---|---|
| 顶栏 | 44 玻璃圆返回钮(符号 17 semibold,ink 色)+ 居中 title2(22)bold 标题;可选 caption 副标题(13 muted);可选右侧动作(accent 圆 + 或文本) |
| 组标 | caption(12)semibold muted,拉丁大写,tracking 0.4;h16 top20 bottom7;无 // 无发丝线 |
| 卡片组 | SURFACE 底,r18,0.5 borderSoft 发丝描边;页边距 h16 |
| 卡片行 | minH 52,pad h14 v4;图标 28(16 medium)或 28 accentTint 座 r7;标题 body(17);副标题 caption(12)muted ≤2 行;值 subheadline(15)muted;箭头 caption semibold muted2;分隔线内缩 54(=14+28+12) |
| 开关 | 药丸,开时 accent 底 |
| 状态 chip | 软底小药丸:已配置/已登录 绿,未填写 灰,自动批准 accent 浅底,高风险 红浅底 |
| 脚注 | caption(12)muted,组下 h16 top6 |

## Phase 0:基建(本人)

PageTemplate.ets 增量(不影响既有组件与根页视觉):
- `CenteredTitleBar`(玻璃圆返回 + 居中标题/副标题 + 可选右侧文本/accent 圆动作)
- `PlainSection`(iOS 组标)
- CardGroup 加 `radius`(默认 16)/`stroke`(默认 false)props,二级页用 r18+描边
- CardRow 加 `desc` 副标题、`plateStyle: neutral|accent`
- `CardToggleRow`(标题+副标题+accent 药丸开关)
- `StatusChip`(success/muted/accent/warn/error 五档软底药丸)
- `FooterNote`(组下脚注)
- `AccentCircleButton`(44 accent 圆 + 白图标,列表页添加动作)

## Phase 1–8:逐页迁移(子代理并发,每页一个子代理;逻辑零改动,只换骨架)

| Phase | 页面 | iOS 截图 |
|---|---|---|
| P1 | SettingAppearancePage(外观)+ SettingDisplayPage(显示与字体) | 1983/1984 |
| P2 | SettingAgentMemoryPageFull(灵魂与记忆)+ SettingSandboxPage + ChatAgentExecutionPage(运行环境/运行与显示) | 1985/1986 |
| P3 | SkillsPage(技能)+ ChatAgentPermissionsPage(权限与批准) | 1987/1988 |
| P4 | ChatProviderSettingsPage(服务商)+ ChatModelPickerPage(默认模型)+ ChatTaskModelsPage(任务模型) | 1989/1990 |
| P5 | ChatSearchSettingsPage(搜索服务)+ SettingExperimentalWebMountPage(站点) | 1991/1992 |
| P6 | SettingSubAgentPage(子代理)+ SettingFilesPage(文件管理) | 1993/1994 |
| P7 | ChatPromptsPage(提示词)+ MiniAppSettingsPage(小应用)+ SettingModelCouncilPage(模型议会) | 无,沿用规范 |
| P8 | SettingBackupPage(同步与备份)+ SettingAboutPage(关于)+ SettingSystemAccessPage(系统权限)+ HistoryPage(历史)+ FavoritePage(收藏)+ SettingDeepReadPage(深度阅读设置) | 无,沿用规范 |

## 每 Phase 回路

1. 子代理改页面文件(仅 .ets 页面,禁动 PageTemplate/tokens/逻辑层)→ 自跑 lint
2. 本人:全量构建 → 装机 → 逐页截图(亮/暗关键页)
3. 审查子代理:对照 iOS 截图+鸿蒙截图+diff,查逻辑闭环/调用链/UI 细节(错位/对齐/边距/大小)
4. 精准修复 → 提交 → 下一 Phase

## 铁律

- 功能零增删:只换视觉骨架;缺截图的页面沿用规范,不发明新功能
- iOS 有而鸿蒙没有的功能块(如运行环境的「最近任务」、主题包网格)不补——那是特性工程,不在对齐范围
- 鸿蒙有而 iOS 没有的入口保留,按规范排布
