# Phase 1：鸿蒙运行装配与会话依赖方向

状态：代码、完整测试、独立逻辑复核、构建/签名/安装及可执行 UI 场景通过，进入 Phase 2。系统 Web 冷启动的一次超时及真实 provider/真机未测门继续登记，不代表全部产品验收完成。

## 范围

本阶段仅修改本仓库 Harmony。以恢复执行时保存的工作区快照为边界，保留另轮清理及 Android/Web WIP。证据目录：`/tmp/amber-modularization-cf0292929/`。

## 职责与调用链

- `AppResources` 持有平台基础资源与聊天仓库、KV、compact/grant/activity 的唯一缓存。
- `ProviderModelRuntime` 处理模型选择、任务模型、认证和 provider 创建；本地执行副作用归 `LocalExecutionResources`。
- `AgentSettingsRuntime`、`AgentTaskRuntime`、`ToolCapabilityRuntime`、`CronRuntime`、`SubAgentRuntime`、`DeepReadRuntime`、`CouncilRuntime`、`NovelRuntime` 持有各自生命周期与能力装配。
- `AppContainer` 保留公开兼容入口，并显式连接平台回调。原 101 项公开声明均保留；当前额外公开中立 `ChatProviderRuntime` 类型。
- `ConversationRunService` 保留唯一 session、运行 admission、队列和存储写序；外部依赖通过 storage/execution ports 注入。准备、工具目录和辅助生成保持各自职责。

## 当前证据

- `node --test harmony/tests/composition-direction.test.cjs`：1/1 通过。检查所有新装配 owner 与会话执行模块的递归运行时 import 路径，未发现回读 AppContainer。
- 恢复执行后两个 domain 的 TypeScript 检查通过；实现者再次执行 chat 域 tsc 通过。运行模块 focused 24/24、装配 focused 31/31；压缩 provider 实例回归断言所在文件分别单跑通过。
- 完整当前 Harmony gate 最终 4588/4588 通过，0 fail/cancel/skip，129.690 秒，日志 `phase1-harmony-tests-final.log`。首轮 24 项失败集中在 5 个旧 AST fixture；迁移到实际 owner 后保留原业务断言，相关文件 28/28，通过完整重跑闭环。
- 最终修改文件 lint：di 15 文件无 defects；运行相关 7 文件 0 error、1 条原有 Date warning。
- 独立审查确认 59 个 Service 方法在 getter→注入归一后实现相同，session/写序/取消/审批无新状态 owner；对照旧实现复核模型选择、认证、Cron、本地资源启用门、能力与小说接线。发现的压缩 provider 工厂/实例接线混淆已修复，并增加真实工具装配回归断言。报告 `phase1-independent-review.md`。
- AppContainer 从 1990 行变为 387 行；最大新 owner ProviderModelRuntime 502 行，工具装配 335 行，其余不超过 223 行。Service 仍为约 1100 行，保留内聚运行状态。
- 实际 App native/ArkTS 编译及 PackageHap 已通过；首轮漏导入 UIMessage 已修。最终 assembleApp 仅因现有签名配置密码问题失败（11014003），日志 `phase1-build-final.log`，不将该结果报成 assembleApp 成功。
- 限定差异 `git diff --check` 通过；恢复快照记录的 19 个范围外已修改文件哈希不变。

## 设备与签名门

- 已用官方 coldboot 恢复现有 `Pura 90` 模拟器，原有会话数据保留。旧安装截图仅作基线。
- 当前配置签名密码与既有 p12 不匹配；清理任务保存的既有配置可通过官方解密实现打开同一 p12，证书与现有安装匹配。使用任务临时签名器，不更改配置、不输出明文、不更换身份。
- 本轮 Release、Debug 均完成 native/ArkTS 编译与打包，Debug 已核生成 BuildProfile.DEBUG=true；两者用现有 AGC 身份签名并通过 verify-app，覆盖安装和启动成功，保留已有数据。assembleApp 的原签名配置失败与手工兼容身份签名成功分开记录。
- 新 Release 首页/聊天/设置明暗主题已目测，无可见错位、覆盖、溢出；未发现 JS RuntimeError/Fatal/TypeError/ReferenceError。Debug 前台 task UI 生成、Smart UI 表单与 SVG 完成渲染已确认；主 agent 复看了原生卡片与 Smart UI/SVG 当前截图。独立 UI 详细证据见 `phase1-ui-review.md` 与 `phase1-ui/`。
- Smart UI 首次系统 Web 冷初始化出现一次 THREAD_BLOCK_6S：主线程在 `NetConnClient::GetDefaultHttpProxy` 同步 Binder 等待，未出现 JS 异常。空闲后同一 fixture 重试完成、SVG 渲染且进程存活，未稳定复现；旧安装此前也出现过冻结，现无证据归为本次装配回归。保留首轮 faultlog 与第二次成功截图，最终集成再次复核，不添加推测性兜底或改网络。
- 现有 Debug 回放在 listeners=0 时主动 abort，HEAD 同样如此；它不能证明真实 provider 离页继续生成，不修改此语义来凑验收。后台运行状态由实际 Service 测试核对，设备上真实 provider 后台、真机、API12 尚未验证。

## 完成门

实际接线和行为测试、修改文件 lint、Harmony App 编译/打包、签名与保留数据安装、明暗主题及离页回连 UI、独立 review 和已确认问题修复全部登记后，才将本阶段标为通过。
