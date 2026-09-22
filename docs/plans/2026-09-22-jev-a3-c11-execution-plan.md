# Jev A3/C11 Android 落地执行计划（2026-09-22）

> **状态：已全部落地（2026-09-22）。** Phase A：JevPolicy 逐用途阈值收编（版本仍为 2）+ `JevCalibrationRecord/Store`（core 接口 + app JSONL 有界落盘 1000 条）+ 四个 Noul 重排适配器「收集判分→完整评估落记录→active 才应用」+ 设置页校准行（计数/导出/清除，6 语言）。Phase B：`app/src/test/resources/jev-corpus/`（tools 102 条真实目录 / memories 42 条 / screens 4 屏）+ 三个 baseline 冻结测试（词面候选可及性、语义回放、信封预算、危险动作排除）。两轮 checker 审查（Phase A：0 P0/1 P1 已修 IO 静默兜底；Phase B：0 P0/2 P1 已修常量引用与 type 断言）+ 不变量测试补齐（stale/chunk 失败不落记录、多 chunk 聚合）。`:core:jev:test` + app 全部 jev 测试 + `:app:assembleDebug` 全绿。未提交（用户未要求）。

对照 iOS 会话 sess_f8b29e85 的评分表，按同一框架（满分 100，≥60 可做、≥80 优先）给 Android 重新打分后的落地计划。完整评分与 Android 现状勘察结论见会话；本文件只留执行所需。

## 评分结论（Android 版）

| 编号 | 建议 | iOS | Android | 裁决 |
|---|---|---|---|---|
| A3 | 置信度逐用途校准/弃权 | 90 | 90 | **Phase A** |
| C11 | 离线 baseline 语料库 | 88 | 88 | **Phase B** |
| A1 | wm_run_goal 接线 | 85 | — | 已存在（Capability.kt:181 + 7e71e5b 引导 + WebMountGoalTool 强审批），不做 |
| A2 | state 注入屏（顺路批量） | 68 | 66 | 顺路做——但注入查询是新增 Jev 出站题，Key blocked 期间无 shadow 流量可验证，推迟到有真实流量后再议 |
| B4 | 子任务意图路由 | 72 | 58 | 挂起（无子代理池，路由目标退化两档；MODEL_ROUTING 已覆盖议会席次） |
| B6 | 审批分诊 | 62 | 58 | 挂起（审批面更窄更严，收益密度低） |
| B9/B7/B5/C10 | 58/55/48/45 | 55/52/48/45 | 挂起 | |
| B8 | 压缩选 turn | 毙 | 毙 | CONTEXT_SELECTION 已是块级投影（JevToolOutputProjector），调参即可 |

## Phase A：A3 置信校准基建

Android 现状：六用途各有独立硬编码阈值 + 三种弃权路径（rerank 回 NOT_APPLIED、web low_confidence handback、screen needs_user_action），缺的是校准证据链——阈值是编译期 const，无对账记录、无落盘。

**A-1 `JevPolicy`（core/jev 新文件）**：逐用途阈值收编为 data class，字段值 = 现值不变（0.5/0.5/0.35/0.3/web 0.5·0.6·0.5/screen 0.8·0.85），`policyVersion` 从 `JevRuntime.POLICY_VERSION`（=2）迁入，语义扩展为「含阈值」，本次数值不变故版本号不升。六个适配器从 `runtime.policy` 读阈值，删除各自 companion const（无外部引用，已核）。校准后更新阈值时递增版本 → 缓存键失效（现有机制）。

**A-2 校准记录（core/jev 接口 + app 落盘）**：`JevCalibrationRecord`（timestamp/purpose/mode/model/latencyMs/threshold/scores/incumbentTop1/jevTop1）。只含候选 id 与概率，不含任务文本、记忆原文、工具输出。`incumbentTop1` = 原路径既任首选（输入序首位：词面序/轮转序），`jevTop1` = 过阈值最高分候选，null 即弃权。`JevCalibrationStore` 接口照 `JevUsageStore` 模式（core 零 Android 依赖，IN_MEMORY 供测试）；app 层 `FileJevCalibrationStore` JSONL 落 filesDir，有界 1000 条、超限重写保留最新 500。

**记录点**：四个 Noul 重排适配器（TOOL_DISCOVERY / MEMORY_RECALL / MODEL_ROUTING / CONTEXT_SELECTION）。重构为「收集判分 →（完整评估才）落记录 → active 才应用排序」：shadow 照旧不应用但落记录（mode=SHADOW），这正是校准数据的主要来源；任一 chunk 失败/过期视为评估不完整，不落记录。

**显式不做**：两个快循环 runner 不另写校准记录——screen 结果已带 safe_probability/jev_latency_ms 步迹、web 已带完整决策轨迹，再落一份是重复；且 screen 阈值已有真机探针数据（0.84–0.88）。协调器与 JevMetrics 不动（对账不进内存环形展示层）。

**A-3 设置页**：Jev 状态卡组加一行「校准记录 N 条」+ 导出（ACTION_SEND text/plain，同 ShareSheet 的 chooser 模式）+ 清除。文件读写在 Dispatchers.IO。新增 4 条字符串 × 6 语言。

**验收**：定点 JVM 测试（council shadow 落记录、policy 阈值收紧 → 弃权、FileJevCalibrationStore 有界/清除）+ `:core:jev:testDebugUnitTest` 全绿。

## Phase B：C11 离线 baseline 语料库

Key 阻塞的只是对照实验的 Jev 半边；baseline 半边零风险解锁后续校准。

**B-1 语料**（app/src/test/resources/jev-corpus/）：tools.json（≥40 条，id/name/category/description，从真实能力清单提取落地）、memories.json（≥40 条，id/content/kind/scope）。附任务集：每条 query 带词面序（incumbent）与人工标注的相关集；tuning 与 frozen 两段互斥。

**B-2 baseline 冻结测试** `JevBaselineCorpusTest`：语料加载与规模断言；信封构造检查（64 候选最坏情况下 state 字节 ≤ MAX_STATE_BYTES、分题 ≤ 32/请求）；词面 baseline 的冻结期望（incumbent 序 + 阈值裁剪后的期望弃权/排序）。为 A3 提供离线回归：未来改 state 组装/分块/阈值时，语料上的行为变化全部显性化。

**B-3 screen 语料**：镜像 androidTest `JevScreenFixtureActivity`（3 页评论列表 + 危险按钮）为 JSON 节点表，供 `JevScreenGoalRunner` 离线调 `READ_ONLY_THRESHOLD`。

**验收**：新增测试全绿；不改生产代码（除非测试暴露适配器 bug——那也是本 phase 的收益）。

## 全局约束

- 每 phase 收尾派 subagent 对抗审查：逻辑闭环、调用链完整、UI 错位/对齐/边距/大小；精准修复，不过度防御/兜底/设计。
- 定点 JVM 测试优先，收尾跑 app compileDebugKotlin + 全量 jev 测试。
- 构建环境：JAVA_HOME=homebrew openjdk@21，`-Dkotlin.daemon.jvm.options=-Xmx4g`。
- 不提交推送（用户未要求）；工作树当前 clean，改动自成一组。
