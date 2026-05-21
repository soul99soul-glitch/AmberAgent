# Rust Native Components Spike Plan

> **状态**: Spike 阶段（不接生产渲染层、不删 JVM 路径、随时可 abandon）
> **分支**: `spike/rust-native-components`（拉自 `main@595e8414`）
> **Worktree 隔离**: 只在 `rikkahub-refactor-godclass/` 工作，主 `rikkahub/` 是 Codex 禁区
> **整体退出条件**: 任一组件 spike 不达标即 abandon 该组件；不强行落地

---

## 0. 目标

把三个 **CPU 重 / 纯 JVM 实现 / 边界清晰** 的子系统替换成 Rust JNI 实现，验证：

1. 真实加速比是否达到承诺线
2. JNI marshalling overhead 是否吞掉加速
3. 多架构 native .so 集成进 AAR 是否平稳
4. APK binary size 增量是否可接受

**核心约束**：
- 不动 UI / Compose 层
- JVM 原实现保留为 fallback，不删
- 每个组件独立可 abandon
- 行为等价（同输入产出等价输出）是硬指标

---

## 1. 组件优先级与执行顺序

按"性能痛点 + 实施风险"综合排序，**风险低先做**：

### Component #1 — `office-parsers` (Docx + Pptx)
- **JVM 现状**: `document/src/main/java/me/rerere/document/{DocxParser,PptxParser}.kt`，手撸 XmlPullParser + ZipInputStream
- **API**: `parse(file: File): String`（纯文本输出带结构标记）
- **Rust 替代**: `quick-xml` + `zip` crate
- **预期加速**: 3-5x（大文档 >5MB），峰值内存 -50%
- **风险**: 🟢 低 — 边界完全清晰，输入路径输出字符串，无 UI 耦合
- **PDF 不动**: 已用 MuPDF native，行业级，Rust 替不了

### Component #2 — `markdown-parser` (pulldown-cmark)
- **JVM 现状**: `app/.../richtext/Markdown.kt` 通过 JetBrains `org.intellij.markdown` 库 + GFM flavour 解析
- **API**: 入口 `parser.buildMarkdownTreeFromString(text)` → `ASTNode` 树；`MarkdownParseResult(preprocessed, astTree, hasHtmlBlocks, stableTopLevelBlocks)` 是结果包装
- **Rust 替代**: `pulldown-cmark` (event-based) + 我们自己构造 AST tree + 序列化成 packed binary
- **预期加速**: 3-5x parse cost（streaming tail 单次 1-2ms → 0.3-0.6ms）
- **风险**: 🔴 高 — Markdown.kt/MarkdownNew.kt 正在 12+ commit/月 migration；ASTNode 形状要做兼容映射；下游 Compose 渲染深度耦合
- **Spike 阶段**: **只验证 parser 加速 + AST 等价**，不接渲染层；接渲染层是 Phase 2 工作

### Component #3 — `highlight-parser` (tree-sitter)
- **JVM 现状**: `highlight/src/main/java/me/rerere/highlight/Highlighter.kt` 通过 QuickJS + Prism JS 库
- **API**: `suspend fun highlight(code, language): List<HighlightToken>`
- **HighlightToken** 形态: `Plain(content) | Token.StringContent(content, type, length) | Token.StringListContent(...) | Token.Nested(content, type, length)`
- **Rust 替代**: `tree-sitter` + `tree-sitter-highlight` + 各语言 grammar crate
- **预期加速**: 5-10x 首次 highlight（QuickJS spin-up + JS lib 加载消失）+ bundle 资源 -2MB（不带 prism.js 了）+ 多 10-15MB（tree-sitter native binaries）
- **风险**: 🟡 中 — 涉及 UI token 渲染，HighlightToken sealed class 映射；需要选哪些语言预打包
- **初始语言集**: Kotlin / Java / Python / JavaScript / TypeScript / Rust / Go / Bash / JSON / YAML / Markdown / SQL / HTML / CSS（14 个常用语言）

### 不做的（前期分析已排除）
- ❌ ai 模块 SSE / search providers / tts — I/O bound 或已 native
- ❌ web Ktor server — 静态文件低流量
- ❌ common 工具 — 微开销
- ❌ WebMount — WebView 系统组件
- ❌ Room/SQLite — 已 C native
- ❌ PdfParser — 已 MuPDF native
- ❌ 图片加载 (Coil/Glide) — 已 Android Skia native

---

## 2. 共享基础设施

### 2.1 Rust 工具链

```bash
# 用户机器需要安装一次
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --default-toolchain stable
source $HOME/.cargo/env
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

# Android NDK（如未装）
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "ndk;27.0.12077973"
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.0.12077973
```

### 2.2 项目结构

```
native/                      ← Cargo workspace 根
├── Cargo.toml               workspace manifest (members: 3 crates)
├── README.md
├── office-parsers/          Component #1
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs           JNI entry
│       ├── docx.rs
│       ├── pptx.rs
│       └── error.rs
├── markdown-parser/         Component #2
│   ├── Cargo.toml
│   └── src/
│       ├── lib.rs           JNI entry
│       ├── packed_ast.rs    packed binary AST format
│       └── type_mapping.rs  pulldown-cmark Tag → JetBrains IElementType 映射
└── highlight-parser/        Component #3
    ├── Cargo.toml
    └── src/
        ├── lib.rs           JNI entry
        ├── grammars.rs      预打包语言注册表
        └── token_emit.rs    tree-sitter highlight events → HighlightToken 序列化
```

### 2.3 Gradle 集成

用 [Mozilla rust-android-gradle plugin](https://github.com/mozilla/rust-android-gradle) v0.9+，配置示例：

```kotlin
// 顶层 settings.gradle.kts: pluginManagement { plugins { id("org.mozilla.rust-android-gradle.rust-android") version "0.9.6" } }
// 模块 build.gradle.kts:
plugins {
    id("org.mozilla.rust-android-gradle.rust-android")
}

cargo {
    module = "../native/office-parsers"
    libname = "office_parsers"
    targets = listOf("arm64", "arm", "x86_64")
    profile = "release"
    apiLevel = 26  // 对齐 document module minSdk
}

afterEvaluate {
    tasks.named("preBuild").configure { dependsOn("cargoBuild") }
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("${buildDir}/rustJniLibs/android")
        }
    }
}
```

### 2.4 JNI 边界设计原则

| 原则 | 说明 |
|---|---|
| **粗粒度调用** | 一次跨 JNI 完成所有工作（"parse 整个文件 / 整段 markdown"），不要每个 child node 一次 hop |
| **零拷贝优先** | Rust 写 buffer 通过 `DirectByteBuffer` 共享，避免数据拷贝 |
| **无状态** | Rust 侧不持有 handle / lifetime，Kotlin GC 完整管理；每次调用独立 |
| **错误透传** | Rust panic → catch → 转 `RuntimeException` 上抛；解析错误返回空 buffer + 可选 Java exception |
| **多架构** | 至少 arm64-v8a + armeabi-v7a + x86_64（emulator 测试）|

### 2.5 packed binary 格式（用于 Markdown AST + Highlight tokens）

通用 header：

```
magic: 4 bytes (per-format identifier, e.g., 'PMDA' for markdown AST, 'PHLT' for highlight tokens)
version: u8
flags: u8
reserved: u16
```

具体格式见各组件章节。**不用 FlatBuffers**——简单 packed binary 解码快 30-50% + 无 schema 依赖 + binary 小。

---

## 3. Component #1 — office-parsers

### 3.1 API 设计

```kotlin
// document/src/main/java/me/rerere/document/native/OfficeParserNative.kt
internal object OfficeParserNative {
    init { System.loadLibrary("office_parsers") }

    external fun parseDocxNative(path: String): String   // 返回纯文本内容，失败返回 "" + 抛 IllegalStateException
    external fun parsePptxNative(path: String): String
}

// document/src/main/java/me/rerere/document/DocxParser.kt 改成：
object DocxParser {
    fun parse(file: File): String = try {
        OfficeParserNative.parseDocxNative(file.absolutePath).ifBlank { fallbackJvmParse(file) }
    } catch (e: Throwable) {
        fallbackJvmParse(file)
    }
    private fun fallbackJvmParse(file: File): String { /* 原 JVM 实现，保留为兜底 */ }
}
```

### 3.2 Rust 实现要点

- `zip` crate 解 .docx 包
- `quick-xml::Reader` 流式解析 `word/document.xml`（docx）/ `ppt/slides/slide*.xml`（pptx）
- 复用 JVM 现有的结构标记规则（list bullet / heading prefix）保持输出兼容
- 错误转 Result<String, OfficeParserError>，JNI 层 panic-catch 包装

### 3.3 退出标准

| 指标 | 阈值 |
|---|---|
| **加速比**（5MB docx, cold） | ≥ 3x |
| **加速比**（30MB pptx, cold） | ≥ 3x |
| **输出等价**（10 个真实 docx + 10 pptx 样本）| 跟 JVM 实现的 String 输出 diff ≤ 5%（允许空白/换行微差，结构应一致）|
| **峰值内存**（30MB docx 解析）| -30% 或更好 |
| **binary 增量**（3 架构总和）| ≤ 800 KB |

---

## 4. Component #2 — markdown-parser

### 4.1 API 设计

```kotlin
// app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownParserNative.kt
internal object MarkdownParserNative {
    init { System.loadLibrary("markdown_parser") }

    /** 返回 packed binary AST，由 PackedAstNode 解码 */
    external fun parseMarkdownNative(text: String): ByteArray
}

// PackedAstNode lazy view：
class PackedAstNode internal constructor(
    private val blob: ByteArray,
    private val nodeOffset: Int,
    val parent: PackedAstNode? = null,
) {
    val type: PackedNodeType by lazy { /* read u8 from blob */ }
    val startOffset: Int by lazy { /* varint */ }
    val endOffset: Int by lazy { /* varint */ }
    val children: List<PackedAstNode> by lazy { /* 解码 children 子树 */ }
    // ... 模仿 org.intellij.markdown.ast.ASTNode 接口
}
```

### 4.2 packed binary AST 格式

```
header (8 bytes):
  magic     : 4 bytes 'PMDA'
  version   : u8     (= 1)
  flags     : u8     (bit 0: has_html_blocks)
  reserved  : u16

string_pool_offset : u32  (绝对偏移，指向 string pool 区域，里面存所有 raw text 切片)
node_count         : u32  (节点总数)

nodes[] (depth-first):
  tag           : u8       (PackedNodeType 枚举，见 4.3)
  start_offset  : varint   (原始文本中的起始字节偏移)
  end_offset    : varint   (delta from start, varint)
  extras_len    : u8       (额外属性的字节数；0 = 无)
  extras        : bytes    (因 tag 而异: heading level / code lang / link href / table align ...)
  children_count: varint   (子节点数；0 = leaf)
  // 子节点紧跟其后，按 depth-first 顺序
```

**varint**: standard LEB128

### 4.3 PackedNodeType 枚举（映射 pulldown-cmark Tag）

| Tag | Code | 说明 |
|---|---|---|
| ROOT | 0 | 根节点 |
| PARAGRAPH | 1 | 段落 |
| HEADING | 2 | 标题（extras: level u8）|
| BLOCKQUOTE | 3 |
| CODE_BLOCK | 4 | 代码块（extras: lang utf-8）|
| LIST_ORDERED | 5 | 有序列表（extras: start u32）|
| LIST_UNORDERED | 6 | 无序列表 |
| LIST_ITEM | 7 |
| TABLE | 8 | 表格（extras: align bytes）|
| TABLE_HEAD | 9 |
| TABLE_ROW | 10 |
| TABLE_CELL | 11 |
| HORIZONTAL_RULE | 12 |
| HTML_BLOCK | 13 |
| THEMATIC_BREAK | 14 |
| EMPHASIS | 30 | inline |
| STRONG | 31 | inline |
| STRIKETHROUGH | 32 | inline (GFM) |
| LINK | 33 | inline（extras: href + title utf-8）|
| IMAGE | 34 | inline（extras: src + alt）|
| INLINE_CODE | 35 | inline |
| INLINE_HTML | 36 | inline |
| MATH_INLINE | 37 | GFM math extension |
| MATH_BLOCK | 38 | GFM math extension |
| FOOTNOTE_REF | 39 |
| FOOTNOTE_DEF | 40 |
| TASK_LIST_MARKER | 41 | GFM 任务列表 [x] |
| TEXT | 100 | 叶子节点 raw text |
| SOFT_BREAK | 101 |
| HARD_BREAK | 102 |

### 4.4 退出标准

| 指标 | 阈值 |
|---|---|
| **冷 parse 4KB markdown** | ≤ 1.5 ms（vs JVM 3-5ms, ≥2x） |
| **streaming tail parse <1KB** | ≤ 0.6 ms（vs JVM 1-2ms, ≥2x） |
| **大消息 30KB** | ≤ 15 ms（vs JVM 30-50ms, ≥2x）|
| **输出等价** | 100 个真实 markdown 样本，top-level block 数量/类型/offset 完全匹配 JVM |
| **JNI overhead 单次** | ≤ 0.1 ms |
| **binary 增量**（3 架构总和）| ≤ 1.2 MB |
| **Markdown migration 状态** | 必须等 Markdown.kt → MarkdownNew.kt 迁移收敛 |

---

## 5. Component #3 — highlight-parser

### 5.1 API 设计

```kotlin
// highlight/src/main/java/me/rerere/highlight/HighlighterNative.kt
internal object HighlighterNative {
    init { System.loadLibrary("highlight_parser") }

    /** 返回 packed binary token list，解码为 List<HighlightToken> */
    external fun highlightNative(code: String, language: String): ByteArray

    external fun supportedLanguages(): Array<String>
}
```

### 5.2 packed binary token 格式

```
header (8 bytes):
  magic    : 4 bytes 'PHLT'
  version  : u8
  flags    : u8
  reserved : u16

token_count : varint
string_pool_offset : u32

tokens[]:
  kind         : u8        (0 = Plain, 1 = StringContent, 2 = StringListContent, 3 = Nested)
  start        : varint
  length       : varint
  type_ref     : varint    (索引进 type 字符串池，例如 "keyword" "string" "comment" ...)
  // 因 kind 而异:
  //   0: 无额外字段（content 通过 start/length 切原文）
  //   1: 同上
  //   2: lines_count: varint, line_offsets: varint × N
  //   3: nested_count: varint, [子 token 同 schema 递归]
```

### 5.3 tree-sitter highlight scope → Prism 命名映射

tree-sitter-highlight 默认 scope（例如 `function.builtin` / `string.special` / `comment`）→ 映射到当前 `HighlightToken.Token.type` 用的 Prism 命名（`function` / `string` / `comment` / `keyword` / `number` / `operator` / `punctuation`）。

精确映射表见 `native/highlight-parser/src/scope_mapping.rs`。

### 5.4 退出标准

| 指标 | 阈值 |
|---|---|
| **首次 highlight 200 行 Kotlin** | ≤ 5 ms（vs QuickJS 30-50ms, ≥6x）|
| **稳态 highlight 200 行** | ≤ 3 ms |
| **输出等价**（5 语言 × 5 样本 = 25 个对照）| token 类型 ≥80% 匹配（允许 scope 命名细微差异）|
| **支持语言** | 至少 10 个常用语言可用 |
| **binary 增量**（含 14 个 grammar，3 架构总和）| ≤ 8 MB |

---

## 6. 执行流程（每个组件）

```
1. Rust crate 实现
   - Cargo.toml + src/lib.rs + JNI bindings
   - 单元测试（cargo test 本地跑）
2. Kotlin adapter
   - Native loader + external 声明
   - packed binary decoder（如适用）
   - fallback 到 JVM 实现的路径
3. Gradle 集成
   - rust-android-gradle plugin 配置
   - cargo build → AAR 包含多架构 .so
4. Compile gate
   - ./gradlew :document:assembleDebug --offline (POC 模块)
   - 三架构 .so 都生成
5. 单元测试 + 行为等价测试
   - JUnit on Android Studio runtime
   - 跑 5-10 个真实样本，diff JVM vs Rust 输出
6. benchmark
   - 真实 corpus × cold/warm × JVM/Rust × 3 设备级 (Pixel 6 / Mi 10 / 旧机 SM-A105)
7. sub-agent Round 1 review
   - 用 general-purpose agent 跑独立 review
   - 检查 byte-equal 行为 / 边界 case / JNI 安全 / 内存泄漏 / 多架构覆盖
8. Debug loop
   - Round 1 列出的 P2+ 风险一一修复
   - Round 2 review 直到 0 P2+ 风险
9. Commit + push（用 spike branch，不合 main）
10. 下一组件
```

---

## 7. Sub-agent Review Template

每个组件完成后用以下 prompt：

```
独立 review Rust JNI 组件 <name>（spike/rust-native-components 分支）。

Repo: /Users/arquiel/Downloads/AI/rikkashit/rikkahub-refactor-godclass/
Branch: spike/rust-native-components
Commit: <sha>

What this component did: <描述>
Acceptance criteria:
  - 加速比阈值: <X>x
  - 输出等价: <Y>个样本
  - binary 增量: <Z>KB

Verify hard rules:
1. **JNI 安全**:
   - panic_catch 包住整个 JNI 入口
   - GetStringUTFChars / ReleaseStringUTFChars 配对
   - 返回 byte array 用 NewByteArray 而非保留 Rust 内存指针
   - 多架构 .so 都生成
2. **行为等价**: 跑 corpus 对比 JVM/Rust 输出，diff 比例 ≤ 阈值
3. **fallback path**: 如果 native 抛或返回空，必须能走 JVM fallback 不崩
4. **错误处理**: panic → catch → Java RuntimeException（不 unwind 过 FFI 边界）
5. **内存**: 长时间循环调用无内存泄漏（valgrind/AddressSanitizer 可选）
6. **binary size**: APK 大小增量符合预期
7. **API 兼容**: Kotlin adapter 跟原 JVM 接口签名一致或上层调用点不需改

Output format:
ROUND <N> COMPONENT <NAME> — <APPROVE | NITS | REQUEST CHANGES>
<rule-by-rule PASS/FAIL>
<P0/P1/P2/P3 风险列表>
<recommendation>
```

**P 等级定义**：
- **P0**: app 启动崩 / 数据损坏 / native segfault / 完全无 fallback
- **P1**: 主要功能行为漂移 / 性能不达标超过 50%
- **P2**: 边界 case 输出差异 / 内存泄漏疑似 / 某架构不支持
- **P3**: 命名规范 / 注释缺失 / 测试覆盖偏少

**循环退出条件**：0 个 P2+ 风险。

---

## 8. 总体退出条件

Spike 工作分两层 gate：

### 8.1 Phase 1（结构性 spike）— 当前阶段

完成意味着：
- ✅ 3 个组件都过了 sub-agent Round N APPROVE（0 P2+ 风险）
- ✅ `cargo check --workspace` clean
- ✅ `cargo test --workspace` 全 pass（host 端）
- ✅ 跨组件全面 review 完成；找到的 cross-cutting 问题已修
- ✅ 3 个 Android module（`document/` / `highlight/` / `app/`）都接了 rust-android-gradle plugin（即每个 .so 都会跟着 module assemble 入 APK）

Phase 1 关心**代码与架构正确性**：JNI 安全、fallback 完整、wire format 一致、生产无崩、向后兼容路径清晰。

### 8.2 Phase 2（acceptance gate）— 后续阶段

数据/性能/真实运行验证：
- ⏳ 真实 corpus（`native/<component>/tests/corpus/`）建库：每组件 5-10 个真实样本
- ⏳ JVM/Rust 输出 diff harness：office 输出字符串等价 / markdown AST 节点 N 对得上 / highlight scope ≥80% 一致
- ⏳ benchmark harness：单 device 真实测速，验证 SPIKE_PLAN §3.3/§4.4/§5.4 的加速比阈值
- ⏳ AAB release build size 增量在 `arm64-v8a` 上量出来跟预期匹配
- ⏳ CI job 显式断言 cargo-ndk 真产出了 `.so`（防止当前 `onlyIf` 守卫让 CI "绿但没打 .so"，Codex Round 3 follow-up）
- ⏳ SPIKE_REPORT.md：决策会用

### 8.3 ⛔ Phase 2 **HARD GATE** — 接入生产前必须满足

**只要任一 production caller 切到 native 路径（即 `DocxParser.kt` /
`MarkdownNew.kt` / `Highlighter.kt` / `replaceRegexes` 在调 `nativebridge.*Native`
而不是当前的 JVM 实现），必须在合 PR **之前** 满足以下所有条件**：

1. **Feature flag / kill switch**：BuildConfig 或运行时 setting（如
   `agentRuntime.useNativeRust = false` 默认 false），可由用户 / 远端配置 /
   实验 framework 单向切回 JVM 而不需要发新版
2. **默认走 JVM**：feature flag 默认值是 `false` / "jvm"，灰度时 opt-in
3. **灰度启用**：按 device cohort / user id hash / build variant 分批
   启用（先 Notion variant → Debug variant → 5% 用户 → 25% → 100%）
4. **Crashlytics 接入**：native panic / load failure / output divergence
   单独 tag，dashboard 可分流监控
5. **观测**：JVM vs Rust 输出 diff 在生产用 sampling 抓回来做事后分析
6. **Revert plan**：合 main 走 merge commit；接入生产的 PR 也走 merge commit。
   任何 incident 都可以 `git revert <merge>` 单步回滚（合并方式正是为此选 merge）

**不达标的部分**：单独 abandon 该组件，不影响其他组件。Phase 2 acceptance
不过的话 **spike 也不会自动落地到生产**——本 spike 合 main 的 PR
保持 zero-侵入（`DocxParser.kt` 等 0 行改动），所有 Kotlin adapter 写法都
lazy load + fallback to JVM，即使 `.so` 缺失也不崩。

### 8.4 本次合 main PR 的边界

**这次合 main 的范围**（PR #9）：
- ✅ 4 个 Rust crate 落 `native/`
- ✅ 4 个 Kotlin nativebridge adapter 落 `nativebridge/` 子包
- ✅ 3 个 Android module 的 build.gradle.kts 加 hand-rolled `cargo-ndk` Exec task
- ✅ docs/ + native/Cargo.toml 等基础设施
- ✅ `panic = "unwind"` 等 release profile 配置

**这次合 main **明确不做** 的**：
- ❌ 任何 production caller 切换到 native 路径
- ❌ feature flag 接入
- ❌ Crashlytics 接入
- ❌ 任何 byte-for-byte 等价性 / benchmark / 真机数据证据

→ **合 main 后 native 路径默认不被调用**。这是 Phase 1 structural prototype，
不是产品功能。任何后续切换都走 §8.3 hard gate。

---

## 9. Phase 2（不在本 spike 范围内）

下面这些是 spike 通过后才考虑的，**当前不做**：

- 接入生产渲染层（Markdown.kt 用 Rust parser 替换 JetBrains 调用）
- BuildConfig feature flag A/B 切流量
- Crashlytics / 性能监控接入
- 多架构 .so 体积优化（strip debug symbols / `panic_immediate_abort` / `lto = "fat"`）
- 在 Codex 主 worktree 同步分支

---

## 10. 状态跟踪

### Phase 1 (结构性 spike) — 完成（含扩张）

第一波 3 组件 + 第二波 4 组件（其中 1 abandoned）= 共 7 组件提议；4 active + 1 abandoned + 2 extension (epub 并入 office、HTML emit 并入 markdown)。

| 节点 | 状态 | 备注 |
|---|---|---|
| 0. 环境（NDK 27.0.12077973 / Rust 1.95 / 3 targets / cargo-ndk 4.1.2）| ✅ | |
| 1. Plan doc + 项目结构 | ✅ | 本文档 |
| 2. native/ Cargo workspace | ✅ | 5 crate（4 active + 1 abandoned）|
| 3. Component #1 office-parsers (Docx+Pptx) | ✅ | Round 2 NITS |
| 4. Component #2 markdown-parser (packed AST) | ✅ | Round 4 APPROVE |
| 5. Component #3 highlight-parser | ✅ | Round 2 APPROVE |
| 6. **Component #4 EPUB**（合入 office-parsers）| ✅ | Round 3 APPROVE，含 CJK round-trip test |
| 7. **Component #6 regex-transformer**（新 crate）| ✅ | Round 2 NITS，含 Java↔Rust 正则方言文档 |
| 8. **Component #7 generative-widget-parser**（新 crate）| ❌ **ABANDONED** | Round 1 P0 结构 mismatch（见 native/generative-widget-parser/ABANDONED.md）|
| 9. **Component #8 Markdown→HTML**（合入 markdown-parser）| ✅ | Round 2 APPROVE，含 GFM autolink + safelink |
| 10. Kotlin adapters × 4 | ✅ | nativebridge 子包，lazy load + fallback to JVM；nullable T? 统一 unavailability sentinel |
| 11. Gradle plugin 集成 | ✅ | document / highlight / app 接 Mozilla rust-android-gradle；app 额外 hand-rolled `cargoBuildRegexTransformer` task 给 regex-transformer 用 |
| 12. Sub-agent review × 5 entry points | ✅ | 循环到 0 P2+ |
| 13. 跨组件全面 review | ✅ V1 + V2 都过 |
| 14. `cargo test --workspace` 全 pass | ✅ | 55/55（office 26 + markdown 16 + highlight 6 + regex 7）|

### Phase 2 (acceptance gate) — 待开

| 节点 | 状态 | 备注 |
|---|---|---|
| A. corpus（每组件 5-10 真实样本） | ⏳ pending | |
| B. JVM/Rust 等价性 diff harness | ⏳ pending | 跑 corpus 比对输出 |
| C. benchmark harness（真机测速）| ⏳ pending | 验证 §3.3/§4.4/§5.4 阈值 |
| D. 3-variant Android assemble 验证 | ⏳ pending | Debug/Notion/Refactortest |
| E. APK size 增量量化 | ⏳ pending | arm64-v8a 真实数据 |
| F. SPIKE_REPORT.md | ⏳ pending | 决策会输出 |
| G. 接入生产渲染层（feature flag）| ⏳ pending | 仅在 Phase 2 数据达标后 |
