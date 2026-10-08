# Phase3 SPEC01 长讨论归档独立复审

结论：源码通过，未发现阻断问题。审查当前 `discussion_archive.ts` 与 `novel_discussion_archive.test.ts` 相对 `/tmp/amber-harmony-review-20261002-baseline` 的限定修改；审查者未实施此块，也未修改生产源码，仅写本报告和 `/tmp` 独立反例。

审查时 `phase3-archive.md` 仍称RED等待实施，与当前GREEN源码不一致，已明确反馈主agent及实施者更新。此文档状态不替代下面实际源码/测试证据。Workspace sourceRange UI仍由另一组审查，本报告不外推其正确性。

## 规格与代码核查

- SPEC01实质问题是全量未归档讨论可能超过当前发送预算，且系统构造输入没有可操作的缩短入口。实现使用既有模型端口的真实inputBudgetTokens/estimateInputTokens，未猜上下文默认值或新建预算框架。
- systemPrompt、输出上限3072和旧累计摘要都位于实际预算计算路径。inputBudgetTokens收到的outputLimit与实际request一致；estimate接收最终system+完整候选userPrompt。恰好等于预算允许通过。
- selectedDiscussion按project.messages的canonical顺序过滤用户选中ID。循环取最早完整前缀，首次超限即停，不跳过超长来源去归档后面的短消息，也不截单条正文。
- draft.sourceMessageIds和sourceDigest只绑定这次实际选中的prefix。确认仍校验branch、previousArchiveId、所选来源正文与role；throughMessageId为本批最后一条。未覆盖讨论原文与ID仍保留且可成为后续批次。
- 先生成draft不改project；作者确认后才增加归档和所选决定。下一次生成会带上作者编辑后的累计摘要；canonical消息数组没有删除或重写。
- 首条完整来源连同必要提示与旧摘要放不入预算时，明确报更大上下文提示，不调用model.start，不推进archive游标。budget查询/validate本身不是provider生成外呼。
- 只有预算与估算两个可选端口同时存在才启用prefix裁选。缺一或全缺沿用全量选择，不引入模型window猜测；生产NovelChatRunAdapter对接的inner同时实现两端口。
- 未增加后台任务、持久队列、自动重试、provider替补或源文截断。预算循环有真实职责，未为此扩抽象。

## 独立验证

在 `harmony/deepread` 执行 `npx tsx --test src/test/novel_discussion_archive.test.ts`，5/5 PASS，0 fail/skip/todo；原有3个确认/branch/digest/坏JSON测试保留，新增两个长讨论/单条超限测试通过。独立执行deepread `npx tsc --noEmit`，退出码0。

另编写 `/tmp/amber-phase3-archive-review.test.ts`，使用真实 `estimateNovelInputTokens`（同production adapter），实跑4/4 PASS：

1. 不按selectedIds输入顺序排序，canonical第一条完整来源刚好达到真实预算可归档，第二条不放入。
2. 加入1800字旧累计摘要后，同一预算不能再容纳首条来源，model.start计数0；适配包含旧摘要的真实预算后第一条通过，previousArchiveId保持。
3. 选中来源改动拒绝确认；未选中后续来源改动不错误阻止当前确认。确认后只推进实际prefix，未归档ID保持；旧draft再次确认仍拒绝。
4. 预算/估算端口均无、仅预算、仅估算三种旧端口配置均完整保留全量选择，不额外调用缺另一半的端口。

这些反例检验实际预算和归档状态边界，不是以源码字符串匹配推断正确性。

## 证据界限

本阶段验证纯领域、实际生产token估算与受控模型事件；未调用真实云模型，未验证Workspace范围展示、ArkUI布局或设备后台行为。没有把旧验收或另组UI验证当作本次完成证据。
