# Android UI 实验版交付

分支：`codex/ui-refinement`。实现基线：`d752f90`。没有合并或推送到main，也没有自动吸收main在其他任务中的后续更新。

采用原型的分组、16dp边距、克制的单强调色和mono/sans分工；保留Android的真实功能、流式渲染、交互与Terminal × Graphite身份。没有照搬全屏点纹、彩色分类瓦片或强制浅色阅读纸面。

## 阶段闭环

| 阶段 | 交付 |
| --- | --- |
| 0 | 原型判断、隔离分支、审计真伪复核、66路由清单 |
| 1 | 主题与共享组件统一 |
| 2 | 首页、设置、服务商、搜索和资料入口 |
| 3 | 聊天、输入、审批/工具chrome、Markdown和导出 |
| 4 | 小说、阅读、议会、技能、小应用、备份与其余设置 |
| 5 | 跨页主题、窄屏大字体、独立总检、回归和APK |

每阶段均进行了subagent独立review，发现的问题按生产路径定点修复。详情见[过程与验证](PROGRESS.md)、[审计复核](AUDIT_TRIAGE.md)、[设计决策](PLAN.md)和[路由清单](ROUTES.md)。

## 验证结果

79项定点JVM/Robolectric回归通过；最终APK成功构建、安装并冷启动主进程。APK内已核对8个Rust JNI库。真实API36模拟器验证普通411dp、360dp/1.3倍字体、键盘，以及Warm/Sage深浅与AMOLED。

模拟器新建本地会话、小说项目和只读theme小应用夹具；这些没有进入产品默认数据。没有付费provider调用或真实账户备份恢复验收。一次首次Chromium初始化超时已记录，后续打开恢复且未新增ANR；真机性能仍需真实设备测量。

## 代表截图

以下均为安装APK后的模拟器页面，不是原型或Compose假数据Preview。早期before、fixed和Phase1截图保留为发现/过程证据；以这里列出的最终页为准。

| 页面 | 截图 |
| --- | --- |
| 首页浅色 | [Warm浅色首页](device/phase5-home-warm-light.png) |
| 首页窄屏大字体 | [360dp与1.3倍字体](device/phase2-home-narrow-large-final.png) |
| 设置浅色 | [Sage浅色设置](device/phase5-settings-sage-light.png) |
| AMOLED | [纯黑背景](device/phase2-display-amoled.png) |
| 聊天与Markdown | [表格、代码与消息](device/phase3-chat-after.png) |
| 小说 | [工作区](device/phase4-novel-workspace-final.png) / [窄屏键盘](device/phase5-novel-narrow-keyboard.png) |
| 议会 | [键盘布局](device/phase4-council-keyboard.png) |
| 技能 | [Warm浅色技能库](device/phase5-skills-warm-light.png) |
| 资料窄屏横滚 | [第五项完整可见](device/phase5-profile-narrow-scrolled.png) |
| 小应用 | [两行标题卡片](device/phase5-miniapps-final.png) / [真实theme API](device/phase4-miniapp-theme.png) |
| 看板 | [设置与未启用状态](device/phase4-board-settings.png) |
| 备份 | [缺少Google配置时的真实状态](device/phase4-backup.png) |

列表里的摘要和超长标题允许省略，说明、正文和表单不套用这一规则。小应用名称独占两行；首页功能入口在窄屏横向滚动，全部入口保留。

## 安装包与源码绑定

工作目录：`/Users/arquiel/Downloads/AI/AmberAgent/android-ui-refinement`。源码提交：`e863706`；其后的交付提交只包含文档和截图。

[安装实验 APK](/Users/arquiel/Downloads/AI/AmberAgent/android-ui-refinement/app/build/outputs/apk/debug/app-universal-debug.apk)（ARM64，Debug，约103.3 MiB）。包名 `app.amber.agent.graphite`，可与正式包并存，不包含模拟器夹具。

SHA-256：`c0458aa2ff9f0d982f31cedd85edc2490ba186529b0661d45e68ec555d1305a1`。完整构建清单见[BUILD.json](BUILD.json)。

思考状态后续调整：等级显示为低/中/高/极高/最大，替代token预算；进行中使用“正在思考”，耗时在旁显示。6项既有思考组件测试与构建通过，原始截图尚未补拍这一文案变化。

思考图标底色修正：无时间线时图标遮罩透明，保留时间线场景原有遮挡。模拟器实测[折叠态](device/thinking-icon-transparent.png)与[展开态](device/thinking-icon-expanded.png)，方形色块消失。

个人资料现可点击名字/铅笔编辑昵称，保存后资料页、首页头像、新聊天问候与聊天署名复用同一字段。已在模拟器验证空白不可保存、取消不写入、保存后杀进程重启及[已有聊天署名](device/nickname-chat-after-relaunch.png)保持一致；[资料页](device/nickname-profile-saved.png)中的Alex仅为模拟器测试昵称，不是产品默认值。

SSH已补齐并更新小米手机上的两个版本，真实设备命令回环通过。稳定版的16个provider/18个model及模型选择也已导入实验版，并通过重启后逐字段核对。见[双版本交付](SSH_DELIVERY.md)。

后续配置验证发现普通设置保存会全局清理SSH凭据，现已修复并在两包更新。日本SSH、Moli和已有Mac mini SSH均通过独立重启验证；详情见[凭据边界修复](SSH_CREDENTIAL_FIX.md)。
