import type {
  ConversationArtifactIndex, ConversationArtifactSource, ConversationDocumentArtifact,
  ConversationImageArtifact, ConversationMiniAppArtifact, ConversationWidgetArtifact, UIMessagePartDocument,
} from '@amber/chat-domain';
import { common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { ACCENT, BG, BODY, INK, INK3, LABEL, LINE, META, SURFACE2 } from '../design/tokens.ets';
import { GenerativeWidgetCard } from './GenerativeWidgetCard.ets';
import { ImagePreviewDialog } from './ui/ImagePreviewDialog.ets';
import { saveWidgetArtifact } from '../platform_impl/WidgetArtifactExport.ets';

@Component
export struct ChatArtifactShelf {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop artifacts: ConversationArtifactIndex = { images: [], docs: [], miniApps: [], widgets: [], duplicateDocumentNames: [] };
  onDismiss: () => void = (): void => {};
  onOpenDocument: (part: UIMessagePartDocument) => void = (_part: UIMessagePartDocument): void => {};
  onOpenDiff: (name: string) => void = (_name: string): void => {};
  onRunMiniApp: (appId: string) => void = (_appId: string): void => {};
  onSource: (source: ConversationArtifactSource) => void = (_source: ConversationArtifactSource): void => {};
  onAction: (prompt: string) => void = (_prompt: string): void => {};
  @State selected: ConversationWidgetArtifact | null = null;
  @State imagePreview: string = '';
  @State exporting: boolean = false;
  @State exportError: string = '';
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void { this.alive = false; }

  private preview(artifact: ConversationWidgetArtifact): void {
    this.selected = artifact;
    this.exportError = '';
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
  private ArtifactList() {
    Scroll() {
      Column() {
        if (this.artifacts.images.length === 0 && this.artifacts.docs.length === 0 &&
          this.artifacts.miniApps.length === 0 && this.artifacts.widgets.length === 0) {
          Text('本会话还没有图片、文件、小应用或可视化产物')
            .fontSize(META).fontColor(INK3).width('100%').textAlign(TextAlign.Center).padding({ top: 48 })
        }
        if (this.artifacts.widgets.length > 0) {
          Text(`可视化 · ${this.artifacts.widgets.length}`).fontSize(LABEL).fontColor(INK3).width('100%')
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
          if (this.selected !== null) {
            Text('返回').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
              .padding({ right: 12 }).onClick((): void => { this.selected = null; })
          }
          Text(this.selected === null ? '产物架' : '可视化预览').fontSize(18).fontColor(INK).layoutWeight(1)
          Text('关闭').fontSize(META).fontColor(ACCENT).constraintSize({ minWidth: 44, minHeight: 44 })
            .padding({ left: 12 }).onClick((): void => { this.onDismiss(); })
        }.width('100%')
        if (this.selected !== null) {
          this.WidgetPreview()
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
