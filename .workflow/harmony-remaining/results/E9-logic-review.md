# E9 独立逻辑与调用链审查

2026-09-28，基线 `189eec7bd`（E8 source checkpoint）。**A 源码/实际调用链 PASS：两项明确 P2 均已局部修复、独立最终源复核关闭；限定本次范围无未关闭的具体 P1/P2。native callback、secure store/signature、domain request、Entry auth 与 UI 消费者已贯通。最终实际 Release/签名由root记录覆盖时点，C设备/真实Google账户及平台安全API仍pending。**

本 reviewer 按 harmony-arkts skill、E9 contract/design 审查实际源码。只读产品和已有证据，仅写本报告；未修改产品/测试，未运行额外 test、lint、build/sign、设备或账户操作。不把源码正确、host fixture、平台 Crypto 和真实网络授权混作同一验收。

## 已确认并回喂的问题

### P2：原 Google API-key 拉取模型入口被新模式分支截断

触发是普通Gemini API-key provider，点击现有详情页“拉取模型”。初版 `ChatProviderDetailPage.fetchModels:265` 对所有Google直接调用新 `loadGoogleModelCatalog` 并return；`GoogleAuthSupport.catalog:317` 在api_key分支仅返回unsupported和“请使用API Key模型列表入口”，而唯一原按钮的Google `x-goog-api-key` 分支已经删除。因此无需真实网络失败即可必然失去原模型目录功能。

最小修法已给root/D，最终 `ChatProviderDetailPage:265` 新catalog分支只收Google OAuth/SA；`:289–290` 恢复普通API-key的原Google header，`:295/304/324` 保原URL与models[].name解析。没有新catalog引擎，也没有为OAuth/SA偷偷用API-key端点。**源复核关闭；D仅受影响详情页lint，最终记录由其 `E9-ui.md` 保持。**

### P2：旧E6生成快照可以取消新客户端配置的登录

实际链：会话A已冻结旧clientA provider setting；用户保存clientB并在独立认证页开始B登录；A下一轮tool结果后再次生成仍使用其旧settingA。初版 `GoogleAuthSupport.resolve:278` 调用 `state(settingA)`，`:63–68` 发现当前binding B不同便invalidate B/controller.abort并替回A；B的合法正在登录attempt被取消，A随后login_required。即使B已经ready，旧快照仍能替换runtime state。这不是普通OAuth HTTP失败，也不是E6需要读后来全局Chat选择。

最小行为边界已给root/B。最终 `GoogleAuthSupport.state:61–76` 默认不准替换不同binding，直接credential_changed；`:201/297–301` 仅显式begin/configure允许replace并沿旧write-tail/callback关闭barrier。实际 `GoogleProviderConfigSupport:93/106–109` 在JSON提交成功后才await配置更新，且在提交回滚catch之外；后置失败不会删已提交新secret。ordinary logout `:318–325` 对不匹配旧binding只在tail后调用C的精确owned remove，不能重绑或invalidate新登录。**最终源关闭；root/B在既有唯一生命周期group追加实际staleA→Blogin反例：`/tmp/e9-google-entry-stale-binding-red.log` 为Missing expected rejection → `/tmp/e9-google-entry-stale-binding-green.log` 1/1；机械compiler修后最终 `/tmp/e9-google-entry-compiler-repair-green.log` 仍1/1。已读取这些既有日志，本人未执行。**

## 已冻结链路

- **Native ownership/close：** `google_loopback_core.cpp` 固定 AF_INET/INADDR_LOOPBACK 绑定请求端口，bind 失败不改端口；getsockname 返回实际 port。连接数最多4，header最多8192 bytes，每连接15秒；只GET、origin-form target、明确HTTP版本，无target控制字符。`Stop:108–116` 先标 stopping、pipe唤醒、join worker，随后在mutex下关listener/conns/pipe；Open中途失败由Server析构同路径回收。
- **NAPI/late callbacks：** `google_loopback_napi.cpp:62–70` 先Channel.closed、同步Server.Close/join，再TSFN abort；队列deliver由holder保持Channel生命周期并检查closed/env，request data用unique_ptr接管。Start失败回收channel；close删除所属session，environment cleanup关闭所有session。没有跨环境全局handle map。此处为源审查，真正host生命周期与OHOS env接受证据由native/root提供。
- **Callback port/validation：** `GoogleOAuthCallback.parseGoogleRedirect` 只接受确切HTTP localhost/127.0.0.1固定port和path；actual native port与配置比较。state/code/error计数与path/hash检查，合法结果单次消费；reply成功后close再resolve，取消/失败/120秒超时走同close promise。监听early callback在handle拿到前有限native队列缓冲，未把callback code或token写provider。
- **Secure refs：** `GoogleSecureStore.stageGoogleSecret` 随机UUID新reference，createOnly检查文件及Asset；私钥/客户端secret作为AES密文文件，Asset仅存随机32-byte key，DEVICE_UNLOCKED/NEVER。文件和plaintext有大小限、namespace/owner/kind/reference在解密后校验；不套SSH secret codec。阶段写失败只回收本次新key；已有token文件原子替换保旧key，不能把旧Preferences明文称为encrypted。
- **Token binding：** token namespace按provider+两种mode隔离，文件内完整client/redirect/secretref绑定。queued write在await前JSON序列化；read返回新parse对象且要求binding精确相同。normal remove只删除同binding；provider专用remove在调用者取消/write-tail后清两个所属mode。状态DTO没有token/key字段。
- **PKCS8/RS256：** `GoogleRS256Signer` 只接受非加密PRIVATE KEY PEM，canonical base64解码为DER；API12既有convertKey和每次新 `RSA2048|PKCS1|SHA256` Sign，await init/sign，finally clearMem+DER归零。没有用PSS或共享已初始化Sign。真实独立临时key验签及API12平台签名gate不由此源审查替代。
- **Provider codec/eligibility：** Google新mode、四个公开引用/客户端字段在make/copy/serialize/decode/defaults贯通，旧缺字段默认空、旧privateKey仍可读。hasUsableAuth只是已配置门：OAuth client+redirect，SA vertex/email/project/location+key或localref；不是网络READY。有效override经过既有provider factory，API key分支未要求新resolver。
- **实际Google request：** `google_chat_api.buildRequest` per-request以原signal await auth并核模式kind/token/project/location。SA用encoded项目/区域/模型path+Bearer；两OAuth固定各自host，CodeAssist与Antigravity确切wrapper分开，不走API-key roulette。非流及SSE共用一层unwrap、nestederror；生成仍沿原消息/body/SSE引擎，没有重发、换host/client/tier后门。

## 最终 Entry / UI 调用链

- **Single-flight与取消：** `GoogleAuthFlight` 每waiter维护独立abort监听；一个取消只移除自身，最后waiter取消底层shared signal。refresh/onboarding各state独立flight，settled只释放同flight引用。每个HTTP请求传原AbortSignalLike；form/token/PKCE/body沿现有HttpClient，无第二client或重新发一次已消费生成。
- **generation/write commit：** auth读等待writeTail，写前后核state对象和generation；persist成功后才更新in-memory tokens/READY。logout、relogin、配置更换和provider删除invalidate旧操作；晚到刷新/onboard不能复活新state。invalid_grant只清所属binding。显式attempt cancel与180秒总超时走 `cancelLogin:122–130` 递增generation并把clear排在pending write之后；清理失败显示真实error，不能因cancel把晚到token误报READY。
- **Onboarding/状态：** token exchange先secure持久，再load/onboard/LRO（有界12轮、每轮signal）；失败保有效token并呈onboarding_required和服务器validationUrl，explicit retry沿同flight。configured、signed_out、authorizing、待刷新、onboarding_required和ready相互区分；配置/2xx token exchange不假称资格或推理授权成功。
- **服务账号：** 私钥ref优先从实际owned secure仓读；legacy只在无ref时兼容。revision带邮箱/项目/区域/ref/实际key hash，新revision取消旧flight；JWT沿确切one-hour claims、RS256、form token endpoint，缓存/请求状态不伪装成OAuth refresh token。返回actual项目/区域供已编码Vertex path；目录走实际Bearer publisherModels endpoint，不等于当前项目/区域推理权。
- **正常logout/provider删除：** 普通退出不越过mode/client绑定；provider删除已成功提交JSON移除后，forget同时invalidate两个OAuth mode、关callback、等待write-tail、清两owned namespaces，SA cache同ID清理；再回收删除对象当前bound refs。后置失败说“已删除但清理失败”，不回滚删除或触碰其它provider。
- **真实host接线：** AppContainer buildContainer使用原createRcpHttpClient一次initializeEntryGoogleAuth；同步provider factory每Google API注入resolveEntryGoogleAuth。Chat/辅助任务/子代理沿同factory及本轮effective provider，不反向Auth→DI或造第二生成引擎。native四源已在原CMake/libamber_native入口和声明接通；callback消费真实声明的named request/handle，API12 port检查保留。
- **配置保存/秘密寿命：** helper fresh lookup保原models和其它provider字段；新ref先stage，再JSON提交，成功后owned旧ref cleanup。metadata失败只删本次新ref，保原provider/secret；传入私钥ref的早期lookup失败/ID已消失也清新ref。旧Preferences私钥只在显式SA成功保存后迁移，未保存草稿离页清理；client secret与private key不回填明文UI State。实际commit后configure钩子是上项authority链的唯一保存入口。
- **UI真实消费者：** saved provider→begin→系统浏览器→实际callback completion；外部browser暂隐藏不取消，显式返回/配置变化/销毁/取消/退出则取消，回来只刷新状态不重发登录。busy导入/保存期间两back窄门已核，authBusy仍可取消离开。Detail普通保存保新模式/ref字段；API-key目录已恢复，SA/Antigravity用真实mode目录，CodeAssist诚实manual。公开export/import只公开模式/email/project/client/redirect/最小model metadata，不带key/ref/tokens；导入新UUID且提示重新配置。

最后actualcompiler回喂的Record字面量键改显式quoted字段、catch/rethrow `Error` 和callback真实namedinterfaces均已精确读取。JWT/auth form字段和RSA算法、signature输入、原错误实例、callback ABI及signal时序保持；不把机械类型调整计成新行为或扩测试。

## 证据边界

两项独立confirmed P2都已源关闭。另独立B UI reviewer查实并回喂保存中back导致详情旧引用覆盖新secret配置的P1，以及无条件安全存储footer的不实文案P2；两项已修，本reviewer亦读取 `GoogleProviderAuthPage.onBackPress:70/back:288` 的busy窄门及准确footer说明，源关闭但不重复计作自己的发现。

已读取 `E9-domain.md`（唯一HTTP/codec组10/10、实际tsc/lint）、`E9-secure.md`（两owned检查、唯一RSA组1/1）、`E9-native.md`（唯一真实POSIX TCP生命周期组和API12SDK syntax0）、`E9-ui.md`（受影响页/helper的必要lint/types）、`E9-entry-auth.md`（五真实模块唯一生命周期group、最终SDKtypes0/affectedlint NoDefects）、`E9-ui-call-review.md` 和root gate。native `/tmp/e9-google-loopback-lifecycle.log`、RSA `/tmp/e9-rsa-check.log`、最终Entry `/tmp/e9-google-entry-compiler-repair-green.log` 的既有结果已读取；本人没有重跑或新建suite。RSA组以真实Node临时key、actual Entry源码和独立OpenSSL证明protocol/signature，不证明设备Crypto；Entry group的platform ports受控，不证明设备Asset、browser或真实账户。

Root最后回执：`/tmp/e9-release-final-build.log` 实际第二次Release 10.586秒成功、BuildProfile DEBUG=false；`/tmp/e9-release-final-sign.log` 本地Digest/verify-app成功，未install。本报告引用该root回执而未自行构建/签名，不能将本地签名验证推作原AGC设备包接受。

**C仍pending**：设备离线时，OAuth client接受、callback真实浏览器/端口/NAPI envcleanup、Google资格/onboarding与流式/非流推理、Crypto/Asset真实API12以及亮暗UI截图均不能声明PASS。实际最后Release/签名artifact是root独立gate，不由本报告source PASS代替。
