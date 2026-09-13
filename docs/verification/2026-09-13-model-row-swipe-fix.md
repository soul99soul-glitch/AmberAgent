# 模型行操作重叠修复

用户在服务商 Qwen 的模型页看到取消、删除、“设为当前”和编辑箭头重叠。已在原真机重现，与用户照片一致。

根因是之前的 Redesign 把 `SwipeToDismissBox` 的前景由不透明卡片改成透明 `Column`，而背景的取消与删除操作始终绘制，因此静止状态也透到了前景上。

修复集中在 `SettingProviderModelTabPage.kt`：前景使用不透明的 `amberCanvas()`，保留原有画布颜色及点阵；只有 `dismissDirection != Settled` 时才组合背景操作。这个状态直接跟踪实际位移，拖动、回弹和取消动画期间保持操作背景，归位后移除，避免隐藏按钮残留在无障碍节点里。

保留左滑后明确点击删除、取消复位、长按排序、设为当前以及编辑回调。未改首页、模型能力标记、提供商配置或持久化逻辑。

验证：

- 新增单个 `ProviderModelRowTest` UI 行为回归，在 320dp 宽度验证静止不显示取消/删除，设为当前与编辑分别触发，左滑不会自动删除，取消复位，再次左滑点击删除只触发一次。通过，无失败或跳过。
- `:app:assembleGraphite` 和原生库完整性检查通过。
- 独立审查核对了缓存中 Material3 的 `dismissDirection`、`reset()` 和前后景绘制契约。
- `git -c core.whitespace=cr-at-eol diff --check` 通过。
- 真机 Xiaomi M610BB（`506e0b25`）保留数据覆盖安装成功。APK SHA-256：`4d7fd372c86f1789368acc78fec28d3e4b4af1ed20e338c1925545e07f294cfd`；签名与此前真机版本一致。

真机安装后的视觉复查等待用户解锁；没有对用户的模型执行删除或切换当前模型。
