// SettingAgentExecutionPageFull — 假数据/假保存页已下线:本路由保留兼容。
// 2026-09-28 层级修正:执行/显示设置并入「运行环境」(iOS ExecutionSettingsView 同构),
// 进入即跳转 SettingSandboxPage。
import { router } from '@kit.ArkUI';
import { BG } from '../design/tokens.ets';

@Entry
@Component
struct SettingAgentExecutionPageFull {
  aboutToAppear(): void {
    router.replaceUrl({ url: 'pages/SettingSandboxPage' });
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
