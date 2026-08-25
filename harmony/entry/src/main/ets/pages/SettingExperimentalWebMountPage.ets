// SettingExperimentalWebMountPage — Alpha 能力未实装(诚实占位)
// 原页面用定时器伪装"连接成功/抓取完成",属欺骗性 UI,已替换为本占位页。
// 真实 WebMount/Office Pro 接线见 parity backlog F8。
import { router } from '@kit.ArkUI';
import { BG, INK, INK2, INK3, BODY, TITLE, SURFACE, LINE, PAD_CARD, RADIUS } from '../design/tokens.ets';

@Entry
@Component
struct SettingExperimentalWebMountPage {
  build(): void {
    Column() {
      Text('Alpha · 未实装').fontSize(TITLE).fontColor(INK)
      Text('该实验能力尚未在 Harmony 版实现,原「连接测试/保存」为模拟反馈,已下线。')
        .fontSize(BODY).fontColor(INK3)
        .textAlign(TextAlign.Center)
        .margin({ top: 10, bottom: 24 })
      Text('返回').fontSize(BODY).fontColor(INK2)
        .padding({ left: 20, right: 20, top: 8, bottom: 8 })
        .onClick((): void => { router.back(); })
    }
    .padding({ left: PAD_CARD, right: PAD_CARD, top: 24, bottom: 24 })
    .backgroundColor(SURFACE)
    .borderRadius(RADIUS)
    .border({ width: 1, color: LINE })
    .margin({ left: 24, right: 24 })
    .width('100%').height('100%').backgroundColor(BG)
    .justifyContent(FlexAlign.Center)
  }
}
