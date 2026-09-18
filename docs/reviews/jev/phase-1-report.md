# Phase 1 — 工具发现与记忆召回（Jev Android）

代码状态：完成
离线检查：passed（JVM 定点测试全绿）
真实 API：blocked（无 API Key；连接测试已实现，待用户配置后验证）
真实业务和真机：未执行（blocked 同上）
用途模式：TOOL_DISCOVERY=off、MEMORY_RECALL=off（默认全部 off，需用户显式开启）
基线 commit 与工作区状态：main 工作区（叠加既有未提交 WIP：记忆系统/气泡/Live 多线，未混入提交）

## 步骤完成情况

- 基建：`core/jev` 新模块（Client/Coordinator/Budget/Metrics/Models），OkHttp enqueue + 取消传播，线格式按 docs.typesafe.ai 2026-09-17 版本核对（questions 为 map 键控；noul 无 confidence；choice 带 probabilities/confidence；422/429/529 语义）。
- 设置闭环：`Settings.jev`（版本化默认 off）→ AgentPrefs/PreferencesKeys/SettingsAggregator 全链持久化；API Key 只存 SecretStore（`jev/default/apiKey`），Settings 仅掩码；连接测试（合成数据、固定 jev-latest、成功解除 auth 暂停）；设置页 SettingJevPage（主开关/Key/连接测试/每用途 off-shadow-active/数据范围/状态开销）+ 6 语言文案 + 设置列表入口 + 路由。
- 工具发现：`ToolSearchIndex.searchPayloadWithSemantic`——仅 lazy 目录（>40）且非精确名查询尝试；候选=词面 Top20∪类别∪目录补充（上限 64）；active 经既有 `expanded_tools` 通路更新暴露；shadow 零暴露仅指标；trace 记录 candidates/catalog/coverage。
- 记忆召回：`MemoryRecallStore` 注入可选 `MemorySemanticReranker`；候选独立于词面 >0 过滤（词面池24+新近池16）；active 重排+置顶强保留；shadow/失败走原路径且 touch 行为不变；轮次锚=最后一条用户消息内容哈希，工具循环内缓存复用。
- 预算/并发：单请求 1200ms deadline、每 run 6 次/App 日 1000 次（SharedPreferences 持久化）、全局 3 在途+每 run 1、429 尊重 Retry-After、401 暂停至连接测试、3 连暂时失败 60s 冷却、内存缓存 128 项 TTL 5min。

## 变更文件与理由

新增：`core/jev/**`（模块）、`app/.../core/jev/{JevRuntime,JevMemoryReranker,JevToolSemanticSearch,AndroidJevUsageStore}.kt`、`app/.../di/JevModule.kt`、`SettingJevPage.kt`、三组测试。
修改：`settings.gradle.kts`（include）、Settings/PreferencesKeys/AgentPrefs/SettingsAggregator（jev 字段）、`ToolSearch.kt`（语义入口+双 searchPayload）、`MemoryRecallStore.kt`（reranker+scoreAll 重构）、`MemoryRepository.kt`（两钩子 open 供测试）、ChatService/ChatModule/RepositoryModule/WorkspaceModule/AmberAgentApp/RouteActivity/SettingPage（接线）、strings×6。

## 已执行检查

- `:core:jev:testDebugUnitTest`：18 通过（编解码校验/缺题/未知候选/值域/Retry-After/预算/缓存/401 暂停/冷却/超时）。
- `:feature:tools:api:testDebugUnitTest`：ToolSearchSemanticTest 5 通过 + 既有 PrivateNetworkTargetTest 2 通过；既有 ToolSearchTest（非 suspend 原入口）不回归。
- `:app:testDebugUnitTest` 定点：MemoryRecallSemanticTest 4、ChatGenerationRoundEngineTest 4、MemoryRecallStoreTest、PreparedContextEditorTest 全绿。
- `:app:compileDebugKotlin` 成功。

## 对照结果

无 Key，冻结评估集与真实 Recall@5 对照 blocked。离线行为验证：语义候选覆盖零词面命中记忆（active 测试证实）、shadow 业务零变更（prompt 与 touch 均不变）、精确名查询零额外网络、小目录（≤40）零调用。

## 阻塞、回退与下一步

- 真实 API 验证 blocked：需用户在设置页配置 Key 并运行连接测试。
- 回退：关闭对应用途开关即回原路径；旧搜索与 builder 全部保留。
- 待办：真实 Key 后跑冻结集对照，达到门槛才允许 active。
