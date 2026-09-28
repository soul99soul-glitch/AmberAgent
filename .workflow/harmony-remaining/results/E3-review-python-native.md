# E3 Python native 独立源码审查

2026-09-28；审查者 e3_python_domain，原生实现者 e3_native_python。
原生代码只读；未修改 native source/资源/设备/SDK，未重复已有全套或压力测试。

最终结论：**本原生审查范围 PASS，无未收口 P1/P2**。
唯一确认 P1 已由原生 owner 精准修复并经本审查独立复现转绿；
其余 owner/C API/TSFN/输出/静态链接路径在所查范围内未发现具体问题。

## P1（已修复）：解释器销毁时的用户析构循环失去协作取消

修复前真实调用链：helper execute 在用户正常返回后关闭 trace → core 也在
python_core.cpp:152 清 trace → :157 Py_EndInterpreter → 解释器 GC 调用用户
`__del__` → 纯 Python 循环没有 Poll → request.complete 永远不到。
同一个 owner 后续请求被阻塞，Cancel 仅设置原子标记；Shutdown 等 controls.empty()
也不能完成。这是用户 Python 字节码的清理回调，不是已声明无法硬杀的阻塞 C helper。

唯一新增的小复现（隔离 host 子进程，watchdog 仅杀该夹具进程）：

```python
class Hold:
    def __del__(self):
        while True: pass
obj = Hold()
print('destructor-fixture-body-complete')
```

先使用同一 native owner 成功执行 warmup，再执行上述代码（timeoutMs=1000），
800ms 发真实 pythonCancel，4 秒仍无 Promise 结算。
`/tmp/e3-review-python-destructor-warm.log`：WARMUP_SETTLED completed / REQUEST_STARTED /
CANCEL_SENT / WATCHDOG_KILLED_AFTER_4S。

初次 200ms 检查只在启动阶段 timed_out，未进入所查清理路径，明确不计为复现证据。
已将确认路径、日志和最小源发送 root/native owner。仅要求精准处理这个清理回调，
然后实际停止/后续成功；未要求架构扩张、压力矩阵或泛化安全沙箱。

## 其余源码核对

| 范围 | 结果与代码依据 |
|---|---|
| owner / GIL | 所有 Py_PreInitialize/Py_InitializeFromConfig/新子解释器/执行/销毁/Finalize 都在单一 owner 线程。NAPI 线程只提交、标记 cancel 和接收 Value；Version 返回编译期 PY_VERSION。没有 PyGILState_* 跨解释器切换。 |
| 初始化 | isolated PyPreConfig + PyConfig；固定 ZIP/helper 搜索路径；无 environment/site/写 bytecode/信号 handler。官方 isolated 默认 configure_c_stdio=0，未做进程全局 stdio 重定向。 |
| 子解释器 | SHARED_GIL + main obmalloc，与单 owner 串行模型一致；fork/exec/threads/daemon_threads 全禁。各请求创建新 namespace、模块环境和 StringIO/输出对象，不保留用户 PyObject 到下次请求。 |
| C API 引用 | helper/function/source/input/value 的拥有引用均在结束前 XDECREF；UTF8 Write 临时 bytes DECREF；PyConfig_Clear 与 PyMem_RawFree 覆盖正常初始化路径。Py_EndInterpreter 后 Swap(main_) 与官方“结束后无 attached state”约束一致。 |
| 真实结算 | 正常执行路径先 Py_EndInterpreter，再构造结果、擦除 control 和 request.complete；没有用 timeout Promise 冒充停止。已修复清理期用户回调的具体 P1，停止结果在实际清理结束后返回。 |
| trace | pinned CPython legacy_tracing.c:185 的 C trace callback 返回错误不清除 c_tracefunc；sysmodule.c:trace_trampoline 才清 trace。已有用户 catch BaseException/bare-except 测试合理，无需 AST handler rewrite。 |
| 输出 | Write 以 stdout+stderr 合计 UTF8 bytes 限 128Ki；截断时回退 continuation byte，保持码点完整；只保存有界文本。stdout/stderr 属用户结果，底层错误不带 source/stdin dump。 |
| NAPI / TSFN | 初始 thread count=2；owner Post 单次原子 posted gate，成功 queue 移交 payload，调用 release；JS Deliver 解决 deferred、移除 pending 并 release；holder 在 Finalize 才释放。 |
| env cleanup | stopping=true 后清 pending、abort 每个 TSFN，再 Runtime.Shutdown 实际取消并等待自身 queued/running controls。其他 env 共用 owner，最后 owner 在锁下 join/finalize 后允许新 owner；无 active Promise 的提前清理声明。清理期用户字节码如今也在 control 范围内。 |
| 可用模块 | helper whitelist 与 Setup.local 的静态 array/_json/math/_statistics/binascii/unicodedata/_datetime 及 packaged pure modules 对得上；decimal 使用纯 Python fallback。外部/native extension/fork/socket 不纳入能力声明。 |

上述判断是对当前代码与 pinned CPython C source 的检查，不宣称 OS 安全沙箱、
硬内存上限、阻塞 C helper 硬取消或永久后台运行。

## 已有证据的核对与来源

- 实现者 host 消费回归 **23/23 PASS**：读取 `/tmp/e3-python-native-test.log` 的最终计数，
  同时读 tests/host_native.test.cjs；Unicode/stdin/json/math/模块、fresh state、限制、
  exception/output cap、进入循环后取消/timeout、queued cancel 与 ID 语义均有对应测试。
- 实现者 env cleanup **3/3 PASS**：读取 `/tmp/e3-python-env-test.log` 并核对测试源码；
  active env teardown、owner reinit 与 concurrent environments 的测试确实调用 Worker.terminate。
- root 转述：前位原生 reviewer 已独立 **25/25 PASS**，含 entered marker、finally loop、
  异常 context manager 和 successor=7；该 reviewer 最后输出遭过滤，没有完整最终报告。
  本审查未冒称自己执行或重复这些场景。
- API12 脚本实际使用 aarch64-linux-ohos12.0.0、API12 sysroot 和 --no-undefined，
  `/tmp/e3-python-target-smoke.log` 完成全 NAPI 链接。
  本审查对现有 ELF 重读 readelf：ELF64 / AArch64 / DYN，NEEDED 仅
  libace_napi.z.so、libc++_shared.so、libc.so。无 Linux/Android/macOS动态依赖。
- 本审查只读重新验证 RESOURCE_SHA256SUMS、PATCH_SHA256SUMS、
  PATCHED_SOURCE_SHA256SUMS：全部 OK。
- API12 compile/link 与 root 的 API24/26 双设备运行门分开；此报告无 HAP/设备 PASS 声明。

## 官方参照

subinterpreter 的 attached state 与 EndInterpreter 约束核对于
[CPython 官方 C API](https://docs.python.org/3.14/c-api/subinterpreters.html)。
fixed isolated 配置与 C stdio 默认核对于
[CPython 初始化配置](https://docs.python.org/3.14/c-api/init_config.html)。
TSFN 双 reference、abort/closing、payload/finalize 时序核对于
[Node-API 官方文档](https://nodejs.org/api/n-api.html#reference-counting-of-thread-safe-functions)，
并与当前 OHOS 目标 ELF 链接证据分开登记。

## 收口复核

native owner 保留 C trace 穿过 helper 返回、对象 DECREF 和 Py_EndInterpreter。
仅在 control 已停止时检查 frame/调用祖先是否为 `<embedded-python>` 用户代码，
不会用取消异常打断 helper/CPython 内部清理。独立逐行核对新增引用：
borrowed frame INCREF，PyFrame_GetCode/GetBack 拥有引用均 DECREF，无残留用户 frame。

sys.unraisablehook 改为 app-owned `_control.unraisable`：停止中的异常静默，
普通析构异常走同一个 128KiB combined UTF8 Write 输出端口；hook 的 error/text/line/
arguments/written 临时引用均释放并清除已处理异常。避免解释器 streams 清理后
默认 stderr fallback 把函数/中断诊断输出到进程/hilog。

仅独立重跑最初确认失败的 warm __del__ 夹具，加同 owner successor（未扩全套）：
`/tmp/e3-review-python-destructor-fixed.log` **exit 0**：WARMUP completed /
CANCEL_SENT / REQUEST_SETTLED cancelled / BODY_MARKER_CONFIRMED / SUCCESSOR completed 7 /
**PROCESS_STDERR_BYTES 0**。已确认 body marker，不能以启动取消代替清理回调停止。

另外只读核对 owner 的同路径必要回归 `/tmp/e3-python-finalizer-green.log`：
普通 __del__ 取消、动态 type 超时 **2/2 PASS**，successor 7/8，body marker 存在。
`/tmp/e3-python-finalizer-process-stderr.log` **0 bytes**，普通析构诊断进入 bounded 用户结果。

最终 RESOURCE_SHA256SUMS 重验全部 OK：helper SHA256
`5530c0f96dacea244f4a944cc841513a8cb73bee850fbdc6782e26e40c8a146e`，ZIP 未变。
最终严格 API12 target smoke 已由 owner 重建；本审查重读新 ELF 仍 AArch64 /
NEEDED 仅 ace_napi/c++shared/c。没有 native source/设备修改、压力矩阵或超出必要的测试。
该 PASS 仍是原生子范围，HAP 与 API24/26 真机门由 root 单独完成。
