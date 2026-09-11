# Android 小说代笔与 iOS 能力对齐计划

日期：2026-08-30
Android 基线：`e9326d7db0b07ab305e27893696f56625a486676`（`main`，相对 `origin/main` ahead 1）
iOS 契约来源：Codex 任务 `01a04dbe-c677-7bf1-a07f-ff0007fb2bfa` 的 Phase 0–3 完成口径
状态：Phase 0–3 已完成；源码、JVM 与编译验收通过，真机/provider/后台验收待具备设备后执行

## 目标与边界

目标是在 Android 现有生产链上完成与 iOS 等价的小说代笔闭环：一个已确认计划绑定一个候选；健康章节只需“写作 + 联合审核”两次逻辑模型调用；审核硬闸通过后，章节、剧情/状态、计划消费、下一计划和进度凭据一次提交；5 章与 10 章连续运行、暂停恢复和进程重启均可从持久状态继续。

保留并复用现有 WorkManager、前台通知、WakeLock、`executionId` owner token、`NovelWorkspaceWriteBatch`、Ledger、D-C 剧情新鲜度、D-D 中间章未解决闸、分支、撤销、暂停/继续/重试/取消与润色链。不重写全局 `commitTree`，不新造通用 orchestrator/state-machine 框架，不把 Android 平台生命周期搬进 Core。

当前未提交 Novel 改动均视为用户 WIP。每个 Phase 只修改其验收所需文件；每阶段完成后先运行最小测试，再分别交由逻辑链审查和 Compose/UI 审查，修完确认项后才进入下一阶段。

## 冻结契约

- 作者点击“开始代笔”即确认当前 `plan/this-chapter.md`；宿主保存计划正文快照、计划 ID/digest、分支 ID、branch head 与 tree digest。批次期间 UI 禁止修改计划。
- 写作轮只产生宿主持有的候选，不直接写正史、不生成可手动收录的普通 draft/proposal；候选始终携带 job、branch、chapter、plan ID/digest。
- 同一计划最多允许两次定向重写；所有重写仍使用同一计划 ID/digest。只有审核明确 `blocking` 或重写两次仍不通过才停止；可选 upcoming arc 与非阻断扫描失败不得自行改写计划。
- 联合审核是只读模型轮，结构化返回候选关联、计划发现、连续性发现、blocking、是否需重写、剧情/状态增量和可选下一章计划。
- 联合审核单轮输出预算至少 10,000 token；只有缺失计划明确要求的必写项、违反明确禁写项，或可定位到当前候选章的连续性硬伤才可 blocking。复读提示、不确定问题和可选 upcoming arc 不能误伤主链。
- 宿主在 owner token 与 expected-head CAS 下执行专用审核收录：正文、剧情/状态、旧计划消费、新计划轮转、Ledger、undo、receipt/progress 一条窄链完成。正史落盘后必须先持久化计账，再允许暂停/取消影响下一章。
- 非最终章：优先使用联合审核返回的下一计划；缺失时只补一次计划生成调用。最终章不生成下一计划。
- UI 章数范围固定 1–10，默认 5；运行时展示当前章与 `writing / reviewing / rewriting 1/2 / committing / planning`，保留明确的暂停、继续、重试、取消与失败原因。

## Phase 0：版本绑定、CAS 与专属进度

实现：

1. 扩展 host-private ghostwrite job：分支 ID、base/expected head、base/expected tree digest、当前计划 ID/digest/正文快照、当前章、阶段、审核收录 receipt。
2. 新批次要求非空计划，按当前工作树生成稳定 digest；点击开始形成不可变确认快照。恢复/重试保留原计划与游标。
3. 写模式进度只统计 job receipt 且 commit 仍位于绑定分支 ancestry；旧 job JSON 保留兼容推导。
4. 提供 job 层 owner/execution/head 绑定更新原语；本 Phase 不改变写作仍直提交流程，避免把候选/审核行为混入绑定层。

验收：序列化兼容；计划快照不随磁盘编辑漂移；外部新章不污染新 job 进度；旧 execution token 和 head 冲突被拒；现有暂停/恢复/润色测试不回归。

## Phase 1：候选先于正史、最多两次定向重写

实现：

1. 写作轮改为只读工具会话，只接收最终正文作为候选；禁止 unattended turn 修改 chapter、plot、plan、setting 或 draft。
2. 候选持久化到 job 私有状态，绑定当前 chapter 与确认计划；正史在此阶段不落盘。
3. 支持携带审核修复指令重新生成，attempt 上限为 2，复用同一 plan ID/digest。

验收：初稿/重写均不推进 Ledger、不产生审批卡或普通 draft；候选冷启动可恢复；第三次重写被拒；写作提示始终携带相同计划绑定。

## Phase 2：联合审核硬闸与审核收录

实现：

1. 增加最小结构化联合审核结果与严格解析；审核会话只读、输出预算至少 10,000 token，并校验 job/candidate/chapter/plan 关联。
2. `blocking=true` 立即停止；`rewriteRequired=true` 触发 Phase 1 的定向重写，最多两次；健康结果直接进入提交。
3. 新增专用 `commitReviewedChapter`，在 owner/execution + expected-head/tree 校验下统一写正文、受限剧情/状态增量、Ledger、undo 和 receipt；失败前回滚未提交文件，Ledger 已持久化则以恢复/补记账完成，不重写全局 `commitTree`。
4. ghostwrite-bound candidate 不进入手动 `collectDraft`/proposal 路径，不能绕过联合审核。

验收：健康章节恰好写作+审核；显式 blocking 不落正史；审核关联不匹配不落正史；重写上限生效；暂停/旧 token/head 冲突在提交前拒绝；章节与 plot 同 commit，D-C 不 stale。

## Phase 3：下一计划原子轮转、长链与 UI

实现：

1. 联合审核结果可携带下一计划；非最终章缺失时只执行一次只读计划生成，宿主分配新 ID/digest。
2. 审核收录同时消费旧计划并写入下一计划；最终章删除当前计划且不再规划。恢复逻辑根据 committing 状态、Ledger 与 receipt 补齐进度，不重复收录。
3. UI/通知读取 durable stage、当前章与 rewrite attempt；章数限制 1–10、默认 5；调整状态区的对齐、触控尺寸、间距与长文案换行，不重做现有视觉系统。
4. 删除批次完成后的独立“自动审最新章”重复路径，仅保留作者主动的一致性审稿入口；联合审核已经覆盖每章并作为提交硬闸。

验收：5/10 章健康路径均为每章两次逻辑调用；仅缺下一计划时多一次；最终章无规划调用；每章计划 ID/digest 轮转且旧计划只消费一次；暂停/恢复、失败重试和冷启动不重复章节；UI 状态与 durable job 一致。

## 阶段审查与最终验证

每阶段：

1. 运行直接覆盖本阶段的既有/最小 JVM 测试，再按风险运行 `:feature:novel-workspace:test`、app 定点测试或 compile。
2. 逻辑 subagent 只读检查 `UI gate -> controller/worker -> owner/CAS -> runtime -> Ledger/job persistence`，确认失败路径与恢复路径闭环。
3. UI subagent 只读检查新状态是否真实接线，并检查 Compose 对齐、边距、间距、尺寸、触控目标、长文案与主题；主 agent 只修可证实问题。
4. 修复审查发现并重跑定点验证，记录尚未覆盖的 provider、后台、设备或视觉证据，不能用 JVM/编译冒充。

最终再跑 CodeGraph changes/impact、相关 JVM 套件、app compile；若本机设备可用，再执行真实 Compose 页面与后台暂停恢复检查。任何无法完成的 provider/device 验收都按证据等级单独列出。

## 完成记录（2026-08-30）

- Phase 0：完成确认计划快照、branch/head/tree 绑定、execution owner/CAS、receipt 计账与旧 JSON 兼容；逻辑和 UI 审查后修正了失败批次仍可编辑冻结计划的问题。
- Phase 1：写作与定向重写均只生成 job 私有候选，不写正史或普通 draft/proposal；同一计划最多重写两次；完成逻辑和 UI 审查。
- Phase 2：完成只读联合审核、严格关联校验、blocking/rewrite 硬闸，以及章节、剧情/状态、Ledger、undo、receipt 的专用审核收录链；完成逻辑和 UI 审查。
- Phase 3：完成下一计划严格解析、缺失时一次补规划、非最终章计划轮转、最终章计划删除、5/10 章长链、durable stage UI、双模型选择与多语言；移除批次后的重复自动审稿。逻辑复核发现并修复了三文件部分写入后的冷恢复死锁，UI 复核发现并修复了窄屏双模型选择器溢出、模式路由、章序/重写次数、触控高度和失败态锁定。
- JVM：`:feature:novel-workspace:test` 通过；app 的 `NovelWorkspaceRuntimeTest`、`NovelWorkspaceBranchFlowTest`、`NovelWorkspacePolishBatchTest`、`NovelWorkspacePromptsTest` 通过。
- 构建与静态检查：`:app:compileDebugKotlin`、Android 多语言键/占位符检查、`git diff --check` 通过；CodeGraph changes/impact 已复核高风险生产路径及调用点。
- 证据边界：本机无已连接 Android 设备且 SDK 无 emulator，因此没有把 JVM/编译结果冒充真机 Compose、真实 provider、WorkManager 后台、杀进程恢复或锁屏验收。规划模型返回到下一计划首次持久化之间仍存在一个很小的进程终止窗口；恢复时可能重做一次只读规划，但不会重复收录正史或重复消费计划，未为此引入额外日志协议。
