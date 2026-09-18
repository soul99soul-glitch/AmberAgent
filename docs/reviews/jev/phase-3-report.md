# Phase 3 — 模型调度与网页自动化（Jev Android）

代码状态：完成
离线检查：passed（JVM 定点测试全绿）
真实 API：blocked（无 Key）
用途模式：MODEL_ROUTING=off、WEB_AUTOMATION=off（默认）
基线：同 Phase 1 工作区

## 3a 模型调度（议会池）

- 按定稿裁决 D-3a：只挂议会默认席次路径。`ModelCouncilValidator.parseTask` 增可选 `rankedPool`（过滤⊆合法池且必须 CHAT；空回退原轮转）；`ModelCouncilManager` 仅在 `seat_strategy=default` 且无显式 `seats` 时调用排序器——显式 seats/agent_planned 是模型/用户的明确选择，不覆盖。
- `JevCouncilPoolRanker`（app 层实现 feature/modelcouncil 的 `CouncilPoolRanker` 接口，保持模块边界）：按任务适配度对池排序（<2 个合格回退 null）；runKey 不穿透三层——仅日预算约束（记录在案）。
- 价格元数据仓库不存在（Model 无价格字段），全部按 unknown 处理，不做成本推断。

## 3b 网页自动化 wm_run_goal

- 新实验工具 `wm_run_goal`（mandatoryApproval，逐次审批）：输入 session/goal/texts（文本值只由主模型提供）/max_steps≤6/max_duration_ms≤15000/dry_run；输出 status∈completed|handback|needs_user_action|shadow_trace|steps_exhausted|budget_exhausted|outcome_unknown|disabled + 步骤轨迹 + 最终状态。
- 每轮 `semantic_state` 观察 + interactive extract（≤24 元素，ref 通用解析）；Jev Choice 选动作（observe/scroll/back/click/type/done/handback）+ 逐元素 Noul 选目标（≥0.5）+ DONE 独立 noul 核验（≥0.6，未核验 handback）；网页动作不缓存。
- 执行经 `runVerifiedAction`：快照校验、回执（dispatched/status/snapshot_id_after/page_changed）、dialog→needs_user_action、unknown→outcome_unknown 且不重放。无进展 3 次终止；dry-run 与 shadow 只产 shadow_trace 不执行任何动作。
- **v1 权限解释（记录在案）**：快循环动作限定读取/滚动/后退/点击/受控输入低风险档；外层工具整体强制逐次审批；提交/支付/发布/账号/验证码不在快循环内（无对应动作选项，一律 handback 回主模型原流程）。内层动作不再单独走 dispatcher 审批——这是低风险档的显式取舍，工具描述向模型声明了范围。

## 已执行检查

- `JevWebGoalRunnerTest` 7 通过：完成核验/未核验回退/unknown 不重放/shadow 仅轨迹/步数耗尽/人工接管立即终止/off 零网络。
- `JevCouncilPoolRankerTest` 5 通过：active 重排/阴影 null/传输失败 null/空目标短路/<2 合格回退。
- 回归：`ModelCouncilManagerTest` 2、`CouncilRoomManagerTest` 7、`CouncilRoomPromptsTest` 15 全绿（parseSeats 向后兼容）。
- `:app:compileDebugKotlin` 成功。

## 对照结果

无 Key/真机，端到端收益对照 blocked。离线验证了控制流不变式（unknown 不重放、shadow 零执行、审批门在工具层）。

## 阻塞、回退与下一步

- 两用途关闭即回原路径；议会轮转与 wm_* 原语完全保留。
- 真机清单：弱网 1200ms p50/p95、锁屏/后台取消、快循环真实页面操作（先受控本地页）。
