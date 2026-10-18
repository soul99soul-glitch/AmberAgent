import type {
  SmartUiAssetDiagnostic,
  SmartUiController,
  SmartUiDocument,
  SmartUiNode,
  SmartUiOption,
  SmartUiScalar,
  SmartUiStateAccessor,
  SmartUiStateValue,
  SmartUiWeatherProjection,
} from '@amber/chat-domain';
import { componentKindAllowsChildren, createSmartUiController, resolveWeatherBinding } from '@amber/chat-domain';
import { ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK3, INK4, LINE, SURFACE, SURFACE2, HOME_GLASS_SHADOW,
  SUCCESS_BG, SUCCESS_FG, WARNING_BG, WARNING_FG, INFO_BG, INFO_FG,
  MOTION_ENTER, MOTION_PRESS, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS, FONT_MONO, FONT_SERIF } from '../design/fonts.ets';
import { NativeSmartUiComponent } from './NativeSmartUiComponent.ets';
import type { NativeUiNodeAreaCallback, NativeUiNodeFrame } from './NativeUiGenerationOverlay.ets';

const EMPTY_ACCESSOR: SmartUiStateAccessor = {
  get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
  set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
};
const EMPTY_CONTROLLER: SmartUiController = createSmartUiController(EMPTY_ACCESSOR);
const EMPTY_DOCUMENT: SmartUiDocument = {
  id: 'empty', nodes: [], states: [], status: 'partial', diagnostics: [], ended: false, rootId: null, records: 0,
};
const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };
const EMPTY_ASSET_DIAGNOSTIC: SmartUiAssetDiagnostic = { ref: '', message: '', severity: 'warning' };

@Component
export struct NativeSmartUiNode {
  /** Keep only the stable identity in retained ForEach closures; read the current definition below. */
  @Prop nodeId: string = EMPTY_NODE.id;
  @Prop @Watch('onNodeVisibilityChanged') document: SmartUiDocument = EMPTY_DOCUMENT;
  @Prop @Watch('onNodeVisibilityChanged') values: SmartUiStateValue[] = [];
  @Prop invalidStateIds: string[] = [];
  @Prop validationDiagnostics: SmartUiAssetDiagnostic[] = [];
  @Prop projections: SmartUiWeatherProjection[] = [];
  @Prop @Watch('onNodeVisibilityChanged') controller: SmartUiController = EMPTY_CONTROLLER;
  @Prop ready: boolean = false;
  @Prop sourceKey: string = '';
  @Prop streaming: boolean = false;
  @Prop depth: number = 1;
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @State private pressedKey: string = '';
  @State private inputFocused: boolean = false;
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_stateId: string, _value: SmartUiScalar): void => {};
  onDraft: (nodeId: string) => void = (_nodeId: string): void => {};
  onAssetView: (ref: string) => void = (_ref: string): void => {};
  onContentGrow: () => void = (): void => {};
  onReset: () => void = (): void => {};
  onNodeArea: NativeUiNodeAreaCallback = (_id: string, _frame: NativeUiNodeFrame | null): void => {};
  onRevealTouch: () => void = (): void => {};

  aboutToAppear(): void {
    this.onNodeVisibilityChanged();
  }

  aboutToDisappear(): void {
    this.onNodeArea(this.nodeId, null);
  }

  private onNodeVisibilityChanged(): void {
    if (!this.nodeExists() || !this.visible()) this.onNodeArea(this.nodeId, null);
  }

  private reportArea(area: Area): void {
    const node: SmartUiNode = this.currentNode();
    if (node.type === 'component' && node.component !== undefined && componentKindAllowsChildren(node.component.kind)) {
      this.onNodeArea(this.nodeId, null);
      return;
    }
    const frame: NativeUiNodeFrame = {
      id: this.nodeId,
      type: node.type,
      x: Number(area.globalPosition.x),
      y: Number(area.globalPosition.y),
      width: Number(area.width),
      height: Number(area.height),
    };
    if (![frame.x, frame.y, frame.width, frame.height].every((value: number): boolean =>
      Number.isFinite(value)) || frame.width <= 0 || frame.height <= 0) {
      this.onNodeArea(this.nodeId, null);
      return;
    }
    this.onNodeArea(this.nodeId, frame);
  }

  private nodeExists(): boolean {
    const nodes: SmartUiNode[] = this.document.nodes;
    const id: string = this.nodeId;
    for (const node of nodes) {
      if (node.id === id) return true;
    }
    return false;
  }

  private currentNode(): SmartUiNode {
    const nodes: SmartUiNode[] = this.document.nodes;
    const id: string = this.nodeId;
    for (let index = 0; index < nodes.length; index++) {
      const node: SmartUiNode = nodes[index];
      if (node.id === id) return node;
    }
    return EMPTY_NODE;
  }

  private childNodeIds(): string[] {
    const result: string[] = [];
    const nodes: SmartUiNode[] = this.document.nodes;
    const parentId: string = this.nodeId;
    for (const node of nodes) {
      if (node.parent === parentId) result.push(node.id);
    }
    return result;
  }

  private textSize(base: number): number {
    const size: number = this.fontSize > 0 ? this.fontSize : 16;
    return Math.max(10, base * size / 16);
  }

  private family(): string {
    return this.fontFamily.length > 0 ? this.fontFamily : FONT_SANS;
  }

  private toneColor(): string {
    const tone: string = this.currentNode().tone ?? 'neutral';
    if (tone === 'accent') return ACCENT;
    if (tone === 'success') return SUCCESS_FG;
    if (tone === 'warning') return WARNING_FG;
    if (tone === 'info') return INFO_FG;
    const variant: string = this.currentNode().variant ?? '';
    if (variant === 'callout' || variant === 'section') return ACCENT;
    return variant === 'caption' || variant === 'eyebrow' ? INK3 : INK;
  }

  private toneBackground(): string {
    const tone: string = this.currentNode().tone ?? 'neutral';
    if (tone === 'accent') return accentSoftBg(ACCENT);
    if (tone === 'success') return SUCCESS_BG;
    if (tone === 'warning') return WARNING_BG;
    if (tone === 'info') return INFO_BG;
    return this.currentNode().variant === 'callout' ? accentSoftBg(ACCENT) : SURFACE2;
  }

  private grouped(): boolean {
    const node: SmartUiNode = this.currentNode();
    return (node.variant !== undefined && node.variant !== 'plain') ||
      (node.tone !== undefined && node.tone !== 'neutral');
  }

  private columnBackground(): string | Color {
    const variant: string = this.currentNode().variant ?? 'plain';
    if (variant === 'outlined' || variant === 'section') return Color.Transparent;
    if (variant === 'elevated' && (this.currentNode().tone ?? 'neutral') === 'neutral') return SURFACE;
    return this.grouped() ? this.toneBackground() : Color.Transparent;
  }

  private textBaseSize(): number {
    const variant: string = this.currentNode().variant ?? 'body';
    if (variant === 'hero') return 40;
    if (variant === 'lead') return 18;
    if (variant === 'eyebrow') return 11;
    if (variant === 'heading') return 22;
    if (variant === 'subheading') return 17;
    if (variant === 'metric') return 32;
    if (variant === 'caption' || variant === 'badge') return 12;
    if (variant === 'code') return 14;
    return 16;
  }

  private chips(): boolean { return this.currentNode().variant === 'chips'; }

  private primaryButton(): boolean {
    const variant: string = this.currentNode().variant ?? 'primary';
    return variant === 'primary' || variant === 'pill';
  }

  private choiceWidth(): string {
    const variant: string = this.currentNode().variant ?? 'list';
    if (variant === 'chips') return 'auto';
    if (variant === 'tiles') return 'calc(50% - 4vp)';
    if (variant === 'segmented') {
      const count: number = Math.min(3, Math.max(1, this.optionKeys().length));
      return `calc((100% - ${(count - 1) * 8}vp) / ${count})`;
    }
    return '100%';
  }

  private choiceMargin(optionKey: string): number {
    const variant: string = this.currentNode().variant ?? 'list';
    if (variant === 'chips') return 8;
    const index: number = this.optionKeys().indexOf(optionKey);
    if (variant === 'tiles') return index % 2 === 0 ? 8 : 0;
    if (variant === 'segmented') {
      const count: number = Math.min(3, this.optionKeys().length);
      return (index + 1) % count === 0 ? 0 : 8;
    }
    return 0;
  }

  private buttonBackground(): string | Color {
    if (this.currentNode().variant === 'ghost') return Color.Transparent;
    if (!this.draftEnabled()) return SURFACE2;
    return this.primaryButton() ? ACCENT : ACCENT_LIGHT;
  }

  private touch(key: string, event: TouchEvent): void {
    if (event.type !== TouchType.Down && event.type !== TouchType.Up && event.type !== TouchType.Cancel) return;
    if (event.type === TouchType.Down) this.onRevealTouch();
    this.getUIContext().animateTo({ duration: MOTION_PRESS, curve: Curve.EaseOut }, (): void => {
      this.pressedKey = event.type === TouchType.Down ? key : '';
    });
  }

  private childWidth(): string {
    if (this.currentNode().layout === 'grid') return 'calc(50% - 4vp)';
    const count: number = Math.min(3, Math.max(1, this.childNodeIds().length));
    return `calc((100% - ${(count - 1) * 8}vp) / ${count})`;
  }

  private valueFor(stateId: string): SmartUiScalar | undefined {
    for (const item of this.values) {
      if (item.id === stateId) return item.value;
    }
    return undefined;
  }

  private stringValue(stateId: string): string {
    const value: SmartUiScalar | undefined = this.valueFor(stateId);
    return typeof value === 'string' ? value : '';
  }

  private stateInvalid(stateId: string | undefined): boolean {
    return stateId !== undefined && this.invalidStateIds.includes(stateId);
  }

  private scalarKey(value: SmartUiScalar): string {
    if (typeof value === 'string') return `string:${value}`;
    if (typeof value === 'number') return `number:${String(value)}`;
    return `boolean:${value ? 'true' : 'false'}`;
  }

  private currentOption(optionKey: string): SmartUiOption | null {
    for (const option of this.currentNode().options ?? []) {
      if (this.scalarKey(option.value) === optionKey) return option;
    }
    return null;
  }

  private optionKeys(): string[] {
    const result: string[] = [];
    for (const option of this.currentNode().options ?? []) result.push(this.scalarKey(option.value));
    return result;
  }

  private optionLabel(optionKey: string): string {
    return this.currentOption(optionKey)?.label ?? '';
  }

  private isSelected(optionKey: string): boolean {
    const node: SmartUiNode = this.currentNode();
    const option: SmartUiOption | null = this.currentOption(optionKey);
    if (node.state === undefined || option === null) return false;
    const value: SmartUiScalar | undefined = this.valueFor(node.state);
    return value !== undefined && this.scalarKey(value) === optionKey;
  }

  private draftText(): string | null {
    return this.controller.draft(this.document, this.currentNode().action, this.values);
  }

  private draftEnabled(): boolean {
    return this.currentNode().action?.type === 'reset' || (this.ready && this.draftText() !== null);
  }

  private visible(): boolean {
    return this.controller.visibleWhen(this.currentNode().visibleWhen, this.values);
  }

  private select(optionKey: string): void {
    const node: SmartUiNode = this.currentNode();
    const option: SmartUiOption | null = this.currentOption(optionKey);
    if (node.state !== undefined && option !== null) this.onStateChange(node.state, option.value);
  }

  private changeInput(value: string): void {
    const stateId: string | undefined = this.currentNode().state;
    if (stateId !== undefined) this.onStateChange(stateId, value.slice(0, 512));
  }

  private clickDraft(): void {
    if (this.currentNode().action?.type === 'reset') {
      this.onReset();
      return;
    }
    // The page expands the action against its current fields; child props can trail the last keystroke.
    if (this.ready && this.currentNode().action !== undefined) this.onDraft(this.nodeId);
  }

  private projectionFor(ref: string): SmartUiWeatherProjection | null {
    let found: SmartUiWeatherProjection | null = null;
    for (const projection of this.projections) {
      if (projection.ref !== ref) continue;
      if (found !== null) return null;
      found = projection;
    }
    return found;
  }

  private projectionAmbiguous(ref: string): boolean {
    let matched: boolean = false;
    for (const projection of this.projections) {
      if (projection.ref !== ref) continue;
      if (matched) return true;
      matched = true;
    }
    return false;
  }

  private boundText(): string {
    const node: SmartUiNode = this.currentNode();
    const binding = node.bind;
    const label: string = node.text?.trim() ?? '';
    if (binding === undefined) return node.text ?? '';
    const projection: SmartUiWeatherProjection | null = this.projectionFor(binding.ref);
    if (projection === null) {
      const placeholder: string = this.projectionAmbiguous(binding.ref) ? '数据不可用' : '未提供';
      return label.length > 0 ? `${label}：${placeholder}` : placeholder;
    }
    if (projection.status === 'missing') {
      return label.length > 0 ? `${label}：未提供` : '未提供';
    }
    if (projection.status === 'failed') {
      return label.length > 0 ? `${label}：数据不可用` : '数据不可用';
    }
    const value: string | number | boolean | null = resolveWeatherBinding(projection, binding.ref, binding.path);
    if (value === null) return label.length > 0 ? `${label}：未提供` : '未提供';
    const display: string = typeof value === 'number' && binding.path === 'temperature_celsius'
      ? `${String(value)} °C` : String(value);
    return label.length > 0 ? `${label}：${display}` : display;
  }

  private assetDiagnostics(): SmartUiAssetDiagnostic[] {
    const ref: string | undefined = this.currentNode().ref;
    if (ref === undefined) return [];
    return this.validationDiagnostics.filter((item: SmartUiAssetDiagnostic): boolean => item.ref === ref);
  }

  private assetDiagnosticIndices(): number[] {
    const result: number[] = [];
    for (let index = 0; index < this.assetDiagnostics().length; index++) result.push(index);
    return result;
  }

  private assetDiagnosticAt(index: number): SmartUiAssetDiagnostic {
    return this.assetDiagnostics()[index] ?? EMPTY_ASSET_DIAGNOSTIC;
  }

  private assetRef(): string {
    return this.currentNode().ref ?? '';
  }

  @Builder
  private ChildNode(childId: string): void {
    if (this.depth < 6) {
      NativeSmartUiNode({ document: this.document, nodeId: childId, values: this.values,
        invalidStateIds: this.invalidStateIds, validationDiagnostics: this.validationDiagnostics,
        projections: this.projections, controller: this.controller, ready: this.ready, sourceKey: this.sourceKey,
        streaming: this.streaming, depth: this.depth + 1, fontSize: this.fontSize, fontFamily: this.fontFamily,
        onStateChange: (stateId: string, value: SmartUiScalar): void => { this.onStateChange(stateId, value); },
        onDraft: (nodeId: string): void => { this.onDraft(nodeId); },
        onAssetView: (ref: string): void => { this.onAssetView(ref); },
        onReset: (): void => { this.onReset(); },
        onContentGrow: (): void => { this.onContentGrow(); },
        onNodeArea: (id: string, frame: NativeUiNodeFrame | null): void => { this.onNodeArea(id, frame); },
        onRevealTouch: (): void => { this.onRevealTouch(); } });
    }
  }

  @Builder
  private Children(): void {
    ForEach(this.childNodeIds(), (childId: string): void => {
      this.ChildNode(childId);
    }, (childId: string): string => `${this.sourceKey}:${childId}`);
  }

  @Builder
  private ColumnNode(): void {
    Column({ space: 12 }) {
      if (this.currentNode().layout === 'row' || this.currentNode().layout === 'grid') {
        Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Start }) {
          ForEach(this.childNodeIds(), (childId: string, index: number): void => {
            Column() {
              this.ChildNode(childId);
            }
            .width(this.childWidth())
            .margin({ right: this.currentNode().layout === 'grid' ? (index % 2 === 0 ? 8 : 0) :
                ((index + 1) % Math.min(3, this.childNodeIds().length) === 0 ? 0 : 8),
              bottom: 8 })
            .alignItems(HorizontalAlign.Start);
          }, (childId: string): string => `${this.sourceKey}:${childId}`);
        }.width('100%');
      } else {
        this.Children();
      }
    }
    .width('100%')
    .padding(this.grouped() ? 14 : 0)
    .backgroundColor(this.columnBackground())
    .border({ width: {
      left: this.currentNode().variant === 'outlined' ? 1 :
        (this.currentNode().variant === 'section' || this.currentNode().variant === 'callout' ? 3 : 0),
      top: this.currentNode().variant === 'outlined' ? 1 : 0,
      right: this.currentNode().variant === 'outlined' ? 1 : 0,
      bottom: this.currentNode().variant === 'outlined' ? 1 : 0,
    }, color: this.currentNode().variant === 'outlined' && (this.currentNode().tone ?? 'neutral') === 'neutral'
      ? LINE : this.toneColor() })
    .borderRadius(12)
    .shadow({ radius: this.currentNode().variant === 'elevated' ? 10 : 0,
      color: HOME_GLASS_SHADOW, offsetY: this.currentNode().variant === 'elevated' ? 3 : 0 })
    .alignItems(HorizontalAlign.Start)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
    .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private TextNode(): void {
    Text(this.boundText())
      .width(this.currentNode().variant === 'badge' ? 'auto' : '100%')
      .fontSize(this.textSize(this.textBaseSize()))
      .lineHeight(this.textSize(this.textBaseSize() * (this.currentNode().variant === 'hero' ? 1.15 : 1.4)))
      .fontWeight(this.currentNode().variant === 'heading' || this.currentNode().variant === 'subheading' ||
        this.currentNode().variant === 'metric' || this.currentNode().variant === 'hero' ||
        this.currentNode().variant === 'eyebrow' ? FontWeight.Medium : FontWeight.Normal)
      .fontFamily(this.currentNode().variant === 'code' ? FONT_MONO :
        this.currentNode().variant === 'quote' ? FONT_SERIF : this.family())
      .letterSpacing(this.currentNode().variant === 'eyebrow' ? 1.2 : 0)
      .fontColor(this.toneColor())
      .textAlign(TextAlign.Start)
      .padding(this.currentNode().variant === 'code' || this.currentNode().variant === 'quote' ? 10 :
        this.currentNode().variant === 'badge' ? 6 : 0)
      .border({ width: { left: this.currentNode().variant === 'quote' ? 2 : 0 }, color: LINE })
      .borderRadius(this.currentNode().variant === 'badge' ? 14 : 8)
      .backgroundColor(this.currentNode().variant === 'code' ? SURFACE2 :
        this.currentNode().variant === 'badge' ? this.toneBackground() : Color.Transparent)
      .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 6 }))
        .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut }))
      .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private ChoiceOption(optionKey: string): void {
    if (this.currentOption(optionKey) !== null) {
      Flex({ alignItems: ItemAlign.Center }) {
        if (!this.chips() && this.currentNode().variant !== 'segmented') {
          Text(this.isSelected(optionKey) ? '✓' : '')
            .width(20).height(20).margin({ right: 10 })
            .flexShrink(0)
            .fontSize(this.textSize(12)).fontColor(ACCENT_INK).textAlign(TextAlign.Center)
            .borderRadius(10).border({ width: 1, color: this.isSelected(optionKey) ? ACCENT : LINE })
            .backgroundColor(this.isSelected(optionKey) ? ACCENT : SURFACE);
        }
        Text(this.optionLabel(optionKey))
          .flexGrow(this.chips() ? 0 : 1)
          .flexShrink(1)
          .fontSize(this.textSize(15))
          .lineHeight(this.textSize(21))
          .fontFamily(this.family())
          .textAlign(this.currentNode().variant === 'segmented' ? TextAlign.Center : TextAlign.Start)
          .fontColor(this.isSelected(optionKey) ? ACCENT : INK);
      }
      .width(this.choiceWidth())
      .constraintSize({ minHeight: this.currentNode().variant === 'tiles' ? 80 : 44, maxWidth: '100%' })
      .padding({ left: 12, right: 12, top: 10, bottom: 10 })
      .borderRadius(this.chips() ? 22 : 12)
      .border({ width: this.currentNode().variant === 'segmented' ? 0 : (this.isSelected(optionKey) ? 1.5 : 1),
        color: this.isSelected(optionKey) ? ACCENT : LINE })
      .backgroundColor(this.currentNode().variant === 'segmented' ?
        (this.isSelected(optionKey) ? SURFACE : SURFACE2) : (this.isSelected(optionKey) ? accentSoftBg(ACCENT) : SURFACE))
      .margin({ right: this.choiceMargin(optionKey), bottom: this.currentNode().variant === 'segmented' ? 0 : 8 })
      .scale({ x: this.pressedKey === optionKey ? 0.98 : 1, y: this.pressedKey === optionKey ? 0.98 : 1 })
      .onTouch((event: TouchEvent): void => { this.touch(optionKey, event); })
      .onClick((): void => { this.select(optionKey); })
      .accessibilityGroup(true)
      .accessibilityDescription(this.isSelected(optionKey) ? '已选择' : '未选择')
      .accessibilityText(`${this.optionLabel(optionKey)}${this.isSelected(optionKey) ? '，已选择' : ''}`);
    }
  }

  @Builder
  private StateWarning(): void {
    if (this.stateInvalid(this.currentNode().state)) {
      Text('当前值已不在可选范围，请重新选择')
        .width('100%')
        .fontSize(this.textSize(12))
        .lineHeight(this.textSize(18))
        .fontFamily(this.family())
        .fontColor(ERROR);
    }
  }

  @Builder
  private ChoiceNode(): void {
    Column() {
      Flex({ wrap: FlexWrap.Wrap,
        alignItems: this.currentNode().variant === 'tiles' ? ItemAlign.Stretch : ItemAlign.Start }) {
        ForEach(this.optionKeys(), (optionKey: string): void => {
          this.ChoiceOption(optionKey);
        }, (optionKey: string): string => `${this.sourceKey}:${this.nodeId}:${optionKey}`);
      }
      .width('100%')
      .padding(this.currentNode().variant === 'segmented' ? 4 : 0)
      .borderRadius(12)
      .backgroundColor(this.currentNode().variant === 'segmented' ? SURFACE2 : Color.Transparent);
      this.StateWarning();
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private InputNode(): void {
    Column({ space: 8 }) {
      TextInput({ text: this.stringValue(this.currentNode().state ?? ''), placeholder: this.currentNode().placeholder ?? '输入内容' })
        .width('100%')
        .constraintSize({ minHeight: 44 })
        .fontSize(this.textSize(15))
        .fontFamily(this.family())
        .fontColor(INK)
        .backgroundColor(this.currentNode().variant === 'underline' ? Color.Transparent : SURFACE2)
        .border({ width: {
          left: this.currentNode().variant === 'underline' ? 0 : 1,
          right: this.currentNode().variant === 'underline' ? 0 : 1,
          top: this.currentNode().variant === 'underline' ? 0 : 1,
          bottom: this.currentNode().variant === 'underline' && this.inputFocused ? 2 : 1,
        }, color: this.stateInvalid(this.currentNode().state) ? ERROR : this.inputFocused ? ACCENT : LINE })
        .borderRadius(this.currentNode().variant === 'underline' ? 0 : 10)
        .padding({ left: 12, right: 12, top: 10, bottom: 10 })
        .maxLength(512)
        .accessibilityText(this.currentNode().placeholder ?? '输入内容')
        .onFocus((): void => { this.inputFocused = true; })
        .onBlur((): void => { this.inputFocused = false; })
        .onTouch((event: TouchEvent): void => {
          if (event.type === TouchType.Down) this.onRevealTouch();
        })
        .onChange((value: string): void => { this.changeInput(value); });
      this.StateWarning();
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private ButtonNode(): void {
    Row() {
      Text(this.currentNode().label ?? '')
        .width('100%')
        .fontSize(this.textSize(15))
        .lineHeight(this.textSize(21))
        .fontFamily(this.family())
        .fontColor(this.draftEnabled() ? (this.primaryButton() ? ACCENT_INK : ACCENT) : INK4)
        .textAlign(TextAlign.Center);
    }
    .width('100%')
    .constraintSize({ minHeight: 44 })
    .padding({ left: 16, right: 16, top: 10, bottom: 10 })
    .borderRadius(this.currentNode().variant === 'pill' ? 24 : 10)
    .border({ width: this.currentNode().variant === 'ghost' ? 0 : 1, color: this.draftEnabled() ? ACCENT : LINE })
    .backgroundColor(this.buttonBackground())
    .scale({ x: this.pressedKey === 'button' ? 0.98 : 1, y: this.pressedKey === 'button' ? 0.98 : 1 })
    .onTouch((event: TouchEvent): void => { this.touch('button', event); })
    .enabled(this.draftEnabled())
    .onClick((): void => { this.clickDraft(); })
    .accessibilityText(`${this.currentNode().label ?? ''}${this.draftEnabled() ? '' : '，暂不可用'}`)
    .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private AssetDiagnostic(index: number): void {
    Text(this.assetDiagnosticAt(index).message)
      .width('100%')
      .fontSize(this.textSize(12))
      .lineHeight(this.textSize(18))
      .fontFamily(this.family())
      .fontColor(this.assetDiagnosticAt(index).severity === 'error' ? ERROR : INK3);
  }

  @Builder
  private AssetNode(): void {
    Column({ space: 8 }) {
      Row() {
        Text(this.currentNode().label ?? '关联图示')
          .layoutWeight(1)
          .fontSize(this.textSize(15))
          .fontFamily(this.family())
          .fontColor(ACCENT)
          .lineHeight(this.textSize(21));
        Text(this.assetDiagnostics().length > 0 ? '暂不可用' : '查看图示')
          .fontSize(this.textSize(12))
          .fontFamily(this.family())
          .fontColor(INK3);
      }
      .width('100%')
      .constraintSize({ minHeight: 44 })
      .padding({ left: 12, right: 12, top: 10, bottom: 10 })
      .borderRadius(10)
      .border({ width: 1, color: LINE })
      .backgroundColor(SURFACE2)
      .scale({ x: this.pressedKey === 'asset' ? 0.98 : 1, y: this.pressedKey === 'asset' ? 0.98 : 1 })
      .onTouch((event: TouchEvent): void => { this.touch('asset', event); })
      .enabled(this.assetDiagnostics().length === 0)
      .onClick((): void => {
        if (this.assetDiagnostics().length === 0 && this.assetRef().length > 0) this.onAssetView(this.assetRef());
      })
      .accessibilityGroup(true)
      .accessibilityText(`${this.currentNode().label ?? '关联图示'}，${this.assetDiagnostics().length > 0 ? '暂不可用' : '查看图示'}`);
      ForEach(this.assetDiagnosticIndices(), (index: number): void => {
        this.AssetDiagnostic(index);
      }, (index: number): string => `${this.sourceKey}:${this.nodeId}:diagnostic:${index}`);
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
  }

  @Builder
  private RenderNode(): void {
    if (this.currentNode().type === 'column') {
      this.ColumnNode();
    } else if (this.currentNode().type === 'text') {
      this.TextNode();
    } else if (this.currentNode().type === 'choice') {
      this.ChoiceNode();
    } else if (this.currentNode().type === 'input') {
      this.InputNode();
    } else if (this.currentNode().type === 'button') {
      this.ButtonNode();
    } else if (this.currentNode().type === 'asset') {
      this.AssetNode();
    } else if (this.currentNode().type === 'component') {
      Column() {
        NativeSmartUiComponent({ document: this.document, nodeId: this.nodeId, values: this.values,
          invalidStateIds: this.invalidStateIds, fontSize: this.fontSize, fontFamily: this.fontFamily,
          draftEnabled: this.draftEnabled(),
          child: (childId: string): void => { this.ChildNode(childId); },
          onStateChange: (stateId: string, value: SmartUiScalar): void => { this.onStateChange(stateId, value); },
          onDraft: (): void => { this.clickDraft(); }, onReset: (): void => { this.onReset(); } });
      }
      .width('100%')
      .onAreaChange((_old: Area, area: Area): void => { this.reportArea(area); });
    }
  }

  build(): void {
    if (this.nodeExists() && this.visible()) this.RenderNode();
  }
}
