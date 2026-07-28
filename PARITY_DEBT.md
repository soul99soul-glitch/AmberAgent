# 等价偏差登记（Parity Debt）

> 鸿蒙实现与 Android 生产语义的已知偏离。每条必须有：Android 基准锚点 / 偏差内容 / 理由 / 消除条件。
> 原则：等价优先级 数据与协议 ＞ 核心闭环 ＞ 错误恢复 ＞ 交互语义 ＞ 视觉。前两档不允许无声偏差。

| ID | 日期 | 领域 | Android 基准 | 鸿蒙现状 | 偏差 | 理由 | 消除条件 | 状态 |
|---|---|---|---|---|---|---|---|---|
| PD-001 | 2026-07-28 | deepread agent | 空 prefetch → hard fail，提示"没有抓到足够的来源"（Android DeepReadSourcePrefetcher 调用方） | 空/异常 prefetch → 用标题构造 fallback source，走 fallbackPlan 产出降级文章 | 错误恢复语义变化（用户可见） | 搜索源全挂时体验：降级文 > 直接失败 | G1 人工确认；若否决则改回 hard fail | open |
| PD-002 | 2026-07-28 | deepread prefetch | SOURCE_COLLECTION_TIMEOUT_MS = 36s（DeepReadSourcePrefetcher.kt:688） | PREFETCH_WALL_BUDGET_MS = 15s | 预算常量减小，可能收集到更少来源 | 移动端等待容忍度；与 HTTP 超时匹配 | 真机 spike 后按实际来源完整度回调 | open |
| PD-003 | 2026-07-28 | 构建目标 | Android targetSdk 37 | harmony targetSdkVersion 5.0.0(12)（API 12 兼容档） | 目标 API 档位远低于 compileSdk 26 | 支持 API 24 模拟器安装（commit 1db3ba8c2 的延续） | 真机设备到位后按真机 API 重新定档 | open |
| PD-004 | 2026-07-28 | chat 时间戳线格式 | UIMessage.createdAt/finishedAt 为 LocalDateTime（无时区，如 `2026-07-28T10:00:00`） | chat-domain 统一 ISO 8601 UTC 带 `Z`（Instant 语义） | persisted JSON 中时间字符串格式不同；语义无歧义且更显式 | ArkTS 侧无时区 LocalDateTime 处理成本高；UTC+Z 更不易错 | 若需与 Android 导出包字节级对齐，做导入归一化 | open |
| PD-005 | 2026-07-28 | chat assistant regex | AssistantRegexProcessor：Java Pattern 编译；非法规则跳过；Rust native 路由为性能优化 | chat-domain replaceRegexes 用 JS RegExp 解释同一批规则；无 native 路由 | 用户自定义规则中 Java-only 语法（\Q\E、possessive quantifier、部分 \p{}）在 Harmony 编译失败被跳过，Android 能生效 → 替换结果不同 | JS RegExp 是 ArkTS 唯一正则引擎；语法差异无法对齐 | 文档化支持的 regex 子集；或引入原生 regex 引擎 spike（G-D 阶段评估） | open |
| PD-006 | 2026-07-28 | chat 生成错误恢复 | GenerationHandler：runProviderCallWithRetry + vision fallback + 工具执行/审批 + 生成守卫 | ~~抛错直接传播~~ **retry 已落地(D-050:GenerationRetry.kt 全文 + 循环集成 chat_turn/regenerate + 状态文案)**；仍缺：vision fallback（forceImageToText 依赖 image→text transformer 链）、工具执行/审批、生成守卫 | 错误恢复语义部分缺失（第三档等价优先级） | retry 纯逻辑可移植（已做）；vision fallback 依赖 transformer 链；审批/工具循环依赖工具定义落地 | vision fallback + 工具执行落地时关闭 | open |
| PD-007 | 2026-07-28 | chat 多模态图片 | FileEncoder.kt:68-103：file:// 统一 compressAndEncode 压缩后 base64；选图复制到应用私有目录存 file:// 引用 | EntryImageEncoder 原样编码不压缩；选图即刻物化 data: URL 直接存消息体 | ① 大图消息体/请求体显著大于 Android；② 消息体携带完整图片数据而非文件引用（会话 JSON 体积增大，RDB 无字段限制但同步/导出体积不同） | source-only 阶段裁剪：压缩依赖设备端 PixelMap 管线；data: URL 避免 picker 临时授权过期 | M3 设备端阶段：PixelMap 压缩 spike + file:// 私有目录存储评估 | open |
| PD-008 | 2026-07-28 | chat provider 密钥 | KeyRoulette：一 provider 多 apiKey 轮换选择（OpenAI/Claude/Google 共用） | 鸿蒙 ProviderSetting 单 apiKey 直用（D-044 Claude headers 直取 setting.apiKey） | 多 key 轮换能力缺失（功能缺失，非行为偏差） | source-only 阶段裁剪：KV 模型仅存单 key；轮换 UI/存储未设计 | provider 编辑面落地时评估（P1） | open |
| PD-009 | 2026-07-28 | chat 多模态图片 | FileEncoder.kt:94-97：http(s) URL 原样当 base64 返回（mime 兜底 image/png） | encodeImageDetailed 命中 http(s) 直接抛错（D-046） | Android 对 Claude inline base64 source / Google inlineData 会产出垃圾数据（Android 已知缺陷）；鸿蒙不照搬，显式失败 | 宪章禁止照搬 Android 已知问题；聊天选图链路物化 data: URL，不产出 http(s) part | 若需支持远程图片 URL，落地下载→编码管线（P1） | open |

## 待 Android 侧核实的疑似偏差

- **2026-07-28 / chat 流式合并（内部不一致，非鸿蒙偏差）**：Android 两条合并路径对 reasoning `finishedAt` 处理不一致 —— `MessageStreamAccumulator.appendReasoning`（主路径）在 delta reasoning 非空时**采用 delta 的 finishedAt**（可为 null，即覆盖已有值）；`Message.kt appendChunk`（不可变变体）在相同情形下**强制置 null**。鸿蒙 chat-domain 两条路径均按 Android 各自语义忠实实现（stream_accumulator.ts / 测试锁定）。需人工确认哪条为权威语义；若统一，改其中一条并更新测试。

- **2026-07-28 / RegexOutputTransformer 的 visual 标志（已核实,忠实保留,非鸿蒙偏差）**：Android `RegexOutputTransformer.transformMessage` 在 `visualTransform`/`visualTransformTail` 内对 Text 与 Reasoning 均硬编码 `visual = false`（RegexOutputTransformer.kt:47,53），即 visual 变换命中的是 `visualOnly = false` 的规则；`visualOnly = true` 的规则不经此 transformer（由渲染层另行应用）。集成测试 integration_chat_loop.test.ts 以源码锚点锁定该口径。

## 已消除

（暂无）

## regenerate 窗口化 merge 偏差(D-024,2026-07-28)
- Android: regenerate 经 mergeGeneratedMessagesIntoWindow/streamingTail 合并 + invalidateCompacts(ContextEngine);依附时间线分页架构。
- Harmony: 扁平节点模型等价语义(上下文截断 + 分支追加不覆盖);ContextEngine/compact = P1,无 invalidateCompacts 对应物。
- 影响:功能等价;时间线分页(大会话性能)未移植,若移植需重估 merge 路径。
- regenerate 流式预览:Android 有;~~本实现 MVP spinner 代替(P1 补)~~ **已消除(D-041,2026-07-28):onUpdate 快照末条渲染在目标节点行下,visual transforms 与 send 路径一致。**
