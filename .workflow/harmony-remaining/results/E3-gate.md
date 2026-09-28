# E3 gate：本机 CPython / 真实 Mosh

状态：功能与源码审查通过；最后 Mosh 布局修后截图及最终 Release 设备安装待连接恢复。未将 E3 设备门标为完成。
E2 commit `87f69a88c`；冻结合同 `packets/E3-contract.md`。仅必要测试约束已同步后续计划。

## 已确认闭环

- CPython 3.14.7 官方源码、API12静态编译/严格ELF、固定私有资源、fresh subinterpreter、stdin/输出、取消与deadline已实现。独立原生审查发现析构清理提前关闭trace会卡住，已保留用户frame取消到Py_End；原warm复现停止且后续7成功、stderr为0，动态type timeout及后续8也通过。进程日志不输出用户析构信息，bounded app hook归入结果。
- Mosh是真实上游SSP/UDP，SSH指纹先于认证且只内存传递session key；key不进任务/聊天/日志。单owner、VT字节流、input/resize、掉包/源端口变化保留远端PID均有实际host证据。独立发现owner自join、旧包误刷新lastHeard、末尾VT丢失已窄修；真实MSG_TRUNC分类问题也以单点红绿修复。
- Python/Mosh复用真实单例、TaskStore、后台中断；工具批准固定目标和protocol；手动连接在首await前冻结target。域/平台/审批/原生各独立A/B审查PASS，没有未收口逻辑P1/P2。

## 必要验证证据

- 一次完整Chat检查2281/2281通过（该时源码快照）；后续只对具体修复做专项。最终Chat类型检查通过，root12文件lint0error/451SDK warnings，新模块owner窄lint0error；未重复已绿套件。
- 最终原生修复消费构建：Release18.238s，显式Debug14.872s成功。必须显式buildMode，默认构建沿用前次Release；已核对BuildProfile.DEBUG。
- API24模拟器一次固定真实验收 `/tmp/e3-final-emu.log`：Python11/11、Mosh4/4，包括中文stdin/json/math、异常与文件/网络边界、输出cap、timeout、swallowed exception取消、fresh隔离、真实UDP/resize/停止/Asset清理。未重跑cold/warm压力矩阵。
- Python浅色 `/tmp/e3-python-light.jpeg`：JSON stdin→中文emoji stdout、completed/exit0；深色 `/tmp/e3-python-dark-background.jpeg`：Home后台后INTERRUPTED、唯一background-start、返回不自动重跑。独立Python C审查PASS。
- Mosh原暗色 `/tmp/e3-mosh-dark-physical.jpeg`：同一真实会话physical keyEvent执行echo e3，输出e3和新prompt。先前UITest剪贴板注入未进入Web、空回车过渡显示^M，排除app回车/PTY缺陷；未改bootstrap，也未补不必要host矩阵。
- 真实IME的AX为43×2，发现具体布局P2；仅一行条件在活动/连接/重连时隐藏说明与禁用locale，保留profile/host/protocol/断开，终止后恢复。单文件lint0error/0warning，最终Release52.596s已消费此修复。
- 最终unsigned HAP本地签名及verify-app通过：`/tmp/harmony-remaining-E3-final-sign.log`含codesign成功、SHA256 digest true、verify-app success。设备安装仍使用项目原AGC签名，不安装临时TestCA包。较早Release已安装启动，合法Debug selector返回0事件 `/tmp/e3-release-isolation.log`；最终布局包的设备安装/启动仍待下项，不能用旧包替代。

## 明确待补的设备门

真机在E3使用前已断开，本阶段没有真机成功证据。模拟器在最终布局包安装时HDC不再返回install/shell；读取进程/端口、一次连接/服务重启及正常保留数据重启请求未恢复。停止继续循环环境尝试，保留应用数据和已有证据。

待连接恢复：最终Release安装/启动、Mosh修后实际IME及浅色/后台必要图、独立Mosh C收口。该门在state.json保留未完成，E4独立源码实施继续；不重跑已通过原生矩阵。API12是真实编译/link证据，实际设备为API24，不能宣称API12真机验收。

本阶段提交排除同时出现的两处MacGateway外部变更及E4合同/设计文档，均保持原样。
