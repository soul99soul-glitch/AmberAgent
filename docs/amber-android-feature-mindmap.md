# Amber Android 功能全景思维导图

> 探查基准:`/Users/arquiel/Downloads/AI/amberagent-Android`(main @ 78693de,2026-09-26;约 1650 个 Kotlin 文件)。
> 形态:单 Activity(RouteActivity)+ Navigation3 密封路由栈,无抽屉/Tab,首页 SessionHome 为 hub 全栈式 push;单内置助手「Amber」(旧多助手已迁移)。

## 🏠 1. 应用骨架与导航

- **根结构**:单 Activity + `Screen` 密封路由(NavKey),水平滑动转场 + 预测式返回;无 drawer/Tab(旧 ChatDrawer 已废弃)
- **启动落地页**(LaunchStartMode):AUTO / 最近会话 / 新对话 / 首页(默认首页)
- **首页 SessionHome**
  - Header:Amber 词标(终端光标闪烁)+ 日期 + 设置 + 头像
  - 可展开搜索框:本地过滤 +「全文搜索」跳 MessageSearch(FTS + 片段高亮)
  - 功能栏 5 入口:深度阅读 / Mini 应用 / 小说 / 网站挂载 / 模型议会(直接开房)
  - Continue 继续候选卡(可恢复任务,可 dismiss)
  - 会话列表:Paging 分页 + 置顶 + 滑动删除 + 最近访问高亮 + 空态/错误重试
  - 「新对话」FAB 胶囊
- **助手体系**:单一内置 Amber;配置项 = systemPrompt / temperature / topP / maxTokens / 上下文消息数 / 流式开关 / 推理等级 / 消息模板 `{{message}}` / 预设消息 / 正则 / 模式注入 / 世界书 Lorebook / 快捷消息 / 启用技能 / 启用 MCP / 记忆与搜索服务
- **保留页**:会话历史 History(置顶/滑删撤销/删除全部)、收藏 Favorite、Profile(头像昵称/活跃热力图/streak/token 统计)、Stats(输入/输出/缓存命中 token、启动次数)
- **深链与外部入口**:ACTION_SEND / SEND_MULTIPLE / PROCESS_TEXT(选中文本处理)→ ShareHandler;`amberagent://oauth`(WebMount 回调)、`/mcp-oauth-callback`、`/webmount`(内联登录)、`/shortcut`;通知深链带 runId 归属校验精准落页
- **快捷方式**:静态「拍照」shortcut;动态 4 条(新会话 / 最近 2 会话 / 快捷提问)
- **无 Onboarding**:设置页顶部「未配置 Provider」警告卡兜底;SafeModeActivity 崩溃恢复页
- 平板双栏布局(ChatPageSplit);音量键监听注册表;KeepScreenOn

## 💬 2. 聊天(核心体验)

### 2.1 输入区
- 文本输入 + 全屏编辑器;粘贴长文本自动转文件(阈值可配 100–10000 字符)
- **附件**:拍照 / 图片(UCrop 裁剪)/ 视频·音频(仅 Google Provider)/ 任意文件;附件芯片预览、失败重试、截断提示
- **联网搜索开关**;模型选择器(头部卷帘下拉、Provider 分组、余额显示);推理强度选择(按模型记忆档位)
- **上下文用量环** + 用量明细 Sheet(配额/压缩后重估)
- **斜杠命令**:/clear /compact /deepread /subagent /usage + 快捷消息(Q)+ 已启用技能(S,展开为 use_skill)
- **@提及**:子代理角色 / 议会角色
- **发送队列**:长按发送选 FOLLOWUP / STEER / COLLECT 模式,上限 20 条,队列气泡可管理(撤销/排序/清空/恢复)
- 生成中发送键变停止;沙盒活动预览条(终端操作缩略图);WebMount 会话卡片;建议填充脉冲动画
- **无语音输入**

### 2.2 消息渲染
- 虚拟化消息项(Header/Thinking/Content/SubAgent/Council/SearchImages/Footer)+ 渲染缓存
- Markdown:自研渲染 + Rust 原生解析桥;**代码块**:语法高亮(Rust)/行号/折叠/复制;**LaTeX** 行内/块级;**Mermaid**(WebView 渲染 + 导出图片)
- 图片:捏合缩放;**生成图轮播**:保存/复制/分享/以图为源二次编辑
- **思考块**:可折叠推理卡、生成完自动收起、可选中复制
- **工具调用胶囊**:按工具映射图标/类别/状态;预览 Sheet(搜索/抓取/浏览器缩略图)
- **工具审批卡**:批准/拒绝(可填理由);会话级自动批准开关
- **AskUser 提问卡**(选项点击);**子代理步骤卡**(实时进度+详情);**议会步骤卡**(辩论时间线)
- **生成式小组件卡**:WebView 沙盒 + 安全分级(READY/TOO_LARGE/UNSAFE)+ 展开大窗
- 搜索来源内联 pill + 搜索图片条;上下文压缩时间线标记;生成中/等待指示;错误逐条 dismiss;配置缺失提示;结果未知可重试
- 无消息时间戳气泡(视图设置可显模型名/头像/气泡)

### 2.3 消息操作
- 操作 Sheet:复制(可选格式)/ 重新生成 / 选中复制文本 / 编辑 / 分享 / 删除
- **分支变体**:MessageNode 树 + `<1/N>` 切换;**fork 整条消息到新会话**
- 收藏消息(收藏库);生成中禁止编辑
- **无 TTS 朗读、无点赞点踩**

### 2.4 会话级功能
- AI 自动标题(可强制再生成)+ 手动改名;置顶/删除;会话内 AI 建议(点击发送/长按填入)+ 空会话固定建议
- **上下文压缩**:手动(/compact)与自动,LLM 摘要,压缩边界可视化
- 输入草稿(切会话保留);多会话并行生成(conversationJobs)
- 全文搜索(FTS4,匹配片段高亮)

### 2.5 生成过程
- 编排器:Send / Regenerate / Branch 三套 + 新版 RunKernel;Turn 投影 + 中断恢复投影 + 流式检查点合并
- **前台服务保活**(多会话并发,常驻通知点击回会话);停止 / RunResume 恢复
- 流式动画(逐字 reveal);每消息 token 统计入库(统计页)
- **无 tok/s 速度显示**

### 2.6 Transformer 管线(用户可感知)
- 输入侧:**时间提醒**(消息间隔 >1h 注入)/ **模式注入 + 世界书** / **文档转 Prompt**(Rust office 解析 PDF/DOCX/PPTX/EPUB)/ **OCR**(图片经视觉模型转文字,提示词可自定义)/ Placeholder / MiniApp 提示
- 输出侧:**`<think>` 标签转思考块** / **Base64 图片落盘** / **MiniApp 代码块入库** / **正则替换**(用户自定义,结束时应用防 ReDoS,可切 Rust 引擎)
- 模板:`{{message}}/{{role}}/{{time}}/{{date}}` 变量

### 2.7 导出与分享
- 导出 **Markdown**;导出**长图 PNG**(可选展开推理);Mermaid 图导出;系统分享发送
- 外部分享/选中文本 → ShareHandler 落聊天

## 📰 3. 今日看板 TodayBoard

- **每日自动生成的个人简报**:热点榜单 + 个人信号 → AI 在锚点时刻生成今日条目;条目一键转深度阅读
- **热点榜单**:12 源(微博/知乎/B 站/HN/arXiv/HuggingFace/GitHub Trending/36kr/抖音/百度/头条/InfoQ),跨源同题去重聚合 Top 20,源开关,WiFi-only,焦点关键词过滤(如 AI/大模型/芯片)
- **个人信号管线**:7 类采集器(系统通知/日历/聊天记录/飞书消息/飞书文档/App 使用统计/时间锚点);通知三层降噪(高价值 IM/邮件 vs 购物广告)
- **调度**:锚点自续(默认 08/12/13/18/19 点)+ 增量阈值触发 + 手动下拉;SMART / WIFI_ONLY / FOREGROUND_ONLY 策略
- **今日回顾**:日记式 Markdown(13 点生成上午段、19 点追加下午段)
- 通知渠道 today_board;设置页:生成模型/信号源开关/来源权重/生成规则

## 📖 4. 深度阅读 DeepRead

- 输入:热榜话题 / URL;管线:来源预取 → 多源检索 → 证据登记 → 配图打分 → **4 阶段生成**(概览/叙事/分析/延伸阅读,各阶段独立来源配额)+ Playbook 方法论库
- **杂志排版渲染**:MagazineHero / NarrativeFrame / AnalysisFrame / ReadingFrame;骨架屏;**分节独立重试**;字号缩放;沉浸模式
- **模板系统**:模板工坊(自然语言描述 → AI 生成/修改 HTML 模板 → 示例预览 → 保存);内置杂志/斜切/自定义模板;严格校验(DraftGuard)
- 历史页 + 产物落库(Artifact);进度通知(deep_read 渠道);Agent 工具入口(聊天内可发起)

## ✍️ 5. 小说创作 Novel

- **文件系统级工作区**(目录 + manifest.yaml + commit 账本),可放任意同步盘
- **项目管理**:新建(空/从文件导入)/ 重命名 / 删除 / **导出成书**(txt/md/epub,epub 字节级可复现);与 iOS 互通的序列化契约
- **多分支**:整支分叉(手稿+大纲+计划)/ 切换 / 活跃分支记录;undo 与未解决编辑分支绑定
- **批量代笔 Ghostwrite**:Write 模式(从最新章批量续写)/ Polish 模式(章节区间润色不改剧情);每章「候选 → 联合评审 → 原子提交」;可暂停、从持久 commit 恢复;WorkManager 前台执行 + 失败通知
- **设定与伏笔**:setting 目录分组、伏笔未回收/已回收、决定日志只读
- **一致性检查**:全书扫描出报告;Markdown 工作区 UI(编辑/分支 sheet/代笔面板/审查模型选择/脏文件保护)
- 模型按用途配置(创作/评审/润色)

## 🧩 6. MiniApp 小应用

- AI 在聊天中生成 HTML 小工具 → 校验入库 → 网格列表管理(重命名/删除/**版本历史**/HTML 导出)
- **运行器**:WebView 沙盒全屏沉浸;声明式权限桥(storage/toast/theme/network/externalImages/search/clipboard/host 写回/ai.generate/sharedStore/eventBus/sensor/location/haptics/device);**敏感动作弹窗确认**(ai.generate 前、host 写回前)
- **对话式迭代**:描述修改 → 生成新版本入历史
- **源码编辑器**:行号/语法高亮/源检查面板/实时预览
- 桥能力:系统 TTS 语音、联网(需声明)、图片代理、搜索、本地存储
- 设置页:总开关 + 各能力权限开关

## 🌐 7. WebMount 网站挂载

- **站点适配器**:7 个内置(知乎/B 站/GitHub/HN/掘金/Reddit/**飞书文档**)+ 自定义站点;飞书文档有完整读写工具族(list/read/blocks/search/create/append)
- 站点状态机:未配置/需登录/探测中/只读/读写/降级/错误
- **登录**:内联 WebView Cookie 登录(URL 轮询 + Cookie 检测);飞书 OAuth(PKCE);Cookie 逐行导入
- **浏览器会话池 Bot/Hand 双模式**:空闲归 Agent 只读使用(Bot),用户认领租约后手工接管(Hand)
- Agent 工具:`wm_*` RPC(打开页面/快照/点击等)+ 站点专属工具(`hn_*`/`reddit_*`/`feishu_docs_*`)
- 设置:站点管理/OAuth 应用凭据/eval 脚本开关/安装 slash 命令/清除会话

## 🤖 8. 多模型协作

- **子代理 SubAgent**
  - 内置 6 角色:explorer(侦察)/ historian(历史检索,授权边界)/ oracle(评审预检)/ designer(视觉)/ writer(写作)/ fixer(机械转换);结构化系统提示 + 工具白名单 + 路由提示
  - 自定义持久角色 + **动态角色**(SMART_DYNAMIC 模式,主 Agent 临时创建);@角色名 文本提及触发
  - 两种模式:ROSTER 花名册 / SMART 智能;默认并发 2、4 轮、12k 字符预算(扩展档 20 分钟/200k)
  - **浮动状态坞**:跨页显示进度,自动隐藏(15s/30s/1m/never)
  - 工具:list/start/read/wait/cancel/followup/send_message/interrupt + 结构化汇报(summary/findings/evidence/risks)
- **模型议会 ModelCouncil**
  - 席位结构:**3 核心席**(支持者/反对者/裁判,强制)+ **最多 5 视角透镜**(产品/市场/公关/工程/UX/风险);默认 8 席 3 轮
  - 两种任务:COMPARE / DEBATE;两档功率:STANDARD(串行发言→综合)/ **FULL**(主持人主动编排,可调用只读搜索、逐轮审阅、请用户介入)
  - **外部 CLI 席位**:gemini-cli / antigravity / codex / claude-code / kimi-cli(经终端执行,失败脱敏)
  - 结果结构:共识/冲突/最强证据/风险/最终建议/分席摘要
- **议会房间 CouncilRoom**(常驻多轮房)
  - 三阶段:EXPLORE 发散 / DEBATE 对抗 / SYNTHESIZE 综合;参与者 HOST(Amber 本体)+ GUEST(绑模型或外部 CLI)
  - 主持人动作层:邀请/追问/让嘉宾回应/切换辩论/综合/停止;时间线 UI + 像素头像
- **Jev 判断服务**:六大用途(工具发现/记忆召回/上下文选择/模型路由/Web 自动化/屏幕自动化);OFF/SHADOW/ACTIVE 三档;数据外发范围按用途授权;校准/预算/失败回退本地路径

## 💻 9. 终端(四种运行时)

- **内置 Alpine + proot**(APK 内嵌资源,自动安装/修复,与 Workspace 同步)
- **Android Shell**;**Termux**(RUN_COMMAND intent,诚实上报未装/未授权);**Remote SSH**(jsch,多 profile,Keystore 凭据,主机指纹探测/信任)
- **Agent 工具三级**:
  - 一次性 `terminal_execute`(长任务自动转后台)/ `terminal_install_packages`
  - 后台 Job:start/read/wait/stop(状态机 + 日志 tail)
  - 交互式会话 Session:start/exec/read/stop
- 设置:并发任务数/输出上限/超时;工作区目录镜像到私有目录供 proot 访问

## 🗂 10. Workspace 工作区

- 用户经系统文件选择器授权 **SAF 目录树**为 Agent 工作区;私有镜像目录保证终端直接访问;活动任务存在时禁止切换
- **Artifact 产物登记**:记录来源(chat/miniapp/deepread)、摘要、解析状态,可重解析/删除
- 文件工具:file_list/read/write/edit/search/move + 归档创建解压 / PDF 渲染页 / 图片转换

## 🧠 11. 记忆 Memory

- **MemoryRecord**:scope(核心/短期/长期)× kind(User/Feedback/Project/Reference/Routine/Note/Topic),置信度/置顶/过期/CAS 版本/来源审计
- **候选审批流**:低置信记忆先入候选区(pending → accepted/ignored/filtered),sensitive 标记
- **自动提取**(聊天后)+ 召回注入(12 条/2000 字符预算)
- **Dream/Daydream 整理**:仅设备空闲+充电时跑,每日上限;模型产出整理计划需确认,确定性维护自动执行
- 导入导出(Markdown frontmatter);内容安全检查
- 设置五页:Overview(开关/分类/来源)/ Recall(选择性召回)/ Worker(手动触发 Dream)/ Compaction / Library(增删改/冲突解决/候选审核)

## 🔌 12. 工具系统与权限治理

- **ToolRegistry**:元数据(category/mutates/sensitiveRead/needsApproval/outputBudget/risk/effectClass);>40 工具时启用 **tool_search**(词面 + Jev 语义重排)
- **Capability 能力域**:filesystem/network/mcp/skill/soul/miniapp/recipe/provider + 设备族(sms/calllog/call/contacts/location/audio/screen.capture/control/automation/clipboard),每域 DISABLED/ASK/AUTO 策略 + 风险下限
- **PolicyGate 沙箱闸门**:按 file/network/shell/capability 维度 fail-closed 限制(独立于审批)
- **审批链**:ApprovalGuard + 逐次决策 + 批准历史;**Tool Effect Ledger**(写前记账,崩溃后安全重试);推测执行(可关)
- **设备访问工具全集**:短信(列表/读/发)/ 日历(增删改查)/ 通讯录(搜/写)/ 媒体搜索 / 定位 / 电话(状态/通话记录/拨打)/ 应用(列表/打开)/ 录音一次 / 设备状态(电量/网络)/ 使用统计 / Intent(开设置)/ 通知(列表/发送)
- **应用内工具**:联网搜索聚合 / 生图 / AskUser / 剪贴板 / JS 执行 / DeepRead / 记忆 / 技能 / MCP 管理 / WebView / 时间 / 主题包(Agent 可自己安装试穿主题)/ RunPlan / **Soul**(soul_preview/import/rollback)/ Provider 配置 / Health 摘要 / 权限状态 / 策略解释

## ⏰ 13. 自动化:Cron + AgentTask + 提醒

- **Cron 定时任务**:cron 表达式 + 时区 + 绑定会话,到点把 prompt 注入会话跑一轮;**Agent 自己可建/改/删**(cron_task_* 工具);开机重排
- **AgentTask 后台任务内核**:统一状态机(queued→running→terminal)+ 恢复状态(RESTORABLE/RETRYABLE)+ 重试需审批;终端 Job/议会/子代理都落这套体系;启动对账恢复
- **提醒调度**:AlarmManager 精确提醒(setExactAndAllowWhileIdle),开机/时区变化重排

## 🔁 14. Run 内核(durable 执行)

- Run 状态机:CREATED→RUNNING→WAITING_USER/EXTERNAL/RESUMABLE/OUTCOME_UNKNOWN→终态(COMPLETED/FAILED/CANCELLED/INTERRUPTED/STEP_LIMIT/OUTPUT_LIMIT/GUARD_STOPPED),CAS 转移
- **durable 路径**:Effect Ledger 播种,崩溃/审批中断可恢复;审批恢复复用同一 effect
- **环路守护**:重复工具调用 → GUARD_STOPPED;步数/输出上限
- 冷启动恢复门 + RunRecoveryService;推测执行在 durable 路径禁用

## 🔧 15. 可扩展资产(技能 / MCP / 提示词扩展)

- **技能 Skills**:列表/详情页;内置技能静默安装;工具族 use_skill/validate/preview/import/rollback(晋级审批);斜杠命令 S 标记调用
- **MCP**:服务器列表 + 新增/编辑(SSE / StreamableHTTP;自定义 Headers;**OAuth 登录**(amberagent://mcp-oauth-callback);需审批开关);工具列表;从配置导入(高风险审批预览);聊天内 McpPicker
- **提示词扩展页**:模式注入 / 世界书 Lorebook 两个 Tab(导入导出);快捷消息(斜杠调用);正则替换规则

## ⚙️ 16. 设置中心(六分组)

- **通用设置**
  - 外观:SYSTEM/LIGHT/DARK;基础色系 Sage / Warm;强调色板;AMOLED 纯黑;**主题库**(内置包应用、**AI 生成主题试穿**(失败回滚)、导入导出、令牌统计)
  - 显示:用户头像/助手气泡/模型名/思考内容四个显示开关;聊天字体族(默认/衬线 NotoSerifSC/等宽 JetBrains Mono);字号 50%–200% 滑杆 + 实时预览;生成完成通知;消息跳转器;自动滚动;触觉反馈;长文本粘贴转文件阈值
- **Agent 运行时**:记忆(§11)/ 执行(沙盒入口、任务气泡、操作预览、生成式 UI、Live 模式与发布、保活、重试次数、工具循环上限)/ TTS(系统引擎试听,语速 4 档)/ 扩展(技能/MCP/Cron/幻灯片字体 + 推测执行/并行工具调试开关)/ 权限(自动批准、高风险自动批准二次确认)+ 能力权限(按域策略 + 批准历史)
- **模型与服务**
  - 提供商:列表(统计条/搜索/启停分区);导入(扫码/相册二维码/JSON 文件);**新增模板**:OAuth 区(Codex/Gemini)+ 内置 9 家(OpenAI/Gemini/DeepSeek/OpenRouter/Kimi/智谱/小米 MiMo/MiniMax/xAI)+ 自定义
  - 详情双 Tab:配置(协议 OpenAI/Gemini/Claude 互转;认证 = API Key / Codex 设备码 / Grok / **智谱·Kimi·MiMo·MiniMax 编码套餐** / Antigravity;Vertex AI 服务账号;Responses API / Prompt Caching;账户余额 API);模型 Tab(拉取目录/全选/自定义添加/设为当前)
  - 单模型表单:类型(chat/embedding/image/audio)/ 输入输出模态 / 上下文窗口 / **Provider Override 按用途覆盖** / 内置工具(搜索/生图/URL Context)/ 自定义 Headers / 自定义 Body / UA 预设
  - 默认模型:聊天默认(+参数弹窗)+ 辅助槽位 7 个(标题/建议/生图/OCR/压缩/记忆 Worker/Daydream,各带提示词模板,可跟随压缩模型);群聊默认
  - 搜索服务:内置免 key 6 源(Jina/DDG/Bing/Wikipedia/HN/Google WebView)+ **16 类自定义**(Tavily/Exa/智谱/SearXNG/LinkUp/Brave/Serper/SerpApi/秘塔/Ollama/Perplexity/BingLocal/Firecrawl/Jina/博查/Grok),拖拽排序,推荐组合
  - Jev 设置(§8)
- **高级功能**:WebMount / SubAgent / ModelCouncil / MiniApp / Novel / 深度阅读 / iCloud(登录/探测/vault 路径)/ Synara / ZCode / MacGateway(配对/健康/推送级别/任务列表)各设置页
- **数据设置**
  - 备份:本地导出导入(修订版本/加密口令含强度显示/设备绑定加密/OAuth token 警告/恢复范围与冲突确认);**WebDAV**(地址账密/快照列表/覆盖冲突);**Google Drive**(OAuth 连接/修订冲突)
  - 存储与清理(用量统计/按天数预览清理);聊天存储(存储位置/导出全部/重建索引);文件(工作区文件管理);沙盒(四运行时/SSH 配置);系统访问(约 22 个能力的授权状态总览)
- **关于**:版本/系统信息/GitHub/开源协议;开发者模式 → Developer / Debug(能力标志/模拟崩溃/批量造数据)/ Log(网络请求日志)
- **Profile/Stats**:活跃热力图、streak、token 统计(万/亿格式化)、启动次数;**无自有账号体系**(登录全为外部 OAuth)

## 📱 17. Android 平台集成

- **通知(8 渠道)**:chat_completed / chat_live_update / screen_capture / memory_tasks / today_board / deep_read / novel_ghostwrite_failure / mac_gateway
  - 通知内动作:停止生成 / **批准·拒绝工具调用** / AskUser 快捷回复(一次性 token 防重放)
  - 运行中实时通知(当前工具名,敏感脱敏,RemoteInput 回复)
  - **小米超级岛焦点通知**(进度条/色块/按钮,仅 MIUI 生效)
- **前台服务**:AI 生成保活(specialUse,多会话并发);MediaProjection 截屏服务(按需会话,空闲自停)
- **无障碍服务**(Agent 操控手机的核心):UI 树快照 / 手势注入(tap/longPress/swipe/back/home)/ 焦点输入 / API 34 截屏;工具族 screen_click/swipe/input_text/read_ui/find_text/wait_for_text/scroll_until + **screen_run_goal 多步自动化**(高风险);快照新鲜度校验防误操作
- **Live 伴随模式**:无障碍事件流 + 截屏(即用即删)→ 场景识别(聊天 App→回复草稿;阅读 App→找重点)→ 卡片可复制/导出/**记住此事入记忆**;悬浮气泡(无障碍 overlay,与任务气泡互斥)
- **通知监听**:今日看板信号采集(三层降噪);notification_list/post 工具
- **分享与文本处理**:ACTION_SEND/SEND_MULTIPLE/PROCESS_TEXT;拍照 shortcut
- **FCM**:Mac Gateway 推送(扫码配对 + SPKI 证书指纹 pinning;手机作 Mac Agent 的移动伴随)
- **备份**:Android Auto Backup(**刻意排除聊天库**)+ 应用内加密同步(WebDAV / Google Drive / 本地 SAF / S3;口令或设备绑定加密,Rust sync-crypto);iCloud Drive(WebDAV + Cookie)
- **Rust JNI 8 库**:markdown 解析/预处理、代码高亮、office 文档解析、深读正文抽取、正则替换、同步加密、HTML diff;全部 JVM fallback + Remote Config kill switch 灰度
- **保活与调度**:WorkManager(看板/热榜/深读/记忆 Dream/小说代笔/Cron)+ AlarmManager 精确提醒;前台补偿(回前台补跑看板);电池优化白名单引导;启动恢复中断的 run/小说/JS cells
- **权限桥**:40+ Android 权限(联系人/短信/通话/日历/媒体/定位/录音/蓝牙/活动识别/**Health Connect**(步数/心率/睡眠/体重)/使用统计/QUERY_ALL_PACKAGES/悬浮窗/Termux)→ 约 22 个分级能力,调用前 ensureGranted + 审计;特殊权限逐项引导跳系统设置
- **其他**:预测式返回;音量键自定义;KeepScreenOn;UCrop 裁剪;FileProvider;CrashHandler + SafeMode;Firebase Crashlytics + Remote Config(30 分钟);局域网明文 HTTP(Ollama/Synara 本地模型需要)
- **未做**:桌面小组件、嵌入式 Web 服务器、MediaSession、语音输入、聊天 TTS 朗读

## 🌐 18. 伴生与周边

- **Synara 伴随**:连接 Mac 端 Synara 工作台(局域网 WebView)
- **ZCode**:智谱 ZCode 分享链接 WebView 查看
- **web-ui**:独立 React 工程(SSR 模板,非 App 内运行)
- **locale-tui**:开发者 Python 翻译管理工具(扫描/AI 补译/dead entry 检测)
- feature/office 已删除(飞书办公增强,能力迁入飞书文档适配器 + Rust 文档解析)

---

### 一句话总结

Amber Android 是一个**「Agent 真正在手机上干活」的本地优先 AI 客户端**:以 SessionHome 单栈为壳、聊天 Agent 为核心(审批链 + durable run + 分支 + Transformer 管线),独有**今日看板(热榜+个人信号)**与 **Live 屏幕伴随**两条主动感知线,深度阅读/小说/MiniApp/WebMount 四大内容形态,子代理/议会(含外部 CLI 席位)/Jev 三种多模型协作,无障碍 + MediaProjection + 通知监听构成的**手机操控与感知能力**,40+ 系统权限被翻译成约 22 个分级 Agent 能力,无自有账号、加密备份走用户自有云。
