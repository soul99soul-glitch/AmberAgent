# E8 配置用途说明与 Chat → DeepRead 草稿复用

2026-09-28。独占修改 `ChatProviderSettingsPage.ets`、`SettingDeepReadPage.ets` 与新 `platform_impl/ChatDeepReadDraft.ets`；保留其他 owner 的 Provider 对、DI、任务消费者及记忆改动。已读 harmony-arkts skill、E8 合同与设计。

- Chat 服务商页明确聊天与小说共用 Provider、小说项目可固定选择、任务可单独选 Provider、DeepRead 仍使用独立兼容 API；普通配置行通向任务模型与 DeepRead。DeepRead AI 卡有实际反向 Chat 服务商路由，两个页面已有路由登记。
- “填入当前 Chat 配置”实时解析现有 `resolveChatProviderChoice()`，使用含模型级 override 的有效 Provider 与 `ProviderModel.modelId` API 名，仅填入本页 `aiBaseUrl`、`aiKey`、`model` 三个草稿。等待本页首次加载完成避免旧 ai_* 加载覆盖草稿；页面失效沿原 token/pageAlive 检查。原“保存全部”与真实存储路径不变，无自动保存、额外 modal 或已读存储。
- 新纯草稿 helper 仅接受 OpenAI `api_key`、Chat Completions，且 Chat 实际 endpoint 与原 DeepRead URL 拼接结果一致。自定义 headers/bodies 只接受原客户端已等效保留的 Content-Type、同值 Bearer、同 API 模型名、`stream:false`；附加参数明确提示无法保留。Google/Claude/OAuth/SA/Responses、不完整配置或无当前模型均返回具体无凭据内容的提示，不更改草稿、不转换鉴权、不扩 DeepRead 协议。
- 旧深读模型覆盖选择仍保留且页面解释其优先级。精确纠正邻近旧“看板模型”与“留空跟随聊天模型”说明为当前深读语义，模型库说明只指向议会；无模型库行为修改。
- 新说明与结果可全文换行，配置行沿现有 52vp 最小高度，按钮沿现有 44vp，横向 16vp、主题 token、Scroll/safe area 沿现有组件，无新固定高度长文容器。

必要验证：一次三 owned 文件 lint，`/tmp/e8-onboarding-lint.log` 退出 0、No defects；新 helper 真实域/DI/官方 SDK 窄类型检查 `/tmp/e8-chat-deepread-types.log` 为 0 diagnostics；两既有页 `git diff --check` 通过。未执行测试、应用构建、签名或设备操作；实际 ArkTS compiler 与独立 review 由 root 收口，设备亮暗排版仍由 root 的实际可用设备 gate 验收。

独立 review 的实际 P2 已精准修复：Chat 使用 `search/search_service.ts` 的空白/逗号 split→trim→distinct 后单 Key 轮换，而 DeepRead 只发送一个 Bearer。草稿 helper 第 30–39 行沿相同规则静态分解，不调用 roulette；多个不同 Key 拒绝并提示手填一个，单 Key 规范化后用于草稿及自定义 Authorization 等效比较。仅修该 helper，单文件 lint `/tmp/e8-onboarding-keyfix-lint.log` 退出 0、No defects，窄类型 `/tmp/e8-chat-deepread-types-keyfix.log` 为 0 diagnostics；未新增测试、请求、构建或设备检查。
