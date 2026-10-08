import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';
import { curves } from '@kit.ArkUI';
import { DeepReadHaptics as H } from '../../design/DeepReadHaptics.ets';
import { DeepReadDiscoverySymbol } from './DeepReadDiscoverySymbol.ets';

@Component
struct DiscoveryHeaderAction {
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settlePress') appBackgrounded: boolean = false;
  @Prop icon: string = '';
  @Prop label: string = '';
  onTap: () => void = (): void => {};
  @State private pressed: boolean = false;

  aboutToDisappear(): void { this.pressed = false; }
  private settlePress(): void { if (this.appBackgrounded) this.pressed = false; }

  build(): void {
    Row() { Icon({ name: this.icon, iconSize: 19, color: D.accent }) }
      .width(40).height(40).borderRadius(20).backgroundColor(this.pressed ? D.paper : D.card)
      .justifyContent(FlexAlign.Center).accessibilityText(this.label)
      .scale({ x: this.pressed && !this.reduceMotion ? 0.94 : 1, y: this.pressed && !this.reduceMotion ? 0.94 : 1 })
      .onTouch((event: TouchEvent): void => {
        if (this.appBackgrounded || (event.type !== TouchType.Down && event.type !== TouchType.Up && event.type !== TouchType.Cancel)) return;
        this.getUIContext().animateTo({ duration: this.reduceMotion ? 0 : 260,
          curve: curves.springCurve(0, 1, 240, 24) }, (): void => { this.pressed = event.type === TouchType.Down; });
      }).onClick((): void => { this.onTap(); })
  }
}

@Component
export struct DeepReadDiscoveryHeader {
  @Prop statusBarHeight: number = 44;
  onSources: () => void = (): void => {};
  onCreate: () => void = (): void => {};

  build(): void {
    Stack({ alignContent: Alignment.Center }) {
      Text('深度阅读').fontSize(16).fontWeight(600).fontFamily(D.sans).fontColor(D.ink)
      Row({ space: 8 }) {
        Blank()
        DiscoveryHeaderAction({ icon: 'menu', label: '热点来源设置', onTap: (): void => { this.onSources(); } })
        DiscoveryHeaderAction({ icon: 'edit', label: '创建阅读', onTap: (): void => { this.onCreate(); } })
      }.width('100%')
    }.width('100%').padding({ left: D.gutter, right: D.gutter, top: this.statusBarHeight + 8, bottom: 10 })
      .backgroundColor(D.paper)
  }
}

@Component
export struct DeepReadDiscoveryMasthead {
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('reduceMotion') @Watch('settleMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('settleMotion') appBackgrounded: boolean = false;
  @Prop issue: number = 1;
  @Prop playEntrance: boolean = true;
  @Prop @Watch('settleMotion') active: boolean = true;
  @State private dateLine: string = '';
  @State private festival: string = '';
  @State private night: boolean = false;
  @State private seal: string = '';
  @State private impression: number = 1;
  @State private stampAngle: number = -10;
  @State private stampInk: number = 0;
  @State private ruleScale: number = 1;
  private alive: boolean = false;
  private stampCycle: number = 0;
  private ruleTimer: number = -1;
  private stampTimer: number = -1;
  private taps: number[] = [];
  private readonly inscriptions: string[] = ['深读', '慢读', '求真', '存疑', '博观', '约取'];
  private sealIndex: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    if (this.playEntrance && this.active && !this.reduceMotion && !this.appBackgrounded) {
      this.ruleScale = 0;
      this.ruleTimer = setTimeout((): void => {
        this.ruleTimer = -1;
        if (!this.alive || !this.active || this.reduceMotion || this.appBackgrounded) return;
        this.getUIContext().animateTo({ duration: 700, curve: curves.springCurve(0, 1, 140, 24) }, (): void => {
          this.ruleScale = 1;
        });
      }, 16);
    }
    const now: Date = new Date();
    const weekdays: string[] = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    this.dateLine = `${now.getMonth() + 1}月${now.getDate()}日 ${weekdays[now.getDay()]}`;
    this.night = now.getHours() >= 22 || now.getHours() < 5;
    const month: number = now.getMonth() + 1;
    const day: number = now.getDate();
    this.festival = this.lunarFestival(now);
    if (this.festival.length > 0) return;
    if (month === 1 && day === 1) this.festival = '元旦 · 新年第一读';
    else if (month === 4 && day === 23) this.festival = '世界读书日';
    else if (month === 10 && day <= 7) this.festival = '国庆 · 慢慢读';
    else if (month === 12 && (day === 24 || day === 25)) this.festival = '圣诞 · 围炉夜读';
  }

  aboutToDisappear(): void { this.alive = false; this.settleMotion(); }

  private settleMotion(): void {
    if (this.alive && this.active && !this.appBackgrounded && !this.reduceMotion) return;
    this.stampCycle++;
    if (this.ruleTimer >= 0) clearTimeout(this.ruleTimer);
    if (this.stampTimer >= 0) clearTimeout(this.stampTimer);
    this.ruleTimer = -1; this.stampTimer = -1;
    this.impression = 1; this.stampAngle = -10; this.stampInk = this.seal.length > 0 ? 0.88 : 0;
    this.ruleScale = 1;
    this.taps = [];
  }

  private lunarFestival(now: Date): string {
    // Use the same Intl date-part API already used by the domain time tools.
    // A leap month is reported as e.g. "6bis" and must not receive a holiday.
    const formatter: Intl.DateTimeFormat = new Intl.DateTimeFormat('en-u-ca-chinese', { month: 'numeric', day: 'numeric' });
    if (formatter.resolvedOptions().calendar !== 'chinese') return '';
    const parts: Intl.DateTimeFormatPart[] = formatter.formatToParts(now);
    let month: string = '';
    let day: number = 0;
    parts.forEach((part: Intl.DateTimeFormatPart): void => {
      if (part.type === 'month') month = part.value;
      else if (part.type === 'day') day = Number.parseInt(part.value, 10);
    });
    if (month === '1' && day >= 1 && day <= 7) return '新春 · 开卷有益';
    if (month === '1' && day === 15) return '元宵 · 灯下读';
    if (month === '8' && day === 15) return '中秋 · 月下读';
    return '';
  }

  private tapHeadline(): void {
    if (!this.alive || !this.active || this.appBackgrounded) return;
    if (this.seal.length > 0) {
      this.sealIndex = (this.sealIndex + 1) % this.inscriptions.length;
    } else {
      const now: number = Date.now();
      this.taps = this.taps.filter((time: number): boolean => now - time < 1600).concat([now]);
      if (this.taps.length < 5) return;
      this.taps = [];
    }
    this.seal = this.inscriptions[this.sealIndex];
    H.impact();
    const cycle: number = ++this.stampCycle;
    const context = this.getUIContext();
    this.impression = this.reduceMotion ? 1 : 2.6;
    this.stampAngle = this.reduceMotion ? -10 : 8;
    this.stampInk = 0;
    if (this.stampTimer >= 0) clearTimeout(this.stampTimer);
    // A first stamp inserts a new node: give its initial keyframe one render turn.
    this.stampTimer = setTimeout((): void => {
      this.stampTimer = -1;
      if (!this.alive || !this.active || this.appBackgrounded || cycle !== this.stampCycle) return;
      context.animateTo({ duration: this.reduceMotion ? 250 : 140, curve: Curve.EaseOut }, (): void => {
        this.stampInk = 0.88;
      });
      if (this.reduceMotion) return;
      context.animateTo({ duration: 180, curve: Curve.EaseOut,
        onFinish: (): void => {
          if (!this.alive || !this.active || this.appBackgrounded || cycle !== this.stampCycle || this.reduceMotion) return;
          context.animateTo({ duration: 350, curve: curves.springCurve(0, 1, 180, 18) }, (): void => { this.impression = 1; });
        } }, (): void => {
        this.impression = 0.92; this.stampAngle = -10;
      });
    }, 16);
  }

  @Builder
  BadgeGlyph(label: string, color: string): void {
    Text(label === '夜读' ? '☾' : '⚑').fontSize(12).fontColor(color)
  }

  @Builder
  Badge(label: string, color: string): void {
    Row({ space: 4 }) {
      DeepReadDiscoverySymbol({ variant: 'bounce', active: this.active, playEntrance: this.playEntrance,
        content: (): void => { this.BadgeGlyph(label, color); } })
      Text(label).fontSize(11).fontFamily(D.sans).fontColor(D.ink)
    }.padding({ left: 8, right: 8, top: 3, bottom: 3 }).borderRadius(12)
      .backgroundColor('#1F' + color.substring(1))
  }

  build(): void {
    Column({ space: 10 }) {
      Row().width('100%').height(2.5).backgroundColor(D.ink).scale({ x: this.ruleScale, y: 1, centerX: 0 })
      Flex({ wrap: FlexWrap.Wrap, justifyContent: FlexAlign.SpaceBetween, alignItems: ItemAlign.Center }) {
        Row({ space: 8 }) {
          Text(this.issue > 0 ? 'No.' + this.issue.toString().padStart(3, '0') : 'No.---')
            .fontSize(12).fontFamily(D.sans).fontColor(D.muted)
          Text(this.dateLine).fontSize(12).fontFamily(D.sans).fontColor(D.muted)
        }
        if (this.night || this.festival.length > 0) {
          Row({ space: 6 }) {
            if (this.night) this.Badge('夜读', D.warn)
            if (this.festival.length > 0) this.Badge(this.festival, D.accent)
          }
        }
      }.width('100%')
      Stack({ alignContent: Alignment.End }) {
        Text('把热点读深一点').fontSize(28).fontWeight(600).fontFamily(D.serif)
          .fontColor(D.ink).width('100%').lineHeight(39).onClick((): void => { this.tapHeadline(); })
        if (this.seal.length > 0) {
          Stack() {
            Row().width('100%').height('100%').border({ width: 3, color: D.accent }).borderRadius(6)
            Row().width('80%').height('80%').border({ width: 0.9, color: D.accent }).borderRadius(4)
            Text(this.seal.substring(0, 1) + '\n' + this.seal.substring(1)).fontSize(15).fontWeight(FontWeight.Bold)
              .fontFamily(D.serif).fontColor(D.accent).textAlign(TextAlign.Center)
          }.width(46).height(46).opacity(this.stampInk).rotate({ angle: this.stampAngle })
            .blendMode(this.darkMode ? BlendMode.NONE : BlendMode.MULTIPLY)
            .scale({ x: this.impression, y: this.impression }).margin({ right: 6 }).hitTestBehavior(HitTestMode.None)
            .accessibilityText('印章：' + this.seal)
        }
      }.width('100%').accessibilityText('把热点读深一点，连续点按五次盖章')
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 4, bottom: 2 })
  }
}
