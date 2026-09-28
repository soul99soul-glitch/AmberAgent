# E0 测试基线修复

## 结论

仅修改 `harmony/chat/src/test/tool_loop.test.ts` 与 `harmony/deepread/src/test/observable.test.ts`。未修改生产行为、未构建签名、未操作设备、未提交。

## 根因与修复

- Chat 窄套件修复前为 21/24：预算与步数的 3 个夹具每步使用同名同参数调用。LoopGuard 将第二次调用置为 `duplicate_tool_call`，本步执行集为空，循环因此在 2 次 provider 调用后退出，无法走到预算门槛。
- 将上述 3 个夹具的查询参数改为每步不同的 `step-${i}`，保留最小步数钳制的 16 次 provider 调用和 WARN 提示断言。没有豁免工具或关闭 LoopGuard。
- 最终隐藏工具夹具的旧注释误写 FINAL 从 `stepIndex=15` 开始；生产规则是 remaining<=2，即从 14 开始。该夹具改为前 14 步有效工具调用、FINAL 步文本收尾，并验证 14 个调用都得到真实执行输出，避免隐藏工具后仍伪造调用却只计工具 part 数量。
- DeepRead observable 窄套件修复前为 5/5；全套件负载下的固定 100ms 等待无法保证发生至少 3 次轮询。该文件的 5 个用例全部改用 `node:test` 的可控 `setInterval` 时钟，每次推进后等待 fetcher 的 Promise 完成。
- 原有通知去重断言现在精确验证 4 次 fetch 只通知 1 次。缓存即时回调、最后一个订阅者退出、abort 停止轮询、多个订阅者收到同一更新均使用确定次数断言。`t.after` 中止轮询，断言失败也能清理。

## 验证

执行环境：本机 Node v26.8.1。

| 命令（在对应模块目录） | 结果 |
| --- | --- |
| Chat：`./node_modules/.bin/tsx --test src/test/tool_loop.test.ts src/test/agent_loop_budget.test.ts` | 30/30 PASS |
| DeepRead：`./node_modules/.bin/tsx --test src/test/observable.test.ts` | 5/5 PASS |
| Chat：`./node_modules/.bin/tsc --noEmit` | 首次 exit 0；最终复查 exit 2，见下方共享工作区说明 |
| DeepRead：`./node_modules/.bin/tsc --noEmit` | exit 0 |
| `git diff --check`（上述 2 个测试文件） | exit 0 |

本子任务未重新运行全仓测试；主集成回路负责完整套件结果。全仓测试源码中未检索到名为 LoopGuard / `duplicate_tool_call` 的独立专项用例，现有测试未被删除或跳过。

最终 Chat 类型检查时，其他并行任务新增的 `src/test/conversation_export.test.ts` 有两个错误：第 9 行导入的 `messageImageExportText` 尚未导出；第 73 行 reasoning part 缺少必需的 `metadata`。首次类型检查时该模块为 exit 0，最终复查未报告本子任务拥有的两个测试文件错误；未触碰其他 agent 的文件，已通知主 agent 完成整合。
