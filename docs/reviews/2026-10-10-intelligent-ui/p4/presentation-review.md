# 原生智能 UI 样式与动效补充

- 文本 body/heading/subheading/caption/metric/code/badge；column plain/group；choice list/chips；button primary/secondary。
- column stack/row/grid 可与 neutral/accent/success/warning/info 语义色组合，row 每行最多三项，grid 双列。
- 模型生成指令、严格解析、原生渲染、产物收集与静态导出完整接通；旧定义无需新增字段。
- 140ms 按压和选中反馈，300ms 文本淡入/位移。仅 choice 状态切换使用动画事务；输入键入不叠加动画。
- 22 项协议/交互/提示检查，6 项宿主/fixture 检查，4 项产物收集/导出检查通过；ArkTS lint 无 defects，最终构建成功。
- 真机本地回放：标题、状态徽标、横排信息块、chips、强调内容、代码文本、输入框和主按钮；选择切换截图捕获文本过渡中间帧。
- 模拟器：双列与列表正常，输入 umbrella 并选择通勤后，草稿含通勤和 umbrella；未发送模型请求。
- subagent review 发现静态快照缺少三个样式字段，补复制及收集/导出回归断言，复审通过。
- 未做全量回归、所有样式排列或深色主题运行矩阵；本轮不做性能基准。
