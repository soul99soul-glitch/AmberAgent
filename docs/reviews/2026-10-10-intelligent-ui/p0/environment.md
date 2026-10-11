# P0 环境与取证记录

日期：2026-10-10（Asia/Shanghai）

## HarmonyOS 模拟器

- HarmonyOS Emulator：6.1.1.350。
- 实例：`MyPhone`，`nova 15 Ultra`，HarmonyOS 6.1.1（API 24），arm64，1320×2856，4 GiB。
- 启动方式：复用已有实例，冷启动；没有删除实例、清理 userdata 或卸载应用。
- `hdc`：3.2.0d。
- 目标：`127.0.0.1:5555`，TCP Connected。
- 首次启动应用时模拟器仍处于锁定状态，系统拒绝自动解锁；通过模拟器自身界面解锁后再次执行 `aa start` 成功。
- 启动的 bundle：`app.amber.deepread`。现有版本为 0.0.1、debug、`appSignType=none`，是旧安装状态，不代表待验证的新 HAP。

## 签名材料可用性

- 仓库内已有本地 AGC 材料文件（只记录类型，不复制内容；密码文件未读取）：`.agc-signing/` 下存在 p7b、p12 和 CSR。
- `harmony/build-profile.json5` 的 `default` signing config 引用本机 `~/.ohos/config` 下对应的 HarmonyOS 证书、p7b 和 p12；文件均存在。
- 已有候选 HAP 可见，包含 `entry-default-signed.hap`、`entry-widget-fix-device-signed.hap` 和 `entry-svg-fix-device-signed.hap`。本子任务没有覆盖安装，避免与主代理的 P0 安装并行冲突。
- 不能从“文件存在”推断真机或模拟器一定接受签名；最终以 `hdc install -r` 的实际结果为准。

## 模型配置可见性

在本机已有配置 `/Users/mi/.config/mimocode/mimocode.jsonc` 中，静态检查到以下已有 provider/model 配置，API key 值没有写入本记录：

- `qwen`：`qwen3.8-max`，存在 base URL 和 key 字段。
- `glm`：`glm-5.3`，存在 base URL 和 key 字段。
- 另有 `kimi`、`mify`、`ocx`、`deepseek` 配置。

这只能证明本机存在用户配置，不能证明当日额度、服务端权限或请求不会计费；本子任务没有发起外部模型请求，也没有读取或输出凭据。P0 少量模型验收可优先使用用户已授权的 Qwen Token Plan 与另一个已有 provider，实际请求结果需要主代理单独记录。

## 设备与日志取证结果

- 模拟器启动后，`bm dump -n app.amber.deepread` 可读，应用可启动，首页截图显示当前会话数为 0。
- 通过 `hdc` 读取模拟器当前 hilog 及已有模拟器滚动日志，并对工作区和相关本地日志检索以下精确字符串：`Unexpected end Text in JSON`、`unexpected end text`、`finish_reason`、`finish reason`。
- 未找到用户截图对应的原始消息 JSON、请求响应体、SSE 终止帧或 finish reason。旧日志中存在系统启动和应用历史运行记录，但没有这条会话的原文。
- 因此，当前可以确认的只有：截图中的组件解析错误发生在某段未完整结束的 JSON 输入上；不能据此判断是模型输出上限、模型主动停止、传输截断、Regex/输出转换或本地拼接丢尾。
- 工作区内的 `harmony/chat/src/test/fixtures/intelligent-ui/p0-model-cases.json` 和 streaming fixtures 是合成测试数据，不作为用户原始证据。

## 阻塞项

原始失败消息不在当前模拟器 userdata 或可读本地日志中，且模拟器是空会话；没有原始 payload 和 provider 终止帧，无法完成该条消息的因果归因。后续应以主代理新增的受控诊断字段、用户复现时的 runId/finishReason/传输终态为准。
