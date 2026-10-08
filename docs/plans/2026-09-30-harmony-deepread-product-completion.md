# 鸿蒙深度阅读产品收口计划

日期：2026-09-30。执行根：`amber-harmony-preview/harmony/`。iOS 仅作为只读功能参照。

## 完成契约

以真实可达功能闭环为标准，不移植已隐藏或仅用于历史兼容的 iOS 代码。不为差异新增第二套调度、版本库、状态真相或无用兜底。

1. 无有效资料不冒充研究成功；重生成失败、取消、写入失败保留旧稿。
2. 每轮使用一致的模型、凭据、规则与来源快照；设置变更影响下一轮，已有运行不被改写。
3. 所有现有版式保留正文、来源、图像、图解与恢复操作；字体、字号、主题有真实消费者。
4. 热榜过滤、刷新、聚合来源、继续卡片与通知表达真实状态。
5. 深读复用现有聊天 Provider/搜索、后台保活与 Workspace；结果可文本/Markdown/PDF 导出。
6. 每个 phase 必须有独立 subagent 逻辑/调用链/UI review，发现问题精准修复后复核；静态、构建、签名、设备证据分别记录。

## 基线与保护

- 当前分支 `harmony-preview`；存在其它聊天/渲染/议会工作。完整状态、二进制 diff 与 217 个既有改动路径已备份到 `.workflow/harmony-deepread-product-completion/baseline/`。
- 不 reset/stash/清理，不覆盖其它 agent 工作，不整仓暂存。修改已有脏文件时只接入深读相关位置。
- 最终 UI 验收仅使用现有真机 `6HK0226627000384`；不清设备数据，不启动、新建或重启模拟器。保持 AGC 真机签名。
- 鸿蒙 `harmony-arkts` skill、API 12、V1 状态、官方文档先查、主题纪元机制保持。

## 阶段与验收

| Phase | 内容 | 主要边界 | 可验证完成条件 |
|---|---|---|---|
| P1 可信结果与运行快照 | 无来源失败；重生成保留旧稿；保存等待真实结果；取消/迟到回调隔离；model/Playbook 每轮快照 | deepread run_manager/scheduler + DI/repository adapter | 聚焦测试复现并覆盖无来源/force失败/取消/写入失败/配置更新；已保存稿不丢、失败不通知完成 |
| P2 热榜与搜索闭环 | 聚合所有种子来源；真实源开关/关键词刷新；失败保留榜单；缓存新鲜度/下拉；继续卡状态；统一搜索配置、修补搜假承诺和图片提取 | BoardPage/HotListAggregator/NewsNow/SearchRegistry/SettingDeepRead/continue | 改设置返回即生效；禁用源不发请求；多源进入研究；空/错误/旧缓存状态真实；必要源与查询角度不被截掉 |
| P3 阅读与模板完整性 | 原生/Web 内容完整；简单可读图解；共用恢复/错误/重生成控制；字体字号主题；模板保存错误/边界约束 | DeepReadArticle + 小组件/DeepReadTemplate/Workbench | 各版式展示同一文章关键内容，设置实际生效；亮暗/长标题/长文/空字段/部分失败布局无错位；模板不吞保存错误 |
| P4 Provider、后台与产物 | 复用已有聊天 Provider 执行通道；每轮认证/配置快照；登记既有后台活动；状态及通知；Workspace 自动存和独立重试 | 专用 DeepRead runtime support + AppContainer/Article | 当前支持的协议走原有执行器；离页/后台/取消/重入所有权正确；结果保存后通知；Workspace失败不丢文章 |
| P5 结果分享与导出 | 文本/Markdown系统分享；PDF正文完整导出；导出错误及忙状态 | 专用导出模块 + Article小入口 | 用已保存结果生成实际文件、分享面板可达；真实长文PDF可读与分页；不另建模板互通/AI修订系统 |
| P6 集成验收与交付 | 两域测试/类型检查、改动lint、最终构建与签名、现有真机关键路径验收、独立最终review | 当前任务改动集合 | 各phase审查阻断问题关闭；交付记录真实源码/测试/构建/签名/安装/设备/UI/Provider证据及未测边界 |

阶段串行推进；同阶段只并行互不冲突的实现责任。每阶段：实施 → 针对性测试 → 独立 review(A逻辑、B调用链、C UI几何) → 修复 → lint/build/sign/设备关键路径 → 记录结果 → 下一阶段。非 UI 阶段设备检查只验证受影响恢复/错误路径，不反复全仓巡检。

执行核实补充：PDF 直接生成最低 API 14；API 12/13 提供完整文本与 Markdown，并准确提示 PDF 系统能力限制。采用系统 Web 分页，不为旧系统新增排版引擎。当前并行流式验证会临时替换 Entry 首页及占用设备；保留其改动，源码审查/测试/构建可继续，受影响设备 UI 验收在正式 Entry 恢复后集中补齐并记录，最终不能把这层标为已通过。

## 范围裁定

最新用户约束：不要乱开模拟器。自此不再启动或新建任何模拟器，关闭本任务新开的实例并保留其数据。UI 验收使用现有真机窗口，避开并行任务占用；实现与独立审查继续。

执行环境补充：P2 独立审查问题关闭后，模拟器系统冻结/失联，另一已授权任务同时占用真机与构建输出。源码阶段继续推进，受影响的阶段构建、签名及视觉检查保留为 pending，并在环境恢复后补齐；不将其算作通过，不为此覆盖其它验收任务或清设备数据。

纳入：上述必要闭环及活跃结果导出。热榜源按科技/AI阅读价值补充，不以数量达到 iOS 的20源为目标；翻译及 Wi-Fi门控随实际新源/刷新路径实现。已有基础能力保留。

不纳入：隐藏的自定义多来源表单、旧线索看板/WebMount入库未闭环路径、`ios_analysis`历史版式、旧Provider helper、模板全schema互通、AI修订对话、模板文件导出、全局durable队列/无损跨进程自动续写、逐像素复制iOS。

## 审查纪律与证据

- 审查者不替实现者兜底：以具体调用点/状态转移/消费者/截图和bounds提出问题，避免推测性扩建。
- 新API查 `harmony/scripts/devecocli.sh docs search/read`；本地缺失才查华为官方文档。
- 日志与截图落 `.workflow/harmony-deepread-product-completion/`；阶段报告落 `results/Pn-*.md`，最终报告注明实际已验证及未验证层。
- 不把旧全量测试、编译通过、已提交系统任务当作当前真实Provider/后台/UI验收。

## P6 定点发现与收口

2026-10-01，实际真机资料不足失败触发了真实平台消费者复验：预取取消监听丢失 receiver，会将正常 200 来源吞成空；预算结束后底层 HTTP 尚未 destroy。先固化真实 EntryAbortSignal/RcpHttpClient 的 RED，再以局部 collect child controller 修复，不延长预算、不增加重试。另核实热榜话题面板系统 Back 未消费，须先关闭面板再允许页面返回。以上问题关闭并重建安装后，继续正文、后台、Workspace 与导出验收，先前包不能替代最终验收包。

## P1–P6 验收收口（2026-10-01）

各phase必要实现、独立逻辑/调用链/UI review及对应实际验收已完成，[最后独立交付核对](/Users/arquiel/Downloads/AI/amber-harmony-preview/.workflow/harmony-deepread-product-completion/results/P6-delivery-review.md)通过，没有完成契约内的未闭阻断。最终证据见[P6集成验证](/Users/arquiel/Downloads/AI/amber-harmony-preview/.workflow/harmony-deepread-product-completion/results/P6-validation.md)与[90项对齐矩阵](/Users/arquiel/Downloads/AI/amber-harmony-preview/docs/reviews/2026-09-30-harmony-deepread-alignment.md)，状态文件在`.workflow/harmony-deepread-product-completion/state.json`。

| Phase | 完成结果 |
|---|---|
| P1 | 无有效资料准确失败；每轮快照、await保存、force失败/取消原子保留旧稿与迟到回调隔离；最终215/218实际取消和重开完整旧稿 |
| P2 | 全seed、源开关/关注/刷新/Wi-Fi/显示翻译实际消费者；原文聚合不混不同事件，真实11源118条反例关闭；Board数字Back及最终Home真实running卡闭环 |
| P3 | native/Web关键信息完整、首mount、字体字号/主题/恢复和工坊保存边界；真实长文、键盘、触区、菜单越界与暗色文字定点修复并独立复核 |
| P4 | 复用Chat协议执行器及当轮认证配置；真实Provider严格完成、Workspace自动/手动保存、后台force同PID完成、取消/重入所有权、普通通知冷暖点击和Home当前任务正确续接 |
| P5 | 完整文本/Markdown/PDF系统导出；准确能力门、真实长文3页A4/13链接/中文与单阅读项分页完整，picker取消/保存及面板关闭闭环 |
| P6 | 域980/980、Entry181/181、共享Chat2413/2413及严格类型检查；scoped lint、AGC构建签名/安装、源码728文件0drift；最终862b0516无诊断包实际212–218关键路径通过 |

P6新反例按实际证据局部修复：Web初始blank覆盖loadData、PDF data结束事件与虚拟historyURL不同、系统通知同步401、菜单/展开页头/placeholder/颜色及尺寸、冷通知在页面渲染前经全局Router派发、Home聚合乱序与Builder按值捕获旧item。每项均经聚焦复现、修复与独立review；没有新增等待轮询、无用fallback、第二套状态或模板互通系统。

公开长时任务content8布局在本设备不可用，已有后台完成和普通通知证据；其他真实OAuth账号、移动网络及所有复杂媒体组合未逐项实测，公共Download哈希未越权读回。以上验收边界保留，不用它们扩建本轮范围。API12/13的PDF能力限制及历史/可选功能差异按既定裁定保持。
