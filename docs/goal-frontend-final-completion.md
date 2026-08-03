# Goal:鸿蒙 AmberAgent 前端最终补齐

> **执行 agent 必读**:本文档是执行权威。按里程碑顺序推进,每个里程碑有明确的验收标准(exit criteria)。不要跳过验收标准自行宣布完成。
>
> **宪章不变**:Android 源码只读;HarmonyOS 6.0.0/API 20/Stage model/ArkTS/ArkUI V2;不过度防御;新组件必须有 Android 当前依据 + 真实消费者 + 行为验证;ArkTS 禁忌(as unknown/对象 spread/indexed-access/any/unknown/@Prop 保留名 enabled|size/ForEach 回调内禁 const|let/for...in 禁用/判别联合 narrowing 后显式 cast)。

---

## 0. 现状基线(2026-08-03,HEAD `7f18fa1ac`)

| 维度 | 当前 |
|---|---|
| 前端组件 | 25 个 |
| 页面 | 27 个 |
| chat 域逻辑 | ~1200 行 / 1664 测试 |
| 已完成 | Markdown 渲染 / 消息组件族拆分 / ChatInput 基础 / TopModelMenu / ReasoningPicker / part 诚实化 / 代码高亮 / SettingPage 索引 / per-role override / 席位编辑 / DeepRead ArticleRenderer 接入 |
| 设备验证 | **0 项**(Gate 0 blocked,`hdc list targets` 为空) |

---

## 1. 剩余缺口全量清单(侦察确认)

### A. 卡设备/平台(本 goal 不做,登记)
| 项 | 原因 |
|---|---|
| LazyForEach + IDataSource 虚拟化 | 18+ 处 rows 赋值重构 + 必须真机验证响应式/性能;盲改当前能跑的列表 = 高风险 |
| GenerativeWidgetCard 真实渲染 | 需 WebView + GenerativeWidgetParser 域逻辑移植(1995 行) |
| Mermaid / LaTeX | 需 Web 组件 + JS bundle |
| TTSController | 需 TTS 引擎绑定(6 个 Android 文件,0 个 Harmony) |
| video/audio/document picker(输入侧) | 需平台 picker API |
| 相机 picker | 同上 |
| 流式 Markdown 增量解析缓存 | 需设备验证性能改善(MessageRenderCache.markdownParseResult 移植) |

### B. source-only 可推进(本 goal 范围)

| 序 | 缺口 | 后端就绪? | 规模 | 设备依赖 |
|---|---|---|---|---|
| B1 | DisplaySetting 类型 + 持久化(~30 字段) | ❌ 鸿蒙无,Android core/settings 有 | 中 | 否 |
| B2 | SettingDisplayPage(显示设置页) | 依赖 B1 | 中 | 否 |
| B3 | sendOnEnter(TextArea 回车发送) | 依赖 B1 | 小 | 否(编码可做,手感待设备) |
| B4 | ChatInputComposers 子功能(source-only 部分) | 混合 | 大 | 否 |
| B5 | 原语动画(LiveDot 呼吸 / BlinkingCursor 闪烁 / DotLoading 跳动) | N/A | 小 | 否(编码可做,效果待设备) |
| B6 | 缺失设置页(About / Sandbox / Files / CronTasks) | 混合 | 中 | 否 |
| B7 | QuickMessage 内容 store + /quick-* 命令 | ❌ 鸿蒙无 | 中 | 否 |

---

## 2. 里程碑(按依赖序)

### M1:DisplaySetting 类型 + 持久化(前置依赖,B1)

**终态**:chat HAR 有 `DisplaySetting` 接口 + `loadDisplaySetting`/`saveDisplaySetting` 纯逻辑 + KV 持久化键 + 单元测试。entry AppContainer 暴露 `getDisplaySetting`/`saveDisplaySetting` 访问器。

**Android 基准**:`core/settings/.../PreferencesStore.kt:315+`(`DisplaySetting` data class,~30 字段)。字段全量(从 Android `SettingDisplayPage` 提取,已侦察):
```
sendOnEnter, pasteLongTextAsFile, pasteLongTextThreshold,
showAssistantBubble, showUserAvatar, showModelName, showModelIcon,
showDateBelowName, showThinkingContent, autoCloseThinking,
enableAutoScroll, enableVolumeKeyScroll, volumeKeyScrollRatio,
showMessageJumper, messageJumperOnLeft, showBottomFollowAnimation,
fontSizeRatio, chatFontFamily, amberBaseFamily, accentColor,
codeBlockAutoCollapse, codeBlockAutoWrap, showLineNumbers,
enableLatexRendering, copy (UI 用), enableMessageGenerationHapticEffect,
enableNotificationOnMessageGeneration, autoPlayTTSAfterGeneration,
ttsOnlyReadQuoted, skipCropImage
```

**步骤**:
1. `harmony/chat/src/main/ets/chat/display_setting.ts`:`DisplaySetting` 接口 + `makeDisplaySetting`(默认值对齐 Android)+ `DISPLAY_SETTING_KEY`
2. `harmony/chat/src/main/ets/chat/kv_store.ts`:`loadDisplaySetting`/`saveDisplaySetting`(JSON 序列化)
3. barrel 注册
4. `harmony/entry/src/main/ets/di/AppContainer.ets`:`getDisplaySetting`/`saveDisplaySetting` 访问器(走 getChatKvStore)
5. 测试 `display_setting.test.ts`:默认值 + round-trip 序列化 + 部分字段覆盖

**验收标准**:
- [ ] DisplaySetting 接口含全部 ~30 字段,默认值对齐 Android
- [ ] load/save round-trip 测试通过
- [ ] AppContainer 访问器可从页面调用
- [ ] chat tsc clean + 测试全绿 + assembleHap SUCCESS

---

### M2:SettingDisplayPage 显示设置页(B2)

**终态**:SettingPage 索引的「通用」分组新增「显示设置」入口 → SettingDisplayPage 页面,编辑高频 DisplaySetting 字段(sendOnEnter 开关 / pasteLongTextAsFile 开关 / showAssistantBubble 开关 / showModelName 开关 / showUserAvatar 开关 / fontSizeRatio 选择)。

**不做**(本轮):accentColor 颜色选择器 / fontFamily 选择器(需 UI 资源 + 设备验证渲染)/ volumeKeyScroll(需平台按键监听)/ hapticEffect(需平台振动 API)/ notificationOnMessageGeneration(需平台通知)。这些字段在 DisplaySetting 类型里保留(M1 已含),但 UI 编辑控件登记为设备解锁后。

**步骤**:
1. `harmony/entry/src/main/ets/pages/SettingDisplayPage.ets`:~6 个 toggle/slider 行,读写 getDisplaySetting/saveDisplaySetting
2. 路由注册 main_pages.json
3. SettingPage 索引「通用」分组加入口

**验收标准**:
- [ ] SettingDisplayPage 存在 + 路由注册 + SettingPage 入口
- [ ] ≥6 个 DisplaySetting 字段可编辑 + 持久化(进页面读 / 改 / 返回再进验证持久)
- [ ] assembleHap SUCCESS

---

### M3:sendOnEnter 接入(B3)

**终态**:DisplaySetting.sendOnEnter = true 时,ChatInput TextArea 回车键触发发送(而非换行)。

**ArkUI 方案**:TextArea 的 `onKeyEvent` 或 `TextAreaEnterKeyType` + `onEditChange`。需验证 ArkUI TextArea 是否支持自定义 Enter 行为;若不支持,降级为「发送键提示」(设置页保留开关,登记 ArkUI 限制)。

**步骤**:
1. ChatInput 新增 `@Prop sendOnEnter: boolean`
2. TextArea 配置 EnterKeyType.Send + onSubmit 回调(若 ArkUI 支持)
3. ChatPage 传入 `sendOnEnter: displaySetting.sendOnEnter`

**验收标准**:
- [ ] sendOnEnter=true 时回车触发 onSend(若 ArkUI 支持 onSubmit)
- [ ] sendOnEnter=false 时回车正常换行
- [ ] 若 ArkUI 不支持 → 登记限制,设置页保留开关(不伪实现)

---

### M4:ChatInputComposers source-only 子功能(B4)

**终态**:以下 ChatInputComposers 子功能从 Android 移植到 Harmony(仅 source-only 可做的):

| 子功能 | 后端 | 步骤 |
|---|---|---|
| **/skill-* 斜杠命令** | ✅ SkillManager 已就绪 | SlashCommandPanel.buildSlashCommands 动态追加 enabled skills;ChatInput.handleSlashCommand 插入 use_skill prompt |
| **空/无匹配行** | N/A | SlashCommandPanel visible && commands.length===0 时显示「无匹配命令」 |
| **per-command minQueryChars** | N/A | SlashCommand 接口加 minQueryChars 字段;filterSlashCommands 遵守 |
| **FullScreen 编辑器** | N/A(state=text) | ChatInput 加全屏展开图标 → bindSheet 全屏 TextArea |
| **minimal-chrome 模式** | N/A | ChatInput 加 `@Prop minimalChrome` → 紧凑单行变体 |
| **斜杠面板动画** | N/A | TransitionEffect.OPACITY + translate(对齐 Index.ets 既有动画模式) |

**不做**(本轮,卡后端或设备):
- @role MentionPanel(MentionRoles.kt 66 行需移植 + detectMentionContextFor 光标检测逻辑复杂,登记后续)
- QuickMessage dropdown(依赖 B7,见 M6)
- /usage 命令(usage 计算器后端缺失,登记)
- pasteLongTextAsFile 内容接收(ArkUI TextArea 无 contentReceiver 等价,需验证平台能力)
- +→× 形变动画(设备验证手感)

**验收标准**:
- [ ] /skill-* 命令出现在斜杠面板(assistant 有 enabled skills 时)
- [ ] 空匹配时显示「无匹配命令」(非空白消失)
- [ ] minQueryChars 生效(动态命令需 ≥2 字符)
- [ ] FullScreen 编辑器可展开/收起/编辑/回填
- [ ] minimalChrome 模式渲染紧凑变体
- [ ] 斜杠面板有入场动画
- [ ] assembleHap SUCCESS

---

### M5:原语动画(B5)

**终态**:LiveDot / BlinkingCursor / DotLoading 三原语从静态 → 动画。

**ArkUI 方案**(已在 Index.ets/HistoryPage.ets 验证可用):
- LiveDot:animateTo 循环 scale 0.8↔1.2 + opacity 0.6↔1.0(呼吸,~1200ms)
- BlinkingCursor:animateTo 循环 opacity 1↔0(闪烁,~530ms)
- DotLoading:三个点错峰 opacity 波动(~400ms 间隔)

**实现**:`design/components.ets` 内用 `@State` + `aboutToAppear` setInterval 或 ArkUI 的 `animation` 属性循环。需验证 ArkUI 循环动画 API(若 setInterval 方案在 ArkTS 不可用,用 `keyframeAnimateTo` 或登记限制)。

**验收标准**:
- [ ] LiveDot 有呼吸动画(scale+opacity 循环)
- [ ] BlinkingCursor 有闪烁动画(opacity 循环)
- [ ] DotLoading 有波动动画(三点错峰)
- [ ] 动画不阻塞主线程(编码层面验证;真机性能待设备)
- [ ] assembleHap SUCCESS

---

### M6:QuickMessage 内容 store + /quick-* 命令(B7)

**终态**:Assistant 的 quickMessages 从「只有 ID」升级为「有内容 store」。斜杠面板支持 /quick-* 命令插入快捷消息内容。

**Android 基准**:`QuickMessagesPage.kt` + `QuickMessagesVM.kt` + `ExtensionContent.kt` 的 QuickMessage 类型。

**步骤**:
1. chat HAR:`quick_message.ts` — QuickMessage 接口{id, title, content} + KV store(load/save list)
2. barrel 注册
3. SlashCommandPanel.buildSlashCommands 追加 /quick-* 动态命令(读当前 assistant 的 quickMessageIds → 查内容 store)
4. ChatInput.handleSlashCommand 插入 quick message content
5. 测试:quick_message round-trip + buildSlashCommands 含 /quick-*

**验收标准**:
- [ ] QuickMessage 类型 + KV store 在 chat HAR,测试通过
- [ ] /quick-* 命令出现在斜杠面板(assistant 有 quickMessages 时)
- [ ] 选中 /quick-* 插入对应内容到输入框
- [ ] assembleHap SUCCESS

---

### M7:缺失设置页 — About / Sandbox / Files / CronTasks(B6)

**终态**:SettingPage 索引补全 4 个设置页入口。

| 页面 | 后端 | 内容 |
|---|---|---|
| SettingAboutPage | UI-only(BuildProfile) | 版本号 / 构建信息 / 开源链接 |
| SettingSandboxPage | 部分(sandbox activities 有) | 沙盒执行设置 |
| SettingFilesPage | 部分(managed_files/workspace 有) | 文件管理 / 工作区根目录 |
| SettingCronTasksPage | 部分(cron reschedule 有) | 定时任务列表 |

**步骤**:每页 ~80-150 行,读后端数据 + 渲染列表/toggle。路由注册 + SettingPage 入口。

**验收标准**:
- [ ] 4 页存在 + 路由注册 + SettingPage 入口
- [ ] 每页有真实数据渲染(非空壳;后端有数据的读后端,无数据的诚实空态)
- [ ] assembleHap SUCCESS

---

### M8:全 goal 收尾

**步骤**:
1. 全量门禁
2. 更新 progress.md / gap-audit.md / MIGRATION_STATE.yaml
3. subagent 四维 review(M1-M7 抽检关键项)

**全 goal 验收标准(全部满足才算完成)**:
- [ ] M1-M7 各自验收标准全勾
- [ ] chat 测试全绿 + tsc clean
- [ ] deepread 测试全绿 + tsc clean
- [ ] assembleHar + assembleHap SUCCESS
- [ ] git diff --check clean
- [ ] progress.md / gap-audit.md 更新
- [ ] **诚实标注**:所有"编码可做但效果待设备"的项(sendOnEnter 手感 / 动画效果 / 流式性能)在文档中明确标注"未经设备验证",不宣称"完成"

---

## 3. 明确不做(避免 unscoped ambition)

| 项 | 原因 | 去向 |
|---|---|---|
| LazyForEach 虚拟化 | 18+ 处重构 + 必须设备验证 | 设备解锁后独立 goal |
| GenerativeWidgetCard 真实渲染 | 需 WebView + 1995 行 Parser | 设备解锁后 |
| Mermaid / LaTeX | 需 Web 组件 + JS bundle | 设备解锁后 |
| TTSController | 需 TTS 引擎(6 文件 0 移植) | 设备解锁后 |
| video/audio picker | 平台 picker API | 设备解锁后 |
| @role MentionPanel | MentionRoles + detectMentionContext 光标逻辑复杂 | 后续 goal |
| /usage 命令 | usage 计算器后端缺失 | 后续 goal |
| pasteLongTextAsFile 内容接收 | ArkUI TextArea 无 contentReceiver | 验证平台能力后 |
| accentColor / fontFamily 选择器 | 需 UI 资源 + 设备渲染验证 | 设备解锁后 |
| 流式 Markdown 增量缓存 | 需设备验证性能改善 | 设备解锁后 |

---

## 4. 风险

| 风险 | 缓解 |
|---|---|
| M3 sendOnEnter ArkUI TextArea 不支持 onSubmit | 降级:设置页保留开关 + 登记限制,不伪实现 |
| M5 循环动画 ArkTS API 限制(setInterval 不可用?) | 备选:keyframeAnimateTo;最坏降级静态(不比现状差) |
| M4 FullScreen 编辑器 bindSheet 与既有 sheet 冲突 | 用独立 @State + 条件渲染,不复用既有 sheet 槽 |
| M6 QuickMessage store 与 Assistant.quickMessageIds 耦合 | store 独立于 assistant,仅按 ID 查内容 |
| 全 goal 无设备验证 | 每个里程碑验收标准只验证"编译+测试+行为闭环";"效果"类明确标注待设备,不宣称完成 |
