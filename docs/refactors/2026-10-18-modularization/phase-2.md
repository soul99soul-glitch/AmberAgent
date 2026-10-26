# Phase 2：聊天页与首页

状态：阶段职责、独立代码审查及已测 UI 验收通过；未验证门单列保留。Harmony-only，保护已有清理 WIP，未触及 Android/Web。

阶段前源码：ChatPage 5471 行，HomeListPane 1593 行。快照与哈希保存在 `/tmp/amber-modularization-cf0292929/phase2-before/`。只读切口报告为同目录根下 `phase2-boundaries.md`，已核官方离线 `arkts-observed-and-objectlink` 指南。

## 已定边界

- ChatPage 持有具体职责 session，资源不随主题 ForEach 子树销毁。session 的 UI 字段在 V1 可观察第一层；异步从页面观察实例启动，构造函数不捕获原始 this 订阅。
- 首先完整迁移 Smart UI 的运行表、值、revision、加载 epoch、controller/accessor、continuation 校验及清理。先用真实 Debug 原生 task UI 验证异步值更新和主题保稿，再迁移时间线等较大职责。
- 时间线保留唯一 message.id 身份和 ForEach，按当前状态重读 seed；分页/定位/跟底/IME/产物几何一起迁移。生成和队列继续由 ConversationRunService 唯一持有。
- Home 继续卡聚合、列表/计数/运行订阅/删除撤销、行视图和导出按完整职责迁移。跨实例 cache 与 pendingDeleteId 仍有唯一共享 owner，主题子组件销毁不会提交删除。

## 实施与验证

### 首个 Smart UI 职责

- ChatSmartUiSession 418 行，ChatPage 暂为 5139 行；唯一值/运行映射/异步 epoch/continuation 与草稿生命周期已迁移，不是最终聊天页完成结果。
- 两组受影响行为 46/46、overlay lifecycle 19/19 通过；独立小门 review 确认代理捕获、源身份、草稿与发送连接、主题/生命周期无已确认回归。报告 `phase2-smartui-independent-review.md`。
- 两个源文件 lint 0 error，3 条 ChatPage 既有性能 warning。中断前构建未完成且进程消失，核实无残留后重跑 `phase2-smartui-build-resume2.log`：native/ArkTS/PackageHap 通过，原签名配置仍仅报 11014003。
- 固定 unsigned、源码哈希及签名/安装证据保存在 `phase2-smartui-ui/`，使用现有 AGC 身份签名/verify 后保数据安装。原 Pura 90 已通过既有实例官方启动、普通唤醒和上滑恢复，新包启动成功。
- 当前真实原生 task-ui 已确认流式→完整表单，选项轻量→深入、高亮与预算 50→80、输入后按钮启用均更新；点击按钮只追加包含当前选择/输入的可编辑 composer 草稿，未发送。主题重挂载后保值/保稿路径仍由 UI reviewer 验证。
- 首小独立 UI 报告 `phase2-smartui-ui-review.md` 已记录上述字段/数组/草稿通过；Pura 窄屏的正常应用路由不能保留原 ChatPage 到全局外观页，不能将路由销毁后的新页面恢复当作同实例主题保留。控制中心没有系统深色控件；临时解除旋转锁也未形成 guest 横屏，已还原。设备几何不满足两栏条件，宽屏/同实例主题事件门仍未测；没有注入状态或恢复死入口来凑通过。原浅色模式和首页已恢复确认。
- 已继续纯附件 IO 迁移，89 行无状态模块；4/4 原 file picker 测试通过，函数体归一对照与快照一致。聊天剩余职责正在迁移，尚未标完整。

### Home 完整职责

- HomeListPane 从 1593 行变为 877 行；6 个新模块分别持有继续卡、会话集合/计数/删除撤销、行视图、导出与纯展示映射。跨实例 cache 与 pendingDeleteId 仍唯一共享，外层 List/scroller 与宿主导航保留。877 行略高于初定 600–850 范围，保留宿主 List 与导航更易定位，不再为凑行数拆碎。
- 恢复复用了完整暂存成果；落盘前修复提取后遗漏的 util import，未改业务语义。真实源码 focused 8/8，7 个修改 `.ets` lint 无 defects；本次差异检查通过。
- 完整文件/哈希及证据在 `phase2-home-resume2.md`；独立 review 已通过，21 个迁移方法经命名/空白归一与阶段前等价，focused 8/8；检查共享 cache、删除锁顺序、firstShow、主题边界、继续卡实时字段及 swipe/间距保持。报告 `phase2-home-independent-review.md`。整合编译、实际 UI 待完成。
- Home+附件 IO 固定 Debug 编译和 PackageHap 通过（`phase2-home-build.log`，22.028 秒）；原签名配置仍失败，兼容既有 AGC 身份签名、verify、保数据安装与启动成功，固定产物/哈希在 `phase2-home-ui/`。
- 新首页已完成浅/暗主题、长列表滚动、打开旧会话返回、继续卡打开匹配小说工作区返回、搜索空态/匹配/取消恢复。未发现新增可见回归；当前进程 365 条运行日志未见 RuntimeError/THREAD_BLOCK/Fatal 等标记。未删除、重命名、导出或发送；原浅色首页、竖屏与旋转锁已恢复确认。报告 `phase2-home-ui-review.md`，截图索引 `phase2-home-ui/README.md`。

### 聊天完整职责与最终接线

- ChatPage 从 5471 行变为 1200 行，保留路由、生命周期和区域装配。9 个页面持有的具体 owner 为 ConversationPresentation、Timeline、Composer、SmartUi、ModelContext、Artifact、MessageActions、RunDetails、ConversationDrawer；另有无状态 AttachmentIO 与 CompactionSupport。视图在 `components/chat/`，状态与行为在 `platform_impl/chat/`，没有整体 page/全部 owner 的 host 接口。
- 生成、队列、审批与取消仍归 ConversationRunService；Presentation 仅保存实际 canonical/visual 引用和订阅投影。时间线保留唯一 message.id ForEach、当前 seed 重读、分页/跟底/IME；所有输入入口使用 Composer 草稿 setter。12 个独立 sheet 宿主与唯一 WebMountHost 保持。
- `composition-direction.test.cjs` 扩展到新 Chat/Home session，递归核对 runtime import/reexport。发现的 DocumentSupport→ManagedFileStore→FavoriteStore 及 model/run 间接回读装配根均通过 page 注入现有窄能力解决；没有放宽守卫或改旧 helper 业务。最终守卫通过。
- 对本轮差异完成一次有限可维护性检查，去掉页面在 owner.hide/dispose 后重复执行的旧清理，保留 owner 的明确生命周期；未添加通用 session 框架、额外状态或防御分支。
- 完整 Harmony Node gate **4634/4634**，0 fail/cancel/skip，131.089 秒，日志 `phase2-harmony-tests.log`。最终受影响 focused 先后 132/132、生命周期清理后 87/87、仅接口声明修正后 92/92；不拿测试数量替代 SDK/UI 验收。
- 独立 reviewer 核对最终 Page、9 个 owner、TimelineView/ComposerPane、发送草稿与 accepted 清理、Smart UI 身份、生命周期、IME/follow/分页及 sheet 接线，未确认新增可达缺陷。独立执行最终 14 文件 132/132，另以真实 Presentation 验证 raw 引用、投影及退订先于丢弃。报告 `phase2-independent-review.md`。
- 修改 `.ets` 定向 lint 0 error；Page 保留 1 条既有动画性能 warning，新增 owner 无 defects。SDK 回喂发现 3 个 callback interface 的 method signature 不能为 ArkTS 字面量提供上下文类型，统一改为现有惯例的 function-property 声明，52 个回调契约与运行体不变；不用动态断言规避检查。
- 最终 `phase2-chat-build-ports.log` 中 CompileArkTS 与 PackageHap 通过；assembleApp 仍停在既有签名配置的 11014003。固定 Debug unsigned SHA256 为 `f318561ad747f2d172244657ee97e4bc2226166d7fe11bbe219a78d565f19f31`，产物与 26 个生产源码哈希在 `phase2-chat-ui/artifact.json`。

最终 Chat 包使用既有 AGC 身份签名和 verify 通过，保数据覆盖安装与启动成功。独立 UI reviewer 已给阶段 GO：浅色旧会话、产物架/附件栏/模型选择/运行详情弹层及返回正常；定时截图确认 native task-ui 正在回复→完整表单与跟底，必填输入使按钮启用，点击仅向 composer 追加可编辑草稿、没有发送；暗色旧会话、产物架与 native 流式→完整表单无确认新增回归。root 另目测模型菜单、附件栏、原生输入表单及暗色完整表单，未见新增错位。

最终 UI 报告为任务证据 `phase2-chat-ui-review.md`，截图索引 `phase2-chat-ui/README.md`；验收末 26 个源码哈希仍匹配，PID 4250 的 352 条 app 日志未见 RuntimeError/THREAD_BLOCK/Fatal，已恢复浅色首页，旧会话和小说继续卡保留。软键盘没有稳定弹出；长会话大规模分页/分支、宽屏、同实例主题事件、真实 provider 后台/审批队列、API12 实机与物理设备未验证；历史图片无法显示尚未归因，不按本轮回归处理。这些缺口不会改写为通过。Phase 3 可在保留上述边界下继续。
