# Phase 3：小说存储、运行与工作区

状态：阶段职责、独立代码审查和修复后的已测 UI 验收通过；外部未测门单列。范围仅本仓 Harmony；Android/Web、兄弟 DeepRead 仓库与已有清理 WIP 不动。

## 阶段前证据与边界

- 阶段前源码快照、哈希在 `/tmp/amber-modularization-cf0292929/phase3-before/`：repository 3498 行、creation 2362 行、NovelWorkspacePage 4641 行。
- 存储切口与事务约束已逐方法映射，见任务证据 `phase3-storage-boundaries.md`。保留公开 API、路径、wire/schema、字节摘要、原件保护、项目锁和目录交换协议；真实项目写入与锁只有一个事务 owner。领域模块拿锁内的明确事务能力，不拿 FileStore/根目录/全部私有函数包。
- creation 保留门面契约，普通运行、持久任务驱动与连续性修复各自持有实际状态；不复制 busy 状态，也不改变普通运行 busy 与全后台 driver busy 的既有区别。删除先取消并等待真实 checkpoint/driver，再删除项目。
- 工作区按讨论/运行与 composer、稿件/编辑、资料/提案、写作控制、任务及审校职责划分。页面持有 V1 第一层可观察 session，页面保留路由、共享项目投影、主题和生命周期；生成与持久任务事实仍由域服务持有。
- 现有 10 个工作区行为文件 baseline 76/76，日志 `phase3-workspace-baseline.txt`；不会以源码搬移为由削弱有效断言或恢复旧测试/功能。

## 已复现的局部问题

准备阶段直接执行当前 Page 的实际 `scheduleGhostwritePoll/pollGhostwrite/onPageHide`，复现旧轮询迟到覆盖重新显示后新稿件：hide 不使旧 token 失效，show 读到任务已完成，新的 schedule 提前返回，旧 openProject 结果仍被接收。

失败证据：`phase3-ghostwrite-poll-before.cjs`、`phase3-ghostwrite-poll-before.output.txt`，源码 SHA256 `705506db63bd293661e0b20272628acd1ed7985363f3e27eaaf2fe8256bcd99f`。这是阶段前可达问题，不是 Phase 2 引入。完整 JobSession 实际测试已 red→green：hide 使 epoch 失效；同一轮询链的第二个 `latestGhostwriteReport` await 后也校验相同 epoch，阻止旧报告覆盖新报告。两种失败均有独立实际 owner 红绿证据，不扩成通用轮询框架。

## 实施与验收

- creation 最终门面 1121 行、普通运行 798、持久任务协调 524、修复 116、工具提案 204。projectRuns/repairRuns/driver maps 分别随对应行为迁移；21 份实际领域测试 162/162、DeepRead tsc 通过。独立 reviewer 复核 112 个公开方法、15 个导出契约、65 个迁移执行体和事件 helper；存储稳定后独立 focused 90/90，未留确认问题。
- Workspace 最终 Page 1063 行，6 个 session 为 discussion 849、job 426、audit 156、writing 366、manuscript 362、compendium 453；5 个视图均不超过 550 行。Page 持有唯一项目快照、UI busy 和路由/生命周期，域服务保留运行事实。递归依赖守卫覆盖全部新 session，无直接/间接 AppContainer runtime 回读。
- 工作区最终实际 focused 238/238，独立 reviewer 另执行一次 238/238，覆盖 UI/编辑生命周期、分支、归档、资料、composer、混合 provider 生命周期、want 与依赖守卫。原断言迁至实际 owner，没有复制旧执行体到测试冒充行为验证。12 个 `.ets` lint 0 errors / No defects；最后 SDK 修正后的两文件 lint 及受影响 31/31 另行通过。
- 独立 review 与编译回喂修复了 route 私有 getter/跨 owner 私有桥接、重复 Builder、遗漏的本地 ExportMenuResult 类型，以及恢复原 KeyboardAvoidMode.OFFSET 初值；没有新增状态机或兜底。三个独立报告为 `phase3-{creation,repository,workspace}-independent-review.md`，最终增量与旧冻结 hash 分开记录。

### 存储最终实现 checkpoint

repository 3498→26 行，保留公开契约/工厂与原导出路径；内部 12 个生产模块中 codec 633、唯一 transaction 610、只读 tree 356、归档约 485、ghostwrite 456、polish 372、项目 331、提案 305、历史 255、契约 189、共用纯 projection 38 行。factory 只创建一次 storage，再组合各领域；每领域只拿静态收窄的锁内 session 能力，真实写入/交换和项目队列均留唯一 owner。

强存储 12 suites **114/114**、DeepRead tsc 通过。新增覆盖两类 job/proposal 的目录交换失败后完整 tuple 与 receipt 重放，以及两个 repository 实例对同一旧 CAS 使用不同 command 只有一次成功。日志 `amber-phase3-storage-final-tests.log`、`amber-phase3-storage-final-tsc.log` 在 `/tmp/`。新旧 ESM 下游集成已执行；收尾消除了错误 runtime type import，剩余旧 UI fixture 由工作区 owner 迁移，不削弱断言。

14 个存储源/测试文件定向 lint exit 0、0 errors、4027 warnings，日志 `/tmp/amber-phase3-storage-lint.log`；多为领域 TS 导入/解析值的 unsafe 系列，未将它写成无 defects。creation 五文件 lint 同样 0 errors、2113 warnings；严格域类型检查与 SDK 编译另列，不能彼此替代。

独立存储 focused 64/64。随后自核发现 inventory 失败分支再次取锁的抽取回归，实际 Proxy 回归先 red，再改成 owner 只读 manifest 能力，保留锁失败时单项目错误记录的原合同；实现者和独立 reviewer 各完成 19/19、最终 tsc 通过，增量 lint 0 errors /1214 warnings。只有 transaction/archive 两份源变化，旧 64 项与最终增量证据没有混写。

## 最终整合门

完整 Harmony 首轮 4651 项中 4650 pass、0 fail、1 cancelled：与 SDK 并行时原有 RunService 首个冷载测试超过 5 秒。该文件单独复跑原时限 18/18 通过，没有延长 timeout 或改断言。最终完整 gate 在 SDK 结束后单独重跑，**4651/4651、0 fail/cancel/skip、77.238 秒**，日志 `phase3-harmony-tests-final.log`；先前超时用例在同门内 917 毫秒通过。首轮 SDK 编译明确发现并修复上述两个 Workspace 类型/枚举问题。

最终 `phase3-build-final.log` 中 CompileArkTS（71.573 秒）和 PackageHap 通过；assembleApp 仍仅停在已有 SignHap 11014003。固定 unsigned SHA256 `410e4d0c3b79c7b6e98f6e92fe10a530387965256d5c3b5739f9a4de81db4426`；75 份相关源码 hash 在最终构建前后全部一致，产物与记录在 `phase3-ui-final/`。兼容既有 AGC 签名/保数据安装/UI 正在执行。

设备旧包 UI 基线在 `phase3-ui-before/README.md`：浅/暗的创作/正文/设定、方式弹层、模型职责、返回，未写入用户内容。设备验收区分下面首包失败与修复包通过；provider、物理设备、API12 与宽屏不能外推通过。

### 实际 UI 发现与修复

首个完整 Phase 3 包虽签名、安装和首页启动成功，点击既有小说继续卡会崩溃，因此该包 UI gate 为 FAIL。两次原始 jscrash 与说明在 `phase3-ui-final/continue-crash.md`。原因是新 DiscussionView 的 `@Prop project` 展示副本丢失 NovelMessage 的 non-enumerable `content` getter，footer 显式从副本解析消息后把它传给收录判定；阶段前页面使用 canonical snapshot。

最小修复仅在 footer 恢复 owner 的 canonical lookup，保留显式 project 的 UI 观察依赖；不改领域 `canCloneCollectedMessage`、不补正文兜底、不全量更换 Prop/Link。用真实消息 factory→模拟 Prop 枚举复制→完整 DiscussionSession→实际 View footer 取得相同 trim 错误的 red，再 green；实际点击源码 callback 验证再次收录接收当前 canonical 对象及同 ID 最新对象。受影响 40/40、View lint 无 defects，独立 reviewer 定点 1/1 并核同族消费者。证据 `phase3-footer-canonical-{red,green,action-green,lint}.txt`；先前完整 4651 门与此最终增量分开记录。

修复后 `phase3-build-footer-fix.log` CompileArkTS 28.597 秒、PackageHap 通过，仅原 SignHap 11014003；固定 unsigned SHA256 `9377929a7ce46f97b0ec035a06cac3e87e10a2e558fa4f283dced106a734edd3`，产物与 75 个最终源码 hash 在 `phase3-ui-footer-fix/`。既有 AGC 签名、验签、保数据覆盖安装成功。独立 UI reviewer 给 GO：同一既有项目继续卡→完整创作→返回→再次进入连续两次成功，PID9083保持；浅/暗创作、正文目录第1章55字、设定资料空态、方式弹层、模型职责与返回均正常，未再出现 trim 崩溃。

首次 `dark-manuscript.jpeg` 是过渡时序截图，实际仍在创作页，已排除正文通过证据；稳定点击后 `dark-manuscript-settled.jpeg` 才作为正文目录证据。root 另目测暗色模型职责和创作区域，布局无确认新增问题。旧包深色系统方式 ActionSheet 为浅底，作为既有外观保留。系统锁屏/SceneBoard 曾临时超时，正常唤醒恢复，无 wipe/重启或业务兜底。完整设备报告为 `phase3-ui-review.md`，最终索引在 `phase3-ui-footer-fix/README.md`；真实生成/审批/后台系统调度、软键盘、宽屏、API12/真机仍未验证。
