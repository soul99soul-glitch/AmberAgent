# Phase 3：SPEC-01 小说长讨论归档

状态：主 agent 确认 Phase 2 独立 review 通过并正式开始 Phase 3 后，已完成最小实施与 GREEN。生产源码停止变更，等待其它 agent 独立 review。

所有权仅为 `harmony/deepread/src/main/ets/novel/discussion_archive.ts` 与 `harmony/deepread/src/test/novel_discussion_archive.test.ts`。Workspace 范围展示由 UI worker 修改，不改变现有 draft 接口。

## RED（2026-10-02）

命令：在 `harmony/deepread/` 运行 `./node_modules/.bin/tsx --test src/test/novel_discussion_archive.test.ts`。

结果：原有 3 项通过；新增 2 项失败。

1. 12 条约 8000 字的合法讨论消息，13312 tokens 发送预算。模型端口校验发现一次请求包含全部未归档内容，报 `模型生成失败：归档来源超出发送端模型预算`。目标是只发送最早能完整容纳的来源消息，确认后下一次归档保留作者累计摘要并覆盖剩余消息。
2. 最早单条 64000 字来源不能放入该预算。当前仍调用 start，由发送端拒绝；目标是调用前明确提示首条完整来源无法容纳，需要更大上下文模型，不截来源、不推进游标、不外呼。

测试 mock 预算端口使用统一输入估算，并在 start 验证发送预算。没有预算端口的既有 mock 保持全量行为，不猜默认模型窗口。

## 最小实现

prepare 保留现有 systemPrompt、3072 输出上限、旧累计摘要模板；若模型同时提供 `inputBudgetTokens` 与 `estimateInputTokens`，读取真实预算，依原消息顺序选择完整前缀。draft.sourceMessageIds / sourceDigest / 原文范围只绑定所选批次。确认逻辑继续复用既有 sourceDigest、分支、previousArchiveId 检查和累计归档持久化。canonical 原文不变。

不增加后台任务、持久队列、重试、默认猜测预算或源文截断。单条无法容纳时明确失败。

## GREEN（2026-10-02）

`discussion_archive.ts` 沿用同一 systemPrompt 和 3072 输出上限，使用已有模型端口真实预算及估算器按消息顺序选择完整前缀。已有累计摘要参与同一次预算判断；只将本批完整来源写入 draft.sourceMessageIds / sourceDigest。下一批通过原有 eligible IDs 排除已确认来源。单条无法完整容纳时，在 start 前准确报错，原文与游标不变。

无端口的旧 mock 仍保持全量行为；没有默认猜测模型窗口，没有源文截断、队列、重试或额外状态。新增约 25 行局部逻辑；接口不变。

验证：

```sh
cd harmony/deepread
node_modules/.bin/tsx --test src/test/novel_discussion_archive.test.ts
node_modules/.bin/tsx --test src/test/novel_discussion_archive.test.ts src/test/novel_context_builder.test.ts
node_modules/.bin/tsc --noEmit
```

- 归档聚焦 **5/5 通过**；归档及上下文共 **15/15 通过**。
- 长讨论实际拆成首批 6 条、第二批 6 条，每条来源完整；作者确认的累计摘要进入第二批，全部确认后 eligible 清零，canonical 原文完整保留。
- deepread TypeScript 检查 exit 0；owned 两个文件 git diff --check exit 0。
- scoped `harmony/scripts/lint-arkts.sh harmony/deepread/src/main/ets/novel/discussion_archive.ts` exit 0，**0 error / 97 warn**；日志 `/tmp/amber-comprehensive-phase3-archive-lint.log`。CodeLinter warning 含 domain 类型导入解析引出的 no-unsafe 警告，独立 tsc 通过；未宣称零 warning 或与历史基线一致。

## 待完成

- 与 UI worker 核对仅用 draft.sourceMessageIds 展示当前批次 sourceRange。
- 主 agent 独立 phase review；发现问题后精准复核修复。
- 整体构建、签名与设备验证由主 agent 串行整合，设备门另记真实证据。本 worker 未安装包、未使用真实 provider、未执行设备视觉或后台验收。
