# 第二轮最终纯代码回归

2026-10-03；共享工作树 HEAD `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`。该 SHA 仅标识工作树基点；回归覆盖当前未提交源码与测试。在 root 发出全部 10 个 production files 与 tests 的 FINALFREEZE 后，五个独立进程并行执行下述验证。

## 命令与范围

DeepRead 和 Chat 分别在自身模块执行全部 TS + CJS：

```sh
./node_modules/.bin/tsx --test src/test/*.test.ts src/test/*.test.cjs
node node_modules/typescript/bin/tsc --noEmit
```

Entry 在 `harmony/entry` 使用现有 chat tsx loader 与路径映射执行全部 CJS：

```sh
TSX_TSCONFIG_PATH=../chat/tsconfig.json node --import ../chat/node_modules/tsx/dist/loader.mjs --test src/test/*.test.cjs
```

仓库根执行：

```sh
node --test scripts/harmony-standalone/prepare.test.mjs
```

日志使用 `/tmp/amber-r2-{deepread,chat,entry,prepare}-tests.log` 与 `/tmp/amber-r2-dualtsc.log`。仅最终全量各组按末尾汇总计数；不把 focused 验证重复计入。退出码取自实际进程结果。

本任务只执行纯代码测试与领域模块类型检查并记录结果，没有修改生产源码或测试，也不执行构建、签名、安装、设备操作。构建和设备验收由 root 独立报告。

## 最终结果

最终 **4554/4554** 测试通过，0 fail / 0 cancelled / 0 skipped / 0 todo；DeepRead 与 Chat 的 tsc 均实际退出 0。DeepRead 测试前提修复冻结后补跑全量与 tsc；其余未受影响的通过组沿用首轮结果，没有重复计数。

| 验证组 | 通过 / 总数 | fail / cancelled / skipped | 实际 exit | 耗时 ms | 日志 |
| --- | --- | --- | --- | --- | --- |
| DeepRead 全部 TS + CJS | 1456 / 1456 | 0 / 0 / 0 | 0 | 13006.046792 | `/tmp/amber-r2-deepread-final-tests.log` |
| Chat 全部 TS + CJS | 2851 / 2851 | 0 / 0 / 0 | 0 | 30909.946834 | `/tmp/amber-r2-chat-tests.log` |
| Entry 全部 CJS | 241 / 241 | 0 / 0 / 0 | 0 | 11276.400375 | `/tmp/amber-r2-entry-tests.log` |
| standalone prepare | 6 / 6 | 0 / 0 / 0 | 0 | 1093.105542 | `/tmp/amber-r2-prepare-tests.log` |
| DeepRead tsc | 通过 | 0 error | 0 | — | `/tmp/amber-r2-deepread-final-tsc.log` |
| Chat tsc | 通过 | 0 error | 0 | — | `/tmp/amber-r2-dualtsc.log` |

Chat 有 184 个 suites，其余测试组的 node:test suites 汇总为 0。首轮 dualtsc 独立进程退出 0；日志分别记录 `[deepread] EXIT=0` 与 `[chat] EXIT=0`，没有类型错误输出。最终 DeepRead tsc 再次实际退出 0，日志为空（0 bytes）。

## 首轮失败与最小修复后的补跑

首轮 **4553/4554** 测试通过，1 fail / 0 cancelled / 0 skipped / 0 todo；DeepRead 1455/1456、实际退出 1、耗时 25111.717417 ms，日志 `/tmp/amber-r2-deepread-tests.log` 保留。

DeepRead `src/test/novel_audit_regressions.test.ts:146` 的「失效 CAS 的代笔能保存失败原因，失败且无 owner 的作业可取消」在调用 `NovelCreation.rename()` 时失败。新 guard 在 `src/main/ets/novel/creation.ts:938` 抛出 `NovelError` / `invalid_input`，错误文本为「请先完成或取消当前代写/润色任务，再重命名项目」。失败位置对应日志末尾 `test at src/test/novel_audit_regressions.test.ts:1:7212`；首轮 DeepRead 日志完整保留。

root 与 novel owner 核实：该旧测试故意调用 `creation.rename()` 制造 CAS 漂移，违反本轮新增的 active job rename 契约；生产 guard 正确，无需回退。novel owner 只把该行改为现有 `creation.saveChapter()`，用真实作者改稿制造 CAS 漂移，并保留原有失败、重试、取消断言。owner 的 focused 11/11 通过后，root 通知 testfreeze，生产源码未再改变。

本任务没有修改生产源码、测试或断言。测试重新冻结后，仅补跑受影响的 DeepRead 全部 TS + CJS 与 tsc，最终 1456/1456、测试 exit 0、tsc exit 0。Chat、Entry、prepare 与 Chat tsc 沿用首轮通过结果；focused 11/11 不重复计入最终 4554/4554。

## Concerns 与证据边界

Chat 原生 vsync C++ 测试夹具输出一条 `-Wunused-but-set-variable` warning：临时 `test.cpp:96` 的 `frames` 变量已赋值但未使用；对应测试通过。这是测试夹具编译 warning，不是类型检查失败。没有发现其它实际退出失败；测试名中的 warning/WARN 文本不作为编译诊断统计。

本任务没有执行 ArkTS lint，领域模块 tsc 不替代 ArkTS 构建诊断；Entry CJS 包含源码契约验证，不能替代 UI 尺寸、生命周期、原生行为或 provider 的设备验收。构建、签名、设备与真实 provider 验收由 root 独立报告。
