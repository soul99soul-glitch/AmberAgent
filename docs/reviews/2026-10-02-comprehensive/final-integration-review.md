# 最终集成交叉复审

日期：2026-10-02。审查者未实施 Phase 1/2/3/4 产品修复；只读基线 `/tmp/amber-harmony-review-20261002-baseline/` 与当前源码，读取 findings、各阶段实施/独立复审报告。仅写本报告及临时探针，不修改生产源码或仓库测试。已读取 harmony-arkts skill。

最终结论：**Phase 1/2/3/4 集成交叉复审 PASS，包含最新 UI10/UI11/UI12 与 UI09 初始状态提示归属补修。没有剩余已确认的源码阻塞问题。** 两处实际编辑输入边界漏修已反馈实施者、补齐并独立复跑为 GREEN；UI09 页面/跨模式 sync 及同一 OAuth 模式取消的追加修复已独立复跑 47/47 GREEN、原取消探针 GREEN，最后初始查询/模式提示补漏自跑 6/6 GREEN。UI10/UI11 补入实际入口复核 34/34 GREEN，UI12 单项源码契约 1/1 GREEN、独立 35 项 focused 门和正式签名包设备字段证据闭环，没有发现新增实际回归。最新完整结果为主 agent 执行的 4001/4001、正式构建及签名安装成功；本报告区分自行验证、其它审查者证据和未覆盖的真实云端边界。

## 新发现及回喂后二次复核

同属 UI-03 输入快照边界，未新增泛化机制：

1. 编辑态长按发送进入异步 append；作者在 append 等待时写入新草稿。原修复先由 clearAcceptedComposer 保留新草稿，随后真实 cancelEdit 又无条件清空 inputText。最初15项 UI fixture 将 cancelEdit 替换为只清 editingNodeId 的 stub，未覆盖这条真实副作用。本轮探针执行实际 cancelEdit，明确复现新草稿变空。
2. 同一 MessageNode 可有多个消息版本。发送预检期间，作者切换版本并对新版本点编辑，实际 startEditMessage 改变 editingMessageId，editingNodeId 保持相同。只比较 nodeId 的原修复仍让 sendEdit 用旧快照文本提交到新 messageId。本轮执行实际 startEditMessage/send/sendEdit，观察到针对新版本的旧稿 edit command。

修复后两个发送入口同时捕获、比较 nodeId/messageId；长按成功仅退出仍匹配的编辑身份，输入文本继续只由 clearAcceptedComposer 按接受快照清除。正常取消编辑仍使用原 cancelEdit；没有改变其用户行为。正式测试开始执行实际 cancelEdit/startEditMessage，并补长按编辑/普通和长按切同node版本回归。

独立反例：`/tmp/amber-integration-ui-proof.cjs`；修前 **2/2 FAIL**，日志 `/tmp/amber-integration-ui-proof.log`；以同一探针读取修复后实际源码 **2/2 PASS**，日志 `/tmp/amber-integration-ui-proof-after.log`。正式 UI 测试 **19/19 PASS**，日志 `/tmp/amber-final-ui-integration-green.log`。

## 跨模块接线结论

| 边界 | 对照实际调用链的复核 |
| --- | --- |
| Novel 取消、失败与 CAS | repository 仅 fail/cancel 使用 requireCas=false，仍校验当前 project/branch；有效 owner 的 ghostwrite token/epoch/lease 校验不放宽。真实 checkpoint/commit、claim/resume/retry 保留默认 CAS。Creation 取消从当前持久化 claim 建 ref，不为取消重新恢复/重试任务。Polish 用户主动取消不要求 claim 是既有授权语义。 |
| Novel 暂停恢复与旧 owner | Promise Map 保留旧 runner 的完整收口 Promise；resume/retry await 后才更改 durable job、启动新driver。finally 清旧key发生在 promise 对等待方完成之前；recover 在key存在时不重复启动。没有加入定时重试或放宽正文写入校验。 |
| transform 后 fit 与 vision 重建 | normal/regenerate/tool loop 均先物化 input transformers，随后执行原 fit；vision fallback 重用该步闭包。输入来源仍是 canonical assembledBase，persisted document/image 保留。最新单条超大输入继续完整保留是既有策略，不误报为本轮 fit 漏修。 |
| foreground 压缩与 OAuth/signal | ConversationRunDependencies 给 domain prepareContext 传 ctx.signal；同步压缩 factory 的 stream wrapper 在认证刷新后把原 opts 传至实际协议API。RcpHttpClient 检查已取消signal并以 AbortError 收口。domain 请求前/后检测取消，取消不进入质量重试或摘要落库；background precompact 显式去掉父signal保持独立生命周期。 |
| 技能刷新、profile 与运行快照 | 仅按 rt.assistant.id 读取最新 enabledSkills、SkillManager目录；RunToolCatalog 对重建的技能primitives继续应用既有profile。其余primitives和provider/model/参数保留当前运行快照。不是读取UI当前助手；搜索/子任务wait取消不触碰这些快照。 |
| 备份与 degraded 初始化 | packBackupBytes先完成真实repo.list/getById读取，再检查chatStorageDegraded，覆盖首次lazy初始化失败。当前ChatRdbRepository内存fallback没有正常写入入口，初始化失败后返回空内存并保持degraded；不能用人工塞入fallback数据的假设推断本轮会输出混合成功备份。settings-only继续不依赖会话数据库；读取/恢复异常仍向上报错。 |
| 页面发送与 service 受理 | 普通send/edit只有submitConversationRun返回true才消耗接受快照；queue/append以实际Promise成功为界。service同步拒绝restore-version/busy时保留输入。异步生成后失败属于已受理运行，不要求把已受理草稿重新放回输入框。页隐藏使预检sequence失效；新输入和附件由接受快照清理，不新增队列机制。 |
| OAuth配置保存 | 保存构造updated快照后，以updated openAi.authMode读取对应token，不在await之后改用当前mutable表单mode。Codex/Grok登录已同步表单accessToken，保存读取最新token；api-key路径保持原校验。 |
| 页面手动标题 | 实际maybeAutoTitle已调用prepareProviderApi，保留原任务模型、自定义headers/body和reasoning参数。标题写回经mutateStoredConversation，只合并符合原tail条件的title；没有用旧会话快照覆盖新消息。 |
| 长讨论归档 UI/domain | domain draft只绑定完整来源prefix及其digest；Workspace的source count/range均使用draft.sourceMessageIds，和实际模型输入批次一致。确认后推进所选来源，后续仍eligible；旧累计摘要进入下批预算。没有增加截原文、后台队列或默认猜测窗口。 |

## 额外真实入口验证

临时 `/tmp/amber-integration-manual-title-proof.cjs` 提取实际 ChatPage.maybeAutoTitle，经过生产 prepareProviderApi、真实Chat/Responses协议adapter，替换OAuth存储/HTTP边界。Codex OAuth、Grok OAuth、API-key共 **3/3 PASS**，日志 `/tmp/amber-integration-manual-title-proof.log`：

- actual HTTP Authorization使用刷新后的正确token family；Codex accountId保留。
- model、自定义task header/body保持；没有继承chat temperature/topP；源provider choice不变。
- 页面命名活动计数仍在finally释放。

初次临时探针曾错误假定 OpenAI reasoning=off 必然在wire中省略effort；源码已明确对非官方模型把none映射low，该既有映射未被本次修改，未计入问题。探针已按实际协议规则修正，避免将测试预期错误当产品缺陷。

Phase1/2已经独立通过的全集未在本轮重复运行。主agent提供的完整domain结果为chat2486/2486、deepread1000/1000，本报告不把这些作为本审查者自行执行的计数；本轮自行执行的新增边界探针和UI19项证据见上文。

## UI09 OAuth晚到回调补修的跨模块复核

另一独立审查者实际复现页面取消Grok exchange后切API key，被旧callback写入key/baseUrl并自动save；并复现client迟到sync覆盖已保存的新认证模式。此发现和原红证据见 `phase3-provider-lifecycle-review.md`，本报告不冒称自己首次发现。

补修后本轮实跑 `ui_send_provider_lifecycle.test.cjs` 与 `oauth_provider_sync_entry.test.cjs`，**UI26 + client14 =40/40 PASS**，日志 `/tmp/amber-final-ui09-integration-green.log`。源码对照及行为复核：

- page的同一loginSequence、provider ID、authMode、pageAlive边界应用于device-code request、poll、models callback、Grok exchange及其错误回调。取消、切模式、销毁使旧owner失效；正常Codex/Grok仍能成功保存，没有把浏览器前后台切换误当作销毁。
- client生产diff仅各自sync helper的一行authMode判断。独立refresh仍保存自己的tokenstore并返回fresh token；匹配OAuth模式同步provider.apiKey，api-key、另一OAuth、token-plan和其它provider身份不写provider。没有把旧请求的mode切换变成refresh失败，没有额外认证重试或fallback。
- prepareProviderAuth此前捕获的运行快照仍使用该次fresh token。client跳过跨模式provider同步不影响同一已授权运行的auth返回；未改全局provider选择或冻结UI配置。

以上首轮40项覆盖页面owner和跨认证模式sync，没有覆盖同一OAuth模式取消时client自己的持久化边界。原审查者随后确认同模式取消后的迟到HTTP仍保存新tokens并覆盖旧账号；这条真实反例继续推动最小补修，未以首轮GREEN提前关闭UI09。

追加补齐后本审查者又执行原 `/tmp/amber-phase3-oauth-cancel-review.cjs`：两家client的saved/synced均保持old-login。正式全集当前 **UI27 + client20 =47/47 PASS**，日志 `/tmp/amber-final-ui09-integration-green2.log`。Codex在poll HTTP后及授权码exchange后、persist前检查既有shouldCancel；Grok以可选第四参数在HTTP后persist前检查，页面接入同一loginCurrent闭包。覆盖HTTP挂起时取消、Codex第二次exchange期间取消、正常登录持久化以及Grok旧三参调用仍成功。refresh函数未修改，匹配/跨模式sync边界仍绿。

原审查者最终回执为 Provider/Novel/History/UI09 PASS，5组实际源码探针GREEN、client20 + UI27 + archive5 =52/52通过。此计数来自原审查者回执；本审查者自行运行的UI/client为47/47及原取消探针GREEN。当前源已冻结，本轮跨模块检查没有剩余已确认的源码问题。受控端口测试仍不能证明真实OAuth/DAV服务器接受、ArkUI显示或设备后台生命周期表现。

## Phase 4 UI10/UI11 最终入口与跨页交接复核

重新对照 findings 和 Phase 4 实施、独立复审报告，并读最终 picker → Settings → Detail 源码。UI10 是已支持的 Codex 被旧统一 gate 阻断；Gemini OAuth 仍属未支持能力，不把既有 gate 列为漏修。UI11 是设备实际观察到的新增 draft 跨页丢失，和 UI10 的行类型缺失为不同根因；报告不猜测未确证的编译器或模块装载原因。

- picker 为两行显式传 `codex` / `gemini`；Settings 只给 Codex 构造新的 UUID draft，包含 `codex_oauth`、既有固定 base URL 和 Responses 开关。创建 draft 不保存提供商、不联网；已有 API key 预置仍使用同一详情路由。
- `setPendingProvider` / `takePendingProvider` 继续采用一次性单槽接口，改用共享 AppStorage 宿主承载。Detail 在异步加载列表之前 take 并清槽；旧页面随后完成加载不会删除等待期间新写入的下一份 draft。现有 providerId 编辑路由不消费新增槽。
- Detail 最终仍通过原 applyProvider 解释认证模式；未切换 provider/model 运行快照，也没有为入口加入 fallback、重试、额外保存或网络调用。生产代码已去掉调试日志。

本审查者执行 `node --test harmony/entry/src/test/provider_template_entry.test.cjs harmony/entry/src/test/ui_send_provider_lifecycle.test.cjs`，**入口 7 + UI 27 =34/34 PASS**，0 fail/skip/cancel，日志 `/tmp/amber-final-ui10-ui11-integration.log`。覆盖 OAuth 行类型、Codex 每次独立 draft、Gemini gate、原预置 callback、单次消费、异步加载期间下一份 draft 保留，以及两个源码模块上下文共享 AppStorage。独立 Phase 4 审查者还执行了实际 Settings callback → Detail lifecycle/applyProvider 的双模块 host 探针；其证据见 `phase4-provider-entry-review.md`，不将该探针计数冒充本审查者自跑。

设备 Codex 入口证据来自主 agent 和独立 Phase 4 报告：AppStorage set true → take true/afterClear false，Detail isNew=1、resolved target=true，真实页面显示 OpenAI Codex OAuth、Codex 模式、Responses 开关、固定 URL 与登录控件。普通 API key 预置已存在并被过滤，未删除用户配置以重跑真实预置按钮；该路径当前证据为源码及双模块 host 测试。未开始真实 ChatGPT 云端登录，不把入口到达等同 OAuth 服务端验收。

UI10/UI11 冻结时主 agent 回执为 CJS **508/508**、Phase 4 lint **0 error / 1 warn**；领域 TS **chat 2486/2486 + deepread 1000/1000** 此后未改领域源码。这些全量结果由主 agent 执行，本轮只复跑新增入口及 UI 的 34 项，未重复全集。UI12 后的最新最终结果见下文，不沿用这个阶段的旧计数。

## UI12 冻结源码与正式设备门

findings 最终确认 **41 组（13 P1 / 28 P2）**。名称 TextInput 的按值 Builder 初帧问题由 `audit_ui` 从设备、官方文档和生成代码确证，`fix_backup` 完成独立复审；本轮只核对最终补修的跨模块影响与设备回喂，不重复根因探索。

最终 Detail 将原六参数 field 改为一个 `ProviderFieldOptions`，六处调用直接传 typed 对象字面量，符合已有 V1 Builder 引用刷新条件。名称/API Key/OpenAI Base URL/API Path/Google Base URL/Claude Base URL 的 value 与 onChange 均仍指向原父页字段；obscure、mono、placeholder、固定官方地址只读分支及其它条件保持。未修改共享 FlatTextField、认证客户端、AppStorage 交接或 provider/model 快照，未添加控制器、整页重建、fallback 或高版本 API。

本审查者独立运行新 `provider_field_binding.test.cjs`，**1/1 PASS**，日志 `/tmp/amber-final-ui12-integration.log`；独立修复审查者最终 **35/35 focused PASS**，并对正式生成代码中的六处 `makeBuilderParameterProxy` 及 FlatTextField update 路径复核通过，证据见 `phase4-provider-fields-review.md`。源码契约检查不能证明 ArkUI 渲染；本审查者另外亲看正式设备 `final-provider-name-light.jpeg` 与 `final-provider-name-retained.jpeg`：名称从初始即为 OpenAI Codex OAuth，输入追加 ReviewDraft-1002、切模型再返回配置后仍显示该草稿。独立审查者还目看 API key 模式分支截图，确认名称保留及可编辑 Base URL 的值正确。没有保存测试草稿，也没有发起 OAuth 云端登录。

UI12 冻结时完整回执为 **CJS 509/509 + chat 2486/2486 + deepread 1000/1000 =3995/3995 PASS**，完整构建 **SUCCESS（13.963s）**、同签名正式 HAP 安装及启动成功。UI12 scoped lint **0 defects**。这些阶段结果归主 agent 及独立审查者，本审查者自行执行的是本报告列明的边界探针、UI/client 47 项、UI10/UI11 34 项与 UI12 单项；最新追加后的最终回执见下文。

## 最后 UI09 初始查询与提示归属补漏

独立设备审查在 API key 模式截图观察到仍显示 Codex 登录失效提示。按既有异步认证状态归属并入 UI09，不再新增问题组。新增 actual-method 6 项在修前为正常路径 2 PASS、提示清理/迟到归属 4 RED；最小补修只新增四行：applyProvider 捕获现有 loginSequence，两家初始 loadTokens callback 发布 account/error 前调用既有 loginCurrent，selectAuthMode 仅在模式确实改变时清 unsupportedAuthMsg。正常当前 OAuth 的缺 token 提示、email 显示、同模式选择保留提示均不变；不改 HTTP、client、tokenstore 或认证数据。

本审查者只核对这四行和对应真实方法回归，执行 `ui_send_provider_lifecycle.test.cjs` 中上述新增 6 项，**6/6 PASS**，日志 `/tmp/amber-final-ui09-hint-integration.log`。覆盖 Codex/Grok 的当前查询正向结果、模式切换与同模式选择，以及旧 null/token 在换模式、新登录、页面销毁后返回不覆盖当前 account/error。未重复全集或扩审领域。

本审查者亲看最新正式包重录的 `device/final-provider-apikey-mode.jpeg`：API key 已选中，名称保持 `OpenAI Codex OAuthReviewDraft-1002`，可编辑 Base URL 和 Path 正常，旧 Codex 登录失效框已消失。主 agent 的最终同名截图和设备脚本 EXIT=0 回执还确认名称初值、输入/切 tab 保留、返回首页未保存 draft、原 light 主题保持；不把该过程等同所有认证组合或真实登录验收。

最新最终回执为主 agent 自行执行 **CJS 515/515 + chat 2486/2486 + deepread 1000/1000 =4001/4001 PASS**；完整构建 **SUCCESS（15.287s）**、同签名正式 HAP（摘要前缀 `13ee`）安装及启动成功，906 份冻结源码 hash **0 drift**。本审查者结论为这一追加窄修集成 PASS；fix_backup 的最终独立回执与整体 completion 由主 agent 汇总。真实 OAuth、DAV/备份云端接受等边界仍不从上述结果推断。

最终独立回执：fix_backup 已补审 UI09 initial-query，正式41/41、额外实际方法8种反例均通过，亲看最新13ee HAP API key screenshot/json，旧hint已清、名称与端点保留；phase3-provider-lifecycle-review.md已记PASS，无blocker。Root交付verification.json/completion.md均已收口。
