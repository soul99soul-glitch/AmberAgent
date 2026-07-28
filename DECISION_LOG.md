# 决策日志

> 分级：L0 自行决定不记录；L1 自行决定并记录于此；L2 按推荐默认继续，记录于此并挂 PENDING_DECISIONS；L3 阻断等人工。
> 每条含：背景 / 决定 / 理由 / 影响 / 可逆性。

## D-001（L1，2026-07-28）HTTP 栈：RCP → @ohos.net.http

- **背景**：entry 的 HttpClient 实现原用 `@kit.RemoteCommunicationKit`（RCP）。
- **决定**：改用 `@ohos.net.http`（系统 NetStack）。
- **理由**：RCP 自带 DNS 解析器，部分设备默认 DNS 为回环地址，导致 `Couldn't resolve host name`；NetStack 走系统 DNS 配置，稳定。
- **影响**：仅 entry adapter 层，deepread 领域层 `HttpClient` Port 不变；与 Android OkHttp 的语义对应关系不变（都是"系统级 HTTP 客户端"）。
- **可逆性**：可逆，adapter 内聚。后续若需 RCP 高级特性（如更细粒度流控）可再评估。
- **关联**：plan §5.2 中 "OkHttp → `@ohos.net.http` `requestInStream()` 或 RCP" 决策落地为前者。

## D-002（L1，2026-07-28）SSE 解码器注入，不在领域层用全局 TextDecoder

- **背景**：ArkTS 应用层无全局 `TextDecoder`（Node 全局）；`SseAssembler` 属纯逻辑层，禁止依赖 `@kit`。
- **决定**：`DecodeChunk` 函数注入。Node 测试用 globalThis 探测 + ASCII 兜底；entry 注入 `util.TextDecoder`。
- **理由**：保持 deepread HAR 零 SDK 依赖、可用 node+tsx 单测；避免 ArkTS 编译期扫描到不存在的全局字面量。
- **影响**：`SseAssembler`/`createSseStreamHandler` 构造函数签名扩展（带默认值，向后兼容）。
- **可逆性**：可逆。

## D-003（L2，2026-07-28）prefetch 失败：hard-fail → 降级 fallback source

- **背景**：Android `createRunContext` 对应路径中，空 prefetch 直接失败并提示"没有抓到足够的来源"。
- **决定**：鸿蒙版 prefetch 为空或异常时，用 topicTitle 构造最小 fallback source，走 fallbackPlan 产出降级文章。
- **理由**：搜索源全挂是常见真实场景（无 key/网络限制），hard-fail 体验差；降级文章至少给出结构化输出。
- **影响**：**用户可见行为变化**，偏离 Android 生产语义 → 记 PARITY_DEBT PD-001，挂 PENDING_DECISIONS 等批量确认。
- **可逆性**：可逆（改回 early return 即可）。
- **验证**：`run_manager.test.ts` 已更新断言（ok=true + fallback source）。

## D-004（L2，2026-07-28）PREFETCH_WALL_BUDGET_MS 36s → 15s

- **背景**：Android `SOURCE_COLLECTION_TIMEOUT_MS = 36s`（DeepReadSourcePrefetcher.kt:688）。
- **决定**：鸿蒙版降为 15s。
- **理由**：移动场景下 36s 无反馈等待过长；15s 与 HTTP 客户端 connect/read 超时（15s/20s）匹配。
- **影响**：偏离 Android 常量 → PARITY_DEBT PD-002。可能影响来源收集完整度（更少时间 = 可能更少来源）。
- **可逆性**：可逆，单常量。

## D-005（L1，2026-07-28）debug 签名配置入库

- **背景**：DevEco 自动签名在 `harmony/build-profile.json5` 写入 signingConfigs，含本地绝对路径与加密后的 keyPassword/storePassword。
- **决定**：提交入库。
- **理由**：密码为 DevEco 加密形态（非明文），密钥文件本体（.p12/.cer/.p7b）留在 `~/.ohos/` 不入库；preview 分支单机开发，入库保证 `build-harmony-app.mjs` 直接产出已签名 HAP。另有 `scripts/sign-hap.sh` 离线签名作为无 AGC 环境的备选路径。
- **影响**：其他机器 clone 后需替换为自己的签名配置或清空 signingConfigs；发布版（release）签名必须另行管理，**禁止复用此 debug 材料**。
- **可逆性**：可逆（清空 signingConfigs 数组）。

## D-006（L1，2026-07-28）治理文件体系：沿用仓库原生 + 新增三份轻量索引

- **背景**：上游提示词要求 9 份治理文件；仓库已有 capability-matrix/gate0-report/harmony-inventory 原生体系。
- **决定**：不新建 9 份。保留仓库原生文件为主干，新增 `MIGRATION_STATE.yaml`（进度快照）、`DECISION_LOG.md`（本文件）、`PARITY_DEBT.md`（等价偏差）、`PENDING_DECISIONS.md`（待确认队列）四份。
- **理由**：避免双轨维护成本；审计矩阵（STREAMING/DATA_SCHEMA）按计划书要求以 CSV 形式补入 `docs/harmony-inventory/`。
- **可逆性**：可逆。

## D-007（L1，2026-07-28）ObjectBox 预研关闭：无实际使用

- **背景**：迁移输入提到"§4.1 ObjectBox 替代"预研。
- **决定**：关闭该预研项，不做 ObjectBox 替代方案设计。
- **理由**：`settings.gradle.kts` 仅有 plugin resolutionStrategy 的坐标映射；app/core/feature/ai 源码中**零引用**（无 entity、无依赖、无 import）。Android 存储基准实为 Room（core:agent-store-room）+ DataStore（core:settings）。
- **影响**：DATA_SCHEMA_MATRIX 以 Room/DataStore 为唯一审计对象。
- **可逆性**：若后续发现隐藏使用（如动态加载），重开。

## D-008（L1，2026-07-28）Chat 核心领域层独立 HAR：`harmony/chat`（@amber/chat-domain）

- **背景**：Chat MVP 主线需要完整版 UIMessage/MessageNode/Conversation；deepread HAR 内的 `agent/message.ts` 是 Deep Read 功能子集（仅 text/image/reasoning/tool，无 metadata/annotations/usage）。
- **决定**：新建 `harmony/chat/` HAR 承载 Chat 核心领域层。deepread 子集暂不动，待 Chat 层稳定后评估统一（登记为技术债候选，不阻塞）。
- **理由**：职责分层（core vs feature）；避免 deepread 子集被动膨胀；tsx 单测 + hvigor 编译双验证模式已验证有效。
- **关键实现决策**：
  - JSON 鉴别器字段名用 `type`（kotlinx 默认 classDiscriminator，`ai/util/Json.kt` 未覆盖）—— persisted blob 与 Android 线格式 1:1。
  - sealed class → discriminated union interface + 工厂函数（ArkTS 友好，同 deepread 模式）。
  - 时间戳统一 ISO 8601 UTC 带 `Z`（Android UIMessage.createdAt 为 LocalDateTime 无时区）→ 偏差记 PD-004。
  - uuid/时间通过 globalThis 探测 + 注入模式（D-002 同款），领域层不依赖 `@kit`。
- **可逆性**：可逆（模块内聚，未被 entry 依赖前可整体调整）。

## D-009（L0，2026-07-28）limitContext 级联语义以 Android 算法为准

- 测试初稿假设"已执行 tool 起点只前扩一级"，实际 Android 算法会**级联**（已执行结果→tool call→user 消息，直至 0 号位）。实现与测试均以 Android 生产语义为准修正。无代码偏差。
