# E5 Plugin runner / broker / tools

2026-09-28。责任范围仅新增 `chat/plugins/{runner,broker,tools}.ts`、`plugin_runner.test.ts` 与本报告。未改 shared loop/dispatcher/policy/DI/UI/native，不执行 build/sign/install/device 或 git add/commit。

## 执行链

- `createPluginLoopAdapter({store,installed,js,http,webMount,resolveMcpPrimitive})` 固定本轮 installed 快照。prepare 固定完整 descriptor/inputs/test 或 import preview；已有合法 `plugin_v1` 保留，不重读 live 包。run/test/import 的普通 wrapper.execute 返回 `plugin_adapter_required`，不能旁路 adapter。
- 子调用沿当前 primitive → capture → decide → 保存 exact pending/STARTED → 真 dispatch → 保存已知结果。父批准只进入当前 child，下一 host call 重走权限；父等待 output 为空，metadata 与同批 sibling 不变。ask_user 只复制真实 answered，普通 approved 仍等待答案。
- JS start/reply/reject 只等待下一 event，host_call 保留同 executionId/native 栈，source 不再次 start。reply 前先持久化已知子结果；UTF8 超 64KiB 明确失败，不截断。任何 JS 保存失败或 reply 抛错同步 cancel 原 VM 后原样抛出，保留耐久检查点而不重放。已经 abort 的 ready 调用不 start。每次 JS advance 将本轮 signal relay 到原 executionId 的 cancel，finally detach，批准续跑后 reply 等待也响应当前 Stop。native timeout/timed_out 都记 timeout。
- 私有 Recipe 仅复用既有 create/next/accept，包内 Recipe 不写全局 Recipe Store。固定 Python source 只传现有 python_execute，stdin 为 JSON 输入或 exact string，核 completed/exit_code/stdout 后再完整 JSON/coarse/schema 校验。固定 MCP 用本轮真实身份 resolver 与当前 primitive，通过 dispatcher；不猜 alias、不直呼 manager。
- OpenAPI 派生本轮真实 http_request definition，保留其审批能力，只替换 execute 为固定 url/method/input/domains 的窄 HTTP port。2xx 完整 body 统一解析；mayHaveApplied 映射 outcome_unknown。已返回的 unknown envelope 优先于外层 abort/timeout，catch 保留明确 typed mayHaveApplied/unknown；普通已知成功没有被泛化为 unknown。恢复时所有固定后端 child 的 name/id/JSON args 必须匹配 descriptor/inputs 派生值，失配零副作用。
- broker 按完整 Workspace path segment、网络域及子域、明确支持的 WebMount action 检查，再交既有 host gate。裸 terminal/python/js/http、plugin/Recipe 管理、memory/private/subagent/exec、wm_eval/signed_fetch/site/private 管理不可经 JS/Recipe hostcall 绕过。WM dispatch 使用平台相交 domain gate，open/tab_select 指定 requireCurrent=false；ack 沿原动作语义，不冒称导航完成。
- `pluginURLHost` 用小纯 authority 检查，无 Harmony 不存在的全局 URL；拒 userinfo/backslash/C0/percent host、空/非法 DNS host、非数字或范围外端口，规范小写与末点。path/query 可保留 Unicode 原字符。平台仍用官方 URL API 核对实际请求/redirect。

## 固定试跑、导入、健康与恢复

- plugin_test 支持 directory/archive 恰一来源，必须 expected_candidate_hash；typed deep comparison 区分缺省 expected_result 与显式 null。返回 candidate_test=true / registered=false，不调用 live health 写入。
- plugin_import prepare 只固定 preview；expected_candidate_hash 必填，optional expected_base_hash 存在时精确比较 preview.baseHash（null 明确首装），变化分别 stale_candidate/stale_base。无论自动配置怎样，父 approved 才 apply 固定 preview；Store 最终双 hash CAS。普通父 auto 不继承内部许可。
- live 已知成功/代码 timeout/schema/exception/remote 失败调用唯一 Store 的 id+hash 健康接口，连续3次失败由 Store 隔离。拒绝、scope 不可用、取消与 unknown 不算代码故障；更新/删除旧 hash 的结果不写新版本。候选试跑不登记健康。
- `recoverInterruptedPlugins(conversation,hasJsSession)` 仅给 root 非 active 冷载消费者使用：STARTED 未知副作用，或 JS running/awaiting 已失同 VM，都 finished/outcome_unknown，不重 eval。ready、非 JS pending、同进程 live JS pending 保留；纯函数无 execution/save/health effect，未变化引用与重入结果保持一致。
- tools 提供 list/SDK/validate/test/import、enable/disable/delete/rollback/restore/export 和启用有效 wrapper。生命周期带 expected_hash；导出返回完整 JSON archive + filename。SDK 来自真实支持列表，明确 cooperative abandon/CPU 与唯一 runtime slot 限制，无硬杀承诺。

## 公开接线

- runner：`PluginLoopAdapterDeps`、`createPluginLoopAdapter`、`createPluginRun`、`pluginSourceFromInput`、`recoverInterruptedPlugins`；type re-export `PluginLoopAdapter` / `PluginExecutionPort`。
- tools：`PluginToolDeps`、`createPluginTools`、`getPluginSDK`。
- broker：`pluginURLHost`、`pluginNetworkURLAllowed`、`pluginPrimitiveSupported`、`supportedPluginWebMountActions`、`checkPluginHostCall`、`PluginBrokerError`。validation 直接共享 pure helpers，无第二白名单/URL parser。
- root 接本轮完整 assistant oracle、真实 resolver/dispatcher/capture 与 awaited parent save；目录刷新创建新 factory，但 pinned execution 不换包。冷载只对非 active conversation 组合 Recipe/Plugin recovery；平台后台取消由 native owner/root 负责。

## 必要验证与边界

- 唯一 `plugin_runner.test.ts` **5/5 PASS**，使用真实 PermissionDecisionResolver/AgentToolDispatcher 和 controlled ports：同 JS session 两次独立批准、answered、STARTED before effect、候选 null；能力/scope拒绝、冷恢复与 refs/idempotence；私有 Recipe/Python、3次代码失败；MCP缺scope/固定 HTTP；强制人工 import/防旁路。
- 同组最窄红绿：无全局 URL 时合法域原先被拒；已保存 Python code/OpenAPI method 失配原先执行；已经 aborted 的 ready 原先 js.start=1。修后受影响 focused case 通过，副作用/启动计数为0。pending 与 STARTED 保存故障关闭 gate、0 dispatch；拒绝不记健康；optional base hash 变化0 apply。
- 独立只读审查回喂 HTTP P1：mutating POST 取消/超时返回 mayHaveApplied 后原来被外层改成 cancelled/failed。仅在既有 remote fixture 补两条，取消先红（实际 cancelled），修后取消与25ms timeout均 outcome_unknown / health0，受影响 focused 1/1 PASS。没有重跑全 suite 或扩大 fixture 组。
- 审批续跑当前 signal 接缝在既有 JS fixture 用 reply barrier 复现：新一轮 Stop 原先不 cancel 初次 start 的原生会话，50ms 观察边界内未结算；补每 advance relay 后 focused 1/1 PASS、start仍1次。fixture 观察定时器仅用于证明控制流，没有产品 idle timeout。HTTP 修复已获另一 reviewer 只读闭环确认，signal 修复由原 reviewer 复核。
- 全 chat strict `tsc -p tsconfig.json --noEmit` 最后在 resumed signal 修复后 exit0。三文件窄 Harmony lint exit0、0 error / 751 warn；最终仅改动 runner 再 lint exit0、0 error / 584 warn，原始 `/tmp/e5-plugin-runner-lint.txt` 与 `/tmp/e5-plugin-runner-final-lint.txt`。warnings 为该工具对 TS import 的 unsafe 类型提示、JSON deep clone 等，未声称0 warning。
- root-loop owner 已独立报告唯一2场景 **2/2 PASS**（`E5-loop-fixture.md`），包括真实 tool_loop/dispatcher、MCP identity碰撞、目录刷新；未在本 owner 重跑。controlled JS/HTTP/Store 不能证明 native JSVM、真实服务器、平台原子文件安装或设备验收。
- 最终源码约453/80/114行，无第二 scheduler/ledger/permission protocol、自动重试或源代码重播。实际 build/sign/install/设备与完整 shared 接线验收留给 root。
