# C2 Integration

## Accepted

- 新增 `InteractiveTurnRuntime`，Chat 普通发送、工具循环续跑、审批/ask-user 续跑和
  regenerate 均进入同一 façade。
- `NovelChatRunAdapter` 精确解析项目模型，复用 Chat 三类 provider、SSE parser、
  accumulator、ThinkTag/Regex transformer、retry 和 abort；Novel 不再走独立非流式 HTTP。
- Novel 活动尾改为 canonical immutable snapshot；UI 独立显示生成活跃态与真实文本流式态，
  历史和活动消息都由共享 `AgentMessageRow/InteractiveMessageTimeline` 渲染。
- `HttpClient.fetchStream.onDataEnd` 从 API 12 NetStack 实现透传至 OpenAI/Claude/Google
  provider；transport 按实际 request data-end 资格化。
- 每次 stream 通过 `baselineMessages` 绑定真实 accumulator seed；HTTP 503、tool step、
  vision fallback 和“旧长答案→新短答案”regenerate 均不会污染下一请求的流式判定。
- retry 前的空 assistant snapshot 真实下发，停止不会把失败尝试旧 partial 保存为正文。
- 页面进入时使用官方 API 11 `KeyboardAvoidMode.RESIZE`，离开恢复 `OFFSET`；键盘弹出时
  顶栏/页签固定，Timeline 压缩而非整体上移。

## Review Fixes

- 修复整包首帧曾暂时报 live，以及 dataEnd 早于 pending bytes/assembler flush 时被误报
  unavailable 的双向分类错误。
- 修复 tool-loop `providerOverride`、Chat 审批续跑和 regenerate 绕过 façade。
- 修复 HTTP 503 无 partial 的旧 dataEnd 污染 retry，以及 retry sleep 不能即时 abort。
- 修复显式 Novel model 被默认 Chat model 覆盖。
- 修复活动 header 在多 assistant snapshot 上重复、Timeline 键盘缩屏越界、跳底按钮仅
  40vp 命中区；最终按钮为 44×44vp。

## Runtime / Device Evidence

- 真实 provider prompt 生成 Markdown，活动首帧显示 pending user/reasoning/stop，终态列表、
  引用和正文由共享 Markdown renderer 呈现。
- telemetry：`Novel.Transport ... transport=live`；`DeepRead.Http ... status=200` 且存在
  多个 `dataReceive` chunk。
- stop-before-first-delta：NetStack 记录 `fetchStream aborted`，transport 为 unavailable，
  未保存虚假 assistant partial。
- `/tmp/c2-resize-final.jpeg`：最终签名包在设备 `5MT0225B12013017` 上，键盘弹出后 header、
  tabs、Timeline、输入器均在正确 viewport。
- `/tmp/c2-jump-final.jpeg`：用户上滑后显示跳底控件，不遮挡正文或输入器；dump 中控件
  约 149×149px（设备 density 约 3，对应源码 44vp 命中区）。
- 测试完成后输入法已恢复 `com.tencent.wetype.hmos`。

## Verification Evidence

- DeepRead：659 passed，0 failed。
- Chat：2199 passed，0 failed。
- ArkTS lint：0 error。
- AGC build：`BUILD SUCCESSFUL`。
- Signed HAP：install success；EntryAbility start success。
- Logic review：最终 PASS，无 P0/P1/P2。
- UI review：最终 PASS，无 P0/P1/P2。

## Rejected

- 未在 Novel 新建 SSE parser、字符串 accumulator、Markdown renderer 或假打字机。
- 未用 operation resolve 时间冒充 data-end。
- 未为 transport 异常增加第二套 provider 或宽泛 fallback。

## Commit

本文件与 C2 实现同一提交；SHA 以该提交 Git 记录为准。
