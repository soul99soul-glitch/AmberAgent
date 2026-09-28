# E1 native core 独立审查

日期：2026-09-28。审查范围：`harmony/native/rust-napi/`、`harmony/entry/src/main/cpp/` 和 `E1-native-core.md`。
只新增本报告；未修改原生源码、构建接入配置或产品 consumer，未操作设备。

**初次预备审结论：原生核心源码审查通过，未发现需要修复的可复现缺陷。后续产品接入、设备及 C 验收结果见追加节；阶段最终状态由主 agent 按全部门槛收口。**

## 边界和调用链

- Rust C ABI 的 `uint32_t`/`size_t`/`int64_t` 与 arm64 的 `u32`/`usize`/`i64` 对应。
  输入只在调用期间借用；没有 Rust 分配对象跨 C++ allocator 释放。
- `len == 0` 直接采用空 slice，不对 null 指针调用 `from_raw_parts`；非空 null 和非法 UTF-8 返回明确负值。
  任意无效指针仍属于 C 调用方的 unsafe 合同，不建议在此添加无法验证指针可读性的兜底。
- BPE 和近似算法都按显式字节长度工作；special token 字面量走 ordinary 编码。
  FFI `catch_unwind` 将 Rust panic 转为 `-4`；当前构建没有启用 `panic=abort`。
- C++ 在发起 async work 前完成所有 NAPI 数组/字符串读取并复制到 `CountJob`。
  `ReadString` 先取得 UTF-8 长度、分配含终结符的缓冲区，再用实际 copied 长度收缩，保留内嵌 NUL。
- Execute 只使用 native 数据和 Rust C ABI，不在工作线程调用 NAPI。
  每个 job 独立持有 counts/error；共享 BPE 用依赖原有 `parking_lot::Mutex` 保护，没有可变无锁缓存。
- 输入失败与 async work 创建失败由 `unique_ptr` 释放 job；queue 失败先删除 work，再释放 job。
  queue 成功后才 `release()`，Complete 收回唯一所有权，成功、native 错误和非 `napi_ok` 状态都会删除 work 并释放 job。
  未发现重复释放、未初始化 counts 暴露或执行中悬空的 JS 字符串引用。

## 独立验证

1. `cargo test --locked --offline --manifest-path harmony/native/rust-napi/Cargo.toml`：**6/6 通过**。
   覆盖空输入、ASCII、中文、emoji、换行、内嵌 NUL、special literal、长文本、错误 ID、非法 UTF-8/null 和受控 panic。
2. 使用原样 `napi_init.cpp` 和当前 host Rust staticlib 构建 macOS Node-API addon。
   仅在 `/tmp/e1-native-review/include/napi/native_api.h` 将 OHOS 的 include 名转接到宿主官方 `node_api.h`，未改回调实现。
   `/tmp/e1-native-review/napi.test.cjs` 执行真实 Node-API/Promise，**12/12 通过**：
   - 全部登记 fixture 的真实 UTF-8 转换，包括 NUL 与 81920-byte 长文本；
   - 空 batch/空文本 Promise 落定；
   - 调用返回后修改、清空输入数组不影响已提交任务；
   - 10 种非法参数均返回被拒绝的 Promise，随后正常调用仍成功；
   - 32 个并发 async job 结果保持输入顺序；
   - 4 个独立 JS worker environment，各自并发 8 次 BPE 调用，结果正确并正常退出。
   证据：`/tmp/e1-native-review/napi-test.log`。这证明宿主 Node-API 的执行路径，不等同于 Ark 引擎设备验收。
3. 独立读取 `/tmp/e1-make/libamber_native.so` 的 ELF：AArch64、SONAME `libamber_native.so`，注册入口存在。
   DT_NEEDED 仅 `libace_napi.z.so`、`libc.so`、`libc++_shared.so`，没有 JNI/Android 或额外 deviceinfo 依赖。
4. 核对实际 CMake link 命令：`--target=aarch64-linux-ohos12.0.0`、`--no-undefined`、Rust archive 与 `unwind/c` 均实际参与链接。
   Rust linker 脚本同样明确为 `aarch64-linux-ohos12`，不只依靠报告中的 API 12 文案。
5. 所用 NAPI 的本机官方 SDK 声明与 async 执行/完成线程语义一致，接口标记 `@since 10`，未引入高于 API 12 的 NAPI。
6. 两个构建脚本 `sh -n` 与范围内 `git diff --check` 通过。

## 剩余阶段门槛

- entry 真实配置、依赖包和 native 库打包仍需接入并检查最终 HAP，而非仅独立 ELF。
- OHOS 上重复空输入、NUL、非法参数、并发调用、错误恢复的 fixture；确认 Ark NAPI 的转换与宿主结果一致。
- consumer 需在模型/对话变化和页面退出后丢弃旧 async 结果，保留同步估算及实际 provider usage 的既有语义。
- 当前连接设备高于 API 12；即使这些设备通过，也不能将 API 12 运行时兼容性写成已实机验证。

不要求为假设的 OOM、损坏地址或未知未来 ABI 添加重试、接口层或备用状态机。

## E1 产品接入追加审查

追加范围：`NativeTokenCounter.ets`、`native_context_tokens.ts` 及测试、ChatPage 新增 native Context 状态与调用点、
`NativeTokenCounterFixture.ets`、EntryAbility Debug gate、entry native 配置。不重复上述原生核心审查。

### P2：旧有效模型读取可覆盖新模型，已修复并复查

进入已有助手会话时，aboutToAppear 在 `conversation == null` 下发起全局有效模型读取。
随后全量会话加载完成，`refreshEffectiveModelState()` 可先装入助手绑定模型；较早的全局请求晚返回时，
其 callback 只检查 `pageAlive/pageVisible`，仍会 `setContextModel(globalChoice)`，将绑定模型的 Context 窗口和 tokenizer 覆盖。
相同助手快速切模的多个读取也只有身份检查，没有统一请求顺序。

具体位置（审查时）：ChatPage `onPageShow` 968、`aboutToAppear` 1136、`refreshEffectiveModelState` 3595、
`selectModelFromMenu` 3651。native count Promise 自身的 seq/model 检查无法挡住此问题：旧 resolver 的错误模型
已经被登记为“当前模型”，计数会合法地套用错误 encoding。

最小修法：四个读取入口共用带请求序号与 conversation/assistant 身份的有效模型刷新入口；
同一 choice 同步更新 label、窗口、tokenizer 和 reasoning，拒绝旧 success/error；退出时使未完成读取失效。
该修复已反馈主 agent 与 consumer worker，并已落地。复查后的 ChatPage：

- onPageShow、aboutToAppear、全量/恢复会话加载完成和菜单切模后均调用同一 `refreshEffectiveModelState()`。
- 入口递增 `contextChoiceSeq` 并捕获 conversationId/assistantId，success/error 都核对序号和两种身份。
- aboutToDisappear 递增同一序号，旧请求不会在页面复用后恢复有效。
- modelLabel、Context 窗口/tokenizer 和 reasoning 只从通过守卫的同一 choice 更新；独立标签 then 已移除。
- 旧 `describeEffectiveModel` 依 AGENTS 规则只标记未使用并保留，没有顺手删除。

上述旧全局请求/绑定会话请求逆序和同助手多个读取逆序路径现在都会拒绝旧结果。未扩展生成行为。

### 其他 A/B 结果

- `NativeTokenCounter.ets` 只有包装调用，无状态、I/O 或 provider 结果写回。
  `countNativeContextFootprint` 在 await 前复制文本、分组边界和固定开销，调用期间原消息改变不会混入旧结果。
- ChatPage native request 捕获 messageNodes、modelId、conversationId 和 seq，回写检查 pageAlive、seq、身份与节点引用；
  getter 再检查节点/compact/model/会话身份，只采用当前快照的结果。历史分页 prepend 不改变完整会话 nodes，复用计数有依据。
- 所有计数消费者均为既有 Context 显示/比率/柱形和 usage 面板中的 Context 行。
  `lastTurnUsage()` 仍直接读取 assistant message 的实际 usage；没有把 native 数字写入 message usage，
  也未替换 `prepareContext`、`estimateTokens` 或 `fitMessagesToTokenBudget` 的同步预算。
- compact 选择复用 `validCompletedCompacts`、`selectCompactsForInjection`、`compactInjectionText`，覆盖集合与旧显示估算一致。
  摘要与剩余消息各保留独立四 token/message 下界；无效分支或未完成 compact 不隐藏正文。
  role、媒体、document、mini_app 开销保持原口径。旧 estimator 的“executed tool”条件就是 `output.length > 0`，
  新遍历空 output 的行为等价，不存在额外的 pending output 分支差异。
- 独立运行 `tsx --test src/test/native_context_tokens.test.ts`：**10/10 通过**。
- Debug fixture 固定 18 个数值/错误验证，Want 只接收固定 selector 与有限 ASCII runId；没有任意文本、文件或命令执行。
  EntryAbility 的 onCreate/onNewWant 均由 `BuildProfile.DEBUG` 守住调用。
  release 不运行 fixture 的事实仍应由主 agent 的 release 冷/暖启动日志确认。
- entry config 连接真实 CMake 路径、arm64 ABI 和类型包；绝对 Ninja 路径是本机工具链修正，已在 core 报告明确。

当前追加审查状态：A/B 源码闭环通过，P2 已关闭；设备及 UI 证据由主 agent 收口。

## E1 runtime chain 与 C 追加验收

独立解析主 agent 保留的原始 hilog：

| 文件 | 核对的 runId | 唯一 fixture | complete |
| --- | --- | ---: | --- |
| `/tmp/e1-emu-native-cold.log` | E1-emu-cold | 18/18 PASS | completed=expected=18, PASS |
| `/tmp/e1-emu-native-warm.log` | E1-emu-warm | 18/18 PASS | completed=expected=18, PASS |
| `/tmp/e1-device-native-cold.log` | E1-device-cold | 18/18 PASS | completed=expected=18, PASS |
| `/tmp/e1-device-native-warm.log` | E1-device-warm | 18/18 PASS | completed=expected=18, PASS |

warm 文件还包含前一次 cold 记录；按 runId 过滤后逐项检查 fixtureId 唯一性、actual==expected 和 pass，
没有将重复冷启动日志误计为暖启动结果。原始记录含实际 app 进程的 `Amber.NativeFixture` tag。
这使输入、真实 Ark NAPI 转换、Rust 计数、async completion、数值日志链路闭环；没有将模拟器结果当 API 12 实机证明。

正常产品 consumer 另有明确的差异证据：`/tmp/e1-context-expect.mts` 直接导入生产
`countNativeContextFootprint`、旧 `estimateConversationInputTokens` 和 `formatContextTokens`，调用同一实际 C++/Rust 宿主 addon。
该受控会话得到 native **1430→1.4K**，旧 estimate **1601→1.6K**。
截图 `/tmp/e1-context-native.jpeg` 的正常 Chat Context popup 显示 **1.4K / --**，
符合 native 路径，并保留 provider 实际 **110 tok**。未使用专用开发页面代替正常 UI 消费。
审查者再次运行同一生产 helper 组合，结果存 `/tmp/e1-native-review/context-expected.log`。

C 布局：卡片右锚定、标题/Context 左对齐、数值右对齐，三项 usage 标签与值的列起点一致；
边距、字号层级、卡片圆角和底部间距没有重叠或裁切。受控 fixture 的 `100000 tok/s` 也在右列内完整显示。

### C 局部可读性问题，已修复并复查

当前 screenshot 的 Context 核心数值与三个统计 caption 很暗。
源码分别为 11.5vp 的 Context value 与 10.5vp 的 `metaStat` label 使用 `INK4`，
实际 screenshot 文字核心约灰色 #707070、卡片背景 #252525；普通小字的对比不足。
建议只把这两处 `fontColor(INK4)` 换为 `INK2`，保留字号、布局和全局主题 token。
这是既有面板的具体局部问题，已反馈主 agent，未要求扩大主题改造。
随后两处已精准改为 `INK2`，独立源码复查确认字号、布局和全局 token 均保持。
最终 Release 截图 `/tmp/e1-context-native-dark-fixed.jpeg` 已独立目测：Context 的 **1.4K** 与三个 caption
明显可读，仍与标题/数值正确对齐；改色没有带入间距、字号、卡片尺寸变化。该 C 问题已关闭。

## 最终 Release 与未知模型证据复查

- `/tmp/e1-context-fallback.jpeg` 显示同一受控正文的 **1.6K / --**，实际 provider **110 tok** 保留。
  这与已独立执行的旧估算 **1601→1.6K** 一致，并区别于受支持模型的 native **1430→1.4K**。
  域测试中的未知别名/其他 provider/gpt-oss 返回 null，源码 null 分支只用既有同步估算，不请求不支持的 encoding。
- 独立读取最终生成的 `harmony/entry/build/default/generated/profile/default/BuildProfile.ets`：
  `BUILD_MODE_NAME='release'`、`DEBUG=false`。
  EntryAbility onCreate/onNewWant 两处 fixture 调用均保留 `if (BuildProfile.DEBUG)`。
- 解析 `/tmp/e1-emu-native-release.log`：0 行；`/tmp/e1-device-native-release.log`：38 行，全部属于之前的 Debug cold/warm。
  对应固定 `E1-emu-release-blocked`、`E1-device-release-blocked` 的 fixture 记录均为 **0**。
  因而源码 release gate 与主 agent 的两设备启动后日志一致；没有把残留 Debug 行误判为 Release 执行。
- `/tmp/harmony-remaining-E1-release-build.log` 包含最终 Release `BUILD SUCCESSFUL`。
  审查者没有重新安装或操作设备；安装/启动动作由主 agent 的设备回路提供。

## 最终亮色 C 收口

独立目测最终 Release `/tmp/e1-context-native-light-fixed.jpeg`：

- Context **1.4K / --** 和 usage **110 tok** 保持；与本地受控 SSE provider 的数据及生产 helper/native 结果一致。
- 两处 `INK2` 在亮色卡面上清晰可读；标题、Context 标签/右值、三项统计 caption/value 均对齐。
- 数值、长速度文本、轨道、分隔线、圆角及右锚定边距没有错位、重叠、裁切；与深色布局一致。
- 不把本地 dummy provider 的验收扩称为外部真实 provider 凭据/服务验收。

**E1 独立审查最终结论：A/B/C 与所提供运行证据通过；已发现的有效模型读取竞态和 Context 局部低对比问题均已修复并复查，没有剩余具体缺陷，可按计划关闭 E1 gate。**

证据边界保持：API 12 为已核验的编译/链接基线；实际执行验证来自 API 24 模拟器
（`127.0.0.1:5555`）与 API 26 真机 HOP-AL00（`6HK0226627000384`）。版本取自主 agent 当前
HDC 的 `const.ohos.apiversion` 读值；没有把这些结果写成 API 12 真机运行验证。
主 agent 已将最终 HAP 重新安装到两设备，并确认真机 `aa start` 成功。
后续 SSH/Python/Mosh 等批次不属于本 E1 完成结论。
