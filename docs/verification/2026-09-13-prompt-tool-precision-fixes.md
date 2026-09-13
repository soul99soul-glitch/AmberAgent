# Android prompt / tool 精准修复

日期：2026-09-13。基于当前工作树；起始 HEAD 为 `f243eb7aa20cb1d9456a46af2bda35a0300430a5`。本次处理审计中六项有生产调用链证据的问题，保留已有 WIP，未提交。

## 实现与边界

| 问题 | 最小修复 | 回归证据 |
|---|---|---|
| Claude / Gemini / Responses 丢后续 system | 在现有序列化函数中按顺序合并全部 SYSTEM 文本；Claude 继续使用现有缓存标记处理 | 三个 provider 的多 system 用例，以及原有消息/缓存用例 |
| 正常等待、读改后重读被重复守护拦截 | 仅允许明确观察工具的新 callId 跨步骤重复；同批重复、已计数 callId、动作去重保留 | 等待三次到完成、读→改→读、原重复守护用例 |
| WebMount unknown 显示成功 | 主消息卡片新增 UNKNOWN 状态，复用已有“结果未知”文案、警告色和时钟图标 | unknown / verified / failed 三态 |
| 首条超长记忆绕过预算 | 统一使用 `used + cost > maxChars`，跳过装不下的完整条目；原始记忆不变 | 超长置顶记录被跳过，较短相关记录仍能选中 |
| stale compact 阻止新压缩 | 计划阶段复用注入阶段的 `validCompletedCompacts` 校验 | 无效 source ID 不再推进压缩起点 |
| 七天清理删除待回放结果 | DAO 清理查询排除现有 live-state run 的 effect，复用 `RUN_TERMINAL_LIVE_STATES` | 八天前 FINISHED 结果回放成功；已完成 run 的旧结果仍清理 |

观察工具集合仅包含已核对的文件、后台任务和页面观察操作。没有统一按 `READ_ONLY` 豁免，因为现有分类还包含执行工具；`terminal_execute`、任务启动/取消、页面写入、远程写入和会调用视觉 provider 的 `wm_visual_read` 继续走原守护与审批。

复核纠正：之前报告中提到的 `ChatPage` 工作区预览会过滤直接 `wm_*` 调用，该路径不可达此 WebMount 缺陷，因此未修改它，也没有扩展共享 `ToolActivityStatus`。UI 改动只落在实际消费 WebMount receipt 的主消息卡片。

本次没有加入摘要 fallback、额外重试、数据库迁移或新的持久化/权限框架。提示词内容调整、长结果分页回读和其它审计改进项留在本次六项缺陷之外。

## 验证

9 个定点测试类，共 **105 passed，0 failed，0 errors，0 skipped**：

| 测试类 | 数量 |
|---|---:|
| ClaudeProviderPromptCacheTest | 8 |
| GoogleProviderMessageTest | 12 |
| ResponseAPIMessageTest | 12 |
| DefaultRunKernelTest | 24 |
| MemoryRecallStoreTest | 9 |
| ConversationContextPlannerTest | 9 |
| RunRecoveryServiceTest | 9 |
| ToolEffectLedgerTest | 19 |
| ChatMessageToolStatusTest | 3 |

使用本机 JDK 21 / Android SDK。可重跑：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :ai:testDebugUnitTest \
  --tests 'app.amber.ai.provider.providers.ClaudeProviderPromptCacheTest' \
  --tests 'app.amber.ai.provider.providers.GoogleProviderMessageTest' \
  --tests 'app.amber.ai.provider.providers.openai.ResponseAPIMessageTest' \
  :app:testDebugUnitTest \
  --tests 'app.amber.core.ai.DefaultRunKernelTest' \
  --tests 'app.amber.core.memory.MemoryRecallStoreTest' \
  --tests 'app.amber.core.context.ConversationContextPlannerTest' \
  --tests 'app.amber.feature.runtime.RunRecoveryServiceTest' \
  --tests 'app.amber.feature.runtime.ToolEffectLedgerTest' \
  --tests 'app.amber.feature.ui.components.message.ChatMessageToolStatusTest' \
  --offline --console=plain -Pksp.incremental=false
```

构建及定点测试通过，本次涉及文件的 `git diff --check` 通过。独立闭环复核确认观察豁免没有绕过执行账本，保留了原审批、同批去重和已计数 callId 保护。

证据层级：provider 请求序列化为本地 JVM 验证；恢复为 Robolectric + Room 的真实 DAO/会话持久化链；工具循环使用真实 kernel 与受控工具/模型入口；UI 为状态映射测试。没有真机 UI、实际进程终止、远程 provider 或付费模型验收，也不据此声称完成率或性能已提升。

本轮日志与 JUnit 结果保存在 `/private/tmp/amber-six-fixes-test-evidence`、`/private/tmp/amber-six-fixes-focused-tests.log` 和 `/private/tmp/amber-six-fixes-memory-ui-tests.log`。
