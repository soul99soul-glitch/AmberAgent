# E5-C — fresh JSVM native transport

2026-09-28。Owner `/root/e2_review_native`。按照 `E5-plugin-design.md` 与 frozen `E5-contract.md` 实作；仅新增 `plugin_js_core.h/.cpp`、`plugin_js_napi.h/.cpp`、`NativePluginJsTransport.ets`。CMake、native exports/type declarations、domain index、DI、EntryAbility 后台接线由 root 完成。未操作设备、未跑整包构建。

## 实作及调用闭环

- 真实 `OH_JSVM_Init/CreateVM/CreateEnv/CompileScript/RunScript/CallFunction`，每个 execution 使用全新 VM/env；源码是同步函数体，`input/tools/console` 以实际 JSVM_Value 参数传入，输入/宿主回复用官方 `JsonParse`，不把 JSON 拼进源码。无 WebView、AST 注入或新增引擎依赖。
- 进程单 active/abandoned 槽，Init/CreateVM 互斥；每个 VM 全部创建、执行、Scope 关闭及销毁都由其独占 native owner 线程完成，VM/env/value 不跨线程。只有 native owner 的宿主回调在 CV 上等待，NAPI/UI 从不 join。
- 冻结请求 `{executionId,source,inputJson,hostTools,timeoutMs,maxOutputChars}`，`sessionId=executionId`。`pluginJsStart/reply/reject` 的 Promise 通过 TSFN 在下一 `host_call/finished/failed` 结算；`cancel/hasSession` 同步。注册函数 `RegisterPluginJs(napi_env,napi_value)`，root 已加 `libjsvm.so` 与注册/声明。
- `tools.<hostTools>` 的 native callback 仅取一份严格 JSON 参数，生成递增 callId，发事件给同一领域 broker，保留原 JS 栈等待同一 sessionId/callId 回复；不直接访问 Workspace/网络/凭据或执行 primitive。错 callId、重复 reply 在恢复 owner 前拒绝。审批与实际 primitive 等待整段均暂停 JS 执行预算，不增加 pause/resume API。
- `reject` 是终止：先关宿主 gate，发送 `host_rejected`，唤醒原栈抛错；脚本即使 catch，也不能再发宿主事件或转成功。timeout/cancel/env cleanup 同样关 gate，拒迟到成功；原 owner 退出且销毁 VM 后才释放 process slot，期间新 start 抛 `runtime_busy`。
- native gate关闭时 failed.abandoned 表示 owner 当时尚未退出；未创建 owner 的 thread-error 分支用 Finish 结算 abandoned=false 并释放槽。正常 finished 在 owner 完成 VM 销毁、释放槽后交付。env cleanup 先关 gate/释放 owner TSFN引用，再清 pending/abort env引用，直接返回，不等待无限脚本。
- adapter 的 abort listener 在首个 host_call 后继续保留，直至终态/取消；EntryAbility.onBackground 同步调用导出的 `cancelNativePluginJsSessions`。开始/回复/事件交付再次核后台/abort。独立 review 指出的「finished 已入 TSFN 队列后才取消」已在 adapter 终态交付处收口，迟到 finished 转 cancelled，已有 failed 保留真实原因。

## JSON、日志及资源边界

- native 输入和宿主回复各≤64KiB UTF8、source≤256KiB、hostTools≤64，JS预算1000–30000ms，成功输出1000–32000 UTF16字符。超输出限额拒绝，不截断成功 JSON。Promise 明确 `promise_not_supported`；undefined/function/symbol/bigint、非有限数、循环引用、accessor、非普通 JSON 对象、数组空洞/额外可枚举属性等拒绝。
- 固定 JSON serializer 在用户脚本之前捕获实际 intrinsics；读取数据属性并复制到无原型 JSON容器，保留 JSON类型/Unicode/`__proto__` 数据，避免用户 toJSON、Array iterator 和 Object.prototype.value 修改导致静默丢值。非 enumerable 的数组索引仍按实际索引序列读取，不替换成 null。
- console.log/info/warn/error 在 VM 上执行；每次最多32参数、单行≤8KiB，总正常日志≤8KiB/128条，限额后追加一次固定标记并停止收集。错误 message 有界保留；解析宿主回复失败会终止 gate，不能由 script catch 重新打开。
- 按官方 `JSVM_CreateVMOptions` 配置老/年轻代及初始大小；**不宣称硬内存隔离**。SDK公开文档没有承诺全进程/ArrayBuffer外部分配限额；所查官方 [OpenHarmony-v5.1.0-Release CreateVM](https://github.com/openharmony/arkcompiler_jsvm/blob/OpenHarmony-v5.1.0-Release/src/js_native_api_v8.cpp) 及当前 master 实现未读取四个 generation 字段，不能仅因设置这些字段就声称实际版本强制生效。真实设备的实际行为尚未验证。
- API12公开接口没有强制终止运行中 JS 的API；JSVM OOM/Fatal handler 是API18，不冒用。超时/取消只及时结算事件与关闭 host gate；无限循环仍可能耗CPU，唯一槽继续占用，直到原线程自行退出/进程结束。fresh VM 提供语言环境隔离和有限宿主入口，不能宣称完整恶意代码 CPU/内存沙箱。

## 必要验证及证据

1. SDK官方离线 docs 已实际 search/read：`API参考/C_API/头文件/jsvm_h/capi-jsvm-h`、`API参考/C_API/结构体/JSVM_CreateVMOptions/capi-jsvm-jsvm-createvmoptions`、`faqs-jsvm-7`（CreateVM多线程竞争）、`jsvm-guidelines`（HandleScope与逆序关闭）。以实际 SDK `ark_runtime/jsvm.h/jsvm_types.h` 的 API11签名实现；没有使用API18任务/中止/错误handler。
2. 两个 cpp 使用 Studio SDK，`--target=aarch64-linux-ohos12 --sysroot=... -D__OHOS_API__=12 -std=c++17 -fPIC -Wall -Wextra -Werror` 编译 **exit0**，独立 shared link `-Wl,--no-undefined -ljsvm -lace_napi.z` **exit0**。`/tmp/e5-plugin-js-api12.log` 为静默成功诊断；确切命令/退出及最终source hash 在 `/tmp/e5-plugin-js-api12-receipt.txt`，source hash另存 `/tmp/e5-plugin-js-source.sha256`。
3. `/tmp/e5-plugin-js-elf.log`：DT_NEEDED仅 `libjsvm.so/libace_napi.z.so/libc++_shared.so/libc.so`；`/tmp/e5-plugin-js-symbols.log` 留存实际OH_JSVM/napi导入符号。
4. 单文件 ArkTS lint **exit0 / No defects found**，`/tmp/e5-plugin-js-lint.log`。SDK默认日志路径需工具常规sandbox escalation，未改变权限配置。
5. 只为具体严格JSON自检问题做一份 fixed serializer 的 Node 语义窄检查，`/tmp/e5-plugin-js-json-check.cjs/.log`：类型/Unicode、隐藏数组索引、iterator修改、accessor拒绝、undefined/Promise/输出限额通过。它验证纯JS helper，不证明 OHOS JSVM、native owner、TSFN 或设备运行。
6. 只为独立review的迟到成功竞态做 adapter 定向红/绿：原交付逻辑实得 finished 红；修后 abort与background两分支 **2/2绿**，`/tmp/e5-plugin-js-late-finish-{red,green}.log`。使用实际 adapter 经TS转译及可控 native Promise 排队，不伪称真实JSVM或设备证据。

## 尚待 root 集成门禁

本owner已完成必要 native compile/link与窄lint；独立审查报告为 `E5-review-native.md`。设备当前离线，**没有真实JSVM运行/同步宿主桥/审批等待/取消与后台/worker env cleanup 的设备证据**，没有整包构建/sign/install/UI声明。root应在统一整包与设备门禁取得真实执行闭环；当前报告不能单独证明E5产品完成。

统一整包编译补修：`/tmp/e5-release-build.log` 指出 adapter catch 重抛任意类型违反 `arkts-limited-throw`；已局部改为 Error 实例原样重抛，否则 `new Error(String(error))`。native代码与取消行为未变，既有case不重跑；实际整包回喂由root继续。
