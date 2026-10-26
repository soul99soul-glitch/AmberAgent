// Assistant visible text only. User text/reasoning and the stored parts stay unchanged.
import type {
  GenerativeWidget, ResponseContentSegment, VisualArtifact, SmartUiAssetDiagnostic,
  SmartUiDocument, SmartUiScalar, SmartUiStateAccessor, SmartUiStateValue, SmartUiWeatherProjection,
} from '@amber/chat-domain';
import { parseResponseContent, applySvgValidation } from '@amber/chat-domain';
import { MarkdownText } from './MarkdownText.ets';
import type { MessageRowViewState } from './MessageRowViewState.ets';
import { GenerativeWidgetCard } from './GenerativeWidgetCard.ets';
import { VisualArtifactCard } from './VisualArtifactCard.ets';
import { NativeSmartUiCard } from './NativeSmartUiCard.ets';
import { INK, INK3, BODY, LABEL } from '../design/tokens.ets';

const EMPTY_ACCESSOR: SmartUiStateAccessor = {
  get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
  set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
};

interface SmartUiValueSnapshot {
  sourceKey: string;
  values: SmartUiStateValue[];
}

interface PreparedVisualAssetInput {
  type: string;
  id: string;
  closed: boolean;
  status: string;
  sourceStart: number;
  raw: string;
  content: string;
  snapshot: string | null;
}

interface PreparedResponseInput {
  content: string;
  streaming: boolean;
  messageStreaming: boolean;
  streamKey: string;
  smartUiSourcePrefix: string;
  smartUiNodeId: string;
  declaredContentIds: string[];
  assets: PreparedVisualAssetInput[];
}

@Component
export struct WidgetMessageContent {
  viewState: MessageRowViewState | null = null;
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop @Watch('prepare') content: string = '';
  @Prop @Watch('prepare') streaming: boolean = false;
  @Prop fontSize: number = BODY;
  @Prop fontColor: string = INK;
  @Prop chatFontFamily: string = 'DEFAULT';
  @Prop enableLatexRendering: boolean = true;
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  @Prop @Watch('prepare') streamKey: string = '';
  @Prop @Watch('prepare') assets: VisualArtifact[] = [];
  @Prop @Watch('prepare') declaredContentIds: string[] = [];
  @Prop @Watch('prepare') messageStreaming: boolean = false;
  @Prop @Watch('prepare') smartUiSourcePrefix: string = '';
  @Prop @Watch('prepare') smartUiNodeId: string = '';
  @Prop smartUiStateAccessor: SmartUiStateAccessor = EMPTY_ACCESSOR;
  @Prop @Watch('syncSmartUiValues') smartUiStateRevision: number = 0;
  @Prop smartUiProjections: SmartUiWeatherProjection[] = [];
  smartUiValuesFor: (sourceKey: string) => SmartUiStateValue[] = (_sourceKey: string): SmartUiStateValue[] => [];
  onVisibleGrow: () => void = (): void => {};
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  onSmartUiStateChange: (sourceKey: string, stateId: string, value: SmartUiScalar) => void =
    (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {};
  onSmartUiDraft: (nodeId: string, sourceKey: string) => void = (_nodeId: string, _sourceKey: string): void => {};
  onSmartUiAssetView: (ref: string, sourceStart: number) => void =
    (_ref: string, _sourceStart: number): void => {};
  onSmartUiSourceReplaced: (sourcePrefix: string) => void = (_sourcePrefix: string): void => {};
  onSmartUiDocument: (sourceKey: string, document: SmartUiDocument, streaming: boolean,
    sourceStart: number, blockId: string, complete: boolean) => void =
    (_sourceKey: string, _document: SmartUiDocument, _streaming: boolean, _sourceStart: number,
      _blockId: string, _complete: boolean): void => {};
  onSmartUiAssetArea: (ref: string, sourceStart: number, area: Area) => void =
    (_ref: string, _sourceStart: number, _area: Area): void => {};
  onArtifactValidation: (start: number, id: string, snapshot: string, ok: boolean, error: string) => void =
    (_start: number, _id: string, _snapshot: string, _ok: boolean, _error: string): void => {};
  @State segments: ResponseContentSegment[] = [];
  @State smartUiValueSnapshots: SmartUiValueSnapshot[] = [];
  @State revealSourcePrefix: string = '';
  private preparedInput: PreparedResponseInput | null = null;
  private previousSmartUiDefinitions: Map<string, string> = new Map<string, string>();

  aboutToAppear(): void {
    this.prepare();
  }

  private clearSmartUiDefinitions(sourcePrefix: string): void {
    if (sourcePrefix.length === 0) return;
    const prefix: string = `${sourcePrefix}:`;
    for (const key of this.previousSmartUiDefinitions.keys()) {
      if (key.startsWith(prefix)) this.previousSmartUiDefinitions.delete(key);
    }
  }

  private matchesPreparedInput(): boolean {
    const previous: PreparedResponseInput | null = this.preparedInput;
    if (previous === null || previous.content !== this.content || previous.streaming !== this.streaming ||
      previous.messageStreaming !== this.messageStreaming || previous.streamKey !== this.streamKey ||
      previous.smartUiSourcePrefix !== this.smartUiSourcePrefix || previous.smartUiNodeId !== this.smartUiNodeId) return false;
    const declaredIds: string[] = this.declaredContentIds;
    const assets: VisualArtifact[] = this.assets;
    if (previous.declaredContentIds.length !== declaredIds.length || previous.assets.length !== assets.length) return false;
    for (let index: number = 0; index < declaredIds.length; index++) {
      if (previous.declaredContentIds[index] !== declaredIds[index]) return false;
    }
    for (let index: number = 0; index < assets.length; index++) {
      const asset: VisualArtifact = assets[index];
      const prior: PreparedVisualAssetInput = previous.assets[index];
      if (prior.type !== asset.type || prior.id !== asset.id || prior.closed !== asset.closed ||
        prior.status !== asset.status || prior.sourceStart !== asset.sourceStart || prior.raw !== asset.raw ||
        prior.content !== asset.content || prior.snapshot !== asset.snapshot) return false;
    }
    return true;
  }

  private snapshotPreparedInput(): PreparedResponseInput {
    const assets: PreparedVisualAssetInput[] = [];
    const inputs: VisualArtifact[] = this.assets;
    for (const asset of inputs) {
      // scene is derived from canonical content; retaining its object identity would miss V1 copies.
      assets.push({ type: asset.type, id: asset.id, closed: asset.closed, status: asset.status,
        sourceStart: asset.sourceStart, raw: asset.raw, content: asset.content, snapshot: asset.snapshot });
    }
    return {
      content: this.content,
      streaming: this.streaming,
      messageStreaming: this.messageStreaming,
      streamKey: this.streamKey,
      smartUiSourcePrefix: this.smartUiSourcePrefix,
      smartUiNodeId: this.smartUiNodeId,
      declaredContentIds: this.declaredContentIds.slice(),
      assets,
    };
  }

  private prepare(): void {
    // V1 @Prop copies arrays when unrelated parent state changes, including 3D interaction ownership.
    if (this.matchesPreparedInput()) return;
    const input: PreparedResponseInput = this.snapshotPreparedInput();
    const previous: PreparedResponseInput | null = this.preparedInput;
    if (this.messageStreaming) this.revealSourcePrefix = this.smartUiSourcePrefix;
    if (previous !== null && previous.smartUiSourcePrefix.length > 0 &&
      previous.smartUiSourcePrefix !== this.smartUiSourcePrefix) {
      this.clearSmartUiDefinitions(previous.smartUiSourcePrefix);
      this.onSmartUiSourceReplaced(previous.smartUiSourcePrefix);
    } else if (previous !== null && previous.content.length > 0 && !this.content.startsWith(previous.content)) {
      this.clearSmartUiDefinitions(this.smartUiSourcePrefix);
      this.onSmartUiSourceReplaced(this.smartUiSourcePrefix);
    }
    this.preparedInput = input;
    let segments: ResponseContentSegment[] = parseResponseContent(this.content, this.streaming,
      this.assets.length > 0 ? this.assets : undefined, !this.messageStreaming,
      this.declaredContentIds.length > 0 ? this.declaredContentIds : undefined);
    for (const segment of segments) {
      if (segment.type !== 'svg' || segment.id === null) continue;
      const validation: VisualArtifact | undefined = this.assets.find((asset: VisualArtifact): boolean =>
        asset.type === 'svg' && asset.id === segment.id && asset.snapshot === segment.artifact?.snapshot &&
          (asset.status === 'ready' || asset.status === 'error'));
      if (validation !== undefined) {
        segments = applySvgValidation(segments, segment.sourceStart, segment.id,
          validation.snapshot ?? '', validation.status === 'ready', validation.status === 'error' ? 'SVG XML 结构无效' : '');
      }
    }
    for (const segment of segments) {
      if (segment.ui === null || segment.id === null) continue;
      const sourceKey: string = this.smartUiSourceKey(segment);
      const fingerprint: string = JSON.stringify({
        document: segment.ui, closed: segment.closed, complete: segment.complete,
        messageStreaming: this.messageStreaming, nodeId: this.smartUiNodeId,
      });
      if (this.previousSmartUiDefinitions.get(sourceKey) !== fingerprint) {
        this.previousSmartUiDefinitions.set(sourceKey, fingerprint);
        this.onSmartUiDocument(sourceKey, segment.ui, this.messageStreaming,
          segment.sourceStart, segment.id, segment.complete);
      }
    }
    this.segments = segments;
    this.syncSmartUiValues();
  }

  private validatedSvg(start: number, id: string, snapshot: string, ok: boolean, error: string): void {
    const current: ResponseContentSegment | undefined = this.segments.find(
      (segment: ResponseContentSegment): boolean => segment.sourceStart === start && segment.id === id);
    if (current?.artifact?.snapshot !== snapshot) return;
    this.onArtifactValidation(start, id, snapshot, ok, error);
  }

  private smartUiSourceKey(segment: ResponseContentSegment): string {
    return `${this.smartUiSourcePrefix}:${segment.sourceStart}:${segment.id ?? ''}`;
  }

  private syncSmartUiValues(): void {
    const snapshots: SmartUiValueSnapshot[] = [];
    for (const segment of this.segments) {
      if (segment.ui === null || segment.id === null) continue;
      const sourceKey: string = this.smartUiSourceKey(segment);
      snapshots.push({ sourceKey, values: this.smartUiValuesFor(sourceKey).slice() });
    }
    this.smartUiValueSnapshots = snapshots;
  }

  private smartUiValuesSnapshotFor(sourceKey: string): SmartUiStateValue[] {
    const snapshot: SmartUiValueSnapshot | undefined = this.smartUiValueSnapshots.find(
      (item: SmartUiValueSnapshot): boolean => item.sourceKey === sourceKey);
    return snapshot?.values ?? [];
  }

  private smartUiDocument(segment: ResponseContentSegment): SmartUiDocument {
    return segment.ui as SmartUiDocument;
  }

  private smartUiSourceKeyAt(index: number): string {
    const segment: ResponseContentSegment | undefined = this.segments[index];
    return segment === undefined ? '' : this.smartUiSourceKey(segment);
  }

  private smartUiStateChangedAt(index: number, stateId: string, value: SmartUiScalar): void {
    const sourceKey: string = this.smartUiSourceKeyAt(index);
    if (sourceKey.length > 0) this.onSmartUiStateChange(sourceKey, stateId, value);
  }

  private smartUiAssetViewAt(index: number, ref: string): void {
    const segment: ResponseContentSegment | undefined = this.segments[index];
    if (segment !== undefined) this.onSmartUiAssetView(ref, segment.sourceStart);
  }

  private smartUiAssetAreaAt(index: number, ref: string, area: Area): void {
    const segment: ResponseContentSegment | undefined = this.segments[index];
    if (segment !== undefined) this.onSmartUiAssetArea(ref, segment.sourceStart, area);
  }

  private svgValidationAt(index: number, snapshot: string, ok: boolean, error: string): void {
    const segment: ResponseContentSegment | undefined = this.segments[index];
    if (segment !== undefined) this.validatedSvg(segment.sourceStart, segment.id ?? '', snapshot, ok, error);
  }

  private assetDiagnostics(segment: ResponseContentSegment): SmartUiAssetDiagnostic[] {
    const result: SmartUiAssetDiagnostic[] = [];
    for (const item of segment.assetDiagnostics) {
      if (item.ref === undefined) continue;
      result.push({ ref: item.ref, message: item.message, severity: item.severity });
    }
    return result;
  }

  private segmentAt(index: number): ResponseContentSegment | null {
    return this.segments[index] ?? null;
  }

  private segmentTypeAt(index: number): string {
    return this.segmentAt(index)?.type ?? '';
  }

  private segmentContentAt(index: number): string {
    return this.segmentAt(index)?.content ?? '';
  }

  private segmentWidgetAt(index: number): GenerativeWidget | null {
    return this.segmentAt(index)?.widget ?? null;
  }

  private segmentArtifactAt(index: number): VisualArtifact | null {
    return this.segmentAt(index)?.artifact ?? null;
  }

  private segmentDocumentAt(index: number): SmartUiDocument | null {
    return this.segmentAt(index)?.ui ?? null;
  }

  private segmentAssetDiagnosticsAt(index: number): SmartUiAssetDiagnostic[] {
    const segment: ResponseContentSegment | null = this.segmentAt(index);
    return segment === null ? [] : this.assetDiagnostics(segment);
  }

  private segmentReadyAt(index: number): boolean {
    return this.segmentAt(index)?.draftEnabled ?? false;
  }

  private revealEligible(): boolean {
    return !this.messageStreaming && this.smartUiSourcePrefix.length > 0 &&
      this.revealSourcePrefix === this.smartUiSourcePrefix;
  }

  private segmentDiagnosticAt(index: number): string {
    return this.segmentAt(index)?.diagnostics[0]?.message ?? '内容未完整生成';
  }

  @Builder
  private TextSegment(index: number): void {
    MarkdownText({ viewState: this.viewState, content: this.segmentContentAt(index), fontSize: this.fontSize, fontColor: this.fontColor,
      streaming: this.streaming && index === this.segments.length - 1,
      chatFontFamily: this.chatFontFamily, enableLatexRendering: this.enableLatexRendering,
      codeBlockAutoCollapse: this.codeBlockAutoCollapse, showLineNumbers: this.showLineNumbers,
      streamKey: `${this.streamKey}#widget-text-${this.segmentAt(index)?.sourceStart ?? 0}`,
      onVisibleGrow: (): void => { this.onVisibleGrow(); } });
  }

  @Builder
  private LegacyWidgetSegment(index: number): void {
    if (this.segmentWidgetAt(index) !== null) {
      GenerativeWidgetCard({ widget: this.segmentWidgetAt(index) as GenerativeWidget,
        revealKey: `widget:${this.smartUiSourceKeyAt(index)}`, revealEligible: this.revealEligible(),
        onAction: (prompt: string): void => { this.onAction(prompt); },
        onContentGrow: (): void => { this.onVisibleGrow(); } });
    }
  }

  @Builder
  private SmartUiSegment(index: number): void {
    NativeSmartUiCard({
      document: this.segmentDocumentAt(index) as SmartUiDocument,
      initialValues: this.smartUiValuesSnapshotFor(this.smartUiSourceKeyAt(index)),
      sourceKey: this.smartUiSourceKeyAt(index),
      stateAccessor: this.smartUiStateAccessor,
      ready: this.segmentReadyAt(index),
      revealEligible: this.revealEligible(),
      streaming: this.messageStreaming,
      projections: this.smartUiProjections,
      fontSize: this.fontSize,
      fontFamily: this.chatFontFamily,
      validationDiagnostics: this.segmentAssetDiagnosticsAt(index),
      onStateChange: (stateId: string, value: SmartUiScalar): void => {
        this.smartUiStateChangedAt(index, stateId, value);
      },
      onDraft: (nodeId: string): void => { this.onSmartUiDraft(nodeId, this.smartUiSourceKeyAt(index)); },
      onAssetView: (ref: string): void => {
        this.smartUiAssetViewAt(index, ref);
      },
      onContentGrow: (): void => { this.onVisibleGrow(); },
    });
  }

  @Builder
  private ArtifactSegment(index: number): void {
    if (this.segmentArtifactAt(index) !== null) {
      VisualArtifactCard({ artifact: this.segmentArtifactAt(index) as VisualArtifact,
        revealKey: `widget:${this.smartUiSourceKeyAt(index)}`, revealEligible: this.revealEligible(),
        streaming: this.messageStreaming, onContentGrow: (): void => { this.onVisibleGrow(); },
        onArtifactArea: (ref: string, area: Area): void => {
          this.smartUiAssetAreaAt(index, ref, area);
        },
        onValidation: (snapshot: string, ok: boolean, error: string): void => {
          this.svgValidationAt(index, snapshot, ok, error);
        } });
    }
  }

  @Builder
  private FallbackSegment(index: number): void {
    Text(this.segmentAt(index)?.status === 'partial' && this.messageStreaming ? '正在生成内容…' :
      this.segmentDiagnosticAt(index))
      .fontSize(LABEL).fontColor(INK3).width('100%').padding({ top: 8, bottom: 8 });
  }

  @Builder
  private ResponseSegment(index: number): void {
    if (this.segmentTypeAt(index) === 'text') {
      this.TextSegment(index);
    } else if (this.segmentTypeAt(index) === 'widget' && this.segmentWidgetAt(index) !== null) {
      this.LegacyWidgetSegment(index);
    } else if (this.segmentDocumentAt(index) !== null) {
      this.SmartUiSegment(index);
      if (this.segmentAt(index)?.status === 'error') this.FallbackSegment(index);
    } else if (this.segmentArtifactAt(index) !== null) {
      this.ArtifactSegment(index);
      if (this.segmentAt(index)?.status === 'error') this.FallbackSegment(index);
    } else {
      this.FallbackSegment(index);
    }
  }

  build(): void {
    Column() {
      ForEach(this.segments, (segment: ResponseContentSegment, index: number): void => {
        this.ResponseSegment(index);
      }, (segment: ResponseContentSegment): string => `${segment.type}-${segment.sourceStart}-${segment.id ?? ''}`)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').alignItems(HorizontalAlign.Start)
  }
}
