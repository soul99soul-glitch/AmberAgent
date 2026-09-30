// SettingAgentExecutionPageFull — 假数据/假保存页已下线:本路由保留兼容。
// 2026-09-28 层级修正:执行/显示设置并入「运行环境」(iOS ExecutionSettingsView 同构),
// 进入即跳转 SettingSandboxPage。
import { router } from '@kit.ArkUI';
import { BG } from '../design/tokens.ets';
import { canvasGradientColors, currentCanvasGradientAngle } from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';

@Entry
@Component
struct SettingAgentExecutionPageFull {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/SettingSandboxPage' });
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
