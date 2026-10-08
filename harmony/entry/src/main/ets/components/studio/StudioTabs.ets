import { ACCENT, INK2, SURFACE, SURFACE2, LINE, SHADOW_KEY } from '../../design/tokens.ets';
import { FONT_SANS_SEMIBOLD } from '../../design/fonts.ets';

// A sliding paper marker, shared by writing modes and material tabs.
@Component
export struct StudioTabs {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') private appBackgrounded: boolean = false;
  @Prop active: boolean = true;
  @Prop options: string[] = [];
  @Prop selected: number = 0;
  onSelect: (index: number) => void = (_index: number): void => {};
  @State private trackWidth: number = 0;

  build(): void {
    Stack({ alignContent: Alignment.TopStart }) {
      Row().width(this.options.length > 0 ? this.trackWidth / this.options.length : 0).height(36)
        .backgroundColor(SURFACE).borderRadius(11).border({ width: 0.5, color: LINE })
        .shadow({ radius: 4, color: SHADOW_KEY, offsetY: 1 })
        .translate({ x: this.options.length > 0 ? this.selected * this.trackWidth / this.options.length : 0, y: 4 })
        .animation({ duration: this.reduceMotion || this.appBackgrounded || !this.active ? 0 : 220, curve: Curve.EaseInOut })
        .hitTestBehavior(HitTestMode.None)
      Row() {
        ForEach(this.options, (label: string, index: number): void => {
          Text(label).fontSize(13).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(this.selected === index ? ACCENT : INK2).textAlign(TextAlign.Center)
            .height(44).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .onClick((): void => { if (this.selected !== index) this.onSelect(index); })
            .accessibilityText(label + (this.selected === index ? '，已选择' : ''))
        }, (label: string): string => label)
      }.width('100%')
    }.width('100%').height(44).padding({ left: 4, right: 4 }).backgroundColor(SURFACE2).borderRadius(15)
      .onAreaChange((_oldArea: Area, area: Area): void => { this.trackWidth = Number(area.width) - 8; })
  }
}
