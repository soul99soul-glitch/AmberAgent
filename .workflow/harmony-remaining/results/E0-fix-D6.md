# E0 D6 精准修复

## 已修复

1. 飞书六工具 execute 接收调用方 signal，贯通 resolveAccessToken → refresh/fetch → append。
2. append 在 token refresh 或根 block GET 等待期间停止后，以 AbortError 收口，不继续 POST children。
3. wm_signed_fetch 接收并透传 signal，停止时真实 HttpClient 能销毁原请求；即使底层晚到响应，也不返回成功。
4. 飞书工具每次执行及每次真实请求前复核既有 WebMount 许可。复用 WebMountTools.syncEnabled，规则仍为 global || assistantEnabled；关闭全局且助手无特许的已装配工具拒绝。
5. 主 agent 已在 ChatPage 的 createFeishuDocsTools 装配点传入 localOpts.indexOf('webmount') >= 0，保留助手单独启用的合法路径。

## 文件与边界

- FeishuDocsTools.ets：六工具的 signal 传递、许可回调；原工具名、参数和输出保留。
- WebMountTools.ets：导出原 syncEnabled；signed fetch 接入有取消能力的请求 helper。
- chat/webmount_request.ts：31 行，复用宿主许可回调，在请求前、许可读取结束后及响应结束后检查取消，以 AbortError 上抛，并将 signal 传给 HttpClient。
- chat/feishu_docs_client.ts：原 refresh/fetch/append 的纯逻辑搬出到真实生产 helper，便于可控网络测试；不增加权限模型、重试或恢复层。
- chat/index.ts：只增上述 helper 的导出。
- chat/src/test/webmount_request.test.ts：11 个直接运行生产 helper 的回归；不读取、提取或 transpile 源码。
- ChatPage 装配参数由主 agent 修改，本 worker 未修改页面。

## 验证

- 先按旧行为保留 signal: undefined / 无许可边界，10 个回归全部红，复现拒绝缺失、signal 丢失、root GET 后仍 POST 等问题。
- 修复后新增第 11 个用例验证 refresh 晚到时不持久化 token、不发后续文档请求。
- `harmony/chat/node_modules/.bin/tsx --test harmony/chat/src/test/webmount_request.test.ts harmony/chat/src/test/webmount_oauth.test.ts`：15/15 PASS（新增 11 + 原 OAuth 4）。
- `git diff --check`：PASS。
- chat `tsc --noEmit` 首次 exit 0；随后并发新增的 `entry_d5_regression.test.ts:200` 有 SubAgentDefinitionInit 参数缺字段错误，已通知主 agent / D5 owner，D6 文件未报错。
- 窄 lint 首次因官方 codelinter 写工具目录日志遇到 EPERM；授权重试虽 exit 0 / No defects found，但输出包含 incomplete report 提示，且与主 agent lint 重叠，因此不计有效 lint。后续统一 lint 由主 agent 执行。
- 未执行全仓构建、签名、设备安装或提交。本修复不改 UI 布局。

## 阶段复审

已向 review_d5_d6 请求复审真实 helper 及宿主接线；主 agent 的阶段集成需接收该复审结论，并完成统一 lint / build / 设备阶段验证。

## 集成回喂修正

主 agent 的 build 指出 FeishuDocsTools.ets 的 FEISHU key 导出插在 import 之间，触发 arkts-no-misplaced-imports。已把该导出移到全部 import 之后，仅调整语句顺序；未重跑 lint，构建回喂由主 agent 统一执行。
