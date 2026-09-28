# E3：Mosh 目标与协议审批快照

2026-09-28，基线 E2 `87f69a88c`。本子任务只修改 `chat/terminal_approval.ts`、`src/test/tool_invocation_metadata.test.ts`、`entry/components/SSHApprovalSummary.ets`，并新增本报告。没有修改 domain runtime、原生 transport、ChatPage、DI 或其它 UI；没有操作设备、提交 Git。

## 结果与接线

`createTerminalApprovalGuard` 在 E2 的既有 metadata capture/hook 上增加 Mosh 分派，120 行，无新审批系统或调度器：

```ts
createTerminalApprovalGuard(
  runtime: Pick<TerminalRuntime, 'captureTarget' | 'readSession'>,
  profiles: Pick<SSHProfileStore, 'snapshot'>,
  moshRuntime?: Pick<MoshRuntime, 'captureTarget' | 'readSession'>,
): TerminalApprovalGuard
```

原两参数调用仍可用。第三参数使用 `chat/mosh/runtime.ts` 的真实 `MoshRuntime`，方法与 E2 同形。返回的 `captureInvocationMetadata` 和 `hook` 沿原 ToolLoopOptions / AgentToolDispatcher.before 接线，不改变工具 execute 协议。

SSH 的 execute/job_start/session_start/session_exec 与 Mosh 的 `terminal_mosh_session_start` / `terminal_mosh_session_exec` 捕获并持久化：

```json
{
  "terminal_protocol": "remote_mosh",
  "terminal_target": { "profileId": "p", "digest": "冻结目标字段元组", "usesDefault": true },
  "terminal_target_label": "fixture@host-p:22"
}
```

protocol 按精确工具名固定为 `remote_ssh` / `remote_mosh`；目标与标签在 factory 创建时冻结，pending 保存时写入。续跑继续使用持久 metadata，不从当前默认配置重新 capture。Mosh exec 查询 Mosh runtime 的已有 session profileId，显式绑定；不查询 SSH session。read/wait/stop 保持原有 metadata，不重绑目标。

before 钩子先核对持久 protocol，再选择相应 runtime 核对 profile/default/digest 与入参/session。Mosh 缺 protocol、协议不匹配、缺 Mosh runtime、缺目标或目标变化都明确返回 `status=failed / error_code=target_changed / recoverable=false`，不进入 execute。历史 SSH pending 没有 protocol 可以继续按 SSH 核对原 target；这种兼容不会应用于 Mosh。`profile_id` 的 undefined/null/空字符串均选默认，与两种真实工具工厂一致。

`SSHApprovalSummary.ets` 保留 `isSSHApprovalTool`、`sshApprovalTargetLabel`、`sshApprovalCommand`，新增 `sshApprovalProtocolLabel`。Mosh start 显示“启动远程 Mosh 交互 Shell”，有效 Mosh 协议显示“Remote Mosh”；缺/错协议或目标显示对应不可用文本。标签继续只用持久 `terminal_target_label`，不含 secret、credentialRef 或 digest 展开。

只读检查确认 root 已将第三参数接到 ChatPage buildToolLoop，并在 ChatMessageTools / ChatMessageToolPreviewSheet 消费协议标签；这些文件由 root 修改，本子任务没有接管其 ownership。

## 验证与边界

- 红：扩展协议/default/session 测试后，旧 E2 helper 的 26 项专项测试出现 10 失败、16 通过，复现缺协议和目标变化仍执行。证据 `/tmp/amber-e3-approval-red.log`。
- 绿：新增 13 项 E3 专项，现专项共 28 项；与 tool_loop、dispatcher、permission、registry 一起共 109/109 通过，0 失败。命令：`./node_modules/.bin/tsx --test src/test/tool_invocation_metadata.test.ts src/test/tool_loop.test.ts src/test/tool_dispatcher.test.ts src/test/tool_permission.test.ts src/test/tool_registry.test.ts`，cwd `harmony/chat`。证据 `/tmp/amber-e3-approval-tests.log`。
- 专项覆盖：协议与标签持久化、旧 SSH 无协议兼容、Mosh 无协议拒绝、双向协议错配拒绝、默认与 host 变更拒绝、SSH/Mosh 混合批与正确 session runtime、Mosh session profile 变更拒绝、缺 Mosh runtime 不借 SSH 执行、read/stop 无重绑，以及真实工具续跑两条链。E2 原 15 项回归继续通过。
- 真实续跑链使用真实 `createMoshTools`、`MoshRuntime`、`SSHTargetResolver`、`SSHProfileStore`、`AgentTaskStore` 和既有 chat loop；仅底层 SSH/UDP transport 为类型化夹具。`pending → JSON store → default p 改 q → approve 落盘 → 重建 factory/guard → continuation` 返回 target_changed，SSH bootstrap=0、UDP start=0，任务列表与 Activity 为空，旧主机标签/protocol/target 保留。目标未变则启动一次，任务 sourceToolName 精确为 `terminal_mosh_session_start`、runtime 为 `remote_mosh`，之后主动 stop 清理。
- `./node_modules/.bin/tsc -p tsconfig.json --noEmit`（cwd `harmony/chat`）通过，包含真实 Mosh 类型和专项测试。证据 `/tmp/amber-e3-approval-types.log`。
- 精确 lint：`harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/chat/terminal_approval.ts harmony/entry/src/main/ets/components/SSHApprovalSummary.ets`，0 error / 93 warn / 0 suggestion。SDK 的跨模块类型解析产生 unsafe 类型提示，严格 tsc 已通过；不声称零 warning。证据 `/tmp/amber-e3-approval-lint.log`。后续仅补测试，没有改变这两份已 lint 的产品源文件。
- 所有自有源文件 `git diff --check` 通过。helper 120 行、UI 文案 helper 32 行、专项测试 549 行。

这些结果证明审批持久化、真实 domain 调用链及拒绝时零启动；不等同于原生 UDP、HAP、页面排版或设备点击验收。统一构建/签名、亮暗 UI 截图、设备 Mosh 重连与独立阶段审查由 root 整合执行。
