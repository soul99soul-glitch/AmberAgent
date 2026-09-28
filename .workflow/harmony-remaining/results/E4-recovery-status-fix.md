# E4 Recipe 冷启恢复与终态状态精准修复

2026-09-28。root 接管 runner owner 的两处独立确认 P2。已读既有 tool_activity、runner checkpoint/terminalParent 及真实 recipe_runner fixture；只修改 `chat/tool_activity.ts`、`recipes/runner.ts` 和新增一个 focused fixture。未改 index/DI/ChatPage、未操作设备/build、未跑 full suite，保留其他编辑。

## 修复范围

- `toolActivityStatus` 仅针对 `recipe_import` 与 `recipe__*` 的已执行父工具，将显式 `status=cancelled` 映射为 cancelled、`outcome_unknown` 映射为 failed。其余工具/状态沿旧路径，不扩展通用协议；原始 output/未知结果说明不修改。
- runner 导出纯 `recoverInterruptedRecipes(conversation: Conversation): Conversation`。仅检查真正 `recipe__*`、空 output、run/started checkpoint，再用原 `checkpointOf` 确认固定 descriptor/inputs/progress/pendingStep，调用原 `terminalParent` 生成 `outcome_unknown`、`phase=finished`、清 pendingStep，明确无保存结果且不会重播。
- 函数不接受 Store/port、不 dispatch、capture、save 或访问 native；不生成调度器。普通工具、已有终态、ready/awaiting_approval、非 run 或无效 checkpoint 保持原 part。遍历保留未变化 part/message/branch/node 的引用与内容，Conversation 无变化时返回原引用；恢复后再次调用也返回原引用。
- root负责仅在 `!hasActiveConversationRuns`、conversation restore 短锁内调用，然后 `await repo.save` 并刷新全部/尾部 UI rows；函数本身不代替这个活跃执行隔离和持久化门。

## 一个必要 red → green fixture

`node_modules/.bin/tsx --test --test-name-pattern='cold Recipe recovery' src/test/recipe_runner.test.ts`（cwd `harmony/chat`）。仅新增并运行这一个目标，不跑已有 runner suite。

- `/tmp/e4-recovery-status-red.log`：真实旧 `toolActivityStatus` 返回 succeeded 而期望 cancelled，1 fail。
- 局部状态映射修后 `/tmp/e4-recovery-status-recovery-red.log`：缺少 cold recovery 入口，1 fail；没有伪造 runner.advance 触发冷启。
- `/tmp/e4-recovery-status-green.log`：1/1 PASS。使用真实 Adapter.prepare 生成固定 checkpoint，模拟已完成 read、compute 已 started 且未落终态；恢复为 unknown/failed，保留 completed_steps、failed_step、原父状态/空output，引用保留及幂等通过。取消与 unknown 卡片状态、原 unknown 输出说明同 fixture 覆盖；普通工具仍沿旧协议。
- fixture 的 primitive calls/capture/save/apply 全为零，恢复只改内存 Conversation，不重播前一步或当前副作用。

必要三变更文件窄 lint `/tmp/e4-recovery-status-lint.log`：0 errors / 462 warnings（域 facade/import 的 no-unsafe 等实际报告，未声称0警告）；diff-check通过。完整类型/build、冷启动锁/保存/rows消费者与设备验收交root，未以域单测宣称入口已接线完成。

root真实compile `/tmp/e4-release-build.log` 回喂SDK TS不支持filter的新版predicate推断，`resultText` 已仅增加UIMessagePartText导入与map显式cast；过滤、顺序和换行行为不变。runner单文件lint `/tmp/e4-runner-sdk-predicate-lint.log` 0 errors / 400 warnings，未重跑已过fixture，保留root及其他owner编辑；待root下一轮build。
