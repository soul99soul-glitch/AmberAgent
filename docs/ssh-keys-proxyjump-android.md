# Android SSH 本机密钥与 ProxyJump

实现基线：`synara/amber-android-baseline-validation`，`cf80b6f86e31997b113f717bf14153007a2f692b`。
本次接续并完成此前中断线程的未提交实现，只改 Android 仓库。本期不包含 `-L`。

## 工具 Schema

以下列出模型可见参数；属性的说明文字以 `SshKeyTools.kt` 为准。

```json
[
  {
    "name": "ssh_key_list",
    "parameters": {"type": "object", "properties": {}}
  },
  {
    "name": "ssh_key_generate",
    "parameters": {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "type": {"type": "string", "enum": ["ed25519", "ecdsa-p256"], "default": "ed25519"}
      },
      "required": ["name"]
    }
  },
  {
    "name": "ssh_key_import",
    "parameters": {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "private_key": {"type": "string"},
        "passphrase": {"type": "string"}
      },
      "required": ["name", "private_key"]
    }
  },
  {
    "name": "ssh_key_delete",
    "parameters": {
      "type": "object",
      "properties": {"key_id": {"type": "string"}},
      "required": ["key_id"]
    }
  }
]
```

`ssh_key_list` 只读、免审批。其余三个工具和原有三个 profile 写工具均为高风险；普通自动批准不放行，既有“自动批准高风险工具”开关仍有效。写工具不提供给子代理。通知不能批准高风险调用；锁屏状态下的 SSH 写入和终端人工批准均被拒绝，仍可拒绝调用。

生成支持 Ed25519、ECDSA P-256。导入支持带口令的 OpenSSH 和 ECDSA P-256 PEM，以及 JSch 支持的 RSA；超过 64 KiB、错误口令和脱敏占位符被拒绝。Android 本期没有每次使用时要求生物识别的能力，不暴露该开关，也不接受 `require_user_presence=true`。

密钥响应只包含元数据、公钥和指纹（兼容 `public_key` / `public_key_openssh`、`fingerprint` / `fingerprint_sha256`），私钥和口令不返回。私钥与可选口令由现有 SecretStore 的 Keystore AES/GCM 信封加密后保存，scope 为 `ssh_key`。存储层在同一把锁内检查当前 profile 引用；被引用的密钥禁止删除，密钥材料不原地替换。

工具描述明确要求：先生成密钥，将公钥交给用户追加到服务器 `authorized_keys`，再用 `key_id` 创建 profile。设置页提供“生成密钥”“复制公钥”、密钥选择及跳板选择。

## Profile 与终端契约

- `ssh_profile_create/update` 增加 `key_id: string`、`jump_profile_id: string | null`。`jump_profile_id=null` 或空字符串清除跳板，省略则保留。
- `auth_method=key` 为模型输出规范值；仍接受 `private_key` / `privateKey` 等旧输入。持久化枚举保留旧序列化名，旧 profile 继续可读。旧的 inline `private_key` 凭据写法仍可用。
- `key_id` 与 inline `private_key` 不能同时提供；引用存储密钥时不能在 profile 上改口令，应重新导入密钥。
- 最多三个跳板、四个端点。解析检测悬空引用与循环；写入时校验所有反向依赖，失败不发布新 profile 或新凭据。被用作跳板的 profile 禁删。
- 两个 listing 均计算整条路由的指纹和凭据准备状态；profile listing 包含 `key_id`、`key_fingerprint`、`jump_profile_id`、路由名和 ID。
- `terminal_execute` / `terminal_job_start` 支持 `profile_id`，旧 `ssh_profile_id` 别名继续有效；`terminal_job_read/wait/stop` 沿既有 job 跟踪同一连接。成功/运行回执包含 `route`。
- 逐跳失败回执包含 `code`、`hop_index`（从 1 起）、`hop_count`、`profile_id`；指纹变化还包含预期与观察到的指纹。`jump_forward_denied` 的索引指向拒绝转发的跳板。SSH 断开仍不能证明远端进程已退出。

## 连接、信任与审批

每个端点使用独立 JSch session 和身份。下一跳传输使用 `ChannelDirectTCPIP` 的自定义 `Proxy`，不在跳板执行 shell。BC 提供 Ed25519 / Curve25519 实现，消费规则保留 JSch 通过反射加载的算法类。

每一跳在发送认证前执行固定指纹校验。host、port、jump profile 变化清除本跳信任。首次指纹可在前台审批卡或设置页核对；审批卡显示跳数，指纹不匹配时隐藏批准动作，只能去设置页人工替换。信任写入同时核对完整探测路由快照，防止探测期间上游改动。

审批绑定运行环境和完整 profile 路由摘要，执行前再次校验，job 接纳时再校验同一摘要。等待审批时改用户名、主机、凭据版本、路由或信任，均不能沿旧审批执行；返回 `approvedTargetChanged`，需发起新调用。首次信任也会改变摘要，旧调用需拒绝并重新发起。

待审批原始凭据只保留在当前活动会话内存。Room 存储和检查点副本会扣留凭据，重启后该调用明确拒绝，要求重新提供；已结束调用脱敏。成功、失败、拒绝、取消、超时和放弃路径复用这一处理，中断投影也清除未结束调用凭据。残缺 JSON 和非对象参数同样不会原样落盘。所有跳的凭据进入流式输出脱敏器；运行时凭据对象的 `toString` 脱敏。

## 验证与交付

实际结果见同目录 `ssh-keys-proxyjump-validation-2026-11-01.md`。
Docker 环境与运行步骤：`tools/ssh-it/README.md`。
本次明确不安装手机、不连接真实服务器、不推送。
