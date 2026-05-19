# P2 第六刀 pre-cut: ChatMessage.kt

> **Playbook**: `docs/REFACTOR_PLAYBOOK.md`（事实源；规则/流程/教训）
> **不重复 playbook 内容**；本文只给本刀的 decl 表、call graph、widening 表、stage plan、decisions。

---

## 0. 元信息

| | |
|---|---|
| 目标 | `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt` |
| 原始行数 | 1634 |
| 风险 | 🟡 中（结构清晰但子-Composable 互调密集；MessagePartsBlock 408 行带 `@OptIn(FlowPreview::class)`） |
| baseline 测试 | 3 failed / 467（GenerativeUiPlannerTest×2 + DefaultProvidersTest×1） |
| 基线 commit | `3809e414`（main HEAD：含 P2 第五刀 + 005a449f compaction fix） |
| 分支 | `refactor/p2-chatmessage` |
| 预计 stage | 4 |
| 预计最终 coord 行数 | ~250 |

---

## 1. Top-level decl 表（扩版 grep）

| L | Decl | Vis | 角色 | Stage |
|---|---|---|---|---|
| 119–132 | `fun List<UIMessagePart>.hasRenderableChatMessageContent()` | private | helper（fun ChatMessage + CMVI Content 调用） | coord（widen→internal） |
| 135–308 | `fun ChatMessage(...)` | public | 公共入口 | **coord** |
| 319–320 | `fun Modifier.animateContentSizeIf(enabled): Modifier =` | private | Modifier ext（fun ChatMessage + CMVF 调用） | coord（widen→internal） |
| 322–323 | `@Suppress("UNUSED_PARAMETER") fun Modifier.streamingContentSize(enabled): Modifier = this` | private | Modifier ext（仅 MessagePartsBlock 调用） | **Stage 4** |
| 326–341 | `fun rememberChatMessageTextStyle(): TextStyle` | private | helper（fun ChatMessage + CMVI Content 调用） | coord（widen→internal） |
| 343 | `const val ChatMessageVirtualMarkdownMinChars = 600` | internal | 仅 shouldVirtualize 用 | **Stage 3** |
| 344 | `const val ChatMessageVirtualMarkdownMinTableLines = 3` | private | 同上 | **Stage 3** |
| 345 | `val MarkdownTableLineRegex = Regex(...)` | private | 同上 | **Stage 3** |
| 412–434 | `fun MessageNode.chatMessageVirtualizationPrewarmTexts(...)` | internal | **dead**（0 caller anywhere） | **Stage 3**（不删，跟 shouldVirtualize 同迁） |
| 436–524 | `fun buildChatMessageVirtualItems(...)` | internal | cross-file: ChatListSupport + ChatListNormalSection | **Stage 3**（保 internal） |
| 526–532 | `fun shouldVirtualizeMarkdownContent(content): Boolean` | private | buildVI + prewarm + CMVI Content 用 | **Stage 3** |
| 534/535–707 | `@Composable internal fun ChatMessageVirtualItemContent(...)` | internal | cross-file: ChatListNormalSection | **Stage 3**（保 internal） |
| 709/710–776 | `@Composable fun VirtualizedAssistantText(...)` | private | CMVI Content 用 | **Stage 2**（widen→internal） |
| 778/779–833 | `@Composable fun AssistantMarkdownBlockOrWidgets(...)` | private | VAT + AssistantBubbleSegment + MessagePartsBlock 用 | **Stage 2**（widen→internal） |
| 835/836–880 | `@Composable fun ReasoningWidgetRescue(...)` | private | MessagePartsBlock 用 | **Stage 2**（widen→internal） |
| 882–886 | `fun String.reasoningWidgetSource(): String?` | internal | 仅 RWR 用，跨工程 0 caller | **Stage 2**（保 internal，byte-equal 不主动收窄） |
| 888/889–990 | `@Composable fun AssistantBubbleSegment(...)` | private | 仅 VAT 用 | **Stage 2**（保 private，同 sibling） |
| 992/993–1108 | `@Composable fun ChatMessageVirtualFooter(...)` | private | 仅 CMVI Content 用 | **Stage 3**（紧跟 CMVI Content） |
| 1110/1111–1119 | `@Composable fun TraceChatComposable(section, content)` | private | 仅 MessageSelectionContainer 用 | **Stage 1**（保 private，同 sibling） |
| 1121/1122–1130 | `@Composable fun MessageSelectionContainer(content)` | private | VAT + MessagePartsBlock 用 | **Stage 1**（widen→internal） |
| 1132/1133–1156 | `@Composable fun rememberClickCitationHandler(parts)` | private | VAT + MessagePartsBlock 用 | **Stage 1**（widen→internal） |
| 1158/1159–1223 | `@Composable fun MessageAnnotations(annotations, loading)` | private | CMVF + MessagePartsBlock 用 | **Stage 1**（widen→internal） |
| 1225/1226/1227–1634 | `@OptIn(FlowPreview::class) @Composable fun MessagePartsBlock(...)` | private | fun ChatMessage + CMVI Content 用 | **Stage 4**（widen→internal） |

---

## 2. Call graph（in-file）

```
fun ChatMessage (L135)
 ├─ rememberChatMessageTextStyle (L326)
 ├─ hasRenderableChatMessageContent (L119)
 ├─ MessagePartsBlock (L1227)
 └─ animateContentSizeIf (L319)

chatMessageVirtualizationPrewarmTexts (L412)
 └─ shouldVirtualizeMarkdownContent (L526)          [dead chain — 0 caller]

buildChatMessageVirtualItems (L436)
 └─ shouldVirtualizeMarkdownContent (L526)

ChatMessageVirtualItemContent (L535)
 ├─ rememberChatMessageTextStyle (L326)
 ├─ hasRenderableChatMessageContent (L119)
 ├─ shouldVirtualizeMarkdownContent (L526)
 ├─ MessagePartsBlock (L1227)
 ├─ VirtualizedAssistantText (L710)
 └─ ChatMessageVirtualFooter (L993)

VirtualizedAssistantText (L710)
 ├─ rememberClickCitationHandler (L1133)
 ├─ MessageSelectionContainer (L1122)
 ├─ AssistantBubbleSegment (L889)
 └─ AssistantMarkdownBlockOrWidgets (L779)

ReasoningWidgetRescue (L836)
 └─ reasoningWidgetSource (L882)

AssistantBubbleSegment (L889)
 └─ AssistantMarkdownBlockOrWidgets (L779)

ChatMessageVirtualFooter (L993)
 ├─ MessageAnnotations (L1159)
 └─ animateContentSizeIf (L319)

MessageSelectionContainer (L1122)
 └─ TraceChatComposable (L1111)

MessagePartsBlock (L1227)
 ├─ rememberClickCitationHandler (L1133)
 ├─ ReasoningWidgetRescue (L836)
 ├─ MessageSelectionContainer (L1122)
 ├─ streamingContentSize (L323)
 ├─ AssistantMarkdownBlockOrWidgets (L779)
 └─ MessageAnnotations (L1159)
```

---

## 3. Cross-file caller scan

| Symbol | Cross-file callers | 决策 |
|---|---|---|
| `fun ChatMessage` | AssistantPromptPage.kt L304, ChatListNormalSection.kt L894 | 保 `public`（byte-equal） |
| `buildChatMessageVirtualItems` | ChatListSupport.kt L111, ChatListNormalSection.kt L815 | 保 `internal` |
| `ChatMessageVirtualItemContent` | ChatListNormalSection.kt L1015 | 保 `internal` |
| `ChatMessageVirtualMarkdownMinChars` | 0 | 保 `internal`（byte-equal，不主动收窄） |
| `chatMessageVirtualizationPrewarmTexts` | 0（dead） | 保 `internal`（不删，byte-equal） |
| `reasoningWidgetSource` | 0 | 保 `internal`（byte-equal，不主动收窄） |
| `hasRenderableChatMessageContent` | 0 | 留 coord，widen `private→internal`（Stage 3 CMVI Content 跨 sibling 用） |
| `MarkdownTableLineRegex` | 0 | 保 `private` |

---

## 4. ⚠ 同文件 cross-stage caller scan（playbook §2.1 #4）

每个抽出块对其他抽出块/coord 残留的依赖：

### 4.1 Coord 残留 helpers 被 sibling 调用 → 必须 widen `private→internal`

| Coord 残留 (L) | 被哪些 sibling 调用 | widen 时机 |
|---|---|---|
| `hasRenderableChatMessageContent` (L119) | Stage 3 CMVI Content (L568) | **Stage 3** |
| `rememberChatMessageTextStyle` (L326) | Stage 3 CMVI Content (L559) | **Stage 3** |
| `animateContentSizeIf` (L319) | Stage 3 CMVF (L1046) | **Stage 3** |

### 4.2 Sibling decl 被其他 sibling 调用 → 必须 sibling 内 widen `private→internal`

| Sibling decl (L, stage) | 跨 sibling 被谁调 | widen 时机 |
|---|---|---|
| `MessageSelectionContainer` (L1122, Stage 1) | Stage 2 VAT + Stage 4 MessagePartsBlock | **Stage 1** |
| `rememberClickCitationHandler` (L1133, Stage 1) | Stage 2 VAT + Stage 4 MessagePartsBlock | **Stage 1** |
| `MessageAnnotations` (L1159, Stage 1) | Stage 3 CMVF + Stage 4 MessagePartsBlock | **Stage 1** |
| `VirtualizedAssistantText` (L710, Stage 2) | Stage 3 CMVI Content | **Stage 2** |
| `AssistantMarkdownBlockOrWidgets` (L779, Stage 2) | Stage 4 MessagePartsBlock | **Stage 2** |
| `ReasoningWidgetRescue` (L836, Stage 2) | Stage 4 MessagePartsBlock | **Stage 2** |
| `MessagePartsBlock` (L1227, Stage 4) | Stage 3 CMVI Content + coord fun ChatMessage | **Stage 4** |

### 4.3 Sibling 内 callsite （保 private）

| Sibling decl | 仅被同 sibling 调用 | 保留 vis |
|---|---|---|
| `TraceChatComposable` (Stage 1) | Stage 1 MessageSelectionContainer | private |
| `AssistantBubbleSegment` (Stage 2) | Stage 2 VAT | private |
| `reasoningWidgetSource` (Stage 2) | Stage 2 RWR | internal（pre-existing，byte-equal 不动） |
| `shouldVirtualizeMarkdownContent` (Stage 3) | Stage 3 prewarm + buildVI + CMVI Content | private |
| `ChatMessageVirtualMarkdownMinChars` (Stage 3) | Stage 3 shouldVirtualize | internal（pre-existing，byte-equal 不动） |
| `ChatMessageVirtualMarkdownMinTableLines` (Stage 3) | Stage 3 shouldVirtualize | private |
| `MarkdownTableLineRegex` (Stage 3) | Stage 3 shouldVirtualize | private |
| `chatMessageVirtualizationPrewarmTexts` (Stage 3) | 0 (dead) | internal（pre-existing dead，byte-equal 不动） |
| `ChatMessageVirtualFooter` (Stage 3) | Stage 3 CMVI Content | private |
| `streamingContentSize` (Stage 4) | Stage 4 MessagePartsBlock | private |

### 4.4 widening 汇总（10 个 p→i 计）

| 位置 | 数 | 说明 |
|---|---|---|
| Stage 1 sibling | 3 | MessageSelectionContainer / rememberClickCitationHandler / MessageAnnotations |
| Stage 2 sibling | 3 | VirtualizedAssistantText / AssistantMarkdownBlockOrWidgets / ReasoningWidgetRescue |
| Stage 3 coord | 3 | hasRenderableChatMessageContent / rememberChatMessageTextStyle / animateContentSizeIf |
| Stage 4 sibling | 1 | MessagePartsBlock |
| **合计** | **10** | |

---

## 5. Stage plan

### Stage 1 — `ChatMessageCommon.kt`（~120 行）

抽出范围（含 @Composable 上方注释/annotation，按 closing `}` 整块抽）：
- L1110 `@Composable` + L1111–1119 `TraceChatComposable` → 保 private
- L1121 `@Composable` + L1122–1130 `MessageSelectionContainer` → **widen internal**
- L1132 `@Composable` + L1133–1156 `rememberClickCitationHandler` → **widen internal**
- L1158 `@Composable` + L1159–1223 `MessageAnnotations` → **widen internal**

抽出后 coord 残留：CMVF (L992-1108) + MessagePartsBlock (L1225-1634) 都还在 coord，跨 stage 调用 MSC/RCC/MA 走 sibling internal。

### Stage 2 — `ChatMessageRenderers.kt`（~280 行）

抽出范围：
- L709 `@Composable` + L710–776 `VirtualizedAssistantText` → **widen internal**
- L778 `@Composable` + L779–833 `AssistantMarkdownBlockOrWidgets` → **widen internal**
- L835 `@Composable` + L836–880 `ReasoningWidgetRescue` → **widen internal**
- L882–886 `String.reasoningWidgetSource` → 保 internal（byte-equal）
- L888 `@Composable` + L889–990 `AssistantBubbleSegment` → 保 private

抽出后 coord 残留：CMVI Content（Stage 3）+ MessagePartsBlock（Stage 4）调 VAT/AMBOW/RWR 走 internal。

### Stage 3 — `ChatMessageVirtualItems.kt`（~420 行）

抽出范围：
- L343 `const val ChatMessageVirtualMarkdownMinChars`（internal，byte-equal 不动）
- L344 `const val ChatMessageVirtualMarkdownMinTableLines`（private）
- L345 `val MarkdownTableLineRegex`（private）
- L412–434 `chatMessageVirtualizationPrewarmTexts`（internal dead，byte-equal 不动；不删）
- L436–524 `buildChatMessageVirtualItems`（internal，保留）
- L526–532 `shouldVirtualizeMarkdownContent`（private）
- L534 `@Composable` + L535–707 `ChatMessageVirtualItemContent`（internal，保留）
- L992 `@Composable` + L993–1108 `ChatMessageVirtualFooter`（private，仅 CMVI Content 用）

**同步 coord widening**（physical residence stays in coord but vis widens）：
- `hasRenderableChatMessageContent` (L119) `private→internal`
- `rememberChatMessageTextStyle` (L326) `private→internal`
- `animateContentSizeIf` (L319) `private→internal`

抽出后 coord 残留：fun ChatMessage + 3 widened helpers + MessagePartsBlock (L1225-1634)。

### Stage 4 — `ChatMessageMessagePartsBlock.kt`（~410 行）

抽出范围：
- L322 `@Suppress("UNUSED_PARAMETER")` + L323 `streamingContentSize`（仅 MessagePartsBlock 用，跟着搬，保 private）
- L1225 `@OptIn(FlowPreview::class)` + L1226 `@Composable` + L1227–1634 `MessagePartsBlock`（**widen internal**）

⚠ `@OptIn(FlowPreview::class)` **逐字保留**，`import kotlinx.coroutines.FlowPreview` 单独 import 到 sibling。Body 字节相等。

⚠ `@Suppress("UNUSED_PARAMETER")` 跟 streamingContentSize 走，行为不变。

最终 coord 残留：
- imports（剩多少看 stage 累计 orphan 清理）
- L119–132 `hasRenderableChatMessageContent`（internal）
- L135–308 `fun ChatMessage`（public）
- L310–318 comments（animateContentSizeIf 历史，byte-equal 跟着 helper 走，留在 coord）
- L319–320 `animateContentSizeIf`（internal）
- L326–341 `rememberChatMessageTextStyle`（internal）

预计 **~250 行**。

---

## 6. 风险

- 🟡 中风险：子-Composable 互调密集（10 个 widening），但全在同 package（`me.rerere.rikkahub.ui.components.message`），internal 安全。
- 🟢 ChatList 已是 sibling 5 个文件，反向 caller `buildChatMessageVirtualItems` / `ChatMessageVirtualItemContent` 保 internal 即可，不需要改 ChatList sibling 的 import。
- 🟡 `@OptIn(FlowPreview::class)` 注解抽出时要严格逐字保留 + sibling 单独 import。
- 🟢 dead 函数 `chatMessageVirtualizationPrewarmTexts` 不删，只搬。
- 🟢 注释块 L310–318（animateContentSizeIf 历史 caveat）跟 helper 物理同居，留 coord（helper 留 coord）。L1167 / L1287 / L1361 等流式相关注释跟 MessagePartsBlock 走 Stage 4。
- 🟡 Modifier 同名属性 import 风险（P2 第五刀教训 #4）：本刀涉及 `offset/size/width/clickable` 等 Modifier 扩展时，stage cleanup 不能仅 grep symbol——按 `Modifier.symbol(...)` 或后置 compile gate 兜底。
- 🟡 PR 前主动 merge main（P2 第五刀教训 #5）：Stage 4 完工后 `git fetch github-private main && git merge github-private/main`，conflict 在本 PR 内解决。

---

## 7. 给用户的决策点

| # | 决策 | 推荐 | 理由 |
|---|---|---|---|
| 1 | Stage 数 | **4** | 边界清晰，每个 stage 独立可 review |
| 2 | Stage 顺序 | **Common → Renderers → VirtualItems → MessagePartsBlock** | helper 物理 owner 先抽（playbook 教训），cross-stage widening 单调向后 |
| 3 | dead `chatMessageVirtualizationPrewarmTexts` | **跟 Stage 3 搬，不删** | CLAUDE.md §3 + playbook §1 #4 byte-equal |
| 4 | dead `internal const ChatMessageVirtualMarkdownMinChars` / `internal fun reasoningWidgetSource` | **保 internal，不主动收窄** | byte-equal 默认 |
| 5 | PR 策略 | **单 PR 4 commit + `--merge`** | playbook §8 |
| 6 | import 排序 | **严格 byte-equal**（不动顺序） | playbook §5 |

---

## 8. 每 stage 关卡

每 stage 必须通过：
1. `compileDebugKotlin --offline` 通过
2. `testDebugUnitTest --offline` = 3/467 baseline
3. sub-agent Round 1 review（playbook §6 模板）
4. Round 1 APPROVE → push；REQUEST CHANGES → 修后 Round 2

Stage 4 后追加：
5. 3-variant assemble (`assembleDebug + assembleNotion + assembleRefactortest` --offline) 全 BUILD SUCCESSFUL
6. `git fetch github-private main && git merge github-private/main`（即使 fast-forward 也确认 main 无 drift）
7. Codex independent review prompt（playbook §7）
8. Codex APPROVE → `gh pr create` + `--merge` 合 main（合前用户确认）

---

## 9. 待用户 GO

pre-cut 完，**先不动手**，等用户 `GO` 进 Stage 1。
