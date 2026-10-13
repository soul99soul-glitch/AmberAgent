// One stable card while the visual result grows; only prompt actions reach the host.
import type { GenerativeWidget, GenerativeWidgetAction } from '@amber/chat-domain';
import { widgetActionPrompt } from '@amber/chat-domain';
import { INK, INK3, LINE, META, LABEL, SURFACE, ACCENT, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { VisualArtifactPreview } from './VisualArtifactPreview.ets';
import { DotLoading } from './ui/DotLoading.ets';

@Component
export struct GenerativeWidgetCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accent: string = ACCENT;
  @Prop widget: GenerativeWidget;
  @Prop revealKey: string = '';
  @Prop revealEligible: boolean = false;
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  onContentGrow: () => void = (): void => {};

  build(): void {
    Column({ space: 6 }) {
      Row() {
        Text(this.widget.title).fontSize(META).fontFamily(FONT_SANS).fontColor(INK)
          .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (!this.widget.complete) {
          DotLoading({ color: this.accent, dotSize: 3 })
            .width(28).height(14).margin({ left: 8 })
          Text('生成中').fontSize(LABEL).fontColor(INK3).margin({ left: 6 })
        }
      }
      .width('100%').padding({ left: 12, right: 12, top: 10 })
      VisualArtifactPreview({ html: this.widget.html, renderer: this.widget.renderer,
        scene: this.widget.scene ?? null, complete: this.widget.complete,
        revealKey: this.revealKey, revealEligible: this.revealEligible,
        onContentGrow: (): void => { this.onContentGrow(); } })
      if (this.widget.notice.length > 0) {
        Text(this.widget.notice)
          .fontSize(LABEL).fontColor(INK3).width('100%')
          .padding({ left: 12, right: 12, bottom: 6 })
      }
      if (this.widget.complete && this.widget.actions.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.widget.actions, (action: GenerativeWidgetAction, index: number): void => {
            Text(action.label).fontSize(META).fontFamily(FONT_SANS).fontColor(this.accent)
              .constraintSize({ minHeight: 44, minWidth: 44 })
              .padding({ left: 12, right: 12 })
              .borderRadius(22).backgroundColor(accentSoftBg(this.accent))
              .margin({ right: 6, bottom: 6 })
              .onClick((): void => { this.onAction(widgetActionPrompt(this.widget.title, action.instruction)); })
              .accessibilityText(`${action.label}，填入输入框`)
          }, (action: GenerativeWidgetAction, index: number): string => `${action.id}-${index}`)
        }
        .width('100%').padding({ left: 12, right: 12, bottom: 4 })
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').backgroundColor(SURFACE).borderRadius(16)
    .border({ width: 1, color: this.widget.complete ? LINE : this.accent })
    .clip(true).margin({ bottom: 8 })
  }
}
