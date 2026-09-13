# HarmonyOS Agent 应用合并审查报告：功能、数据、安全与 UI/UX

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

### P1

#### R01 [P1] 当前工作树无法编译为Harmony应用

- **置信度：高。影响/前提：** 当前源码构建必失败，不能产出本轮可安装HAP；不是推断运行时崩溃，也不是P0数据事故。
- **证据：** `harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets:21,28,31-32,90`；`WebMountOAuthSupport.ets:38`；`harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets:154,157,182`。请求了不存在的crypto API/type、错误initSync参数及ArkTS不接受的匿名对象类型。
- **验证：** 官方构建包装器exit255，CompileArkTS总计ERROR:12/WARN:502，列出11条路径诊断；本地SDK声明独立复核。目标语句在起始未提交改动内；未另构建HEAD，不对HEAD全树可编译性作保证。
- **保护为何无效：** chat/deepread的tsx/tsc不编译entry ArkTS及SDK签名；针对性lint自己报内部错误，不能替代编译。
- **最小修复/回归：** 按API12可用SDK签名改SymKeyGenerator/Random/Cipher调用，显式interface；CompileArkTS通过后做真实GCM/PKCE测试及装机。详见[验证记录](2026-09-13-harmony-deep-audit/validation.md)、[独立复核](2026-09-13-harmony-deep-audit/review-xag.md)。同根编译诊断合并一项。

#### R02 [P1] 完整备份复用了扁平导出，恢复后消息分支和会话状态不可逆缩水

- **置信度：高。影响/前提：** 有重新生成分支的会话备份→迁移/恢复后只剩所选回答，建议/置顶/自动批准状态被重置；页面仍报恢复成功。
- **证据/链：** `harmony/chat/src/main/ets/chat/sync_snapshot.ts:181-190` → `conversation_export.ts:122-150`（每node只取selected message）→ `harmony/entry/src/main/ets/pages/SettingBackupPage.ets:219-234`（toMessageNode，suggestions=[]、pinned/autoApprove=false）。
- **机制：** 人类可读导出DTO不包含node多分支和元数据，恢复不可能从无信息数据还原。密文校验只能证明字节完整，不能补回未导出的字段。
- **验证：** 真实createSyncBackup→restoreSyncBackup→toMessageNode合成crypto往返，双分支变单条、selectIndex 1→0；独立复核确认。未用真实设备备份。
- **最小修复/回归：** 单独可逆SyncConversation DTO，完整节点/选择索引/状态；版本迁移并明确旧格式限制。回归比较完整会话深等价。详见[存储复核E01](2026-09-13-harmony-deep-audit/review-e.md)、[E初审](2026-09-13-harmony-deep-audit/topic-e-storage.md)。附件文件树未备份另列盲区，不据此增加计数。

#### R03 [P1] 恢复没有整体校验与回滚，失败可留下已覆盖的部分数据

- **置信度：高。影响/前提：** 可正确解密但内部结构非法/异版本的归档，或多条恢复中途存储失败：前面会话已覆盖、后面报错，形成混合状态。
- **证据/链：** `sync_snapshot.ts:277-302`将JSON强转SyncPayload，无validator → `SettingBackupPage.ets:219-246`逐条save再覆盖settings → `254-258`仅显示错误。
- **机制/保护：** 整体修改之前没有结构/重复ID验证或事务；catch不能撤回之前save。**普通密文截断/篡改并不能凭此绕过sha/GCM**，此项不声称加密漏洞。
- **验证：** 真实restore接受missing conversations、object conversations、数组内missing messages；独立探针显示首条可保存后第二条失败。合成crypto仅用于结构验证；真实文件/RDB未操作。
- **最小修复/回归：** 先校验完整payload并分配恢复计划，再事务或暂存原子切换；失败不落任何覆盖或明确部分恢复明细。加入第二条失败/重复ID/settings失败回归。详见[review-e E02](2026-09-13-harmony-deep-audit/review-e.md)。与R02的信息丢弃是不同根因。

#### R04 [P1] 数据库失败静默转内存，持久化恢复前退出会丢降级期改动

- **置信度：高。影响/前提：** getRdbStore/迁移失败期间仍允许正常新增/编辑；若数据库恢复回灌前进程退出，这些改动丢失，用户没有可见警告。
- **证据/链：** `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets:149-197`失败设置chatStorageDegraded并return null → `283-300`memory.save；全仓该标志只有178/182两处写，无UI消费者；AppContainer getChatRepository为单例。
- **保护/边界：** hilog不向用户展示。下次操作会重试初始化，成功时171-176把memory迁回RDB，故**不称所有原数据库内容被删除，也不是每次失败必丢**。
- **验证：** 三方独立静态调用链及全仓标志消费者核验；没有制造真实设备数据库故障。
- **最小修复/回归：** 降级状态必须可见，提供安全持久化/恢复或拒绝不可持久写入；故障注入测试失败→新增→重启、恢复后回灌。详见[E04勘误](2026-09-13-harmony-deep-audit/topic-e-storage.md)、[独立复核](2026-09-13-harmony-deep-audit/review-e.md)。AG3同根合并。

#### R05 [P1] 普通Provider错误文本含“abort”会被误当取消，并用旧会话覆盖新用户消息

- **置信度：高（条件确定）。影响/前提：** Provider返回非AbortError但message含小写abort/aborted，例如上游中断的错误正文；本次user已落库却随错误被回退，UI不显示真实错误。
- **证据/链：** `harmony/chat/src/main/ets/chat/chat_kernel_host.ts:148-166`子串判取消并返回入参conversation → `harmony/entry/src/main/ets/pages/ChatPage.ets:3295-3306,3991-3997`取得旧会话 → `1157-1184`最终save；user先保存于chat_turn:187-188/tool_loop send链。
- **保护为何无效：** generation retry按name识别，kernel额外按message识别；page将result=null的旧conversation当合法最终结果。checkpoint排序正确也挡不住内容本身回退。
- **验证：** `probes-b/probe_b3_chain.mjs`真实kernel+runChatTurnWithTools：engine save nodes=2，kernel返回nodes=1，按页面最终save后只剩原消息；普通500对照走throw且user保留。未声称已从真实服务捕获该正文。
- **最小修复/回归：** 统一结构化取消判据；取消/异常也不能返回早于已持久化user的快照。加入非AbortError含abort及AbortError但signal未abort的回归。终审真实Stop（signal确实aborted）对照保留user；原probe第四例标签错误已更正，不能据此声称普通Stop丢消息。详见[review-b B3](2026-09-13-harmony-deep-audit/review-b.md)。

#### R06 [P1] HTTP 200非SSE响应被静默当成功，核心发送流程无回复也不报错

- **置信度：高。影响/前提：** 兼容网关200返回JSON错误，或customBody.stream与调用模式冲突让服务器返回普通JSON；发送结束只留user，无错误/重试。
- **证据/链：** `harmony/chat/src/main/ets/chat/openai_chat_api.ts:124-151`、`claude_chat_api.ts:93-120`、`google_chat_api.ts:136-160`只检查status；`harmony/deepread/src/main/ets/platform/sse_assembler.ts:131-162`忽略无data前缀正文；chat_turn普通发送没有空输出失败守卫。
- **保护为何无效：** RcpHttpClient已回传headers但Provider未消费Content-Type；非2xx错误解析对200不生效。regenerate的sawChunk只避免空分支，不修普通发送。
- **验证：** 真实OpenAI api→runChatTurn+fake 200 application/json，threw=null/nodes=1/assistantNodes=0；三Provider对照一致，SSE包装错误正常抛出。
- **最小修复/回归：** 校验stream协议/非SSE错误正文及零有效事件；请求模式字段与解析器必须一致。`openai_request.ts:396,465`/`claude_request.ts:234,293`customBody可覆盖stream，作为同一协议闭环修复，不另计C04。详见[review-c C01/C04](2026-09-13-harmony-deep-audit/review-c.md)。

#### R07 [P1] WebMount首次打开永远等不到宿主就绪

- **置信度：高。影响/前提：** 用户启用WebMount并调用wm_open，约5秒后host not mounted，无法打开授权站点。
- **证据/链：** `WebMountSession.ets:35,109-111,139-153`hostMounted初值false，仅setter无任何调用；`ChatPage.ets:875-877,4456`hook只显示host；`WebMountSession.ets:224-237`组件只attachUiContext/markPageEnd，不置mounted。
- **验证：** 真实Session class转译、假时钟50tick，hook1次/loadUrl0次/按预期reject；全仓setter调用搜索及A/G、D/F两次独立复核。未设备实跑5秒。
- **保护为何无效：** Web组件存在/初始pageEnd与hostMounted是不同状态，前者不会修改后者。
- **最小修复/回归：** controller实际attached时通知，并在销毁时对称复位/解除hooks；首次打开、退出重开、超时和多实例用例。**必须与L01/L02一起修，不能只解锁挂载后暴露潜伏安全面。** 详见[F03](2026-09-13-harmony-deep-audit/topic-f-security.md)、[review-df](2026-09-13-harmony-deep-audit/review-df.md)。

#### R10 [P1] 读失败或损坏被当空库，随后成功写入覆盖原数据

- **置信度：高。** 瞬时readText错误后再添加记忆/附件追踪，或收藏JSON损坏后新增收藏，旧记录整体丢失。
- **证据：** `MemoryStore.ets:39-63`、`ManagedFileStore.ets:44-71`的IO catch返回空；`FavoriteStore.ets:27-45,66-72`解析失败返回[]再写；AtomicJsonFile只能防撕裂，不防错误基准覆盖。
- **验证：** `probes-e/probe-e-io.cjs`加载真实.ets/managed_files.ts并只mock IO/KV：一次读异常后addMemory只剩gamma、insert只剩upload/c.txt；损坏收藏后只剩new-1；调用仍成功。kv.get自身失败不被Favorite decode吞掉，边界已明确。
- **修复/回归：** 不存在/读失败/解析损坏三态分开，失败拒绝覆写并保留原值；注入一次IO失败断言无写。E05/E07/H8合并，同历史“错误当空库”残余根因；详见[E勘误](2026-09-13-harmony-deep-audit/topic-e-storage.md)。

#### UI-F20 [P1] 清理孤立文件将损坏/缺失登记库当成删除全部附件的依据

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `SettingFilesPage.ets:111-128,157-195,205-208; ManagedFileStore.ets:44-71`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** rows来自可能错误为空的登记快照，loaded=true不代表数据有效；known为空时对upload逐个unlink，不核对会话引用。
- **最小修复与回归：** 错误状态禁止破坏性清理；重新核验引用并预览候选/可恢复隔离；损坏/缺失/EIO/健康库mock对照，不能仅加确认框。
- **验证及来源：** 前一轮UI编号F20，本次亲读调用链复核；F20另有真实目标方法/真实Store+mock文件系统故障注入，健康库负例保留。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F30 [P1] 重进未完成深读会隐式新建任务并替换当前轮次

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `DeepReadArticlePage.ets:116-127,129-176,282-288; scheduler.ts:108-131; run_manager.ts:293-308`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 取消意图只在页State，离页不abort；重新进入不查isRunning，仅完整缓存短路，否则新run触发REPLACE；不声称进度清零或锁内并发写。
- **最小修复与回归：** 存在run时仅订阅，取消/中断显示显式继续；测活动run重入、取消重入、完整缓存对照。
- **P1依据：** 已明确取消或仅返回阅读的用户行为不应无提示启动完整深读并中止已在执行的轮次；高成本核心任务的执行意图与页面导航混同。级别不依赖已撤回的并发写/进度清零主张，真实收费金额与发生频率未测。
- **验证及来源：** 前一轮UI编号F30，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

### P2

#### R08 [P2] 缺失工具ID时，审批与结果回填不再是一一对应

- **置信度：高，前提受限。** 非合规兼容Provider全程不给多个toolCallId；不是正常Claude delta空ID（初始tool_use的ID会被merge保留），Google也有生成ID。
- **证据/链：** `tool_loop.ts:119-143,333-359`把resume真子集按双blank对齐到全集；`tool_approval.ts:86-111`按空ID匹配全部part。单个批准可批准全部blank工具，ask_user自由文本答案可覆盖另一个工具part。
- **验证：** 真实blocker→continuation→dispatcher得到[file_list:auto,ask_user:answered]后最终两part都变ask_user；构造auto/approved态可重复file_write，但该特定态不直接从UI单批批准产生，**不泛称正常Provider必重复执行**。详见[review-b B1](2026-09-13-harmony-deep-audit/review-b.md)。
- **最小修复/回归：** 在输入边界拒绝或生成稳定身份，全链按part身份/index+turn定位，不按blank等价；测试ID后到补全/永缺/单项批准/续跑子集。原P1降P2；错配与审批别名同一身份根因合并。

#### R09 [P2] 删除墓碑同时挡住合法的备份恢复

- **置信度：高。** 同进程删除会话后恢复含该ID的备份，save抛Conversation was deleted，恢复中断。
- **证据：** `ChatRdbRepository.ets:481-512`成功delete后永久保留deletedIds；`283-300`save一律拒绝；`SettingBackupPage.ets:233`恢复没有显式recreate路径。
- **验证：** 写入/删除/恢复入口完整静态证明，独立复核；没有真实设备删数据。删除不存在ID也会留墓碑。
- **修复/回归：** 区分迟到旧写与授权重建，用代际/显式restore操作而非简单清除所有墓碑；删除→恢复成功、删除→旧流迟写仍拒绝。详见[review-e E03](2026-09-13-harmony-deep-audit/review-e.md)。与R03中途失败的表现相关，根因独立。

#### R12 [P2] 定时运行与立即运行可重叠执行同一任务

- **置信度：高。** 已启动fire期间再runNow会并发模型调用，runCount可丢更新，同conversation结果后写覆盖。
- **证据：** `AgentCronStore.ets:186-217`只清等待timer，没有inFlight锁；`agent_cron.ts:456-495,505-520,583-593`无原子running claim。
- **验证：** 真实manager+entry scheduler受控runner重入两次，count不一致；单timer对照安全。历史以“执行器不可用”排除的前提现已失效。
- **修复/回归：** 按任务原子claim、明确定时/手动冲突策略；单run、删除、重排配套测试。当前executor无工具，**仅确认重复模型/存储，不宣称已重复真实外部工具写**。详见[review-df D02](2026-09-13-harmony-deep-audit/review-df.md)。

#### R13 [P2] 定时Agent实际没有工具循环，却按任务成功完成

- **置信度：高。** 需要搜索/读取文件等工具的定时任务只会做一轮文本生成，不能实现要求的Agent工作。
- **证据：** `AppContainer.ets:441-491`构造ChatTurnDeps不传tools/loop，482只调runChatTurn，随后markCompleted；创建工具与设置入口真实可达，未提示“仅文本”。
- **验证：** 完整真实装配链静态证明；未调用真实模型。域`runCronTaskOnce`导出不等于entry使用。
- **修复/回归：** 显式定义能力范围，接工具循环前先完成R12/R17取消和审批语义；mock断言工具请求与结果持久化，不能盲目无人值守放行。详见[review-df D03](2026-09-13-harmony-deep-audit/review-df.md)。

#### R14 [P2] 子代理等待审批后停在不可恢复、不可取消的任务状态

- **置信度：高。** 子代理遇需要审批工具返回approval_required，任务板仍RUNNING但无续跑入口，cancel无效。
- **证据：** `subagent_runner.ts:376-386` → `subagent_manager.ts:207-214,445-454,593-608,735-762`，finish只接受running，cancelCapability=false。
- **验证：** 真实runner→manager闭环probe，wait/cancel均不改变状态。**admission只计running，approval_required不占并发额度**；已剔除原误报子结论。
- **修复/回归：** 明确WAITING_APPROVAL可恢复态或有错误说明的终态；至少可cancel。审批required→取消/拒绝/恢复均终结。详见[review-df D04](2026-09-13-harmony-deep-audit/review-df.md)。双端同构不作为排除理由。

#### R15 [P2] “继续写首诗”等自然聊天文本会批准全部待授权工具

- **置信度：高。** 已有pending工具时用户发以“继续”开头的纯文本，即使自动批准全关、工具mandatory，仍变approved；UI只展示✓/✕授权按钮，没有说明该句会批准。
- **证据/链：** `ChatPage.ets:3952-3977` → `tool_approval.ts:31-39,168,185-188`前缀匹配且改全部pending → `tool_permission.ts:182-184`优先接受approved。`AgentToolCapsule.ets:141-162`为显式审批UI。
- **验证：** 真实blocker/permission探针：pending wm_eval+“继续写首诗”→allow；auto态对照仍ask；无关句/图文对照不批准。无实际执行脚本或外部工具。
- **修复/回归：** 强制审批仅通过明确工具/参数绑定的用户授权，至少删宽前缀且风险工具不能靠模糊续写批准；测试全部开关关闭、多个pending、否定/继续写作句。P2保留主动用户发话的前提，不误称网页单独能批准。详见[独立补审D11](2026-09-13-harmony-deep-audit/review-d-supplement.md)。

#### R16 [P2] 超过24.855天的定时延迟被截短并提前真正执行

- **置信度：高。** enabled任务下次执行>2^31−1ms且进程连续存活至少约24.855天；短存活不声称触发。
- **证据：** `AgentCronStore.ets:207-213`直接clamp后fire；`agent_cron.ts:340-372,456-495,601`允许366天内任务但fire前不复核due。启动rescheduleAll不能修正在存活进程内的截断。
- **验证：** 真实manager+转译entry scheduler+受控Date/timer13断言，年度任务第24.855天executor被调用且记Succeeded，后续还能重复提前排期；近任务对照正常。
- **修复/回归：** 长delay分段只重排不执行，每次fire核验due；测试一年后任务第一次timer不执行、近任务仍执行。与R12锁不同根因。详见[长延迟独立报告](2026-09-13-harmony-deep-audit/review-cron-longdelay.md)。

#### R17 [P2] 已启动的定时任务没有停止通道，删除任务也不取消当前生成

- **置信度：高。** 在executor捕获task之后删除，模型仍跑完并写该conversation；删除前尚未启动则安全，不会复活调度。
- **证据：** `agent_cron.ts:368,434-443,743`无cancel callback且capability=false；`AgentCronStore.ets:145,186-221`executor无signal，cancel只clearTimer；`AppContainer.ets:441-483`捕获task后照常save。
- **验证：** 真实manager/entry scheduler受控runner：delete=true，run未打断、释放gate后完成；不重排/不复活为反例。真实模型未调用。
- **修复/回归：** run级AbortController贯穿Provider及将来工具；区别“停后续”与“取消当前”并提供入口，删除按产品语义明确处置。详见[补审S5](2026-09-13-harmony-deep-audit/review-d-supplement.md)。与R12独立。

#### R18 [P2] 协议终止/错误帧不能立即收口底层流

- **置信度：高（机制），真实等待时长未验证。** 服务端发DONE/completed/error后不关闭响应，UI仍生成，错误也迟迟不显。
- **证据：** `openai_chat_api.ts:128,134-150`、`claude_chat_api.ts:97-113`、`google_chat_api.ts:140-152`、`openai_responses_api.ts:140-181`OpenAI/Claude/Responses仅置done/error并从回调return；Google错误分支同样只缓存error且没有done标志/专门终止处理。HTTP Port无协议cancel。
- **验证：** fake流终止后保持pending，60ms未settle；真实SDK有readTimeout=120000及用户Stop，**不宣称无限挂死或确切120秒上限语义**。心跳是否延长等待待设备。
- **修复/回归：** Port显式cancel/complete，协议终止触发清理并及时resolve/reject；保留用户abort和timeout一致错误。详见[review-c C02](2026-09-13-harmony-deep-audit/review-c.md)。

#### R19 [P2] 再生/审批续跑预览重复执行非幂等视觉正则

- **置信度：高。** 用户配置非幂等assistant正则且走tool-enabled再生/续跑时，预览与发送路径不一致；不损坏落库原文。
- **证据：** `tool_loop.ts:145-150`emitSnapshot先visual transform → `ChatPage.ets:1743-1752,3441-3450`再次transform；普通发送3929-3941不重复。
- **验证：** 真实runRegenerateAtWithTools+regex，正确say *foo*被展示层再变say **foo**，存库仍say foo；默认无正则不触发。
- **修复/回归：** raw与visual回调契约唯一化，onUpdate只变换一次；对非幂等规则测send/regenerate/resume一致。详见[review-b B5](2026-09-13-harmony-deep-audit/review-b.md)。

#### R20 [P2] 深读取消未贯穿预抓和规划，取消后仍工作并写运行中状态

- **置信度：高。** 在prefetch/article-plan窗口取消，底层无signal仍继续，到writer才抛abort，并已写COLLECTING/WRITING+RUNNING。
- **证据：** `source_prefetcher.ts:105-112`collect无signal；`run_manager.ts:91-97,127,208-210,231-237`；`scheduler.ts:138-161`仅finally。文件位于`harmony/deepread/src/main/ets/research/`及`agent/`。
- **验证：** 真实scheduler/run chain受控prefetch，saves=[COLLECTING,WRITING:RUNNING]后reject。页面已有catch和cancelled展示，**不报未处理rejection或永久假完成**。
- **修复/回归：** signal贯穿collect/plan，持久化前重查，统一aborted RunResult与阶段终态；取消发生在每个await窗口的测试。详见[review-xag H2](2026-09-13-harmony-deep-audit/review-xag.md)。

#### R22 [P2] 深读通知共用ID，完成发布与finally取消相互竞争

- **置信度：中高（代码竞态），设备表现未测。** 完成通知可能刚发布就被撤回；REPLACE旧run可能撤掉新run通知。
- **证据：** `scheduler.ts:150-159`完成后无条件cancelRunning；`NotificationNotifier.ets:118-122,149-169`running/completed/cancel共用id，notifyCompleted不await/return publish，cancel也void。
- **验证：** 真实scheduler probe证明调用顺序；`probes-xag/probe-h10-real.mjs`转译真实Notifier并mock平台deferred，publish先落地则无通知，cancel先落地则完成通知保留。**原手写转写probe仅说明，不作证据；不说每次正常必消失**。
- **修复/回归：** await真实publish，成功后不撤同一完成通知，旧run cancel受token约束或独立ID；测试两种完成顺序及REPLACE。详见[review-xag H10补遗](2026-09-13-harmony-deep-audit/review-xag.md)。

#### R23 [P2] 在非首页页面接收Want时，目标跳转滞留到返回首页

- **置信度：高（静态）。** 当前栈顶ChatPage/设置页时点击通知/收到参数Want，不立即进入目标；未消费参数可被后续Want覆盖。
- **证据：** `EntryAbility.ets:84-116`onNewWant只写AppStorage；唯一消费者`ChatListPage.ets:68,81,85-111`show/appear；其它页无消费者。
- **验证：** 入口→存储→消费路径独立复核；冷启动首页消费正常是反例。无设备通知点击证据，称“滞留”而非所有场景永久丢失。
- **修复/回归：** 统一前台路由派发，稳定参数校验/单次消费；测试冷启动、首页、会话页、设置页连续Want。详见[review-xag AG2](2026-09-13-harmony-deep-audit/review-xag.md)。

#### UI-F08 [P2] 会话列表预览全量扇出并逐条复制全部结果键

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatListPage.ets:60-82,120-166`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** refreshSeq挡旧list回调，不能称必2N getById；获胜刷新仍N个无界并发getById和累计O(N²)键复制。
- **最小修复与回归：** 批量/限并发加载预览，聚合后提交；N=100/1000合成库检查并发上限与复制量。
- **验证及来源：** 前一轮UI编号F08，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F14 [P2] 历史页无法进入选择模式，批量导出不可达

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `HistoryPage.ets:39,90,101-104,166-170,211`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** selecting初值false且只有置false写点；选择按钮调用exitSelecting，SettingPage有活入口。
- **最小修复与回归：** 选择/取消分别置true/false；检查进入多选、勾选、导出、退出。
- **验证及来源：** 前一轮UI编号F14，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F15 [P2] 工具正常输出包含error等词被标成失败

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatMessageTools.ets:33-54; AgentToolCapsule.ets:67-101`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 原始正文includes(error/exception/failed)判Failed，搜索/源码/日志正文可正常包含；与R05不同代码和后果。
- **最小修复与回归：** 执行层提供结构化终态，不让任意正文决定状态；正常error handling正文与真实失败对照。
- **验证及来源：** 前一轮UI编号F15，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F16 / UI-F16a [P2] 八项显示/输入设置未接消费，用户头像开关还绑错字段

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `SettingDisplayPage.ets:96-154; MarkdownText.ets:646-722; ChatInput.ets:197-258`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** showAssistantBubble/showModelName/showModelIcon/sendOnEnter/pasteLongTextAsFile/codeBlockAutoCollapse/showLineNumbers/fontSizeRatio有UI但无对应消费；F16a并入不另计。
- **最小修复与回归：** 逐键接线或隐藏未支持设置；以render/input spy验证每个开关改变行为，其余有效显示设置不误伤。
- **验证及来源：** 前一轮UI编号F16,F16a，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F19 [P2] Slides字体页面完成按钮不保存任何设置

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `SettingSlidesFontPage.ets:21-23,32-51,117,144-148; SkillsPage.ets:173`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 私有常量加载、本地State setter、done仅back，无存储/消费者，字体族预览也固定。
- **最小修复与回归：** 接真实配置并成功后返回，或下线占位入口；改字号字体→完成→重进持久化回归。
- **验证及来源：** 前一轮UI编号F19，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F21 [P2] 子代理和议会展开编辑直接返回会丢未提交配置

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `SettingSubAgentPage.ets:237-245,304-316; SettingModelCouncilPage.ets:259-266,309-324`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 文本只改State，折叠/其它保存点才整份写盘；直接返回无dirty提示/flush；切行后再保存不会丢的反例保留。
- **最小修复与回归：** 显式保存取消/自动保存并显示错误；编辑后直接返回与折叠后返回对照。
- **验证及来源：** 前一轮UI编号F21，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F29 [P2] editorial_slant模板缩放按钮不影响内容

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `DeepReadArticlePage.ets:305-313,388-399,464-479; DeepReadTemplate.ets:151`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** fontScale改变但Web模板函数不消费，原生分支消费；fontSerif无消费作为同显示配置链子项。
- **最小修复与回归：** 模板接fontScale或隐藏不支持按钮；原生/Web模板同输入比较，字体模式单独核验。
- **验证及来源：** 前一轮UI编号F29，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F32 [P2] 收进章节后跳错资料建议二级页签

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `NovelWorkspacePage.ets:109,951-965,1756,1845-1873`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 成功只tab=2，settingsSection仍0，建议在section1，用户不能直接看刚生成建议。
- **最小修复与回归：** 明确tab和settingsSection或保留正文显示提示；初始section0收集后应定位资料建议。
- **验证及来源：** 前一轮UI编号F32，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F34 [P2] 助手绑定模型与菜单显示/切换模型不一致

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `AppContainer.ets:1003-1021; ChatPage.ets:2814-2822,2840-2849; ChatModelPickerPage.ets:60-74`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 实际runtime优先assistant.chatModelId，UI只读写全局；带绑定助手通过恢复可达，手动绑定旧助手页不可达。
- **最小修复与回归：** 展示和请求采用同一effective model，提供清除绑定；合成恢复助手绑定A/全局B后比较菜单与请求。
- **验证及来源：** 前一轮UI编号F34，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F35 [P2] 助手工具和扩展开关并发写入可丢失更新

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatAssistantExtensionsPage.ets:84-135; ChatAssistantLocalToolsPage.ets:135-160; ChatAssistantMcpPage.ets:97-123; kv_store.ts:59-60`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 依据旧this.assistant算列表，await load后整份put；assistants键无共享RMW锁，其它键的mutex不保护它。
- **最小修复与回归：** 按助手/键串行mutation，锁内读最新并变更字段；受控两次开关乱序写验证均保留，避免仅锁旧快照写。
- **验证及来源：** 前一轮UI编号F35，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F38 / UI-F40 [P2] 自绘覆盖层未接管返回，返回跳过弹窗层级

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `NovelWorkspacePage.ets:2030-2168; ChatPage.ets:777-781,4631-4640,4858-4936`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 小说无onBackPress，ChatPage只处理不可达drawer；模型菜单/拒绝/编辑等自绘层不能像系统sheet消费返回；同返回层级契约合并一项。
- **最小修复与回归：** 统一overlay优先返回，编辑脏数据确认；测试关闭弹窗而非退出页面，系统sheet不重复拦截。
- **验证及来源：** 前一轮UI编号F38,F40，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F41 / UI-F42 [P2] 页面加载状态不闭合：返回卸载列表、加载前误报为空

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `NovelProjectsPage.ets:33-54,282-294; ChatPage.ets:4836; CouncilPage.ets:43,68-71,235-237`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 小说返回先loading=true移除既有列表，Chat/Council未区分未加载和空；逻辑分支可证，闪烁时长/滚动损失待设备；合并状态展示契约一项。
- **最小修复与回归：** 初载/刷新/空/错分离，刷新保留旧内容；慢fake库断言不会显示空库或卸载已读列表。
- **验证及来源：** 前一轮UI编号F41,F42，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F44 [P2] 聊天文档附件没有打开或预览路径

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ContentAttachmentChip.ets:15-33; AgentMessageRow.ets:351-376`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 文档chip无onOpen，用户消息整体点击编辑不等于预览；音视频仅导入历史可出现，不泛化当前产生。
- **最小修复与回归：** 支持类型绑定open/preview，无法打开明确标记；用户及助手文档两路径测试。
- **验证及来源：** 前一轮UI编号F44，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-X01 [P2] 已完成深读缓存早退，重进忽略已保存字号和模板

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `DeepReadArticlePage.ets:69,76-77,129-150`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** isComplete命中137-140return，唯一KV显示设置读取144-150被跳过，无onPageShow；完整缓存只用默认1.0/none，部分缓存对照才读设置。
- **最小修复与回归：** 读取显示偏好与生成/缓存命中解耦；完整/部分/无缓存三种入口均读取，已完成不触发新生成。
- **验证及来源：** 前一轮UI编号X01，本次亲读调用链复核；X01另有真实loadArticle原文提取方法的完整/部分缓存隔离对照。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

### P3

#### R11 [P3] 会话删除未级联清理压缩摘要与审计KV

- **级别/置信度：P3 / 高（静态）。** 可达会话删除入口调用cleanupDeletedConversation，但未清理compacts/<conversationId>及事件键；摘要可能含原文，形成残留数据，不声称外部泄露。
- **证据/调用链：** `harmony/entry/src/main/ets/pages/ChatListPage.ets:248-268` → `platform_impl/ManagedFileStore.ets:208-227`，缺少`EntryCompactStore.ets:53-59`的deleteByConversation。
- **本次重要纠偏：** 深审R11原“ChatPage抽屉漏清理”子路径不可达：`ChatPage.ets:2704-2714` openDrawer零调用、drawerOpen仅此置true；故撤回其作为当前可达附件/收藏泄漏的主张，转死路径维护债。不是修好了，而是原可达性判断错误。
- **验证：** 合并阶段两个代理及主代理独立核对入口/全部drawer赋值；未删除任何真实会话或文件。
- **P3依据：** 本次确认的是应用私有KV中不可见的孤儿摘要/事件残留；没有证实删除后正常UI再读回、跨会话串用或外部可读路径。若后续证明隐私承诺或外部读取链受损，应上调，不因所有删除都残留而直接推断已泄露。
- **最小修复/回归：** 在统一可达删除编排中清理compact及events，并防在途摘要迟到复活；所有真实删除入口一致。不要仅修死抽屉。
- **来源：** 深审R11/E06/E09；本次[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)。

#### R24 [P3] 规划失败的深读降级路径给后段分配零证据

- **置信度：高。前提：** 仅article-plan解析/生成失败走fallbackPlan，来源≤6/12；正常normalizePlan从全局池补满，不受此问题影响。
- **证据/验证：** `harmony/deepread/src/main/ets/research/article_plan.ts:31-34,259-263`fallback直接用连续bucket；`evidence_pack.ts:109-115,124-156`；真实probe比较fallback与normalize，n=12仅fallback后两段0，normal每段6。
- **修复/回归：** 降级计划同样用全局卡池补满；测试规划失败而不是只测分桶。详见[review-xag H1](2026-09-13-harmony-deep-audit/review-xag.md)。原P1“常态无证据”已排除。

#### R25 [P3] 清理工具未限定终态，可删正在运行的任务看板记录和取消回调

- **置信度：高。前提：** 模型调用并获授权cleanup运行中task；工具描述仅承诺清理完成/失败/中断/取消任务。
- **证据/验证：** `agent_task_tools.ts:219`描述 vs `agent_task.ts:755-770`无status门，直接tasks.delete/cancelCallbacks.delete；真实store探针核验。根因不是允许删除private output的授权档位。
- **影响/边界：** 任务仍跑却从统一看板消失，该看板取消通道被删；**子代理manager自身cancel可能仍可用，不称全系统永不可取消**。
- **修复/回归：** 非终态拒绝cleanup，或先显式取消再清；测running/retrying及四种终态。详见[补审S4](2026-09-13-harmony-deep-audit/review-d-supplement.md)。

#### R26 [P3] 主题模式保存失败没有收口，显示与重启后的模式可能不一致

- **置信度：高（失败路径）。** KV写入拒绝时，页面已换色，put没有await/catch，不能告知或回滚。
- **证据/验证：** `harmony/entry/src/main/ets/pages/SettingAppearancePage.ets:37-45`，与47-56 accent保存处理作对照；静态证明，未制造真实存储错误，也不据此断言崩溃。
- **修复/回归：** await落盘并处理错误、回滚或明确未保存；注入reject断言错误提示且无unhandled rejection。详见[AG8](2026-09-13-harmony-deep-audit/topic-ag-platform-ui.md)。与主题响应式待验项分开。

#### R27 [P3] 编辑重发的前置保存晚到后可在销毁页启动新生成

- **置信度：中高（代码链），设备时序未测。** runSendEdit异步invalidate/save期间退出，完成后仍regenerate；此时原退出abort还没有对应的新controller可取消。
- **证据：** `ChatPage.ets:2301-2333`缺operationToken/pageAlive门；再生1685之后新建controller。下游`isGenerationOperationActive`过滤UI及其它会话，所以**未确认跨会话串写/数据损坏**。
- **验证：** 入口/await/离页/下游token保护静态交叉追踪；无页面实例运行probe，影响限定为无意后台生成/资源耗用。
- **修复/回归：** 前置异步链也捕获token，离页后不启动新生成；受控保存延迟+立刻返回验证Provider未被调用。详见[review-b B4](2026-09-13-harmony-deep-audit/review-b.md)。

#### UI-F07 [P3] 热力图渲染按活跃日期重复排序

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ProfilePage.ets:590-641`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 每build重建371个Date；只在非零天排序，次数≤min(371,activeDays)，不是恒371次；重复工作可证，掉帧未测。
- **最小修复与回归：** loadStats后一次计算日期和阈值，build只读；用稀疏/稠密计数比较sort次数。
- **验证及来源：** 前一轮UI编号F07，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F09 [P3] 静止助手头像仍各自启动525ms定时器

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatMessageAvatar.ets:19-46; AgentMessageRow.ets:433`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 启动只排除user，不判断streaming；约1.9N次/秒状态翻转，无实际帧率证据，销毁清理存在。
- **最小修复与回归：** 随streaming和页面可见性启停，共享时间源；假时钟断言静止消息不调度。
- **验证及来源：** 前一轮UI编号F09，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F12 [P3] 子代理/议会阶段标签缺乏真实阶段驱动

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatMessageSubAgentStep.ets:77-81; ChatMessageCouncilStep.ets:86-102; CouncilRunPage.ets:110-116,165`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 子代理按墙钟算阶段但无tick，议会轮换为空或按固定时钟，不是实际任务phase；不称任务本身卡死。
- **最小修复与回归：** 绑定真实状态；无阶段事件则显示运行状态，不伪造进度；测慢任务/无新输出时文案。
- **验证及来源：** 前一轮UI编号F12，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F13 [P3] ChatPage隐藏后问候动画计时器继续运行

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatPage.ets:687-690,767-775,889-894`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** onPageHide只停cursor，不停3.2秒greetTimer；destroy才停，回调无visible门。
- **最小修复与回归：** show/hide对称启停，保留destroy清理；假生命周期测试覆盖push子页与返回。
- **验证及来源：** 前一轮UI编号F13，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F17 [P3] ask_user单问题重复显示标题和正文

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatMessageAskUserStep.ets:41-52,169-174`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 单问题heading返回问题，ForEach再次渲染同一问题，多问题对照不重复。
- **最小修复与回归：** 单问题只渲染一次；测试1个及多个问题的文本树。
- **验证及来源：** 前一轮UI编号F17，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

#### UI-F23 [P3] MCP空名称保存按钮静默无反馈

- **状态/置信度：** 当前关键实现复核确认，高（静态机制）；设备呈现和频率未测。
- **源码锚点：** `ChatMcpSettingsPage.ets:101-115,485-488`（完整文件映射见第12节；同名行号按该映射解析）。
- **影响、前提与反证：** 空名称return是正确拒绝，但按钮仍可点且无错误状态/文案；不称应用实际卡死。
- **最小修复与回归：** 禁用无效提交或行内提示；空/空白/合法名称三种输入测试。
- **验证及来源：** 前一轮UI编号F23，本次亲读调用链复核；未做设备UI实测，不以源码检查冒充截图。 详见[高风险复核](2026-09-13-harmony-merged-evidence/ui-risk-recheck.md)、[交互复核](2026-09-13-harmony-merged-evidence/ui-interaction-recheck.md)、[布局/性能复核](2026-09-13-harmony-merged-evidence/ui-visual-recheck.md)。

## 4. 已证实的潜伏安全缺陷（独立计数2项）

以下真实工具边界缺陷已隔离证实，但当前mount/编译阻断遮蔽设备端利用，不称真实隐私已经泄露；修复WebMount功能前必须一起消除。

### L01 [P1 潜伏] wm_click目标字符串可执行任意JS，关闭wm_eval也无法阻止

- **置信度：高（真实工具逻辑）；端到端利用未验证。** `WebMountTools.ets:120-143`target虽在querySelector中JSON编码，135行错误字符串又未经转义插入；未命中CSS目标时可执行额外表达式。
- **授权链：** click只needsApproval，默认allowsAutoApproval=true/mandatory=false；`tool_policy.ts:473-479`及`tool_permission.ts:256-258`单开自动批准可过。`wm_eval:196-206`强制门与includeEval=false不约束click。run-trust无生产写入，不作为绕过。
- **验证：** 真实WebMountTools转译execute、VM仅改合成window标记；includeEval=false正例及转义负例；独立19断言复核。没有访问cookie、网页或外网。
- **阻塞/修复：** 当前R07导致首次导航失败，R01导致本快照不可构建，因此不称真实用户已可被外泄；挂载修复前必须修所有字符串编码并统一显式审批。回归含单双引号、反斜杠、换行及关闭eval。详见[F01](2026-09-13-harmony-deep-audit/topic-f-security.md)、[review-df](2026-09-13-harmony-deep-audit/review-df.md)。D01并入，不重复统计。

### L02 [P1 潜伏] WebMount白名单误认userinfo为主机，并接受非HTTP scheme

- **置信度：高（真实gate）；端到端利用未验证。** `WebMountSites.ets:73-88`用authority.split(':')[0]取host；带冒号userinfo可让授权域成为返回host，而实际主机是outside.invalid。无冒号userinfo的负例会拒绝，攻击前提已收窄。
- **证据/验证：** 真siteAllowsUrl合成URL→true，标准URL.hostname→未授权主机；javascript/file scheme也过文本gate。`WebMountTools.ets:83-92`未经标准URL规范化直传open，无每导航检查。
- **影响/限制：** 站点授权边界在逻辑上可绕；受R07/R01遮蔽，没有实际WebView导航或数据读取证明。具体scheme能否被ArkWeb加载也未实测，不当作已执行路径。
- **最小修复/回归：** 标准URL解析、http(s)限定、userinfo拒绝、实际导航/重定向/revoke复查；覆盖IPv6/端口/编码/子域/不同scheme。详见[F02](2026-09-13-harmony-deep-audit/topic-f-security.md)、[review-df](2026-09-13-harmony-deep-audit/review-df.md)。与L01代码注入不同根因。

## 5. UI运行时待验（10组，不计确认Bug）

|来源|风险|缺少的证据/验证边界|
|---|---|---|
|UI-F01|小说 pane 百分比高度与剩余空间不匹配|结构约束已核，实际裁切/可滚达性需设备，不套用CSS祖先规则|
|UI-F02|分隔线100%宽叠加margin|占位约束与内缩目标矛盾，实际裁切/全出血需设备|
|UI-F03|附件展开挤压输入框|约束估算文本余宽W−320vp：320为0、360仍40；需设备检查ArkUI布局分配|
|UI-F04|分享锚点固定像素坐标|固定660/2200确实存在，屏外行为/SDK回退未知|
|UI-F05|多行时间轴固定54vp竖线|几何/样式风险保留，未实机截图确认断裂|
|UI-F10|小说全量时间线与返回滚底|全量ForEach/强制scroll事实成立，规模卡顿和用户阅读位置损失需设备；不把产品跟随策略自动当Bug|
|UI-F24|MCP输入中trim可能吞掉Bearer空格|onChange trim回写为事实，ArkUI光标/选区及尾空格行为待设备；草稿应保留raw|
|UI-F25|导出选择器返回空URI仍私有落盘并报成功|代码分支已证，取消在当前SDK返回[]还是reject需设备；不将真实取消必报成功当已实测，部分恢复子项并R03|
|UI-F27|MiniApp图片预取后refresh可能重复初始化副作用|refresh确实存在；ai.generate/sendToConversation/createArtifact未实现，撤回重复计费断言，只验证storage/fetch/search等实际可用方法|
|UI-F37|弹窗卡片空白点击可能传播至遮罩关闭|缺内容消费点击且有正确反例，但真实hit-test须设备；不声称已经丢用户输入|


## 6. UI体验、设计或维护建议（9组，不计确认Bug）

**最终裁决优先于复核候选标签。** 三份recheck是独立候选意见；合并主审再次收窄：F18/F22/F26/F28/F33从“确认Bug”降为本节建议，F27从“确认P2”移第5节运行时待验。前者是可完成动作的交互/设计取舍，后者尚缺真实WebView重载副作用证据；原始意见保留作可追溯记录，不与最终数量相加。

**Bug与建议的边界：** 明确点击“删除”且执行所选删除，没有第二次确认本身不证明行为错误，故F22/F28为安全体验改进；用户点击“保存”却因未提示的字段校验静默拒绝，完成条件与错误状态对用户不可见，F23保留P3反馈缺陷，不声称数据损坏或应用卡死。缺图标、轮转选值、长按菜单若仍能完成动作，原则上列建议。

|来源|主题|不计Bug的依据/保留建议|
|---|---|---|
|UI-F06|顶栏规格差异|水平基准不同是打磨，原总高差15vp推算未闭合|
|UI-F11|搜索输入即清旧结果|清空发生在debounce前，属体验取舍；可建议保留旧结果，不作数据/功能故障|
|UI-F18|MiniApp更多菜单仅长按|LongPress为明确设计且菜单能用；点按可发现性改进保留，不当关键流程不可用|
|UI-F22|MCP删除缺撤销或二次确认|用户先滑出再明确点删除，属于授权删除；无额外确认不足以单独证明Bug，仍建议保护不可恢复配置|
|UI-F26|循环选择器可发现性与滑块多次落盘|无chevron/轮转选择本身是设计取舍；落盘放大已定位，但覆盖更新/卡顿未证，建议提交结束防抖|
|UI-F28|章节/资料删除缺恢复保护|明确点击删除会永久移除，但缺确认不自动等价越权/误删Bug；列高优先UX安全改进，设备核实误触风险|
|UI-F31|会话手动重命名代码无入口|startEditTitle及drawer均无入口，全局无替代手动rename；但没有可见按钮承诺，按功能缺口/死代码登记而非现行点击故障|
|UI-F33|重新分析只扫末章且范围不明|源码只last章为事实，标题未明确承诺整项目，不擅自扩大需求；明确范围/展示warning为改进|
|UI-F36|通讯录暂不可用项点击无反馈|已显示暂不可用/ACL原因，未授权与不可用非同义；撤回撒谎/权限故障定性|


## 7. 全部UI来源编号去向（44主编号+F16a子项+X01补查）

|来源编号|最终归属|说明|
|---|---|---|
|UI-F01|第5节待设备|小说 pane 百分比高度与剩余空间不匹配|
|UI-F02|第5节待设备|分隔线100%宽叠加margin|
|UI-F03|第5节待设备|附件展开挤压输入框|
|UI-F04|第5节待设备|分享锚点固定像素坐标|
|UI-F05|第5节待设备|多行时间轴固定54vp竖线|
|UI-F06|第6节建议/维护|顶栏规格差异|
|UI-F07|P3确认|热力图渲染按活跃日期重复排序|
|UI-F08|P2确认|会话列表预览全量扇出并逐条复制全部结果键|
|UI-F09|P3确认|静止助手头像仍各自启动525ms定时器|
|UI-F10|第5节待设备|小说全量时间线与返回滚底|
|UI-F11|第6节建议/维护|搜索输入即清旧结果|
|UI-F12|P3确认|子代理/议会阶段标签缺乏真实阶段驱动|
|UI-F13|P3确认|ChatPage隐藏后问候动画计时器继续运行|
|UI-F14|P2确认|历史页无法进入选择模式，批量导出不可达|
|UI-F15|P2确认|工具正常输出包含error等词被标成失败|
|UI-F16,UI-F16a|P2确认|八项显示/输入设置未接消费，用户头像开关还绑错字段|
|UI-F17|P3确认|ask_user单问题重复显示标题和正文|
|UI-F18|第6节建议/维护|MiniApp更多菜单仅长按|
|UI-F19|P2确认|Slides字体页面完成按钮不保存任何设置|
|UI-F20|P1确认|清理孤立文件将损坏/缺失登记库当成删除全部附件的依据|
|UI-F21|P2确认|子代理和议会展开编辑直接返回会丢未提交配置|
|UI-F22|第6节建议/维护|MCP删除缺撤销或二次确认|
|UI-F23|P3确认|MCP空名称保存按钮静默无反馈|
|UI-F24|第5节待设备|MCP输入中trim可能吞掉Bearer空格|
|UI-F25|第5节待设备|导出选择器返回空URI仍私有落盘并报成功|
|UI-F26|第6节建议/维护|循环选择器可发现性与滑块多次落盘|
|UI-F27|第5节待设备|MiniApp图片预取后refresh可能重复初始化副作用|
|UI-F28|第6节建议/维护|章节/资料删除缺恢复保护|
|UI-F29|P2确认|editorial_slant模板缩放按钮不影响内容|
|UI-F30|P1确认|重进未完成深读会隐式新建任务并替换当前轮次|
|UI-F31|第6节建议/维护|会话手动重命名代码无入口|
|UI-F32|P2确认|收进章节后跳错资料建议二级页签|
|UI-F33|第6节建议/维护|重新分析只扫末章且范围不明|
|UI-F34|P2确认|助手绑定模型与菜单显示/切换模型不一致|
|UI-F35|P2确认|助手工具和扩展开关并发写入可丢失更新|
|UI-F36|第6节建议/维护|通讯录暂不可用项点击无反馈|
|UI-F37|第5节待设备|弹窗卡片空白点击可能传播至遮罩关闭|
|UI-F38,UI-F40|P2确认|自绘覆盖层未接管返回，返回跳过弹窗层级|
|UI-F39|并入R23|通知/Want路由不及时消费|
|UI-F41,UI-F42|P2确认|页面加载状态不闭合：返回卸载列表、加载前误报为空|
|UI-F43|并入R07|WebMount挂载标志永不就绪|
|UI-F44|P2确认|聊天文档附件没有打开或预览路径|
|UI-X01|P2确认|已完成深读缓存早退，重进忽略已保存字号和模板|


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

## 12. 源码文件名到仓库路径映射

本附录解析正文简写锚点；同名多文件时以正文领域说明确定，不改源码行号。

|文件名|仓库相对路径|
|---|---|
|`AgentCronStore.ets`|`harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets`|
|`AgentMessageRow.ets`|`harmony/entry/src/main/ets/components/AgentMessageRow.ets`|
|`AgentToolCapsule.ets`|`harmony/entry/src/main/ets/components/AgentToolCapsule.ets`|
|`AppContainer.ets`|`harmony/entry/src/main/ets/di/AppContainer.ets`|
|`ChatAssistantExtensionsPage.ets`|`harmony/entry/src/main/ets/pages/ChatAssistantExtensionsPage.ets`|
|`ChatAssistantLocalToolsPage.ets`|`harmony/entry/src/main/ets/pages/ChatAssistantLocalToolsPage.ets`|
|`ChatAssistantMcpPage.ets`|`harmony/entry/src/main/ets/pages/ChatAssistantMcpPage.ets`|
|`ChatInput.ets`|`harmony/entry/src/main/ets/components/ChatInput.ets`|
|`ChatListPage.ets`|`harmony/entry/src/main/ets/pages/ChatListPage.ets`|
|`ChatMcpSettingsPage.ets`|`harmony/entry/src/main/ets/pages/ChatMcpSettingsPage.ets`|
|`ChatMessageAskUserStep.ets`|`harmony/entry/src/main/ets/components/ChatMessageAskUserStep.ets`|
|`ChatMessageAvatar.ets`|`harmony/entry/src/main/ets/components/ChatMessageAvatar.ets`|
|`ChatMessageCouncilStep.ets`|`harmony/entry/src/main/ets/components/ChatMessageCouncilStep.ets`|
|`ChatMessageSubAgentStep.ets`|`harmony/entry/src/main/ets/components/ChatMessageSubAgentStep.ets`|
|`ChatMessageTools.ets`|`harmony/entry/src/main/ets/components/ChatMessageTools.ets`|
|`ChatModelPickerPage.ets`|`harmony/entry/src/main/ets/pages/ChatModelPickerPage.ets`|
|`ChatPage.ets`|`harmony/entry/src/main/ets/pages/ChatPage.ets`|
|`ChatRdbRepository.ets`|`harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets`|
|`ContentAttachmentChip.ets`|`harmony/entry/src/main/ets/components/ContentAttachmentChip.ets`|
|`CouncilPage.ets`|`harmony/entry/src/main/ets/pages/CouncilPage.ets`|
|`CouncilRunPage.ets`|`harmony/entry/src/main/ets/pages/CouncilRunPage.ets`|
|`DeepReadArticlePage.ets`|`harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets`|
|`DeepReadTemplate.ets`|`harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets`|
|`EntryAbility.ets`|`harmony/entry/src/main/ets/entryability/EntryAbility.ets`|
|`EntryCompactStore.ets`|`harmony/entry/src/main/ets/platform_impl/EntryCompactStore.ets`|
|`FavoriteStore.ets`|`harmony/entry/src/main/ets/platform_impl/FavoriteStore.ets`|
|`HistoryPage.ets`|`harmony/entry/src/main/ets/pages/HistoryPage.ets`|
|`ManagedFileStore.ets`|`harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets`|
|`MarkdownText.ets`|`harmony/entry/src/main/ets/components/MarkdownText.ets`|
|`MemoryStore.ets`|`harmony/entry/src/main/ets/platform_impl/MemoryStore.ets`|
|`NotificationNotifier.ets`|`harmony/entry/src/main/ets/platform_impl/NotificationNotifier.ets`|
|`NovelProjectsPage.ets`|`harmony/entry/src/main/ets/pages/NovelProjectsPage.ets`|
|`NovelWorkspacePage.ets`|`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets`|
|`ProfilePage.ets`|`harmony/entry/src/main/ets/pages/ProfilePage.ets`|
|`SettingAppearancePage.ets`|`harmony/entry/src/main/ets/pages/SettingAppearancePage.ets`|
|`SettingBackupPage.ets`|`harmony/entry/src/main/ets/pages/SettingBackupPage.ets`|
|`SettingDisplayPage.ets`|`harmony/entry/src/main/ets/pages/SettingDisplayPage.ets`|
|`SettingExperimentalWebMountPage.ets`|`harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets`|
|`SettingFilesPage.ets`|`harmony/entry/src/main/ets/pages/SettingFilesPage.ets`|
|`SettingModelCouncilPage.ets`|`harmony/entry/src/main/ets/pages/SettingModelCouncilPage.ets`|
|`SettingSlidesFontPage.ets`|`harmony/entry/src/main/ets/pages/SettingSlidesFontPage.ets`|
|`SettingSubAgentPage.ets`|`harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets`|
|`SkillsPage.ets`|`harmony/entry/src/main/ets/pages/SkillsPage.ets`|
|`SyncCryptoPort.ets`|`harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets`|
|`WebMountOAuthSupport.ets`|`harmony/entry/src/main/ets/platform_impl/WebMountOAuthSupport.ets`|
|`WebMountSession.ets`|`harmony/entry/src/main/ets/platform_impl/WebMountSession.ets`|
|`WebMountSites.ets`|`harmony/entry/src/main/ets/platform_impl/WebMountSites.ets`|
|`WebMountTools.ets`|`harmony/entry/src/main/ets/platform_impl/WebMountTools.ets`|
|`agent_cron.ts`|`harmony/chat/src/main/ets/chat/agent_cron.ts`|
|`agent_task.ts`|`harmony/chat/src/main/ets/chat/agent_task.ts`|
|`agent_task_tools.ts`|`harmony/chat/src/main/ets/chat/agent_task_tools.ts`|
|`article_plan.ts`|`harmony/deepread/src/main/ets/research/article_plan.ts`|
|`chat_kernel_host.ts`|`harmony/chat/src/main/ets/chat/chat_kernel_host.ts`|
|`claude_chat_api.ts`|`harmony/chat/src/main/ets/chat/claude_chat_api.ts`|
|`claude_request.ts`|`harmony/chat/src/main/ets/chat/claude_request.ts`|
|`conversation_export.ts`|`harmony/chat/src/main/ets/chat/conversation_export.ts`|
|`evidence_pack.ts`|`harmony/deepread/src/main/ets/research/evidence_pack.ts`|
|`google_chat_api.ts`|`harmony/chat/src/main/ets/chat/google_chat_api.ts`|
|`kv_store.ts`|`harmony/chat/src/main/ets/chat/kv_store.ts`|
|`openai_chat_api.ts`|`harmony/chat/src/main/ets/chat/openai_chat_api.ts`|
|`openai_request.ts`|`harmony/chat/src/main/ets/chat/openai_request.ts`|
|`openai_responses_api.ts`|`harmony/chat/src/main/ets/chat/openai_responses_api.ts`|
|`run_manager.ts`|`harmony/deepread/src/main/ets/agent/run_manager.ts`|
|`scheduler.ts`|`harmony/deepread/src/main/ets/agent/scheduler.ts`|
|`source_prefetcher.ts`|`harmony/deepread/src/main/ets/research/source_prefetcher.ts`|
|`sse_assembler.ts`|`harmony/deepread/src/main/ets/platform/sse_assembler.ts`|
|`subagent_manager.ts`|`harmony/chat/src/main/ets/chat/subagent_manager.ts`|
|`subagent_runner.ts`|`harmony/chat/src/main/ets/chat/subagent_runner.ts`|
|`sync_snapshot.ts`|`harmony/chat/src/main/ets/chat/sync_snapshot.ts`|
|`tool_approval.ts`|`harmony/chat/src/main/ets/chat/tool_approval.ts`|
|`tool_loop.ts`|`harmony/chat/src/main/ets/chat/tool_loop.ts`|
|`tool_permission.ts`|`harmony/chat/src/main/ets/chat/tool_permission.ts`|
|`tool_policy.ts`|`harmony/chat/src/main/ets/chat/tool_policy.ts`|
