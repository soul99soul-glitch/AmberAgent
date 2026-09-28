# E10 独立逻辑与调用链审查

2026-09-28，基点 `caa5b9d50`。**A 源码逻辑与实际调用链审查 PASS：共享 DTO/持久化、Google 请求/解析、模型设置和 Common/HTML 最终生产源已核，没有未关闭的 confirmed P1/P2。设备/真实账户未验，不能声明视觉或 Google 执行 PASS。**

已载 harmony-arkts skill，读取E10 frozen contract/design和共享实际caller。只写本报告；未修改产品/测试，未运行tests/lint/build/device或增加矩阵。仅按实际触发上报问题，不将in-progress缺项算作最终缺陷。

## 共享调用链

- DeepRead保持唯一UIMessageAnnotation定义，新增两个named DTO，Chat facade与两个index继续re-export同一类型。
- Chat serialize/parse为url_context与google_search_suggestions各加显式分支，原url_citation/generation_interrupted保持；新annotation不跌落为interrupted。
- ProviderModel.tools原codec→toChatModel.tools完整，实际runtime选择choice.model保留tools；effective provider仍按model.providerOverwrite走原factory，E6冻结seed未改成后来全局provider。
- 原StreamAccumulator与不可变merge均沿annotations append+JSONdistinct，来源不会因metadata-only event需要第二buffer或新状态框架；保存/回读消费同serialize链。
- AgentMessageRow和ChatMessageActionFooter传同message.annotations到Common来源消费者，未引第二份来源字段。

## 已核生产请求/解析与模型设置

- `google_request.ts:343–355` search/url_context实映射googleSearch/urlContext；只要有选中内置条目便builtin-only、不注册functionDeclarations，不猜Gemini3混用。二者都未选时沿原function门，image_generation单独不产生空tools或压掉本地函数。`:361` customBody保持最后覆盖，模型页说明该真实规则，未加错误降级/换端点/重复生成。
- `google_parse.ts:121–136` 共用candidate helper保原grounding citations、exact renderedContent HTML和retrievedUrl/实际status；非流`:153`与SSE`:270–277`同消费。metadata-only tail生成assistant空parts+annotations，finishReason/usage沿原chunk，不附假正文；HTTP/SSE/promptFeedback真实错误仍原API层处理。
- 读唯一actual fixture和既有 `/tmp/e10-google-builtin-red.log` → `/tmp/e10-google-builtin-green.log` 1/1：真实model codec→toChatModel→GoogleAPI fakeHTTP正文+metadata-only尾包→真实accumulator→serialize/reload；正文一次、重复citations/status去重、成功/失败url和exactHTML保留，非流与onlyimage两分支同group。本人未跑；网络端点受控不是Google账户接受。
- A owner最终窄types `/tmp/e10-google-builtin-types.log` exit0；两source精确lint `/tmp/e10-google-builtin-lint.log` exit0/0error270跨TSimport unsafe warnings，不误称零缺陷。没有新增其它case/旧suite/build/device。
- 模型设置新增toolUrlContext load/draft/confirm，三个工具统一回写同ProviderModel.tools；Google/Responses/其它协议按实际model.providerOverwrite ?? container判定，unsupported已存选项也保存，文案提前说明Google内置模式不注册本地/MCP以及image选项单独不改变函数模式。无模型名allowlist。
- removeOverwrite成功后同时更新真实page provider snapshot、hasOverwrite和protocol；其后confirm保已提交无overwrite的模型，未用旧快照恢复。三个静态tool Builder参数仅用于type分支，动态State在builder内读；主题为V1 StorageProp/watch，操作44vp，长说明换行，底部navBarHeight沿现有safe area。这里只是source布局，未冒称实拍。

## 来源显示与最终窄修复复核

- `ChatMessageCommon.ets:33–61` 逐一消费实际 SUCCESS/ERROR/PAYWALL/UNSAFE，未知值保原 status 并显示未确认；UNSAFE 来源可读但不打开，失败/付费来源仍显示真实结果，未伪称已读取。`:67–88` 精确分支接两个 named annotations，原 citation 保留；长 URL/title 换行，内容宽度沿消息 100%，行内 padding 和 ≥44vp 操作边界明确。
- `GoogleSearchSuggestions.ets:22–32` 将原 HTML/CSS 不改写地放入 viewport/color-scheme 文档壳，controller attached 后按现有 DeepRead 编码/loadData 路径加载。`:47–58` 使用 FIT_CONTENT（API11）与 SYNC_RENDER（API12），关闭 over-scroll，无固定高度/maxHeight/JS 测量；Web 宽度 100%，JS/DOM storage/多窗口关闭，没有 bridge 或伪造建议 chips。
- `GoogleSearchSuggestions.ets:35–42` 仅一个导航 hook：HTTP(S) 主 frame 用户手势交给 Common 原浏览器路径一次，再返回 true 阻止内嵌加载。about:blank/data 放行；不在第二回调再开链接。`:7–23` 原 HTML 更新与 controller/组件生命周期一致，离开后不再加载，darkMode 改 WebDarkMode 而不改写 provider CSS，forceDark=false。真实 target blank 点击触发仍属设备门，未冒称 PASS。
- 已读本机官方文档缓存 `e10-web-fit-content.md`、`e10-web-loadData-faq.md`、`e10-web-dark.md` 和 `e10-web-types.md:206–224`：内容自适应推荐同步渲染/关闭 over-scroll；loadData 编码和空格 base/history 参数有现有官方/仓内依据；OnOverrideUrlLoadingCallback 为 API12，true 明确阻止加载。没有凭记忆新增 SDK API。
- 独立 B 上报的主题 P2 已精准源复核关闭：`ChatMessageCommon.ets:11–25,54–70` 以 V1 StorageProp/watch 更新本地 State 色板，静态 imported tokens 不再单独承担活跃尾消息的主题刷新；Suggestions `:9–20,60` 同步刷新错误字色，原 Web darkMode 直接响应。没有借主题修复重载 HTML 或新增全局状态。B 上报的移除覆写后确认恢复旧快照 P2 也按前段最终成功提交分支复核关闭；不是本 A 新发现。

## 已有验证证据与边界

已读 `E10-domain.md`、`E10-model-ui.md`、`E10-source-ui.md` 与各 owner 最终回执。A 唯一真实 HTTP/codec/accumulator/持久化 fixture 红→绿 1/1 和窄 types 通过；本人未重跑。Root 共享源 lint 0 error/207 跨文件 warnings；A 两源 0 error/270 跨文件 warnings；B 模型页 0 error/1 原有 JSON clone warning。C 两源最初 0 error/1 现有 Builder 性能建议，主题窄修最终 `/tmp/e10-source-ui-themefix-lint.log` 退出 0、No defects；不将普通 TS 抽样解析当作 ArkUI DSL 完整编译。

截至本报告最终复核，统一实际 Release/本地签名仍由 root gate 记录；本审查不代其声明 artifact 成功。设备离线，亮暗模型设置、实际 HTML 高度/长来源、单次外链打开及真实 Google 账户执行保持 C pending。源码/受控 HTTP fixture 审查通过足以完成本 A 责任，不替代安装、视觉或外部账户验收。
