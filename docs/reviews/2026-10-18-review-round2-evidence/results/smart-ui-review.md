# Smart UI / response artifacts 二轮审查

只读审查，基于 HEAD `e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55` 加当前已有修复工作树。已阅读 harmony-arkts skill 和 2026-10-10 / 2026-10-12 两份实际设计。没有修改生产或测试文件；本报告与 probe 均在本任务 results 目录。

## 确认的新问题

### D01 · P2 · 3D 围栏收到损坏的终态尾部时，移除已显示的合法对象和静态封面

- 直接原因：`harmony/chat/src/main/ets/chat/response_content.ts:307-309`。未闭合时 `completedThreeObjects(body)` 会渲染已经完成、通过既有 normalizer 的对象；围栏一闭合，整场景 JSON/normalizer 失败即返回 `artifact:null`。
- 生产路径：`WidgetMessageContent.ets:282-299` 的真实 `ResponseSegment()` 在 artifact 非空时挂载 `VisualArtifactCard` / `VisualArtifactPreview`，随后 artifact 为空只输出诊断 Text。相同 ForEach key 不会保留条件分支中已经移除的 Web。`VisualArtifactPreview.ets:101-107` 的 disappear 清除 interaction 并 detachHost，`detachHost():80` 调 `window.AmberVisual.dispose()`；`rawfile/visual/host.js:264-266` 清掉场景资源和最新 payload。因此这是整条预览路由被替换，不是 renderer 内部拒绝更新后仍保持旧对象。
- 实际触发：模型先输出一个完整合法 cube，用户已见其 3D/cover；继续输出第二对象但 type 无效，或尾 JSON 缺少括号，随后输出关闭围栏。第一对象本身未改变，终态却连同它一起消失。正常流式 LLM 截断/格式错误可达，不需要攻击输入。
- 真实源码 probe：`smart-ui-three-probe.ts`，读取真实 parser 和真实 native `ResponseSegment()` builder 方法。partial 的 scene.objects 为 `["cube"]`、snapshot 存在，native child 为 `VisualArtifactCard/VisualArtifactPreview`；closed-invalid-object 和 closed-malformed-tail 均 artifact=null，native child 仅 `diagnostic Text only`。未声称设备逐帧回放已执行。
- 契约：`docs/plans/2026-10-10-harmony-intelligent-ui-and-visual-artifacts.md` 验证矩阵明确要求“3D 非有限数值、重复 ID、超量对象、WebGL 失败”时“无效对象拒绝，已有对象或静态封面仍可用”；P0 本地回放要求“坏记录隔离与最后有效画面”。保留合法对象不代表把整个场景宣称完成。
- 精准修复：终态场景失败时仍用既有 `completedThreeObjects` 提取有界且合法的对象，非空则保留 error/partial artifact 和现有 cover，诊断仍显示、complete=false、禁止收录为 ready/export。不要增加重试或通用缓存框架。用真实 partial→terminal 测试确认正常合法终态不变、损坏尾部仅拒绝坏对象、source replacement 不保留其他来源场景。

## 二次复核后排除

- **numberField 的小数输入中间态**：方法级 probe 显示 `raw()` 会把 `0.` 映射回 `0`，但这不足以证明真实输入丢字符。`NativeSmartUiInputs.change()` 在 scalar 相同时不写 @Prop，Card/Page 的同值回灌也早退，故小数点中间态可能由原生 TextInput 保持。没有设备证据，不计缺陷。保留 `smart-ui-probe.cjs` 仅记录这个已撤回候选，不能把其模拟每次强制回灌当作真实 UI 证据。
- **study 普通翻卡返回后重新隐藏答案**：next/previous 按当前位置结果重建 reveal，不等同 U03 的重挂载丢状态；翻卡返回正面也可能是正常练习行为，没有足够契约认定缺陷。U03 的当前题序列化恢复已修复，不重复报。
- **study 重复题目 ID**：目前进度按有界位置存储，原生 option key 含当前位置；重复 item ID 没有观察到实际状态冲突。与已修复的同题 option ID 歧义不同，不报纯格式偏好。
- **linkCard 没有自动打开 URL**：当前是可复制的原生内容卡，v1 未承诺可执行链接动作；不扩大副作用或权限。
- **disclosure/tabs/accordion 的状态不进入任务快照**：后续设计明确要求展示状态和任务输入区分，是 by design。
- **静态 UI 导出使用默认值**：2026-10-10 计划实施记录已明确“智能 UI 静态导出保存定义默认值，明确不包含当前输入与选择”；不把默认快照当草稿丢失。
- **SVG 校验异步覆盖新 revision**：VisualArtifactPreview 已同时检查 info.revision 与 validationRevision，对应当前原生 revision；无实际旧来源覆盖复现。
- **资源失败阻断草稿**：现实现把 asset diagnostics 局部显示，UI 节点和任务草稿独立，符合规范。
- 现有 U01–U05、R11 的修复逐链核对后未确认新的遗漏，不重复登记。

## 验证边界

本轮运行真实 parser / native route 方法 probe；无生产变更，因此未重复构建、安装、签名或真实 provider 请求。没有物理设备、API 12 或真实模型坏尾部逐帧验收。
