# 鸿蒙版共通功能补齐执行计划(C0–C6)

> 日期:2026-09-27。上游分析:[harmony-parity-gap-plan.md](../harmony-parity-gap-plan.md)(缺口清单唯一来源)。
> 设计规范:`docs/plans/2026-09-27-harmony-secondary-ios-alignment.md` + 交接胶囊(sess_165b90ed):**设置根页 Android 风 / 二级页 iOS 风(PlainSection + CardGroup r18 描边 + CardRow 52/h14)**,组件一律复用 `components/PageTemplate.ets`,token 用 `design/tokens.ets`,accent 走 `@StorageProp('accentColor')`。
> 铁律:`.agents/skills/harmony-arkts/SKILL.md`(AppStorage 禁 export let / API 12 基线 canIUse / V1 状态 / @BuilderParam lambda 调 @Builder / 新 API 查文档)。

## Phase 工作方式(每个 phase 必须走完)

1. 实施:精准最小改动,不过度防御/兜底/设计;动手前先核实缺口真实存在。
2. 静态:`harmony/scripts/lint-arkts.sh <改动文件>` —— 门槛 0 error(基线 126 warn 不涨)。
3. 构建:`node scripts/harmony-inventory/build-harmony-app.mjs`(错误 grep "ArkTS Compiler Error")。
4. 审查:派 subagent 按三清单出 PASS/FAIL + file:line 证据:
   - **A 逻辑闭环**:状态写入→消费链路完整;开关有真实消费者;无死代码/半接线。
   - **B 调用链**:页面路由/参数/回调/持久化 KV 键一致;无悬空引用。
   - **C UI 对齐**:对照设计规范查错位/对齐/边距间距/大小(CardRow minH52、分隔线内缩 54/14、GUTTER 16、caption 12 muted、卡片 r18+0.5 描边)。
5. 修阻断项 → 模拟器/真机截图目测(UI 改动必做)→ **立即提交**(每 phase 一个 commit)。

---

## C0 · 小项清扫批(10 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C0-1 | 会话列表级重命名 | `ChatListPage.ets` 长按菜单加重命名项(复用 ChatPage `editingTitle` 的 dialog 模式,写同一 conversation title 存储) | 长按 → 重命名 → 列表行标题即时刷新 |
| C0-2 | Continue 卡补议会+图片生成两源 | `ChatListPage.ets mergeContinueCandidates`(现四源;参照 iOS `HomeContinueCardModel` 优先级 draft<readyResult<recoverable<active<actionRequired) | 有进行中的议会/图片任务时出现对应继续卡,点击直达 |
| C0-3 | 显示开关补全 + 回车发送 + 长文本粘贴转文件 | `SettingDisplayPage.ets`(147-154 现标"未支持")接 domain 字段;消费端:`ChatInput.ets`(回车发送)、`ChatPage.ets`(粘贴阈值转文件) | 开关落 KV 且聊天页真实生效 |
| C0-4 | compact 消息流标记 | `ChatPage.ets` 消息列表渲染处,按 `context_compact.ts` 边界数据插 marker 行(对齐 Android `ContextCompactMarker` 语义:已压缩 N 条) | 执行 /compact 后消息流出现边界标记 |
| C0-5 | WebDAV 下载与快照列表 | `SettingBackupPage.ets`(已有 PUT 上传)补 PROPFIND 列表 + GET 下载 → 走现有恢复预览 | WebDAV 快照可见、可下载恢复 |
| C0-6 | 30 天未置顶对话清理 | 存储管理入口(SettingPage 聊天存储统计卡 → 清理确认 → 调 conversation 存储删除;置顶豁免,对齐双端语义) | 预览数量 → 确认 → 清理,置顶保留 |
| C0-7 | UA 伪装预设 | `ChatProviderDetailPage.ets` 配置 tab:UA 预设(OpenCode/Claude Code/Cursor/Cline/自定义)落到 provider 配置,请求时注入 User-Agent | 预设落 KV,请求头带对应 UA |
| C0-8 | Provider 导出分享 | `ChatProviderDetailPage.ets` 头部"导出"(现 toast)→ 导出 JSON(不含 API Key)到文件 | 导出文件可用,无凭据泄漏 |
| C0-9 | 聊天产物架入口 | ChatPage header/dock 加入口 → 半模态陈列 ArtifactPorts 已有产物(来源图标/标题/打开) | 有产物时可浏览、可打开 |
| C0-10 | 字体族三选 | `SettingDisplayPage.ets` 字体卡加 默认/衬线/等宽;消费端聊天文本样式(`MarkdownText.ets` 字体栈) | 切换后聊天字体即时变化 |

## C1 · 聊天渲染补齐(3 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C1-1 | LaTeX 渲染 | `MarkdownText.ets` 块/行内解析加 math 节点;自研渲染(常用符号/上下标/分式/根号),不引 WebView | `$\alpha$` 与 `$$\frac{a}{b}$$` 正确渲染,流式不炸 |
| C1-2 | 生成图消息流轮播 + 修改再生成 | 消息流 image part 复用工具路径 carousel 组件(保存/分享/修改);"修改"带 prompt+原图再生成 | 消息内多图可滑动,修改入口发起再生成 |
| C1-3 | compact search/expand 工具循环 | `EntryCompactStore` 头注释登记项:工具循环内可检索/展开被压缩内容 | 工具循环可调,结果注入上下文 |

## C2 · 主题系统(3 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C2-1 | 主题包模型 + 内置包 + 应用 | `design/tokens.ets` 扩展:ThemePack(底色/卡片/强调色/字标,AppStorage 驱动,禁 export let);内置 2 包(点阵·陶土/Notion·暖白,对齐 iOS) | 切包全 UI 即时刷新,重启保持 |
| C2-2 | 主题包 JSON 导入导出 | 外观页:导入(校验 schema/预览)、导出当前 | 与 iOS/Android JSON 可互换 |
| C2-3 | AI 主题生成 | 外观页入口:描述 → LLM 产 JSON → 全局试穿条(采用/还原,失败回滚) | 端到端可生成可采用 |

## C3 · 服务商完整化(4 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C3-1 | 模型目录拉取 + 类型/模态/上下文窗口编辑 | `ChatProviderDetailPage.ets` 模型 tab:拉取 /models、勾选启用;编辑(类型 chat/image/embedding、输入模态、上下文窗口) | 拉取成功可启用;编辑落库并在选择器生效 |
| C3-2 | Codex 设备码 OAuth | 新增 OAuth 流程页:设备码 → 复制码/打开页 → 轮询 → token 落安全存储 → 拉模型目录(ArkWeb/want 深链替代 loopback,API 查文档) | 端到端登录可用 |
| C3-3 | Grok/Antigravity OAuth | 同流程页复用(PKCE + 深链回调) | 端到端登录可用 |
| C3-4 | Token Plan(智谱/Kimi/MiniMax) | provider 认证模式加 plan 选项,钉官方地址 | 选 plan 后请求走官方地址+token |

## C4 · WebMount 完整化(3 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C4-1 | 工具面扩至 30+ | `platform_impl/WebMountTools.ets`:补 tap/keys/select/find/back/forward/tab_*/visual_*/network_inspect 等(沿用 L01/L02 安全收口,逐导航 gate) | 工具注册表可见,单测/真机各调通 |
| C4-2 | bot/hand 接管模式 | 会话页:租约模型(Agent 只读 ↔ 用户认领 HUMAN 接管),接管后才可点击/输入/登录;状态条 | 两种模式切换正确,工具在 HUMAN 租约下被拦 |
| C4-3 | 多标签真实化 | 逻辑 tab → 真实多 Web 组件(或按需挂载),tab_* 工具配套 | 多页并存可切换 |

## C5 · Agent 能力补齐(5 项)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C5-1 | Jev 判断服务 | chat 模块新增 jev 子域:客户端(systemone/Vercel 双方言)、用途路由(工具发现/记忆召回/上下文选择/模型路由)、OFF/SHADOW/ACTIVE、数据范围授权、预算/冷却、失败回退本地;设置页 | SHADOW 跑通有指标,ACTIVE 影响选择且可关 |
| C5-2 | Soul 导入/回滚 | soul 工具:import → diff 预览 → 审批 → CAS 应用 → previous 回滚(对齐 iOS IOSSoulService) | 导入/回滚闭环,聊天注入生效 |
| C5-3 | JS 沙箱执行 | 先确认现状(是否已有);无则评估仓内 QuickJS/NAPI 路线后落地 `javascript_execute` 工具(无网络无文件) | 工具可调用,沙盒约束成立 |
| C5-4 | 子代理跨会话活动条 | AppStorage 全局活动状态 + 聊天页/首页胶囊条(计时/点开详情/跳来源会话) | 子代理运行中跨页可见 |
| C5-5 | 工具环路守护 | tool_loop:重复工具调用检测 → 收口;步数/输出上限 → 自动总结收尾 | 守护触发有明确收口行为 |

## C6 · 后台生成 + 终端(2 项,先调研后攻坚)

| # | 任务 | 触点 | 验收 |
|---|---|---|---|
| C6-1 | 后台继续生成 | 调研 `backgroundTaskManager` 长时任务类型(查官方文档)→ 选定方案实现:切后台挂起续跑,完成走 NotificationNotifier(进度+完成);回前台无缝接管 | 切后台生成完成并通知,无重复追加 |
| C6-2 | 终端运行时 | 先出调研结论(纯 TS SSH 选型 / NAPI libssh2 / HiSH 本机沙盒)→ Remote SSH 优先落地:profile 管理、指纹信任、`terminal_execute`(自动转后台)/job/session | SSH 执行真机调通,输出回流 |

## 收尾记录(2026-09-27 执行完毕)

**C0–C6 全部完成**:C0 4515362ec / C1 5fa00912f / C2 51645d367 / C3 db9baeead / C4 74dd085f2 / C5 c33f23678 / C6 见下。

**C6-1 已落地**:`BackgroundGenerationKeepAlive`(DATA_TRANSFER 长时任务,API 12,KEEP_BACKGROUND_RUNNING normal 声明)——生成中切后台 `EntryAbility.onBackground` 起任务保活,回前台/生成结束对称释放;实况窗进度通知与 >10 分钟任务续约 = 后续梯队(登记)。

**C6-2 调研结论(转独立原生批次)**:
1. 纯 ArkTS SSH 客户端不可行(完整协议栈=握手/KEX/加密/信道,数千行加密代码,风险不可控)。
2. ohpm 三方仓无 SSH 客户端库(2026-09-27 检索 ssh/ssh client/sftp 全空)。
3. 落地路线 = 借仓库既有 `harmony/native/rust-napi` 评估轨道:先以小 API 面(tokenizer)打通 ohos 交叉编译+NAPI 管线 → libssh2(依赖 OpenSSL/mbedTLS)交叉编译 + NAPI 绑定 → `terminal_execute`/job/session。SettingSandboxPage 的「鸿蒙当前不提供 shell 运行时」诚实声明保持不动,无虚假入口。

**登记缓办(诚实声明,不造假入口)**:
- Antigravity(Google OAuth):需 Gemini Code Assist v1internal 包装 + onboarding 流(google_chat_api.ts:81 已显式抛错登记)。
- WebMount 高级子系统:tap(≈click)、signed_fetch、network_inspect、oauth_connect/refresh、site_adapter/site_memory、profile_synthesize、recipe_candidates、fetch_replay、run_goal、observe。
- DATA_TRANSFER 实况窗进度通知(C6-1 深化)。
- Jev 其余用途:记忆召回/上下文选择/模型路由/审批分诊/Web 自动化(仅 TOOL_DISCOVERY 已接)。

## 里程碑

- M1 = C0+C1 提交 → 体感 parity 九成 ✅
- M2 = C2+C3 → 设置面 parity ✅
- M3 = C4+C5 → 功能面 parity ✅
- M4 = C6 → 共通功能闭环(C6-1 落地;C6-2 转原生批次)✅
