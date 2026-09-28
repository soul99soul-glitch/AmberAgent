# E0 ChatPage sheet 入口阻断窄探查

结论：发现 P1。同一个根 Stack 上的 9 次 `bindSheet` 共用同一个宿主节点标识；打开消息操作面板后，后面的 `toolPreviewSheetOpen = false` 调用会关闭相同标识的面板。长按到 `openActionsSheet` 的连接本身完整，不应先改手势或添加延时兜底。

本次仅阅读源码、必载 ArkTS skill、官方离线文档及 OpenHarmony 官方框架源码；未修改产品代码，未构建、lint 或操作设备。模拟器现象由主 agent 提供，本报告不声明设备修复已验证。

## 具体调用链

- `harmony/entry/src/main/ets/components/AgentMessageRow.ets:518–526`：用户消息在能力允许时给 `MessageContent` 的 Column 绑定 Exclusive 长按/单击组合；长按调用 `onOpenActions`，单击调用编辑。
- `harmony/entry/src/main/ets/pages/ChatPage.ets:5202`：`onOpenActions` 注入 `openActionsSheet(row)`。
- 同文件 `3332–3335`：仅生成中禁止打开；先保存 row，再将 `@State actionsSheetOpen` 置 true。
- 同文件 `5908–5958`：document、artifactShelf、artifactDiff、webmount、sandbox、subagent、copy、actions、toolPreview 共 9 次 `bindSheet` 全部位于同一根组件的通用属性链。
- 同文件 `5950–5958`：actions 的 true 更新紧接着 toolPreview 的 false，两个调用针对同一节点。

## 官方证据与机制

先读离线官方文档：

- `API参考/ArkUI_方舟UI框架/ArkTS组件/通用属性/模态转场设置/半模态转场/ts-universal-attributes-sheet-transition`：API 10 起可用；sheet 与宿主节点严格关联；宿主需要已挂载。该文档未直接说明多次调用的相互作用，故继续查框架源码。
- `API参考/ArkUI_方舟UI框架/ArkTS组件/手势处理/基础手势/LongPressGesture/ts-basic-gestures-longpressgesture`：默认长按阈值 500 ms。
- `API参考/ArkUI_方舟UI框架/ArkTS组件/手势处理/GestureGroup/ts-combined-gestures`：Exclusive 是同时识别，先成功者使其余手势失败；现有长按+Tap 排列合法。

以 OpenHarmony 5.0.0 官方分支固定提交 `c8e2e7da833f72df174c7d9b10dfa90d9d07efad` 核对 API 12 时期实现：

1. [`ViewAbstractModelNG::BindSheet`，474–527](https://github.com/openharmony/arkui_ace_engine/blob/c8e2e7da833f72df174c7d9b10dfa90d9d07efad/frameworks/core/components_ng/base/view_abstract_model_ng.cpp#L474)：每次取得当前主 FrameNode，传给 overlay manager。连续挂在同一组件上的属性调用得到相同节点。
2. [`OverlayManager::BindSheet`，3947–3988](https://github.com/openharmony/arkui_ace_engine/blob/c8e2e7da833f72df174c7d9b10dfa90d9d07efad/frameworks/core/components_ng/pattern/overlay/overlay_manager.cpp#L3947)：每个调用安排处理自己的显示状态。
3. [`OverlayManager::OnBindSheet`，4345–4380](https://github.com/openharmony/arkui_ace_engine/blob/c8e2e7da833f72df174c7d9b10dfa90d9d07efad/frameworks/core/components_ng/pattern/overlay/overlay_manager.cpp#L4345)：sheet key 由宿主 targetId 构成；false 直接关闭该 key，true 检查/创建同一个 key。独立布尔状态不意味着独立 sheet。

因此根因应精确表述为“共享宿主标识，后续 false 关闭前面 true 打开的 sheet”，无需假设 SDK 把 builder 字段简单覆盖。最新官方 master 的 model 层也继续把当前主节点传给 overlay，未见允许同一宿主同时配置不同 sheet 身份的接口。

## 最小修复建议

保留现有状态、builder 和 onDisappear 回调，把每个 `bindSheet` 移到根 Stack 内一个稳定的、独立的空 Column 锚点上；去掉根属性链的重复绑定。每个锚点一个绑定。例：

```ts
Column()
  .width(0).height(0)
  .hitTestBehavior(HitTestMode.None)
  .bindSheet(this.actionsSheetOpen, this.messageActionsSheetBuilder(), {
    height: SheetSize.LARGE,
    onDisappear: (): void => { this.clearActionsSheet(); },
  })
```

锚点必须始终存在，不能只在 `actionsSheetOpen` 为 true 时创建；官方要求先挂载宿主。现有 `ChatPage.ets:3299` 已使用 `hitTestBehavior(HitTestMode.None)`，无需新 API 或额外封装。空锚点不需要容纳 sheet 内容，也不会占据列表空间或拦截触摸。

可先将 actions/copy 各移一个独立锚点进行模拟器 A/B，证明长按动作面板和复制入口恢复，再按同样机械变更处理其余 7 个。整个修复不需要统一 sheet 管理器、计时重试、全局状态或手势优先级改造。

## 其他可能原因核对

- `sending` 的早退是明确现有行为；主 agent 应使用已停止生成的消息验收，避免将限制误判为手势失败。
- 普通用户文字经 `MarkdownText` 渲染，未配置 `copyOption`、选择菜单或整段文字长按；不存在已查明的普通文字子手势争抢证据。
- Markdown 链接 Span 自身确实有独立长按复制 URL/点击菜单（`MarkdownText.ets:988–1001`），图片有点击预览；这些是特殊内容的既有交互，不解释纯文本气泡的普遍阻断。不要为恢复消息菜单抢走链接/图片正常操作。
- 官方提示可拖拽 Text/Image 与 ≥500 ms 长按可能竞争；如独立 sheet 宿主修复后仍只有特定内容长按失败，再凭日志/不同命中区域查该竞争，当前不建议缩短 duration 或添加 priorityGesture。
- 仓内 `ChatMcpSettingsPage.ets:659–667` 也在同一宿主连挂编辑/导入两个 sheet，具有同类问题；本次未验证对应 UI，不将其当作已验证故障，也不在本探针中扩展修复范围。

## 中央验收需要

1. 普通用户文字稳定按住超过 500 ms 后，动作面板出现，单击仍能编辑。
2. 动作面板“复制”可进入复制面板，Markdown/纯文本选择可见且内容正确；面板外点击、下拉退出后可以重新打开。
3. 动作面板“导出对话”可进入导出 overlay，导出图片/分页链继续验收。
4. 现有文档、artifact、WebMount、SubAgent、tool preview 入口分别打开对应内容，不被其他关闭状态吞掉。
5. 不声明截图/设备 PASS，直到主 agent 返回新的安装版本及实际截图/输出证据。
