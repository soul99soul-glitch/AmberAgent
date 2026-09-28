# E2 SSH 跨 worker 合同

2026-09-28。主 agent 已审阅并冻结，实施变更须集中通知，保留小而直接的现有协议。范围是原生 Remote SSH、profile/安全仓、exec/job/PTY 和真实端内 UI；不包含 Python/Mosh、SFTP、外部 WebTerminal 或新的任务调度器。依据：[只读调研](../results/E2-ssh-design.md) 和当前 `chat/agent_task.ts` / `chat/tool.ts` / `chat/tool_registry.ts`。

以下名字、参数和字段是共享边界。`.d.ts` 与 domain model 保持结构一致，domain 不 import 原生模块；entry adapter 做直接映射。实现确需变更合同，先通知主 agent，不能各自造另一套签名。所有 worker 共享仓库，只改被分配文件，保留 E0/E1 和他人的编辑。

## 1. 原生 `.d.ts`

追加到 `entry/src/main/cpp/types/libamber_native/index.d.ts`，保留 E1 `countBatch`。`requestId` 是每次 probe/start 独立 UUID，`handle.id` 是不可复用的原生注册表 ID，不暴露指针。

```ts
export type SSHAuthMethod = 'password' | 'privateKey';
export type SSHHandleKind = 'exec' | 'pty';
export type SSHReadState =
  'running' | 'exited' | 'failed' | 'cancelled' | 'timed_out' | 'disconnected';
export type SSHCloseReason = 'cancelled' | 'disconnected' | 'release';
export type SSHNativeErrorCode =
  'invalid_arguments' | 'unknown_handle' | 'network_error' |
  'host_key_mismatch' | 'authentication_failed' | 'unsupported_key' |
  'connection_timeout' | 'cancelled' | 'channel_error';

export interface SSHNativeError extends Error {
  code: SSHNativeErrorCode;
}
export interface SSHHandle {
  id: string;
  kind: SSHHandleKind;
}
export interface SSHProbeOptions {
  host: string;
  port: number;
  timeoutMs: number;
}
export interface SSHProbeResult {
  fingerprintSHA256: string;
  hostKeyType: string;
}
export interface SSHConnectionOptions {
  host: string;
  port: number;
  username: string;
  expectedFingerprintSHA256: string;
  authMethod: SSHAuthMethod;
  secret: string;
  passphrase: string | null;
  connectTimeoutMs: number;
}
export interface SSHExecOptions {
  command: string;
  timeoutMs: number;
}
export interface SSHPtyOptions {
  term: string;
  columns: number;
  rows: number;
}
export interface SSHOutputChunk {
  bytes: Uint8Array;
  isStderr: boolean;
}
export interface SSHReadPacket {
  chunks: SSHOutputChunk[];
  state: SSHReadState;
  exitCode: number | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export const sshProbe: (
  requestId: string, options: SSHProbeOptions
) => Promise<SSHProbeResult>;
export const sshStartExec: (
  requestId: string, connection: SSHConnectionOptions, options: SSHExecOptions
) => Promise<SSHHandle>;
export const sshStartPty: (
  requestId: string, connection: SSHConnectionOptions, options: SSHPtyOptions
) => Promise<SSHHandle>;
export const sshCancel: (requestId: string) => Promise<void>;
export const sshRead: (handle: SSHHandle, maxBytes: number) => SSHReadPacket;
export const sshWrite: (handle: SSHHandle, bytes: Uint8Array) => Promise<void>;
export const sshResize: (handle: SSHHandle, columns: number, rows: number) => Promise<void>;
export const sshClose: (
  handle: SSHHandle, reason: SSHCloseReason
) => Promise<SSHReadPacket>;
```

语义约束：

- `sshProbe` 参数没有 username、pin 或 credential。只 TCP + SSH handshake → SHA256 主机指纹 → 关闭，绝不调用 userauth。指纹格式是 OpenSSH `SHA256:` + raw 32-byte hash 的无 `=` base64。
- `start*` 必须在真实握手后比较非空 expected fingerprint，匹配后才发送密码/私钥。probe 成功不能替代这一检查。私钥从内存解析，不生成明文临时 PEM；认证后清理原生秘密副本。
- probe/start 调用进入 JS binding 时就登记 requestId，然后才启动 owner；立即 `sshCancel` 不能丢失。取消重复调用/已结束 request 是 no-op。取消唤醒 poll、关闭 socket；pending Promise 以 `code: cancelled` 拒绝。若完成与取消竞态产生 handle，adapter 先 close 再以取消拒绝，不能泄漏 handle。
- 每条连接只有一个后台 owner 串行调用 libssh2；socket nonblocking，EAGAIN + poll，有取消唤醒。不能每次 read 占住 NAPI async-work pool，也不能在 JS/UI 线程 connect、认证、poll 或阻塞 socket read。owner 的 Promise 完成投递不能留悬空 NAPI env。
- `sshRead` 仅短暂取锁并复制已经缓存的 bytes，总 bytes ≤ `maxBytes`，允许空 chunks + running；不做网络 I/O。默认 domain 每 100ms 读 64KiB。native pending 输出最多 1MiB，满时暂停继续读 socket，用背压保留输出，不静默丢弃。
- stdout/stderr 均 drain，UTF8 不在 native 强行按块转字符串。PTY 通常合并成 stdout。read=0/EAGAIN 不等于 EOF；只有真实 EOF、输出 drain、退出状态收口才返回 exited。尚有缓存未交付时继续返回 running，最后一包可同时有 chunks + terminal state。
- `exitCode` 仅真实 exited 可非 null；网络断开、超时、取消不得用默认 0。exec deadline 从 start 调用起计，connectTimeout 与 exec 总期限均能结束 owner；PTY 无虚构 idle timeout。
- `sshWrite` 只接受 pty handle，Promise 在 bytes 完整写入 channel 后 resolve，处理部分写入/EAGAIN；resize 只接受 pty。bytes 包含 UTF8、Enter、Tab、Ctrl-C、方向键序列，不能当普通命令字符串重建。
- close 由 runtime 唯一拥有。active + cancelled/disconnected 请求停止并返回已缓存尾部与正确末态；已经 terminal 时保留原末态。`release` 只用于 terminal 后释放资源，active 时拒绝。close 最终注销 handle、关闭 fd、回收 owner。native read 不自行注销，因此正常结束也须 finally release。未知/错误 kind handle 不能误用。
- cancel 可尝试 TERM，但服务端可忽略；成功只表示客户端连接/执行已停止，不承诺任意远端后台 daemon 被杀死。错误对象和日志不得包含 connection dump/secret/passphrase。

## 2. Domain port 与数据模型

文件建议：`chat/terminal/models.ts`、`ports.ts`。上述无 SDK 类型在 domain 镜像导出；`AbortSignalLike` 从 `@amber/deepread-domain` 引入。

```ts
export interface SSHTransportPort {
  probe(requestId: string, options: SSHProbeOptions,
    signal?: AbortSignalLike): Promise<SSHProbeResult>;
  startExec(requestId: string, connection: SSHConnectionOptions,
    options: SSHExecOptions, signal?: AbortSignalLike): Promise<SSHHandle>;
  startPty(requestId: string, connection: SSHConnectionOptions,
    options: SSHPtyOptions, signal?: AbortSignalLike): Promise<SSHHandle>;
  read(handle: SSHHandle, maxBytes: number): Promise<SSHReadPacket>;
  write(handle: SSHHandle, bytes: Uint8Array): Promise<void>;
  resize(handle: SSHHandle, columns: number, rows: number): Promise<void>;
  close(handle: SSHHandle, reason: SSHCloseReason): Promise<SSHReadPacket>;
}

export interface SSHCredentialBinding {
  host: string;
  port: number;
  username: string;
  authMethod: SSHAuthMethod;
}
export interface SSHCredential {
  secret: string;
  passphrase: string | null;
}
export interface SSHCredentialStorePort {
  save(reference: string, binding: SSHCredentialBinding,
    credential: SSHCredential): Promise<void>;
  load(reference: string, binding: SSHCredentialBinding): Promise<SSHCredential | null>;
  exists(reference: string): Promise<boolean>;
  delete(reference: string): Promise<void>;
}
export interface TerminalLogPort {
  create(id: string): Promise<string>;
  append(path: string, text: string): Promise<void>;
}

export interface SSHProfileDraft extends SSHCredentialBinding {
  id: string;
  name: string;
}
export interface SSHProfile extends SSHProfileDraft {
  revision: number;
  knownHostSHA256: string | null;
  knownHostHost: string | null;
  knownHostPort: number | null;
  credentialRef: string | null;
}
export interface SSHProfileSettings {
  profiles: SSHProfile[];
  defaultProfileId: string | null;
}
export interface SSHTrustProbe {
  draftDigest: string;
  profileRevision: number | null;
  fingerprintSHA256: string;
  hostKeyType: string;
  expectedFingerprintSHA256: string | null;
  trustState: 'trusted' | 'untrusted' | 'mismatch';
}
export interface SSHTargetSnapshot {
  profileId: string;
  digest: string;
  usesDefault: boolean;
}
```

- transport adapter 将 signal listener 接 `sshCancel(requestId)`，先检查 aborted、注册、再次检查；settled 时解除。start 返回后 signal 的后续语义由 runtime 管理活跃 handle，不能依赖已经失效的 requestId。domain 不直接 import NAPI/@kit。
- credential `save` 写全新 UUID reference，不覆盖旧引用；重复 reference 拒绝。`load` 缺失返回 null，解密/绑定不符/Asset失败须抛真实错误。`exists` 查询属性和密文存在，不把秘密加载到设置 UI。
- 平台仓：每 reference 独立32-byte AES key 进 Asset（DEVICE_UNLOCKED、NEVER），credential + binding 用 AES-GCM 新 IV 加密进 app-private 原子文件。能保存64KiB私钥；Asset SECRET 1024-byte 限制不是截断依据。不要用 Preferences 的 getSecret/setSecret，不导出明文/密钥/密文到通用备份。
- host trim；port 整数 1–65535；username/name 非空；secret 不 trim（密码和 PEM 内容保留）。只 pin host+port；credential binding 是 host+port+username+authMethod。host/port 改变失效 pin 与 credential；username/authMethod 改变仅失效 credential。不能让可编辑 draft 携带任意 credentialRef/pin。
- domain stream UTF8 decoder 分 stdout/stderr 保留尾部1–3字节；terminal event 保持原 bytes。可复用 `zip_archive.ts` 的完整 UTF8 解码和 `sse_assembler.ts` 尾字节处理模式，不能引用 ArkTS 不存在的全局 TextDecoder。UTF8 encoder 放小 helper；不为此加另一套通用 platform service。

## 3. ProfileStore 公开签名与提交边界

```ts
export interface SSHProfileStoreDeps {
  kv: KeyValueStore;
  credentials: SSHCredentialStorePort;
}
export class SSHProfileStore {
  static create(deps: SSHProfileStoreDeps): Promise<SSHProfileStore>;
  snapshot(): SSHProfileSettings;
  get(id: string): SSHProfile | null;
  defaultProfile(): SSHProfile | null;
  subscribe(listener: (settings: SSHProfileSettings) => void): () => void;
  setDefault(id: string | null): Promise<void>;
  saveMetadata(draft: SSHProfileDraft,
    expectedRevision: number | null): Promise<SSHProfile>;
  commitVerified(draft: SSHProfileDraft, expectedRevision: number | null,
    fingerprintSHA256: string, credential: SSHCredential | null): Promise<SSHProfile>;
  deleteProfile(id: string, expectedRevision: number): Promise<void>;
}
```

`terminal_ssh_profiles` 单个 KV JSON 存 profiles + default，只有 metadata；snapshot/get 返回副本。修改操作在 store 内小范围串行化，revision 做 CAS；null 仅允许不存在的新 profile，旧 profile 不存在/不相等均报 `profile_conflict`，不能强制覆盖。

`saveMetadata` 保留仍匹配的 pin/ref，改 binding 后按上述规则失效；不认证、不接受新主机密钥。UI 的连接详情修改应先 `verifyAndSave`，纯重命名可调用此方法。只有 runtime 在成功验证后调用 `commitVerified`。

`commitVerified`：null credential 仅允许沿用完全相同 binding 的有效旧引用；有新 credential 则先写全新 reference → CAS检查 → 一次 KV提交 profile/revision/pin/ref → 发布快照 → 清理旧引用。KV失败/版本冲突只清理新暂存引用，旧 profile/credential 保持可用。不得先 delete 旧 credential，也不得先改内存快照再假装落盘成功。认证期间 profile 被改/删，最终提交必失败。

失效引用/删除 profile 同样先 KV成功再清理对应旧秘密。提交成功后旧引用清理失败不谎报“保存失败”或回滚已提交 metadata；记录无秘密的明确清理错误即可，不建立后台重试/事务框架。

## 4. Runtime 公开签名

```ts
export interface TerminalCommandRequest {
  target: SSHTargetSnapshot;
  command: string;
  cwd: string | null;
  timeoutMs: number;
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface TerminalSessionRequest {
  target: SSHTargetSnapshot;
  cwd: string | null;
  columns: number;
  rows: number;
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface TerminalJobSnapshot {
  jobId: string;
  profileId: string;
  command: string;
  cwd: string | null;
  status: AgentTaskStatus;
  exitCode: number | null;
  stdoutTail: string;
  stderrTail: string;
  outputTail: string;
  outputLogPath: string;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface TerminalSessionSnapshot {
  sessionId: string;
  profileId: string;
  status: AgentTaskStatus;
  exitCode: number | null;
  outputTail: string;
  outputLogPath: string;
  columns: number;
  rows: number;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface TerminalSessionEvent {
  snapshot: TerminalSessionSnapshot;
  chunks: SSHOutputChunk[];
}
export interface TerminalRuntimeDeps {
  profiles: SSHProfileStore;
  credentials: SSHCredentialStorePort;
  transport: SSHTransportPort;
  logs: TerminalLogPort;
  taskStore: AgentTaskStore;
}
export class TerminalRuntime {
  constructor(deps: TerminalRuntimeDeps);
  captureTarget(profileId: string | null): SSHTargetSnapshot;
  probe(draft: SSHProfileDraft, signal?: AbortSignalLike): Promise<SSHTrustProbe>;
  verifyAndSave(draft: SSHProfileDraft, probe: SSHTrustProbe,
    credential: SSHCredential | null, acceptFingerprint: boolean,
    signal?: AbortSignalLike): Promise<SSHProfile>;
  execute(request: TerminalCommandRequest,
    signal?: AbortSignalLike): Promise<TerminalJobSnapshot>;
  startJob(request: TerminalCommandRequest,
    signal?: AbortSignalLike): Promise<TerminalJobSnapshot>;
  readJob(jobId: string): TerminalJobSnapshot;
  waitJob(jobId: string, timeoutMs: number,
    signal?: AbortSignalLike): Promise<TerminalJobSnapshot>;
  stopJob(jobId: string): Promise<TerminalJobSnapshot>;
  startSession(request: TerminalSessionRequest,
    signal?: AbortSignalLike): Promise<TerminalSessionSnapshot>;
  readSession(sessionId: string): TerminalSessionSnapshot;
  subscribeSession(sessionId: string,
    listener: (event: TerminalSessionEvent) => void): () => void;
  sendSessionBytes(sessionId: string, bytes: Uint8Array): Promise<void>;
  execSession(sessionId: string, command: string,
    approvedTarget: SSHTargetSnapshot, signal?: AbortSignalLike): Promise<TerminalSessionSnapshot>;
  resizeSession(sessionId: string, columns: number, rows: number): Promise<void>;
  stopSession(sessionId: string): Promise<TerminalSessionSnapshot>;
  interruptForBackground(): Promise<void>;
}
```

行为：

- `probe` 捕获标准化 draft digest 和当前持久 revision；返回前再比较。digest 是明确字段元组的稳定 JSON，非秘密，不需要为 equality 加 hash 依赖。probe 无凭据。`verifyAndSave` 先核对 draft/revision/候选，再执行真实 pinned startExec 认证检查（固定 `printf 'amber-terminal-auth-check\\n'`，总期限10s）并 drain/close；成功后再次核对再 commit。untrusted/mismatch 必须 `acceptFingerprint=true`，由 UI 的明确“信任并验证”动作传入；普通验证不能自动信任。失败/取消不改旧 profile/credential。credential=null 从仍匹配的旧引用加载，不能误将缺失秘密当空密码。
- target digest 包括 profileId、host、port、username、authMethod、pin绑定和 credentialRef，不包括展示 name。`usesDefault` 时还须确认默认 ID 没改。运行之前、秘密 load 后、调用 native 之前重新核对；不把批准后编辑的 profile 直接当新目标执行。连接开始后使用已核对的不可变快照。
- `execute` 用同一 job，有限等待5s后返回真实 RUNNING + jobId，或终态；无“长命令”字符串猜测。`startJob` 完成 TaskStore 注册并启动后返回，不等整个命令。start握手失败仍收口已有 job/task 为 FAILED 并返回快照；参数/目标验证失败可抛错误，不执行。QUEUED/RUNNING 的 exitCode 始终 null。
- `execute` signal 在其等待窗口内取消真实 job；成功返回 RUNNING 后解除调用方 signal，job 后续经 stopJob/TaskStore取消。`startJob` signal 只拥有启动阶段，返回后脱离本次工具调用；`waitJob` signal 仅结束等待，不能隐式杀已启动 job。`startSession` signal 拥有启动阶段；返回后 UI/runtime 拥有 session，退出页面通过 stopSession。所有 pending handshake 都必须接 requestId cancel。
- 每 job/session 仅 runtime 一个 pump 读取 native，逐包先写日志/更新尾部/通知，再处理末态；所有 Promise 内 read/append/close 串行，终态拒绝迟到 output。tail最多每流16Ki字符、combined32Ki字符，截尾不切断surrogate pair；私有 log 追加保留完整文本，不用 unbounded RAM 重写整份文件。记录 stdout/stderr 标签或分别保持尾部；native PTY bytes 原样给 UI。输出/log错误必须真实失败并关闭连接。
- jobs 复用 AgentTaskStore：taskId=jobId、type=`terminal`、runtime=`remote_ssh`、spec仅 command/cwd/profileId、source明确、outputRef.type=`terminal_log`、private outputPath、offset按UTF8日志字节增长。在 native start 前 register 真实 cancel callback、非 retryable；pending阶段回调触发该job自己的AbortController，经transport到requestId取消，已有handle则close真实owner。terminal status及时 update并 cancelCapability=false。callback不调用 TaskStore.cancel 自递归，不把已真实完成的 job 再覆盖 CANCELLED。无第二个 Scheduler。
- native exited + code0 → COMPLETED；真实非0 → FAILED但保留真实 exitCode；failed → FAILED；cancelled → CANCELLED；timed_out → TIMED_OUT；disconnected → INTERRUPTED。只有正常 exited 有 exitCode。重启任务恢复用现有 INTERRUPTED规则，不自动重发命令；readJob若不在live map可从TaskStore恢复 metadata/log引用的末态快照。
- PTY shell session 返回的是会话状态，session_exec只是经批准向该 shell写 command + newline，不伪造逐命令 exit code。session cwd 表示启动时请求的远端目录，不宣称之后 shell 当前目录可知；非空初始cwd用正确 POSIX quoting发送 cd，不能映射手机 /workspace。
- `subscribeSession` 立即发 snapshot；UI在 startSession resolve 后立即订阅。首次 pump用下一个100ms timer，保证连接初始prompt仍在 native队列。UI输出订阅和tool read均不二次 drain native，`readSession`读已有 tail，订阅注销不擅自关闭。页面创建的新 session由页面退出时stop，不能误取消其它job。
- `sendSessionBytes` 用于用户亲自输入；Agent只能走带 approvedTarget 校验的 execSession，不能绕它直接调用 write。resize值取实际terminal fit（至少1行/列），term固定xterm-256color；关闭后输入/resize报 session closed。
- foreground之外的实际限制不能假装不存在：EntryAbility onBackground调 interruptForBackground，释放连接并正确 INTERRUPTED，回前台不重发命令。不要将 terminalRunning并入DATA_TRANSFER保活，也不要添加TASK_KEEPING ACL；tmux托管另做明确实际验收的功能，不能先给空入口。

实现建议：ProfileStore <500行；Runtime facade/验证 <450行；`job_runtime.ts`、`session_runtime.ts` 各 <700行，共享小 UTF8 helper，不把全部管线塞进一个1500行文件。只拆真实职责，不引入 transport provider registry/generic scheduler。

## 5. 工具、UI 与主 agent接线

Domain factory 固定：

```ts
export interface TerminalToolsDeps {
  runtime: TerminalRuntime;
  profiles: SSHProfileStore;
  activityStore: AgentToolActivityStore;
  conversationId: string;
}
export const createTerminalTools: (deps: TerminalToolsDeps) => AgentTool[];
```

factory 建立时捕获所有 profile target和默认 target；这是工具审批开始前的快照，不能在 execute 已获批准后才 capture。`profile_id` 缺省对应当时默认；新加/改过配置需重新组装工具，旧工具 target_changed 拒绝。审批描述/systemPrompt显示实际 profile/host:port/username，使批准对象可辨认。session_exec根据 session的profileId取同一捕获表并传 approvedTarget。

| 工具 | API | 批准 |
| --- | --- | --- |
| terminal_execute | execute | needsApproval=true，allowsAutoApproval=false |
| terminal_job_start | startJob | 同上 |
| terminal_job_read | readJob | needsApproval=false |
| terminal_job_wait | waitJob | needsApproval=false |
| terminal_job_stop | stopJob | needsApproval=true，allowsAutoApproval=false |
| terminal_session_start | startSession | 同上 |
| terminal_session_exec | execSession | 同上 |
| terminal_session_read | readSession | needsApproval=false |
| terminal_session_stop | stopSession | needsApproval=true，allowsAutoApproval=false |

JSON保持 `runtime: remote_ssh`、`profile_id`、`command`/`cwd`（适用时）、`job_id`或`session_id`、小写 `status`、`running`、`exit_code: number|null`、`output`/`stdout`/`stderr`尾部、`output_log_path`、`error_code`/`error`；禁止 credential入参/回填/结果。read/wait不重复审批，不修改全局terminal风险类别制造轮询批准。

ActivityStore由factory接，startTool明确 Remote SSH + conversationId，最终才 completeWithExitCode/fail/cancel。RUNNING结果不能调用complete标绿；普通工具调用结束与底层job结束分清。job的任务板取消走TaskStore live callback。UI和Agent均注入同一ProfileStore/Runtime单例。

UI只消费这些公开 API：profile列表/默认/metadata；probe candidate+revision；明确主机信任；密码或私钥/可选passphrase；验证并保存；PTY connect/input/resize/stop。秘密 draft在退出/后台清空，保存后只显示“已保存”，不 load回填。包内本地xterm write(Uint8Array)，bridge ready后供数据，input/resize/ready三种消息，主题/实际容器/键盘fit驱动；不得拼远端输出到innerHTML或执行它。

文件所有权：native worker独占ssh源/依赖脚本/native.d.ts；domain worker独占chat/terminal及tests；platform worker独占NativeSSHTransport/SSHCredentialStore/TerminalLogPort adapter；UI worker独占新profile/terminal页面、组件、local terminal rawfiles。主agent独占index.ts、AppContainer、ChatPage、EntryAbility、CMake总入口、路由和已有设置/助手行/活动文案接线与设备验证。各模块public导出清单交主agent，避免多worker竞争index.ts。

## 6. 必须通过的合同验收

1. fixture认证计数证明probe不auth、wrongpin先于auth；立即cancel握手能退出，重复连接无fd/thread/handle泄漏。
2. 真实password及>1024-byte私钥认证；native非0exit/stderr/中文emoji跨块、输出超tail完整log；timeout/取消/断网终态准确；Read返回0不误完成。
3. Profile验证失败、Asset保存失败、KV失败、认证期间profile变更都不覆旧凭据；新ref失败回收；host/port/user变化对应失效；磁盘不出现秘密marker。
4. PTY真实stty尺寸、Ctrl-C/Tab/箭头/alt-screen/resize；首prompt无丢失，session读工具不吃掉UI bytes，退出只stop自己的session。
5. 同一runtime UI和九件Agent工具贯通；审批拒绝无命令，批准后换默认/编辑target拒绝；RUNNING不报0成功；TaskStore真实cancel、日志路径、重启INTERRUPTED。
6. 精确domain tests/tsc和ArkTS lint，再由主agentbuild/sign/install/亮暗与键盘截图review；原生真实fixture和UI证据分开记录，不用README/throw/工具名清单宣称E2完成。
