# E5 原生 JSVM 独立只读 review

2026-09-28，reviewer `/root/e2_ssh_design`。只新增本报告；没有改产品、运行测试/编译/lint、操作设备或提交 Git。已阅读 harmony-arkts skill、E5 合同/design，逐段检查 core/NAPI/adapter 及 root CMake、注册、声明、后台接线。与 native owner `/root/e2_review_native` 协调后才对冻结差异收口，不将其早期自检快照当最终源码。

**结论：本次两个具体原生问题均由 owner 修复，最终原生差异未发现尚未修复的阻断。现有证据支持 API12 目标编译、链接与 adapter lint；设备离线，没有真实 JSVM/TSFN/同步宿主桥运行 PASS，不能凭本报告判定 E5 产品验收完成。** shared DI/Chat 接入与 domain 接缝由相应 owner/root 继续核对。

## 两个确认问题与最终修复

1. **取消发生在成功事件排队与交付之间，旧 adapter 仍返回 finished。** 触发：native 已 Finish 并向 TSFN 排队成功；ArkTS abort/onBackground 先执行 cancel+forgetSession；随后 TSFN 才交付。旧 receive 的非 host_call 分支直接返回 event，取消无法覆盖已关闭的 native gate。最终 [NativePluginJsTransport.ets:49](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/NativePluginJsTransport.ets:49) 在 finished 交付处核 session/abort/background，迟到成功转 cancelled、abandoned=false；真实 failed 保留原错误。owner 的可控 Promise adapter 红绿证据见 `/tmp/e5-plugin-js-late-finish-{red,green}.log`：原实得 finished，修后 abort/background 两项通过。此证据不冒充 native JSVM 运行。
2. **owner 线程根本未创建时被错误标为 abandoned。** 触发：预留 process slot 后 `std::thread` 创建 owner 抛异常；旧 catch 先 Close，按默认 ownerExited=false 发 abandoned=true，却立即释放 slot。最终 [plugin_js_core.cpp:372](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:372) 区分 ownerStarted：没有 owner 时用 Finish failed，abandoned=false 且释放槽；只有 owner 已存在而 watchdog 创建失败时仍 Close，保留真实遗弃边界。最终失败路径没有 join、重开额外 owner 或跨线程销毁 VM。该分支是静态差异核对，未做线程故障运行注入。

## 核对调用与所有权

| 要求 | 源码落点与结果 |
| --- | --- |
| 真实 fresh VM | [core:278](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:278) 直接 Init/CreateVM/CreateEnv、CompileScript/RunScript/CallFunction；每次新的 VM/env，input/tools/console 为真正 JSVM_Value 参数，JsonParse 输入/回复。没有 WebView、JS 假执行或把 input 拼到源代码。只证明实现与实际链接，不声称设备执行已成功。 |
| API12 / symbols | 实际 JSVM 导入留在 `/tmp/e5-plugin-js-symbols.log`，使用 Init/CreateVM/Scope/Compile/Run/Call/JsonParse/IsPromise/ObjectFreeze 等公开 API11符号；SDK header标注 since11。NAPI TSFN/create/call/release 标注 since10，env cleanup标注 since11。没有 API18 fatal/OOM handler、未文档化 V8 中断符号或高版本硬终止。 |
| 专属 owner | [core:285](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:285) 仅 Init/CreateVM 全局互斥；所有 VM/env/value 操作与 [core:254](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:254) 逆序 scope/env/VM 析构均在 Execute 的同一线程。NAPI/预算 watcher 只操作 mutex/CV/字符串状态，不碰 JSVM env。 |
| 同步宿主回调保栈 | [core:208](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:208) ToolCall 严格序列化参数、CallHost 发唯一递增 callId并在CV等回复，再 JsonParse 返回原 JS 栈。callback 只发事件，不直接执行 Workspace/网络/primitive。bindings预留完整 vector容量，指针与 callback存活至 VM销毁；Session由owner/watch/slot shared_ptr持有，未见此路径 callback UAF。 |
| next-event Promise | [NAPI:80](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_napi.cpp:80) 每个 start/reply/reject 有独立 TSFN/deferred；owner完成回调在 host_call或terminal时 Post，主线程 Deliver才 resolve/reject。不是 NAPI async pool 内长期 read，也不等待整个脚本才展示批准。 |
| 待批准预算暂停 | [core:141](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:141) CallHost累计已用时间后 paused=true，Watch在paused分支只等CV；同call回复后恢复activeSince。包含审批及实际宿主工具等待整段，预算耗尽、终态deadline检查均不包装成功。 |
| gate与遗弃槽 | [core:102](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:102) Close先关gate，再送失败/唤醒；Reject关闭该gate，script catch不会重开。Finish在VM销毁后才移除唯一process slot；[core:364](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:364) 旧owner未退出时新请求runtime_busy。无限脚本不会通过新请求无限积累线程。 |
| cleanup / env death | [NAPI:89](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_napi.cpp:89) stopping→runtime.Shutdown关闭gate/释放owner completion→清pending并abort env引用，不join。TSFN初始两引用分别对应Post和Deliver/cleanup；finalizer持有shared Completion，[NAPI:69](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_napi.cpp:69) env为空时先由unique Payload回收数据，不访问死env。未发现自join或为“硬取消”跨线程DestroyVM。真实env cleanup运行尚未验。 |

## JSON / 错误与证据边界

[core:26](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:26) 固定 serializer 捕获intrinsics，读取真实数据属性、保留JSON类型/Unicode；不调用用户toJSON，不静默把undefined/function/nonfinite/循环/holes等变成成功。数组索引和用户改iterator/property-prototype问题由owner最终自检修正；Node语义日志通过仅证明这个纯JS helper。

[core:193](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/plugin_js_core.cpp:193) 明确拒绝 Promise；成功JSON输出按配置字符预算拒绝，不截断成功。input/reply为64KiB UTF8，source为256KiB，日志有条数和字节上限；JSON非法回复在原owner解析失败后Close，script catch不能产出成功。调用者传空/超预算reply在NAPI前拒绝，domain必须取消/收口原待回复栈，不能将拒绝当新host事件。

接受已冻结的 cooperative abandon 与无硬内存沙箱边界：预算/取消结算事件并关宿主gate，不保证无限JS停止CPU；未结束owner仍占唯一槽。generation配置字段不构成ArrayBuffer/全进程硬上限，owner报告已明确实际上游实现未读取这些字段，不能声称已强制内存隔离。本review未要求新中断API、第二调度器或假硬杀。

## root 接线与 domain 小接缝

root [CMakeLists.txt:71](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/CMakeLists.txt:71) 包含两新cpp并链接libjsvm；[napi_init.cpp:183](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/napi_init.cpp:183) 按 RegisterPluginJs 注册；[index.d.ts:96](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/cpp/types/libamber_native/index.d.ts:96) request/event及五方法与合同一致。EntryAbility先设置appBackgrounded=true，随后 [EntryAbility.ets:370](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/entryability/EntryAbility.ets:370) 同步取消active JS sessions。没有把整条DI/Chat运行闭环当已通过。

只核native接缝时向domain owner精准回喂并取得修复回执，随后只读核实：[runner:193](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/plugins/runner.ts:193) 的local save统一包裹awaiting/started/已知子结果/terminal保存，JS保存失败同步cancel原executionId再原样throw，保留最后耐久state；[runner:309](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/plugins/runner.ts:309) 将native timeout和timed_out都分为timeout；[runner:399](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/plugins/runner.ts:399) 在reply前明确拒绝>64KiB UTF8宿主结果，reject/cancel而非截断。实际primitive前先save started，reply原栈前先保存已知子结果，reply异常也取消原VM。owner报告关键fixture绿并正补故障断言/类型；这里只确认这个native接缝静态修复，不代替其最终domain review或宣称native运行通过。

## 已读现有验证证据

- `/tmp/e5-plugin-js-api12-receipt.txt` 留精确命令及工具exit0：Studio clang++ `--target=aarch64-linux-ohos12 -D__OHOS_API__=12 -std=c++17 -fPIC -Wall -Wextra -Werror` 编译两cpp；`-shared -Wl,--no-undefined -ljsvm -lace_napi.z` 链接。静默诊断 `/tmp/e5-plugin-js-api12.log`；最后thread-error修复后owner已重编译链接。
- `/tmp/e5-plugin-js-elf.log` 的DT_NEEDED为libjsvm/libace_napi/libc++/libc；导入符号日志支持真实JSVM链接，无私有V8依赖。
- `/tmp/e5-plugin-js-lint.log`：最终adapter单文件No defects、exit0。
- `/tmp/e5-plugin-js-json-check.log`：固定serializer的Node语义必要检查；`/tmp/e5-plugin-js-late-finish-{red,green}.log`：实际adapter转译加可控native Promise竞态红绿。两类都不证明OHOS native运行。
- 冻结source receipt `/tmp/e5-plugin-js-source.sha256`：core=`9a84b8f565496da0de4393f04cd0e25a036c30e844be776dae74aefd0643f9ad`；NAPI=`527cfeecc205878b70e0e6e768dd8a07751c892b92726c8593713539a9e5626c`；adapter=`dfc8d294e6e238e35c48d5a1139e1de461907b505549423d2d4d9ff11bffa31b`。

没有重跑owner检查。真实设备JSVM、连续host_call批准保栈、预算暂停、reject/catch、timeout/无限循环槽、后台和worker env cleanup必须在设备恢复后由root取得运行证据；当前不标PASS。
