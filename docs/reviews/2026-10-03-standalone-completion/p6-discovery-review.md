# P6 Discovery 独立审查

2026-10-03。生产代码只读；本报告由独立 reviewer 维护。审查 discovery 目录、HTTP 解析、缓存、聚合、发现设置、Article 持久输入与 prefetch/planner 链；不执行构建、安装或设备操作，不覆盖模板/library/runtime 的独立审查。

## 结论

**本次 discovery 源码与调用链审查通过，未发现新增的可复现阻断问题。** 独立运行仓库聚焦回归 **91/91 通过**；额外三个独立实际执行探针 **3/3 通过**。设备几何、真实公网刷新与 provider 生成验收由 root 另外执行，本报告不把 HTTP fixture 成功当作真实服务在线证明。

## 当前 iOS 参照与能力映射

实际读取独立 iOS `DeepReadDiscoveryView/DeepReadDiscoveryPreferences` 与生产 `IOSHotListProviderRegistry/IOSHotListProviders`，生产目录为 5 个英文源 + 15 个 NewsNow 源。独立探针直接提取 Swift 的 15 个 NewsNow ID/category，并核对 Harmony 20 项目录类别。

Harmony 原有 11 个源及综合热榜/缓存/刷新行为，新增的真实缺口为 HN、HF Papers、GitHub AI、IT 之家、少数派、掘金、酷安、雪球、虎扑共 9 项；原有 `github-trending-today` NewsNow 与独立 `github_trending_ai` 都保留，未把既有聚合内容重复判为缺失。

新可选中文源默认关闭，原保存开关优先；已有源延续既有默认。当前 `BoardPage`、新 `DeepReadDiscoverySettingsPage`、共享 `SettingDeepReadPage` 都采用目录的同一 defaultEnabled fallback，设置与返回发现页后的实际抓取范围一致。保留 Harmony 原已有源的默认并不意味着与 iOS 新安装默认集合逐项相同。

## 实际链路核对

- `HOTLIST_PRESETS → NewsNowProvider.fetchAllSources(enabledIds) → fetchBuiltinSource/fetchSource/fetchRssSource` 真实选择源 HTTP。HN 请求 Firebase topstories 与 item；HF 请求 daily_papers；GitHub 解析实际 trending HTML；六个新中文源使用既有 NewsNow URL。未知和重复启用项不扩大 HTTP 数量，空启用集不回退全部，取消在后续 HN item 请求之前可停止。解析无链接项不伪造 URL，删除/死亡 HN 项过滤，HF summary 保留。
- `HotListState.mergeHotListSections/projectHotListSections → HotListAggregator → Board` 保留源名、rank、summary、原文 URL。失败刷新沿原 stale/error 展示保留上次成功条目和成功时间，标题翻译只改变显示投影，不改变来源身份。实际 Board 聚合点击传入所有来源上下文与所有真实链接，单源点击同样保存 provider/rank/summary。
- `discoveryHotspotInputs/discoveryArticleParams → Article.saveDiscoveryInputs → repository.upsert → createRunContext → prefetch → mergeCollectedSources → generationSources → planner` 闭环。无 URL 热点保存 ready 文本和 null URL、stable discovery topic ID，空搜索结果时仍使用已保存来源进入规划。topic identity 不依赖 rank，正常排名刷新不会创造另一个无链接主题。
- 有真实链接的 ready 排名背景不会被当作已验证网页正文：`pendingUrls` 只允许 web/search ready 资料免抓，text 型发现背景仍触发链接采集。合并后同一来源同时保存榜单背景与网页正文，不把十条元数据当作正文缓存。
- 已缓存文章显式 force 时，实际 Article 方法在 scheduler.run 之前保存本次 provider/rank/summary，保留旧正文与模板元数据直到实际 run 替换。普通缓存重开不生成新任务；已有活动任务时拒绝替换其输入。独立 probe 将真实 Article 方法接到真实 createRunContext，确认 planner 中含 NEW provider/summary 且不含 OLD provider。

## UI 源层核对

新发现设置按五个目录类别分组，采用现 V1/theme/page 组件。Board 来源设置与刷新入口均为 44 × 44 vp；设置源行采用现 `CardToggleRow`，标题列 layoutWeight(1)，开关热区至少 44 × 44 vp，长标题单行省略，不使用固定标题宽度挤开关。focus/refresh selector 最小 44 vp，保存失败/读取失败与离页迟到结果有实际方法测试。

这只证明声明和方法接线；错位、实际边距与暗色对比仍需 root 最终签名包截图核对。

## 独立验证

- 仓库 12 个聚焦 suites，合计 **91/91**：discovery、provider、state、RSS、aggregation、Board、grouped settings、shared toggle、input sources、input run、run manager、actual Article/compose capture methods。
- `/tmp/p6-discovery-review-probes.cjs`，**3/3**：当前 Swift 20 项类别对照；HF 实际 provider HTTP response → 失败刷新缓存 → 持久输入 → 实际 planner；真实 cached-force Article → 新 provider/rank/summary 持久化 → 实际 planner。
- 日志 `/tmp/p6-discovery-independent-review-tests.log`、`/tmp/p6-discovery-independent-probes.log`。

实现报告此前记录的 linked-background 阻断正文采集、cached force 忽略本次来源、共享设置 default mismatch 三个问题，当前生产代码与实际回归均已闭环，本轮无需额外生产修改。
