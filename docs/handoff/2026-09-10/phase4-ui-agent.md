# Phase 4 UI Q03/Q04/Q05 交接

状态：用户已主动暂停实施。本交接记录只覆盖当前 agent 已落盘内容；没有继续修改产品、测试或执行构建。

## 已落盘改动

### Q03：移除无消费者的语音输入正式入口

`SettingAgentExecutionPage.kt` 已移除 Live mode 中的 `voiceInputEnabled` 开关、文案和 `Volume2` 图标导入。`LiveModeModels` 中的字段以及 `LiveCompanionVM.setVoiceInputEnabled` 没有改动，因此已有设置数据仍可反序列化，兼容字段仍然存在。

该文件同时包含其他 agent 的并行 WIP（例如环境/沙盒入口）；集成时只确认本次 Q03 的删除，不要用基线文件覆盖整个文件。

### Q04：Synara 主 frame 加载状态闭环

`SynaraWorkspacePage.kt` 增加了窄状态模型和 reducer：

- `SynaraWorkspaceLoadState.Loading(progress)`、`Ready`、`Error(message)`；
- `onPageStarted` 重置为 Loading，`onProgressChanged` 只更新 Loading 状态；
- `onPageFinished` 转为 Ready，但保留已经收到的主 frame Error，避免错误被结束回调覆盖；
- `onReceivedError` 和 `onReceivedHttpError` 仅在 `request.isForMainFrame == true` 时转为 Error，子资源错误保留当前状态；
- 错误卡片复用当前 WebView 调用 `stopLoading()` + `loadUrl(pageUrl)` 重试，并通过 `Screen.SynaraCompanion` 返回连接设置；
- 加载时显示进度指示，错误信息限制为清理后的 240 字符，未知错误走本地化兜底文案。

调用链为：`SynaraConnectPage` → `Screen.SynaraWorkspace` → `RouteActivity` → `SynaraWorkspacePage` → `SynaraAndroidWebView` 的 WebView 回调 → 状态 reducer → Loading/Error UI；连接设置按钮回到已有 `SynaraConnectPage`。

### Q05：MiniApp 源码编辑器退出与保存竞态

`MiniAppSourceEditor.kt` 已增加统一的 `requestDismiss()` 和 `miniAppEditorDismissAction()`：

- 返回、AlertDialog 点外、查看模式关闭、预览模式关闭都走同一判断；
- 有未保存内容时显示本地化二次确认，继续编辑只关闭确认框，放弃后才调用 `onDismiss()`；
- saving 时退出请求被忽略，编辑框、预览/返回编辑、放弃更改和关闭按钮全部禁用，避免保存期间修改或关闭；
- 保存成功才关闭；保存失败保留 `editorText`，解除 saving 并将错误放入 issues；`CancellationException` 会继续抛出，不会被当作普通保存失败吞掉；
- 编辑器文本仍由现有 `editorText` 驱动，切换预览使用同一草稿；外层文本区增加高度限制、滚动和 `imePadding()`，给窄屏/大字体键盘场景保留操作空间。

保存链路保持为：编辑文本 → `MiniAppSourceChecks.issues` → `MiniAppRepository.saveNewVersion` → 成功关闭 / 失败保留草稿。

## 文件清单

- 修改：`app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt`
- 修改：`app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt`
- 修改：`app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSourceEditor.kt`
- 新增：`app/src/main/res/values/strings_parity_phase4.xml`
- 新增：`app/src/main/res/values-zh/strings_parity_phase4.xml`
- 新增测试：`app/src/test/java/app/amber/feature/ui/pages/synara/SynaraWorkspaceLoadStateTest.kt`
- 新增测试：`app/src/test/java/app/amber/feature/ui/pages/miniapp/MiniAppSourceEditorStateTest.kt`

完整测试类名：

- `app.amber.feature.ui.pages.synara.SynaraWorkspaceLoadStateTest`
- `app.amber.feature.ui.pages.miniapp.MiniAppSourceEditorStateTest`

## 未验证与剩余风险

- 按主线要求没有运行 Gradle、编译、单元测试或设备测试；当前只执行过 `git diff --check`，无输出。
- Q04/Q05 新增的是纯状态 helper 回归，不是完整的 Compose 设备交互测试。实际集成后仍需验证 AlertDialog 的系统返回、点外、关闭三条路径，以及保存失败和键盘/大字体布局。
- 需要编译确认新资源生成的 `app.amber.agent.R.string.parity_*`、`WebResourceResponse.reasonPhrase`、`BackHandler` 函数引用和 Compose `imePadding` 导入；这些均未在本次暂停前由 Gradle 证实。
- 需要设备复验 Synara 的主 frame HTTP/网络错误遮罩、重试是否复用当前 WebView，以及点击“连接设置”后的导航栈行为。
- 未触及 `MiniAppListPage`、`MiniAppChatCard`、`SynaraConnectPage`、`MiniAppRepository`、`LiveModeModels` 和 `LiveCompanionVM`；它们是现有调用链或兼容数据的边界。

恢复实施时，先由主线做一次 app compile 和两个定点测试类，再安排独立 Compose/UI review；若出现编译失败，优先检查上述新增 import/resource/API，不要回滚并行 agent 的同文件 WIP。
