// SettingTTSProviderPage — TTS 配置(诚实降级)
//
// Android 基准: TTSProviderConfigure.kt 多 provider。
// 平台事实:当前 SDK 不含 CoreSpeechKit 语音合成 API(核对 ets/kits d.ts),
//   无真引擎可接 → 本页如实展示能力状态,不伪造 provider/试听。
//   朗读入口(消息 speaker 键)经 TtsEngine 诚实降级 toast。

import { promptAction } from '@kit.ArkUI';
import { speakText } from '../platform_impl/TtsEngine.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct SettingTTSProviderPage {
  build(): void {
    Column() {
      PageTitleBar({ title: 'TTS' })

      Column() {
        Text('系统语音引擎').fontSize(BODY).fontColor(INK)
        Text('unavailable · SDK 无 CoreSpeechKit')
          .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono').margin({ top: 3 })
        Text('升级 SDK 后在 platform_impl/TtsEngine.ets 单点接入')
          .fontSize(BODY2).fontColor(INK3).margin({ top: 8 })
      }
      .padding(16)
      .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(14)
      .margin({ left: 18, right: 18, top: 12 })
      .alignItems(HorizontalAlign.Start)

      Row() {
        Text('探测合成能力')
          .fontSize(BODY2).fontColor(INK3)
          .padding({ left: 14, right: 14, top: 8, bottom: 8 })
          .backgroundColor(SURFACE).borderRadius(999)
          .border({ width: 1, color: LINE })
          .onClick((): void => {
            speakText('probe').then((ok: boolean): void => {
              promptAction.showToast({
                message: ok ? '合成可用' : '合成不可用：SDK 无语音 API',
                duration: 2000,
              });
            });
          })
      }
      .margin({ left: 18, right: 18, top: 12 })

      Text('// Android 多 provider(azure/openai)依赖网络服务,后续按需移植')
        .fontSize(META).fontColor(INK4).fontFamily('JetBrainsMono')
        .margin({ left: 18, right: 18, top: 14 })

      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
