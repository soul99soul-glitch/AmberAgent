# WebMount 受控验收页

运行 `python3 -m http.server 18765 --bind 127.0.0.1 --directory scripts/webmount-parity`，再为指定模拟器运行 `adb -s emulator-5554 reverse tcp:18765 tcp:18765`。页面地址为 `http://127.0.0.1:18765/index.html`。

覆盖同页草稿/cookie、旧 snapshot/ref、目标替换/移动、动作计数、预先满足的后置条件、JS alert/confirm/prompt、popup opener、动作后页面桥失联。所有数据均为合成数据，不可把此结果写为真实账号 SSO 验收。

测试结束移除对应 reverse 映射并停止本地 HTTP 进程。此目录不打包进生产 APK。

实际 WebView 测试类：`app.amber.feature.webmount.WebMountParityDeviceTest`。主任务统一构建 `:app:assembleDebug :app:assembleDebugAndroidTest` 后，用 `adb -s emulator-5554 install -r` 安装两个 APK，再执行：

```sh
adb -s emulator-5554 shell am instrument -w -e class app.amber.feature.webmount.WebMountParityDeviceTest app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
```

测试通过 `webmountFixtureUrl` instrumentation 参数可替换本地 fixture 地址。不要使用未限定设备的 `connectedDebugAndroidTest`，避免同时选中个人真机；直接 instrument 也避免 Gradle 测试清理卸载当前合成安装。
