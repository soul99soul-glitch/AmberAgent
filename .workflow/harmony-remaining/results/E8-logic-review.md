# E8 独立逻辑与调用链审查

2026-09-28，基线 `1bbd1dc43`（E7 source commit）。**A 源码与实际调用链 PASS：三项明确旧 UUID 兼容 P2 均已精准修复并独立源复核关闭；Root 的无关坏槽牵连 P1 也已源关闭。限定本次范围无未关闭的具体 P1/P2。新 fixed pair 定位、worker/daydream候选与E6父轮回退保持完整。C设备验收仍待验证。**

本 reviewer 已载 harmony-arkts skill，读取 E8 contract/design、全部当前 tracked diff 与新引用/选择/草稿模块，以及实际 caller。只读产品，仅写本报告；不修改源码/测试，不运行额外suite、lint、build/sign或设备操作。本文中 B 是已有执行者回执，不是本 reviewer 执行。最终行号以收口源更新，函数名保留定位意义。

## 可明确触发的问题

### P2：旧 UUID 聊天任务从可用 B 错回退到全局 C

触发：旧 scalar UUID M，provider顺序 A(M/chat，无 usable auth)、B(M/chat，有 usable auth)，全局Chat C。E7 `resolveConfiguredTaskChatModel` 先取UUID/type，再在循环跳无auth容器，实际选B。E8初版 `resolveConfiguredTaskModel` 用pure rawlookup先选A，再makeProviderChoice返回null，title/suggestion/compress/worker/daydream遂回退C；不是用户选择了fixed A。

已回喂root，root确认，并授权在既有唯一选择group用实际DI factory/resolver AST抽取、真实auth/copy/旧UUIDlookup复现；owner明确回执先红实际C/预期B。最终 `AppContainer.ets:1555–1556` 仅 legacy+chat复用原resolver；fixed分支仍raw容器+UUID匹配后经过原 effective/auth factory，不能借legacy修复去另Provider。**最终分支已独立源复核关闭；执行证据范围见下文。**

### P2：旧 UUID 生图预筛规则丢失，后面的可用 B 不再装配

同一明确兼容场景：旧 image scalar M，前置A为Google或无auth OpenAI且含M/image，后置B为有auth OpenAI且含相同UUID。E7 `ImageGenTool.resolveImageGenChoice` 先筛OpenAI/usable auth再扫UUID/image，实际B；E8初版new rawfirst-match拿A后type/auth gate失败而返回null。新fixed {B,M}正常，不能以修legacy为理由放松fixed精确定位。

已给root/B owner。最终 `ImageGenTool.ets:47–67` 仅 legacy 沿旧 raw OpenAI/auth 预筛循环匹配UUID+image，将实际容器在内存转fixed，再走原effective/auth factory；没有偏移持久scalar或放宽新fixed。已独立读取最终源。**源关闭，owner在同一个已有consumer group必要红→绿 1/1，见 `/tmp/e8-consumers-legacy-red.log`、`/tmp/e8-consumers-legacy-green.log`。**

### P2：legacy实际已选B，设置却显示A或“选择不可用”

仍用第一项同一旧scalar/A无auth/B可用trigger。Picker初版`:89`用pure rawlookup将selected定在A，随后usable筛掉A，B没selected，`:108`假称不可用；TaskModels chatSlot实际configured B，但rawName仍取A，current优先rawName而显示A。原picker的auth预筛后UUID选中能显示B。

已给root/UI owner。最终 `ChatTaskModelsPage.configuredName` (`:120–125`) 仅 legacy 以实际choice.providerContainerId定位容器、choice.model展示模型；chat/memory/image/OCR四入口 (`:136/162/173/203`) 均消费自己的实际choice。`ChatTaskModelPickerPage.refresh` (`:114–118/133`) 仅legacy按用途读取实际choice并以rawcontainer+UUID高亮，fixed仍raw精确pair。OCR分支用OcrSeed、image分支用真实image resolver，没有套通用legacy聊天规则。**源关闭，owner两页必要lint `/tmp/e8-task-model-ui-legacy-fix-lint.log` 退出0/No defects。**

伴随聊天legacy修复，已精准复核OCR不同的旧身份边界：`OcrSupport.ets:174–182` 先以原raw UUID取第一个模型（不加type/auth筛选），把该容器在内存转fixed后经过chat factory。无凭据A不能变为有凭据同UUID B；实际seed `:189` 仍保原legacy reference。上述同consumer group同时复现并证明image选B、OCR不可用的原行为，没有新suite或持久迁移。

## 已核实的核心链路

- **引用/存储：** `task_model_reference.ts` 区分auto、legacy UUID和fixed ProviderSetting.id+ProviderModel.id。API modelId不当配置UUID保存。decode没有只读写入或猜Provider，new pair缺字段/数组/坏JSON显式配置错误；fixed只查指定容器。TaskModelSelectionSupport接受实际KeyValueStore，不引共享DI/第二factory。`getChatKvStore`由同一个AppContainer.storage适配，pair与旧scalar/clear写同现有键。
- **effective/auth与raw身份：** `AppContainer.makeProviderChoice:1319–1324` 用原effectiveProvider/auth，新增providerContainerId取原p.id；有效provider.id可以是model.override另一ID。fixed解析不遍历另provider同UUID。taskModelReferenceForChoice以raw container和配置UUID投影，不以有效override ID猜容器。
- **候选链：** `task_model.ts:77–102` worker和daydream仍沿原分支选择及外层fallback顺序；每候选完整带Provider身份，没用UUID→Provider全局Map。旧string helpers留给未迁角色等caller，新七槽consumer沿typedref。
- **E6父轮回退：** Dependencies的compressReference在seed准备中解析，并显式带runtime.resolvedChoice；Auxiliary title/suggestion每次读其槽、fallback仍传本轮seed；memory worker候选chatRef由同seed rawcontainer/UUID产生，最后仍显式fallback。Subagent role UUID仍保原resolver+parentRuntime.resolvedChoice，E8没把父助手/模型变成后来B全局选择。
- **Pref RMW：** MemoryWorker新增providerId/daydreamProviderId默认null，旧UUID仍读legacy。选择同一次updateMemoryWorkerModels写UUID+provider+follow=false；clear写AUTO+provider=null+follow=true。AgentRuntimePrefs保原共享KV锁和rawJSON RMW，不覆盖E7 topicWriteEnabled/timeout/充电设置或未知key；两follow旗分别保存。
- **真实参数：** compress、title/suggestion、worker extraction、MemoryDream采用已选effective provider，保真实API modelId；新增/保留model customHeaders/customBodies，title/suggestion仍reasoning off，worker原default、daydream用原daydreamReasoningLevel。E7 TopicOps复用新版resolveMemoryDaydreamChoice，已有独立feature/write/cancel/signal门未被用途绑定替换。
- **OCR/cache/health：** OCR fixed seed冻结实际choice；transformer与probeEntryOcrHealth读取同effective provider/model与IMAGE gate，无Chat fallback。目标cache namespace包含typed reference与rawproviderContainerId，避免两个容器/重复UUID、甚至有效override ID相同情况下串用OCR正文；仍委托原cache容量/过期/持久文件。ChatPage实际发图健康门 `guardImagesForSend:3292–3294` 也调用同seed helper。未因这次改动给OCR加入不相关customheaders/body规则；最终legacy原first UUID身份分支已复核。
- **生图：** availability和execute共用实际resolveImageGenChoice，每次execute重读该槽，仍type=image、effective OpenAI/usable-auth，无Chat fallback；原create/edit HTTP、真实源图、decoded bytes本地落盘和文件仓接线保留。最终legacy查找顺序已源修复关闭，fixed仍严格指定原容器。
- **保留其它引用：** Novel fixed仍精确Provider容器+UUID且失效null；Chat全局API-name选择、assistant/role UUID、DeepRead/model registry/council模型格式未迁成用途pair。原backup范围未扩大，不宣称用途键已在providers/assistants包备份。
- **配置说明/草稿：** 两页路由已真实注册可达；小说共用Chat的说明符合当前runtime。ChatDeepReadDraft只收当前effective OpenAI API-key Chat Completions，检查实际endpoint、不能保留的headers/bodies及缺字段；返回三个draft不保存。SettingDeepRead等首load再填本页字段，原Save仍唯一持久consumer；没有OAuth/SA凭据转换或更改DeepRead引擎。

## Root 相关 P1 源修复复核

Root独立查实并修复“无关malformed槽阻断当前用途”：初版loadTaskModelReferences一次解四槽，image/title等坏pair会让compress seed或Daydream先throw。最终Dependencies只read compress key；Auxiliary title/suggestion各自read对应key、worker只readcompress；MemoryDream只readcompress；ChatPage压缩展示与命名各自read本槽。全文实际consumer已无loadTaskModelReferences，DI兼容export保留；malformed当前用途仍诚实报告，而无关槽不牵连。**源关闭，此项不记作本reviewer独立发现或执行复现。**

## 已有 B / C 范围

纯引用/选择owner已回执唯一focused group PASS、窄types 0和lint 0error，日志`/tmp/e8-task-model-selection-test.log`、`/tmp/e8-task-reference-typecheck.log`、`/tmp/e8-task-reference-lint.log`。上述legacy认证counterexample后续由root授权只扩同一个group：`/tmp/e8-task-model-selection-red.log` 明确 actual container-c / expected container-b → `/tmp/e8-task-model-selection-green.log` 1/1 PASS。这组执行实际DI factory/resolver AST提取、原auth/copy和legacy lookup；本文只读取该既有日志，未跑测试。

`results/E8-task-consumers.md`记录唯一实际Entry选择/OCR seed/transformer/health/ImageGen受控group，缓存跨目标具体红→局部prefix修→1/1绿；model/DI/network端点及SDKIO受控，不是外网服务或设备验收。最终legacy image/OCR两个具体反例只追加至同group，`/tmp/e8-consumers-legacy-red.log` 0/1 → `/tmp/e8-consumers-legacy-green.log` 1/1；末版两owned文件 `/tmp/e8-consumers-legacy-lint.log` No defects。本reviewer读取报告与最终source，不另重复执行。

UI owner 的两页legacy修复lint及只读call-chain reviewer报告已读取；后来编译回喂的文案表从Record literal机械改为显式七字段TaskTexts+switch，已只读核对实际两页声明和最终选择分支，没有变更选择或存储行为。本文行号已更新至该机械修复后的源；编译及签名仍由root记录最终覆盖时点。

Root actualcompiler/sign与独立UI call-chain reviewer负责最终gate；**C设备仍pending**，本报告不据purelookup、transpile执行或source布局宣称真实设备亮暗/保存重进/OCR网络/生图平台验收。
