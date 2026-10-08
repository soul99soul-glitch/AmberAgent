# 独立复审与精准修复

固定基线为 `b2d054bd7a81c64795497098d54cf30ad049e04f`；审查上一轮四页面、六个 studio 组件及三个行为测试。三个新 subagent 分别检查 Standards、Spec/调用链和 UI，未将先前 PASS 作为结论。其余工作区修改保持原样。

## Standards

发现并修复 P2 文案错误：首次生成出现摘要等部分稿后，`hasReadableOutput()` 会成立，但原实现把它作为“原文章已保留”的依据。现复用同屏宿主已有的 `output !== null && isComplete(output)`；空输出及部分稿显示“正在编排这篇阅读”。没有增加业务状态。

独立 reviewer 执行三个 focused test 文件，14/14 通过。V1 状态、主题纪元、订阅和印记定时器的清理未发现新增问题；当前无生产调用的 StudioEmblem 仅标记并保留。三处修复已逐项回送 reviewer 复核。

## Spec 与调用链

未发现确定的新增生产缺陷。小说 collectMessage 等待 commitMutation / commitProject / commitLocked / installSnapshot，再 reload、触发收稿；整章仍保留讨论归档选择。附带资料分析失败不会否定已经保存的正文。

深读 run/runSection 等待 saveGuarded / DeepReadRunRepositoryAdapter.save / repository.upsert，再返回结果。付印要求成功、整篇完整、当前可见周期及未取消；部分成功、失败保留旧文、取消、进度回放均不盖章。创建、导入、热点、设置、资料及导出仍连到既有回调与路由。

## UI

1. 小说页签原视觉高 44 vp，文字点击区仅 36 vp。现在文字点击区 44 vp，外层仍 44 vp；36 vp 标记下移 4 vp，左右 padding 保留。实际点击正文上沿、设定下沿和次级“更多”下沿均可切换。复审截图确认居中、对齐及正文起点保持稳定。
2. 编辑室文案对称淡出让旧“规划结构”和新“概览”叠印。第一次设备端 80 帧采样在 frame10 捕获了真实重叠；只调整旧文案为 0 ms 退出，新文案保留 220 ms 淡入和 4 vp 位移，阶段指示线不变。修后 subagent 检查首 20 帧：frame7 显示旧文案，frame8 只有正在淡入的新文案，frame10 新文案清晰；原叠印消除，进度区高度、取消行及正文起点稳定。

浅深色首页、固定印记槽位及修正后的小说正文首帧未发现当前错位、裁切、间距或按钮遮挡问题。全屏编辑室进入部分正文/紧凑进度的实际帧未见标题与正文叠印，取消按钮可读。画面宽约 377 vp；没有 320 vp、全字号或全帧性能证据。

## 验证与证据

三处改动 scoped lint 均 0 error；DeepReadArticlePage 两条既有 deep-clone warning 保留，StudioTabs/StudioNewsroom 无 warning。两个最终产品已构建、签名并覆盖安装到模拟器；工作区、隔离候选和两个产品副本的 10 个源码指纹一致，最终签名产物见 artifacts.json。

[页签上沿](device/rereview-tabs-top-edge.jpeg)、[页签下沿](device/rereview-tabs-bottom-edge.jpeg)、[次级页签下沿](device/rereview-secondary-tabs-bottom-edge.jpeg)、[首次部分正文正确提示](device/rereview-first-partial-notice.jpeg)、[首次生成完成](device/rereview-native-complete.jpeg)、[编辑室修复前叠印](device/rereview-newsroom-before.jpeg)、[叠印三帧对照](device/rereview-newsroom-before-crops.jpg)。

追加运行两轮本地受控生成：首次原生生成与完整旧稿重生成，各包含规划及四段模型请求，共 10 次。首次部分正文提示正确，完整旧稿重生成保留提示正确，两轮均最终完成；不据此宣称真实云模型质量通过。新增 QA 文章保留供观察。两个测试服务器已停止，仅清理本轮新建 18771 reverse，原 18765 reverse 保留。

修后连续帧：[旧阶段](device/rereview-newsroom-fixed-7.jpeg)、[新阶段淡入](device/rereview-newsroom-fixed-8.jpeg)、[新阶段落稳](device/rereview-newsroom-fixed-10.jpeg)。两次设备端采样各 80 帧；相邻帧间隔中位数约 76/81 ms，时间记录见 rereview-fix-frame-timing.json 与 rereview-final-frame-timing.json。该证据验证此次文案切换，不代表全帧性能测量。

[完整旧稿重生成提示](device/rereview-regeneration-notice.jpeg)、[最终生成完成](device/rereview-final-complete.jpeg)、[最终小说启动](device/rereview-final-novel-home.jpeg)、[最终深读启动](device/rereview-final-deepread-home.jpeg)。

真机 6HK0226627000384 仍 USB Offline；模拟器安装、启动与截图已验证，真机安装和真机截图待设备恢复。本轮未提交、未推送。
