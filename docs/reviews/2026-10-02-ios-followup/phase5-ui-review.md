# Phase5 实际 UI 验收

验收对象：最新构建、签名并覆盖安装的 Harmony 包；Pura90 模拟器 `127.0.0.1:5555`，1320×2856 px，3.5 px/vp。使用既有备份恢复、聊天、审批与工作区 UI；未改产品、未新增测试、未调用付费 Provider。这里只记录实际界面与操作证据，算法和调用链见 A/B/C 独立 review。

## 结果

| 项目 | 实际操作与结果 | 证据 |
| --- | --- | --- |
| 正常完成链路 | 恢复同一自有空会话，发送固定请求；原审批各批准一次 `file_write`、`file_edit`；两工具成功后最终回答完整保留，底部出现完成校验提示 | `/tmp/amber-followup-ui/phase5-completion-light.{jpeg,json}` |
| 完成提示浅/暗布局 | 深色下只重放同一自有链一次，未扩用例；两主题提示文案完整换行，按钮和关闭控制不重叠，位于输入区上方 | `phase5-completion-light.jpeg`、`phase5-completion-dark-live.jpeg` |
| 验证按钮触区 | 两主题 AX 均为 `[900,2366][1054,2520]`，154×154 px，即 44×44 vp；输入区和提示区有明确间隔 | `phase5-completed.json`、`phase5-dark-completed2.json` |
| 已有草稿操作 | 深色完成提示仍在时输入 `OWN_PHASE5_DRAFT_ac181afc`，点击验证；原草稿完整保留，追加两换行与既定验证请求，没有发送新消息 | `phase5-draft-appended.{jpeg,json}` |
| 四用途设置 | 工具结果筛选、工具结果保留、自动批准复核均关，完成校验主动；权限文案与右侧允许状态对齐，长说明换行，浅/暗卡片边距一致；没有改模式或权限 | `phase5-jev-settings-light-0/1.jpeg`、`phase5-settings-dark-1/2.jpeg` |
| 审批原因 | 原生 pending 记录包含直接工具和 recipe checkpoint 两卡，浅/暗均完整显示“自动批准复核：破坏性、外发数据、超出任务范围”；原因左起 32 vp，与工具图标区错开；右侧审批触区没有挤占原因。recipe 长参数沿用既有省略显示 | `phase5-risk-light.{jpeg,json}`、`phase5-risk-dark.{jpeg,json}` |

未发现需要追加产品修改的错位、对齐、边距、大小或暗色对比度问题。离开 ChatPage 后完成提示消失符合 volatile 生命周期；为实看暗色提示及草稿追加，仅经 root 授权重放同一自有链一次。

主链本地协议计数：浅色 `chat=3`、`auxiliary=2`（其中 memoryExtraction=1）、`jev=4`、`rejected=0`；深色相同成功计数且 `rejected=1`。两轮各有 `toolWriteOutputVerified=1`、`toolEditOutputVerified=1`、`completionQuestionCalls=2`。深色的额外拒绝计数未从现有计数确定来源，不将其描述为零；它没有阻止三次主调用与实际完成提示。记录分别为 `/tmp/amber-phase5-final-light-run-metrics.json` 和 `/tmp/amber-phase5-ui-UGM2Gx/request-counts.json`。mock 已由 root 精确停止。此证据验证本地产品链，不验证付费模型质量。

## 精确清理与交回

- 经正常会话列表删除 Phase5 主自有会话（其草稿随会话清理）、审批布局会话和 Phase3 `IOSFollowup-Artifacts-1002` 会话。
- 正常 USER 消息长按 → `查看 Workspace 文件` → 唯一自有 91B `.ts` 长按 → 删除确认，精确删除 `notes/phase5-ui-ac181afc-2ea7-48f9-9174-8db2723e6a81.ts`。仅为进入该 UI 重导过同一审批布局会话，之后再次删除；未批准其两张 pending 卡，未发送或重新生成。
- 小说项目列表正常软删除 `IOSFollowup-UI-1002` 和 `IOSFollowup-Phase4-审批结果`；它们仍属于既有“恢复删除的项目”范围，未声称物理删除。原用户小说保留。
- 依 root 先前指示，曾短暂打开原 `NovelPhaseCheck-0930` 后立即返回，未手动生成、审批或编辑；root 后续收紧为禁止进入原项目后未再打开。没有以此声称其后台 jobs 已验收。
- 外观已恢复浅色；生成式 widgets 未改，保持此前 ON。设备停在主页交回 root。公开 fixture 文件、synthetic provider/assistant/Jev 的 fresh-current 精确恢复及 reverse 清理由 root 处理，本 agent 未改原用户配置。

所有截图和 AX 均在 `/tmp/amber-followup-ui/`。本次未新增测试或产品代码。
