# Batch 5B — 设置页保存/反馈/并发写(SubAgent / Council / MCP / Appearance / ChatAssistant*)

范围:F21 / F23 / R26 / F35。仅改分配给本 agent 的独占文件 + 新增 `entry/platform_impl/AssistantMutation.ets`;未触碰 `ChatPage.ets` / `AppContainer.ets` / `SettingDisplayPage.ets` / `SkillsPage.ets`。

约束遵守:加载 `harmony-arkts`;API 基线 12;状态装饰器全 V1;无新依赖/新框架;未跑 hvigor 全仓构建;未做设备目测。

---

## 修改文件与修复 ID

| 文件 | 修复 | 最小机制 |
|---|---|---|
| `harmony/entry/src/main/ets/platform_impl/AssistantMutation.ets`(新增) | F35 | `mutateAssistantInStore(kv,id,mutator)` + 生产入口 `mutateAssistant(id,mutator)`;`sharedKvMutex.withLock(ASSISTANTS_KEY)` 内 load 最新 → 锁内 transform 目标条 → 原位回写 |
| `harmony/entry/src/main/ets/pages/ChatAssistantExtensionsPage.ets` | F35 | 4 个 toggle(modeInjection/lorebook/quickMessage/skill)改走 `mutateAssistant`,删除 `saveAssistant`(load-modify-save);onChange 补 `.catch` |
| `harmony/entry/src/main/ets/pages/ChatAssistantLocalToolsPage.ets` | F35 | `toggleLocalTool`(含 webmount→webmount_eval 连带)改走 `mutateAssistant`;删除 `saveAssistant` |
| `harmony/entry/src/main/ets/pages/ChatAssistantMcpPage.ets` | F35 | `toggleServer` 改走 `mutateAssistant`;删除内联 load-modify-save |
| `harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets` | F21 | `dirty/saving/saveError` + `onBackPress` 脏确认 + 顶栏显式「保存」`saveAndBack()`(await 成功才 back);`saveSetting` 返回 boolean 且不再静默吞错 |
| `harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets` | F21 | 同上;`updateSeatName/updateSeatPrompt` 置 dirty |
| `harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets` | F23 | `nameError` 行内反馈 + 名称输入框错误描边;空白名保存不再静默 no-op;清空/合法输入即消错 |
| `harmony/entry/src/main/ets/pages/SettingAppearancePage.ets` | R26 | `setTheme` 改 async:乐观应用后 `await put`;失败可见回滚到最近成功模式;`themeSeq` guard 防旧失败覆盖后操作 |

---

## 各修复细节

### F35 — 锁内最新 snapshot 的 RMW(不写旧快照)
新增 helper 与 `ExtensionPrefs.updateModeInjections/updateLorebooks` 同范式:
- `sharedKvMutex.withLock(ASSISTANTS_KEY, ...)` —— 全仓 0 命中 `mutateAssistants`,此前 `assistants` 键确无锁;`AtomicJsonFile` 的 `sharedKvMutex` 单例就是既有 KV RMW 锁。
- 锁内 `loadAssistants(kv)` → `findIndex(id)` → 命中则 `mutator(current[index])` → 仅替换该条、`saveAssistants` 回写。
- 未命中 → 返回 `null`(不新建/不清空,避免旧 clone 复活或整表覆盖);`mutator` 抛错 → 不落盘并向上抛(Promise 拒绝,由调用方 `.catch`)。
- 调用方把「算 ids」的纯逻辑搬进 mutator(基于锁内最新助手),不再用 `this.assistant` 快照做 next。页面 state 仅在 `next !== null` 时更新。

### F21 — 显式保存 + 脏返回确认(不叠三套机制)
- `updateRolePrompt`(SubAgent)/`updateSeatName`+`updateSeatPrompt`(Council)只置 `dirty=true`,**不每键写盘、不 aboutToDisappear flush**。
- 顶栏 `PageTitleBar` 增 `actionText:'保存'`(保存中显示「保存中…」)与 `onAction: saveAndBack()`;`saveAndBack` await `saveSetting()` 成功才 `router.back()`,失败弹「保存失败」且草稿留在页内。
- `onBackPress()`:脏/保存中即 `requestBack()` 返回 true 消费返回;弹 AlertDialog「放弃修改 / 保存并返回」,点外部取消。非脏直接放行系统返回。
- `saveSetting()` 收敛为 `Promise<boolean>`,失败写 `saveError` 并在页顶渲染错误条(不再 `catch {}` 静默)。
- 反证保留:`saveSetting` 序列化整份 `@State`,故「切行后再触发任一保存点」仍会把先前文本编辑一并落盘(dirty 清空),即「切行即永久丢」依旧不成立;本修复只闭合「编辑后未触发任何保存点直接返回」。

### F23 — 空名称行内反馈
`confirmDraft()` 空白名分支由 `return;` 改为置 `nameError='请填写服务器名称'` 并 return(sheet 停留供修正);名称 `TextInput` 在 `nameError` 非空时 `border(1, ERROR)`;下方渲染与导入错误同款式的行内文案;`onChange` 在名称去空白非空时清错;`openCreate/openEdit` 复位 `nameError`。未禁用按钮(与导入校验反馈风格一致,且避免 `BtnFill` 无 enabled 造成的额外改动面)。

### R26 — 主题保存失败可见回滚 + 竞态 guard
- 抽 `applyThemeMode(mode)` 单一应用入口(applyTheme + applyWindowTheme + AppStorage uiColorMode/darkMode)。
- `setTheme` 改 async:`seq=++themeSeq` → 乐观 `themeMode=mode` + 应用 → `await kv.put(UI_COLOR_MODE_KEY, mode)`;成功后记 `persistedThemeMode=mode`;失败且 `seq===themeSeq` 时回滚 `themeMode=persistedThemeMode` 并重新应用 + 置 `themeError='主题设置保存失败,已恢复原设置'`。
- **竞态 guard**:较早操作晚到的失败若 `seq!==themeSeq`(已被后操作超越)直接 return,不回滚、不报错——不覆盖用户之后的选择。
- `aboutToAppear` 读回模式时同步 `persistedThemeMode`,保证回滚目标=最近成功落盘值;错误条渲染在颜色模式 Seg 下方;`onSelect` 补 `.catch`。
- 强调色 `setAccent` 未改(超出本 ID 范围,且原语义为 fire-and-forget)。

---

## 测试与静态检查

- 新增 `docs/reviews/2026-09-13-harmony-repair/probes/probe-batch5-settings.cjs`(真 logic,内存 KV,无设备/网络/真实文件):
  - **F35**:驱动真实 `mutateAssistantInStore` + 真实 `KeyedAsyncMutex` + 真实 `kv_store` helpers,20 个并发 toggle **全部保留**(20/20);对照组复刻被删除的 stale-snapshot load-modify-save,确证会丢一条(A 被 B 覆盖)→ 探针能捕获回归;目标缺失返回 `null` 且表未被改写。
  - **R26**:用 TS AST 从真实 `SettingAppearancePage.ets` 抽取 `setTheme`+`applyThemeMode` 原文(headless),断言:(a) put 拒绝 → 回滚到最近成功值 + 错误可见;(b) 旧 op 晚到失败 → 不回滚更新的成功选择、不置错误。
  - 结果:`PROBE OK: batch 5B (F35 + R26)`(日志 `probes/probe-batch5-settings.log`)。
- `harmony/scripts/lint-arkts.sh <file>`(`LINT_EXIT_ON=none`)逐文件:AssistantMutation / 三个 ChatAssistant* 页 / SettingSubAgentPage / SettingModelCouncilPage / ChatMcpSettingsPage / SettingAppearancePage → **全部 0 error / 0 defect**。
- 未跑 hvigor/全仓 lint(主代理统一串行);未做设备/模拟器截图目测(无在线设备),**不声称 UI 已验收通过**。

---

## 共享接线需求(主代理 / ChatPage owner)

1. **F35 helper 已就绪,供 ChatPage 复用**(用户指定签名):
   ```ts
   import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';
   // 技能 toggle(替换 ChatPage.ets:3305-3316 setSkillEnabled 的裸 load/save)
   const next = await mutateAssistant(rt.assistant.id, (cur: Assistant): Assistant => {
     const skills = enable
       ? (cur.enabledSkills.indexOf(name) >= 0 ? cur.enabledSkills : cur.enabledSkills.concat([name]))
       : cur.enabledSkills.filter((n: string): boolean => n !== name);
     return patchAssistant(cur, { enabledSkills: skills });
   });
   ```
   模型绑定(`handleReasoningLevelChange` ChatPage:2925-2946)同样把「算全表 next」改为锁内 `mutateAssistant(assistantId, a => withReasoningLevelForModel(a, modelId, rl))`。ChatPage 仍可保留 `saveAssistants` import 供其它路径使用。
   - 注意:`mutateAssistant(id, mutator)` 内部经 `getChatKvStore()`,与 `platform_impl/McpSettingsStore.ets` 既有「platform_impl 引 AppContainer」先例一致;AppContainer 不反向 import AssistantMutation,无环。
2. `AssistantMutation.ets` 是新的共享写入口:任何新的 `assistants` 键写者都应改走它,方可与既有写者共用同一把锁;否则仍可能与页面 toggle 交错覆盖。
3. F21 两页的 `PageTitleBar` 现传 `actionText/onAction/onBack`;若其它 owner 也改 `PageTemplate.PageTitleBar` 签名,请保留这三个可选成员。

## 遗留 / 未解决
- F21 的设备态呈现(返回手势是否稳定消费、AlertDialog 与自绘层叠加)未实测,待设备回归;`onBackPress` 在 `@Entry` 页的触发已按 ArkUI 页面生命周期用法实现。
- F23 采用行内错误而非禁用保存按钮;若产品更希望「按钮置灰」,`BtnFill` 当前无 `enabled` prop(见 PageTemplate.ets:174-205),需另行扩展组件,本 agent 未改以避免扩大组件面。
- R26 只回滚主题模式;强调色保存仍为原 fire-and-forget(不属本 ID)。
- `assistants` 键的锁仅覆盖经 `mutateAssistant` 的写者;历史/旁路直接 `saveAssistants` 的调用点(如 ChatPage 部分路径)迁移前仍是潜在交错源,需主代理统一收敛。
