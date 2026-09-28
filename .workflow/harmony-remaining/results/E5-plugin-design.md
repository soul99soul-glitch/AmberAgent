# E5 动态插件：只读设计与冻结建议

2026-09-28。只修改本报告，未执行测试、构建或设备操作。当前 E5 范围依据 `docs/plans/2026-09-28-harmony-remaining-execution.md:38`；不得用纯 JS/Recipe 子集宣称整个批次完成。

**目标闭环：创建 Workspace 包 → SDK/校验 → 固定内容真实试运行及 expected_result → 固定 hash 的批准导入/启用 → 下一模型轮发现 plugin__id__tool → 同一 backend 与逐次 primitive 审批 → 结果、启停卸载、更新/回退、信任与诊断在插件中心可见。** 插件是独立多文件、多工具包，不把 Recipes 页面换名字，不把插件成员另装为全局 Recipe。

## 1. 真实参考与当前可复用模块

路径缩写：I=`/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/`；H=`/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/`。不存在名为 IOSDynamicPlugin 的源码；实际插件代码分布如下。

| 真实来源 | 本阶段应保留的合约 |
| --- | --- |
| iOS 根 `DYNAMIC_PLUGIN_IMPLEMENTATION_PLAN.md` | 四种 handler、能力代理、试运行不注册、hash CAS、启停更新回退、归档/签名、诊断与中心；不存在服务端就不虚构市场。 |
| I`IOSRecipeModels.swift:601/648/818/907` | `amber.plugin.v1`、多工具命名、manifest 校验及实际 primitive 派生权限包络。 |
| I`IOSPluginJSONSchema.swift:3/57/114/221` | 有界 JSON Schema；旧三类型 inputs 全必填、新 input_schema 支持对象/数组/可选项，不吞未知 schema 关键字。 |
| I`IOSRecipeFileStore.swift:755/1186/1517` | 安全 JSON 归档、独立 plugins store、逐文件及整包 hash、候选/基线 CAS。 |
| I`IOSRecipeTools.swift:812/832/864`、`IOSPluginDevelopment.swift:3` | prepare 固定候选/输入/expected_result；测试结果带 candidate_hash；导入批准后重读双 hash，enable=true 合并安装启用。 |
| I`ChatToolRuntime.swift:3432/3579/3884` | 测试与安装调用同一 pinned descriptor/runtime；不是校验成功就算测试成功；副作用仍有审批。 |
| I`IOSJsSandboxEngine.swift:142/226/286/319` | fresh JSC VM、同步 tools 桥、取消/超时 abandon 且关闭宿主调用门；构造器仍是语言 intrinsic，安全边界不是字符串黑名单。 |
| I`IOSPluginCommand.swift:155/228` | 固定源、数据与代码分离、实际 runtime stdout/退出态解析；非零/取消/截断/未知均失败。 |
| I`IOSRecipeFileStore.swift:900/931`、`RecipesView.swift:689/1104/1260` | 包版本独立健康记录、连续失败3次隔离、最多50条500字符诊断；真实中心/详情/能力/信任/生命周期。 |
| H`chat/src/main/ets/chat/recipes/runner.ts:69/107/141` | 可复用 createRecipeRun/nextRecipeStep/acceptRecipeStep；独立插件 checkpoint 包含私有 workflow 状态，原 Recipe 合约保持。 |
| H`chat/src/main/ets/chat/recipe_loop.ts:37` | 保存精确子 ToolPart，再 capture/dispatcher.resolveDecision/execute；作为插件宿主 primitive 桥的已验证模式。 |
| H`entry/src/main/ets/pages/ChatPage.ets:3868` | registry/catalog/refreshTools 已随 E4 接好；插件目录合入同一刷新，不另建曝光目录。 |
| H`entry/src/main/ets/platform_impl/RecipePageLaunch.ets:20` | 实际助手新会话、真实 parent ToolPart、await repository.save、手动 continuation；插件中心试运行/运行沿此入口形态。 |
| H`entry/src/main/ets/platform_impl/SkillStore.ets:33` | 可参考文件 API 与原子目录替换；Skill 是提示/文件包，无 executable descriptor，不能复用其宽松 walk 充当插件验证。 |

## 2. Manifest 与内容身份必须先冻结

- 保留 iOS wire 名：顶层必需 `schema,id,name,version,description,tools,capabilities,backgroundAllowed`，可选 directory；每个工具恰好一个 `recipe/script/remote/command`，不新造通用 plugin DSL。
- id 为 `^[a-z][a-z0-9_]{1,31}$`，工具名为 `^[a-z][a-z0-9_]{0,31}$`，两者禁止 `__`；注册名固定 `plugin__<id>__<tool>`。版本非空无空白，display name 非空≤80字符，description 非空。工具重复或与现有 registry 冲突，零注册/零写入。
- 工具 fields：`description? / host_tools? / inputs? / input_schema? / output? / output_schema? / timeout_ms? / max_output_chars?`。旧 inputs 默认空；output 默认 json；timeout 默认10000，JS/remote为1000–30000；output字符预算默认10000、1000–32000；Python上限按本端实际60000，不虚构 iOS 的180000。
- Schema 词汇只支持 `type,properties,required,items,additionalProperties,enum,description`；type 包括 integer/null。input_schema 根明确 object/properties，不与非空 inputs 混用；输入及输出同一 validator 校验，参数的 display_title 在严格校验前移除。
- Recipe handler 引用 `recipes/<name>.json`，输入/输出/primitive 来自该文件，不同时允许工具自报 inputs/host_tools/schema。原 Recipe 禁 recipe__/plugin__ 递归规则保留。
- JS handler 引用 `scripts/<name>.js`；源码是同步函数体，收到 input，return JSON值；`tools.<declared_name>(args)` 同步桥，不接受 Promise 作为成功 JSON。
- command 固定 `runtime/entry/stdin_input?`；本端明确支持 `runtime='embedded_python'`、`scripts/*.py`，要求 capabilities.localRuntimes 包含它。`ambershell/ish` 是 iOS runtime 名，不能自动映射成 SSH/本机 Python。SSH command 若加入须独立 profile 绑定/whole-runtime授权与真实执行子阶段，不是隐式 fallback。
- remote 保留 iOS `kind='mcp'|'openapi'`。MCP 固定已有 server/tool，不让输入覆盖；OpenAPI 固定能力域内 HTTPS URL 与大写 GET/HEAD/POST/PUT/PATCH/DELETE，GET/HEAD 参数为 query，其他为 JSON body。
- capabilities 保留 `workspaceReadPrefixes/workspaceWritePrefixes/networkDomains/webMountActions/localRuntimes`。只接受可执行、可校验能力，权限由解析后的成员与现行 primitive policy 派生，manifest 无降低风险字段。
- 包为 `plugin.json`、`README.md?`、`recipes/*.json`、`scripts/*.js/*.py/*.sh`、`assets/**`；路径相对规范、NFC、无绝对路径/空段/./../反斜线/冒号/控制字符/点开头段；拒绝符号链接和非普通文件。assets 不自动挂载进执行器。
- iOS 限额：32文件、每文件256KiB、合计1MiB；归档≤2MiB。读取前检查 size，读取后再核对；有界遍历，不先读全目录再拒绝。
- canonical：从 decoded schema 重建 plugin.json；Recipe 同原 canonical；脚本/README/assets 保留实际字节。整包 `SHA256(UTF8('amber.plugin.package.v1\0') + 每个排序path的 uint64BE(pathUTF8长度)+pathUTF8+uint64BE(file长度)+fileBytes)`，另存各文件 SHA256。不是只 hash manifest，更不是仅 hash 文件路径。
- 至少冻结一份跨端 canonical/hash fixture，才能声称 Swift/JS兼容；`embedded_python` 是显式平台扩展，SDK必须给实际 manifest示例。

## 3. Store/试运行/批准安装合约

```ts
interface PluginPackage { manifest: PluginManifest; hash: string; files: PluginFile[];
  fileHashes: Record<string,string>; tools: PluginDescriptor[]; envelope: PluginEnvelope; }
interface PluginImportPreview { source: PluginSource; candidate: PluginPackage;
  baseHash: string|null; permissionExpanded: boolean; trust: PluginTrust; enable: boolean; }
interface PluginTestPlan { descriptor: PluginDescriptor; candidateHash: string;
  inputs: JsonObject; expectedProvided: boolean; expectedResult: JsonValue; }
interface PluginStore {
  listInstalled(): Promise<InstalledPlugin[]>;
  prepareImport(source: PluginSource, primitives: AgentTool[], enable: boolean): Promise<PluginImportPreview>;
  applyImport(preview: PluginImportPreview, primitives: AgentTool[]): Promise<PluginReceipt>;
  setEnabled(id: string, expectedHash: string, enabled: boolean): Promise<void>;
  remove(id: string, expectedHash: string): Promise<void>;
  rollback(id: string, expectedCurrentHash: string): Promise<PluginReceipt>;
}
interface PluginLoopAdapter {
  supports(name: string): boolean; // 仅 plugin_import/plugin_test/plugin__*
  prepare(parent: UIMessagePartTool, primitives: AgentTool[]): Promise<UIMessagePartTool>;
  advance(parent: UIMessagePartTool, port: PluginExecutionPort, signal?: AbortSignalLike): Promise<UIMessagePartTool>;
}
```

- `PluginExecutionPort` 结构直接复用现有 `recipes/ports.ts:31` 的 primitive/capture/decide/dispatch/saveParent，不新增第二套权限resolver。其他DTO在model owner文件定义；descriptor需含owning pluginId/packageHash、固定源/recipe/remote、输入输出schema与派生envelope。
- Store 独立 `filesDir/plugins`，用已有 sharedKvMutex 和原子文件写；整包先 stage 后 promote，不逐文件覆盖 live。可用内容 hash 目录+一个原子 current/enabled 指针提交，上一版仅一槽；需要检查维护成本后由 store owner固定，勿引入通用 artifact ledger。
- 插件包读取不能直接借Skill宽松walk；官方离线 js-apis-file-fs 的fileIo.lstatSync/Stat.isSymbolicLink及SDK标注API9可拒绝link，listFileSync的listNum标注API11可有界列举。目录各级均检查，权限/IO错误不得吞成空包。
- prepare 返回候选内容、基线 hash、能力差异、启用意图与信任；apply 在同id锁内重新读取候选并核对 candidate/base hash。更新权限扩大明确重新批准；新包默认停用，enable=true 审批文案明示安装并启用，不静默改意图。
- `plugin_import` mandatory approval，即便高风险自动批准设置开启也不能 self-approve。生命周期用 expected_hash；批准 UI 与工具同 Store，不单独 execute/walk。
- `plugin_test {workspace_directory,tool,inputs,expected_result?}` 固定候选 descriptor、输入、expected-result 后，运行**同安装态 adapter/backend**。不得注册/启用/覆盖健康记录。expected_result=null 与未提供区别保留。
- JSON 深比较：对象键顺序无关，数组顺序/类型/值精确；不 trim、强制字符串化或容差猜测。只比较成功的实际 `result/outputs`；不匹配返回 `test_failed/expected_match=false`，不能仍说测试通过。
- 返回 `candidate_hash,candidate_test=true,registered=false,expected_match?`；后续 `plugin_import {expected_candidate_hash,enable?}` 必须匹配同一包，试跑后改任何脚本/asset都拒绝。试跑本身的副作用仍需正常审批。
- 管理工具 `plugins_list/plugin_sdk/plugin_validate/plugin_test/plugin_import/plugin_enable/plugin_disable/plugin_delete/plugin_rollback/plugin_restore/plugin_export`；list/SDK/validate只读，其余按实际行为批准。SDK说明真实 runtime、限制、精确示例，不把研究结论包装成已可调用能力。
- checkpoint 用 parent.metadata 单键 `plugin_v1`：固定 package hash/descriptor/input/test context、executionId、backend状态、pending精确子ToolPart。原生句柄、MCP凭据、SSH秘密不入 checkpoint；目录更新/停用/删除只影响新调用，在途用固定内容收口。

## 4. JSVM 官方证据与真正 JS backend

**不要复用现有 WebView eval 作为插件安全边界。** H`entry/src/main/ets/platform_impl/JsSandboxTool.ets:67/77/93/123` 为共享 about:blank WebView、Promise.race、stop尽力及 eval，仍有DOM/global原型通路，结果会String化/截断；没有 tools callback，也没有真正 fresh VM。

本机 SDK `/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/oh-uni-package.json` 为 API24 / 6.1.1.125，不是 API26。实际 `sysroot/usr/include/ark_runtime/jsvm.h:113/126/251/341/411/2587` 的 Init/CreateVM/CreateEnv/CompileScript/RunScript/JsonStringify 从API11支持；arm64 `sysroot/usr/lib/aarch64-linux-ohos/libjsvm.so`存在。官方 use-jsvm-process 直接链接 libace_napi.z.so/libjsvm.so；可沿已通 NAPI/CMake 管线新增少量 JSVM C++，不用新 QuickJS 依赖。

- 官方离线 `capi-jsvm-h` 与 SDK24的两份JSVM header全文未列 TerminateExecution/RequestInterrupt/AbortExecution 同类中止API；Profiler/Trace stop不是脚本取消。不发明高版本签名，不用未文档化V8内部符号。
- 离线 `js-apis-worker:604` 只描述 terminate销毁Worker并停止消息，没有强停正在Native RunScript的保证。把它当Native强杀依据没有证据。
- 官方 `jsvm-introduction` 明示接口并发不安全；`faqs-jsvm-7` 要求 CreateVM 创建时加锁。VM的全部操作/销毁归属其执行线程；主线程不能跨线程操作同env。
- JS在专属native线程执行，NAPI调用立即返回，不在UI线程 runScript；沿 H`entry/src/main/cpp/python_napi.cpp:22/72/76` 现有TSFN/环境cleanup模式通信。[官方线程通信约束](https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/native_subthread-to-uimain)说明native子线程不能直接使用主线程napi_env/value/ref。
- 每次调用 fresh VM/env，只注入 input、受限console、声明的tools桥；没有DOM、fetch、文件、模块加载或其他原生对象。隐藏eval/Function直接全局只是便利限制，安全边界是标准VM没有这些宿主API及broker；不靠大量AST重写伪装沙箱。
- 返回JSON保留类型；拒绝不可编码值/Promise/超预算，不截断成功JSON。超时/取消关闭宿主gate并丢弃迟到结果；使用官方VM内存参数与有界日志。
- API12按iOS诚实 **abandon/cooperative限制**，不能承诺OS安全沙箱/强杀。纯JS无限循环可能仍耗CPU。唯一active VM与abandoned槽必须同一native所有权：旧线程未结束不释放槽、不再开新VM无限累积；新JS请求明确runtime_busy/abandoned，Chat UI/其他工具仍可用。旧线程结束后在原线程销毁并释放槽；不得join无限脚本阻塞UI/环境cleanup。
- 这条限制要在SDK/错误状态说清，与已认可的Python取消边界同口径；若要求无限脚本强杀且立刻继续另一JS调用，当前公开JSVM API证据不足，是硬能力边界，不能登记成功。

## 5. JS host_tools：保持栈、逐次审批、不重演

```ts
type PluginJsEvent = HostCallEvent | JsFinishedEvent | JsFailedEvent;
interface PluginJsPort {
  start(request: PinnedPluginJsRequest): Promise<PluginJsEvent>;
  reply(sessionId: string, callId: string, resultJSON: string): Promise<PluginJsEvent>;
  reject(sessionId: string, callId: string, reason: string): Promise<PluginJsEvent>;
  cancel(sessionId: string): void;
  hasSession(sessionId: string): boolean;
}
```

建议 native tools callback 发 TSFN事件后用条件变量等待，仅阻塞该VM执行线程；ArkTS adapter消费事件。start/reply的Promise在**下一事件**结算，不等整脚本，这样 pending审批后loop可返回，VM栈仍等待而UI正常。

1. host_call 包含稳定session/execution/call序号、toolName、pristine JSON参数；每个声明成员只暴露一个同步tools函数，bridge自身再次核白名单，所有handle只留native。
2. adapter检查能力broker、当前primitive存在/助手门控/profile，capture现有 terminal等metadata，创建精确child ToolPart并await保存。没有额外审批总授权穿透。
3. 调现有dispatcher.resolveDecision；ask→保存child pending与parent摘要→返回，无primitive执行。批准卡消费固定child参数/包hash，批准后dispatcher.execute同child并保存结果，再reply原call。
4. deny/cancel/超时→关闭gate、唤醒等待回调并给失败；超时后native不允许再发新宿主调用。callback只处理data，不直接执行文件/网络/模型。
5. 执行预算在等待用户审批时暂停，恢复后继续剩余预算；宿主primitive自身超时照常生效。不得因默认10秒在用户阅读审批卡时悄悄过期；此语义要与runtime owner冻结。
6. 保存started先于dispatcher副作用；只有确认持有同native会话才续栈。重开页面可凭executionId找回同会话；**进程重启无VM**→interrupted/outcome_unknown，不从头eval源码、不缓存输出后盲重演。已开始副作用结果未知时停止，不自动retry。
7. 单独 `plugin_v1` checkpoint负责JS暂停，与E4 primitive port共用capture/decide/dispatch/saveParent的具体接线；不改Recipe adapter支持prefix，不做所有工具通用VM框架。

## 6. 其他 backend 与能力边界

- **Recipe成员**：解析包内文件后复用已有step函数和dispatcher桥；外层仍plugin__id__name。broker在每步参数解析之后、批准之前检查，Recipe导入/更新不受影响。
- **Python command**：固定源进入现有python_execute child，stdin=整个输入JSON或stdin_input的确切字符串，绝不拼入源。H`chat/.../python/models.ts:3` 限256KiB源/64KiB输入/60秒；`python/tools.ts:28` 禁auto approval；`python_core.cpp:197` output_limit明确失败。stdout为string或完整JSON后走同output_schema。允许模块见rawfile/python/amber_python.py:12，不能用sys/os/pip；示例 `import json;print(json.dumps(json.loads(stdin),ensure_ascii=False,sort_keys=True))`。
- **Capability broker**：允许当前可精确授权的Workspace路径工具、scrape_web、已实现wm_*、tool_search/tools_list；拒绝管理插件/Recipe、裸python/terminal、memory/private-data、subagent/exec等绕路，command backend另走明确local runtime授权。路径按段前缀比、move核两端、ID型读/写不能绕过path。
- **WebMount**：动作需同时声明webMountActions，导航/当前URL还需networkDomains。H`WebMountSession.ets:184/204/225` 已有站点gate；新增本次插件gate应与站点gate相交，不能覆盖原规则，结束清理；跳转后还核实际URL，不能只核wm_open输入。
- **MCP**：用固定且已配置服务器/工具对应的现有AgentTool与dispatcher，保留assistantIds白名单（H`chat/.../mcp_tools.ts:325/359`），不直接manager.callTool绕审批；MCP OAuth属于③不做，不新造账号流程。
- **OpenAPI/网页域限制**：固定endpoint/method，禁止参数替换URL/认证头，重定向必须每跳核domain。当前RcpHttpClient实际上用@ohos.net.http，没有插件redirect策略；仅核初始URL不构成网络限制。官方离线remote-communication-rcp及SDK `@hms.collaboration.rcp.d.ts:1042` 的transfer.autoRedirect从API11可用，可做插件窄HTTP port关闭自动redirect后处理允许跳转；legacy http.maxRedirects为API23，不能裸用当base12。
- **签名/归档**：iOS `.amberplugin`是 `amber.plugin.archive.v1` JSON `{files:[{path,dataBase64}],signature?}`，不是zip。保持budgets/重复路径检查；Ed25519签的是 `amber.plugin.signature.v1\0 + packageHash十六进制UTF8`，keyId=公钥SHA256前16hex。CryptoFramework官方规格Ed25519/ED25519PubKeySpec/createVerify均API11可用，按raw32bytes公钥fixture验真，不需新原生库；签名证明发布者身份，不自动授权运行。
- **健康/回退**：隔离按id+hash、连续3次真实runtime异常/超时/schema/remote错误，最多50条500字符；测试不影响live健康，拒绝批准不算代码故障。store损坏/信任失败不能“空库”覆盖；restore只解除当前hash隔离，CAS回退一版并重新验证权限/信任。
- **后台资格**：backgroundAllowed不是系统保活承诺；仅显式白名单Recipe/remote与既有cron宿主能力相交。JS/Python/SSH不自动进入后台；appBackgrounded时本次native gate关闭/取消，记录真实中断，不改E2已查证的DATA_TRANSFER/TASK_KEEPING平台裁定。后台无法交互批准时沿现行cron策略拒绝/暂停，不伪造allow。

## 7. 实施子phase、ownership 与产品中心

| 子phase / owner | 独占文件建议与完成条件 |
| --- | --- |
| E5-A model/schema | 新chat/plugins/models.ts、validation.ts、json_schema.ts；包/工具/schema/path/canonical/结果合约冻结。不得改Recipe manifest。 |
| E5-B store/trust/archive | 新entry/platform_impl/PluginStore.ets、PluginArchive.ets；独立原子包/hash/CAS/启停删除/一槽回退/签名；读实际目录，拒绝link。 |
| E5-C native JS | 新cpp/plugin_js_core/napi.*、entry/NativePluginJsTransport.ets；真VM/TSFN事件/host等待/gate/abandon/唯一slot；root接CMake/napi_init/index.d.ts，worker不碰shared。 |
| E5-D execution | 新chat/plugins/runner.ts、broker.ts、tools.ts；JS逐次审批、真实test/expected_result；Recipe/Python/remote同pinned descriptor，root统一dispatcher和loop出口。 |
| E5-E remote/ops | 独立PluginHttpPort.ets及小health模块；MCP固定解析、domain redirect、归档导出/信任、隔离恢复，与runtime/store合同协作，不扩MCP OAuth/市场。 |
| E5-F center/integration | 新PluginsPage.ets/必要小组件；root接DI/index/routes/SkillsPage/ChatPage/catalog/approval摘要/manual continuation。各owner保留他人编辑，sharedfile仅root负责。 |

中心最小真行为：已安装列表、enabled/disabled/quarantined/invalid分开、版本/工具数/信任；详情展示工具输入输出/能力/runtime/hash、测试及结果、启停删除、更新diff/回退、有限诊断。导入预览固定所有文件hash/能力差异/启用意图；试运行与调用写实际助手会话，按E4手动续跑形态不让模型猜工具。长hash/源码/JSON可展开或复制、窄屏可换行；按钮沿既有44vp触控目标，亮暗色/导航栏/底部留白/键盘/错误状态用截图验收。

## 8. 不可能项与必要验收（最多六类）

- 平台不对应：iOS AmberShell/iSH/Apple私有数据不冒充鸿蒙实现；第三方pip/原生下载插件不在已打包CPython能力内；③明确不做项继续排除。
- 公开索引/举报上传无服务端，不能伪造市场。插件中心和本机目录元数据可真实实现；不把这一点当整个E5阻断。
- JSVM强杀无当前官方证据；Worker/native终止无保证。只按上面有界abandon声明，纯JS子集和README不算E5完成。

1. 包/schema/path/hash/signature：真实fixtures、篡改/重复路径/link/预算/冲突零写入，跨端hash fixture。
2. Store生命周期：批准固定candidate/base/enable，stale拒绝、restart目录、一槽rollback、损坏/信任状态、不影响固定在途版本。
3. Backend必要逻辑：JS真实返回/异常/Promise/预算；Python stdin与退出；Recipe私有成员；MCP固定与OpenAPI跨域redirect；output-schema失败不能成功。
4. 宿主审批/取消：JS第1/第2宿主call分别停住、批准/拒绝、参数/权限broker、started先落库、无VM恢复不重演、native后台/超时gate关闭；UI响应/唯一abandoned slot必须实测。
5. 端内闭环：SDK→Workspace→validate→真实test+expected_result→hash批准注册启用→tool_search→实际调用→停用消失→重启可见；candidate test不污染live健康。
6. 产品UI与最终设备门：独立subagent逻辑/调用链和UI审查，精准修复；必要lint/build/sign/install、亮暗色和窄屏/键盘截图。研究阶段不执行，未声称任何验收已通过。
