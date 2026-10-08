import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';
import type { HotTopic, HotTopicSource } from '../../platform_impl/HotListAggregator.ets';
import type { SourceSection, HotItem } from '../../platform_impl/NewsNowProvider.ets';
import { DeepReadDiscoveryMotion } from './DeepReadDiscoveryMotion.ets';
import { curves } from '@kit.ArkUI';

@Component
export struct DeepReadDiscoverySectionHeader {
  @Prop active: boolean = true;
  @Prop playEntrance: boolean = true;

  @Builder
  Headline(): void {
    Row({ space: 10 }) {
      Text('多来源热点').fontSize(20).fontFamily(D.serif).fontWeight(600).fontColor(D.ink)
      Text('多个榜单都在讨论').fontSize(12).fontFamily(D.sans).fontColor(D.muted)
      Row().height(0.8).layoutWeight(1).backgroundColor(D.rule)
    }.width('100%').padding({ top: 10 })
  }

  build(): void {
    DeepReadDiscoveryMotion({ index: 0, scatterEnabled: false, active: this.active, playEntrance: this.playEntrance,
      content: (): void => { this.Headline(); } })
  }
}

@Component
export struct DeepReadDiscoveryTopicCard {
  @StorageProp('reduceMotion') @Watch('settlePress') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settlePress') appBackgrounded: boolean = false;
  @Prop topic: HotTopic;
  @Prop index: number = 0;
  @Prop playEntrance: boolean = true;
  @Prop @Watch('settlePress') active: boolean = true;
  @State private pressed: boolean = false;
  onOpen: () => void = (): void => {};
  onActions: () => void = (): void => {};

  private sourceSummary(): string {
    const names: string[] = [];
    this.topic.sources.forEach((source: HotTopicSource): void => {
      if (!names.includes(source.providerName)) names.push(source.providerName);
    });
    return `${this.topic.sourceCount} 个来源 · ${names.join('、')}`;
  }

  aboutToDisappear(): void { this.pressed = false; }

  private settlePress(): void { if (!this.active || this.appBackgrounded) this.pressed = false; }

  private press(event: TouchEvent): void {
    if (!this.active || this.appBackgrounded) return;
    if (event.type !== TouchType.Down && event.type !== TouchType.Up && event.type !== TouchType.Cancel) return;
    const down: boolean = event.type === TouchType.Down;
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 280,
      curve: curves.springCurve(0, 1, 240, 18) }, (): void => { this.pressed = down; });
  }

  @Builder
  CardContent(): void {
    Row({ space: 12 }) {
      Text((this.index + 1).toString().padStart(2, '0')).fontSize(22).fontFamily(D.serif)
        .fontWeight(300).fontColor(D.accent).align(Alignment.TopStart)
      Column({ space: 6 }) {
        Text(this.topic.displayTitle ?? this.topic.title).fontSize(17).fontWeight(600)
          .fontFamily(D.serif).fontColor(D.ink).lineHeight(24).maxLines(3)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Text(this.sourceSummary()).fontSize(12).fontFamily(D.sans).fontColor(D.muted)
          .maxLines(2).lineHeight(17).width('100%')
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text('↗').fontSize(16).fontColor(D.muted).align(Alignment.TopEnd)
    }.alignItems(VerticalAlign.Top).width('100%').padding(14)
      .backgroundColor(this.pressed ? D.paper : D.card).borderRadius(D.radius).border({ width: 0.6, color: D.rule })
      .shadow({ radius: 10, color: '#0F2A2320', offsetY: 4 })
      .scale({ x: this.pressed && !this.reduceMotion ? 0.975 : 1, y: this.pressed && !this.reduceMotion ? 0.975 : 1 })
      .onTouch((event: TouchEvent): void => { this.press(event); })
      .gesture(GestureGroup(GestureMode.Exclusive,
        LongPressGesture().onAction((): void => { this.onActions(); }),
        TapGesture().onAction((): void => { this.onOpen(); })))
      .accessibilityText((this.index + 1) + '，' + (this.topic.displayTitle ?? this.topic.title) + '，' + this.sourceSummary())
  }

  build(): void {
    DeepReadDiscoveryMotion({ index: this.index + 1, active: this.active, playEntrance: this.playEntrance,
      content: (): void => { this.CardContent(); } })
  }
}

@Component
export struct DeepReadDiscoverySourceCard {
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settlePress') appBackgrounded: boolean = false;
  @Prop section: SourceSection;
  @Prop index: number = 0;
  @Prop playEntrance: boolean = true;
  @Prop @Watch('settlePress') active: boolean = true;
  @State private expanded: boolean = true;
  @State private pressedRank: number = -1;
  onOpen: (item: HotItem) => void = (_item: HotItem): void => {};
  onActions: (item: HotItem) => void = (_item: HotItem): void => {};

  aboutToAppear(): void {
    const collapsed: string[] = AppStorage.get<string[]>('deepreadDiscoveryCollapsedSources') ?? [];
    this.expanded = !collapsed.includes(this.section.sourceId);
  }

  private toggleExpanded(): void {
    this.expanded = !this.expanded;
    const collapsed: string[] = AppStorage.get<string[]>('deepreadDiscoveryCollapsedSources') ?? [];
    AppStorage.setOrCreate<string[]>('deepreadDiscoveryCollapsedSources', this.expanded
      ? collapsed.filter((id: string): boolean => id !== this.section.sourceId)
      : collapsed.concat([this.section.sourceId]));
  }

  aboutToDisappear(): void { this.pressedRank = -1; }
  private settlePress(): void { if (!this.active || this.appBackgrounded) this.pressedRank = -1; }

  private pressRow(event: TouchEvent, rank: number): void {
    if (!this.active || this.appBackgrounded) return;
    if (event.type !== TouchType.Down && event.type !== TouchType.Up && event.type !== TouchType.Cancel) return;
    const down: boolean = event.type === TouchType.Down;
    this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 280,
      curve: curves.springCurve(0, 1, 240, 18) }, (): void => { this.pressedRank = down ? rank : -1; });
  }

  @Builder
  CardContent(): void {
    Column() {
      Row({ space: 8 }) {
        Text(this.section.sourceName).fontSize(17).fontWeight(600).fontFamily(D.serif).fontColor(D.ink)
          .layoutWeight(1)
        if (this.section.stale === true) {
          Text('上次内容').fontSize(11).fontFamily(D.sans).fontColor(D.muted)
        }
        Icon({ name: this.expanded ? 'chevron_down' : 'chevron_right', iconSize: 12, color: D.muted })
      }.width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Center)
        .accessibilityText(this.section.sourceName + (this.expanded ? '，点按收起榜单' : '，点按展开榜单'))
        .onClick((): void => { this.toggleExpanded(); })
      if (this.section.error !== null) {
        Text(this.section.error).fontSize(12).lineHeight(17).fontFamily(D.sans).fontColor(D.muted)
          .width('100%').padding({ bottom: 6 })
      }
      if (this.section.stale === true) {
        Text('暂时无法更新，显示上次取得的内容。').fontSize(12).lineHeight(17).fontFamily(D.sans)
          .fontColor(D.muted).width('100%').padding({ bottom: 6 })
      }
      if (this.expanded) {
        if (this.section.items.length === 0 && this.section.error === null) {
          Text('暂无条目').fontSize(13).fontColor(D.muted).width('100%').padding({ top: 8, bottom: 8 })
        }
        ForEach(this.section.items.slice(0, 12), (item: HotItem, index: number): void => {
          if (index > 0) Row().height(0.6).width('100%').backgroundColor(D.rule)
          Row({ space: 10 }) {
            Text(item.rank.toString()).fontSize(16).fontFamily(D.serif)
              .fontWeight(item.rank <= 3 ? FontWeight.Bold : FontWeight.Normal)
              .fontColor(item.rank <= 3 ? D.accent : D.muted).width(26)
            Text(item.displayTitle ?? item.title).fontSize(16).fontFamily(D.sans).fontColor(D.ink)
              .lineHeight(23).layoutWeight(1)
          }.width('100%').alignItems(VerticalAlign.Top).padding({ top: 12, bottom: 12 })
            .backgroundColor(this.pressedRank === item.rank ? D.paper : Color.Transparent)
            .scale({ x: this.pressedRank === item.rank && !this.reduceMotion ? 0.975 : 1,
              y: this.pressedRank === item.rank && !this.reduceMotion ? 0.975 : 1 })
            .onTouch((event: TouchEvent): void => { this.pressRow(event, item.rank); })
            .gesture(GestureGroup(GestureMode.Exclusive,
              LongPressGesture().onAction((): void => { this.onActions(item); }),
              TapGesture().onAction((): void => { this.onOpen(item); })))
        }, (item: HotItem): string => `${this.section.sourceId}-${item.rank}`)
      }
    }.width('100%').padding(14).alignItems(HorizontalAlign.Start).backgroundColor(D.card)
      .borderRadius(D.radius).border({ width: 0.6, color: D.rule })
      .shadow({ radius: 10, color: '#0F2A2320', offsetY: 4 })
  }

  build(): void {
    DeepReadDiscoveryMotion({ index: this.index, active: this.active, playEntrance: this.playEntrance,
      content: (): void => { this.CardContent(); } })
  }
}
