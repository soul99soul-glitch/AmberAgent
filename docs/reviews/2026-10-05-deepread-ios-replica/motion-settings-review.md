# 设置与创建细节复核

2026-10-05；只读当前 iOS `DeepReadApp/Sources/{Settings,Discovery,UI}` 与 Harmony 设置、模板、创建表单 WIP。基线 HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`；读取了 scoped `git diff` 及未追踪组件。以下是本轮首次取样结论，修改者正在并行修复，行号以该取样为准；不能把旧设备图作为修复后的动画或触控证据。未构建、测试或操作设备。

## 确证缺陷

1. **P1：未保存的搜索草稿会丢失。** `SettingDeepReadPage.ets:158` 每次回栈递增 `settingsRefreshEpoch`，`DeepReadSettingsForm.ets:33,51–57` 整份重载持久化搜索配置。修改结果数、免费源或首选服务后进入子页再返回，修改被覆盖。改强调色也通过 `tokens.ets:223` 的 theme epoch、父页 `:742–747` 重挂 Form，丢掉同一局部草稿。iOS `DeepReadSettingsView.swift:55–108` 编辑共享 `SettingsStore`，返回仍保留。草稿所有权须跨重挂保留，服务目录刷新按 ID 合并。

2. **P1：返回模板后可覆盖刚选的新默认。** 模板页 `DeepReadTemplateSettingsPage.ets:70–75` 已保存新 ID，父设置 `onPageShow:161–166` 仅更新自定义目录和模型，不更新 `templateId`；再次保存 `:623` 写回旧值。iOS `DeepReadTemplatesView.swift:66–67,107–108` 更新的是同一个 settings 对象。

3. **P2：提交时机与 iOS 不同。** iOS 热点来源配置 `DeepReadDiscoveryPreferences.swift:16–22` 每次变化持久化，页面没有保存按钮。Harmony `DeepReadDiscoverySettingsPage.ets:83–89,225–247` 只改 State，`:269–270` 要额外保存，直接返回会丢修改。模型选择则反向：父设置 `selectTaskModel:271` 即时保存；iOS `selectedModelID` 要等 `store.save()`。这是既有能力的保存语义差异，固定/跟随模型能力本身不应删除。

4. **P2：可见选中态及点击边界缺失。** `DeepReadSettingsForm.ets:181–184` 的数量按钮仅 40×34、无外扩响应区域，低于 spec §2.2 的 44。`Appearance:132–142` 未选色点缺 `.9` 缩放，名称全部 muted/regular；iOS `SettingsView:293–326` 选中为 ink/semibold，并有 spring `.4/.7` 移环、check 过渡和 selection 反馈。通用 Picker 的 `SettingsVisual.ets:128` 没使用 `selected`，菜单未标记当前项。

5. **P2：主操作与状态动画遗漏。** 通用按钮 `SettingsVisual.ets:153–155` 只有文字，保存与创建缺 iOS 的 `checkmark.circle`/`sparkles`。保存成功已有 3 秒消失（父页 `:625–626`），但缺成功触发的 icon bounce、success 反馈及确认文字 opacity+顶部位移。Composer 文件新增/删除 `:95,250`、错误出现 `:282` 没有 iOS `ComposerView:71,123–126` 的 leading+opacity / snappy 状态过渡。模板 check/icon 的对应缺项由 templates_sources agent 复核，需保留稳定 row identity。

6. **P2：模板预览读取旧的已保存字号。** iOS `TemplatesView.swift:230` 使用父 settings 当前草稿字体/字号；Harmony `DeepReadTemplateWorkbench.ets:211–221,390` 单独读 KV。父设置改字号而未保存，进入模板预览仍是旧字号。

7. **P3：表单尺寸与百分比格式。** Composer `:214–230` 固定 topic/text/URL 高 72/142/90；iOS topic 可垂直自长，补充文本 4–10 行、链接 2–5 行（`ComposerView.swift:45,52–55`）。多行输入的展开一致性需新设备证据，不据此声称已发生裁切。Harmony 百分比 `SettingsForm:210` 直接插值，父页 `:240` 可能保留小数；iOS `SettingsView:104` 显示四舍五入整数百分比，格式层应圆整。

## 已符合及证据边界

现有字体 slider 范围 70–180%、步长 5，UI 字号 17/12、模板图标预览 48×48、行内 gap 14、名称/描述 gap 4、文件移除 44×44、代码编辑高 300 与本切面规格相符；描述、校验变更清理、预览 sheet、AI 要求 push/草稿编辑 sheet 已有真实实现。原生 Form 的默认精确间距、动态大字号、弹簧轨迹和触感仍需主 agent 新设备取样。本文不重开阅读模式、4 编排/8 样式或业务模板范围。
