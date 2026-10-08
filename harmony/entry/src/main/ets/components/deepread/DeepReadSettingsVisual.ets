import { router, curves } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { Icon } from '../ui/Icon.ets';

// Native iOS form geometry shared by the standalone reading settings and composer.
@Component
export struct DeepReadSettingsHeader {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @Prop title: string = '';
  @Prop root: boolean = false;
  @Prop cancel: boolean = false;
  @Prop action: string = '';
  @Prop topInset: number = -1;
  onAction: () => void = (): void => {};
  onBack: () => void = (): void => { router.back(); };

  build(): void {
    Column() {
      Row() {
        if (!this.root) {
          Row({ space: 3 }) {
            if (!this.cancel) { Icon({ name: 'back', iconSize: 19, color: D.accent }) }
            Text(this.cancel ? '取消' : '返回').fontSize(17).fontColor(D.accent)
          }.height(44).constraintSize({ minWidth: 60 }).onClick((): void => { this.onBack(); })
        } else { Blank() }
        if (!this.root) {
          Text(this.title).fontSize(17).fontWeight(FontWeight.Medium).fontColor(D.ink)
            .layoutWeight(1).textAlign(TextAlign.Center).maxLines(1)
        }
        if (this.action.length > 0) {
          Row() { Icon({ name: 'edit', iconSize: 22, color: D.accent }) }
            .width(this.root ? 44 : 60).height(44).justifyContent(FlexAlign.Center)
            .accessibilityText(this.action).onClick((): void => { this.onAction(); })
        } else if (!this.root) { Blank().width(60) }
      }.width('100%').height(44).padding({ left: D.gutter, right: D.gutter })
      if (this.root) {
        Text(this.title).fontSize(34).fontWeight(FontWeight.Bold).fontColor(D.ink)
          .width('100%').padding({ left: D.gutter, right: D.gutter, bottom: 10 })
      }
    }.width('100%').padding({ top: this.topInset >= 0 ? this.topInset : this.statusBarHeight })
  }
}

@Component
export struct DeepReadSettingsSection {
  @Prop title: string = '';
  build(): void {
    Text(this.title).fontSize(13).fontColor(D.muted).width('100%')
      .padding({ left: 16, right: 16, top: 24, bottom: 8 })
  }
}

@Component
export struct DeepReadSettingsNote {
  @Prop text: string = '';
  build(): void {
    Text(this.text).fontSize(13).fontColor(D.muted).lineHeight(19).width('100%')
      .padding({ left: 16, right: 16, top: 7 })
  }
}

@Component
export struct DeepReadSettingsGroup {
  @BuilderParam content: () => void;
  build(): void {
    Column() { this.content(); }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true)
  }
}

@Component
export struct DeepReadSettingsRow {
  @Prop label: string = '';
  @Prop detail: string = '';
  @Prop value: string = '';
  @Prop last: boolean = false;
  @Prop action: boolean = false;
  @Prop destructive: boolean = false;
  @Prop valueAccent: boolean = false;
  @Prop picker: boolean = false;
  @Prop chevron: boolean = true;
  onTap: () => void = (): void => {};
  build(): void {
    Column() {
      Row({ space: 10 }) {
        if (this.action) { Icon({ name: 'plus', iconSize: 22, color: D.accent }) }
        Column({ space: 3 }) {
          Text(this.label).fontSize(17).fontColor(this.destructive ? D.danger : this.action ? D.accent : D.ink).width('100%')
          if (this.detail.length > 0) { Text(this.detail).fontSize(12).fontColor(D.muted).lineHeight(17).width('100%').maxLines(2) }
        }.layoutWeight(1).alignItems(HorizontalAlign.Start)
        if (this.value.length > 0) {
          Text(this.value).fontSize(15).fontColor(this.valueAccent ? D.accent : D.muted).maxLines(2)
            .textAlign(TextAlign.End).constraintSize({ maxWidth: '48%' })
        }
        if (this.chevron) { Icon({ name: this.picker ? 'chevron_down' : 'chevron_right', iconSize: 13, color: this.valueAccent ? D.accent : D.muted }) }
      }.width('100%').constraintSize({ minHeight: 48 }).padding({ left: 16, right: 16, top: 10, bottom: 10 })
        .onClick((): void => { this.onTap(); })
      if (!this.last) { Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 }) }
    }.width('100%')
  }
}

@Component
export struct DeepReadSettingsToggle {
  @Prop label: string = '';
  @Prop checked: boolean = false;
  @Prop last: boolean = false;
  onChange: (checked: boolean) => void = (_checked: boolean): void => {};
  build(): void {
    Column() {
      Row({ space: 12 }) {
        Text(this.label).fontSize(17).fontColor(D.ink).layoutWeight(1).maxLines(2)
        Toggle({ type: ToggleType.Switch, isOn: this.checked }).selectedColor(D.accent)
          .onChange((checked: boolean): void => { this.onChange(checked); })
      }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 16, right: 16, top: 7, bottom: 7 })
      if (!this.last) { Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 }) }
    }.width('100%')
  }
}

@Component
export struct DeepReadSettingsPicker {
  @Prop label: string = '';
  @Prop options: string[] = [];
  @Prop selected: number = 0;
  @Prop last: boolean = false;
  onSelect: (index: number) => void = (_index: number): void => {};

  @Builder
  Choices(): void {
    Menu() {
      ForEach(this.options, (option: string, index: number): void => {
        MenuItem({ content: option }).selected(this.selected === index).selectIcon(true).onClick((): void => { this.onSelect(index); })
      }, (option: string, index: number): string => `${index}:${option}`)
    }
  }

  build(): void {
    Column() {
      Row({ space: 12 }) {
        Text(this.label).fontSize(17).fontColor(D.ink).layoutWeight(1).maxLines(2)
        Text(this.options[this.selected] ?? '请选择').fontSize(16).fontColor(D.accent)
          .maxLines(2).textAlign(TextAlign.End).constraintSize({ maxWidth: '48%' })
        Icon({ name: 'chevron_down', iconSize: 13, color: D.accent })
      }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 16, right: 16, top: 9, bottom: 9 })
        .bindMenu(this.Choices())
      if (!this.last) { Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 }) }
    }.width('100%')
  }
}

@Component
export struct DeepReadSettingsButton {
  @StorageProp('reduceMotion') @Watch('stopBounce') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('stopBounce') appBackgrounded: boolean = false;
  @Prop label: string = '';
  @Prop icon: string = '';
  @Prop @Watch('savedChanged') success: boolean = false;
  @Prop disabled: boolean = false;
  @State iconScale: number = 1;
  private alive: boolean = false;
  private bounceTimer: number = -1;
  onTap: () => void = (): void => {};

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; this.stopBounce(); }

  private stopBounce(): void {
    if (this.bounceTimer >= 0) clearTimeout(this.bounceTimer);
    this.bounceTimer = -1;
    this.iconScale = 1;
  }

  private savedChanged(): void {
    this.stopBounce();
    if (!this.alive || !this.success || this.reduceMotion || this.appBackgrounded) return;
    this.getUIContext().animateTo({ curve: curves.springMotion(0.28, 0.6) }, (): void => { this.iconScale = 1.25; });
    this.bounceTimer = setTimeout((): void => {
      this.bounceTimer = -1;
      if (!this.alive || this.reduceMotion || this.appBackgrounded) return;
      this.getUIContext().animateTo({ curve: curves.springMotion(0.35, 0.65) }, (): void => { this.iconScale = 1; });
    }, 150);
  }

  build(): void {
    Button() {
      Row({ space: 8 }) {
        if (this.icon.length > 0) {
          Icon({ name: this.icon, iconSize: 19, color: D.paper }).scale({ x: this.iconScale, y: this.iconScale })
        }
        Text(this.label).fontSize(17).fontWeight(FontWeight.Medium).fontColor(D.paper)
      }.justifyContent(FlexAlign.Center)
    }.backgroundColor(D.accent).borderRadius(28).height(52).width('100%').enabled(!this.disabled)
      .opacity(this.disabled ? 0.5 : 1).onClick((): void => { this.onTap(); })
  }
}
