import { router } from '@kit.ArkUI';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';
import { BUILTIN_TEMPLATES, loadCustomTemplates } from '../platform_impl/DeepReadTemplate.ets';
import type { DeepReadTemplateDef, CustomTemplateDef } from '../platform_impl/DeepReadTemplate.ets';
import { BG, INK3, ERROR, GUTTER } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer } from '../components/PageTemplate.ets';

@Entry
@Component
struct DeepReadTemplateSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State selectedId: string = 'none';
  @State templates: CustomTemplateDef[] = [];
  @State error: string = '';
  @State saving: boolean = false;
  private storage: Storage | null = null;
  private alive: boolean = false;
  private loadId: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.storage = getAppContainer().storage;
  }

  onPageShow(): void { this.reload(); }

  aboutToDisappear(): void { this.alive = false; this.loadId++; }

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
    this.saving = true;
    try {
      await this.storage.set<string>('deepread_template_id', id);
      if (this.alive) { this.selectedId = id; this.error = ''; }
    } catch (e) {
      if (this.alive) this.error = `选用失败:${e instanceof Error ? e.message : String(e)}`;
    } finally { if (this.alive) this.saving = false; }
  }

  private edit(id: string = ''): void {
    router.pushUrl({ url: 'pages/DeepReadTemplateWorkbenchPage', params: { templateId: id } });
  }

  private customRowKey(t: CustomTemplateDef): string {
    return `${t.id}-${t.name}-${t.updatedAt ?? t.createdAt}-${this.selectedId}`;
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
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Column() {
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
        }.width('100%').height('100%')
      }, (epoch: number): string => `${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
