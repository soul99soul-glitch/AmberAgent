# E4 child loop Recipe 接线精修

2026-09-28，owner `/root/e3_python_domain`。独立 review 的 P2 为：真实 `ChatStreamSubAgentGenerationPort.run()` 自建 `ToolLoopOptions`，没有注入 Recipe Adapter；子代理收到 `recipe__*` wrapper 后走普通 dispatcher，只会得到 `recipe_adapter_required`，无法调用其声明 primitive。

## 最小实现

`chat/subagent_runner.ts` 仅两行新增：

```ts
// ChatStreamSubAgentGenerationPortDeps
configureRecipeLoop?: (loop: ToolLoopOptions) => Promise<void>;

// 实际 child loop 构造后、runAgenticToolLoop 之前
if (this.deps.configureRecipeLoop !== undefined) await this.deps.configureRecipeLoop(loop);
```

callback 接收即将执行的同一个 loop，可由 root DI 注入当前 Store/Recipe Adapter、刷新、target guard。原调用方无 callback 时走原路径；现有 child tools、invocationContext=`subagent`、全局批准设置、provider、budget、signal 与内存 conversation store 不变。没有平台 import、持久 child 会话、Cron 接线或扩大 child scope。

root 负责实际 DI 注入及仅 request scope Recipe 名/primitive 边界，本报告不把测试用 callback 当作平台 DI 验收。

## metadata 真实路径核查

- `scopedSubAgentTools` 先 filter 再 `executableTools.slice()`，保留原 AgentTool 对象；Recipe envelope 不丢失。
- `makeAgentTool` 已赋值 `recipeEnvelope:opts.recipeEnvelope`；现有 `tool_registry` wrapper 也已赋值 `recipeEnvelope:e.tool.recipeEnvelope`。
- `subagent_manager.copyToolForHistoryGrant` 当前只供 session_read/session_expand grant 分支，Recipe 不经过该路径。按 root 精准范围裁定不改这个 history copy，也不改 manager/scope/tool factory。

## 唯一 focused 回归

在现有 `src/test/subagent_runner.test.ts` 保持唯一实际 child 场景，不 mock `runAgenticToolLoop`。独立 review 发现平台刷新遗漏 report 后，本场景已接入真实 `ScopedRecipeLoop.ets`：只读取实际源码、TypeScript `transpileModule`、受控 `Function` 执行，`require` 唯一允许 `@amber/chat-domain` 并映射真实 index，不复制平台算法。

1. 当前 primitive `file_read`、真实 `createRecipeTools` wrapper 经 `scopedSubAgentTools`；断言 wrapper 同一个对象且 envelope 保留。
2. 通过真实 `GenerationSubAgentRunner` 注入原始 report 工具；`configureRecipeLoop` 接到实际 child tools 和 subagent context，经过异步边界后调用实际 `configureEntryScopedRecipeLoop`，由其注入 Recipe Adapter/refresh。
3. 真实 provider loop 发起 `recipe__child_read`，真实 Recipe runner + dispatcher 执行一次 `file_read`，读取参数为严格绑定后的 `child.json`。
4. 断言刷新保留原 report 对象、schema required summary 和 execute 函数身份；真实 provider 发出 report 调用后，runner 收到其 structured summary。callback 仅一次，证明正常首轮完成无需 report retry；所有 provider 调用都在 callback 完成后。最终 Recipe `status=succeeded`、`outputs.content='child content'` 且 `recipe_v1` 已保存。
5. Store 列表出现同 primitive 的新 Recipe `outside_child`，刷新与真实 provider 目录仍没有该 Recipe；不扩大 child 原始 Recipe allowlist。

必要证据：

- 精确命令 `tsx --test --test-name-pattern='awaits Recipe configuration' src/test/subagent_runner.test.ts`。
- 修前 **1 failed / exit 1**，callback 实际未调用（0≠1），`/tmp/e4-child-loop-red.log`。
- 两行修复后 **1/1 PASS / exit 0**，`/tmp/e4-child-loop-green.log`。
- `tsc -p /tmp/e4-child-loop-tsconfig.json`，只 include 改动 runner 与现有测试文件，**exit 0**。
- `lint-arkts.sh` 仅 `subagent_runner.ts`，**exit 0 / 0 error / 187 warn**，`/tmp/e4-child-loop-lint.log`。现有 SDK CodeLinter 日志写权限按必要授权 escalated，未改 SDK 配置或规则。
- 指定两份改动的 `git diff --check`，**exit 0**。

未运行整份 subagent suite、全 suite、构建或设备。已给 root 冻结签名/回归证据；阶段 DI 与独立复核由 root 收口。

## 平台 report 精修审查记录

Reviewer `/root/e2_review_domain_ui` 查实：平台 refresh 二次 `scopedSubAgentTools` 会过滤 runner 在初次 scope 后注入的 `subagent_report`。正常运行因此被逼到额外 report retry；不能夸大为所有场景永远失败。

强化后的同一个 fixture 修前 **1 failed / exit 1**，明确断言 `refreshedReport === originalReport` 得到 undefined，`/tmp/e4-child-scoped-report-red.log`。fixture 的这个主动断言使测试 child 提前返回 failed，用于确认对象丢失；并非对正常产品最终状态的断言。root 拥有平台源码修复，固定 initial report 对象、从 primitives 分离并在 scoped 目录后 append；test owner 未改平台源。

root 修正后只执行同项，**1/1 PASS / exit 0**，`/tmp/e4-child-scoped-report-green.log`；窄 `tsc -p /tmp/e4-child-loop-tsconfig.json` 和指定测试/报告的 diff check 均 **exit 0**。原报告对象 identity/schema/execute、首轮实际 structured report、Recipe primitive/成功结果、新 Recipe scope 均在这一个真实平台 fixture 闭环。没有另造 source wrapper 或新 fixture、未再执行源 lint；root 最终 lint 覆盖平台源改动。
