# 模板与原始来源：iOS 界面复刻实现

本轮修改分配的模板、来源与确认对话框文件，以及获批的模板描述字段存储兼容扩展。iOS 对照源为当前工作树的 `iosApp/DeepReadApp/Sources/UI/DeepReadTemplatesView.swift`、`DeepReadTemplateGeneratorView.swift`、`DeepReadDetailView.swift` 中 `DeepReadSourcesView`，以及 `Sources/Design/DeepReadDesign.swift`。iOS 源只读；此前已有 Harmony dirty/WIP 保留。

## 已实现

| 界面 | Harmony 实现 | 对齐内容与保留行为 |
| --- | --- | --- |
| 阅读模板 | `pages/DeepReadTemplateSettingsPage.ets` | 暖纸背景、34 vp 大导航标题、18 vp 外边距、分组纸卡；节名“文章模板”，内置项 48×48 doc/book 图标预览、17 vp 名称、12 vp 说明、选中 check，明确这是新文章使用的模板。保留真实模板选用和自定义项编辑；补齐列表上的预览、AI 入口。 |
| 编辑模板 | `components/deepread/DeepReadTemplateWorkbench.ets`、`DeepReadTemplateCodeEditor.ets` | 列表新建/自定义编辑打开真实 sheet；inline 标题与取消、可持久化的名称与描述、HTML/CSS 分组、300 vp 等宽代码区、校验/预览/错误/保存顺序。保留 HTML 文件导入、全屏编辑、校验、静态 Web 预览、删除确认、保存并选用；成功完成两个持久化动作后才关闭。模板信息或 HTML 未保存时，标题取消、系统返回及 sheet 滑动共用继续编辑／放弃护栏。 |
| AI 阅读模板 | `pages/DeepReadTemplateWorkbenchPage.ets`，参数 `mode: 'generate'` | 按当前 iOS 保持 push 入口、大标题、名称、多行版式要求、生成草稿、进度、模型说明和错误。使用原有 `getDeepReadTextRuntime()` 实际生成；成功后从 AI 页面打开编辑 sheet，AI 要求页保持原名称和输入。保存／取消仅关闭编辑 sheet，回到同一要求页；生成结果在显式保存前不持久化。 |
| 模板预览 | Workbench 原有 preview sheet | 模板预览/完成标题，独立版 paper/card/ink/accent 渲染选项；宿主预览令牌保持原样。保留 Web 控制器初始化、主题纪元、错误、离页和旧回调保护。 |
| 原始来源 | `pages/DeepReadSourcesPage.ets` | 大标题“原始来源”与完成，每个来源一张分组卡；title/kind/URL/status/error/“查看采集内容”分别成行。保留原始文本、复制、截断说明、系统浏览器操作、URL 验证、实时仓库订阅和离页保护。原有采集限制与重试说明移到列表页脚。 |
| 重新生成确认 | `components/DeepReadConfirmationDialog.ets` | 独立版 17 vp 标题/按钮、13 vp 说明、居中文字、纸卡与朱砂按钮；宿主原样式和回调保留。 |

所有独立视觉入口均由 `getProductKind() === 'deepread'` 判断。共享 `DeepReadDesign` 由主 agent 提供，未改全局 tokens、Board/Article/Compose/Settings、生成 pipeline、bundle 或路由注册。宿主仍使用原 push 工坊，AI 成功继续填同页编辑器。

新增组件：`DeepReadTemplateNavigation.ets`、`DeepReadTemplateCodeEditor.ets`、机械提取的共享 `DeepReadTemplateWorkbench.ets`。共享控制器 843 行；兼容 route wrapper 112 行，负责 AI 生成结果的 sheet 生命周期。继续复用原有存储与生成控制方法，未增加第二套模板服务。三页接入主 agent 提供的 `DeepReadPaperBackground`，来源内容容器透明，保留背景粒纹。

两个 sheet anchor 均位于主题纪元 ForEach 外。每次编辑分配独立的普通属性 `DeepReadTemplateEditorPort` 引用，避免 `@Prop` 深拷贝使 child 返回/离页处理器不能回传 parent。child 负责预览、保存和退出护栏；parent 负责关闭和 reload。sheet 回调捕获 session，AI route 回调另外捕获 lifecycle；旧实例不能关闭或替换新 draft。保存沿用单一 `saveCustomTemplates()` 写入点，随后设置默认选用，parent 不重复 upsert。

共享组件输入为 `templateId`（已存模板）、`initialMode`（editor/generate）、`initialPreview`、`embedded`（sheet 关闭走 parent）、`draftName/draftHtml`（未持久化初始草稿）、`presentGeneratedDraft`（AI 要求页保持 generate 并把成功结果交给 parent sheet），以及普通引用 `editorPort`。输出为 `onDismiss`、已完成两个存储动作的 `onSaved`、仅包含草稿的 `onGenerated(name, html)`。Port 的 `back()` 返回 child 是否消费返回，`leave(dismiss)` 复用预览/保存/dirty 护栏，child 销毁时清除注册。导航组件的 `topInset` 默认读状态栏，embedded 为 0；代码组件的内容与导入/全屏回调由共享控制器提供。

`platform_impl/DeepReadTemplate.ets` 中 `CustomTemplateDef` 与内部 JSON doc 仅增加 optional `description`，旧数据加载为 `''`。新描述与现有 HTML、时间戳一起由原 JSON 存储保存；编辑、HTML 文件导入、重载和列表说明真实消费该字段。不存在独立模板元数据导入/导出接口，未新增迁移键、存储服务或修改文章的捕获 snapshot。

## 路由契约

- `pages/DeepReadTemplateSettingsPage`：阅读模板列表。
- `pages/DeepReadTemplateWorkbenchPage`：保留默认编辑新 HTML 模板的既有 route 契约；独立列表直接嵌入共享编辑器 sheet。
- `params: { templateId: id }`：编辑已保存模板。
- `params: { templateId: id, preview: true }`：先加载该模板，再打开真实示例预览。
- `params: { mode: 'generate' }`：AI 模板要求 push 页，独立版生成成功后再打开 editor sheet。

## 验证

以下定向测试 **79/79 通过**：

```sh
node --test harmony/entry/src/test/deepread_template_ui.test.cjs \
  harmony/deepread/src/test/deepread_template.test.cjs \
  harmony/deepread/src/test/deepread_model_selection.test.cjs \
  harmony/deepread/src/test/deepread_template_library.test.cjs \
  harmony/deepread/src/test/deepread_sources_ui.test.cjs
```

新增行为检查验证独立/宿主预览颜色分别进入实际 HTML、列表预览先加载 durable 模板、修改草稿清除旧校验状态、非法 HTML 不覆盖存储，以及只有模板保存和默认选用均成功才关闭编辑器。AI 生成实际 callback 打开未保存 editor sheet，保存仅写模板一次/默认 ID 一次并返回 AI 要求页；取消不写入，旧生成/route/native sheet 回调不能影响新实例。描述 dirty、旧数据默认值、保存重载与 HTML 文件导入保留描述均通过实际方法与存储验证。已有来源 HTTP URL/浏览器失败/离页旧回调，以及模板捕获、删除、继续生成、旧稿保留等行为检查继续通过。

Workbench 预览默认字体与独立正文/设置一致：未存字体时 deepread 使用 serif，宿主继续系统字体，显式默认/serif 设置不变。字体与字号异步返回后刷新已经初始化的 Web 预览；慢偏好不会让自动打开的 preview 固定在先前默认值，离页仍走现有 alive 门。

复审新增返回行为验证：系统返回和标题取消共用确认、继续编辑／交互关闭弹窗保留 draft、放弃才返回、旧确认不能 pop 重新打开的页面、宿主行为不受影响、返回预览只关闭预览。生成的未保存草稿同样被标记为 dirty。`AlertDialogParam.cancel` 与 `onWillDismiss` 已经由本地官方文档确认，后者自 API 12 支持，无超基线 API。

最终共享 Workbench、wrapper、模板列表、导航组件与模板 schema 五个文件完成 scoped lint：**0 errors / 1 performance warning**，在导航组件的 `avoid-overusing-custom-component-check`；最后偏好刷新变更再检查共享 Workbench 为 **0 defects**。此前来源页、代码编辑器与确认对话框检查亦无 error；原确认对话框有同类 performance warning。`bindSheet`、`SheetOptions.onWillDismiss` 与 `DismissSheetAction` 已由本地官方文档确认可用于 API 12。

集成回归中的两个 Workbench 预览 fixture 缺少新增的产品身份依赖，已在 `deepread/src/test/deepread_template.test.cjs` 注入宿主 `getProductKind`；包括持久化 fixture 一并对齐。机械提取后的测试读取实际共享组件方法。未删除或降低 raw HTML 精确相等、中文/CSS/百分号、初始空文档只加载一次与旧预览/主题回调不影响新预览等断言。`git diff --check` 通过。

设备验收定位：导航取消最小 64×44 vp，17 vp 标题限定 1 行；代码区高 300 vp，导入/全屏按钮最小高 44 vp；全屏取消/完成最小 64×44 vp；校验/预览/保存行最小高 50 vp。这些是源码约束，真实键盘、安全区和可见点击 bounds 仍由主 agent 在设备 UI 树及截图核验。

未独立执行 build、签名、安装或截图。主 agent 集成后仍需编译与实际页面截图回喂；行为测试和 lint 不证明像素一致、键盘、字号或真机浏览器手感。

## 当前基线差异

当前 iOS 页面还包含 4 个阅读版式、8 个阅读样式、8 个生成模板。此次分配的 Harmony 既有链是 `none`、`editorial_slant` 与自定义 HTML 模板捕获；没有同等的 synthesis 消费接口。未添加不能改变文章的选择器，剩余功能范围由主 agent 依据用户答复推进。新的 appearance 元数据与真实文章渲染消费可接入本列表，模板默认仍是 `deepread_template_id`。自定义描述已由获批的最小兼容扩展补齐，不再是缺口。

iOS 当前 `DeepReadTemplateGeneratorView` 的实际界面是简洁 Form，采访/报社主题实际在 `DeepReadNewsroomView` 中；本轮按当前模板 Form 源码复刻。
