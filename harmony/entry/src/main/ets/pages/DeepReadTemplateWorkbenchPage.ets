import { router } from '@kit.ArkUI';
import { DeepReadTemplateWorkbench, DeepReadTemplateEditorPort } from '../components/deepread/DeepReadTemplateWorkbench.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';

interface TemplateEditorParams { templateId?: string; mode?: string; preview?: boolean; readerFontMode?: string; readerFontScale?: number; }

@Entry
@Component
struct DeepReadTemplateWorkbenchPage {
  @State templateId: string = '';
  @State editorMode: string = 'editor';
  @State preview: boolean = false;
  @State previewFontMode: string = '';
  @State previewFontScale: number = 0;
  @State ready: boolean = false;
  @State sheetOpen: boolean = false;
  @State sheetSession: number = 0;
  @State draftName: string = '';
  @State draftHtml: string = '';
  @State lifecycleId: number = 0;
  private editorPort: DeepReadTemplateEditorPort = new DeepReadTemplateEditorPort();
  private sheetPort: DeepReadTemplateEditorPort = new DeepReadTemplateEditorPort();
  private alive: boolean = false;
  private sheetCloseApproved: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.lifecycleId++;
    const params: TemplateEditorParams = (router.getParams() as TemplateEditorParams) ?? {};
    this.templateId = params.templateId ?? '';
    this.editorMode = params.mode === 'generate' ? 'generate' : 'editor';
    this.preview = params.preview === true;
    this.previewFontMode = params.readerFontMode === 'default' || params.readerFontMode === 'serif' ? params.readerFontMode : '';
    const percent: number = params.readerFontScale ?? 0;
    this.previewFontScale = Number.isFinite(percent) && percent >= 70 && percent <= 180 ? percent : 0;
    this.ready = true;
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.lifecycleId++;
    this.sheetSession++;
    this.sheetOpen = false;
  }

  onBackPress(): boolean {
    if (!this.sheetOpen) return this.editorPort.back();
    const session: number = this.sheetSession;
    this.sheetPort.leave((): void => { this.closeSheet(session); });
    return true;
  }

  private generatedDraft(lifecycle: number, name: string, html: string): void {
    if (!this.alive || lifecycle !== this.lifecycleId || this.sheetOpen || this.editorMode !== 'generate') return;
    this.sheetPort = new DeepReadTemplateEditorPort();
    this.sheetCloseApproved = false;
    this.draftName = name;
    this.draftHtml = html;
    this.sheetSession++;
    this.sheetOpen = true;
  }

  private closeSheet(session: number): void {
    if (!this.alive || session !== this.sheetSession) return;
    this.sheetCloseApproved = true;
    this.sheetOpen = false;
  }

  private sheetDisappeared(session: number): void {
    if (!this.alive || session !== this.sheetSession) return;
    this.sheetOpen = false;
    this.draftName = '';
    this.draftHtml = '';
  }

  private editorSheetOptions(session: number, port: DeepReadTemplateEditorPort): SheetOptions {
    return {
      height: '92%', showClose: false, backgroundColor: D.paper,
      onDisappear: (): void => { this.sheetDisappeared(session); },
      onWillDismiss: (action: DismissSheetAction): void => {
        if (!this.alive || session !== this.sheetSession || this.sheetCloseApproved) {
          action.dismiss();
          return;
        }
        port.leave((): void => { action.dismiss(); });
      },
    };
  }

  build(): void {
    Column() {
      if (this.ready) {
        this.WorkbenchRoute(this.lifecycleId)
      }
    }.width('100%').height('100%')
      .bindSheet($$this.sheetOpen, this.GeneratedEditor(this.sheetSession, this.sheetPort),
        this.editorSheetOptions(this.sheetSession, this.sheetPort))
  }

  @Builder
  WorkbenchRoute(lifecycle: number): void {
    DeepReadTemplateWorkbench({ templateId: this.templateId, initialMode: this.editorMode,
      initialPreview: this.preview, editorPort: this.editorPort,
      previewFontMode: this.previewFontMode, previewFontScale: this.previewFontScale,
      presentGeneratedDraft: getProductKind() === 'deepread' && this.editorMode === 'generate',
      onGenerated: (name: string, html: string): void => { this.generatedDraft(lifecycle, name, html); } })
  }

  @Builder
  GeneratedEditor(session: number, port: DeepReadTemplateEditorPort): void {
    ForEach([session], (_session: number): void => {
      DeepReadTemplateWorkbench({ embedded: true, draftName: this.draftName, draftHtml: this.draftHtml,
        previewFontMode: this.previewFontMode, previewFontScale: this.previewFontScale,
        editorPort: port, onDismiss: (): void => { this.closeSheet(session); },
        onSaved: (): void => { this.closeSheet(session); } })
    }, (id: number): string => `generated-template-${id}`)
  }
}
