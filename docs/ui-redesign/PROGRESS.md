# 实施与验收记录

## Phase 0

- 建立 `codex/ui-refinement`，独立工作树 `/private/tmp/amber-android-ui`，基线 `d752f90`。原main未完成合并不变。
- 原型提取并捕获深/浅首页，以及设置、聊天、provider、看板、小说、议会、技能、模型、小应用等参考截图。保留中性瓦片、单accent、mono机器信息；改正低对比小字和过紧热区，不强制照搬底纹。
- 编译：`JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew :app:compileDebugKotlin --offline --console=plain -Pksp.incremental=false`，成功，1m33s。
- APK：同环境 `:app:assembleDebug`，成功，1m13s；Debug为`app.amber.agent.graphite`并存包。构建日志位于 `/private/tmp/amber-ui-baseline-build.log`、`/private/tmp/amber-ui-baseline-apk.log`。
- 独立计划review：要求补路由清单和逐条审计追踪，已建立 `AUDIT_TRIAGE.md`；reviewer完成 `ROUTES.md` 并核对66个Screen全部覆盖。补充隐藏Debug入口、外部入口、议会创建owner链和精确variant。计划本身未将Debug判为死路由，不接受原型中的该推断。两项范围问题已闭合，进入Phase1。
- 环境：ADB和AndroMeld均无设备。官方模拟器安装到临时隔离SDK，沿用已有SDK许可；未操作用户手机数据。真实布局截图待模拟器启动后补，构建成功不等价视觉验收。

## Phase 1

已完成：主题/M3/Workspace桥接与共享行组，5个生产文件，保留owner和持久化契约。四base ink3提升；AMOLED统一token源；共享header和常规UI字体/圆角收口；CardGroup单卡片内部线，rawItem与trailing控件保持原契约。

- 原有Compose测试：GraphiteComposePipeline 1、ComposerInteraction 4、ModelMenuInteraction 5，共10通过。
- 新增一项行为回归：CardGroup trailing Switch点击不触发row；同文件合计2通过。首次运行遇到测试用真实Application导致Koin重复启动，按项目既有裸Application约定隔离后通过；不是产品bug。
- 实际APK在新建API36模拟器冷启动成功，主进程PID已确认；应用内选择深色，更新APK重启后保持深色。
- AndroMeld无法建立模拟器视频会话（phase failed、video 0×0）；CUA不能识别非bundle模拟器窗口。改用仅绑定`emulator-5554`的Android SDK启动/截图/UI树验收，未触碰真实手机。临时操作脚本 `/private/tmp/amber-ui-device.py` 不进入产品源码。
- 实际截图：`device/phase1-settings-dark.png`、`device/phase1-experiments-dark.png`；独立review已查看设置页并确认无裁切、箭头挤压或入口断链。
- 发现并修复：ExperimentDivider重复计入14dp父padding，start58→44；WebMount说明两行截断，取消描述限行。截图记录初次发现状态，修复后继续在产品验收覆盖。
- 最终 `:app:assembleDebug` 成功15s；`git diff --check`通过。独立review结论无阻断，进入Phase2。

## Phase 2

实施中：首页、设置/服务商/模型及入口列表，依据ROUTES保留所有入口。
