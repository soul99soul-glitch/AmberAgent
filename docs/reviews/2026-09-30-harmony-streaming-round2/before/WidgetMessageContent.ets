// Assistant visible text only. User text/reasoning and the stored parts stay unchanged.
import type { GenerativeWidget, GenerativeWidgetSegment } from '@amber/chat-domain';
import { parseGenerativeWidgets } from '@amber/chat-domain';
import { MarkdownText } from './MarkdownText.ets';
import { GenerativeWidgetCard } from './GenerativeWidgetCard.ets';
import { INK, INK3, BODY, LABEL } from '../design/tokens.ets';

@Component
export struct WidgetMessageContent {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('prepare') content: string = '';
  @Prop @Watch('prepare') streaming: boolean = false;
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  @Prop chatFontFamily: string = 'DEFAULT';
  @Prop enableLatexRendering: boolean = true;
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  @Prop streamKey: string = '';
  onVisibleGrow: () => void = (): void => {};
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  @State segments: GenerativeWidgetSegment[] = [];

  aboutToAppear(): void { this.prepare(); }
  private prepare(): void { this.segments = parseGenerativeWidgets(this.content, this.streaming); }

  build(): void {
    Column() {
      ForEach(this.segments, (segment: GenerativeWidgetSegment, index: number): void => {
        if (segment.kind === 'text') {
          MarkdownText({ content: this.segments[index].content, fontSize: this.fontSize, fontColor: this.fontColor,
            streaming: this.streaming && index === this.segments.length - 1,
            chatFontFamily: this.chatFontFamily, enableLatexRendering: this.enableLatexRendering,
            codeBlockAutoCollapse: this.codeBlockAutoCollapse, showLineNumbers: this.showLineNumbers,
            streamKey: `${this.streamKey}#widget-text-${segment.start}`,
            onVisibleGrow: (): void => { this.onVisibleGrow(); } })
        } else if (segment.kind === 'widget' && segment.widget !== null) {
          GenerativeWidgetCard({ widget: this.segments[index].widget as GenerativeWidget,
            onAction: (prompt: string): void => { this.onAction(prompt); },
            onContentGrow: (): void => { this.onVisibleGrow(); } })
        } else {
          Text(segment.kind === 'loading' ? '正在生成可视化组件…' : `组件无法显示：${this.segments[index].content}`)
            .fontSize(LABEL).fontColor(INK3).width('100%').padding({ top: 8, bottom: 8 })
        }
      }, (segment: GenerativeWidgetSegment): string => `${segment.kind}-${segment.start}`)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').alignItems(HorizontalAlign.Start)
  }
}
