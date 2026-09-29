// SettingAgentPermissionsPageFull — 假数据/假保存页已下线:本路由保留兼容,进入即跳转真实页面 ChatAgentPermissionsPage
// (原实现数据伪造/保存不落盘,详见 docs/plans/2026-08-25-harmony-parity-backlog.md F1)
import { router } from '@kit.ArkUI';
import { BG, INK3, BODY, TITLE, INK } from '../design/tokens.ets';

@Entry
@Component
struct SettingAgentPermissionsPageFull {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/ChatAgentPermissionsPage' });
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // 兼容壳:仅 replaceUrl,不渲染占位文案避免闪屏
      Blank()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
    .justifyContent(FlexAlign.Center)
  }
}
