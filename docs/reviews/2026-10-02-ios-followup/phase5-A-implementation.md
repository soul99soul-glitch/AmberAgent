# Phase 5 A：工具上下文筛选与压缩保留

## 交付与生产接线

- `jev_context.ts` 纯领域模块：安全本地只读结果的段落投影、代码/表格原范围、结构失败/未知执行/审批/TODO/分页保护、跨轮合法同参重读全文、异步保留窗口判断。没有新网络协议、持久化 schema 或通用调度框架。
- `JevContextSupport.ets` 将现有 KV、逐用途模式与 output/task/metadata 许可、`jevEvaluatePurpose`、Entry abort controller 接到同一个 bounded conversation runtime。
- `ConversationRunDependencies.prepareMessages` → `prepareContext` → Jev 请求副本 → 既有 editor（明确保留 IDs 跳过裁剪/清空）→ provider。
- `ContextCompactionSupport.compactConversationNow` 和自动/强制压缩均通过 `CompactEngineDeps.prepareToolResults` 消费同一个投影。完整历史提供最新任务语境，压缩源仍按原 message IDs 选入；原历史、source indices/source IDs、compact DB 格式不改。Jev off 不改变原压缩源文本。
- 只筛 `file_read/file_search/file_list/session_read/session_search` 的已执行、非拒绝、单 text、大于 8000 字符结果。HTTP/MCP、搜索活网、实时页面、写工具、混合图片/文档均不筛。
- 全文要求不发 Jev；只有具 output 许可的 active 用途可改变 prepared editor 的保留行为。off/shadow 全文请求仍沿原 editor。

## 决策、归属与范围

- 每会话最多 256 个 result identities，全局最多 16 个 conversation。结果按 locator、call ID、原 text/input 精确比较；分支不存在/输出变化时清理相应决定。
- 新 run 中止旧在途判断，完成决定跨 turn 保留。应用前校验 current run、abort、对应用途配置和范围；selection 与 retention 分别校验/失效，互不重置另一用途。
- output 许可是准入，task text 与工具 metadata 独立控制外发字段。未允许 task 时 state 不含任务/对话文本；未允许 metadata 时不含工具名/参数。全文/重读匹配只在本地做。
- retention 在近期窗口内且模型已使用结果后后台判断，`p >= 0.7` 保留。prepare 不等待 retention 网络；首次出窗口仍未判定则固定沿原清空，迟到结果不能翻回原文。
- shadow 仅观察并去重，不改 projection/retention；判断失败不丢失原始存储。

## RED/GREEN 与 review 回喂

- 新测试先因模块不存在 RED；随后落实行为并验证实际 `prepareContext`、editor 与手动压缩 prompt/source IDs。
- 独立 reviewer 指出 Harmony 会复用一个 assistant message 追加新 tool part；仅按 messageIndex 会把同参重读再隐藏。定点测试 12 GREEN/1 RED 后，记录 partIndex 并按 `(messageIndex, partIndex)` 先后排序，13/13 GREEN。
- 独立 reviewer 指出显式全文早返回使 off/shadow 也豁免旧清空。真实 editor 测试 13 GREEN/1 RED，修复为 active+许可才豁免，14/14 GREEN。
- 同一 assistant 尾部文本代表已使用结果：14 GREEN/1 RED，复用 part 顺序补判定，15/15 GREEN。
- 首次隐藏之前已有的同参调用不能倒算“隐藏后重读”：15 GREEN/1 RED，仅新出现结果参与重读归因，16/16 GREEN。
- 实际 `file_read` 输出 JSON.content、`session_read` 输出 JSON.transcript。真实 `buildFileReadJson` 回归 16 GREEN/1 RED 后，仅对这两个确切 schema 解包正文/替换正文/重编码，保留 envelope 所有其他字段，17/17 GREEN。
- 构造 state 后撤销 task/metadata 许可、在 shared evaluator 首次读取之前的 wire race：C 新增 expectedSettings 第五参数；A 保留本次构造输入的原 settings snapshot透传，两用途回归确认，18/18 GREEN。

## 验证

- `npx tsx --test src/test/jev_context.test.ts`：18/18 GREEN。
- 既有 `context_engine.test.ts`、`manual_compaction.test.ts` 加新增 focused 的最终受影响回归 39/39 GREEN；日志 `/tmp/phase5-A-tests.log`。
- `npx tsc --noEmit`：通过（其他 workers 中途未完成的 test 类型问题已由各 owner 修复）。
- A 五个变更文件 ArkTS lint：0 errors，现有跨 HAR 类型解析仍产生 warnings；日志 `/tmp/phase5-A-lint.log`。最终新增局部修复后再跑定点 lint。
- A 无新 UI；设置/notice UI 属 C，由主 agent 串行构建、签名与设备截图验收。未调用付费 Jev API，也未运行构建/装机。
