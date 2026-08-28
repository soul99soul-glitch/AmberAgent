// SettingTTSProviderPage — TTS 配置(诚实降级)
//
// Android 基准: TTSProviderConfigure.kt 多 provider。
// 平台事实:当前 SDK 不含 CoreSpeechKit 语音合成 API(核对 ets/kits d.ts),
//   无真引擎可接 → 本页如实展示能力状态,不伪造 provider/试听。
//   朗读入口(消息 speaker 键)经 TtsEngine 诚实降级 toast。

import { promptAction } from '@kit.ArkUI';
import { speakText } from '../platform_impl/TtsEngine.ets';
import {
  BG, INK, INK2, INK3, INK4, LINE,
  BODY, BODY2, META, MONO_SMALL, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer, PaperSeparator } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';

@Entry
@Component
struct SettingTTSProviderPage {
  build(): void {
    Column() {
      PageTitleBar({ title: 'TTS' })

      Column() {
        Row() {
          Column() {
            Text('系统语音引擎')
              .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            Text('unavailable · SDK 无 CoreSpeechKit')
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(INK4).margin({ top: 3 })
            Text('升级 SDK 后在 platform_impl/TtsEngine.ets 单点接入')
              .fontSize(BODY2).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.secondary.lineHeight)
              .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK3).margin({ top: 8 })
          }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('离线')
            .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(INK4)
        }
        .width('100%').constraintSize({ minHeight: 64 })
        .padding({ left: 16, right: 16, top: 12, bottom: 12 })
        PaperSeparator()
        Row() {
          Text('探测合成能力')
            .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
            .layoutWeight(1)
          Text('运行')
            .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(INK2)
        }
        .width('100%').constraintSize({ minHeight: 64 })
        .padding({ left: 16, right: 16, top: 12, bottom: 12 })
        .onClick((): void => {
          speakText('probe').then((ok: boolean): void => {
            promptAction.showToast({
              message: ok ? '合成可用' : '合成不可用：SDK 无语音 API',
              duration: 2000,
            });
          });
        })
        PaperSeparator()
        Text('// Android 多 provider(azure/openai)依赖网络服务,后续按需移植')
          .fontSize(META).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
          .fontFamily(FONT_MONO).fontColor(INK4)
          .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      }
      .width('100%')

      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
