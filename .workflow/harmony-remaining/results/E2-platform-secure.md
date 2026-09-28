# E2 SSH 平台安全仓、传输映射与日志

日期：2026-09-28。修改 `SSHCredentialStore.ets`、`NativeSSHTransport.ets`、`TerminalLogPort.ets`；本轮获主 agent 明确授权，仅另改 `AtomicJsonFile.ets` 的 writeTextFileAtomic 写入段；`chat/terminal` 和 `libamber_native/index.d.ts` 分别由 domain/native worker 所有。主 agent 统一导出、DI、完整构建与设备验收。本文不宣称设备 Asset 或 SSH 已验收。

## 实际实现与接线

固定公开 factory：

```ts
createEntrySSHCredentialStore(): SSHCredentialStorePort
createNativeSSHTransport(): SSHTransportPort
createEntryTerminalLogPort(): TerminalLogPort
```

`SSHCredentialStore.ets` 从 AppStorage 的 `abilityContext.filesDir` 惰性取私有目录；factory 不加载秘密。密文路径：`filesDir/amberagent/terminal/credentials/<reference UUID>.json`。每新 reference 单独随机 32-byte key 入 Asset，`DEVICE_UNLOCKED` / `NEVER` / `THROW_ERROR`；全凭据与 host/port/username/authMethod 通过现有 `createEntrySyncCryptoPort()` AES-GCM 和新 12-byte IV 加密，复用 `writeTextFileAtomic()` 原子落密文。该共享 writer 现在循环完成实际 partial write，0/负数/超出剩余 bytes 的返回值立即失败并沿原有路径清理 tmp；SSH 仓已删除重复读回校验，失败仍回收新 ref。

实际调用 domain worker 的 `encodeSSHCredential/decodeSSHCredential` 做严格 payload/schema/binding 与 65536 UTF8 byte 边界验证；密码、PEM 和 passphrase 原样保留。`save` 拒绝重复 ref，失败后仅回收本次成功新增的 ref，旧 ref 由 domain CAS 提交边界决定清理。`load` 文件缺失返回 null；文件损坏、GCM tag 验证失败、绑定失配、密钥缺失、Asset 锁屏/服务错误均真实失败。`exists` 仅 Asset ATTRIBUTES + 密文存在，不获取 SECRET 或回填明文。随机 key 和解密/加密 plaintext byte buffers 在 finally 清零；JS immutable strings 不承诺可清零。未使用 Preferences secret、未加入备份代码。

`NativeSSHTransport.ets` 严格直接映射冻结 `.d.ts`，不改 `countBatch`。request signal 在 native 调用前检查 aborted → 注册 listener → 再次检查；pending abort 调用真实 `sshCancel(requestId)`；settled 解除 listener。start 已产 handle 后若取消/背景，先 `sshClose(handle, cancelled/disconnected)` 再 reject。AppStorage `appBackgrounded=true` 明确拒绝新 probe/start，code=`disconnected`；正常返回 handle 后交给 runtime，不用过期 requestId 持续拥有连接。read 保持原 packet/bytes，不做解码或网络轮询。

`TerminalLogPort.ets` 路径：`filesDir/amberagent/terminal/logs/<id>.log`。`create` 拒绝重复 id，不 TRUNC 已有日志；append 仅允许该私有路径、WRITE_ONLY|APPEND|NOFOLLOW，不 CREATE 丢失日志。UTF8 编码当前文本，循环处理实际短写，按 path 串行；不读/重写完整历史，不额外加入标签。domain 负责文本合流、尾部限制和 UTF8 byte offsets。

主 agent 需集中导出冻结 SSH models/ports 与 `encodeSSHCredential/decodeSSHCredential`。首次真实构建后若有精确 ArkTS/原生类型诊断，回喂此 worker 局部修正。共享 writer 本轮只做精确 lint 与主机控制流回归，未独立构建/设备验证。

## 官方 API 依据

均先 offline docs search，再完整 docs read；未根据记忆发明接口。

- `API参考/安全/Asset_Store_Kit_关键资产存储服务/ArkTS_API/ohos_security_asset_关键资产存储服务_/js-apis-asset`：首批 API11；异步 add/query/remove；NOT_FOUND=24000002；DEVICE_UNLOCKED；SyncType.NEVER；ReturnType.ATTRIBUTES 不包含关键资产明文。未使用 API14+ 加密标签、API18+ group/wrap 或 API26 batch。
- `开发指南/安全/Asset_Store_Kit_关键资产存储服务/Asset_Store_Kit开发指导_ArkTS/新增关键资产_ArkTS/asset-js-add`：SECRET 限制 1–1024 bytes、ALIAS 不加密；仅 UUID alias，无 endpoint/user 信息。
- `API参考/Core_File_Kit_文件基础服务/ArkTS_API/ohos_file_fs_文件管理_/js-apis-file-fs`：API9 APPEND/NOFOLLOW、write 返回实际写入字节数，API11 WriteOptions；日志使用现有基础接口。
- `API参考/ArkTS_方舟编程语言/ArkTS_API/ohos_util_util工具函数_/js-apis-util`：API11 TextDecoder.create 与 fatal，API12 decodeToString；不调用 global TextDecoder。

## 已验证证据与边界

- 精确 lint：`harmony/scripts/lint-arkts.sh <上述三个文件>`，通过，`No defects found in your code.`。首次运行因工具自身写 `codelinter/linter/result/codelinter.log` 的沙箱 EPERM，原命令申请工具日志权限后成功。未反复调用已知不可用的 deveco-mcp check。
- 本轮共享写入修复精确 lint：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/AtomicJsonFile.ets harmony/entry/src/main/ets/platform_impl/SSHCredentialStore.ets`，2 files，`No defects found in your code.`；仅 writer 写入段与删除 SSH 重复读回，不改其它 helper。
- 主机 control-flow tests：`node --test /tmp/e2-platform-adapter-control.test.cjs`，9/9。实际 `NativeSSHTransport.ets` 经 TypeScript transpile，在 mock native 与 AppStorage 中运行：pre-aborted/background不进 native、listener 注册期间取消、重复取消仅一次、返回 handle 后 close再reject、PTY 背景 close、probe取消、正常返回解除调用 signal、同步/异步 native error 保留、close真实失败、raw bytes/packet直传。
- 主机 adapter/codec/文件 tests：`node --test /tmp/e2-platform-store-control.test.cjs`，12/12。实际 `SSHCredentialStore.ets`、`TerminalLogPort.ets`、`AtomicJsonFile.ets`、`SyncCryptoPort.ets` 和 domain codec 都被执行；Asset 用内存替身、SDK crypto用 Node AES-GCM签名替身、fileIo用真实 `/tmp` 文件映射。覆盖 65536 byte credential 原样 roundtrip、32byte key与策略属性、exists无SECRET查询、磁盘无测试marker、重复ref拒绝、四binding失配、ciphertext GCM篡改/坏envelope、原子写失败新增key回收且旧ref可用、Asset失败不降级、真正缺失null、缺key真实error、65536+ UTF8 byte拒绝、中文emoji大日志连续/短写与路径拒绝，credential 7byte短写循环完成、共享原子写UTF8内容/byte count完全一致、0/负数返回真实error、已有目标内容保留且tmp/handle清理、credential零写回收新ref，三处 typed rethrow 保留原 SDK BusinessError 对象/code/message。
- 主机替身验证的是实际 adapter 控制流与落盘编排；它不证明真实 Asset owner/锁屏行为，也不证明 Harmony CryptoFramework 与文件 SDK 运行正确，更不证明 native SSH认证、socket退出或远端命令已验收。
- 平台文件 `git diff --check` 无空白诊断。所有新文件保持 V1 项目约束，没有 UI 状态装饰器或新高版本 API。

## 留给主 agent 的真实验收

完整 ArkTS/原生构建、签名、安装由主 agent 统一执行。设备需实际测试 Asset 保存/读取/锁屏服务失败、>1024byte且≤64KiB私钥、密文篡改、同 binding 读取与各 binding 失配、磁盘 marker、失败新ref回收与旧ref可用；native/PTY/生命周期/亮暗键盘由相应夹具和 UI 截图验证。未操作设备，未 git add/commit。

共用 helper 短写边界已按新增授权修复：`writeTextFileAtomic` 将 text 一次编码为 UTF8 bytes，按真实 written count 前移 consumed，并给每次 write 提供剩余 buffer。官方文档说明 WriteOptions.offset 是文件写入位置，非 buffer offset；未传 options 时默认当前文件位置、length默认当前 buffer 长度，因此这里省略 offset/length并slice输入buffer，不错误地用 consumed 指定文件 offset。0/负数/大于剩余 bytes 立即报错，沿原有 catch 关闭fd/删除tmp；仅写满后rename。原 mkdir/close/rename/cleanup结构未扩展。

共享影响限于该函数调用者：`ManagedFileStore.ets` 的 registry、`MemoryStore.ets` 的 memory JSON、`AtomicJsonFile.ets` 的 quarantine marker、`SSHCredentialStore.ets` 的 credential ciphertext。正常写入内容与UTF8编码保持；short write由错误成功改为续写，不能续写时明确失败且原目标保留。未改 guarded read/quarantine/mutex；无新抽象或接口。SSH重复readback已移除。

统一构建回喂另发现 SSHCredentialStore 三处 catch 裸 rethrow 触发 `arkts-limited-throw`（原41/104/158行）。本轮三处仅改为 `throw error as Error`：依据 SDK BusinessError extends Error 与现有 crypto/file/自有 Error，类型收敛不构造 fallback、不改变异常对象/code/message。主机 SDK BusinessError identity 测试通过；最终单文件 lint（SSHCredentialStore.ets）通过，`No defects found in your code.`。完整重编译仍由主 agent 统一进行。
