// DeepReadTemplateWorkbenchPage — 模板工坊(D1-1/D1-2;替换 2026-09-27 redirect 兼容壳)
//
// 自定义 HTML 版式模板:列表管理 + 编辑器(全屏/从文件导入)+ 示例预览(ArkWeb)+
//   AI 生成(自然语言 → LLM 产 HTML → 严格安检 → 填入编辑器人工确认)。
// 安检/渲染收口在 platform_impl/DeepReadTemplate.ets(sanitizeTemplateHtml/
//   renderCustomTemplateHtml);占位符 {{title}}/{{kicker}}/{{summary}}/{{timeline}}/
//   {{analysis}}/{{reading}}。
// 偏差登记:自定义模板为静态壳,deepread 字号倍率/衬线偏好不生效(页脚注明)。
import { getAppContainer } from '../di/AppContainer.ets';
import {
  CustomTemplateDef, loadCustomTemplates, saveCustomTemplates,
  sanitizeTemplateHtml, hasTemplatePlaceholders, renderCustomTemplateHtml,
  buildSampleTemplateOutput,
} from '../platform_impl/DeepReadTemplate.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer,
  BtnFill, BtnGhost,
} from '../components/PageTemplate.ets';
import { AmberTextArea } from '../components/ui/AmberTextArea.ets';
import {
  INK, INK3, ACCENT, BG, ERROR, GUTTER, LINE,
} from '../design/tokens.ets';
import { webview } from '@kit.ArkWeb';
import { picker, fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { makeUserMessage, UIMessage, UIMessagePart, UIMessagePartText } from '@amber/chat-domain';
import { Storage } from '@amber/deepread-domain';

const SAMPLE_TITLE: string = '示例文章标题:自定义模板预览';

const AI_TEMPLATE_PROMPT: string =
  '你是前端版式设计师。根据用户描述输出一个深度阅读文章的 HTML 模板,只输出 HTML 本体,不要解释。' +
  '硬性规则:①可用占位符 {{title}} {{kicker}} {{summary}} {{timeline}} {{analysis}} {{reading}},' +
  '正文至少包含 {{summary}} 和一个其他内容占位符;②只允许内联 <style> CSS,' +
  '禁止 script/iframe/form/外部资源(url()/import);③排版杂志风,雅致、留白充分;' +
  '④所有章节占位符放在合适的容器里。用户描述:';

@Entry
@Component
struct DeepReadTemplateWorkbenchPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State templates: CustomTemplateDef[] = [];
  @State editingId: string = '';   // '' = 新建
  @State editName: string = '';
  @State editHtml: string = '';
  @State note: string = '';
  @State aiPrompt: string = '';
  @State generating: boolean = false;
  @State genError: string = '';
  @State previewOpen: boolean = false;
  private storage: Storage | null = null;
  private webController: webview.WebviewController = new webview.WebviewController();
  private webAttached: boolean = false;

  aboutToAppear(): void {
    this.storage = getAppContainer().storage;
    this.reload();
  }

  private reload(): void {
    if (this.storage === null) return;
    loadCustomTemplates(this.storage).then((list: CustomTemplateDef[]): void => {
      this.templates = list;
    }).catch((): void => {});
  }

  private startEdit(t: CustomTemplateDef): void {
    this.editingId = t.id;
    this.editName = t.name;
    this.editHtml = t.html;
    this.note = '';
  }

  private startNew(): void {
    this.editingId = '';
    this.editName = '';
    this.editHtml = '';
    this.note = '';
  }

  private async save(): Promise<void> {
    if (this.storage === null) return;
    const name: string = this.editName.trim();
    if (name.length === 0) {
      this.note = '请填写模板名称';
      return;
    }
    if (!hasTemplatePlaceholders(this.editHtml)) {
      this.note = '模板至少需要一个 {{占位符}}(如 {{summary}})';
      return;
    }
    if (sanitizeTemplateHtml(this.editHtml).length === 0) {
      this.note = '模板内容在安检后为空,请移除被禁元素';
      return;
    }
    const now: number = Date.now();
    const item: CustomTemplateDef = {
      id: this.editingId.length > 0 ? this.editingId : `custom_${now}`,
      name: name,
      html: this.editHtml,
      createdAt: now,
    };
    const next: CustomTemplateDef[] = [
      ...this.templates.filter((t: CustomTemplateDef): boolean => t.id !== item.id),
      item,
    ];
    await saveCustomTemplates(this.storage, next);
    this.templates = next;
    this.editingId = item.id;
    this.note = '已保存 ✓(到 深度阅读设置 选用)';
  }

  private async remove(id: string): Promise<void> {
    if (this.storage === null) return;
    const next: CustomTemplateDef[] = this.templates.filter(
      (t: CustomTemplateDef): boolean => t.id !== id);
    await saveCustomTemplates(this.storage, next);
    this.templates = next;
    if (this.editingId === id) this.startNew();
    // 删除的是当前选中模板 → 清选中键,否则设置页选中态与存储不一致
    const selected: string = await this.storage.get<string>('deepread_template_id', 'none');
    if (selected === id) {
      await this.storage.set<string>('deepread_template_id', 'none');
    }
  }

  private async importHtmlFile(): Promise<void> {
    try {
      const ctx: common.UIAbilityContext =
        AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.html', '.htm'];
      const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(ctx);
      const uris: string[] = await docPicker.select(options);
      if (uris.length === 0) return;
      const text: string = fileIo.readTextSync(uris[0]);
      this.editHtml = text;
      this.note = '已从文件导入,保存前请确认安检通过';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.note = `导入失败:${msg}`;
    }
  }

  private loadPreview(): void {
    if (!this.webAttached) return;
    const html: string = renderCustomTemplateHtml(
      this.editHtml, SAMPLE_TITLE, buildSampleTemplateOutput());
    this.webController.loadData(
      encodeURIComponent(html).replace(/#/g, '%23'),
      'text/html', 'UTF-8', ' ', ' ');
  }

  private openPreview(): void {
    this.previewOpen = true;
    // controller 已附着时直接重载;未附着由 onControllerAttached 触发
    this.loadPreview();
  }

  private async generate(): Promise<void> {
    const prompt: string = this.aiPrompt.trim();
    if (prompt.length === 0 || this.generating) return;
    this.generating = true;
    this.genError = '';
    try {
      if (this.storage === null) throw new Error('存储未就绪');
      const model: string = await this.storage.get<string>('ai_model', 'deepseek-chat');
      const messages: UIMessage[] = [makeUserMessage(prompt.length > 0 ? AI_TEMPLATE_PROMPT + prompt : prompt)];
      const out: UIMessage[] = await getAppContainer().aiClient.generateText({ model: model, messages: messages });
      let text: string = '';
      for (const m of out) {
        if (m.role !== 'assistant') continue;
        for (const p of m.parts) {
          if (p.type === 'text') {
            const tp: UIMessagePartText = p as UIMessagePartText;
            text += tp.text;
          }
        }
      }
      // 剥代码围栏(模型常用 ```html … ``` 包裹)再截取 HTML 本体
      text = text.replace(/```[a-zA-Z]*\s*\n?/g, '');
      const start: number = text.indexOf('<');
      const end: number = text.lastIndexOf('>');
      if (start < 0 || end <= start) throw new Error('模型未返回 HTML');
      const html: string = text.substring(start, end + 1);
      if (sanitizeTemplateHtml(html).length === 0) throw new Error('生成内容未通过安检');
      this.editHtml = html;
      if (this.editName.trim().length === 0) this.editName = prompt.substring(0, 12);
      this.note = 'AI 生成已填入编辑器,请预览确认后保存';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.genError = `生成失败:${msg}`;
    } finally {
      this.generating = false;
    }
  }

  private templateDate(t: CustomTemplateDef): string {
    if (t.createdAt <= 0) return '';
    const d: Date = new Date(t.createdAt);
    return `${d.getFullYear()}-${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')}`;
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '模板工坊' })
      Scroll() {
        Column() {
          PlainSection({ label: '自定义模板' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ListCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '编辑器' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.EditorCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: 'AI 生成' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.AiCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          FooterNote({
            text: '占位符:{{title}} 标题 · {{kicker}} 刊头 · {{summary}} 导语与实体 · ' +
              '{{timeline}} 时间线 · {{analysis}} 深度分析 · {{reading}} 扩展阅读。' +
              '模板经严格安检(禁脚本/表单/外部资源);自定义模板不随字号倍率与衬线偏好缩放。',
          })
          PageSpacer()
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
    .bindSheet(this.previewOpen, this.PreviewSheet(), {
      height: SheetSize.LARGE, showClose: false,
      onDisappear: (): void => { this.previewOpen = false; },
    })
  }

  @Builder
  ListCard(): void {
    if (this.templates.length === 0) {
      Column() {
        Text('还没有自定义模板。用下方编辑器手写,或用 AI 生成。')
          .fontSize(13).fontColor(INK3)
          .width('100%')
          .padding({ top: 8, bottom: 8 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
    }
    ForEach(this.templates, (t: CustomTemplateDef, idx: number): void => {
      CardRow({
        label: t.name,
        desc: this.templateDate(t),
        valueText: '编辑',
        isLast: idx === this.templates.length - 1 && this.editingId.length === 0,
        onRowClick: (): void => { this.startEdit(t); },
      })
    }, (t: CustomTemplateDef): string => t.id)
    if (this.templates.length > 0 && this.editingId.length > 0) {
      Column() {
        Row().width('100%').height(0.5).backgroundColor(LINE)
        Text(`删除「${this.editName}」`)
          .fontSize(13).fontColor(ERROR)
          .constraintSize({ minHeight: 40 })
          .onClick((): void => { this.remove(this.editingId); })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
    }
  }

  @Builder
  EditorCard(): void {
    Column() {
      TextInput({ text: this.editName, placeholder: '模板名称(如:暗夜极简)' })
        .fontSize(15).fontColor(INK)
        .backgroundColor(Color.Transparent)
        .constraintSize({ minHeight: 44 })
        .onChange((v: string): void => { this.editName = v; })
      Row().width('100%').height(0.5).backgroundColor(LINE)
      AmberTextArea({
        value: this.editHtml,
        label: 'HTML(占位符见页脚说明)',
        placeholder: '<style>…</style>\n<h1>{{title}}</h1>\n{{summary}}\n{{analysis}}',
        minLines: 8,
        maxLines: 14,
        onChange: (v: string): void => { this.editHtml = v; },
        onImportFile: (): void => { this.importHtmlFile(); },
      })
      if (this.note.length > 0) {
        Text(this.note)
          .fontSize(12).fontColor(this.note.includes('✓') || this.note.includes('填入') ? INK3 : ERROR)
          .width('100%')
          .padding({ top: 8 })
      }
      Row() {
        BtnGhost({ label: '新建', onTap: (): void => { this.startNew(); } })
        Blank().layoutWeight(1)
        BtnGhost({ label: '预览', onTap: (): void => { this.openPreview(); } })
        BtnFill({ label: '保存', onTap: (): void => { this.save().catch((): void => {}); } })
          .margin({ left: 8 })
      }
      .width('100%')
      .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  AiCard(): void {
    Column() {
      TextInput({ text: this.aiPrompt, placeholder: '描述想要的版式,如:深夜极简、大标题衬线' })
        .fontSize(15).fontColor(INK)
        .backgroundColor(Color.Transparent)
        .constraintSize({ minHeight: 44 })
        .onChange((v: string): void => { this.aiPrompt = v; })
      if (this.genError.length > 0) {
        Text(this.genError)
          .fontSize(12).fontColor(ERROR)
          .width('100%')
          .padding({ top: 8 })
      }
      Row() {
        Blank().layoutWeight(1)
        BtnFill({
          label: this.generating ? '生成中…' : '生成模板',
          onTap: (): void => { this.generate().catch((): void => {}); },
        })
      }
      .width('100%')
      .margin({ top: 8 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  PreviewSheet(): void {
    Column() {
      Row() {
        Text('预览(示例数据)').fontSize(18).fontColor(INK).layoutWeight(1)
        Text('关闭').fontSize(14).fontColor(this.accentColorStore).padding(12)
          .onClick((): void => { this.previewOpen = false; })
      }
      .width('100%')
      .padding({ left: 4, right: 4, bottom: 8 })
      Web({ src: 'about:blank', controller: this.webController })
        .width('100%').layoutWeight(1)
        .backgroundColor(BG)
        .javaScriptAccess(false)
        .onControllerAttached((): void => {
          this.webAttached = true;
          this.loadPreview();
        })
    }
    .width('100%').height('100%')
    .padding({ left: 16, right: 16, top: 12 })
    .backgroundColor(BG)
  }
}
