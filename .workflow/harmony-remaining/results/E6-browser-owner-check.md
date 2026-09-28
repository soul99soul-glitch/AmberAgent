# E6 Plugin WebMount owner controlled check

结论：唯一短 group 1/1 PASS，执行一次 `node /tmp/e6-browser-owner-check.cjs`，日志 `/tmp/e6-browser-owner-check.log`。没有仓库 tests 文件、full suite、网络、JSVM、构建或设备执行。

检查通过 TypeScript `transpileModule` 加载实际 `ConversationBrowserGuard.ets` 与 `PluginWebMountGate.ets`。WebMount session 是计数器替身，URL check port 使用 Node URL；session 的 `bindPluginUrlGate` 方法从实际 `WebMountSession.ets` 精确提取，保留原 gate 身份检查。B gate 接管由受控替身直接设置，用来验证旧 binding 的 identity cleanup；不声称实际 WebView 接管或设备验证。

- A→B 后调用 A 的 `wm_open` scope（`requireCurrent=false`）：operation 未调用；read/bind/reset/isReady/unbind 全为 0。
- A scope 等待期间切 B，分别 settle 为 error parts 或 rejection：每次仅切页前 read/bind 各 1，切页后 reset/isReady 为 0；finally unbind 调用 1 次，B URL、B gate identity、B active flag 均保持。
- 修复为 adapter 第 4 参与 WebMount port 第 2 参 `conversationId: string | null = null`；非 null scope 在读取/绑定前捕获原 owner lease，await 后及错误清理前重新检查。直接中心调用保留默认 null。

双 owned 文件精确 lint：0 error / 0 warn，`/tmp/e6-plugin-webmount-owner-lint.txt`；diff check clean。Root 负责 BrowserGuard helper 与实际 ctx/parent conversationId 接线。

本次加载 SHA-256：BrowserGuard `96650ce1ae79aa8598170e10a95f9d7adbe4abb1c4ede957499e9d59e681676b`；PluginWebMountGate `da51e80a7400b49665bb0e566561a685e168c2a3e71a838d232442d5bf32f872`；提取 binding 方法 `bf7f041c44d4bdbd7a19adafc3cc11c9feff56b087c01e58bf7ef86815269597`。
