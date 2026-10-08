import { curves } from '@kit.ArkUI';
import type { DeepReadLibraryFilter } from '@amber/deepread-domain';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';

@Component
export struct DeepReadLibraryFilters {
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @Prop selected: DeepReadLibraryFilter = 'all';
  @Prop filters: DeepReadLibraryFilter[] = [];
  @Prop labels: string[] = [];
  onSelect: (filter: DeepReadLibraryFilter) => void = (_filter: DeepReadLibraryFilter): void => {};

  private select(filter: DeepReadLibraryFilter): void {
    if (filter === this.selected) return;
    DeepReadHaptics.selection();
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 350,
      curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.35, 0.75) }, (): void => { this.onSelect(filter); });
  }

  build(): void {
    Scroll() {
      Row({ space: 8 }) {
        ForEach(this.filters, (filter: DeepReadLibraryFilter, index: number): void => {
          Stack() {
            if (this.selected === filter) {
              Row() { Text(this.labels[index]).fontSize(15).opacity(0) }
                .padding({ left: 14, right: 14 }).height(34).borderRadius(22).backgroundColor(D.accent)
                .geometryTransition(this.reduceMotion ? '' : 'deepread-library-filter')
                .transition(TransitionEffect.OPACITY.animation({ duration: this.reduceMotion ? 0 : 350 }))
            }
            Text(this.labels[index]).fontSize(15).fontWeight(this.selected === filter ? 600 : 400)
              .fontColor(this.selected === filter ? D.paper : D.ink).padding({ left: 14, right: 14 })
              .height(34).borderRadius(22).border({ width: this.selected === filter ? 0 : 0.5, color: D.rule })
          }.height(44).onClick((): void => { this.select(filter); }).accessibilityText(this.labels[index])
        }, (filter: DeepReadLibraryFilter): string => filter)
      }.padding({ left: D.gutter, right: D.gutter })
    }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).width('100%').height(44).align(Alignment.TopStart)
  }
}
