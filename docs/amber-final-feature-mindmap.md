# Amber 功能全景最终思维导图(iOS × Android 对齐版)

> 基准:iOS = amberagent-ios(2026-09-27);Android = amberagent-Android(main @ 78693de)。
> 已剔除纯系统/厂商服务类功能(iOS 的 Watch、灵动岛、AlarmKit、Siri/捷径、HealthKit/WorkoutKit/WeatherKit、iCloud  entitlement、StoreKit 机制;Android 的无障碍操控、MediaProjection、通知监听、FCM、小米超级岛、Termux、Health Connect、系统权限桥等),只保留可跨平台对比的产品功能。
>
> 图例:🔴 <span style="color:#d32f2f">iOS 独占</span> / 🟢 <span style="color:#2e7d32">Android 独占</span> / 🔵 <span style="color:#1565c0">双端共有</span>

## 🏠 1. 应用骨架与首页

- 🔵 <span style="color:#1565c0">单栈导航:首页为 hub,其余页面全栈式 push(均无 Tab/抽屉)</span>
- 🔵 <span style="color:#1565c0">首页:品牌头 + 搜索(本地过滤 → 全局/全文搜索页)+ 设置与账户入口</span>
- 🔵 <span style="color:#1565c0">Continue 继续卡:在小说/议会/深读/小应用/生图任务中选一件续跑直达</span>
- 🔵 <span style="color:#1565c0">5 个快捷入口:深度阅读 / 小说 / 议会 / 小应用 / WebMount</span>
- 🔵 <span style="color:#1565c0">会话列表:置顶 / 重命名 / 删除(滑动操作)、生成中状态、按置顶+时间排序</span>
- 🔴 <span style="color:#d32f2f">会话列表 LLM 浓缩预览轮播(28 字摘要)</span>
- 🔵 <span style="color:#1565c0">Profile 页:头像昵称 + 使用热力图 + token/会话统计</span>
- 🟢 <span style="color:#2e7d32">启动落地页可选(最近会话/新对话/首页);收藏页;会话历史页;Stats 统计页</span>
- 🔵 <span style="color:#1565c0">无 Onboarding(空态 + 配置警告卡兜底);无自有账号体系</span>

## 💬 2. 聊天体验

### 输入区
- 🔵 <span style="color:#1565c0">附件:拍照 / 相册 / 文件;图片发送前视觉识别转文字</span>
- 🟢 <span style="color:#2e7d32">视频·音频附件(仅 Google 系模型);粘贴长文本自动转文件(阈值可配);全屏编辑器</span>
- 🔵 <span style="color:#1565c0">模型选择器(按服务商分组、上下文窗口显示);推理/思考等级选择</span>
- 🟢 <span style="color:#2e7d32">输入区联网搜索开关;供应商余额显示</span>
- 🔵 <span style="color:#1565c0">上下文用量环(窗口装载/缓存命中/用量明细)</span>
- 🔵 <span style="color:#1565c0">生成中消息排队(可撤销/管理)</span>
- 🟢 <span style="color:#2e7d32">排队三模式 FOLLOWUP / STEER / COLLECT(上限 20)</span>
- 🟢 <span style="color:#2e7d32">斜杠命令(/clear /compact /deepread /subagent /usage + 快捷消息 + 技能);@提及子代理/议会角色</span>
- 🔴 <span style="color:#d32f2f">个人上下文注入:AI 请求 → 用户确认提供联系人/照片/日记数据</span>

### 消息渲染
- 🔵 <span style="color:#1565c0">Markdown(双端均 Rust 原生解析);代码高亮(折叠/复制);LaTeX 公式;表格</span>
- 🟢 <span style="color:#2e7d32">Mermaid 图渲染 + 导出图片</span>
- 🔵 <span style="color:#1565c0">思考块(可折叠、生成完自动收起)</span>
- 🔵 <span style="color:#1565c0">工具调用胶囊(按工具类型图标/状态)+ 详情 Sheet(参数脱敏/结果/失败原因)</span>
- 🔵 <span style="color:#1565c0">工具审批卡(批准/拒绝,拒绝可填理由;会话级自动批准)</span>
- 🔵 <span style="color:#1565c0">AskUser 提问卡;子代理步骤卡;议会步骤卡;子代理结果卡</span>
- 🔵 <span style="color:#1565c0">生成图片:保存/分享/带 prompt 修改再生成</span>
- 🔵 <span style="color:#1565c0">MiniApp 卡片与生成式小组件(沙盒渲染);搜索来源引用;上下文压缩时间线标记</span>

### 消息操作与会话级
- 🔵 <span style="color:#1565c0">复制 / 编辑 / 删除 / 重新生成;分支变体(消息树,1/N 切换)</span>
- 🟢 <span style="color:#2e7d32">fork 到新会话;消息收藏;单条分享;选中复制;复制格式选择</span>
- 🔴 <span style="color:#d32f2f">保存到 Workspace(一键存 artifact)</span>
- 🔵 <span style="color:#1565c0">AI 自动标题;置顶;AI 聊天建议条;上下文压缩(LLM 摘要,边界可视化)</span>
- 🟢 <span style="color:#2e7d32">输入草稿跨会话保留;多会话并行生成;FTS 全文搜索片段高亮</span>
- 🔵 <span style="color:#1565c0">流式渲染 + 停止;后台继续生成(双端实现机制不同,功能对等);中断 Run 恢复</span>
- 🔴 <span style="color:#d32f2f">生成速度钟(tok/s);按助手持久化推理档位</span>
- 🟢 <span style="color:#2e7d32">会话导出 Markdown / 长图 PNG</span>

### 提示词工程(输入输出处理管线)
- 🔵 <span style="color:#1565c0">文档附件解析转文本(PDF/DOCX/PPTX/EPUB,Rust 解析器)</span>
- 🟢 <span style="color:#2e7d32">消息模板变量({{message}}/{{time}} 等);用户正则替换输出;时间提醒注入(>1h);think 标签转思考块;OCR 提示词自定义</span>
- 🟢 <span style="color:#2e7d32">模式注入 + 世界书 Lorebook(导入导出);预设消息;快捷消息库</span>
- 🔴 <span style="color:#d32f2f">注入与 Lorebook 路由已定义但无 UI 入口(预留)</span>

## 📰 3. 热榜与深度阅读

- 🔵 <span style="color:#1565c0">聚合热榜:多源(HN/arXiv/36kr/HF/GitHub 等)同题去重 Top N;源开关;WiFi 门控</span>
- 🔵 <span style="color:#1565c0">热榜话题一键转深度阅读</span>
- 🔵 <span style="color:#1565c0">深度阅读管线:搜索补源 → 逐条抓取 → 多阶段 LLM 结构化生成(概览/叙事/分析/延伸阅读/配图)</span>
- 🔵 <span style="color:#1565c0">杂志排版阅读器;骨架屏;缺节/分节补写重试;中断恢复;历史记录;产物入 Workspace</span>
- 🔵 <span style="color:#1565c0">版式模板:内置杂志/斜切 + 自定义 HTML 模板(严格安检)</span>
- 🟢 <span style="color:#2e7d32">模板工坊:自然语言描述 → AI 生成/修改模板 → 示例预览 → 保存</span>
- 🔵 <span style="color:#1565c0">阅读字体(系统/衬线/等宽)+ 字号比例;来源分类开关;关注关键词筛选</span>

## 📋 4. 今日看板(Android 独占主线)

- 🟢 <span style="color:#2e7d32">每日自动个人简报:热榜 + 个人信号(日历/聊天/App 使用/飞书消息)→ AI 锚点时刻生成今日条目</span>
- 🟢 <span style="color:#2e7d32">今日回顾:日记式 Markdown(午后/傍晚追加)</span>
- 🟢 <span style="color:#2e7d32">信号源开关与来源权重;锚点时刻/增量阈值/手动三种调度</span>

## ✍️ 5. 小说创作

- 🔵 <span style="color:#1565c0">多项目工作台:账本 JSON + Markdown 书稿(双端序列化互通)</span>
- 🔵 <span style="color:#1565c0">三区:创作(聊天式会话)/ 正文(章节阅读)/ 设定(角色、资料、伏笔)</span>
- 🔵 <span style="color:#1565c0">批量代笔:Write(续写新章)/ Polish(区间润色);候选 → 评审 → 原子提交;可暂停恢复</span>
- 🔵 <span style="color:#1565c0">多分支:整支分叉/切换/检查点回退;undo</span>
- 🔵 <span style="color:#1565c0">一致性审计(全书扫矛盾出报告);设定/伏笔管理;模型按用途分槽(创作/评审/状态同步)</span>
- 🟢 <span style="color:#2e7d32">导出成书 txt/md/epub(字节级可复现);从文件导入项目</span>
- 🔴 <span style="color:#d32f2f">创作会话逐段流式讨论;章节计划提案与验收;章节裁决</span>

## 🧩 6. MiniApp 小应用

- 🔵 <span style="color:#1565c0">聊天中 AI 生成 HTML 小工具 → 校验入库 → 卡片运行</span>
- 🔵 <span style="color:#1565c0">安全闸:禁外部脚本/eval/iframe,体积上限,仅允许内联 script 调宿主桥</span>
- 🔵 <span style="color:#1565c0">声明式权限桥(网络/搜索/剪贴板/AI 生成/写回宿主/共享存储/事件总线/传感器等),敏感动作弹窗确认</span>
- 🔵 <span style="color:#1565c0">列表管理(重命名/删除/版本历史);对话式迭代改版;权限总开关设置页</span>
- 🟢 <span style="color:#2e7d32">源码编辑器(行号/高亮/检查面板/实时预览);HTML 导出</span>

## 🌐 7. WebMount 站点挂载

- 🔵 <span style="color:#1565c0">站点模型:匿名/Cookie/OAuth;内置站点(双端均有 HN/Reddit/GitHub/B 站/微博或知乎/掘金等),默认停用需授权</span>
- 🔵 <span style="color:#1565c0">内嵌 WebView 登录(Cookie 检测)/ OAuth 登录 / 清除站点登录</span>
- 🔵 <span style="color:#1565c0">浏览器会话:Agent 使用与用户接管双模式(租约);wm_* 工具族(导航/点击/输入/快照/提取/截图等)</span>
- 🟢 <span style="color:#2e7d32">飞书文档适配器:完整读写工具族(list/read/blocks/search/create/append);站点状态机;Cookie 逐行导入;eval 脚本开关</span>
- 🔴 <span style="color:#d32f2f">远程桌面后端(Moli / Playwright MCP / Steel,强制 HTTPS);视觉只读校验;Jev 网页快速循环(白名单动作,副作用 handback)</span>

## 🤖 8. 多模型协作

- 🔵 <span style="color:#1565c0">子代理:内置角色(侦察/历史/评审/设计/写作/修复等)+ 自定义角色(提示词/工具白名单/预算);动态创建角色</span>
- 🔵 <span style="color:#1565c0">运行状态显示(活动条/状态坞,跨会话,自动隐藏);并发/超时/输出预算;专属会话页可追问</span>
- 🔴 <span style="color:#d32f2f">子代理模型池;悬浮条完成后自动收起计时;Browser 角色浏览器自动化 opt-in</span>
- 🟢 <span style="color:#2e7d32">@角色提及触发;ROSTER/SMART 双模式;结构化汇报(findings/evidence/risks)</span>
- 🔵 <span style="color:#1565c0">模型议会:多席位(模型+思考档位+角色提示词)评审同一问题;主持人;轮数/超时/输出预算限制;实时转录房间;连通性测试</span>
- 🔴 <span style="color:#d32f2f">动态席位生成;席位联网搜索</span>
- 🟢 <span style="color:#2e7d32">3 核心席(支持/反对/裁判)+ 5 视角透镜结构;COMPARE/DEBATE 任务模式;FULL 功率(主持人调只读工具主动编排);外部 CLI 席位(gemini/codex/claude/kimi CLI);常驻房间三阶段 EXPLORE/DEBATE/SYNTHESIZE</span>
- 🔵 <span style="color:#1565c0">Jev 判断服务:工具发现/记忆召回/上下文选择/模型路由/自动化分诊;OFF/SHADOW/ACTIVE;数据外发按用途授权;失败回退本地</span>
- 🔵 <span style="color:#1565c0">线程编排:派生子会话、追问、结果回流父会话</span>

## 💻 9. 终端运行时

- 🔵 <span style="color:#1565c0">多运行时:Remote SSH(密钥/密码、指纹信任)为推荐 + 内置沙盒环境(实现各异:iSH / Alpine-proot)+ 本机 shell</span>
- 🔴 <span style="color:#d32f2f">内嵌 Python(白名单模块、AST 限额);Mosh;JavaScript 沙箱(双端均有,iOS 在设置显式开关)</span>
- 🔵 <span style="color:#1565c0">工具三级:一次性执行(长任务自动转后台)/ 后台 Job(状态机+日志)/ 交互式会话</span>
- 🔵 <span style="color:#1565c0">工具循环单轮上限(达到自动收尾);并发/输出/超时限制;Smoke Test 与危险命令拦截</span>

## 🗂 10. Workspace 工作区

- 🔵 <span style="color:#1565c0">用户授权目录作为 Agent 工作区;文件工具(读/写/搜/改/移/删)</span>
- 🔵 <span style="color:#1565c0">Artifact 产物登记:来源(chat/deepread/miniapp/image…)、解析状态、重解析</span>
- 🔵 <span style="color:#1565c0">聊天产物架:钉选/导出/diff;产物统一管理页</span>

## 🧠 11. 记忆与 Soul

- 🔵 <span style="color:#1565c0">三档记忆(核心/短期/长期)× 类型(User/Feedback/Project/Reference/Routine/Topic 等);置顶/搜索/过期</span>
- 🔵 <span style="color:#1565c0">聊天后自动提取(日预算/仅充电选项);召回注入(条数与字符预算)</span>
- 🔵 <span style="color:#1565c0">Dream 整理:合并重复/归档过期/短期晋升长期;仅空闲+充电;模型计划需确认</span>
- 🔵 <span style="color:#1565c0">低置信记忆候选审批;写入审批记录;导入导出(Markdown frontmatter)</span>
- 🔴 <span style="color:#d32f2f">污染检测:联网/MCP 触碰过的会话暂停提炼;主题聚合派生文档</span>
- 🔵 <span style="color:#1565c0">Soul 核心指令:查看/编辑/导入(diff 预览 → 审批 → CAS)/ 回滚</span>

## 🔌 12. 工具系统与权限治理

- 🔵 <span style="color:#1565c0">能力域权限模型:禁用/询问/自动(+高风险自动)逐域策略;高风险操作二次确认;批准历史</span>
- 🔵 <span style="color:#1565c0">审批卡体系:记忆/搜索/WebMount/Workspace/终端/MCP/Recipe/议会/AskUser</span>
- 🟢 <span style="color:#2e7d32">tool_search(>40 工具时词面+语义重排);PolicyGate 沙箱闸门(fail-closed);Tool Effect Ledger(崩溃安全重试);推测执行开关</span>
- 🔵 <span style="color:#1565c0">通用工具:联网搜索聚合 / 网页抓取 / 生图(参考图绑定)/ 剪贴板 / JS 执行 / 时间 / AskUser</span>
- 🔵 <span style="color:#1565c0">自配置工具:主题包(Agent 安装试穿)/ Provider 配置 / RunPlan / 权限状态</span>

## ⏰ 13. 自动化

- 🟢 <span style="color:#2e7d32">Cron 定时任务:cron 表达式 + 绑定会话,到点注入 prompt 跑一轮;Agent 可自建/改/删</span>
- 🟢 <span style="color:#2e7d32">AgentTask 统一后台任务内核(恢复状态机/启动对账);精确提醒(AlarmManager)</span>
- 🔵 <span style="color:#1565c0">Durable Run:状态机 + 中断恢复 + 环路守护(重复工具/步数/输出上限)</span>

## 🔧 14. 可扩展资产

- 🔵 <span style="color:#1565c0">技能 Skills:SKILL.md 包,CAS 安装/回滚/恢复出厂;内置技能(skill-creator 等);启用开关与触发文案;聊天按需注入</span>
- 🔵 <span style="color:#1565c0">MCP 服务器:SSE / StreamableHTTP;自定义 Headers;批量导入(解析→预检→审批→下轮生效);工具汇聚与 readOnly 标注</span>
- 🟢 <span style="color:#2e7d32">MCP OAuth 登录;幻灯片字体管理</span>
- 🔴 <span style="color:#d32f2f">Recipes:单文件 ≤8 步声明式工作流,注册为工具;逐步账本/超时/失败即停;导入晋级审批</span>
- 🔴 <span style="color:#d32f2f">动态插件:试运行 + expected_result 比对 + hash 注册;插件中心(导入/授权/隔离)</span>

## 🔍 15. 搜索服务

- 🔵 <span style="color:#1565c0">Agent 联网搜索总开关 + 多源召回</span>
- 🔵 <span style="color:#1565c0">内置免费源:Jina / DuckDuckGo / Bing HTML / Wikipedia / HN / Google WebView 兜底</span>
- 🔵 <span style="color:#1565c0">自定义服务 16+ 类(Tavily/Exa/智谱/Brave/Serper/SerpAPI/SearXNG/Perplexity/秘塔/博查/Firecrawl/Grok/Ollama/LinkUp 等),拖拽调优先级</span>
- 🔵 <span style="color:#1565c0">结果数量调节;推荐组合(零成本/Google 兜底/高质量多源)</span>

## 🎨 16. 外观与显示

- 🔵 <span style="color:#1565c0">外观模式:浅色/深色/跟随系统;强调色板;内置主题包</span>
- 🔵 <span style="color:#1565c0">AI 主题生成:文字描述 → 试穿 → 采用/还原(失败回滚);主题包 JSON 导入导出</span>
- 🟢 <span style="color:#2e7d32">双基础色系(Sage/Warm);AMOLED 纯黑</span>
- 🔵 <span style="color:#1565c0">聊天字号缩放;字体族(默认/衬线/等宽)</span>
- 🟢 <span style="color:#2e7d32">显示开关:用户头像/助手气泡/模型名/思考内容;消息跳转器;生成完成通知与触觉反馈</span>
- 🔴 <span style="color:#d32f2f">应用内语言切换(简/繁/英/日/韩/俄)</span>
- 🔵 <span style="color:#1565c0">TTS:系统引擎试听(语速档位),双端均明确不接入云端 TTS 与聊天朗读</span>

## 🧮 17. 模型与服务商

- 🔵 <span style="color:#1565c0">协议:OpenAI 兼容 / Anthropic / Gemini 三互转;模型目录拉取/手动添加;模型类型(chat/image/embedding/audio)与输入模态、上下文窗口</span>
- 🔵 <span style="color:#1565c0">订阅 OAuth 登录免 Key:Codex(ChatGPT 设备码)/ Antigravity(Google)/ Grok(xAI)</span>
- 🔵 <span style="color:#1565c0">编码套餐 Token Plan:智谱 / Kimi / MiniMax(Android 另有 MiMo),钉官方地址</span>
- 🔵 <span style="color:#1565c0">UA 伪装预设(OpenCode/Claude Code/Cursor/Cline)+ 自定义 Headers</span>
- 🟢 <span style="color:#2e7d32">内置 9 家模板与二维码/文件导入;Vertex AI 服务账号;Responses API / Prompt Caching 开关;账户余额 API;Provider Override 按用途覆盖;模型级自定义 Body;内置工具(搜索/生图/URL Context)</span>
- 🔵 <span style="color:#1565c0">默认模型槽位:聊天默认 + 辅助任务槽(标题/建议/生图/OCR(视觉)/压缩/记忆 Worker 等,留空回退聊天模型,各带提示词模板)</span>
- 🟢 <span style="color:#2e7d32">群聊会话默认;Daydream 模型槽</span>

## 💾 18. 数据与备份

- 🔵 <span style="color:#1565c0">本地加密备份:导出口令(强度显示)/ 恢复预览 / 冲突确认</span>
- 🔵 <span style="color:#1565c0">远端同步:WebDAV / 本机文件夹;快照清单与覆盖冲突</span>
- 🔴 <span style="color:#d32f2f">iCloud(CloudKit 私有库)跨设备加密备份 —— Amber Pro 订阅(月 ¥18/年 ¥168)唯一解锁项</span>
- 🟢 <span style="color:#2e7d32">Google Drive(OAuth)/ S3 / iCloud Drive(WebDAV 客户端实验);设备绑定加密;恢复范围可选</span>
- 🔵 <span style="color:#1565c0">存储管理:用量统计 / 按时间清理(置顶豁免)/ 删除全部;聊天文件存储与导出</span>

## 🔐 19. 账号与登录

- 🔵 <span style="color:#1565c0">无自有账号体系;账户页 = 本地昵称/头像 + 使用统计热力图</span>
- 🔵 <span style="color:#1565c0">全部「登录」均为外部 OAuth(服务商/MCP/站点/云盘);凭据进系统安全存储(钥匙串/Keystore)</span>

## 🛰 20. 伴生功能

- 🔵 <span style="color:#1565c0">Mac Gateway:扫码/链接配对 + SPKI 证书指纹核对;Mac 端 Agent 任务状态推送到手机;每任务开关</span>
- 🟢 <span style="color:#2e7d32">Synara(Mac 工作台局域网伴随);ZCode 分享链接查看</span>

---

### 差异速览

- <span style="color:#d32f2f">**iOS 独占主线**</span>:Recipes 与动态插件两层可扩展资产、WebMount 远程桌面后端与视觉校验、内嵌 Python、议会动态席位、子代理模型池、iCloud+订阅、应用内多语言、列表 LLM 预览、个人上下文注入、tok/s 速度钟
- <span style="color:#2e7d32">**Android 独占主线**</span>:今日看板(信号简报+今日回顾)、提示词工程全家桶(模板/正则/世界书/时间提醒)、Mermaid、会话导出、Cron 定时任务、斜杠命令与 @提及、飞书文档读写、外部 CLI 议会席位、小说成书导出、模板工坊、Google Drive/S3、群聊默认、开发者 Debug/Log
- <span style="color:#1565c0">**双端共有主干**</span>:聊天(审批/分支/压缩/排队/后台生成)、深度阅读+热榜、小说、MiniApp、WebMount、子代理/议会/Jev、终端、Workspace、记忆+Soul、Skills/MCP、搜索服务、主题(含 AI 生成)、服务商与默认模型、加密备份、Mac Gateway
