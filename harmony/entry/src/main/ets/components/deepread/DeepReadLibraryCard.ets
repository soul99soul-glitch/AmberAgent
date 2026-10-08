import { curves } from '@kit.ArkUI';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

@Component
export struct DeepReadLibraryCard {
  @Prop title: string = '';
  @Prop date: string = '';
  @Prop status: string = '';
  @StorageProp('reduceMotion') @Watch('syncPulse') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('syncPulse') appBackgrounded: boolean = false;
  @Prop @Watch('syncPulse') active: boolean = true;
  @Prop @Watch('syncPulse') statusKind: string = 'incomplete';
  @Prop summary: string = '';
  @Prop error: string = '';
  @State pressed: boolean = false;
  @State fullyVisible: boolean = true;
  @State dotOpacity: number = 1;
  private inViewport: boolean = false;
  private alive: boolean = false;
  private pulseTimer: number = -1;

  aboutToAppear(): void { this.alive = true; this.syncPulse(); }
  aboutToDisappear(): void { this.alive = false; this.stopPulse(); }

  private stopPulse(): void {
    if (this.pulseTimer >= 0) clearTimeout(this.pulseTimer);
    this.pulseTimer = -1;
    this.dotOpacity = 1;
  }

  private syncPulse(): void {
    this.stopPulse();
    if (!this.alive || !this.active || !this.inViewport || this.reduceMotion || this.appBackgrounded || this.statusKind !== 'running') return;
    this.pulse();
  }

  private pulse(): void {
    if (!this.alive || !this.active || !this.inViewport || this.reduceMotion || this.appBackgrounded || this.statusKind !== 'running') return;
    this.getUIContext().animateTo({ duration: 700, curve: Curve.EaseInOut }, (): void => { this.dotOpacity = this.dotOpacity === 1 ? 0.25 : 1; });
    this.pulseTimer = setTimeout((): void => { this.pulseTimer = -1; this.pulse(); }, 700);
  }

  private changeVisibility(ratio: number): void {
    this.fullyVisible = ratio >= 0.999;
    const visible: boolean = ratio > 0;
    if (this.inViewport !== visible) { this.inViewport = visible; this.syncPulse(); }
  }
  onTap: () => void = (): void => {};

  private tint(): string {
    return this.statusKind === 'complete' ? D.done : this.statusKind === 'failed' ? D.danger : D.warn;
  }

  build(): void {
    Column({ space: 10 }) {
      Row() {
        Row({ space: 6 }) {
          Circle().width(7).height(7).fill(this.tint()).opacity(this.dotOpacity)
          Text(this.status).fontSize(12).fontWeight(600).fontColor(this.tint())
        }.padding({ left: 9, right: 9, top: 4, bottom: 4 }).borderRadius(20)
          .backgroundColor('#1C' + this.tint().substring(1))
        Blank()
        Text(this.date).fontSize(12).fontColor(D.muted)
      }.width('100%')
      Text(this.title).fontSize(20).fontFamily(D.serif).fontWeight(FontWeight.Medium)
        .fontColor(D.ink).width('100%').maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
      if (this.summary.length > 0) { Text(this.summary).fontSize(12).fontColor(D.muted).lineHeight(18).maxLines(2).width('100%') }
      if (this.error.length > 0) { Text(this.error).fontSize(12).fontColor(D.danger).lineHeight(18).maxLines(2).width('100%') }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(16).backgroundColor(D.card)
      .borderRadius(D.radius).border({ width: 0.5, color: D.rule })
      .scale({ x: this.reduceMotion ? 1 : (this.fullyVisible ? 1 : 0.95) * (this.pressed ? 0.975 : 1),
        y: this.reduceMotion ? 1 : (this.fullyVisible ? 1 : 0.95) * (this.pressed ? 0.975 : 1) })
      .opacity(this.reduceMotion || this.fullyVisible ? 1 : 0.5)
      .brightness(this.reduceMotion || !this.pressed ? 1 : 0.98)
      .animation({ duration: this.reduceMotion ? 0 : 280, curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.28, 0.62) })
      .onVisibleAreaChange([0, 1], (_expanding: boolean, ratio: number): void => { this.changeVisibility(ratio); })
      .onTouch((event: TouchEvent): void => { this.pressed = event.type === TouchType.Down; })
      .onClick((): void => { DeepReadHaptics.impact(); this.onTap(); })
  }
}
