# 小说与深读：纸面设计与动效修缮

目标：在现有暖纸／陶土设计语言上，让两个独立应用有鲜明视觉身份、细腻反馈和可发现的彩蛋；保持现有创作、阅读、导入、任务和配置调用链。

## 完成条件

- 小说首页形成写作书房：有层次的纸页插画、创作引导、项目统计与清楚的创建／导入入口。
- 深读首页形成阅读观察台：星轨插画、清楚的发现层级、卡片触感、随机阅读与刷新反馈。
- 公用小组件实现弹性按压、有限时长的插画反馈与长按彩蛋。彩蛋纯视觉，不写项目、不发请求、不覆盖正常操作。
- 返回、主题重挂载、后台／销毁期间清理定时器和动画回调；保持点击范围、可读性和阅读区域。
- 每阶段独立 subagent review，通过 scoped lint、两个独立应用构建、浅／深色与触发彩蛋的设备截图／连续帧检查后交付。

## 约束与范围

工作树已有其他未提交工作，按初始 SHA 保护。本轮修改两个首页、新增 studio 组件，并对主要写作页／阅读页的切换和工具控件做局部接入，不改共享主题／按钮的全局行为，不改领域逻辑。构建以已提交 b2d054b 的独立检出加本轮修改进行，沿用已授权真机签名和覆盖安装流程，保留应用数据。无需额外确认；不自动提交／推送新改动。

## Phase 1：纸面组件与动效基础

Root 独占 components/studio 下 StudioEmblem、StudioHero、StudioButton。API 12／V1，已有 springCurve、animateTo、onTouch、LongPressGesture；新 API 先读官方文档。组件负责局部状态与有限动效，不建立全局动画管理器。独立 reviewer 检查生命周期、点击与长按分离、禁用状态和主题。

## Phase 2：两应用页面落地

Novel worker 独占 NovelProjectsPage.ets：空态与项目列表的设计层次、真实统计、创建／导入／FAB 与行按压。
DeepRead worker 独占 BoardPage.ets：首页插画和层级、头部操作与随机阅读／刷新反馈、条目按压。

共享契约：StudioHero({ kind: 'novel'|'deepread', title: string, caption: string, detail: string, compact: boolean })；StudioButton({ label: string, iconName: string, primary: boolean, disabled: boolean, onTap: () => void })。仅独立产品首页加入 Hero；原业务回调原样接入。两 worker 不修改共享组件／其他页面／领域逻辑，适配他人同时工作。实现后分别做逻辑、链路和 UI 代码 review 并修复确证问题。

## Phase 3：验证、安装与视觉复审

精准 lint → 独立源码拷贝一致性／相关既有测试 → 两包构建与现有 AGC 签名 → 原真机覆盖安装与冷启动 → 模拟器浅深主题、长按彩蛋连续帧／按压／返回检查。实际 UI 截图由独立 subagent 检查对齐、边距、尺寸、裁切与内容占用；只修确证问题。静态图不能替代运动证据，不外推全应用或性能无卡顿结论。

当前状态：Phase 1、Phase 2、Phase 3 全部完成并通过独立 review。8 文件 lint 0 errors／2 条旧 performance warnings，Entry 172/172，双应用最终构建、AGC 签名、真机覆盖安装和冷启动通过。浅深色首页、彩蛋连续帧、主题返回、工作区页签、阅读字号和资料／导出入口的设备复审 PASS。详见 [Phase 3 报告](../reviews/2026-10-03-standalone-studio-design/phase3-review.md)。

## Phase 2 补充：核心写作和阅读交互

为让设计进入实际使用流程，小说工作区主页签与设定分类接入滑动纸面标记 StudioTabs；深读阅读工具（字号／资料／导出）接入 compact StudioButton 弹性反馈。仅独立产品，新组件保持原索引、回调及守卫，宿主原 controls 保留。大文件不做机械重构，只替换局部视图。与首页一起进行 review 和设备检查。

Phase3首批实际截图发现并修复：深读窄屏标题孤字换行（缩短标题）、暗色纸页层次较弱（局部paper填充/纹理用既有分层token）、compact插画整体下偏35–45px（内部scale和外部占位统一）。无需修改全局边距或主题。

Phase3核心页面复拍另发现浅色小说列表 FAB 重复外层阴影造成直角矩形底，精准移除外层 shadow 一行。最终双产品重建、签名、覆盖安装、浅深色复拍和独立复审 PASS；原操作回调、禁用条件及按钮自身圆角阴影保留。
