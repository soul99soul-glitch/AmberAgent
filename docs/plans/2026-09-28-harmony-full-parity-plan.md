# 鸿蒙版全量补齐计划(D0–D8)

> 日期:2026-09-28。上游分析:2026-09-28 全量缺口复核(四路 subagent 核实,结论见会话记忆 harmony-remaining-gaps-audit;基准图 = [amber-final-feature-mindmap.md](../amber-final-feature-mindmap.md))。
> 上游计划:[2026-09-27-harmony-parity-execution.md](2026-09-27-harmony-parity-execution.md)(C0–C6 已闭环,本计划接续)。
> 设计规范:`docs/plans/2026-09-27-harmony-secondary-ios-alignment.md` + 交接胶囊(sess_165b90ed):**设置根页 Android 风 / 二级页 iOS 风(PlainSection + CardGroup r18 描边 + CardRow 52/h14)**,组件一律复用 `components/PageTemplate.ets`,token 用 `design/tokens.ets`,accent 走 `@StorageProp('accentColor')`,滑杆 accent 化。
> 铁律:`.agents/skills/harmony-arkts/SKILL.md`(AppStorage 禁 export let / API 12 基线 canIUse / V1 状态 / @BuilderParam lambda 调 @Builder / 新 API 查文档 / @State 数组整体赋值)。

## Phase 工作方式(每个 phase 必须走完)

1. 实施:精准最小改动,不过度防御/兜底/设计;动手前先核实缺口真实存在。
2. 静态:`harmony/scripts/lint-arkts.sh <改动文件>` —— 门槛 0 error(基线 126 warn 不涨)。
3. 构建:`node scripts/harmony-inventory/build-harmony-app.mjs`(错误 grep "ArkTS Compiler Error")。
4. 审查:派 subagent 按三清单出 PASS/FAIL + file:line 证据:
   - **A 逻辑闭环**:状态写入→消费链路完整;开关有真实消费者;无死代码/半接线。
   - **B 调用链**:页面路由/参数/回调/持久化 KV 键一致;无悬空引用。
   - **C UI 对齐**:对照设计规范查错位/对齐/边距间距/大小(CardRow minH52、分隔线内缩 54/14、GUTTER 16、caption 12 muted、卡片 r18+0.5 描边)。
5. 修阻断项 → 真机截图目测(UI 改动必做,设备 6HK0226627000384)→ **立即提交**(每 phase 一个 commit)。

## 范围裁定(用户拍板前按此执行)

**纳入**:🔵 共通残留 7 项 + 登记缓办中可端内落地的 + 🔴/🟢 独占中无平台/架构阻断的。

**登记不做(诚实声明,不造假入口)**:
| 项 | 原因 |
|---|---|
| 终端 SSH / Mosh / 内嵌 Python | rust-napi 原生批次(C6-2 结论,ohpm 无 SSH 库,需交叉编译管线) |
| 多会话并行生成 | 生成管线宿主架构级重构(单 abortController + 全局 chatGenerating),独立批次 |
| 应用内多语言 | AGENTS.md:未明确要求不做本地化 |
| iCloud + Amber Pro 订阅 | Apple 平台服务 |
| 今日看板 | 产品方向已下线 |
| 议会外部 CLI 席位 | 依赖终端运行时 |
| 群聊默认模型槽 | 群聊功能本体不在鸿蒙(全仓零命中) |
| Synara / ZCode 分享链接 | 伴生服务,协议与服务端不在本仓 |
| 个人上下文注入 | READ_CONTACTS 被 profile ACL 卡(9568289),照片/日记无数据源 |
| 设备绑定加密 | 与跨设备恢复冲突,产品层面需先定语义 |
| WebMount 远程桌面(Moli/Playwright/Steel) | 依赖外部桌面服务 |
| 按用途 Provider Override(D3-4) | 任务槽数据模型(uuid → (providerId,modelId) 对)+ 全链路解析改造,独立批次 |
| Vertex AI 服务账号(D3-5) | RS256 JWT 签名 + onboarding 流,独立批次(保持 google_chat_api.ts:53 抛错登记) |
| Antigravity OAuth(D3-6) | Gemini Code Assist v1internal 包装 + onboarding,独立批次(保持 :79 抛错登记) |
| Provider 内置工具 url_context / Google 侧(D3-3 余量) | OpenAI Responses 无此工具;Google 侧需 ChatModel.tools 管线改造,P1 登记 |
| Jev 审批分诊 / Web 自动化(D5-1 余量) | 分诊需审批卡协议改造、Web 自动化依赖 WebMount 远程能力,独立批次 |
| 议会席位联网搜索 / FULL 功率(D5-3 余量) | 需席位级工具循环(runner 现为纯文本流式),独立批次 |
| 记忆主题聚合派生文档(D5-4 余量) | iOS 独占大型子系统(聚类+派生文档+查看 UI),独立批次 |
| 小说章节裁决卡 / 章节计划提案专用 UI(D7-1/D7-2) | 通用管线已闭环:候选评审原子提交(裁决语义)+ novel_workspace_write patch plan/this-chapter.md 走提案卡 diff→验收写回;专用 UI 为锦上添花,backlog |

---

## D0 · 接线与显示小项批(13 项,一个提交组)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D0-1 | Continue 卡补图片生成源 | `continue_candidate.ts` + `ChatListPage.ets mergeContinueCandidates`;源=生图产物登记(FilesRepository/ImageGenTool 现状核实后定:进行中任务 or 最近产物) | 有生图产物时首页出现继续卡,点击直达 |
| D0-2 | 图片保存系统相册 | `GeneratedImageCarousel.ets` + 消息图片查看处加保存按钮;`photoAccessHelper` 保存 API(查文档,canIUse) | 生成图可一键存相册,有成功/失败反馈 |
| D0-3 | 议会连通性测试 | `SettingModelCouncilPage.ets` 席位卡加测试动作:逐席位发最小 chat 请求,显示成功/延迟/失败原因 | 测试结果真实反映各席位连通性 |
| D0-4 | 显示用户头像开关(修错绑) | `display_setting.ts` userAvatar 字段 + `SettingDisplayPage.ets` 加开关行 + ChatPage 头像渲染消费 | 开关落 KV 且聊天页头像显隐生效 |
| D0-5 | 消息跳转器完善 | `ChatPage.ets`:消费 `messageJumperOnLeft`(按钮左右位移)+ 加回顶部按钮 | 设置换侧即时生效 |
| D0-6 | 生成完成触觉反馈 | `display_setting.ts enableMessageGenerationHapticEffect` 已有 → ChatPage 生成完成处调 `@ohos.vibrator`(声明 ohos.permission.VIBRATE,查文档) | 开关开时生成完成震动 |
| D0-7 | 全屏编辑器接线 | `AmberTextArea.ets`(已写好零消费者)接入 `ChatInput.ets` 长按/展开入口 | 输入区可进全屏编辑,内容回写 |
| D0-8 | @提及接线 | `MentionPanel.ets` + `mention_roles.ts` 接入 `ChatInput.ets`/@ 触发;选中角色写入消息(对齐既有 slash 命令分发) | 输入 @ 弹面板,选中子代理/议会角色生效 |
| D0-9 | 输入区联网搜索开关 | `ChatInput.ets` 加 toggle ↔ `ChatSearchSettingsPage.ets` 的 enableWebSearch KV(同一键) | 输入区开关与设置页双向同步,工具门控生效 |
| D0-10 | 输入草稿跨会话保留 | `ChatPage.ets`:按 conversationId 存草稿 KV,切换保存/恢复 | 切会话回来草稿还在 |
| D0-11 | AMOLED 纯黑选项 | `tokens.ets`/`SettingAppearancePage.ets`:深色模式下纯黑画布开关(AppStorage 驱动) | 开关后深色背景变纯黑,全 UI 即时刷新 |
| D0-12 | 聊天产物 diff 视图 | 产物架(C0-9 入口)加版本对比:同名产物两版本逐行 diff(复用 soul diff 的诚实行级摘要模式) | 有版本产物可查看差异 |
| D0-13 | 死代码清理 | 删 `ChatTheme.ets`(三主题零消费者)、`QRCode.ets`(零引用);`ProviderBalanceText.ets` 留待 D3-1 接线 | 删除后全仓零引用,lint 不涨 |

## D1 · 深读模板 + TTS(3 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D1-1 | 深读自定义 HTML 版式模板 | `DeepReadTemplate.ets` 扩展:自定义模板存储+编辑器页(替换 redirect 壳 `DeepReadTemplateSettingsPage/WorkbenchPage`)+ 严格安检(script/外链/on* 属性剥离,对齐内置渲染器 URL 白名单)+ 渲染消费 | 自写模板可保存、可选中、正文按模板渲染,恶意标记被剥离 |
| D1-2 | 模板工坊(NL→AI 生成) | 模板编辑器页加「AI 生成」:描述 → LLM 产 HTML → 过 D1-1 安检 → 示例数据预览 → 保存 | 端到端生成可采用,安检不合格有提示 |
| D1-3 | TTS 系统引擎试听 | `SettingDisplayPage.ets` 或外观页加「语音试听」卡:CoreSpeechKit textToSpeech(查离线文档,canIUse 包裹)+ 语速档位 | 点试听真实发声,语速可调 |

## D2 · Workspace 自选目录 + MiniApp 编辑器(2 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D2-1 | 用户自选工作区目录 | `SettingSandboxPage.ets` 加目录选择:DocumentViewPicker.select(folder 模式)+ fileshare persistPermission 持久化授权(查文档+ACL 核实,不可行则保持 filesDir 并诚实标注) | 选定目录后 workspace_files 工具读写落在该目录 |
| D2-2 | MiniApp 源码编辑器 | `MiniAppRunnerPage.ets` sourceDialog 升级为编辑页:行号+语法高亮(复用 chat 代码高亮)+ 实时预览(半屏 Web)+ 保存走既有校验入库管线 | 编辑→校验→保存→运行闭环 |

## D3 · 服务商完整化 2(6 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D3-1 | 账户余额 API | 移植 getBalance(balanceOption 配置已在 ProviderDefaults):HTTP 查询 + `ProviderBalanceText.ets` 接线显示(替换占位) | provider 详情页显示真实余额,失败降级 '~' |
| D3-2 | Provider 文件导入 | `ChatProviderDetailPage.ets` 导出 JSON 的逆通道:DocumentViewPicker.select → 校验 schema → 新建 provider(不含 key 提示补填) | 导出的 JSON 可再导入成新 provider |
| D3-3 | 内置工具 UI + url_context | `ChatModelParamsPage.ets` 补 builtInTools 开关区;`openai_responses_request.ts` 补 url_context 分支;Google 侧评估 | 开关落库且请求体带对应工具声明 |
| D3-4 | 按用途 Provider Override | `ChatTaskModelPickerPage.ets` 槽位从模型 uuid 扩为 (providerId, modelId) 对 + 解析处按对取 provider | 任务槽可跨 provider 覆盖,运行走指定 provider |
| D3-5 | Vertex AI 服务账号 | `google_chat_api.ts:53` 抛错处:SA JSON → JWT(RS256,查 @kit crypto 能力)→ access_token → 请求;不可行则保持抛错登记 | 可行则 SA 登录可用 |
| D3-6 | Antigravity(Gemini Code Assist OAuth) | 调研 v1internal 包装 + onboarding(参考双端);`google_chat_api.ts:79` 抛错处落地或保持登记 | 可行则端到端登录;不可行维持诚实抛错 |

## D4 · 备份完整化(2 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D4-1 | 恢复范围按类别勾选 | `sync_snapshot.ts` restoreSyncBackup 加类别参数 + `SettingBackupPage.ets` 恢复预览处勾选(会话/助手/服务商/设置) | 勾选生效,未选类别不动 |
| D4-2 | 按类别导出 | createSyncBackup 加类别输入 + 导出页勾选 | 可只导出所选类别,恢复端兼容 |

## D5 · Agent 体系(4 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D5-1 | Jev 其余五用途 | `JevSupport.ets` 登记处:记忆召回(memory_recall 注入)/上下文选择/模型路由/审批分诊/Web 自动化,逐用途接 OFF/SHADOW/ACTIVE + 失败回退;设置页按真实接线展示 | 每用途 SHADOW 有指标,ACTIVE 影响行为且可关 |
| D5-2 | 子代理模型池 UI + 悬浮条收起 | `SettingSubAgentPage.ets` 加模型选择器(消费既有 modelId override);`SubAgentActivityStrip.ets` 加完成后延时收起 | 模型池落库生效;条完成后 N 秒自收 |
| D5-3 | 议会增强 | 动态席位生成(主持人按问题生成席位)/席位联网搜索/FULL 功率(主持人只读工具编排)——逐项评估最小实现,能落地的落地 | 落地的模式真实可选可用 |
| D5-4 | 记忆污染检测 + 主题聚合 | `memory_extractor.ts` 提炼门加 web/mcp 触碰维度;Dream 加主题聚合派生文档(参考 iOS 语义最小实现) | 触碰会话暂停提炼;聚合文档可查看 |

## D6 · WebMount 完整化 2(3 组,逐项裁定)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D6-1 | 高级子系统第一批 | `WebMountTools.ets`:signed_fetch(带站点凭据 fetch)/network_inspect(请求日志)/observe(页面变更观察)/oauth_connect+refresh(通用化飞书已有流) | 工具注册可调,安全收口沿用逐导航 gate |
| D6-2 | 高级子系统第二批 | site_adapter/site_memory/profile_synthesize/recipe_candidates/fetch_replay/run_goal——逐件评估端内可行性,不可行保持登记 | 落地的真实可用;不落地更新登记注释 |
| D6-3 | 飞书文档工具族 + Cookie 导入 | feishu_docs list/read/blocks/search/create/append(用已有 OAuth token);Cookie 逐行导入(站点卡加导入入口,写 Web Cookie) | 飞书文档读写调通;导入 Cookie 生效 |

## D7 · 小说 + 渲染(4 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D7-1 | 章节裁决 | 参考 iOS 语义:对章节候选/冲突出裁决卡(通过/驳回+理由),接提案审批管线 | 裁决闭环落库 |
| D7-2 | 章节计划提案→验收 UI | 「本章计划」从手动字段升级为 Agent 提案卡(提案→diff→验收写回) | 提案验收闭环 |
| D7-3 | Mermaid 渲染 | 消息流 mermaid 代码块:本地打包 mermaid.js + ArkWeb 沙箱渲染(禁外链),失败降级源码;导出图片(componentSnapshot) | 常见图类型渲染正确,可导出 PNG |
| D7-4 | 长图导出 + 复制格式 | `ChatExportSheet.ets` 图片导出落地(componentSnapshot 分段拼接);`ChatMessageCopySheet.ets` 加复制格式选择(纯文本/Markdown) | 长图可导出分享;复制可选格式 |

## D8 · Recipes + 动态插件(评估后裁定)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| D8-1 | Recipes | 调研 iOS 实现(≤8 步声明式工作流注册为工具):端内最小闭环(解析→注册→工具执行→账本)可落地则落地 | 落地则示例 recipe 可调;否则登记 |
| D8-2 | 动态插件 | 调研 iOS 实现(试运行+expected_result+hash 注册+插件中心);与 D8-1 复用度评估 | 同上 |

---

## 里程碑

- **M5(D0–D2)**:小项清零 + 内容模块完整 —— 🔵 共通残留抹平
- **M6(D3–D4)**:设置面完整 —— 服务商/备份无占位
- **M7(D5–D6)**:Agent 面完整 —— 缓办大头清零
- **M8(D7–D8)**:独占功能裁定落地 —— 产品完整

---

## 收尾记录(执行中)

**已完成提交**:D0(13 项,审查 3P1+4P2 已修,真机验证)/ D1(3 项,审查 1P1 已修,真机验证)/ D2(2 项,审查 1P0+2P1 已修,真机验证自选目录全链路)/ D3(3 项落地+3 项登记,构建过;**D3 的 subagent 审查与真机截图待补**——窗口资源耗尽前提交,下窗口先补审 D3 再进 D4)。

**下窗口接续点**:
1. 先补 D3 subagent 审查(余额解析/导入互通/内置工具消费链)+ 真机服务商页截图。
2. D4 备份完整化(恢复范围勾选/按类别导出)→ D5 Agent 体系 → D6 WebMount → D7 小说+渲染 → D8 Recipes/插件评估。
3. 设计规范/铁律/验证回路见本文件头部;sess_165b90ed 胶囊 = 二级页 iOS 风骨架。
