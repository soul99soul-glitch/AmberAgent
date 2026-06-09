# P2 Pre-Cut Analysis — `SettingExperimentalPage.kt`

> 2026-05-18 / 分支 `refactor/p2-settings-experimental` / 基线 `cdbdf159` (kickoff doc)
> Target: `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingExperimentalPage.kt` (2447 行)

---

## 0. 结论先行

文件结构异常整洁：**1 个入口 hub + 4 个独立子页 + 1 组共享 Experiment\* 原语**。建议按 4 个 stage 抽出 4 个子页 sibling，coordinator（`SettingExperimentalPage` + 共享原语）留在原文件。

预估完工后：

| 文件 | 行数 |
|---|---|
| `SettingExperimentalPage.kt`（coordinator） | ~400 |
| `SettingExperimentalSubAgentPage.kt` | ~515 |
| `SettingExperimentalModelCouncilPage.kt` | ~504 |
| `SettingExperimentalICloudPage.kt` | ~225 |
| `SettingExperimentalOfficeProPage.kt` | ~735 |

风险：🟢 **低**。子页之间零静态依赖（仅通过 `navController.navigate(Screen.X)` 跨页跳转），共享原语已 `internal` 已跨包验证。

---

## 1. 文件解剖

### 1.1 Top-level decl 速查（按行号）

| 行号 | 类型 | 名称 | 当前可见性 |
|---|---|---|---|
| 122–180 | @Composable | `SettingExperimentalPage` | `public`（hub 入口） |
| 182–449 | @Composable | `SettingExperimentalSubAgentPage` | `public` |
| 451–478 | @Composable | `SubAgentSelectRow<T>` | `private` |
| 480–655 | @Composable | `SubAgentBuiltInRow` | `private` |
| 656–697 | @Composable | `SubAgentCustomRow` | `private` |
| 698–966 | @Composable | `SettingExperimentalModelCouncilPage` | `public` |
| 967–1153 | @Composable | `ModelCouncilSeatEditor` | `private` |
| 1154–1172 | @Composable | `ModelCouncilPropertyRow` | `private` |
| 1173–1201 | @Composable | `ModelCouncilSelectRow<T>` | `private` |
| 1202–1359 | @Composable | `SettingExperimentalICloudPage` | `public` |
| 1360–1642 | @Composable | `SettingExperimentalOfficeProPage` | `public` |
| 1643–1868 | @Composable | `OfficeProjectEditorDialog` | `private` |
| 1869–1977 | @Composable | `WatchDocDialog` | `private` |
| 1978–2004 | @Composable | `OfficeProSwitchRow` | `private` |
| 2005–2036 | @Composable | `ExperimentSectionCard` | `internal` |
| 2037–2088 | @Composable | `ExperimentHeroCard` | `internal` |
| 2089–2137 | @Composable | `ExperimentFeatureRow` | `private` |
| 2138–2149 | @Composable | `ExperimentDivider` | `internal` |
| 2150–2162 | @Composable | `ExperimentActionRow` | `internal` |
| 2163–2198 | @Composable | `ExperimentActionButton` | `internal` |
| 2199–2225 | @Composable | `ExperimentStatusRow` | `internal` |
| 2226–2246 | @Composable | `ExperimentBooleanPill` | `private` |
| 2247–2266 | @Composable | `ExperimentNote` | `internal` |
| 2267–2295 | @Composable | `ExperimentalSettingsScaffold` | `internal` |
| 2296–2358 | @Composable | `ICloudLoginDialog` | `private`（属于 ICloud section） |
| 2360 | const val | `ICLOUD_WEBVIEW_USER_AGENT` | `private`（属于 ICloud） |
| 2363 | const val | `OFFICE_PROJECT_IMPORT_MAX_BYTES` | `private`（属于 Office） |
| 2365–2446 | fun / ext fun | `parseProjectList` / `parseProjectSourceRefs` / `appendSourceRef` / `importOfficeProjectSource` / `String.toOfficeProjectId` / `String.toSafeWorkspaceFileName` / `formatOfficeProjectUpdatedAt` | `private`（全部属于 Office） |

### 1.2 调用关系

- **`SettingExperimentalPage`（hub）**：只调用 `Experiment*` 原语 + `navController.navigate(Screen.SettingExperimental{ICloud,OfficePro,SubAgent,WebMount,TodayBoard})`。不直接调用任何子页 composable，所以**所有子页可独立抽出**。
- **每个子页内部**：调用同 section 的 private helper + `Experiment*` 原语。**section 之间零直接调用**，唯一跨 section 链接是 SubAgent 页通过 `Screen.SettingExperimentalModelCouncil` 路由跳到 ModelCouncil 页（route 字符串，非静态依赖）。
- **`Experiment*` 原语**：被本文件内 5 个页面 + 外部 2 个文件使用，**必须保持 `internal`**。
- **`ICloudLoginDialog` / `ICLOUD_WEBVIEW_USER_AGENT`** 仅 ICloud 用 → 跟着 ICloud 走。
- **Office 私有 helper 群 (L2360–2446)** 仅 Office 用 → 跟着 Office 走。

### 1.3 Cross-file caller 实证（已 grep）

```
RouteActivity.kt → SettingExperimentalPage (L560)
                  SettingExperimentalICloudPage (L564)
                  SettingExperimentalOfficeProPage (L568)
                  SettingExperimentalSubAgentPage (L572)
                  SettingExperimentalModelCouncilPage (L576)

SettingTodayBoardPage.kt (ui/pages/board/) → ExperimentDivider, ExperimentSectionCard, ExperimentalSettingsScaffold
SettingExperimentalWebMountPage.kt (同 pkg)  → ExperimentHeroCard, ExperimentSectionCard, ExperimentStatusRow,
                                              ExperimentNote, ExperimentActionRow, ExperimentActionButton,
                                              ExperimentDivider, ExperimentalSettingsScaffold
```

`SubAgentBuiltInRow` / `SubAgentCustomRow` / `SubAgentSelectRow` / `ModelCouncil*` / `OfficeProjectEditorDialog` / `WatchDocDialog` / `OfficeProSwitchRow` / `ICloudLoginDialog` / Office 私有 helpers — **均 0 cross-file caller**，安全保持 `private` 入新 sibling。

---

## 2. 切分方案（4 stage）

每个 stage 独立 commit，独立 sub-agent Round 1 review。

### Stage 1 — SubAgent
- 抽出范围：L182–697
- 包含：`SettingExperimentalSubAgentPage`（public，保持）+ 3 个 private helper
- 新文件：`SettingExperimentalSubAgentPage.kt`
- 行数：~515
- 风险：低。state 全在 `koinViewModel` + `LaunchedEffect`，无跨 section 共享。

### Stage 2 — ModelCouncil
- 抽出范围：L698–1201
- 包含：`SettingExperimentalModelCouncilPage`（public，保持）+ 3 个 private helper（含泛型 `ModelCouncilSelectRow<T>`）
- 新文件：`SettingExperimentalModelCouncilPage.kt`
- 行数：~504
- 风险：低。

### Stage 3 — ICloud
- 抽出范围：L1202–1359 + L2296–2358 + L2360 const
- 包含：`SettingExperimentalICloudPage`（public）+ `ICloudLoginDialog`（private）+ `ICLOUD_WEBVIEW_USER_AGENT` const
- 新文件：`SettingExperimentalICloudPage.kt`
- 行数：~225
- 风险：低。WebView state 通过 `rememberWebViewState`/`DisposableEffect`，effect key 稳定（来源参数）。

### Stage 4 — OfficePro
- 抽出范围：L1360–2004 + L2363 const + L2365–2446 private helpers
- 包含：`SettingExperimentalOfficeProPage`（public）+ 3 个 private composable + 1 个 const + 7 个 private 顶层函数（含 2 个 String 扩展）
- 新文件：`SettingExperimentalOfficeProPage.kt`
- 行数：~735
- 风险：低-中。**最大 stage，且含 `suspend fun importOfficeProjectSource`**，需确认所有依赖（`WorkspaceManager` / `Uri` / `OpenableColumns` / `withContext`）的 import 全部跟着进 sibling。

### Coordinator 留存
- `SettingExperimentalPage`（hub L122–180） + 全部 `Experiment*` 原语（L2005–2295）
- 行数：~400
- 共享原语**必须保持 `internal`**（外部跨包 caller）

---

## 3. 风险点 & 验证清单

### 3.1 命名 namespace 风险（P1 教训 #1）
- 子页文件名 = 抽出函数名（`SettingExperimentalXxxPage.kt`）→ 公开 API，已在 RouteActivity 引用，不能改。
- private helper 名都带 section 前缀（`SubAgent*` / `ModelCouncil*` / `Office*` / `ICloud*`），冲突概率低。
- `Experiment*` 原语保持现状（已稳定，外部已引用）。
- **新增 namespace 风险只在新增 helper 时考虑** — 本次纯抽出，无新增 helper。

### 3.2 Byte-equal 严格度（P1 教训 #5）
- 函数体内的 FQN（如 `Activity.RESULT_OK`）不变。
- 签名 / 参数类型本来就 short-form，无需调整。
- **可见性 modifier 不变**（所有 public 保 public，internal 保 internal，private 保 private）。strict byte-equal 模式：除了换行截断点，函数 body 字节级一致。

### 3.3 Pre-existing dead import（P1 教训 #4）
- 抽出后原文件的 import 清理：**只清这次抽出造成的 orphan**。
- 抽出范围里的 import 需要新文件自带，但**新文件 import 只放确实被本文件用到的**，不顺手清原文件里 pre-existing dead。
- 候选 dead imports 待 stage 完成后由 sub-agent review 单独识别。

### 3.4 跨包 visibility（P1 教训 #2）
- 不收紧任何现有 `public` → `internal`。所有子页保持 `public`（RouteActivity 跨包但同 module，`internal` 也行，但**最小变动原则**留 public）。
- `Experiment*` 原语已 `internal`，跨包外部已有 caller（`SettingTodayBoardPage` / `SettingExperimentalWebMountPage`），**必须保持 `internal`**，绝不能 `private`。

### 3.5 LaunchedEffect / DisposableEffect 副作用稳定性（kickoff §5.3）
- 子页内的 effect key 全部来自 ViewModel state / `remember` 派生值 — 抽到新文件后这些来源不变，effect key 稳定。
- ICloud WebView 的 `rememberWebViewState` 用 `key = state.url` — 不受文件位置影响。

### 3.6 共享 ViewModel
- 4 个子页都用 `koinViewModel<SettingVM>()` 取 settings。每个 composable 实例独立 ViewModel scope（基于 NavBackStackEntry），抽到新文件不影响。

---

## 4. 每 stage 执行流程（kickoff §4 模板）

```
1. 读 stage 范围 + grep section 内 helper 边界（已完成，行号在 §1.1）
2. 列 stage 范围用到的 imports（从文件顶部 import 列表 ∩ stage 范围内 symbol 引用）
3. Write 新 sibling 文件（package + imports + 抽出代码 byte-equal）
4. compile gate: ./gradlew :app:compileDebugKotlin --offline
5. awk strip 旧块：awk 'NR<X || NR>Y' file > tmp && mv tmp file
   ⚠ 注意：多个范围要从最大行号往最小行号 strip，避免行号偏移
6. compile gate
7. dead-import 清理（grep "<symbol>" 原文件，0 ref 且确认是这次抽出 orphan 才删）
8. compile 再验
9. ./gradlew :app:testDebugUnitTest --offline → 必须 6 failed (精确 6 test 名)
10. git add + commit（消息：refactor(p2): extract <stage> from SettingExperimentalPage）
11. sub-agent Round 1 review
12. (可选) Round 2 if NITs/CHANGES → 修了再 commit 新 commit
13. push
```

完成 Stage 4 后追加：
```
14. ./gradlew :app:assembleDebug :app:assembleNotion :app:assembleRefactortest --offline (BUILD SUCCESSFUL)
15. 起 PR + Codex 独立 review
```

---

## 5. Stage 顺序建议

按源码自上而下（无依赖 → 最低脑负担）：
1. **Stage 1 — SubAgent** （L182–697）
2. **Stage 2 — ModelCouncil** （L698–1201）
3. **Stage 3 — ICloud** （L1202–1359 + L2296–2360）
4. **Stage 4 — OfficePro** （L1360–2004 + L2363–2446）

每个 stage 独立 push 到远端，崩了能 reset 到上一个 commit。

---

## 6. 待用户确认才动手的事项

1. **是否真按 4 stage 拆**（还是想合并 ICloud + OfficePro，或 SubAgent + ModelCouncil 这种）？4 stage 更细但 review 次数也多。
2. **是否允许在 stage 完成后做"非 byte-equal" 的小整理**（如统一 import 排序）？默认**不**做。
3. **PR 时机**：所有 stage 一次 PR（推荐），还是每 stage 一个 PR？P1 是单 PR 13 commits，audit 价值高，建议沿用。

确认后我从 **Stage 1 (SubAgent)** 开始动刀。
