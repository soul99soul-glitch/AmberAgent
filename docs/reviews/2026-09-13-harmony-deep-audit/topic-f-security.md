# 专题 F：安全/信任边界与 WebMount、MiniApp、归档

主审：主代理。状态：关键实现已读、跨入口链已追踪、WebMount三项隔离实证；设备和真实服务未验证。最终结论以总报告及独立 review-df.md 为准。

## F-01 — wm_click 参数可执行任意脚本，绕开 wm_eval 开关与强制审批（候选 P1，高置信度）

- 位置：`harmony/entry/src/main/ets/platform_impl/WebMountTools.ets:120-143`，尤其135行error字符串直接插入target；`196-206`为wm_eval强制批准对照；`455-459`关闭includeEval只移除eval。
- 调用链：ChatPage:2971-2973创建工具 → tool_policy:473-479把click设为sensitive/mutates但autoApprovable=true → tool_permission:256-258单开autoApproveTools即允许 → click.execute → evalToPart → WebMountSession.evalJson:181-185 → runJavaScript。
- 触发：WebMount启用，恶意网页或模型输出构造CSS目标参数。合法CSS属性选择器内包含单引号表达式；querySelector未找到元素时error分支求值插入表达式。攻击不需要wm_eval暴露。若自动批准全部工具关闭，用户仍可能以“点击”而非执行脚本语义授权一次；单开时无此交互。
- 已验证：`probe-f-webmount.cjs`直接转译并调用当前WebMountTools.ets真实execute，替换平台端口为VM，未复制业务实现；includeEval=false仍可将合成window.__audit由0写为1；click.allowsAutoApproval=true/mandatoryApproval=false。D负责人补真实policy/permission probe证明单开可达。run-trust分支没有生产赋值入口，**不**作为攻击路径。
- 保护不足：selector在querySelector参数中JSON编码，但同一个target又进入单引号error文案，第二处绕过编码。wm_eval mandatoryApproval无法约束另一个工具内部的脚本汇点。双开无人值守是有意设计，不作为漏洞依据。
- 可达性限制：F-03导致当前首次wm_open失败，且当前整个工作树无法编译。故已证实的是工具执行边界缺陷，不宣称设备端外泄实证；挂载修好后不得单独放行此安全项。初始about:blank可就绪时click路径也应由设备确认。需独立复核是否在最终报告保留为“潜伏已证实”或降为待验证。
- 最小修复：所有动态值仅经JSON参数编码，不拼入脚本文本；click审批至少恢复mandatory/no-auto合同；回归需对含单双引号、反斜杠、换行及CSS escape的参数断言无法执行额外表达式，同时关闭eval测试。
- 根因：代码生成中的不可信参数边界，与F-02 URL解析不同；D-01审批降级合并至本项，不重复计数。

## F-02 — 站点白名单把 URL userinfo 当 hostname（候选 P1，高置信度）

- 位置：`WebMountSites.ets:73-88`；`WebMountTools.ets:83-92`。hostOf用正则取authority后split(':')[0]，而真实URL主机由最后一个@后的authority决定。
- 最小复现：合成已授权github.com站点，对包含github.com用户名、口令分隔符及outside.invalid主机的URL调用真实siteAllowsUrl，返回true；标准URL.hostname为outside.invalid。探针只比较解析器，不发请求。
- 影响：站点页承诺“仅授权站点”可被URL参数绕过；实际能导航后即可读取或操作未授权站点。原始URL未规范化直接传loadUrl。
- 现有保护：站点精确/子域匹配对错误host无效；没有逐导航/重定向拦截，extract/type/click也不重新核验实际location。F-03当前遮蔽首次导航，所以无设备端利用证据，不夸大为已外泄。
- 建议：使用标准URL解析并限定http(s)，拒绝userinfo、异常端口/编码；实际导航及重定向逐跳授权，revoke后重新核验当前页面。回归覆盖userinfo、IPv6、大小写、非HTTP scheme及重定向。
- 另一个同授权根因表现：WebMountHost.onPageEnd(:234-236)使用session.getUrl而非事件真实URL，跟随链接后元数据/授权仍为旧地址。缺少真实Web运行证据，记为同项待验证扩展而不再计数。

## F-03 — WebMount 从未标记宿主已挂载，首次打开恒超时（P1，高置信度）

- 位置：`WebMountSession.ets:35,109-111,139-153,224-237`；`ChatPage.ets:875-877,4456`。
- 静态证明：hostMounted初值false，唯一setter markHostMounted全仓只有定义，没有调用。open触发hook，ChatPage仅将visible设true；WebMountHost.aboutToAppear只attachUiContext，Web.onPageEnd只markPageEnd，并不置hostMounted。50×100ms后必抛WebMount host not mounted，loadUrl未执行。
- 已验证：`probe-f-mount.cjs`转译真实Session class（只略去TypeScript不能解析的ArkUI struct尾部），加速假时钟、mock controller。50tick、hook1次、loadUrl0次、按预期拒绝，exit0。真实5秒/ArkUI显示未实测，但上述状态不可达是确定性代码证明。
- 影响：启用WebMount后wm_open核心旅程不可用；用户看见工具错误而不能打开授权站点。
- 修复：在controller真正attached时置就绪，并在宿主销毁时解除hooks/context、清理state；等待应绑定本次导航而非任意pageEnd。测试首次挂载/重入/退出重开/导航结束顺序。
- 同域低影响残余：waitForPageEnd保存wrapped却用indexOf(resolve)删除(:125-134)，超时waiter不移除；reload超时仍回报reloaded。挂载问题修复后需覆盖，未单列高风险。

## 其他实读范围与待验证风险

|区域|已读关键实现与链路|结论/边界|
|---|---|---|
|OAuth|webmount_oauth.ts全文、WebMountOAuthSupport全文、SettingExperimentalWebMountPage授权/兑换/注销链|PKCE随机verifier/state与挑战、手动粘贴有state则匹配；裸code仍受PKCE，不直接报CSRF。token不会因存在就证明WebView登录。random API错误由构建项统一。无真实token/回调交换|
|MiniApp bridge|MiniAppBridgeImpl全文、RunnerPage全文、sandbox/runtime/network_policy/html_validator/shell、repository shared namespace保护|每请求token+declared/global/deny grant；敏感context/launch二次确认、close后confirm复查；sharedStore底层限制namespace，排除“bridge不校验所以跨app读取”假阳性；CSP connect-src none与native权限门，不能只用HTML regex绕过推断外泄|
|MiniApp网络|RCP bridge逐跳checkUrl与manual redirect；Runner图片预取单次imageGuard.check后NetworkKit.request|图片路径没有逐重定向校验，可能公网图片302到私网；SDK里maxRedirects从API23才有、redirect interceptor API22；缺API12运行证明，列待验证，不混入确认漏洞。DNS解析再HTTP解析未钉住地址：DNS rebinding待设备/受控双解析证据|
|MiniApp稳定性|postMessage串行队列、prefetch 32URLs/4workers/8MiB预算、fetchBytes上限|已有限额但网络体先完整收后检查、bridge queue无入队长度与速率门。只能证明缺控制，未测平台实际峰值；大响应OOM/队列卡死列待验证，不直接声称崩溃。页面预取成功refresh会重跑应用脚本，初始化写副作用可能重复，需实页证据|
|归档/技能|workspace_artifacts safeArchiveTarget/readTarHeader/extractArchive/createZip/http；skills path resolve/unzip/collect；ArtifactPorts HTTP；EntryDocumentParser inflate上限|zip单条64MiB/总量限制、stored长度检查存在，旧Zip Slip '..'被normalize拒绝；技能4MiB单文件限制已修。技能多条目累积Map无总量限制，归档输入全读/HTTP chunked缺累计截断为性能盲区，未实际分配大文件|
|密钥/日志|仅代码路径检查，不打开真实密钥文件|轮换LRU以apiKey作为JSON key落cache事实成立，但沙箱明文不是自动泄露证明；无外部可读攻击路径则不正式报告。build-profile可能包含签名密码字段，未展示或验证值真实性，隔离probe仅用合成字符串|

## 验证材料

- `node docs/reviews/2026-09-13-harmony-deep-audit/probe-f-webmount.cjs`：exit0，脚本注入/URL gate两断言通过。
- `node docs/reviews/2026-09-13-harmony-deep-audit/probe-f-mount.cjs`：exit0，50次挂载轮询后失败。
- 不启动浏览器访问目标，不执行真实fetch，不读cookie/storage，不调用付费模型。
