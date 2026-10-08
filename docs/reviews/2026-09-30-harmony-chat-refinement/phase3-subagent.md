# Phase 3：子代理历史输出恢复

## 已确认缺口

鸿蒙 `SubAgentRunSheet` 原先只显示父组件传入的实时文本。进程重启后 manager 没有内存 snapshot，即使子代理最终结果和 transcript 已持久化，详情仍显示“等待子代理输出…”。

Android `ChatMessageSubAgentStep.kt` 当前按实时文本、snapshot、transcript、tool final summary 的顺序恢复显示。鸿蒙现有 `subagent_manager.ts` 已把完整 `display_text` 写入 finished JSONL 事件，并将 final result 编码为 JSON 字符串写回工具消息；复用这些数据即可闭环，无需恢复执行 engine。

## 本次实现

- `subagent_display.ts` 从同一 `run_id` 的最新子代理工具结果解析 `result.summary`，处理 manager 的 JSON 字符串格式。
- `SubAgentRunDisplaySupport.ets` 接入现有 `SubAgentStore` 文件 port。历史读取使用当前 run 的固定路径，继续复用 canonical containment 与 256 KiB transcript 尾窗。
- `SubAgentRunSheet` 优先显示父组件传入的实时文本/snapshot，无文本时恢复 transcript，最后回退到工具结果摘要。读取完成仍无内容时显示明确空态，关闭按钮保留 44 vp 点击高度。
- sheet 消失、run 切换或后续读取开始后，旧异步结果不会覆盖当前状态。

`ChatPage` 调用接口不变，现有订阅与取消订阅逻辑继续生效；本次没有修改该文件。

## 验证

- 新增 2 条 focused 行为测试：同 run 的原生 final result 恢复；重启后 transcript 优先、损坏尾部回退及 run 路径边界。
- 与现有 13 条 transcript 边界测试一起运行，共 **15 pass / 0 fail**。日志：`/tmp/amber-phase3-subagent-tests.log`。
- 3 个生产文件 targeted ArkTS lint：**0 error / 20 warn，exit 0**；警告为跨模块导入的 linter 类型解析。日志：`/tmp/amber-phase3-subagent-lint.log`。
- 修改文件 `git diff --check` 通过。

独立 review、集成构建、签名与模拟器/真机 UI 验证由主 agent 继续完成，本记录不把这些未执行检查列为通过。

## 独立 review 回喂：首 token 前失败

review 确认 `manager.finish()` 可能保存空 summary 和空 display_text，但带有真实 `result.error`。异步启动工具最初返回 running payload，因此重启后也可能只有 finished JSONL 持有该错误。

- 工具结果的 summary 为空时显示实际 result.error。
- 现有受限 transcript reader 增加 `includeFinalResult` 参数，默认 false 保留旧语义；仅历史显示调用 true。finished 事件仍优先正文，再读取 summary / error；没有新增读取窗口或执行恢复逻辑。
- 新增同 1 条用例先复现工具结果空态，再复现只有 running 工具消息的历史空态。修复后与原有检查共 **16 pass / 0 fail**；同时确认默认参数的旧语义及正常正文优先。红日志：`/tmp/amber-phase3-subagent-error-red.log`、`/tmp/amber-phase3-subagent-transcript-error-red.log`；绿日志：`/tmp/amber-phase3-subagent-error-green.log`。
- 两个生产文件 targeted lint：**0 error / 23 warn，exit 0**，日志 `/tmp/amber-phase3-subagent-error-lint.log`；源文件 diff check 通过。
