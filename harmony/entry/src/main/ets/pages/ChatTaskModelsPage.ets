// ChatTaskModelsPage — 任务模型设置面(D-094)
// Android 锚点:SettingModelPage.kt 辅助任务区(:433-700)—
//   title(:433-470)/suggestion(:472-509)/compress(:556-592):
//     allowClear;follows = findModelById(id) == null → '跟随当前聊天模型' +
//     '当前使用：X';onClear → DEFAULT_AUTO_MODEL_ID;'提示词' → ModelPromptSheet
//     (变量 hint + 重置为默认 → DEFAULT_*_PROMPT)
//   worker(:594-645)/daydream(:647-700):
//     follows = followCompressModel && findModelById(modelId) == null →
//     '跟随压缩模型' + '当前跟随压缩模型：X';onClear → AUTO + follow=true;
//     onSelect → id + follow=false
import { ACCENT, BG, INK, INK2, INK3, LINE, OVERLAY, SURFACE } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { router } from '@kit.ArkUI';
import {
  loadProviders, hasUsableAuth, DEFAULT_AUTO_MODEL_ID,
  DEFAULT_TITLE_PROMPT, DEFAULT_SUGGESTION_PROMPT, DEFAULT_COMPRESS_PROMPT,
  DEFAULT_OCR_PROMPT, resolveVisionRecognitionPrompt, probeVisionModelHealth,
} from '@amber/chat-domain';
import type { ProviderSetting, ProviderModel, UIMessage, VisionModelHealth } from '@amber/chat-domain';
import {
  getChatKvStore, saveTaskModelId, loadTaskModelIds, TaskModelIds,
  resolveTaskChatModelChoice, saveTaskPrompt, loadTaskPrompts, TaskPrompts,
  ChatProviderChoice,
  TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, TASK_COMPRESS_MODEL_KEY,
  TASK_TITLE_PROMPT_KEY, TASK_SUGGESTION_PROMPT_KEY, TASK_COMPRESS_PROMPT_KEY,
} from '../di/AppContainer.ets';
import { findModelByIdInProviders } from '../platform_impl/OcrSupport.ets';
import {
  getOcrModelId, getOcrPrompt, saveOcrPrompt, findProviderOfModel, generateVisionText,
} from '../platform_impl/OcrSupport.ets';
import {
  loadAgentRuntimeSnapshot, updateMemoryWorkerModels, AgentRuntimeSnapshot,
  MemoryWorkerModelPatch,
} from '../platform_impl/AgentRuntimePrefs.ets';


@Entry
@Component
struct ChatTaskModelsPage {
  @State loaded: boolean = false;
  @State providers: ProviderSetting[] = [];
  @State titleUuid: string = DEFAULT_AUTO_MODEL_ID;
  @State suggestionUuid: string = DEFAULT_AUTO_MODEL_ID;
  @State compressUuid: string = DEFAULT_AUTO_MODEL_ID;
  @State workerUuid: string = DEFAULT_AUTO_MODEL_ID;
  @State workerFollow: boolean = true;
  @State daydreamUuid: string = DEFAULT_AUTO_MODEL_ID;
  @State daydreamFollow: boolean = true;
  // 跟随时的回退名(Android fallbackModel?.displayName)
  @State titleFallback: string = '';
  @State suggestionFallback: string = '';
  @State compressFallback: string = '';
  @State prompts: TaskPrompts = {
    titlePrompt: DEFAULT_TITLE_PROMPT,
    suggestionPrompt: DEFAULT_SUGGESTION_PROMPT,
    compressPrompt: DEFAULT_COMPRESS_PROMPT,
  };
  // 提示词编辑 overlay('' = 关闭;'ocr' = 视觉识别提示词,D-098)
  @State editingPrompt: string = '';
  @State promptDraft: string = '';
  // D-098:OCR(ChatPrefs.kt:80,83 — 未配置 = random uuid;prompt 缺失 → DEFAULT)
  @State ocrUuid: string = '';
  @State ocrPromptRaw: string = DEFAULT_OCR_PROMPT;
  // D-111:视觉健康标签(VisionModelHealthChecker;初始 checking '检测中' 逐字)
  @State ocrHealthLabel: string = '检测中';
  // 健康探测用原始 providers(Android settings.providers 未过滤;显示用 usable 过滤列表)
  private rawProviders: ProviderSetting[] = [];

  aboutToAppear(): void {
    this.refresh();
  }

  // 子选择页返回后重读选中名
  onPageShow(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const kv = getChatKvStore();
    const providers: ProviderSetting[] | null = await loadProviders(kv);
    const usable: ProviderSetting[] = [];
    if (providers !== null) {
      this.rawProviders = providers;
      for (const p of providers) {
        if (hasUsableAuth(p) && p.models.some((m: ProviderModel): boolean => m.type === 'chat')) {
          usable.push(p);
        }
      }
    }
    const ids: TaskModelIds = await loadTaskModelIds();
    const snap: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(kv);
    const prompts: TaskPrompts = await loadTaskPrompts();
    this.providers = usable;
    this.titleUuid = ids.titleModelId;
    this.suggestionUuid = ids.suggestionModelId;
    this.compressUuid = ids.compressModelId;
    this.workerUuid = snap.memoryWorker.modelId;
    this.workerFollow = snap.memoryWorker.followCompressModel;
    this.daydreamUuid = snap.memoryWorker.daydreamModelId;
    this.daydreamFollow = snap.memoryWorker.daydreamFollowCompressModel;
    this.prompts = prompts;
    // fallback 名:resolveTaskChatModel(id)(:447/:486/:569/:601-602)
    const titleChoice: ChatProviderChoice | null = await resolveTaskChatModelChoice(ids.titleModelId);
    const suggChoice: ChatProviderChoice | null = await resolveTaskChatModelChoice(ids.suggestionModelId);
    const compChoice: ChatProviderChoice | null = await resolveTaskChatModelChoice(ids.compressModelId);
    this.titleFallback = titleChoice !== null ? titleChoice.model.displayName : '';
    this.suggestionFallback = suggChoice !== null ? suggChoice.model.displayName : '';
    this.compressFallback = compChoice !== null ? compChoice.model.displayName : '';
    // D-098:OCR 设置面读侧(D-071b 同函数)
    this.ocrUuid = await getOcrModelId(kv);
    this.ocrPromptRaw = await getOcrPrompt(kv);
    this.loaded = true;
    // D-111:健康探测(produceState key = ocrModelId + providers;refresh 即重键)
    this.probeOcrHealth();
  }

  // D-111:SettingModelPage.kt:518-526 — initial checking();IO 探测 → label;
  //   generateText 与 OcrTransformer 同口径(OcrSupport.generateVisionText,D-071b)
  private probeOcrHealth(): void {
    this.ocrHealthLabel = '检测中';
    const providers: ProviderSetting[] = this.rawProviders;
    const ocrId: string = this.ocrUuid;
    probeVisionModelHealth({
      findOcrModel: (): ProviderModel | null => findModelByIdInProviders(providers, ocrId),
      modelSupportsImage: (m: ProviderModel): boolean => m.inputModalities.includes('image'),
      findProvider: (m: ProviderModel): ProviderSetting | null =>
        findProviderOfModel(providers, m),
      generateText: (p: ProviderSetting, m: ProviderModel, msgs: UIMessage[]): Promise<string | null> =>
        generateVisionText(p, m, msgs),
    }).then((h: VisionModelHealth): void => {
      this.ocrHealthLabel = h.label;
    }).catch((): void => {
      // probe 内部 runCatching 语义不拒promise;此处仅兜底装配期异常
      this.ocrHealthLabel = '不可用：检测失败';
    });
  }

  // follows = findModelById(id) == null(:446/:485/:568)
  private followsChat(uuid: string): boolean {
    const m: ProviderModel | null = findModelByIdInProviders(this.providers, uuid);
    return m === null || m.type !== 'chat';
  }

  private selectedName(uuid: string): string {
    const m: ProviderModel | null = findModelByIdInProviders(this.providers, uuid);
    if (m === null || m.type !== 'chat') return '';
    return m.displayName.length > 0 ? m.displayName : m.modelId;
  }

  private openPicker(task: string): void {
    router.pushUrl({ url: 'pages/ChatTaskModelPickerPage', params: { task: task } });
  }

  // onClear → DEFAULT_AUTO_MODEL_ID(:451-453/:490-492/:573-575)
  private clearTaskModel(task: string): void {
    const key: string = task === 'title' ? TASK_TITLE_MODEL_KEY
      : task === 'suggestion' ? TASK_SUGGESTION_MODEL_KEY : TASK_COMPRESS_MODEL_KEY;
    saveTaskModelId(key, DEFAULT_AUTO_MODEL_ID).then((): void => { this.refresh(); });
  }

  // worker/daydream onClear → AUTO + follow=true(:619-630/:678-683)
  private clearWorkerModel(daydream: boolean): void {
    const patch: MemoryWorkerModelPatch = daydream
      ? { daydreamModelId: DEFAULT_AUTO_MODEL_ID, daydreamFollowCompressModel: true }
      : { modelId: DEFAULT_AUTO_MODEL_ID, followCompressModel: true };
    updateMemoryWorkerModels(getChatKvStore(), patch).then((): void => { this.refresh(); });
  }

  private openPromptEditor(task: string): void {
    // D-098:ocr 展示 resolveVisionRecognitionPrompt(settings.ocrPrompt)(:543 逐字)
    this.promptDraft = task === 'title' ? this.prompts.titlePrompt
      : task === 'suggestion' ? this.prompts.suggestionPrompt
        : task === 'compress' ? this.prompts.compressPrompt
          : resolveVisionRecognitionPrompt(this.ocrPromptRaw);
    this.editingPrompt = task;
  }

  private promptKey(): string {
    return this.editingPrompt === 'title' ? TASK_TITLE_PROMPT_KEY
      : this.editingPrompt === 'suggestion' ? TASK_SUGGESTION_PROMPT_KEY
        : TASK_COMPRESS_PROMPT_KEY;
  }

  private promptDefault(): string {
    return this.editingPrompt === 'title' ? DEFAULT_TITLE_PROMPT
      : this.editingPrompt === 'suggestion' ? DEFAULT_SUGGESTION_PROMPT
        : this.editingPrompt === 'compress' ? DEFAULT_COMPRESS_PROMPT
          : DEFAULT_OCR_PROMPT;
  }

  // ModelPromptSheet onPromptChange → updateSettings(:461-463/:500-502/:583-585/:544-546)
  private savePrompt(): void {
    if (this.editingPrompt === 'ocr') {
      saveOcrPrompt(getChatKvStore(), this.promptDraft).then((): void => {
        this.editingPrompt = '';
        this.refresh();
      });
      return;
    }
    saveTaskPrompt(this.promptKey(), this.promptDraft).then((): void => {
      this.editingPrompt = '';
      this.refresh();
    });
  }

  // onReset → DEFAULT_*_PROMPT(:464-466/:503-505/:586-588/:547-549)
  private resetPrompt(): void {
    const def: string = this.promptDefault();
    this.promptDraft = def;
    if (this.editingPrompt === 'ocr') {
      saveOcrPrompt(getChatKvStore(), def).then((): void => { this.refresh(); });
      return;
    }
    saveTaskPrompt(this.promptKey(), def).then((): void => { this.refresh(); });
  }

  @Builder
  taskCard(title: string, desc: string, currentLine: string, followLine: string,
    showClear: boolean, clearText: string, task: string, hasPrompt: boolean,
    isWorkerRow: boolean) {
    Column() {
      Row() {
        Column() {
          Text(title).fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(desc).fontSize(12).fontColor(INK3).margin({ top: 3 })
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        if (hasPrompt) {
          Text('提示词')
            .fontSize(13).fontColor(ACCENT)
            .onClick((): void => { this.openPromptEditor(task); })
        }
      }
      .width('100%')

      Row() {
        Column() {
          Text(currentLine).fontSize(13).fontColor(INK2)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (followLine.length > 0) {
            Text(followLine).fontSize(11).fontColor(INK3).margin({ top: 3 })
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text('选择')
          .fontSize(13).fontColor(ACCENT).margin({ right: 16 })
          .onClick((): void => { this.openPicker(task); })
        if (showClear) {
          Text(clearText)
            .fontSize(13).fontColor(INK3)
            .onClick((): void => {
              if (isWorkerRow) {
                this.clearWorkerModel(task === 'daydream');
              } else {
                this.clearTaskModel(task);
              }
            })
        }
      }
      .width('100%').margin({ top: 10 })
    }
    .padding({ left: 18, right: 18, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .borderRadius(12)
    .margin({ left: 18, right: 18, top: 12 })
  }

  @Builder
  promptEditorOverlay() {
    Column() {
      Blank()
      Column() {
        Row() {
          Text(this.editingPrompt === 'title' ? '标题总结模型'
            : this.editingPrompt === 'suggestion' ? '聊天建议模型'
              : this.editingPrompt === 'compress' ? '压缩模型' : '视觉识别模型')
            .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
          Blank()
          Text('重置为默认')
            .fontSize(13).fontColor(INK3)
            .onClick((): void => { this.resetPrompt(); })
        }
        .width('100%')

        Text(this.editingPrompt === 'compress'
          ? '变量：{content}、{target_tokens}、{additional_context}'
          : this.editingPrompt === 'ocr'
            ? '变量：图片数据：{images}'
            : '变量: 对话内容: {content}, 语言: {locale}')
          .fontSize(11).fontColor(INK3).margin({ top: 8 })

        TextArea({ text: this.promptDraft })
          .width('100%').height(220).margin({ top: 10 })
          .fontSize(13).fontColor(INK)
          .backgroundColor(BG).borderRadius(8)
          .onChange((value: string): void => { this.promptDraft = value; })

        Row() {
          Text('取消')
            .fontSize(14).fontColor(INK3)
            .padding({ left: 20, right: 20, top: 8, bottom: 8 })
            .onClick((): void => { this.editingPrompt = ''; })
          Blank()
          Text('保存')
            .fontSize(14).fontColor(ACCENT)
            .padding({ left: 20, right: 20, top: 8, bottom: 8 })
            .onClick((): void => { this.savePrompt(); })
        }
        .width('100%').margin({ top: 12 })
      }
      .width('100%')
      .padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
    }
    .width('100%').height('100%')
    .backgroundColor(OVERLAY)
    .onClick((): void => { this.editingPrompt = ''; })
  }

  build() {
    Stack() {
      Column() {
        PageTitleBar({ title: '任务模型' })

        if (!this.loaded) {
          Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
        } else {
          Scroll() {
            Column() {
              // 标题总结模型(:433-470)
              this.taskCard('标题总结模型',
                '用于总结对话标题的模型, 推荐使用快速且便宜的模型',
                this.followsChat(this.titleUuid) ? '跟随当前聊天模型' : this.selectedName(this.titleUuid),
                this.followsChat(this.titleUuid)
                  ? (this.titleFallback.length > 0 ? `当前使用：${this.titleFallback}` : '暂无可用聊天模型')
                  : '',
                !this.followsChat(this.titleUuid), '恢复为跟随当前聊天模型', 'title', true, false)

              // 聊天建议模型(:472-509)
              this.taskCard('聊天建议模型',
                '用于生成对话建议的模型, 推荐使用快速且便宜的模型',
                this.followsChat(this.suggestionUuid) ? '跟随当前聊天模型' : this.selectedName(this.suggestionUuid),
                this.followsChat(this.suggestionUuid)
                  ? (this.suggestionFallback.length > 0 ? `当前使用：${this.suggestionFallback}` : '暂无可用聊天模型')
                  : '',
                !this.followsChat(this.suggestionUuid), '恢复为跟随当前聊天模型', 'suggestion', true, false)

              // 压缩模型(:556-592)
              this.taskCard('压缩模型',
                '用于压缩对话历史的模型',
                this.followsChat(this.compressUuid) ? '跟随当前聊天模型' : this.selectedName(this.compressUuid),
                this.followsChat(this.compressUuid)
                  ? (this.compressFallback.length > 0 ? `当前使用：${this.compressFallback}` : '暂无可用聊天模型')
                  : '',
                !this.followsChat(this.compressUuid), '恢复为跟随当前聊天模型', 'compress', true, false)

              // 记忆提取模型(:594-645)— follows = followCompressModel && findById==null(:600)
              this.taskCard('记忆提取模型',
                '用于对话结束后提取候选记忆，轻量稳定即可',
                (this.workerFollow && this.followsChat(this.workerUuid))
                  ? '跟随压缩模型' : this.selectedName(this.workerUuid),
                (this.workerFollow && this.followsChat(this.workerUuid))
                  ? `当前跟随压缩模型：${this.compressFallback.length > 0 ? this.compressFallback : '不可用'}`
                  : '',
                !(this.workerFollow && this.followsChat(this.workerUuid)), '恢复跟随压缩模型',
                'worker', false, true)

              // Daydream 模型(:647-700)
              this.taskCard('Daydream 模型',
                '用于后台整理、合并和审查记忆，建议选择推理更强的模型',
                (this.daydreamFollow && this.followsChat(this.daydreamUuid))
                  ? '跟随压缩模型' : this.selectedName(this.daydreamUuid),
                (this.daydreamFollow && this.followsChat(this.daydreamUuid))
                  ? `当前跟随压缩模型：${this.compressFallback.length > 0 ? this.compressFallback : '不可用'}`
                  : '',
                !(this.daydreamFollow && this.followsChat(this.daydreamUuid)), '恢复跟随压缩模型',
                'daydream', false, true)

              // 视觉识别模型(:511-553;D-098)— 无 allowClear;IMAGE 模态过滤在 picker;
              //   D-111:desc 健康后缀 "${desc} · ${health.label}"(:529 逐字)
              this.taskCard('视觉识别模型',
                `当前聊天模型不能直接读取图片时，用它理解图片内容 · ${this.ocrHealthLabel}`,
                this.selectedName(this.ocrUuid).length > 0 ? this.selectedName(this.ocrUuid) : '未配置',
                '',
                false, '', 'ocr', true, false)
            }
            .width('100%').padding({ top: 2, bottom: 24 })
          }
        .align(Alignment.Start)
          .width('100%').layoutWeight(1)
        }
      }
      .width('100%').height('100%').backgroundColor(BG)

      if (this.editingPrompt.length > 0) {
        this.promptEditorOverlay()
      }
    }
    .width('100%').height('100%')
  }
}
