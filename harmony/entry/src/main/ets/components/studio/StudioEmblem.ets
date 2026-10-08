import { curves } from '@kit.ArkUI';
import { ACCENT, ACCENT_LIGHT, INK3, LINE, LINE2, SURFACE, HOME_CARD2, RAISED, SHADOW_KEY, accentSoftBg } from '../../design/tokens.ets';
import { FONT_MONO, FONT_SERIF } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';

// A finite, local paper / constellation scene. Tap unfolds it; hold reveals the secret.
@Component
export struct StudioEmblem {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop kind: string = 'novel';
  @Prop compact: boolean = false;
  @Prop @Watch('activeChanged') active: boolean = true;
  onSecretChange: (visible: boolean) => void = (_visible: boolean): void => {};
  @State private revealed: boolean = false;
  @State private spread: number = 0;
  @State private sparkling: boolean = false;
  private alive: boolean = false;
  private playing: boolean = false;
  private enterTimer: number = -1;
  private returnTimer: number = -1;
  private finishTimer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    this.enterTimer = setTimeout((): void => {
      this.enterTimer = -1;
      if (!this.alive || !this.active) return;
      this.getUIContext().animateTo({ duration: 420, curve: curves.springCurve(0, 1, 180, 22) }, (): void => {
        this.revealed = true;
      });
    }, 32);
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.clearTimers();
  }

  private clearTimers(): void {
    if (this.enterTimer >= 0) clearTimeout(this.enterTimer);
    if (this.returnTimer >= 0) clearTimeout(this.returnTimer);
    if (this.finishTimer >= 0) clearTimeout(this.finishTimer);
    this.enterTimer = -1;
    this.returnTimer = -1;
    this.finishTimer = -1;
  }

  private activeChanged(): void {
    if (this.active) {
      this.revealed = true;
      return;
    }
    this.clearTimers();
    this.playing = false;
    this.spread = 0;
    this.sparkling = false;
    this.revealed = true;
    this.onSecretChange(false);
  }

  private unfold(secret: boolean): void {
    if (!this.active || !this.alive || this.playing) return;
    this.playing = true;
    this.sparkling = secret;
    this.onSecretChange(secret);
    this.getUIContext().animateTo({ duration: secret ? 520 : 320,
      curve: curves.springCurve(0, 1, 150, 18) }, (): void => {
      this.spread = secret ? 1 : 0.45;
    });
    this.returnTimer = setTimeout((): void => {
      this.returnTimer = -1;
      if (!this.alive || !this.active) return;
      this.getUIContext().animateTo({ duration: 420, curve: curves.springCurve(0, 1, 170, 22) }, (): void => {
        this.spread = 0;
        this.sparkling = false;
      });
      this.finishTimer = setTimeout((): void => {
        this.finishTimer = -1;
        if (!this.alive || !this.active) return;
        this.playing = false;
        this.onSecretChange(false);
      }, 420);
    }, secret ? 1400 : 500);
  }

  @Builder
  PaperLeaf(index: number): void {
    Column({ space: 6 }) {
      Text(index === 2 ? '序' : `0${index + 1}`).fontSize(16).fontFamily(FONT_SERIF)
        .fontColor(ACCENT).width('100%')
      ForEach([0, 1, 2, 3], (line: number): void => {
        Row().height(1).width(line === 3 ? '58%' : '100%').backgroundColor(LINE2)
      }, (line: number): string => `${line}`)
      Blank()
      Text('AMBER').fontSize(6).fontFamily(FONT_MONO).letterSpacing(1.5).fontColor(INK3)
    }
    .width(68).height(91).padding(12).alignItems(HorizontalAlign.Start)
    .backgroundColor(index === 2 ? HOME_CARD2 : index === 1 ? RAISED : SURFACE)
    .borderRadius(8).border({ width: 0.5, color: LINE2 })
    .shadow({ radius: 6, color: SHADOW_KEY, offsetY: 3 })
    .rotate({ angle: (index - 1) * (12 + this.spread * 25) })
    .translate({ x: (index - 1) * (10 + this.spread * 16), y: (2 - index) * 3 - this.spread * (index + 1) * 3 })
    .hitTestBehavior(HitTestMode.None)
  }

  @Builder
  Observatory(): void {
    Stack({ alignContent: Alignment.Center }) {
      Row().width(100).height(100).borderRadius(50).border({ width: 0.5, color: LINE })
      Row().width(74).height(74).borderRadius(37).border({ width: 1, color: accentSoftBg(ACCENT) })
      Row().width(44).height(44).borderRadius(22).backgroundColor(ACCENT_LIGHT)
      Icon({ name: 'book', iconSize: 24, color: ACCENT })
        .rotate({ angle: this.spread * -12 })
      ForEach([0, 1, 2, 3, 4, 5], (index: number): void => {
        Circle({ width: index % 2 === 0 ? 6 : 4, height: index % 2 === 0 ? 6 : 4 })
          .fill(index % 2 === 0 ? ACCENT : INK3)
          .translate({ x: Math.cos(index * Math.PI / 3 + this.spread * 0.8) * (36 + (index % 2) * 12 + this.spread * 9),
            y: Math.sin(index * Math.PI / 3 + this.spread * 0.8) * (36 + (index % 2) * 12 + this.spread * 9) })
      }, (index: number): string => `${index}`)
    }.width(128).height(128).hitTestBehavior(HitTestMode.None)
  }

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      Stack({ alignContent: Alignment.Center }) {
      Circle({ width: 112, height: 112 }).fill(accentSoftBg(ACCENT))
        .scale({ x: 0.9 + this.spread * 0.12, y: 0.9 + this.spread * 0.12 })
      if (this.kind === 'novel') {
        ForEach([0, 1, 2], (index: number): void => { this.PaperLeaf(index) }, (index: number): string => `${index}`)
      } else {
        this.Observatory()
      }
      ForEach([0, 1, 2, 3], (index: number): void => {
        Text('+').fontSize(13).fontFamily(FONT_MONO).fontColor(ACCENT)
          .translate({ x: Math.cos(index * Math.PI / 2 + 0.6) * (40 + this.spread * 21),
            y: Math.sin(index * Math.PI / 2 + 0.6) * (40 + this.spread * 21) })
          .rotate({ angle: this.spread * 90 }).opacity(this.sparkling ? 0.9 : 0)
          .hitTestBehavior(HitTestMode.None)
      }, (index: number): string => `spark-${index}`)
      }
    .width(144).height(144)
    .scale({ x: (this.compact ? 0.65 : 1) * (this.revealed ? 1 : 0.88),
      y: (this.compact ? 0.65 : 1) * (this.revealed ? 1 : 0.88) })
    .opacity(this.revealed ? 1 : 0)
    .hitTestBehavior(HitTestMode.None)
    }
    .width(this.compact ? 94 : 144).height(this.compact ? 100 : 144)
    .id('studio-emblem-' + this.kind)
    .onClick((): void => { this.unfold(false); })
    .gesture(LongPressGesture().onAction((): void => { this.unfold(true); }))
    .accessibilityText(this.kind === 'novel' ? '翻动纸页，长按点亮灵感' : '阅读星轨，长按寻找星光')
  }
}
