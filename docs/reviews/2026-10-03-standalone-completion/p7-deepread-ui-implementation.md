# P7 深度阅读产品 UI

实际 iOS 的 `DeepReadDiscoveryView.shuffle` 从当前热点与提供方条目选择选题，确认后复用原 `start → runtime.create`。鸿蒙在独立应用 Board 增加明确的“随机阅读”按钮，只取当前可见 topics 和每个当前来源实际显示的前 12 项；保留真实标题、来源、排名、摘要与可选 URL，经既有 `discoveryHotspotInputs/discoveryArticleParams/openArticle` 打开文章。有缓存时沿既有缓存读取，没有缓存时沿原热点生成调用；按钮本身不创建运行、存储或虚构 URL。空选题显示说明并禁用按钮。

实际 iOS `DeepReadDetailView` 在文章尚未显示且任务正在运行时呈现 Newsroom。鸿蒙使用 Board 上的活动任务小卡片承接同一可达能力，页面位置有所不同。卡片使用采访、整理、撰稿、付印四阶段轨道，展示任务当前准确说明，不显示估算百分比。运行阶段来自 scheduler 真实 `runningJobs/observeActiveRuns`，不是仓库文章 phase；强制重生成时旧缓存仍保持 COMPLETE，也不会误报新任务完成。阶段与回调归属的领域接线及实际失败、取消测试见 `p7-runtime-progress.md`。

新组件 `DeepReadNewsroomCard.ets` 直接读取 V1 `@Prop run`，标题可自然换行、正文无固定高度、内容左对齐，“查看进度”热区至少 44vp。新阶段与同阶段说明变化都进入确定性的运行 key，不使用随机 epoch。Board 在 onShow 订阅当前活动运行，在 hide/disappear 释放，初始化与回调均使用既有页面生命周期 token 防止旧页写入。活动卡放在原可滚动内容顶部；热榜首载骨架不会遮住已存在的任务卡。真实订阅失败及导航失败对作者可见。新增观察和入口仅在 `productKind === 'deepread'` 独立产品启用，宿主原有热点调用保持原路径，不冷初始化无关深读 runtime。

生产修改范围是 `pages/BoardPage.ets` 与新增 `components/DeepReadNewsroomCard.ets`；本轮没有修改 Article、History、搜索宿主、AppContainer、小说页面或新增存储。History 的既有实时库订阅、查询、页面释放和键盘回归继续运行。旧 `hotlist_board.test.cjs` 只调整 fixture 以满足新增页面依赖，保留已有断言语义。

源码已冻结。新 Board 实际方法 9/9、组件真实 helper/build adapter 3/3、既有 History 实际方法 8/8、原 hotlist Board 7/7，组合 27/27；包含无 URL 来源保留、聚合多来源、可见范围与空候选、同任务阶段变更、迟到初始化/回调、返回重新订阅及 chat/novel 不初始化 standalone runtime。实际 scheduler progress/signal 10/10，DeepRead `tsc --noEmit` 通过。Board 最终单文件 scoped ArkTS lint 无缺陷；Board + Card scoped lint 为 0 errors / 1 warning，warning 是新增职责独立小组件的性能建议，不是编译或状态错误。未执行构建、签名、安装或设备操作；这些统一由 root 负责，截图视觉门槛尚不能以源码测试代替。

日志：`/tmp/amber-p7-deepread-ui-tests.log`、`/tmp/amber-p7-deepread-ui-lint.log`、`/tmp/amber-p7-board-tests.log`、`/tmp/amber-p7-board-lint.log`。实际运行阶段领域日志见 runtime 报告。

独立 reviewer 为 `/root/p1_deepread_inventory`，只读生产源码，报告 `p7-deepread-ui-independent-review.md`。该独立报告已关闭源码与实际调用 gate，并通过真实 scheduler 强制生成旧缓存 → Board 订阅 → Card builder 的组合验证；设备视觉 gate 仍等待 root 的截图/AX。实现者报告不代替独立审查结论。
