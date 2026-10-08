# Phase 2 DeepRead 独立复审

日期：2026-10-02。审查者未参与本阶段实现。本轮仅写此报告，不修改生产源码。对照 `/tmp/amber-harmony-review-20261002-baseline/` 与 `phase2-deepread.md` 检查 DR-01–05；Council `wait` 的并行 worker 改动不纳入本报告批准范围。

结论：**本阶段限定修复未发现阻断问题，可以继续后续阶段。** 以下是当前代码与本轮独立执行结果，不把历史记录当作当前验证。

| 项目 | 二次核查 | 结论 |
| --- | --- | --- |
| DR-01 排队席位同轮信息泄漏 | `council/manager.ts:394-410` 每轮开始时捕获 `round < currentRound` 的 turns 数组，再通过 `runSeat` 参数传给全部席位；`:434-439` response/final position 都用这份前轮数组。前轮 turns 后续不会被本轮修改，新增本轮 turns 只进入 run.turns，既有 semaphore/轮序保持。6 席同模型、3 轮独立测试验证排队席位和末轮均不读取同轮输出。 | 修复成立；无需深拷贝历史或新调度。 |
| DR-02 空裁判误报完成 | `manager.ts:554` 在 verdict 应用前拒绝空/空白回答；`:565-568` 使用现有失败 result；`:339-355` 在成功席位仍存在时准确得到 partial_failed。两组空裁判测试检查成功 turns 均保留、result.error 非空、finalRecommendation 为空。 | 修复成立；没有伪综合裁决或丢掉成功席位。 |
| DR-03 私网字面量等价分类 | `research/url_filter.ts:47-79` 去合法 root dot，正规展开 IPv6 分组后判断 unspecified/loopback、fe80::/10、fc00::/7、IPv4-mapped 的既有 IPv4 私网区间；`:150-181` 只做本地文本解析。独立检查 fe80/fe90/fea0/febf、fc00/fdff、完整展开 loopback/unspecified、mapped 10/172.16/169.254/100.64，和 fe7f/fec0/fbff/fe00、mapped 8.8.8.8/172.32 公网边界。原 flags 语义不变。 | 修复成立；未新增 DNS 请求、redirect gate、缓存或重试。 |
| DR-04 visual 模型 schema | `section_writer_tools.ts:472-478` 声明的 hero_image_url/hero_caption/hero_reason 和 image_assets.url/caption/source/quality_hint/selection_reason 与 `:483-513,762-781` 实际读取逐字段对应。url 在 item required；其余字段允许执行器已有的默认值。`candidateForUrl` / `isHeroCandidate` / `withCandidateEvidence` 仍是接受图像的事实来源，schema 未授予候选池外 URL 权限。 | 修复成立；可选 hero 或 inline 允许独立填写，不人为要求两者同时存在。 |
| DR-05 diagram 模型 schema | `:528-536` 声明的 5 个 type、type/title/nodes 必填，nodes 的 id/label 必填，edges 的 from/to 必填，与 `parseDiagram :785-843` 一致；reason/caption/note/group/edge label 都有对应读取。已有节点去重、引用边检查、数量和长度限制继续由执行器处理。额外缺节点 ID/label、缺边 from/to 的 schema 反例均被拒绝。 | 修复成立；不增加兼容 parser 或任意 HTML/SVG 通道。 |

本轮独立执行：

```sh
cd harmony/deepread
node_modules/.bin/tsx --test src/test/council_round_boundary.test.ts src/test/writer_tool_contract.test.ts src/test/url_filter.test.ts src/test/section_writer_tools.test.ts src/test/council_manager.test.ts
node_modules/.bin/tsc --noEmit
```

结果 **94 tests / 94 pass / 0 fail，exit 0**；deepread TypeScript 检查 **exit 0**。此外使用生产 `isPrivateUrl` 和 `validateJsonSchema` 独立执行 **18 组 IPv6 字面量分类、5 组 required item 反例，全部通过**。

审查边界：未批准或复审其它 worker 的 `manager.wait` 改动；未执行真实 Provider、实际私网网络探测、lint/build/sign/install 或设备视觉验收。这些域逻辑修复不涉及新 UI，最终 Harmony 构建与集成由主 agent 统一验证。本报告不声称端到端设备验收完成。
