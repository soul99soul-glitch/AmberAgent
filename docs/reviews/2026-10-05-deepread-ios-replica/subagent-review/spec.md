# Spec：独立深读动作与配置复核

复核目标为当前 Harmony WIP，而不是 HEAD 提交内容；HEAD 为 `b2d054bd7a81c64795497098d54cf30ad049e04f`。iOS 只读参考根为 `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources` 和 `iosApp/iosApp/DeepReadCore`。生产、测试、设备配置均未修改；本文及 `/tmp` probe 是本 agent 唯一写入。检查使用当前实际方法，不将历史报告当作现状证据。

## Looks implemented but wrong

### S1 · P2 · 非法网页链接仍成功创建阅读

- iOS 规则：`UI/DeepReadComposerView.swift:149–159` 逐条检查 HTTP/HTTPS URL、host，非法时设置 inline error 并 `return`，不调用 `runtime.create`。
- Harmony：`harmony/entry/src/main/ets/components/deepread/DeepReadComposerSheet.ets:132–162` 的 `createArticle` 直接 `sourceInputs` → snapshot → `upsert` → success feedback → `onCreated`；`harmony/deepread/src/main/ets/domain/input_sources.ts:41–52` 仅将非法 URL 保存成 failed source，没有阻止创建。
- 触发：填写有效主题，链接框填 `ftp://invalid.example`，点开始。
- 实际方法 probe：`entriesSaved=1`、`articleCallback=1`、`error=""`，failed source 的 error 为“请输入有效的 HTTP 或 HTTPS 网页链接。”。因此输入阶段没有向用户指出无效链接，却转入文章执行流程。模型是否实际调用不属于本 probe 的证明范围。
- 证据：实际生产方法+真实 sourceInputs，通过假 storage/repository 端口验证；未操作真实 provider。

### S2 · P2 · 具体模型/搜索服务行没有连到对应对象

- iOS：`Settings/DeepReadSettingsView.swift:36–46、62–74` 使用当前 `$model` / `$service` 直接打开对应 editor。
- Harmony：`harmony/entry/src/main/ets/components/deepread/DeepReadSettingsForm.ets:172–177、190–196` 每个具体行都打开共享列表 `ChatProviderSettingsPage` / `ChatSearchSettingsPage`，不传 provider ID、model ID 或 service ID；添加行也只进入相同目录。
- 触发：在深读设置点某个已显示模型或某个已显示搜索服务，目标是编辑该项；实际先回整个列表，再需重新辨认并选择。
- 影响：按钮路由有接通，但对象级动作与 iOS 不同；同名服务或多个模型时增加误编辑风险。不是断路或无法配置，不升级为 P1。
- 证据：两个平台当前调用点、目的页 `ChatProviderSettingsPage.ets:60–64、270–274` 和 `ChatSearchSettingsPage.ets:144–148` 没有读取这种目标参数。

### S3 · P2 · iOS 模板槽位校验通过，却留在最终 HTML 中

- iOS：`DeepReadCore/IOSDeepReadHTMLSecurity.swift:203–213` 要求 `title / summary / analysis_html / extended_reading_html / font_css`；`DeepReadCore/IOSDeepReadTemplates.swift:202–214` 会替换这些槽位。
- Harmony：`harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets:243–244、256–261、325–333` 接受 `title` / `summary` 即可，但不识别后三个 iOS 槽位。
- 触发：将含上述五个槽位的有效静态 iOS HTML 放入编辑器，点校验和预览。实际 `validateTemplateHtml` 返回空字符串（通过）；`renderCustomTemplateHtml` 结果残留 `{{font_css}}`、`{{analysis_html}}`、`{{extended_reading_html}}`，缺失章节再追加到页面末尾而非指定位置。
- 影响：自定义模板能力存在，但 iOS 编辑器合同不能直接复用；字面槽位及布局错位会出现在预览/文章。此条是跨端模板合同差异，不要求迁移已存 Harmony 模板或重写生成管线。
- 证据：实际生产 validator+renderer，使用内置 sample output。iOS 规则源码检查，未运行 Swift renderer。

## Missing / partial

### S4 · P2 · 最后一个无效搜索服务无法删除

- iOS：`Settings/DeepReadSettingsView.swift:74` 的 `onDelete` 无最少一个限制，服务可删至零；首选 picker 有“请选择服务”。
- Harmony：`harmony/entry/src/main/ets/pages/ChatSearchSettingsPage.ets:467–472` 两处 `size<=1` guard；`:879–880` 也隐藏最后一项的删除按钮。深读设置使用的就是这个页面。
- 触发：只剩一个过期/无效搜索服务，进入编辑准备删除。无法删除，必须保留或再新增一个才能删。
- 证据：实际 `confirmDelete` 对单项输入调用 `saveEditorPatch` 0 次；没有改动共享宿主语义。

### S5 · P3 · 来源展开行为是互斥的

- iOS：`UI/DeepReadDetailView.swift:253–269` 每个 source 各有独立 `DisclosureGroup`，可以同时展开多份采集内容。
- Harmony：`harmony/entry/src/main/ets/pages/DeepReadSourcesPage.ets:25、96–105` 仅一个 `expanded` ID；打开第二来源必定关闭第一来源。
- 影响：同屏对照多个来源需要反复开关。功能可用，但交互并非同等行为。
- 证据：当前状态结构与 toggle 实现；未声称设备几何缺陷。

### S6 · P2 · 保留旧稿的再生成期间不能导出

- iOS：`UI/DeepReadDetailView.swift:127–133` 分享仅受 exporting 或结果正文为空限制，已有正文在任务 running 时仍可分享。
- Harmony：`harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets:273–276、858–860` 对 `running` 强行禁分享及打开 export。
- 触发：完整文章重新生成，旧正文仍显示，想先保存/分享原版本。直到运行结束前无法打开导出。
- 证据：源码条件对照；未重新跑完整生成与导出链路，不把它当文件损坏或数据丢失。

## Scope creep

未发现本轮为 UI 补齐而越界重写 provider 或生成管线。工作区保存、字体缩放和共享服务配置是 Harmony 已有能力，不能仅因 iOS 页面没有就要求删除。模板额外防丢修改确认也不作为缺陷。

## 已通过的局部闭环与已知边界

- 模板保存实际验证 HTML、保存列表、写默认 ID 后才报告选用成功；失败会区分模板已保存但选用失败（`DeepReadTemplateWorkbench.ets:270–323`）。删除后仅在持久默认仍为被删 ID 时重置（`:338–360`）。
- 未保存模板的返回先关闭 preview，再阻挡 saving，再确认放弃；sheet 父页通过 editorPort 转交 Back/WillDismiss（`DeepReadTemplateWorkbench.ets:141–190`、`DeepReadTemplateSettingsPage.ets:82–89、191–207`）。
- settings font/scale 通过 typed route 参数传给模板预览，预览 KV 回读不会覆盖有效 override（`DeepReadTemplateWorkbench.ets:194–228`）。
- 统一 Save 搜索写入保留当前目录/凭据并按 ID 找选中服务，选中对象已删除时明确报错；阅读/模型成功后才显示 success（`DeepReadSettingsForm.ets:109–135`、`SettingDeepReadPage.ets:827–857`）。这并非对 storage 故障回滚或真云端可用性的认证。
- 已公开的 Tab 随滚动收缩、source→article zoom、原文/样式/编排选择三个缺口不计为新发现；单源精读 Composer 分支同属已知业务缺口，不重复报告。

## Probe 结果与复验方式

`node /tmp/deepread-spec-probe-20261005.cjs` 只抽取执行当前实际方法/renderer，以测试现有依赖替身。结果写 `/tmp/deepread-spec-probe-result-20261005.json`；核心输出为：

```json
{"template":{"validation":"","unresolvedPlaceholders":["{{font_css}}","{{analysis_html}}","{{extended_reading_html}}"]},"invalidUrl":{"entriesSaved":1,"articleCallback":1,"error":""},"deleteLastSearchService":{"deleteWrites":0}}
```

未运行全量测试、构建、签名或设备点击；没有把它们重复报告为本 agent 验证。UI 错位/边距/大小的设备截图由独立 UI reviewer 负责。发现均是当前可复核源码/实际方法问题，未推断何次提交引入。
