# E10 模型内置工具设置页

Owner 仅 `ChatModelParamsPage.ets`；已读 harmony-arkts skill、E10-contract 与 Google tools design。保留 root/domain/source UI owner 的并行修改，不改 shared pages、serializer、annotations、request engine 或存储键。

## 实际草稿与存储

- 新增 `toolUrlContext` V1 草稿，读取原 `ProviderModel.tools` 的 `url_context`；确认时和原 search/image_generation 一起写回同一 tools 字段。取消仍丢弃草稿；没有新存储真相或第二执行路径。
- 当前协议不支持的已存选项仍显示、可编辑并保存，未按 provider 类型隐藏、清空或虚构生效。原模型类型、输入模态、context、Headers、Bodies、UA 与 search/image 设置沿原保存链保留。
- 模式说明实际采用 `model.providerOverwrite ?? provider`，Google、OpenAI `useResponseApi` 与其它协议分开；不按容器品牌推断请求协议，不猜模型名称白名单。

## 使用边界提前可见

- Google 搜索或 URL 上下文任一开启时，确认前显示本 phase 为内置工具模式，不注册本地工具和 MCP 函数；关闭这两项回到既有函数工具模式，具体工具仍取实际助手配置。没有宣称所有 Gemini 版本都不能混用。
- Google only image 时明确该选项保留但此处不映射 Google 内置工具，也不单独抑制函数工具。没有承诺 Google 图像生成功能。
- Responses 明确 search/image 沿原映射，Google-only URL context 不发送但保留。其它协议明确不映射这些内置选项；实际 provider/model 错误如实显示。自定义 Body 原最后覆盖语义保持，页面提醒 tools 覆盖与所选模式应一致，没有新重试/降级/抓取。

## 同源覆写与 UI

- 与新 effective 模式说明直接相关的原窄缺陷：移除 override 成功后只改标签，确认还从旧 private provider.models 恢复旧 override。已在同一成功回调同步 no-override provider 模型快照及有效协议；后续确认使用实际已移除的来源。B 独立只读审查确认该源链关闭。
- 保留原页骨架；新增工具 builder 只收静态 tool/last，直接读取页面草稿和主题 state。无裸 BuilderParam 组件 lambda，无新 V2 装饰器。
- 局部 `ModelParameterColors` 保留原 BG/INK/INK3/LINE/RAISED/ERROR token 值，由 darkMode/themePackId/accent 的 V1 StorageProp/watch 更新；页面自身文本、输入 placeholder、线条与背景不依赖不可观察的模块可变 token。共享 PageTemplate 未修改。
- 工具开关 44vp 目标、行≥64vp，原输入从40vp补44vp，UA目标≥44vp；原保存/取消/删除组件已≥44。左右16vp，Top Scroll，footer底部16vp+实际navBarHeight。实际 provider长名称换行，原标题按既有单行省略，不重排整个设置页。

## 必要检查与外部门槛

- 单 owned 文件窄 lint `/tmp/e10-model-ui-lint.log`，退出0，0 error / 1 warning；warning 为原 rebuildProvider JSON deep clone，未因 E10 扩改克隆协议。
- `git diff --check` 退出0。B source review 已核 URL load/save、effective protocol、unsupported preserve、移除 override 同源，无 confirmed 新 P1/P2（完整 UI 来源链报告由 reviewer 等 C owner 收口）。
- 未写或运行 UI tests、fixture/full suite、build、sign、安装、模拟器或设备。root统一必要类型/实际编译及 A/B review；C设备offline pending，不能据源码/lint宣称实际亮暗、开关或保存回读验收已通过。
