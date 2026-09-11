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

已完成：首页、设置/显示/服务商/模型、搜索/收藏/资料/关于，依据ROUTES保留所有入口。Home继续区视觉合并但每个candidate仍是独立Lazy item，未删cap外候选；日期与wordmark分行，16dp基线，中性图标。Provider长名保留状态点。分享state、连接测试取消、MCP空名称、SearXNG密码掩码与Form modifier按审计表精准修正。

- 编译初次缺少SettingModelPage的layout.size导入，补齐后成功。`assembleDebug` + ModelMenuInteraction 5 / ModelContextWindowInput 1，共6通过；最后共享说明字阶调整后的CardGroup测试2通过。
- 两组独立源码review通过：Home/设置/模型的创建/选择/持久化链，以及Provider/表单/搜索/资料/收藏/隐藏Debug链均未断。
- 模拟器实际完成Home→Settings→Display，选择Sage、开启/关闭AMOLED；checked状态与纯黑背景一致，更新APK冷启动后设置保留。
- 仅在新建模拟器中种入3条标注UI验收的本地会话、6条消息，用于实际Room/渲染器的长标题、推理、代码、表格验证。无provider调用，夹具不是网络或完整统计服务验收。
- 实际截图：phase2-home-dark、settings-dark、display-dark、display-amoled、profile-narrow-large。360dp/fontScale1.3发现等分入口标签断行和图标上下错位；改为真实TextMeasurer测标签宽度、Top对齐、必要时横滚。固定64sp宽度因Android非线性缩放仍不足，已撤掉该中间方案。
- 最终截图 `device/phase2-home-narrow-large-final.png` 标签单行；实际横滚确认模型议会可达。Profile统计横滚确认第五项完整可见。副标题使用secondary字阶，时间使用ink3；颜色选择补可访问selected语义。
- 最后rail精准修复后assemble成功10s，独立review已看最终截图并放行。恢复模拟器density/fontScale默认。进入Phase3。

## Phase 3

实施中：聊天与消息、Markdown、导出；保留流式状态机与所有工具/审批owner。
