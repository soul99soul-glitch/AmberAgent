// SettingAgentPermissionsPageFull — 假数据/假保存页已下线:本路由保留兼容,进入即跳转真实页面 ChatAgentPermissionsPage
// (原实现数据伪造/保存不落盘,详见 docs/plans/2026-08-25-harmony-parity-backlog.md F1)
import { router } from '@kit.ArkUI';
import { BG, INK3, BODY, TITLE, INK } from '../design/tokens.ets';

@Entry
@Component
struct SettingAgentPermissionsPageFull {
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/ChatAgentPermissionsPage' });
  }

  build(): void {
    Column() {
      Text('该设置已迁移').fontSize(TITLE).fontColor(INK)
      Text('正在跳转到新页面…').fontSize(BODY).fontColor(INK3).margin({ top: 8 })
    }
    .width('100%').height('100%').backgroundColor(BG)
    .justifyContent(FlexAlign.Center)
  }
}
