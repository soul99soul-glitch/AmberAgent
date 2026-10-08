# Spec 轴独立审查

日期：2026-10-02。审查基线：HEAD `5ea51f7a` 的当前 Harmony 工作树，包括 tracked diff 和 untracked 生产模块。只读审查，未修改产品代码；本报告不替代其它领域的逻辑和设备 review。

规格来源：`docs/plans/2026-09-30-harmony-chat-refinement.md`、`2026-09-30-harmony-conversation-recap.md`、`2026-09-30-harmony-deepread-product-completion.md`、`2026-09-30-novel-functional-completion.md`，以及 `docs/reviews/2026-09-30-novel-functional-completion/current-parity.md`。已读取 Harmony ArkTS skill。

## 二次核实后成立的问题

### SPEC-01 / P2：长讨论仍可继续聊天，却无法通过生产入口归档

- **规格**：小说计划第 9 行要求长篇创作和输入保存闭环，第 15 行列 B19「有效归档」为必要能力，第 35 行要求发送历史预算与有效归档；当前 parity 第 45 行声明作者确认累计摘要后排除已归档讨论、提供长期记忆。
- **生产触发**：使用当前 16K 上下文模型，累积 12 条各 8000 字的讨论消息（6 个正常往返，每条用户输入均在允许的 8000 字内），点击「归档讨论」。普通下一轮发送可以裁剪旧历史继续，但归档会把所有未归档消息当作不可裁剪的本轮输入，直接报窗口超限。
- **源码**：`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets:186-199` 无范围选择，固定传入全部 eligible IDs；`harmony/deepread/src/main/ets/novel/discussion_archive.ts:94-109` 一次拼接全部内容到 userPrompt；`harmony/chat/src/main/ets/chat/novel_context_policy.ts:48-51,63-70,93` 将这条新 user 消息视为必需输入并拒绝超限。`NovelDiscussionArchiveSheet.ets:89-100` 只有生成成功后的范围展示，没有可用于缩短来源的入口。
- **实际影响**：归档失败时原文未丢失，但在当前模型下重复点击永远无法推进归档游标；「请缩短本次输入」对这条系统构造请求不可操作。越需要压缩长期讨论时，越无法使用归档。
- **复现证据**：直接使用 `makeNovelProject` / `makeNovelMessage` / `eligibleNovelDiscussion` / `prepareNovelDiscussionArchive` 与生产 `createNovelContextPolicy`，在不调用网络的模型端口上校验实际归档请求。结果：`eligibleMessages=12`、`eligibleChars=96000`、`inputTokenBudget=13312`；同一预算下普通发送裁到 7 条消息通过，归档请求在 provider 调用前被窗口检查拒绝。
- **二次排除**：不是 provider 的未知限制；拒绝来自本地生产预算策略。不是源文自动截断的预期保护本身有错；真正缺口是 UI 总选全部、归档没有可预算的完整来源范围。不是跨进程续写、iOS UI parity 或可选功能。模型端口已经提供 `inputBudgetTokens` / `estimateInputTokens`（`model_running.ts:65-68`），无需另建预算框架。
- **最小修复建议**：基于现有发送预算选择最早能完整容纳的消息范围，并让实际 draft IDs / sourceRange 与本轮范围一致。确认后下一次归档继续处理未覆盖 IDs，沿用现有累计摘要和源 digest。保留全量 canonical 原文，不截断单条来源、不引入后台队列或重试链。覆盖首次超预算、带旧累计摘要、确认后下一批、不足以容纳单条消息的准确报错。

## 明确要求尚未验收的部分

小说新增 UI、真实云模型、系统后台的设备门仍在小说计划第 7、34-39、47-49、66 行，以及 current-parity 第 88-90、108 行明示 pending。本轮只读源码审查不能将这些门改为通过；也不能把 pending 本身列为已确认代码 bug。修复交付应记录当前包的实际设备/主题/键盘/长文结果，遵守不启动、新建或重启模拟器的已有约束。

## 二次核查后排除的候选

| 候选 | 核查结果与依据 |
| --- | --- |
| 深读搜索运行中变更配置会污染既有来源 | 假阳性。`SearchRegistry.ets:387-394` 创建绑定 settings 的 provider 快照；`source_prefetcher.ts:187-204,228` 每轮 collect 使用该快照。不能仅凭 provider 类存在现读设置函数判 bug。 |
| 深读 runSection 不保护完整旧稿 | 当前生产路径不成立。`DeepReadArticlePage.ets:486-510` 只重试首个 FAILED stage；`section_writer_tools.ts:580-594` 不将已有 READY 段改为 RUNNING/FAILED。完整重生成走 force 路径，由 `run_manager.ts:181-227,260-283` 保留旧稿。 |
| API 12/13 没有 PDF 导出 | By design。深读计划第 36 行明确系统能力限制；`DeepReadExportFiles.ets:9-10` 有 API 14 能力门，`DeepReadExportPanel.ets:253-259` 可见提示，完整文本/Markdown 仍可达。 |
| 小说计划缺 iOS 结构化 must/bans、草稿/确认状态机 | By design。current-parity 第 69 行保留 Markdown 作者计划等效；`ghostwrite_start_preview.ts:13-35` + `NovelWorkspacePage.ets:739-760` 提供完整计划预览、作者确认和 CAS。内部 optional preview 参数不等于生产 UI 绕过确认。 |
| 小说归档原讨论没有全部折叠消失 | By design。current-parity 第 45 行明确「长期记忆已补齐；呈现仍不同」。`context_builder.ts:44-47,62-67` 注入累计摘要并排除已归档消息；不能为了 iOS 呈现新增删除/第二套历史。 |
| 小说缺远端 Responses 游标、Live Activity、全局三职责小说默认模型 | By design / provider 平台边界。小说计划第 19、28 行和 parity 第 81、87-89 行明确保留范围。不能转成此次必要修复。 |
| 回顾切页后会写错会话、删除/恢复不会失效 | 未发现违反此规格的调用链。`ConversationRecapService` 按 id/token 收口，`ConversationRecapSupport.ets:61-74` 在存储变更锁内校验；删除 `ManagedFileStore.ets:247-250`、恢复 `SettingBackupPage.ets:323-331` 都显式失效。回顾 5 项聚焦测试本轮通过。 |

## 未要求的范围扩张

未发现能够仅依据上述五份规格成立的 scope-creep blocker。当前大工作树同时承载其它既有聊天、议会、附件、渲染任务；不将「此五份计划未列出」直接当成「用户未授权」。死代码/历史 adapter 按仓库要求只标记，不建议顺手删除。修复 SPEC-01 应限制在现有归档入口与预算选择，不扩建通用调度、持久队列、模型救援流水线。

## 本轮验证边界

已执行：回顾聚焦测试 5/5；SPEC-01 真实生产预算纯函数反例；上述生产入口、数据持久化和规格的独立二次阅读。未执行：产品代码变更、lint/build/sign/install、当前真实 provider、设备视觉和系统后台验收。未把历史验证记录冒充本轮证据。
