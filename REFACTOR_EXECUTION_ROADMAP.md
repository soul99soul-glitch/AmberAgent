# AmberAgent 重构执行路线图

> 基于 Plan v1.2.1 + CodeGraph/git 架构验证（2026-05-27）
> 本文是 plan 的 **delta 修订 + 执行 checklist**，不是替代文档。

---

## 一、架构验证结果汇总

### 1.1 Plan 假设验证

| # | Plan 假设 | 代码实际 | 判定 |
|---|---|---|---|
| H1 | ChatService 2618 行 god class | **2623 行**，85 个方法，24 个构造器依赖 | ✅ 一致 |
| H2 | data/agent/ 下 23 个子系统 | **18 个子目录 + 5 个根文件 = 23** | ✅ 一致 |
| H3 | DeepReadAgentRunManager 是"准 Runner" | **1163 行**，双层 supervisor loop + retry + cache，确认 | ✅ 一致 |
| H4 | DeepReadHiddenAssistantFactory 是 §1.5 生产样板 | 确认 `base.copy()` override 模式 | ✅ 一致 |
| H5 | AgentRuntimeModels.kt 已有 AgentRun/AgentRunStatus | 确认 L9/L30 | ✅ 一致 |
| H6 | GenerationHandler 直接持有 AgentToolDispatcher + PermissionDecisionResolver + SpeculativeToolRunner | **部分错误**：只直接持有 AgentToolDispatcher（1个），PermissionDecisionResolver 藏在 Dispatcher 内部，SpeculativeToolRunner 是 per-loop 临时创建 | ⚠️ 需修订 |
| H7 | Agent 子系统跟 ChatService 深度耦合 | **极低耦合**：18 个子目录中只有 cron(1处) 直接引用 ChatService | ✅ 比预期好 |
| H8 | Rust 4 crate 零调用方 | 当前分支确认一致（未在 main 上重验证 grep，但 Plan §2.6.1 事实1 已复核） | ✅ |

### 1.2 ChatService 职责分解（85 个方法 → 10 个群组）

| 群组 | 方法数 | 估计行数 | 耦合度 | 可拆性 |
|---|---|---|---|---|
| 1. Session 生命周期 | 8 | ~150 | 高 | 后拆（`sessions` map 是全局网关） |
| 2. 消息发送/生成主循环 | 17 | ~600 | 高 | 后拆（核心心脏） |
| 3. 工具审批 | 4 | ~150 | 高 | 后拆（与主循环强耦合） |
| 4. 工具构建 | 5 | ~200 | 中 | 中期（纯 factory，但依赖 17 个参数） |
| 5. 通知/任务跟踪 | 9 | ~200 | 低 | **先拆**（纯 side-effect） |
| 6. 持久化/状态更新 | 6 | ~100 | 高 | 后拆（基础设施） |
| 7. 消息操作（编辑/删除/分支） | 7 | ~250 | 中 | 中期 |
| 8. 翻译 | 3 | ~80 | 低 | **先拆**（零共享状态） |
| 9. AI 辅助生成（标题/建议/压缩） | 3 | ~150 | 低 | **先拆** |
| 10. Pending 消息队列 | 10 | ~120 | 低 | **先拆** |

**核心共享可变状态**（导致高耦合的根源）：
- `sessions: ConcurrentHashMap<Uuid, ConversationSession>` — 所有群组的中枢
- `trustedRunToolNames: ConcurrentHashMap` — 工具审批 + 生成循环共享
- `generationCheckpointAt: ConcurrentHashMap` — checkpoint + 生成循环共享

### 1.3 Agent 子系统耦合矩阵

**入站引用排名（被其他 agent 文件 import 的数量）：**

| 子系统 | 入站引用 | ChatService 引用 | GenerationHandler 引用 | 可拆难度 |
|---|---|---|---|---|
| **tools** | **19** | 0 | 0 | Hard（God Connector，扇出 10 模块） |
| workspace | 7 | 0 | 0 | Easy（零出向，被引用是数据类） |
| system | 6 | 0 | 0 | Easy（同上） |
| task | 6 | 0 | 0 | Easy（同上） |
| subagent | 5 | 0 | 1 | Hard（依赖 4 模块 + GenerationHandler） |
| modelcouncil | 4 | 0 | 0 | Medium（依赖 task+terminal） |
| terminal | 4 | 0 | 0 | Medium（依赖 task+workspace） |
| runtime | 2 | 0 | 0 | Hard（与 tools 双向依赖） |
| history | 2 | 0 | 0 | Easy |
| cron | 1 | **1** | 0 | Medium（唯一直接用 ChatService 的） |
| icloud | 1 | 0 | 0 | Easy |
| office | 1 | 0 | 0 | Medium（依赖 system+workspace） |
| **board** | **0** | 0 | **1** (deepread) | Medium（54 文件，GenerationHandler 嵌入 deepread） |
| live | 0 | 0 | 0 | **Easy** |
| miniapp | 0 | 0 | 0 | **Easy** |
| prompts | 0 | 0 | 0 | **Easy**（1 文件，可合并） |
| webmount | 0 | 0 | 0 | **Easy**（70 文件，仅依赖 tools 的 4 个扩展函数） |
| webview | 0 | 0 | 0 | **Easy**（1 文件） |

**关键发现**：
- tools/ 被引用的 19 处中，7 处来自 webmount adapters（仅用 4 个 JSON 扩展函数）
- 把这 4 个扩展函数提到 `:core:agent-utils`，webmount 的 70 文件立即解耦
- ChatService 对 agent 子系统的渗透极低（仅 cron 1 处），远好于预期

### 1.4 DeepRead 现状确认

| 维度 | 详情 |
|---|---|
| 文件数 | 22 个（含 template/ 子目录 6 个） |
| RunManager 行数 | 1163 行 |
| 与 ChatService 耦合 | **零**（22 个文件无一 import ChatService） |
| 外部依赖 | 3 个核心：SettingsAggregator, GenerationHandler, AppScope |
| Supervisor loop | 双层：Stage loop (max 2) + Verification loop (max 3) |
| "适配而非重写"可行性 | **高**：适配层 ~50-80 行，保留 RunManager + supervisor + retry + cache 全部不动 |

### 1.5 GenerationHandler 调用链（已修正）

```
ChatService.sendMessage() → GenerationHandler.generateText()
  │
  ├── [loop 0..maxSteps]
  │     ├── generateInternal() → Provider.streamText()
  │     │     └── speculativeRunner.observe() (临时创建)
  │     │
  │     ├── toolDispatcher.resolveDecision()
  │     │     └── [内部] PermissionDecisionResolver.resolve()
  │     │
  │     └── toolDispatcher.executeBatch()
  │           ├── prefetched = speculativeRunner.reusableResults()
  │           └── AgentToolDispatcher.execute() per tool
  │
  └── speculativeRunner is per-loop ephemeral (NOT DI-injected)
```

**修正 Plan TA.4**：不是"三件套从直接持有改为 RunScope"，而是"一件（toolDispatcher）从构造器注入改为 RunScope 传入"。PermissionDecisionResolver 不需要动（它在 Dispatcher 内部），SpeculativeToolRunner 不需要动（已经是临时创建）。

### 1.6 Legacy 白名单验证

**现有 5 条全部验证通过**。需补充 3 个中风险遗漏：

| # | 遗漏项 | 风险 | 建议 |
|---|---|---|---|
| 1 | `shortcuts.xml` — 硬编码 `me.rerere.rikkahub.ui.activity.ShortcutHandlerActivity` | 迁移后快捷方式失效 | 加入白名单或标记"需同步更新" |
| 2 | Broadcast action 字符串 `me.rerere.rikkahub.action.STOP_GENERATION` 等 | 通知按钮失效 | 标记"需同步更新的字符串常量" |
| 3 | `PreferenceStoreV1Migration.kt` — JSON class discriminator 字符串 | 已有用户 DataStore 解析失败 | **永不可改**，加入白名单备注 |

---

## 二、Plan 修订 Delta

### 修订 #1：Phase 0 白名单补充

**原因**：Legacy 包验证发现 3 个中风险遗漏。

**改动**：`config/legacy-package-allowlist.txt` 追加：

```
# 快捷方式 Activity FQN（shortcuts.xml 硬编码）
app/src/main/res/xml/shortcuts.xml  # 同步更新

# Broadcast action 常量（发送方/接收方必须同步迁移）
# STOP_GENERATION / TERMUX_COMMAND_RESULT 等

# JSON class discriminator（历史数据中的 FQN，永不可改）
app/src/main/java/me/rerere/rikkahub/data/datastore/PreferenceStoreV1Migration.kt
```

### 修订 #2：Phase A TA.4 描述修正

**原因**：GenerationHandler 的实际依赖关系与 plan 描述不符。

**改动**：TA.4 描述从"把 AgentToolDispatcher / SpeculativeToolRunner / PermissionDecisionResolver 这三件套从 GenerationHandler 的直接持有改为通过 RunScope.tools 拿"修正为：

> 把 `AgentToolDispatcher` 从 GenerationHandler 的构造器注入改为通过 `RunScope.tools` 传入。PermissionDecisionResolver 不需要改动（已封装在 Dispatcher 内部）。SpeculativeToolRunner 不需要改动（已是 per-loop 临时创建，只需把它的 `dispatcher` 参数改为从 scope 获取）。
>
> **真正的拆分难点**：`generateText` 内部 230 行的 tool loop 混合了 permission checking + execution + speculative prefetch + streaming + Generative UI fallback。TA.4 改的是"谁传 dispatcher"，不是拆这个 god method。God method 的拆解应视为 Phase C 的一部分（ChatTurnAgent 将重写此循环）。

**Effort 影响**：从 3-4 天降为 **2-3 天**（工作量比预期小）。

### 修订 #3：Phase B 估时确认

**原因**：DeepRead 依赖图验证确认适配策略高度可行。

**改动**：保持 v1.2.1 的"适配而非重写"选择和 4-5 周估时。补充以下技术细节：

- `collectRun()` 方法（~40 行）是桥接 GenerationHandler → scope.llm 的唯一改动点
- `AppScope` 用于 `scheduleBackgroundFill`——需确认 Agent 框架的 scope 与 AppScope 兼容
- `toIsolatedSubAgentSettings()` 从 Settings 创建隔离副本——Agent 框架如对 Settings 有要求需适配

### 修订 #4：Phase D 优先级重排（基于耦合矩阵）

**原因**：Plan v1.2.1 按"改动频率"排序，但缺少"耦合成本"维度。代码验证揭示了实际的依赖拓扑。

**选项**（需要你决定）：

| 选项 | 策略 | 优点 | 缺点 |
|---|---|---|---|
| **A. 先易后难 ✅ 已选定** | 先拆零入站引用的 6 个 easy 模块，清场后再攻 tools/board | 每步低风险；进度可见；为 tools 拆分减负 | 可能先拆的是低改动频率的"冷门"模块 |
| B. 保持 v1.2.1 频率驱动 | 按 tools/board 高频改动机会主义拆 | 顺应开发节奏 | tools 有 19 入站引用，拆它成本高且风险大 |
| C. 混合策略 | 先做 Quick Win（提取 4 个扩展函数解耦 webmount），再按频率 | 兼顾两边 | 需要协调两种节奏 |

**推荐选项 A 的具体排序**：

```
Sprint 1（Quick Wins，1-2 天）:
  ├── 提取 tools/ 的 4 个 JSON 扩展函数到 :core:agent-utils
  └── webmount 入站引用降为 0

Sprint 2（零依赖批量拆，1 周 sprint）:
  ├── :feature:live (3 files)
  ├── :feature:webview (1 file)
  ├── :feature:miniapp (12 files)
  └── :feature:history (2 files)    — 被 subagent/tools 引用需提取接口
  └── prompts (1 file) → 合并入 :feature:subagent 或 :feature:board

Sprint 3（中等难度，机会主义）:
  ├── :feature:webmount (70 files，依赖 Sprint 1 解耦)
  ├── :feature:icloud (6 files)
  ├── :feature:office (5 files，依赖 system+workspace 接口)
  └── :feature:cron (4 files，唯一需要去除 ChatService 依赖)

Sprint 4+（高耦合，Phase C 之后）:
  ├── :feature:board (54 files，含 DeepRead，Phase B 已部分迁)
  ├── :feature:tools (33 files，God Connector，最后拆)
  ├── :feature:subagent (9 files)
  └── :feature:terminal, :feature:modelcouncil
```

### 修订 #5：新增 Phase A.0 — 顶层函数清理

**原因**：GenerationHandler.kt 第 120-134 行有一个游离的顶层函数 `shouldPauseForToolApproval` 每次 `new PermissionDecisionResolver()` 绕过 DI singleton。这是遗留代码，Phase A 开始前应清理。

**改动**：在 Phase 0 完成后、Phase A 开始前，把 `shouldPauseForToolApproval` 改为调用 DI 注入的 `AgentToolDispatcher.shouldPauseForApproval`。工作量 < 1 小时。

### 修订 #6：Phase C ChatService 拆分策略细化

**原因**：ChatService 85 方法的职责群组分析揭示了具体的拆分路径。

**改动**：Phase C 不需要一次性把所有非核心职责都搬走。建议分两步：

**TC.0（前置，可在 Phase B 期间并行做）：低耦合外围预拆**
- `TranslationService`（3 方法，~80 行）
- `GenerationNotifier`（通知/任务跟踪，9 方法，~200 行）
- `AiAuxiliaryGenerator`（标题/建议/压缩，3 方法，~150 行）
- `PendingMessageStore`（Pending 队列，10 方法，~120 行）

合计削减 ~550 行。每刀零共享状态，独立 PR，可在 Phase B 期间机会主义完成。

**TC.1-TC.6（原 Phase C 主体）：核心搬迁**

ChatService 经 TC.0 预拆后从 ~2600 行降到 ~2050 行，剩下的是 Session 管理 + 主循环 + 工具审批 + 持久化 + 消息操作——这些通过 ChatTurnAgent 替换整块搬走。

### 修订 #7：Rust 性能热路径全面嵌入（高+中 ROI，按 crate 归组）

**原因**：对全 codebase 的 CPU/内存敏感路径扫描 + 中等收益候选补充。既然大重构了，把所有值得做的 Rust 重写一次性排进去。

**按 crate 归组的完整 Rust 重写清单**：

---

#### Crate 1: `:core:native:tokenizer`（Phase A TA.3b）

**TA.3b — batch token count**（plan 已有，不重复）

**TA.3b+ — `weightedTokenChars()` CJK 字符分类（+0.5 天）**

`ContextFootprintEstimator.kt` 的 `weightedTokenChars()` 逐字符扫描判断 CJK 范围（O(n)，n = 100K-500K 字符），与 tokenizer 同域。

```rust
// core/native/tokenizer/src/lib.rs 追加
fn weighted_token_chars(text: &str) -> u32 {
    text.chars().map(|c| if is_cjk(c) { 2 } else { 1 }).sum()
}
```

**TA.3b++ — `estimateFootprint()` 消息序列化估算整体下沉（+1 天）**

`ContextFootprintEstimator` 168 行中除了 CJK 分类，还有消息结构遍历 + 序列化长度估算。把整个估算逻辑下沉到 Rust，Kotlin 端只传消息结构（序列化后的 JSON 或 packed binary），Rust 做遍历 + CJK 分类 + 累加。

```rust
fn estimate_footprint(messages_json: &str) -> FootprintResult {
    // serde_json 遍历 + weighted char count 一次完成
}
```

**验收**：benchmark 100K 字符 / 200 条消息输入，native vs JVM，预期 2-3x。

---

#### Crate 2: `:core:native:sync-crypto`（Phase A.5 TA5.9）

**TA5.9a — PBKDF2 + AES-GCM + SHA-256（+2-3 天，已在 roadmap）**

```
core/native/sync-crypto/
├── Cargo.toml           # ring 或 aws-lc-rs
├── sync_crypto.udl
└── src/
    ├── lib.rs
    ├── pbkdf2.rs        # PBKDF2-HMAC-SHA256, 210k iterations → 5-15x
    ├── aes_gcm.rs       # AES-256-GCM streaming encrypt/decrypt → 2-4x
    ├── sha256.rs        # SHA-256 hash → 3-5x
    ├── hmac.rs          # ★ 新增：HMAC-SHA256（AwsSignatureV4 用）
    └── redactor.rs      # ★ 新增：JSON deep redact
```

**TA5.9b — AwsSignatureV4 HMAC-SHA256 签名（+0.5 天）**

`AwsSignatureV4.kt` 161 行，S3 sync 每个请求都要做 HMAC-SHA256 签名链。ring 的 HMAC 跟 SHA-256 同一个 crate，边际成本极低。

**TA5.9c — SyncRedactor JSON deep redact（+1 天）**

`SyncRedactor.kt` 192 行，递归遍历 JSON 树做敏感字段脱敏（匹配 key → 替换 value）。serde_json 的树遍历比 kotlinx.serialization 快，且 redact 逻辑是纯字符串匹配，天然适合 Rust。

```rust
fn redact_json(json: &str, sensitive_keys: &[&str], mask: &str) -> String {
    // serde_json::Value 递归遍历 + key 匹配 → 替换
}
```

**验收**：
- PBKDF2 / AES-GCM / SHA-256 benchmark（同前）
- HMAC-SHA256：1000 次签名 native vs JVM，预期 3-5x
- Redactor：1MB JSON deep redact，native vs JVM，预期 2-3x
- 备份/恢复 + S3 sync 全链路冒烟测试通过

---

#### Crate 3: `:core:native:markdown`（Phase D TD.Rust.1 扩展）

**TD.Rust.1a — AST primary 切换（+5-7 天，已在 roadmap）**

`Markdown.kt`（2009 行）是全 app 最热的路径：
- JetBrains markdown-parser + 5 遍正则预处理
- Streaming 场景每秒 10-30 次 parse，**直接影响帧率**
- Shadow bridge（`MarkdownNativeSwitch`）已验证 pulldown-cmark 正确性

Phase D 滚动迁移触碰 richtext 模块时切为 primary：
1. 渲染器从消费 JetBrains `ASTNode` 切到消费 `PackedAstReader`（已有）
2. Feature flag dispatch 复用 `MarkdownNativeSwitch` 现有框架

**TD.Rust.1b — LaTeX 预处理合并到 Rust 侧单遍扫描（+1 天）**

`MarkdownNew.kt` 的 `preProcess()` 每次渲染跑 3 遍正则（`INLINE_LATEX_REGEX` / `BLOCK_LATEX_REGEX` / `CODE_BLOCK_REGEX`），streaming 时每秒触发 10-30 次。合并到 pulldown-cmark AST 构建前的单遍扫描：

```rust
fn preprocess_latex(input: &str) -> String {
    // 单遍状态机：识别 code block 区间 → 跳过；识别 \(...\) 和 \[...\] → 转义为 $...$ 和 $$...$$
    // 比 3 遍 Kotlin Regex 快 3-5x
}
```

**TD.Rust.1c — HtmlDiffNormalizer 归一化（+0.5 天）**

`HtmlDiffNormalizer.kt` 139 行用 Jsoup 做 HTML 归一化（属性排序、空白折叠、实体统一），用于 divergence sampling。Rust 侧用 html5ever 做同样的 normalize，Phase A.5 的 divergence sampler 可直接复用，减少 Jsoup 依赖。

**验收**：
- 30 篇代表性 markdown 渲染一致性 ≥ 99%
- Streaming parse benchmark：帧率 P95 提升可测
- 长对话（500+ 消息）滚动无 jank
- LaTeX 渲染正确性：20 篇含 LaTeX 的消息，native 预处理 vs Kotlin 预处理输出等同

---

#### Crate 4: `:core:native:json-expr`（Phase D 新增，触碰 common/ 模块时）

**TD.Rust.2 — JsonExpression 引擎重写（+2-3 天）**

`common/http/JsonExpression.kt` 382 行，自研 lexer + parser + evaluator：
- 支持路径导航（`field.sub.array[0]`）、算术运算、字符串连接
- 用于 Provider 余额查询的 result path 解析、自定义 body merge
- 纯计算零 Android 依赖，是最适合 Rust 重写的类型

```
core/native/json-expr/
├── Cargo.toml           # serde_json
├── json_expr.udl
└── src/
    ├── lib.rs
    ├── lexer.rs
    ├── parser.rs
    └── evaluator.rs     # serde_json::Value 上做路径遍历 + 运算
```

**验收**：
- 现有 JsonExpression 单测全部移植到 Rust（确保行为等同）
- benchmark 100 个表达式 × 10KB JSON，native vs JVM，预期 2-4x

---

#### Crate 5: `:core:native:sync-archive`（Phase D 条件性，触碰 sync 模块时）

**TD.Rust.3 — JSONL 流式序列化 + Zip 压缩（+5-6 天，条件性）**

`SyncArchiveManager.kt` 764 行：cursor → JsonObject → JSONL → ZipOutputStream。恢复反向。

只在备份/恢复成为用户痛点时做。收益：simd-json + Rust zip 处理，预期 2-5x。

若做，可让 Rust 通过 UniFFI 接收 `Vec<String>`（每行 JSONL），直接写 Zip：

```rust
fn archive_to_zip(jsonl_rows: Vec<String>, output_path: &str) -> Result<(), ArchiveError>
fn extract_from_zip(zip_path: &str) -> Result<Vec<String>, ArchiveError>
```

**TD.Rust.3b — SQLite cursor → JSON 批量转换（+2 天，条件性）**

进一步优化：让 Rust 通过 rusqlite 直接读 SQLite 文件 → serde 序列化，跳过 Kotlin 的 Room cursor + JsonElement 中间层。预期 3-5x。

**触发条件**：备份 > 50MB 或恢复耗时 > 30 秒成为用户投诉。

---

#### Crate 6: `:core:native:image-util`（Phase D 条件性，触碰截图/文件处理时）

**TD.Rust.4 — PNG/JPEG 编码 + Base64 + EXIF 旋转（+3 天，条件性）**

| 功能 | 现状文件 | Rust 实现 | 收益 |
|---|---|---|---|
| PNG/JPEG 编码 | ScreenCaptureService + WebViewScreenshot，`Bitmap.compress()` | image-rs / oxipng | 2-4x |
| Base64 大 payload | FileEncoder.kt 303 行，HEIC/AVIF → JPEG + Base64 | base64 crate（SIMD） | 2-3x |
| EXIF 旋转 | FileEncoder 的 ExifTransform，纯像素矩阵操作 | image-rs rotate/flip | 3-5x |

注意：Bitmap **解码**（BitmapFactory.decodeStream）仍用 Android native。Rust 只接管"拿到 raw pixels 之后"的编码/转码/旋转。

**触发条件**：多模态消息体积 / OOM 问题被 profile 出来，或截图压缩延迟影响 UX。

---

#### 不做的（ROI 不足即使中等也不值）

| 候选 | 为什么不做 |
|---|---|
| 正则 transformer | 已有 Rust bridge（`RegexNativeSwitch`），无额外空间 |
| 模板替换 TemplateTransformer | 49 行，n 太小（几个变量），JNI 往返 > 执行成本 |
| MessageStreamAccumulator | 331 行，瓶颈是数据结构操作不是计算，Rust 无优势 |
| FTS 搜索 | SQLite FTS5 已是 native C |
| AIIconMatcher | 总耗时 < 1ms，有缓存 |
| ConversationContextEngine 整体 | 806 行但重度依赖 Room/Kotlin Flow，只有 estimateFootprint 子函数适合（已收进 tokenizer crate） |

---

#### Rust 重写工作量汇总

| Crate | Phase | 确定/条件 | 额外工作量 |
|---|---|---|---|
| tokenizer 扩展 | A | 确定 | +1.5 天 |
| sync-crypto 扩展 | A.5 | 确定 | +1.5 天（AwsV4 + Redactor） |
| markdown 扩展 | D | 确定 | +1.5 天（LaTeX + HtmlDiff） |
| json-expr | D | 确定 | +2-3 天 |
| sync-archive | D | 条件性 | +5-8 天 |
| image-util | D | 条件性 | +3 天 |

**确定做的总额外**：+6.5-7.5 天（分散在 A / A.5 / D 中）
**条件性的**：+8-11 天（触发条件明确，不做不亏）

---

## 三、时间线修订

```
Week 1      Phase 0    (2-4 天)    护栏 + 白名单补充
            Phase A.0  (<1 天)     shouldPauseForToolApproval 清理

Week 2-4    Phase A    (2-3 周)    Kernel 合同 + UniFFI pioneer
            TA.4 调为 2-3 天（比 plan 少 1 天）
            ★ TA.3b+  weightedTokenChars 并入 tokenizer crate (+0.5 天)
            ★ TA.3b++ estimateFootprint 整体下沉 (+1 天)

Week 5-7    Phase A.5  (2-3 周)    接线 markdown + regex
            ★ TA5.9a sync-crypto: PBKDF2 + AES-GCM + SHA-256 (+2-3 天)
            ★ TA5.9b sync-crypto: AwsSignatureV4 HMAC (+0.5 天)
            ★ TA5.9c sync-crypto: SyncRedactor JSON redact (+1 天)

Week 5-7    TC.0 可并行  (机会)     ChatService 外围预拆（翻译/通知/AI辅助/Pending）
            ↑ 不阻塞 Phase B，按机会做

Week 8-12   Phase B    (4-5 周)    DeepRead 适配 + reader-extractor
            保持 v1.2.1 估时

Week 13-18  Phase C    (5-7 周)    ChatService 核心降级
            TC.0 已削减 ~550 行，主体工作量稍降
            ★ TC.4+ ChatPage recomposition 治理（挂载 TC.4, +2-3 天）

            ═══ "事实上脱 fork" milestone ═══

Week 19+    Phase D    (滚动)      Quick Win sprint → 零依赖批量拆 → 中等 → 高耦合
            ↑ 见修订 #4 排序
            ★ TD.Rust.1a Markdown AST primary 切换 (+5-7 天)
            ★ TD.Rust.1+ Markdown streaming 增量 parse（挂载 Rust.1a, +1-2 天）
            ★ TD.Rust.1b LaTeX 预处理合并到 Rust (+1 天)
            ★ TD.Rust.1c HtmlDiffNormalizer Rust 化 (+0.5 天)
            ★ TD.Rust.2  JsonExpression 引擎重写 (+2-3 天)
            ★ TD.Perf.1  对话全量加载守卫 (+0.5 天)
            ◆ TD.Perf.2  DI lazy module（模块拆分自然获得, +0 天）
            ☆ TD.Rust.3  sync-archive JSONL+Zip（条件性, +5-8 天）
            ☆ TD.Rust.4  image-util PNG/JPEG/Base64/EXIF（条件性, +3 天）

Week ?      Phase E    (1-2 周)    Legacy 清理
            Phase F    (永远进行时)
```

★ = 确定做　　☆ = 条件性　　◆ = 自然获得（零额外工作量）

**总周期影响**：Rust 全面嵌入 +14-18 天 + Compose 优化 +3.5-5.5 天 = 确定做的合计 **+18-24 天**（分散在 A/A.5/C/D 中）。条件性 Rust +8-11 天。Phase D 排序优化预计加速 2-4 周（抵消新增工作量）。整体 **7-10 个月**，"事实脱 fork"约 **4-5.5 个月**。

**Rust 加速用户感知时间线**：
- Phase A 结束：token 计数加速（开发者可测，用户无直接感知）
- Phase A.5 结束：**备份/恢复速度 5-15x**（用户明确感知）+ markdown/regex 渲染加速
- Phase D 中段：**Markdown 帧率提升**（长对话滚动体验显著改善）+ JSON 表达式加速

### 修订 #8：Compose 性能优化嵌入（挂载到已有 Task）

**原因**：Compose 层存在 recomposition 风暴和 streaming 重复计算，是独立于 Rust 的另一类性能问题。这些优化跟已有 Task 改的是同一个文件，不需要独立 plan，挂到对应 Task 上顺手做即可。

#### TC.4+ — ChatPage recomposition 治理（挂载到 TC.4 ChatPage UI 改造，+2-3 天）

**问题**：ChatPage.kt 有 **16 个 `collectAsStateWithLifecycle`**（conversation / settings / loadingJob / processingStatus / streamingSummary / compactLifecycleState...）。每个 StateFlow 变化触发整个 ChatPage recompose。streaming 时 `processingStatus` 每秒更新几十次，拖着整个页面重组。

**改法**：TC.4 已经要改 ChatPage 切 observe 模式。同时做：
1. 按区域拆成独立 Composable：`ChatMessageList` / `ChatInputBar` / `ChatTopBar` / `ChatStreamingIndicator`
2. 每个子 Composable 各自 collect 自己需要的 StateFlow
3. streaming 状态变化只 recompose 指示器，不波及消息列表
4. `derivedStateOf` 合并多个 state 读取，减少不必要的 recomposition

**验收**：
- Compose compiler metrics：ChatPage 的 restartable groups 数量减少 50%+
- streaming 长消息时 GPU Profiler 无连续 jank frame
- Layout Inspector 确认消息列表区域在 streaming 时不 recompose

#### TD.Rust.1+ — Markdown streaming 增量解析（挂载到 TD.Rust.1a AST 切换，+1-2 天）

**问题**：每个 token chunk 到达 → 整条消息重新 `parseMarkdown` → 重新构建 AST。`MarkdownParseCache` 的 LRU 在 streaming 时永远 miss（每次 content 多一个 token，key 变化）。5KB 消息每秒 parse 30 次。

**改法**：TD.Rust.1a 已经要把 Markdown AST 切到 Rust。同时做：
- **方案 A（推荐）**：streaming 降频 parse——每 200ms parse 一次，中间只追加 raw text 到末尾。完成时做最终 parse。实现简单，Kotlin 端改 ~20 行
- **方案 B**：Rust 侧 pulldown-cmark 保留 parser 状态，新 chunk 只 parse 增量。实现复杂但理论最优

**验收**：streaming 长消息（5KB+）时 CPU 占用对比优化前降 5x+

#### TD.Perf.1 — 对话全量加载守卫（Phase D 触碰 ChatService 时，+0.5 天）

**问题**：`ensureFullConversationLoaded` 在某些路径下一次性加载全量消息到内存。500+ 消息的长对话明显卡顿。

**改法**：加一个 guard——全量加载只在 export / search / compress 时触发，正常浏览保持 window 分页模式。

```kotlin
suspend fun ensureFullConversationLoaded(conversationId: Uuid, reason: LoadReason) {
    when (reason) {
        LoadReason.BROWSE -> return // window 模式已够用
        LoadReason.EXPORT, LoadReason.SEARCH, LoadReason.COMPRESS -> { /* 全量加载 */ }
    }
}
```

#### TD.Perf.2 — DI lazy module loading（Phase D 模块拆分后自然获得，+0 天）

Phase D 把 agent 子系统拆成独立 Gradle module 后，每个 feature module 的 Koin module 可以 lazy load：

```kotlin
// 用户不进 DeepRead → 不初始化 deepreadModule
val deepreadModule = lazy { module { single { DeepReadAgentRunManager(...) } } }
```

13 个 module 缩减到启动时只加载核心 5 个（app / chat / di / dataSource / repository），其余按需加载。冷启动预计改善 100-200ms。

**不额外加工作量**——模块拆分本身自然产生这个能力，只需要在 `startKoin` 注册时从 `modules(...)` 改为 `lazyModules(...)`。

#### 不做的（ROI 不足）

| 候选 | 为什么不做 |
|---|---|
| Room Flow 精确 invalidation | Room 的 `InvalidationTracker` 按表粒度是框架限制，workaround 需要 `@RawQuery` 重写所有 DAO，成本高收益低 |
| 消息列表 key 策略 | 已经用 `item.second.id` 做 key，正确 |

#### Compose 优化工作量汇总

| 优化 | 挂载到 | 额外工作量 |
|---|---|---|
| ChatPage recomposition | TC.4 | +2-3 天 |
| Markdown streaming 增量 parse | TD.Rust.1a | +1-2 天 |
| 对话全量加载守卫 | Phase D | +0.5 天 |
| DI lazy module | Phase D 模块拆分 | +0 天（自然获得） |
| **合计** | | **+3.5-5.5 天** |

---

## 四、Phase 0 立即可执行 Checklist

以下是 Phase 0 的完整 checklist，可以马上开始：

### T0.1 — 建立 `app.amber.*` 包骨架 (30 min)
- [ ] `mkdir -p app/src/main/java/app/amber/{core,feature,platform}`
- [ ] 每个目录加 `.gitkeep`
- [ ] 验收：目录存在

### T0.2 — 写 legacy 白名单 (30 min)
- [ ] 创建 `config/legacy-package-allowlist.txt`
- [ ] 包含 plan 的 5 个原始条目
- [ ] **新增**：`shortcuts.xml`（同步更新标记）
- [ ] **新增**：broadcast action 常量说明
- [ ] **新增**：`PreferenceStoreV1Migration.kt`（永不可改标记）
- [ ] 验收：文件存在，后续迁移前先 grep

### T0.3 — detekt 自定义规则 (3-4 hr)
- [ ] `LegacyPackageMustNotGrow` 规则
- [ ] 读取白名单文件做排除
- [ ] 验收：新文件进 `me.rerere.*` → detekt fail；进 `app.amber.*` → pass

### T0.4 — CI 防回流 job (1-2 hr)
- [ ] `.github/workflows/legacy-package-guard.yml`
- [ ] 验收：PR 加 legacy 新文件 → CI fail

### T0.5 — 不变性清单 ADR-0001 (1-2 hr)
- [ ] Room DB 文件名
- [ ] DataStore name 列表
- [ ] 备份格式 keyword
- [ ] JNI 符号前缀映射表
- [ ] applicationId / namespace
- [ ] AppDatabase ↔ AgentRuntimeDatabase 版本联动
- [ ] NativeAccelFlags DataStore key 永久保留

### T0.6 — shouldPauseForToolApproval 清理 (<1 hr)
- [ ] GenerationHandler.kt L120-134 的顶层函数改为调用 DI 注入的 Dispatcher
- [ ] 验收：grep `new PermissionDecisionResolver` 结果为 0（排除 DI 声明）

---

## 五、第一个 PR 的具体范围

**PR Title**: `chore: Phase 0 — defensive foundation for Agent Kernel migration`

**Scope**:
1. `app/src/main/java/app/amber/` 包骨架（T0.1）
2. `config/legacy-package-allowlist.txt`（T0.2）
3. detekt custom rule `LegacyPackageMustNotGrow`（T0.3）
4. CI workflow `legacy-package-guard.yml`（T0.4）
5. ADR-0001 文档（T0.5）

**不含**：T0.6 的 `shouldPauseForToolApproval` 清理单独一个 PR。

**预计 reviewable 行数**：~300 行（大部分是配置/文档/CI yaml）。

---

## 六、模块依赖图（文字版）

```
┌──────────────────────────────────────────────────────────────┐
│  ChatService (2623 行，85 方法，24 依赖)                      │
│  ↓ 调用                                                      │
│  GenerationHandler (9 依赖，仅 toolDispatcher 涉及 agent)     │
│  ↓ 持有                                                      │
│  AgentToolDispatcher ←→ PermissionDecisionResolver (内部)      │
│  ↑ 临时创建                                                   │
│  SpeculativeToolRunner (per-loop ephemeral)                   │
└──────────────────────────────────────────────────────────────┘

Agent 子系统 (18 dirs, 228 .kt files, ~35k lines):
┌─────────────────────────────────────────────────────────────┐
│ [Zero Inbound — Easy Extract]                                │
│  board(54) live(3) miniapp(12) webmount(70)                  │
│  webview(1) prompts(1)                                       │
│                                                              │
│ [Low Inbound — Easy Extract]                                 │
│  history(2) icloud(6) workspace(2) system(2) task(4)         │
│                                                              │
│ [Medium Inbound]                                             │
│  cron(4) office(5) modelcouncil(6) terminal(4)               │
│                                                              │
│ [High Inbound — Hard Extract]                                │
│  tools(33) ← God Connector, 19 inbound                      │
│  subagent(9) ← depends on GenerationHandler                  │
│  runtime(5) ← bidirectional with tools                       │
└─────────────────────────────────────────────────────────────┘

tools/ 入站来源分解:
  7 × webmount adapters (仅 4 个 JSON 扩展函数 → 可提取到 :core:utils)
  3 × runtime (AgentToolDispatcher 等)
  1 × DeepRead (AgentToolSetFactory)
  1 × subagent (SubAgentToolScope)
  7 × 其他 (board, prompts, etc.)
```

---

## 七、风险盲点（Plan 未识别，CodeGraph 揭示）

| # | 风险 | 来源 | 影响 | 建议缓解 |
|---|---|---|---|---|
| R1 | `shouldPauseForToolApproval` 顶层函数绕过 DI singleton 创建 PermissionDecisionResolver | GenerationHandler.kt L120-134 | Phase A TA.4 如果不清理，会留两个 resolver 实例 | Phase 0 末尾清理（修订 #5） |
| R2 | tools/ 与 runtime/ 存在**双向依赖** | 耦合矩阵分析 | Phase D 拆 tools/ 或 runtime/ 时需同时处理 | 先提取共享类型到 `:core:agent-models` |
| R3 | GenerationHandler.generateText 230 行 tool loop 是真正的 god method | 调用链分析 | TA.4 只改"谁传 dispatcher"不降低此复杂度；Phase C ChatTurnAgent 必须重写此循环 | Phase C 估时中已含此工作量，但应在 ADR-0002 中显式标注 |
| R4 | `PreferenceStoreV1Migration.kt` 的 JSON discriminator 字符串永不可改 | Legacy 白名单验证 | Phase E 包名清理时如果不小心改了，已有用户数据解析崩溃 | 白名单中标注"永不可改"，detekt 规则禁止修改此文件 |
| R5 | webmount 70 文件对 tools 的依赖实质是 4 个 JSON 扩展函数 | 耦合矩阵分析 | Phase D 如果不先提取这 4 个函数，webmount 会被误判为"依赖 tools、不可先拆" | Phase D Sprint 1 先做 Quick Win |

---

## 八、Quick Win 列表（可低成本立即做的解耦）

| # | 动作 | 工作量 | 效果 |
|---|---|---|---|
| QW1 | 提取 `boolean/long/string/requiredString` 4 个 JSON 扩展函数从 tools/ 到 `:core:agent-utils` | 2 小时 | webmount(70 files) 对 tools 的 14 处 import 归零 |
| QW2 | `shouldPauseForToolApproval` 清理 | 1 小时 | 消除绕过 DI 的遗留入口 |
| QW3 | 翻译 3 方法从 ChatService 抽出 | 2 小时 | ChatService -80 行，零风险 |
| QW4 | 通知/任务跟踪 9 方法从 ChatService 抽出 | 4 小时 | ChatService -200 行，纯 side-effect 无共享状态 |
| QW5 | Pending 消息队列抽为独立 store | 3 小时 | ChatService -120 行 |
