# 候选裁决、去重与历史问题重核

**最终阅读顺序：README.md → findings.md → 本裁决 → review-*.md → topic-*.md。** topic报告为首轮候选记录，故保留了被反证的P0/P1标题；不能将其逐项相加。这里只统计真实用户影响及可证代码缺陷，不统计死代码、风格、平台差异或测试缺失。

## 统一纠偏

1. 构建失败为P1，**不是P0**。总计ERROR:12和逐条11诊断的差额原因未被工具说明，不推断“第12条隐藏错误”或聚合规则。
2. 原RDB失败不是“删除全部旧会话”。准确影响是降级期间的新增/编辑尚未迁回RDB而进程终止时丢失；旧RDB内容是否可恢复取决于故障。
3. 加密payload结构异常不能与普通密文损坏混同。sha/GCM拒绝普通篡改；E02前提是**可成功解密且内部结构非法/异版本**或恢复过程中存储失败。
4. WebMount F01/F02的边界缺陷已有真实源码隔离证明，但mount失败及当前构建失败遮蔽端到端利用。单独列“已证实潜伏安全缺陷”，不能称真实Cookie/数据已泄露。
5. 终止帧后fake流60ms未settle不等于真机无限挂死。存在readTimeout配置和用户Stop，最终时长待设备。
6. “Android/SDK同款”不单独作为假阳性依据；需产品授权、协议前提或上游实际保护反证。
7. 17个直接孤儿页及旧助手子树是覆盖/维护债务；活链提供同能力，不作为正式Bug。
8. 主题机制疑点必须设备验证，未因export let或搜索命中就确定具体页面有暗色故障。官方V1 Array.push/splice可观察，已排除相反断言。

## 初始候选→最终处置

|来源|处置|理由/复核|
|---|---|---|
|E01|确认P1|真实backup→restore往返丢分支/元数据；review-e|
|E02|确认P1，前提收窄|结构未整体校验/逐条save无回滚，非GCM绕过；review-e|
|E04=AG3|确认P1，合并|静默内存降级，丢降级期改动；原数据库不宣称被删|
|E03|确认P2|删除墓碑与合法恢复重建契约冲突|
|E05/E07/H8|同类根因合并P1（终审上调）|读取错误/损坏折叠为空，后续写覆盖；E静态/后续IO探针优先，H“无实例所以低置信”不否定确定性路径|
|E06/E09|删除清理根因合并P2|抽屉漏清理 + 所有删除漏compact；反证Android FK cascade更正于review-e|
|E08|排除正式Bug|WebDAV list/mkcol无调用方，能力债务|
|D01=F01审批部分|并入潜伏F01|单开自动批准的弱门加剧脚本注入；run-trust生产无赋值，不作攻击路径|
|F01|确认源码安全缺陷，端到端待验|真实wm_click注入正负例；review-df；与当前mount失败分开叙述|
|F02|确认源码安全缺陷，端到端待验|userinfo**带冒号**才伪装授权host，scheme未约束；review-df|
|F03=AG5|确认P1|markHostMounted零调用，真实class50tick后失败|
|AG6|确认P1|build实跑+SDK签名独立核验；无业务修改|
|D02|确认P2|真实timer/manager重叠run与计数丢失；当前无工具不能称真实外部工具被重复执行|
|D03|确认P2|定时Agent实际无工具单轮，成功态无降级提示|
|D04|确认P2，删除错误子结论|approval_required无法cancel/续跑；**不占running并发额度**|
|D05|排除正式Bug|run-trust未接是死分支，Android仅用于未移植screen工具|
|D06|待产品契约确认|模型自供scope但grant有界，父会话本已有历史读能力，不声称新增越权|
|D07|排除正式Bug|fireDueCronTasks生产未接线|
|D08|原排除被推翻→独立CRON-LONGDELAY P2|entry显式截断超长delay，timer并非一定due；review-cron-longdelay13断言|
|D09/S6|覆盖债务|任务重试无可用回调，缺少用户已承诺链证据，不机械计缺功能|
|D10|排除|不可达分支，无实际影响|
|D11|从“同款宽容设计”改为确认P2|自然语句可approve强制工具且UI无提示，review-d-supplement28断言|
|D12/S1|历史修复确认|loadSnapshots→recoverOnStartup仍有效，reconcile未接不等于没恢复|
|S5|确认P2，独立于D02|无在途取消信号/回调，删已启动任务仍跑完；不复活调度是已验证反证|
|S4授权本体|排除|有界private output删除+显式全局auto许可；非权限突破|
|S4 cleanup终态门|确认P3，收窄|可删RUNNING任务和任务看板cancel回调；不称子代理manager自身也再无取消通道|
|S7-13|不计或归其它专题|展示字段/结构性隔离/合法profile/Provider增量边界|
|C01/C04|合并确认P1|200非SSE静默成功；customBody.stream为可触发前提之一；review-c真实api→turn|
|C02|从P1无限卡死降P2|缺协议级取消已证实，真实超时/心跳长期性待验|
|C03|待验证兼容风险|null抛错可复现但缺真实合法网关样本；Android JsonNull!=null也抛，初始对照说法错误|
|C05/C06|已登记残余，不计正式Bug|Responses恢复明确后续，runId没接；save rejection潜伏不可达|
|C07/C08/C09|协议样本待验/不可达|Gemini官方不发DONE；Responses省event的网关未知；未知HTTP method没调用方|
|MCP start try外|排除现存泄漏主张|真实Legacy self-close使client释放，Streamable.start不reject，不能用抽象坏port冒充产品|
|MCP Streamable EOF|接受残余，未称无问题|active GET通知不重连但POST工具仍可用；真实影响待server验证|
|H1|P1降为P3，仅规划失败分支|正常normalizePlan从全局池补满；原probe只测fallback，被独立全链反证|
|H2|确认P2，收窄|prefetch/plan取消不贯穿；页面catch存在，不报unhandled rejection|
|H3|有意降级，非本轮Bug|PARITY_DEBT PD001，旧头注释不作正确性证据|
|H4/R21|终审改为待验证契约，不计Bug|双栈配置不贯通成立，但ChatSearchSettingsPage文案限定聊天工具，未证对DeepRead有相同承诺；不能机械对齐|
|H5|预算契约风险/不正式计数|outputBudgetChars本地输出预算并不自动承诺max_tokens成本限制，无实跑收费证据|
|H6|低影响待验证|终结后空listeners map是否形成可测增长/闭包留存需取消订阅反证，不以map存在自动报泄漏|
|H7|排除|时钟注入不一致无用户实例|
|H9|排除|32位哈希理论碰撞无实际碰撞任务证据|
|H10|确认P2竞态，非正常必消失|真实scheduler顺序+真实Notifier转译显示未await平台publish/cancel，同id互相覆盖；手写转写probe明确不作证据|
|AG1|待设备|主题响应粒度及旧页重建需截图|
|AG2|确认P2|Want只由根页消费，栈顶非根页时不导航；描述应“滞留”，非立即永久丢弃|
|AG4|不计Bug，覆盖债务|实际有替代活入口，17直孤儿非22|
|AG7|低影响信息丢失风险|sourceUrl生产Notifier本就传空，无法声称本来有URL在Entry丢；列待验/债务|
|AG8|确认P3|主题保存fire-and-forget无catch，写失败UI与盘不一致|
|AG9|待设备恢复|模块级PENDING_PROVIDER丢失后的页面恢复路径未证，不据静态isNew就称正常必现|
|AG10/12/13|排除|保存草稿保留有意；冷启动消费自洽；权限声明/Unsupported诚实|
|AG11|排除|过期注释/死页非Bug|
|AG14|仅已读部分的保护成立|不能扩展成全应用无资源泄漏；WebMount等单列例外|

## B候选最终裁决补充

- B1机制确认，但降P2：正常Claude初始ID会保留，OpenAI后到ID会补齐，Google生成ID；仅非合规Provider全程缺ID触发。真实ask_user自由文本→resume子集的结果错配可达，审批空ID还会全匹配。原auto/approved态重复file_write探针不能直接当UI可达。两种表现按缺稳定身份根因合并为R08。
- B2原P1降级为U02预算风险：memory有≤12000字符硬上限，原20000字符probe超生产约束；send/regenerate有最终fit，resume缺省与overhead未接仍需补真实窗口场景，不能声称常态记忆必溢窗。保留超长system为历史登记策略，不单因Android相同排除。
- B3确认R05/P1：真实kernel+turn+store证明非AbortError的错误正文含abort时，返回旧conversation会覆盖已落库user；真实线上出现频率未测不改变确定性条件路径。
- B4降R27/P3：前置save晚到会启动新生成，但下游token过滤UI，未证跨会话串写。保留资源耗用，设备实际时序未测。
- B5确认R19/P2：真实再生+非幂等正则预览二次转换，落库原文未损坏。

完整独立证据见 `review-b.md` 和 `probes-b/`，不沿用topic-b原始P1数量。

## 历史状态

- 已修复且本轮重核：usage-only尾块、图片重复data前缀、删除前一alternative索引漂移、普通发送重入/append队列失败复位、RDB新增列迁移、writeBytes视图偏移、zip单项/总量限制、MCP endpoint早EOF/版本头/Content-Type/generation token、subagent超时/cancel-start、禁用cron恢复、error显式清空、小说RMW/TDZ/流退订、搜索标题去重/HTML解析、整数私网IP过滤。
- 仍存在/未闭环：JSON读IO异常折叠为空、审批子任务收口、部分后置context预算接线、删除清理、MCP Streamable主动通知重连。
- 原豁免前提失效：cron曾以“执行器不可用”排除并发风险，现AVAILABLE=true，必须重新报告；远期delay则由跨模块复核推翻“timer总是due”假设。
- 已登记接受残余：进程级cron不系统唤醒、Responses完整恢复后续、空来源降级文、跨端KDF不互通、沙箱明文LRU/主配置需具体泄露路径才作漏洞。
- 不直接沿用：历史“同Android即假阳性”口径、本地README“尚无工具链/深读no-op”、旧状态字段消费者描述，均以当前源重核。

## 最终质量门修订

最终数量26当前缺陷=8P1+14P2+4P3，另2潜伏P1。R10上调因一次IO故障后成功覆写整份数据，概率低不降低损失严重性。R21移待验证因UI聊天范围限定。R05探针第四例仅抛AbortError未abort signal，标签已修正；终审真实Stop对照user保留，不扩展普通取消丢消息结论。R18已更正Google没有done标志。终审原27项计数只代表修订前快照。
