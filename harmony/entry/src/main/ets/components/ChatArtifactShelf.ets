import type {
  ConversationArtifactIndex, ConversationArtifactSource, ConversationDocumentArtifact,
  ConversationImageArtifact, ConversationVideoArtifact, ConversationMiniAppArtifact, ConversationWidgetArtifact, ConversationSvgArtifact,
  ConversationSceneArtifact, ConversationUiSnapshotArtifact, ConversationSvgValidationProof,
  ConversationDeclaredArtifactSource, UIMessagePartDocument,
} from '@amber/chat-domain';
import { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { ACCENT, BG, BODY, INK, INK3, LABEL, LINE, META, SURFACE2 } from '../design/tokens.ets';
import { GenerativeWidgetCard } from './GenerativeWidgetCard.ets';
import { VisualArtifactPreview } from './VisualArtifactPreview.ets';
import { ImagePreviewDialog } from './ui/ImagePreviewDialog.ets';
import { urlBasename } from './ContentAttachmentChip.ets';
import { saveWidgetArtifact } from '../platform_impl/WidgetArtifactExport.ets';
import {
  saveSceneCoverArtifact, saveSceneJsonArtifact, saveSvgArtifact, saveUiSnapshotArtifact,
} from '../platform_impl/VisualArtifactExport.ets';

@Component
export struct ChatArtifactShelf {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop artifacts: ConversationArtifactIndex = {
    images: [], videos: [], docs: [], miniApps: [], widgets: [], svg: [], scenes: [], uiSnapshots: [], duplicateDocumentNames: [],
  };
  onDismiss: () => void = (): void => {};
  onOpenDocument: (part: UIMessagePartDocument) => void = (_part: UIMessagePartDocument): void => {};
  onOpenDiff: (name: string) => void = (_name: string): void => {};
  onRunMiniApp: (appId: string) => void = (_appId: string): void => {};
  onSource: (source: ConversationArtifactSource) => void = (_source: ConversationArtifactSource): void => {};
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  @State selected: ConversationWidgetArtifact | null = null;
  @State selectedVideo: ConversationVideoArtifact | null = null;
  @State videoFailed: boolean = false;
  @State selectedSvg: ConversationSvgArtifact | null = null;
  @State selectedScene: ConversationSceneArtifact | null = null;
  @State selectedUiSnapshot: ConversationUiSnapshotArtifact | null = null;
  @State imagePreview: string = '';
  @State exporting: boolean = false;
  @State exportError: string = '';
  @State svgValidationSource: string = '';
  @State svgValidationSnapshot: string = '';
  @State svgValidationCanonical: string = '';
  @State svgValidationOk: boolean = false;
  @State svgSelectionEpoch: number = 0;
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; }

  private sourceKey(source: ConversationArtifactSource): string {
    return `${source.conversationId}/${source.nodeId}/${source.messageId}`;
  }

  private declaredSourceKey(source: ConversationDeclaredArtifactSource): string {
    return `${this.sourceKey(source)}/${source.blockIndex}/${source.sourceStart}/${source.blockId}/${source.protocol}/v${source.version}`;
  }

  private declaredArtifactKey(id: string, source: ConversationDeclaredArtifactSource): string {
    return `${id}:${this.declaredSourceKey(source)}`;
  }

  private clearSelection(): void {
    this.selected = null;
    this.selectedVideo = null;
    this.videoFailed = false;
    this.selectedSvg = null;
    this.selectedScene = null;
    this.selectedUiSnapshot = null;
    this.svgValidationSource = '';
    this.svgValidationSnapshot = '';
    this.svgValidationCanonical = '';
    this.svgValidationOk = false;
    this.svgSelectionEpoch += 1;
    this.exportError = '';
  }

  private hasSelection(): boolean {
    return this.selected !== null || this.selectedVideo !== null || this.selectedSvg !== null ||
      this.selectedScene !== null || this.selectedUiSnapshot !== null;
  }

  private preview(artifact: ConversationWidgetArtifact): void {
    this.clearSelection();
    this.selected = artifact;
  }

  private previewVideo(artifact: ConversationVideoArtifact): void {
    this.clearSelection();
    this.videoFailed = false;
    this.selectedVideo = artifact;
  }

  private previewSvg(artifact: ConversationSvgArtifact): void {
    this.clearSelection();
    this.selectedSvg = artifact;
    this.svgValidationSource = this.declaredSourceKey(artifact.source);
    this.svgValidationSnapshot = artifact.svg;
  }

  private previewScene(artifact: ConversationSceneArtifact): void {
    this.clearSelection();
    this.selectedScene = artifact;
  }

  private previewUiSnapshot(artifact: ConversationUiSnapshotArtifact): void {
    this.clearSelection();
    this.selectedUiSnapshot = artifact;
  }

  private onSvgValidation(
    source: ConversationDeclaredArtifactSource,
    snapshot: string,
    epoch: number,
    ok: boolean,
    error: string,
    canonicalSvg: string = '',
  ): void {
    if (this.selectedSvg === null || epoch !== this.svgSelectionEpoch ||
      this.declaredSourceKey(this.selectedSvg.source) !== this.declaredSourceKey(source) ||
      this.selectedSvg.svg !== snapshot) return;
    const canonicalValid: boolean = canonicalSvg.length > 0 && canonicalSvg.length <= 120000;
    this.svgValidationOk = ok && canonicalValid;
    this.svgValidationSnapshot = snapshot;
    this.svgValidationCanonical = canonicalValid ? canonicalSvg : '';
    if (ok && canonicalValid) {
      this.selectedSvg = {
        id: this.selectedSvg.id,
        protocol: this.selectedSvg.protocol,
        version: this.selectedSvg.version,
        status: 'ready',
        source: this.selectedSvg.source,
        svg: this.selectedSvg.svg,
        validation: 'domparser',
      };
    }
    if (!ok || !canonicalValid) {
      this.exportError = error.length > 0 ? `SVG 校验失败：${error}` : 'SVG 校验结果缺少完整内容';
    }
  }

  private svgProof(): ConversationSvgValidationProof | null {
    if (this.selectedSvg === null || !this.svgValidationOk || this.selectedSvg.status !== 'ready' ||
      this.svgValidationSource !== this.declaredSourceKey(this.selectedSvg.source) ||
      this.svgValidationSnapshot !== this.selectedSvg.svg || this.svgValidationCanonical.length === 0 ||
      this.svgValidationCanonical.length > 120000) return null;
    return {
      source: this.selectedSvg.source,
      snapshot: this.selectedSvg.svg,
      validator: 'domparser',
      ok: true,
      canonicalSvg: this.svgValidationCanonical,
    };
  }

  private svgExportReady(): boolean {
    return this.svgProof() !== null;
  }

  private async exportSvg(): Promise<void> {
    const artifact: ConversationSvgArtifact | null = this.selectedSvg;
    const proof: ConversationSvgValidationProof | null = this.svgProof();
    if (artifact === null || proof === null || this.exporting ||
      this.svgValidationSource !== this.declaredSourceKey(artifact.source)) return;
    this.exporting = true;
    this.exportError = '';
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const saved: boolean = await saveSvgArtifact(context, artifact, proof);
      if (this.alive && saved) promptAction.showToast({ message: 'SVG 已保存' });
    } catch (error) {
      if (this.alive && this.selectedSvg?.id === artifact.id) this.exportError = `导出失败：${(error as Error).message}`;
    } finally {
      if (this.alive) this.exporting = false;
    }
  }

  private async exportSceneJson(): Promise<void> {
    const artifact: ConversationSceneArtifact | null = this.selectedScene;
    if (artifact === null || this.exporting) return;
    this.exporting = true;
    this.exportError = '';
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const saved: boolean = await saveSceneJsonArtifact(context, artifact);
      if (this.alive && saved) promptAction.showToast({ message: '三维场景 JSON 已保存' });
    } catch (error) {
      if (this.alive && this.selectedScene?.id === artifact.id) this.exportError = `导出失败：${(error as Error).message}`;
    } finally {
      if (this.alive) this.exporting = false;
    }
  }

  private async exportSceneCover(): Promise<void> {
    const artifact: ConversationSceneArtifact | null = this.selectedScene;
    if (artifact === null || this.exporting) return;
    this.exporting = true;
    this.exportError = '';
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const saved: boolean = await saveSceneCoverArtifact(context, artifact);
      if (this.alive && saved) promptAction.showToast({ message: '三维场景封面已保存' });
    } catch (error) {
      if (this.alive && this.selectedScene?.id === artifact.id) this.exportError = `导出失败：${(error as Error).message}`;
    } finally {
      if (this.alive) this.exporting = false;
    }
  }

  private async exportUiSnapshot(): Promise<void> {
    const artifact: ConversationUiSnapshotArtifact | null = this.selectedUiSnapshot;
    if (artifact === null || this.exporting) return;
    this.exporting = true;
    this.exportError = '';
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const saved: boolean = await saveUiSnapshotArtifact(context, artifact);
      if (this.alive && saved) promptAction.showToast({ message: 'UI 静态快照已保存' });
    } catch (error) {
      if (this.alive && this.selectedUiSnapshot?.id === artifact.id) this.exportError = `导出失败：${(error as Error).message}`;
    } finally {
      if (this.alive) this.exporting = false;
    }
  }

  private async exportSelected(): Promise<void> {
    const artifact: ConversationWidgetArtifact | null = this.selected;
    if (artifact === null || this.exporting) return;
    this.exporting = true;
    this.exportError = '';
    try {
      const context: common.UIAbilityContext = this.getUIContext().getHostContext() as common.UIAbilityContext;
      const saved: boolean = await saveWidgetArtifact(context, artifact, this.darkMode);
      if (this.alive && saved) promptAction.showToast({ message: 'HTML 已保存' });
    } catch (error) {
      if (this.alive && this.selected?.id === artifact.id) this.exportError = `导出失败：${(error as Error).message}`;
    } finally {
      if (this.alive) this.exporting = false;
    }
  }

  private uiDefinitionText(): string {
    return this.selectedUiSnapshot === null ? '' : JSON.stringify(this.selectedUiSnapshot.definition, null, 2);
  }

  @Builder
  private WidgetPreview() {
    if (this.selected !== null) {
      Column({ space: 12 }) {
        Scroll() {
          Column({ space: 12 }) {
            Text(this.selected.widget.title).fontSize(BODY).fontColor(INK).width('100%').maxLines(3)
            GenerativeWidgetCard({ widget: this.selected.widget,
              onAction: (prompt: string): void => { this.onAction(prompt); } })
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
        Row({ space: 12 }) {
          Text('来源消息').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .onClick((): void => { if (this.selected !== null) this.onSource(this.selected.source); })
          Text(this.exporting ? '正在导出…' : '导出 HTML').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .enabled(!this.exporting).opacity(this.exporting ? 0.6 : 1)
            .onClick((): void => { this.exportSelected(); })
        }.width('100%')
        if (this.exportError.length > 0) {
          Text(this.exportError).fontSize(LABEL).fontColor(INK3).width('100%')
        }
      }.layoutWeight(1).width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private SvgPreviewHost(
    epoch: number,
    source: ConversationDeclaredArtifactSource,
    snapshot: string,
  ) {
    VisualArtifactPreview({ html: snapshot, renderer: 'svg', scene: null,
      complete: this.selectedSvg !== null && this.selectedSvg.status === 'ready',
      strictSvg: true,
      onValidation: (ok: boolean, error: string, canonicalSvg?: string): void => {
        this.onSvgValidation(source, snapshot, epoch, ok, error, canonicalSvg ?? '');
      } }).layoutWeight(1).width('100%')
  }

  @Builder
  private SvgPreview() {
    if (this.selectedSvg !== null) {
      Column({ space: 12 }) {
        ForEach([this.svgSelectionEpoch], (epoch: number): void => {
          if (this.selectedSvg !== null) {
            this.SvgPreviewHost(epoch, this.selectedSvg.source, this.selectedSvg.svg)
          }
        }, (epoch: number): string => `svg-preview-${epoch}`)
        Row({ space: 12 }) {
          Text('来源消息').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .onClick((): void => { if (this.selectedSvg !== null) this.onSource(this.selectedSvg.source); })
          Text(this.exporting ? '正在导出…' : this.svgExportReady() ? '导出 SVG' : '等待 SVG 校验')
            .fontSize(META).fontColor(this.svgExportReady() ? ACCENT : INK3)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .enabled(this.svgExportReady() && !this.exporting).opacity(this.svgExportReady() && !this.exporting ? 1 : 0.6)
            .onClick((): void => { this.exportSvg(); })
        }.width('100%')
        if (this.exportError.length > 0) {
          Text(this.exportError).fontSize(LABEL).fontColor(INK3).width('100%')
        }
      }.layoutWeight(1).width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private ScenePreview() {
    if (this.selectedScene !== null) {
      Column({ space: 12 }) {
        VisualArtifactPreview({ html: '', renderer: 'threejs', scene: this.selectedScene.scene, complete: true })
          .layoutWeight(1).width('100%')
        Text('三维场景可导出为声明式 JSON 或静态 SVG 封面，不包含可执行脚本。')
          .fontSize(LABEL).fontColor(INK3).width('100%')
        Row({ space: 12 }) {
          Text('来源消息').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .onClick((): void => { if (this.selectedScene !== null) this.onSource(this.selectedScene.source); })
          Text(this.exporting ? '正在导出…' : '导出 JSON').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .enabled(!this.exporting).onClick((): void => { this.exportSceneJson(); })
          Text(this.exporting ? '正在导出…' : '导出封面').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .enabled(!this.exporting).onClick((): void => { this.exportSceneCover(); })
        }.width('100%')
        if (this.exportError.length > 0) {
          Text(this.exportError).fontSize(LABEL).fontColor(INK3).width('100%')
        }
      }.layoutWeight(1).width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private UiSnapshotPreview() {
    if (this.selectedUiSnapshot !== null) {
      Column({ space: 12 }) {
        Scroll() {
          Column({ space: 12 }) {
            Text('界面定义 · 不含当前输入与选择 · 离线不可交互').fontSize(META).fontColor(INK3).width('100%')
            Text(this.selectedUiSnapshot.summary).fontSize(BODY).fontColor(INK).width('100%').lineHeight(22)
            Text('定义').fontSize(META).fontColor(ACCENT).width('100%')
            Text(this.uiDefinitionText()).fontSize(LABEL).fontColor(INK).fontFamily('monospace')
              .width('100%').lineHeight(18)
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
        Row({ space: 12 }) {
          Text('来源消息').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .onClick((): void => { if (this.selectedUiSnapshot !== null) this.onSource(this.selectedUiSnapshot.source); })
          Text(this.exporting ? '正在导出…' : '导出静态快照').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .enabled(!this.exporting).onClick((): void => { this.exportUiSnapshot(); })
        }.width('100%')
        if (this.exportError.length > 0) {
          Text(this.exportError).fontSize(LABEL).fontColor(INK3).width('100%')
        }
      }.layoutWeight(1).width('100%').alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private VideoPreview(): void {
    if (this.selectedVideo !== null) {
      Column({ space: 12 }) {
        if (this.videoFailed) {
          Text('这段视频无法播放，可能已失效或不是视频文件').width('100%')
            .fontSize(META).fontColor(INK3).padding(16).textAlign(TextAlign.Center)
            .backgroundColor(SURFACE2).borderRadius(12)
        } else {
          Video({ src: this.selectedVideo.url }).controls(true).autoPlay(false).loop(false)
            .width('100%').layoutWeight(1).constraintSize({ maxHeight: 240 })
            .objectFit(ImageFit.Contain).backgroundColor(Color.Black).borderRadius(12)
            .onError((): void => { this.videoFailed = true; })
        }
        Row({ space: 12 }) {
          Text(this.selectedVideo.mime).fontSize(META).fontColor(INK3).layoutWeight(1)
          Text('来源消息').fontSize(META).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 12, right: 12 })
            .onClick((): void => { if (this.selectedVideo !== null) this.onSource(this.selectedVideo.source); })
        }.width('100%')
      }.width('100%').layoutWeight(1).alignItems(HorizontalAlign.Start)
    }
  }

  @Builder
  private ArtifactList() {
    Scroll() {
      Column() {
        if (this.artifacts.images.length === 0 && this.artifacts.videos.length === 0 && this.artifacts.docs.length === 0 &&
          this.artifacts.miniApps.length === 0 && this.artifacts.widgets.length === 0 &&
          this.artifacts.svg.length === 0 && this.artifacts.scenes.length === 0 &&
          this.artifacts.uiSnapshots.length === 0) {
          Text('本会话还没有图片、视频、文件、小应用或可视化产物')
            .fontSize(META).fontColor(INK3).width('100%').textAlign(TextAlign.Center).padding({ top: 48 })
        }
        if (this.artifacts.widgets.length > 0) {
          Text(`旧可视化 HTML · ${this.artifacts.widgets.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 8, bottom: 8 })
          ForEach(this.artifacts.widgets, (artifact: ConversationWidgetArtifact): void => {
            Column() {
              Row({ space: 8 }) {
                Text(artifact.widget.title).fontSize(BODY).fontColor(INK).layoutWeight(1)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text('预览').fontSize(META).fontColor(ACCENT)
                  .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                  .onClick((): void => { this.preview(artifact); })
              }.width('100%').constraintSize({ minHeight: 44 })
              Row() {
                Text(artifact.widget.renderer === 'vchart' ? '图表' : artifact.widget.renderer === 'slides' ? '幻灯片' : 'HTML')
                  .fontSize(LABEL).fontColor(INK3).layoutWeight(1)
                Text('来源消息').fontSize(LABEL).fontColor(ACCENT)
                  .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                  .onClick((): void => { this.onSource(artifact.source); })
              }.width('100%')
            }.width('100%').padding({ left: 12, right: 4, top: 8, bottom: 4 })
              .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE }).margin({ bottom: 8 })
          }, (artifact: ConversationWidgetArtifact): string => artifact.id)
        }
        if (this.artifacts.svg.length > 0) {
          Text(`SVG · ${this.artifacts.svg.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.svg, (artifact: ConversationSvgArtifact): void => {
            Row({ space: 8 }) {
              Text(artifact.id).fontSize(BODY).fontColor(INK).layoutWeight(1)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('预览').fontSize(META).fontColor(ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                .onClick((): void => { this.previewSvg(artifact); })
            }.width('100%').constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 4, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE }).margin({ bottom: 8 })
          }, (artifact: ConversationSvgArtifact): string => this.declaredArtifactKey(artifact.id, artifact.source))
        }
        if (this.artifacts.scenes.length > 0) {
          Text(`三维场景 · ${this.artifacts.scenes.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.scenes, (artifact: ConversationSceneArtifact): void => {
            Row({ space: 8 }) {
              Text(artifact.id).fontSize(BODY).fontColor(INK).layoutWeight(1)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('预览').fontSize(META).fontColor(ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                .onClick((): void => { this.previewScene(artifact); })
            }.width('100%').constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 4, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE }).margin({ bottom: 8 })
          }, (artifact: ConversationSceneArtifact): string => this.declaredArtifactKey(artifact.id, artifact.source))
        }
        if (this.artifacts.uiSnapshots.length > 0) {
          Text(`智能 UI 静态快照 · ${this.artifacts.uiSnapshots.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.uiSnapshots, (artifact: ConversationUiSnapshotArtifact): void => {
            Row({ space: 8 }) {
              Text(artifact.summary).fontSize(BODY).fontColor(INK).layoutWeight(1)
                .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('预览').fontSize(META).fontColor(ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                .onClick((): void => { this.previewUiSnapshot(artifact); })
            }.width('100%').constraintSize({ minHeight: 44 })
              .padding({ left: 12, right: 4, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE }).margin({ bottom: 8 })
          }, (artifact: ConversationUiSnapshotArtifact): string => this.declaredArtifactKey(artifact.id, artifact.source))
        }
        if (this.artifacts.videos.length > 0) {
          Text(`视频 · ${this.artifacts.videos.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.videos, (artifact: ConversationVideoArtifact): void => {
            Row({ space: 10 }) {
              Text('▶').fontSize(16).fontColor(ACCENT).width(28).textAlign(TextAlign.Center)
              Column({ space: 2 }) {
                Text(urlBasename(artifact.url).length > 0 ? urlBasename(artifact.url) : '视频').fontSize(BODY).fontColor(INK).width('100%')
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(artifact.mime).fontSize(LABEL).fontColor(INK3).width('100%').maxLines(1)
              }.layoutWeight(1).alignItems(HorizontalAlign.Start)
              Text('播放').fontSize(META).fontColor(ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).padding({ left: 8, right: 8 })
                .onClick((): void => { this.previewVideo(artifact); })
            }.width('100%').height(64).padding({ left: 12, right: 4, top: 6, bottom: 6 })
              .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE }).margin({ bottom: 8 })
          }, (artifact: ConversationVideoArtifact): string => artifact.id)
        }
        if (this.artifacts.images.length > 0) {
          Text(`图片 · ${this.artifacts.images.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 8, bottom: 8 })
          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(this.artifacts.images, (artifact: ConversationImageArtifact): void => {
              Image(artifact.url).width(96).height(96).objectFit(ImageFit.Cover).borderRadius(8)
                .margin({ right: 8, bottom: 8 }).onClick((): void => { this.imagePreview = artifact.url; })
            }, (artifact: ConversationImageArtifact): string => artifact.id)
          }.width('100%')
        }
        if (this.artifacts.docs.length > 0) {
          Text(`文件 · ${this.artifacts.docs.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.duplicateDocumentNames, (name: string): void => {
            Row() {
              Text(`「${name}」有多个版本`).fontSize(META).fontColor(INK3).layoutWeight(1)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('对比').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
                .padding({ left: 12, right: 8 })
            }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.onOpenDiff(name); })
          }, (name: string): string => `dup-${name}`)
          ForEach(this.artifacts.docs, (artifact: ConversationDocumentArtifact, index: number): void => {
            Row() {
              Text(artifact.part.fileName.length > 0 ? artifact.part.fileName : `文档 ${index + 1}`)
                .fontSize(BODY).fontColor(INK).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('预览').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
                .padding({ left: 12, right: 8 })
            }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.onOpenDocument(artifact.part); })
          }, (artifact: ConversationDocumentArtifact): string => artifact.id)
        }
        if (this.artifacts.miniApps.length > 0) {
          Text(`小应用 · ${this.artifacts.miniApps.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
            .padding({ top: 12, bottom: 8 })
          ForEach(this.artifacts.miniApps, (artifact: ConversationMiniAppArtifact): void => {
            Row() {
              Text(artifact.title).fontSize(BODY).fontColor(INK).layoutWeight(1)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
              Text('运行').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
                .padding({ left: 12, right: 8 })
            }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.onRunMiniApp(artifact.appId); })
          }, (artifact: ConversationMiniAppArtifact): string => artifact.id)
        }
      }.width('100%').alignItems(HorizontalAlign.Start)
    }.layoutWeight(1).width('100%').align(Alignment.TopStart)
  }

  build(): void {
    Stack({ alignContent: Alignment.TopStart }) {
      Column({ space: 12 }) {
        Row() {
          if (this.hasSelection()) {
            Text('返回').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
              .padding({ right: 12 }).onClick((): void => { this.clearSelection(); })
          }
          Text(this.hasSelection() ? '产物预览' : '产物架').fontSize(18).fontColor(INK).layoutWeight(1)
          Text('关闭').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
            .padding({ left: 12 }).onClick((): void => { this.onDismiss(); })
        }.width('100%')
        if (this.selected !== null) {
          this.WidgetPreview()
        } else if (this.selectedVideo !== null) {
          this.VideoPreview()
        } else if (this.selectedSvg !== null) {
          this.SvgPreview()
        } else if (this.selectedScene !== null) {
          this.ScenePreview()
        } else if (this.selectedUiSnapshot !== null) {
          this.UiSnapshotPreview()
        } else {
          this.ArtifactList()
        }
      }.width('100%').height('100%')
        .padding({ left: 20, right: 20, top: 12, bottom: 20 + this.navBarHeight }).backgroundColor(BG)
      if (this.imagePreview.length > 0) {
        ImagePreviewDialog({ images: [this.imagePreview], onDismiss: (): void => { this.imagePreview = ''; } })
      }
    }.width('100%').height('100%')
  }
}
