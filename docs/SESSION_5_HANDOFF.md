# Session #5 P1 God-class Refactor — Handoff (for /compact recovery)

> 写于 2026-05-18，会话压缩前。读完这个文档就能从 ChatInputSandbox 拆分继续工作。

---

## 0. 上下文一句话

P1 god-class 拆分进行中。Session #3 拆完 LocalTools，Session #4 拆完 SystemAccessTools + WebMountPrimitiveTools，Session #5 正在拆 ChatInput（3657 行）：已完成 Usage / Attachments / Composers 3 刀，**还剩最后一刀 ChatInputSandbox**。

---

## 1. 工作环境

- **Worktree**：`/Users/arquiel/Downloads/AI/rikkashit/rikkahub-refactor-godclass/`（**禁区**：主目录 `rikkahub/` 是 Codex 的，不要碰）
- **分支**：`refactor/p1-godclass`
- **远端**：`github-private`，最新 push `a62e30d4`
- **Java**：`/opt/homebrew/opt/openjdk@17`，每次跑 gradle 前 `export JAVA_HOME=... PATH=...`
- **Gradle**：必须加 `--offline`（代理 127.0.0.1:7897 经常挂）
- **Baseline 单测**：427 tests, **6 failed**（GenerativeUiPlannerTest×2, ContextFootprintEstimatorTest×3, DefaultProvidersTest×1）— 任何刀拆完必须保持 6/427

---

## 2. 累积成果（Session #3+#4+#5）

| 文件 | 起始 | 目前 | sibling 文件 | 减少 |
|---|---:|---:|---:|---:|
| LocalTools.kt | 1093 | 189 | 14 | -83% |
| SystemAccessTools.kt | 1441 | 93 | 13 | -93% |
| WebMountPrimitiveTools.kt | 1604 | 108 | 7 | -93% |
| **ChatInput.kt** | **3657** | **1811** | **3**（Usage/Attachments/Composers）| **-50%** |

ChatInput 剩 Sandbox 一刀，预估再 -800 ~ -1000 行。

---

## 3. Session #5 commit 列表（按时间）

```
713b4a83 refactor(p1): extract WebMountPrimitiveShared.kt
9635b336 refactor(p1): extract WebMountTabsTools.kt + WebMountCaptureTools.kt
967f2aca refactor(p1): extract WebMountNavigationTools.kt
c63afd70 refactor(p1): extract WebMountInteractionTools.kt
ff342bbe refactor(p1): extract WebMount Group C — Fetch + Site mgmt
b67d8ccc test(p1): WebMountBridgeRefProtocolTest scans the whole webmount/tools/ package
effdd0ef refactor(p1): extract ChatInputUsage.kt — context indicator + reasoning chip + usage sheet
6c5021b7 chore(p1): drop 9 dead imports from ChatInput.kt after Usage extraction
f7ef1061 refactor(p1): extract ChatInputAttachments.kt — chips, pickers, editor, cropper
a62e30d4 refactor(p1): extract ChatInputComposers.kt — TextInputRow + slash panel + @mention panel
```

合 main 时 squash 也好、保留也行 — 全 review 通过。

---

## 4. 硬规则（不能违反）

1. **Worktree 隔离**：只动 `rikkahub-refactor-godclass/`，不动主 `rikkahub/`
2. **不 amend**：commit 完只能加新 commit（per CLAUDE.md）
3. **不动不理解**（CLAUDE.md §3）：dead import 清理只清这次拆分搬走的，**不顺手清 pre-existing dead imports**
4. **每 commit sub-agent review**：用户规则 "每做完一个阶段用 subagent 来 review，你 debug 再给 subagent 第二轮 review，前面修改的如果忘了 review 就一会先review"
   - Round 1: 拆完后立即 review
   - Round 2: **只在 Round 1 REQUEST CHANGES 或 NITS 我修了之后** 才跑
   - Round 1 直接 APPROVE → 跳过 Round 2
5. **Baseline 6/427**：拆完每次 `:app:testDebugUnitTest --offline` 必须 = 6 failed
6. **Compile gate**：每次 strip + 写新文件后立即 `:app:compileDebugKotlin --offline` 验证

---

## 5. 拆分模板（已稳定 3 刀）

```
1. grep 块边界（@Composable / private fun + 行号范围）
2. grep 每个符号的外部 call site（决定 internal vs private）
3. 写新 sibling 文件（同 package，含必要 imports，标对 internal/private）
4. awk strip 旧块：awk 'NR<X || NR>Y' file > tmp && mv tmp file
5. compile gate
6. dead-import 清理（grep 每个候选 import "<symbol>" file | grep -v "^import"，0 ref 才删）
7. compile 再验
8. testDebugUnitTest 跑（后台 run_in_background，验证 baseline 6/427）
9. commit
10. sub-agent Round 1 review
11. (可选) Round 2 if NITs/CHANGES
12. push
```

### Internal vs Private 决策
- 外部 file 有 caller → `internal`
- 仅本块内部用 → `private`（file-private to new file）
- 跨包 caller（如 ChatPage.kt 调） → **`internal` 必须保留**（不能 demote）

### AnimatedVisibility 处理
- 原 ChatInput 用全限定 `androidx.compose.animation.AnimatedVisibility(...)` —— **保留 FQN，不顺手 import**（byte-equal 最稳）

---

## 6. 下一刀：ChatInputSandbox.kt（**未开工**）

### 6.1 边界（ChatInput.kt 现状 1811 行）

| 范围 | 内容 |
|---|---|
| L729-1786（**~1058 行**）| 全部 Sandbox 块 |
| L1787+ | ActionIconButton 等 — **不属于** Sandbox，留在 ChatInput.kt |

### 6.2 符号清单 + 可见性决策

**🔴 必须 `internal`（跨文件 caller）**：
- `SandboxActivitySheet` (L1127, 当前 `public fun`) — **`ChatPage.kt:93 import + L804 调用`**！demote 到 `internal` 即可（保持 cross-file 可见）
- `SandboxPeekBar` (L729) — 主 ChatInput 在用（in-file external caller 1 个）

**🟢 全部 `private`（块内自洽）**：
- 22 个 composables：AgentOperationPreviewPeek, WebOperationPreviewThumbnail, HiddenWebOperationRenderer, WebOperationPreviewPlaceholder, SandboxStepPeek, SandboxStepArrow, SandboxStepStatusIcon, SandboxSheetHeader, SandboxToolActivityContent, SandboxWebActivityContent, OperationWebPreview, WebOperationPreviewLoadingOverlay, SandboxSheetCodeBlock
- 13 个 helper：String.normalizedWebPreviewUrl, WebViewOperationState.matchesPreview, WebViewOperationState.bestThumbnailFile, String.asValidThumbnailFile, WebSettings.disablePreviewDarkening, extractReadablePage, scheduleReadableExtracts, scheduleThumbnailCaptures, captureWebViewThumbnail, Bitmap.looksBlank, sandboxStatusLabel, sandboxStatusContainerColor, sandboxStatusOnContainerColor, SandboxActivityUiState.operationPreviewKind/Text/Url/stepProgressText/terminalTranscript, String.firstHttpUrl/webHostPreview/compactForSandbox

### 6.3 风险点

🔴 **高风险**：
1. **WebView 状态机** — HiddenWebOperationRenderer 创建实际 WebView 加载页面、抓 thumbnail、提取 readable 文本。涉及 `rememberWebViewState`、`DisposableEffect`、`WebSettings`
2. **Bitmap 处理** — captureWebViewThumbnail 用 Canvas + Bitmap.createBitmap + compress；Bitmap.looksBlank 扫像素
3. **跨包 SandboxActivitySheet** — `fun` → `internal` 是 visibility 收紧，确认 ChatPage 调用还能编译
4. **`scheduleReadableExtracts` / `scheduleThumbnailCaptures`** — 异步任务，可能用 LaunchedEffect / produceState

🟡 **中风险**：
- SandboxActivitySheet 内嵌 ModalBottomSheet，状态生命周期
- OperationWebPreview 有自己的加载 overlay 动画

### 6.4 预估

- 移走 ~1058 行
- ChatInput.kt 拆完：1811 → **~750-850 行**（含主 ChatInput composable 568 行 + ExpandState enum + ActionIconButton + 其他剩余）
- Sandbox.kt 新文件 ~1100 行

### 6.5 跨文件依赖（应已 internal 同 package）
- `SandboxActivityUiState` — `data/agent/` package（external 导入）
- `ToolActivityStatus` — 同上
- `WebViewLink`, `WebViewLoadStatus`, `WebViewOperationState`, `WebViewOperationStore` — `data/agent/webview/`
- `WebView`, `rememberWebViewState` — `ui/components/webview/`

### 6.6 建议拆分策略

**A. 一刀切**（推荐）— 整个 1058 行块 → ChatInputSandbox.kt 单文件。Sandbox 内部高度耦合（PreviewPeek 调 WebOperationPreviewThumbnail，调 normalizedWebPreviewUrl/matchesPreview/bestThumbnailFile/asValidThumbnailFile；HiddenWebOperationRenderer 用 scheduleReadableExtracts / scheduleThumbnailCaptures；SandboxActivitySheet 调 SandboxSheetHeader / SandboxToolActivityContent / SandboxWebActivityContent / SandboxSheetCodeBlock；sandboxStatus* 系列被 Sheet 调）— 强行切分会产生大量跨文件 `internal` 边界，得不偿失。

**B. 二刀切** — Peek 上层（L729-1108）+ Sheet/Preview 下层（L1126-1786）。但 sandboxStatusLabel 等 helper 两层都用，得 promote 到 internal 或重复定义。**不推荐**。

---

## 7. 拆完 Sandbox 后

1. testDebugUnitTest → baseline 6/427
2. assembleDebug + assembleNotion + assembleRefactortest 三变体全过
3. sub-agent Round 1 review（重点：跨包 SandboxActivitySheet visibility）
4. push
5. 给用户汇总：ChatInput 收尾、是否合 main

---

## 8. 关键文件路径

- Coordinator: `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`（1811 行）
- 已存在 sibling（必须保留）:
  - `ChatInputUsage.kt`
  - `ChatInputAttachments.kt`
  - `ChatInputComposers.kt`
  - `MentionRoles.kt`（pre-existing，含 MentionRoleItem/buildMentionRoleItems/filterMentionRoleItems）
- 计划文档:
  - `docs/chatinput-composers-plan.md`（pre-Composers 的规划，Composers 完工后留作历史）
  - `docs/SESSION_5_HANDOFF.md`（本文档）

---

## 9. 失败回退

如果 Sandbox 拆出问题：
```bash
git reset --hard a62e30d4    # 退回到 Composers 完工
```
本 commit 已 push 到 `github-private`，远端有备份。
