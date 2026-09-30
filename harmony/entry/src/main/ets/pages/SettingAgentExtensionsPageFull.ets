// SettingAgentExtensionsPageFull — 假数据/假保存页已下线:本路由保留兼容,进入即跳转真实页面 SettingPage
// (原实现数据伪造/保存不落盘,详见 docs/plans/2026-08-25-harmony-parity-backlog.md F1)
import { router } from '@kit.ArkUI';
import { INK3, BODY, TITLE, INK } from '../design/tokens.ets';
import { canvasGradientColors, currentCanvasGradientAngle } from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';

@Entry
@Component
struct SettingAgentExtensionsPageFull {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/SettingPage' });
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      // 画布纹理层(共享组件;压在渐变上、内容下)
      DesignCanvasLayer({ surface: 'shell' })
      // 兼容壳:仅 replaceUrl,不渲染占位文案避免闪屏
      Blank()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .linearGradient({
      angle: currentCanvasGradientAngle(),
      colors: canvasGradientColors(),
    })
    .justifyContent(FlexAlign.Center)
  }
}
