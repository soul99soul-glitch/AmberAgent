# HarmonyOS SSH 本机密钥与跳板交付记录

日期：2026-11-01。Work item：`hub-work:9dcaa4c11be848fb18307d657396f9730b2e1405`。
工作目录：`/Users/arquiel/.synara/worktrees/a631a45c149b`。
实现提交：`79977a6c7adb0d01e7d24a2c432f72763185019b`。
分支：`synara/amber-harmonyos-release-baseline`。接手基线：`1062fbb34d71913fdbf0bee08d1bd44f55d46b47`。

## 实现

- 注册 `ssh_key_generate`、`ssh_key_list`、`ssh_key_import`、`ssh_key_delete`。
  Ed25519 默认，另支持 ECDSA P-256。生成/导入通过真实 OpenSSL/libssh2；带口令 OpenSSH/PEM 保留原密文与口令。
  删除密钥由存储层检查所有 profile 引用，与 profile 写入共享串行边界。
- 复用既有 `SSHCredentialStore`：AES-GCM 加密完整凭据，AES 密钥由 Asset 安全仓保护，
  `DEVICE_UNLOCKED`、`SyncType.NEVER`。工具与密钥元数据只输出公钥、指纹和非秘密属性。
  Asset 的底层加解密和访问控制由 HUKS 实现（[OpenHarmony ASSET 官方说明](https://github.com/openharmony/security_asset)）。
  本实现使用 Asset/HUKS 保护包装密钥，加密私钥与口令；保护类型标记为 `asset_encrypted`。
  不可导出的 HUKS 签名密钥属于设计 P3，不是本轮的包装存储路径；本轮未验证设备上的 TEE 行为。
- profile 新增 `key_id`、`jump_profile_id`。支持 `key`、`private_key` 与旧 `privateKey` 认证拼写。
  旧 private_key 写法在生产组合根中导入密钥库；已有 inline 凭据继续可用。
- 最多 3 个跳板，检测自环、尾环、悬空引用和反向依赖超深；被引用的跳板禁止删除。
- 原生桥接使用 `libssh2_channel_direct_tcpip_ex` + 匿名 socketpair + 各跳独立泵线程；不依赖跳板 shell，未实现 -L。
  该通道语义已核对 [libssh2 官方文档](https://libssh2.org/libssh2_channel_direct_tcpip_ex.html)。
  认证专用 `sshVerify` 不创建 shell/session channel，设置页因此可验证禁止 shell 的跳板。
- `terminal_execute`、`terminal_job_*` 和 SSH PTY 透明走路由；审批摘要涵盖全链，摘要变化阻断旧批准。
  每跳在认证前检查 pin，变化时阻断且返回 hop/profile 信息；终端输出和日志在写入前过滤各跳凭据。
- 设置页增加生成密钥、复制公钥、选择本机密钥与跳板入口。
- 为落实前台首次信任入口，新增高风险 `ssh_host_trust(profile_id, fingerprint?)`：
  不带 fingerprint 时探测候选指纹，带 fingerprint 时再次探测并仅确认首次信任。
  已有 pin 不可通过该工具替换，只能到设置页处理；对应待审批卡禁用批准按钮。
- 所有写工具高风险、mandatory approval，普通自动批准不能放行，高风险自动批准沿用既有用户设置。
  写工具不提供给 subagent/Council；手动批准还检查前台与屏幕解锁，未知锁屏状态拒绝批准，拒绝操作保留。
  `ssh_key_import` 纳入既有 tool_input_vault 和持久化脱敏，六条终态均覆盖；占位符不能写入凭据。
- `HANDOFF_STREAMING_RENDERING.md` 未触碰。未连接真实服务器、未安装设备、未推送。

## 工具合同

完整、从当前工具工厂直接导出的 JSON Schema：[`tool-schemas.json`](tool-schemas.json)。
包括 4 个密钥工具、4 个更新后的 profile 工具及首次信任工具。
链解析固定用例：[`route-fixtures.json`](route-fixtures.json)。

## 实际验证

| 检查 | 结果与证据 |
|---|---|
| 现有定点回归 | 102 项通过，含 profile CRUD/门禁、terminal runtime/codec、审批快照、助手工具发现。收尾后密钥/六终态组 13 项再次通过，未把重复用例相加。 |
| 前台审批门禁 | 1 个表驱动测试通过，覆盖锁屏、后台、未知锁屏状态、已有 pin 禁止替换，以及各状态仍可拒绝。另有密钥引用/竞态定点测试。 |
| 真实 native | 7 项通过，同一份 HAP C++ 源码和静态依赖在 macOS 编译。Ed25519/P-256 公钥及指纹与 ssh-keygen 一致；带口令导入/错误口令；1–3 跳、目标密码/密钥、无 shell 跳板、400KB 输出、第二跳变化前零认证/零后续连接、转发拒绝、探测不认证目标、取消后全部连接回收。 |
| TypeScript | `tsc -p harmony/chat/tsconfig.json` 退出 0。修正了本次复用终态测试中的两个旧函数调用签名。 |
| ArkTS lint | 35 个改动源文件，退出 0；0 error / 2498 warn。未清理任务外的告警，不能称为零告警。 |
| HAP | `HOME=/Users/arquiel node scripts/harmony-inventory/build-harmony-app.mjs` 成功，`BUILD SUCCESSFUL in 39 s 243 ms`。 |
| 签名 | `HOME=/Users/arquiel ... harmony/scripts/sign-hap.sh` 成功；`verify codesign success`、`Digest verify result: true`、`verify-app success`。离线调试签名。 |
| Docker | 宿主无 `docker` 命令，没有实跑。已交付 [`tools/ssh-it`](../../tools/ssh-it/README.md) 的 compose、sshd 配置、密钥准备脚本和同源 native 测试步骤。 |
| 安装/运行/UI | `hdc list targets` 为 `[Empty]`。未安装手机或模拟器，未做 UI 截图目测、真实 Asset/锁屏 API 或设备行为验收。 |

关键命令：

```sh
HOME=/Users/arquiel harmony/native/ssh/scripts/build-host-addon.sh
HOME=/Users/arquiel AMBER_SSH_FIXTURE_PYTHON="$PWD/harmony/native/ssh/build/host/test-venv/bin/python" node --test harmony/native/ssh/tests/jump_native.test.cjs
HOME=/Users/arquiel harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json --test harmony/chat/src/test/ssh_keys_jump.test.ts harmony/chat/src/test/ssh_profile_end_to_end.test.ts
HOME=/Users/arquiel harmony/chat/node_modules/.bin/tsc -p harmony/chat/tsconfig.json
HOME=/Users/arquiel harmony/scripts/lint-arkts.sh <35 changed source files>
HOME=/Users/arquiel node scripts/harmony-inventory/build-harmony-app.mjs
HOME=/Users/arquiel JAVA_HOME=/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home harmony/scripts/sign-hap.sh
```

原始本机日志：`/tmp/amber-harmony-ssh-final-unit.log`、`/tmp/amber-harmony-ssh-last-tests.log`、
`/tmp/amber-harmony-ssh-gate-tests.log`、`/tmp/amber-harmony-ssh-native-final.log`、
`/tmp/amber-harmony-ssh-tsc-clean.log`、`/tmp/amber-harmony-ssh-final-lint.log`、
`/tmp/amber-harmony-ssh-hap-build.log`、`/tmp/amber-harmony-ssh-hap-sign.log`。

## 产物

包名沿用 worktree 的 `app.amber.deepread`，不是应用市场正式身份验证。

- `harmony/entry/build/default/outputs/default/entry-default-unsigned.hap`
  - SHA256: `34d9e3e1b252b3a6f4910975cc2688556f9fbff0d7d1c416456bd4bbf0329e88`
- `harmony/entry/build/default/outputs/default/signed/entry-default-signed.hap`
  - SHA256: `6690fba9c59ecc2745d754145cb0809db195389d593cb0ed358fd18bfbf18d93`

## 改动文件与行数

下表包含接手续作的 SSH 未提交改动及本轮新增文件，`+/-` 相对接手 HEAD；不包含构建产物和本报告自身。
所有源文件均未超过 1500 行；既有 barrel `index.ts` 与既有 `ConversationRunService.ets` 保留原职责，本次未引入新的大类。

| 文件 | diff | 完成后行数 |
|---|---:|---:|
| `docs/ssh/route-fixtures.json` | +11/−0 | 11 |
| `docs/ssh/tool-schemas.json` | +285/−0 | 285 |
| `harmony/chat/src/main/ets/chat/terminal/control.ts` | +43/−6 | 98 |
| `harmony/chat/src/main/ets/chat/terminal/host_trust_tool.ts` | +56/−0 | 56 |
| `harmony/chat/src/main/ets/chat/terminal/job_runtime.ts` | +15/−6 | 135 |
| `harmony/chat/src/main/ets/chat/terminal/key_store.ts` | +253/−0 | 253 |
| `harmony/chat/src/main/ets/chat/terminal/key_tools.ts` | +241/−0 | 241 |
| `harmony/chat/src/main/ets/chat/terminal/models.ts` | +92/−4 | 183 |
| `harmony/chat/src/main/ets/chat/terminal/output_redactor.ts` | +41/−0 | 41 |
| `harmony/chat/src/main/ets/chat/terminal/ports.ts` | +7/−1 | 33 |
| `harmony/chat/src/main/ets/chat/terminal/profile_store.ts` | +217/−49 | 366 |
| `harmony/chat/src/main/ets/chat/terminal/profile_tools.ts` | +192/−102 | 425 |
| `harmony/chat/src/main/ets/chat/terminal/route_resolver.ts` | +163/−0 | 163 |
| `harmony/chat/src/main/ets/chat/terminal/runtime.ts` | +16/−67 | 122 |
| `harmony/chat/src/main/ets/chat/terminal/session_runtime.ts` | +10/−5 | 127 |
| `harmony/chat/src/main/ets/chat/terminal/ssh_tool_contract.ts` | +11/−0 | 11 |
| `harmony/chat/src/main/ets/chat/terminal/stream.ts` | +27/−4 | 182 |
| `harmony/chat/src/main/ets/chat/terminal/target_resolver.ts` | +156/−17 | 177 |
| `harmony/chat/src/main/ets/chat/terminal/tools.ts` | +10/−3 | 177 |
| `harmony/chat/src/main/ets/chat/terminal/trust_flow.ts` | +68/−0 | 68 |
| `harmony/chat/src/main/ets/chat/terminal_approval.ts` | +9/−3 | 126 |
| `harmony/chat/src/main/ets/chat/tool_activity.ts` | +3/−0 | 859 |
| `harmony/chat/src/main/ets/chat/tool_display.ts` | +8/−1 | 545 |
| `harmony/chat/src/main/ets/chat/tool_input_redaction.ts` | +3/−2 | 101 |
| `harmony/chat/src/main/ets/chat/tool_policy.ts` | +7/−2 | 731 |
| `harmony/chat/src/main/ets/index.ts` | +8/−3 | 1439 |
| `harmony/chat/src/test/ssh_keys_jump.test.ts` | +158/−0 | 158 |
| `harmony/chat/src/test/ssh_profile_end_to_end.test.ts` | +30/−8 | 470 |
| `harmony/chat/src/test/terminal_runtime.test.ts` | +8/−4 | 515 |
| `harmony/entry/src/main/cpp/CMakeLists.txt` | +1/−1 | 86 |
| `harmony/entry/src/main/cpp/ssh_core.cpp` | +180/−39 | 682 |
| `harmony/entry/src/main/cpp/ssh_core.h` | +25/−2 | 113 |
| `harmony/entry/src/main/cpp/ssh_jump.cpp` | +326/−0 | 326 |
| `harmony/entry/src/main/cpp/ssh_jump.h` | +55/−0 | 55 |
| `harmony/entry/src/main/cpp/ssh_keys.cpp` | +380/−0 | 380 |
| `harmony/entry/src/main/cpp/ssh_keys.h` | +31/−0 | 31 |
| `harmony/entry/src/main/cpp/ssh_napi.cpp` | +147/−14 | 412 |
| `harmony/entry/src/main/cpp/types/libamber_native/index.d.ts` | +50/−1 | 199 |
| `harmony/entry/src/main/ets/components/ChatMessageTools.ets` | +1/−1 | 331 |
| `harmony/entry/src/main/ets/components/SSHApprovalSummary.ets` | +5/−1 | 36 |
| `harmony/entry/src/main/ets/components/SSHKeysSection.ets` | +99/−0 | 99 |
| `harmony/entry/src/main/ets/components/SSHPageErrors.ets` | +8/−1 | 27 |
| `harmony/entry/src/main/ets/di/LocalExecutionResources.ets` | +10/−6 | 102 |
| `harmony/entry/src/main/ets/pages/SettingSSHProfilesPage.ets` | +41/−7 | 598 |
| `harmony/entry/src/main/ets/platform_impl/AssistantLocalPrimitives.ets` | +5/−2 | 58 |
| `harmony/entry/src/main/ets/platform_impl/ConversationRunService.ets` | +2/−0 | 1111 |
| `harmony/entry/src/main/ets/platform_impl/NativeSSHKeyCrypto.ets` | +9/−0 | 9 |
| `harmony/entry/src/main/ets/platform_impl/NativeSSHTransport.ets` | +3/−0 | 88 |
| `harmony/entry/src/main/ets/platform_impl/SSHForegroundApproval.ets` | +31/−0 | 31 |
| `harmony/entry/src/test/ssh_chat_tools.test.cjs` | +3/−1 | 133 |
| `harmony/entry/src/test/ssh_foreground_approval.test.cjs` | +38/−0 | 38 |
| `harmony/native/ssh/scripts/build-host-addon.sh` | +1/−1 | 17 |
| `harmony/native/ssh/tests/jump_fixture.py` | +83/−0 | 83 |
| `harmony/native/ssh/tests/jump_native.test.cjs` | +110/−0 | 110 |
| `tools/ssh-it/.gitignore` | +1/−0 | 1 |
| `tools/ssh-it/Dockerfile` | +10/−0 | 10 |
| `tools/ssh-it/README.md` | +40/−0 | 40 |
| `tools/ssh-it/compose.yml` | +27/−0 | 27 |
| `tools/ssh-it/run-native.cjs` | +48/−0 | 48 |
| `tools/ssh-it/setup.sh` | +8/−0 | 8 |
| `tools/ssh-it/sshd_jump.conf` | +16/−0 | 16 |
| `tools/ssh-it/sshd_jump2.conf` | +16/−0 | 16 |
| `tools/ssh-it/sshd_target.conf` | +11/−0 | 11 |
