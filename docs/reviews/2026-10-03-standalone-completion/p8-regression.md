# P8 最终纯代码回归

2026-10-03；共享工作树 HEAD `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`。该 SHA 标识工作树基点，本次结果包含当前未提交源码和测试。仅运行测试、类型检查与记录结果，没有修改生产代码、构建、签名或安装。

## 最终结果

| 验证组 | 通过 / 总数 | fail / cancelled / skipped | exit | 日志 |
| --- | --- | --- | --- | --- |
| DeepRead 全部 TS + CJS | 1444 / 1444 | 0 / 0 / 0 | 0 | `/tmp/amber-p8-deepread-final-tests.log` |
| Chat 全部 TS + CJS | 2848 / 2848 | 0 / 0 / 0 | 0 | `/tmp/amber-p8-chat-tests.log` |
| Entry 全部 CJS | 241 / 241 | 0 / 0 / 0 | 0 | `/tmp/amber-p8-entry-final-tests.log` |
| standalone prepare | 6 / 6 | 0 / 0 / 0 | 0 | `/tmp/amber-p8-prepare-tests.log` |
| DeepRead tsc | 通过 | 0 error | 0 | `/tmp/amber-p8-deepread-final-tsc.log` |
| Chat tsc | 通过 | 0 error | 0 | `/tmp/amber-p8-chat-tsc.log` |

最终 **4539/4539** 测试通过，0 fail / 0 cancelled / 0 skipped。双模块 tsc 均退出 0，日志无类型错误。Chat 包含 184 个 suites；其余组 node:test 汇总 suites 为 0，测试数以每组最终统计为准。

## 首轮失败与精准修复

首轮共 4539 个测试，4538 通过、1 失败，DeepRead 退出 1。首轮日志保留在 `/tmp/amber-p8-deepread-tests.log`；该轮的 DeepRead tsc 已通过，日志为 `/tmp/amber-p8-deepread-tsc.log`。

失败为 `deepread/src/test/product_p1.test.ts:230` 的 `P1: deferred commit cancellation settles success and publishes the saved completion`：既有 observeRunning 布尔契约要求精确数组 `[false,true,false]`，P7 onProgress 新增调用 publishRunning 导致阶段更新重复发布 `true`。实际保存成功、完成通知与最终 false 断言未失败。

先向 root 报告；root 核对后授权 runtime owner 将既有 active-run 发布提取为 publishActiveRuns，onProgress 只通知该流，保留 observeRunning 原有布尔通知语义。原测试断言没有修改。runtime owner 的定向测试 24/24、tsc 通过后再次冻结源码；本任务仅重跑受影响的 DeepRead 全部 TS+CJS 和 tsc，最终 1444/1444、exit 0。未受该单文件变更影响的 Chat、Entry 和 prepare 沿用首轮通过结果，没有重复计数。

## 最后 UI 尺寸冻结后的补跑

2026-10-03 05:58（Asia/Shanghai）确认 Entry 最后一次全量补跑结果：241/241、exit 0，0 fail / 0 cancelled / 0 skipped，耗时 2194.290333 ms。该次覆盖 root 最后冻结的两处纯 UI 尺寸调整：`DeepReadComposePage` 主题 TextInput 固定高度 44，`DeepReadConfirmationDialog` 两个动作最小高度 44。没有业务或 API 变化，因此只重跑受影响的 Entry；DeepRead、Chat、prepare 及两个领域模块 tsc 沿用上表已通过结果，合计仍为 4539/4539。Entry 首轮日志 `/tmp/amber-p8-entry-tests.log` 保留，最终日志为 `/tmp/amber-p8-entry-final-tests.log`。触控尺寸的设备实测证据由 root 单独记录，CJS 通过不代替截图验收。

## 命令与边界

DeepRead 和 Chat 分别在自身模块运行：

```sh
./node_modules/.bin/tsx --test src/test/*.test.ts src/test/*.test.cjs
node node_modules/typescript/bin/tsc --noEmit
```

Entry 在 `harmony/entry` 使用仓库已有 chat tsx loader 与路径映射运行：

```sh
TSX_TSCONFIG_PATH=../chat/tsconfig.json node --import ../chat/node_modules/tsx/dist/loader.mjs --test src/test/*.test.cjs
```

仓库根执行 `node --test scripts/harmony-standalone/prepare.test.mjs`。各组输出独立保存，上表 exit 来自实际进程退出，不以日志关键字替代。

本轮 Chat 原生 vsync 测试夹具 C++ 编译输出一条 `-Wunused-but-set-variable` warning：临时 `test.cpp:96` 的 `frames` 变量已赋值但未使用；所属测试通过。这是测试夹具编译警告，不是 tsc 错误。本任务没有执行 ArkTS lint，不将此前阶段 warning 数或测试名中的 WARN/warnings 文本当成本轮新增错误。runtime owner 对上述单文件修复另行 lint 的实际日志为 `/tmp/amber-p8-progress-boolean-lint.log`：0 error、73 warning，分别记录其数量，不把类型推断类 lint warning 计作类型检查失败；定向 RED/GREEN/tsc 日志为 `/tmp/amber-p8-progress-boolean-{red,green,tsc}.log`。构建、签名、设备与真实 provider 验收由 root 的其他 P8 gate 独立报告。
