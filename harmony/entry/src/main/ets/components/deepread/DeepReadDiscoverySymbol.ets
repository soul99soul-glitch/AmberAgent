import { curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';

// One finite symbol movement, shared by masthead badges and the empty state.
@Component
export struct DeepReadDiscoverySymbol {
  @StorageProp('reduceMotion') @Watch('settleMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settleMotion') appBackgrounded: boolean = false;
  @Prop @Watch('settleMotion') active: boolean = true;
  @Prop playEntrance: boolean = true;
  @Prop variant: string = 'bounce';
  @BuilderParam content: () => void;
  @State private y: number = 0;
  @State private scaleValue: number = 1;
  @State private angle: number = 0;
  private alive: boolean = false;
  private timer: number = -1;
  private cycle: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    if (!this.playEntrance || !this.active || this.reduceMotion || this.appBackgrounded) return;
    const cycle: number = ++this.cycle;
    this.y = this.variant === 'bounce' ? -3 : 0;
    this.scaleValue = this.variant === 'bounce' ? 1.12 : 1;
    this.angle = this.variant === 'wiggle' ? -8 : 0;
    this.timer = setTimeout((): void => {
      this.timer = -1;
      if (!this.current(cycle)) return;
      const context = this.getUIContext();
      context.animateTo({ duration: this.variant === 'wiggle' ? 140 : 420,
        curve: curves.springCurve(0, 1, 180, 16),
        onFinish: (): void => {
          if (this.variant !== 'wiggle' || !this.current(cycle)) return;
          context.animateTo({ duration: 260, curve: curves.springCurve(0, 1, 200, 22) }, (): void => {
            this.angle = 0;
          });
        } }, (): void => { this.y = 0; this.scaleValue = 1; this.angle = this.variant === 'wiggle' ? 6 : 0; });
    }, 16);
  }

  private current(cycle: number): boolean {
    return this.alive && this.active && !this.reduceMotion && !this.appBackgrounded && cycle === this.cycle;
  }

  aboutToDisappear(): void { this.alive = false; this.settleMotion(); }

  private settleMotion(): void {
    if (this.alive && this.active && !this.reduceMotion && !this.appBackgrounded) return;
    this.cycle++;
    if (this.timer >= 0) clearTimeout(this.timer);
    this.timer = -1; this.y = 0; this.scaleValue = 1; this.angle = 0;
  }

  build(): void {
    Row() { this.content() }.translate({ y: this.y }).scale({ x: this.scaleValue, y: this.scaleValue })
      .rotate({ angle: this.angle })
  }
}

@Component
export struct DeepReadDiscoveryEmptyGlyph {
  @Prop active: boolean = true;
  @Prop playEntrance: boolean = true;

  @Builder
  Glyph(): void { Icon({ name: 'ph_notebook', iconSize: 40, color: D.muted }) }

  build(): void {
    DeepReadDiscoverySymbol({ variant: 'wiggle', active: this.active, playEntrance: this.playEntrance,
      content: (): void => { this.Glyph(); } })
  }
}
