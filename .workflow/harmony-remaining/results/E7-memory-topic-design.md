# E7 记忆主题与派生文档：窄预查及接口建议

2026-09-28，只读代码调查；仅写本报告，未测试、构建、设备操作或外网检索，不改任何产品文件。范围来自 `docs/plans/2026-09-28-harmony-remaining-execution.md:40`。

**最小真闭环：已保存且有效的源记忆 → daydream模型真实JSON分组 → 自动维护topic记录 → index.md/topics文档 → 主题成员/来源与只读Markdown查看 → 编辑/删除源记录或再次聚合后刷新；源记忆内容和身份保持。**

路径缩写 I=`/Users/arquiel/Downloads/AI/amberagent-ios/`；H=`/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/`。

## 1. 已核对的真实实现与缺口

| 真实文件/位置 | 行为及E7含义 |
| --- | --- |
| I`iosApp/iosApp/IOSMemoryConsolidationCoordinator.swift:152/163/181/194` | 聚类实际是一次LLM JSON分组；成员仅未归档、未过期、非topic、非core，至少2条。结果返回后重查feature/write门与live IDs，再写topic，不应用模型merge/archive建议。 |
| 同文件`:395/404/415/437/476` | 返回 `{topics:[{title,summary,memberIds}]}`；prompt输入含id/120字content/scope/kind及已有title/memberIds；短标题≤12字、一句summary、每成员最多一组、组至少2条为prompt约束。固定模型调用无tools/maxTokens，按worker.timeoutMs超时。 |
| I`core/memory/api/.../IosMemoryFactory.kt:154/163/202` | topicTitle忽略空白/大小写匹配；同标题summary更新且成员union/distinct、恢复归档topic；完全无变化不写；新topic是long_term/__long_term__，sourceConversationId=null。 |
| I`core/memory/api/.../MemoryModels.kt:95`、`core/types/.../MemoryEnums.kt:78` | topicTitle/memberIds是record字段，kind='topic'；自动维护；常规提炼/memory_tool/编辑不得生成或覆盖topic。 |
| I`iosApp/iosApp/IOSMemoryMarkdownStore.swift:46/142/165/193/202/234` | 源记录仍是真相；index.md+topics/<id>-<slug>.md，存储投影变化才sync，实际内容相同不改mtime，清除失效主题文件，索引成员覆盖数按真实live IDs计。 |
| I`iosApp/iosApp/IOSMemoryPersistence.swift:884/919/935/962` | 成功load/persist后sync；文档错误不回滚已成功源写入；revision驱动UI，渲染前去generated注释。 |
| I`iosApp/iosApp/MemoryDocumentView.swift:4/64/103` | 文档只读、onAppear及revision变化reload；文件消失有明确状态。 |
| I`iosApp/iosApp/MemoryEditView.swift:128/148/170/299/347/423` | 主题成员详情共用解析结果；主题禁正文/归类/置顶编辑，保存再次拒绝；普通源记录可编辑，显式用户删除仍有确认和过期快照检查。 |
| H`chat/.../memory_models.ts:21/75` | 尚无topic kind/topicTitle/memberIds，旧kind parser未知值回note；加字段必须处理旧JSON默认值及每条显式copy，不能被upsert丢掉。 |
| H`chat/.../memory_dream.ts:44/79/352` | 旧DreamPlan是merge/promote/archive/supersede/ignore的建议审批；没有topic操作，不能沿旧planHasChanges判断主题成果。 |
| H`entry/.../MemoryStore.ets:54/102/111/139/400/424` | 读列表失败降空不写回，写基准guard拒覆盖；writeQueue可复用；DreamStore与LibraryOps已有全量/CRUD但无主题批量更新/派生文档。 |
| H`entry/.../pages/MemoryPage.ets:45/98/116` | 当前页只看__global__，不能显示long_term topic；只有源编辑dialog，无文档列表/详情/成员来源。 |
| H`chat/.../memory_extractor.ts:290` | 已保存提炼前的web/MCP污染guard；E7应吃已通过此路径的records，不新增从聊天/网页再提炼的入口。 |

## 2. 最小数据与调用合同（建议冻结）

```ts
type MemoryKind = ExistingMemoryKind | 'topic';
// Record新字段必有默认：旧JSON为null/[]；Maker、persist/copy/upsert/import保持。
interface TopicFields { topicTitle: string|null; memberIds: number[]; }
interface MemoryTopicSuggestion { title: string; summary: string; memberIds: number[]; }
interface MemoryDerivedDocument { relativePath: string; title: string;
  preview: string; sizeBytes: number; modifiedAt: number; topicId: number|null; }
interface MemoryTopicStorePort {
  snapshotForTopics(): Promise<MemoryRecord[]>; // guarded源，不用降空读触发删除文档
  applyTopics(suggestions: MemoryTopicSuggestion[], baseline: MemoryRecord[], now: number): Promise<number[]>;
  syncDocuments(): Promise<void>;
  listDocuments(): Promise<MemoryDerivedDocument[]>;
  readDocument(relativePath: string): Promise<string|null>;
}
interface MemoryTopicRunDeps {
  store: MemoryTopicStorePort;
  readGate(): Promise<{ modelEnabled: boolean; writable: boolean }>;
  generateText(prompt: string, signal?: AbortSignalLike): Promise<string>;
}
```

- Pure domain只需buildTopicPrompt/decodeTopicSuggestions/applyTopicSuggestions/renderMemoryDocuments；不引embedding数据库、向量算法、第二记忆仓或通用workflow。
- applyTopics在现有writeQueue内重读源：删除/归档/过期/core/topic剔除；只接真实32-bit整数成员，去重后≥2；title/summary非空，title按12字prompt约定校验。同标题幂等upsert，不改源content/来源/ID，不自动merge/delete源。
- iOS“每成员一主题”仅prompt保障，upsert的旧成员union可能跨主题；建议结果validator拒绝一轮重复成员/重复归一标题，并核已有成员归属，不能默默登记满足。源内容在模型等待时改动，命中该suggestion的baseline id/content/updatedAt变动时返回stale，不把旧summary当新结果保存。
- 删除/归档源时本地prune成员；live成员<2则归档topic；文档随新状态删除。新模型分组可按同归一标题复活。常规memory_write/create/edit拒topic，topic手动详情禁保存；用户显式删除topic需独立确认，不连带删成员。
- 数据格式升级必须同步MemoryRecord的Maker、memory_write.upsert、MemoryStore.updatePinned及MemoryPage的显式copy；主JSON持久化/恢复保留两字段。旧Dream maintenance/planner/applier目前非core即managed（memory_dream.ts:414），需明确排除topic，防旧merge/archive再次处理派生记录。旧frontmatter六kind导出只导源并按实际源计数，主题由源重建；普通导入拒topic/derived文件，不fallback成note，避免未重映射旧memberIds指错源。无需新跨ID迁移框架。
- sourceConversationId/sourceMessageIds继续留在成员；主题详情成员按钮可进现有源编辑与会话定位（会话已删则显示来源已不存在）。文档meta不能拿topic的null来源代替每个成员来源。
- 文档路径仅index.md及受控topics/<id>-<slug>.md；UI用descriptor选择，不接任意磁盘路径。slug保留CJK/字母数字、压成短名；id保证重名不覆盖。
- 投影signature仅受实际正文、分类、归档/过期、topic成员/标题与显示metadata影响；不因召回touch(lastUsedAt)重写全部文档。无变化不刷新mtime；源读失败不清空派生文件。源保存成功/文档失败时显示“源已保存、文档刷新失败”，提供重试，不回滚源。

## 3. 任务模型、门与UI复用边界

- H`chat/.../task_model.ts:59/68`已有daydream→worker→compress→chat候选链；H`entry/.../MemoryDreamOps.ets:48/56/74/84`已有resolve/generate；H`entry/.../di/AppContainer.ets:1375/1514`解析真实provider+model。E7每次run重读runtime/task配置并复用此链、reasoningLevel、buildProviderApi；不另加topic模型槽，不提前改E8的provider override格式。
- H`chat/.../openai_chat_api.ts:41/46`的generateText支持CallOpts.signal；运行超时/取消关闭写入gate，迟到结果不写。I`core/types/.../MemorySettings.kt:32`默认timeoutMs=120000，Harmony worker快照目前缺此字段，需要按源默认补齐并沿provider取消，不靠裸Promise.race声称中止。
- H旧runMemoryDreamRun仍要求worker.enabled，I coordinator的主题/维护开关独立于提炼enabled；E7专用topic run消费dreamModelEnabled（默认false），关闭提炼不应悄悄禁主题。保留旧破坏性DreamPlan审批，不混入auto topic写入。
- iOS自动入口仅前台resume、idle/charging/daily预算（`Coordinator:91/115`），手动跳环境/日预算但仍查feature/write门。E7可前台onResume触发同一coordinator，单isRunning、每日计数复用事件/KV；充电/idle从实际平台状态注入。现Harmony只有纯night-plan延迟函数，没有entry调度接线证据；不把旧WorkManager注释当真实后台能力。
- H`components/MarkdownText.ets`复用原生渲染，现链接走通用showLinkActions（`:999`），不支持记忆内部相对链接回调。最小UI用独立文档列表/主题成员按钮导航，不让topics/*.md发给浏览器；必要时只加窄本地link handler。
- MemoryPage保留核心桶功能；新增“主题/记忆文档”入口到小页面，主题detail成员以精确recordId进小源详情editor（当前MemoryPage私有dialog/仅核心桶，不能假跳它定位长期成员），generated doc只读。列表与detail在aboutToAppear/成功变更revision后整体赋值刷新；加载失败、空库、模型未配置、运行中/失败、stale、文档已不存在明确显示。长标题/路径/正文可换行，44vp操作目标、左右16vp/底部安全区沿既有页面，最终亮暗色/键盘截图审查。
- 污染guard必须维持在源提炼入口；新增topic不从外部tool结果取材料，候选未批准不参与。Harmony没有iOS pollutedConversationSummaries页面/清理实现证据，若做“污染会话列表”须另查ConversationStore可用消息树并准确复用判断；E7不能凭空声称已有该UI。

## 4. 三块独立所有权与成功标准

| Owner | 窄文件范围/完成内容 |
| --- | --- |
| A domain | 新memory_topics.ts/memory_documents.ts及必要memory_models/write/frontmatter/import兼容；纯分组验证、幂等topic/prune、真实投影/来源，保护旧源格式与常规tool边界。 |
| B store/runtime | 新MemoryTopicOps.ets/MemoryDocumentStore.ets、必要MemoryStore/MemoryDreamOps/AgentRuntimePrefs接线；guarded批量写、同队列、真实模型/signal/门/前台触发、源成功后派生同步与错误重试。 |
| C UI/integration | 新MemoryTopicsPage/MemoryDocumentPage及MemoryPage窄入口；主题成员/源编辑/来源定位/刷新、任务模型入口文案；统一root index/routes/lifecycle接线，不再分散改shared文件。 |

1. 两条真实非core源记忆→真实daydream provider返回合法分组→同一持久源JSON新增topic；重跑同内容不重复ID、不改mtime，关闭/超时/迟到/无模型/污染候选无写入。
2. malformed/member重复/未知或中途编辑删除/归档/过期→精确拒绝或prune；源content/provenance不被摘要覆盖；旧JSON reload、普通CRUD/提炼、frontmatter导入导出保真。
3. index+topic文件包含真实成员正文/分类/日期/覆盖数，删除/标题变更清stale文件，空库真正空；源读错不毁文档，文档IO错不回滚已保存源，可重试。
4. topic/detail/source编辑/generated只读/返回刷新/来源消失完整走通；独立subagent核调用链与窄屏亮暗色/键盘/间距截图，最后必要types/lint/build/install由实施阶段执行。本报告未声称验收通过。
