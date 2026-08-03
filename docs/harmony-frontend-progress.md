# AmberAgent 鸿蒙迁移 — 前端消息渲染层工作进度

> 更新时间：2026-08-03
> 分支：`harmony-preview`
> 仓库 HEAD：`69daef7ad`（TopModelMenu 顶部模型快速切换下拉）
> 前一个 HEAD：`483112715`（前端消息渲染层收尾）
> 前端 audit 文档：`docs/harmony-frontend-gap-audit.md`

---

## 增量更新（2026-08-03，`483112715..69daef7ad`）

### TopModelMenu — 顶部模型快速切换下拉

Android `TopModelMenu.kt`（275 行）→ 鸿蒙 ArkTS 移植。聊天页顶部模型标签点击从「跳全页选择器」升级为「内联手风琴下拉」：provider 分组可折叠，当前 provider 自动展开，点模型即切换并关闭；选中态仅 accent 色（对齐 Android）。

| 文件 | 行 | 功能 |
|---|---|---|
| `harmony/chat/.../top_model_menu.ts` | 96 | `buildTopModelMenuGroups` 纯逻辑（过滤 hasUsableAuth + ModelType + selected 标记）|
| `harmony/chat/src/test/top_model_menu.test.ts` | — | 17 测试（auth 过滤/type 过滤/selected/空态/混合场景）|
| `harmony/entry/.../components/TopModelMenu.ets` | 155 | 自含 dimmed scrim + 贴顶面板 + provider 分组手风琴 + 空态 + 全页入口 |
| `harmony/entry/.../pages/ChatPage.ets` | +50 | header chevron 触发 + Stack overlay + toggleModelMenu（重入守卫）+ selectModelFromMenu |

**数据流闭环**：toggleModelMenu（async loadProviders+getChatModelSelection → buildTopModelMenuGroups → 装配 → open）→ 点模型 → selectModelFromMenu（saveChatModelSelection + describeChatModel 刷新标签 + contextWindowTokens 刷新 → close）。

**与 Android 的对齐与差异**：
- ✅ accent-only selection / 自动展开当前 provider / 空态 / scrim 关闭 — 全对齐
- 差异：选择层复用既有 Preferences 契约（非 uuid 化，与 ChatModelPickerPage 一致）；backdrop 由组件自拥（dim 28% 黑 ≈ Android sheetBackdrop）；底部加「全部模型 / 管理」入口到全页选择器（Android 无此行，鸿蒙保留既有页不浪费）

**review 纪律**（subagent 四维 review）：
- 修 3 项：① [DEAD-CODE] onClose prop 从未被调用 → backdrop 从父级移入组件内部（对齐 Android 结构，onClose 现有意义）② [UX] 透明 backdrop → 加 dimmed scrim（28% 黑）③ [LOGIC] toggleModelMenu 无重入守卫 → 加 modelMenuLoading 门
- 驳 1 项：[UX] chevron 仅 async 后翻 = 本地 KV/Preferences 单帧读取，加 loading 态弊大于利

**组件数**：22 → **23**；chat 域逻辑 629 → **725 行**（+top_model_menu.ts 96）；新测试 51 → **68**（+17）。

**门禁**：chat 1628/1628 + tsc clean / deepread 610 + tsc clean / assembleHar+assembleHap SUCCESS / diff --check clean。

---

## 历史快照（2026-07-31，HEAD `483112715`）

> 以下为 `483112715` 时的完整进度记录，保留作历史基准。

## 0. 本次工作范围

从 `docs/harmony-frontend-gap-audit.md` 第一/二/三梯队推进前端消息渲染层：
- Markdown 渲染（从裸 Text → RichText+HTML）
- 消息组件族拆分（从全内联 ChatPage → 22 个独立 @Component）
- 通用 UI 原语层（从 5 个杂志风原语 → 23 个含交互原语）
- ChatInput 组件化 + 斜杠命令面板
- SubAgent/Council 设置页
- 全部经 subagent 四维 review（代码bug/逻辑闭环/调用链路/UX）

---

## 1. 代码量对比

| 维度 | Android 基准 | 本次开始 | 当前 | 覆盖率 |
|---|---|---|---|---|
| 消息渲染组件 | 10,020 行（17+7 文件） | 0 行 | **1,738 行（22 文件）** | ~17% |
| richtext（Markdown/高亮） | 9,947 行（23 文件） | 0 行 | **303 行（markdown_html.ts）** | ~3% |
| 通用 UI 原语 | 7,814 行（41 文件） | 99 行（5 原语） | **445 行（23 原语）** | ~6% |
| ChatInput 生态 | 7,518 行（12 文件） | 0 行 | **158 行（1 组件+1 面板）** | ~2% |
| chat 域逻辑新增 | — | 0 | **629 行**（message_grouping + markdown_html） | — |
| 新测试 | — | 0 | **51**（19 + 32） | — |

### 组件数变化

| | 开始 | 当前 |
|---|---|---|
| 前端组件 | 2（ArticleRenderer + StageProgressCard） | **22** |
| design 原语 | 5 | **23** |
| 页面 | 24 | **26**（+SettingSubAgentPage +SettingModelCouncilPage） |

---

## 2. 22 个前端组件清单

### 消息渲染（14 个）

| 组件 | 行数 | 功能 | 后端依赖 | 接入状态 |
|---|---|---|---|---|
| MarkdownRichText | 39 | Markdown→HTML→ArkUI RichText | markdownToHtml | ✅ ChatPage assistant text |
| ChatMessageReasoning | 80 | reasoning 三态卡片（秒数+折叠） | reasoning_display.ts D-053 | ✅ ThinkingBlock 内 |
| ChatMessageTools | 112 | 工具卡片（状态/审批/output预览/generate_image轮播） | tool_activity.ts D-122 | ✅ ThinkingBlock 内 |
| ChatMessageAskUserStep | 174 | ask_user 多问题表单 | ask_user_questions.ts D-105 | ✅ ThinkingBlock 内 |
| ChatMessageAvatar | 24 | 用户/助手头像（UIAvatar+文字） | UIMessage | ✅ MessageRow |
| ChatMessageBranch | 29 | 分支切换器 ◀ n/total ▶ | MessageNode | ✅ ActionFooter 内 |
| ChatMessageActions | 33 | 复制/分叉/编辑/重新生成 | — | ✅ ActionFooter 内 |
| ChatMessageActionFooter | 77 | 统一尾部（annotations+branch+actions） | resolveActionFooterMode | ✅ MessageRow |
| ChatMessageCommon | 48 | URL citation 引用注释（可点击打开） | UIMessageAnnotation | ✅ ActionFooter 内 |
| ChatMessageCopySheet | 87 | 复制消息 sheet（逐段/全部复制） | toText | ✅ bindSheet |
| ChatMessageToolPreviewSheet | 92 | 工具详情 sheet（input/output JSON+复制） | toolInputAsJson/toolOutputText | ✅ bindSheet |
| ChatMessageSubAgentStep | 128 | 子代理任务卡片（SubAgentAvatar+状态+目标） | subagent_models D-132a | ✅ SubAgentBlock |
| ChatMessageCouncilStep | 97 | Council 任务卡片（状态+议题） | toolOutputJson | ✅ CouncilBlock |
| SubAgentRunSheet | 74 | 子代理运行详情 sheet（实时 Markdown 文本） | SubAgentManager.subscribeLiveText | ✅ bindSheet |

### 输入（2 个）

| 组件 | 行数 | 功能 | 接入状态 |
|---|---|---|---|
| ChatInput | 158 | 三段式药丸输入（📎/TextArea/发送停止）+ 斜杠命令 | ✅ ChatPage |
| SlashCommandPanel | 77 | 斜杠命令面板（/clear /compact /deepread /subagent） | ✅ ChatInput 内 |

### 头像（2 个）

| 组件 | 行数 | 功能 | 接入状态 |
|---|---|---|---|
| UIAvatar | 48 | 通用头像（首字母/emoji/图片URL+稳定色） | ✅ ChatMessageAvatar |
| SubAgentAvatar | 51 | 子代理头像（首字母+状态点 running绿/failed红） | ✅ ChatMessageSubAgentStep |

### 图片/Widget（2 个）

| 组件 | 行数 | 功能 | 接入状态 |
|---|---|---|---|
| GeneratedImageCarousel | 71 | AI 生成图片水平轮播（Swiper+复制链接） | ✅ ChatMessageTools generate_image |
| GenerativeWidgetCard | 34 | 生成式 Widget 诚实占位（需 WebView+Parser） | ⏳ 待 WebView |

### DeepRead（2 个，既有）

| 组件 | 行数 | 功能 |
|---|---|---|
| ArticleRenderer | 156 | DeepRead 文章渲染（Text 直出，待 Markdown） |
| StageProgressCard | 49 | DeepRead 进度卡 |

---

## 3. 23 个 design 原语

### 聊天交互原语（对齐 Android AmberPrimitives.kt）

| 原语 | 功能 |
|---|---|
| AmberCard | 通用容器（surface 底+hairline+圆角） |
| Hairline | 1dp 发丝分割线 |
| BtnInk | 主操作按钮（ink 实心） |
| BtnAccent | accent 实心按钮 |
| LiveDot | 呼吸光晕圆点（静态 MVP，动画待设备验证） |
| BlinkingCursor | 闪烁终端光标（静态 MVP） |
| SectionLabel | accent // 前缀眉标 |

### 表单/反馈原语（对齐 Android ui/）

| 原语 | 功能 |
|---|---|
| FormItem | 标签+内容行 |
| AmberSwitch | 自定义开关（Toggle 封装） |
| AmberSelect | 下拉选择（Select 封装） |
| ConfirmDialog | 通用确认对话框 |
| ErrorCard | 错误信息卡片（可关闭） |
| JsonTree | JSON 树形展示（MVP 美化文本） |
| Tag | 标签 chip（选中态） |
| ImagePreviewDialog | 全屏图片预览（点击关闭） |
| DotLoading | 三点加载指示（静态，动画待设备验证） |
| ShareSheet | 分享面板（onCopy+取消） |
| ToggleSurface | 可折叠表面（标题切换展开/折叠） |

### 杂志风原语（既有）

| 原语 | 功能 |
|---|---|
| RubricHead | // accent 前缀小节标题 |
| LeadCard | 标题卡 |
| IndexRow | 带序号列表行 |
| EmptyState | 空态 |
| SkeletonRow | 加载骨架行 |

---

## 4. chat HAR 域逻辑新增

| 文件 | 行数 | 功能 | 测试 |
|---|---|---|---|
| message_grouping.ts | 326 | groupMessageParts 纯逻辑（ChatMessageCot.kt 全文移植） | 19 |
| markdown_html.ts | 303 | markdown→html 转换器（GFM 核心子集+安全防护） | 32 |

### message_grouping.ts

- `ThinkingStep` 命名 discriminated union（reasoning/tool/subagent_task/council_task）
- `MessagePartBlock` 命名 discriminated union（thinking/content/subagent/council）
- `groupMessageParts`：连续 reasoning+tool 折叠为 ThinkingBlock；连续 text 合并；image/video/audio/document/mini_app 独立 ContentBlock
- `coalesceSubAgentSteps`/`coalesceCouncilSteps`：同 run_id 工具调用合并
- `mergeDuplicateRunBlocks`：被 Text 拆开的同 run_id block 合并
- **已接入 ChatPage MessageRow**（ForEach getBlocks 分组渲染）

### markdown_html.ts

- 覆盖：标题/段落/粗斜体/行内代码/链接/图片/无序有序列表/代码块/引用/表格/水平线
- 安全：前置 escapeHtml（一次性）+ 行内代码占位符隔离 + URL scheme 白名单（http/https/mailto/tel）
- 降级：Mermaid/LaTeX 不识别→原样文本（诚实，不伪成功）
- **已接入 MarkdownRichText 组件**

---

## 5. ChatPage 集成状态

### MessageRow 调用链路（全部闭环 ✓）

| 回调属性 | UI 触发点 | ChatPage 方法 |
|---|---|---|
| onSwitch | ChatMessageBranch ◀/▶ | switchBranch |
| onRegenerate | ChatMessageActions 重新生成 | regenerate |
| onDelete | LongPressGesture 长按 | confirmDeleteMessage |
| onEdit | ChatMessageActions 编辑 | startEditMessage |
| onFork | ChatMessageActions 分叉 | forkAt |
| onApproveTool | ChatMessageTools 批准 | handleToolApproval |
| onDenyTool | ChatMessageTools 拒绝 | openDenyDialog |
| onAnswerTool | ChatMessageAskUserStep 提交 | handleToolAnswer |
| onOpenSubAgentRun | ChatMessageSubAgentStep 卡片点击 | openSubAgentSheet |
| onCopy | ChatMessageActions 复制 | openCopySheet |
| onOpenToolPreview | ChatMessageTools 头部点击 | openToolPreview |

### 4 个 bindSheet（全部 open/close 配对 ✓）

| Sheet | 打开 | 关闭 |
|---|---|---|
| sandbox | sandboxSheetOpen | onDisappear |
| subAgent | openSubAgentSheet (async) | closeSubAgentSheet + aboutToDisappear |
| copy | openCopySheet | closeCopySheet |
| toolPreview | openToolPreview | closeToolPreview |

### groupMessageParts 渲染（全部 part 类型覆盖 ✓）

- ThinkingBlock → 组级折叠头部 + 展开时遍历 steps（reasoning/tool/subagent_task/council_task）
- ContentBlock → text(MarkdownRichText)/image/video/audio/document/mini_app（全覆盖，无静默丢弃）
- SubAgentBlock → ChatMessageSubAgentStep
- CouncilBlock → ChatMessageCouncilStep
- pending tool 折叠守卫：含 pending 审批 tool 的 ThinkingBlock 禁止折叠

### 流式渲染

- streaming=true → footer hidden（不渲染无响应的操作按钮）
- send 路径 streamingTail + regen 路径 regenTail 均传 streaming=true

---

## 6. 设置页（新增 2 个）

| 页面 | 行数 | 功能 | 路由 |
|---|---|---|---|
| SettingSubAgentPage | 230 | enabled/mode/allowDynamic/maxConcurrentRuns/maxTurns/timeout/outputBudget | ✅ main_pages.json + ChatListPage |
| SettingModelCouncilPage | 190 | enabled/maxSeats/rounds/timeouts/showSeatOutputs | ✅ main_pages.json + ChatListPage |

---

## 7. review 纪律

全部批次经 subagent 四维 review（代码bug/逻辑闭环/调用链路/UX）：

| review 结论 | 次数 | 修复项 |
|---|---|---|
| APPROVED | 4 | — |
| CHANGES-REQUIRED | 13 | 全部修复 → 验证 |

### reviewer 发现的真实阻断（全部已修复）

1. 双重 HTML 转义（escapeHtml 前置+子处理器再转义冲突）
2. 行内代码未隔离（bold/italic 侵入 code 内容）
3. URL 注入无 scheme 白名单（javascript: 保留）
4. parseAskUserQuestions 参数类型错（传 part 对象而非 input 字符串）
5. askAnswerDisplayText 签名错（缺 parseAskedAnswers + 第三参数）
6. reasoning 秒数丢失（loading 无 title 时显示"思考中…"而非秒数）
7. toolStateLabel 键值偏差（用 approvalState.type 而非 output.length）
8. 纯图发送阻断（发送键守卫过严，缺 hasImages）
9. STEER 长按切断（停止键未挂 LongPressGesture）
10. 动态子代理 displayName 失效（只读 subagent_id，缺 custom_subagent.name）
11. pasteboard API 全错（DataRecord 不存在+构造非法+签名不匹配）
12. onOpenPreview 无触发（声明了但 build 内无 onClick）
13. subAgentMode 硬编码（三元运算符两分支相同）
14. 全角斜杠不支持（中文输入法兼容）
15. ERROR token 未导入（阻断编译）
16. citation startAbility 硬编码华为浏览器（非华为设备无反应）
17. reserved 态隐藏 citations（流式中引用消失）
18. 分支切换器丢失（ActionFooter 内部无 ChatMessageBranch）
19. user 操作行消失（resolveActionFooterMode user→hidden）
20. denyReasonText TextArea 缺 onChange（用户输入被丢弃）
21. 流式预览行渲染无响应按钮（streaming 行 footer 应 hidden）
22. isAssistant 死代码（声明+传入但从不引用）
23. 设置页无路由（main_pages.json 未注册+无导航入口）
24. enabled ArkUI 保留名冲突（@State enabled 与 CustomComponent.enabled）
25. size ArkUI 保留名冲突（@Prop size 与 CustomComponent.size）
26. ChainOfThought 死代码（被内联取代后零引用）
27. pending tool 折叠守卫缺失（折叠后审批按钮不可见）

### 非阻断建议处理（精准判断）

| 建议项 | 判断 | 处理 |
|---|---|---|
| ShareSheet onCopy 缺失 | 真问题 | ✅ 修 |
| getImageUrls 重复调用 | 真问题 | ✅ 修（hasImageOutput 轻量判断） |
| getNonBlankTextParts 重复调用 | 真问题 | ✅ 修（去掉 ForEach 内分隔线判断） |
| title urlDecode | 过度防御 | ❌ 不修（parse 层偏差，非前端 bug） |
| DotLoading Text→Circle | 样式偏好 | ❌ 不修（静态三点够用） |
| displayName/currentStatus 重复 | 数据量小 | ❌ 不修（不是 bug） |
| /deepread 后面板抖动 | FALSE-POSITIVE | ❌ 不修（尾部空格使 isSlashVisible=false） |

---

## 8. 仍然缺的前端

### 组件级缺口

| 缺失组件 | Android 行数 | 依赖 | 优先级 |
|---|---|---|---|
| ChatMessageRenderers | 436 | richtext + GenerativeWidgetParser | 高（依赖 WebView） |
| ChatMessageMessagePartsBlock | 565 | groupMessageParts 已就绪 | 中（当前内联替代） |
| ChatMessageVirtualItems | 544 | LazyForEach | 中（需设备验证） |
| GenerativeWidgetCard 真实渲染 | 1995 | WebView + Parser | 高（当前占位） |
| ChatInputComposers | 1030 | 斜杠面板增强 | 中 |
| ChatInputAttachments | 603 | 相机/文件 picker | 低（依赖设备） |
| ChatInputUsage | 663 | 用量查询 | 低（ChatPage 已有 Progress Ring） |
| ChatInputSandbox | 1161 | WebView 沙盒 | 低 |
| TopModelMenu | 274 | 模型切换 | 中 |
| ReasoningPicker | 303 | 推理深度 | 中 |
| TTSController | 213 | TTS 引擎 | 低（依赖设备） |

### 渲染质量缺口

- RichText 实际渲染效果未验证（设备 blocked，Gate 0 未解锁）
- 流式 Markdown 全量重建可能闪烁（RichText 每 chunk 重解析）
- 代码块无语法高亮（RichText 内 pre/code 无高亮）
- Mermaid/LaTeX 不支持（降级为纯文本）
- LazyForEach 未做（ForEach 全量构建，长会话性能隐患，cachedCount(5) 缓解）

### 设置页缺口

- SettingPage（设置入口索引页）
- SettingDisplayPage（显示设置）
- SettingAboutPage（关于页）
- per-role override 编辑 UI（SettingSubAgentPage 内）
- custom definition 编辑 UI（SettingSubAgentPage 内）
- 席位编辑 UI（SettingModelCouncilPage 内）

### 原语动画缺口

- LiveDot 呼吸动画（当前静态圆点）
- BlinkingCursor 闪烁动画（当前静态竖线）
- DotLoading 跳动动画（当前静态三点 opacity）
- 发送键 pressable 缩放（当前无按压反馈）

---

## 9. 门禁

```
chat: 1611/1611 pass, tsc clean
deepread: 610/610 pass, tsc clean
assembleHar: BUILD SUCCESSFUL
assembleHap: BUILD SUCCESSFUL
git diff --check: clean
```

---

## 10. 提交历史（30 个）

```
483112715 fix(ui): 删 ChainOfThought 死代码 + pending tool 折叠守卫
3e8fcdd59 feat(ui): 接入 UIAvatar/SubAgentAvatar + ThinkingBlock 组级折叠
7c5120a91 fix(ui): 设置页路由注册 + 导航入口 + 死代码清理 + enabled 保留名修复
eca8218c6 feat(ui): SettingModelCouncilPage Council 实验设置页
750d573a7 feat(ui): SettingSubAgentPage 子代理实验设置页
086569576 feat(ui): ChainOfThought 思维链折叠容器
a3b1295b3 feat(ui): UIAvatar + SubAgentAvatar 头像组件
669b2c1c0 fix(ui): 修复 3 项调用链路断裂
4fb37731c fix(ui): 精准修复 3 个 review 非阻断项
54fac0b78 fix(ui): 修复分支切换器丢失 + user 操作行消失(调用链路断裂)
8f6668e30 feat(ui): ActionFooter 接入 ChatPage + 斜杠命令过滤
080d49acf fix(ui): review 修复(ERROR import/citation startAbility/annotations visible)
df24eb7a6 feat(ui): 更多 UI 原语(ImagePreviewDialog/DotLoading/ShareSheet/ToggleSurface)
d0827d29d feat(ui): ChatMessageAnnotations + ChatMessageActionFooter
db6d7eaf9 fix(ui): subAgentMode 正确传递 + 全角斜杠支持
37c5244ad feat(ui): SlashCommandPanel 接入 ChatInput + Council run sheet
1b9230152 feat(ui): SlashCommandPanel 斜杠命令面板
50898c01a fix(ui): 工具卡片头部行 onClick 触发 onOpenPreview
91da05fc1 feat(ui): GeneratedImageCarousel 接入 ChatMessageTools
2ab9b68a6 feat(ui): CopySheet + ToolPreviewSheet 接入 ChatPage
ecb2062c8 feat(ui): SubAgentRunSheet 升级 MarkdownRichText 渲染
a8d03b311 feat(ui): 通用 UI 原语补充(FormItem/Switch/Select/ConfirmDialog/ErrorCard/JsonTree/Tag)
b77d79caa feat(ui): GeneratedImageCarousel + GenerativeWidgetCard 组件
249361a53 feat(ui): SubAgent run sheet 接入(bindSheet + subscribeLiveText)
153a49a2f feat(ui): ChatMessageSubAgentStep + ChatMessageCouncilStep 聚合 UI
97af34ccb feat(ui): ChatMessageCopySheet + ChatMessageToolPreviewSheet 组件
3453c49f1 perf(ui): 第三梯队 G — List cachedCount(5) 预渲染
9ec065ea4 feat(ui): 第二梯队 F — ChatMessageAvatar/Branch/Actions 三件拆分
8f00fc64b feat(ui): 第二梯队 E — ChatInput 组件化 + 通用 UI 原语层
0ce1d352b feat(ui): 前端消息渲染第一梯队 — Markdown 渲染 + 组件拆分 + groupMessageParts 接入
```
