# Deep Read claim 级来源标注设计

- 日期：2026-06-19
- 分支：feature/council-host-orchestration-tools
- 状态：已批准，待写实现计划
- 依赖：第一轮质量硬化（簇 A/B/C，docs/superpowers/specs/2026-06-19-deep-read-quality-hardening-design.md）已实现

## 背景与动机

当前 Deep Read 的正文 claim 和来源链接是脱钩的：timeline/corePoints/perspectives/summary 等结构化正文里，每个具体陈述（如"定价 4999 元"）没有指向具体来源的入口；读者看到 claim 时无法知道它出自哪条来源、是否真有出处。`references`/`extendedReading` 是整篇文章共用的全局列表，无法定位到单条 claim。

对一个主打"事实核查、杂志深度阅读"的功能，这是信任机制的核心缺失。本设计为 claim 添加可点击的来源胶囊，让读者能从具体陈述跳转到支撑它的来源。

## 核心决策（用户已确认）

1. **复用聊天页来源胶囊机制**：聊天页已有完整实现——模型在 markdown 正文写普通链接 `[文字](url)`，`MarkdownNew` 渲染时查 `LocalSearchSources` registry，URL 命中则渲染成胶囊（`appendSearchSourcePill`），点击打开浏览器。DeepRead 正文已走 `MarkdownNew`，复用这套渲染，UI 零成本。
2. **挂载方式：正文嵌 markdown 链接**：模型在 claim 正文里直接写 `[来源](真实URL)`。不新增 writer schema 字段——claim 已是 markdown 字符串，模型直接在字符串里嵌链接。
3. **挂载范围：summary/timeline/corePoints/perspectives**（不含 quotes——引用语嵌链接不自然）。
4. **白名单不命中：整个 `[文字](url)` 删除**（不留纯文本，避免残留误导）。胶囊只渲染真实抓取过的来源，读者看到的胶囊 100% 可信。
5. **点击行为：直接打开浏览器**（用现有 `openHttpUrl`）。
6. **匹配粒度：host 级**（复用 `SearchSourcesRegistry.lookup` 的 host 匹配）。
7. **渲染路径：只做 Compose 原生**（EditorialSlant HTML 模板不支持胶囊，沿用第一轮决定）。
8. **非阻断**：模型不挂链接或挂错 URL，不影响段落 READY 判定，不影响产出率。这是与已否决的 VERIFYING 验真的本质区别。

## 与验真的区别（为什么这套可行而验真不可行）

| | 验真（已否决） | claim 标注（本设计） |
|---|---|---|
| 失败处理 | URL 不在白名单 → 整段判 FAILED，阻断生成 | URL 不在白名单 → 该链接静默删除，claim 照常显示 |
| 模型要求 | 必须逐条核对 evidence_excerpt | 只需在 claim 顺手挂 URL |
| 对产出率影响 | 崩溃（阻断式） | 零影响（非阻断式） |

## 改动分层

### 层 1 — 渲染注入（UI，零模型改动）

**目标**：让 DeepRead 的 Compose 正文渲染树具备来源胶囊能力。

**改动点**：

1. **构建 SearchSourcesRegistry 并注入**（`DeepReadScreen.kt`）：
   - 从 `output.references` + `output.extendedReading` 的 URL 集合构建 `SearchSourcesRegistry`（复用 `SearchPresentation.kt` 的 `SourceRef` + `normalizeSearchSourceHost`）。
   - 用 `CompositionLocalProvider(LocalSearchSources provides registry)` 包裹 `DeepReadArticle`（Compose 原生渲染路径）。
   - registry 为空（无来源）时不注入（胶囊功能自然不生效）。

2. **DeepReadMarkdownText 支持 onClickUrl**（`DeepReadScreen.kt:840`）：
   - 加 `onClickUrl: (String) -> Unit` 参数，透传给 `MarkdownNew`。
   - 各调用处（`HeroTextBlock`/`TimelineSection`/`CorePointsSection`/`PerspectiveRow`/`AnalysisSection`）从外层取 `uriHandler`，传 `openHttpUrl(uriHandler, url)`。

3. **严格删除未验证链接**（`MarkdownNew.kt`）：
   - DeepRead 只走 `MarkdownNew`（HTML 渲染路径），不碰旧的 `Markdown.kt`。
   - 新增 CompositionLocal `LocalStripUnverifiedLinks: Boolean`（默认 false）。
   - 在 `MarkdownNew.kt` 的链接渲染分支（`"a"` 分支，约 `:965`）：
     - 当 `LocalStripUnverifiedLinks.current == true` 且链接既不是 citation 也不命中 `searchSources` → **不 append 任何内容**（整个 `[文字](url)` 消失）。
     - 当 `LocalStripUnverifiedLinks.current == false`（聊天页默认）→ 沿用现有行为（渲染普通蓝色超链接）。
   - DeepRead 渲染树里 `CompositionLocalProvider(LocalStripUnverifiedLinks provides true)` 开启严格模式。
   - **关键约束**：默认 false，聊天页行为完全不变；只有 DeepRead 显式开启。

### 层 2 — prompt + writer（模型引导，非阻断）

**目标**：引导模型在 claim 正文嵌来源链接。

**改动点**：

1. **DeepReadPrompt 各 stage**（`DeepReadPrompt.kt`）：
   - OVERVIEW/NARRATIVE/ANALYSIS 的 stage prompt 加指引：
     > "每个关键 claim 在句末用 `[来源](真实URL)` 标注来源，URL 必须来自本段证据包中出现的来源。链接文字用来源名或'来源'。把链接作为句末附加标记，不要写成句子的必要成分（如写'定价4999元[报道](url)'，不要写'详见[此报道](url)了解'）。"
   - EXTENDED_READING 不加（它本身就是链接列表）。
   - 主 prompt `build()`（非分段路径）也加同样指引。

2. **writer tool schema 不改**：
   - overview 的 `summary`、narrative 的 `timeline[].event`/`corePoints[].point`、analysis 的 `perspectives[].viewpoint` 等已是字符串字段，模型直接在字符串里嵌 markdown 链接。
   - writer 的清洗逻辑（`cleanText`/`safeTake`）对含链接字符串的截断安全性见层 3。

3. **writer 门槛不动**：
   - 段落 READY 判定（`hasOverviewContent`/`hasNarrativeContent`/`hasAnalysisContent`）基于内容长度，不检查是否含链接。
   - 模型不挂链接 → 段落仍能 READY，只是正文没胶囊。

4. **非阻断验证**：
   - 不引入新的 writer 校验、不引入 `verify_claims`、不改 READY 判定。
   - 链接的白名单过滤纯粹在渲染层（层 1），不在写入层。

### 层 3 — writer 清洗的安全性（边界处理）

`cleanText`/`safeTake` 对含 markdown 链接的字符串的截断风险：

- `cleanText(max)` 内部调 `safeTake(max)`，按字符数截断。
- 若 claim 正文较长（如 summary 接近 `OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1200`），截断点可能落在 `[文字](url)` 中间，产生残缺链接（如 `[报道](https://exam`）。
- 残缺链接渲染时：`MarkdownNew` 解析失败 → 当作纯文本显示残缺字符（不会崩，但不美观）。

**处理**：在 `cleanText` 截断后，修复未闭合的 markdown 链接——若截断点在 `[...](` 之后但 `)` 之前，回退到 `[` 之前截断（丢弃整个残缺链接）。这是 defensive 处理，实际触发概率低（claim 正文通常远小于存储 cap）。

## 数据流

```
预抓来源 (DeepReadSource[])
  → output.references + output.extendedReading (URL 集合)
  → SearchSourcesRegistry (host → SourceRef)
  → LocalSearchSources (CompositionLocal)
  → MarkdownNew 链接渲染查 registry
  → 命中: appendSearchSourcePill (胶囊, 点击 openHttpUrl)
  → 未命中 + LocalStripUnverifiedLinks: 整个链接删除
  → 未命中 + 聊天页: 普通蓝色超链接 (不变)
```

## 测试策略

### 层 1 渲染
- **新增** SearchSourcesRegistry 构建测试：从 references/extendedReading URL 集合正确构建 host 索引。
- **新增** 链接渲染行为测试（若可 instrumented）：
  - 命中 registry 的链接 → 渲染胶囊
  - 未命中 + 严格模式 → 链接消失
  - 未命中 + 默认模式 → 普通超链接（聊天页行为不变）
- **回归** 聊天页消息渲染：确认 `LocalStripUnverifiedLinks` 默认 false 不影响聊天页。

### 层 2 prompt
- **修改** `DeepReadPromptWordingTest`：确认各 stage prompt 含来源标注指引。
- 无 writer schema 变化，writer 测试不新增。

### 层 3 清洗
- **新增** `cleanText`/`safeTake` 对含链接字符串截断的测试：
  - 截断点在链接外 → 链接完整保留
  - 截断点在链接内 → 残缺链接被修复（回退到 `[` 前）

## 风险与缓解

1. **模型配合度**（主要风险）：弱模型可能不嵌链接，或嵌的 URL host 不在预抓集合。结果：claim 没胶囊。**可接受**（非阻断，claim 照常显示）。缓解：prompt 明确指引 + 给来源 URL 列表。
2. **断句风险**：模型若把链接写成句子必要成分（"详见[此报道]了解"），删除后断句。缓解：prompt 明确要求句末附加标记式。实际影响小，且即使断句也只是丢一个标签词，不影响主干事实。
3. **EditorialSlant 模板用户无溯源**：用 HTML 模板渲染时，链接显示成普通超链接（无白名单过滤、无删除）。**可接受**——模板是排版增强，溯源功能主路径是 Compose 原生。
4. **MarkdownNew 共享组件侵入**：加 `LocalStripUnverifiedLinks` 是唯一动聊天页共享组件的地方。**关键约束**：默认 false，聊天页行为完全不变；DeepRead 显式开启。需回归测试聊天页。
5. **host 匹配的宽松性**：同 host 不同路径的 URL 都会命中（预抓 A 文章，模型嵌同站 B 文章也渲染胶囊）。**可接受**——同 host 至少是真实站点，比瞎编的强；精确匹配需改渲染逻辑，成本不值。

## 非目标（明确排除）

- 段落级流式（`StreamChunk` event 接通）——下一轮独立 spec。
- VERIFYING 验真（覆盖度校验 + claim 级证据核对）——已否决。
- EditorialSlant HTML 模板的来源胶囊支持——模板路径不做。
- 精确 URL 匹配（只做 host 级）。
- references 反向链接到用它支撑的 claim（点击 reference 高亮对应 claim）——未来增强。
- claim 标注的覆盖率统计/质量指标——未来增强。
