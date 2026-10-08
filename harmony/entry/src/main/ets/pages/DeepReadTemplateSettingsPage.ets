import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { router, curves } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';
import { BUILTIN_TEMPLATES, loadCustomTemplates } from '../platform_impl/DeepReadTemplate.ets';
import type { DeepReadTemplateDef, CustomTemplateDef } from '../platform_impl/DeepReadTemplate.ets';
import { BG, INK3, ERROR, GUTTER } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer } from '../components/PageTemplate.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { DeepReadHaptics } from '../design/DeepReadHaptics.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { Icon } from '../components/ui/Icon.ets';
import { DeepReadTemplateNavigation } from '../components/deepread/DeepReadTemplateNavigation.ets';
import { DeepReadPaperBackground } from '../components/deepread/DeepReadPaperBackground.ets';
import { DeepReadTemplateWorkbench, DeepReadTemplateEditorPort } from '../components/deepread/DeepReadTemplateWorkbench.ets';

interface TemplateSettingsParams { readerFontMode?: string; readerFontScale?: number; }

@Entry
@Component
struct DeepReadTemplateSettingsPage {
  @StorageProp('themeEpoch') @Watch('clearSelectionMotion') themeEpoch: number = 0;
  @StorageProp('reduceMotion') @Watch('clearSelectionMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('clearSelectionMotion') appBackgrounded: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State selectedId: string = 'none';
  @State templates: CustomTemplateDef[] = [];
  @State error: string = '';
  @State saving: boolean = false;
  @State bounceId: string = '';
  @State iconScale: number = 1;
  @State iconOffset: number = 0;
  @State previewFontMode: string = '';
  @State previewFontScale: number = 0;
  @State sheetOpen: boolean = false;
  @State sheetSession: number = 0;
  @State sheetTemplateId: string = '';
  @State sheetPreview: boolean = false;
  private sheetPort: DeepReadTemplateEditorPort = new DeepReadTemplateEditorPort();
  private sheetCloseApproved: boolean = false;
  private storage: Storage | null = null;
  private alive: boolean = false;
  private loadId: number = 0;
  private lifecycleId: number = 0;
  private motionId: number = 0;
  private pageVisible: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.lifecycleId++;
    this.pageVisible = true;
    this.clearSelectionMotion();
    this.storage = getAppContainer().storage;
    const params: TemplateSettingsParams = (router.getParams() as TemplateSettingsParams) ?? {};
    this.previewFontMode = params.readerFontMode === 'default' || params.readerFontMode === 'serif' ? params.readerFontMode : '';
    const percent: number = params.readerFontScale ?? 0;
    this.previewFontScale = Number.isFinite(percent) && percent >= 70 && percent <= 180 ? percent : 0;
  }

  onPageShow(): void { this.pageVisible = true; this.reload(); }

  onPageHide(): void { this.pageVisible = false; this.clearSelectionMotion(); }

  aboutToDisappear(): void {
    this.alive = false;
    this.lifecycleId++;
    this.pageVisible = false;
    this.clearSelectionMotion();
    this.loadId++;
    this.sheetSession++;
    this.sheetOpen = false;
  }

  onBackPress(): boolean {
    if (!this.sheetOpen) return false;
    const session: number = this.sheetSession;
    this.sheetPort.leave((): void => { this.closeSheet(session); });
    return true;
  }

  private async reload(): Promise<void> {
    if (this.storage === null) return;
    const id: number = ++this.loadId;
    try {
      const templates: CustomTemplateDef[] = await loadCustomTemplates(this.storage);
      const selected: string = await this.storage.get<string>('deepread_template_id', 'none');
      if (!this.alive || id !== this.loadId) return;
      this.templates = templates;
      this.selectedId = selected;
      this.error = '';
    } catch (e) {
      if (this.alive && id === this.loadId) this.error = `读取模板失败:${e instanceof Error ? e.message : String(e)}`;
    }
  }

  private async select(id: string): Promise<void> {
    if (this.storage === null || this.saving) return;
    const lifecycle: number = this.lifecycleId;
    this.saving = true;
    try {
      await this.storage.set<string>('deepread_template_id', id);
      if (this.alive && lifecycle === this.lifecycleId) this.applySelection(id);
    } catch (e) {
      if (this.alive && lifecycle === this.lifecycleId) this.error = `选用失败:${e instanceof Error ? e.message : String(e)}`;
    } finally { if (this.alive && lifecycle === this.lifecycleId) this.saving = false; }
  }

  private clearSelectionMotion(): void {
    this.motionId++;
    this.bounceId = '';
    this.iconScale = 1;
    this.iconOffset = 0;
  }

  private applySelection(id: string): void {
    const changed: boolean = this.selectedId !== id;
    this.selectedId = id;
    this.error = '';
    if (!changed || getProductKind() !== 'deepread' || !this.pageVisible || this.appBackgrounded) return;
    DeepReadHaptics.selection();
    this.clearSelectionMotion();
    if (this.reduceMotion) return;
    const motion: number = this.motionId;
    this.bounceId = id;
    this.getUIContext().keyframeAnimateTo({ iterations: 1, onFinish: (): void => {
      if (this.alive && motion === this.motionId) this.clearSelectionMotion();
    } }, [
      { duration: 100, curve: Curve.EaseOut, event: (): void => {
        if (this.alive && motion === this.motionId) { this.iconScale = 1.15; this.iconOffset = -3; }
      } },
      { duration: 180, curve: curves.springCurve(0, 1, 240, 24), event: (): void => {
        if (this.alive && motion === this.motionId) { this.iconScale = 1; this.iconOffset = 0; }
      } },
    ]);
  }

  private edit(id: string = ''): void {
    if (getProductKind() === 'deepread') { this.openWorkbench(id, false); return; }
    router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage', params: { templateId: id } });
  }

  private preview(id: string): void {
    if (getProductKind() === 'deepread') { this.openWorkbench(id, true); return; }
    router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage', params: { templateId: id, preview: true } });
  }

  private generateTemplate(): void {
    if (getProductKind() === 'deepread' && (this.previewFontMode.length > 0 || this.previewFontScale > 0)) {
      router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage', params: { mode: 'generate',
        readerFontMode: this.previewFontMode, readerFontScale: this.previewFontScale } });
      return;
    }
    router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage', params: { mode: 'generate' } });
  }

  private customRowKey(t: CustomTemplateDef): string {
    return `${t.id}-${t.name}-${t.description ?? ''}-${t.updatedAt ?? t.createdAt}`;
  }

  private openWorkbench(id: string, preview: boolean): void {
    if (!this.alive || this.sheetOpen) return;
    this.sheetPort = new DeepReadTemplateEditorPort();
    this.sheetCloseApproved = false;
    this.sheetTemplateId = id;
    this.sheetPreview = preview;
    this.sheetSession++;
    this.sheetOpen = true;
  }

  private closeSheet(session: number): void {
    if (!this.alive || session !== this.sheetSession) return;
    this.sheetCloseApproved = true;
    this.sheetOpen = false;
  }

  private sheetSaved(session: number): void {
    if (!this.alive || session !== this.sheetSession) return;
    this.sheetCloseApproved = true;
    this.sheetOpen = false;
  }

  private sheetDisappeared(session: number): void {
    if (!this.alive || session !== this.sheetSession) return;
    this.sheetOpen = false;
    this.reload();
  }

  private workbenchSheetOptions(session: number, port: DeepReadTemplateEditorPort): SheetOptions {
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

  @Builder
  BuiltinRows(): void {
    ForEach(BUILTIN_TEMPLATES, (t: DeepReadTemplateDef, i: number): void => {
      CardRow({ label: t.name, desc: t.description, valueText: this.selectedId === t.id ? '已选用 ✓' : '选用',
        isLast: i === BUILTIN_TEMPLATES.length - 1, showChevron: false,
        onRowClick: (): void => { this.select(t.id); } })
    }, (t: DeepReadTemplateDef): string => t.id)
  }

  @Builder
  CustomRows(): void {
    ForEach(this.templates, (t: CustomTemplateDef): void => {
      CardRow({ label: t.name, desc: this.selectedId === t.id ? '已选用 · 点按编辑' : '点按编辑模板', valueText: '编辑',
        onRowClick: (): void => { this.edit(t.id); } })
      Text(this.selectedId === t.id ? '已用于新文章 ✓' : '用于新文章')
        .fontSize(13).fontColor(INK3).width('100%').textAlign(TextAlign.End)
        .constraintSize({ minHeight: 44 }).padding({ left: 14, right: 14 })
        .onClick((): void => { this.select(t.id); })
    }, (t: CustomTemplateDef): string => this.customRowKey(t))
    CardRow({ label: '新建 HTML 模板', desc: '从可直接预览的基础版式开始，也可用 AI 生成', isLast: true,
      onRowClick: (): void => { this.edit(); } })
  }

  build(): void {
    Stack() {
      if (getProductKind() === 'deepread') { DeepReadPaperBackground() }
      Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
          if (getProductKind() === 'deepread') {
            DeepReadTemplateNavigation({ title: '阅读模板', largeTitle: true,
              onBack: (): void => { router.back(); } })
            Scroll() {
              this.StandaloneTemplates()
            }.layoutWeight(1).width('100%').align(Alignment.TopStart)
          } else {
          CenteredTitleBar({ title: '阅读模板' })
          Scroll() {
            Column() {
              PlainSection({ label: '内置版式' })
              CardGroup({ content: (): void => { this.BuiltinRows(); } }).margin({ left: GUTTER, right: GUTTER })
              PlainSection({ label: '自定义模板' })
              CardGroup({ content: (): void => { this.CustomRows(); } }).margin({ left: GUTTER, right: GUTTER })
              if (this.error.length > 0) {
                Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding(GUTTER)
              }
              FooterNote({ text: '选用版式用于新文章。编辑器可导入 HTML、生成模板、预览并保存。' })
              PageSpacer()
            }.width('100%').padding({ bottom: this.navBarHeight + 16 })
          }.layoutWeight(1).width('100%').align(Alignment.TopStart)
          }
        }.width('100%').height('100%')
      }, (epoch: number): string => `${epoch}`)
      }.width('100%').height('100%')
      DeepReadSubpageDock().align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
      .bindSheet($$this.sheetOpen, this.WorkbenchSheet(this.sheetSession, this.sheetPort),
        this.workbenchSheetOptions(this.sheetSession, this.sheetPort))
  }

  @Builder
  WorkbenchSheet(session: number, port: DeepReadTemplateEditorPort): void {
    ForEach([session], (_session: number): void => {
      DeepReadTemplateWorkbench({ embedded: true, templateId: this.sheetTemplateId,
        initialPreview: this.sheetPreview, previewFontMode: this.previewFontMode, previewFontScale: this.previewFontScale,
        editorPort: port, onDismiss: (): void => { this.closeSheet(session); },
        onSaved: (): void => { this.sheetSaved(session); } })
    }, (id: number): string => `template-editor-${id}`)
  }

  @Builder
  SectionLabel(label: string): void {
    Text(label).fontSize(13).fontColor(D.muted).width('100%')
      .padding({ left: 14, top: 22, bottom: 8 })
  }

  @Builder
  TemplateSwatch(editorial: boolean, id: string): void {
    Row() {
      Icon({ name: id === 'deepread_auto' ? 'spark' : id === 'deepread_qa' ? 'hi_message' : editorial ? 'book' : 'doc', iconSize: 20, color: D.accent })
        .scale({ x: this.bounceId === id ? this.iconScale : 1, y: this.bounceId === id ? this.iconScale : 1 })
        .translate({ y: this.bounceId === id ? this.iconOffset : 0 })
    }.width(48).height(48).alignItems(VerticalAlign.Center)
      .justifyContent(FlexAlign.Center).backgroundColor(D.paper)
      .borderRadius(8).border({ width: 0.6, color: D.rule })
  }

  @Builder
  StandaloneTemplates(): void {
    Column() {
      this.SectionLabel('生成模板')
      Column() {
        ForEach(BUILTIN_TEMPLATES, (t: DeepReadTemplateDef, i: number): void => {
          Row({ space: 14 }) {
            this.TemplateSwatch(t.id === 'editorial_slant', t.id)
            Column({ space: 4 }) {
              Text(t.name).fontSize(17).fontColor(D.ink).width('100%')
              Text(t.description)
                .fontSize(12).fontColor(D.muted).width('100%')
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            if (this.selectedId === t.id) {
              Icon({ name: 'check', iconSize: 17, color: D.accent })
                .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.65, y: 0.65 }))
                  .animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 220, curve: curves.springCurve(0, 1, 240, 24) }))
            }
          }.width('100%').padding(14).constraintSize({ minHeight: 76 }).enabled(!this.saving)
            .accessibilityText(`${t.name}${this.selectedId === t.id ? '，已选用' : ''}`)
            .onClick((): void => { this.select(t.id); })
          if (i < BUILTIN_TEMPLATES.length - 1) {
            Row().width('100%').height(0.5).backgroundColor(D.rule)
          }
        }, (t: DeepReadTemplateDef): string => t.id)
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true)
      Text('决定新文章生成什么内容；历史文章保留生成时的模板。').fontSize(12).fontColor(D.muted).width('100%')
        .padding({ left: 14, right: 14, top: 7 })

      this.SectionLabel('自定义模板')
      Column() {
        ForEach(this.templates, (t: CustomTemplateDef): void => {
          Column() {
            Row({ space: 12 }) {
              Column({ space: 4 }) {
                Text(t.name).fontSize(17).fontColor(D.ink).width('100%')
                Text((t.description ?? '').length > 0 ? (t.description ?? '') : '自定义阅读版式')
                  .fontSize(12).fontColor(D.muted).width('100%')
              }.layoutWeight(1).alignItems(HorizontalAlign.Start)
              if (this.selectedId === t.id) {
                Icon({ name: 'check', iconSize: 17, color: D.accent })
                  .transition(TransitionEffect.OPACITY.combine(TransitionEffect.scale({ x: 0.65, y: 0.65 }))
                    .animation({ duration: this.reduceMotion || this.appBackgrounded ? 0 : 220, curve: curves.springCurve(0, 1, 240, 24) }))
              }
            }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 4 })
              .constraintSize({ minHeight: 60 }).onClick((): void => { this.edit(t.id); })
            Row() {
              Text('预览版式').fontSize(13).fontColor(D.accent).constraintSize({ minHeight: 44 })
                .onClick((): void => { this.preview(t.id); })
              Blank().layoutWeight(1)
              Text(this.selectedId === t.id ? '已用于新文章' : '用于新文章')
                .fontSize(13).fontColor(D.accent).constraintSize({ minHeight: 44 }).enabled(!this.saving)
                .onClick((): void => { this.select(t.id); })
            }.width('100%').padding({ left: 14, right: 14 })
            Row().width('100%').height(0.5).backgroundColor(D.rule)
          }.width('100%')
        }, (t: CustomTemplateDef): string => this.customRowKey(t))
        Row({ space: 10 }) {
          Icon({ name: 'plus', iconSize: 17, color: D.accent })
          Text('新建 HTML 模板').fontSize(17).fontColor(D.accent).layoutWeight(1)
        }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 14, right: 14 })
          .onClick((): void => { this.edit(); })
        Row().width('100%').height(0.5).backgroundColor(D.rule)
        Row({ space: 10 }) {
          Icon({ name: 'spark', iconSize: 17, color: D.accent })
          Text('用 AI 生成模板').fontSize(17).fontColor(D.accent).layoutWeight(1)
          Icon({ name: 'chevron_right', iconSize: 13, color: D.muted })
        }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 14, right: 14 })
          .onClick((): void => { this.generateTemplate(); })
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true)
      if (this.error.length > 0) {
        Text(this.error).fontSize(13).fontColor(D.danger).width('100%').padding({ top: 14 })
      }
    }.width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: D.gutter, right: D.gutter, bottom: getProductKind() === 'deepread' ? DeepReadDockLayout.bottomInset(this.navBarHeight) : this.navBarHeight + 28 })
  }
}
