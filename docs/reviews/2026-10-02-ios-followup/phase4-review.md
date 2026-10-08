# Phase 4 独立 review

结论：本 phase 已达到范围内的闭环标准，没有未处理的具体逻辑、调用链或已观察 UI 缺陷。评审只写本报告，未改源码，也未操作共享设备。

## 范围与原位置投影

相对起始快照 `/tmp/amber-ios-followup-20261002-baseline` 评审 NovelWorkspacePage、AgentMessageRow、NovelSettingProposalCard、NovelChapterPlanProposalCard、NovelApprovalShell 与两份窄投影 helper，以及对应 focused tests。保护其余 WIP，没有将既有业务逻辑问题扩大为本轮改造。

- 设定 pending／accepted／rejected 均按 sourceMessageId 放在来源消息之后，兼容 NovelMessage.id 和 uiMessage.id，不靠标题或当前列表索引匹配。
- orphan 仅根据完整持久 messages 判定。活动运行的 history slice 不会把暂时隐藏的来源误判为 orphan；真实无来源的提案保留尾部入口。原序与每个 proposal ID 保留。
- 设置卡终态没有采用／忽略按钮。采用仍进入原 material adoption sheet，确认后 repository mutation 同时写材料和设定终态；忽略仅结案。采用失败保留原 pending 和既有错误入口，没有虚构 durable failed 设定记录。

## 计划回执与执行链

- 有效完整的单个本章计划路径才进入专用卡，所有 write／delete patches 按原序保留，包括重复操作、完整写入内容与空字符串写入。混合文件／分支、非法形状／坏 JSON 保留 generic 工具 UI；没有重写输入或产生新的批准目标。
- approved 且无 output 只显示“待执行”，不是已应用。只有真实工具回执的 accepted 且 proposal_id 与输入精确一致才显示已应用；显式不同 ID 的 accepted／rejected／failed 不能成为本卡终态。无 ID 的结构化失败与现有标准化工具错误协议一致，显示原错误消息；无法识别的结果保留原 output 和“结果待确认”。
- AgentMessageRow 的专用卡分支与 ChatMessageTools 互斥，因此只有一个审批入口。JevApprovalFacts 仍在该分支内，使用同 part 和同 locator 取 facts，未隐藏审计信息。
- 批准／拒绝传原 toolCallId 和 locatorFor 的原 message.parts 索引；沿 NovelWorkspacePage.continueNovelTool／resolveNovelToolId → NovelCreation.runToolContinuation／prepareWorkspaceWriteTool → 原 ToolPort.workspaceWriteResult → 同 proposal／固定 patches／CAS resolve 链执行，没有增加直接写计划入口。
- 终态专用卡不再暴露审批动作；失败详情和每个 patch 内容仍可展开，旧 generic fallback 及未识别 raw result 保留。原 tool output 持久结构未改变。

## UI 与已闭环发现

实际 UI agent 在初包发现“查看／收起”触区 127×154 px，即 36.3×44 vp，仅设置 minHeight44 导致宽度不足。实现者仅补 minWidth44，没有新增按钮、交互层或兜底。独立源码复核与最终浅／暗 JSON 实测均为154×154 px，即44×44 vp，bounds == origBounds；此具体问题已闭环。

独立实际读取以下截图，而非凭文件名或 AX checked 状态判断：

- `phase4-settings-receipts-light.jpeg`：已采用／已忽略仍紧随各自来源，长标题最多两行省略，右侧状态完整分离，没有动作或标题碰撞。
- `phase4-dark-sequence-1.jpeg`：pending 有单一批准／拒绝卡；approved-but-unexecuted 实际为待执行且显示等待说明，未误报已应用。
- `phase4-plan-accepted-rejected-light.jpeg` 与 `phase4-plan-completed-dark.jpeg`：accepted 已应用，rejected 已拒绝，failed 未应用；完成卡保留查看，没有重复 generic 审批卡。
- `phase4-final-touch-full-light.jpeg`／`phase4-final-touch-full-dark.jpeg`：失败错误、展开的完整计划内容和 plan/this-chapter.md 路径均实际可达，底部卡片与 composer 分离，浅暗正文和错误颜色可读。对应 JSON 的查看／收起均是完整154×154 px触区。
- `phase4-setting-adopted-reopen.jpeg`：本地真实采用并强制退出／重开后，同来源保持已采用且无动作；`phase4-adopted-world-material-dark.jpeg` 显示实际新增世界材料。
- `phase4-local-ignored-reopen.jpeg`：真实忽略并重开后，短人物设定已忽略，旁边世界设定仍 pending，状态没有串到其他提案。

以上画面没有识别到新的具体错位、对齐、边距、间距或大小缺陷。长列表跨屏裁切遵循原滚动视口，已实际滚至完整详情，不把初始视口没有包含末尾内容当成缺陷。

## 独立验证与证据边界

独立执行，全部 GREEN，合计27/27：

- chat `novel_plan_proposal.test.cjs`（5）＋`novel_setting_proposal_projection.test.cjs`（3）＝投影8/8。
- chat `message_row_blocks_cache.test.cjs`＝4/4；用项目 tsx loader 启动以解析 domain alias。首次 plain node 运行的 alias 解析失败已更正为正确启动方式，不是产品失败。
- deepread `novel_c5_tool_continuation.test.ts`（4）＋`novel_material_adoption.test.ts`（7）＋`novel_adoption_branch_notification.test.ts`（4）＝原执行／材料／分支回归15/15。

主 agent 的真实 ArkTS build 暴露 builder 内 nullable project 收窄问题，已修成方法内读取；本次独立 source review 确认窄修改，最终 build3／sign3／安装由主 agent 串行完成，未在本 review 重跑共享构建。

已读取 UI operator 报告 `/tmp/amber-followup-phase4-ui-acceptance.md`：这是 Pura90 simulator target127.0.0.1:5555 的真实产品 UI，不是物理设备或云端 Provider 验收。备份经过现有 native 导入入口创建独立项目；本地采用／忽略与重开真实执行，计划状态来自实际 repository fixture／工具回执，未点击计划批准／续跑或发 Provider 请求。截图里的未配置模型错误属于故意断开 Provider 的 fixture，不能据其声称云端链路测试成功。本 review 源码＋domain tests证明原执行接线，UI证明相应持久状态／呈现／触区，不合并这两种证据为云端实测。

状态：Phase 4 scope review 通过，可以推进 Phase 5。
