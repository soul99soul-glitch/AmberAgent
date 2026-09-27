# 鸿蒙版 × 双端共通功能 缺口分析与补齐计划

> 基准日期:2026-09-27。
> 对比基准:[amber-final-feature-mindmap.md](amber-final-feature-mindmap.md) 中的 🔵 双端共有功能(已剔除系统/厂商服务类)。
> 鸿蒙版家底:`harmony/` 250 个 .ets,经三路代码盘点(首页聊天 / 内容模块 / 设置与运行环境)。
> 总体判断:**鸿蒙版已覆盖共通主干约八成**——聊天、深读、小说、MiniApp、记忆、子代理、议会、Skills/MCP、搜索服务、备份、Mac Gateway 均已达到「完整」;缺口集中在 **5 个大项、7 个中项、十余个小项**。下面 case by case 列出(只列缺口,已有项标注 ✅ 一句带过)。

## Case by case 缺口清单

### 1. 应用骨架与首页 — ✅ 基本完整
- [小] 会话列表无**列表级重命名**(重命名在 ChatPage 内;双端均在列表滑出重命名)
- [小] Continue 继续卡候选源缺**议会、图片生成**两类(现有小说/cron/深读/小应用四源)

### 2. 聊天体验
- [大] **后台继续生成缺失**:离页即 abort + checkpoint(诚实降级)。双端均支持切后台继续生成并完成通知(Harmony 侧已有 WorkScheduler 先例:NovelPolishWorkAbility)
- [中] **LaTeX 公式渲染缺失**(全仓无 latex;双端 🔵)
- [中] **生成图片消息流内轮播 + 带 prompt 修改再生成**仅工具路径可用,消息流 image part 只是 160px 单图
- [中] **消息流内上下文压缩标记**缺失(只有 context sheet 里「压缩中」指示)
- [小] compact 的 **search/expand 工具循环**未接(EntryCompactStore 已登记)
- [小] **回车发送开关、长文本粘贴转文件**(SettingDisplayPage 显式标"未支持")
- [小] 显示开关未补全(思考内容开关等字段在 domain 有、无 UI)
- [小] 字体族选择(默认/衬线/等宽)无 UI 无消费者(字体注册基建已有)
- ✅ 已有:附件三件套+vision 健康检查、OCR、模型/推理选择、上下文环(含 tok/s 与缓存命中)、三态排队(超 iOS 对齐 Android)、斜杠命令、思考块、工具审批+拒绝理由、AskUser、分支变体、fork、收藏、分享、AI 标题/建议、/compact、checkpoint 恢复、全文搜索、Markdown 导出、多协议(OpenAI/Claude/Gemini/Responses)

### 3. 提示词工程管线 — ✅ 完整
模板变量、正则输出、模式注入、世界书、时间提醒、文档解析、OCR、预设消息均在(部分超出 Android 对齐面,如独立模板库)。

### 4. 热榜与深度阅读 — ✅ 基本完整
- [小] 独立模板工作台为 redirect 壳;**自定义 HTML 模板**能力未确认(双端 🔵 含自定义模板;Android 模板工坊属 🟢 可不做)
- ✅ 已有:9 源热榜+聚合+兴趣过滤、四阶段管线、杂志版式、分节重试、历史、字号/衬线

### 5. 今日看板 — 不适用(🟢 Android 独占,且鸿蒙已按产品方向下线)

### 6. 小说创作 — ✅ 完整(最强模块)
项目/三区/代笔/润色(含后台)/分支/审计/EPUB+ZIP 导出/设定提案全在。

### 7. MiniApp — ✅ 完整
生成-校验-卡片-权限桥-版本-修订-导出闭环,设置 17 开关。

### 8. WebMount — 缺口集中区
- [大] **工具面仅 9 件**(open/state/extract/click/type/scroll/screenshot/eval 等),双端为 30–40 件:缺 tap/keys/select/find/back/forward/**tab_ 多标签**/visual_*/signed_fetch/network_inspect/site_* 等
- [大] **无 bot/hand 用户接管模式**(双端 🔵:Agent 使用↔用户认领租约接管,点击/输入/登录需接管)
- ✅ 已有:九站 seed、授权边界逐导航拦截、飞书 OAuth PKCE、wm_eval 开关、单会话宿主

### 9. 多模型协作
- [大] **Jev 判断服务全缺**(全库零命中;双端 🔵:工具发现/记忆召回/上下文选择/模型路由等,OFF/SHADOW/ACTIVE)
- [中] **子代理跨会话活动条/状态坞**缺失(现有仅会话内步骤卡 + RunSheet;双端均有跨页活动条)
- [小] 议会**连通性测试**入口未确认(设置页有席位编辑/限制,无双端那种 test 按钮)
- ✅ 已有:子代理内置角色+动态+override+followup+结构化汇报、议会三核心席+透镜+流式运行页+设置(CLI 席位 🟢、动态席位 🔴 非共通,可不做)

### 10. 终端运行时 — 缺口集中区
- [大] **无任何 shell 运行时**(写死 builtin"鸿蒙当前不提供 shell 运行时"):无 Remote SSH、无本地沙盒、无 job/session 三级工具(双端 🔵)
- [中] **JS 沙箱执行**未见(双端 🔵 通用工具)——需确认后补齐
- ✅ 已有:工具循环上限、推测并发开关

### 11. Workspace — 半完整
- [小] **聊天产物架 UI** 缺失(工具链 WorkspaceStore/ArtifactPorts 已接,无陈列入口)
- [小] **用户自选工作区目录**缺失(现固定 filesDir 镜像)

### 12. 记忆与 Soul
- [中] **Soul 导入/回滚**未见(双端 🔵:soul_import → diff 预览 → 审批 → CAS → 回滚);Harmony 仅有 soul prompt 注入
- ✅ 已有:三档记忆、提取、召回、Dream、候选审批、导入导出、记忆页

### 13. 工具与权限治理 — ✅ 完整
能力域策略、审批卡、推测执行均在。(PolicyGate/Effect Ledger 为 🟢 实现细节,可不做)

### 14. 自动化 — ✅ 完整(Cron 1101 行 + 后台 WorkScheduler;durable checkpoint)
- [小] 工具**环路守护**(重复工具调用 GUARD_STOPPED、步数/输出上限收口)未确认

### 15. 可扩展资产 — ✅ 完整(Skills 全链 + MCP 669 行设置页;Recipes/插件 🔴 非共通)

### 16. 搜索服务 — ✅ 完整(内置 6 源 + 17 类自定义 + 结果数 + 推荐组合;优先级为上下移按钮,语义等价)

### 17. 外观与显示
- [中] **主题包系统缺失**(内置包/JSON 导入导出;双端 🔵)
- [中] **AI 主题生成**(描述 → 试穿 → 采用/还原;双端 🔵)
- [小] **TTS 系统试听**(语音服务已下线;双端 🔵 仅系统引擎试听)——CoreSpeechKit 可接
- ✅ 已有:三模式、强调色、字号四档、代码块开关

### 18. 模型与服务商
- [大] **OAuth 登录全缺**(Codex 设备码 / Antigravity / Grok;双端 🔵,现 toast"暂不支持")
- [中] **Token Plan 编码套餐**(智谱/Kimi/MiniMax)缺失
- [中] **模型目录拉取 + 类型/模态/上下文窗口编辑 UI**缺失(现仅逗号分隔手输 ID)
- [小] **UA 伪装预设**缺失(模型级 Headers/Body 已有)
- [小] Provider **导出分享**占位
- ✅ 已有:三协议互转、9 家模板、Responses API、Prompt Caching、6 任务模型槽+提示词模板

### 19. 数据与备份
- [小] **WebDAV 仅上传**,缺下载/快照列表管理
- [小] **按时间清理**(30 天前未置顶)未见
- ✅ 已有:.abin 加密导出、manifest 恢复预览、四托管文件夹管理、孤立文件清理

### 20. 账号 / 伴生 — ✅ 完整(Mac Gateway 配对+健康+推送已就绪,待提交)

---

## 缺口汇总(按量级)

| 量级 | 缺口 |
|---|---|
| **大(5)** | ① 后台继续生成 ② WebMount 工具面 9→30+ 与 bot/hand 接管 ③ 终端运行时(SSH/沙盒+job/session)④ Jev 判断服务 ⑤ 服务商 OAuth 登录(Codex/Grok/Antigravity) |
| **中(7)** | ⑥ LaTeX ⑦ 主题包系统+JSON 导入导出 ⑧ AI 主题生成 ⑨ 模型目录拉取+模态编辑 UI ⑩ Token Plan ⑪ 子代理跨会话活动条 ⑫ Soul 导入/回滚(+JS 沙箱待确认) |
| **小(~13)** | 列表重命名、继续卡补两源、生成图流内轮播+修改、compact 标记与 search/expand、回车发送/粘贴转文件/显示开关补全、字体族 UI、产物架 UI、自选工作区目录、WebDAV 下载与快照、30 天清理、UA 预设、Provider 导出、TTS 试听、环路守护确认、自定义深读模板确认 |

---

## 基础补齐计划(建议 7 个阶段,C0–C6)

> 每阶段走完验证回路:`lint-arkts.sh`(0 error)→ `build-harmony-app.mjs` → 签名 → 模拟器截图目测(UI 项必截)。API 12 基线,新 API 一律 `canIUse()`;状态 V1 + AppStorage。

### C0 · 小项清扫批(1 次提交组,纯 UI/接线)
列表级重命名;继续卡补议会+图片生成两源;显示开关补全(思考内容等)+ 回车发送 + 长文本粘贴转文件;compact 消息流标记;WebDAV 下载与快照列表;30 天未置顶清理;UA 伪装预设;Provider 导出分享;产物架入口(读 ArtifactPorts 已有数据);字体族三选 UI(消费已注册的字体)。
**收益**:零风险,快速抹平 13 个小缺口的大半。

### C1 · 聊天渲染补齐
LaTeX(评估自研块渲染 vs ArkWeb KaTeX,倾向自研 inline/block 两节点对齐 MarkdownText 现有架构);生成图消息流轮播 + 修改再生成(复用工具路径 carousel 组件);compact search/expand 工具循环接入。
**收益**:消息渲染面与双端对齐。

### C2 · 主题系统
主题包模型(色板+画布+字标,JSON schema 对齐双端可互换);内置包 2–3 个;导入导出;AI 主题生成(描述 → LLM 产 JSON → 试穿条 → 采用/还原,复用双端 prompt)。
**收益**:外观面抹平最后一块 🔵 缺口。

### C3 · 服务商完整化
模型目录拉取(/models)+ 类型/模态/上下文窗口编辑 UI;**Codex 设备码 OAuth 优先**(深链/剪贴板回执最简),Grok/Antigravity 次之(loopback 在鸿蒙需验证,可用 ArkWeb + want 深链替代);Token Plan(智谱/Kimi/MiniMax 钉地址)。
**收益**:免 Key 登录链路打通,服务商页对齐。

### C4 · WebMount 完整化
工具面扩至 30+(tap/keys/select/find/back/forward/tab_*/visual_*/network_inspect,逐导航 gate 与安全收口沿用 L01/L02);bot/hand 接管模式(租约 UI + 接管后才能点击/输入/登录);多标签真实化。
**收益**:Agent 浏览器达到共通水准。

### C5 · Agent 能力补齐
Jev 服务(纯网络协议移植:systemone/Vercel 双方言、六用途、OFF/SHADOW/ACTIVE、数据范围授权、失败回退);Soul 导入/回滚(diff 预览 → 审批 → CAS);JS 沙箱执行(QuickJS/方舟运行时评估,先确认现状);子代理跨会话活动条(AppStorage 全局状态 + 胶囊条);环路守护(重复工具/步数/输出上限)。
**收益**:Agent 治理与自我演进链路对齐。

### C6 · 后台生成 + 终端(调研与攻坚)
后台继续生成:走 backgroundTaskManager 长时任务(数据传输/音视频类型评估)或 WorkScheduler 接力,通知收口(现有 NotificationNotifier 扩展为进度+完成);终端:Remote SSH 优先(纯 TS SSH 客户端选型调研,或 NAPI 封 libssh2),本地沙盒评估 HiSH 路线(此前审计已定"终端保留 HiSH 路线"),job/session 三级工具随运行时落地。
**收益**:抹平最后两个大缺口;终端是成本最高项,放最后并先出调研结论。

### 里程碑建议
- **M1(C0–C1)**:小项清零 + 渲染对齐 —— 体感 parity 九成
- **M2(C2–C3)**:主题 + 服务商 —— 设置面 parity
- **M3(C4–C5)**:WebMount + Agent 能力 —— 功能面 parity
- **M4(C6)**:后台生成 + 终端 —— 全共通功能闭环
