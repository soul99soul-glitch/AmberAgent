# Phase 2 — 上下文筛选（Jev Android）

代码状态：完成
离线检查：passed（JVM 定点测试全绿）
真实 API：blocked（无 Key）
用途模式：CONTEXT_SELECTION=off（默认）
基线：同 Phase 1 工作区

## 设计要点（相对上游计划的关键适配）

- **插入点**：`ConversationContextEngine.prepareContext` 的两个 return 之前、压缩与 fit **之后**——保证压缩摘要源与持久化会话始终是原文，投影只修改发往模型的请求副本（请求副本/canonical 分离硬门槛）。
- **与既有确定性裁剪的关系**：`PreparedContextEditor`（>16k takeMiddle 截断、clearable 名单、失败/审批/多模态硬守卫）保持原样作为硬策略与回退；Jev 投影处理 >8,000 字符纯文本已完成工具输出，按空行+围栏块切分、相邻块打包控制 ≤32 题。
- **must-keep 块级**：error/failed/exception/denied/approval/pending/permission/policy/next_page/page_token/cursor/todo/credential/unresolved/required 命中即整块保留（宁可多保留）；未执行/待审批/含多模态部件的输出整体不筛。
- **省略标记**：`[omitted by context filter: block i/N of <tool> output… Call conversation_expand if needed.]`——恢复原语 conversation_expand 已是常驻上下文工具（RESIDENT_CONTEXT_TOOLS）。
- **幂等**：缓存锚含 toolCallId+任务文本哈希+块摘要；vision fallback 二次准备命中缓存不再计费。

## 已执行检查

- `JevToolOutputProjectorTest` 8 通过：active 隐藏无关块保留相关+must-keep、shadow 原样返回（含网络调用一次的指标路径）、off 零网络、短输出不筛、error 信号块级保留、splitBlocks 围栏完整性、bundleBlocks 目标尺寸与 must-keep 标记。
- 回归：`PreparedContextEditorTest`、`ChatGenerationRoundEngineTest` 全绿（接线未改变确定性路径）。
- `:app:compileDebugKotlin` 成功。

## 对照结果

无 Key，真实 token 节省对照 blocked。离线验证投影只影响请求副本。

## 阻塞、回退与下一步

- 关闭 CONTEXT_SELECTION 立即回到纯确定性路径；会话存储与压缩从未接触投影副本。
- 真实 Key 后需要 20+ 长文本样本的 baseline/active 对照（门槛：必要证据漏失=0、原文恢复率 100%、输入 token 中位数 −20%）。
