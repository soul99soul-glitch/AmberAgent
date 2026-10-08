import { loadNovelResearchEnabled, saveNovelResearchEnabled } from '../platform_impl/NovelResearchSupport.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { BG, INK, INK3, ACCENT, ERROR, SURFACE } from '../design/tokens.ets';
import { PageTitleBar, PlainSection, PageSpacer, CardGroup, CardRow } from '../components/PageTemplate.ets';
import { loadNovelModelOptions, describeChatModel } from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelModelDefaults, NovelModelTarget, NovelModelRole, PolishContextOptions } from '@amber/deepread-domain';
import { Icon } from '../components/ui/Icon.ets';
import { defaultNovelModelDefaults } from '@amber/deepread-domain';
import {
  loadNovelModelDefaults, saveNovelModelDefaults, loadNovelPolishContext, saveNovelPolishContext,
} from '../platform_impl/NovelStandaloneDefaults.ets';


@Entry
@Component
struct NovelStandaloneSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('reduceMotion') reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') @Watch('backgroundChanged') appBackgrounded: boolean = false;
  @State writingSection: boolean = false;
  @State panelError: string = '';
  @State panelSession: number = 0;
  @State defaults: NovelModelDefaults = defaultNovelModelDefaults();
  @State options: NovelModelOption[] = [];
  @State chatModelLabel: string = '未配置可用模型';
  @State includePlot: boolean = true;
  @State includeForeshadows: boolean = true;
  @State includeCharacters: boolean = true;
  @State includeDecisions: boolean = true;
  @State saving: boolean = false;
  @State loading: boolean = true;
  @State ready: boolean = false;
  @State error: string = '';
  @State savedMessage: string = '';
  @State pickingRole: NovelModelRole | null = null;
  @State researchEnabled: boolean = false;
  private alive: boolean = false;
  private loadToken: number = 0;
  private saveToken: number = 0;
  private visible: boolean = false;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    this.writingSection = params !== undefined && params !== null && params['section'] === 'writing';
    this.alive = true;
    this.visible = true;
    this.enableKeyboardAvoidance();
    this.reload();
  }
  onPageShow(): void { this.visible = true; this.enableKeyboardAvoidance(); if (this.alive) this.reload(); }
  onPageHide(): void {
    this.visible = false;
    this.closeModelPanel(this.panelSession);
    this.restoreKeyboardAvoidance();
    this.loadToken++;
  }
  aboutToDisappear(): void {
    this.onPageHide();
    this.alive = false;
    this.saveToken++;
  }
  onBackPress(): boolean {
    if (this.pickingRole !== null) { this.closeModelPanel(this.panelSession); return true; }
    if (this.saving) { this.error = '正在保存设置，请稍候再返回。'; return true; }
    return false;
  }
  private goBack(): void { if (!this.onBackPress()) router.back(); }
  private backgroundChanged(): void {
    if (this.appBackgrounded) this.closeModelPanel(this.panelSession);
  }
  private navigate(url: string): void {
    if (this.saving) return;
    router.pushUrl({ url }).catch((error: Error): void => { if (this.alive) this.error = String(error); });
  }
  private openWritingSettings(): void {
    if (this.saving) return;
    router.pushUrl({ url: 'pages/NovelStandaloneSettingsPage', params: { section: 'writing' } })
      .catch((error: Error): void => { if (this.alive) this.error = String(error); });
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

  private async reload(): Promise<void> {
    if (this.saving) return;
    this.ready = false;
    this.loading = true;
    const token: number = ++this.loadToken;
    try {
      const options: NovelModelOption[] = await loadNovelModelOptions();
      const chatLabel: string = await describeChatModel();
      const defaults: NovelModelDefaults = await loadNovelModelDefaults();
      const researchEnabled: boolean = await loadNovelResearchEnabled(getChatKvStore());
      const context: PolishContextOptions = await loadNovelPolishContext();
      if (!this.alive || token !== this.loadToken) return;
      this.applyContext(context);
      this.defaults = defaults;
      this.researchEnabled = researchEnabled;
      this.options = options;
      this.chatModelLabel = chatLabel.trim().length > 0 ? chatLabel : '未配置可用模型';
      this.ready = true;
      this.error = '';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = String(error);
    } finally {
      if (this.alive && token === this.loadToken) this.loading = false;
    }
  }

  private labelFor(target: NovelModelTarget): string {
    if (target.kind === 'global') return `跟随默认模型 · ${this.chatModelLabel}`;
    const option: NovelModelOption | undefined = this.options.find((item: NovelModelOption): boolean =>
      item.providerId === target.providerId && item.modelId === target.modelId);
    if (option === undefined) return `模型已移除 · ${target.modelId}`;
    return `${option.providerLabel} · ${option.label}${option.available ? '' : '（当前不可用）'}`;
  }

  private roleTitle(role: NovelModelRole): string {
    return role === 'writing' ? '创作模型' : role === 'review' ? '审稿与润色审核' : '剧情同步与资料提取';
  }

  private openModelPanel(role: NovelModelRole): void {
    if (!this.alive || !this.visible || this.appBackgrounded || !this.ready || this.loading || this.pickingRole !== null) return;
    this.panelSession++;
    this.panelError = '';
    this.pickingRole = role;
  }
  private panelActive(role: NovelModelRole, session: number): boolean {
    return this.alive && this.visible && !this.appBackgrounded && this.pickingRole === role && this.panelSession === session;
  }
  private closeModelPanel(session: number): void {
    if (session !== this.panelSession) return;
    this.pickingRole = null;
    this.panelSession++;
    this.panelError = '';
  }

  private async chooseModel(role: NovelModelRole, option: NovelModelOption | null, session: number): Promise<void> {
    if (!this.ready || this.loading || this.saving || !this.panelActive(role, session) || (option !== null && !option.available)) return;
    const target: NovelModelTarget = option === null ? { kind: 'global' } : {
      kind: 'fixed', providerId: option.providerId, modelId: option.modelId,
    };
    const next: NovelModelDefaults = {
      writing: role === 'writing' ? target : this.defaults.writing,
      review: role === 'review' ? target : this.defaults.review,
      stateSync: role === 'stateSync' ? target : this.defaults.stateSync,
    };
    const token: number = ++this.saveToken;
    this.saving = true;
    this.loadToken++;
    try {
      await saveNovelModelDefaults(next);
      if (this.alive && token === this.saveToken) {
        this.defaults = next;
        this.savedMessage = '写作模型已保存';
        this.error = '';
        if (this.panelActive(role, session)) this.closeModelPanel(session);
      }
    } catch (error) {
      if (this.alive && token === this.saveToken) {
        if (this.panelActive(role, session)) this.panelError = String(error);
        else this.error = String(error);
      }
    } finally { if (this.alive && token === this.saveToken) this.saving = false; }
  }

  private async saveResearch(enabled: boolean): Promise<void> {
    if (!this.ready || this.saving || this.loading || !this.alive) return;
    const token: number = ++this.saveToken;
    const previous: boolean = this.researchEnabled;
    this.researchEnabled = enabled;
    this.saving = true;
    this.loadToken++;
    try {
      await saveNovelResearchEnabled(getChatKvStore(), enabled);
      if (this.alive && token === this.saveToken) this.error = '';
    } catch (error) {
      if (this.alive && token === this.saveToken) { this.researchEnabled = previous; this.error = String(error); }
    } finally { if (this.alive && token === this.saveToken) this.saving = false; }
  }

  private contextDraft(): PolishContextOptions {
    return { includePlot: this.includePlot, includeForeshadows: this.includeForeshadows,
      includeCharacters: this.includeCharacters, includeDecisions: this.includeDecisions };
  }
  private applyContext(context: PolishContextOptions): void {
    this.includePlot = context.includePlot;
    this.includeForeshadows = context.includeForeshadows;
    this.includeCharacters = context.includeCharacters;
    this.includeDecisions = context.includeDecisions;
  }
  private async changeContext(field: string, value: boolean): Promise<void> {
    if (!this.ready || this.saving || this.loading || !this.alive) return;
    const previous: PolishContextOptions = this.contextDraft();
    if (field === 'plot') this.includePlot = value;
    else if (field === 'foreshadows') this.includeForeshadows = value;
    else if (field === 'characters') this.includeCharacters = value;
    else this.includeDecisions = value;
    const token: number = ++this.saveToken;
    this.saving = true;
    this.loadToken++;
    try {
      await saveNovelPolishContext(this.contextDraft());
      if (this.alive && token === this.saveToken) { this.savedMessage = '润色上下文已保存'; this.error = ''; }
    } catch (error) {
      if (this.alive && token === this.saveToken) { this.applyContext(previous); this.error = String(error); }
    } finally { if (this.alive && token === this.saveToken) this.saving = false; }
  }

  private modelPanelTransition(card: boolean): TransitionEffect {
    if (this.appBackgrounded) return TransitionEffect.IDENTITY;
    if (!card || this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 160 });
    return TransitionEffect.asymmetric(
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 320, curve: Curve.EaseOut }),
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 250, curve: Curve.EaseIn }));
  }

  @Builder
  private ModelRow(role: NovelModelRole): void {
    CardRow({ label: this.roleTitle(role), desc: this.labelFor(this.defaults[role]),
      iconName: 'brain', isLast: role === 'stateSync',
      onRowClick: (): void => { this.openModelPanel(role); } })
  }

  private optionSelected(role: NovelModelRole, option: NovelModelOption | null): boolean {
    const target: NovelModelTarget = this.defaults[role];
    return option === null ? target.kind === 'global' : target.kind === 'fixed' &&
      target.providerId === option.providerId && target.modelId === option.modelId;
  }

  @Builder
  private ModelOptionRow(role: NovelModelRole, option: NovelModelOption | null, session: number): void {
    Row() {
      Text(option === null ? `跟随默认模型 · ${this.chatModelLabel}` :
        `${option.providerLabel} · ${option.label}${option.available ? '' : '（当前不可用）'}`)
        .fontSize(15).fontColor(option !== null && !option.available ? INK3 : INK)
        .layoutWeight(1).maxLines(3).wordBreak(WordBreak.BREAK_ALL)
      if (this.optionSelected(role, option)) {
        Icon({ name: 'check', iconSize: 18, color: ACCENT })
      }
    }.width('100%').constraintSize({ minHeight: 52 })
      .padding({ left: 20, right: 20, top: 10, bottom: 10 })
      .enabled(this.ready && !this.loading && !this.saving && (option === null || option.available))
      .onClick((): void => { this.chooseModel(role, option, session); })
  }

  @Builder
  private ModelPanel(role: NovelModelRole, session: number): void {
    Column() {
      Blank().onClick((): void => { this.closeModelPanel(session); })
      Column() {
        Row() {
          Text(this.roleTitle(role)).fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
          Text('完成').fontSize(15).fontColor(ACCENT).constraintSize({ minWidth: 48, minHeight: 44 })
            .textAlign(TextAlign.End).onClick((): void => { this.closeModelPanel(session); })
        }.width('100%').padding({ left: 20, right: 20, top: 10, bottom: 4 })
        Scroll() {
          Column() {
            this.ModelOptionRow(role, null, session)
            ForEach(this.options, (option: NovelModelOption): void => {
              this.ModelOptionRow(role, option, session)
            }, (option: NovelModelOption): string => `${option.providerId}:${option.modelId}`)
            if (this.options.length === 0) {
              Text('暂无模型，请先添加模型服务。').fontSize(13).fontColor(INK3).width('100%').padding(20)
            }
            if (this.panelError.length > 0) {
              Text(this.panelError).fontSize(13).fontColor(ERROR).width('100%').padding(20)
            }
            if (this.saving) { Text('保存中…').fontSize(13).fontColor(INK3).padding(12) }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.constraintSize({ maxHeight: 420 }).scrollBar(BarState.Auto)
      }.width('100%').backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ bottom: Math.max(16, this.navBarHeight) }).transition(this.modelPanelTransition(true))
    }.width('100%').height('100%').backgroundColor('#66000000')
      .justifyContent(FlexAlign.End).transition(this.modelPanelTransition(false))
  }

  private contextSelected(field: string): boolean {
    if (field === 'plot') return this.includePlot;
    if (field === 'foreshadows') return this.includeForeshadows;
    if (field === 'characters') return this.includeCharacters;
    return this.includeDecisions;
  }

  @Builder
  private ContextRow(title: string, field: string): void {
    Row() {
      Text(title).fontSize(14).fontColor(INK).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: this.contextSelected(field) }).selectedColor(ACCENT)
        .enabled(this.ready && !this.loading && !this.saving).onChange((value: boolean): void => {
          this.changeContext(field, value);
        })
    }.width('100%').constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
  }

  @Builder
  private RootCards(): void {
    CardRow({ label: '模型服务', desc: '添加和管理服务与模型', iconName: 'brain',
      onRowClick: (): void => { this.navigate('pages/ChatProviderSettingsPage'); } })
    CardRow({ label: '默认模型', desc: this.chatModelLabel, iconName: 'check_circle',
      onRowClick: (): void => { this.navigate('pages/ChatModelPickerPage'); } })
    CardRow({ label: '写作模型与偏好', desc: '创作、审稿、剧情同步与润色上下文', iconName: 'edit',
      onRowClick: (): void => { this.openWritingSettings(); } })
    CardRow({ label: '外观与主题', iconName: 'gear', isLast: true,
      onRowClick: (): void => { this.navigate('pages/NovelAppearancePage'); } })
  }

  @Builder
  private WritingCards(): void {
    this.ModelRow('writing')
    this.ModelRow('review')
    this.ModelRow('stateSync')
  }

  build(): void {
    Column() {
      ForEach([this.themeEpoch], (_epoch: number): void => {
        Stack() {
          Column() {
            PageTitleBar({ title: this.writingSection ? '写作模型与偏好' : '设置',
              onBack: (): void => { this.goBack(); } })
            Scroll() {
              Column() {
                if (this.writingSection) {
                  PlainSection({ label: '写作模型' })
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.WritingCards(); } })
                    .margin({ left: 20, right: 20 })
                  Text('项目固定模型优先。跟随默认模型时使用设置中的默认模型；项目继承职责时，先使用该职责默认值，再跟随写作模型。')
                    .fontSize(12).fontColor(INK3).lineHeight(18).width('100%').padding(20)
                  PlainSection({ label: '润色上下文' })
                  Text('选择润色时附带的上下文，修改后自动保存。润色要求在项目设置中编辑。')
                    .fontSize(12).fontColor(INK3).lineHeight(18).width('100%').padding(20)
                  this.ContextRow('包含剧情状态', 'plot')
                  this.ContextRow('包含伏笔', 'foreshadows')
                  this.ContextRow('包含人物资料', 'characters')
                  this.ContextRow('包含已确认决定', 'decisions')
                } else {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.RootCards(); } })
                    .margin({ left: 20, right: 20, top: 12 })
                  PlainSection({ label: '联网搜索' })
                  Row() {
                    Text('讨论时允许联网搜索').fontSize(15).fontColor(INK).layoutWeight(1)
                    Toggle({ type: ToggleType.Switch, isOn: this.researchEnabled }).selectedColor(ACCENT)
                      .enabled(this.ready && !this.loading && !this.saving)
                      .onChange((enabled: boolean): void => { this.saveResearch(enabled); })
                  }.width('100%').constraintSize({ minHeight: 52 }).padding({ left: 20, right: 20 })
                  if (this.researchEnabled) {
                    CardRow({ label: '搜索服务', desc: '选择搜索引擎并配置服务', iconName: 'search', isLast: true,
                      onRowClick: (): void => { this.navigate('pages/ChatSearchSettingsPage'); } })
                      .margin({ left: 6, right: 6 })
                  }
                }
                if (this.loading) { Text('加载中…').fontSize(13).fontColor(INK3).padding(20) }
                if (this.options.filter((item: NovelModelOption): boolean => item.available).length === 0 && !this.loading) {
                  Text('尚未配置可用模型，请先添加模型服务。').fontSize(13).fontColor(INK3).width('100%').padding(20)
                }
                if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding(20) }
                if (!this.ready && this.error.length > 0) {
                  Text('重新加载设置').fontSize(15).fontColor(ACCENT)
                    .constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
                    .enabled(!this.loading && !this.saving).onClick((): void => { this.reload(); })
                }
                if (this.saving) { Text('保存中…').fontSize(13).fontColor(INK3).padding(12) }
                else if (this.savedMessage.length > 0) { Text(this.savedMessage).fontSize(13).fontColor(INK3).padding(12) }
                PageSpacer()
              }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: this.navBarHeight })
            }.layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
          }.width('100%').height('100%').backgroundColor(BG)
          if (this.pickingRole !== null) { this.ModelPanel(this.pickingRole, this.panelSession) }
        }.width('100%').height('100%')
          .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
      }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
