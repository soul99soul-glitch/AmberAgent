# E9 gate: Google authentication identities and actual request chain

2026-09-28，基点 `189eec7bd`。源实现、独立 A/B 与实际 Release/签名已收口；最终回执如下。设备、真实 Google 账号与平台安全 API 的 C gate 保留 pending；没有新增设备恢复、模拟器矩阵或外网认证请求。

## 已落实范围

- Gemini/Vertex API Key 保留原传输及模型列表。Code Assist 与 Antigravity 使用独立身份、host、wrapper、配置及令牌 namespace；明确自有 client/注册 redirect 与真实资格/onboarding 边界，没有借用其它平台客户端或换 host/tier 重试。
- Service Account PKCS8 私钥引用、API12 RS256 JWT、单次请求鉴权与令牌缓存已贯通原 Google API。每次请求保留原 signal；非流与 SSE 解包保留真实错误，不重发已输出正文。
- 新 private key/client secret 与 OAuth token 通过独立 Asset key + AES-GCM 文件仓保存。旧 Preferences 明文仅显式成功保存后迁移。公开导出/导入不携带秘密或外来本地引用；模式与公开客户端字段保留。
- OAuth PKCE/state、native API12 loopback 与精确 callback 校验、取消/超时关闭、refresh/onboarding single-flight、generation/write-tail barrier 接通。Root 复用原 HttpClient 初始化并注入原 provider factory，不造第二生成引擎或共享 OAuth 框架。
- 保存先 stage、再 provider JSON commit、后 owned cleanup；失败保原引用，后置失败准确说明已保存。真实配置提交/显式 begin 可以更新绑定，普通旧会话快照不能替换或取消新登录。删除先提交 provider 移除，再取消两个身份并清所属仓。
- 新认证页与已有详情/列表/路由接通，实际配置态、授权中、待刷新、onboarding、ready、失败分别展示；Code Assist 未核模型目录保持真实手填入口。统一主题、44vp 操作区、长文换行、安全区/间距已源审查，未冒称设备视觉 PASS。

## 已确认问题的局部修复

- 独立 B 的保存期间返回旧详情再保存会恢复已删除 secret ref（P1）：保存 busy 消费硬返回/忽略标题返回；OAuth 自身仍能明确取消离开。
- 无条件安全存储说明（P2）：区分新 SA/OAuth 安全仓与 API Key 原配置仓、显式 legacy 迁移。
- 独立 A 的普通 API Key 模型目录被新 OAuth/SA 分支截断（P2）：限定新 catalog 分支，恢复旧 URL/header/parser。
- 独立 A 的 E6 旧 client 快照取消新 client 登录（P2）：只真实 save/显式 begin 拥有重绑定权限，普通读取/请求遇到旧 binding 明确拒绝；旧绑定 logout 只做 owned remove，不重绑新状态。同一生命周期组具体 red→green。

## 必要证据

- 唯一域层 HTTP/codec 组 10/10 PASS，覆盖实际路由/wrapper/signal/nested error/SSE 和 sanitized import；不是外网账户接受。
- 唯一 Entry 生命周期/callback 组初 1/1，具体 stale-binding 反例 red→green 1/1；实际平台端口受控，不是设备 OAuth PASS。
- 唯一临时真实 RSA 签名组 1/1，实际源经 Node CryptoFramework adapter 与独立 OpenSSL 验签/篡改拒绝；不是 API12设备 Crypto PASS。
- 唯一 native host TCP group PASS，固定端口占用拒绝、真实 GET/固定响应、未完成连接取消与原端口重绑；API12 SDK 两 native 文件 strict syntax PASS。host 不证明 NAPI env cleanup 或设备外部浏览器。
- Domain tsc/secure/config helper 窄 SDK types PASS；各 owned 窄 lint 0error，B/C/D NoDefects。Root 五接线文件 `/tmp/e9-root-lint.log` 0error/19跨文件解析 warnings。
- 首次实际 Release `/tmp/e9-release-build.log` 暴露 23 条具体 ArkTS 类型诊断（汇总24），涉及 Record literal、跨域 Error/原生类型、indexed phase 和 catch 类型。按受影响 owner 机械修复，没有为编译器改认证语义或加防御路径。最终编译/签名待以下回执。

## 最终 artifact / C gate

最终 source freeze 与独立 A/B PASS，实际 Release/本地验签 PASS；回执如下。HDC 已记录离线，不重复 polling。C pending：原 AGC 签名安装启动、API12 Asset/Crypto 和 loopback/browser callback、自有客户端/账号资格、真实 OAuth/SA 流与非流请求、关键配置页亮暗视觉。局部 Test-CA 包仅验签，不能安装到原签名设备。源 checkpoint 后继续 E10，保留实际未验层。

### Root 最终实际回执

第二次 `/tmp/e9-release-final-build.log` **Release 10.586s PASS**，实际 generated BuildProfile 为 release / DEBUG=false。`/tmp/e9-release-final-sign.log` exit0、Digest verify=true、verify-app success，未安装。原有 app-symbol symlink/zip warnings保留，未顺手清理；原生新模块已被本次实际构建链接。D 四条 catch 类型、B 十九条类型限制已机械修复，B 原必要生命周期组受影响复跑 1/1（`/tmp/e9-google-entry-compiler-repair-green.log`），没有新组。B SDK types0/affected lint NoDefects，D final helper types0/affected lint NoDefects。独立报告最终收口后作为本阶段 source checkpoint，不把上述 PASS 替代 C gate。

独立 A `E9-logic-review.md` 与 B `E9-ui-call-review.md` 最终源码/调用链 PASS，所有已确认 P1/P2 精确关闭，无新确认缺陷。各 owner 报告已冻结；未追加测试、设备或外网账户调用。继续 E10。
