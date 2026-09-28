# E2 固定 SSH / Asset 设备验收入口

2026-09-28。此文件记录 Debug harness 的交付，设备执行、构建与 Release gate 证据由主 agent 补入。没有把静态检查当作设备通过。

## 入口与 Want 合同

文件：`harmony/entry/src/main/ets/platform_impl/SSHRuntimeFixture.ets`。

```ts
export const runSSHRuntimeFixture = async (want: Want): Promise<void>
```

主 agent 在已登记 `abilityContext` 的 EntryAbility 生命周期入口接入。正式构建由函数内部 `BuildProfile.DEBUG` 再次拦截，先检查 build gate 与 selector，随后才读取凭据参数。正常前台启动；transport 自身的后台拒绝规则保留，不为 fixture 改写后台状态。

| Want 字段 | 固定约束 |
| --- | --- |
| `amberSSHFixture` | `ssh-v1` |
| `amberSSHRunId` | `[A-Za-z0-9._-]{1,64}` |
| `amberSSHPassword` | 生成的测试密码，1–256 字符 |
| `amberSSHPrivateKey` | 生成的加密 RSA PEM，1025–16384 字符，含 `-----BEGIN` |
| `amberSSHPassphrase` | 生成的测试 passphrase，8–256 字符 |

仅允许代码内固定 `127.0.0.1:22224`、`amber-fixture` 与 host0 pin `SHA256:cKNdbZG2rwmIGdsuNSEdLYA55g9Zy5JFQvXNXIdVZA4`。无 endpoint、username、command 入参；PEM 不从用户目录读取、不进 rawresource、不打印。输入错误只记录 runId 与固定失败 summary。

主 agent 的 HDC reverse-port 与 fixture server 必须保持 host0；不得与别的原生认证计数窗口交叠。PEM 通过 Want string 传入时须完整保留换行和 shell quoting，不能把 `\n` 字面量当换行。入口未提供生产 UI，也未提供任意远端执行器。

## 17 项真实检查

每项 hilog tag=`SSH.Fixture`，JSON 仅 `{runId,name,pass}`；最终 `{runId,name:"complete",completed,expected:17,pass}`。不输出原始异常/secret/passphrase/输入对象。异常将当前固定 stage 记为失败；finally 仍执行清理，summary 保持失败。

| name | 真实检查 |
| --- | --- |
| `asset-large-roundtrip` | 真实 Asset AES key + 私有加密文件；PEM 加换行至至少 4096 字符后，exists、完整 secret 与 passphrase roundtrip |
| `asset-binding-rejected` | 仅 username 改变，真实 load 以 `credential_invalid` 拒绝 |
| `asset-file-has-no-plaintext` | 读取本次独有 ref 的私有文件，无 PEM header、完整 PEM 或 passphrase 明文 |
| `asset-delete` | 删除后 exists=false、load=null |
| `probe-pinned-host` | 真实 native probe 返回固定主机指纹与 untrusted；不匹配时停止认证 |
| `wrong-pin-before-auth` | 错误非空 pin 的真实 startExec 以 `host_key_mismatch` 拒绝；意外 handle 会立即关闭 |
| `password-verify-and-save` | runtime 的 probe → pinned auth check → credential commit，确认 pin/ref/exists |
| `exec-unicode-stderr-exit7` | 实际远端 `/bin/sh` 中文/emoji stdout/stderr，status=FAILED、真实 exitCode=7 |
| `exec-private-complete-log` | 读取真实平台 log 文件，确认两个完整流与 stderr 标签 |
| `active-exec-abort` | execute 的真实 RUNNING job exit=null，调用方 abort 使 CANCELLED/null，并清除 TaskStore cancelCapability |
| `encrypted-rsa-auth` | >1024 字符加密 RSA PEM + passphrase 真实 verifyAndSave，再执行固定命令获得 exit0/完整输出 |
| `pty-first-prompt-bytes` | 真实 PTY 80×24，首次订阅保留 shell prompt 与原始 bytes，RUNNING/null |
| `pty-initial-stty-size` | 写入 `stty size`，读取真实 `24 80` 输出 |
| `pty-resize-stty-size` | resize 93×31，真实远端 `stty size` 返回 `31 93`，domain snapshot 同步尺寸 |
| `pty-ctrl-c-shell-survives` | PTY 启动 sleep 后写真实 byte 3，再写 printf；shell 仍 RUNNING 并给出新行输出 |
| `pty-stop-no-fake-exit` | stopSession 收口 CANCELLED，exitCode=null |
| `cleanup` | 注销订阅、interrupt 所有剩余连接，删除本次 profile/ref 并确认 refs 不存在，清理已记录 fixture logs |

ProfileStore 使用新建内存 KV，TaskStore 使用独立内存文件 metadata；网络、凭据仓、UTF8 stream、任务 runtime 与 log 文件均是实际生产代码。未接 AppContainer 单例，避免测试覆盖用户 profiles 或任务记录。原始私钥用于认证，4096 字符 padding 仅用于存储大小验收。

设备 PASS 的 wrong-pin 返回值不能单独证明服务端没有收到认证；还需主 agent 对照 fixture `events.jsonl` 的 auth 计数/时间窗口。主机 key0/key1 切换、真实弱网/后台生命周期、重复连接 fd/thread 计数不在此单入口声称完成，仍由主 agent 的整合验收负责。

## 当前证据

- 已阅读必载 `harmony-arkts` skill、冻结 E2 contract 和现有 SDK/adapter/domain 实现。
- 没有引入新的 SDK API：Want/hilog/fileIo/util/BuildProfile/EntryAbortController 均沿仓库已用入口。
- 窄 ArkTS lint 已通过，exit=0、`No defects found in your code`：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/SSHRuntimeFixture.ets`；SDK 写诊断日志需正常 escalation，初次 sandbox EPERM 后原命令授权重跑。日志 `/tmp/e2-device-fixture-lint.log`。
- 设备运行、编译成功、Release selector 拦截：待主 agent 接线与执行，不能由这份交付报告推定通过。
