# Android 第二轮审查与修复（2026-09-08）

工作目录：`/Users/arquiel/Downloads/AI/AmberAgent/android`。本轮承接“再来一轮”，对照首轮结束时的工作树，继续沿用户入口、运行时 owner、持久化和失败反馈核对；没有读取兄弟产品仓库，没有提交、推送或安装应用。

以下为第二轮新增修复，首轮成果不重复计数。同工作树另一个 WebMount 任务的改动保持原状，不作为本轮成果认领。

## 新增修复

| # | 真实触发与原结果 | 本轮修复 | 主要位置 |
|---|---|---|---|
| 1 | 历史页删除后在 Undo 等待期间离页，清理协程随页面取消；恢复失败也可能跳过清理 | 删除、恢复和最终清理由 AppScope 持有；先等待实际删除成功再提示；恢复失败补清理，清理前确认数据库中不存在该会话 | `HistoryPage`、`HistoryVM` |
| 2 | 删除生成中或暂停中的会话，仅取消 session observer，独立 kernel run 和部分 durable pause 仍存活 | 复用真正的 Stop 链取消 runner、等待终态/暂停；无待核实远端 cursor 的残留 live run 结算为 CANCELLED，同时处理 event/ledger | `ChatService.stopRunForDeletion` |
| 3 | 页面取消落在数据库删除与文件清理之间，删除异常分支解除 tombstone | 一次已开始的破坏性删除在 NonCancellable 内完成；失败时仅对仍存在的会话解除 tombstone，避免已删会话复活并引用已清理附件 | `ChatService.deleteConversation/deleteAllConversations` |
| 4 | 分叉附件复制失败时回退到原 URL；工具输出里的图片未递归复制；后续复制失败留下前序文件 | 复制失败明确报错；递归复制 Tool.output；分叉未持久化时回收本次文件，已提交的分叉保留文件；UI 显示失败 | `ChatService.forkConversationAtMessage`、`ChatPage` |
| 5 | 保存新消息引用之前，内存更新先触发旧附件删除；保存失败仍丢附件 | 保持即时 UI 更新，将附件清理延后到数据库写入成功之后 | `ChatService.saveConversation` |
| 6 | 普通附件物理删除失败，但 managed-file 索引仍被删除，Files 页无法重试 | 物理删除成功或文件已不存在后才删除索引；会话最终清理等待文件任务结束 | `FilesManager.deleteChatFiles`、`ConversationRepository` |
| 7 | 删除含生成图的消息/variant 后，`chat_images` 被普通 upload 清理过滤掉 | 消息持久化后，只清理不再引用且属于当前会话目录的生成图，保留其他 variant 和会话文件 | `ChatService.deleteMessage`、`FilesManager.deleteChatImageFiles` |
| 8 | 技能 rollback 的 rename 失败，finally 仍删除唯一 previous 快照与元数据 | 失败保留 previous 与 metadata；成功恢复后才清 metadata，无法恢复 active 时保留 swap | `SkillManager.rollbackSkill` |
| 9 | 记忆编辑/删除弹窗持有旧版本时，忽略 revision 覆盖或删除并发新值 | UI 操作传递原 revision，走现有 CAS；冲突经既有 operationMessage → Toast 显示 | `SettingAgentMemoryVM` |
| 10 | 同目录重复导出记忆只追加文件，旧 slug/已删记忆在后续导入中复活 | 本次写入成功后清除受管理路径里的过期 `.mem.md`；清理失败明确报错，保留其他文件 | `MemoryImportExportManager` |
| 11 | Daydream 同批对同一记录先 merge 再 promote，用旧 revision 被自己的 CAS 拒绝 | 每次成功写入后更新批内记录，后续步骤使用返回的新 revision | `MemoryDreamApplier` |
| 12 | pending Dream 计划先 dismiss 再 insert，两次写之间取消/失败会丢待审计划 | 使用真实 Room `@Transaction` 替换 pending，手动与后台共用同一入口 | `MemoryDreamPlanDAO`、`MemoryDreamPlanStore` |
| 13 | `generate_image` 被记为 READ_ONLY，中断后可能自动再次请求远端生图，同批调用也可并发 | 显式 NON_IDEMPOTENT_WRITE，禁止同批并发；保留原有审批契约 | `ToolRegistry` |
| 14 | 多图生成后面一张解码/写入失败，前面已落盘图片没有引用 | 失败只回收本次新建文件，并保留原始错误 | `ImageGenerationRepository` |
| 15 | Provider 返回空图片列表，工具仍报成功 0 张 | 在 repository 明确拒绝空结果，沿工具失败通道反馈 | `ImageGenerationRepository` |
| 16 | MCP 删除/禁用触发 close 后再次排队重连；取消、旧连接迟到结果污染当前连接 | 主动关闭先解除当前 client 所有权；连接/同步/回调检查当前启用配置与 client；取消继续传播并清理旧连接 | `McpManager` |
| 17 | 多 MCP server 同时更新状态，整张 map 读改写互相覆盖，甚至抑制重连 | 状态使用 StateFlow 原子 update；共享连接集合使用并发 map，删除按实例条件移除 | `McpManager` |
| 18 | MCP 导入合法 JSON 中的非对象 server 项使预览抛异常 | 对根配置和每个 server 的对象形状显式校验，返回 Rejected | `McpImportTransaction` |
| 19 | Board 把取消和瞬时 provider 异常都吞成永久“model call failed” | 取消原样传播；保留 provider Throwable，用现有 GenerationFailureClassifier 和原有 WorkManager 次数上限判断重试 | `BoardAgent`、`BoardWorker` |
| 20 | FOREGROUND_ONLY 下通知仍可入队自动刷新；已有自动任务也继续运行 | 自动入队及 Worker 执行处检查策略，停止自动 anchor 重排，保留手动刷新 | `BoardScheduler`、`BoardWorker` |
| 21 | 同一稳定看板 ID 再次生成时 REPLACE 把 completed/dismissed 重置为 active | 事务内插入缺失项，仅更新生成字段，保留用户生命周期列 | `BoardItemDAO` |
| 22 | DeepRead 冷启动读取 dummy settings，已有有效配置也被判为缺模型并永久失败 | Worker 和 RunManager 等待设置完成加载再读取 | `DeepReadWorker`、`DeepReadAgentRunManager` |
| 23 | Live pause/stop 后旧分析的开始、成功或失败状态仍可能覆盖新状态 | 停止/暂停先失效 generation，首次 IO 状态写入与成功/失败回写均检查代次 | `LiveModeManager` |
| 24 | Task JSON 原地覆盖，进程中断可能使整个快照变成截断 JSON | 同目录临时写入、同步后原子替换；失败保留旧文件并记录错误，没有非原子 fallback | `AgentTaskStore` |
| 25 | 冷启动恢复尚未处理旧 RUNNING 行，新消息已经复用旧 runId，runner 拒绝激活但 observer 一直等待 | 单次恢复 gate 覆盖 durable recovery 与 projector；ChatService 在读取恢复 runId 前等待，生产 runner 在创建 durable 行前等待；失败明确传播 | `AmberAgentApp`、`ColdStartRuntimeRecoveryGate`、`AgentRuntimeModule`、`InProcessAgentRunner`、`ChatService` |

## 复核中纠正的结论

- 生成图已经包含在 `Conversation.files` 的递归 Tool.output 遍历中；漏清理的真实原因是 `deleteChatFiles` 只允许 upload 前缀。修复针对目录所有权，没有重复增加另一套消息遍历。
- Undo 的标记不能简单移到 `await()` 成功后：页面取消时 AppScope 的恢复可能仍在提交。补偿由恢复任务自身的失败分支负责，避免与仍在进行的恢复竞跑。
- 保留已选和未选 variant 是现有历史契约；只清理实际删除后不再被引用的生成图。
- 已删除会话如果仍有未确认远端结果的 cursor，保留 WAITING_EXTERNAL 用于核实真实结果。不能将它伪装成已成功取消；会话不存在时恢复服务不会重新写入聊天内容。
- 后台记忆抽取是现有进程级 fire-and-forget，没有足够契约证据把“进程退出后不补历史抽取”判为本轮 bug。
- 没有凭静态时序猜测扩展相机缓存队列、图片时间戳恢复框架或全局文件生命周期系统。

## 仍未闭合的风险

`ResponseAPI.persistDrain` 在交付事件前保存 sequence cursor，而聊天内容/checkpoint 在下游另行持久化；终态 cursor 也在流交付后清除。进程在两份持久化之间终止，可能跳过尚未落库的事件。当前代码没有 caller durable-ack 和事件幂等去重契约，简单改成先 emit 后 save 只会引入重复重放。本轮确认并记录这个持久化缺口，**没有声称已经修复**；需要将事件落库、去重与 cursor 推进绑定后才能闭合。

Board 的不同触发器仍可能并行请求模型。本轮闭合了最直接的数据后果（用户已完成/忽略的项被重新激活），没有将所有触发器改造成新的统一队列，因此不声称已消除全部重复请求开销。

MCP 的连接 enable 与直接工具选择集合是两个现有条件；当前设置页缺少直接工具选择入口属于契约/体验观察。没有未经确认扩大模型的默认工具暴露范围。

## 验证与复审

- 初次定点发现技能 rollback 测试未命中目标失败分支；改为真实 transaction CAS 通过后用文件权限制造 rename 失败，保留断言后重新通过。
- 第一批本轮定点：74 个用例通过，0 失败。覆盖存储、技能回滚、图片、工具效果、MCP 导入、记忆 CAS/重复导出和任务快照。
- Runner 新增的两条测试直接验证“恢复前不写 durable 行、不执行 handler”和“恢复失败不执行 handler”。修正 fixture 新参数对既有 trailing lambda 的绑定后进行统一回归。
- 最终完整回归：`:app:testDebugUnitTest` 共 1,980 项，1,967 通过、13 跳过、0 失败；`:core:agent-runtime-impl:testDebugUnitTest` 18 项全部通过。合计 **1,985 通过、13 跳过、0 失败**，`BUILD SUCCESSFUL`。日志：`/tmp/amber-r2-verified-tests.log`。
- 跳过项为已有的 MarkdownTreeParityTest 12 项，以及并行 WebMount 任务的 SessionHandleLifecycleTest 1 项。本轮没有为通过检查新增跳过。
- 前两次完整回归曾暴露并行 WebMount 任务中的截图视口 fixture 和 OAuth 测试失败；对应任务继续修改后，以上最终完整回归通过。本轮没有覆盖或回退其修改，也不认领其修复。
- 独立子代理分别复核聊天删除/分叉、Memory/Image、后台/Live/任务快照、MCP 生命周期。发现的问题继续返回修复，不把最初审查报告直接当成通过结论。
- Memory 的事务不仅核对接口注释，还核对了 Room KSP 生成的 `performInTransactionSuspending` 接线。
- 最终 `git diff --check` 通过。检查范围保留首轮和并行任务的 WIP，最终结果没有提交或推送。
- 未连接真机、真实 MCP 服务或远端生图 provider；测试和源码推演不等同于设备/真实服务验收。

本轮保持界面布局、主题、动画和图片展示效果。性能方面主要减少失效连接、过期状态写回和无引用文件积累；没有帧率或端到端延迟实测，不报告量化提升。
