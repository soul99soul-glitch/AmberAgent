import type { SmartUiComponent, SmartUiDocument, SmartUiNode, SmartUiScalar, SmartUiStateValue } from '@amber/chat-domain';
import { componentKindAllowsChildren, componentKindIsPresentationState, encodeSmartUiSelection } from '@amber/chat-domain';
import { ACCENT, ERROR, INK, INK3, LINE, SURFACE, SURFACE2, SUCCESS_FG, WARNING_FG,
  accentInkFor, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS, FONT_MONO, FONT_SERIF } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';
import { NativeSmartUiInputs } from './NativeSmartUiInputs.ets';
import { NativeSmartUiData } from './NativeSmartUiData.ets';
import { NativeSmartUiStudy } from './NativeSmartUiStudy.ets';
import { smartUiValue, smartUiString, smartUiSelection, smartUiSpace } from './NativeSmartUiValues.ets';

const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };
const EMPTY_COMPONENT: SmartUiComponent = { kind: 'group' };
const EMPTY_DOCUMENT: SmartUiDocument = {
  id: 'empty', nodes: [], states: [], status: 'partial', diagnostics: [], ended: false, rootId: null, records: 0,
};

@Builder
function EmptyChild(_childId: string): void {}

/** Family-level rendering stays independent of tree recursion and the page's state store. */
@Component
export struct NativeSmartUiComponent {
  @Prop nodeId: string = '';
  @Prop @Watch('onDocumentChanged') document: SmartUiDocument = EMPTY_DOCUMENT;
  @Prop values: SmartUiStateValue[] = [];
  @Prop invalidStateIds: string[] = [];
  @Prop draftEnabled: boolean = false;
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @State private availableWidth: number = 260;
  @State private imageFailed: boolean = false;
  private imageUrl: string = '';
  @BuilderParam child: (childId: string) => void = EmptyChild;
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_id: string, _value: SmartUiScalar): void => {};
  onDraft: () => void = (): void => {};
  onReset: () => void = (): void => {};

  aboutToAppear(): void { this.imageUrl = this.component().url ?? ''; }
  private onDocumentChanged(): void {
    const url: string = this.component().url ?? '';
    if (url !== this.imageUrl) {
      this.imageUrl = url;
      this.imageFailed = false;
    }
  }

  private node(): SmartUiNode {
    for (const node of this.document.nodes) if (node.id === this.nodeId) return node;
    return EMPTY_NODE;
  }
  private component(): SmartUiComponent { return this.node().component ?? EMPTY_COMPONENT; }
  private kind(): string { return this.component().kind; }
  private title(): string { return this.component().title ?? this.node().label ?? ''; }
  private heading(): string { return this.title() + (this.node().required ? '（必填）' : ''); }
  private text(): string { return this.component().text ?? ''; }
  private items(): string[] { return this.component().items ?? []; }
  private itemIndices(): number[] { return this.items().map((_item: string, index: number): number => index); }
  private item(index: number): string { return this.items()[index] ?? ''; }
  private textSize(base: number): number { return Math.max(10, base * this.fontSize / 16); }
  private linkedNumber(fieldId: string | undefined, fallback: number): number {
    if (fieldId === undefined) return fallback;
    const value = smartUiValue(this.values, fieldId);
    return typeof value === 'number' ? value : fallback;
  }
  private space(): number { return smartUiSpace(this.component().style?.spacing, this.component().style?.density === 'compact' ? 6 : 12); }
  private tone(): string {
    const tone: string = this.component().style?.tone ?? 'accent';
    if (tone === 'neutral') return INK3;
    if (tone === 'success') return SUCCESS_FG;
    if (tone === 'warning') return WARNING_FG;
    if (tone === 'danger') return ERROR;
    return ACCENT;
  }
  private filled(): boolean { return this.component().style?.emphasis === 'filled'; }
  private ink(): string { return this.filled() ? accentInkFor(this.tone()) : INK; }
  private secondary(): string { return this.filled() ? this.ink() : INK3; }
  private accent(): string { return this.filled() ? this.ink() : this.tone(); }
  private surface(): string { return this.filled() ? accentSoftBg(this.ink()) : SURFACE2; }
  private cardSurface(): string { return this.filled() ? accentSoftBg(this.ink()) : SURFACE; }
  private componentSurface(): string | Color {
    const emphasis: string = this.component().style?.emphasis ?? 'plain';
    if (emphasis === 'filled') return this.tone();
    if (emphasis === 'soft') return accentSoftBg(this.tone());
    return Color.Transparent;
  }
  private childIds(): string[] {
    const result: string[] = [];
    if (!componentKindAllowsChildren(this.component().kind)) return result;
    for (const node of this.document.nodes) if (node.parent === this.nodeId) result.push(node.id);
    return result;
  }
  private childNode(id: string): SmartUiNode {
    for (const node of this.document.nodes) if (node.id === id) return node;
    return EMPTY_NODE;
  }
  private childTitle(id: string): string { return this.childNode(id).component?.title ?? this.childNode(id).label ?? id; }
  private columns(): number {
    if (this.fontSize >= 22) return 1;
    const fitting: number = Math.max(1, Math.floor((this.availableWidth + this.space()) / (140 + this.space())));
    return Math.min(this.component().columns ?? (this.kind() === 'row' || this.kind() === 'buttonGroup' ? 3 : 2), fitting);
  }
  private childWidth(): string { return `calc((100% - ${(this.columns() - 1) * this.space()}vp) / ${this.columns()})`; }
  private raw(): string { return smartUiString(this.values, this.node().state); }
  private resetAction(): boolean { return this.kind() === 'resetButton' || this.node().action?.type === 'reset'; }
  private requiredEmpty(): boolean {
    if (!this.node().required) return false;
    const value: SmartUiScalar | undefined = smartUiValue(this.values, this.node().state);
    if (value === undefined || (typeof value === 'string' && value.trim().length === 0)) return true;
    return ['multiChoice', 'checklist'].includes(this.kind()) && smartUiSelection(this.values, this.node().state).length === 0;
  }
  private change(value: SmartUiScalar): void {
    const id: string | undefined = this.node().state;
    if (id !== undefined) this.onStateChange(id, value);
  }
  private activeTab(): string {
    const raw: string = this.raw();
    return this.childIds().includes(raw) ? raw : this.childIds()[0] ?? '';
  }
  private expanded(childId: string): boolean { return smartUiSelection(this.values, this.node().state).includes(childId); }
  private toggleChild(childId: string): void {
    const selected: string[] = smartUiSelection(this.values, this.node().state);
    this.change(encodeSmartUiSelection(selected.includes(childId) ? selected.filter((id: string): boolean => id !== childId) : selected.concat([childId])));
  }
  private textBase(): number {
    if (this.kind() === 'title') return 24;
    if (this.kind() === 'subtitle') return 18;
    if (['caption', 'footnote', 'badge', 'chip', 'tag', 'statusDot'].includes(this.kind())) return 12;
    if (this.kind() === 'inlineCode') return 14;
    return 16;
  }
  private textInk(): string { return ['caption', 'footnote', 'subtitle'].includes(this.kind()) ? this.secondary() : this.ink(); }
  private displayText(): string {
    if (['title', 'subtitle', 'badge', 'chip', 'tag', 'statusDot', 'icon'].includes(this.kind())) return this.text() || this.title();
    return this.text();
  }
  private narrativeTitle(): string {
    if (this.title().length > 0) return this.title();
    if (this.kind() === 'goal') return '目标';
    if (this.kind() === 'context') return '背景';
    if (this.kind() === 'source') return '来源说明';
    if (this.kind() === 'reason') return '判断依据';
    if (this.kind() === 'tradeoff') return '取舍';
    if (this.kind() === 'result') return '结果说明';
    if (this.kind() === 'assumptions') return '前提假设';
    return this.kind() === 'alert' ? '注意' : '提示';
  }
  private iconName(): string {
    const symbol: string = this.component().symbol ?? '';
    if (symbol === 'info.circle') return 'info';
    if (symbol === 'exclamationmark.triangle') return 'bell';
    if (symbol === 'sparkles') return 'spark';
    if (symbol === 'photo') return 'image';
    if (symbol === 'person.crop.circle') return 'person';
    if (symbol === 'doc.text' || symbol === 'doc') return 'doc';
    if (symbol === 'link') return 'link';
    if (symbol.length > 0) return symbol;
    if (this.kind() === 'avatar') return 'person';
    if (this.kind() === 'image') return 'image';
    if (this.kind() === 'fileCard' || this.kind() === 'source') return 'doc';
    if (this.kind() === 'linkCard') return 'link';
    if (this.kind() === 'goal') return 'pin';
    if (this.kind() === 'reason') return 'brain';
    if (this.kind() === 'result') return 'check_circle';
    return 'info';
  }
  private summaryNode(stateId: string): SmartUiNode {
    for (const node of this.document.nodes) {
      if (node.state === stateId && (node.component === undefined || !componentKindIsPresentationState(node.component.kind))) return node;
    }
    return EMPTY_NODE;
  }
  private summaryLabel(stateId: string): string { return this.summaryNode(stateId).component?.title ?? this.summaryNode(stateId).label ?? stateId; }
  private summaryValue(stateId: string): string {
    const value: SmartUiScalar | undefined = smartUiValue(this.values, stateId);
    if (value === undefined || value === '') return '尚未填写';
    const node: SmartUiNode = this.summaryNode(stateId);
    const kind: string = node.component?.kind ?? '';
    if (kind === 'multiChoice' || kind === 'checklist') {
      const selected: string[] = smartUiSelection(this.values, stateId);
      return selected.length === 0 ? '尚未选择' : selected.map((key: string): string => {
        for (const option of node.options ?? []) if (String(option.value) === key) return option.label;
        return key;
      }).join('、');
    }
    for (const option of node.options ?? []) if (option.value === value) return option.label;
    if (typeof value === 'boolean') return value ? '是' : '否';
    return String(value);
  }

  @Builder
  private Heading(): void {
    if (this.heading().length > 0) {
      Text(this.heading()).width('100%').fontSize(this.textSize(15)).fontWeight(FontWeight.Medium)
        .fontFamily(this.fontFamily).fontColor(this.ink()).lineHeight(this.textSize(21));
    }
  }

  @Builder
  private ChildContent(id: string): void {
    if (this.filled()) {
      // Children render with their own normal ink; give that independent theme a matching surface.
      Column() { this.child(id); }
        .width('100%').padding(8).backgroundColor(SURFACE).borderRadius(8).alignItems(HorizontalAlign.Start);
    } else {
      this.child(id);
    }
  }

  @Builder
  private Children(): void {
    ForEach(this.childIds(), (id: string): void => { this.ChildContent(id); }, (id: string): string => `${this.nodeId}:${id}`);
  }

  @Builder
  private Layout(): void {
    if (this.kind() === 'stack') {
      Stack({ alignContent: this.component().style?.alignment === 'center' ? Alignment.Center : Alignment.TopStart }) {
        this.Children();
      }.width('100%');
    } else if (['row', 'grid', 'metricGrid', 'imageGrid', 'buttonGroup'].includes(this.kind())) {
      Column({ space: this.space() }) {
        this.Heading();
        Flex({ wrap: FlexWrap.Wrap, alignItems: ItemAlign.Start }) {
          ForEach(this.childIds(), (id: string, index: number): void => {
            Column() { this.ChildContent(id); }.width(this.childWidth()).alignItems(HorizontalAlign.Start)
              .margin({ right: (index + 1) % this.columns() === 0 ? 0 : this.space(), bottom: this.space() });
          }, (id: string): string => `${this.nodeId}:${id}`);
        }.width('100%');
      }.width('100%').alignItems(HorizontalAlign.Start);
    } else if (this.kind() === 'divider') {
      Row({ space: 8 }) {
        Divider().layoutWeight(1).color(LINE);
        if (this.title().length > 0) Text(this.title()).fontSize(this.textSize(12)).fontColor(this.secondary()).flexShrink(1);
        Divider().layoutWeight(1).color(LINE);
      }.width('100%').padding({ top: 4, bottom: 4 });
    } else if (this.kind() === 'spacer') {
      Row().height(Math.max(0, Math.min(48, this.component().value ?? 12))).width('100%');
    } else {
      Column({ space: this.space() }) {
        this.Heading();
        if (this.kind() === 'panel' && this.title().length > 0) Divider().color(LINE);
        this.Children();
      }.width('100%').alignItems(this.component().style?.alignment === 'center' ? HorizontalAlign.Center : HorizontalAlign.Start)
        .padding(this.kind() === 'card' ? 14 : this.kind() === 'panel' ? 16 : this.kind() === 'inset' ? 20 : 0)
        .backgroundColor(this.kind() === 'card' ? this.cardSurface() : this.kind() === 'panel' ? this.surface() : Color.Transparent)
        .border({ width: this.kind() === 'card' ? 1 : 0, color: LINE }).borderRadius(this.kind() === 'card' ? 16 : 8);
    }
  }

  @Builder
  private Items(numbered: boolean, diagram: boolean): void {
    ForEach(this.itemIndices(), (index: number): void => {
      Row({ space: 8 }) {
        // 序号栏随字号缩放:固定 22vp 在大字号下会把两位数序号裁掉
        if (!diagram) Text(numbered ? `${index + 1}.` : '•').width(this.textSize(22)).fontSize(this.textSize(15)).fontColor(this.accent()).flexShrink(0);
        Text(this.item(index)).layoutWeight(1).fontSize(this.textSize(15)).fontFamily(this.fontFamily)
          .fontColor(this.ink()).lineHeight(this.textSize(21)).textAlign(diagram ? TextAlign.Center : TextAlign.Start);
      }.width('100%').alignItems(VerticalAlign.Top).padding(diagram ? 10 : 0)
        .backgroundColor(diagram ? accentSoftBg(this.accent()) : Color.Transparent).borderRadius(8);
      if (diagram && index < this.items().length - 1) {
        Row() { Icon({ name: 'arrow_up', color: this.secondary(), iconSize: 18 }).rotate({ angle: 180 }); }
          .width('100%').justifyContent(FlexAlign.Center);
      }
    }, (index: number): string => `${this.nodeId}:item:${index}`);
  }

  @Builder
  private TextContent(): void {
    if (this.kind() === 'bulletList' || this.kind() === 'numberedList') {
      this.Heading();
      this.Items(this.kind() === 'numberedList', false);
    } else if (this.kind() === 'callout') {
      this.Narrative();
    } else if (this.kind() === 'keyValue') {
      this.Heading();
      Text(this.text()).width('100%').fontSize(this.textSize(16)).fontColor(this.ink()).lineHeight(this.textSize(22));
    } else {
      Text(this.displayText()).width('100%').fontSize(this.textSize(this.textBase())).lineHeight(this.textSize(this.textBase() * 1.4))
        .fontFamily(this.kind() === 'inlineCode' ? FONT_MONO : this.kind() === 'quote' ? FONT_SERIF : this.fontFamily)
        .fontWeight(this.kind() === 'title' || this.kind() === 'subtitle' || this.kind() === 'highlight' ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.textInk()).copyOption(CopyOptions.InApp)
        .padding(['quote', 'inlineCode', 'highlight'].includes(this.kind()) ? 10 : 0)
        .backgroundColor(this.kind() === 'inlineCode' ? this.surface() : this.kind() === 'highlight' ? accentSoftBg(this.accent()) : Color.Transparent)
        .border({ width: { left: this.kind() === 'quote' ? 3 : 0 }, color: this.accent() }).borderRadius(6);
      if (this.kind() === 'quote' && this.title().length > 0) this.Heading();
    }
  }

  @Builder
  private Narrative(): void {
    Row({ space: 10 }) {
      Icon({ name: this.iconName(), color: this.accent(), iconSize: 20 });
      Column({ space: 6 }) {
        Text(this.narrativeTitle()).width('100%').fontSize(this.textSize(14)).fontWeight(FontWeight.Medium).fontColor(this.ink());
        if (this.text().length > 0) Text(this.text()).width('100%').fontSize(this.textSize(15)).lineHeight(this.textSize(21)).fontColor(this.ink());
        if (this.kind() === 'assumptions') this.Items(false, false);
      }.layoutWeight(1).alignItems(HorizontalAlign.Start);
    }.width('100%').alignItems(VerticalAlign.Top)
      .padding(['alert', 'result', 'callout', 'assumptions'].includes(this.kind()) ? 12 : 4)
      .backgroundColor(['alert', 'result', 'callout'].includes(this.kind()) ? accentSoftBg(this.accent()) : Color.Transparent)
      .border({ width: this.kind() === 'assumptions' ? 1 : 0, color: LINE }).borderRadius(10);
  }

  @Builder
  private Status(): void {
    if (['badge', 'chip', 'tag', 'statusDot'].includes(this.kind())) {
      Row({ space: 6 }) {
        if (this.kind() === 'statusDot') Row().width(8).height(8).borderRadius(4).backgroundColor(this.accent());
        if (this.kind() === 'chip') Icon({ name: this.iconName(), iconSize: 14, color: this.accent() });
        Text((this.kind() === 'tag' ? '# ' : '') + this.displayText()).fontSize(this.textSize(12))
          .fontColor(this.accent()).lineHeight(this.textSize(18)).flexShrink(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis });
      }.constraintSize({ maxWidth: '100%' }).padding(this.kind() === 'statusDot' ? 0 : 8)
        .backgroundColor(this.kind() === 'badge' || this.kind() === 'chip' ? accentSoftBg(this.accent()) : Color.Transparent)
        .border({ width: this.kind() === 'tag' ? 1 : 0, color: this.accent() }).borderRadius(this.kind() === 'badge' ? 20 : 6);
    } else if (this.kind() === 'progress' || this.kind() === 'stepProgress') {
      this.Heading();
      if (this.kind() === 'progress') {
        Progress({
          value: this.linkedNumber(this.component().valueFieldId, this.component().value ?? 0),
          total: this.component().max ?? 100, type: ProgressType.Linear,
        })
          .width('100%').color(this.accent()).backgroundColor(LINE)
          .accessibilityText(`${this.linkedNumber(this.component().valueFieldId, this.component().value ?? 0)} / ${this.component().max ?? 100}`);
      } else {
        Row({ space: 4 }) {
          ForEach(this.itemIndices(), (index: number): void => {
            Row().layoutWeight(1).height(5).borderRadius(3)
              .backgroundColor(index < this.linkedNumber(this.component().valueFieldId, this.component().value ?? 0) ? this.accent() : LINE);
          }, (index: number): string => `${this.nodeId}:step:${index}`);
        }.width('100%');
        Text(this.items().join(' → ')).fontSize(this.textSize(12)).fontColor(this.secondary());
      }
      if (this.text().length > 0) Text(this.text()).fontSize(this.textSize(12)).fontColor(this.secondary());
    } else if (this.kind() === 'skeleton') {
      Text(this.title() || '正在准备内容').fontSize(this.textSize(12)).fontColor(this.secondary());
      Row().width('100%').height(14).borderRadius(4).backgroundColor(LINE);
      Row().width('65%').height(14).borderRadius(4).backgroundColor(LINE);
    } else if (this.kind() === 'emptyState') {
      Column({ space: 8 }) {
        Icon({ name: this.component().symbol ?? 'box', iconSize: 32, color: this.secondary() });
        this.Heading();
        Text(this.text()).width('100%').fontSize(this.textSize(14)).lineHeight(this.textSize(20)).fontColor(this.secondary()).textAlign(TextAlign.Center);
      }.width('100%').padding(20);
    } else {
      this.Narrative();
    }
  }

  @Builder
  private Media(): void {
    if (this.kind() === 'image' || this.kind() === 'avatar') {
      if ((this.component().url ?? '').length > 0 && !this.imageFailed) {
        Image(this.component().url ?? '').width(this.kind() === 'avatar' ? 56 : '100%')
          .height(this.kind() === 'avatar' ? 56 : 220).objectFit(this.kind() === 'avatar' ? ImageFit.Cover : ImageFit.Contain)
          .borderRadius(this.kind() === 'avatar' ? 28 : 10).accessibilityText(this.title() || '图片')
          .onError((): void => { this.imageFailed = true; });
      } else {
        Row({ space: 8 }) {
          Icon({ name: this.iconName(), iconSize: 28, color: this.secondary() });
          if (this.imageFailed) Text('图片暂时无法显示').fontSize(this.textSize(12)).fontColor(this.secondary());
        }.constraintSize({ minHeight: this.kind() === 'avatar' ? 56 : 80 }).width('100%');
      }
      if (this.title().length > 0) this.Heading();
    } else if (this.kind() === 'codeBlock') {
      this.Heading();
      Scroll() {
        Text(this.text()).fontFamily(FONT_MONO).fontSize(this.textSize(13)).fontColor(this.ink()).lineHeight(this.textSize(20))
          .copyOption(CopyOptions.InApp).padding(12);
      }.width('100%').scrollable(ScrollDirection.Horizontal).backgroundColor(this.surface()).borderRadius(8);
    } else if (this.kind() === 'diagram') {
      this.Heading();
      this.Items(false, true);
    } else {
      Row({ space: 10 }) {
        Icon({ name: this.iconName(), iconSize: this.kind() === 'icon' ? 22 : 24, color: this.accent() });
        Column({ space: 6 }) {
          Text(this.kind() === 'icon' ? this.displayText() : this.title()).width('100%')
            .fontSize(this.textSize(15)).fontColor(this.ink()).lineHeight(this.textSize(21));
          if (this.kind() !== 'icon' && this.text().length > 0) Text(this.text()).fontSize(this.textSize(13)).fontColor(this.secondary()).lineHeight(this.textSize(19));
          if (this.kind() === 'linkCard') Text(this.component().url ?? '').width('100%').fontSize(this.textSize(12))
            .fontColor(this.secondary()).copyOption(CopyOptions.InApp).lineHeight(this.textSize(18));
        }.layoutWeight(1).alignItems(HorizontalAlign.Start);
      }.width('100%').alignItems(VerticalAlign.Top).padding(this.kind() === 'icon' ? 0 : 12)
        .backgroundColor(this.kind() === 'icon' ? Color.Transparent : this.surface()).borderRadius(10);
    }
  }

  @Builder
  private Summary(): void {
    this.Heading();
    ForEach(this.itemIndices(), (index: number): void => {
      if (this.summaryNode(this.item(index)).id !== EMPTY_NODE.id) {
        Column({ space: 4 }) {
          Text(this.summaryLabel(this.item(index))).width('100%').fontSize(this.textSize(12)).fontColor(this.secondary());
          Text(this.summaryValue(this.item(index))).width('100%').fontSize(this.textSize(15)).fontColor(this.ink()).lineHeight(this.textSize(21));
        }.width('100%').padding(10).backgroundColor(this.surface()).borderRadius(8).alignItems(HorizontalAlign.Start);
      }
    }, (index: number): string => `${this.nodeId}:summary:${index}`);
  }

  @Builder
  private Actions(): void {
    if (this.kind() === 'button' || this.kind() === 'submitButton' || this.kind() === 'resetButton') {
      Button(this.title() || (this.kind() === 'resetButton' ? '重置选择' : '基于选择继续'), { type: ButtonType.Normal })
        .width('100%').constraintSize({ minHeight: 44 }).padding(12).borderRadius(12)
        .fontSize(this.textSize(15))
        .fontWeight(this.kind() === 'submitButton' ? FontWeight.Medium : FontWeight.Normal)
        .fontColor(this.kind() === 'submitButton' ? accentInkFor(this.accent())
          : this.resetAction() ? this.secondary() : this.accent())
        .backgroundColor(this.kind() === 'submitButton' ? this.accent()
          : this.resetAction() ? Color.Transparent : this.surface())
        .border({ width: this.kind() === 'submitButton' ? 0 : 1, color: LINE })
        .opacity(this.kind() === 'submitButton' && !this.draftEnabled ? 0.55 : 1)
        .enabled(this.resetAction() || this.draftEnabled)
        .onClick((): void => { if (this.resetAction()) this.onReset(); else this.onDraft(); });
      if (!this.resetAction() && !this.draftEnabled) {
        Text('请填写必填项并检查当前选择；内容完整后即可继续')
          .width('100%').fontSize(this.textSize(12)).fontColor(this.secondary()).lineHeight(this.textSize(18));
      }
    } else if (this.kind() === 'disclosure') {
      Row({ space: 8 }) {
        Text(this.heading()).layoutWeight(1).fontSize(this.textSize(15)).fontColor(this.ink()).lineHeight(this.textSize(21));
        Icon({ name: this.raw() === 'true' ? 'chevron_down' : 'chevron_right', iconSize: 16, color: this.accent() });
      }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.change(this.raw() !== 'true'); });
      if (this.raw() === 'true') this.Children();
    } else if (this.kind() === 'tabs') {
      this.Heading();
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.childIds(), (id: string): void => {
          Text(this.childTitle(id)).fontSize(this.textSize(14)).fontColor(this.activeTab() === id ? this.accent() : this.ink())
            .lineHeight(this.textSize(20)).constraintSize({ minHeight: 44, maxWidth: '100%' }).padding(10).borderRadius(8)
            .backgroundColor(this.activeTab() === id ? accentSoftBg(this.accent()) : this.surface()).margin({ right: 6, bottom: 6 })
            .onClick((): void => { this.change(id); }).accessibilityText(`${this.childTitle(id)}${this.activeTab() === id ? '，已选择' : ''}`);
        }, (id: string): string => `${this.nodeId}:tab:${id}`);
      }.width('100%');
      if (this.activeTab().length > 0) this.ChildContent(this.activeTab());
    } else if (this.kind() === 'accordion') {
      this.Heading();
      ForEach(this.childIds(), (id: string): void => {
        Column({ space: this.space() }) {
          Row({ space: 8 }) {
            Text(this.childTitle(id)).layoutWeight(1).fontSize(this.textSize(15)).lineHeight(this.textSize(21)).fontColor(this.ink());
            Icon({ name: this.expanded(id) ? 'chevron_down' : 'chevron_right', iconSize: 16, color: this.accent() });
          }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.toggleChild(id); });
          if (this.expanded(id)) this.ChildContent(id);
        }.width('100%').padding(10).border({ width: 1, color: LINE }).borderRadius(10).alignItems(HorizontalAlign.Start);
      }, (id: string): string => `${this.nodeId}:accordion:${id}`);
    }
  }

  @Builder
  private Content(): void {
    if (componentKindAllowsChildren(this.component().kind) && !['disclosure', 'tabs', 'accordion'].includes(this.kind()) ||
      this.kind() === 'divider' || this.kind() === 'spacer') {
      this.Layout();
    } else if (['title', 'subtitle', 'body', 'caption', 'callout', 'quote', 'inlineCode', 'bulletList', 'numberedList', 'keyValue', 'footnote', 'highlight'].includes(this.kind())) {
      this.TextContent();
    } else if (['badge', 'chip', 'tag', 'statusDot', 'progress', 'stepProgress', 'skeleton', 'emptyState', 'notice', 'alert'].includes(this.kind())) {
      this.Status();
    } else if (['singleChoice', 'multiChoice', 'segmented', 'picker', 'toggle', 'textField', 'textArea', 'numberField', 'slider', 'stepper', 'datePicker', 'timePicker', 'rating', 'colorChoice', 'checklist', 'menu'].includes(this.kind())) {
      this.Heading();
      NativeSmartUiInputs({ node: this.node(), values: this.values, fontSize: this.fontSize, fontFamily: this.fontFamily,
        ink: this.ink(), secondaryInk: this.secondary(), accent: this.accent(), surface: this.surface(),
        onStateChange: (id: string, value: SmartUiScalar): void => { this.onStateChange(id, value); } });
    } else if (this.kind() === 'study') {
      this.Heading();
      NativeSmartUiStudy({ node: this.node(), values: this.values, fontSize: this.fontSize, fontFamily: this.fontFamily,
        ink: this.ink(), secondaryInk: this.secondary(), accent: this.accent(), surface: this.surface(),
        onStateChange: (id: string, value: SmartUiScalar): void => { this.onStateChange(id, value); } });
    } else if (['metric', 'table', 'list', 'comparison', 'timeline', 'steps', 'barChart', 'lineChart'].includes(this.kind())) {
      this.Heading();
      NativeSmartUiData({ node: this.node(), fontSize: this.fontSize, fontFamily: this.fontFamily,
        ink: this.ink(), secondaryInk: this.secondary(), accent: this.accent(), surface: this.surface(),
        values: this.values });
    } else if (['image', 'avatar', 'icon', 'linkCard', 'fileCard', 'codeBlock', 'diagram'].includes(this.kind())) {
      this.Media();
    } else if (this.kind() === 'decisionSummary') {
      this.Summary();
    } else if (['goal', 'context', 'source', 'reason', 'tradeoff', 'result', 'assumptions'].includes(this.kind())) {
      this.Narrative();
    } else {
      this.Actions();
    }
    if (this.invalidStateIds.includes(this.node().state ?? '')) {
      Text('当前值不符合组件要求，请修改后继续').width('100%').fontSize(this.textSize(12)).fontColor(ERROR).lineHeight(this.textSize(18));
    } else if (this.requiredEmpty()) {
      Text('必填：请填写或选择后继续').width('100%').fontSize(this.textSize(12)).fontColor(this.secondary()).lineHeight(this.textSize(18));
    }
  }

  build(): void {
    Column({ space: this.space() }) { this.Content(); }
      .width('100%').padding(smartUiSpace(this.component().style?.padding, 0)).backgroundColor(this.componentSurface())
      .border({ width: this.component().style?.emphasis === 'outlined' ? 1 : 0, color: this.tone() }).borderRadius(12)
      .alignItems(this.component().style?.alignment === 'center' ? HorizontalAlign.Center : HorizontalAlign.Start)
      .onAreaChange((_before: Area, after: Area): void => { this.availableWidth = Math.max(40, Number(after.width)); });
  }
}
