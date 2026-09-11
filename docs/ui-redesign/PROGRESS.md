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

已完成：聊天header/composer/附件、消息操作/分支/审批chrome、图片索引、上下文/排队/跳转控件、Markdown与导出。保留流式状态机、缓存/reveal、单次表格测量、所有发送/取消/工具/审批owner。

- reasoning导出逐行appendLine；现有ExportMarkdownTest增加一个多行用例。GFM alignment经现有AST source span提取进入单一columnAlignments，header/body及多行TextAlign共同消费；JvmMdTree.kt:175与NativeMdTree.kt:153明确当前AST不提供alignment，故有真实补齐依据。没有修改解析器或Rust wire。
- HTML ol start保留；HTML消息调用处补段距，MarkdownNew显式0语义保留。代码工具栏48dp、glyph20、AmberMono；文件名改为可移植时间格式，不把原冒号推断成已复现SAF故障。
- 首轮定点：Export 3、Table 6、Composer 4、ContextMeter 6，共19通过。既有Markdown renderer/edge回归42通过（含新增HTML起始编号用例），无快照更新。最终表格6再次通过。
- 实际截图 `device/phase3-chat-after.png` 确认status列居中、数量列右对齐，代码操作可见，idle预览收紧；`phase3-chat-keyboard-before.png` 验证原IME位移正确，未叠加额外键盘padding。
- 独立review发现active预览在320dp同时显示cancel和历史导航会挤掉next。已改Peek及SheetHeader为公共标题行+导航行；没有历史时cancel也保留，idle onOpen不丢，非Web文本/终端peek仍显示。最终ComposerInteraction 5通过，新用例直接渲染288dp内容宽的真实SandboxPeekBar并验证三个按钮边界、触摸回调、首步取消及不误触onOpen。
- 三组独立review最终无阻断。编译、组内回归与 `git diff --check` 通过。生成完整本地阶段APK `/private/tmp/amber-ui-phase3.apk`。真实provider/付费生成未触发，不能把夹具渲染当成provider验收。

## Phase 4

实施中：看板/阅读、小说、议会/Live、扩展/小应用、备份/日志等剩余路由。已有共享主题覆盖不等于逐页视觉验收，继续按ROUTES和参考图补查。
