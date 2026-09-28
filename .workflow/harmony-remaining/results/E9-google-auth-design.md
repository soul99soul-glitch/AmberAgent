# E9 Google 授权只读设计

日期：2026-09-28。范围仅源码、SDK、本地官方文档与官方网页查证；本轮没有产品修改、测试、构建、设备、授权/令牌/推理请求，也没有读取账号配置或真实 secret。Harmony API 基线 12、V1，已读 `harmony-arkts` skill。

## 结论与需要冻结的边界

Vertex 服务账号 JWT → OAuth access token → Gemini REST 的协议与 API 12 签名接口都有依据，可以按既有 HttpClient / Google request / SSE 层补齐。Antigravity 与 Gemini Code Assist 是不同授权身份和 wire mode，不能把旧 `gemini_code_assist_oauth` 静默改成 Antigravity，也不能共用 token store。

Google 当前文档明确：**2026-06-18 起，Code Assist IDE/Gemini CLI 停止为 individuals、Google AI Pro、Ultra tiers 服务；Standard/Enterprise 未受该项停用影响**。旧免费 tier 流程不能作为当前个人账号可用保证。见 [停用公告](https://developers.google.com/gemini-code-assist/docs/deprecations/code-assist-individuals)、[现行 Code Assist 概览](https://docs.cloud.google.com/gemini/docs/codeassist/overview)。

本轮没有证明 Harmony 上的授权 client/redirect 被 Google 接受，也没有证明 daily-cloudcode 的账户可用性。正式 E9 合同应先冻结：是否新增独立 `antigravity_oauth`；旧 Code Assist 是否保留为 Standard/Enterprise 兼容模式；使用哪套具备使用授权的 client 配置及其精确 redirect。缺 client 配置应明确 `client_unavailable`，不能填入假 token、借另一模式或改 UA/endpoint 重试。

## 当前 Harmony 的实际链

| 位置 | 当前事实 | 最小实施影响 |
| --- | --- | --- |
| `chat/google_chat_api.ts:52/81` | Service Account / Code Assist 分支真实 throw；`buildRequest` 同步，`generateText` 与 `streamText` 本身异步 | 两个调用点 await 同一个 request/auth preparation；API key 路径保持现有 roulette 与认证方式 |
| `chat/provider_settings.ts:79/99/210` | enum 只有 api_key、gemini_code_assist_oauth；已有 vertexAI/useServiceAccount/privateKey/email/location/project 字段；hasUsableAuth 主动排除两项 | codec/导入/选择器/运行时 eligibility 一起更新，不能只去掉 throw |
| `chat/provider_settings_serialize.ts:48/172` | enum 白名单不认识 antigravity_oauth；provider blob 明文包含 privateKey | 新 mode 必须进入白名单并有往返覆盖；旧字段迁移不得被 UI 保存清掉 |
| `ChatProviderDetailPage.ets:181/327/1015` | Google 历史 auth 被提示不支持；编辑不回填 email/key；保存强制 api_key、useServiceAccount=false、清 email/key；只显示 API key 与 Vertex toggle | Google 自己的模式/登录状态与 SA 配置表单，按选定模式保存；模型 fetch 也要分路 |
| `di/AppContainer.ets:1578` | 同步 factory 创建 Google API，无异步 Google auth port | factory 可保持同步，注入真实 per-request auth resolver；title、suggestion、memory、子代理调用也自然经过同一个 API |
| `google_request.ts:274/319`、`google_parse.ts:219` | 已有 Code Assist thinking 参数门、SSE `response` 包装解包；非流 parser仍直接读顶层candidates，尚未构造外层 wrapper | 复用消息/工具/SSE 逻辑，补确切外层与非流解包，不造第二个聊天引擎 |
| `ChatKvStore.ets` / `PreferencesStorage.ets` | 普通 Preferences；getSecret/setSecret 也只是 Preferences；Codex/Grok token 当前也落普通 KV | 这是已有事实，不能把其命名当作安全存储证明 |

源码根：`/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony`。上表 `chat/*` 是 `chat/src/main/ets/chat/*`，Entry 文件在 `entry/src/main/ets` 下。

## iOS / Android 对照，按真实实现取舍

- Android `ai/.../google/GoogleGeminiOAuth.kt` 使用 `cloudcode-pa.googleapis.com/v1internal`，独立 Code Assist tokens、PKCE + 外部浏览器 + loopback、load/onboard/LRO。chat wrapper 是 `{model, project, user_prompt_id, request}`。Google 官方 [CLI server](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/server.ts)、[converter](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/converter.ts)、[setup](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/setup.ts) 可查同一路径；这些实现源码不是第三方 REST 稳定性承诺。
- Android `AntigravityOAuth.kt:42/165/275` 是另一 mode：daily-cloudcode、Origin antigravity.google、agent wrapper `{model,project,userAgent,requestType,requestId,request}`；provider UUID 存储隔离；refresh Mutex、logout/login generation 防迟到写回。client 来自 `ai/build.gradle.kts:15/32` 的 Gradle property → env → local.properties，缺配置真实报不可用。本轮仅读 loader 源码，没有打开 local.properties/env 值。
- iOS `IOSAntigravityOAuthClient.swift:31/40` 也使用 daily-cloudcode，但 callback 是 localhost:8085/oauth/callback；Android 是 localhost:51121/oauth-callback。iOS scopes、platform/CLOUD_CODE metadata 与 Android scopes、ANDROID/GEMINI metadata不同。`AntigravityLoginView.swift:57` 实际同时启动 loopback socket 与 ASWebAuthenticationSession，不能相信旧注释“无需 socket”。`IOSGeminiProvider.swift:429` 还给内层 request 加 sessionId。不要把 darwin/arm64 字符串当 Harmony 身份。
- iOS `IOSGeminiProvider.swift:201` 实际仅支持非 Vertex API key 和 Antigravity；Vertex / SA / 旧 Code Assist 不支持。**没有 iOS 已完成 SA 的证据**。SA 协议参照 Android `vertex/ServiceAccountTokenProvider.kt` 与官方文档。
- Android `SettingProviderConfigPage.kt:619/1472` 分开 Code Assist/Antigravity；SA JSON 导入取 project_id/client_email/private_key，另有 location 表单。Harmony 不应只打开旧私钥 @State 就宣布流程完成。

## OAuth 授权、刷新与回调建议

1. 每次登录先有 providerId + 明确 auth mode + client 配置，再生成随机 state、PKCE verifier、S256 challenge；保存 pending attempt 仅内存，超时/取消/页面关闭销毁。外部系统浏览器复用现有 `startAbility(action.viewData, browsable, uri)`，不使用受控 WebMount 登录 Google。
2. callback 严格匹配该 client 的 host/port/path、state、code/error，单次消费；exchange form 包含 code_verifier、原 redirect_uri、该模式 client_id，client_secret 仅按注册 client 要求。刷新保留服务器没返回的 refresh token、project/tier；按 provider+mode single-flight，提前约 2 分钟刷新；invalid_grant 清会话并提示重登。logout / re-login generation 在 refresh/onboard 完成前再次检查，不能复活旧会话。
3. Harmony `module.json5` 目前仅 home skill；EntryAbility 的现有 Want 处理是内容/Novel路由，**没有 Google callback**。如果冻结 loopback，不需要伪造 scheme Want callback；如果冻结注册过的深链则必须补实际 module skill/onNewWant 分发，不能凭空改 redirect。
4. 本地官方 Socket API 给出 `constructTCPSocketServerInstance()`、`listen(NetAddress):Promise<void>`、`on('connect', Callback<TCPSocketConnection>)`（API 10），server `getLocalAddress():Promise<NetAddress>`（API12，实际SDK也核过）。监听仅 loopback，严格有界 HTTP header 解析、正确 path/state、错误回显不带 code/token，成功/取消/超时关闭 connection/server。官方文档称 bind 失败可能随机分配端口；因此固定注册 redirect 必须检查实际 `getLocalAddress()` 的 port，不能仅 listen resolve 就认为正确端口。这里是实施要求，尚未设备实证。
5. Google [native OAuth 文档](https://developers.google.com/identity/protocols/oauth2/native-app)要求 redirect 精确对应注册 client；[loopback 迁移说明](https://developers.google.com/identity/protocols/oauth2/resources/loopback-migration)明确 Android/iOS 类型不再支持 loopback，桌面类型继续支持；[OAuth policy](https://developers.google.com/identity/protocols/oauth2/policies)限制 embedded user-agent、要求适合平台的 client。Harmony 技术上有 socket，不等于借 CLI Desktop client 成为官方支持。不得把 Grok 的手工粘贴重定向 URL/OOB 假定为 Google 可用替代。
6. 官方 [Antigravity CLI 授权指南](https://antigravity.google/docs/cli/install/)覆盖 macOS/Linux/Windows、secure keyring 与远程 SSH 的独立人工 code 流程；本轮未找到向 Harmony/第三方客户端公开 daily-cloudcode/client/header 的稳定授权契约。该 CLI remote 流程不能等同已废弃的通用 Google OOB，也不能猜它的 token endpoint 实现。

## Onboarding 与真实请求

旧 Code Assist：`:loadCodeAssist` 使用对应 metadata，可带用户确实指定的 project；已有 project/tier 可用时保存。没有时按服务器 allowedTiers / eligibility 选择，不猜免费的 tier；FREE onboard 不传 cloudaicompanionProject，其他 tier 需相应 project。`:onboardUser` 返回 LRO，GET `/v1internal/{operationName}`，沿用本仓 5s × 最多12次有界等待，每次检查 signal/generation，done error / 无 project / 资格 validation 明确报错。当前官方 setup 已有 validationRequired / projectRequired，旧源码的“总能 fallback FREE”不能作为当前合同。

Antigravity：同类 load/onboard 形状仅有本地跨平台源码依据，应冻结自己的 route、metadata、agent wrapper、model catalog；不会复制 Code Assist 的 wrapper 或 header 冒充另一产品。OAuth 已登录而 onboarding 失败应显示 project_missing/onboarding_required，保留合法 token 可供显式重试，不能显示 READY。2xx OAuth exchange ≠ 推理可用。

生成前 per-request resolve 真实有效 access token + project；OAuth 用 Bearer、固定该模式 host，禁走 API key roulette/query；SA 用 Bearer 与 project/location 路径，禁写回 apiKey。OAuth非流和每个 SSE data 都只解包一层真实 `response`，保留现有 error/empty-stream/abort 收口。不要因 403/429 自动换 client、端点、tier 或重发一段已消费的生成。

## Vertex RS256 JWT，API 12 可实施路径

配置：projectId、location、client_email、private_key（Google service-account JSON 的 PKCS#8 PEM）；private_key_id 可选，不编造 kid。JSON importer 用 JSON.parse 解析转义一次，legacy单字段输入的 `\\n` 兼容需显式规则；不在原私钥 secret 上整体 trim 后保存。

JWT header `alg=RS256, typ=JWT`，claim `iss=email, scope=https://www.googleapis.com/auth/cloud-platform, aud=https://oauth2.googleapis.com/token, iat=floor(nowMs/1000), exp=iat+3600`；不加用户 delegation sub。UTF-8 JSON → base64url 无 padding → `header.payload` → RSA PKCS#1 v1.5 SHA256 → base64url signature。POST token endpoint，form `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=...`；响应需非空 access_token、Bearer/合理 expires_in，缓存到期前约5分钟重签换 token，**服务账号没有 refresh_token 流程**。字段/算法/一小时上限见 [Google service-account OAuth](https://developers.google.com/identity/protocols/oauth2/service-account)。

Entry 最小 CryptoFramework 顺序：去除确切 BEGIN/END PRIVATE KEY 与 PEM行空白、base64 decode 到 PKCS#8 DER；显式 `DataBlob`；`createAsyKeyGenerator('RSA2048').convertKey(null, priBlob)` → `createSign('RSA2048|PKCS1|SHA256')` → `await init(pair.priKey)` → `await sign(utf8SigningInputBlob)`。每次新的 Sign，不复用已初始化 signer。RSA2048 的转换算法根据输入 DER 的实际规格，不把 keygen 名字当转换后长度验证；是否仅接受 Google常见2048bit输入需合同冻结。保持异步接口；不采用新版本 OnlySign/format API，也不使用 PSS 代替 RS256。

JWT/token cache 按 provider+凭据revision（含 key变化）+scope，换私钥、删除 provider 时失效；并发同凭据 token exchange 合并一次。Android 当前仅 email+scope cache，不应使同 email 换 key 后仍复用旧 credential状态。

REST 为 `https://aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent`，流式 `:streamGenerateContent?alt=sse`；regional host 若采用必须与 location 一致，global 可按官方示例的 global location。project/location/model 是 path segment，校验/编码而非字符串注入。复用现有 Gemini body 与 parser。官方 [quickstart](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/start/quickstart)与[生成参考](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/model-reference/inference)给出 Bearer、project/location、两个方法；旧文档 URL 当前会重定向到 Gemini Enterprise Agent Platform，API hostname仍为 aiplatform.googleapis.com。启用 API、IAM/billing/model地区权限不由本地签名成功证明。

## 存储与最小分工

新增 Google tokens 应独立 providerId+mode namespace，entry专用安全存储；不往 provider.apiKey 复制access token、不把refresh/id token送进普通 provider导出。现成 `SyncCryptoPort.ets`/`AtomicJsonFile.ets` + E2 Asset32byte AES-GCM 私有文件模式可以复用实际 helper，但不能把 SSH绑定codec用于Google。长私钥也不能直接塞受1024byte限制的 Asset SECRET。新加这一份Google存储，不顺手重做Codex/Grok或整个secret架构。旧 privateKey 兼容读取、迁移与备份/export字段如何处理需在E9合同明确，不能静默删除历史凭据或称 Preferences已加密。

| 分工 | 归属与输出 |
| --- | --- |
| Domain owner | provider mode/codec/eligibility、GoogleChatApi async auth port、确切 wrapper/payload/error；只复用现有消息/SSE引擎 |
| Entry auth owner | 具体Google OAuth配置/PKCE/callback/token secure store、刷新/generation/onboarding；独立SA PKCS8→RS256→token provider；复用真实HttpClient/Crypto |
| UI/root | Google模式表单、SA JSON导入与真实状态、系统浏览器、必要callback接线；DI注入auth port，当前辅助生成/子代理无需第二套刷新入口 |

先冻结小接口再并行；每个新文件目标 <1000行，不造统一OAuth framework、不动E7 owner源码。浏览器回调是否官方可用与daily-cloudcode资格是外部 gate，不能由domain fixtures替代。

## 正式实施只需要的验证

- 一组 domain/HTTP-port fixture：API key保留、SA路由/Bearer/JWT字段/expiry、两OAuthmode wrapper不混、onboard LRO错误/取消、刷新single-flight与logout迟到不写回、SSE包装错误。只测实际改动，不重跑全Chat。
- 一组临时 RSA2048 PKCS#8 key + 固定 clock 实签，独立 OpenSSL/Node验签同JWT bytes，篡改失败；若Node shim仅替Crypto则只证明协议，Root一次API12实际Crypto签名验证才证明平台签名。
- callback parser 有界/path/state/重复消费/取消关闭和存储失败不READY合在必要一组；安全store key/私钥静息/篡改由Root统一实际平台fixture，不拿Preferences假仓充数。
- 最后仅 Root 在正式实现授权后统一 compile、真实OAuth登录/刷新/回调、一个非流/流请求与关键设置页图；本报告均未执行，也不声称账号/网络/配额 PASS。

## 官方 API 查证记录

已用仓内 `harmony/scripts/devecocli.sh docs search` 后按完整ID `docs read`：

- `.../ArkTS_API/ohos_security_cryptoFramework_加解密算法库框架_/js-apis-cryptoframework`：convertKey nullable API10；Sign/init/sign API9起，API12对应AsymKey/Signature能力。缓存 `/tmp/e9-crypto-official.txt`，实际SDK d.ts也核过签名。
- `.../签名验签开发指导/使用RSA密钥对_PKCS1模式_签名验签_ArkTS/crypto-rsa-sign-sig-verify-pkcs1`：异步init/sign顺序。缓存 `/tmp/e9-rsa-guide-official.txt`。
- `.../密钥生成和转换规格/非对称密钥生成和转换规格/crypto-asym-key-generation-conversion-spec`：RSA2048规格API9起、转换遵从输入key规格。缓存 `/tmp/e9-rsa-spec-official.txt`。
- `API参考/网络/Network_Kit_网络服务/ArkTS_API/ohos_net_socket_Socket连接_/js-apis-socket`：TCPSocketServer API10。缓存 `/tmp/e9-socket-official.txt`。

只读阶段没有跑上述验证，没有新SDK API凭记忆落产品代码。未取得或推断真实账户 secret；OAuth client源码常量值也未复制进报告。
