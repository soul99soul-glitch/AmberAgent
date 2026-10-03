# P5 独立包设备验收

最终源码包：`/tmp/amber-p5-card-final-build.log`，两产品构建与签名校验成功；novel 独立包覆盖安装成功并保留验证项目。仅操作本轮验证项目与独立包。

| Case | 实际观察与证据 | 状态 |
| --- | --- | --- |
| 普通请求冷恢复 | 长输出受控停止后冷开，恢复卡可展示与复制完整部分稿；明确重试完成 7340 字含 P5_FULL_OUTPUT_TAIL；原请求和重试实际 body 相同、失败稿未加入输入。recovery-card-top-final / recovery-full-partial / original-retry-completed | 设备通过；Responses 官方 GET 为真实 adapter HTTP 回归，未使用云凭据 |
| 润色冻结与混合结果 | 第 1、3 章冻结偏好/alias/tag/四种上下文；第 1 章受控格式失败仍处理第 3 章；同 job 定向重试仅第 1 章，最终成功 2/2；第 3 章没有重复请求与收录。polish-latest-result-page / polish-retry-real-outcomes / polish-retry-completed-workspace | 通过 |
| 任务离页/后台 | 普通生成 Home 后完成；润色实际 notification 返回、暂停/继续均沿原 job。hilog 记录 DATATRANSFER 申请与停止；设备授予通知 ID，不声称无限后台存活 | 通过 |
| 系统通知目标 | 实际热通知点击返回正确 project/branch/润色任务；force-stop 后真实已完成通知启动 own novel 并显示正确混合结果。polish-hot-notification-workspace / polish-cold-notification-return / card-final-hot-workspace-writing | 通过；ordinary-hot-notification-return 是错过时机的尝试，不计成功 |
| 再次收录与归档 | 旧书稿候选正确拒绝；新候选真实收录第四章，正文保存与后续分析失败分别反馈；再次收录确认创建新候选并打开第五章位置表单，未重复收录。8 条原讨论持久归档，折叠/展开原文且正文保留 | 通过 |
| 导出保存与分享 | TXT、公开 ZIP、完整备份 ZIP 均进入 DocumentPicker Download 并返回保存成功；ShareKit 显示真实 TXT 文件 798B，未向任何接收人发送。格式字节往返由实际 repository 安装测试独立验证 | 通过；未读取系统保存文件 bytes |
| 进度卡刷新及布局 | 设备复现旧 Builder 按值状态冻结及阶段标题居中；已精确改为直接 Prop/State，阶段左对齐。最终热返回同 Card 从 writing 0/1→暂停→继续→自动完成 1/1，未重新导航或挂载；执行阶段标题左边界108px；card-final-automatic-completed | 设备通过，独立 reviewer 收口中 |

本地合成 provider 仅验证调用链、数据状态与控件，不能作为真实云模型质量证据。未复制宿主密钥。零新调试路由或权限旁路。
