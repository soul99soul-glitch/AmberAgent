# E0 ChatPage 独立 sheet 宿主修复

状态：代码改动完成，可进入主 agent 中央 lint、构建与设备验收。未运行 lint、构建、签名或设备操作，未声明 UI 已通过。

## 已查明的缺陷

同一根 Stack 上的 9 次 `bindSheet` 共用宿主节点标识。消息长按已连通 `openActionsSheet`，但 actions 的 true 更新后，toolPreview 的 false 调用会关闭相同标识的 sheet。主 agent 已观察到模拟器普通文字长按不出现菜单、单击编辑正常；没有据此修改手势或添加延时重试。

依据为 [E0-sheet-probe.md](E0-sheet-probe.md) 的官方离线文档与 API 12 时期 OpenHarmony 固定源码：

- [`ViewAbstractModelNG::BindSheet`](https://github.com/openharmony/arkui_ace_engine/blob/c8e2e7da833f72df174c7d9b10dfa90d9d07efad/frameworks/core/components_ng/base/view_abstract_model_ng.cpp#L474) 每次取得当前主 FrameNode。
- [`OverlayManager::OnBindSheet`](https://github.com/openharmony/arkui_ace_engine/blob/c8e2e7da833f72df174c7d9b10dfa90d9d07efad/frameworks/core/components_ng/pattern/overlay/overlay_manager.cpp#L4345) 使用 targetId 构成 SheetKey；false 关闭这个 key，true 查找或创建同一个 key。
- 官方离线半模态文档要求宿主已挂载；`HitTestMode.None` 在当前 ChatPage 已有先例。本修复未接入新 API。

## 改动

仅修改 `harmony/entry/src/main/ets/pages/ChatPage.ets`：新增 `@Builder chatSheetHosts()`，在根 Stack 内无条件调用。它包含 9 个稳定的独立空 Column，每个只绑定一张 sheet，尺寸均为 0×0，并设置 `HitTestMode.None`，不占据聊天布局也不拦截触摸。

覆盖 document、artifactShelf、artifactDiff、WebMount、sandbox、SubAgent、copy、actions、toolPreview。删除根 Stack 的 9 次重复绑定；逐项保留原状态（包括前 4 项的 `$$` 双向绑定）、原内容 builder、尺寸、showClose 及 onDisappear 回调。根 Stack 的入场动画保持原样。保留已有导出 overlay 和 Feishu 改动。

这是宿主绑定位置的机械修复，没有统一 sheet 管理器、共享状态、镜像源码测试或全页面改造。

## 已做与待做验证

`git diff --check -- harmony/entry/src/main/ets/pages/ChatPage.ets` 通过；人工逐项对照 9 个绑定的参数和回调，无遗漏。

主 agent 接下来统一运行有效 ArkTS lint、构建并安装新 HAP。设备重点验收普通消息长按打开动作面板、单击编辑、动作面板到复制/导出入口、手势关闭后重新打开，以及对应其余入口的内容与生命周期。实际截图与输出证据仍待中央验收。
