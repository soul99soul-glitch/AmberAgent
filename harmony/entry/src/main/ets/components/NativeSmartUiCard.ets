import type {
  SmartUiAssetDiagnostic,
  SmartUiController,
  SmartUiDocument,
  SmartUiNode,
  SmartUiScalar,
  SmartUiStateAccessor,
  SmartUiStateValue,
  SmartUiStateReconciliation,
  SmartUiWeatherProjection,
} from '@amber/chat-domain';
import { createSmartUiController, SMART_UI_MAX_INPUT_VALUE_CHARS } from '@amber/chat-domain';
import { ACCENT, ERROR, INK, INK3, LINE, MOTION_PRESS, SURFACE } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { NativeSmartUiNode } from './NativeSmartUiNode.ets';
import { NativeUiGenerationOverlay } from './NativeUiGenerationOverlay.ets';
import type { NativeUiNodeFrame } from './NativeUiGenerationOverlay.ets';

const EMPTY_ACCESSOR: SmartUiStateAccessor = {
  get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
  set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
};
const EMPTY_CONTROLLER: SmartUiController = createSmartUiController(EMPTY_ACCESSOR);
const EMPTY_DOCUMENT: SmartUiDocument = {
  id: 'empty', nodes: [], states: [], status: 'partial', diagnostics: [], ended: false, rootId: null, records: 0,
};
const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };

export class NativeUiRevealLedger {
  private consumedKeys: Set<string> = new Set<string>();

  has(key: string): boolean {
    return this.consumedKeys.has(key);
  }

  consume(key: string): void {
    this.consumedKeys.add(key);
  }
}

const CONSUMED_NATIVE_UI_REVEALS: NativeUiRevealLedger = new NativeUiRevealLedger();

export const nativeUiRevealFrames = (frames: NativeUiNodeFrame[]): NativeUiNodeFrame[] =>
  frames.filter((frame: NativeUiNodeFrame): boolean => frame.type !== 'column');

@Component
export struct NativeSmartUiCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('onDocumentChanged') document: SmartUiDocument = EMPTY_DOCUMENT;
  @Prop @Watch('onInitialValuesChanged') initialValues: SmartUiStateValue[] = [];
  @Prop @Watch('onSourceKeyChanged') sourceKey: string = '';
  @Prop @Watch('onAccessorChanged') stateAccessor: SmartUiStateAccessor = EMPTY_ACCESSOR;
  @Prop @Watch('onReadyChanged') ready: boolean = false;
  @Prop @Watch('onStreamingChanged') streaming: boolean = false;
  @Prop @Watch('onRevealEligibleChanged') revealEligible: boolean = false;
  @StorageProp('reduceMotion') @Watch('onRevealEnvironmentChanged') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('onRevealEnvironmentChanged') appBackgrounded: boolean = false;
  @Prop @Watch('onProjectionsChanged') projections: SmartUiWeatherProjection[] = [];
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @Prop validationDiagnostics: SmartUiAssetDiagnostic[] = [];
  onDraft: (nodeId: string) => void = (_nodeId: string): void => {};
  onAssetView: (ref: string) => void = (_ref: string): void => {};
  onContentGrow: () => void = (): void => {};
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_stateId: string, _value: SmartUiScalar): void => {};
  @State values: SmartUiStateValue[] = [];
  @State invalidStateIds: string[] = [];
  @State stateDiagnostics: string[] = [];
  @State frames: NativeUiNodeFrame[] = [];
  @State revealActive: boolean = false;
  @State cardVisible: boolean = true;
  private controller: SmartUiController = EMPTY_CONTROLLER;
  private measuredFrames: Map<string, NativeUiNodeFrame> = new Map<string, NativeUiNodeFrame>();
  private revealStartTimer: number = -1;
  private pendingReveal: boolean = false;

  aboutToAppear(): void {
    this.controller = createSmartUiController(this.stateAccessor);
    this.reconcile(this.values);
    if (this.ready && this.revealEligible) this.requestRevealStart();
  }

  aboutToDisappear(): void {
    this.finishReveal();
  }

  private revealKey(): string {
    return this.sourceKey.length > 0 ? this.sourceKey : this.document.id;
  }

  private consumed(): boolean {
    return CONSUMED_NATIVE_UI_REVEALS.has(this.revealKey());
  }

  private consume(): void {
    CONSUMED_NATIVE_UI_REVEALS.consume(this.revealKey());
  }

  private snapshotFrames(): NativeUiNodeFrame[] {
    const result: NativeUiNodeFrame[] = [];
    this.measuredFrames.forEach((frame: NativeUiNodeFrame): void => { result.push(frame); });
    return nativeUiRevealFrames(result);
  }

  private clearRevealStartTimer(): void {
    if (this.revealStartTimer >= 0) clearTimeout(this.revealStartTimer);
    this.revealStartTimer = -1;
  }

  private clearMeasurements(): void {
    this.measuredFrames.clear();
    this.frames = [];
  }

  private pruneMeasurements(): void {
    const liveIds: Set<string> = new Set<string>();
    for (const node of this.document.nodes) liveIds.add(node.id);
    const removed: string[] = [];
    this.measuredFrames.forEach((_frame: NativeUiNodeFrame, id: string): void => {
      if (!liveIds.has(id)) removed.push(id);
    });
    for (const id of removed) this.measuredFrames.delete(id);
    if (this.revealActive) this.frames = this.snapshotFrames();
  }

  private finishReveal(): void {
    this.clearRevealStartTimer();
    this.pendingReveal = false;
    this.revealActive = false;
  }

  private cancelReveal(consumePending: boolean = true): void {
    if (!this.revealActive && !this.pendingReveal) return;
    if (consumePending && this.pendingReveal) this.consume();
    this.finishReveal();
  }

  private scheduleRevealStart(): void {
    if (!this.pendingReveal || this.revealStartTimer >= 0 || this.measuredFrames.size === 0) return;
    this.revealStartTimer = setTimeout((): void => {
      this.revealStartTimer = -1;
      this.tryStartReveal();
    }, 0);
  }

  private requestRevealStart(): void {
    if (!this.ready || !this.revealEligible || this.consumed()) return;
    this.pendingReveal = true;
    if (this.reduceMotion) {
      this.consume();
      this.finishReveal();
      return;
    }
    if (this.appBackgrounded || !this.cardVisible) return;
    this.scheduleRevealStart();
  }

  private tryStartReveal(): void {
    if (!this.pendingReveal || !this.ready || !this.revealEligible || this.consumed()) return;
    if (this.reduceMotion) {
      this.consume();
      this.finishReveal();
      return;
    }
    if (this.appBackgrounded || !this.cardVisible) return;
    const measured: NativeUiNodeFrame[] = this.snapshotFrames();
    if (measured.length === 0) {
      this.consume();
      this.finishReveal();
      return;
    }
    this.frames = measured;
    this.pendingReveal = false;
    this.consume();
    this.revealActive = true;
  }

  private onReadyChanged(): void {
    if (this.ready && this.revealEligible) this.requestRevealStart();
    else this.cancelReveal();
  }

  private onStreamingChanged(): void {
    if (!this.streaming && this.ready && this.revealEligible) this.requestRevealStart();
    else this.cancelReveal();
  }

  private onRevealEligibleChanged(): void {
    if (this.revealEligible && this.ready) this.requestRevealStart();
    else this.cancelReveal();
  }

  private onRevealEnvironmentChanged(): void {
    if (this.revealActive) {
      this.cancelReveal();
    } else if (this.pendingReveal) {
      this.requestRevealStart();
    }
  }

  private onNodeArea(id: string, frame: NativeUiNodeFrame | null): void {
    let changed: boolean = false;
    if (frame === null) {
      changed = this.measuredFrames.delete(id);
    } else {
      const previous: NativeUiNodeFrame | undefined = this.measuredFrames.get(id);
      changed = previous === undefined || previous.x !== frame.x || previous.y !== frame.y ||
        previous.width !== frame.width || previous.height !== frame.height;
      this.measuredFrames.set(id, frame);
    }
    if (!changed) return;
    if (this.revealActive) this.frames = this.snapshotFrames();
    if (this.pendingReveal) this.scheduleRevealStart();
  }

  private reconcile(currentValues: SmartUiStateValue[]): void {
    const result = this.controller.reconcile(this.document, this.sourceKey, currentValues, this.initialValues);
    this.applyReconciliation(result);
  }

  private sameValues(left: SmartUiStateValue[], right: SmartUiStateValue[]): boolean {
    if (left.length !== right.length) return false;
    for (let index = 0; index < left.length; index++) {
      if (left[index].id !== right[index].id || left[index].value !== right[index].value) return false;
    }
    return true;
  }

  private sameStrings(left: string[], right: string[]): boolean {
    return left.length === right.length && left.every((value: string, index: number): boolean => value === right[index]);
  }

  private applyReconciliation(result: SmartUiStateReconciliation): void {
    // Native text controls can echo programmatic values. Keep equivalent snapshots stable to end that update cycle.
    if (!this.sameValues(this.values, result.values)) this.values = result.values;
    if (!this.sameStrings(this.invalidStateIds, result.invalidStateIds)) this.invalidStateIds = result.invalidStateIds;
    const diagnostics: string[] = result.diagnostics.map((item): string => item.message);
    if (!this.sameStrings(this.stateDiagnostics, diagnostics)) this.stateDiagnostics = diagnostics;
  }

  private onDocumentChanged(): void {
    this.pruneMeasurements();
    this.reconcile(this.values);
    if (this.ready && this.revealEligible) this.requestRevealStart();
    else this.cancelReveal();
  }

  private onInitialValuesChanged(): void {
    // The page owns recovery snapshots; a new snapshot replaces only the pre-edit local state.
    this.reconcile([]);
  }

  private onSourceKeyChanged(): void {
    // A source namespace change must not carry values across messages or branches.
    this.values = [];
    this.clearMeasurements();
    this.cancelReveal(false);
    this.reconcile(this.values);
    if (this.ready && this.revealEligible) this.requestRevealStart();
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
    if (this.values.some((item: SmartUiStateValue): boolean => item.id === stateId && item.value === nextValue)) return;
    const result = this.controller.setValue(this.document, this.sourceKey, this.values, stateId, nextValue);
    const choice: boolean = this.document.nodes.some((node: SmartUiNode): boolean =>
      node.type === 'choice' && node.state === stateId);
    if (choice && result.accepted) {
      this.getUIContext().animateTo({ duration: MOTION_PRESS, curve: Curve.EaseOut }, (): void => {
        this.applyReconciliation(result);
      });
    } else {
      this.applyReconciliation(result);
    }
    // Invalid edits remain in the page snapshot too; the controller alone decides what is persisted.
    this.onStateChange(stateId, nextValue);
    this.onContentGrow();
  }

  private hasRoot(): boolean {
    return this.document.rootId !== null;
  }

  private resetValues(): void {
    // Reset follows the same controller and page persistence path as a normal edit.
    const result = this.controller.reset(this.document, this.sourceKey);
    this.applyReconciliation(result);
    for (const item of result.values) this.onStateChange(item.id, item.value);
    this.onContentGrow();
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
      onNodeArea: (id: string, frame: NativeUiNodeFrame | null): void => { this.onNodeArea(id, frame); },
      onRevealTouch: (): void => { this.cancelReveal(); },
      onStateChange: (stateId: string, value: SmartUiScalar): void => { this.updateState(stateId, value); },
      onDraft: (nodeId: string): void => { this.onDraft(nodeId); },
      onAssetView: (ref: string): void => { this.onAssetView(ref); },
      onReset: (): void => { this.resetValues(); },
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
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 12 }) {
        ForEach([this.themeEpoch], (_epoch: number): void => { this.Content(); }, (epoch: number): string => String(epoch));
      }
      .width('100%');
      if (this.revealActive) {
        NativeUiGenerationOverlay({ frames: this.frames, active: this.revealActive, visible: this.cardVisible,
          onFinished: (): void => { this.finishReveal(); } })
          .width('100%').height('100%').position({ x: 0, y: 0 });
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: this.ready ? LINE : ACCENT })
    .borderRadius(16)
    .margin({ bottom: 8 })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.cancelReveal();
    })
    .onVisibleAreaChange([0, 1], (_expanding: boolean, ratio: number): void => {
      this.cardVisible = ratio > 0;
      if (!this.cardVisible) {
        if (this.revealActive) this.cancelReveal();
      } else if (this.pendingReveal) {
        this.requestRevealStart();
      }
    })
    .onAreaChange((before: Area, after: Area): void => {
      if (before.height !== after.height) this.onContentGrow();
    });
  }
}
