# DECISION_PACKET 001 — G1 批量决策包

> 日期：2026-07-28
> 依据：Phase 0 source-only 审计收口（STREAMING_PROTOCOL_MATRIX / STREAMING_SEMANTICS_MATRIX / DATA_SCHEMA_MATRIX / CAPABILITY_MATRIX 已入库 `docs/harmony-inventory/`）
> 状态：**待人工确认**。除标注"已执行"的 L1/L2 项外，其余为建议。

## 一、审计结论摘要

1. **流式协议**：7 类 provider 协议 + 1 条非流式路径全部归档（8 行）。解析层（parseMessage/parseResponseDelta/Reconciler/Accumulator）全部是无 Android 依赖的纯 Kotlin，可原样复用；平台相关点集中在 OkHttp EventSource（已决策 @ohos.net.http，D-001）、Android Keystore token 存储（→HUKS）、Vertex RSA 签名（后置）。
2. **SSE 边界**：`ai/util/SSE.kt` 在 Android 侧**零调用**（死代码）；鸿蒙以 Codex 手写行解析器为移植蓝本。两个已识别 gap：SseAssembler 需扩展 `event:` 字段（Claude/Responses 依赖）、长连接读超时处理。
3. **数据模式**：41 张 Room 表（AppDatabase 37 + agent_runtime 4）+ 52 个 DataStore 键 + 6 个 SharedPreferences 文件全部归档，全部有 RDB/Preferences 归属。
4. **ObjectBox**：确认零使用（D-007，预研关闭）。
5. **sqlite-vector**：plan §5.2 判为"硬缺口"，实际审计发现 **toml 声明但零 implementation、源码零引用**（V01）。memory/embedding 检索的降级压力比计划预估小。

## 二、待确认决策（推荐方案已按 L2 先行执行）

| # | 事项 | 推荐 | 详情 |
|---|---|---|---|
| 1 | prefetch 失败降级替代 hard-fail（PD-001） | 维持降级 | PARITY_DEBT.md PD-001；D-003 |
| 2 | prefetch 预算 36s→15s（PD-002） | 维持 15s | PARITY_DEBT.md PD-002；D-004 |
| 3 | 治理文件 4 份轻量制 | 维持 | D-006 |
| 4 | ObjectBox 预研关闭 | 关闭 | D-007 |

## 三、待确认方向（未执行，等 G1 拍板）

| # | 事项 | 推荐 | 备选 | 影响 |
|---|---|---|---|---|
| 5 | 消息搜索 FTS：jieba+libsimple 在鸿蒙不可用（T38/V02） | **降级**：MVP 用 RDB LIKE 前缀搜索，后续自建 ArkTS 倒排索引 | Rust jieba NAPI（重，绑死 native 工具链） | 影响 C-017；不阻塞 Chat MVP |
| 6 | MCP 集成：ArkTS 无 SDK（C-029） | **Phase 1 早期 spike**：按 MCP 协议规范自研最小 client（stdio 无→HTTP/SSE transport 优先） | 首版不做 MCP，UI 隐藏入口 | 产品卖点之一；自研成本待 spike 估算 |
| 7 | 图片加载：Coil 无对应（C-026） | 自建基于 `@ohos.multimedia.image` 的简易内存+磁盘缓存 | 评估 ohpm 三方库 | 影响所有图片消息/附件 |
| 8 | 崩溃/埋点：Firebase 不可用（C-047） | Preview 阶段**无埋点**，仅本地崩溃日志；上架前再接华为 Crash Kit | 直接接 HiAP 全家桶 | 合规边界最干净 |
| 9 | memory 检索（C-030） | sqlite-vector 未使用事实确认后，按 ArkTS 暴力检索（<10k 条）+ 服务端检索兜底设计 | 等 NAPI 向量库 | P1 后置，不阻塞 |

## 四、高风险登记（无需决策，需知情）

- **R-FTS**：FTS 分词方案重设计（见决策 5）。
- **R-OAUTH**：Codex/Gemini OAuth 的 token 存储与重定向流程需 HUKS + 平台化重实现（C-007/C-008）。
- **R-DEVICE**：Gate 0 唯一阻塞仍是 `hdc list targets` 为空；Q-1..Q-4 全部待设备。
- **R-SCOPE**：鸿蒙当前领先计划的切片（多搜索 provider、council、novel）增加了表面积；Chat MVP 主线（G-E）尚未开始，需在设备解锁后优先回归。

## 五、退出条件核对（Phase 0 source-only 部分）

- [x] Android 全部活跃能力在能力矩阵中有归属（CAPABILITY_MATRIX.csv 56 项：keep/degrade/replace/drop/defer/new/blocked_by_platform）
- [x] 流式协议矩阵完成（8 协议 + 23 条语义规则）
- [x] 数据模式矩阵完成（41 表 + 52 键 + 6 SP + 5 JSON blob + 7 文件目录 + 3 项结论）
- [x] ObjectBox 与三方 SDK 预研结论（D-007 + 本 packet 决策 5-9）
- [ ] 设备端 spike（Gate 0 blocked，等人工解锁 G-C）
