# AmberAgent 文档地图

这是一张入口地图，不是第二份项目状态。接手任务时先读仓库根 `AGENTS.md` 和 `PROJECT_STATE.md`，再按主题选择下列最少文档。

## 权威顺序

1. 真实代码、测试、Git 状态和运行证据
2. 当前目录链上的 `AGENTS.md`
3. [`PROJECT_STATE.md`](PROJECT_STATE.md)
4. 已接受的产品规格与 ADR
5. 标为 Active/Paused 的计划
6. 日期化审计、评估和已完成计划（仅作历史证据）

旧 handoff、session snapshot、一次性 prompt 和本地绝对路径不属于当前入口。需要追溯时使用 Git 历史。

## 快速入口

| 主题 | 先读 | 说明 |
| --- | --- | --- |
| 当前分支与最近工作 | [`PROJECT_STATE.md`](PROJECT_STATE.md) | 当前事实、验证、风险和下一切口 |
| 全仓工程规则 | [`../AGENTS.md`](../AGENTS.md) | 启动、状态链路审计、验证与收尾 |
| 原生 iOS | [`../iosApp/AGENTS.md`](../iosApp/AGENTS.md) | Chat、provider、后台、真机门禁 |
| 小说创作 | [`../iosApp/iosApp/NovelCreation/AGENTS.md`](../iosApp/iosApp/NovelCreation/AGENTS.md) | 局部所有权、终态、输入法与测试契约 |
| 小说产品契约 | [`NOVEL_CREATION_SPEC.md`](NOVEL_CREATION_SPEC.md) | 用户可见行为与领域边界 |
| 小说领域语言 | [`../CONTEXT.md`](../CONTEXT.md) | 项目、分支、章节、候选和资料词汇 |
| 小说状态所有权 | [`adr/0007-novel-creation-owns-project-state.md`](adr/0007-novel-creation-owns-project-state.md) | 小说项目为何是权威来源 |
| 小说实现基线 | [`NOVEL_CREATION_IMPLEMENTATION_PLAN.md`](NOVEL_CREATION_IMPLEMENTATION_PLAN.md) | 已完成架构和仍受 schema 约束的工作 |
| Live Activity 视觉 | [`ACTIVITY_ISLAND_REDESIGN.md`](ACTIVITY_ISLAND_REDESIGN.md) | 灵动岛/锁屏视觉规范 |
| iOS 首页 / Liquid Glass 调研建议 | [`IOS_HOME_DESIGN_RESEARCH_AND_RECOMMENDATIONS.md`](IOS_HOME_DESIGN_RESEARCH_AND_RECOMMENDATIONS.md) | 首页诊断、蓝图与参考仓库/Skills（Proposed） |
| Amber Soul / MCP 最小闭环 | [`SOUL_MCP_MINIMAL_CLOSURE_PLAN.md`](SOUL_MCP_MINIMAL_CLOSURE_PLAN.md) | 在既有 Skill 安全发布上补 Soul 自更新与 MCP 受控接入（Completed） |
| iOS Liquid Glass 设计系统稿 | [`ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md`](ios-port/IOS_LIQUID_GLASS_DESIGN_SYSTEM.md) | 玻璃/原生结构原则；与首页调研配套 |
| iOS 终端能力 | [`ios-terminal-runtime.md`](ios-terminal-runtime.md) | iSH/终端运行时边界 |
| Android Agent Kernel 历史基线 | [`architecture.md`](architecture.md) | 仅解释 Android 模块化背景，不代表当前 iOS 运行时 |

## 仍开放但不是默认任务

- [`IOS_AGENT_PROVIDER_CONFIG_PLAN.md`](IOS_AGENT_PROVIDER_CONFIG_PLAN.md)：Agent 受控读写 provider/model 配置（先手配一个 → status/apply/refresh/set_model_slot）；P0 只读脱敏、P1 审批写 key、P2 拉模型与槽位；Active Proposed，实施前按文件内威胁模型与复核清单核对实时代码。
- [`IOS_DEEPREAD_PARITY_PLAN.md`](IOS_DEEPREAD_PARITY_PLAN.md)：iOS 深度阅读对齐 Android（P0 阶段鲁棒性、P1 规划段/证据分桶/quotes/门闩/references、P2 单段重试与超时均已完成；P3 工具型 agent loop 为决策门）；Active，实施 P3 前按文件内开门条件与实时代码复核。
- [`superpowers/plans/2026-08-10-novel-session-streaming-parity.md`](superpowers/plans/2026-08-10-novel-session-streaming-parity.md)：小说会话流式对齐 Chat（终态排空、思考呈现、列表/滚动、污染防火墙）；Active Proposed，实施前按文件内成功标准与禁区复核实时代码。
- [`AGENT_ORCHESTRATION_ADOPTION_PLAN.md`](AGENT_ORCHESTRATION_ADOPTION_PLAN.md)：借鉴 codex 编排体系的 P0–P3 Proposed 计划（tool_search → 线程 mailbox → 记忆 polluted/citation → code mode）；含 2026-08-08 四路代码复核事实与开工前复核清单，实施前须按清单重新核对实时代码。
- [`IOS_THEME_SYSTEM_ADVANCEMENT_PLAN.md`](IOS_THEME_SYSTEM_ADVANCEMENT_PLAN.md)：iOS 主题系统完善计划（P0–P3 Active 完成；P4 资源包/沉浸色需产品闸门）；每个 Phase 下笔前须按文件内复核清单重核实时代码。设计契约见 [`IOS_THEME_PACK_DESIGN_SPEC.md`](IOS_THEME_PACK_DESIGN_SPEC.md)。
- [`NOVEL_COCREATION_GHOSTWRITE_PLAN.md`](NOVEL_COCREATION_GHOSTWRITE_PLAN.md)：共创 / 代笔双模式 Proposed 计划；含共创补洞与代笔自动收录 MVP，实施前需再核对实时代码。
- [`NOVEL_SESSION_MEMORY_PLAN_2026-07-19.md`](NOVEL_SESSION_MEMORY_PLAN_2026-07-19.md)：S1-S3 已完成，S4-S7 暂停，需真实 provider/真机证据后再决定。
- [`IOS_AGENT_HARDENING_PLAN_2026-07-29.md`](IOS_AGENT_HARDENING_PLAN_2026-07-29.md)：iOS Agent 不变量补强设计稿；开始前必须重新核对当前代码是否已部分落地。
- [`NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md`](NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md)：跨仓 Android 草案，不得在本 iOS 仓直接开工。

其余日期化计划、审计、评估和 `.workflow/` 结果默认视为历史材料。只有当前任务明确命中其主题时再读取，并以实时代码复核。
