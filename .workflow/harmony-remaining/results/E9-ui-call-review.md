# E9 独立 B UI / 全调用链只读审查

2026-09-28，基点 `189eec7bd`。已读 harmony-arkts skill、E9 frozen contract/design、D 三文件最终源、domain/import/export、列表状态、DI 和冻结后的 AuthSupport 调用链。仅写本报告，没有修改产品/测试，没有运行测试、lint、构建、签名、设备或真实认证请求。

**最终源码结论：本审查的保存返回 P1 与存储文案 P2，以及 A 提出的 API-key catalog 回归均已窄修并复核关闭。已按 root 最终冻结通知补审鉴权接线与实际 compiler 类型修正，本范围无剩余已确认 P1/P2。设备 C gate 仍 pending。**

## 具体发现与修后复核

1. **P1：保存期间返回，详情页能用旧凭据引用覆盖新提交，已关闭（源码）。** 原 GoogleProviderAuthPage 保存 `:185–199` 同步置 busy 后 await helper；标题 back 与 hardware back 却允许离开。helper fresh lookup `:53` 早于安全 stage `:76/82`。用户返回后 Detail `:154–157` 可先读 prior，helper 随后提交新 ref 并清旧 ref；Detail save `:392–411` 用 prior 构造 updated，即使 `:446–450` 再读全列表也整替换为旧 updated，会恢复已删除的旧 ref。D 窄修 AuthPage `:69–73/287–291`：busy 时 hardware back 消费、标题 back 忽略；OAuth authBusy 不是该门，仍可显式取消后离开。没有新增 provider 锁或框架。导入 stage 在 alive=false 时原本 `:167–169` 会回收新 ref，未把它冒称同一 metadata commit 缺陷。
2. **P2：无条件声称全部凭据仅存安全仓，已关闭（源码）。** 原页说明将 API Key 和迁移前 legacy privateKey 也称为“仅本机安全存储”，与实际 providers Preferences 及合同不符。D 当前 `:341` 明确“账号授权与新服务账号私钥…；API Key 保留原配置存储”，`:350` 说明旧明文仅显式成功保存后迁移。没有把既有 Preferences blob 宣称为加密。
3. **A 提出的 API-key catalog P2 追加复核关闭（源码）。** Detail `:265` 仅 Google OAuth/SA 进入新 catalog；`:289–290` 恢复原 Google API-key header，`:295/304` 保留 baseUrl/models 和原 models[].name parser。OAuth/SA 不用 API Key 替代其真实目录身份，Code Assist unsupported 仍显示原因并保留手动 ID 输入。Vertex API-key 只保留原行为，没有扩大目录支持或据源码称为远程验证成功。

root 提到的 incoming stage ref 早期失败窗口，当前 ConfigSupport `:53–61` 已在 loadProviders 失败/目标消失时回收 incoming privateKeyRef；Page `:196` 移交后能由 helper 覆盖。这不是当前未修问题，不按旧草稿重复登记。

## 已核 UI / 配置链

- Detail 新入口先检查 provider 实际已保存，传真实 providerId；new page fresh lookup 精确找到 Google setting。main_pages 已登记实际页面，不给未保存 PENDING_PROVIDER 猜授权身份。
- 模式区分 api_key/service_account/CodeAssist/Antigravity；API Key 原表单保留。新页保存只改变鉴权相关字段，复制 fresh provider 的 models/其它字段。旧 Detail 普通保存保留实际 authMode、useServiceAccount、legacy/privateKeyRef、email 和 oauthClient*，不清成 API Key；返回刷新真实 Google 配置引用与有变化的相关表单字段。
- 导入只解析实际 Google JSON 的 type/project_id/client_email/PKCS#8 private_key，文件限 128KiB；private key 原样 stage，页面只保 pending ref，不置入 State/可见文本。Client Secret 使用 FlatTextField obscure→InputType.Password，留空保留旧 ref，可显式移除。
- 保存 stage→provider JSON commit→仅 superseded owned cleanup；失败回收新 staged refs，旧 metadata/secret 保持。commit 后清理失败返回“配置已保存，但旧凭据清理失败”，UI 不谎称未保存或回滚。未保存导入 ref 在离开/取消清理，save 移交后页面不误删提交中的 ref。
- OAuth login 只使用已保存 setting；dirty 先保存，字段/模式变化取消 attempt 与 retry controller。completion handler 在 startAbility 前安装；浏览器启动失败取消，迟到 begin 返回按 attemptSeq/alive 取消；外部浏览器只暂时后台，没有 onPageHide 自取消。明确 back/cancel/destroy/logout 关闭本页 attempt，retry 同样有 EntryAbortController。
- onPageShow 只 refreshStatus，不重发 login。配置、签出、授权中、onboarding_required、READY、错误分别消费真实 status；保存配置不称服务器已授权；retry/validation URL 是实际按钮，Code Assist 资格边界有说明，不声称免费 tier 必成功。
- Detail 导出只公开 mode/email/project/location/clientId/redirect 与最小 model metadata；不含 API Key/privateKey/privateKeyRef/client secret/ref/tokens 或秘密 overwrite。import 调 makers 生成新 Provider/model UUID，只复制上述公开字段，外来 local refs/secret 字段不进入 maker；导入后明确需重新配置凭据。
- Detail 删除先读取 fresh 删除对象并提交 JSON 移除，成功后才调用 cleanupDeletedGoogleProvider。helper 调 forgetGoogleProvider 并清当前 bound key/client-secret refs；后置清理失败明确“服务商已删除”，不恢复已删除 source、不删其他 Provider。
- root buildContainer 使用原 HttpClient 初始化 AuthSupport 一次；buildProviderApi Google 分支注入 resolveEntryGoogleAuth，任务、Chat、子代理仍经过同工厂。native callback 已在同 libamber_native 注册/声明，不造第二 HttpClient 或反向 Auth→DI 依赖。
- 列表先配置态、后 loadGoogleAuthStatus 实际态；row viewVersion 有意进入 ForEach key 让异步状态重建。配置不等于 READY，SA configured 与 OAuth ready/signed_out/onboarding 等徽标分开。

## 鉴权更新最终差异已核对

- ConfigSupport `:106–125` 在 JSON commit 成功、rollback catch 之外，顺序 await 实际 updateGoogleAuthConfiguration(updated)，再收回 superseded refs/注销 prior。hook 或后置清理失败仍返回真实 updated setting 与“配置已保存”说明，不回滚源、不删除 committed 新 ref。
- AuthSupport `:61–76/297–302/382` 普通 lookup 遇不同 binding 报 credential_changed；显式配置更新才可 replace authority，并等待旧 writeTail/callback close。旧 detached run 不因调用 resolver 将新 binding 切回。ordinary logout `:321–325` 对不匹配的 prior 只在 tail 后删除旧绑定 token，不 invalidate 新登录态；`forget :335–349` 两 mode 同时 invalidate，关 callback、等各 writeTail 后清 secure namespaces，SA cache 同 provider 清理。
- 列表 `:115–117/178–191` 以真实 status.mode 区分 service_account configured 与 OAuth configured，分别显示“SA 已配置 / 待刷新”；READY 才“已授权”。API-key catalog 修正见发现 3。
- 最后实际 compiler 修正已只读复核：ConfigSupport `:56/104` 的 Error cast、Detail `:277` 与 AuthPage `:236` 的 catch 参数类型、Callback 的实际 native named ABI types 和 Error casts、AuthSupport 表单 Record 的 typed temporary / phase union，均保留原控制流与错误对象身份，没有引入新的回退/调度层。root 已通知 B source 再冻结，本报告据冻结源码收口，没有新增测试/设备矩阵。

## 布局与证据边界

新页 V1 State、说明/状态全文换行，统一 outer 16vp gutter；长 Client ID/Redirect/email/project 是实际 editable fields。按钮复用 44vp minHeight，标题返回 48vp，滚动内容尾部沿 PageSpacer 40vp，系统安全区沿现有标题栏/expandSafeArea；秘密输入 Password，私钥永不回填。新说明没有固定高长文本容器，已用本页 note/section 避免双重内缩。

页面和既有组件使用当前主题 tokens；本次没有设备截图，不能证明亮暗即时刷新、窄屏长名称、IME/底部安全区或点击实拍。D 所有 lint/types/Root compiler/签名与 controlled auth/native fixture 是不同证据；本报告不称自己执行过，也不将其当作真实 Google 账号、浏览器 callback、API12 Crypto/Asset 或推理接受。**无视觉/真实账户 PASS。**
