// ProviderTemplatePickerSheet — 添加提供商(amber-zhenji / Android ProviderTemplatePickerSheet 同构)
// 结构:// OAUTH 区(Codex/Gemini OAuth,鸿蒙暂不支持 → 回调提示)+
//   // 预置 区(DEFAULT_PROVIDERS 减去已存在的 id),行 = monogram34 + 名称 +
//   subtitle(slug)+ auth 徽标。选择 → onPickPreset(整份 ProviderSetting)。
import {
  ACCENT, BG, INK, INK2, INK3, INK4, LABEL, LINE, META, OVERLAY, SURFACE2,
} from '../design/tokens.ets';
import type { ProviderSetting } from '@amber/chat-domain';
import { DEFAULT_PROVIDERS, toProviderMonogram, providerSlug } from '../di/ProviderDefaults.ets';

interface OAuthRow {
  monogram: string;
  name: string;
  subtitle: string;
}

const OAUTH_ROWS: OAuthRow[] = [
  { monogram: 'OC', name: 'OpenAI Codex OAuth', subtitle: 'openai · chatgpt.com 后端' },
  { monogram: 'GO', name: 'Gemini OAuth', subtitle: 'google-gemini · cloudcode-pa' },
];

@Component
export struct ProviderTemplatePickerSheet {
  @Prop show: boolean = false;
  @Prop existingIds: string = ''; // JSON 数组字符串(ArkTS @Prop 传数组受限,序列化传递)
  onPickPreset: (p: ProviderSetting) => void = (_p: ProviderSetting): void => {};
  onOauthPick: () => void = (): void => {};
  onDismiss: () => void = (): void => {};

  private bundled(): ProviderSetting[] {
    const exist: string = this.existingIds;
    return DEFAULT_PROVIDERS.filter((p: ProviderSetting): boolean => exist.indexOf(`"${p.id}"`) < 0);
  }

  @Builder
  sectionLabel(text: string) {
    Row() {
      Text('//').fontSize(11).fontWeight(FontWeight.Medium).fontColor(ACCENT)
        .fontFamily('JetBrainsMono')
      Text(` ${text.toUpperCase()}`).fontSize(11).fontWeight(FontWeight.Medium)
        .fontColor(INK3).fontFamily('JetBrainsMono').letterSpacing(1.3)
      Blank()
    }
    .width('100%')
    .padding({ top: 18, bottom: 10 })
  }

  @Builder
  tagBadge(text: string) {
    Text(text)
      .fontSize(10)
      .fontWeight(FontWeight.Medium)
      .fontFamily('JetBrainsMono')
      .fontColor(text === '—' ? INK4 : INK3)
      .padding({ left: 6, right: 6, top: 2, bottom: 2 })
      .backgroundColor(SURFACE2)
      .borderRadius(3)
      .border({ width: 1, color: LINE })
  }

  build(): void {
    if (this.show) {
      Stack({ alignContent: Alignment.Bottom }) {
        Column()
          .width('100%')
          .height('100%')
          .backgroundColor(OVERLAY)
          .onClick((): void => { this.onDismiss(); })

        Column() {
          Text('')
            .width(42).height(4).borderRadius(2)
            .backgroundColor(LINE)
            .margin({ top: 10, bottom: 6 })

          Scroll() {
            Column() {
              Text('添加提供商')
                .fontSize(22)
                .fontWeight(FontWeight.Bold)
                .fontColor(INK)
                .width('100%')
                .margin({ top: 6, bottom: 4 })

              this.sectionLabel('OAUTH')
              ForEach(OAUTH_ROWS, (row: OAuthRow): void => {
                Row() {
                  Text(row.monogram)
                    .fontSize(11).fontWeight(FontWeight.Bold)
                    .fontFamily('JetBrainsMono')
                    .fontColor(INK2)
                    .width(34).height(34)
                    .borderRadius(9)
                    .backgroundColor(SURFACE2)
                    .border({ width: 1, color: LINE })
                    .textAlign(TextAlign.Center)
                  Column() {
                    Text(row.name)
                      .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(row.subtitle)
                      .fontSize(12).fontColor(INK4)
                      .fontFamily('JetBrainsMono')
                      .margin({ top: 2 })
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  .margin({ left: 12 })

                  this.tagBadge('oauth')
                }
                .width('100%')
                .padding({ left: 4, right: 4, top: 10, bottom: 10 })
                .onClick((): void => { this.onOauthPick(); })
              }, (row: OAuthRow): string => `oa-${row.monogram}`)

              this.sectionLabel('预置')
              ForEach(this.bundled(), (p: ProviderSetting): void => {
                Row() {
                  Text(toProviderMonogram(p.name))
                    .fontSize(11).fontWeight(FontWeight.Bold)
                    .fontFamily('JetBrainsMono')
                    .fontColor(INK2)
                    .width(34).height(34)
                    .borderRadius(9)
                    .backgroundColor(SURFACE2)
                    .border({ width: 1, color: LINE })
                    .textAlign(TextAlign.Center)
                  Column() {
                    Text(p.name)
                      .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(providerSlug(p))
                      .fontSize(12).fontColor(INK4)
                      .fontFamily('JetBrainsMono')
                      .margin({ top: 2 })
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  .margin({ left: 12 })

                  this.tagBadge('key')
                }
                .width('100%')
                .padding({ left: 4, right: 4, top: 10, bottom: 10 })
                .onClick((): void => { this.onPickPreset(p); })
              }, (p: ProviderSetting): string => `pre-${p.id}`)

              Text('列表里没有的?选一个相近的预置,进详情改 Base URL 即可。')
                .fontSize(12).fontColor(INK3)
                .width('100%')
                .margin({ top: 8, bottom: 16 })
            }
            .width('100%')
            .padding({ left: 18, right: 18, bottom: 12 })
          }
          .align(Alignment.TopStart)
          .layoutWeight(1)
          .scrollBar(BarState.Off)

          Text('关闭')
            .fontSize(14).fontColor(INK3)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 14 })
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%')
        .height('88%')
        .backgroundColor(BG)
        .borderRadius({ topLeft: 16, topRight: 16 })
        .onClick((): void => {})
      }
      .width('100%')
      .height('100%')
    }
  }
}
