import type {
  SmartUiAssetDiagnostic,
  SmartUiController,
  SmartUiDocument,
  SmartUiNode,
  SmartUiScalar,
  SmartUiStateAccessor,
  SmartUiStateValue,
  SmartUiWeatherProjection,
} from '@amber/chat-domain';
import { createSmartUiController, SMART_UI_MAX_INPUT_VALUE_CHARS } from '@amber/chat-domain';
import { ACCENT, ERROR, INK, INK3, LINE, MOTION_PRESS, SURFACE } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { NativeSmartUiNode } from './NativeSmartUiNode.ets';

const EMPTY_ACCESSOR: SmartUiStateAccessor = {
  get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
  set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
};
const EMPTY_CONTROLLER: SmartUiController = createSmartUiController(EMPTY_ACCESSOR);
const EMPTY_DOCUMENT: SmartUiDocument = {
  id: 'empty', nodes: [], states: [], status: 'partial', diagnostics: [], ended: false, rootId: null, records: 0,
};
const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };

@Component
export struct NativeSmartUiCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('onDocumentChanged') document: SmartUiDocument = EMPTY_DOCUMENT;
  @Prop @Watch('onInitialValuesChanged') initialValues: SmartUiStateValue[] = [];
  @Prop @Watch('onSourceKeyChanged') sourceKey: string = '';
  @Prop @Watch('onAccessorChanged') stateAccessor: SmartUiStateAccessor = EMPTY_ACCESSOR;
  @Prop ready: boolean = false;
  @Prop streaming: boolean = false;
  @Prop @Watch('onProjectionsChanged') projections: SmartUiWeatherProjection[] = [];
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @Prop validationDiagnostics: SmartUiAssetDiagnostic[] = [];
  onDraft: (prompt: string) => void = (_prompt: string): void => {};
  onAssetView: (ref: string) => void = (_ref: string): void => {};
  onContentGrow: () => void = (): void => {};
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_stateId: string, _value: SmartUiScalar): void => {};
  @State values: SmartUiStateValue[] = [];
  @State invalidStateIds: string[] = [];
  @State stateDiagnostics: string[] = [];
  private controller: SmartUiController = EMPTY_CONTROLLER;

  aboutToAppear(): void {
    this.controller = createSmartUiController(this.stateAccessor);
    this.reconcile(this.values);
  }

  private reconcile(currentValues: SmartUiStateValue[]): void {
    const result = this.controller.reconcile(this.document, this.sourceKey, currentValues, this.initialValues);
    this.values = result.values;
    this.invalidStateIds = result.invalidStateIds;
    this.stateDiagnostics = result.diagnostics.map((item): string => item.message);
  }

  private onDocumentChanged(): void {
    this.reconcile(this.values);
  }

  private onInitialValuesChanged(): void {
    // The page owns recovery snapshots; a new snapshot replaces only the pre-edit local state.
    this.reconcile([]);
  }

  private onSourceKeyChanged(): void {
    // A source namespace change must not carry values across messages or branches.
    this.values = [];
    this.reconcile(this.values);
  }

  private onAccessorChanged(): void {
    this.controller = createSmartUiController(this.stateAccessor);
    this.reconcile(this.values);
  }

  private onProjectionsChanged(): void {
    this.onContentGrow();
  }

  private updateState(stateId: string, value: SmartUiScalar): void {
    const nextValue: SmartUiScalar = typeof value === 'string'
      ? value.slice(0, SMART_UI_MAX_INPUT_VALUE_CHARS) : value;
    const result = this.controller.setValue(this.document, this.sourceKey, this.values, stateId, nextValue);
    const choice: boolean = this.document.nodes.some((node: SmartUiNode): boolean =>
      node.type === 'choice' && node.state === stateId);
    if (choice && result.accepted) {
      this.getUIContext().animateTo({ duration: MOTION_PRESS, curve: Curve.EaseOut }, (): void => {
        this.values = result.values;
        this.invalidStateIds = result.invalidStateIds;
        this.stateDiagnostics = result.diagnostics.map((item): string => item.message);
      });
    } else {
      this.values = result.values;
      this.invalidStateIds = result.invalidStateIds;
      this.stateDiagnostics = result.diagnostics.map((item): string => item.message);
    }
    if (result.accepted) this.onStateChange(stateId, nextValue);
    this.onContentGrow();
  }

  private hasRoot(): boolean {
    return this.document.rootId !== null;
  }

  private rootNodeId(): string {
    if (this.document.rootId !== null) return this.document.rootId;
    for (const node of this.document.nodes) {
      if (node.parent === null) return node.id;
    }
    return EMPTY_NODE.id;
  }

  @Builder
  private RootNode(nodeId: string): void {
    NativeSmartUiNode({ document: this.document, nodeId, values: this.values,
      invalidStateIds: this.invalidStateIds, validationDiagnostics: this.validationDiagnostics,
      controller: this.controller, ready: this.ready, sourceKey: this.sourceKey, streaming: this.streaming,
      projections: this.projections,
      depth: 1, fontSize: this.fontSize, fontFamily: this.fontFamily,
      onStateChange: (stateId: string, value: SmartUiScalar): void => { this.updateState(stateId, value); },
      onDraft: (prompt: string): void => { this.onDraft(prompt); },
      onAssetView: (ref: string): void => { this.onAssetView(ref); },
      onContentGrow: (): void => { this.onContentGrow(); } });
  }

  @Builder
  private Notice(): void {
    if (this.streaming && !this.ready) {
      Text('交互内容生成中，已收到的控件仍可使用')
        .width('100%').fontSize(Math.max(10, this.fontSize * 12 / 16)).fontFamily(this.fontFamily)
        .fontColor(INK3).lineHeight(Math.max(16, this.fontSize * 18 / 16));
    } else if (!this.ready) {
      Text('内容未完整生成，继续解释暂不可用')
        .width('100%').fontSize(Math.max(10, this.fontSize * 12 / 16)).fontFamily(this.fontFamily)
        .fontColor(ERROR).lineHeight(Math.max(16, this.fontSize * 18 / 16));
    }
    ForEach(this.stateDiagnosticIndices(), (index: number): void => {
      this.StateDiagnostic(index);
    }, (index: number): string => `${this.sourceKey}:state:${index}`);
  }

  private stateDiagnosticIndices(): number[] {
    const result: number[] = [];
    for (let index = 0; index < this.stateDiagnostics.length; index++) result.push(index);
    return result;
  }

  @Builder
  private StateDiagnostic(index: number): void {
    Text(this.stateDiagnosticAt(index)).width('100%').fontSize(Math.max(10, this.fontSize * 12 / 16))
        .fontFamily(this.fontFamily).fontColor(ERROR).lineHeight(Math.max(16, this.fontSize * 18 / 16));
  }

  private stateDiagnosticAt(index: number): string {
    return this.stateDiagnostics[index] ?? '';
  }

  @Builder
  private Content(): void {
    if (this.hasRoot()) {
      this.RootNode(this.rootNodeId());
    } else {
      Text(this.streaming ? '正在生成交互内容…' : '交互内容未就绪')
        .width('100%').fontSize(Math.max(10, this.fontSize)).fontFamily(this.fontFamily).fontColor(INK3);
    }
      this.Notice()
  }

  build(): void {
    Column({ space: 12 }) {
      this.Content()
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: this.ready ? LINE : ACCENT })
    .borderRadius(16)
    .alignItems(HorizontalAlign.Start)
    .margin({ bottom: 8 })
    .onAreaChange((before: Area, after: Area): void => {
      if (before.height !== after.height) this.onContentGrow();
    });
  }
}

