# Phase 2 DeepRead 修复

责任范围：DR-01–05。Council `runRound/runSeat/synthesize` 局部、私网 URL 等价形式分类、visual/diagram 模型可见 schema 与已有执行器同步。`manager.wait` 属另一个 worker，使用局部 patch，不覆盖其改动。

## RED

已新增 `council_round_boundary.test.ts`、`writer_tool_contract.test.ts`、`url_filter.test.ts` 等价地址用例。

```sh
cd harmony/deepread
node_modules/.bin/tsx --test src/test/council_round_boundary.test.ts src/test/writer_tool_contract.test.ts src/test/url_filter.test.ts
```

结果 **43 tests / 36 pass / 7 fail，exit 1**；失败均为本轮新增反例：

- 同模型 6 席 3 轮，第 2/末轮排队席位读到同轮输出。
- 裁判空串、空白回答误报 completed（两例）。
- localhost root dot 与展开 IPv6/完整 link-local 范围漏分类。
- 模型可见 visuals 参数缺执行器字段。
- 模型可见 diagram 参数缺 type 和节点/边端点。

主 agent 确认 Phase 1 独立复审通过后通知 Phase 2 开始，随后才编辑生产源码。

## 最小实施边界

- 每轮前轮 turns 快照传给所有席位；不新增调度设施。
- 裁判空文本走现有错误和 partial_failed，成功席位保留。
- 只规范化/分类既有 hostname 和 IPv6 地址字面量，不新增 DNS 请求、redirect gate 或重试。
- Schema 声明已有执行器参数和必要 items，不加第二套兼容 parser，保留候选池拒绝逻辑和图解限制。

## GREEN

- DR-01：`runRound` 一次捕获 `round < currentRound` 的 turns，传给本轮所有 `runSeat`，排队席位与先启动席位读取相同前轮证据。原 semaphore 和轮次调度保持。
- DR-02：裁判返回空/空白文本时抛 `empty response text`，进入已有 synthesis 错误结果；所有已完成席位和 transcript 保留，run 现有判定为 `partial_failed`。
- DR-03：hostname 去一个合法 DNS root dot；IPv6 字面量展开八个 16-bit 分组后判定 unspecified/loopback、`fe80::/10`、`fc00::/7` 和 mapped IPv4。用同一正规解析替换本任务直接涉及的旧 mapped-tail helper，不是额外死代码清理。原 flags 和 malformed 私网前缀拒绝语义保留；没有 DNS/redirect/重试。
- DR-04：visual schema 改为执行器已有 `hero_image_url/hero_caption/hero_reason/image_assets`；image_assets item 声明已有字段和 url required。没有添加 hero/inline 兼容 parser，候选池检查未变。
- DR-05：diagram schema 声明现有 5 种 type、必需 type/title/nodes、节点和边端点 items；既有解析、尺寸和候选校验未变。

验证：

```sh
cd harmony/deepread
node_modules/.bin/tsx --test src/test/council_round_boundary.test.ts src/test/writer_tool_contract.test.ts src/test/url_filter.test.ts src/test/section_writer_tools.test.ts src/test/council_manager.test.ts
node_modules/.bin/tsc --noEmit
```

**94/94 通过，exit 0（0.44 秒）；完整 deepread TypeScript 检查 exit 0；改动范围 git diff --check exit 0。**

三份生产源文件 scoped `lint-arkts.sh` **0 error / 814 warn，exit 0**。warn 包括 CodeLinter 对 domain `.ts` imports 解析引出的 no-unsafe-member/call 警告，未把其当作零警告或与 baseline 相同的已证事实。严谨类型检查单独由上面的 domain tsc 通过；Harmony 构建仍由主 agent 在本阶段集成验证。

当前状态：实施和 focused 验证完成，已通知主 agent 进入独立 phase review；本报告不把未执行的独立 review、构建、签名或真机 UI 标为通过。
