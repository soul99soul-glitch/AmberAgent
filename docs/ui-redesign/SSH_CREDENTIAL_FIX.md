# 普通设置保存误删 SSH 凭据的修复

实验分支提交`e863706`；main仅提交三个相关文件为`a7d0ada`，保留其他任务的WIP。稳定包使用e771914隔离树加同一修复构建，源码提交`6b3026f`，未把进行中的无关重构装到手机。

## 根因与范围

SettingsAggregator正常保存结束调用SecretRedactor.deleteOrphans，active集合仅来自普通设置的SECRET_REFS；旧实现却清理整个SecretStore，所以独立ssh scope会被误删。已有SettingsSecretMigrator正确限定了七个设置scope，但日常保存遗漏了该边界。

修复让两处共用同一个内部scope集合，仅清理provider/assistant/search/mcp/webdav/s3/tts。SecretStore通用删除语义与SshProfileStore自有回收不变。

新增一个最小回归：active provider保留、无引用MCP删除、独立SSH保留。旧实现失败；修复后两个代码版本各45项凭据相关测试通过（SecretRedactor 9、SettingsSecretMigration 25、SshProfileStore 11）。独立review确认了生产调用链和清理owner边界。

## 手机验证与恢复

两个包保留数据覆盖更新。日本SSH和Moli已配置到两包，Moli可发现24个工具并成功执行只读browser_tabs。六次独立重启验证全部通过：两包的日本SSH、Moli、Mac mini SSH各一次；执行MCP设置同步后，SSH密钥仍存在且能完成实际命令。

组合式临时配置验证曾卡住，已中止并用各自独立冷启动核验；这些结果不声称证明多客户端并发。未降低工具审批，也未绕过TLS或主机指纹验证。

误删发生后，稳定版原Mac专用密钥仍保留；导出该私钥的方案被自动审批拒绝，未执行。改为给实验版生成新的专用密钥并追加相应Mac授权，稳定版原密钥不变。两个Mac连接均已重新验证。

临时明文SSH密钥、MCP认证文件和手机上的instrumentation APK已清理；正常配置经现有Keystore保存。配置与凭据不写入仓库。

美国VPS尚未配置：本机athena经云元数据验证为东京机房，不能据此替代用户未提供的美国目标。
