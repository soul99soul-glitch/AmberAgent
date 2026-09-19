# Jev 无障碍接入（2026-09-19）

## 生产链路

`LocalTools.getTools(runId)` → `screen_run_goal` → 既有审批/非幂等工具账本 → `JevScreenGoalRunner` → `JevRuntime(SCREEN_AUTOMATION)` → Jev HTTP → 当前快照校验 → Android 原生节点动作 → 新快照/完成核验。

- 新用途 `SCREEN_AUTOMATION`、新数据范围 `SCREEN_CONTENT` 均不继承网页授权。需要总开关、用途 ACTIVE、屏幕内容和任务文本范围，以及已有 Jev Key。
- 主模型提供包名、有限目标和可输入文本。输入中的 run ID 不可信，因此由宿主工具工厂闭包绑定。
- `screen_run_goal` 固定强制审批、High 风险、不可并行、非幂等写；取消/进程死亡沿既有工具账本恢复边界处理，不自动重放。
- 一次调用默认最多 6 次判断/15 秒，硬上限 8 次/30 秒；同一 run 的无障碍用途最多 24 请求/512 KiB，普通用途仍为 6 请求/256 KiB，共享日预算。
- 快照使用包名、窗口、节点路径、全文标签/属性/边界的 SHA-256。密码文本屏蔽，越屏但部分可见节点可读取；过大/无法完整扫描的页面明确回退。
- 点击/输入/滚动执行前重验快照与 live node；使用原生节点 action，不回退旧坐标点击。输入后回读；未知结果终止。只在新快照与 Jev 完成判题都通过时报告 completed。
- 当前只支持有限导航、滚动和指定文本填写。敏感按钮从候选移除，另有 Jev 只读动作判题。发送、发布、购买、权限和账号动作交还用户/主模型。
- 工具返回 `engine=jev_accessibility`、`jev_decisions`、`actions_dispatched`、逐步回执和可见文本。不能用“目录里是否存在名为 jev 的工具”判断是否使用了 Jev。

## 检查与实机复现

- JVM：`JevScreenGoalRunnerTest` 覆盖目标执行、完成重验、OFF、SHADOW、dry-run、范围、前台包、配置变化、取消、未知结果、无进展、步数和时间边界。
- 策略与预算：`ScreenRunGoalPolicyTest`、`ExecutionPolicyCoveragePinTest`、`JevBudgetTest`。
- 构建同包名验收程序：`./gradlew :app:assembleGraphite :app:assembleGraphiteAndroidTest -PdeviceTestBuildType=graphite -PuiSmokeTest=true`。应用和测试 APK 都须使用真机已有包的签名。
- `JevScreenDeviceTest#nativeSnapshotsRejectStaleAndVerifyLocalActions`：不调用 Jev，对原生测试页面执行 stale 拒绝和真实节点点击。
- `JevScreenDeviceTest#realJevRunsNativeGoalThroughProductionTool` 配合 `-e realJev true`：显式启用新用途和范围，使用保存的真实 Key，完成原生合成页面两次点击及完成核验。需授权此测试的屏幕数据外发。
- `JevScreenDeviceTest#realJevBilibiliSearch` 配合 `-e bilibiliJev true`：B 站搜索页导航与填写“两颗皮蛋”，不提交搜索。需先启用用途和授权 B 站页面数据外发。
- 真机测试直接执行生产工具入口；正常会话仍经过模型工具发现与审批。此测试不等同于完整“三条视频评论总结”端到端验收，也不提供加速百分比。

原始 9 月 17 日计划中的 `screen_*` 排除项描述的是当时的 v1 范围，本次按用户要求新增独立无障碍路径。

## 本轮验证状态

- JVM 目标循环 14 项、预算 3 项、工具策略 2 项及现有 Jev/策略覆盖测试通过，APK 构建通过。
- M610BB 上，纯本地原生测试已验证 stale 拒绝和真实节点点击。
- 同机真实 Jev 已完成一次原生点击，第二步因刷新后的节点身份变化而安全 handback；尚不能声称连续目标验收通过。已把动作前快照校验与节点解析合并在同一个 root 内，并细分身份变化原因，待复验。
- 用户已授权合成页面与 B 站搜索页的 Jev 外发测试及启用。之后更换为 PMA110 要求安装；该新手机当前尚未启用 Amber 无障碍服务，B 站端到端验收尚未完成。
