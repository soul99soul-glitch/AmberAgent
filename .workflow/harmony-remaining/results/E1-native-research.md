# E1 / E2 OHOS 原生最短路径调研

日期：2026-09-28。只读调研；本轮没有安装工具、没有构建或签名应用、没有修改业务代码。唯一写入为本文件。以下命令中的新增源文件是建议的实施布局，尚未创建。

## 结论

E1 先用独立 Rust `staticlib` + C++ Node-API 桥打通 tokenizer。不要把现有 Android JNI crate 直接拿来构建 OHOS，也不需要引入 Rust NAPI 宏框架。E2 在同一个 Rust crate 里添加 `ssh2`/libssh2，首先尝试其正式的 `vendored-openssl` 路径。现有本机条件没有证实平台硬阻断；缺 Rust OHOS 标准库组件是已定位的前置依赖。

E1 完成条件是：OHOS ELF 产物进入 HAP、ArkTS 实际加载并调用、四种 tokenizer 在相同 UTF-8 文本样例上与原 Rust 语义一致、错误分支不崩进程。仅编出 `.a` 不代表 E1 完成。E2 SSH 完成条件要另加真实服务端的主机校验、认证、PTY、双向输入输出、窗口 resize、取消/关闭；tokenizer 接通不会自动证明这些能力可用。

## 已核验的仓库与设备事实

| 对象 | 2026-09-28 实际状态 |
| --- | --- |
| `harmony/native/rust-napi/` | 只有 README；README 明确先验证 tokenizer，要求 Rust/Harmony 使用同样 fixture |
| `native/tokenizer/Cargo.toml` | `crate-type = ["cdylib"]`，JNI、jni-common、android_logger 依赖，是 Android 桥 |
| `native/tokenizer/src/lib.rs` | JNI `countBatch`；支持 o200k_base、cl100k_base、claude、gemini；后两者按 **UTF-8 字节数** / 3.5、4.0 向上取整 |
| `harmony/chat/.../context_compact.ts:163` | 现 token 估算为字符 / 4 与消息下限；当前没有 native tokenizer port，不应在 E1 为其强行改整条同步估算链 |
| `harmony/entry/build-profile.json5` | 当前没有 `externalNativeOptions` |
| 产品 API 基线 | `compatibleSdkVersion` / `targetSdkVersion` 均为 `5.0.0(12)` |
| 本机 Rust | rustc/cargo 1.96.0；已装 target 为 Apple arm64、Android arm64/x86_64；**未装 OHOS target std** |
| SDK | `~/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/native`；Native 26.0.0.32 / API 26 / Beta2 |
| 工具 | SDK clang-15；CMake 3.28.2；SDK 自带 Ninja；主机 perl、make 可用 |
| 模拟器 | `127.0.0.1:5555`，`uname -m = aarch64`，`const.product.cpu.abilist = arm64-v8a`，API 24 |
| 连接真机 | `6HK0226627000384`，`uname -m = aarch64`，`abilist = arm64-v8a` |
| 权限 | entry 已声明 `ohos.permission.INTERNET` 与 `GET_NETWORK_INFO` |

本机 `rustc --print cfg --target aarch64-unknown-linux-ohos` 已确认：`target_os="linux"`、`target_env="ohos"`、`unix`、64 位指针。写 cfg 判断时不能假设 `target_os="ohos"`。

本轮已经运行了不产出文件的头文件验证，退出码 0：

```sh
native_sdk=/Users/arquiel/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/native
"$native_sdk/llvm/bin/clang" -target aarch64-linux-ohos12 \
  --sysroot="$native_sdk/sysroot" -D__MUSL__ -fsyntax-only -x c - <<'C'
#include <napi/native_api.h>
#include <sys/socket.h>
int main(void) { return 0; }
C
```

这只证明 API 12 triple 被当前 clang 接受且 NAPI/socket 头能编译；不证明 Rust 链接或 API 12 设备运行。

## 官方资料核验

离线文档通过仓库封装 `harmony/scripts/devecocli.sh` 查阅，未升级 devecocli。

- `API参考/Node-API/napi`：`#include <napi/native_api.h>`，链接 `libace_napi.z.so`。所需 module register、字符串、数组、promise、async work、threadsafe function 均从 API 10 支持。
- `开发指南/代码开发/使用Node-API实现ArkTS_JS与C_C_语言交互/使用Node-API实现跨语言交互开发流程/use-napi-process`：constructor 调用 `napi_module_register`；静态 Init 绑定 exports；`nm_modname` 与 SO 名（去掉 lib/.so）一致；index.d.ts + types package；Node-API env/value 不能跨线程使用。
- `开发指南/配置构建流程/配置CPP/ide-hvigor-cpp` 与 `开发指南/构建NDK工程/使用DevEco_Studio模板构建NDK工程/build-with-ndk-ide`：entry `buildOption.externalNativeOptions.path` 指向 CMakeLists，`abiFilters` 控制 ABI，Hvigor 收集 CMake 产出的 SO 及运行时 SO。
- `开发指南/代码开发/使用Node-API实现ArkTS_JS与C_C_语言交互/Node-API典型使用场景/使用Node-API接口进行异步任务开发/use-napi-asynchronous-task`：耗时操作用 async work；execute 在工作线程只处理 native 数据，complete 回 ArkTS 线程生成结果并释放任务。

本 SDK 存在 `napi/native_api.h` 与其引用的 `node_api.h`，**没有 `napi/native_node_api.h`**；不要复制旧模板中该无效 include。

Rust 官方将 aarch64/armv7/x86_64 OHOS 列为 Tier 2，提供 rustup 预编译 std；OHOS SDK clang wrapper 设置 target、sysroot、`__MUSL__`。这些能支持交叉编译，但 SDK 不直接替 Cargo 配置 linker。[Rust 官方 OHOS 文档](https://doc.rust-lang.org/rustc/platform-support/openharmony.html)

Rust 官方建议跨语言链接用 staticlib，系统动态依赖仍由外部链接器补齐；一个 SO 内多个独立 Rust staticlib 容易重复 runtime。E2 继续扩展 E1 的单一 crate 即可。[Rust 官方 linkage](https://doc.rust-lang.org/stable/reference/linkage.html)

## E1 最小布局与接口

```text
harmony/native/rust-napi/
  Cargo.toml                 # 独立 crate，lib name=amber_native_core，staticlib，edition 2021
  Cargo.lock                 # 锁定依赖；初始 tiktoken-rs="=0.6.0" 与 Android 对齐
  src/lib.rs                 # 四 tokenizer、窄 C ABI、对应语义 tests
  scripts/ohos-clang.sh       # SDK target/sysroot wrapper；不改 ~/.cargo/config.toml
harmony/entry/src/main/cpp/
  CMakeLists.txt
  napi_init.cpp              # 参数/字符串/数组桥接与 async work
  types/libamber_native/
    index.d.ts
    oh-package.json5
harmony/entry/src/main/ets/platform_impl/
  NativeTokenCounter.ets     # 平台调用入口；domain TS 与 Node 测试不 import SO
```

Rust 最小依赖仅 `tiktoken-rs = "=0.6.0"`。纯逻辑按现 `count_tokens` 语义实现；直接使用该版本自带的 `o200k_base_singleton()` / `cl100k_base_singleton()`，不另造 tokenizer cache。C ABI 可仅收 tokenizer 枚举值、UTF-8 ptr+len，返回非负 token 数或少量明确负错误码；字符串由 C++ 持有，无跨语言 allocator/free 协议。Rust FFI 边界 catch_unwind，错误转成 NAPI Error，panic 不越过 C 边界。

对产品调用推荐 `countBatch(ids: string[], texts: string[]): Promise<number[]>`：BPE 首次初始化及长文本扫描在 async work 中执行。同步小 fixture 可作为早期 smoke，不能把长文本同步跑在 UI 主线程。数组不等长、未知 ID、Unicode、空批次都要验证，不需泛型 RPC 或全局 native service manager。

entry 的类型包声明：

```json
{ "name": "libamber_native.so", "types": "./index.d.ts", "version": "0.1.0" }
```

entry `oh-package.json5` 的 dependencies 添加 `"libamber_native.so": "file:./src/main/cpp/types/libamber_native"`；ArkTS `import native from 'libamber_native.so'`；constructor 注册唯一函数名如 `RegisterAmberNativeModule`，`nm_modname="amber_native"`。仅一个 shim SO，Rust 静态 archive 不另外打包。

entry 构建增量：

```json5
buildOption: {
  externalNativeOptions: {
    path: './src/main/cpp/CMakeLists.txt',
    abiFilters: ['arm64-v8a']
  }
}
```

当前模拟器无需 x86_64；未来支持 Intel 环境时再补 target，不在 E1 为未出现设备扩 ABI。

## E1 确切实施/验证命令

以下为实施阶段命令，本次未执行安装和构建：

```sh
# 安装的是现有 Rust toolchain 的目标 std 组件；不用安装 cargo-ohos 或 npm NAPI 框架。
rustup target add aarch64-unknown-linux-ohos
```

wrapper 内容（SDK 根可由项目现有 SDK 定位惯例解析）：

```sh
#!/bin/sh
native_sdk="${OHOS_BASE_SDK_HOME:?OHOS_BASE_SDK_HOME must point to openharmony}/native"
exec "$native_sdk/llvm/bin/clang" -target aarch64-linux-ohos12 \
  --sysroot="$native_sdk/sysroot" -D__MUSL__ "$@"
```

从仓库根手工验证 Rust 层后再接 Hvigor，避免一次失败混入三层错误：

```sh
export OHOS_BASE_SDK_HOME=/Users/arquiel/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony
native_crate="$PWD/harmony/native/rust-napi"
native_sdk="$OHOS_BASE_SDK_HOME/native"
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_OHOS_LINKER="$native_crate/scripts/ohos-clang.sh"
export CC_aarch64_unknown_linux_ohos="$native_crate/scripts/ohos-clang.sh"
export AR_aarch64_unknown_linux_ohos="$native_sdk/llvm/bin/llvm-ar"
export RANLIB_aarch64_unknown_linux_ohos="$native_sdk/llvm/bin/llvm-ranlib"

cargo generate-lockfile --manifest-path "$native_crate/Cargo.toml"
cargo test --locked --manifest-path "$native_crate/Cargo.toml"
cargo rustc --locked --manifest-path "$native_crate/Cargo.toml" \
  --target aarch64-unknown-linux-ohos --release -- --print=native-static-libs
```

最后一个命令同时产出 `.a` 并打印外部最终链接所需系统库；CMake 采用**实际输出**，不猜 Rust std 的系统链接依赖。release 起步无需 LTO，避免为小入口提前引入编译/链接复杂度。

CMake IMPORTED STATIC 指向 `target/aarch64-unknown-linux-ohos/release/libamber_native_core.a`；`add_library(amber_native SHARED napi_init.cpp)` 并链接该 archive、`libace_napi.z.so` 及 `native-static-libs` 实际列表。最终链接开启 `-Wl,--no-undefined`；隐藏 Rust archive 非接口符号可用 `-Wl,--exclude-libs,ALL`。后续把上述 cargo 调用接为同一 CMake target 的前置依赖，避免靠人工跑脚本留下旧 archive。

```sh
harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/NativeTokenCounter.ets
node scripts/harmony-inventory/build-harmony-app.mjs
unzip -l harmony/entry/build/default/outputs/default/entry-default-signed.hap | rg 'libs/.*amber_native|libc\+\+'
"$native_sdk/llvm/bin/llvm-readelf" -h -d <CMake产出的libamber_native.so>
hdc -t 127.0.0.1:5555 install -r harmony/entry/build/default/outputs/default/entry-default-signed.hap
hdc -t 127.0.0.1:5555 shell aa force-stop app.amber.deepread
hdc -t 127.0.0.1:5555 shell aa start -b app.amber.deepread -a EntryAbility
```

当前 AGC signingConfig 已配置；用构建产出的已签名 HAP。若切回离线模拟器签名，才使用 `harmony/scripts/sign-hap.sh`；不覆写或重新暴露已有签名材料。

验收 fixture 至少含空文本、ASCII、中文、emoji、换行、内嵌 NUL、长文本；四种 ID 都走同一份 fixture。Claude/Gemini 必须用 UTF-8 byte 口径，不能把 ArkTS `text.length` 当现 Rust 语义。BPE 对 special-token 字面量使用原 `encode_ordinary`。原 Android Rust 测试只有范围断言，E1 需要增加明确 fixture 结果并让设备 NAPI 回传对照，不能把“>0”当正确。

## E2 libssh2：直接复用的路径与约束

建议先在 E1 Rust crate 增加 `ssh2 = { version = "0.9", features = ["vendored-openssl"] }` 并生成锁文件。官方 ssh2 0.9.6 manifest 暴露此 feature，libssh2-sys Unix 依赖 openssl-sys 与 libz-sys。[ssh2 官方 manifest](https://github.com/rust-lang/ssh2-rs/blob/master/Cargo.toml)、[libssh2-sys 官方 manifest](https://github.com/rust-lang/ssh2-rs/blob/master/libssh2-sys/Cargo.toml)

OpenSSL-src 官方当前源码已经显式将 `aarch64-unknown-linux-ohos` 映射为 `linux-aarch64`，并通过 cc 读取 target compiler/archiver 配置。因此“vendored OpenSSL 不认识 OHOS，必先 fork”不成立；锁文件解析到的具体版本仍需交叉编译确认。[openssl-src 官方实现](https://github.com/alexcrichton/openssl-src-rs/blob/master/src/lib.rs)

本 SDK sysroot 中未找到公开 `libssl.so` / `libcrypto.so`，不要链接设备私有系统 crypto。以上 Rust target 的 CC/AR/RANLIB wrapper 加 `vendored-openssl` 是先试的单路径；失败后依据具体 configure/compile 错误修正，不提前并存 OpenSSL、mbedTLS 和多种 Rust SSH 实现。

需要专门补 `CXX_aarch64_unknown_linux_ohos` 时建同一 wrapper 的 clang++ 版本；OpenSSL 也需要本机已存在的 perl/make。不能让 pkg-config 找到 Homebrew macOS OpenSSL 后把 host archive 混进 OHOS 链接。

`ssh2::Session` 已提供 TCP、handshake、host key、password/pubkey auth、channel、PTY/shell/resize，不需手写 SSH。最小顺序为 TCP→handshake→返回/匹配已知 host fingerprint→auth→PTY→shell。未知或不匹配 host key 必须在发密码/私钥之前停止；连接成功与认证成功分开报告。主机信任可直接做 profile 指纹字段，不自建复杂信任状态机。

真实终端读写应使用非阻塞 session/有限等待；不能让一个阻塞 Channel.read 把同 Session 的所有 write/close 卡住。官方说明同 Session 对象共享内部锁，blocking read 会阻塞其余操作；生产验证必须覆盖服务端不回数据时的输入与关闭。[ssh2 Session 官方线程约束](https://github.com/rust-lang/ssh2-rs/blob/master/src/session.rs)

NAPI async work 适合短期 handshake/exec；持续 PTY reader 不能永远占用 libuv worker。可用一条会话 reader 线程 + threadsafe callback，并以明确关闭信号结束；这是实时终端确有的生命周期需求，不要继续扩成泛用任务调度器。execute/reader 只操作 Rust/C++数据，napi_value 的创建和 UI 回调回 ArkTS 线程。

E2 测试应有受控 SSH 服务端：正确指纹及错误指纹、正确及错误认证、PTY prompt/输入、多字节字符、stderr/exit、窗口 resize、命令等待期间 close、页面退出清理、重连。未经这些设备测试，不能称端内 SSH 已闭环。

## 阻断分类与证据边界

1. **当前可处理前置缺失**：OHOS rust std 未安装。执行 target add 后再验证；本轮没改用户 toolchain。
2. **尚未证实的构建问题**：tiktoken/parking_lot 和 vendored OpenSSL/libz 的具体锁版本能否在 SDK clang-15 + Rust 1.96 下完整链接，必须看编译回喂。没有证据时不能写“已支持”或“平台禁止”。
3. **API 基线验收缺口**：当前模拟器 API 24；成功运行不证明 API 12 真机兼容。接口保持 API 10/12，链接 target12，报告仍需标记没有 API 12 设备验收。
4. **SSH服务端验收输入**：即使 native binary 编译成功，也需要可用服务端/可信指纹/认证材料做端内验收；不要读取或输出未授权凭据来虚报完成。
5. **Python/Mosh 范围**：libssh2 仅是 SSH。内嵌 Python 还需要解释器/标准库/扩展模块打包与执行线程；Mosh 还需要 mosh client/UDP/服务端，不可能以“SSH exec 了 python/mosh”替代端内实现。E1/E2 不把它们勾成已完成。

## E1 补充：真实产品 consumer 与调试设备 fixture

### Android model→tokenizer mapping 的实际边界

全仓 Kotlin/Rust 源码检索 `TokenCounter`、`TokenSegment(`、`TokenizableSegment`、`tokenizerId`、`o200k_base`、`cl100k_base` 后，**没有找到 Android 产品中的 model→tokenizer ID 映射或 TokenCounter 的真实调用**。实际只有：

- `core/llm/.../TokenCounter.kt` 接收调用者给定的 `TokenSegment(tokenizerId,text)`，native 失败后按 Kotlin 字符数近似。
- `core/llm/.../TokenCounterNative.kt` 加载 tokenizer JNI SO。
- `core/agent-runtime/.../ModelRouter.kt` 只声明 `countTokens` 接口；`core/agent-runtime-impl/.../LegacyRunScope.kt:89` 的 NoOp 实现返回 `TokenBudget(0,0,0)`。
- Android `app/.../core/context/ContextFootprintEstimator.kt` 仍用 CJK 加权字符估算，和当前鸿蒙 `context_compact.ts` 同源。

所以不能把新增 model mapping 描述为“移植 Android 已使用映射”。现 tiktoken-rs 0.6.0 的模型表较旧，例如缺 gpt-5/4.1；BPE encoding 自身仍有 o200k/cl100k，可依官方模型映射选择 encoding。2026-09-28 查阅 OpenAI 官方 `tiktoken/model.py`：gpt-5、gpt-4.1、gpt-4o、o1/o3/o4-mini 及相应版本前缀为 o200k_base；gpt-4、gpt-3.5/35-turbo 为 cl100k_base；gpt-oss 前缀为 **o200k_harmony**，不能冒充 o200k_base。[OpenAI 官方映射](https://github.com/openai/tiktoken/blob/main/tiktoken/model.py)

最窄产品方案：只对明确支持的 OpenAI 模型名使用 o200k/cl100k；未识别部署别名、其他厂商、gpt-oss 继续既有 CJK 加权估算。不把所有 OpenAI-compatible endpoint 都默认为 cl100k。Claude/Gemini 的 Rust primitives 可以参与 fixture 对照，但它们仅为字节比例近似，不必为了“走 native”替换现有更合适的 CJK 默认估算。

### 最窄真实调用链

建议 E1 产品 consumer 选择 **Chat 已有 Context 用量展示**，不修改发送、裁剪、压缩的同步预算接口，不额外增加产品按钮或工程 smoke 页面。

```text
已有会话/compact/模型更新事件
  → NativeTokenCounter.ets 的 async estimate（调用 native.countBatch）
  → ChatPage @State nativeContextTokens
  → estimatedContextTokens()                     ChatPage:1882
  → contextUsageRatio / contextUsageText           ChatPage:1923 / 1938
  → 顶部 ContextBarsIndicator、用量卡、无障碍文本   ChatPage:5467 / 2980 / 5479
```

现 provider 回传的 `lastTurnUsage()`（ChatPage:2898）用于本次 token、缓存命中、生成速度；native 数字**不能覆盖这份真实 usage**。`formatContextTokens` 只格式化展示，仍使用既有 UI。

首帧/未识别模型/native 失败时返回原 `estimateConversationInputTokens`。支持模型的 native 完成时更新 `@State`，使已有 Context 指示器真实重绘。getter 保持同步只读，不从 build/getter 内开 Promise，也不等待 native 才让用户发消息。

### 默认与 compact 语义必须保留

`estimateConversationInputTokens(messages,compacts)` 当前不是 `toText(messages)/4`：

1. 无有效 compact：估算全量 `currentMessages(conversation)`。
2. 有有效 completed compact：使用现已导出的 `validCompletedCompacts` 去掉被覆盖源消息，再由 `selectCompactsForInjection` + `compactInjectionText` 构建摘要消息。
3. **摘要组与 recent 组分别估算/下限，再相加**；不要合并后只套一个下限。
4. role 开销、reasoning、tool input、已执行 tool output、document fileName、mini app 文本都在 UI 足迹；image/video/audio 为每项 4500 weighted-chars（1125 token 估算），document 另加 80、mini app 另加 120 weighted-chars。

native estimator 可在 `NativeTokenCounter.ets` 内用这些既有导出拆出两个消息组。收集 text/reasoning/tool input/已执行 tool output/document filename/mini app title-description 为字符串 batch；role 开销和非文本固定 footprint 单独累加。每组 `Math.max(nativeTextTokenSum + Math.floor(fixedChars/4), messages.length*4)`，两组相加。`toText` 仅收 text part，会漏 reasoning/tools/多媒体，不能作为此 consumer 的全部输入。不要去改 domain 同步 estimator 的函数签名或全局逻辑。

此 UI 仍为上下文估算：BPE 文本更精确，多媒体与协议开销依旧使用已存在的估算。不要把它标为供应商账单或“整个请求精确 token”。

### 现有窗口参数与缓存失效

| 输入 | 现有消费/缓存语义 | E1 的处理 |
| --- | --- | --- |
| Conversation 内容 | ChatPage:467-469 缓存按 Conversation 对象身份；修改分支/编辑/删除/追加/终态更换对象 | 消息改变即废弃旧 native 结果，重新异步 count |
| active compacts | `refreshContextCompacts():1864` 异步换整个 `@State` 数组；getter 按数组身份 | compact 完成、失效、重载必须重新 count 替换后的组 |
| 选定 model | 当前 numerator 不依赖 model，所以旧缓存没有 model key | 新增当前 `choice.model.modelId` → tokenizer key；模型切换必须失效在途旧结果 |
| contextWindowTokens | 现 `@State` 分母；`contextUsageRatio` clamp 0..1 | 保持 null/<=0 的 0 ratio 与 `--` 文案；窗口大小改变只需刷新分母，同 encoding 可不重算文本 |
| Assistant.contextMessageSize | `resolveChatRuntime`/`prepareContext` 的发送前截断参数 | **当前 UI estimator 没有按它截断**；E1 不趁机改变 UI 全量/compact 默认口径 |
| contextCompaction enabled/ratios/keepRecentTurns/maxSummaryTokens | `agentRuntime` 快照 → `buildPrepareContext` 的压缩规划 | E1 不修改 planner 或其默认值；UI 的 actual compacts 变更时才改足迹 |
| streamingTail | 当前 UI 足迹读 conversation 全量，流式展示另存 streamingTail | E1 保留现边界；不每个流式 chunk 重扫整会话 |

模型有效值必须沿 `resolveEffectiveChatChoice(assistantId)`，不要读取全局选择键自行造第二套 resolver。现模型上下文刷新发生在：`aboutToAppear:1122`、返回页面 `onPageShow:955`、初载/恢复后的 `refreshEffectiveModelState:3541`、顶部选模型 `selectModelFromMenu:3598`。这些点都已有 `choice`，可以用一个窄 `setContextModel(choice)` 替换各自的 `contextWindowTokens` 赋值，同时保存 tokenizer key并触发 count；原 ReasoningPicker 调用保持。

最小刷新接线建议利用已有 V1 状态事件：给已有 `rows` 与 `contextCompacts` 加同一 `@Watch('refreshNativeContextEstimate')`，模型 helper 也调用它。`rows` 在 `refreshRowsAfterConversationChange:1444` 的分支/编辑/删除/追加/生成终态/回滚等共用出口都会整体赋值；首次尾窗/恢复也是整体赋值。无需给 20 个 `this.conversation =` 分别加调用，也不把完整 Conversation 新包成观察 store。历史分页仅 rows 变更时，用已捕获 messageNodes/compacts/model key 身份短路相同来源的重复计数。

`refreshNativeContextEstimate` 只持有本页一个 sequence；先用 messageNodes/compacts/model key 判断是否同一计数来源，相同来源直接返回（含历史分页，不清有效结果）。来源变化才清旧 native 值并递增 sequence，捕获 conversation ID + full messages + compacts + model key，await 后要求 pageAlive/sequence/conversation ID/当前 model key 仍匹配再写 `@State`。离页递增 sequence。metadata-only title/suggestions 更新无需计数；不引入 fingerprint 框架、计数 debounce队列或全局任务注册表。

计数重绘的验证至少覆盖：长中文/英文选支持模型后 native 数值进入原 Context UI；含图、reasoning、tool output 不消失；compact 完成后原消息被替换；快速换模型/分支时旧结果不覆盖；离页后无写 state；native 失败继续原估算；provider 的本次/cached usage 不被改写。

### DEBUG-only Want fixture 与 hilog 回传

仓内已有 `PermissionStatusPortImpl.ets:18/36` 使用 `import BuildProfile from 'BuildProfile'` 与 `BuildProfile.DEBUG`，可以直接沿先例。现 `entry/build/default/generated/profile/default/BuildProfile.ets` 是 **release / DEBUG=false**；默认 assembleApp 产物不能拿来证明调试 fixture 没运行。

增加很小的 `NativeTokenizerFixture.ets`（或者同文件独立方法），固定已知样例/expected，入口受 `BuildProfile.DEBUG` 与 Want fixture selector **双门**。EntryAbility `onCreate:188` / `onNewWant:214` 调用同一 runner；不增加正常 UI、设置项、导航、AppStorage 开关。Want 只选择预置测试组与 run ID，不收任意代码、密码或用户文本。

推荐 Want 键：`amberNativeFixture=tokenizer-v1`、`amberNativeRunId=E1-20260928-a`。逐字段读取 `want.parameters`，不用裸 `Record<string,object>` assertion，不新增运行时 typeof 判型；selector 只有等于固定字符串才通过。冷启动和前台重入均运行同一入口。

fixture 调同一个 production `countBatch`；每个 fixed case 记录 `fixtureId/tokenizer/actual/expected/pass`，最后用固定 tag `Amber.NativeFixture` 打一行 public JSON。数据只含固定 fixture 的 ID/数字结果，不输出应用消息或配置。序列化后的结果建议保持在几百字节；通过 run ID 区分本轮，不清系统日志。

本轮实际核对过模拟器的 `aa start -h` 与 `hilog -h`：支持 `--ps key value`、hilog `-x -T`。离线官方文档也明确这两个用法：

- `开发指南/调测调优/调试命令/aa工具/aa-tool`
- `开发指南/调测调优/调试命令/hilog/hilog`
- `开发指南/定制构建/获取自定义编译参数/能力说明/ide-hvigor-get-build-profile-para-guide`
- `开发指南/定制构建/灵活定制编译选项/实践说明/ide-hvigor-compilation-options-customizing-sample`

实施阶段命令：

```sh
# 官方 buildMode 用法；保持项目既有 SDK 定位封装。
node scripts/harmony-inventory/build-harmony-app.mjs \
  --mode project -p product=default -p buildMode=debug assembleApp --no-daemon
hdc -t 127.0.0.1:5555 install -r \
  harmony/entry/build/default/outputs/default/entry-default-signed.hap
hdc -t 127.0.0.1:5555 shell aa force-stop app.amber.deepread
hdc -t 127.0.0.1:5555 shell \
  "aa start -b app.amber.deepread -a EntryAbility --ps amberNativeFixture tokenizer-v1 --ps amberNativeRunId E1-20260928-a"
hdc -t 127.0.0.1:5555 shell "hilog -x -T Amber.NativeFixture"
```

等待实际 fixture completion 后再读非阻塞日志；宿主脚本按本轮 run ID 精确解析 JSON，对每个 expected 逐项相等才判通过。“aa start success”、没有崩溃、只出现 loaded 日志均不算 fixture 通过。用正常无参数启动验证不执行 fixture，再装 release、带相同 Want验证 DEBUG 门拒绝。`onNewWant` 再发相同 selector/新 run ID，补验证热启动路径。

本补充仍是只读方案与命令核对，未执行这些 build/install/fixture 动作；待主 agent 的实现与设备回传验证。

## E1 后续：Hvigor 独立 Ninja 执行覆盖（2026-09-28）

结论：当前官方 `@ohos/hvigor-ohos-plugin@6.26.2` 有主入口运行时导出的 `registryCommandModifier` / `CommandBuilderType.NINJA`，可以在项目 `entry/hvigorfile.ts` 注册一个 modifier，仅替换 Ninja 可执行文件。这个 hook 已用只读内存测试验证其数组语义，优先于 SDK overlay。但 `package.json` 指向的公开 `types/index.d.ts` 未声明这两个成员，离线官方构建 API 文档与精确 Web 搜索也未命中，因此应注明“绑定 6.26.2 的未文档化运行时导出”，不能声称其有官方稳定 API 保证。

未修改 SDK、全局工具、业务代码、设备状态；未读取根 `build-profile.json5` / signingconfig。HAP 完整构建尚由主 agent 验证。

### 精确源码证据与数组语义

以下路径相对 `/Users/arquiel/Library/Huawei/commandline/command-line-tools/hvigor/hvigor-ohos-plugin/`；源码 JS 为单行压缩，指向文件与符号比行号更有效。

- `index.js`：主入口 `Object.defineProperty(exports, "CommandBuilderType", ...)` 与 `Object.defineProperty(exports, "registryCommandModifier", ...)`，转发 `src/builder/command-builder-modifier.js`，无需 deep import。
- `src/builder/command-builder-modifier.d.ts`：`registryCommandModifier(cmdBuilderType: CommandBuilderType, modifier: CommandModifier): void`；`CommandModifier` 是 `{ modify: (command: string[]) => string[] }`。枚举 `NONE=0, CMAKE=1, NINJA=2`。
- 同名 `.js`：静态 Map 按命令类型保存单个 modifier；同类型再次注册会替换之前的 modifier。`wrapCommandBuilder` 包装原 `build()`，先取原数组，若已注册则返回 `modifier.modify(array)`。不要异步返回 Promise，不要返回字符串。
- `src/tasks/build-native-with-ninja.js` 的 `buildCommand`：原数组为 `[sdkInfo.getNativeNinjaTool(), '-C', <getNinjaWorkDir()/ABI>]`；创建 `CommandBuilderModifier(builder, NINJA).wrapCommandBuilder().build()` 后才 `push(...externalNativeOptions.targets)`，最后 `executeCommand(array, ...)`。
- `src/tasks/abstract-build-native.js` 的 `nativeExecution`：提交到 worker 的已经是替换过的 `commandLine`；worker 调用 `spawn(commandLine[0], commandLine.slice(1), ...)`，因此注册发生在主进程即可，不需要 worker 里再次注册。
- `src/sdk/impl/sdk-native-component.js`：Ninja 固定拼 `getBaseDir()/build-tools/cmake/bin/ninja`，`getNinjaTool` 只返回私有 `_ninjaTool`。`src/sdk/info/common-sdk-info.js` 的 `getNativeNinjaTool` 只转发该 getter。这里没有环境变量或 setter，改 PATH 无效。
- `src/plugin/context/plugin-context.d.ts` 的公开 `OhosModuleContext` / `Target` 只提供 profile/buildOption 等配置 getter/setter，未提供 native SDK/Ninja 路径 setter。`OhosProjectContext.getSdkDetails()` 也是只读 SDK 信息。

只读测试直接加载 `command-builder-modifier.js` 与 `base-command-builder.js`，对原数组注册 `modify: command => [armNinja, ...command.slice(1)]`，`build()` 后再加 `amber_native`，得到：

```text
['/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/native/build-tools/cmake/bin/ninja',
 '-C', '/project/.cxx/default/arm64-v8a', 'amber_native']
replaced=true, preservedDirectory=true, targetAddedAfterModifier=true
```

`file` 已核对 Studio Ninja 为 Mach-O arm64，CLI SDK26 Ninja 为 Mach-O x86_64；Studio Ninja `--version` 实测 `1.12.0`。这只替换宿主构建工具，不替换 CLI API26 编译器、sysroot、ets、toolchains 或 SDK metadata。

### 最窄项目实现建议

在原有 `entry/hvigorfile.ts` 里用主入口 namespace import 配合局部 intersection assertion，弥补 package 类型缺失；不要 patch plugin / SDK，不需要额外 package、类型框架或 subclass：

```ts
import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import * as ohosPlugin from '@ohos/hvigor-ohos-plugin';

type NativeCommandHooks = {
  CommandBuilderType: { NINJA: number };
  registryCommandModifier: (
    type: number, modifier: { modify: (command: string[]) => string[] }
  ) => void;
};

const ninja = process.env.AMBER_NATIVE_NINJA;
if (ninja) {
  const hooks = ohosPlugin as typeof ohosPlugin & NativeCommandHooks;
  hooks.registryCommandModifier(hooks.CommandBuilderType.NINJA, {
    modify: command => [ninja, ...command.slice(1)],
  });
}

export default { system: hapTasks, plugins: [] };
```

这是建议片段，未写入工程。没有环境变量时仍走原 SDK 行为，普通支持该 SDK Ninja 的主机无需接入 hook。`6.26.2` 已在工程 hvigor-config 中锁定，升级版本需重新核对主入口导出与 task 调用。由于官方 `.d.ts` 未导出，直接 `import { registryCommandModifier } ...` 会有 TS “无导出成员”错误；局部 assertion 解决编译类型，不改变运行时保证，也不应作为正式文档化 API 描述。

build wrapper 复用已有 SDK 路径：先取显式 `AMBER_NATIVE_NINJA`，否则探查 `${ohosBaseSdkHome}/native/build-tools/cmake/bin/ninja`。对候选执行 `spawnSync(candidate, ['--version'])` 判定实际可运行；SDK Ninja 不能运行时才探查已有 Studio 的 Ninja，并以同样命令验证。当前失败 `-86` 已是具体触发条件，无需建立架构解析器、下载器、复杂 fallback 列表。全部候选失败就报告具体路径/错误，不能改 SDK 或降 compileSdkVersion。成功路径放入仅本次 Hvigor 子进程的 `env.AMBER_NATIVE_NINJA`。

**CMake 与 Hvigor Ninja 必须使用同一路径。** 当前 `externalNativeOptions.arguments` 的 `CMAKE_MAKE_PROGRAM` 已能覆盖 CMake，但硬编码本机 Studio 路径不适合保留。可在模块级自定义 plugin 中依托文档化 `OhosHapContext.getBuildProfileOpt()/setBuildProfileOpt()` 设置已有 `buildOption.externalNativeOptions.arguments`（保留其他 arguments，仅将 CMAKE_MAKE_PROGRAM 项替换为该 env 路径）。官方示例在 `hvigor.nodesEvaluated` 内修改 module profile；不需要读取或修改工程根 profile。备选是在 CMakeLists 的 `project()` **前**读取该 env 并 FORCE 设置 CMAKE_MAKE_PROGRAM，主 agent 可选择更短、现有代码更顺的一处。当前插件任务源码中只有 `BuildNativeWithNinja` 使用 `CommandBuilderModifier`，不要因为枚举有 CMAKE=1 就假设 CMAKE modifier 也被任务调用。

### 正式文档入口与验证边界

已读本地官方：

- `开发指南/扩展构建能力/开发Hvigor插件/ide-hvigor-plugin`：允许直接在 hvigorfile 脚本扩展；默认 `system` 保留。
- `开发指南/扩展构建能力/扩展构建API/插件上下文/ide-build-expanding-context`：公开 module profile get/set 与 `nodesEvaluated` 示例，没有 Ninja setter。
- `开发指南/扩展构建能力/扩展构建API/API使用示例/ide-build-expanding-sample`：公开注册任务、依赖、setEnable，可作替代 prebuild task 但比单个 runtime hook 多改现有流程。
- `最佳实践/定制hvigor插件/bpta-custom-hvigor-plugin`：自定义任务/插件的正式扩展机制，没有 Ninja tool override 示例。

验收须重新执行真实 Debug HAP 构建，确认：CMake 配置命令指向可运行的 ARM Ninja、`BuildNativeWithNinja` 实际命令也指向同一路径、`libamber_native.so` 进入 HAP、CLI API26 SDK 路径不变。只读 modifier 测试或 CMake 单独成功不能代替这些验收。

### 如必须完全回避未文档化 hook

不应盲切到 Studio API24 SDK。项目局部 overlay 仍可沿公开 `DEVECO_SDK_HOME` / `OHOS_BASE_SDK_HOME` 入口实现，但布局与维护明显多于上述 hook：overlay 真实目录必须有 `default/openharmony/native/build-tools/cmake/bin`，保留 CLI26 的 `default/sdk-pkg.json`、`default/hms`、其他 OpenHarmony 组件、native 内除所需祖先目录以外的所有条目指向 CLI26 原路径；仅该 `bin/ninja` 指向已验证的 ARM Ninja。不能把整个 native 目录 symlink 到原 SDK，否则 Ninja 又回到 x86_64。`sdk/superior/sdk-component.js` 使用 SDKmanager component 的 `getLocation()` 作为 native baseDir；需要验证 SDKmanager scan 没有 realpath 归并，才算 overlay 成功。此处只查证了目录与取址逻辑，未创建 overlay、未宣称它已通过 SDK scan。当前已有可验证主入口 hook，不建议增加 overlay。
