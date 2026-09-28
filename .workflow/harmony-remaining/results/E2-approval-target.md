# E2：审批续跑的 SSH 目标持久绑定

2026-09-28。已实现 root 授权的最小修复，仅修改 `chat/tool_loop.ts`，新增 `chat/terminal_approval.ts` 和 `src/test/tool_invocation_metadata.test.ts`。没有改 `terminal/`、AgentTool execute 协议、registry、index、ChatPage、AppContainer 或设备；没有提交 Git。

## 问题与结果

ChatPage 在审批后重建 runtime/tool factory，factory 内存捕获的 SSH target 会变成当前配置；若原批准调用没有持久 target，改默认主机或 host 后可能执行到另一个目标。

工具循环新增可选 `ToolLoopOptions.captureInvocationMetadata`。仅生成新调用（auto态）时调用，在 permission pending 保存前合并 metadata、按既有输入part身份写回 conversation 并保存。已有批内 metadata 与 permission_trace 保留。resume 分支继续使用原持久 part，绝不重新 capture；没有 callback 的旧工具行为保持。

`createTerminalApprovalGuard` 创建时冻结 profiles 和 default target；只捕获 terminal_execute/job_start/session_start/session_exec。session_exec 从已有session读取profileId，以显式target绑定，不跟随默认。metadata格式：

```json
{"terminal_target":{"profileId":"p","digest":"稳定目标字段元组","usesDefault":true},"terminal_target_label":"fixture@host-p:22"}
```

dispatcher.before 用这个持久snapshot核对当前 `runtime.captureTarget`，同时核对调用入参/profile与session所属profile；缺snapshot、删除或改变目标都返回 `status=failed / error_code=target_changed / recoverable=false`，不给 execute 副作用机会。原default绑定也验证默认ID；显式profile/已存在PTY不受无关默认变化影响。read/wait/stop和非terminal不加或重绑target。

`terminal_target_label` 从同一冻结 profiles.snapshot 的 username/host/port构造，随pending持久化。resume不从当前default取标签；默认变更被拒绝时仍保留原label。explicit profile和其session_exec显示相同label。它不包含secret、credentialRef或digest展开；缺有效target仍为null、label为空字符串、继续拒绝。字段已直接同步UI worker，审批卡消费这个持久字符串。

`profile_id` 的undefined/null/空字符串统一选择默认，与真实terminal/tools.ts的stringArg完全一致；空白字符串不扩成新的默认规则。capture与before使用同一个小判定函数。

helper 100行，使用现有模型类型和 `Pick` 收窄注入方法，不新增运行接口层、调度器、全局状态或 execute 参数。

## root 精确接线

公开符号由 root 加到 `chat/src/main/ets/index.ts`：

```ts
export { createTerminalApprovalGuard } from './chat/terminal_approval.ts';
export type { TerminalApprovalGuard } from './chat/terminal_approval.ts';
```

函数及返回值：

```ts
createTerminalApprovalGuard(
  runtime: Pick<TerminalRuntime, 'captureTarget' | 'readSession'>,
  profiles: Pick<SSHProfileStore, 'snapshot'>,
): TerminalApprovalGuard

interface TerminalApprovalGuard {
  captureInvocationMetadata: (part: UIMessagePartTool) => JsonObject | null;
  hook: ToolInvocationHook;
}
```

每次 buildToolLoop 使用同一个 guard 组装该轮 dispatcher 和 options：

```ts
const terminalGuard = createTerminalApprovalGuard(terminalRuntime, sshProfiles);
const dispatcher = new AgentToolDispatcher({
  hooks: [...defaultToolInvocationHooks(), terminalGuard.hook],
});
// 既有 loop.options:
// dispatcher,
// captureInvocationMetadata: terminalGuard.captureInvocationMetadata,
```

若现有 dispatcher还有其它hooks，保留它们并在真实 execute前接入guard.hook；不新增dispatcher方法。`hook.before(request)` 是既有协议，返回失败ToolInvocationResult短路执行。终端工具 factory和此guard应在同一 buildToolLoop构建；恢复时可以重建guard，但resume不capture，旧批准缺context会明确失败，需发起新的终端调用。page已有ToolLoopOptions类型入口可透传callback；不要在批准点击处拿新target补旧metadata。

## 验证

- 先新增真实loop测试复现：pending保存后 `captured` 为undefined；序列化审批后resume仍缺原调用metadata（2/2失败）。随后实现，修复后通过。
- 测试走真实 `runChatTurnWithTools → pending → JSON持久store → approved落盘 → runToolLoopContinuation`，续跑创建新 registry/factory/guard。profiles使用真实SSHProfileStore/KV和targetDigest；transport目标查询仅收窄测试夹具，execute计数证明拒绝时不执行。此项不是SSH连接或设备认证验收。
- 后续先测试复现：空profile_id续跑没有调用native start（0≠1），持久label不存在（undefined≠fixture@host-p:22），explicit/session label均缺失。修复后通过。空profile_id用真实TerminalTools、TerminalRuntime和TaskStore执行到typed transport夹具，证明factory参数解析和guard一致，而不只测试helper返回值。
- 新增15项：批内metadata保留；resume零recapture；auto allow执行前保存；换default拒绝且旧label保留；改host拒绝；未改执行；缺旧context拒绝；其它tools/read/wait/stop无target变化；多目标独立保存；session显式绑定/无关default可继续；session目标修改拒绝；factory时刻冻结；explicit/session label一致；缺target为null/空label并拒绝；空profile_id默认真实续跑。
- 命令：`./node_modules/.bin/tsx --test src/test/tool_invocation_metadata.test.ts src/test/tool_loop.test.ts src/test/tool_dispatcher.test.ts src/test/tool_permission.test.ts src/test/tool_registry.test.ts`（cwd为harmony/chat）。结果96/96通过，0失败；证据日志 `/tmp/amber-e2-approval-tests.log`。
- `./node_modules/.bin/tsc -p tsconfig.json --noEmit`（cwd为harmony/chat）通过。
- 精确 `harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/chat/tool_loop.ts harmony/chat/src/main/ets/chat/terminal_approval.ts`：最新0 error / 360 warn / 0 suggestion。工具首次受sandbox拒绝SDK日志写入，使用同一验证命令的权限升级重跑成功；未改变规则/关闭检查。warn包含此窄范围TS导入的unsafe类型提示；完整TS strict检查通过，不声称零warning。最新证据 `/tmp/amber-e2-approval-lint.log`。
- 修改的tool_loop diff-check通过。构建/HAR编译、页面接线、signed安装与设备审批点击由root统一执行，未在本子任务宣称通过。
