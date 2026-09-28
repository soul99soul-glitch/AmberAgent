# E3 Python domain：实现与验证

2026-09-28。领域子范围 PASS；不是 native/HAP/双设备/整个 E3 完成声明。
已读 harmony-arkts skill、E3-contract.md 与 E3-runtime-design.md。

## Ownership 与接口

仅新增 `harmony/chat/src/main/ets/chat/python/` 5 个模块（229 行）和
`src/test/python_runtime.test.ts` / `python_tools.test.ts`，未改共享任务/Activity、
assistant/catalog/permissions、UI、native、DI、index.ts 或设备。

- models.ts：PythonExecuteOptions / PythonNativeResult / PythonExecuteRequest / PythonSnapshot。
- ports.ts：PythonTransportPort.version() 与 execute(requestId, options, signal?)。
  resourceRoot 只由平台 adapter 添加，不进入 Agent 入参。
- control.ts：PythonController/AbortSignal 与 UTF-8 字节计数、PythonError。
- runtime.ts：PythonRuntime({transport,taskStore})；version()、execute()、
  cancel(runId):Promise<void>、interruptForBackground():Promise<void>。
- tools.ts：createPythonTool({runtime,activityStore,conversationId}) 返回 python_execute。

PythonSnapshot 是 `{runId,runtime:'embedded_python',status:AgentTaskStatus,
exitCode,stdout,stderr,errorCode}`。TaskStore.taskId 与 runId 相同。
手动 UI 可使用自身 PythonController.signal 驱动停止，实际 settle 后才结束“取消中”。

## 状态与调用链

execute 同步复制已批准代码/输入/timeout → 新 UUID + 内部 controller →
TaskStore QUEUED 注册真实取消回调 → RUNNING → transport.execute →
真实 native result → status/输出 + TaskStore terminal/cancelCapability=false →
返回 snapshot 并解除 caller abort listener。

- abort 只发信号，不提前 resolve/reject 正在执行的 native promise。
  TaskStore.cancel 回调等待相同运行的 done，实际停止后才结算。
- 后台中断所有 live run。包括 task 注册尚未结束的窗口；此时 native 不再启动。
  原生 cancelled 结算为 INTERRUPTED/background_interrupted；回前台不重发。
- native completed 若赢取消竞态，保持 COMPLETED；不伪造 CANCELLED。
- native failed、cancelled、timed_out 分别映射真实终态，保留 stdout/stderr。
  未知 transport reject 只记 python_transport_error，未打印任意 native dump。
- domain 校验 source/stdin 的 256/64KiB UTF-8 bytes、整数 timeout 1–60000ms。
  128KiB combined output 限制及实际字节码停止由 native 提供，domain 不伪造硬停止。
- TaskStore 不持久化 source/stdin；spec 仅 timeout。summary 只保留两个 1600 字符输出尾。

python_execute needsApproval=true、allowsAutoApproval=false；参数 code/stdin/timeout_ms。
缺省 stdin=''、timeout=15000ms。真实 runtime='embedded_python'，不借 SSH 默认主机。
工具说明准确列出 Workspace 输入/保存、无网络/直接文件/子进程/pip、协作式取消与非系统安全沙箱。

结果 JSON **只保留 stdout/stderr，无重复 output**。Activity 内部仍组合二者供尾部显示。
128KiB 全控制字符输出的 JSON 转义回归证明总序列化长度 ≤128KiB×6+2048。
Activity 更新均使用原 toolCallId，旧执行结束不能覆盖新的活动槽。

## 验证证据

- 新模块未实现时，先运行新增 runtime 测试：1 fail / ERR_MODULE_NOT_FOUND；
  `/tmp/e3-python-domain-red.log`。
- 最终 Python 新增 24 项 + AgentTaskStore 17 项 = **41/41 PASS**，
  `/tmp/e3-python-domain-tests-final.log`。
  覆盖 Unicode/stdin、请求复制、UTF-8 边界、取消等待原生、完成赢取消、
  task 注册中的后台中断、多 run 中断、timeout/异常/后续成功、Activity 槽隔离与 JSON 预算。
- Python owned files 与其导入依赖的 scoped `tsc --noEmit`：**exit 0**；
  `/tmp/e3-python-domain-types-final.log`。
  首次全域 tsc 仅报当时同队尚未落地的 mosh/runtime.ts；未用 scoped 检查替代最终统一类型 gate。
- 最终 5 个领域文件 CodeLinter：**0 errors / 155 warns / exit 0**；
  `/tmp/e3-python-domain-lint-final.log`。警告是 SDK 对纯 TS 模块导入类型解析出的
  no-unsafe-*；源中没有声明 any，scoped TypeScript 严格检查通过。
  第一次 sandbox 运行因 SDK codelinter.log 写权限失败；同命令合法提权后完成，未改 SDK 配置。
- owned files `git diff --check`：PASS。

## 整合边界

UI owner 已收到冻结接口和 PythonController；root 负责 native adapter/DI/index、
assistant 开关/catalog/批准策略、Activity 注册、生命周期入口和构建/设备。

已向 root/reviewer 精确回喂：移除重复 output 后历史 Activity 需组合 stdout/stderr，
且 Python timed_out/interrupted/cancelled 要准确显示终态；这两处共享文件不在本 ownership。
本报告不宣称这些 root 整合点、真实 CPython/设备或整个 E3 已通过。

## 独立审查 P2 修复：活动卡 Unicode 尾部

reviewer 使用真实 runtime/tool/registry/history 链复现：
stdout=`'😀' + 'x'.repeat(1599)` 传入共享 ActivityStore 后 UTF-16 slice(-1600)
从 0xDE00 开始，活动卡出现半个 emoji。

仅在本 ownership 的 tools.ts 中复用 existing terminalTail(output.trim(),1600)，
先按 Unicode 边界裁 Activity 尾，再调用 completeWithExitCode/cancel；未改共享 Store。
工具结果的 stdout/stderr 保持完整，不影响 native cap 或序列化预算。

- 新增 completed/failed/cancelled 三个真实 runtime/tool 回归：修前 **0/3 PASS**，
  `/tmp/e3-python-activity-surrogate-red.log`；修后合并 **41/41 PASS**。
- scoped tsc 再次 **exit 0**；tools.ts 最终单文件 CodeLinter
  **0 errors / 59 warns / exit 0**，`/tmp/e3-python-tools-surrogate-lint.log`。
- 修复已交独立 reviewer 再验；历史 Python 专用分支由 root 精准修复。

## 统一 ArkTS 编译回喂

root 首次统一 HAP build 发现 tools.ts 的 stdin/timeout ternary 在 ArkTS 编译器下
未继承已通过 typeof 判断的 JsonValue narrowing（TypeScript 严格检查原本通过）。
仅在已验证分支显式 `as string` / `as number`，无新逻辑或兜底。
必要工具专项 **12/12 PASS**：`/tmp/e3-python-tools-arkts-types-tests.log`；
scoped tsc **exit 0**：`/tmp/e3-python-domain-arkts-types.log`。
后续统一 build 仍由 root 验证，不把该类型检查当 HAP 编译成功。
