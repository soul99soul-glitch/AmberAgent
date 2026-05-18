# ChatInputComposers.kt 拆分规划（pre-动手）

> 状态：**等用户 GO**。本文档仅供 planning，不入 git。
> 上下文：ChatInput god class 第 3 刀。前 2 刀（Usage/Attachments）已完成、Round 2 APPROVE。

## 1. Block 边界

| 字段 | 值 |
|---|---|
| 当前 ChatInput.kt 行数 | 2551 |
| 移走范围 | **L1793-1795（3 行常量）+ L1836-2551（716 行）= 总 ~720 行** |
| 目标新文件 | `ChatInputComposers.kt` |
| 拆完 ChatInput.kt 预估 | ~1830 行（−720）|

## 2. 17 个符号 + 4 个常量盘点

### Internal（1 个，主 ChatInput 在用）
| 符号 | 行 | 签名 |
|---|---:|---|
| `TextInputRow` | 1836 | `(ChatInputState, () -> Unit × 3)` — 入口 composable |

### File-private（其余 16 + 4 常量）

**Composables（块内自洽，0 外部 caller）**：
- `SlashCommandPanel` (2108) — 命令面板（TextInputRow 调）
- `SlashCommandRow` (2153) — 单行（Panel 调）
- `SlashCommandEmptyRow` (2205) — 空状态（Panel + Mention 调）
- `QuickMessageButton` (2215) — TextField leadingIcon
- `SlashCommandLeadingMark` (2287) — "/" 小标
- `MentionPanel` (2466) — @role 面板
- `MentionRow` (2499) — Mention 单行

**类型**：
- `sealed interface SlashCommandAction` (2312) — ClearInput / CompactContext / OpenUsage / InsertText
- `data class SlashCommandItem` (2319) — 命令项数据
- `data class MentionContext` (2433) — @ 位置 + query

**纯逻辑函数**：
- `String.slashCommandQuery()` (2306)
- `buildSlashCommandItems(...)` (2329)
- `filterSlashCommandItems(...)` (2406)
- `SkillMetadata.toSlashCommandPrompt()` (2424)
- `String.escapeForPromptString()` (2427)
- `detectMentionContextFor(text, cursor)` (2440)
- `ChatInputState.replaceMention(context, roleId)` (2459)
- `QuickMessage.slashTitle(fallback)` (2545)

**常量**：
- `MAX_SLASH_COMMANDS = 9` (1793)
- `MAX_SLASH_COMMAND_TITLE_CHARS = 32` (1794)
- `DYNAMIC_SLASH_COMMAND_MIN_QUERY_CHARS = 2` (1795)
- `MAX_MENTIONS = 9` (2430)

## 3. 外部依赖（同 package，已 internal）

| 依赖 | 来源 | 用途 |
|---|---|---|
| `FullScreenEditor` | `ChatInputAttachments.kt`（已拆）| TextInputRow.L2101 调 |
| `MentionRoleItem` | `MentionRoles.kt`（已存在）| MentionPanel/Row 入参 |
| `buildMentionRoleItems` | 同上 | TextInputRow.L2009 调 |
| `filterMentionRoleItems` | 同上 | TextInputRow.L2008 调 |

✅ 全是 `internal`、同 package — 自动可见无需 import。

## 4. 外部 caller（仅 1 个）

```
ChatInput.kt 主 composable → TextInputRow(state, onSendMessage, onUsageClick, onCompactContext)
```

精确行号拆完后才能 confirm（当前 ChatInput.kt 内的 `TextInputRow` 调用在 L? — 等抽完再 grep）。

## 5. Compose-specific 风险点（按等级）

### 🟡 中风险
- **`TextInputRow` 内部 var state**：
  - `var isFocused by remember { mutableStateOf(false) }` (L1895)
  - `var isFullScreen by remember { mutableStateOf(false) }` (L1896)
  - 两个都是 TextInputRow 本地状态，搬家不动 → 风险 = 0。但要确认搬过去后 `onFocusChanged { isFocused = it.isFocused }` 还能改到自己。
- **`AnimatedVisibility` 用全限定名**：
  - L1954 `androidx.compose.animation.AnimatedVisibility(...)` + L1956 `androidx.compose.animation.fadeIn(...)` + slide/tween 同模式
  - 选择：**保留 FQN**（与原始字节对齐，零风险）vs 改成 import（diff 更乱）
  - 决定：**保留 FQN**

### 🟢 低风险
- `ReceiveContentListener` 闭包（L1917-1952）：captures `state` + `settings.displaySetting.*` + `filesManager`。`remember` key 是 `pasteLongTextAsFile` + `pasteLongTextThreshold` — 保字节对齐
- `produceState(enabledSkills, ...)` (L1851)：withContext(Dispatchers.IO) 异步加载 skills
- `state.textContent.text.toString()` (L1897, 1993)：每次重组都重新读 — 必须保持

### 🟢 零风险
- 所有 helper 数据类、enum、纯函数（pure logic）— 字节对齐复制

## 6. Internal vs Private 边界

| 符号 | 当前 | 新文件 visibility |
|---|---|---|
| `TextInputRow` | private | **`internal`** |
| 其余 16 + 4 常量 | private | **保持 `private`**（file-private 到新文件）|

✅ 比 Usage / Attachments 都干净 — 只有 **1 个** internal 符号要 promote。

## 7. 必删的 dead imports（拆完后）

需要 post-strip 后逐个 grep 确认。预计候选（出现在被移走代码、需要 verify 全文不再用）：
- `FilesManager`, `SkillManager`, `SkillMetadata`, `QuickMessage`, `SubAgentMode`
- `getCurrentAssistant`, `getQuickMessagesOfAssistant`
- 各种 `androidx.compose.animation.core.tween` 子包（如果 FQN 保留就不导入）
- `MediaType`, `ReceiveContentListener`, `consume`, `contentReceiver`, `hasMediaType`
- `TextFieldLineLimits`, `KeyboardOptions`, `ImeAction`
- `onFocusChanged`
- `LocalSoftwareKeyboardController` (?)

注意：很多 import 也用在 ChatInput 主 composable 别的地方。**逐个 grep 验证后再删**。

## 8. 验证清单

1. [ ] `awk` strip + 新文件写入
2. [ ] `:app:compileDebugKotlin --offline` 过
3. [ ] dead import 清理（grep verify）+ 二次 compile
4. [ ] `:app:testDebugUnitTest` baseline 仍是 6
5. [ ] sub-agent review round 1 → 修 → round 2
6. [ ] push
7. [ ] （可选）assembleNotion + assembleRefactortest

## 9. Pattern 复用度

| 维度 | 与 Attachments 比较 |
|---|---|
| 块大小 | 720 行 vs 503 行（大 43%）|
| Internal 符号数 | 1 vs 5（**少 80%** — 边界更干净）|
| 状态管理 | 中（var isFocused/isFullScreen + AnimatedVisibility）vs 低（无 var 局部状态） |
| 外部跨文件依赖 | FullScreenEditor / MentionRoles（都 internal 同包）vs 无 |
| 动画 | AnimatedVisibility × 2 用 FQN vs 无 |
| 整体风险 | 🟡 中 vs 🟢 低 |

**结论**：方法和 Attachments 一样可行，但要小心 AnimatedVisibility FQN + 复用 state 的两个 `var`。

## 10. 建议路径

**A. 直接动手**：分析已经够细，pattern 与前两刀一致，可以直接 strip + write + compile + commit。
**B. 先做最小 PoC**：先只搬最简单的 `SlashCommandLeadingMark` (1 个 composable, 18 行) 确认包间引用工作正常，再搬大块。
**C. 拆细更多**：把 720 行再切成 ChatInputSlash.kt + ChatInputMentions.kt + ChatInputTextRow.kt 三块（每块更小但 commit 更多）。

我倾向 **A** — 17 个符号 + 4 常量边界已经验证，主块（TextInputRow）必须整体搬否则 var state 会断开。前 2 刀实证 pattern 可靠。
