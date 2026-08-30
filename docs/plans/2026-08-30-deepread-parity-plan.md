# 深度阅读能力对齐计划(2026-08-30 彻底审计驱动)

来源:Android 侧逐文件精读(DeepReadScreen 2076 行 / BoardPage 1303 行 / SettingTodayBoardPage 896 行 /
模板子系统 1301 行 / 生成链 Worker+Notifier+Scheduler+Prefetcher+Aggregator)vs 鸿蒙逐文件对照。
执行纪律:每 Phase 结束跑审查清单(逻辑闭环/调用链/UI 细节),精准修复,不过度防御;构建+lint+单测全绿才过。

总约束(全程):域层(chat/deepread)改动必须过两域当次全量单测（数量随功能增加，以命令输出为准）;
验证回路 lint→build→真机截图;
诚实 UI(F1 原则);新 API 先 `devecocli docs search`,API 12 以上 canIUse;V1 状态装饰器。

---

## P1 — 域层补齐(一切 UI 的地基)

1. `DeepReadSectionStatus` 增 `VERIFYING`;域模型补 `verificationState`
   (`{status, verifiedAtMs?}`),scheduler 尾段(全段 READY 后)进入补漏→COMPLETE
2. 缓存条目补 `expired`(24h 语义,`createdAtMs` 基准)与 `createdAtMs`/`updatedAtMs`
3. 新增 `deepReadProgressSnapshot`(照搬 Android:准备 6/收集 10/规划 24/写作按段
   递增/收尾 94/补漏 96/完成 100 + label),供 UI 胶囊与通知共用
4. 单测:快照映射表逐态断言 + expired 边界(23h/25h)

验收:deepread 当次全量单测与新增单测全绿;tsc clean。UI 不动。

## P2 — 热榜体验包

1. **聚合器**:新建 `platform_impl/HotListAggregator.ets`,照搬 Android 算法:
   标题归一化(标点折叠+小写)→ 实体抽取(别名词典可先空)→ 同话题判定
   (实体≥2 / 单实体+标题相等 / bigram Jaccard≥0.4 且长度≥6)→ 合并出
   `HotTopic{sources, sourceCount, bestRank}`;排序 = sourceCount↓,bestRank↑,新↓;
   上限 10
2. **BoardPage 版式**:LeadStory(首条大字)/IndexRow(索引行)报纸版式(rank mono、
   meta「来源·热度」、底部发丝线);每组 RubricHead 带 `timeAgo` 更新时间/错误状态
   (「⚠ 上次更新 N分钟前」)
3. **下拉刷新**(列表 onScrollEdge? 用绑定 Scroll 偏移+触摸上拉实现,或 Refresh 组件
   docs 检索后定)+ **骨架屏**(标题条×6 灰条) + 空源引导文案(「未启用热榜数据源…」)
4. 首条 LeadStory = 聚合话题第一名(带 sourceCount 徽标「3 源」)

验收:真机目测版式/骨架/刷新;聚合单测(构造三源数据,断言合并/排序/上限)。

## P3 — 热榜 ActionSheet 四动作

条目点击 → 半模态 sheet(复用 ChatMessageActionsSheet 视觉):
- **深度阅读**(默认高亮)
- **重新生成**(force=true 进文章页)
- **打开原文**(浏览器 intent,复用 MarkdownText openUrl 先例;无 url 禁用)
- **分享**(标题+url 文本,复用 ChatPage shareMessage 的 systemShare;降级剪贴板)

验收:真机四动作各走一遍。

## P4 — 文章页状态机补全

1. **首用确认页**:首次进文章页显示「深度阅读会消耗更多 tokens」+ 确认按钮
   (KV `deepread_first_use_confirmed`;确认前不自动跑生成)
2. **段落失败卡+单段重试**:各段 FAILED 时段卡内显示错误行+「重试本段」
   (`scheduler.runSection` 已存在,直接接)
3. **百分比进度胶囊**(顶部浮层,复用 P1 快照:label+percent,生成中显示)
4. **PartialError 浮层**:已有一部分 READY 段+生成中断 → 错误胶囊+「仅重试这一段」
5. **沉浸式**:进页 setWindowLayoutFullScreen 已是全局态,补状态栏内容色随暗色
   (applyWindowTheme 已有,确认文章页暗色下正确即可,不新造机制)
6. 「基础稿,可继续增强」提示(fallback 稿 READY 但非全 READY 时)

验收:真机走首用确认→生成→中途取消→重试本段;胶囊百分比递增目测。

## P5 — 设置页四组(SettingDeepReadPage)

1. **关注筛选**:关键词TextInput+chips(超 16 折叠 +N)+模式 SelectRow(全部/关注优先/仅关注)
   +「恢复推荐」;存储 KV `deepread_focus_keywords`(JSON 数组)/`deepread_focus_mode`
2. **阅读体验**:字体模式(默认/衬线;Slides 字体包接入后扩)SelectRow + 字号百分比
   Slider(80%–130%,存 `deepread_font_scale`);文章页消费(fontScale 既有通道改从设置初值)
3. **看板模型**:独立模型选择(默认=跟随聊天模型;KV `deepread_model_id`),
   getDeepReadScheduler 的 `ai_model` 读取改为:deepread_model_id → 解析,空则回落 ai_model
4. **深读搜索优先级**:搜索源列表拖序简化为上下移按钮(既有先例),存顺序;prefetcher
   消费(若现实现已按源序,登记即可)

验收:设置改动→文章页/热榜实际生效;单测不动(纯 UI+KV)。

## P6 — 通知增强

1. 进度百分比:Notifier 加 `notifyProgress(snapshot)`;调度层在 phase 变化时回调
   (节流=状态变化才发,与 Android shouldNotifyDeepReadProgress 同义);
   通知文案「深度阅读正在生成 · 24%」
2. 点击回文章:notificationManager NotificationRequest 带 `wantAgent`
   (docs 检索 notificationManagerWantAgent 确切 API;bundleName+ability+params
   topicId/title/sourceUrl)→ EntryAbility wantTopicId 通道已存在,接通即闭环

验收:真机生成中通知显示百分比,点击通知落到对应文章页。

## P7 — 历史页

1. DeepReadHistoryPage 行加「有效/已失效」徽章(P1 expired)
2. 文章页 fromHistory 参数:历史进入带 `fromHistory=true` → 允许读 expired 缓存+
   顶部「内容已过 24 小时,可能需要重新生成」胶囊(P4 浮层体系复用)
3. 空态文案对齐(「还没有生成过深度阅读」)

验收:真机历史进出+过期条目(可把设备时间或缓存时间改旧验证,不做测试钩子)。

## P8 — 模板子系统(等价移植,最大单项)

1. **模型**:TemplatePackage{id,name,description,html,createdByAi,schemaVersion,
   createdAt,updatedAt};MAX_HTML_BYTES=96KB;内置 editorial_slant
2. **Validator**:HTML 长度/占位符校验(port Android DeepReadTemplateValidator)
3. **Renderer**:editorial_slant HTML 渲染器(output→HTML;安全图片/链接白名单;
   sampleOutput 供预览)——Web 组件离屏渲染(ArkWeb 已有 WebMountHost 先例)
4. **文章页接线**:设置选模板→complete 且 templateSelected→WebView 渲染 HTML,
   fallback 回原生排版;「模板不可用,已回退」胶囊
5. **Agent 生成模板**(generate/revise/repair prompt):LLM 产 HTML 模板;
   工作台页(草稿编辑/预览/保存)——若工期紧可后置 Agent,先手内置模板
6. 设置页模板入口(选择内置/自定义)

验收:真机 editorial_slant 渲染一篇完整文章;模板删除→回退原生+胶囊。

---

## 里程碑

- M1(P1-P3):生成地基+热榜体验 — 约 3 天
- M2(P4-P6):文章页与通知 — 约 3 天
- M3(P7-P8):历史+模板 — 约 4 天

每 Phase:审查清单(逻辑闭环/调用链/UI 细节:错位/对齐/边距/字号)→ 修复 →
构建+lint+deepread 单测 → 真机截图目测 → 提交。

## 待办遗产

- 上轮遗留:热榜条目→文章正文生成的真机终验(P1 完成后一并跑,超时修复已就位)
- 36kr 源 HTTP 500(对方接口问题,容错已有)
