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

### 8.3 ⚠ 历史"HARD GATE" — personal-use 项目降级为"insurance"

> **状态变更（2026-05-21，Phase 2/3 合 main 前夕）**：
> 本节最初的 6 条 HARD GATE 是按 enterprise rollout 起的草——但 rikkahub 是
> 单用户项目，"5% → 25% → 100% 灰度 + Crashlytics dashboard 监控"
> 这套流程没有承接的人和承接的指标。所以执行版降级如下：
> - ❌ `默认 JVM` / `灰度启用 5%→25%→100%` — **不要求**。用户单一，
>   `NativePathPrefs` 4 个 user-facing flag default **`true`**（Rust 默认开）
> - ✅ `Feature flag / kill switch` — **保留**。`NATIVE_PATH_*` per-key
>   + RC `native_path_kill_switch` 全局 → 出问题能 RC console 一键关，
>   或 user-level 写 DataStore opt-out
> - ✅ `Crashlytics 接入` — **保留**，零额外维护成本，出事能查
> - ✅ `JVM vs Rust diff sampling` — **保留**但默认 `sampleRate=0`，
>   想抓数据时调高，平时不烧 Crashlytics quota
> - ✅ `Revert plan via merge commit` — **保留**。合 main 必须 merge
>   commit，单步 `git revert <merge>` 可回滚整个 Phase 2/3

下面是历史原文，标作 deprecated 留作以后多用户化场景的参考：

~~**只要任一 production caller 切到 native 路径（即 `DocxParser.kt` /
`MarkdownNew.kt` / `Highlighter.kt` / `replaceRegexes` 在调 `nativebridge.*Native`
而不是当前的 JVM 实现），必须在合 PR 之前满足以下所有条件**：~~

1. ~~**Feature flag / kill switch**：BuildConfig 或运行时 setting~~ → 仍要求
2. ~~**默认走 JVM**~~ → **personal-use 不要求**
3. ~~**灰度启用**（先 Notion variant → Debug variant → 5% 用户 → 25% → 100%）~~ → **personal-use 不要求**
4. ~~**Crashlytics 接入**~~ → 仍要求
5. ~~**观测**：JVM vs Rust 输出 diff sampling~~ → 仍要求，但默认 0%
6. ~~**Revert plan**~~ → 仍要求

**仍然不可让步的**：fallback 设计。所有 Kotlin adapter `lazy load + fallback to
JVM`，即使 `.so` 缺失或 panic 也不崩；这是 personal-use 默认开的前提条件。

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

## 9. Phase 2（结构 + 5 个 caller switch）— **完成于 branch**

> Status: 2026-05-21，`phase2/rust-native-production-switch`，**未合 main**。
> +349/-32 行，6 模块改 + 6 新文件，6 轮 sub-agent review 收敛到 0 P3+。
> 默认全 JVM（5 flag false + sampleRate 0 + kill switch false），用户 0 行为变化。

实做项（personal-use 版 §8.3 — 4 条 insurance 全保留 + 2 条 enterprise-only 撤销）：

- ✅ Feature flag / kill switch — `NativePathPrefs` 5 个 boolean + RC `native_path_kill_switch`
- ✅ **默认 Rust 全开**（personal-use 决定）— `NativePathPrefsData` 4 个 user-facing flag default `true`；DataStore key 缺失时 `readFrom` 也 fallback 到 `true`。`markdownAst` 留 `false` 因为它是 shadow-only 没用户价值
- ✅ Crashlytics — `NativePathFailure` / `NativePathDivergence` 子类（race-free，per-event payload 走 message 不走全局 custom key）
- ✅ Diff sampling — 每 Switch 内置 sample rate 门控
- ✅ Revert plan — merge commit 模式合 main 即可单步 `git revert <merge>`

5 个 caller switch：

| Step | 文件 | switch 后行为 |
|---|---|---|
| 1 office | DocumentAsPromptTransformer + WorkspaceArtifactTools | `OfficeNativeSwitch.parseXOrNull(file) { jvm } ?: jvm` |
| 2 highlight | Highlighter.kt — 原 body 抽到 `highlightJvm()` | concurrency profile change：JVM 单线程 executor，native parallel |
| 3 regex | Assistant.kt `String.replaceRegexes` + 私有 `applyRegexesJvm` | `isEnabled()` gate 防热路径 lambda alloc |
| 4 markdown HTML | MarkdownNew.kt `generateMarkdownHtml` + 私有 `generateMarkdownHtmlJvm` | HTML diff sampling **硬关**（`HTML_DIFF_ENABLED=false`） |
| 5 markdown AST | Markdown.kt `parsePreprocessedMarkdownUncached` + `maybeShadowCompareNativeAst` | **shadow-compare only**：renderer 仍吃 JVM tree |

⚠ Step 5 故意不切 renderer：Markdown.kt 还在 migration（10+ commits/月），切 renderer 会爆 conflict。Phase 2 只装 telemetry，真正 renderer 切换留给 Phase 3-B。

## 10. Phase 3（用户感受加速 + 工程债收尾）— 待开

Phase 2 让 native 路径**能跑**但默认不跑。Phase 3 干两件事：(1) **真的让用户感受到加速** —— 灰度开 flag + Markdown.kt renderer 真正消费 packed AST；(2) **还工程债** —— xlsx Rust 化、抽 jni-common、HTML normalizer。

### A. 灰度开 flag（运营层，无代码）

**前置条件**：§10-C 的 HTML normalizer 必须先 ship 并把 `HTML_DIFF_ENABLED` 翻为 true；否则 markdownHtml flag 任何灰度都没有 divergence 数据可看。office / highlight / regex / markdownAst 不受此约束，可独立灰度。

1. Dogfood 1+ 周：开发机 5 flag 全 true，sampleRate=0.05，每天看 Crashlytics 是否有 `NativePathFailure` / `NativePathDivergence`
2. Notion variant 5-10 人内测，48h+ 无新 issue
3. RC ramp 5% → 25% → 100%，每档 48h+
4. 配套：markdownHtml 灰度到 ≥25% 且 48h 无 divergence 后启动 `CharReveal.kt:BALANCED` 从 80ms 继续下探的实验（D-3 收集数据）

### B. Markdown.kt renderer 切到 packed AST

**前提**：Markdown.kt streaming-render migration 收敛 ≥ 2 周（最近一次相关 commit 时间 + 14d 才能动）。

- ~1700 行 Markdown.kt 里所有 `ASTNode.children.find { type == X }` 走查 → 改为 `PackedAstNode` walker
- 写 JVM `IElementType` ↔ Rust `NodeType` 全量映射表（30+ 节点 + extras 字段：链接 url / image alt / code lang / table column alignments）
- 删 JetBrains markdown artifact（Gradle 依赖 `libs.markdown` → `com.github.JetBrains:markdown`；包 `org.intellij.markdown.*`），APK 估省 ~1MiB
- **跑全套测试**——任何 `test/` 文件 `import org.intellij.markdown.*` 都要先迁移或删除

### C. HTML 等价归一化器 + 翻 `HTML_DIFF_ENABLED`

JetBrains HtmlGenerator 与 pulldown-cmark 在 whitespace / attr 顺序 / entity 转义上不等价 → 直接 `==` 比较会产生大量假阳性 → Phase 2 把 `HTML_DIFF_ENABLED` 硬关了。

Phase 3-C 写一个 `normalizeHtml(s: String): String`（Jsoup 解析 + canonical 序列化 + entity 归一 + 自闭合标签统一），两边 normalize 后再比较，然后翻 `HTML_DIFF_ENABLED=true`。这是开 markdownHtml 灰度的前置数据条件。

### D. 工程债收尾

| 编号 | 项 | 工作量 |
|---|---|---|
| D-1 | **xlsx via `calamine`（新格式，无 JVM baseline）**：在 office-parsers crate 加 `parseXlsxNative` + Switch 扩展 `parseXlsxOrNull` + WorkspaceArtifactTools `xlsx` 分支切走 Rust。无既有 JVM `XlsxParser`——这是新功能，**没有 byte-equivalence diff**，靠 corpus + 输出 schema 单元测试验证 | 大 |
| D-2 | 抽 `native/jni-common/` Rust crate：LEB128 + panic_to_string + init_logger_once 4 crate 共享 | 中 |
| D-3 | CharReveal `BALANCED` baseRevealDurationMs 调优实验数据：当前已下调至 80ms，markdownHtml 灰度到 ≥25% 且 48h 无 divergence 后启动下探到 50ms 的灰度数据收集 | 小 |
| D-4 | Macrobenchmark + APK size baseline（§8.2 acceptance gate）。**Phase 3 退出门槛**：arm64-v8a APK size 增量 ≤ +5 MiB（Phase 1 baseline +3.95 MiB，给 D-1 xlsx 留 +1 MiB 头寸） | 大，需真机 |
| D-5 | Phase 2 PR 拆 6 个 commit（infra + 5 step）便于独立 review | 中，git 操作 |
| D-6 | 删 JetBrains markdown 依赖（依赖 B 完成）| 小 |
| D-7 | **Per-component kill switch**：当前只有一个 `native_path_kill_switch`，触发即全停。Phase 3 加 5 个 `native_kill_*` per-component RC key；保留全局 kill switch 作为最高优先级 OR-门 | 小 |

### Phase 3 退出条件

- 5 个 native flag 在生产 100% 开启完成 **1 个完整 release cycle 且未触发 kill switch**（任意 per-component 或全局）
- markdownAst 不仅 shadow compare，且 renderer 真正消费 packed AST，`org.intellij.markdown` 依赖移除
- xlsx 走 Rust 路径——所有 `WorkspaceArtifactTools` 的 office 入口（docx/pptx/epub/xlsx）行为一致经过 Switch
- jni-common 抽完，4 crate 0 重复（grep `LEB128` / `panic_to_string` / `init_logger_once` 在 office/markdown/highlight/regex 各 crate 应为 0 命中）
- `HTML_DIFF_ENABLED=true` 在生产开启后 ≥ 48h 连续采样，divergence 报告稳定 < 0.1%
- arm64-v8a APK size 增量 ≤ +5 MiB（vs Phase 0 baseline `595e8414`）。若 D-1 xlsx 实测占用 >+1 MiB，重审阈值（Phase 1 占 +3.95 MiB，给 D-1 留 +1 MiB 头寸；超出说明 calamine 比预期大，需要评估缩减策略或抬阈值）
- SPIKE_REPORT.md 输出，与 §8.2 acceptance gate 全部对齐

---

## 11. 状态跟踪

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

### Phase 2 (basic infra + 5 caller switches) — 完成于 branch（未合 main）

| 节点 | 状态 | 备注 |
|---|---|---|
| Step 0 pre-flight (NativePathBootstrap + Prefs + Crashlytics + CI .so 校验) | ✅ | 6 轮 review，0 P3+ |
| Step 1 office caller switch | ✅ | DocumentAsPromptTransformer + WorkspaceArtifactTools，2 轮 review |
| Step 2 highlight caller switch | ✅ | Highlighter.kt 重构，2 轮 review |
| Step 3 regex caller switch | ✅ | Assistant.kt `replaceRegexes`，2 轮 review |
| Step 4 markdown HTML caller switch | ✅ | MarkdownNew.kt，HTML diff sampling 硬关，2 轮 review |
| Step 5 markdown AST shadow compare | ✅ | Markdown.kt 加 `maybeShadowCompareNativeAst`，renderer 仍吃 JVM，2 轮 review |
| Final cross-component sweep | ✅ | 3 轮 review，收敛到 0 P0/P1/P2/P3 |
| 合 main + 推 remote | ⏳ pending | 需要先真机 dogfood |

### Phase 3 (用户感受加速 + 工程债) — 进行中

| 节点 | 状态 | 备注 |
|---|---|---|
| A. 灰度开 flag（dogfood → 5%→25%→100%） | ⏳ pending | 运营层，无代码；markdownHtml 依赖 C — 现可启动（C 已完成） |
| B. Markdown.kt renderer 切到 packed AST | ✅ | renderer consumes packed AST behind MdNode interface (TD.Rust.1a); markdownAst flag default false pending dogfood; parity rig 2 samples green / 30 documented divergences (`MarkdownTreeParityTest`，全部归因于 2 个真实 renderer bug：native heading 渲染空 + native inline code 保留反引号，见测试 KDoc) |
| C. HTML normalizer + flip `HTML_DIFF_ENABLED` | ✅ | `HtmlDiffNormalizer.kt` (17 tests) + `HTML_DIFF_ENABLED=true`，2 轮 sub-agent review |
| D-1 xlsx via calamine | ✅ | calamine 0.26 + Howard Hinnant date conv + 12 tests + Switch hard-gate sampling，2 轮 sub-agent review |
| D-2 抽 native/jni-common crate | ✅ | `panic_to_string` + `init_logger_once!` macro + `write_varint` 4 crate 共享 + rust-version 1.75→1.76，2 轮 sub-agent review |
| D-3 CharReveal BALANCED 80→50 调优 | ⏳ blocked | 依赖 A markdownHtml ≥25% 灰度 48h |
| D-4 Macrobenchmark + APK size baseline | ⏳ pending | 需真机 |
| D-5 Phase 2 PR 拆 6 commit | ⏳ pending | git 操作，需用户授权 |
| D-6 删 JetBrains markdown 依赖 | ⏳ blocked | 依赖 B |
| D-7 per-component kill switch | ⏳ pending | 5 个 `native_kill_*` RC key |
| §11 SPIKE_REPORT.md | ⏳ pending | Phase 3 出口产物 |
