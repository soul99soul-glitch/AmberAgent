// SettingModelPage — 假数据/假保存页已下线:本路由保留兼容,进入即跳转真实页面 ChatModelPickerPage
// (原实现数据伪造/保存不落盘,详见 docs/plans/2026-08-25-harmony-parity-backlog.md F1)
import { router } from '@kit.ArkUI';
import { BG, INK3, BODY, TITLE, INK } from '../design/tokens.ets';

@Entry
@Component
struct SettingModelPage {
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/ChatModelPickerPage' });
  }

  build(): void {
    Column() {
      // 兼容壳:仅 replaceUrl,不渲染占位文案避免闪屏
      Blank()
    }
    .width('100%').height('100%').backgroundColor(BG)
    .justifyContent(FlexAlign.Center)
  }
}
