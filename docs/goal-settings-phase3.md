# 大阶段 G3:设置页生态(DisplaySetting + 缺失设置页)

> G1-G2 已交付 51 组件 + 1710 测试。G3 是 source-only 前端最后一个实质大阶段。
> G3 完成后 AUTO-NEXT 应终止 loop。

## S1:DisplaySetting 类型 + 持久化(chat HAR)
DisplaySetting 接口(~30 字段) + load/save + AppContainer 访问器 + 测试。

## S2:SettingDisplayPage 显示设置页(entry)
6+ 字段编辑(sendOnEnter/pasteLongTextAsFile/showAssistantBubble/showModelName/showUserAvatar/fontSizeRatio) + 路由。

## S3:sendOnEnter 接入 ChatInput
DisplaySetting.sendOnEnter → ChatInput TextArea EnterKeyType.Send。

## S4:缺失设置页(About/Sandbox/Files/CronTasks)
4 页 ~80-150 行/页 + SettingPage 入口。

## G3 完成后 → AUTO-NEXT 终止
source-only 前端穷尽。剩余全部卡设备/平台。
