from pathlib import Path
import json,re,collections
root=Path(__file__).resolve().parents[3]
r=root/'docs/reviews'; evidence=r/'2026-09-13-harmony-merged-evidence'; deep=r/'2026-09-13-harmony-deep-audit'
ui=json.loads((evidence/'ui-adjudication.json').read_text())
s=(deep/'findings.md').read_text(); chunks={}
for m in re.finditer(r'^### (R\d+|L\d+) (.+)\n([\s\S]*?)(?=^### |^## |\Z)',s,re.M):
    chunks[m[1]]=(m[2],m[3].strip())
base_severity={};level=None
for line in s.splitlines():
    if line.startswith('## P'):level=line.split(' ')[1]
    if line.startswith('### R'):base_severity[line.split(' ')[1]]=level
base_severity['R11']='P3'
chunks['R11']=('会话删除未级联清理压缩摘要与审计KV', '''- **级别/置信度：P3 / 高（静态）。** 可达会话删除入口调用cleanupDeletedConversation，但未清理compacts/<conversationId>及事件键；摘要可能含原文，形成残留数据，不声称外部泄露。
- **证据/调用链：** `harmony/entry/src/main/ets/pages/ChatListPage.ets:248-268` → `platform_impl/ManagedFileStore.ets:208-227`，缺少`EntryCompactStore.ets:53-59`的deleteByConversation。
- **本次重要纠偏：** 深审R11原“ChatPage抽屉漏清理”子路径不可达：`ChatPage.ets:2704-2714` openDrawer零调用、drawerOpen仅此置true；故撤回其作为当前可达附件/收藏泄漏的主张，转死路径维护债。不是修好了，而是原可达性判断错误。
- **验证：** 合并阶段两个代理及主代理独立核对入口/全部drawer赋值；未删除任何真实会话或文件。
- **最小修复/回归：** 在统一可达删除编排中清理compact及events，并防在途摘要迟到复活；所有真实删除入口一致。不要仅修死抽屉。
- **来源：** 深审R11/E06/E09；本次[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)。''')
def deep_links(t):
    return re.sub(r'\]\(([^)]+)\)',lambda m:']('+('2026-09-13-harmony-deep-audit/'+m[1] if not m[1].startswith(('http','/','#','2026-09-13-')) else m[1])+')',t)
header='''# HarmonyOS Agent 应用合并审查报告：功能、数据、安全与 UI/UX

日期：2026-09-13｜当前分支：`harmony-preview`｜HEAD：`bdce551dd373f3d7e4c393b194997cae19289c40`

## 1. 执行摘要与阅读口径

**合并后确认48项去重缺陷：P1 10项、P2 27项、P3 11项；另有2项P1级潜伏安全缺陷。未确认P0。** 此外保留10组UI运行时待验项、9组体验/设计/维护建议，以及深审原20项待验证风险。这里的“确认”包括可闭合的静态证明和隔离真实代码验证，绝不表示全部已在设备运行。

本报告将前一轮UI专项的F01–F44和本会话深审26项放入**同一份可独立阅读报告**，并对每个UI编号重新核对当前实现。不是把26+44相加：F16a归原F16；F38/F40及F41/F42分别合并同型根因；F39主问题并R23、F43并R07；纯规格偏好和设备依赖项另列。新补查UI-X01为完整缓存跳过阅读设置。保留`Rxx/Lxx`和`UI-Fxx`命名空间，避免两份原报告的编号混淆。

### 最重要的合并增量

- **文件管理“清理孤立文件”存在P1误删链：** 登记库损坏、缺失或读取失败后返回空快照，清理直接把upload文件当孤儿unlink，没有会话引用核验。与R10错误读后覆写同属危险错误降级，但GC删除决策是需要独立修复的边界。
- **深读取消/离开后重进会隐式重跑并REPLACE在跑任务（P1）：** 页面只对完整缓存短路，不区分在跑/取消意图。现有topic锁仍串行，不宣称超时后并发写或进度清零。
- **多项真实操作/设置断链：** 历史页无法进入多选，Slides字体完成不保存，八项显示/输入开关无消费者，子代理/议会展开编辑直接返回丢草稿，助手绑定模型与菜单选择不一致，文档附件无预览入口。
- **合并还纠正了深审：** 原R11抽屉删除漏清理是死路径，撤回当前可达影响；只保留可达删除后的compact残留并降P3。原R21搜索双栈差异仍为契约待确认，未恢复成Bug。
- **没有夸大设备后果：** 几何裁切/键盘/点击传播/暗色/帧率保持待设备；热力图只对非零天排序，会话列表refreshSeq会丢旧回调，不能再写“每格371次排序”或“必2N逐会话查询”。

### 来源、基线与不变性

1. [上一轮 UI/UX 主报告](2026-09-13-harmony-ui-ux-motion-review.md)：44个正式编号（F16a为子项），无在线设备；原报告没有记录HEAD SHA，**不据日期断言其原工作树逐字相同**。本次重新核对当前关键实现。
2. [上一轮 UI 覆盖表](2026-09-13-harmony-ui-ux-coverage.md)：报告自述74注册页，62全文+1全文UI面+11扫描；这是历史覆盖声明，不伪称本次重新全文审遍74页。
3. [本会话深审权威清单](2026-09-13-harmony-deep-audit/findings.md)：26项当前缺陷+2潜伏安全项，含独立反证后的最终修订。
4. 本次合并时，当前2923个跟踪文件指纹与深审基线一致，原52个未提交修改完整保留。实际API compatible/target为12，V1状态体系，entry→chat→deepread。74注册页、75页面源文件；57有引用入边不等于57从根可达。
5. 8月UI修复战役/round3仅作历史参考，不把已修/已排除问题重新加总。`docs/audits/2026-09-13-harmony/`是另一份同日全范围审计，并非此次指定UI报告，未纳入合并。
6. 两份原报告及覆盖表均未覆盖修改；本次只新增此文件和`2026-09-13-harmony-merged-evidence/`隔离材料，未改业务、依赖、设备设置，未提交/推送、外呼模型或读写真实用户数据。

## 2. 验证结果与证据层次

- **沿用已执行且源码未变的深审结果，不冒称本次重跑：** chat 2097通过、deepread 775通过（合2872，零失败/跳过）；两域TypeScript通过；Harmony构建exit255，CompileArkTS汇总ERROR:12/WARN:502、11条路径诊断。lint报内部错误，未当通过。
- **合并新增执行：** 三路UI源码/调用链复核；F20真实目标方法及ManagedFileStore的内存文件系统故障注入；UI-X01真实loadArticle完整/部分缓存对照；主代理重核高风险入口和指纹。具体日志及探针在[合并证据目录](2026-09-13-harmony-merged-evidence/)。仅mock文件删除，不是删除真实upload。
- **环境仍阻塞：** `hdc list targets`再次为空；当前源码构建失败，没有设备UI/截图/帧率/权限撤销/真实crypto/OAuth证据。本次没有重跑构建/全测，因为业务源码指纹未变，目标是合并与差异复核。
- **可信度分离：** P级代表影响，不代表验证程度。表中“静态确认”是入口、数据流与结果可由代码确定；“待设备”则不能计入已确认运行故障。原报告提到的临时`/tmp`证据不自动视为本次可用，以已保存在报告目录的新增证据为准。

## 3. 去重后的确认缺陷（按严重程度）

每项包含影响/前提、当前源码锚点、机制与保护、实际验证边界、最小修复及回归。来源R项全文保留已纠正的深审证据；UI项归并后补充当前复核结论。
'''
sections=[header]
for level in ['P1','P2','P3']:
    sections.append(f'### {level}\n')
    for id,(title,body) in chunks.items():
        if id.startswith('R') and base_severity[id]==level:
            sections.append(f'#### {id} [{level}] {title}\n\n'+deep_links(body)+'\n')
    for x in ui:
        if x['status']!='confirmed' or x['severity']!=level:continue
        label=' / '.join('UI-'+i for i in x['ids'])
        sections.append(f"#### {label} [{level}] {x['title']}\n\n- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。\n- **源码锚点：** `{x['anchor']}`（路径在`harmony/entry/src/main/ets/pages/`或`components/`；域层文件见原报告链接）。\n- **影响、前提与反证：** {x['reason']}。\n- **最小修复与回归：** {x['fix']}。\n- **验证及来源：** 前一轮UI编号{','.join(x['ids'])}，本次亲读调用链复核；"+('F20另有真实目标方法/真实Store+mock文件系统故障注入，健康库负例保留。' if 'F20' in x['ids'] else 'X01另有真实loadArticle原文提取方法的完整/部分缓存隔离对照。' if 'X01' in x['ids'] else '未做设备UI实测，不以源码检查冒充截图。')+' 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。\n')
sections.append('## 4. 已证实的潜伏安全缺陷（独立计数2项）\n\n以下真实工具边界缺陷已隔离证实，但当前mount/编译阻断遮蔽设备端利用，不称真实隐私已经泄露；修复WebMount功能前必须一起消除。\n')
for id,(title,body) in chunks.items():
    if id.startswith('L'): sections.append(f'### {id} [P1 潜伏] {title}\n\n'+deep_links(body)+'\n')
sections.append('## 5. UI运行时待验（10组，不计确认Bug）\n\n|来源|风险|缺少的证据/验证边界|\n|---|---|---|\n')
for x in ui:
    if x['status']=='runtime':sections.append('|'+','.join('UI-'+i for i in x['ids'])+'|'+x['title']+'|'+x['reason']+'|\n')
sections.append('\n## 6. UI体验、设计或维护建议（9组，不计确认Bug）\n\n|来源|主题|不计Bug的依据/保留建议|\n|---|---|---|\n')
for x in ui:
    if x['status']=='debt':sections.append('|'+','.join('UI-'+i for i in x['ids'])+'|'+x['title']+'|'+x['reason']+'|\n')
sections.append('\n## 7. 全部UI来源编号去向（44主编号+F16a子项+X01补查）\n\n|来源编号|最终归属|说明|\n|---|---|---|\n')
for x in ui:
    label={'confirmed':x.get('severity','')+'确认','runtime':'第5节待设备','debt':'第6节建议/维护','merged':'并入'+x.get('target','')}[x['status']]
    sections.append('|'+','.join('UI-'+i for i in x['ids'])+'|'+label+'|'+x['title']+'|\n')
sections.append('''
### 去重与纠偏要点

- UI-F43完全并R07；不能把state/tab工具全都称不可用，wm_click并无isReady门；安全L01/L02保持独立。
- UI-F39栈顶Want消费并R23；sourceUrl与导航失败清键属于同链待验扩展，不独立增计；普通聊天完成通知无深链不自动认缺陷。
- UI-F25部分恢复并R03，空URI导出路径另待设备；不混淆sha/GCM认证与内部payload校验。
- UI-F20与R10同起点“登记数据错误为空”，但R10是写覆盖，F20是无引用核验的破坏性GC：即使只修Store，正常快照后文件引用变化仍需安全GC边界，因此按独立修复点保留。
- UI-F30与R20分开：前者重进页面隐式新建/REPLACE，后者已发任务取消signal不贯穿。不能以名称都含“取消”合并。
- R11修正为可达compact残留P3，抽屉漏清理降死路径；R21仍是待确认的跨搜索栈契约，不因UI合并自动重新判Bug。
- UI-F16a并F16；UI-F38/F40合并自绘层返回；UI-F41/F42合并loading/empty状态模型。UI-X01为完整缓存早退，UI-F29为Web渲染不消费fontScale，修复点不同。
- UI-F07/F08/F03修正量级：排序只活跃天；刷新sequence会丢旧list回调；窄屏文本余宽约W−320，360vp并非零宽。没有实机数值。
- UI-F27撤回“重复调用ai.generate/sendToConversation/createArtifact收费”：这些方法在Harmony明确not_available；真实可用storage/sharedStore/network等重载副作用仍待设备验证。
- UI-F36已明确标注ACL/暂不可用，并非“未授权”；无click反馈是建议，不再称撒谎。无额外删除确认、无chevron、长按更多不机械定义为Bug。

## 8. 合并覆盖与剩余盲区

### 功能/领域覆盖（继承深审，详读记录可追溯）

|区域|真实入口与核心实现|审查深度|仍未验证|
|---|---|---|---|
|平台/生命周期|EntryAbility、AppContainer、权限、tokens、router|关键实现与SDK声明，构建实跑失败|设备前后台、API版本行为、主题刷新、键盘/安全区|
|对话/Provider|ChatPage、kernel/turn/loop/context/stream/serialize、OpenAI/Claude/Google|跨模块+真实域mock+独立复核|真实网关样本、Tokenizer、SDK流超时与资源释放|
|Agent/MCP/Cron|policy/approval/dispatcher/subagent/cron/task/MCP transports|执行链/取消/恢复、受控时钟、正反例|实际副作用丢响应幂等、后台唤醒、长连通知|
|存储/恢复/附件|RDB/KV/AtomicJsonFile/*Store、sync、Files页|错误读/恢复/删除实体归属；新增GC故障注入|真实磁盘满/掉电、RDB事务并发读、picker、真实crypto|
|WebMount/MiniApp|Host/Tools/Sites/OAuth、Bridge/Sandbox/Runner|代码边界及VM探针，桥权限/namespace/CSP反证|真实浏览器/cookie、图片重定向/DNS、首刷脚本副作用|
|深读/搜索/议会/小说|scheduler/run_manager/research/council/novel及Entry服务|关键实现、取消/通知/规划fallback，新增重进与缓存偏好核查|真实模型成本/内容质量、WorkScheduler设备恢复、实际通知顺序|

### UI覆盖（分清来源声明与本次复核）

上一轮UI矩阵自述74注册页：62全文、ChatPage全文UI面、11扫描；ui组件44、顶层组件54深读/1抽样/3未读死码。本次读完主报告与覆盖表，再以三路职责核对44编号和附录，**不宣称重新全文深读全部页面**。17个零入边和Assistant族内互指孤岛均已识别；有入边不等于根可达。原矩阵个别壳清单叙述不一致，以74行逐页表/本次实际注册数为准。

### 必追用户旅程

发送→上下文→流→工具→落库→展示；生成期间Stop/退出/切助手会话；副作用发生后丢响应；Cron执行中改配置/删除/重启；恢复与生成并发；附件选择解析发送删除重启；OAuth/Want回到非根页面；深读/小说后台任务。本轮UI合并额外追踪“文件管理清理→登记错误降级→unlink”“重进部分深读→scheduler REPLACE”“已完成缓存→显示设置早退”。详细状态所有权、失败/取消、重试及持久化时点见本报告各缺陷和[原跨模块覆盖表](2026-09-13-harmony-deep-audit/coverage-final.md)。

### 原深审20项待验证风险（继续保留，不因UI合并关闭）

主题响应粒度；最终预算/overhead/resume窗；恢复与在途生成冲突；附件文件树跨机；MiniApp图片重定向/DNS；响应/队列/多文件总量峰值；MiniApp首刷重跑；MCP Streamable GET EOF；error:null等兼容样本；协议终止与readTimeout；RDB/文件故障与fsync；真实GCM/KDF；WebMount实际导航/解绑；OAuth页面PKCE恢复；历史grant产品授权；Provider新建页进程恢复；Council成本/监听；大上下文渲染性能；远程副作用幂等；聊天与深读搜索配置契约。详见[原待验风险](2026-09-13-harmony-deep-audit/pending-risks.md)。其中MiniApp首刷与第5节UI-F27同一待验风险，不能重复计。

明确不适用/已登记：Cron不具系统持久唤醒，Responses完整续传未接，WebDAV双向同步不存在，跨端KDF未互通，未移植系统控制/短信等不能虚构实际越权路径。沙箱明文密钥结构无具体外读链，不当已泄露。

## 9. 已排除与历史状态

- 8月UI修复记忆与round3只用于定位：ERROR字面量、操作行/占位、Switch/Markdown等历史完成项未盲目重报；本次没有确认“旧修复已回归”就不以历史名称增计。
- 当前已重核的旧修复：usage-only、图片data前缀、分支删除索引、普通发送重入、RDB迁移、byteOffset/zip容量、MCP初始化/头/generation token、subagent超时/cancel-start、禁用cron恢复、小说RMW/TDZ/退订等。
- 维持排除：V1 Array.push/splice并非普遍不刷新；默认键盘避让并非未配置就遮挡；两套安全区策略可各自合法；300ms防连点为设计；恒真opacity并不自动重播入场；不可达INK未导入组件不等于当前编译失败原因；别把“Android同款”单独当反证。
- 基线注释/README可能过时；实际代码和复核优先。深审R05真实Stop signal已abort时保留user，不能把“仅抛AbortError”的错误标签当真实取消复现。

## 10. 按风险与依赖排序的修复批次（建议，未实施）

1. **恢复可验证性与数据保护：** R01 SDK/ArkTS；R02/R03/R04/R05/R10；新增UI-F20安全GC，错误态禁止清理且核会话引用；R09显式合法恢复代际。先堵数据损失，不只补确认框。
2. **授权、身份与自动化：** R15明确批准、R08稳定工具身份、R14等待审批终态；R12/R16/R17锁/due/取消先于R13增加Cron工具；R07与L01/L02必须同批，不先解锁危险WebMount。
3. **深读/模型真实效果：** UI-F30复用在跑任务/明确继续；UI-X01加载阅读设置独立于缓存；UI-F29模板消费fontScale；UI-F34有效模型同源；R20取消、R22通知、R23 Want（含UI-F39扩展）。
4. **页面和设置闭环：** UI-F14/F16/F19/F21/F35、UI-F15结构化工具状态、UI-F38/F40返回层级、UI-F41/F42加载状态、UI-F44附件打开；R19预览变换、R11 compact清理、R26/R27。
5. **性能与设备验收：** UI-F07/F08/F09/F13先减少可证重复工作，再测实际收益；运行第5节窄屏/大字体/键盘/暗色/弹窗命中/图片重载，不根据静态像素算式直接重做布局。体验建议由产品确认，不与故障修复强绑。

## 11. 交付及完整性

本文件是本次合并后的唯一阅读入口，已包含全部确认发现和所有UI编号的去向；原两轮报告及独立证据仍保留供追溯，不覆盖不删除。合并来源指纹、探针日志和最终检查位于[merged-evidence](2026-09-13-harmony-merged-evidence/)。源码指纹一致且未触碰真实数据。没有在线设备不等于没有UI问题；未验证的风险仍明确开放，不承诺“没有任何Bug”。
''')
out=r/'2026-09-13-harmony-consolidated-review.md'
if out.exists():raise SystemExit('Refuse overwrite existing merged report')
out.write_text('\n'.join(sections))
print(out)
print('Current:',len(base_severity)+sum(x['status']=='confirmed' for x in ui))
print('Severity:',dict(collections.Counter(base_severity.values())+collections.Counter(x['severity'] for x in ui if x['status']=='confirmed')))
