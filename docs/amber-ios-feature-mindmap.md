# Amber iOS 功能全景思维导图

> 探查基准:`/Users/arquiel/Downloads/AI/amberagent-ios`(约 3500 个 Swift 文件,KMP shared + SwiftUI)。
> 形态:单 NavigationStack 单栈应用(无 Tab/Sidebar,禁多窗口),首页为会话列表,50+ 路由向下展开。

## 🏠 1. 应用骨架与导航

- **根结构**:单 `NavigationStack`,根页 = 会话首页;无 Tab/Sidebar(残留未接线的 AppTab 枚举),禁多场景
- **首页(ConversationsView)**
  - 品牌标 + 搜索胶囊(morph 展开成玻璃搜索条,本地过滤 → 回车进全局搜索页)
  - 设置入口 + 账户头像入口
  - Continue 继续卡:在小说/议会/深度阅读/小应用/图片生成五类任务中按优先级选一件续跑,点击直达
  - 5 个快捷入口:深度阅读 / 小说 / 议会 / 小应用 / WebMount
  - 会话卡列表:置顶/重命名/删除(Apple Music 式双侧滑)、LLM 浓缩预览轮播(28 字摘要)、生成中状态、当前会话 accent 色带、按置顶+时间排序
  - 右下拇指区「新对话」玻璃胶囊
- **路由系统**:50+ `Route` 枚举目的地;高级能力受 CapabilityGate 门禁(未开启显示锁页)
- **Deep Link `amber://`**:conversation/new|latest|{id}、agent/ask(Siri 交接)、task/active、settings/health|weather|apple-integrations、gateway/pair(Mac 配对)、AgentActivity(灵动岛/Watch 点入恢复现场);统一 pending 队列等 bootstrap 后执行
- **启动恢复管线**:会话 bootstrap、中断的议会/远程命令/iSH 任务标记、DurableRun 恢复、内置技能安装、记忆加载、Watch 快照、Live Activity 恢复、闹钟对账、后台任务清算
- **无 Onboarding**:空态文案 + 能力锁页引导代替向导
- **全局主题试穿条**:AI 生成主题后可全局试穿/采用/还原
- **iPad 支持四向旋转但仍是 iPhone 式单栈**

## 💬 2. 聊天(核心体验)

### 2.1 输入区(Composer)
- 自绘 UITextView:自动增高(最多 5 行)、回车发送可配、CJK 输入法 marked text 安全、键盘交互下滑收起
- 发送/停止键:生成中翻转为停止;生成中且有草稿 → 发送即入队
- 附件「+」玻璃菜单:拍照 / 相册(上限 N 张)/ 文件;待发图片条、待发文件卡(截断标注);图片发送前 Vision 预识别(可取消)
- 模型选择胶囊:按服务商分组展开、显示上下文窗口、可回退默认模型
- 思考等级:关/自动/低/中/高/极高/最高,跟随模型能力,按助手持久化
- 上下文环:下轮预计装载/模型窗口环形图、总消息数、token/s、缓存命中率、Novel 设定注入回执、Jev 判断统计
- 排队消息(Steer Queue):生成中发送的消息入队,可逐条撤销
- 个人上下文注入:AI 发起请求 → 用户确认提供联系人/照片/Journaling 数据(预览确认交接)
- 配置异常占位:缺 Key/未登录(Codex/Grok/Antigravity)时输入框提示

### 2.2 消息渲染
- Markdown:Rust 原生解析器 + 自有同步渲染器;段落/标题/引用/列表/分隔线/行内代码/链接/图片/删除线
- 代码块:语法高亮、自动换行开关、自动折叠开关
- 表格:流式表格检测(半截行隐藏)、复制还原 rawMarkdown
- 数学公式:LaTeX 行内/块级渲染
- 思考卡:实时计时、Reasoning 档位标签、流式展开/完成折叠、自动收起开关
- 等待占位:「正在连接模型/等待响应 N 秒」+ TypingDots + ThinkingOrb
- 工具调用胶囊:每工具一行(Koboyo 图标 + 标题 + spinner/绿勾/失败),17 种视觉族;点开详情 Sheet(参数 JSON 脱敏、结果、失败原因、子代理实时流、跳子会话)
- 图片:用户图片瓦片;生成图网格(保存相册/分享/带 prompt+比例修改再生成/大图预览/失败卡)
- MiniApp 卡:流式劫持检测 → 可运行卡片;生成式 widget(chart/diagram/slides)动作可回填 prompt
- 子代理结果卡:可展开玻璃卡(发件人/摘要/完整 Markdown)
- URL 引用注释块(Provider UrlCitation)、信箱来源标签(「来自XX的任务」)、上下文压缩时间线标记
- 用户气泡 50 行折叠;助手侧无气泡平铺;可关的 Agent 名字显示

### 2.3 消息操作
- 长按菜单:编辑(追加为新变体)/ 复制 / 删除(确认框)/ 重新生成 / 保存到 Workspace(存 artifact)
- 分支变体:左右箭头切「1/N」变体;编辑与重生成均以追加变体实现;生成中冻结分支
- 无点赞点踩;整条消息无原生分享(图片可)

### 2.4 会话级功能
- 助手/人设:KMP Assistant 列表、system prompt、按助手记忆模型与思考档位、SubAgent 角色覆写
- 自动标题(首条用户消息起题)、置顶(置顶不参与 30 天清理)
- 上下文压缩:按模型窗口规划/强制压缩,LLM 摘要注入 handoff,工具输出截断,时间线压缩边界标记
- 聊天建议条:每轮生成后 LLM 产出最多 4 条建议,点击回填输入框
- 信箱/线程互通:主会话与子代理线程互发消息
- 编排子线程只读:被编排会话 composer 禁用
- 会话存储管理:占用统计、清理 30 天前未置顶、删除全部

### 2.5 生成体验
- 流式:block 级增量渲染 + 合并节流、视口外冻结快照(LOD)、字体缩放跟随
- 顶栏活动岛:准备/思考/检索/读网页/生图等阶段显示 + 停止键;产物架(图片/文件/网页/置顶片段/采用版本)飞入动画
- 跟随滚动开关、回到底部浮键
- 生成速度钟:仅计首 token 后解码时长,显示 token/s
- 后台生成:BGContinuedProcessingTask 接管 + durable 累积回灌 + 终止通知;音频/定位保活(实验)
- Run 恢复:中断 run 从 durable store 恢复
- 空态:轨道动画 A 字标 + 随机欢迎语

### 2.6 工具与审批
- 审批卡堆栈(composer 上方):记忆 / 搜索 / WebMount / Workspace / iSH handoff / MCP / Recipe / 议会 / AskUser 提问卡;Jev 审批分诊标签;结果未知补偿卡
- 工具目录:
  - search_web / scrape_web(多源搜索 + 校验抓取)
  - generate_image(参考图自动绑定会话最近上传图)
  - terminal_execute / ios_shell_execute / ish_handoff
  - workspace 文件读写删
  - wm_* 约 40 个 WebMount 浏览器工具
  - mcp_call(外部 MCP 服务器工具)
  - memory_tool(记忆库)
  - model_council_run(聊天内发起议会)
  - Apple 工具目录(日历/提醒/闹钟/通知/通讯录/照片/Journaling/体能训练/健康摘要/天气)
  - Recipe / 插件动态工具、Skills 注入工具

## 📰 3. 深度阅读 + 综合热榜

- **综合热榜**:聚合 Hacker News / arXiv AI / InfoQ AI / 36Kr / HF Papers / GitHub AI,Top 20(排名+命中源数),下拉刷新,WiFi-only 门控;分来源榜各 Top 8
- **一键深度阅读**:热榜话题 → TopicActionSheet → 创建 DeepRead 任务 / 打开原文 / 重新生成
- **创建来源**:热榜话题 / 手动文本+可选搜索 / 文件导入(txt/md/json/csv/pdf/docx)/ WebMount 当前页 / 当前会话内容 / 纯手动
- **生成管线**:搜索补充来源 → 逐条抓取正文 → 4 阶段 LLM 生成结构化 JSON(摘要/关键实体/时间轴/核心观点/分析/图表/延伸阅读/参考/hero 图);支持缺节补写重试、中断恢复、后台保活
- **版式模板**:默认杂志 / 斜切图文 / 自定义 HTML 模板(占位符 + 严格安检:禁 JS/外链)
- **阅读器**:WKWebView 自包含 HTML 杂志页,内置字体 amberfont://(Noto Serif SC / JetBrains Mono),跟随主题 accent/暗色
- **详情页**:杂志式骨架(「排版中」阶段文案)、封面、相关报道(可开原文)、重新生成、Workspace 同步状态、部分段落失败提示
- **设置**:生成模型(可跟随聊天模型)、前台刷新间隔(30–240 分钟)、仅 WiFi、热榜来源分类开关、关注筛选(关键词+译中)、阅读字体(系统/衬线+字号)、模板管理
- 历史记录 sheet

## ✍️ 4. 小说创作(工程量最大模块,约 60 文件)

- **多项目工作台**:事件溯源「账本」JSON + Markdown 书稿 worktree(checkout/ 为权威),版本与恢复 sidecar
- **三区工作区**:
  - 创作:聊天式创作会话,逐段流式讨论
  - 正文:章节阅读器
  - 设定:角色页、设定资料(Material)修订/分支覆写、设定提案接受
- **生成能力**(17 种版本化提示词):快速开局、角色提案、讨论、续写/整章生成、章节计划提案与验收、章节裁决
- **代笔管线**:多章批量代写,候选稿与章节计划绑定校验,自动收录/暂停判定,跨进程进度
- **润色**:整章润色候选、采纳事务(SHA-256 校验+竞速 latch)、润色漂移检测、批量润色队列(可停止)
- **连续性审计与修复**:分块扫全书找矛盾(类别/严重级/证据跳章),失败如实上报;按审计结果改章
- **模型策略**:创作/剧情同步/审稿三槽模型,剧情同步默认关推理;结构化任务(状态同步/归档蒸馏)要求非流式完整 JSON
- **分支管理**:分支列表、Fork、重命名、检查点回退、分支级设定覆写;旧项目迁移
- **自定义文档类型 .ambernovel**(可作为小说项目文件打开)
- **Agent 工具接入**:项目操作暴露给聊天 agent

## 🧩 5. 小应用(Mini Apps)

- **聊天内生成**:模型输出含 html/title/description 的 JSON → 流式增量解析 → 可交互小应用卡片(运行/打开列表/修改)
- **安全闸**:加载前强校验(禁外部脚本/外链 CSS/eval/WebSocket/iframe/storage 等),768KB 上限,仅允许内联 script 调 Amber 桥
- **运行器**:WKWebView + 权限系统(20+ 权限:storage/toast/theme/network/search/clipboard/写回宿主/AI 生成/sharedStore/eventBus/传感器/定位/触感/语音/分享…),首次使用弹授权,CSP 重建
- **设备能力**:owner-scoped 原生能力(触感、屏幕、语音、分享、定位、剪贴板),前台活跃才可用;向 mini app 报告 phone/pad/mac/vision 形态
- **管理**:应用列表(重命名/删除/版本/htmlHash/来源会话/图标/分类/置顶/运行次数)、预设权限矩阵设置页
- 产物可入 Workspace(host.createArtifact)

## 🌐 6. WebMount(站点挂载 / Agent 浏览器)

- **站点模型**:authKind = 匿名/Cookie/OAuth;内置 9 站(HN/Reddit/GitHub/Bilibili/X/微博/掘金/知乎/飞书云文档),默认停用需用户启用授权;可恢复内置站点
- **登录授权**:内嵌 WKWebView 登录、登录 Cookie 检测、多登录窗口、清除本站登录、Cookie 匿名化摘要
- **会话与任务**:控制权租约、持久会话 opt-in;浏览/守望(watch)两种模式;Agent 浏览任务卡可查看/接管(「接管后才能点击、输入和登录」)
- **Agent 工具集约 40 个**:导航/点击/输入/按键/选择/滚动/截图/视觉读取/观察/快照/提取/eval/网络检查/标签页管理/OAuth 连接/站点记忆/站点增删/run_goal…
- **视觉读取**:截图 + 视觉模型只读校验(不落盘、无动作)
- **桌面后端**:本地 WKWebView / Moli / Playwright MCP / Steel;远程端点强制 HTTPS + Streamable HTTP + 非公网
- **网络安全**:Google DoH 解析 + 私网拦截(防 SSRF)
- **Jev 网页快速循环**:wm_run_goal 有界自动化(动作白名单,副作用动作一律 handback 审批,v1 dry-run)
- 高风险公网模式说明

## 🧠 7. 记忆与灵魂(SOUL)

- **三档记忆**:核心/短期/长期 + 主题聚合;归档只留审计;搜索、置顶;memories.json 唯一事实源
- **灵魂(SOUL)**:核心指令 Markdown 查看/编辑/回退上一版;soul_import 工具(workspace/SOUL.md → diff 预览 → 审批 → CAS 应用)
- **自动积累**:聊天后提炼(压缩模型,日预算,仅充电时可限)、前台/空闲调度、失败重试、原子写
- **记忆整理(dream)**:合并重复、归档过期、短期满 14 天晋升长期、清理死主题;模型驱动主题改写独立门控
- **召回**:Jev 判断记忆相关性后注入;引用清洗
- **污染检测**:联网搜索/抓取/MCP 触碰过的会话暂停提炼(污染会话列表)
- **派生物**:主题聚合 Markdown 文档、写入审批记录(可清除)
- 记忆工具审批卡

## 🗂 8. Workspace(工作区 / 产物架)

- Agent 本地文件工作区:workspace_file_read/list/search/write/edit/move、artifact 读写删
- **产物类型统一归档**:chat / deepRead / webMount / miniApp / image / note / file
- **文件授权**:用户选取文件 → 范围摘要+载荷摘要+有效期+次数的 grant,Agent 只能在授权内读
- **UI**:Workspace 网格页(统计/导入文件/重解析/删除);聊天内产物架(钉选/导出/文本 diff)
- 受 CapabilityGate 门禁

## 🤖 9. 多模型协作

- **子代理(SubAgents)**
  - 派发工具:subagent_dispatch / spawn_agent / followup_task
  - 内置角色 7 个(Explorer/Historian/Oracle/Designer/Writer/Fixer/Browser)+ 自定义角色(systemPrompt/工具白名单/轮次/超时/输出预算;Browser 角色可 opt-in 浏览器自动化,父级权限闸兜底)
  - 活动条:跨会话显示运行中子代理,计时,详情 sheet,跳来源会话
  - 模型池:子代理专用模型池(空则沿用聊天模型)
  - 专属会话页:只读查看,可追问;像素 sprite 头像
  - 设置:动态创建开关、悬浮条、最大并发、单任务超时
- **模型议会(Council)**
  - 多席位(2–8)从不同角度评审同一问题,席位 = 模型+思考档位+角色提示词
  - 主持人:先调研完善议题再按序拉起席位,主持模型/思考档位/提示词可配
  - 实时议会室:类聊天转录、附件(拍照/照片/文件统一解析)、成员 sheet、运行中改席位、后台保活、恢复
  - 动态席位生成、席位联网搜索开关
  - 限制:轮数 1–5、单模型超时 15–180s、输出预算 2k–40k 字符;连通性测试
  - 聊天内 model_council_run 工具可发起(审批卡);仅 OpenAI 兼容服务商
- **线程编排**:spawn_agent / list_agents / interrupt_agent;会话派生子会话(thread edge + mailbox),子 run 走 durable 后台引擎,FINAL_ANSWER 回传父线程;可再派孙线程
- **Jev 判断服务**:小型判断模型(typesafe.ai systemone),7 用途:工具发现/记忆召回/上下文筛选/模型调度/网页操作/子代理意图路由/审批分诊;active/shadow 模式、预算、缓存、校准

## 💻 10. 终端运行时(四种)

- **Remote SSH(推荐,稳定)**:密码/私钥认证、Ed25519 密钥生成/导入、known-host SHA-256 指纹信任闸门、Smoke Test、危险命令拦截、输出凭据脱敏、远程命令任务记录
- **AmberShell(内置,稳定)**:自实现 23 个核心命令(pwd/ls/cat/grep/mv/rm…),作用于 Workspace 沙盒
- **嵌入式 Python**:CPython xcframework,白名单约 24 个模块,禁 eval/exec/open/os;AST 静态限额(256KB/2 万节点/深度 100),输出 128KB 上限(资源护栏非安全沙盒)
- **iSH(实验,GPL 构建)**:busybox rootfs;前台 180s/后台 3600s 超时、命令策略校验;交互式全屏 VT 终端;handoff 降级(脚本写盘+复制剪贴板,用户自行到 iSH App 粘贴)
- **Remote Mosh**
- **JavaScript 沙箱执行开关**(默认关,无网络无文件)
- 工具循环单轮上限(达到自动总结收尾);后台任务/作业管理

## 🔌 11. 可扩展资产(技能 / Recipes / 插件 / MCP)

- **技能(Skills)**
  - 磁盘 skills/<name>/ 目录(SKILL.md 式包),触发文案/分类/工具白名单,聊天按需注入
  - 包级哈希 CAS 安装:预览 → 原子应用 → 单槽回滚 → 恢复出厂
  - 内置:skill-creator(必装)、visual-svg / provider-setup(种子,装而不启用)
  - 管理页:列表/添加表单/详情(启用、触发文案、工具、回滚)/扫描本机技能
- **Recipes(声明式工作流包)**
  - 单文件 recipe.json ≤8 步,绑定语法 ${step.id.output.field},禁循环/递归/动态代码/recipe 套 recipe
  - App 原语(workspace/search/ish/webMount/memory/sessionRead/skill…)串成确定性管线,注册为 recipe__<name> 工具
  - 执行:不可变计划 + 逐步账本(先记账后动手)+ 逐步超时 + 失败即停
  - 导入审批:步骤级审批、permission envelope 展示、CAS 发布 + 回滚
- **动态插件**:试运行 + expected_result 比对 + candidate_hash 注册;插件命令可锚定 AmberShell 或 iSH;插件中心导入/授权/隔离
- **MCP 服务器**
  - 传输:streamableHTTP / SSE(无 stdio);headers 凭据进钥匙串
  - 工具发现汇聚、inputSchema 持久化、readOnlyHint 断线重放判定、歧义名检测、断线恢复
  - 手填添加 + mcp.json 批量导入(解析→预检→重名校验→审批卡→下轮生效)
  - CapabilityGate 门禁

## 🔍 12. 搜索服务

- Agent 网络搜索总开关 + 多源召回(N/M 启用)
- **内置免费源**:Jina Search/Reader、DuckDuckGo Lite、Bing HTML、Wikipedia、Hacker News、Google WebView 兜底
- **可添加服务(17 种)**:AmberAgent、智谱、Tavily、Exa、SearXNG(自建)、LinkUp、Brave、Serper、SerpAPI、秘塔、Ollama、Perplexity、Firecrawl、Jina、博查、Grok、Bing HTML 兜底
- 自定义服务拖拽调优先级、逐项开关;结果数量 1–50;推荐组合(零成本/Google 结果/高质量多源)
- 安全:响应大小上限、DoH + 私网拦截、校验抓取传输
- 结果含候选图片(供 DeepRead hero 图)

## ⚙️ 13. 设置中心(五分组)

- **通用设置**
  - 外观:浅色/深色/跟随系统;内置主题包 3 个(点阵·陶土 / 点阵·Pi / Notion·暖白);自定义画布与强调色;JSON 配方导入导出;AI 主题生成(文字描述 → 试穿/采用/还原)
  - 语言:跟随系统 + 简中/繁中/英/日/韩/俄(切换同步 Live Activity 与 Watch)
  - 显示与字体:聊天字号 0.88–1.25、聊天字体(默认/等宽)、显示 Agent 名字、生成完成振动、灵动岛彩色边缘辉光、生成时跟随滚动
  - Apple Watch(见 §15)
  - Mac Gateway:扫码/链接配对、SPKI 证书指纹核对、Mac 任务状态推送(每任务开关)、取消配对
- **Agent 设置**:灵魂与记忆(§7)、运行环境(§10)、技能(§11)、权限与批准
  - 权限与批准:快速批准(全局自动/高风险自动);按能力逐项策略(禁用/询问/自动/自动·高风险,AmberShell 强制仅禁用/询问);最近批准记录
  - 权限与能力:iOS 系统权限三组图(常用:文件/照片/相机/麦克风/定位/通知;个人数据:通讯录/日历/提醒/语音识别/Face ID;设备与网络:蓝牙/本地网络/WebMount 高风险),逐项状态
- **模型与服务**
  - 服务商:搜索过滤/启用徽标/删除;添加(OpenAI Compatible/Anthropic/Gemini 协议);详情页——API Key/地址/路径、Token Plan(智谱/Kimi/MiniMax Coding Plan 钉官方地址)、Gemini 双认证(API Key ↔ Antigravity OAuth)、Claude Prompt Caching、UA 伪装(OpenCode/Claude Code/Cursor/Cline/自定义)+ 自定义 Header;模型管理(启用/可用目录/手动添加;类型 聊天/生图/嵌入;输入能力 文本/图片/视频/音频;上下文窗口;模型级 Headers)
  - 模型与提示词:默认聊天模型 + 5 辅助槽(视觉识别/标题总结/聊天建议/压缩/生图,留空回退)
  - 搜索服务(§12)、语音服务(系统 TTS,语速 0.5–2.0 六档试听;云端 TTS 暂不接入)
- **高级功能**:WebMount / 子代理 / 模型议会 / 小应用 / 小说创作 / 深度阅读(各设置项见对应章节)
- **数据设置**
  - Amber Pro 订阅(§14)
  - Workspace(§8)
  - 同步备份:本地加密备份(可选口令,导出/预览导入);远端同步——本机文件夹 / iCloud(CloudKit 私有库,**Pro 专属**)/ WebDAV(Google Drive/S3 标不可用);远端快照、冲突提示、恢复预览
  - 对话存储:用量统计、子代理记录、清理 30 天前未置顶、删除全部

## 🔐 14. 账号与订阅

- **账户页**:头像/昵称(本机)、使用热力图(近 21 周每日运行次数)、可选 Apple 账户绑定(可解除;强调完全本地可用,不上传对话/密钥)
- **Amber Pro(StoreKit 2)**:月度 ¥18 / 年度 ¥168;**唯一权益 = iCloud 私有库加密跨设备备份**;状态机(active/grace/billingRetry/expired/revoked)、恢复购买无需绑账户;本地备份/WebDAV/对话/HealthKit/Apple 登录均不受订阅约束
- **服务商 OAuth 登录**(免 API Key 用订阅额度):
  - ChatGPT/Codex 设备码登录(Plus/Pro/Team,自动拉模型目录,显示 email/档位)
  - Antigravity(Google 账号 OAuth,复用 Gemini CLI 客户端,显示套餐 tier)
  - Grok(xAI OAuth PKCE,SuperGrok / X Premium)
- 凭据全部进钥匙串;无自有邮箱/手机号账号体系

## 🍎 15. Apple 平台集成

- **Apple Watch(独立 App)**
  - 不跑模型:渲染 iPhone 任务快照,回传意图;任务详情/近期动态/对话/随手记(同步回手机)/快捷动作编辑/连接状态
  - 手表可决策:approve / deny / choose / openOnPhone / 听写回复
  - 快捷动作最多 4 个(手机端编辑,手表确认发送)
  - 任务完成/需回答时手表提醒
  - 4 个 Watch 小组件:一键提问 / 随手记 / 近期记录 / 当前任务(amber-watch:// 深链)
- **Live Activity + 灵动岛**
  - Agent 任务实时活动:8 类 kind(research/response/image/document/web/memory/command/workflow)× 多 phase/stage,进度指标,节流更新,锁屏终态停留,重启恢复
  - 灵动岛直接取消/重试运行(LiveActivityIntent)
  - App 内「活动岛」组件(ThinkingOrb 六态 + 边缘辉光)
  - 闹钟 Live Activity(倒计时/stop/打开 App)
- **AlarmKit(iOS 26 系统闹钟)**:一次性/每周重复/计时器(10s–24h);作为 Agent 工具暴露(alarm_schedule/list/cancel,需审批,可覆盖静音/专注)
- **App Intents / Siri / 快捷指令**:询问 Amber / 每日简报(日历+提醒+天气+HealthKit 预设)/ 新会话 / 恢复最近会话 / 打开活动任务 / 继续对话 / 运行快捷消息;3 个 App Shortcuts 磁贴
- **CloudKit 同步**:私有库加密备份(amberbackup 口令加密 ZIP,PBKDF2 210k);Pro 专属
- **后台执行**:UIKit 短窗 → 静音音频循环 / BGContinuedProcessingTask 三级策略;覆盖聊天/生图/小说/议会/深度阅读/MiniApp;实验性定位保活(不采集坐标)
- **通知**:本地通知(任务完成/需回答,带 deepLink,可 Agent 调度需审批);时间敏感通知 entitlement;APNs 预留未接线
- **Apple 服务 = Agent 工具目录**
  - EventKit:日历事件增删改查、提醒事项增删改查+完成
  - WorkoutKit:生成跑步/步行/骑行/徒步训练计划(goal/pacer/intervals)写入系统体能训练 App
  - HealthKit:步数/体能训练摘要(1–30 天)
  - WeatherKit:命名地点/当前定位天气
  - 选择器:通讯录/照片/Journaling Suggestions
  - 除只读外均需用户审批
- **Sign in with Apple**(可选,本机绑定)
- 其他:App Attest 防滥用、App Group(Watch 共享)、时间敏感通知、隐私清单、六语言、触感反馈、单 Scene、无 Share Extension/Universal Links/Complications

## 🛡 16. 安全与治理(横切面)

- 工具权限模型:禁用/询问/自动/自动·高风险四档,按能力逐项;AmberShell 强制最严
- 审批中心:各工具审批卡 + Jev 审批分诊 + 最近批准记录
- 凭据治理:Keychain 侧表 + 输出脱敏(SSH/OAuth/API Key)
- 提示注入:注入编辑(模式注入/Lorebook,预留)+ Jev 注入筛查
- 工具循环护栏(单轮上限自动收尾)、Agent 运行账本
- 高风险操作:公网网站/远程命令独立「高风险自动批准」开关;WebMount 公网模式明示
- 隐私叙事:完全本地可用,对话/健康数据/密钥不上传;Apple 登录纯可选

---

### 一句话总结

Amber iOS 是一个**「本地优先的 Agent 操作系统化 AI 客户端」**:以单栈会话首页为壳,聊天 Agent 为核心(工具审批 + 流式渲染 + 分支 + 后台续跑),向外辐射深度阅读/小说/小应用/WebMount 四大内容形态,技能/Recipe/插件/MCP 四层可扩展资产,子代理/议会/线程编排三种多模型协作,再叠加 Watch/灵动岛/AlarmKit/HealthKit 等深度 Apple 平台集成,以 Amber Pro 订阅仅解锁 iCloud 加密备份。
