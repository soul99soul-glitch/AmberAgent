# E7 source store / topic coordinator / derived documents

2026-09-28。本 owner 仅改 MemoryStore、MemoryTopicOps、MemoryDocumentStore、AgentRuntimePrefs 与 MemoryDreamOps，以及唯一必要 `memory_topic_store.test.cjs`。Root 许可更新 packet 的可选 beforeCommit/write-choice 说明。未修改 domain/UI/DI/routes/EntryAbility；未运行 full suite、build/sign、设备或真实网络模型。

## 实际接口和写入边界

- `getMemoryTopicStoreOps()` 按 packet 提供全 normalized guarded snapshot、applyTopics、专用 deleteTopic、sync/list/readDocuments。Snapshot返回只读当前可用性view：保留全部source records，按当前时间prune成员、失效topic展示归档，不写JSON/docs；实际归档持久仍save/refresh队列。普通源写仍在原 writeQueue/guarded JSON 上；旧 JSON 经 makeMemoryRecord 补 null/[]，更新 pin 的显式 copy 保留 topic 字段。Topic 删除只删该 record，普通 CRUD 不能编辑/创建/删除 topic。
- Source CRUD 成功后 prune/同步 `filesDir/memory/documents/index.md` 与受控 `topics/<id>-<slug>.md`。Domain 的实质源内容/分类/归档/到期/删除变更会 archive 依赖主题；prune 按成员实际 availability，不因 pin/touch metadata失效。丢失成员即使仍≥2也 archive，以免旧摘要冒称新鲜；同标题重新模型聚合可按稳定ID复活。
- 实际生成正文相同就不改文件/mtime；只清除受控生成路径。空有效源投影为[]。List/read 先 guarded 源，再按当前投影刷新；读取失败绝不降空删文档；任意上跳/绝对路径不受理。
- Source JSON 先 atomic 成功，再同步文档；后者失败抛 `MemoryDocumentRefreshError`，真实保留 sourceSaved/documentError/topicResult，source 不回滚。幂等未写源而文档刷新失败则 sourceSaved=false。显式 sync 重试只刷新派生状态。辅助 recall touch 保留原 raw writeAll，不耦合文档IO，已保存 touch 不会因doc失败阻断Chat请求。
- `memoryRevision` 在源成功提交后发布（文档失败也发布），显式sync成功发布。读取/无变化投影不发布revision，避免UI观察刷新循环。

## 协调器和模型配置

`getMemoryTopicCoordinator()` 有 state/即时 subscribe/run(manual)/cancel/foreground/background。State = Result + running；Result 包含 status、error、sourceSaved、documentError、topicCount、staleCount。Status固定 idle/running/completed/skipped/missing_model/cancelled/timed_out/failed；skip文案说明实际门，missing_model不伪造生成结果。

每轮读取当前 runtime/task/model：复用旧 daydream→worker→compress→chat 的实际解析函数 `resolveMemoryDaydreamChoice`，实际 effective choice.provider、model customHeaders/customBodies 与 reasoningLevel 均传给已有 provider API。没有新模型槽或覆盖真实 provider override 的新解析。

Topic 独立于 worker.enabled/extractionEnabled 与 recall 开关：dreamModelEnabled 控制模型聚合；root接受的 topicWriteEnabled 默认false，明确许可派生写入。旧破坏性 DreamPlan 的门/审批保持原行为，仅复用其模型选择函数。Timeout默认120000ms（读取既有timeout字段，上限120000），使用真实 EntryAbortController signal；本轮取消/后台/超时关闭提交门，迟到返回不写，不声称硬杀网络。

手动只跳 idle/charging/每日预算，保留 feature/write/前台/signal/timeout。自动只由实际foreground触发，检查 chatGenerating idle、实际 charging 与现有runOnly*设置，以及独立KV日预算（本地日界，消费模型尝试一次）；没有background work、定时重试或新scheduler，不计入Chat aggregate。Lifecycle方法同步轻，自动run Promise自行结算；root实际EntryAbility在onForeground先恢复appBackgrounded后调用，在onBackground取消。

Root许可 applyTopics 可选四参beforeCommit：现有queue内 guarded live读取后，重读 feature/write/signal/background，使用不早于调用时间的实际commit时钟判断expiry，再真正写源。取消或开关变更发生在queued等待期间不能迟到落topic/docs。

新增充电API已查本地官方文档：`API参考/基础功能/Basic_Services_Kit_基础服务/ArkTS_API/设备管理/ohos_batteryInfo_电量信息_/js-apis-battery-info`（`harmony/scripts/devecocli.sh docs read`）。@kit.BasicServicesKit batteryInfo.chargingStatus/BatteryChargeState.ENABLE/FULL 是首批API6成员，API12可用；异常返回未充电，不声称已满足环境。

## 唯一必要 controlled group

`harmony/chat/src/test/memory_topic_store.test.cjs` 初次142行、最终160行/1个test。直接transpile当前 Entry store/coordinator/prefs/daydream/abort/atomic模块，真实chat domain与memory KV；SDK fileIo thinforward Node真实临时私有目录，atomic写/rename/guard实际执行。只受控 AppStorage/charging/DI provider choice/model返回与注入写/read错误，不复制业务引擎。

运行：`node node_modules/tsx/dist/cli.mjs --test src/test/memory_topic_store.test.cjs`。`/tmp/e7-platform-focused.log` **1 PASS / 0 FAIL**，247ms/总475ms。必要结论：

- 旧JSON缺topic字段正常水合；提炼两个开关false仍可按独立许可聚合，真实源JSON新增topic，Markdown含源中文正文。
- 同分组重跑稳定ID、sourceSaved=false、真实文件mtime不变；当前模型候选链/headers/customBody/reasoning/signal实际穿过coordinator provider调用。
- 源编辑后derived write失败，源正文已真实保存、topic归档，回执sourceSaved=true；touch在同doc失败条件仍成功。guarded源读取故障时保留旧派生文件字节；显式sync恢复后删除失效topic文件，索引显示实际新正文。
- 自动不充电/Chat运行时无模型调用；真实环境满足后一次自动调用，日预算阻止第二次。Topic不改chatGenerating。
- 模型已返回、apply实际入队，前面的真实source touch atomic open barrier仍阻塞：cancel后释放队列，最终cancelled且零topic。20ms受控timeout使用真实signal，迟到provider返回不写。
- background后manual确实拒写；foreground自动run按预算结算、当前state订阅确认后，manual可再次实际成功。

首次夹具错误仅为Entry目录相对层级写错；下一次末尾foreground自动run尚未结算便manual，返回正确busy/skipped，改为实际subscribe终态等待后通过。两者均不计产品缺陷，无源码为迁就fixture而改行为。

## 静态和实际编译回喂时点

五个owned ETS 初次窄 lint：`/tmp/e7-platform-lint.log`，No defects found。工具固定日志目录不在sandbox写范围，首次EPERM后用已授权必要lint升级权限重试；无产品权限/签名变更。

Root首次actual app compile回喂3条：Store对象literal接口与AbortSignalLike可选listener。Optional listener调用窄修后实际第二compile两条消失；Store仍一条，最终按现有MemoryLibraryOps将六method signatures改明确function-property signatures，runtime协议不变。Typed local保留。提交时钟修正后只重检Store，接口类型最后修正后仍只Store最终lint；旧passedfiles不重复。最终actual app compile与UI/设备gate由root执行，本报告不借Node fixture或TS transpile声称ArkTS编译成功。

初次fixture绿是在最后具名factory/可选listener/function-property编译类型收口与guard后commit时钟修正之前；其后的具体expiry P2由下节必要原组红绿再次覆盖所有当前源，不扩大矩阵。独立源码review已认可原writeQueue/guarded/source-doc/真实模型门调用链；产品UI/真实模型网络/设备仍未在本owner范围执行。

首次冻结回执（由下节具体P2新回执替代）：Store单文件lint `No defects found`；SHA256 `49e886f19a532f978f8519a7b6d19077ead5ff4d98bf056dffe5c378a49617c7`。TopicOps SHA256 `57356a62282dac37e530a302cfbefe89c980a772399fe9147a4c5f1fd6483482`；fixture SHA256 `70684baf129d15e55355011689fab7694da6cf163f09721377411c65158910f5`。

## 最终复查两处真实 expiry P2：仅原组必要红绿

Root此前实际compile/sign PASS（11.259s）针对上节旧冻结源；独立review随后发现以下明确问题，因此旧artifact不当最终交付，root需在本节修复后再最终compile/sign。本 owner未自行构建/设备操作。

1. apply使用实际commit时间，而changed=false文档分支仍old enqueue now。原group末尾追加实际source JSON两short_term记录，expiresAt介于oldNow与Date.now，无topic。`/tmp/e7-commit-clock-red.log` 0/1：domain已staleCount=1/changed=false正确拒绝，但直接raw filesystem index仍存在。未调用list/readDocs二次sync掩盖错误。Store518–521改constcommitNow同传apply/syncProjection后，`/tmp/e7-commit-clock-green.log` 1/1，239ms/455ms，raw index真正不存在。
2. topic列表单独打开时snapshot旧源topic仍archived=false，已到期成员继续显示fresh摘要。Root明确许可snapshot只读pure prune当前可用性view。原group末尾再追加最小seed两expired sources+先前实际topic，先验证rawJSON逐字不变/source两条仍保留，再期待archived=true/memberIds=[]。`/tmp/e7-snapshot-expiry-red.log` 0/1，真实archived仍false。Store509–512在原guarded enqueue内return prune(...,Date.now()).records，不写源/docs；最终 `/tmp/e7-platform-final-green.log` **1 PASS / 0 FAIL**，234ms/447ms。当前原组覆盖最后所有runtime/编译类型调整及两到期边界，仅同一test，无新suite/矩阵。

最终只对受影响Store跑单文件lint（旧passed模块不重复），日志 `/tmp/e7-platform-store-final-lint.log`。Packet已明确snapshot只读可用性view、归档持久原队列，以及apply/unchanged docs同commit clock；UI DTO/公开方法签名未变。

两P2最终冻结：Store单文件lint `No defects found`；Store SHA256 `6f1b1c4984bf18130178f7ecd2a619b4508a46ddca128a398ad6f75f86c8ad49`，160行fixture SHA256 `e4c8b1409148d1fbbddc207f191b33debcdefaf326aab90e0fe1507e91d3f491`。Root最终compile/sign/设备gate仍单独记录。
