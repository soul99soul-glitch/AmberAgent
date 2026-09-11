# MiniApp 系统能力验收样本

`index.html` 使用真实 `Amber` JS SDK。将其作为小应用内容通过生产 repository 导入，声明 `haptics/device/screen/speech/share/openURL`，在真实 runner 中运行；网页不模拟 bridge 或 native 返回值。

先检查 `getAppInfo/getCapabilities` 不触发授权，再逐项验证未声明、全局关闭、用户拒绝及允许状态。二维码内容固定为 `amber-miniapp-parity-2026`，需实际解码验证。朗读/亮度/常亮要检查离页与后台释放。分享仅验证系统 chooser 的取消，不向联系人发送。

结果中的 `error.code` 保留原生错误；没有引擎或硬件时不当作成功。设备、账号与权限验证结果记录在执行台账，不能以 HTML 存在作为功能完成证据。
