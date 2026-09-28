# E1 native packaging and fixed Debug fixtures

日期：2026-09-28。所有权为 entry `build-profile.json5`、`oh-package.json5`、
`EntryAbility.ets` 的两个 Debug 入口，以及新增 `NativeTokenCounterFixture.ets`。
未修改产品 consumer、Rust/C++ core、根项目签名配置；未构建 HAP、未签名、未操作设备。

## 已接入

- `externalNativeOptions` 指向真实 CMakeLists，ABI 只编当前模拟器和真机所用的 `arm64-v8a`。
- CMake arguments 使用已有 DevEco Studio arm64 Ninja，不安装或替换系统工具。
- entry 依赖 `libamber_native.so` 的本地 types package。
- Ability 的 `onCreate` / `onNewWant` 在原深链派发后各加一个 `BuildProfile.DEBUG` 调用；
  原路由、字体、后台任务、窗口生命周期保持原顺序。
- 固定 selector `amberNativeFixture=tokenizer-v1`，run ID 只接受 1–64 字符的字母、数字、点、
  下划线、连字符；没有任意代码或文本参数、UI 工程入口、AppStorage 开关。
- 直接调用 production native `countBatch`，固定八种文本逐一比较四种 tokenizer 的 Rust expected。
  内嵌 NUL 使用真实 `\u0000`，special token 字面量按 ordinary 语义，长样例重复 4096 次。
- 另验空 batch、数组长度不等、未知 tokenizer ID、两个参数非数组、两个数组元素非字符串、
  两个并行 batch、失败后恢复。总计 **18 个结果**；全部比较通过且 completed=18 才输出 complete/pass=true。
- 故意错误的输入由固定 JSON 构造，以到达真实 NAPI 校验，不改生产 d.ts 或使用 `any` / `unknown`。
- `Amber.NativeFixture` 每个结果为 public JSON：runId、固定 fixtureId、actual/expected 数字数组、pass。
  不打印 fixture 正文、用户消息、配置、异常全文。非预期失败保留已完成计数并输出 pass=false。

## 本次验证

1. 按 skill 查本地官方 `配置CPP/ide-hvigor-cpp`，确认 arguments 支持字符串数组、
   path 与 ABI 字段用法；查当前 Want 文档与 SDK d.ts，parameters 为 `Record<string,Object>`，
   直接逐字段读取，不新增裸 Record 断言或运行时 typeof。
2. 两份配置 JSON 解析成功，本地依赖、CMakeLists 与 arm64 ABI 核验成功。
3. 目标 Ninja `file` 显示 Mach-O arm64，路径真实存在。
4. 两个 ETS 文件实际 CodeLinter 完成，**0 error / 0 warning**，退出码 0，
   日志 `/tmp/e1-native-pack-lint.log`。首轮受 sandbox 拦截工具目录日志，按同一检查范围获得权限后重跑。
5. `git diff --check` 通过。

没有把源级检查当作 native 设备调用验证。主 agent 仍需完整 HAP 打包、加载、冷启动/热启动
18 项结果回传，以及正常启动和 release 带相同 Want 时不执行夹具的验证。

## 已定位的 Hvigor Ninja 矛盾

CMake 的 `CMAKE_MAKE_PROGRAM` 参数能覆盖 configure 阶段的 Ninja，但当前 Hvigor 插件
`src/tasks/build-native-with-ninja.js` 的 `buildCommand` 单独构造
`NativeCommandBuilder(this.sdkInfo.getNativeNinjaTool())`，不读取该 CMake 参数。
command-line SDK 的 Ninja 是 x86_64，DevEco Studio SDK 的 Ninja 是 arm64。

因此全 HAP 的 native build 阶段仍可能调用不兼容的 command-line SDK Ninja；必须以实际构建结果确认。
若命中，建议在项目构建入口选择已有 DevEco Studio SDK，让 Hvigor 的 SDK 工具定位一致，
不替换 SDK 全局二进制。我已将此具体证据报告主 agent，未擅自扩大文件所有权。

## 主 agent 设备回传命令

```sh
node scripts/harmony-inventory/build-harmony-app.mjs \
  --mode project -p product=default -p buildMode=debug assembleApp --no-daemon
hdc -t 127.0.0.1:5555 install -r \
  harmony/entry/build/default/outputs/default/entry-default-signed.hap
hdc -t 127.0.0.1:5555 shell aa force-stop app.amber.deepread
hdc -t 127.0.0.1:5555 shell \
  "aa start -b app.amber.deepread -a EntryAbility --ps amberNativeFixture tokenizer-v1 --ps amberNativeRunId E1-cold-a"
hdc -t 127.0.0.1:5555 shell "hilog -x -T Amber.NativeFixture"
```

精确按本轮 run ID 解析 18 项和 complete；更换 run ID 再触发 `onNewWant`。
正常无 selector 启动应无本轮夹具结果；release / DEBUG=false 带相同 selector 也应无结果。
当前模拟器为 API 24，以上运行通过也不构成 API 12 设备验证。
