# 高风险自动批准与暂停恢复

## 行为

- 高风险自动批准独立生效，不再依赖全局常规自动批准。SSH/终端、文件写入、MCP、屏幕、子代理等共享权限判定路径均使用这一规则。
- 能力策略为 AUTO 时，不再让工具自身的 mandatory/alwaysAsk 提前阻断已经开启的高风险自动批准。
- 显式 DISABLED/ASK 能力策略、ask_user、主题导入和网站目录确认保持原契约；执行沙箱和系统权限未放宽。
- 开关写入改用最新设置的 transform 更新，避免旧快照覆盖另一个开关。
- 返回当前聊天时，可以重新判定同一持久化 WAITING_USER 运行中尚未执行的审批。原调用按当前策略以 Auto 执行，不伪造用户 Approved 或手工授权记录。同批还有人类输入/强制确认时整批等待。
- 恢复绑定 conversation、runId 和工具 metadata.run_id；已停止、失配或不具备持久化运行能力时不自动启动。不会恢复旧背栈会话，也不会借此排空用户消息队列。
- UI Stop 能识别已释放 session job 的待审批运行；固定 runId 与既有 AgentRunner 启动检查/取消意图配合，避免取消后另起运行。
- generation sanitizer 保留合法 Pending 检查点，防止恢复前把审批消息删掉。

## 验证

- 修改前，独立高风险开关、AUTO 能力下的 mandatory 工具、连续 SSH/文件调用三个最小用例均失败。
- 最终 app 定点测试 86 项通过：PermissionDecisionResolverTest（29）、CapabilityPermissionResolverTest（17）、AgentToolDispatcherTest（14）、DefaultRunKernelTest（22）、ChatGenerationFinalizationTest（4）。
- 既有 AgentRunner 启动检查定点测试 3 项通过：暂停恢复 CAS、恢复检查期间取消、终态记录阻止执行。
- `:app:assembleGraphite` 通过，`git diff --check` 通过。
- 采用本机 JDK 21、Android SDK 与离线 Gradle；`-Pksp.incremental=false`。
- APK：`app/build/outputs/apk/graphite/app-arm64-v8a-graphite.apk`
- APK SHA-256：`833f7b58583d18fcb5ea7f28d08b89a9735b2911d44ee8b6c56ad3987c81e881`
- 首页及已确认聊天视觉的 11 个基线文件保持一致；ChatPage 仅增加可见时的恢复回调，ChatPageSplit 同样只增加生命周期回调。

以上是 JVM/Robolectric、Room 与构建证据。测试工具/模型使用本地替身，没有执行真实 SSH 命令或调用真实 provider。最终 ADB 仅有模拟器在线，未覆盖安装真机。既有未提交 WIP 保留，本次未提交或推送。

## 设计原型

后续经用户确认，五项聊天样式已接入 Android 原生页面，详见 [原生落地与验收](2026-09-13-native-chat-soft-timeline.md)。

OpenDesign 项目：`amber-chat-timeline-soft-v2-2026-09-13-d779`。原型保持原用户气泡配色、顶底栏布局，思考增加外框，普通工具为 30px 视觉本体/48px 点击区。审批改为时间线内卡片，提供参数展开、可选拒绝理由和文字按钮；批准只进入“待执行”。上下文弹层 260px，进入 380ms、退出 240ms。

浏览器已检查浅色、深色、320px 宽度、思考展开、审批/拒绝、上下文开合/快速切换及焦点返回；320px 下页面无横向溢出。设计通过 OpenDesign MCP 保存，尚未应用为 Android 新视觉。
