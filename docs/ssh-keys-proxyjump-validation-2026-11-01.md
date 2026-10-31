# Android SSH 本机密钥与 ProxyJump 验证记录

基线 `cf80b6f86e31997b113f717bf14153007a2f692b`，分支 `synara/amber-android-baseline-validation`。
接续并完成此前中断的未提交任务。未推送，未安装手机，未连接真实服务器。

工具参数及行为说明见 [ssh-keys-proxyjump-android.md](ssh-keys-proxyjump-android.md)。
Docker 文件和运行步骤见 [../tools/ssh-it/README.md](../tools/ssh-it/README.md)。

## 验证内容

- 路由解析：直连与 1–3 个跳板，悬空引用、循环、深度上限及反向依赖写入校验；被引用的密钥与跳板禁止删除。
- 密钥：Ed25519、ECDSA P-256 生成与导回；系统 `ssh-keygen -lf` 独立验证公钥指纹；系统 `ssh-keygen` 生成的带口令 Ed25519 OpenSSH 私钥导入，正确/错误/缺少口令和超长输入验证。
- 传输：本机隔离的三个 `/usr/sbin/sshd`，仅绑定随机 loopback 端口；Ed25519 用户认证，两个 `ForceCommand /usr/bin/false` 跳板，经 direct-tcpip 成功执行目标命令并返回 0；所有跳凭据参与输出脱敏；第 2 跳指纹不匹配拦截目标命令；`PermitOpen` 拒绝归属跳板第 2 跳。
- 审批：普通自动批准不放行写工具；显式高风险开关保留；子代理不暴露六个 SSH 写工具；等待期间更改上游用户名或运行环境，dispatcher 返回 `approvedTargetChanged` 且不调用执行器。
- 脱敏：成功、失败、拒绝、取消、超时、放弃的结束调用清除凭据；待审批及残缺 JSON 检查点扣留凭据；中断投影回归。
- Room / 锁屏：Robolectric 下通过真实 ConversationRepository / Room 写入读取，待审批 private_key 与 passphrase 不落盘，重载为明确拒绝；锁屏密钥生成调用不能批准，仍可拒绝。
- `git diff --check` 通过；只对本次接续改动做了一次有限维护性检查，没有引入超过 1000 行的 Kotlin 文件。

## 执行命令

核心模型、设置、连接与 app 定点测试（在当前工作目录）：

```bash
HOME=/Users/arquiel JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  :core:model:testDebugUnitTest --tests '*Ssh*' \
  :core:settings:testDebugUnitTest --tests '*Ssh*' \
  :feature:terminal:testDebugUnitTest --tests '*Ssh*' \
  :app:testFullDebugUnitTest --tests '*Ssh*' \
    --tests '*InterruptedRunProjectionTest' --tests '*ChatApprovalActionsTest'
```

修改存储校验、Room 与锁屏回归后，重跑受影响的 settings / app 测试并构建：

```bash
HOME=/Users/arquiel JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  :core:settings:testDebugUnitTest --tests '*Ssh*' \
  :app:testFullDebugUnitTest --tests '*Ssh*' \
    --tests '*InterruptedRunProjectionTest' --tests '*ChatApprovalActionsTest' \
  assembleFullGraphite
```

上述两轮受影响检查均通过。最后统一路由失效回执为 `status=failed, code=approvedTargetChanged`，使 UI 和重复调用守卫能正确识别失败，再只跑该 guard 的定点测试和 `assembleFullGraphite`。

| 模块 / 测试组 | 用例 | 通过 | 跳过 |
|---|---:|---:|---:|
| `core/model` 定点集合 | 20 | 20 | 0 |
| `core/settings` 定点集合 | 16 | 16 | 0 |
| `feature/terminal` 定点集合 | 23 | 14 | 9 |
| `app` 定点集合 | 68 | 68 | 0 |

已有 XML 的摘要保存在本地 `build/ssh-focused-tests-evidence.json`。这是针对本次变化与既有直接回归的集合，未跑全仓测试。

最终 guard 回归 1 项通过，`assembleFullGraphite` 成功（最后一轮 53s）。产物有 JSch BC Ed25519 实现及 BC Ed25519 参数类。构建带有既有的弃用/非空断言警告，没有编译或测试失败。

基线通过 `git archive cf80b6f86` 在当前仓库的忽略目录 `build/ssh-apk-baseline-cf80b6f86/` 构建，同一命令、JDK、local.properties 和现有 debug 签名；没有覆盖工作区。以下是整个功能的实测增量，包含 BC 与代码的变化，不是单独 BC 的理论体积。

| APK | 基线字节数 | 本次字节数 | 增量 |
|---|---:|---:|---:|
| `app-full-arm64-v8a-graphite.apk` | 119,060,313 | 123,415,794 | +4,355,481（4.154 MiB，3.66%） |
| `app-full-universal-graphite.apk` | 119,060,313 | 123,415,794 | +4,355,481（4.154 MiB，3.66%） |

- `app-full-arm64-v8a-graphite.apk` SHA-256：`0eb24a10b5df958eb73ad7de7c88ab3548b391bccdef518a62c90d8fecf2a9d5`
- `app-full-universal-graphite.apk` SHA-256：`0eb24a10b5df958eb73ad7de7c88ab3548b391bccdef518a62c90d8fecf2a9d5`

最终 APK 位于 `app/build/outputs/apk/full/graphite/`。本地体积/hash 证据：`build/ssh-final-apk-evidence.json`；构建与验证日志：`/tmp/amber-ssh-final-validation.log`、`/tmp/amber-ssh-final-result-check.log`、`/tmp/amber-ssh-baseline-build.log`。

## 未验证边界

- 本机 PATH 和通常 Docker Desktop / Homebrew / ~/.docker 二进制位置均无 Docker。Docker 测试明确跳过，Compose 未宣称实测通过。
- 既有 8 个外部 loopback fixture 用例未设置 `AMBER_SSH_LOOPBACK_FIXTURE`，按原有门禁跳过；本次自建隔离 sshd 多跳用例实际执行通过。
- 按任务要求不安装手机、不连接真实服务器；设置页视觉/交互、真实 Android Keystore、OEM 后台和设备锁屏行为未做物理设备验收。锁屏门禁本次证据为 Robolectric。
- FullGraphite 是现有 debug 派生构建，不开启 R8。R8 消费规则已包含，但本轮未另跑 minified release。

## 改动文件与最终行数

| 文件（相对仓库根） | 最终行数 |
|---|---:|
| `app/src/main/java/app/amber/core/ai/ApprovalBatchPlanner.kt` | 198 |
| `app/src/main/java/app/amber/core/ai/DefaultRunKernel.kt` | 778 |
| `app/src/main/java/app/amber/core/ai/tools/LocalTools.kt` | 187 |
| `app/src/main/java/app/amber/core/di/DataSourceModule.kt` | 845 |
| `app/src/main/java/app/amber/core/di/ToolExecutionModule.kt` | 79 |
| `app/src/main/java/app/amber/core/repository/ConversationRepository.kt` | 686 |
| `app/src/main/java/app/amber/core/service/ChatApprovalActions.kt` | 559 |
| `app/src/main/java/app/amber/core/utils/CredentialArgumentRedaction.kt` | 237 |
| `app/src/main/java/app/amber/feature/bubble/AgentBubbleReducer.kt` | 207 |
| `app/src/main/java/app/amber/feature/bubble/AgentTaskBubbleController.kt` | 241 |
| `app/src/main/java/app/amber/feature/chat/impl/InterruptedRunProjection.kt` | 131 |
| `app/src/main/java/app/amber/feature/runtime/AgentToolDispatcher.kt` | 845 |
| `app/src/main/java/app/amber/feature/runtime/SshApprovalTargetGuard.kt` | 76 |
| `app/src/main/java/app/amber/feature/ui/components/message/ChatMessageTools.kt` | 391 |
| `app/src/main/java/app/amber/feature/ui/components/message/ChatToolApprovalCard.kt` | 217 |
| `app/src/main/java/app/amber/feature/ui/components/message/SshApprovalCardSupport.kt` | 133 |
| `app/src/main/java/app/amber/feature/ui/components/message/ToolMessagePresentation.kt` | 672 |
| `app/src/main/java/app/amber/feature/ui/pages/chat/ChatSandboxActivityMapper.kt` | 417 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SettingSshProfiles.kt` | 264 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SshHostKeyTrustDialog.kt` | 104 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SshKeysSection.kt` | 238 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SshProfileCard.kt` | 199 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SshProfileEditor.kt` | 351 |
| `app/src/main/java/app/amber/feature/ui/pages/setting/SshProfilesScreenSession.kt` | 285 |
| `app/src/test/java/app/amber/agent/data/agent/runtime/SshProfileToolApprovalTest.kt` | 221 |
| `app/src/test/java/app/amber/core/service/ChatApprovalActionsTest.kt` | 273 |
| `app/src/test/java/app/amber/feature/runtime/SshApprovalTargetGuardTest.kt` | 76 |
| `app/src/test/java/app/amber/feature/tools/SshKeyAndProxyJumpToolsTest.kt` | 391 |
| `app/src/test/java/app/amber/feature/tools/SshProfileToolsTest.kt` | 842 |
| `core/model/src/main/kotlin/app/amber/feature/terminal/SshKey.kt` | 89 |
| `core/model/src/main/kotlin/app/amber/feature/terminal/SshProfile.kt` | 164 |
| `core/model/src/main/kotlin/app/amber/feature/terminal/SshRouteResolver.kt` | 207 |
| `core/model/src/main/kotlin/app/amber/feature/terminal/TerminalRuntimeModels.kt` | 104 |
| `core/model/src/test/kotlin/app/amber/feature/terminal/SshRouteResolverTest.kt` | 177 |
| `core/settings/src/main/kotlin/app/amber/core/settings/ssh/SshKeyStore.kt` | 276 |
| `core/settings/src/main/kotlin/app/amber/core/settings/ssh/SshProfileStore.kt` | 494 |
| `core/settings/src/test/kotlin/app/amber/core/settings/ssh/SshKeyAndRouteStoreTest.kt` | 205 |
| `docs/ssh-keys-proxyjump-android.md` | 82 |
| `feature/terminal/build.gradle.kts` | 47 |
| `feature/terminal/consumer-rules.pro` | 72 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/DirectTcpipProxy.kt` | 116 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/SshClient.kt` | 595 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/SshHopChain.kt` | 443 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/SshKeyPairs.kt` | 221 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalModels.kt` | 28 |
| `feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt` | 855 |
| `feature/terminal/src/test/kotlin/app/amber/feature/terminal/SshKeyPairsTest.kt` | 109 |
| `feature/terminal/src/test/kotlin/app/amber/feature/terminal/SshProxyJumpClientTest.kt` | 332 |
| `feature/terminal/src/test/kotlin/app/amber/feature/terminal/SshProxyJumpDockerTest.kt` | 123 |
| `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolProfileFilter.kt` | 179 |
| `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt` | 980 |
| `feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SshKeyTools.kt` | 295 |
| `feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SshProfileTools.kt` | 590 |
| `feature/tools/impl/src/main/kotlin/app/amber/feature/tools/TerminalTools.kt` | 448 |
| `gradle/libs.versions.toml` | 197 |
| `tools/ssh-it/Dockerfile.sshd` | 15 |
| `tools/ssh-it/README.md` | 40 |
| `tools/ssh-it/compose.yml` | 52 |
| `tools/ssh-it/sshd_jump.conf` | 10 |
| `tools/ssh-it/sshd_jump2.conf` | 10 |
| `tools/ssh-it/sshd_target.conf` | 7 |
| `docs/ssh-keys-proxyjump-validation-2026-11-01.md` | 139 |
