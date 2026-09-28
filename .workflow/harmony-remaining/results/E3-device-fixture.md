# E3 必要设备验证入口

2026-09-28。文件：`entry/src/main/ets/platform_impl/RuntimeE3Fixture.ets`。设备操作、统一构建及 Release gate 由主 agent 负责；本报告不将代码交付或 lint 视为设备验收通过。

## 入口

```ts
export const runRuntimeE3Fixture = async (want: Want): Promise<void>
```

函数内部先检查 `BuildProfile.DEBUG` 与固定 selector，再读取参数。EntryAbility 完成 `abilityContext` 登记后，在正常前台调用；保留生产 transport 的后台拒绝规则。

| Want 字段 | 允许值 |
| --- | --- |
| `amberE3Fixture` | `runtime-v1` |
| `amberE3RunId` | `[A-Za-z0-9._-]{1,64}` |
| `amberE3Target` | 缺省只跑 Python；`emulator` 或 `phone` 才追加 Mosh |
| `amberE3Password` | 主 agent 从独立测试配置读取的生成密码；仅用于 Mosh |

无任意 source、stdin、command、endpoint、port、pin 参数。hilog tag=`E3.Fixture`，每项只记录 `{runId,name,pass}`，summary 只加 completed/expected。没有源代码、stdin、Python traceback、SSH/Mosh 原始错误、密码或 session key 输出。

## 已收敛的必要矩阵

根据用户最新要求，把重复正常路径合并为一次执行，没有添加压力循环或冷/热重复轮次。

Python summary=`python-complete`，expected=11：

| name | 验证 |
| --- | --- |
| `python-version-stdin-unicode-json-math` | 一次真实本机执行：CPython 3.14.7、input/stdin、中文/emoji、json、math |
| `python-exception` | 真实 ValueError → FAILED/exit1/python_exception；traceback 只在内存判断 |
| `python-open-rejected` | 禁用文件 builtin 的独立 AST 分支 |
| `python-os-rejected` | OS 模块不可用 |
| `python-network-rejected` | socket 模块不可用 |
| `python-output-limit` | 128KiB 输出边界与 output_limit；不新增源/stdin极值矩阵 |
| `python-loop-timeout` | 实际字节码循环停止后收口 TIMED_OUT/null |
| `python-swallowed-base-exception-cancel` | 一个嵌套循环同测 bytecode cancellation 与吞 BaseException；输出固定进入循环 marker，native 返回后才断言 CANCELLED/null |
| `python-module-mutation` | 取消后下一次成功，并在本次修改 json 模块/设临时 globals |
| `python-fresh-module-and-globals` | 下一次真实执行 json 与 globals 恢复；不另重复普通 print 成功 |
| `python-cleanup` | 中断仍活跃执行，所有独立 task 元数据终态、cancelCapability=false |

Mosh summary=`mosh-complete`，expected=4：

| name | 验证 |
| --- | --- |
| `mosh-bootstrap-unicode-bytes` | 同一次实际 SSH bootstrap + UDP session，原始订阅 bytes/真实远端格式化输出验证中文与 emoji 输入；命令 echo 不含完整结果 marker |
| `mosh-resize-stty-size` | 只做一次 93×31 resize，真实远端 `stty size` 与 domain snapshot 同步 |
| `mosh-stop` | 实际停止后 CANCELLED/cancelled、task cancelCapability=false；不声明每命令 exitCode |
| `mosh-cleanup` | 关闭剩余 session、删除本次 Asset references，task metadata 没有 MOSH CONNECT bootstrap 输出 |

Mosh 固定测试 SSH 为 22225 / `amber-fixture` / pin `SHA256:2C/scFRFC6NoNJmL2ogZAhVd/tjTmVoZ2FnpaN7s2MY`。`emulator` 对应 `10.0.2.2`，`phone` 对应主 agent 已核实的 Mac LAN IP `192.168.100.107`。显式 `serverLocale:'en_US.UTF-8'`，使用真实生产启动命令，不在测试 SSH server 静默转换 Linux 默认 C.UTF-8。它与 E2 22224/pin 不共用。

## 真实边界与资源

- PythonRuntime 注入真实异步 `createNativePythonTransport()`；CPython helper/stdlib 由生产 adapter 部署，native 真执行。MoshRuntime 注入生产 SSH/Mosh transport 与实际 SSHTargetResolver。
- ProfileStore 与 TaskStore metadata 为新建独立内存实例，避免修改用户配置/任务。Mosh 固定已批准 pin 仅用于 seed 测试 profile；native bootstrap 仍须真实握手核 pin 后才认证。凭据经过实际 Asset + 私有 AES 文件仓，finally 清理。
- 只为断言去掉 ANSI 控制序列；生产终端仍收原 bytes。没有把 frame bytes 伪装为完整终端历史，也没有提供新的终端模拟器。
- 不提供冷/热、压力连接、冗余 resize、正常会话重复 PID 测试。真实 UDP 丢包、源端口变化、同 PID/变量与无第二次 bootstrap 的专项证据由主 agent 故障夹具负责；此入口不冒充已测。
- Task metadata 的无 bootstrap 输出断言不等于全设备磁盘扫描。生产 key 不持久链的源码审查和实际专项证据保持分别登记。

## 交付验证

- 已读取 `harmony-arkts` skill、E3 frozen contract、真实 helper/native 与 domain/adapter；没有引入仓库未用的 SDK API。
- 最终窄 lint 已通过，exit=0 / `No defects found in your code`：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/RuntimeE3Fixture.ets`，日志 `/tmp/e3-device-fixture-lint.log`；SDK 诊断日志写入按正常权限 escalation 执行。diff 空白检查通过。
- 未操作设备、EntryAbility、DI、构建入口或 Git；实际双设备运行和 Release selector gate 由主 agent 接线后执行一次必要矩阵。
