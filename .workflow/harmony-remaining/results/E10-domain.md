# E10 Google 内置工具 domain

2026-09-28。E9 source checkpoint `caa5b9d50` 后，按冻结 `packets/E10-contract.md` 与 `results/E10-google-tools-design.md` 完成。仅修改 `google_request.ts`、`google_parse.ts`，新增唯一 `src/test/google_builtin_tools.test.ts` 与本报告；未改 E9 鉴权、DTO/index/serializer、UI、DI 或其它 owner 文件。此前已读取 harmony-arkts skill，本次没有新增 SDK API 或网络请求。

## 最终行为

- `google_request.ts` 消费实际 `params.model.tools`：search/url_context 映射 `googleSearch:{}` / `urlContext:{}`，任一存在即使用明确内置工具模式，不注册本地/MCP functionDeclarations、不启用混用 flag。两者可共存且不依赖 function tool ability；只有 image_generation 时仍保留原 functionDeclarations，没有函数时不写空 tools。customBody 继续最后覆盖，参数、模型输出模态、鉴权和 endpoints 不改。
- `google_parse.ts` 私有 `parseGoogleCandidateAnnotations` 同时供非流式 candidate 与 SSE 消费。既有 grounding web citations 保持；真实 retrievedUrl/status 映射 root 已冻结的 url_context annotation；searchEntryPoint.renderedContent 原样映射 google_search_suggestions。没有将失败 URL 变成成功 citation，也不根据 URL 执行额外抓取。
- 只有 metadata、没有 content 的 SSE candidate 仍生成 `assistant` 空 parts + annotations，没有假正文；既有 accumulator 接收并 distinct，root 的 serializer exact branches 保存/重载两种新 annotation。finishReason/usage、普通 response part 及真实 provider/promptFeedback/SSE 错误原路径保留。
- 既有 public 函数签名与导出不变；两新增 annotation DTO、re-export 和序列化由 root 独占。未启用 Gemini 3 server toolCall/toolResponse/id/signature 混用协议，能力说明按冻结 UI 合同由对应 owner 实现。

## 唯一必要 fixture 与红绿

`google_builtin_tools.test.ts` **84 行、一个 test group**，真实 ProviderModel/Google provider serialize → reload → toChatModel → actual GoogleChatApi，仅 HTTP 是供两段 SSE 的 fake port。真实 parser/MessageStreamAccumulator/消息 serializer 全部消费正式源码与 root DTO，没有抄写请求或 merge 算法。

- 修前实际 RED：`/tmp/e10-google-builtin-red.log`。请求实际仍是 file_read functionDeclarations，断言期望 googleSearch + urlContext 失败。最初仓根执行曾因未加载 Chat tsconfig path alias 在测试入口前失败；随后在 `harmony/chat` 正确上下文重跑，该日志记录的是上面的真实产品断点，不将工具入口问题冒充 RED。
- 修后同组 **1/1 PASS**，`/tmp/e10-google-builtin-green.log`。正文 event 先到，最后 event 只有来源 metadata：grounding citation 重到、成功/真实失败 URL、重复 URL、带换行/Unicode/暗色 CSS 的 HTML。最终正文只一份、citation/status/HTML distinct 并 serialize/reload 全保留。
- 同组调用 actual API 的非流式方法，验证同 helper 的失败 URL status / exact HTML 消费；同组的 only-image 分支验证保留 local functions，且无 local functions 时不写空 tools。未拆新 UI test、扩参数矩阵或重跑旧 Google/E9/全 Chat suite。

## 必要静态证据与边界

- 两 owned 源 + 唯一 fixture 的窄 strict `tsc --noEmit` **exit 0**：`/private/tmp/e10-google-builtin-tsconfig.json`、`/tmp/e10-google-builtin-types.log`。真实导入类型照常参与检查，未用假 SDK 类型或 any 兜底。
- 两 source 精确 CodeLinter **exit 0 / 0 error / 270 warning**：`/tmp/e10-google-builtin-lint.log`。warning 为现有跨 domain TS import 解析产生的 unsafe diagnostics，准确保留数量，不声称 0 warning。工具初次因 SDK 固定日志目录 EPERM 未完成，随后在自动批准的同一命令所需权限下完成，未改变 SDK 配置。
- 指定两 source 的 `git diff --check` **exit 0**。最终文件：request 362 行、parse 283 行；reviewer `/root/e2_review_domain_ui` 已只读复核最终 request/parse/shared chain 与该红绿日志，无确认问题，未重复执行检查。

本次不做 build、签名、设备或真实供应商网络验收；统一 Harmony compiler 与 UI/设备证据由 root 和 UI owners 分开报告。此报告证明冻结 domain 链已闭环，不替代整 phase 产品验收。
