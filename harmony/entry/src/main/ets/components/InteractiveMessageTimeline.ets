// InteractiveMessageTimeline — Chat/Novel 共用的消息 List 壳。
// 只统一 List 几何、历史入口、活动尾和事件透传；分页、首帧定位与 follow 状态仍由 host 持有。

import { INK3, LINE, META } from '../design/tokens.ets';
import { DotLoading } from './ui/DotLoading.ets';

@Component
export struct InteractiveMessageTimeline {
  scroller: Scroller = new Scroller();
  @Prop horizontalGutter: number = 20;
  @Prop topPadding: number = 0;
  @Prop bottomPadding: number = 0;
  @Prop cachedItems: number = 0;
  @Prop showOlderLoader: boolean = false;
  @Prop showDivider: boolean = false;
  onTimelineAppear: () => void = (): void => {};
  onViewportHeightChange: (height: number) => void = (_height: number): void => {};
  onReachStart: () => void = (): void => {};
  onVisibleRangeChange: (start: number, end: number) => void =
    (_start: number, _end: number): void => {};
  onTimelineTouch: (event: TouchEvent) => void = (_event: TouchEvent): void => {};
  onTimelineScroll: (offset: number, state: ScrollState) => void =
    (_offset: number, _state: ScrollState): void => {};
  onTimelineScrollStop: () => void = (): void => {};
  @BuilderParam rows: () => void;
  @BuilderParam activeTail: () => void;
  @BuilderParam hostTail: () => void;

  build(): void {
    List({ scroller: this.scroller }) {
      if (this.showOlderLoader) {
        ListItem() {
          Row() {
            DotLoading({ dotSize: 6, color: INK3 })
            Text('加载更早消息…')
              .fontSize(META)
              .fontColor(INK3)
              .margin({ left: 8 })
          }
          .width('100%')
          .height(48)
          .justifyContent(FlexAlign.Center)
        }
      }
      this.rows()
      this.activeTail()
      this.hostTail()
    }
    .width('100%')
    .layoutWeight(1)
    .cachedCount(this.cachedItems)
    .padding({
      left: this.horizontalGutter,
      right: this.horizontalGutter,
      top: this.topPadding,
      bottom: this.bottomPadding,
    })
    .onAppear((): void => { this.onTimelineAppear(); })
    .onAreaChange((_oldValue: Area, newValue: Area): void => {
      const height: number = Number(newValue.height);
      if (Number.isFinite(height)) this.onViewportHeightChange(height);
    })
    .onReachStart((): void => { this.onReachStart(); })
    .onScrollIndex((start: number, end: number, _center: number): void => {
      this.onVisibleRangeChange(start, end);
    })
    .onTouch((event: TouchEvent): void => { this.onTimelineTouch(event); })
    .onDidScroll((offset: number, state: ScrollState): void => {
      this.onTimelineScroll(offset, state);
    })
    .onScrollStop((): void => { this.onTimelineScrollStop(); })
    .divider({ strokeWidth: this.showDivider ? 1 : 0, color: LINE })
  }
}
