# Amber Harmony 搜索修复

范围：当前 harmony-preview 工作区的搜索配置、供应商、聚合及实际消费入口。基线 HEAD cf02929297ab53cbbf954aa91486880bde45bf81；既有 WIP 只读保护，快照位于 /tmp/amber-search-repair-20261025/baseline。

授权：用户要求复核、分阶段连续修复，每阶段独立 subagent review，检查调用闭环和相关 UI，完成必要验证；不提交、不推送、不清空设备数据、不新增权限。

## Phase 0：复核（完成）

已重跑上一轮生产函数 probes，独立复核确认：Google 兜底工具/站点能力缺口、Grok answer 丢失、Bing captcha 误判、DeepRead 数量超限/失败不切换、智谱 query 超长、日期丢失、SearchPage 未取消、设置连点丢操作、40/50上限不一致、Firecrawl错误字面量。
新增确认：供应商目标 ID 已删除时，旧编辑页 fallbackIndex 可覆盖/删除其他条目；同一旧行连续排序也会错移对象。
保留设计：首选成功仅用单服务；运行持有配置快照；手动搜索不受 Agent 开关约束；浏览器沿用既有站点/宿主权限。

## Phase 1：供应商边界与返回数据（完成）

- Tavily、Brave、Perplexity 在 adapter 内遵守 web 数量上限。
- 智谱限制最终查询长度；只影响该供应商。
- 映射已有发布日期；Firecrawl 返回真实失败信息。
- 验证：现有 provider tests + 真实边界回归；独立 subagent review 调用者和 UI 参数范围。

Phase1 验证：新增回归先红后绿，主 agent定向tests通过；独立review 51/51通过，ArkTS lint 0 error（392 warn，原有类型推断类告警为主）。未改UI结构，参数边界复核完成。

## Phase 2：聚合与执行闭环（完成）

- 单独保留供应商综合答案和来源身份，不冒充逐来源原文。
- Bing 先识别有效结果，验证页才触发熔断。
- DeepRead 首选失败后顺序尝试其他已启用且可用配置；不新增重试，不把成功空结果视作失败，保留总预算/取消。
- 浏览器兜底复用 wm_open 和现有站点 gate，根据真实能力报告可用性；无能力时保留手动链接。
- 验证：工具→HTTP→响应行为、失败/取消/权限分支；独立 subagent review。

Phase2 验证：148项定向行为检查通过，独立review确认DeepRead候选顺序与browser-only分支闭环；实际Bing免费HTTP200返回6条，Brave429仍保留Bing结果。免费实网证据只覆盖主机HTTP Port，不冒称设备/付费认证验收。

## Phase 3：设置与页面生命周期（完成）

- 增减数量在保存队列中计算；上限与编排一致为40，处理历史保存值。
- 编辑/删除只按稳定 ID；排序按条目身份及方向对最新快照操作。
- 保存错误可见，草稿保留；SearchPage 换查询/清空/离页真实取消 HTTP。
- 检查设置列表、编辑器及搜索页的键盘/长文本/对齐/间距；只修实际问题。
- 验证：生产 owner/page fixtures、独立 subagent review。

Phase3 独立review通过。补齐router覆盖/切后台触发onPageHide而未析构的取消路径，返回保留查询/已有结果且不自动重搜。设置存储失败、旧ID冲突、连续操作及读取规范化均以实际owner回归确认；增量page取消4项全过。

## Phase 4：整合、构建与设备验收（完成）

- 对本次改动做一次 code-simplifier 有限维护性检查。
- 运行受影响测试和项目 gate；lint 改动 ArkTS；构建签名。
- 使用保留数据的替换安装；目视设置、供应商表单、深浅色和键盘相关布局；复测调用链。
- 每个实现 phase review 通过后再推进，review 发现的实际问题本阶段闭环。
- 区分源码/行为测试、构建签名、安装启动、真实免费网络、付费供应商和物理设备证据，不冒称未完成的验收。

Phase4 增量：真实模拟器搜索结果发现Bing日期摘要露出`&ensp;`，仅补现有HTML解码器；实际摘要回归先红后绿，独立review验证单次解码和空白规范化，无通用框架改造。旧浅色首屏截图捕到导航过渡帧，补稳定画面后确认不是布局bug。

## 验证记录

- 全仓 `npm run test:harmony`：4795/4795通过。后续隐藏页及HTML实体增量以最终搜索范围检查180/180覆盖，通过后没有重复扩大测试。
- 改动ArkTS各阶段lint均为0 error；仍有类型推断和深克隆warning，不宣称全仓无warning。
- 禁用增量构建，防止旧缓存漏带最后修改；检查HAP的ABC中包含最终DeepRead文案、取消owner及浏览器能力判断。
- `app.amber.deepread` 为本仓库Amber主应用，未切换独立DeepRead产品；现有AGC签名，不新增权限。
- 模拟器及物理设备使用 `install -r` 保留数据；旧会话标题/条数仍可见，真机现有Exa配置及Key未被更改。
- 模拟器：浅色/深色设置、配置列表、Tavily和Grok表单、键盘状态截图独立UI review通过；物理设备Tavily/SearXNG和底部字段键盘状态正常。
- 主机真实免费HTTP：Bing200返回6条，Brave429保持partial且保留其他源。模拟器真实Rcp手动搜索已返回OpenHarmony网页结果。
- 真机只提交一次公开查询，按现有Exa和免费源配置得到文档站/GitHub结果；当前日志缺少单源HTTP记录，不能把每条结果或HTTP200归给Exa。
- 付费供应商的请求数量/日期/错误等修复以生产adapter和受控HTTP契约验证，未宣称所有供应商账户逐一实网认证通过。
- 构建仍有既有symbol压缩包EEXIST/ENOENT告警，主HAP构建/签名通过；不修改与搜索无关的构建配置。

详细日志、脱敏配置、任务源hash、HAP hash与截图：`/tmp/amber-search-repair-20261025/`。工作区并行出现的MathRichBlock/MermaidBlock及其测试不属于本轮搜索修改，保留原样；未提交、推送或清空数据。

最终交付：禁增量构建和AGC签名成功（`build-final.log`），HAP SHA-256为`5d3fcc97fa94478a4e45b8087a92c29a0b95104e459fffde25c2be11325cab77`。两台设备已替换安装此包；模拟器PID21920实搜摘要已消除实体字面量，独立截图review通过；真机PID7241、前台及旧会话核对通过，未重复付费查询。四个实现phase均独立复核关闭，无已确认而未修复的本轮阻断项。
