# 最终确认发现

本文件是统一去重后的正式清单，覆盖并纠正topic初审结论。**26项当前代码缺陷：P1×8、P2×14、P3×4；另有2项P1级潜伏安全缺陷，工具逻辑已证实、端到端利用受阻。没有确认P0。** 严重度与置信度分开；“确认”可基于确定性静态证明，不表示设备实测。

相对路径均从仓库根解析；源码行号为1-based。每项补充证据、具体测试源码与反证详见链接专题/独立复核报告。所有修复方向仅是建议，本次没有实施。R编号保留审查轨迹：R21在最终质量复核后移入待验证配置契约，不计入确认数。

## P1 — 当前代码缺陷

### R01 当前工作树无法编译为Harmony应用
- **置信度：高。影响/前提：** 当前源码构建必失败，不能产出本轮可安装HAP；不是推断运行时崩溃，也不是P0数据事故。
- **证据：** `harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets:21,28,31-32,90`；`WebMountOAuthSupport.ets:38`；`harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets:154,157,182`。请求了不存在的crypto API/type、错误initSync参数及ArkTS不接受的匿名对象类型。
- **验证：** 官方构建包装器exit255，CompileArkTS总计ERROR:12/WARN:502，列出11条路径诊断；本地SDK声明独立复核。目标语句在起始未提交改动内；未另构建HEAD，不对HEAD全树可编译性作保证。
- **保护为何无效：** chat/deepread的tsx/tsc不编译entry ArkTS及SDK签名；针对性lint自己报内部错误，不能替代编译。
- **最小修复/回归：** 按API12可用SDK签名改SymKeyGenerator/Random/Cipher调用，显式interface；CompileArkTS通过后做真实GCM/PKCE测试及装机。详见[验证记录](validation.md)、[独立复核](review-xag.md)。同根编译诊断合并一项。

### R02 完整备份复用了扁平导出，恢复后消息分支和会话状态不可逆缩水
- **置信度：高。影响/前提：** 有重新生成分支的会话备份→迁移/恢复后只剩所选回答，建议/置顶/自动批准状态被重置；页面仍报恢复成功。
- **证据/链：** `harmony/chat/src/main/ets/chat/sync_snapshot.ts:181-190` → `conversation_export.ts:122-150`（每node只取selected message）→ `harmony/entry/src/main/ets/pages/SettingBackupPage.ets:219-234`（toMessageNode，suggestions=[]、pinned/autoApprove=false）。
- **机制：** 人类可读导出DTO不包含node多分支和元数据，恢复不可能从无信息数据还原。密文校验只能证明字节完整，不能补回未导出的字段。
- **验证：** 真实createSyncBackup→restoreSyncBackup→toMessageNode合成crypto往返，双分支变单条、selectIndex 1→0；独立复核确认。未用真实设备备份。
- **最小修复/回归：** 单独可逆SyncConversation DTO，完整节点/选择索引/状态；版本迁移并明确旧格式限制。回归比较完整会话深等价。详见[存储复核E01](review-e.md)、[E初审](topic-e-storage.md)。附件文件树未备份另列盲区，不据此增加计数。

### R03 恢复没有整体校验与回滚，失败可留下已覆盖的部分数据
- **置信度：高。影响/前提：** 可正确解密但内部结构非法/异版本的归档，或多条恢复中途存储失败：前面会话已覆盖、后面报错，形成混合状态。
- **证据/链：** `sync_snapshot.ts:277-302`将JSON强转SyncPayload，无validator → `SettingBackupPage.ets:219-246`逐条save再覆盖settings → `254-258`仅显示错误。
- **机制/保护：** 整体修改之前没有结构/重复ID验证或事务；catch不能撤回之前save。**普通密文截断/篡改并不能凭此绕过sha/GCM**，此项不声称加密漏洞。
- **验证：** 真实restore接受missing conversations、object conversations、数组内missing messages；独立探针显示首条可保存后第二条失败。合成crypto仅用于结构验证；真实文件/RDB未操作。
- **最小修复/回归：** 先校验完整payload并分配恢复计划，再事务或暂存原子切换；失败不落任何覆盖或明确部分恢复明细。加入第二条失败/重复ID/settings失败回归。详见[review-e E02](review-e.md)。与R02的信息丢弃是不同根因。

### R04 数据库失败静默转内存，持久化恢复前退出会丢降级期改动
- **置信度：高。影响/前提：** getRdbStore/迁移失败期间仍允许正常新增/编辑；若数据库恢复回灌前进程退出，这些改动丢失，用户没有可见警告。
- **证据/链：** `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets:149-197`失败设置chatStorageDegraded并return null → `283-300`memory.save；全仓该标志只有178/182两处写，无UI消费者；AppContainer getChatRepository为单例。
- **保护/边界：** hilog不向用户展示。下次操作会重试初始化，成功时171-176把memory迁回RDB，故**不称所有原数据库内容被删除，也不是每次失败必丢**。
- **验证：** 三方独立静态调用链及全仓标志消费者核验；没有制造真实设备数据库故障。
- **最小修复/回归：** 降级状态必须可见，提供安全持久化/恢复或拒绝不可持久写入；故障注入测试失败→新增→重启、恢复后回灌。详见[E04勘误](topic-e-storage.md)、[独立复核](review-e.md)。AG3同根合并。

### R05 普通Provider错误文本含“abort”会被误当取消，并用旧会话覆盖新用户消息
- **置信度：高（条件确定）。影响/前提：** Provider返回非AbortError但message含小写abort/aborted，例如上游中断的错误正文；本次user已落库却随错误被回退，UI不显示真实错误。
- **证据/链：** `harmony/chat/src/main/ets/chat/chat_kernel_host.ts:148-166`子串判取消并返回入参conversation → `harmony/entry/src/main/ets/pages/ChatPage.ets:3295-3306,3991-3997`取得旧会话 → `1157-1184`最终save；user先保存于chat_turn:187-188/tool_loop send链。
- **保护为何无效：** generation retry按name识别，kernel额外按message识别；page将result=null的旧conversation当合法最终结果。checkpoint排序正确也挡不住内容本身回退。
- **验证：** `probes-b/probe_b3_chain.mjs`真实kernel+runChatTurnWithTools：engine save nodes=2，kernel返回nodes=1，按页面最终save后只剩原消息；普通500对照走throw且user保留。未声称已从真实服务捕获该正文。
- **最小修复/回归：** 统一结构化取消判据；取消/异常也不能返回早于已持久化user的快照。加入非AbortError含abort及AbortError但signal未abort的回归。终审真实Stop（signal确实aborted）对照保留user；原probe第四例标签错误已更正，不能据此声称普通Stop丢消息。详见[review-b B3](review-b.md)。

### R06 HTTP 200非SSE响应被静默当成功，核心发送流程无回复也不报错
- **置信度：高。影响/前提：** 兼容网关200返回JSON错误，或customBody.stream与调用模式冲突让服务器返回普通JSON；发送结束只留user，无错误/重试。
- **证据/链：** `harmony/chat/src/main/ets/chat/openai_chat_api.ts:124-151`、`claude_chat_api.ts:93-120`、`google_chat_api.ts:136-160`只检查status；`harmony/deepread/src/main/ets/platform/sse_assembler.ts:131-162`忽略无data前缀正文；chat_turn普通发送没有空输出失败守卫。
- **保护为何无效：** RcpHttpClient已回传headers但Provider未消费Content-Type；非2xx错误解析对200不生效。regenerate的sawChunk只避免空分支，不修普通发送。
- **验证：** 真实OpenAI api→runChatTurn+fake 200 application/json，threw=null/nodes=1/assistantNodes=0；三Provider对照一致，SSE包装错误正常抛出。
- **最小修复/回归：** 校验stream协议/非SSE错误正文及零有效事件；请求模式字段与解析器必须一致。`openai_request.ts:396,465`/`claude_request.ts:234,293`customBody可覆盖stream，作为同一协议闭环修复，不另计C04。详见[review-c C01/C04](review-c.md)。

### R07 WebMount首次打开永远等不到宿主就绪
- **置信度：高。影响/前提：** 用户启用WebMount并调用wm_open，约5秒后host not mounted，无法打开授权站点。
- **证据/链：** `WebMountSession.ets:35,109-111,139-153`hostMounted初值false，仅setter无任何调用；`ChatPage.ets:875-877,4456`hook只显示host；`WebMountSession.ets:224-237`组件只attachUiContext/markPageEnd，不置mounted。
- **验证：** 真实Session class转译、假时钟50tick，hook1次/loadUrl0次/按预期reject；全仓setter调用搜索及A/G、D/F两次独立复核。未设备实跑5秒。
- **保护为何无效：** Web组件存在/初始pageEnd与hostMounted是不同状态，前者不会修改后者。
- **最小修复/回归：** controller实际attached时通知，并在销毁时对称复位/解除hooks；首次打开、退出重开、超时和多实例用例。**必须与L01/L02一起修，不能只解锁挂载后暴露潜伏安全面。** 详见[F03](topic-f-security.md)、[review-df](review-df.md)。

### R10 读失败或损坏被当空库，随后成功写入覆盖原数据
- **置信度：高。** 瞬时readText错误后再添加记忆/附件追踪，或收藏JSON损坏后新增收藏，旧记录整体丢失。
- **证据：** `MemoryStore.ets:39-63`、`ManagedFileStore.ets:44-71`的IO catch返回空；`FavoriteStore.ets:27-45,66-72`解析失败返回[]再写；AtomicJsonFile只能防撕裂，不防错误基准覆盖。
- **验证：** `probes-e/probe-e-io.cjs`加载真实.ets/managed_files.ts并只mock IO/KV：一次读异常后addMemory只剩gamma、insert只剩upload/c.txt；损坏收藏后只剩new-1；调用仍成功。kv.get自身失败不被Favorite decode吞掉，边界已明确。
- **修复/回归：** 不存在/读失败/解析损坏三态分开，失败拒绝覆写并保留原值；注入一次IO失败断言无写。E05/E07/H8合并，同历史“错误当空库”残余根因；详见[E勘误](topic-e-storage.md)。

## P2 — 条件性功能、数据与执行缺陷

### R08 缺失工具ID时，审批与结果回填不再是一一对应
- **置信度：高，前提受限。** 非合规兼容Provider全程不给多个toolCallId；不是正常Claude delta空ID（初始tool_use的ID会被merge保留），Google也有生成ID。
- **证据/链：** `tool_loop.ts:119-143,333-359`把resume真子集按双blank对齐到全集；`tool_approval.ts:86-111`按空ID匹配全部part。单个批准可批准全部blank工具，ask_user自由文本答案可覆盖另一个工具part。
- **验证：** 真实blocker→continuation→dispatcher得到[file_list:auto,ask_user:answered]后最终两part都变ask_user；构造auto/approved态可重复file_write，但该特定态不直接从UI单批批准产生，**不泛称正常Provider必重复执行**。详见[review-b B1](review-b.md)。
- **最小修复/回归：** 在输入边界拒绝或生成稳定身份，全链按part身份/index+turn定位，不按blank等价；测试ID后到补全/永缺/单项批准/续跑子集。原P1降P2；错配与审批别名同一身份根因合并。

### R09 删除墓碑同时挡住合法的备份恢复
- **置信度：高。** 同进程删除会话后恢复含该ID的备份，save抛Conversation was deleted，恢复中断。
- **证据：** `ChatRdbRepository.ets:481-512`成功delete后永久保留deletedIds；`283-300`save一律拒绝；`SettingBackupPage.ets:233`恢复没有显式recreate路径。
- **验证：** 写入/删除/恢复入口完整静态证明，独立复核；没有真实设备删数据。删除不存在ID也会留墓碑。
- **修复/回归：** 区分迟到旧写与授权重建，用代际/显式restore操作而非简单清除所有墓碑；删除→恢复成功、删除→旧流迟写仍拒绝。详见[review-e E03](review-e.md)。与R03中途失败的表现相关，根因独立。

### R11 会话删除清理不统一，留下收藏、待发队列、附件与压缩摘要
- **置信度：高。** 从ChatPage抽屉删除时只删RDB；其它入口虽调用cleanup也没覆盖compact KV。影响残留隐私文本、孤儿数据及失效收藏链接，不宣称外部泄露。
- **证据：** `ChatPage.ets:2727-2752`无cleanup；`ManagedFileStore.ets:208-227`清理清单未含`EntryCompactStore.ets:53-59`deleteByConversation；ChatListPage:248-268是已接清理的对照。
- **验证：** 真实入口/仓储职责/消费者静态追踪，E独立复核；未删真实附件。
- **修复/回归：** 统一删除编排并涵盖全部会话所属存储，阻止在途摘要复活；四种入口同会话清理一致、共享附件仍保留。E06/E09合并。

### R12 定时运行与立即运行可重叠执行同一任务
- **置信度：高。** 已启动fire期间再runNow会并发模型调用，runCount可丢更新，同conversation结果后写覆盖。
- **证据：** `AgentCronStore.ets:186-217`只清等待timer，没有inFlight锁；`agent_cron.ts:456-495,505-520,583-593`无原子running claim。
- **验证：** 真实manager+entry scheduler受控runner重入两次，count不一致；单timer对照安全。历史以“执行器不可用”排除的前提现已失效。
- **修复/回归：** 按任务原子claim、明确定时/手动冲突策略；单run、删除、重排配套测试。当前executor无工具，**仅确认重复模型/存储，不宣称已重复真实外部工具写**。详见[review-df D02](review-df.md)。

### R13 定时Agent实际没有工具循环，却按任务成功完成
- **置信度：高。** 需要搜索/读取文件等工具的定时任务只会做一轮文本生成，不能实现要求的Agent工作。
- **证据：** `AppContainer.ets:441-491`构造ChatTurnDeps不传tools/loop，482只调runChatTurn，随后markCompleted；创建工具与设置入口真实可达，未提示“仅文本”。
- **验证：** 完整真实装配链静态证明；未调用真实模型。域`runCronTaskOnce`导出不等于entry使用。
- **修复/回归：** 显式定义能力范围，接工具循环前先完成R12/R17取消和审批语义；mock断言工具请求与结果持久化，不能盲目无人值守放行。详见[review-df D03](review-df.md)。

### R14 子代理等待审批后停在不可恢复、不可取消的任务状态
- **置信度：高。** 子代理遇需要审批工具返回approval_required，任务板仍RUNNING但无续跑入口，cancel无效。
- **证据：** `subagent_runner.ts:376-386` → `subagent_manager.ts:207-214,445-454,593-608,735-762`，finish只接受running，cancelCapability=false。
- **验证：** 真实runner→manager闭环probe，wait/cancel均不改变状态。**admission只计running，approval_required不占并发额度**；已剔除原误报子结论。
- **修复/回归：** 明确WAITING_APPROVAL可恢复态或有错误说明的终态；至少可cancel。审批required→取消/拒绝/恢复均终结。详见[review-df D04](review-df.md)。双端同构不作为排除理由。

### R15 “继续写首诗”等自然聊天文本会批准全部待授权工具
- **置信度：高。** 已有pending工具时用户发以“继续”开头的纯文本，即使自动批准全关、工具mandatory，仍变approved；UI只展示✓/✕授权按钮，没有说明该句会批准。
- **证据/链：** `ChatPage.ets:3952-3977` → `tool_approval.ts:31-39,168,185-188`前缀匹配且改全部pending → `tool_permission.ts:182-184`优先接受approved。`AgentToolCapsule.ets:141-162`为显式审批UI。
- **验证：** 真实blocker/permission探针：pending wm_eval+“继续写首诗”→allow；auto态对照仍ask；无关句/图文对照不批准。无实际执行脚本或外部工具。
- **修复/回归：** 强制审批仅通过明确工具/参数绑定的用户授权，至少删宽前缀且风险工具不能靠模糊续写批准；测试全部开关关闭、多个pending、否定/继续写作句。P2保留主动用户发话的前提，不误称网页单独能批准。详见[独立补审D11](review-d-supplement.md)。

### R16 超过24.855天的定时延迟被截短并提前真正执行
- **置信度：高。** enabled任务下次执行>2^31−1ms且进程连续存活至少约24.855天；短存活不声称触发。
- **证据：** `AgentCronStore.ets:207-213`直接clamp后fire；`agent_cron.ts:340-372,456-495,601`允许366天内任务但fire前不复核due。启动rescheduleAll不能修正在存活进程内的截断。
- **验证：** 真实manager+转译entry scheduler+受控Date/timer13断言，年度任务第24.855天executor被调用且记Succeeded，后续还能重复提前排期；近任务对照正常。
- **修复/回归：** 长delay分段只重排不执行，每次fire核验due；测试一年后任务第一次timer不执行、近任务仍执行。与R12锁不同根因。详见[长延迟独立报告](review-cron-longdelay.md)。

### R17 已启动的定时任务没有停止通道，删除任务也不取消当前生成
- **置信度：高。** 在executor捕获task之后删除，模型仍跑完并写该conversation；删除前尚未启动则安全，不会复活调度。
- **证据：** `agent_cron.ts:368,434-443,743`无cancel callback且capability=false；`AgentCronStore.ets:145,186-221`executor无signal，cancel只clearTimer；`AppContainer.ets:441-483`捕获task后照常save。
- **验证：** 真实manager/entry scheduler受控runner：delete=true，run未打断、释放gate后完成；不重排/不复活为反例。真实模型未调用。
- **修复/回归：** run级AbortController贯穿Provider及将来工具；区别“停后续”与“取消当前”并提供入口，删除按产品语义明确处置。详见[补审S5](review-d-supplement.md)。与R12独立。

### R18 协议终止/错误帧不能立即收口底层流
- **置信度：高（机制），真实等待时长未验证。** 服务端发DONE/completed/error后不关闭响应，UI仍生成，错误也迟迟不显。
- **证据：** `openai_chat_api.ts:128,134-150`、`claude_chat_api.ts:97-113`、`google_chat_api.ts:140-152`、`openai_responses_api.ts:140-181`OpenAI/Claude/Responses仅置done/error并从回调return；Google错误分支同样只缓存error且没有done标志/专门终止处理。HTTP Port无协议cancel。
- **验证：** fake流终止后保持pending，60ms未settle；真实SDK有readTimeout=120000及用户Stop，**不宣称无限挂死或确切120秒上限语义**。心跳是否延长等待待设备。
- **修复/回归：** Port显式cancel/complete，协议终止触发清理并及时resolve/reject；保留用户abort和timeout一致错误。详见[review-c C02](review-c.md)。

### R19 再生/审批续跑预览重复执行非幂等视觉正则
- **置信度：高。** 用户配置非幂等assistant正则且走tool-enabled再生/续跑时，预览与发送路径不一致；不损坏落库原文。
- **证据：** `tool_loop.ts:145-150`emitSnapshot先visual transform → `ChatPage.ets:1743-1752,3441-3450`再次transform；普通发送3929-3941不重复。
- **验证：** 真实runRegenerateAtWithTools+regex，正确say *foo*被展示层再变say **foo**，存库仍say foo；默认无正则不触发。
- **修复/回归：** raw与visual回调契约唯一化，onUpdate只变换一次；对非幂等规则测send/regenerate/resume一致。详见[review-b B5](review-b.md)。

### R20 深读取消未贯穿预抓和规划，取消后仍工作并写运行中状态
- **置信度：高。** 在prefetch/article-plan窗口取消，底层无signal仍继续，到writer才抛abort，并已写COLLECTING/WRITING+RUNNING。
- **证据：** `source_prefetcher.ts:105-112`collect无signal；`run_manager.ts:91-97,127,208-210,231-237`；`scheduler.ts:138-161`仅finally。文件位于`harmony/deepread/src/main/ets/research/`及`agent/`。
- **验证：** 真实scheduler/run chain受控prefetch，saves=[COLLECTING,WRITING:RUNNING]后reject。页面已有catch和cancelled展示，**不报未处理rejection或永久假完成**。
- **修复/回归：** signal贯穿collect/plan，持久化前重查，统一aborted RunResult与阶段终态；取消发生在每个await窗口的测试。详见[review-xag H2](review-xag.md)。

### R22 深读通知共用ID，完成发布与finally取消相互竞争
- **置信度：中高（代码竞态），设备表现未测。** 完成通知可能刚发布就被撤回；REPLACE旧run可能撤掉新run通知。
- **证据：** `scheduler.ts:150-159`完成后无条件cancelRunning；`NotificationNotifier.ets:118-122,149-169`running/completed/cancel共用id，notifyCompleted不await/return publish，cancel也void。
- **验证：** 真实scheduler probe证明调用顺序；`probes-xag/probe-h10-real.mjs`转译真实Notifier并mock平台deferred，publish先落地则无通知，cancel先落地则完成通知保留。**原手写转写probe仅说明，不作证据；不说每次正常必消失**。
- **修复/回归：** await真实publish，成功后不撤同一完成通知，旧run cancel受token约束或独立ID；测试两种完成顺序及REPLACE。详见[review-xag H10补遗](review-xag.md)。

### R23 在非首页页面接收Want时，目标跳转滞留到返回首页
- **置信度：高（静态）。** 当前栈顶ChatPage/设置页时点击通知/收到参数Want，不立即进入目标；未消费参数可被后续Want覆盖。
- **证据：** `EntryAbility.ets:84-116`onNewWant只写AppStorage；唯一消费者`ChatListPage.ets:68,81,85-111`show/appear；其它页无消费者。
- **验证：** 入口→存储→消费路径独立复核；冷启动首页消费正常是反例。无设备通知点击证据，称“滞留”而非所有场景永久丢失。
- **修复/回归：** 统一前台路由派发，稳定参数校验/单次消费；测试冷启动、首页、会话页、设置页连续Want。详见[review-xag AG2](review-xag.md)。

## P3 — 低影响、可证实缺陷

### R24 规划失败的深读降级路径给后段分配零证据
- **置信度：高。前提：** 仅article-plan解析/生成失败走fallbackPlan，来源≤6/12；正常normalizePlan从全局池补满，不受此问题影响。
- **证据/验证：** `harmony/deepread/src/main/ets/research/article_plan.ts:31-34,259-263`fallback直接用连续bucket；`evidence_pack.ts:109-115,124-156`；真实probe比较fallback与normalize，n=12仅fallback后两段0，normal每段6。
- **修复/回归：** 降级计划同样用全局卡池补满；测试规划失败而不是只测分桶。详见[review-xag H1](review-xag.md)。原P1“常态无证据”已排除。

### R25 清理工具未限定终态，可删正在运行的任务看板记录和取消回调
- **置信度：高。前提：** 模型调用并获授权cleanup运行中task；工具描述仅承诺清理完成/失败/中断/取消任务。
- **证据/验证：** `agent_task_tools.ts:219`描述 vs `agent_task.ts:755-770`无status门，直接tasks.delete/cancelCallbacks.delete；真实store探针核验。根因不是允许删除private output的授权档位。
- **影响/边界：** 任务仍跑却从统一看板消失，该看板取消通道被删；**子代理manager自身cancel可能仍可用，不称全系统永不可取消**。
- **修复/回归：** 非终态拒绝cleanup，或先显式取消再清；测running/retrying及四种终态。详见[补审S4](review-d-supplement.md)。

### R26 主题模式保存失败没有收口，显示与重启后的模式可能不一致
- **置信度：高（失败路径）。** KV写入拒绝时，页面已换色，put没有await/catch，不能告知或回滚。
- **证据/验证：** `harmony/entry/src/main/ets/pages/SettingAppearancePage.ets:37-45`，与47-56 accent保存处理作对照；静态证明，未制造真实存储错误，也不据此断言崩溃。
- **修复/回归：** await落盘并处理错误、回滚或明确未保存；注入reject断言错误提示且无unhandled rejection。详见[AG8](topic-ag-platform-ui.md)。与主题响应式待验项分开。

### R27 编辑重发的前置保存晚到后可在销毁页启动新生成
- **置信度：中高（代码链），设备时序未测。** runSendEdit异步invalidate/save期间退出，完成后仍regenerate；此时原退出abort还没有对应的新controller可取消。
- **证据：** `ChatPage.ets:2301-2333`缺operationToken/pageAlive门；再生1685之后新建controller。下游`isGenerationOperationActive`过滤UI及其它会话，所以**未确认跨会话串写/数据损坏**。
- **验证：** 入口/await/离页/下游token保护静态交叉追踪；无页面实例运行probe，影响限定为无意后台生成/资源耗用。
- **修复/回归：** 前置异步链也捕获token，离页后不启动新生成；受控保存延迟+立刻返回验证Provider未被调用。详见[review-b B4](review-b.md)。

## 单独列示：P1级潜伏安全缺陷（不混入上述26项）

### L01 wm_click目标字符串可执行任意JS，关闭wm_eval也无法阻止
- **置信度：高（真实工具逻辑）；端到端利用未验证。** `WebMountTools.ets:120-143`target虽在querySelector中JSON编码，135行错误字符串又未经转义插入；未命中CSS目标时可执行额外表达式。
- **授权链：** click只needsApproval，默认allowsAutoApproval=true/mandatory=false；`tool_policy.ts:473-479`及`tool_permission.ts:256-258`单开自动批准可过。`wm_eval:196-206`强制门与includeEval=false不约束click。run-trust无生产写入，不作为绕过。
- **验证：** 真实WebMountTools转译execute、VM仅改合成window标记；includeEval=false正例及转义负例；独立19断言复核。没有访问cookie、网页或外网。
- **阻塞/修复：** 当前R07导致首次导航失败，R01导致本快照不可构建，因此不称真实用户已可被外泄；挂载修复前必须修所有字符串编码并统一显式审批。回归含单双引号、反斜杠、换行及关闭eval。详见[F01](topic-f-security.md)、[review-df](review-df.md)。D01并入，不重复统计。

### L02 WebMount白名单误认userinfo为主机，并接受非HTTP scheme
- **置信度：高（真实gate）；端到端利用未验证。** `WebMountSites.ets:73-88`用authority.split(':')[0]取host；带冒号userinfo可让授权域成为返回host，而实际主机是outside.invalid。无冒号userinfo的负例会拒绝，攻击前提已收窄。
- **证据/验证：** 真siteAllowsUrl合成URL→true，标准URL.hostname→未授权主机；javascript/file scheme也过文本gate。`WebMountTools.ets:83-92`未经标准URL规范化直传open，无每导航检查。
- **影响/限制：** 站点授权边界在逻辑上可绕；受R07/R01遮蔽，没有实际WebView导航或数据读取证明。具体scheme能否被ArkWeb加载也未实测，不当作已执行路径。
- **最小修复/回归：** 标准URL解析、http(s)限定、userinfo拒绝、实际导航/重定向/revoke复查；覆盖IPv6/端口/编码/子域/不同scheme。详见[F02](topic-f-security.md)、[review-df](review-df.md)。与L01代码注入不同根因。
