// Task selection pins the provider container and the model configuration UUID.
import { router } from '@kit.ArkUI';
import { loadProviders, hasUsableAuth, taskModelReferenceFromId } from '@amber/chat-domain';
import type { ProviderSetting, TaskModelReference, TaskModelPair } from '@amber/chat-domain';
import {
  getChatKvStore, readTaskModelReference, saveTaskModelPair, resolveConfiguredTaskModel, ChatProviderChoice,
  TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, TASK_COMPRESS_MODEL_KEY, TASK_IMAGE_MODEL_KEY,
} from '../di/AppContainer.ets';
import { loadAgentRuntimeSnapshot, updateMemoryWorkerModels, AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { OCR_MODEL_KEY, loadOcrSeed } from '../platform_impl/OcrSupport.ets';
import { resolveImageGenChoice } from '../platform_impl/ImageGenTool.ets';
import { findTaskProviderModel } from '../platform_impl/TaskModelSelectionSupport.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';

interface TaskModelPickerParams { task?: string; }
interface TaskModelRow {
  providerId: string;
  providerName: string;
  providerType: string;
  requestProviderLabel: string;
  modelUuid: string;
  displayName: string;
  modelId: string;
  selected: boolean;
  viewVersion: number;
}
interface TaskTexts {
  title: string;
  suggestion: string;
  compress: string;
  worker: string;
  daydream: string;
  ocr: string;
  image: string;
}
const taskText = (texts: TaskTexts, task: string): string => {
  switch (task) {
    case 'suggestion': return texts.suggestion;
    case 'compress': return texts.compress;
    case 'worker': return texts.worker;
    case 'daydream': return texts.daydream;
    case 'ocr': return texts.ocr;
    case 'image': return texts.image;
    default: return texts.title;
  }
};
const TASK_TITLES: TaskTexts = {
  title: '标题总结模型', suggestion: '聊天建议模型', compress: '压缩模型',
  worker: '记忆提取模型', daydream: 'Daydream 模型', ocr: '视觉识别模型', image: '生图模型',
};

@Entry
@Component
struct ChatTaskModelPickerPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State rows: TaskModelRow[] = [];
  @State loaded: boolean = false;
  @State task: string = 'title';
  @State configured: string = '';
  @State error: string = '';
  @State saving: boolean = false;
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    const params: TaskModelPickerParams = router.getParams() as TaskModelPickerParams;
    const task: string = params?.task ?? 'title';
    this.task = task === 'suggestion' || task === 'compress' || task === 'worker' || task === 'daydream'
      || task === 'ocr' || task === 'image' ? task : 'title';
    this.alive = true; this.visible = true; this.refresh();
  }
  onPageShow(): void { if (!this.visible) { this.visible = true; this.refresh(); } }
  onPageHide(): void { this.visible = false; this.loadSeq++; }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private taskKey(): string {
    return this.task === 'title' ? TASK_TITLE_MODEL_KEY : this.task === 'suggestion' ? TASK_SUGGESTION_MODEL_KEY
      : this.task === 'image' ? TASK_IMAGE_MODEL_KEY : this.task === 'ocr' ? OCR_MODEL_KEY : TASK_COMPRESS_MODEL_KEY;
  }
  private async readReference(): Promise<TaskModelReference> {
    if (this.task === 'worker' || this.task === 'daydream') {
      const snapshot: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(getChatKvStore());
      return this.task === 'worker'
        ? taskModelReferenceFromId(snapshot.memoryWorker.modelId, snapshot.memoryWorker.providerId)
        : taskModelReferenceFromId(snapshot.memoryWorker.daydreamModelId, snapshot.memoryWorker.daydreamProviderId);
    }
    return readTaskModelReference(this.taskKey());
  }
  private referenceLabel(reference: TaskModelReference): string {
    if (reference.kind === 'auto') return this.task === 'ocr' || this.task === 'image' ? '当前未配置' : '当前按任务规则跟随';
    if (reference.kind === 'legacy') return `旧选择（尚未固定 Provider）：${reference.modelId}`;
    return `已固定 Provider：${reference.pair.providerId}\n模型配置 UUID：${reference.pair.modelId}`;
  }

  private async refresh(): Promise<void> {
    const seq: number = ++this.loadSeq;
    try {
      const providers: ProviderSetting[] = await loadProviders(getChatKvStore()) ?? [];
      let reference: TaskModelReference | null = null;
      let referenceError: string = '';
      try { reference = await this.readReference(); }
      catch (error) { referenceError = error instanceof Error ? error.message : String(error); }
      const selected = reference === null ? null : findTaskProviderModel(providers, reference, this.task === 'image' ? 'image' : 'chat');
      let selectedProviderId: string = selected?.provider.id ?? '';
      let selectedModelId: string = selected?.model.id ?? '';
      if (reference?.kind === 'legacy') {
        const choice: ChatProviderChoice | null = this.task === 'ocr' ? (await loadOcrSeed(getChatKvStore())).ocrChoice
          : this.task === 'image' ? await resolveImageGenChoice() : await resolveConfiguredTaskModel(reference, 'chat');
        selectedProviderId = choice?.providerContainerId ?? '';
        selectedModelId = choice?.model.id ?? '';
      }
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      const usable: TaskModelRow[] = [];
      for (const provider of providers) {
        for (const model of provider.models) {
          if (this.task === 'image' ? model.type !== 'image' : model.type !== 'chat') continue;
          if (this.task === 'ocr' && !model.inputModalities.includes('image')) continue;
          const effective: ProviderSetting = model.providerOverwrite ?? provider;
          if (!hasUsableAuth(effective)) continue;
          if (this.task === 'image' && effective.type !== 'openai') continue;
          usable.push({
            providerId: provider.id, providerName: provider.name, providerType: provider.type,
            requestProviderLabel: model.providerOverwrite === null ? '' : `${effective.name} · ${effective.type}`,
            modelUuid: model.id, displayName: model.displayName || model.modelId, modelId: model.modelId,
            selected: selectedProviderId === provider.id && selectedModelId === model.id,
            viewVersion: seq,
          });
        }
      }
      this.rows = usable;
      this.configured = reference === null ? '已保存的任务模型配置无法读取。' : this.referenceLabel(reference);
      if (reference !== null && reference.kind !== 'auto' && !usable.some((row: TaskModelRow): boolean => row.selected)) {
        this.configured += '\n当前选择不可用；选择下方模型可替换此配置。';
      }
      this.error = referenceError; this.loaded = true;
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true; this.error = error instanceof Error ? error.message : String(error);
      }
    }
  }

  private async pick(row: TaskModelRow): Promise<void> {
    if (this.saving) return;
    this.saving = true; this.error = '';
    try {
      if (this.task === 'worker') {
        await updateMemoryWorkerModels(getChatKvStore(), {
          modelId: row.modelUuid, providerId: row.providerId, followCompressModel: false,
        });
      } else if (this.task === 'daydream') {
        await updateMemoryWorkerModels(getChatKvStore(), {
          daydreamModelId: row.modelUuid, daydreamProviderId: row.providerId, daydreamFollowCompressModel: false,
        });
      } else {
        const pair: TaskModelPair = { providerId: row.providerId, modelId: row.modelUuid };
        await saveTaskModelPair(this.taskKey(), pair);
      }
      if (this.alive && this.visible) router.back();
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? error.message : String(error);
    } finally { this.saving = false; }
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: taskText(TASK_TITLES, this.task) })
      Scroll() {
        Column({ space: 10 }) {
          if (this.error.length > 0) Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
          if (!this.loaded) Text('加载中…').fontSize(14).fontColor(this.colors.secondary).width('100%')
          else {
            Text(this.configured).fontSize(12).fontColor(this.colors.secondary).width('100%')
              .wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
            Text('选择会保存服务商配置 ID 与模型配置 UUID；API 模型名仅用于实际请求。')
              .fontSize(12).fontColor(this.colors.muted).width('100%')
            if (this.rows.length === 0) {
              Text('没有符合此任务的可用 Provider/模型。').fontSize(14).fontColor(this.colors.ink).width('100%')
              Text(this.task === 'ocr' ? '视觉识别需要支持图片输入的聊天模型。' : this.task === 'image' ? '需要配置生图模型；实际生成还要求可用的 OpenAI 兼容服务商。' : '请先配置服务商凭据和聊天模型。')
                .fontSize(13).fontColor(this.colors.secondary).width('100%')
            }
            ForEach(this.rows, (row: TaskModelRow): void => {
              Row({ space: 12 }) {
                Column({ space: 4 }) {
                  Text(row.displayName).fontSize(15).fontWeight(FontWeight.Medium).fontColor(row.selected ? this.accentColor : this.colors.ink)
                    .width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(`${row.providerName} · ${row.providerType}`).fontSize(12).fontColor(this.colors.secondary)
                    .width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                  if (row.requestProviderLabel.length > 0) Text(`实际请求：${row.requestProviderLabel}`).fontSize(12)
                    .fontColor(this.colors.secondary).width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(row.modelId).fontSize(12).fontColor(this.colors.muted).width('100%').wordBreak(WordBreak.BREAK_ALL)
                }.alignItems(HorizontalAlign.Start).layoutWeight(1)
                if (row.selected) Text('当前').fontSize(12).fontColor(this.accentColor)
                Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
              }.width('100%').constraintSize({ minHeight: 64 }).padding(16).borderRadius(14)
                .backgroundColor(row.selected ? this.colors.inset : this.colors.surface).enabled(!this.saving)
                .onClick((): void => { this.pick(row); })
            }, (row: TaskModelRow): string => `${row.providerId.length}:${row.providerId}${row.modelUuid}:${row.viewVersion}`)
            Button(this.saving ? '保存中' : '服务商设置').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
              .fontColor(this.accentColor).backgroundColor(this.colors.surface).enabled(!this.saving)
              .onClick((): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); })
          }
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
