# E0 CodeLinter gate 修复证据

2026-09-28。本项仅修改规则配置、lint 包装脚本及其行为测试；未修改全局 SDK 依赖，未构建、签名、安装或提交。

## 根因与官方依据

- `harmony/code-linter.json5` 原配置 `plugin:@typescript/recommended` 不受支持；正确名称为 `plugin:@typescript-eslint/recommended`。
- 本机 SDK 默认配置 `~/Library/Huawei/commandline/command-line-tools/codelinter/linter/eslintAgent/config/code-linter.json` 使用后者；SDK linter enum 同样声明 `TS_ESLINT_RECOMMENDED="plugin:@typescript-eslint/recommended"`。
- 已通过仓库封装读取离线官方文档：`harmony/scripts/devecocli.sh docs search codelinter`、`docs search ruleSet`、`docs read 开发指南/代码编辑/代码检查/Code_Linter代码检查/ide-code-linter`、`docs read 开发指南/代码检查工具_codelinter/ide-command-line-codelinter`。文档明确支持此规则集与多文件/目录参数。
- SDK 的 `BaseResultParser.printMessage()` 对 `CHECK_ERROR` / `BASE_ERROR` 仅打印红字；最终 `report()` 又按缺陷集合调用 `setExitCode()`，因此可能将内部失败覆盖成退出码 0、显示 `No defects`。缺陷明细由普通文本输出，红色 ANSI 输出由 `CmdPrinter.ERROR_COLOR` 专用于工具错误。

## 修复

- 保留 performance + TypeScript 推荐规则，只纠正规则集名称。
- `lint-arkts.sh` 收集所有位置参数，文件与目录可混传，继续按既有 80 文件批次上限运行；缺失目标、空扫描范围和非法批大小返回 2。
- 捕获并即时显示 CodeLinter 输出；遇 SDK 红色错误、标准内部失败或插件加载失败，即使原工具退出 0，也返回 2，并打印“检查未完成”。`LINT_EXIT_ON=none` 也不能绕过内部错误。
- 原生非零退出仍返回 1；批次聚合保留最严重失败。
- 官方 `-e` 是精确级别集合，因此 `LINT_EXIT_ON=warn` 改为 `error,warn`，`suggestion` 改为 `error,warn,suggestion`，符合包装脚本的最低级别门槛语义。

## 验证

1. `bash -n harmony/scripts/lint-arkts.sh`：通过。
2. `/opt/homebrew/bin/node harmony/scripts/test-lint-arkts.mjs`：通过。测试实际启动 `/bin/bash`（macOS Bash 3.2），用临时 SDK stub 执行包装脚本；覆盖带空格的两文件、目录 + 文件分批、忽略目录、工具错误但退出 0、普通缺陷退出 4、none/warn 门槛、缺失/空目标及非法批大小。测试检查真实子进程退出码与传给工具的参数，不对源文本做镜像断言。
3. `git diff --check -- harmony/code-linter.json5 harmony/scripts/lint-arkts.sh`：通过。
4. 真实 SDK：`harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/chat/provider_balance.ts`。
   - 默认 sandbox 首次运行因 SDK 自身 `result/codelinter.log` 写入权限报 `EPERM`，包装脚本返回 1，未误通过。
   - 按已有验证授权对同一命令提升权限重跑，扫描到 100%，退出 0：`Defects: 6; Errors: 0; Warns: 6; Suggestions: 0`。
   - 6 条 warning 为 provider 参数成员的 `no-unsafe-member-access` / `no-unsafe-call`，分别涉及 id/baseUrl/balanceOption/apiKey；没有插件加载失败。真实规则已加载并产出缺陷明细。该文件逻辑属其他 worker，不在本项中修改。

本项完成。后续 phase 可继续使用此 gate；此前以错误插件配置产出的 `No defects` 不能作为静态检查完成证据。
