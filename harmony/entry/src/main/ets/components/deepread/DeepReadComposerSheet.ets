import { router, KeyboardAvoidMode, curves } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { picker } from '@kit.CoreFileKit';
import { newId } from '@amber/chat-domain';
import { makeEmptyDeepReadOutput, sourceInputs, inputSourceLabel } from '@amber/deepread-domain';
import type { DeepReadCacheEntry, DeepReadInputSource, DeepReadOutput } from '@amber/deepread-domain';
import { getRepository, getAppContainer, getChatKvStore } from '../../di/AppContainer.ets';
import { BUILTIN_TEMPLATES, DeepReadTemplateDef, loadCustomTemplates, CustomTemplateDef, captureDeepReadTemplate } from '../../platform_impl/DeepReadTemplate.ets';
import { importDeepReadSource } from '../../platform_impl/DeepReadSourceImporter.ets';
import { BG, INK, INK2, INK3, SURFACE2, ACCENT, ACCENT_INK, ERROR, TITLE, BODY, META } from '../../design/tokens.ets';
import { FONT_SERIF } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';
import { DeepReadHaptics } from '../../design/DeepReadHaptics.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';
import { getProductKind } from '../../platform_impl/ProductIdentity.ets';
import { DeepReadPaperBackground } from './DeepReadPaperBackground.ets';
import {
  DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup, DeepReadSettingsNote,
  DeepReadSettingsPicker, DeepReadSettingsButton,
} from './DeepReadSettingsVisual.ets';

@Component
export struct DeepReadComposerSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop embedded: boolean = false;
  onDismiss: () => void = (): void => { router.back(); };
  onCreated: (topicId: string, title: string) => Promise<void> = async (topicId: string, title: string): Promise<void> => {
    await router.replaceUrl({ url: 'pages/DeepReadArticlePage', params: { topicId, title } });
  };
  @State pendingTopicId: string = '';
  @State pendingTitle: string = '';
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
    // A library host can restore its own keyboard mode before this sheet disappears.
    // In that order the sheet no longer owns the current mode and must leave it alone.
    if (!this.embedded || this.getUIContext().getKeyboardAvoidMode() === KeyboardAvoidMode.RESIZE) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardAvoidMode);
    }
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
        this.appendFile(source);
      }
    } catch (error) {
      if (this.alive && token === this.pickerToken) this.error = `选择文件失败：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (this.alive && token === this.pickerToken) this.busy = false;
    }
  }

  private appendFile(source: DeepReadInputSource): void {
    this.getUIContext().animateTo({ duration: this.reduceMotion || getProductKind() !== 'deepread' ? 0 : 350,
      curve: this.reduceMotion || getProductKind() !== 'deepread' ? Curve.Linear : curves.springMotion(0.35, 0.85) }, (): void => {
      this.files = [...this.files, source];
    });
  }

  private removeFile(id: string): void {
    if (this.busy || this.pendingTopicId.length > 0) return;
    DeepReadHaptics.selection();
    this.getUIContext().animateTo({ duration: this.reduceMotion || getProductKind() !== 'deepread' ? 0 : 350,
      curve: this.reduceMotion || getProductKind() !== 'deepread' ? Curve.Linear : curves.springMotion(0.35, 0.85) }, (): void => {
      this.files = this.files.filter((file: DeepReadInputSource): boolean => file.id !== id);
    });
  }

  private async createArticle(): Promise<void> {
    if (this.busy || this.templatesLoading) return;
    if (this.pendingTopicId.length > 0) {
      this.busy = true;
      this.error = '';
      try { await this.onCreated(this.pendingTopicId, this.pendingTitle); }
      catch (error) { if (this.alive) this.error = '阅读已保存，打开失败：' + String(error); }
      finally { if (this.alive) this.busy = false; }
      return;
    }
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
      DeepReadHaptics.success();
      this.pendingTopicId = topicId;
      this.pendingTitle = title;
      await this.onCreated(topicId, title);
    } catch (error) {
      if (this.alive && token === this.pickerToken) this.error = (this.pendingTopicId.length > 0 ? '阅读已保存，打开失败：' : '') + (error instanceof Error ? error.message : String(error));
    } finally { if (this.alive && token === this.pickerToken) this.busy = false; }
  }

  @Builder
  HostCompose(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        Row() {
          Row() {
            Icon({ name: 'back', iconSize: 20, color: INK3 })
          }.width(44).height(44).justifyContent(FlexAlign.Center)
            .onClick((): void => { this.onDismiss(); })
          Text('创建深读').fontSize(TITLE).fontFamily(FONT_SERIF).fontColor(INK).layoutWeight(1)
        }.width('100%').padding({ top: this.statusBarHeight, left: 12, right: 20, bottom: 10 })
        Scroll() {
          Column({ space: 16 }) {
            Text('围绕一个主题，汇集你提供的资料').fontSize(BODY).fontColor(INK2).width('100%')
            TextInput({ placeholder: '文章主题（必填）', text: this.title }).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(44).enabled(!this.busy && this.pendingTopicId.length === 0)
              .onChange((value: string): void => { this.title = value; })
            TextArea({ placeholder: '粘贴文本（可选）', text: this.text }).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(180).enabled(!this.busy && this.pendingTopicId.length === 0)
              .onChange((value: string): void => { this.text = value; })
            TextArea({ placeholder: '网页链接（可选，每行一个 HTTP / HTTPS 地址）', text: this.urls })
              .fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).height(120).enabled(!this.busy && this.pendingTopicId.length === 0)
              .onChange((value: string): void => { this.urls = value; })
            Text(this.busy ? '正在读取文件…' : '添加文件').fontSize(BODY).fontColor(ACCENT).width('100%').height(48)
              .backgroundColor(SURFACE2).borderRadius(12).textAlign(TextAlign.Center).enabled(!this.busy && this.pendingTopicId.length === 0)
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
                  .enabled(!this.busy && this.pendingTopicId.length === 0).onClick((): void => { this.removeFile(source.id); })
              }.width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(12)
            }, (source: DeepReadInputSource): string => source.id)
            Text('本篇版式').fontSize(BODY).fontColor(INK).width('100%')
            ForEach(this.templateChoices, (item: DeepReadTemplateDef): void => {
              Column({ space: 4 }) {
                Text((this.templateId === item.id ? '已选 · ' : '') + item.name)
                  .fontSize(BODY).fontColor(this.templateId === item.id ? ACCENT : INK).width('100%')
                Text(item.description).fontSize(META).fontColor(INK3).width('100%')
              }.width('100%').constraintSize({ minHeight: 48 }).padding(12).backgroundColor(SURFACE2)
                .borderRadius(12).alignItems(HorizontalAlign.Start).enabled(!this.busy && this.pendingTopicId.length === 0)
                .onClick((): void => { this.templateId = item.id; })
            }, (item: DeepReadTemplateDef): string => `${item.id}:${item.name}:${this.templateId === item.id}`)
            Text('开始时保存本篇版式；之后修改或删除模板不会改变这篇文章及其 PDF。')
              .fontSize(META).fontColor(INK3).width('100%')
            if (this.error.length > 0) { Text(this.error).fontSize(META).fontColor(ERROR).width('100%') }
            Text(this.busy ? '处理中…' : this.pendingTopicId.length > 0 ? '打开已创建阅读' : '生成文章').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(ACCENT_INK)
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

  @Builder
  TopicCard(): void {
    TextArea({ text: this.title, placeholder: '想深入了解什么？' }).fontSize(20).fontFamily(D.serif)
      .fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent).height(72)
      .padding({ left: 16, right: 16, top: 15, bottom: 15 }).enabled(!this.busy && this.pendingTopicId.length === 0)
      .onChange((value: string): void => { this.title = value; })
  }

  @Builder
  SourcesCard(): void {
    Column() {
      TextArea({ text: this.text, placeholder: '粘贴文本（可选）' }).fontSize(17).fontColor(D.ink)
        .placeholderColor(D.muted).backgroundColor(Color.Transparent).height(142).enabled(!this.busy && this.pendingTopicId.length === 0)
        .padding({ left: 16, right: 16, top: 14, bottom: 14 })
        .onChange((value: string): void => { this.text = value; })
      Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 })
      TextArea({ text: this.urls, placeholder: '网页链接，每行一个（可选）' }).fontSize(17).fontColor(D.ink)
        .placeholderColor(D.muted).backgroundColor(Color.Transparent).height(90).enabled(!this.busy && this.pendingTopicId.length === 0)
        .padding({ left: 16, right: 16, top: 14, bottom: 14 })
        .onChange((value: string): void => { this.urls = value; })
      Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 })
      Row({ space: 9 }) {
        Icon({ name: 'file', iconSize: 20, color: D.accent })
        Text(this.busy ? '正在读取文件…' : '导入文件').fontSize(17).fontColor(D.accent)
      }.width('100%').height(50).padding({ left: 16, right: 16 }).enabled(!this.busy && this.pendingTopicId.length === 0)
        .onClick((): void => { this.pickFiles(); })
      ForEach(this.files, (source: DeepReadInputSource): void => {
        Column() {
        Row().width('100%').height(0.5).backgroundColor(D.rule).margin({ left: 16 })
        Row({ space: 10 }) {
          Icon({ name: 'file', iconSize: 20, color: D.muted })
          Column({ space: 4 }) {
            Text(source.title).fontSize(16).fontColor(D.ink).width('100%').maxLines(2)
            Text(inputSourceLabel(source)).fontSize(12).fontColor(source.status === 'failed' ? D.danger : D.muted)
              .width('100%').maxLines(3)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          Row() { Icon({ name: 'close', iconSize: 19, color: D.muted }) }
            .width(44).height(44).justifyContent(FlexAlign.Center).enabled(!this.busy && this.pendingTopicId.length === 0)
            .accessibilityText('移除 ' + source.title)
            .onClick((): void => { this.removeFile(source.id); })
        }.width('100%').padding({ left: 16, right: 8, top: 8, bottom: 8 })

        }.width('100%').transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ x: this.reduceMotion ? 0 : -24 }))
          .animation({ duration: this.reduceMotion ? 0 : 350, curve: this.reduceMotion ? Curve.Linear : curves.springMotion(0.35, 0.85) }))
      }, (source: DeepReadInputSource): string => source.id)
    }.width('100%')
  }

  @Builder
  TemplateCard(): void {
    DeepReadSettingsPicker({ label: '生成模板', options: this.templateChoices.map((item: DeepReadTemplateDef): string => item.name),
      selected: Math.max(0, this.templateChoices.findIndex((item: DeepReadTemplateDef): boolean => item.id === this.templateId)), last: true,
      onSelect: (index: number): void => { DeepReadHaptics.selection(); this.templateId = this.templateChoices[index].id; } })
  }

  @Builder
  StandaloneCompose(): void {
    Stack() {
    DeepReadPaperBackground()
    Column() {
      DeepReadSettingsHeader({ title: '创建阅读', cancel: true, topInset: this.embedded ? 0 : -1,
        onBack: (): void => { this.onDismiss(); } })
      Scroll() {
        Column() {
          DeepReadSettingsSection({ title: '阅读主题' })
          DeepReadSettingsGroup({ content: (): void => { this.TopicCard(); } })
          DeepReadSettingsNote({ text: '自动从多个角度搜索资料，再结合你提供的来源生成文章。' })
          DeepReadSettingsSection({ title: '补充来源' })
          DeepReadSettingsGroup({ content: (): void => { this.SourcesCard(); } })
          DeepReadSettingsNote({ text: '支持 TXT、MD、JSON、CSV、文本 PDF、DOCX。单文件最多 20 MiB，正文最多保存 40,000 字符。' })
          DeepReadSettingsSection({ title: '版式' })
          DeepReadSettingsGroup({ content: (): void => { this.TemplateCard(); } }).enabled(!this.busy && !this.templatesLoading && this.pendingTopicId.length === 0)
          DeepReadSettingsNote({ text: '此版式只用于本篇阅读；默认版式在设置中选择。' })
          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor(D.danger).lineHeight(19).width('100%').padding({ top: 18, left: 16, right: 16 })
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: this.reduceMotion ? 0 : -8 }))
                .animation({ duration: this.reduceMotion ? 0 : 350, curve: Curve.EaseOut }))
          }
          DeepReadSettingsButton({ icon: 'spark', success: this.pendingTopicId.length > 0, label: this.busy ? '处理中…' : this.pendingTopicId.length > 0 ? '打开已创建阅读' : '开始深度阅读',
            disabled: this.busy || this.templatesLoading || (this.pendingTopicId.length === 0 && this.title.trim().length === 0),
            onTap: (): void => { this.createArticle(); } }).margin({ top: 28 })
          DeepReadSettingsNote({ text: '生成开始后离开文章页面，任务仍由应用持有。' })
        }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: this.navBarHeight + 28 })
      }.width('100%').layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
    }.width('100%').height('100%')
    }.width('100%').height('100%').backgroundColor(D.paper)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        if (getProductKind() === 'deepread') { this.StandaloneCompose(); } else { this.HostCompose(); }
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').backgroundColor(D.paper)
  }
}
