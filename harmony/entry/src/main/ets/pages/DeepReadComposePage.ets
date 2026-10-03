import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { newId } from '@amber/chat-domain';
import { makeEmptyDeepReadOutput, sourceInputs, inputSourceLabel } from '@amber/deepread-domain';
import type { DeepReadCacheEntry, DeepReadInputSource, DeepReadOutput } from '@amber/deepread-domain';
import { getRepository, getAppContainer, getChatKvStore } from '../di/AppContainer.ets';
import { BUILTIN_TEMPLATES, DeepReadTemplateDef, loadCustomTemplates, CustomTemplateDef, captureDeepReadTemplate } from '../platform_impl/DeepReadTemplate.ets';
import { importDeepReadSource } from '../platform_impl/DeepReadSourceImporter.ets';
import { BG, INK, INK2, INK3, SURFACE2, ACCENT, ACCENT_INK, ERROR, TITLE, BODY, META } from '../design/tokens.ets';
import { FONT_SERIF } from '../design/fonts.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct DeepReadComposePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State title: string = '';
  @State text: string = '';
  @State urls: string = '';
  @State files: DeepReadInputSource[] = [];
  @State busy: boolean = false;
  @State error: string = '';
  @State templateId: string = 'none';
  @State templateChoices: DeepReadTemplateDef[] = BUILTIN_TEMPLATES;
  @State templatesLoading: boolean = true;
  private alive: boolean = false;
  private pickerToken: number = 0;
  private templateLoadToken: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;

  aboutToAppear(): void {
    this.alive = true;
    this.busy = false;
    this.previousKeyboardAvoidMode = this.getUIContext().getKeyboardAvoidMode();
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.loadTemplates();
  }

  private async loadTemplates(): Promise<void> {
    const token: number = ++this.templateLoadToken;
    this.templatesLoading = true;
    try {
      const selected: string | null = await getChatKvStore().get('deepread_template_id');
      const custom: CustomTemplateDef[] = await loadCustomTemplates(getAppContainer().storage);
      if (!this.alive || token !== this.templateLoadToken) return;
      this.templateChoices = [...BUILTIN_TEMPLATES, ...custom.map((item: CustomTemplateDef): DeepReadTemplateDef =>
        ({ id: item.id, name: item.name, description: '自定义版式' }))];
      this.templateId = this.templateChoices.some((item: DeepReadTemplateDef): boolean => item.id === selected)
        ? selected as string : 'none';
    } catch (error) {
      if (this.alive && token === this.templateLoadToken) this.error = `读取版式失败：${error instanceof Error ? error.message : String(error)}`;
    } finally { if (this.alive && token === this.templateLoadToken) this.templatesLoading = false; }
  }
  aboutToDisappear(): void {
    this.alive = false;
    this.pickerToken++;
    this.templateLoadToken++;
    this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
  }

  private async pickFiles(): Promise<void> {
    if (this.busy) return;
    const token: number = ++this.pickerToken;
    this.busy = true;
    this.error = '';
    try {
      const context: common.UIAbilityContext = AppStorage.get<common.UIAbilityContext>('abilityContext') as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 10;
      options.fileSuffixFilters = ['.txt', '.md', '.json', '.csv', '.pdf', '.docx'];
      const uris: string[] = await new picker.DocumentViewPicker(context).select(options);
      for (const uri of uris) {
        const source: DeepReadInputSource = await importDeepReadSource(uri);
        if (!this.alive || token !== this.pickerToken) return;
        this.files = [...this.files, source];
      }
    } catch (error) {
      if (this.alive && token === this.pickerToken) this.error = `选择文件失败：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.alive && token === this.pickerToken) this.busy = false;
    }
  }

  private async createArticle(): Promise<void> {
    if (this.busy || this.templatesLoading) return;
    const title: string = this.title.trim();
    if (title.length === 0) { this.error = '请填写文章主题。'; return; }
    this.busy = true;
    this.error = '';
    const selectedTemplateId: string = this.templateId;
    const token: number = ++this.pickerToken;
    try {
      const sources: DeepReadInputSource[] = [...sourceInputs(this.text, this.urls), ...this.files];
      const output: DeepReadOutput = makeEmptyDeepReadOutput();
      output.inputSources = sources;
      output.inputText = this.text;
      output.inputUrlsText = this.urls;
      output.templateSnapshot = await captureDeepReadTemplate(getAppContainer().storage, selectedTemplateId);
      output.templateId = output.templateSnapshot.id;
      if (!this.alive || token !== this.pickerToken) return;
      output.inputSourceUrls = sources.filter((source: DeepReadInputSource): boolean => source.kind === 'web'
        && source.status !== 'failed' && source.url !== null).map((source: DeepReadInputSource): string => source.url as string);
      const now: number = Date.now();
      const topicId: string = `compose-${newId()}`;
      const entry: DeepReadCacheEntry = {
        topicId, title, sourceUrl: output.inputSourceUrls[0] ?? null, output, phase: 'IDLE',
        attemptCount: 0, lastError: null, createdAt: now, updatedAt: now, expiresAt: now + 86400000,
        templateId: output.templateId,
      };
      // Commit every user input before transferring execution ownership to the article runtime.
      await getRepository().upsert(entry);
      if (!this.alive || token !== this.pickerToken) return;
      await router.replaceUrl({ url: 'pages/DeepReadArticlePage', params: { topicId, title } });
    } catch (error) {
      if (this.alive && token === this.pickerToken) this.error = error instanceof Error ? error.message : String(error);
    } finally { if (this.alive && token === this.pickerToken) this.busy = false; }
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        Row() {
          Row() {
            Icon({ name: 'back', iconSize: 20, color: INK3 })
          }.width(44).height(44).justifyContent(FlexAlign.Center)
            .onClick((): void => { router.back(); })
          Text('创建深读').fontSize(TITLE).fontFamily(FONT_SERIF).fontColor(INK).layoutWeight(1)
        }.width('100%').padding({ top: this.statusBarHeight, left: 12, right: 20, bottom: 10 })
        Scroll() {
          Column({ space: 16 }) {
            Text('围绕一个主题，汇集你提供的资料').fontSize(BODY).fontColor(INK2).width('100%')
            TextInput({ placeholder: '文章主题（必填）', text: this.title }).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(44).enabled(!this.busy)
              .onChange((value: string): void => { this.title = value; })
            TextArea({ placeholder: '粘贴文本（可选）', text: this.text }).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(180).enabled(!this.busy)
              .onChange((value: string): void => { this.text = value; })
            TextArea({ placeholder: '网页链接（可选，每行一个 HTTP / HTTPS 地址）', text: this.urls })
              .fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(120).enabled(!this.busy)
              .onChange((value: string): void => { this.urls = value; })
            Text(this.busy ? '正在读取文件…' : '添加文件').fontSize(BODY).fontColor(ACCENT).width('100%').height(48)
              .backgroundColor(SURFACE2).borderRadius(12).textAlign(TextAlign.Center).enabled(!this.busy)
              .onClick((): void => { this.pickFiles(); })
            Text('支持 TXT、MD、JSON、CSV、文本 PDF、DOCX；单文件最多 20 MiB。每个来源正文最多保存 40,000 字符；生成最多使用 10 个可用来源，全部输入保留。')
              .fontSize(META).fontColor(INK3).width('100%')
            ForEach(this.files, (source: DeepReadInputSource): void => {
              Row({ space: 10 }) {
                Column({ space: 4 }) {
                  Text(source.title).fontSize(BODY).fontColor(INK).width('100%').maxLines(2)
                  Text(inputSourceLabel(source)).fontSize(META).fontColor(source.status === 'failed' ? ERROR : INK3).width('100%')
                }.layoutWeight(1)
                Text('移除').fontSize(META).fontColor(INK3).width(44).height(44).textAlign(TextAlign.Center)
                  .enabled(!this.busy).onClick((): void => { this.files = this.files.filter((file: DeepReadInputSource): boolean => file.id !== source.id); })
              }.width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(12)
            }, (source: DeepReadInputSource): string => source.id)
            Text('本篇版式').fontSize(BODY).fontColor(INK).width('100%')
            ForEach(this.templateChoices, (item: DeepReadTemplateDef): void => {
              Column({ space: 4 }) {
                Text((this.templateId === item.id ? '已选 · ' : '') + item.name)
                  .fontSize(BODY).fontColor(this.templateId === item.id ? ACCENT : INK).width('100%')
                Text(item.description).fontSize(META).fontColor(INK3).width('100%')
              }.width('100%').constraintSize({ minHeight: 48 }).padding(12).backgroundColor(SURFACE2)
                .borderRadius(12).alignItems(HorizontalAlign.Start).enabled(!this.busy)
                .onClick((): void => { this.templateId = item.id; })
            }, (item: DeepReadTemplateDef): string => `${item.id}:${item.name}:${this.templateId === item.id}`)
            Text('开始时保存本篇版式；之后修改或删除模板不会改变这篇文章及其 PDF。')
              .fontSize(META).fontColor(INK3).width('100%')
            if (this.error.length > 0) { Text(this.error).fontSize(META).fontColor(ERROR).width('100%') }
            Text(this.busy ? '处理中…' : '生成文章').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(ACCENT_INK)
              .height(52).width('100%').backgroundColor(ACCENT).borderRadius(14).textAlign(TextAlign.Center)
              .enabled(!this.busy && !this.templatesLoading).onClick((): void => { this.createArticle(); })
            Text('只填主题时将使用当前搜索配置采集资料。生成开始后离开文章页面，任务仍由应用持有。')
              .fontSize(META).fontColor(INK3).width('100%')
          }.width('100%').padding({ left: 20, right: 20, bottom: this.navBarHeight + 28 })
        }.layoutWeight(1).width('100%').align(Alignment.TopStart)
      }.width('100%').height('100%').backgroundColor(BG)
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(BG)
  }
}
