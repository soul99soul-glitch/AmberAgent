import { loadNovelResearchEnabled, saveNovelResearchEnabled } from '../platform_impl/NovelResearchSupport.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { BG, INK, INK3, ACCENT, ERROR, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar, PlainSection, PageSpacer } from '../components/PageTemplate.ets';
import { loadNovelModelOptions, describeChatModel } from '../di/AppContainer.ets';
import type { NovelModelOption } from '../di/AppContainer.ets';
import type { NovelModelDefaults, NovelModelTarget, NovelModelRole, PolishContextOptions } from '@amber/deepread-domain';
import { defaultNovelModelDefaults } from '@amber/deepread-domain';
import {
  loadNovelModelDefaults, saveNovelModelDefaults, loadNovelPolishContext, saveNovelPolishContext,
} from '../platform_impl/NovelStandaloneDefaults.ets';


@Entry
@Component
struct NovelStandaloneSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State defaults: NovelModelDefaults = defaultNovelModelDefaults();
  @State options: NovelModelOption[] = [];
  @State chatModelLabel: string = '未配置可用模型';
  @State includePlot: boolean = true;
  @State includeForeshadows: boolean = true;
  @State includeCharacters: boolean = true;
  @State includeDecisions: boolean = true;
  @State saving: boolean = false;
  @State loading: boolean = true;
  @State error: string = '';
  @State savedMessage: string = '';
  @State pickingRole: NovelModelRole | null = null;
  @State researchEnabled: boolean = false;
  private alive: boolean = false;
  private loadToken: number = 0;
  private previousKeyboardAvoidMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private keyboardAvoidanceActive: boolean = false;

  aboutToAppear(): void { this.alive = true; this.enableKeyboardAvoidance(); this.reload(); }
  onPageShow(): void { this.enableKeyboardAvoidance(); if (this.alive) this.reload(this.loading); }
  onPageHide(): void { this.restoreKeyboardAvoidance(); this.loadToken++; }
  aboutToDisappear(): void { this.restoreKeyboardAvoidance(); this.alive = false; this.loadToken++; }

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

  private async reload(readPreferences: boolean = true): Promise<void> {
    const token: number = ++this.loadToken;
    try {
      const options: NovelModelOption[] = await loadNovelModelOptions();
      const chatLabel: string = await describeChatModel();
      const defaults: NovelModelDefaults = await loadNovelModelDefaults();
      const researchEnabled: boolean = await loadNovelResearchEnabled(getChatKvStore());
      if (readPreferences) {
        const context: PolishContextOptions = await loadNovelPolishContext();
        if (!this.alive || token !== this.loadToken) return;
        this.includePlot = context.includePlot;
        this.includeForeshadows = context.includeForeshadows;
        this.includeCharacters = context.includeCharacters;
        this.includeDecisions = context.includeDecisions;
      }
      if (!this.alive || token !== this.loadToken) return;
      this.defaults = defaults;
      this.researchEnabled = researchEnabled;
      this.options = options;
      this.chatModelLabel = chatLabel.trim().length > 0 ? chatLabel : '未配置可用模型';
      this.error = '';
    } catch (error) {
      if (this.alive && token === this.loadToken) this.error = String(error);
    } finally {
      if (this.alive && token === this.loadToken) this.loading = false;
    }
  }

  private labelFor(target: NovelModelTarget, role: NovelModelRole): string {
    if (target.kind === 'global') return role === 'writing'
      ? `应用当前模型 · ${this.chatModelLabel}` : '项目未指定时跟随默认写作模型';
    const option: NovelModelOption | undefined = this.options.find((item: NovelModelOption): boolean =>
      item.providerId === target.providerId && item.modelId === target.modelId);
    if (option === undefined) return `模型已移除 · ${target.modelId}`;
    return `${option.providerLabel} · ${option.label}${option.available ? '' : '（尚未配置凭据）'}`;
  }

  private async chooseModel(role: NovelModelRole, option: NovelModelOption | null): Promise<void> {
    if (this.saving) return;
    const target: NovelModelTarget = option === null ? { kind: 'global' } : {
      kind: 'fixed', providerId: option.providerId, modelId: option.modelId,
    };
    const next: NovelModelDefaults = {
      writing: role === 'writing' ? target : this.defaults.writing,
      review: role === 'review' ? target : this.defaults.review,
      stateSync: role === 'stateSync' ? target : this.defaults.stateSync,
    };
    this.saving = true;
    try {
      await saveNovelModelDefaults(next);
      if (this.alive) { this.defaults = next; this.pickingRole = null; this.savedMessage = '默认模型已保存'; this.error = ''; }
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.saving = false; }
  }

  private async saveResearch(enabled: boolean): Promise<void> {
    try {
      await saveNovelResearchEnabled(getChatKvStore(), enabled);
      if (this.alive) this.researchEnabled = enabled;
    } catch (error) { if (this.alive) this.error = String(error); }
  }

  private async savePolish(): Promise<void> {
    if (this.saving || this.loading) return;
    this.saving = true;
    try {
      const context: PolishContextOptions = {
        includePlot: this.includePlot, includeForeshadows: this.includeForeshadows,
        includeCharacters: this.includeCharacters, includeDecisions: this.includeDecisions,
      };
      await saveNovelPolishContext(context);
      if (this.alive) { this.savedMessage = '润色上下文默认项已保存'; this.error = ''; }
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.saving = false; }
  }

  @Builder
  private ModelRow(role: NovelModelRole, title: string): void {
    Column({ space: 5 }) {
      Text(title).fontSize(15).fontColor(INK).width('100%')
      Text(this.labelFor(this.defaults[role], role)).fontSize(13).fontColor(INK3).width('100%')
      Text('选择模型').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
        .enabled(!this.loading && !this.saving).onClick((): void => { this.pickingRole = this.pickingRole === role ? null : role; })
      if (this.pickingRole === role) {
        Text(role === 'writing' ? '应用当前模型' : '跟随默认写作模型')
          .fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 }).enabled(!this.saving)
          .onClick((): void => { this.chooseModel(role, null); })
        ForEach(this.options, (option: NovelModelOption): void => {
          Text(`${option.providerLabel} · ${option.label}${option.available ? '' : '（未配置凭据）'}`)
            .fontSize(14).fontColor(option.available ? INK : INK3).width('100%')
            .constraintSize({ minHeight: 44 }).enabled(option.available && !this.saving)
            .onClick((): void => { this.chooseModel(role, option); })
        }, (option: NovelModelOption): string => `${option.providerId}:${option.modelId}`)
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 12, bottom: 4 })
  }

  @Builder
  private ContextRow(title: string, selected: boolean, field: string): void {
    Row() {
      Text(title).fontSize(14).fontColor(INK).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: selected }).selectedColor(ACCENT)
        .enabled(!this.loading && !this.saving).onChange((value: boolean): void => {
          if (field === 'plot') this.includePlot = value;
          else if (field === 'foreshadows') this.includeForeshadows = value;
          else if (field === 'characters') this.includeCharacters = value;
          else this.includeDecisions = value;
        })
    }.width('100%').constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
  }

  build(): void {
    Column() {
    ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        PageTitleBar({ title: '小说设置' })
        Scroll() {
          Column() {
            Text('模型服务商').fontSize(16).fontColor(ACCENT).constraintSize({ minHeight: 48 })
              .padding({ left: 20, right: 20 })
              .onClick((): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); })
            Text('先添加服务商和模型，再选择小说职责模型。').fontSize(13).fontColor(INK3)
              .width('100%').padding({ left: 20, right: 20, bottom: 12 })
            if (this.options.filter((item: NovelModelOption): boolean => item.available).length === 0 && !this.loading) {
              Text('尚未配置可用模型，创作任务暂时无法启动。').fontSize(13).fontColor(ERROR)
                .width('100%').padding({ left: 20, right: 20, bottom: 12 })
            }
            PlainSection({ label: '默认模型职责' })
            this.ModelRow('writing', '写作')
            this.ModelRow('review', '审稿与润色审核')
            this.ModelRow('stateSync', '剧情同步与资料提取')
            Text('项目固定模型优先；未单独指定的职责使用这里的默认值。').fontSize(12).fontColor(INK3)
              .width('100%').padding(20)
            PlainSection({ label: '联网研究' })
            Row() {
              Text('启用联网研究').fontSize(14).fontColor(INK).layoutWeight(1)
              Toggle({ type: ToggleType.Switch, isOn: this.researchEnabled }).selectedColor(ACCENT)
                .onChange((enabled: boolean): void => { this.saveResearch(enabled); })
            }.width('100%').constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 })
            Text('搜索服务').fontSize(14).fontColor(ACCENT).constraintSize({ minHeight: 44 })
              .padding({ left: 20, right: 20 }).onClick((): void => { router.pushUrl({ url: 'pages/ChatSearchSettingsPage' }); })
            PlainSection({ label: '润色上下文默认项' })
            Text('润色要求在各项目的“项目创建设置”中编辑。这里的默认项用于选择润色时附带的上下文。')
              .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
              .padding({ left: 20, right: 20, top: 8, bottom: 12 })
            this.ContextRow('包含剧情状态', this.includePlot, 'plot')
            this.ContextRow('包含伏笔', this.includeForeshadows, 'foreshadows')
            this.ContextRow('包含人物资料', this.includeCharacters, 'characters')
            this.ContextRow('包含已确认决定', this.includeDecisions, 'decisions')
            Text(this.saving ? '保存中…' : '保存润色上下文').fontSize(15).fontColor(ACCENT)
              .constraintSize({ minHeight: 48 }).padding({ left: 20, right: 20 }).enabled(!this.loading && !this.saving)
              .onClick((): void => { this.savePolish(); })
            if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).width('100%').padding(20) }
            if (this.savedMessage.length > 0) { Text(this.savedMessage).fontSize(13).fontColor(INK3).padding(12) }
            Text('外观').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 48 })
              .padding({ left: 20, right: 20 })
              .onClick((): void => { router.pushUrl({ url: 'pages/SettingAppearancePage' }); })
            PageSpacer()
          }.width('100%').alignItems(HorizontalAlign.Start).padding({ bottom: this.navBarHeight })
        }.layoutWeight(1).scrollBar(BarState.Off).align(Alignment.TopStart)
      }.width('100%').height('100%').backgroundColor(BG)
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
    }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%')
  }
}
