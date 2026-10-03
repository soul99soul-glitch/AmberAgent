# P7 深读 UI 独立审查

状态：**Newsroom/随机阅读的源码、实际调用与当前 P7 设备视觉 gate CLOSED**。最后三行触控尺寸修复的源码 gate 通过；新尺寸 AX 等 root 最终包采集。本 reviewer 不改生产源码，不构建或操作设备。

## 已确定的实际 iOS 行为

- `DeepReadDiscoveryView.swift:247` 的 shuffle 从当前 dashboard 的 topics 和 provider.items 选择 LuckyPick；点击“读这篇”走 `start:233` → `runtime.create`，保留真实来源、排名和可选 URL。不是已保存文章随机阅读。
- `DeepReadDetailView.swift:46` 仅在没有已显示 HTML 且任务 queued/running 时展示 Newsroom；四种编辑台阶段由 runtime 的真实 progressLabel 映射，装饰性动画不会推动运行进度。
- 鸿蒙 P7 指定实现是 Board 可达活动任务卡与当前真实热点随机选择。Newsroom 所在入口与 iOS 不同，审核以可达能力与真实状态为准，不把不同入口位置直接判作缺失。

## 冻结后必查

| 范围 | 必须成立的闭环 | 当前证据 |
| --- | --- | --- |
| 活动任务 | scheduler 真实 runningJobs/observeActiveRuns → 当前运行 stage/label → Board @State → Newsroom；force 运行不能从旧 COMPLETE 缓存猜新阶段 | actual scheduler/Board/Card 组合通过 |
| 页面生命周期 | onShow 注册；hide/disappear 释放；异步初始化与旧订阅迟到回调不能覆盖新页面状态 | actual 方法与 held/cancel 反例通过 |
| 随机热点 | 只取当前实际显示 topics/各 source 的可见 items；过滤或禁用来源不能再次进入随机候选 | 当前 topics/sections、每源前12条；既有真实 projection tests 通过 |
| 导航与来源 | 同既有 discoveryArticleParams → Article；无 URL 时保留 provider/rank/context，不造 URL；空候选可达提示而不建假任务 | actual randomRead → Article params 通过；空状态提示与 disabled 可达 |
| UI 状态刷新 | 新进度与候选能通过 ArkUI V1 装饰状态刷新，ForEach identity 不吞内容更新；不从 source 推断已在设备正常 | @State 新数组赋值、runKey 含实际 stage/label、Card build 同实例字段变化测试通过；设备待验 |
| 视觉细节 | 浅暗色、长标题、卡片宽度/对齐、阶段轨道、边距及操作热区依据实际截图/AX 判断 | 当前 P7 Newsroom 与随机入口截图/AX 已独立通过；末尾三行触控修复的新 AX 待最终包 |

## 冻结源码与实际测试结论

生产范围是 `pages/BoardPage.ets`、新 `components/DeepReadNewsroomCard.ets`，以及 runtime owner 的 `agent/scheduler.ts` / `agent/run_manager.ts` 与 AppContainer 一行 `onProgress` 透传。Board 只在 deepread 产品初始化活动任务订阅、显示随机操作；agent/novel 产品保留原热点入口。没有新的持久化 store、作业 API、轮询或假进度百分比。

Scheduler 以现有 job token 持有 stage/label，factory 的第四参数以及 manager callback 都受 ownsJob/取消约束。RunManager 在实际来源收集、规划、段落生成、最终保存前发阶段更新；COMPLETE 在成功保存后才发。强制重做时旧完整稿仍在仓库，新活动卡仍能显示 COLLECTING/PLANNING/WRITING/VERIFYING。保存持有期间不会提前显示 COMPLETE。原 factory callback 在取消后、第二个 owner 运行时、清理后都不能更新新活动任务。

Board `onPageShow` 注册，hide/disappear 同时释放订阅并增加 observation token。初始化晚到和旧 callback 均检查 visible/token；重新显示立即读取真实最新活动列表。活动卡在 Scroll 内，首载骨架的判断包含 activeRuns，因此没有热点也可以看到正在运行的任务。`runKey` 包含 topicId、startedAt、title、stage、label；段轨道 key 包含阶段，同一任务正文标签变化可刷新。长标题没有固定正文高度，查看进度和随机阅读控制均有44最小高度；这些是源码与 recording adapter 证据，尺寸和裁切仍需设备确认。

随机候选来自 `this.topics` 与 `this.sections` 当前投影，每个来源同显示列表一样只取前12条；不从 rawSections、历史库或尚未显示的第13条抽取。无 URL 条目经过生产 `discoveryHotspotInputs` 和 `discoveryArticleParams` 保留来源、排名、摘要，得到 discovery identity，不产生 sourceUrl/假链接。聚合条目保留全部真实来源与有效 seed URL。点击只导航到现有 Article，原缓存/生成语义继续由该入口负责；打开正在运行的卡只传原 topicId/title，不重写来源。

独立复跑：

| 测试 | 结果 | 日志 |
| --- | --- | --- |
| runtime scheduler progress、signal/start failure、structured pipeline | 24/24 | `/tmp/amber-p7-independent-progress.log` |
| actual Board lifecycle/random/navigation、actual Card builder、既有 History | 20/20 | `/tmp/amber-p7-independent-board-card-ui.log` |
| 既有真实 Board 热点 projection/cache/Wi-Fi/late response/多来源路径 | 7/7 | `/tmp/amber-p7-independent-hotlist-projection.log` |
| 独立 actual force Scheduler → production Board observer → actual Card builder | 1/1 | `/tmp/amber-p7-runtime-board-card-independent-final.log` |

最后一项独立组合反例持有来源收集：旧仓库是 COMPLETE，但实际 Card 高亮“采访”、显示当前 COLLECTING，未出现旧稿已保存提示；同任务 label 改变会改变真实 runKey；隐藏后停止接收运行阶段，重新显示取到完成后的空活动列表。

## 当前 P7 真机 Newsroom 判定

已独立读取 `device/p7/newsroom-start`、`newsroom-light-planning`、`newsroom-light-writing`、`newsroom-light-analysis-current`、`newsroom-completed-light` 的 JPEG 和实际 AX。

- 深色收集图高亮“采访”，标签为“收集来源”；浅色后续图高亮“撰稿”，标签依次为“概览”“时间轴叙事”“扩展阅读”。文件名 `light-planning`/`light-analysis-current` 是采集命名，实际截图不证明捕获了 PLANNING 或 ANALYSIS，报告按可见文字判定。
- 长英文标题完整显示四行。卡片 AX `[56,752][1264,1746]`；标题 `[113,893][1207,1193]`，四个阶段均为同一行 y1228–1319，宽度258且横向间距约21px。标题底部到轨道有35px间隔，文字没有侵入轨道，右侧付印单元没有溢出。浅暗色的卡片、标题、说明和查看动作左右对齐，卡片与热点内容有独立间距。
- “查看进度” AX `[113,1535][1207,1689]`，clickable/enabled=true，154px 对应该设备44vp热区。标题没有压缩该操作，底部安全区外没有被切断的动作。
- `newsroom-completed-light` 的截图与 AX 均已没有编辑室卡、阶段轨道和查看进度节点，热点列表正常补位；完成没有残留假活动状态。源码/held-save测试证明 COMPLETE 只在保存成功后发，设备图证明结束后的可见移除。

这些设备证据通过长标题、浅暗色、同任务阶段文字更新与完成移除的 UI 验收。root 所述本地 fixture 模型请求与完成结果属于受控设备链，不能推导真实云模型内容质量或所有系统版本布局。

## 当前 P7 真机随机阅读判定

已独立读取 `random-actual-article`、`random-actual-source`、`random-cancelled`、`random-return-board` 的 JPEG 和 AX，并结合此前 actual Board randomRead tests。

- 点击当前热点随机入口进入原 Article，显示当前捕获版式“杂志 · 立场”、真实收集阶段与可用取消动作。来源页是 Arxiv AI 当前热点 `Characterizing a Configuration Where Inference-Time PRM-Pruned Fragment Grafting Is Inert: Evidence from Three Reasoning LMs`，保存142字符的来源输入与 `https://arxiv.org/abs/2610.00047`，没有替换成预设演示主题。
- 来源长标题正常换行，URL、字符数、浏览器动作和查看正文均在卡片内有独立间隔。AX 来源标题 `[126,570][1194,818]`，URL `[126,923][1194,972]` clickable/enabled=true；没有长标题挤占后续操作的可见问题。
- 明确取消后 Article 显示“生成已取消”和可点击“继续生成”（AX `[70,1581][344,1735]`）；返回 Board 已没有活动卡，随机入口仍可点击（`[56,556][392,710]`）。取消与活动列表清理闭环通过。

此随机设备 gate 证明实际热点选择、来源入参、路由、取消与返回后的任务清理；该随机文章被主动取消，不宣称它的完整模型正文生成已验收。无 URL 条目的来源/排名保护由实际调用测试覆盖，不冒称这组有 URL 的设备图也验收了无 URL 云生成。

## 最后三行触控修复窄审

按 root 经 UI owner 传达的授权，只读复核 Compose 主题 TextInput `.height(44)` 与 `DeepReadConfirmationDialog.ets` 两动作 `.constraintSize({ minHeight: 44 })`。只增加尺寸约束；原 `.enabled(!this.busy)`、输入 onChange、同行 layoutWeight、padding、分隔线及确认/取消回调均保留。没有改变创建或重新生成调用链。

读取 owner 现有 `/tmp/amber-p7-final-touch-tests.log` 确认 focused31/31、fail0；scoped lint 日志/报告为0 errors及2条既有性能建议。按要求未为这三行再新增源码镜像测试。源码 gate CLOSED，新尺寸实际 AX 由 root 最终构建后验收；当前 Newsroom/随机截图属于该尺寸补丁之前的 P7 包，不能据此给新尺寸下最终结论。
