import type { SmartUiNode, SmartUiOption, SmartUiScalar, SmartUiStateValue } from '@amber/chat-domain';
import { encodeSmartUiSelection } from '@amber/chat-domain';
import { ACCENT, INK, INK3, LINE, SURFACE, SURFACE2, ERROR, SUCCESS_FG, WARNING_FG,
  STATUS_BLUE, STATUS_AMBER, INFO_FG, ACCENT_OPTIONS, accentInkFor, accentSoftBg, midpointHex } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { smartUiValue, smartUiString, smartUiSelection, smartUiOptions, smartUiNumericValue,
  smartUiSelectedDate, smartUiDateString } from './NativeSmartUiValues.ets';

const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };

@Component
export struct NativeSmartUiInputs {
  @Prop node: SmartUiNode = EMPTY_NODE;
  @Prop values: SmartUiStateValue[] = [];
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @Prop ink: string = INK;
  @Prop secondaryInk: string = INK3;
  @Prop accent: string = ACCENT;
  @Prop surface: string = SURFACE2;
  @State private pickerExpanded: boolean = false;
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_id: string, _value: SmartUiScalar): void => {};

  private kind(): string { return this.node.component?.kind ?? ''; }
  private textSize(base: number): number { return Math.max(10, base * this.fontSize / 16); }
  private raw(): string { return smartUiString(this.values, this.node.state); }
  private value(): SmartUiScalar | undefined { return smartUiValue(this.values, this.node.state); }
  private options(): SmartUiOption[] { return smartUiOptions(this.node); }
  private indices(): number[] { return this.options().map((_option: SmartUiOption, index: number): number => index); }
  private option(index: number): SmartUiOption { return this.options()[index] ?? { label: '', value: '' }; }
  private multiple(): boolean { return this.kind() === 'multiChoice' || this.kind() === 'checklist'; }
  private selected(index: number): boolean {
    const value: SmartUiScalar = this.option(index).value;
    return this.multiple() ? smartUiSelection(this.values, this.node.state).includes(String(value)) : this.value() === value;
  }
  private change(value: SmartUiScalar): void {
    const stateId: string | undefined = this.node.state;
    if (stateId === undefined) return;
    if (this.value() !== value) {
      // V1 @Prop is a local copy: update the native binding immediately, before the owner echoes through the tree.
      const next: SmartUiStateValue[] = this.values.slice();
      const index: number = next.findIndex((item: SmartUiStateValue): boolean => item.id === stateId);
      if (index >= 0) next[index] = { id: stateId, value };
      else next.push({ id: stateId, value });
      this.values = next;
    }
    this.onStateChange(stateId, value);
  }
  private select(index: number): void {
    const value: SmartUiScalar = this.option(index).value;
    if (this.multiple()) {
      const selected: string[] = smartUiSelection(this.values, this.node.state);
      const key: string = String(value);
      this.change(encodeSmartUiSelection(selected.includes(key) ? selected.filter((item: string): boolean => item !== key) : selected.concat([key])));
    } else {
      this.change(value);
      this.pickerExpanded = false;
    }
  }
  private compactChoices(): boolean {
    return ['segmented', 'singleChoice', 'multiChoice'].includes(this.kind()) &&
      this.fontSize <= 18 && this.options().length >= 2 && this.options().length <= 4 &&
      this.options().every((option: SmartUiOption): boolean =>
        option.label.length <= 8 && (option.detail ?? '').length === 0);
  }
  private choiceLabel(): string {
    for (const option of this.options()) if (option.value === this.value()) return option.label;
    return '请选择';
  }
  private color(index: number): string {
    const name: string = this.option(index).color ?? 'accent';
    if (name === 'red') return ERROR;
    if (name === 'pink') return ACCENT_OPTIONS[4];
    if (name === 'orange') return WARNING_FG;
    if (name === 'yellow') return STATUS_AMBER;
    if (name === 'green') return SUCCESS_FG;
    if (name === 'cyan') return midpointHex(STATUS_BLUE, SUCCESS_FG);
    if (name === 'blue') return STATUS_BLUE;
    if (name === 'indigo') return INFO_FG;
    if (name === 'purple') return ACCENT_OPTIONS[3];
    if (name === 'gray') return INK3;
    return this.accent;
  }
  private minimum(): number { return this.node.component?.min ?? 0; }
  private maximum(): number { return this.node.component?.max ?? (this.kind() === 'rating' ? 5 : 100); }
  private step(): number { return this.node.component?.step ?? 1; }
  private lastStepIndex(): number { return Math.floor((this.maximum() - this.minimum()) / this.step() + 1e-7); }
  private stepValue(index: number): number {
    const bounded: number = Math.max(0, Math.min(this.lastStepIndex(), index));
    return Number((this.minimum() + bounded * this.step()).toPrecision(15));
  }
  private stepMaximum(): number { return this.stepValue(this.lastStepIndex()); }
  private numeric(): number {
    const value: number = smartUiNumericValue(this.node, this.values);
    return this.stepValue(Math.round((value - this.minimum()) / this.step()));
  }
  private increment(direction: number): void {
    const index: number = Math.round((this.numeric() - this.minimum()) / this.step());
    this.change(this.stepValue(index + direction));
  }
  private changeSlider(value: number): void {
    this.change(this.stepValue(Math.round((value - this.minimum()) / this.step())));
  }
  private changeNumber(raw: string): void {
    // Preserve invalid edits in the shared snapshot so continuation cannot submit a display default.
    if (raw.trim().length === 0) this.change('');
    else {
      const numeric: number = Number(raw);
      this.change(Number.isFinite(numeric) ? numeric : raw);
    }
  }
  private date(): Date { return smartUiSelectedDate(this.raw(), this.kind() === 'timePicker'); }
  private ratingValues(): number[] {
    const result: number[] = [];
    for (let value = 1; value <= this.maximum(); value++) result.push(value);
    return result;
  }

  @Builder
  private Choice(index: number): void {
    Row({ space: 10 }) {
      if (this.kind() === 'colorChoice') {
        Text(this.selected(index) ? '✓' : '').width(26).height(26).fontSize(this.textSize(16))
          .fontColor(accentInkFor(this.color(index))).textAlign(TextAlign.Center).borderRadius(13).backgroundColor(this.color(index));
      } else if (!this.compactChoices()) {
        Text(this.selected(index) ? '✓' : this.multiple() ? '□' : '○')
          .fontSize(this.textSize(18)).fontColor(this.accent).width(24).flexShrink(0);
      }
      Column({ space: 4 }) {
        Text((this.compactChoices() && this.multiple() && this.selected(index) ? '✓ ' : '') +
          this.option(index).label).width('100%').fontSize(this.textSize(15)).fontFamily(this.fontFamily)
          .lineHeight(this.textSize(21)).textAlign(this.compactChoices() ? TextAlign.Center : TextAlign.Start)
          .fontColor(this.selected(index) ? this.accent : this.ink);
        if ((this.option(index).detail ?? '').length > 0) {
          Text(this.option(index).detail ?? '').width('100%').fontSize(this.textSize(12))
            .fontFamily(this.fontFamily).lineHeight(this.textSize(18)).fontColor(this.secondaryInk);
        }
      }.layoutWeight(1).alignItems(HorizontalAlign.Start);
    }
    .width(this.compactChoices() ? `calc(100% / ${this.options().length} - 6vp)` : '100%')
    .constraintSize({ minHeight: 44 }).padding(10).borderRadius(10)
    .border({ width: this.selected(index) ? 1.5 : 1, color: this.selected(index) ? this.accent : LINE })
    .backgroundColor(this.selected(index) ? accentSoftBg(this.accent) : this.surface)
    .margin({ bottom: 6, right: this.compactChoices() ? 6 : 0 })
    .onClick((): void => { this.select(index); })
    .accessibilityGroup(true).accessibilityText(`${this.option(index).label}，${this.selected(index) ? '已选择' : '未选择'}`);
  }

  @Builder
  private Choices(): void {
    Flex({ wrap: FlexWrap.Wrap }) {
      ForEach(this.indices(), (index: number): void => { this.Choice(index); }, (index: number): string => `${this.node.id}:${index}`);
    }.width('100%');
  }

  @Builder
  private ToggleInput(): void {
    if (typeof this.value() !== 'boolean') {
      Row({ space: 8 }) {
        Button('是').layoutWeight(1).constraintSize({ minHeight: 44 }).fontColor(this.ink)
          .backgroundColor(this.surface).onClick((): void => { this.change(true); });
        Button('否').layoutWeight(1).constraintSize({ minHeight: 44 }).fontColor(this.ink)
          .backgroundColor(this.surface).onClick((): void => { this.change(false); });
      }.width('100%');
    } else {
      Row({ space: 12 }) {
        Text(this.value() === true ? '是' : '否').layoutWeight(1).fontSize(this.textSize(15)).fontColor(this.ink);
        Toggle({ type: ToggleType.Switch, isOn: this.value() === true }).selectedColor(this.accent)
          .onChange((value: boolean): void => { this.change(value); });
      }.width('100%').constraintSize({ minHeight: 44 });
    }
  }

  @Builder
  private TextInputControl(): void {
    if (this.kind() === 'textArea') {
      TextArea({ text: this.raw(), placeholder: this.node.component?.text ?? '输入内容' })
        .width('100%').height(112).fontSize(this.textSize(15)).fontFamily(this.fontFamily)
        .fontColor(this.ink).placeholderColor(this.secondaryInk).backgroundColor(this.surface).borderRadius(10).maxLength(512)
        .onChange((value: string): void => { this.change(value); });
    } else {
      TextInput({ text: this.raw(), placeholder: this.node.component?.text ?? (this.kind() === 'numberField' ? '输入数字' : '输入内容') })
        .width('100%').constraintSize({ minHeight: 44 }).fontSize(this.textSize(15)).fontFamily(this.fontFamily)
        .fontColor(this.ink).placeholderColor(this.secondaryInk).backgroundColor(this.surface).borderRadius(10).maxLength(512)
        .type(InputType.Normal)
        .onChange((value: string): void => { if (this.kind() === 'numberField') this.changeNumber(value); else this.change(value); });
    }
  }

  @Builder
  private NumberConfirmation(): void {
    if (this.value() === undefined || this.raw().length === 0) {
      Button(`使用当前值 ${this.numeric()}`).constraintSize({ minHeight: 44 }).fontSize(this.textSize(13))
        .fontColor(this.ink).backgroundColor(this.surface).onClick((): void => { this.change(this.numeric()); });
    }
  }

  @Builder
  private NumericInput(): void {
    Text(this.raw().length === 0 ? '尚未设置' : this.raw()).fontSize(this.textSize(15)).fontColor(this.ink);
    if (this.kind() === 'slider') {
      if (this.stepMaximum() > this.minimum()) {
        Slider({ value: this.numeric(), min: this.minimum(), max: this.stepMaximum(), step: this.step() })
          .width('100%').constraintSize({ minHeight: 44 }).selectedColor(this.accent).trackColor(LINE)
          .onChange((value: number): void => { this.changeSlider(value); });
      }
      Row() {
        Text(String(this.minimum())).fontSize(this.textSize(12)).fontColor(this.secondaryInk);
        Blank();
        Text(String(this.stepMaximum())).fontSize(this.textSize(12)).fontColor(this.secondaryInk);
      }.width('100%');
    } else {
      Row({ space: 12 }) {
        Button('−').constraintSize({ minWidth: 44, minHeight: 44 }).fontSize(this.textSize(20)).fontColor(this.ink)
          .backgroundColor(this.surface).enabled(this.numeric() > this.minimum())
          .onClick((): void => { this.increment(-1); }).accessibilityText('减少');
        Text(String(this.numeric())).layoutWeight(1).fontSize(this.textSize(18)).fontColor(this.ink).textAlign(TextAlign.Center);
        Button('+').constraintSize({ minWidth: 44, minHeight: 44 }).fontSize(this.textSize(20)).fontColor(this.ink)
          .backgroundColor(this.surface).enabled(this.numeric() < this.stepMaximum())
          .onClick((): void => { this.increment(1); }).accessibilityText('增加');
      }.width('100%');
    }
    this.NumberConfirmation();
  }

  @Builder
  private DateInput(): void {
    Text(this.raw().length === 0 ? '尚未设置' : this.raw()).fontSize(this.textSize(13)).fontColor(this.secondaryInk);
    if (this.kind() === 'datePicker') {
      DatePicker({ start: new Date(1900, 0, 31), end: new Date(2100, 11, 31), selected: this.date() })
        .width('100%').height(150).textStyle({ color: this.secondaryInk, font: { size: this.textSize(14) } })
        .selectedTextStyle({ color: this.accent, font: { size: this.textSize(17) } })
        .onDateChange((date: Date): void => { this.change(smartUiDateString(date, false)); });
    } else {
      TimePicker({ selected: this.date() }).useMilitaryTime(true).width('100%').height(150)
        .textStyle({ color: this.secondaryInk, font: { size: this.textSize(14) } })
        .selectedTextStyle({ color: this.accent, font: { size: this.textSize(17) } })
        .onChange((value: TimePickerResult): void => {
          this.change(`${String(value.hour).padStart(2, '0')}:${String(value.minute).padStart(2, '0')}`);
        });
    }
    if (this.raw().length === 0) {
      Button(`使用当前${this.kind() === 'timePicker' ? '时间' : '日期'}`)
        .constraintSize({ minHeight: 44 }).fontColor(this.ink).backgroundColor(this.surface)
        .onClick((): void => { this.change(smartUiDateString(this.date(), this.kind() === 'timePicker')); });
    }
  }

  build(): void {
    Column({ space: 8 }) {
      if (this.kind() === 'picker' || this.kind() === 'menu') {
        Row() {
          Text(this.choiceLabel()).layoutWeight(1).fontSize(this.textSize(15)).fontColor(this.ink);
          Text(this.pickerExpanded ? '⌃' : '⌄').fontColor(this.accent);
        }.width('100%').constraintSize({ minHeight: 44 }).padding(12).borderRadius(10).backgroundColor(this.surface)
          .onClick((): void => { this.pickerExpanded = !this.pickerExpanded; });
        if (this.pickerExpanded) this.Choices();
      } else if (['singleChoice', 'multiChoice', 'segmented', 'colorChoice', 'checklist'].includes(this.kind())) {
        this.Choices();
      } else if (this.kind() === 'toggle') {
        this.ToggleInput();
      } else if (['textField', 'textArea', 'numberField'].includes(this.kind())) {
        this.TextInputControl();
      } else if (this.kind() === 'slider' || this.kind() === 'stepper') {
        this.NumericInput();
      } else if (this.kind() === 'datePicker' || this.kind() === 'timePicker') {
        this.DateInput();
      } else if (this.kind() === 'rating') {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.ratingValues(), (value: number): void => {
            Text(value <= (Number(this.raw()) || 0) ? '★' : '☆').fontSize(this.textSize(26)).fontColor(this.accent)
              .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
              .onClick((): void => { this.change(value); }).accessibilityText(`${value} 分`);
          }, (value: number): string => `${this.node.id}:${value}`);
        }.width('100%');
      }
    }.width('100%').alignItems(HorizontalAlign.Start);
  }
}
