# AmberAgent for Android

AmberAgent 的 Android 产品仓库。应用、Compose UI、Room、WorkManager、Keystore、Android DI 与本地 Native 构建都在本仓维护；iOS 产品代码不再混放。

## 构建

```bash
./gradlew :app:assembleDebug
```

需要完整 Native APK 时，先安装 Rust、`aarch64-linux-android` target、Android NDK `27.0.12077973` 与 `cargo-ndk` 3.5.4。缺少 `cargo-ndk` 时 debug 构建会明确跳过 Native task，只能证明 JVM/普通 APK 组装；发布 CI 会验证八个 required `.so`。细节见 `native/README.md`。

Core 只接收两端已经共同消费且平台无关的稳定契约；当前 Android 代码不通过相对路径读取其他仓库。

## SSH

SSH 客户端固定使用 `com.github.mwiede:jsch:2.28.7`。

在「设置 → 运行环境 → Agent 运行环境 → SSH 配置」添加多个服务器 profile，配置名称、地址、端口、用户名和密码或 RSA/ECDSA 私钥。私钥可设置解密口令，凭据使用本机 Android Keystore 保护，不进入普通配置或聊天工具参数。可编辑、删除和选择默认 profile。

首次连接先探测服务器主机密钥，对照服务器指纹后确认；服务器地址或端口变更会清除信任。密钥不匹配会阻止认证，需要重新核对并明确接受。

在 SSH 区域选择 profile 输入命令，也可让 Agent 使用 `terminal_ssh_profiles` 查找 profile，再调用 `terminal_execute` 或 `terminal_job_start`，传入 `runtime=remote_ssh` 和 `ssh_profile_id`。长任务沿用 `terminal_job_read/wait/stop`。远端路径不会映射到手机的 `/workspace`，安装软件应显式使用远端自己的包管理器。

当前支持一次性远程命令及后台任务，不提供交互式 PTY、SFTP 或重启后自动重连。取消、超时或连接丢失后无法证明远端进程已停止，任务显示中断；只有服务器返回退出码才判定完成或失败。当前无额外加密库的 Android 客户端支持 RSA/SHA-2 和 ECDSA，不支持 Ed25519/Ed448。

定点测试、模拟器实连记录与验证边界见 [SSH 验证记录](docs/verification/2026-09-11-ssh.md)。
