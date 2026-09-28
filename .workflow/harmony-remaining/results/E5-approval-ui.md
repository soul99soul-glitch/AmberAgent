# E5 插件审批投影

2026-09-28。新 helper `entry/src/main/ets/components/PluginApprovalSummary.ets` 复用 `RecipeApprovalView`，无新增 Builder、无 store 读取、无其他消费者修改。

```ts
export type PluginApprovalView = RecipeApprovalView;
export const pluginApprovalView = (parent: UIMessagePartTool): RecipeApprovalView | null;
export const pluginAskUserPart = (parent: UIMessagePartTool): UIMessagePartTool | null;
```

run 的 version/tool/backend/hash、输入与精确 child 全取 parent.metadata.plugin_v1 的固定 checkpoint。普通 parent.input 即便变化也不重建 child。试跑 expectedProvided=false、显式 null 与具体 JSON 分开显示；完整 test 预览在 canonicalJSON，短摘要不把大 JSON 塞入审批卡。

import 的固定审核 JSON 包括 manifest、全部 file_hashes、package_hash、trust（发布者信任与 fingerprint）、派生 permissions、permission_diff/expanded、enable、base_hash/source。标题区分安装与安装并启用，短摘要明示发布者未信任和权限扩大。canonicalJSON 是同一 layout 的字段名，内容是完整固定审核 JSON，不能把它标成“只有 canonical manifest”。metadata 缺失/身份不符返回 null，不读取当前 live package 来兜底。

ask_user 仅从保存的 pendingStep 投影，input/output/metadata 保留子记录，toolCallId 与 approvalState 取父记录。回答仍须由 AgentMessageRow 的真实 parent message/part locator 提交；普通 approved 不转换为 answered，也不修改 child 的 ID/state。SSH/Python 参数摘要继续由 root 接线后的既有 child guard 展示。

主 agent 需接入现有 ChatMessageTools、Preview、AgentMessageRow；Preview 应在 run 的 canonicalJSON 非空时展示固定 descriptor/input/test，并用“固定审核内容/固定内容 hash”之类准确标签，不声称它只有 manifest。现有 Recipe layout 的字体、间距、颜色和 child 参数区复用。

发现并通知 root 的真实 execution 问题：当时 `plugins/runner.ts` 的 awaiting_approval 只接受 parent approved，没有接受/转交 answered，会让插件 ask_user 回答卡住。execution owner 需沿 Recipe 既有方式修复；本任务不越权修改 runner，也不由 UI 伪造答案绕过。

必要验证只新增一组 `harmony/tests/plugin-approval-summary.test.cjs`，沿原 recipe-approval-summary 的 TypeScript transpile/VM pattern，覆盖固定 child/input/hash、expected 缺省/null/JSON、精确父答案身份、普通 approve 不伪答、全部 import hash/trust/diff/enable/source、缺失 metadata 返回 null。`node --test harmony/tests/plugin-approval-summary.test.cjs`：1/1 PASS。

窄 lint 一次：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/components/PluginApprovalSummary.ets`，exit=0 / `No defects found in your code`，日志 `/tmp/e5-approval-ui-lint.log`。SDK 日志写权限正常 escalation。helper 少于150行，未执行设备、build 或 full suite；consumer 接线及真实 UI 由 root 统一验收。
