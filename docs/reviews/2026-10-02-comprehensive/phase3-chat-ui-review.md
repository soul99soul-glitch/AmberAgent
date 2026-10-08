# Phase 3 ChatPage 独立复核

结论：最终实现通过本轮独立复核。在普通发送、编辑发送、长按、持久入队、离页预检、抽屉搜索与手动标题生成的指定范围内，没有新增已证实的待修问题。对根 agent 交叉复核发现的编辑态草稿二次清空和同节点切换消息变体两项问题，已用最终实际页面方法复测通过。

## 证据与方法

- 阅读原始 `audit-ui.md` 的 UI02/UI03/UI05 触发条件及本阶段实现，而非只检查 diff 中出现某个字符串。
- 独立脚本 `/tmp/phase3-chat-ui-independent.cjs` 从当前 `ChatPage.ets` 提取完整实际方法，TypeScript 转译后执行；模型检查、图片检查、服务 append/enqueue/start、仓库请求为可控异步端口。实际 `startEditMessage`、`cancelEdit`、`submitConversationRun`、`enqueuePendingParts` 与 `onPageHide` 均纳入提取，不以简化 stub 替代业务方法。onPageHide 的键盘、计时器与 overlay 清理端口为 no-op；页面返回按实际 onPageShow 的 pageVisible=true 状态转换模拟。
- 手动标题用实际 `regenerateDrawerTitle` → `maybeAutoTitle` → Entry `prepareProviderApi` / `buildProviderApi` → domain 的真实 OpenAI Chat/Responses 协议适配器，HTTP 为可观测模拟端口。模型参数在 `makeTextGenerationParams` 边界记录，协议 body 和 headers 另行断言。
- 独立脚本 **25/25 GREEN**；仓内 `ui_send_provider_lifecycle.test.cjs` **19/19 GREEN**。本报告 reviewer 未修改页面或测试源码。

复现命令（仓库根目录）：

```bash
node --test harmony/entry/src/test/ui_send_provider_lifecycle.test.cjs
cd harmony/chat
./node_modules/.bin/tsx --test --tsconfig tsconfig.json /tmp/phase3-chat-ui-independent.cjs
```

独立脚本结果保存在 `/tmp/phase3-chat-ui-independent.log`。`/tmp` 证据属于本次工作会话；长期回归覆盖见仓内新增生命周期测试。

## 检查结果

| 边界 | 当前实际实现 | 独立动态证据 |
| --- | --- | --- |
| 重复点击 / Enter / 长按 | 两入口共用同步 `sendPrechecking`，预检开始前占用；完成仅 matching sequence 释放 | 普通、长按交叉调用仅一轮模型检查且仅派发一次 |
| 隐藏后返回，旧模型检查迟到 | onPageHide:911 递增序号并释放 gate；composerSendCurrent:3648 同时检查 alive/visible/owner/sequence | 旧 completion 不派发；新预检仍占用；新文本单独提交 |
| 图片检查期间离页 | guardImagesForSend 在图片异步检查后再验证请求所属页面状态 | 普通和长按都不派发；输入保留 |
| 旧持久 append 完成与新预检交错 | finishComposerPreflight:3652 只释放所属请求；消费只针对 accepted snapshot | hide/return 后旧 append 成功不清新文本、不释放新 gate |
| 长按 append 与排队 await 后的新草稿 | sendWithoutAnswer:3608、send:3735 捕获文本和附件；clearAcceptedComposer:3772 只消费被受理快照 | 普通 FOLLOWUP 与长按 STEER 入队均保留后来输入的文本、图片、文档 |
| 编辑态长按的二次清空 | 成功后仅清匹配 node+message 的编辑身份；不调用会清文本的 cancelEdit | 实际页面方法确认 append 期间的新文本保留；原编辑身份完成后解除 |
| 同节点换分支消息 | 两发送入口都捕获并比对 editingNodeId + editingMessageId | 预检期间实际 startEditMessage 换同节点变体后，旧文本不能被提交；持久 append 完成后也不取消新变体的编辑态 |
| 普通发送与编辑的服务受理失败 | sendParts/sendEdit 的 bool 源自实际 submitConversationRun:723 | restore-version 拒绝和 service.start 同步抛错均保留输入、附件、编辑身份 |
| append/enqueue 持久化拒绝 | await 成功前不消费 captured draft | 两路径 disk-full 均保留文本附件、展示错误、释放 gate |
| 迟到预检失败 | prepareComposerSend 的 catch 仅更新 current 请求 | hide/return 后旧 rejection 不覆盖新页面错误；当前图片检查失败可释放 gate 并保留草稿 |
| 抽屉标题和全文搜索 | refreshDrawerConversations:3089 以每次刷新序号过滤成功和失败；清空立即清全文命中 | same→other→same 的 ABA 请求顺序、旧标题失败、旧全文失败/成功均不覆盖新结果；清空后恢复完整列表 |
| 手动抽屉标题的认证和参数 | maybeAutoTitle:2094 await prepareProviderApi；保留 model headers/custom body/reasoning off | codex_oauth / grok_oauth / api_key 三种真实协议请求均检查 Authorization、Codex accountId、X-Task、custom body、model、非变异 provider snapshot、生成标题及释放锁 |

## 二次复核排除项与行为边界

- 普通 submit 的 boolean 表示同步服务受理，不表示 provider 回答或全流程持久化已经成功。该修复解决的是 restore guard/start 拒绝仍清草稿的问题；未引入异步生成失败后恢复发送草稿的新产品行为。
- ChatInput.ets 的 `disabled` 确实禁用 TextArea 和附件控件，非仅禁发送。最终 ChatPage.ets:4779 已不把 prechecking 放进 disabled；用户可以在预检或持久提交期间继续写新草稿。重复发送由入口 gate 控制。
- 页面隐藏不撤销已受理并进入持久提交的消息。持久成功后消费其 accepted 附件、保留不同的新文本，符合既有 run service 与后台运行边界；离页尚未通过预检的请求被阻止。
- reasoningLevel='off' 到 OpenAI 兼容请求的映射由既有协议层决定：当前 fixture 的 Chat Completions body 为 low；Responses 不启用 reasoning 字段。本次检查先确认页面传入 off，再确认实际协议字段符合既有映射，未把要求 off 必须等于字面 none 的错误测试假设报成产品 bug。
- 未做模拟器截图或设备交互。本报告证明实际方法的异步行为与真实协议参数传递，不扩大为视觉、输入法布局、真 Provider 在线验收或性能结论；ArkTS lint/build/device 验证由根 agent 合并执行。
