# E14 gate — 整体验收回执(2026-09-29)

范围:E11(Jev 中性分诊/议会工具循环)、E12(本地 WebMount 余量)、E13(小说卡/后台进度)的最终验收。不复述各阶段合同,只记验收事实与硬缺口。

## 源码与检查(全部通过)

- 检查点:E11 `e36090bad`、E12 `c394c7241`、E13 `35970ac90`(本分支 harmony-preview,均含精确文件清单)。
- 必要组:council_tool_runner 1/1、jev_approval_lifecycle 7/7、webmount_domain 6/6、webmount_goal_loop 4/4、novel_plan_proposal 2/2、generation_keepalive 3/3、novel_interactive_adapter+novel_workspace_tools 15/15。
- 受影响文件 codelinter 全部 0 error;E12/E13 各自独立窄审查(逻辑闭环)通过,审查发现的缺陷(URL 连字符正则、EntryAbility cancelAll 漏接、back/forward 文档失效窗口、ForEach key)已全部修复并复验。

## 构建、签名、安装、启动(分开报告)

- 构建:集成 CompileArkTS + assembleApp Release 多次 BUILD SUCCESSFUL(最终态源码)。
- 签名:本地 Test-CA `verify-app success`(仅离线验签,不用于真机交付)。
- 安装:hvigorw 直接产出的 **AGC 调试签名** hap 安装到真机 `6HK0226627000384` 成功(`install bundle successfully`)。这不是 Test-CA 包冒充;两条签名路径产物分开。
- 启动:`aa start EntryAbility` 成功,首页正常渲染。

## 设备 UI 验收(真机截图)

- 浅色:首页、设置列表、WebMount 设置页(开关/飞书/Cookie 卡)正常;新增「站点 OAuth」表单(站点 ID/双端点/ClientID/回调/Scope/编码与密钥方式选择/API origins/密钥)与「HAR 导入」卡完整渲染,布局/换行/按钮高度正常。
- 深色:同一 WebMount 页及 OAuth 表单全部正确渲染(新页实例)。
- 已知现象:切主题后**栈内旧页**保持旧配色(设置列表页),与 2026-09 已登记的暗色主题 bug(export let 非响应式,方案 B 待排期)一致,属既有问题非本批回归;新建页面深色全部正确。
- 巡检期间设备日志 fatal/crash 计数 0。

## 设备/账号硬缺口(未验收,不冒充完成)

- E11:真实模型议会运行、Jev 真实评估端点、审批 UI 亮暗逐项。
- E12:真实站点 OAuth(需已注册应用凭据与真实浏览器回调)、ArkWeb 台账/重放/触摸事件计数实测、九个站点真实 DOM 匹配、live 决策模型的 goal 运行、真实 MCP 服务器。
- E13:系统实况窗渲染(真实返回的通知 ID)、DATA_TRANSFER 超过十分钟的真实内容流验收(官方规则下绝对值进度是否被系统接受)、小说裁决卡实机亮暗(需真实 ghostwrite 任务)。
- E3–E10 既有登记缺口(设备/账号类)维持原登记,不在本批重复。

## 结论

E11–E13 源码层验收与可得的真机 UI/安装/启动验收全部通过;上述账号/长时运行类验收按硬缺口如实挂起,源码通过不升级为产品完整。
