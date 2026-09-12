# SSH 双版本与小米手机交付 · 2026-09-12

稳定版源码为 main `e771914`，包名`app.amber.agent`；实验版为`codex/ui-refinement`的`ee22ffc`，包名`app.amber.agent.graphite`。两者均已覆盖安装到用户的小米M610BB，保留各自数据。实验版仅移植已有SSH及必要依赖，没有全量合并main的数百个其他改动。

## 功能与入口

- 稳定版：设置 → 运行环境 → Agent运行环境 → SSH配置。
- 实验版：设置 → Agent运行环境 → SSH配置。
- 支持多个profile、密码/私钥认证、默认profile、认证前服务器指纹确认、远端命令与输出、取消/超时及unknown状态。终端工具可以传remote_ssh与ssh_profile_id。
- 独立review发现Model Council跟随SSH时误用Android本地目录，已采用main同样的远端HOME分支修复。
- 当前支持RSA/SHA-2和ECDSA，不支持Ed25519/Ed448；不是交互式PTY终端，不提供SFTP。

## 验证

实验分支65项定点JVM测试通过：profile存储11、凭据迁移25、指纹信任11、客户端8、终端模型8、ModelCouncilManager 2；另有8项真实SSH回环测试通过。main的8项真实SSH回环也重新通过。重复字符串资源已修正；D8因原临时工作树路径产生的缓存问题已通过清理涉及模块的构建输出解决，没有为缓存问题改变生产逻辑。

两个版本都在这台真实小米手机上完成一次完整SSH命令回环：临时profile的私钥经应用自己的Keystore保存，探测指纹与本次生成的服务器公钥核对，明确接受该指纹后，经TerminalRuntime执行printf并获得exitCode=0与预期输出。服务器只监听Mac回环，通过本次USB反向映射访问。测试profile已删除，原profile集合保持不变；没有连接用户服务器、使用用户SSH私钥或改变用户默认运行时。

两包签名一致且与原稳定版匹配，使用install -r，未卸载正式应用或清理数据。稳定版Room从16迁移到17，原有2个会话和4个消息节点的ID全部保留。独立新增的运行记录不作为原数据丢失。实验版Workspace权限保持。

## Provider / model迁移

稳定版16个provider、18个model已导入实验版；同时保留favoriteModels、chat/title/image/suggestion/ocr/compress模型选择、modelGroupSessionDefaults与逐模型思考等级。通过两个应用现有SettingsAggregator及SecretStore进行读取/写入，没有直接复制跨包Keystore密文，也没有覆盖聊天库、主题或Workspace。

源导出、目标导入、目标杀进程后的重新读取均通过；后者逐字段比较配置和重新解密后的provider内容一致，不只检查数量。未触发付费模型生成或外部API测试。

一次性迁移/设备验证代码位于任务临时目录，未进入产品或仓库。手机私有目录中的明文迁移文件和临时私钥、临时instrumentation APK、USB反向映射及临时sshd均已清理。本地备份留在权限受限的任务目录，未放入仓库。

## 产物

APK绝对路径、源码提交和SHA-256见[BUILD.json](BUILD.json)。

- [稳定版SSH入口](device/stable-ssh-ready.png)
- [实验版SSH入口](device/experimental-ssh-final.png)

当前手机停留在实验版SSH配置页，可添加用户自己的服务器。需要核对服务器指纹并提供相应登录凭据，不能把本次回环验证当作某台用户服务器已经连通。
