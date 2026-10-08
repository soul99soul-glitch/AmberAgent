// DeepReadTemplateWorkbenchPage — 模板工坊(D1-1/D1-2;替换 2026-09-27 redirect 兼容壳)
//
// 自定义 HTML 版式模板:列表管理 + 编辑器(全屏/从文件导入)+ 示例预览(ArkWeb)+
//   AI 生成(自然语言 → LLM 产 HTML → 严格安检 → 填入编辑器人工确认)。
// 安检/渲染收口在 DeepReadTemplate;旧模板缺少的章节会补在文章末尾。
import { getAppContainer, getChatKvStore, getDeepReadTextRuntime } from '../../di/AppContainer.ets';
import type { DeepReadTextRuntime } from '../../di/AppContainer.ets';
import {
  CustomTemplateDef, loadCustomTemplates, saveCustomTemplates,
  validateTemplateHtml, renderCustomTemplateHtml, MAX_TEMPLATE_BYTES,
  buildSampleTemplateOutput, DeepReadRenderOptions,
  starterTemplateHtml,
} from '../../platform_impl/DeepReadTemplate.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer,
  BtnFill, BtnGhost,
} from '../PageTemplate.ets';
import { AmberTextArea } from '../ui/AmberTextArea.ets';
import {
  INK, INK3, INK4, ACCENT, BG, SURFACE, ERROR, GUTTER, LINE,
} from '../../design/tokens.ets';
import { webview } from '@kit.ArkWeb';
import { picker, fileIo } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { makeUserMessage, UIMessage, UIMessagePartText } from '@amber/chat-domain';
import { Storage, AbortControllerLike } from '@amber/deepread-domain';
import { createEntryAbortController } from '../../platform_impl/EntryAbortController.ets';
import { router } from '@kit.ArkUI';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
import { Icon } from '../ui/Icon.ets';
import { DeepReadTemplateNavigation } from './DeepReadTemplateNavigation.ets';
import { DeepReadTemplateCodeEditor } from './DeepReadTemplateCodeEditor.ets';
import { DeepReadPaperBackground } from './DeepReadPaperBackground.ets';

const SAMPLE_TITLE: string = '示例文章标题:自定义模板预览';

const AI_TEMPLATE_PROMPT: string =
  '你是前端版式设计师。根据用户描述输出一个深度阅读文章的 HTML 模板,只输出 HTML 本体,不要解释。' +
  '硬性规则:①可用占位符 {{title}} {{kicker}} {{topic}} {{hero}} {{summary}} {{timeline}} {{core_points}} ' +
  '{{analysis}} {{diagram}} {{images}} {{reading}} {{references}};或使用 {{content}} 一次展示全部内容;' +
  '②只允许内联 <style> CSS,禁止 script/iframe/form/事件属性/外部 CSS(url()/import);' +
  '③字号使用 calc(15.5px * var(--ds)),字体使用 var(--dr-font),' +
  '背景和文字使用 var(--dr-bg) 和 var(--dr-ink),辅助色可用 var(--dr-muted)/var(--dr-surface)/var(--dr-border)/var(--dr-accent);' +
  '④排版雅致、留白充分,所有章节占位符放在合适容器。用户描述:';

export class DeepReadTemplateEditorPort {
  onBack: (() => boolean) | null = null;
  onLeave: ((dismiss: () => void) => void) | null = null;

  back(): boolean { return this.onBack === null ? false : this.onBack(); }
  leave(dismiss: () => void): void {
    if (this.onLeave === null) dismiss();
    else this.onLeave(dismiss);
  }
}

@Component
export struct DeepReadTemplateWorkbench {
  @Prop templateId: string = '';
  @Prop initialMode: string = 'editor';
  @Prop initialPreview: boolean = false;
  @Prop embedded: boolean = false;
  @Prop presentGeneratedDraft: boolean = false;
  @Prop draftName: string = '';
  @Prop draftHtml: string = '';
  @Prop previewFontMode: string = '';
  @Prop previewFontScale: number = 0;
  editorPort: DeepReadTemplateEditorPort = new DeepReadTemplateEditorPort();
  onDismiss: () => void = (): void => {};
  onSaved: () => void = (): void => {};
  onGenerated: (name: string, html: string) => void = (_name: string, _html: string): void => {};
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State editorMode: string = 'editor';
  @State templates: CustomTemplateDef[] = [];
  @State editingId: string = '';   // '' = 新建
  @State editName: string = '';
  @State editDescription: string = '';
  @State editHtml: string = '';
  @State note: string = '';
  @State aiPrompt: string = '';
  @State generating: boolean = false;
  @State genError: string = '';
  @State previewOpen: boolean = false;
  @State saving: boolean = false;
  @State loaded: boolean = false;
  private storage: Storage | null = null;
  private webController: webview.WebviewController = new webview.WebviewController();
  private webAttached: boolean = false;
  private webInitialized: boolean = false;
  private previewId: number = 0;
  private alive: boolean = false;
  private generationController: AbortControllerLike | null = null;
  private fontScale: number = 1;
  private fontSerif: boolean = false;
  private lifecycleId: number = 0;
  private editorId: number = 0;
  private requestedTemplateId: string = '';
  private requestedPreview: boolean = false;
  private initialName: string = '';
  private initialDescription: string = '';
  private initialHtml: string = '';
  private exitPromptOpen: boolean = false;

  aboutToAppear(): void {
    this.alive = true;
    this.lifecycleId++;
    this.storage = getAppContainer().storage;
    this.editorPort.onBack = (): boolean => this.onBackPress();
    this.editorPort.onLeave = (dismiss: () => void): void => { this.requestLeave(dismiss); };
    this.requestedTemplateId = this.templateId;
    this.requestedPreview = this.initialPreview;
    this.loaded = false;
    this.saving = false;
    this.startNew();
    if (this.initialMode === 'generate') {
      this.editorMode = 'generate';
      this.editName = '我的阅读模板';
    }
    if (this.draftHtml.length > 0) {
      this.editName = this.draftName;
      this.editHtml = this.draftHtml;
      this.note = 'AI 生成已填入编辑器,请预览确认后保存';
    }
    this.reload();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.lifecycleId++;
    this.webAttached = false;
    this.webInitialized = false;
    this.generationController?.abort();
    this.exitPromptOpen = false;
    this.editorPort.onBack = null;
    this.editorPort.onLeave = null;
  }

  onBackPress(): boolean {
    if (getProductKind() !== 'deepread') return false;
    if (this.previewOpen) {
      this.previewOpen = false;
      this.webAttached = false;
      this.webInitialized = false;
      return true;
    }
    if (this.saving) return true;
    if (!this.hasUnsavedChanges()) return false;
    this.confirmLeave();
    return true;
  }

  private hasUnsavedChanges(): boolean {
    return this.editorMode === 'editor' && (this.editName !== this.initialName ||
      this.editDescription !== this.initialDescription || this.editHtml !== this.initialHtml);
  }

  private dismissEditor(): void {
    if (this.embedded) this.onDismiss();
    else router.back();
  }

  private requestLeave(dismiss?: () => void): void {
    if (this.previewOpen) { this.onBackPress(); return; }
    if (this.saving) return;
    const action: () => void = dismiss ?? ((): void => { this.dismissEditor(); });
    if (this.hasUnsavedChanges()) this.confirmLeave(action);
    else action();
  }

  private confirmLeave(dismiss?: () => void): void {
    if (this.exitPromptOpen) return;
    const lifecycle: number = this.lifecycleId;
    this.exitPromptOpen = true;
    AlertDialog.show({ title: '放弃未保存的修改？', message: '模板信息或 HTML 有未保存的修改。',
      primaryButton: { value: '继续编辑', action: (): void => { this.exitPromptOpen = false; } },
      secondaryButton: { value: '放弃并返回', action: (): void => {
        this.exitPromptOpen = false;
        if (this.alive && lifecycle === this.lifecycleId) {
          if (dismiss !== undefined) dismiss();
          else this.dismissEditor();
        }
      } },
      cancel: (): void => { this.exitPromptOpen = false; },
      onWillDismiss: (action: DismissDialogAction): void => {
        this.exitPromptOpen = false;
        action.dismiss();
      },
    });
  }

  private reload(): void {
    if (this.storage === null) return;
    if (this.previewFontScale >= 70 && this.previewFontScale <= 180) this.fontScale = this.previewFontScale / 100;
    if (this.previewFontMode === 'default' || this.previewFontMode === 'serif') this.fontSerif = this.previewFontMode === 'serif';
    const lifecycle: number = this.lifecycleId;
    const editor: number = this.editorId;
    loadCustomTemplates(this.storage).then((list: CustomTemplateDef[]): void => {
      if (!this.alive || lifecycle !== this.lifecycleId) return;
      this.templates = list;
      this.loaded = true;
      if (editor === this.editorId && this.requestedTemplateId.length > 0) {
        const selected: CustomTemplateDef | undefined = list.find((t: CustomTemplateDef): boolean => t.id === this.requestedTemplateId);
        if (selected !== undefined) {
          this.startEdit(selected);
          if (this.requestedPreview) this.openPreview();
        }
        else this.note = '该模板已不存在，可新建模板。';
      }
    }).catch((e: Error): void => {
      if (this.alive && lifecycle === this.lifecycleId) this.note = `加载模板失败:${e.message}`;
    });
    getChatKvStore().get('deepread_font_scale').then((raw: string | null): void => {
      if (!this.alive || lifecycle !== this.lifecycleId) return;
      if (this.previewFontScale >= 70 && this.previewFontScale <= 180) return;
      const percent: number = raw !== null ? parseFloat(raw) : 100;
      this.fontScale = Math.max(0.7, Math.min(1.8, (Number.isNaN(percent) ? 100 : percent) / 100));
      this.loadPreview();
    }).catch((e: Error): void => {
      if (this.alive && lifecycle === this.lifecycleId) this.note = `加载阅读偏好失败:${e.message}`;
    });
    getChatKvStore().get('deepread_font_mode').then((raw: string | null): void => {
      if (!this.alive || lifecycle !== this.lifecycleId) return;
      if (this.previewFontMode === 'default' || this.previewFontMode === 'serif') return;
      this.fontSerif = raw === 'serif' || (raw === null && getProductKind() === 'deepread');
      this.loadPreview();
    }).catch((e: Error): void => {
      if (this.alive && lifecycle === this.lifecycleId) this.note = `加载阅读偏好失败:${e.message}`;
    });
  }

  private startEdit(t: CustomTemplateDef): void {
    if (this.saving) return;
    this.generationController?.abort();
    this.generationController = null;
    this.generating = false;
    this.editorId++;
    this.editorMode = 'editor';
    this.editingId = t.id;
    this.editName = t.name;
    this.editDescription = t.description ?? '';
    this.editHtml = t.html;
    this.initialName = this.editName;
    this.initialDescription = this.editDescription;
    this.initialHtml = this.editHtml;
    this.note = '';
    this.genError = '';
  }

  private startNew(): void {
    if (this.saving) return;
    this.generationController?.abort();
    this.generationController = null;
    this.generating = false;
    this.editorId++;
    this.editorMode = 'editor';
    this.editingId = '';
    this.editName = '自定义模板';
    this.editDescription = '';
    this.editHtml = starterTemplateHtml();
    this.initialName = this.editName;
    this.initialDescription = this.editDescription;
    this.initialHtml = this.editHtml;
    this.note = '';
    this.genError = '';
  }

  private async save(): Promise<void> {
    if (this.storage === null || this.saving || this.generating) return;
    if (!this.loaded) { this.note = '模板尚未加载完成，暂不能保存'; return; }
    const lifecycle: number = this.lifecycleId;
    const name: string = this.editName.trim();
    if (name.length === 0) {
      this.note = '请填写模板名称';
      return;
    }
    const error: string = validateTemplateHtml(this.editHtml);
    if (error.length > 0) {
      this.note = error;
      return;
    }
    const now: number = Date.now();
    const existing: CustomTemplateDef | undefined = this.templates.find((t: CustomTemplateDef): boolean => t.id === this.editingId);
    const item: CustomTemplateDef = {
      id: this.editingId.length > 0 ? this.editingId : `custom_${now}`,
      name: name,
      description: this.editDescription.trim(),
      html: this.editHtml,
      createdAt: existing !== undefined ? existing.createdAt : now,
      updatedAt: now,
    };
    const next: CustomTemplateDef[] = [
      ...this.templates.filter((t: CustomTemplateDef): boolean => t.id !== item.id),
      item,
    ];
    this.saving = true;
    let saved: boolean = false;
    try {
      await saveCustomTemplates(this.storage, next);
      saved = true;
      if (this.alive && lifecycle === this.lifecycleId) {
        this.templates = next;
        this.editingId = item.id;
        this.initialName = this.editName;
        this.initialDescription = this.editDescription;
        this.initialHtml = this.editHtml;
      }
      await this.storage.set<string>('deepread_template_id', item.id);
      if (this.alive && lifecycle === this.lifecycleId) {
        this.note = '已保存并用于新文章 ✓';
        if (getProductKind() === 'deepread') {
          if (this.embedded) this.onSaved();
          this.dismissEditor();
        }
      }
    } catch (e) {
      if (this.alive && lifecycle === this.lifecycleId) {
        this.note = `${saved ? '模板已保存，但选用失败' : '保存失败'}:${e instanceof Error ? e.message : String(e)}`;
      }
    } finally {
      if (this.alive && lifecycle === this.lifecycleId) this.saving = false;
    }
  }

  private confirmRemove(id: string): void {
    if (this.saving) return;
    const item: CustomTemplateDef | undefined = this.templates.find((t: CustomTemplateDef): boolean => t.id === id);
    if (item === undefined) return;
    AlertDialog.show({
      title: '删除模板？', message: `删除「${item.name}」？若已选用，后续新文章将使用默认排版。`,
      primaryButton: { value: '删除', action: (): void => { this.remove(id); } },
      secondaryButton: { value: '取消', action: (): void => {} },
    });
  }

  private async remove(id: string): Promise<void> {
    if (this.storage === null || this.saving) return;
    const lifecycle: number = this.lifecycleId;
    const next: CustomTemplateDef[] = this.templates.filter(
      (t: CustomTemplateDef): boolean => t.id !== id);
    this.saving = true;
    let removed: boolean = false;
    try {
      await saveCustomTemplates(this.storage, next);
      removed = true;
      if (this.alive && lifecycle === this.lifecycleId) this.templates = next;
      // Only clear selection after the template was actually removed.
      const selected: string = await this.storage.get<string>('deepread_template_id', 'none');
      if (selected === id) await this.storage.set<string>('deepread_template_id', 'none');
      if (this.alive && lifecycle === this.lifecycleId) {
        this.saving = false;
        if (this.editingId === id) this.startNew();
        this.note = '已删除模板 ✓';
      }
    } catch (e) {
      const reason: string = e instanceof Error ? e.message : String(e);
      if (this.alive && lifecycle === this.lifecycleId) {
        this.note = removed ? `模板已删除,但选用状态更新失败:${reason}。请到阅读模板重新选用。` : `删除失败:${reason}`;
      }
    } finally {
      if (this.alive && lifecycle === this.lifecycleId) this.saving = false;
    }
  }

  private async importHtmlFile(): Promise<void> {
    if (this.saving || this.generating) return;
    const lifecycle: number = this.lifecycleId;
    const editor: number = this.editorId;
    try {
      const ctx: common.UIAbilityContext =
        AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.html', '.htm'];
      const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(ctx);
      const uris: string[] = await docPicker.select(options);
      if (!this.alive || lifecycle !== this.lifecycleId || editor !== this.editorId) return;
      if (uris.length === 0) return;
      if (fileIo.statSync(uris[0]).size > MAX_TEMPLATE_BYTES) throw new Error('模板超过 96 KiB UTF-8 大小限制');
      const text: string = fileIo.readTextSync(uris[0]);
      const error: string = validateTemplateHtml(text);
      if (error.length > 0) throw new Error(error);
      this.editHtml = text;
      this.note = '已从文件导入，请预览后保存 ✓';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      if (this.alive && lifecycle === this.lifecycleId && editor === this.editorId) this.note = `导入失败:${msg}`;
    }
  }

  private loadPreview(): void {
    if (!this.alive || !this.previewOpen || !this.webAttached || !this.webInitialized) return;
    const options: DeepReadRenderOptions = {
      fontScale: this.fontScale, fontSerif: this.fontSerif,
      background: getProductKind() === 'deepread' ? D.paper : BG,
      foreground: getProductKind() === 'deepread' ? D.ink : INK,
      muted: getProductKind() === 'deepread' ? D.muted : INK3,
      surface: getProductKind() === 'deepread' ? D.card : SURFACE,
      border: getProductKind() === 'deepread' ? D.rule : LINE,
      accent: getProductKind() === 'deepread' ? D.accent : this.accentColorStore,
    };
    const html: string = renderCustomTemplateHtml(
      this.editHtml, SAMPLE_TITLE, buildSampleTemplateOutput(), options);
    try {
      const documentUrl: string = 'https://amber-deepread.invalid/template-preview';
      this.webController.loadData(html, 'text/html', 'UTF-8', documentUrl, documentUrl);
    } catch (e) {
      this.previewOpen = false;
      this.note = `预览失败:${e instanceof Error ? e.message : String(e)}`;
    }
  }

  private attachPreviewWeb(id: number, epoch: number): void {
    if (!this.alive || !this.previewOpen || id !== this.previewId || epoch !== this.themeEpoch) return;
    this.webAttached = true;
    this.webInitialized = false;
  }

  private onPreviewPageEnd(url: string, id: number, epoch: number): void {
    if (!this.alive || !this.previewOpen || !this.webAttached || id !== this.previewId ||
      epoch !== this.themeEpoch || this.webInitialized || url !== 'about:blank') return;
    this.webInitialized = true;
    this.loadPreview();
  }

  private onPreviewError(mainFrame: boolean, id: number = this.previewId, epoch: number = this.themeEpoch): void {
    if (!this.alive || !this.webAttached || !this.previewOpen || !mainFrame ||
      id !== this.previewId || epoch !== this.themeEpoch) return;
    this.previewOpen = false;
    this.note = '预览失败:正文加载错误，请重试。';
  }

  private openPreview(): void {
    const error: string = validateTemplateHtml(this.editHtml);
    if (error.length > 0) { this.note = error; return; }
    if (this.previewOpen) { this.loadPreview(); return; }
    this.previewId++;
    this.webAttached = false;
    this.webInitialized = false;
    this.previewOpen = true;
  }

  private async generate(): Promise<void> {
    const prompt: string = this.aiPrompt.trim();
    if (prompt.length === 0 || this.generating || this.saving) return;
    this.generating = true;
    this.genError = '';
    const controller: AbortControllerLike = createEntryAbortController();
    this.generationController = controller;
    try {
      if (this.storage === null) throw new Error('存储未就绪');
      const runtime: DeepReadTextRuntime = await getDeepReadTextRuntime();
      if (!this.alive || controller.signal.aborted) return;
      const messages: UIMessage[] = [makeUserMessage(prompt.length > 0 ? AI_TEMPLATE_PROMPT + prompt : prompt)];
      const out: UIMessage[] = await runtime.aiClient.generateText({ model: runtime.model, messages: messages, signal: controller.signal });
      let text: string = '';
      const assistants: UIMessage[] = out.filter((m: UIMessage): boolean => m.role === 'assistant');
      const last: UIMessage | undefined = assistants.length > 0 ? assistants[assistants.length - 1] : undefined;
      if (last !== undefined) {
        const m: UIMessage = last;
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
      const error: string = validateTemplateHtml(html);
      if (error.length > 0) throw new Error(error);
      if (!this.alive || controller.signal.aborted) return;
      if (this.editName.trim().length === 0) this.editName = prompt.substring(0, 12);
      if (this.presentGeneratedDraft) {
        this.onGenerated(this.editName, html);
      } else {
        this.editHtml = html;
        this.editorMode = 'editor';
        this.note = 'AI 生成已填入编辑器,请预览确认后保存';
      }
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      if (this.alive && !controller.signal.aborted) this.genError = `生成失败:${msg}`;
    } finally {
      if (this.alive && this.generationController === controller) {
        this.generating = false;
        this.generationController = null;
      }
    }
  }

  private templateDate(t: CustomTemplateDef): string {
    const time: number = t.updatedAt ?? t.createdAt;
    if (time <= 0) return '';
    const d: Date = new Date(time);
    return `${d.getFullYear()}-${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')}`;
  }

  private templateRowKey(t: CustomTemplateDef): string {
    return `${t.id}-${t.name}-${t.updatedAt ?? t.createdAt}`;
  }

  private validateDraft(): void {
    const error: string = validateTemplateHtml(this.editHtml);
    this.note = error.length === 0 ? '校验通过 ✓' : error;
  }

  private changeName(value: string): void {
    this.editName = value;
    this.note = '';
  }

  private changeHtml(value: string): void {
    this.editHtml = value;
    this.note = '';
  }

  private changeDescription(value: string): void {
    this.editDescription = value;
    this.note = '';
  }

  build(): void {
    Stack() {
      if (getProductKind() === 'deepread') { DeepReadPaperBackground() }
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      if (getProductKind() === 'deepread') {
        DeepReadTemplateNavigation({ title: this.editorMode === 'generate' ? 'AI 阅读模板' : '编辑模板',
          largeTitle: this.editorMode === 'generate' && this.presentGeneratedDraft,
          cancelText: this.editorMode === 'generate' && this.presentGeneratedDraft ? '' : '取消',
          topInset: this.embedded ? 0 : -1, onBack: (): void => { this.requestLeave(); } })
        Scroll() {
          if (this.editorMode === 'generate') {
            this.StandaloneGenerator()
          } else {
            this.StandaloneEditor()
          }
        }.align(Alignment.TopStart).width('100%').layoutWeight(1)
      } else {
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
              '{{timeline}} 时间线 · {{core_points}} 核心要点 · {{analysis}} 分析 · {{diagram}} 图解 · ' +
              '{{hero}} 主图 · {{images}} 其他图片 · {{reading}} 扩展阅读 · {{references}} 来源 · {{content}} 全部章节。' +
              '缺少的章节补在末尾。字号、字体与主题通过 CSS 变量生效;模板自行写死或强制覆盖的样式需手动修改。' +
              '大小限制 96 KiB,禁止脚本、表单、事件与外部 CSS。',
          })
          PageSpacer()
        }
        .width('100%')
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
      .width('100%').height('100%')
    }
    .width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
    .bindSheet(this.previewOpen, this.PreviewSheet(this.previewId), {
      height: SheetSize.LARGE, showClose: false,
      onDisappear: (): void => { this.previewOpen = false; this.webAttached = false; this.webInitialized = false; },
    })
  }

  @Builder
  StandaloneSection(label: string): void {
    Text(label).fontSize(13).fontColor(D.muted).width('100%')
      .padding({ left: 14, top: 22, bottom: 8 })
  }

  @Builder
  StandaloneAction(label: string, icon: string, busy: boolean, action: () => void): void {
    Row({ space: 10 }) {
      Icon({ name: icon, iconSize: 18, color: busy ? D.muted : D.accent })
      Text(label).fontSize(17).fontColor(busy ? D.muted : D.accent).layoutWeight(1)
    }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 14, right: 14 })
      .enabled(!busy).onClick((): void => { action(); })
  }

  @Builder
  StandaloneEditor(): void {
    Column() {
      this.StandaloneSection('模板信息')
      Column() {
      TextInput({ text: this.editName, placeholder: '名称' })
        .fontSize(17).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent)
        .borderRadius(0).height(50).padding({ left: 14, right: 14 })
        .enabled(!this.saving && !this.generating)
        .onChange((value: string): void => { this.changeName(value); })
      Row().width('100%').height(0.5).backgroundColor(D.rule)
      TextArea({ text: this.editDescription, placeholder: '描述' })
        .fontSize(17).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent)
        .borderRadius(0).height(72).padding(14).enabled(!this.saving && !this.generating)
        .onChange((value: string): void => { this.changeDescription(value); })
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true)

      this.StandaloneSection('HTML 与 CSS')
      DeepReadTemplateCodeEditor({ value: this.editHtml, inputEnabled: !this.saving && !this.generating,
        onChange: (value: string): void => { this.changeHtml(value); },
        onImportFile: (): void => { this.importHtmlFile(); } })
        .borderRadius(D.radius).clip(true)
      Text('保留文章占位符。模板支持静态 HTML 与内联 CSS，可先校验并预览排版。')
        .fontSize(12).fontColor(D.muted).width('100%').padding({ left: 14, right: 14, top: 7 })

      Column() {
        this.StandaloneAction('校验模板', 'shield', this.saving || this.generating, (): void => { this.validateDraft(); })
        Row().width('100%').height(0.5).backgroundColor(D.rule)
        this.StandaloneAction('预览版式', 'eye', this.saving || this.generating, (): void => { this.openPreview(); })
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true).margin({ top: 22 })

      if (this.note.length > 0) {
        Text(this.note).fontSize(13).fontColor(this.note.includes('✓') || this.note.includes('填入') ? D.muted : D.danger)
          .width('100%').copyOption(CopyOptions.InApp).padding(14).backgroundColor(D.card)
          .borderRadius(D.radius).margin({ top: 16 })
      }
      if (this.genError.length > 0) {
        Text(this.genError).fontSize(13).fontColor(D.danger).width('100%').copyOption(CopyOptions.InApp)
          .padding(14).backgroundColor(D.card).borderRadius(D.radius).margin({ top: 16 })
      }
      Column() {
        this.StandaloneAction(this.saving ? '保存中…' : '保存并用于新文章', 'check',
          this.saving || this.generating || !this.loaded, (): void => { this.save(); })
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).margin({ top: 22 })

      if (this.editingId.length > 0) {
        Row({ space: 10 }) {
          Icon({ name: 'trash', iconSize: 18, color: D.danger })
          Text('删除模板').fontSize(17).fontColor(D.danger).layoutWeight(1)
        }.width('100%').constraintSize({ minHeight: 50 }).padding({ left: 14, right: 14 })
          .backgroundColor(D.card).borderRadius(D.radius).margin({ top: 22 }).enabled(!this.saving)
          .onClick((): void => { this.confirmRemove(this.editingId); })
      }
      Text('可用占位符：{{title}} 标题、{{kicker}} 刊头、{{bottom_line}} 一句话结论、{{summary}} 导语、{{timeline}} 时间线、' +
        '{{core_points}} 核心要点、{{analysis}} 立场、{{outlook}} 影响与走向、{{uncertainties}} 待核实、{{sources}} 编号来源、{{diagram}} 图解、{{hero}} 主图、{{images}} 图片、' +
        '{{reading}} 扩展阅读、{{references}} 来源、{{content}} 全部章节。缺少的章节会补在末尾。' +
        '模板最多 96 KiB，不支持脚本、表单、事件与外部样式。')
        .fontSize(12).fontColor(D.muted).width('100%').padding({ left: 14, right: 14, top: 16 })
    }.width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: D.gutter, right: D.gutter, bottom: this.navBarHeight + 28 })
  }

  @Builder
  StandaloneGenerator(): void {
    Column() {
      this.StandaloneSection('模板要求')
      Column() {
        TextInput({ text: this.editName, placeholder: '模板名称' })
          .fontSize(17).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent)
          .borderRadius(0).height(50).padding({ left: 14, right: 14 }).enabled(!this.generating)
          .onChange((value: string): void => { this.changeName(value); })
        Row().width('100%').height(0.5).backgroundColor(D.rule)
        TextArea({ text: this.aiPrompt, placeholder: '描述你想要的排版、色彩与阅读风格' })
          .fontSize(17).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent)
          .borderRadius(0).height(160).padding(14).enabled(!this.generating)
          .onChange((value: string): void => { this.aiPrompt = value; this.genError = ''; })
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).clip(true)
      Column() {
        this.StandaloneAction('生成模板草稿', 'spark', this.generating || this.aiPrompt.trim().length === 0,
          (): void => { this.generate(); })
        if (this.generating) {
          Row({ space: 8 }) {
            LoadingProgress().width(20).height(20).color(D.accent)
            Text('正在生成并校验模板…').fontSize(15).fontColor(D.muted)
          }.width('100%').padding({ left: 14, right: 14, bottom: 14 })
        }
      }.width('100%').backgroundColor(D.card).borderRadius(D.radius).margin({ top: 22 })
      Text('使用设置中选定的模型生成草稿。校验通过后可以预览、编辑并保存。')
        .fontSize(12).fontColor(D.muted).width('100%').padding({ left: 14, right: 14, top: 7 })
      if (this.genError.length > 0) {
        Text(this.genError).fontSize(13).fontColor(D.danger).width('100%').copyOption(CopyOptions.InApp)
          .padding(14).backgroundColor(D.card).borderRadius(D.radius).margin({ top: 22 })
      }
    }.width('100%').constraintSize({ maxWidth: 760 })
      .padding({ left: D.gutter, right: D.gutter, bottom: this.navBarHeight + 28 })
  }

  @Builder
  ListCard(): void {
    CardRow({ label: '阅读模板与选用', desc: '查看内置版式和当前选用状态',
      onRowClick: (): void => { router.pushUrl({ url: 'pages/DeepReadTemplateSettingsPage' }); } })
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
    }, (t: CustomTemplateDef): string => this.templateRowKey(t))
    if (this.templates.length > 0 && this.editingId.length > 0) {
      Column() {
        Row().width('100%').height(0.5).backgroundColor(LINE)
        Text(`删除「${this.editName}」`)
          .fontSize(13).fontColor(ERROR)
          .constraintSize({ minHeight: 44 })
          .onClick((): void => { this.confirmRemove(this.editingId); })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
    }
  }

  @Builder
  EditorCard(): void {
    Column() {
      TextInput({ text: this.editName, placeholder: '模板名称(如:暗夜极简)' })
        .enabled(!this.saving && !this.generating)
        .fontSize(15).fontColor(INK)
        .placeholderColor(INK4)
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
        inputEnabled: !this.saving && !this.generating,
        enableFullscreen: !this.saving && !this.generating,
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
        BtnFill({ label: this.saving ? '保存中…' : '保存并选用', onTap: (): void => { this.save(); } })
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
        .placeholderColor(INK4)
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
          onTap: (): void => { this.generate(); },
        })
      }
      .width('100%')
      .margin({ top: 8 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  PreviewSheet(id: number): void {
    Column() {
      Row() {
        Text(getProductKind() === 'deepread' ? '模板预览' : '预览(示例数据)')
          .fontSize(17).fontColor(getProductKind() === 'deepread' ? D.ink : INK).layoutWeight(1)
        Row() {
          Text(getProductKind() === 'deepread' ? '完成' : '关闭')
            .fontSize(17).fontColor(getProductKind() === 'deepread' ? D.accent : INK)
        }
          .constraintSize({ minWidth: 64, minHeight: 44 })
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .onClick((): void => { this.previewOpen = false; })
      }
      .width('100%')
      .padding({ left: 4, right: 4, bottom: 8 })
      ForEach([this.themeEpoch], (epoch: number): void => {
        Web({ src: 'about:blank', controller: this.webController })
        .width('100%').layoutWeight(1)
        .backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
        .javaScriptAccess(false)
        .fileAccess(false).domStorageAccess(false).multiWindowAccess(false)
        .onOverrideUrlLoading((request: WebResourceRequest): boolean => {
          const url: string = request.getRequestUrl();
          const documentUrl: string = 'https://amber-deepread.invalid/template-preview';
          return url !== documentUrl && !url.startsWith(documentUrl + '#') &&
            url !== 'about:blank' && !url.startsWith('about:blank#') &&
            !url.startsWith('data:') && !url.startsWith('#');
        })
        .onControllerAttached((): void => {
          this.attachPreviewWeb(id, epoch);
        })
        .onPageEnd((event: OnPageEndEvent): void => {
          this.onPreviewPageEnd(event.url, id, epoch);
        })
        .onErrorReceive((event: OnErrorReceiveEvent): void => {
          this.onPreviewError(event.request.isMainFrame(), id, epoch);
        })
      }, (epoch: number): string => `preview-${epoch}`)
    }
    .width('100%').height('100%')
    .padding({ left: 16, right: 16, top: 12 })
    .backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
  }
}
