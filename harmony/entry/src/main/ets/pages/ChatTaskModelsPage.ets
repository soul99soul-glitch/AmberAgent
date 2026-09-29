import { KeyboardAvoidMode, router } from '@kit.ArkUI';
import {
  loadProviders, DEFAULT_AUTO_MODEL_ID, DEFAULT_TITLE_PROMPT, DEFAULT_SUGGESTION_PROMPT,
  DEFAULT_COMPRESS_PROMPT, DEFAULT_OCR_PROMPT, resolveVisionRecognitionPrompt,
  taskModelReferenceFromId, memoryWorkerModelReferences, daydreamModelReferences,
} from '@amber/chat-domain';
import type { ProviderSetting, TaskModelReference, ModelType, VisionModelHealth } from '@amber/chat-domain';
import {
  getChatKvStore, describeChatModel, readTaskModelReference, saveTaskModelId, resolveTaskModelReference, resolveConfiguredTaskModel,
  resolveTaskModelReferenceChain, taskModelReferenceForChoice,
  saveTaskPrompt, loadTaskPrompts, TaskPrompts, ChatProviderChoice,
  TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, TASK_COMPRESS_MODEL_KEY, TASK_IMAGE_MODEL_KEY,
  TASK_TITLE_PROMPT_KEY, TASK_SUGGESTION_PROMPT_KEY, TASK_COMPRESS_PROMPT_KEY,
} from '../di/AppContainer.ets';
import { findTaskProviderModel } from '../platform_impl/TaskModelSelectionSupport.ets';
import {
  OCR_MODEL_KEY, getOcrPrompt, saveOcrPrompt, loadOcrSeed, probeEntryOcrHealth, OcrSeed,
} from '../platform_impl/OcrSupport.ets';
import { resolveImageGenChoice } from '../platform_impl/ImageGenTool.ets';
import {
  loadAgentRuntimeSnapshot, updateMemoryWorkerModels, MemoryWorkerModelPatch, MemoryWorkerGateSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { CenteredTitleBar, PlainSection, CardGroup, FooterNote } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT, accentSoftBg, MOTION_OVERLAY, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_MONO, FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';

interface TaskSlotView {
  task: string;
  reference: TaskModelReference | null;
  current: string;
  detail: string;
  identity: string;
  canClear: boolean;
}
interface TaskTexts {
  title: string;
  suggestion: string;
  compress: string;
  image: string;
  worker: string;
  daydream: string;
  ocr: string;
}
const taskText = (texts: TaskTexts, task: string): string => {
  switch (task) {
    case 'suggestion': return texts.suggestion;
    case 'compress': return texts.compress;
    case 'image': return texts.image;
    case 'worker': return texts.worker;
    case 'daydream': return texts.daydream;
    case 'ocr': return texts.ocr;
    default: return texts.title;
  }
};
const TASK_TITLES: TaskTexts = {
  title: '标题总结模型', suggestion: '聊天建议模型', compress: '压缩模型', image: '生图模型',
  worker: '记忆提取模型', daydream: 'Daydream 模型', ocr: '视觉识别模型',
};
const TASK_DESCRIPTIONS: TaskTexts = {
  title: '用于总结对话标题，推荐快速、便宜的模型。', suggestion: '用于生成对话建议，推荐快速、便宜的模型。',
  compress: '用于压缩对话历史。', image: '用于 generate_image；需要可用的 OpenAI 兼容生图模型，无聊天模型回退。',
  worker: '用于对话结束后提取候选记忆，轻量稳定即可。', daydream: '用于记忆整理与主题派生，建议使用推理较强的模型。',
  ocr: '当前聊天模型不能直接读取图片时，用它理解图片内容；无聊天模型回退。',
};
const TASK_ICONS: TaskTexts = {
  title: 'edit', suggestion: 'spark', compress: 'history', image: 'image', worker: 'brain', daydream: 'star', ocr: 'eye',
};

@Entry
@Component
struct ChatTaskModelsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State loaded: boolean = false;
  @State slots: TaskSlotView[] = [];
  @State error: string = '';
  @State saving: boolean = false;
  @State prompts: TaskPrompts = {
    titlePrompt: DEFAULT_TITLE_PROMPT, suggestionPrompt: DEFAULT_SUGGESTION_PROMPT, compressPrompt: DEFAULT_COMPRESS_PROMPT,
  };
  @State editingPrompt: string = '';
  @State promptDraft: string = '';
  @State ocrPromptRaw: string = DEFAULT_OCR_PROMPT;
  @State ocrHealthLabel: string = '检测中';
  @State colors: SSHUIColors = new SSHUIColors();
  @State chatModelLabel: string = '';
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    this.enableKeyboardAvoidance();
    this.alive = true; this.visible = true; this.refresh();
    describeChatModel().then((label: string): void => {
      if (this.alive) this.chatModelLabel = label;
    }).catch((): void => {});
  }
  onPageShow(): void {
    this.enableKeyboardAvoidance();
    if (!this.visible) { this.visible = true; this.refresh(); }
  }
  onPageHide(): void {
    this.restoreKeyboardAvoidance();
    this.visible = false; this.loadSeq++;
  }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  onBackPress(): boolean {
    if (this.editingPrompt.length === 0) return false;
    if (!this.saving) this.editingPrompt = '';
    return true;
  }
  private enableKeyboardAvoidance(): void {
    if (this.keyboardAvoidanceActive) return;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.keyboardAvoidanceActive = true;
  }
  private restoreKeyboardAvoidance(): void {
    if (!this.keyboardAvoidanceActive) return;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    this.keyboardAvoidanceActive = false;
  }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private taskKey(task: string): string {
    return task === 'title' ? TASK_TITLE_MODEL_KEY : task === 'suggestion' ? TASK_SUGGESTION_MODEL_KEY
      : task === 'image' ? TASK_IMAGE_MODEL_KEY : task === 'ocr' ? OCR_MODEL_KEY : TASK_COMPRESS_MODEL_KEY;
  }
  private slot(task: string): TaskSlotView {
    return this.slots.find((item: TaskSlotView): boolean => item.task === task) ?? {
      task: task, reference: null, current: '尚未读取配置', detail: '', identity: '', canClear: task !== 'ocr',
    };
  }
  private choiceName(choice: ChatProviderChoice | null): string {
    if (choice === null) return '暂无可用模型';
    return `${choice.provider.name} / ${choice.model.displayName || choice.model.modelId}`;
  }
  private referenceIdentity(reference: TaskModelReference): string {
    if (reference.kind === 'auto') return '';
    if (reference.kind === 'legacy') return `旧模型 UUID：${reference.modelId}（重新选择可固定 Provider）`;
    return `Provider 配置 ID：${reference.pair.providerId}\n模型配置 UUID：${reference.pair.modelId}`;
  }
  private rawName(providers: ProviderSetting[], reference: TaskModelReference, type: ModelType): string {
    const raw = findTaskProviderModel(providers, reference, type);
    return raw === null ? '' : `${raw.provider.name} / ${raw.model.displayName || raw.model.modelId}`;
  }
  private configuredName(providers: ProviderSetting[], reference: TaskModelReference, type: ModelType,
    choice: ChatProviderChoice | null): string {
    if (reference.kind !== 'legacy') return this.rawName(providers, reference, type);
    if (choice === null) return '';
    const container: ProviderSetting | undefined = providers.find((provider: ProviderSetting): boolean => provider.id === choice.providerContainerId);
    return `${container?.name ?? choice.provider.name} / ${choice.model.displayName || choice.model.modelId}`;
  }
  private invalidSlot(task: string, message: string): TaskSlotView {
    return { task: task, reference: null, current: '配置无法解析', detail: message, identity: '', canClear: task !== 'ocr' };
  }

  private async chatSlot(task: string, providers: ProviderSetting[]): Promise<TaskSlotView> {
    try {
      const reference: TaskModelReference = await readTaskModelReference(this.taskKey(task));
      const configured: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'chat');
      const actual: ChatProviderChoice | null = await resolveTaskModelReference(reference);
      const name: string = this.configuredName(providers, reference, 'chat', configured);
      return {
        task: task, reference: reference,
        current: reference.kind === 'auto' ? '跟随当前聊天模型' : configured === null ? '所选模型不可用' : name || this.choiceName(configured),
        detail: reference.kind === 'auto' || configured === null
          ? `设置页当前回退参考：${this.choiceName(actual)}；实际任务跟随本轮聊天选择。`
          : `实际请求服务商：${configured.provider.name} · ${configured.provider.type}`,
        identity: this.referenceIdentity(reference), canClear: reference.kind !== 'auto',
      };
    } catch (error) { return this.invalidSlot(task, error instanceof Error ? error.message : String(error)); }
  }

  private async memorySlot(task: string, providers: ProviderSetting[], worker: MemoryWorkerGateSnapshot,
    compress: TaskModelReference | null, chat: ChatProviderChoice | null): Promise<TaskSlotView> {
    const daydream: boolean = task === 'daydream';
    const reference: TaskModelReference = daydream
      ? taskModelReferenceFromId(worker.daydreamModelId, worker.daydreamProviderId)
      : taskModelReferenceFromId(worker.modelId, worker.providerId);
    const follow: boolean = daydream ? worker.daydreamFollowCompressModel : worker.followCompressModel;
    const configured: ChatProviderChoice | null = await resolveConfiguredTaskModel(reference, 'chat');
    const chatReference: TaskModelReference = chat === null ? { kind: 'auto' } : taskModelReferenceForChoice(chat);
    const actual: ChatProviderChoice | null = compress === null ? null : await resolveTaskModelReferenceChain(daydream
      ? daydreamModelReferences(worker, compress, chatReference) : memoryWorkerModelReferences(worker, compress, chatReference), chat ?? undefined);
    return {
      task: task, reference: reference,
      current: reference.kind === 'auto' ? follow ? '跟随压缩模型' : daydream ? '按记忆提取设置跟随' : '跟随当前聊天模型'
        : configured === null ? '所选模型不可用' : this.configuredName(providers, reference, 'chat', configured) || this.choiceName(configured),
      detail: compress === null ? '压缩模型配置无法解析，请先修正该配置。'
        : `设置页当前解析：${this.choiceName(actual)}；聊天回退以实际运行轮次为准。`,
      identity: this.referenceIdentity(reference), canClear: reference.kind !== 'auto' || !follow,
    };
  }

  private async imageSlot(providers: ProviderSetting[]): Promise<TaskSlotView> {
    try {
      const reference: TaskModelReference = await readTaskModelReference(TASK_IMAGE_MODEL_KEY);
      const choice: ChatProviderChoice | null = await resolveImageGenChoice();
      const name: string = this.configuredName(providers, reference, 'image', choice);
      return {
        task: 'image', reference: reference,
        current: reference.kind === 'auto' ? '未配置' : choice === null ? '所选模型不可用' : name || this.choiceName(choice),
        detail: choice === null ? `${name.length > 0 ? `已配置：${name}。` : ''}生图当前不可用，没有聊天模型回退。`
          : `实际请求服务商：${choice.provider.name} · ${choice.provider.type}`,
        identity: this.referenceIdentity(reference), canClear: reference.kind !== 'auto',
      };
    } catch (error) { return this.invalidSlot('image', error instanceof Error ? error.message : String(error)); }
  }

  private async refresh(): Promise<void> {
    const seq: number = ++this.loadSeq;
    try {
      const providers: ProviderSetting[] = await loadProviders(getChatKvStore()) ?? [];
      const worker: MemoryWorkerGateSnapshot = (await loadAgentRuntimeSnapshot(getChatKvStore())).memoryWorker;
      const prompts: TaskPrompts = await loadTaskPrompts();
      const ocrPrompt: string = await getOcrPrompt(getChatKvStore());
      const title: TaskSlotView = await this.chatSlot('title', providers);
      const suggestion: TaskSlotView = await this.chatSlot('suggestion', providers);
      const compress: TaskSlotView = await this.chatSlot('compress', providers);
      const image: TaskSlotView = await this.imageSlot(providers);
      const chat: ChatProviderChoice | null = await resolveTaskModelReference({ kind: 'auto' });
      const extraction: TaskSlotView = await this.memorySlot('worker', providers, worker, compress.reference, chat);
      const daydream: TaskSlotView = await this.memorySlot('daydream', providers, worker, compress.reference, chat);
      let ocr: TaskSlotView;
      let ocrSeed: OcrSeed | null = null;
      try {
        ocrSeed = await loadOcrSeed(getChatKvStore());
        const reference: TaskModelReference = ocrSeed.ocrReference;
        const name: string = this.configuredName(providers, reference, 'chat', ocrSeed.ocrChoice);
        ocr = {
          task: 'ocr', reference: reference,
          current: reference.kind === 'auto' ? '未配置' : ocrSeed.ocrChoice === null ? '所选模型不可用' : name || this.choiceName(ocrSeed.ocrChoice),
          detail: ocrSeed.ocrChoice === null ? '视觉识别当前不可用，没有聊天模型回退。' : `实际请求服务商：${ocrSeed.ocrChoice.provider.name}`,
          identity: this.referenceIdentity(reference), canClear: false,
        };
      } catch (error) { ocr = this.invalidSlot('ocr', error instanceof Error ? error.message : String(error)); }
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.slots = [title, suggestion, compress, image, extraction, daydream, ocr];
      this.prompts = prompts; this.ocrPromptRaw = ocrPrompt; this.loaded = true; this.error = '';
      this.ocrHealthLabel = ocrSeed === null ? '不可用：配置无法解析' : '检测中';
      if (ocrSeed !== null) this.probeOcrHealth(ocrSeed, seq);
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
      }
    }
  }
  private probeOcrHealth(seed: OcrSeed, seq: number): void {
    probeEntryOcrHealth(seed).then((health: VisionModelHealth): void => {
      if (this.alive && this.visible && seq === this.loadSeq) this.ocrHealthLabel = health.label;
    }).catch((error: Error): void => {
      if (this.alive && this.visible && seq === this.loadSeq) this.ocrHealthLabel = `不可用：${error.message}`;
    });
  }
  private openPicker(task: string): void { router.pushUrl({ url: 'pages/ChatTaskModelPickerPage', params: { task: task } }); }
  private async clearTaskModel(task: string): Promise<void> {
    if (this.saving) return;
    this.saving = true; this.error = '';
    try {
      if (task === 'worker' || task === 'daydream') {
        const patch: MemoryWorkerModelPatch = task === 'daydream'
          ? { daydreamModelId: DEFAULT_AUTO_MODEL_ID, daydreamProviderId: null, daydreamFollowCompressModel: true }
          : { modelId: DEFAULT_AUTO_MODEL_ID, providerId: null, followCompressModel: true };
        await updateMemoryWorkerModels(getChatKvStore(), patch);
      } else await saveTaskModelId(this.taskKey(task), DEFAULT_AUTO_MODEL_ID);
      if (this.alive && this.visible) await this.refresh();
    } catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : String(error); }
    finally { this.saving = false; }
  }

  private openPromptEditor(task: string): void {
    this.promptDraft = task === 'title' ? this.prompts.titlePrompt : task === 'suggestion' ? this.prompts.suggestionPrompt
      : task === 'compress' ? this.prompts.compressPrompt : resolveVisionRecognitionPrompt(this.ocrPromptRaw);
    this.editingPrompt = task;
  }
  private promptKey(): string {
    return this.editingPrompt === 'title' ? TASK_TITLE_PROMPT_KEY : this.editingPrompt === 'suggestion' ? TASK_SUGGESTION_PROMPT_KEY : TASK_COMPRESS_PROMPT_KEY;
  }
  private promptDefault(): string {
    return this.editingPrompt === 'title' ? DEFAULT_TITLE_PROMPT : this.editingPrompt === 'suggestion' ? DEFAULT_SUGGESTION_PROMPT
      : this.editingPrompt === 'compress' ? DEFAULT_COMPRESS_PROMPT : DEFAULT_OCR_PROMPT;
  }
  private async savePrompt(): Promise<void> {
    if (this.saving) return;
    this.saving = true; this.error = '';
    try {
      if (this.editingPrompt === 'ocr') await saveOcrPrompt(getChatKvStore(), this.promptDraft);
      else await saveTaskPrompt(this.promptKey(), this.promptDraft);
      if (this.alive && this.visible) { this.editingPrompt = ''; await this.refresh(); }
    } catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : String(error); }
    finally { this.saving = false; }
  }
  private async resetPrompt(): Promise<void> {
    if (this.saving) return;
    const value: string = this.promptDefault();
    this.promptDraft = value; this.saving = true; this.error = '';
    try {
      if (this.editingPrompt === 'ocr') await saveOcrPrompt(getChatKvStore(), value);
      else await saveTaskPrompt(this.promptKey(), value);
      if (this.alive && this.visible) await this.refresh();
    } catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : String(error); }
    finally { this.saving = false; }
  }
  private hasPrompt(task: string): boolean { return task === 'title' || task === 'suggestion' || task === 'compress' || task === 'ocr'; }
  private clearLabel(task: string): string {
    return task === 'image' ? '恢复为未配置' : task === 'worker' || task === 'daydream' ? '恢复跟随压缩模型' : '恢复跟随当前聊天模型';
  }

  // 任务行(iOS AmberFormRow 结构):着色图标座 + 标题/简述 + 当前模型(mono 值)+ 箭头;
  // 行下小字区放诊断信息,动作走文本(提示词/恢复跟随),不再用方角灰块按钮
  @Builder
  private TaskRow(task: string, last: boolean): void {
    Column() {
      Row({ space: 12 }) {
        Row() { Icon({ name: taskText(TASK_ICONS, task), iconSize: 15, color: this.accentColor }) }
          .width(28).height(28).borderRadius(7).backgroundColor(accentSoftBg(this.accentColor))
          .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        Column({ space: 2 }) {
          Text(taskText(TASK_TITLES, task)).fontSize(16).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.ink).maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(taskText(TASK_DESCRIPTIONS, task)).fontSize(12).fontColor(this.colors.secondary)
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(this.slot(task).current).fontSize(TEXT_STYLES.meta.size).fontFamily(FONT_MONO_MEDIUM)
          .fontColor(this.colors.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.End).constraintSize({ maxWidth: '38%' })
        Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
      }.width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
      .enabled(!this.saving).onClick((): void => { this.openPicker(task); })
      Column({ space: 6 }) {
        Text(this.slot(task).detail).fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
        if (task === 'ocr') Text(`健康检测：${this.ocrHealthLabel}`).fontSize(12).fontColor(this.colors.secondary).width('100%')
        if (this.slot(task).identity.length > 0) Text(this.slot(task).identity).fontSize(11).fontColor(this.colors.muted)
          .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
        if (this.hasPrompt(task) || this.slot(task).canClear) {
          Row({ space: 20 }) {
            if (this.hasPrompt(task)) Text('提示词').fontSize(14).fontWeight(FontWeight.Medium)
              .fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColor).constraintSize({ minHeight: 44 })
              .enabled(!this.saving).onClick((): void => { this.openPromptEditor(task); })
            if (this.slot(task).canClear) Text(this.clearLabel(task)).fontSize(14).fontFamily(FONT_SANS_MEDIUM)
              .fontColor(this.colors.muted).constraintSize({ minHeight: 44 })
              .enabled(!this.saving).onClick((): void => { this.clearTaskModel(task); })
            Blank()
          }.width('100%').alignItems(VerticalAlign.Center)
        }
      }.width('100%').padding({ left: 40 })
      if (!last) Row().width('100%').height(1).backgroundColor(this.colors.line).margin({ top: 8 })
    }.width('100%').padding({ left: 14, right: 14, top: 10, bottom: 10 })
  }
  @Builder
  private ChatModelCard(): void {
    // iOS ModelDefaultsView 聊天段:当前聊天模型(进入选择器固定 Provider+模型)
    Row({ space: 12 }) {
      Row() { Icon({ name: 'cpu', iconSize: 15, color: this.accentColor }) }
        .width(28).height(28).borderRadius(7).backgroundColor(accentSoftBg(this.accentColor))
        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
      Column({ space: 2 }) {
        Text('聊天模型').fontSize(16).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.colors.ink).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text('来自已启用服务商的聊天模型;已有会话保留当时的模型记录')
          .fontSize(12).fontColor(this.colors.secondary).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }.alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text(this.chatModelLabel.length > 0 ? this.chatModelLabel : '未选择')
        .fontSize(TEXT_STYLES.meta.size).fontFamily(FONT_MONO_MEDIUM)
        .fontColor(this.colors.muted).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .textAlign(TextAlign.End).constraintSize({ maxWidth: '38%' })
      Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
    }.width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
    .padding({ left: 14, right: 14, top: 10, bottom: 10 }).enabled(!this.saving)
    .onClick((): void => { router.pushUrl({ url: 'pages/ChatModelPickerPage' }); })
  }

  @Builder
  private TaskCard(): void {
    this.TaskRow('title', false)
    this.TaskRow('suggestion', false)
    this.TaskRow('compress', false)
    this.TaskRow('image', false)
    this.TaskRow('worker', false)
    this.TaskRow('daydream', false)
    this.TaskRow('ocr', true)
  }
  // 提示词编辑 sheet:与灵魂编辑器同族(顶角 r20、mono 文本域 r12、文本动作)
  @Builder
  private PromptEditor(task: string): void {
    Column() {
      Blank().onClick((): void => { if (!this.saving) this.editingPrompt = ''; })
      Column({ space: 10 }) {
        Row() {
          Text(taskText(TASK_TITLES, task)).fontSize(16).fontWeight(FontWeight.Medium)
            .fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.ink).layoutWeight(1)
          Text('重置为默认').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.muted)
            .constraintSize({ minHeight: 44 }).padding({ left: 12 })
            .enabled(!this.saving).onClick((): void => { this.resetPrompt(); })
        }.width('100%').alignItems(VerticalAlign.Center)
        Scroll() {
          Column({ space: 6 }) {
            Text(task === 'compress' ? '变量：{content}、{target_tokens}、{additional_context}'
              : task === 'ocr' ? '变量：图片数据：{images}' : '变量：对话内容 {content}，语言 {locale}')
              .fontSize(12).fontColor(this.colors.secondary).width('100%')
            if (this.error.length > 0) {
              Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            }
          }.width('100%')
        }.width('100%').constraintSize({ maxHeight: 64 }).align(Alignment.TopStart).scrollBar(BarState.Auto)
        TextArea({ text: this.promptDraft }).width('100%').layoutWeight(1).fontSize(13).fontFamily(FONT_MONO)
          .fontColor(this.colors.ink).backgroundColor(this.colors.inset).borderRadius(12).padding(12)
          .enabled(!this.saving).onChange((value: string): void => { this.promptDraft = value; })
        Row() {
          Text('取消').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.muted)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.saving).onClick((): void => { this.editingPrompt = ''; })
          Text(this.saving ? '保存中' : '保存').fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.accentColor).layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.saving).onClick((): void => { this.savePrompt(); })
        }.width('100%')
      }.width('100%').height('85%').padding({ left: 18, right: 18, top: 16, bottom: 14 + this.navBarHeight })
        .backgroundColor(this.colors.surface).borderRadius({ topLeft: 20, topRight: 20 })
        .transition(TransitionEffect.translate({ y: 160 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }.width('100%').height('100%').backgroundColor('#66000000').justifyContent(FlexAlign.End)
      .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        CenteredTitleBar({ title: '模型与提示词' })
        Scroll() {
          Column({ space: 10 }) {
            if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            if (!this.loaded) Text('加载中…').fontSize(14).fontColor(this.colors.secondary).width('100%')
            else {
              PlainSection({ label: '聊天' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.ChatModelCard(); } })
              PlainSection({ label: '辅助任务' })
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.TaskCard(); } })
              FooterNote({ text: '固定选择绑定服务商配置与模型 UUID。旧 UUID 保持兼容，重新选择后固定；辅助任务的聊天回退以实际运行轮次为准。' })
            }
          }.width('100%').padding({ left: 16, right: 16, top: 2, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart)
      }.width('100%').height('100%').backgroundColor(this.colors.background)
      if (this.editingPrompt.length > 0) this.PromptEditor(this.editingPrompt)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%')
  }
}
