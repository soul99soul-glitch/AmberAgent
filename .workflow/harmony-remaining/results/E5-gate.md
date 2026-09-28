# E5 动态插件阶段源收口

2026-09-28。冻结合同见 `packets/E5-contract.md`，独立审查分别见 `E5-review-package-store.md`、`E5-review-native.md`、`E5-review-runner.md`、`E5-review-integration-ui.md`。各自最终范围无未关闭 P1/P2；设备 C 尚未通过，E5 不登记为产品全部完成。

## 已闭环的源码

真正多文件、多工具插件包，内容 hash/签名/可信发布者、候选试跑与 expected_result、固定候选及安装基线的人工导入、版本 CAS/启停/删除/回退/健康隔离/导出、真实 SDK 与插件中心均接入。Recipe、JSVM 同步宿主回调、内嵌 Python、固定 MCP/HTTPS 后端沿当轮真实工具目录和逐步审批执行，STARTED 保存先于原语副作用。手动运行不调用模型辅助生成；冷恢复不重演已开始或丢失 VM 的操作；子代理保持原许可目录，管理工具不增加其能力。

当前 run/version/inputs 和每步宿主参数持久绑定；导入只在明确父级人工 approved 后执行，不受两个自动开关旁路。普通生命周期沿既有用户高风险自动策略与 CAS。HTTP 已发出后取消或超时的未知副作用保留 outcome_unknown；JS 批准续跑的本轮 signal 能关闭原 VM gate。审批卡展示固定权限差异/内容与宿主参数；ask_user 仍回答父工具定位。设置入口、路由及输入编辑控件均接通；Recipes/插件两卡边距已复核。

## 必要验证证据

- 包/Schema 六类必要检查通过；跨真实 Swift canonical/hash、实际文件 Store/CAS/签名/健康同一个夹具 1/1 通过。后续仅孤立 surrogate 路径和缺包目录受影响点红绿修复，未重复完整组。平台 Crypto 由宿主等价端口验证，未冒充 OHOS 实机运行。
- runner 原有五组、实际 tool_loop 两组、页面启动两组与审批投影一组必要检查通过。review 修复只重跑对应 HTTP 未知副作用、续跑 JS signal 和子代理新增分支，未重跑全 suite。
- 全 chat strict TypeScript 最终 exit0；各新模块窄 lint 0 error。root 实际改动 13 文件 lint 完整结束 0 error/775 warn，设置页边距修复后单文件 No defects。TS import 类型解析产生 warnings，未登记为零警告。
- 原生 JSVM 两 C++ 文件 API12 目标编译/实际 `libjsvm` 链接通过；受控事件 port/transport 夹具不证明真实 VM 执行。原生 cooperative abandon 与无硬内存沙箱边界已在 SDK/文档明确，未宣称强杀无限脚本。
- Release 编译定位的名义接口、构造字段、throw 类型和输入编辑绑定错误已精准修复。最终 review 修复后的整包构建与签名收据由 root 下方记录，不重复无关测试。

最终收据：`/tmp/e5-release-review-final-build.log` Release 8.191s，BUILD SUCCESSFUL/exit0；`/tmp/e5-release-review-final-sign.log` 使用已有本地调试材料签名，Digest verify true、verify-app success/exit0。该本地 TestCA 不用于设备安装；真实安装仍需设备可用与原 AGC 调试证书路径。

## 未验收的外部门

HDC 最后有效只读快照为 Empty，未再次进行环境恢复、循环安装或模拟器矩阵。真实 JSVM/TSFN/审批保栈、官方平台 HTTP/文件 Crypto、插件中心明暗主题实拍、最终签名包 install/launch 均 pending。已有源/宿主证据不代替这些门；后续恢复设备后只补必要的最小路径。E6 的独立源码实施继续，最终 E14 汇总剩余验证。
