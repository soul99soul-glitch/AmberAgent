# Deep Read claim 级来源标注 — 实现计划

- 日期：2026-06-19
- 对应 spec：`docs/superpowers/specs/2026-06-19-deep-read-claim-sourcing-design.md`
- 分支：feature/council-host-orchestration-tools

## 执行原则

- claim 标注复用聊天页胶囊机制，UI 渲染零成本，主要工作是注入 + 开关 + prompt。
- 改动顺序：层 1（渲染注入）→ 层 2（prompt）→ 层 3（截断修复）。
- 每个 commit 编译通过（环境无 JDK，靠静态 grep 穷举验证 + subagent review）。

---

## 阶段 1 — 层 1：渲染注入

### 步骤 L1.1：新增 LocalStripUnverifiedLinks CompositionLocal
- `app/src/main/java/app/amber/feature/ui/components/message/SearchPresentation.kt`：
  ```kotlin
  internal val LocalStripUnverifiedLinks = compositionLocalOf<Boolean> { false }
  ```
  放在 SearchPresentation.kt（与 LocalSearchSources 同处，语义相关）。默认 false = 聊天页行为不变。

### 步骤 L1.2：MarkdownNew 的链接渲染分支接入开关
- `MarkdownNew.kt` 的 `appendHtmlInlineElement`（`:909`）`"a"` 分支（`:1006` 的 `href.isNotEmpty()` arm）：
  - 该函数非 @Composable，需把 `stripUnverified: Boolean` 作为参数传入。
  - 改 arm：
    ```kotlin
    href.isNotEmpty() -> {
        if (stripUnverified) {
            // Deep Read strict mode: unverified links disappear entirely.
            // Neither pill nor plain text — the whole [text](url) is dropped.
        } else {
            val linkStyle = SpanStyle(...)
            withLink(openUrlLinkAnnotation(href, onClickUrl)) {
                withStyle(linkStyle) { recurseChildren(...) }
            }
        }
    }
    ```
- 参数传播：`appendHtmlInlineElement` 加 `stripUnverified` 参数 → 调用方 `appendHtmlInlineNode`（找它的定义）加参数 → 一路上溯到 @Composable 层（`HtmlInlineAsComposable` / `HtmlInlineGroup` 等），在那里读 `LocalStripUnverifiedLinks.current` 并传入。
- **grep 全链路**：搜索所有调用 `appendHtmlInlineElement` / `appendHtmlInlineNode` 的地方，逐一加参数。这是本步最繁琐的部分，必须穷尽，否则编译失败。

### 步骤 L1.3：MarkdownNew 顶层读 LocalStripUnverifiedLinks 并下传
- `MarkdownNew` 主函数（`:158`）读 `val stripUnverified = LocalStripUnverifiedLinks.current`，传给下游所有渲染入口。
- 确认所有从 MarkdownNew 主函数到 `appendHtmlInlineElement` 的路径都传到了。

### 步骤 L1.4：DeepReadMarkdownText 透传 onClickUrl
- `DeepReadScreen.kt` 的 `DeepReadMarkdownText`（`:840`）：
  - 加 `onClickUrl: (String) -> Unit = {}` 参数，传给 `MarkdownNew`。
  - 各调用处（HeroTextBlock/TimelineSection/CorePointsSection/PerspectiveRow/AnalysisSection）从外层取 uriHandler 传 `openHttpUrl(uriHandler, url)`。
  - 注意：这些 composable 当前不持有 uriHandler。需要在 `DeepReadArticle` 层取 `LocalUriHandler.current`，作为参数下传到各 section，或用 CompositionLocal。倾向参数下传（显式）。

### 步骤 L1.5：DeepReadArticle 注入 SearchSourcesRegistry + 开启严格模式
- `DeepReadScreen.kt` 的 `DeepReadArticle`（Compose 原生渲染路径）：
  ```kotlin
  val uriHandler = LocalUriHandler.current
  val sourceRegistry = remember(output) { buildDeepReadSourceRegistry(output) }
  CompositionLocalProvider(
      LocalSearchSources provides sourceRegistry,
      LocalStripUnverifiedLinks provides sourceRegistry.isNotEmpty,
  ) {
      // 现有 LazyColumn 内容
  }
  ```
- `buildDeepReadSourceRegistry(output)`：从 `output.references + output.extendedReading` 的 URL 构建 `SearchSourcesRegistry`。复用 SearchPresentation.kt 的 `SourceRef` + `normalizeSearchSourceHost`。registry 为空时不开启严格模式（避免无来源时所有链接被删）。

### 步骤 L1 commit 边界
- L1.1-L1.5 合为一个 commit `feat(deep-read): inject source registry and strip unverified links in Compose path`。

---

## 阶段 2 — 层 2：prompt 指引

### 步骤 L2.1：DeepReadPrompt 各 stage 加来源标注指引
- `DeepReadPrompt.kt`：
  - `buildStage` 的 OVERVIEW/NARRATIVE/ANALYSIS 阶段要求（在现有"- 输出合法 JSON"附近）加：
    > "关键 claim 在句末用 `[来源名](真实URL)` 标注来源，URL 必须来自本段证据包。链接作为句末附加标记，不要写成句子必要成分（如'定价4999元[报道](url)'，不要写'详见[此报道](url)了解'）。"
  - EXTENDED_READING 不加（它本身是链接列表）。
  - `build()`（主 prompt）也加同样指引。
- 验证：`DeepReadPromptWordingTest` 现有断言不含此文案，不会破坏。可选加一个 assertTrue 断言 prompt 含"来源"指引。

### 步骤 L2 commit 边界
- L2.1 单独 commit `feat(deep-read): prompt model to embed source links in claims`。

---

## 阶段 3 — 层 3：cleanText 截断修复

### 步骤 L3.1：截断后修复未闭合 markdown 链接
- `DeepReadSectionWriterTools.kt` 的 `safeTake`（或 `cleanText` 调 safeTake 之后）：
  - 截断后检查字符串是否含未闭合的 `[...](...)`：
    - 找最后一个 `[`，若其后有 `](` 但无配对的 `)` → 回退到最后一个 `[` 之前截断。
    - 找最后一个 `[`，若其后无 `](` → `[` 是普通字符，不处理。
  - 实现：在 `cleanText` 的 `safeTake(max)` 之后加 `repairTruncatedMarkdownLink()`。
- 验证：新增单测——截断点在链接外（完整保留）、在链接内（回退）。

### 步骤 L3 commit 边界
- L3.1 单独 commit `fix(deep-read): repair truncated markdown links after cleanText cutoff`。

---

## 阶段 4 — 静态验证

### 步骤 V1：穷举检查
- grep 所有 `appendHtmlInlineElement` / `appendHtmlInlineNode` 调用点，确认 stripUnverified 参数都传到。
- grep `DeepReadMarkdownText` 所有调用点，确认 onClickUrl 传到（或默认值兜底）。
- 确认 `LocalStripUnverifiedLinks` 默认 false，聊天页未开启。

### 步骤 V2：subagent review
- 派 subagent 静态审查，重点查参数传播链完整性、CompositionLocal 默认值、registry 构建正确性。

---

## 风险节点

| 步骤 | 风险 | 缓解 |
|---|---|---|
| L1.2 | appendHtmlInlineElement 参数传播链长，漏传编译失败 | grep 穷举 + subagent review |
| L1.5 | registry 构建的 host 归一化与 SearchSourcesRegistry.lookup 一致 | 复用 normalizeSearchSourceHost |
| L3.1 | 截断修复逻辑边界（嵌套括号、多个链接） | 单测覆盖；实际 claim 罕见多链接 |
| 全局 | 聊天页行为变化 | LocalStripUnverifiedLinks 默认 false，仅 DeepRead 开启 |

## 明确排除
- EditorialSlant HTML 模板的来源胶囊（模板路径不做）
- 段落流式（下一轮）
- VERIFYING 验真（已否决）
