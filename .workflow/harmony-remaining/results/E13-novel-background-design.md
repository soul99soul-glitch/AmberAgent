# E13 小说专用卡与后台进度预查

2026-09-28；只读实施设计。已读取 harmony-arkts skill，API 基线 12、ArkUI V1。没有改产品、state/index，没有测试、构建、签名或设备操作；E13 尚待 root 正式阶段授权。

## 已核实的最小范围

小说卡直接消费当前已闭环的事实：代笔 `GhostwriteReview` 是候选章节裁定；`novel_workspace_write` 中本章计划 patch 是待作者批准的章节计划提案。没有必要迁入 iOS 的另一套 structured-output、mutation 或审批协议。

后台深化是申请 API 12 长时任务后取得系统通知 ID，用同 ID 更新真实传输进度，并把生命周期和聚合活动运行接起来。“超过十分钟续约”不是每十分钟停掉重启任务，也不是调用 `updateBackgroundRunning`。官方约束是 DATA_TRANSFER 进度长时间未更新会被取消；不能承诺无传输进展的无限后台运行。

## 小说真实调用链与 UI 接点

| 事实 | 当前源锚点 | 最小专用展示 |
| --- | --- | --- |
| 候选、冻结计划与审核绑定 | `deepread/.../novel/ghostwrite.ts:269` 的 `validateGhostwriteReview` 校验 candidateId/candidateDigest/planId/planDigest；`:470` 的 `applyGhostwriteReview` 从 reviewing 转 failed/rewriting/committing | “章节裁决”显示第几章、第几稿、通过/阻断/需重写、必写遗漏/禁写违反/连续性硬伤/提示，以及发现位置、重写说明。依据 durable 数据判定，不能扫正文关键词猜状态 |
| 真实审核与提交 | `ghostwrite_runner.ts:281` reviewer 调用→parseReview→repository.saveGhostwriteReview；`:304` `commitGhostwriteChapter`；`repository.ts:2131` 提交前再次校验绑定 | 专用卡是现有裁定的展示，不增加“强制通过/收录”按钮；原 pause/resume/retry/cancel 行为不变 |
| 已有审稿 UI | `components/NovelGhostwriteCard.ets:168` reviewSection 已显示 review 摘要/发现/状态增量，但多处限行省略 | 提取小 `NovelChapterVerdictCard.ets` 替换该段，展开时完整可读；继续用原候选和冻结计划事实。不要另开轮询/重新请求模型 |
| 计划工具与批准 | `chat/.../novel_workspace_tools.ts:150` `novel_workspace_write`，输入 `{proposal_id,patches:[{operation,path,content}]}`；needsApproval=true/allowsAutoApproval=false | 从明确工具名和结构识别“章节计划提案”，显示 proposal_id、写入/清空语义和完整计划内容；不根据“计划”二字或 fenced JSON 猜协议 |
| 计划写回 | `deepread/.../novel/creation.ts:1055` prepareWorkspaceWriteTool→`:1133` normalizeWorkspaceProposalPatches；真实执行→`:597` workspaceWriteResult→`:615` resolveWorkspaceProposal；拒绝→`:1080` resolvePreparedWorkspaceWrite(false) | 复用当前 approved/denied continuation，不直接写 branchSettings、不另存一份 UI 提案状态。现有 CAS、固定 patches、幂等 commandId 和 receipt 保持 |
| 公共 UI 回调 | `components/AgentMessageRow.ets:370` tool 分支→ChatMessageTools；`pages/NovelWorkspacePage.ets:1490` 和 `:1544` 传批准/拒绝；`:988` continueNovelTool→resolveNovelToolId(locator)→NovelCreation.continueTool | 在 ChatMessageTools 内新增小计划摘要/组件分支即可，调用原 onApprove/onDeny；locator 仍由 AgentMessageRow 提供，历史和 active tail 一并生效 |

路径表中的 `deepread/...` 为 `harmony/deepread/src/main/ets/`，`chat/...` 为 `harmony/chat/src/main/ets/`，组件/页面为 `harmony/entry/src/main/ets/`。

计划识别的窄合同：只有完整有效的 `novel_workspace_write` input，且 patches 全部针对同一个 `plan/this-chapter.md` 或明确的 `branches/<branch>/plan/this-chapter.md`，才展示为单章计划专用卡。现域 `workspaceToolPath` 负责绑定 active branch，UI 不代替授权判定。write 的完整字符串与 delete 的 null 必须分清，delete 文案为“清空本章计划”。混合修改章节/plot 的提案继续展示通用工具卡，不能只展示其中一条计划而让作者忽略其余批准副作用。坏 JSON/未知结构继续原工具 UI，保留原 input，不能创建可批准的新伪目标。

`NovelSettingProposalCard.ets` 操作的是 project.settingProposals/material kind，不是 durable workspace patch；不要复用其 `resolveSettingProposal` 写回路径。

UI 只新增必要展示：原统一卡色板与 V1 状态、44vp 操作高度、全宽可换行计划正文和路径、展开/收起。长内容展开后必须可读完整，不能把 maxLines(12/16) 当“全文”。候选全文仍可沿既有展开入口，裁定标题与动作分开对齐；不要为新卡新建导航栈/表单引擎。

## 官方 API 查证

已通过 `harmony/scripts/devecocli.sh docs search/read` 读取官方离线材料，并核对当前 DevEco SDK `.d.ts`，不是凭记忆写接口。

| 能力 | 官方事实与 API 版本 | 实施含义 |
| --- | --- | --- |
| 申请并拿通知 ID | `startBackgroundRunning(context, bgModes:string[], wantAgent):Promise<ContinuousTaskNotification>`，API 12；`['dataTransfer']`；返回 notificationId/slotType/contentType | 当前枚举重载返回 void，改为 string[] 重载才能更新真实系统通知；同 UIAbility 的 API12 接口只持有一个任务，不能为每个会话各申请一个 |
| 更新系统通知 | `notificationManager.publish(request)`；SYSTEM_LIVE_VIEW/API11、SlotType.LIVE_VIEW/API11、systemLiveView.typeCode=8；必须使用申请返回的 notificationId | 三方不能自行创建这个系统渠道/系统实况窗，只能更新系统代理创建的同 ID。不要使用 990001 完成通知 ID 或普通通知代替 |
| 无总量真实进度 | `NotificationSystemLiveViewContent.progress?:NotificationProgress`，API11；currentValue/maxValue/isPercentage 均可选；isPercentage=false 显示绝对值 | 使用真实本租期新增接收量作为 currentValue，明确单位，省略未知 maxValue；不能把字符数当百分比。系统是否将该更新纳入 DATA_TRANSFER 检测需要一次真实验收，源码/API 合法不等于系统已续持 |
| downloadTemplate | NotificationTemplate/API8，title/fileName 必填，progressValue 代表百分比；<=0 为0，>=100表示完成 | 官方后台例子使用该模板，但 LLM 没有已知总量，不能按时间虚增到99，也不能把累计 token/字符填入此百分比字段。优先采用上述绝对值 progress，不为模板制造假总量 |
| 类型更新 | `updateBackgroundRunning(context,bgModes:string[]):Promise<ContinuousTaskNotification>`，API12 | 仅更新已有任务类型；官方没有 renew/extend lease 语义，不用于定时续约 |
| 取消/查询 | `stopBackgroundRunning(context)` API9；on/off continuousTaskCancel API15；getAllContinuousTasks API20 | E13 必需链可全用 API12。不能裸引入15/20/21 API；若有必要引入必须真实 canIUse 检查和12路径，不能用较新SDK编译成功冒充兼容 |
| 通知权限 | `isNotificationEnabled():Promise<boolean>` API11 / Sync API12；`requestEnableNotification(context)` API10；isSupportTemplate API8 | EntryAbility 窗口加载后已有授权请求；后台不能反复弹窗。拒绝通知或发布失败明确可观测，不宣称进度已更新。普通完成通知仍原 best-effort 路径 |

官方资料可复查：

- [后台任务 API](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-resourceschedule-backgroundtaskmanager)；离线 ID `API参考/Background_Tasks_Kit_后台任务开发服务/ArkTS_API/ohos_resourceschedule_backgroundTaskManager_后台任务管理_/js-apis-resourceschedule-backgroundtaskmanager`，摘录 `/tmp/e13-background-official.md`。
- [长时任务开发指南](https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/continuous-task)；`/tmp/e13-continuous-guide.md`，DATA_TRANSFER 规则与 module backgroundModes 声明。
- [通知内容 API](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-inner-notification-notificationcontent)；`/tmp/e13-content-official.md:113,170–182`，绝对值进度。
- [通知模板 API](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-inner-notification-notificationtemplate)；`/tmp/e13-template-official.md`，百分比边界。
- [通知管理 API](https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-notificationmanager)；`/tmp/e13-notification-official.md`，系统实况窗由代理创建和通知授权。
- [OpenHarmony 官方通知内容文档](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-notification-kit/js-apis-inner-notification-notificationContent.md)亦确认 NotificationProgress 的最大值/当前值/百分比开关。该开源文档仅补充 API 字段事实，不证明本机 HarmonyOS 的后台策略已验收。

不需要引入独立 `@kit.LiveViewKit` 业务实况窗：本任务更新 DATA_TRANSFER 已有系统通知；新建 LiveViewKit 实况窗并不能替代长时任务进度更新。

## 当前后台缺口与最小生命周期

1. `entry/src/main/module.json5` 只有 KEEP_BACKGROUND_RUNNING 权限，EntryAbility 没有 `backgroundModes:['dataTransfer']`。官方长时任务指南要求同时声明类型；缺省为空。E13 要补声明，现文件开头“normal声明即授权”的注释不能作为申请成功证据。
2. `BackgroundGenerationKeepAlive.ets:18` 只有 taskRunning bool。两次 start 在 await 间可重复申请；foreground/最后任务完成发生在 start 尚未 resolve 时，stop 因 false 直接返回，随后旧 start 置 true，留下不需要的后台任务。最小修为一个正在申请 Promise/序列与 desired 标志，在申请落地后再检查后台+仍有活动 run，必要时立即 stop；保存系统 notificationId 只属于该次实际申请。不要另建通用 scheduler。
3. `EntryAbility.onBackground:369` 只看 AppStorage.chatGenerating；`onForeground:362` stop；`ConversationRunService.aggregate:188` 已按 activeCount 处理 A/B 归零，但不负责后续后台入场和进度。服务可在 record preparing/真实 raw 增长/terminal 报告一个小平台进度入口，入口按 runId 保存当前 active 量；waiting_user/结束从 active 集合移除，A 结束不能释放 B。
4. 真实增长源优先 `ConversationRunService.captureRaw:444` 的 canonical raw：仅本轮 assistant 流的 `streamContentLength` 增长，按发送/重生成/续跑已有 historyCount/目标定基线；它计 text、reasoning 与工具名/input，不计工具output。启动历史、USER/STEER消息、批准状态改变和本地产生的工具结果均不当作下载量。不要从 UI visual tail、定时器、retry倒计时或活动文案生成伪进度。重试/重生成重新定基线，按收到的真实新增量累计；流重排不能造成倒退或历史重复计数。
5. 小说模型当前不写 chatGenerating；Page activeRun/poll 不能当脱页后的后台源。若本阶段承诺小说后台进度，应在 Entry 的 `NovelChatRunAdapter` 包住既有 `NovelModelRunning.start/subscribe`，用 request.runId/projectId 与真实 snapshot.generationActive/terminal 维护同一个后台活动集；现 `novel_interactive_adapter.ts:100` 与 `creation.ts:1344` 都有真实 snapshot。同一适配器覆盖普通小说、ghostwrite、polish 的模型调用，不额外复制生成引擎，不依赖 NovelWorkspacePage 仍存活。
6. 一个系统 DATA_TRANSFER 通知显示“正在生成 · N个任务”与本租期累计真实接收量。新增 run 不覆盖别人的 owner；归零/前台/Ability销毁清理通知与 pending publish。一次旧 publish/start 的迟到完成不能重新标记新 lease 或停止新 lease；保持实际系统操作顺序即可。stop 失败应记真实失败，不能无条件写“系统已停止”。
7. 进度更新可合并为最多每秒一次且只在量改变时发送；这个小计时器只是限流，不能随时间推进数值。系统可能因用户移除通知、无传输、权限、低速/系统管控结束任务；API12不保证收到取消回调。保留实际 failure/log，下一次明确生命周期入场可申请，不每十分钟 stop/start 绕过系统。没有新增进展的长静默请求不承诺超时续持。

后台普通 Chat/小说网络流与 SSH/Mosh/Python/JSVM 的平台政策仍分开：现 EntryAbility.onBackground 原生中断路径保持，E13 不用 DATA_TRANSFER 偷宣称终端/计算可长期后台常驻。批准等待、暂停任务、仅持久 session、待用户操作不算正在网络生成。

## 正式阶段的窄 ownership

- 小说 UI worker：新小 `NovelChapterVerdictCard.ets` 与 `NovelChapterPlanProposalCard/ApprovalSummary.ets`，只负责展示/识别；owned `NovelGhostwriteCard` 和 `ChatMessageTools` 的必要调用点。原回调和 locator 不改协议。
- 后台 worker：`BackgroundGenerationKeepAlive.ets` 的 API12申请/实际notificationId/绝对值progress/生命周期状态；不修改 provider/native引擎。
- root 集成：module.json5声明；EntryAbility前后台/销毁；ConversationRunService record/raw/terminal 与 NovelChatRunAdapter 的少量事实接线，保证同一个聚合活动集；独占共享文件避免冲突。
- 独立 review：核专用展示对应真实候选/计划/patch全部副作用、批准链/receipt未退化，后台 A/B归零与start/foreground交错、绝对值进度API12及通知同ID。只给具体可执行问题。

## 仅必要验收（本预查均未执行）

- 计划摘要的单组识别：合法 plan write/delete 呈现；mixed patch 仍能看见全部副作用；坏 JSON不生伪批准卡。批准/拒绝只复用现有 E0 plan闭环证据，若仅展示改动不重跑整套域测试。
- 一组后台生命周期交错：A/B活动，A结束B仍保活，foreground发生在start完成前不残留，再后台时新notificationId正确；真progress增长、静默不增长、归零清理。只对新共享状态做必要fixture，不堆压力/组合矩阵。
- owned ETS lint + 真实SDK类型/API12编译；root统一构建。专用卡一轮亮暗状态截图核长标题/长计划、44vp按钮、间距、折叠展开及已拒绝状态。
- 一次真实后台传输持续越过10分钟（有真实进度且同系统通知）确认通知、任务与流仍活动；若离线/无可用provider，记录未执行这项，不把合成时间fixture或API合法宣称为该系统验收PASS。可用本地受控真实HTTP流，无需反复长跑设备。通知拒绝路径只需确认失败被观察、前台生成仍正常。
