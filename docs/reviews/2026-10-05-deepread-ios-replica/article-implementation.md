# 文章详细、编辑室与导出复刻记录

基准为当前 iOS 独立 `DeepReadApp` 工作树：`DeepReadDetailView.swift`、`DeepReadNewsroomView.swift`、`DeepReadArticleRenderer.swift`、`DeepReadDesign.swift`，以及其直接引用 `DeepReadCore/DeepReadNewsroom.swift`、`DeepReadCloseReadingRenderer.swift` 与本次 `ios-spec.md`。只读参考，没有修改 iOS 产品。

## 已实现

- `DeepReadArticlePage.ets` 仍持有原有调度、缓存续跑、订阅、章节重试、字号持久化、Web 生命周期与工作区保存。最后动效补修后 994 行，未增加业务状态机。
- 仅独立 `getProductKind() === 'deepread'` 使用 iOS 纸色与紧凑 inline 导航。字体菜单收纳 A-/A+，来源与分享为线形图标；模板菜单连接真实 `DeepReadTemplateSettingsPage`，跳转失败 toast。保存到工作区与重新生成仍可由菜单使用。宿主工具栏与原生正文独立保留。
- 设备回喂 `device/harmony-article-light.jpeg` 对照 `reference/ios-current-article-failed-light.jpg` 后，导航进一步改为左侧 40 card 圆底/ink 返回与左排 17 标题，右侧 Aa/来源/分享同一个 40 高 card 胶囊，每个控制仍有 44×44 点击区域。Pura 的系统 share 实际呈圆箭头，因此在本工具栏内以 density 换算的 20vp Path 实现向上出框线形分享，未修改共享 Icon 或原有菜单。
- 独立详情保留底部三个 tab，selected 取 `UIContext.getRouter().getStateByIndex(1)` 的真实栈根（发现/阅读库/设置），没有把记录离开 tab 的 `deepreadPreviousTab` 误作 current tab。共享 `DeepReadTabBar.clearHistoryOnSelect` 默认 false；只有详情为 true，切换前捕获绑定 Router，replaceUrl 成功后 clear 只保留新的根页，失败仍保留原详情栈并可重试。
- 设备另确认目标页 aboutToAppear 先于 replace promise 后续 clear，此时栈仍为 2。详情切 tab 显式传 `standaloneTabRoot: true` 路由参数，由 History/Settings 的 typed 参数消费识别根，避免新根因旧栈长度而漏掉底部导航；没有跨 await 临时全局标志。
- `DeepReadArticleStatusPanel` 只在运行、取消、未完成、失败、Web 显示失败、工作区保存失败或模板归属提示时出现。成功且无提示文章不常驻状态卡。失败/补全/取消/手动原生阅读仍连接现有处理方法；旧完整稿重试时不清空正文。
- `DeepReadNewsroomDesk` 在首次未有可读稿时占据详情剩余空间，四个 34 圆节点与 2 细线、1.2 秒当前节点外圈 pulse、主图标 breathe、阶段切换淡入、22 衬线阶段标题、前 10 字活字盘、每 4.2 秒桌面格言、离页说明。活字槽布局权重均分且 maxWidth 28，窄屏可缩小，不固定撑出 373 宽。真实阶段来自 scheduler 的 phase/label；300ms 定时器只改变活字和格言，页面隐藏/后台/销毁时释放。窄屏/小高度可滚动。
- 新稿实测回喂发现 overview 逐段持久化会令 Harmony `hasReadableOutput()` 提前为 true，而 iOS detail 只有最终 `resultMarkdown/structuredJSON` 生成 HTML 后才进入正文（runtime `store.complete`）。现新增页面视觉布尔 `runStartsUnreadable`，本轮开始/首次观察时按已有可读缓存快照；首次无稿的 overview/narrative/analysis/VERIFYING 保存不再提前退出编辑室，实际 running 终止后显示完整或部分稿。取消立即释放部分稿；旧完整/部分可读缓存的重生成或继续始终可读。没有修改 pipeline、READY、持久阶段或输出；同页隐藏/返回保留布尔，新页进入已有可读缓存按旧稿展示。
- `DeepReadArticlePressMoment` 提供 128 竖排章与完成说明：一般付印 1.4 秒，真实首次稿按全部保留的已完成历史计数产生首篇/十篇/百篇里程碑 2.6 秒，可点击关闭。真实 `RunResult.ok + isComplete` 与当页实际观察的 activeRun COMPLETE 共用去重。已查 scheduler COMPLETE 在 final saveGuarded await 成功后才发送，观察路径另读持久 complete 确认；初始 terminal 回放、失败、取消、部分稿、旧成功稿保留、后台/离页旧 cycle、被新 startedAt 替换的回调不会盖成功章。来源失败计数会进入状态 panel，即使正文完整也不会隐藏采集失败。
- 付印覆盖层使用 `backgroundBlurStyle(BlurStyle.Thin)` 与 50% paper 背景，文字和印章保持自身 opacity，对应 iOS ultraThinMaterial；宿主没有调用该组件。
- 独立默认杂志原生排版采用 iOS 22 边距、28 段间距、32/1.13 标题、18/1.34 脉络标题、15/1.68 正文、18/1.48 引用与 3 朱砂左线。实体改为细线标签；时间轴、观点、参考来源保留全部已有内容；默认杂志隐藏配图，对齐 iOS 默认 text-only 杂志。斜切/自定义 HTML 保留原 Web 链路。未配置字体时独立深读默认衬线；显式 system 偏好与 70–180% 缩放保留。
- 导出改为文本/Markdown/PDF 分组与文件保存分组。读取打开面板时持久文章快照、部分稿文本/Markdown、A4 PDF 页终态门控、API14 能力判断、Web 预览重试、系统分享、文件选择与 short-write 处理均保留。独立 PDF 隐藏 img/figure 对齐 iOS print；宿主 PDF 图片策略保持原样。

## 验证

- `node --test harmony/deepread/src/test/deepread_reader.test.cjs harmony/deepread/src/test/deepread_export_panel.test.cjs harmony/deepread/src/test/deepread_export_files.test.cjs harmony/deepread/src/test/deepread_library_entry.test.cjs harmony/entry/src/test/deepread_newsroom_card.test.cjs harmony/entry/src/test/deepread_ios_newsroom.test.cjs harmony/entry/src/test/deepread_ios_reader_navigation.test.cjs harmony/entry/src/test/deepread_editorial_motion.test.cjs`：87/87 通过。新增覆盖真实阶段/可见生命周期、模板路由失败、sources失败计数、重入完成信号、后台/替换/持久读取失败、原有结果回调去重、里程碑完整计数和迟到历史读取。详情 tab 用实际 selectTab 方法验证成功/重复切换仅余一个根、再打开文章后 Back 无旧根、失败保留详情栈及重试、绑定 Router 在页销毁后继续 clear；根 tab 默认路由行为保留。最后新增实际 runNow/repository observe/running observe/cancel 方法行为测试，验证 overview 至 VERIFYING 持续编辑室、终态释放、已有稿继续可读、同页隐藏/返回、已活动任务不重复启动和过期页面回调 guard。
- 集成全套测试的两处 PDF 与三处 newsroom fixture 仅补 `getProductKind: () => 'agent'`，保持原 host 字号/模板/配色和真实阶段断言。
- 另跑 `deepread_export_files.test.cjs`：16/16 通过；`deepread_artifact_store.test.cjs`：8/8 通过，涵盖实际失败文件写入/rename、持久化失败及保存后回调条件。
- spec 补修后 12 个改动 ArkTS 文件 lint：0 error / 10 warn。剩余警告为既有 JSON 深拷贝和组件提取的性能建议。
- 本轮底部 tab / material 改动的 Article、TabBar、PressMoment 三文件 lint：0 error / 2 warn，均为 Article 既有 JSON 深拷贝建议；`git diff --check` 通过。
- 最后一次截图回喂的 Toolbar 文件 lint：0 error / 1 个组件性能建议 warn；只修改导航几何与局部分享路径，既有菜单动作保持。
- 编辑室持续到首次 run 终态以及 root 初始化时序补修后的同一组定向测试：88/88 通过。新增目标页实际 replace 初始化仍为 len2 时携带明确根参数、清栈随后发生、失败不初始化目标也不改变旧栈的 contract。Article lint 0 error / 2 个既有深拷贝建议 warn。
- 主 agent 集成构建首轮发现 CopyOptions.Local 枚举不存在；已使用本仓先例 CopyOptions.InApp 修复。主 agent 负责后续集成构建、签名、模拟器截图和设备验收，本分支未独立 build 或操作设备。

## 本轮 API 文档核准

- 离线官方路由指南 `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/设置组件导航和页面路由/页面路由_ohos_router_不推荐/arkts-routing` 与 FAQ `FAQ/UI框架/组件使用/router使用pushUrl回调崩溃/faqs-arkui-1416`：异步路由回调不能依赖全局 Router 的瞬时 UIContext；先捕获 `getUIContext().getRouter()` 再调用。
- 当前 SDK `@ohos.arkui.UIContext.d.ts` 明确 `getRouter` / `Router.clear` 从 API10 提供、`getStateByIndex` 从 API12 提供；clear 的契约是清空历史、仅留栈顶。`@ohos.router.d.ts` 明确栈 index 从 1 起、name 为页面文件名、path 为页面路径。
- 离线官方动态模糊指南 `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/使用动画/动画效果/模糊/动态模糊/arkts-blur-effect` 提供 `backgroundBlurStyle(BlurStyle.Thin)` 示例；当前 SDK common.d.ts 核准 Thin 与背景模糊在 API12 之内，未引入高版本 API。

## 仍需明确的边界

- 目前 Harmony 业务模型提供 summary/timeline/corePoints/analysis/diagram/references 杂志结果，没有 iOS CloseReading 的原段落 ID、notes/modules/comparison 结构。因此未伪造精读/原文切换，也未将普通分析套成虚假的段落脚注。真正的 `<details>` 段落批注、宽屏旁注、多源比较与原文免模型路径需要业务范围确认后接入。
- 8 reader styles / 4 section layouts 需要同一配置的持久化与正文消费者，目前没有仅展示选项而不生效。
- 未强行改用户自定义 HTML 的自身 CSS；内置斜切 Web 和 PDF 目前仍复用既有模板 renderer。默认原生杂志复刻不能当作所有 HTML 样式已逐像素一致的证据。
- 减弱动效组件读取 AppStorage `reduceMotion` 并动态响应；主 agent 后续已接 API23 系统配置桥接，API12 没有该公开读取能力。本分工没有操作设备，不能从消费端测试推断系统设置与实际动画验收通过。完整动效补修见 [motion-article-implementation.md](motion-article-implementation.md)。
- 真实 provider 全流程、Harmony/iOS 同场景截图、真机触感和动态字号辅助功能效果仍由主 agent 实验验证，不从源码测试推断通过。
