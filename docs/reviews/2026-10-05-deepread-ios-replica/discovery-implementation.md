# 鸿蒙独立深读发现页与底部导航

2026-10-05。以当前 iOS `DeepReadDiscoveryView.swift` 与主 agent 重建取得的 `reference/ios-current-discovery-light.jpg` 为准。已装旧 iOS binary 的截图包含副标题和刊头下方创建按钮，当前源码已经改为紧凑刊头与右侧来源/创建 toolbar，因此本次按当前源码实现。

## 改动与接口

- `BoardPage.ets` 的独立 `deepread` 分支使用 iOS 纸色、刊头、每条热点单独卡片与共享浮动 TabBar；宿主仍走 `HostBoardContent`。
- `components/deepread/DeepReadDiscoveryHeader.ets`：居中标题、右侧来源/创建；2.5 vp 墨线、真实阅读库数量加一的期号、日期、节日和夜读徽章、28 vp 衬线刊头、连续五次点按盖章。窄屏下日期/徽章允许换行。
- `components/deepread/DeepReadDiscoveryCards.ets`：18 vp 卡片圆角、0.6 vp 描边、14 vp 内容内距、22 vp 两位编号、17 vp 衬线热点标题（最多三行）、来源摘要和右上箭头。来源榜单保留收起入口、逐源失败/旧缓存说明。
- `components/deepread/DeepReadTabBar.ets`：`DeepReadTabBar({ selected: 0 | 1 | 2 })`，依次为发现、阅读库、设置；页面 import 路径 `../components/deepread/DeepReadTabBar.ets`。内部用 `router.replaceUrl` 切换 `BoardPage` / `DeepReadHistoryPage` / `SettingDeepReadPage`，错误通过 toast 呈现。文章继续 `pushUrl`，Back 回到所属根页。
- 创建阅读使用库/设置 worker 提取的唯一 `DeepReadComposerSheet`，发现与阅读库均为 92% `bindSheet` 模态。保存成功后 `onCreated(topicId,title)` 等待文章路由成功再关闭 sheet；路由失败抛回组件，组件保留已保存 draft ID，重试只打开同一稿件，不重复创建。
- sheet binding 挂在 Board 最外层 Stack，位于 `ForEach(themeEpoch)` 外；切主题仅重挂载纸面内容，Composer 子组件及其草稿状态保留，无额外草稿 store。
- TabBar 写入 `AppStorage('deepreadPreviousTab')` 与 `AppStorage('deepreadTabDirection')`；新根页首现时可用方向值播放 30 vp 水平到位，返回文章时不重复播放。发现页已经接入。胶囊内部指示器从前一 Tab 滑向当前 Tab。
- 为使 replacement 保留发现的界面状态，滚动停止/离页时将 Scroller offset 保存至 `deepreadDiscoveryScroll`，有实际热点/来源内容的第一次布局后恢复一次；后续刷新不再次恢复。来源折叠集以 `deepreadDiscoveryCollapsedSources` 按 sourceId 存在 AppStorage，重新进入或主题重建时恢复。
- 发现根页接入主 agent 的 `DeepReadPaperBackground`，内容透明以显示纸纹；底部留 104 vp 保证最后条目可滚到浮动导航上方。
- 发现根调用 `DeepReadPaperBackground({ nightReading: true })` 以启用主 agent 提供的夜间桌灯效果；子页仍使用普通纸底。

点按热点卡片直接携带真实来源进入文章，长按仍打开已有深读/重新生成/原文/分享操作。随机阅读、真实后台任务入口、缓存/关注/Wi-Fi/翻译与退出取消 HTTP 的已有调用链保留。新增期号读取带可见性和观察 token 校验，过期结果不能覆盖返回后的页面；无法读取时显示 `No.---`。

节日按 iOS 规则覆盖公历节日与农历新春、元宵、中秋。农历使用仓库已有 `Intl.DateTimeFormat.formatToParts` API 并检查 `calendar === 'chinese'`；闰月不误判节日。未增加新的 `@ohos` / `@kit` API。

## 验证

```sh
node --test harmony/entry/src/test/deepread_board_ui.test.cjs \
  harmony/entry/src/test/deepread_ios_discovery_navigation.test.cjs \
  harmony/deepread/src/test/hotlist_board.test.cjs
```

23 / 23 定向测试：原缓存/网络/任务生命周期行为；三根页 replacement 与文章返回；路由失败可见并可重试；无 URL 与多源元数据；真实期号和迟到保护；农历节日及闰月；滚动恢复只在实际内容出现后执行一次；来源各自保留折叠；真实 Composer Builder 回调在路由失败时保持 sheet、重试同一 draft 成功后关闭。

四个 ArkTS 文件定向 lint：退出 0，0 error / 1 warn（header 优先 Builder 的性能建议）。`BoardPage.ets` 保持低于 1000 行，视图拆入三个小文件。

滚动/折叠更新后三文件 lint 仍为 0 error / 1 warn；模态接线后 Board 单文件 lint 无缺陷。

统一构建回喂修正了当前 SDK 不存在的 `FontWeight.SemiBold` / `FontWeight.Light` 枚举，使用已有数值字重 `600` / `300`；lint 本身不能捕捉此类字重错误。

构建、签名、安装和截图目测由主 agent 统一完成。本 slice 未独立构建、未操作设备，不把 Node 方法测试当作 ArkUI 视觉或真实 provider 验收。
