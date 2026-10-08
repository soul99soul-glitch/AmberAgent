import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

@Component
export struct DeepReadPaperBackground {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @Prop nightReading: boolean = false;
  @State lampOpacity: number = 0;
  private alive: boolean = false;
  private lampTimer: number = -1;

  private isNight(): boolean {
    const hour = new Date().getHours();
    return this.nightReading && (hour >= 22 || hour < 5);
  }

  aboutToAppear(): void {
    this.alive = true;
    if (!this.isNight()) return;
    // Let the initial transparent frame render before starting the lamp fade.
    this.lampTimer = setTimeout((): void => {
      this.lampTimer = -1;
      if (!this.alive) return;
      this.getUIContext().animateTo({ duration: this.reduceMotion ? 300 : 1600,
        delay: this.reduceMotion ? 0 : 200, curve: this.reduceMotion ? Curve.EaseIn : Curve.EaseOut }, (): void => {
        this.lampOpacity = 1;
      });
    }, 16);
  }

  aboutToDisappear(): void {
    this.alive = false;
    if (this.lampTimer >= 0) clearTimeout(this.lampTimer);
    this.lampTimer = -1;
  }

  build(): void {
    Stack() {
      Image(this.darkMode ? $r('app.media.deepread_paper_grain_dark') : $r('app.media.deepread_paper_grain_light'))
        .width('100%').height('100%').objectFit(ImageFit.Cover)
      if (this.isNight()) {
        Column() {}
          .width('100%').height('100%')
          .radialGradient({ center: ['82%', 0], radius: 520, repeating: false,
            colors: [[this.darkMode ? '#42FFC773' : '#33FFC773', 0.02], ['#00FFC773', 1]] })
          .blendMode(this.darkMode ? BlendMode.PLUS : BlendMode.NONE)
          .opacity(this.lampOpacity)
      }
    }.width('100%').height('100%').backgroundColor(D.paper).hitTestBehavior(HitTestMode.None)
  }
}
