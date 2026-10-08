import { DeepReadSubpageDock, DeepReadDockLayout } from '../components/deepread/DeepReadSubpageDock.ets';
import { router } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { picker, fileIo } from '@kit.CoreFileKit';
import type { ProviderSetting, ProviderSettingGoogle, GoogleAuthStatus, GoogleLoginAttempt } from '@amber/chat-domain';
import { loadProviders } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import {
  loadGoogleAuthStatus, beginGoogleLogin, retryGoogleOnboarding, logoutGoogle,
} from '../platform_impl/GoogleAuthSupport.ets';
import { stageGoogleSecret, deleteGoogleSecret } from '../platform_impl/GoogleSecureStore.ets';
import {
  GooglePageAuthMode, GoogleProviderAuthDraft, GoogleProviderSaveResult,
  GoogleServiceAccountImport, parseGoogleServiceAccountJson, saveGoogleProviderAuthDraft,
} from '../platform_impl/GoogleProviderConfigSupport.ets';
import { PageTitleBar, BtnFill, BtnGhost, PageSpacer } from '../components/PageTemplate.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { BG, INK, INK3, ERROR, TEXT_STYLES, MOTION_OVERLAY } from '../design/tokens.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import {
  DeepReadSettingsHeader, DeepReadSettingsSection, DeepReadSettingsGroup,
  DeepReadSettingsNote, DeepReadSettingsRow, DeepReadSettingsPicker, DeepReadSettingsButton,
} from '../components/deepread/DeepReadSettingsVisual.ets';

interface GoogleProviderAuthRoute { providerId?: string; }

@Entry
@Component
struct GoogleProviderAuthPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State provider: ProviderSettingGoogle | null = null;
  @State mode: GooglePageAuthMode = 'api_key';
  @State clientId: string = '';
  @State redirectUri: string = '';
  @State clientSecret: string = '';
  @State clearClientSecret: boolean = false;
  @State clearPrivateKey: boolean = false;
  @State hasPrivateKey: boolean = false;
  @State email: string = '';
  @State projectId: string = '';
  @State location: string = '';
  @State status: GoogleAuthStatus | null = null;
  @State message: string = '';
  @State dirty: boolean = false;
  @State busy: boolean = false;
  @State authBusy: boolean = false;
  private alive: boolean = false;
  private statusSeq: number = 0;
  private attemptSeq: number = 0;
  private attempt: GoogleLoginAttempt | null = null;
  private onboardingController: EntryAbortController | null = null;
  private pendingPrivateKeyRef: string = '';

  aboutToAppear(): void {
    this.alive = true;
    const params: GoogleProviderAuthRoute = router.getParams() as GoogleProviderAuthRoute;
    this.load(params.providerId ?? '').catch((): void => {
      if (this.alive) this.message = '无法读取服务商配置，请返回后重试。';
    });
  }

  onPageShow(): void {
    if (this.provider !== null) this.refreshStatus();
  }

  // The external browser may temporarily hide this page; that is not cancellation.
  aboutToDisappear(): void {
    this.alive = false;
    this.statusSeq++;
    this.cancelAttempt();
    this.discardPendingPrivateKey();
    this.clientSecret = '';
  }

  onBackPress(): boolean {
    if (this.busy) return true;
    this.cancelAttempt();
    this.discardPendingPrivateKey();
    return false;
  }

  private async load(providerId: string): Promise<void> {
    const all: ProviderSetting[] = (await loadProviders(getChatKvStore())) ?? [];
    const found: ProviderSetting | undefined = all.find((item: ProviderSetting): boolean => item.id === providerId);
    if (!this.alive) return;
    if (found === undefined || found.type !== 'google') {
      this.message = '服务商已不存在，请返回服务商列表。';
      return;
    }
    this.applySaved(found as ProviderSettingGoogle);
    this.refreshStatus();
  }

  private applySaved(setting: ProviderSettingGoogle): void {
    this.provider = setting;
    this.mode = setting.authMode === 'api_key'
      ? (setting.useServiceAccount ? 'service_account' : 'api_key') : setting.authMode;
    this.clientId = setting.oauthClientId;
    this.redirectUri = setting.oauthRedirectUri;
    this.email = setting.serviceAccountEmail;
    this.projectId = setting.projectId;
    this.location = setting.location;
    this.hasPrivateKey = setting.privateKeyRef.length > 0 || setting.privateKey.length > 0;
    this.clearPrivateKey = false;
    this.clearClientSecret = false;
    this.clientSecret = '';
    this.dirty = false;
  }

  private refreshStatus(): void {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.authBusy) return;
    const seq: number = ++this.statusSeq;
    loadGoogleAuthStatus(setting).then((status: GoogleAuthStatus): void => {
      if (this.alive && seq === this.statusSeq) this.status = status;
    }).catch((): void => {
      if (this.alive && seq === this.statusSeq) this.message = '无法读取本地授权状态，请重新进入后检查。';
    });
  }

  private cancelAttempt(): void {
    this.attemptSeq++;
    const current: GoogleLoginAttempt | null = this.attempt;
    this.attempt = null;
    if (current !== null) current.cancel();
    if (this.onboardingController !== null) this.onboardingController.abort();
    this.onboardingController = null;
    this.authBusy = false;
  }

  private changed(): void {
    this.cancelAttempt();
    this.statusSeq++;
    this.dirty = true;
    this.message = '配置有未保存的修改，请保存后再登录或重试授权。';
  }

  private selectMode(mode: GooglePageAuthMode): void {
    if (this.busy || this.mode === mode) return;
    animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => { this.mode = mode; });
    this.changed();
  }

  private discardPendingPrivateKey(): void {
    const setting: ProviderSettingGoogle | null = this.provider;
    const reference: string = this.pendingPrivateKeyRef;
    this.pendingPrivateKeyRef = '';
    if (setting === null || reference.length === 0) return;
    deleteGoogleSecret(setting.id, 'service_account_private_key', reference).catch((): void => {
      if (this.alive) this.message = '未保存的私钥清理失败，请重新进入后检查。';
    });
  }

  private async importServiceAccount(): Promise<void> {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.busy) return;
    this.cancelAttempt();
    this.busy = true;
    try {
      const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      const options: picker.DocumentSelectOptions = new picker.DocumentSelectOptions();
      options.maxSelectNumber = 1;
      options.fileSuffixFilters = ['.json'];
      const uris: string[] = await new picker.DocumentViewPicker(context).select(options);
      if (uris.length === 0 || !this.alive) return;
      const file: fileIo.File = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
      let raw: string;
      try {
        if (fileIo.statSync(file.fd).size > 128 * 1024) throw new Error('文件过大');
        raw = fileIo.readTextSync(uris[0]);
      } finally { fileIo.closeSync(file.fd); }
      const parsed: GoogleServiceAccountImport = parseGoogleServiceAccountJson(raw);
      const reference: string = await stageGoogleSecret(setting.id, 'service_account_private_key', parsed.privateKey);
      if (!this.alive) {
        await deleteGoogleSecret(setting.id, 'service_account_private_key', reference);
        return;
      }
      this.discardPendingPrivateKey();
      this.pendingPrivateKeyRef = reference;
      this.mode = 'service_account';
      this.email = parsed.email;
      this.projectId = parsed.projectId;
      this.hasPrivateKey = true;
      this.clearPrivateKey = false;
      this.changed();
      this.message = '服务账号已导入安全草稿，点击保存后生效；私钥不会显示或导出。';
    } catch (_error) {
      if (this.alive) this.message = '导入失败，请检查 Google 服务账号 JSON、PKCS#8 私钥和本地安全存储。';
    } finally { this.busy = false; }
  }

  private async save(): Promise<void> {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.busy) return;
    this.cancelAttempt();
    this.busy = true;
    const draft: GoogleProviderAuthDraft = {
      mode: this.mode, clientId: this.clientId, redirectUri: this.redirectUri,
      clientSecret: this.clientSecret, clearClientSecret: this.clearClientSecret,
      privateKeyRef: this.pendingPrivateKeyRef, clearPrivateKey: this.clearPrivateKey,
      email: this.email, projectId: this.projectId, location: this.location,
    };
    this.pendingPrivateKeyRef = ''; // Save owns staging cleanup from this point.
    this.statusSeq++;
    try {
      const result: GoogleProviderSaveResult = await saveGoogleProviderAuthDraft(setting.id, draft);
      if (!this.alive) return;
      this.applySaved(result.setting);
      this.message = result.cleanupMessage.length > 0 ? result.cleanupMessage : '认证配置已保存。配置完成不代表服务器授权成功。';
      this.refreshStatus();
    } catch (error) {
      if (this.alive) {
        this.hasPrivateKey = setting.privateKeyRef.length > 0 || setting.privateKey.length > 0;
        this.message = error instanceof Error ? `保存失败：${error.message}` : '保存失败，原配置仍有效。';
        if (draft.privateKeyRef.length > 0) this.message += ' 导入的私钥草稿已撤销，请重新导入后再保存。';
      }
    } finally { this.busy = false; }
  }

  private openBrowser(url: string): Promise<void> {
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    return context.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: url });
  }

  private async login(): Promise<void> {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.busy || this.authBusy) return;
    if (this.dirty) { this.message = '请先保存当前配置，再发起登录。'; return; }
    const seq: number = ++this.attemptSeq;
    this.authBusy = true;
    this.message = '正在准备浏览器授权…';
    try {
      const attempt: GoogleLoginAttempt = await beginGoogleLogin(setting);
      if (!this.alive || seq !== this.attemptSeq) { attempt.cancel(); return; }
      this.attempt = attempt;
      // Attach completion before browser launch so a launch failure still consumes cancellation.
      const completed: Promise<void> = attempt.completion.then((status: GoogleAuthStatus): void => {
        if (this.alive && seq === this.attemptSeq) {
          this.status = status;
          this.message = status.message;
        }
      }).catch((error: Error): void => {
        if (this.alive && seq === this.attemptSeq) {
          this.message = error instanceof Error ? `授权失败：${error.message}` : '授权未完成，请重试。';
        }
      }).finally((): void => {
        if (seq === this.attemptSeq) { this.attempt = null; this.authBusy = false; }
      });
      try { await this.openBrowser(attempt.authorizationUrl); }
      catch (_error) {
        this.cancelAttempt();
        if (this.alive) this.message = '无法打开系统浏览器，授权已取消。';
      }
      await completed;
    } catch (error) {
      if (this.alive && seq === this.attemptSeq) {
        this.authBusy = false;
        this.message = error instanceof Error ? `无法开始授权：${error.message}` : '无法开始授权。';
      }
    }
  }

  private async retryOnboarding(): Promise<void> {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.busy || this.authBusy || this.dirty) return;
    const seq: number = ++this.attemptSeq;
    this.authBusy = true;
    const controller: EntryAbortController = new EntryAbortController();
    this.onboardingController = controller;
    try {
      const status: GoogleAuthStatus = await retryGoogleOnboarding(setting, controller.signal);
      if (this.alive && seq === this.attemptSeq) { this.status = status; this.message = status.message; }
    } catch (error) {
      if (this.alive && seq === this.attemptSeq) this.message = error instanceof Error ? `重试失败：${error.message}` : '重试失败。';
    } finally {
      if (this.onboardingController === controller) this.onboardingController = null;
      if (seq === this.attemptSeq) this.authBusy = false;
    }
  }

  private async logout(): Promise<void> {
    const setting: ProviderSettingGoogle | null = this.provider;
    if (setting === null || this.busy) return;
    this.cancelAttempt();
    this.busy = true;
    try {
      await logoutGoogle(setting);
      if (this.alive) { this.message = '已退出当前 Google 授权。'; this.refreshStatus(); }
    } catch (_error) { if (this.alive) this.message = '退出失败，请重新进入后检查授权状态。'; }
    finally { this.busy = false; }
  }

  private back(): void {
    if (this.busy) return;
    this.cancelAttempt();
    this.discardPendingPrivateKey();
    router.back();
  }

  private isOAuth(): boolean { return this.mode === 'gemini_code_assist_oauth' || this.mode === 'antigravity_oauth'; }

  private statusLabel(): string {
    if (this.authBusy) return '授权进行中';
    if (this.status === null) return '尚未读取授权状态';
    switch (this.status.phase) {
      case 'ready': return '授权已就绪';
      case 'configured': return '已配置，尚未验证服务器授权';
      case 'signed_out': return '尚未登录';
      case 'authorizing': return '授权进行中';
      case 'onboarding_required': return '已登录，需完成服务开通';
      case 'error': return '授权异常';
      default: return '认证配置未完成';
    }
  }

  @Builder
  note(text: string) {
    Text(text).fontSize(12).lineHeight(17).fontColor(INK3).width('100%').margin({ top: 6, bottom: 8 })
  }

  @Builder
  section(text: string) {
    Text(text).fontSize(12).fontWeight(600).lineHeight(17).fontColor(INK3)
      .width('100%').padding({ top: 20, bottom: 7 })
  }

  @Builder
  modeButton(label: string, mode: GooglePageAuthMode) {
    BtnGhost({ label: this.mode === mode ? `${label} · 当前` : label, onTap: (): void => { this.selectMode(mode); } })
      .width('100%').enabled(!this.busy).margin({ bottom: 8 })
  }

  @Builder
  field(label: string, value: string, placeholder: string, change: (value: string) => void, secret: boolean = false) {
    if (getProductKind() === 'deepread') {
      Column() {
        Text(label).fontSize(13).fontColor(D.muted).width('100%').padding({ top: 12, left: 16, right: 16 })
        TextInput({ text: value, placeholder }).type(secret ? InputType.Password : InputType.Normal)
          .fontSize(16).fontColor(D.ink).placeholderColor(D.muted).backgroundColor(Color.Transparent)
          .height(46).padding({ left: 16, right: 16 }).enabled(!this.busy)
          .onChange((text: string): void => { change(text); this.changed(); })
      }.width('100%')
    } else {
    FlatTextField({ label: label, value: value, placeholder: placeholder, obscure: secret,
      mono: true, fieldEnabled: !this.busy,
      onChange: (value: string): void => { change(value); this.changed(); } })
      .margin({ bottom: 12 })
    }
  }

  private modeIndex(): number {
    return this.mode === 'service_account' ? 1 : this.mode === 'gemini_code_assist_oauth' ? 2
      : this.mode === 'antigravity_oauth' ? 3 : 0;
  }

  private selectModeIndex(index: number): void {
    const modes: GooglePageAuthMode[] = ['api_key', 'service_account', 'gemini_code_assist_oauth', 'antigravity_oauth'];
    this.selectMode(modes[index] ?? 'api_key');
  }

  @Builder
  DeepReadAuthFields(): void {
    if (this.mode === 'service_account') {
      DeepReadSettingsRow({ label: this.busy ? '正在导入…' : '导入服务账号 JSON', valueAccent: true, chevron: false,
        onTap: (): void => { this.importServiceAccount(); } })
      this.field('服务账号邮箱', this.email, 'name@project.iam.gserviceaccount.com', (value: string): void => { this.email = value; })
      this.field('Google Cloud 项目', this.projectId, 'project-id', (value: string): void => { this.projectId = value; })
      this.field('区域', this.location, 'us-central1 或 global', (value: string): void => { this.location = value; })
      DeepReadSettingsRow({ label: '私钥', value: this.clearPrivateKey ? '保存后移除' : this.hasPrivateKey ? '已导入' : '未提供',
        chevron: false, last: !this.hasPrivateKey || this.clearPrivateKey })
      if (this.hasPrivateKey && !this.clearPrivateKey) {
        DeepReadSettingsRow({ label: '移除私钥', destructive: true, chevron: false, last: true,
          onTap: (): void => {
            if (!this.busy) { this.discardPendingPrivateKey(); this.clearPrivateKey = true; this.changed(); }
          } })
      }
    } else if (this.isOAuth()) {
      this.field('Client ID', this.clientId, '已登记的 client_id', (value: string): void => { this.clientId = value; })
      this.field('回调地址', this.redirectUri, 'http://127.0.0.1:PORT/PATH', (value: string): void => { this.redirectUri = value; })
      this.field('Client Secret（选填）', this.clientSecret, this.provider !== null && this.provider.oauthClientSecretRef.length > 0
        ? '已保存；留空保留' : '留空不提供',
        (value: string): void => { this.clientSecret = value; this.clearClientSecret = false; }, true)
      if (this.provider !== null && this.provider.oauthClientSecretRef.length > 0 && !this.clearClientSecret) {
        DeepReadSettingsRow({ label: '移除 Client Secret', destructive: true, chevron: false, last: true,
          onTap: (): void => { if (!this.busy) { this.clientSecret = ''; this.clearClientSecret = true; this.changed(); } } })
      }
    }
  }

  @Builder
  DeepReadAuthStatus(): void {
    DeepReadSettingsRow({ label: '授权状态', value: this.statusLabel(), chevron: false,
      last: this.status === null || this.status.projectId.length === 0 })
    if (this.status !== null && this.status.projectId.length > 0) {
      DeepReadSettingsRow({ label: '已开通项目', value: this.status.projectId, chevron: false, last: true })
    }
  }

  @Builder
  DeepReadAuthActions(): void {
    if (this.status !== null && this.status.validationUrl !== null) {
      DeepReadSettingsRow({ label: '打开验证页',
        onTap: (): void => {
          if (this.status !== null && this.status.validationUrl !== null) {
            this.openBrowser(this.status.validationUrl).catch((): void => { this.message = '无法打开验证页。'; });
          }
        } })
    }
    if (this.authBusy) {
      DeepReadSettingsRow({ label: '取消当前授权', chevron: false,
        onTap: (): void => { this.cancelAttempt(); this.message = '当前授权已取消。'; this.refreshStatus(); } })
    }
    if (this.status !== null && this.status.phase === 'onboarding_required') {
      DeepReadSettingsRow({ label: '重试服务开通', chevron: false,
        onTap: (): void => { this.retryOnboarding(); } }).enabled(!this.busy && !this.authBusy && !this.dirty)
    }
    DeepReadSettingsRow({ label: '退出当前授权', destructive: true, chevron: false, last: true,
      onTap: (): void => { this.logout(); } })
  }

  @Builder
  DeepReadPage(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        DeepReadSettingsHeader({ title: 'Google 认证', onBack: (): void => { this.back(); } })
        Scroll() {
          Column() {
            if (this.provider !== null) {
              DeepReadSettingsSection({ title: '认证方式' })
              DeepReadSettingsGroup({ content: (): void => { this.DeepReadAuthMode(); } }).enabled(!this.busy)
              if (this.mode === 'api_key') {
                DeepReadSettingsNote({ text: 'API Key 与 Vertex 设置在模型服务页填写。切换认证方式后需保存。' })
              } else {
                DeepReadSettingsSection({ title: this.mode === 'service_account' ? '服务账号' : 'OAuth 配置' })
                DeepReadSettingsGroup({ content: (): void => { this.DeepReadAuthFields(); } })
                DeepReadSettingsNote({ text: this.mode === 'service_account'
                  ? '私钥仅存于本机安全存储。请确认项目、区域及服务权限。'
                  : this.mode === 'gemini_code_assist_oauth'
                    ? '使用已登记的 Client ID 和精确回调地址。Code Assist 需 Standard 或 Enterprise 资格。'
                    : '使用已登记的 Client ID 和精确回调地址。Antigravity 资格以服务器返回为准。' })
                if (this.clearClientSecret) { DeepReadSettingsNote({ text: '保存后移除 Client Secret。' }) }
              }
              Column() {
                DeepReadSettingsButton({ label: this.busy ? '正在保存…' : '保存认证配置', disabled: this.busy,
                  onTap: (): void => { this.save(); } })
              }.width('100%').padding({ left: 16, right: 16, top: 20 })
              DeepReadSettingsSection({ title: '已保存配置' })
              DeepReadSettingsGroup({ content: (): void => { this.DeepReadAuthStatus(); } })
              if (this.status !== null && this.status.message.length > 0) {
                DeepReadSettingsNote({ text: this.status.message })
              }
              if (this.isOAuth()) {
                Column() {
                  DeepReadSettingsButton({ label: this.authBusy ? '等待授权…' : '登录 Google',
                    disabled: this.busy || this.authBusy || this.dirty,
                    onTap: (): void => { this.login(); } })
                }.width('100%').padding({ left: 16, right: 16, top: 20, bottom: 12 })
                DeepReadSettingsGroup({ content: (): void => { this.DeepReadAuthActions(); } }).enabled(!this.busy)
              }
            }
            if (this.message.length > 0) {
              Text(this.message).fontSize(13).lineHeight(19).fontColor(this.message.indexOf('失败') >= 0 ? D.danger : D.muted)
                .width('100%').padding({ left: 16, right: 16, top: 12 })
            }
          }.width('100%').padding({ left: D.gutter, right: D.gutter, bottom: DeepReadDockLayout.bottomInset(this.navBarHeight) })
        }.align(Alignment.TopStart).layoutWeight(1).width('100%').scrollBar(BarState.Off)
      }.width('100%').height('100%').backgroundColor(D.paper)
      DeepReadSubpageDock({ onBeforeLeave: (): boolean => { return !this.busy; } }).align(Alignment.Bottom).hitTestBehavior(HitTestMode.Transparent)
    }.width('100%').height('100%')
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }

  @Builder
  DeepReadAuthMode(): void {
    DeepReadSettingsPicker({ label: '认证方式', options: ['API Key', 'Vertex 服务账号', 'Gemini Code Assist', 'Antigravity'],
      selected: this.modeIndex(), last: true, onSelect: (index: number): void => { this.selectModeIndex(index); } })
  }

  build() {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      if (getProductKind() === 'deepread') {
        this.DeepReadPage()
      } else {
      PageTitleBar({ title: 'Google 认证', onBack: (): void => { this.back(); } })
      Scroll() {
        Column() {
          if (this.provider !== null) {
            this.note(`${this.provider.name} · 账号授权与新服务账号私钥保存在本机安全存储；API Key 保留原配置存储。导出后需重新配置凭据。`)
            this.section('认证方式')
            this.modeButton('API Key / Vertex API Key', 'api_key')
            this.modeButton('Vertex 服务账号', 'service_account')
            this.modeButton('Gemini Code Assist', 'gemini_code_assist_oauth')
            this.modeButton('Antigravity', 'antigravity_oauth')
            if (this.mode === 'api_key') {
              this.note('API Key 与 Vertex API Key 在服务商详情页填写。选择后保存即可返回原页编辑。')
            } else if (this.mode === 'service_account') {
              this.note('导入 Google 服务账号 JSON。私钥不会回填到文本框；旧明文私钥仅在成功保存后迁入安全存储。Vertex 还需要项目、区域与相应服务权限。')
              BtnGhost({ label: this.busy ? '正在处理…' : '导入服务账号 JSON', onTap: (): void => { this.importServiceAccount(); } })
                .width('100%').enabled(!this.busy).margin({ top: 12, bottom: 12 })
              this.field('服务账号邮箱', this.email, 'name@project.iam.gserviceaccount.com', (value: string): void => { this.email = value; })
              this.field('Google Cloud 项目', this.projectId, 'project-id', (value: string): void => { this.projectId = value; })
              this.field('区域', this.location, 'us-central1 或 global', (value: string): void => { this.location = value; })
              this.note(this.clearPrivateKey ? '保存后移除服务账号私钥。' : (this.hasPrivateKey ? '已提供私钥，内容已隐藏。' : '尚未提供服务账号私钥。'))
              if (this.hasPrivateKey && !this.clearPrivateKey) {
                BtnGhost({ label: '保存时移除私钥', onTap: (): void => { this.discardPendingPrivateKey(); this.clearPrivateKey = true; this.changed(); } })
                  .width('100%').enabled(!this.busy).margin({ top: 8 })
              }
            } else {
              this.note(this.mode === 'gemini_code_assist_oauth'
                ? 'Code Assist 当前面向 Standard / Enterprise。个人、Pro、Ultra 层级已于 2026-06-18 停用，登录不保证账户资格。'
                : 'Antigravity 使用独立授权与开通状态，不与 Code Assist 登录态互换。账户资格以服务器返回为准。')
              this.note('填写你有权使用的 OAuth Client ID 和已登记的精确回调 URL。只支持配置的 loopback host、固定端口与路径；不会借用其他平台客户端。')
              this.field('OAuth Client ID', this.clientId, '已登记的 client_id', (value: string): void => { this.clientId = value; })
              this.field('精确 Redirect URI', this.redirectUri, 'http://127.0.0.1:PORT/PATH', (value: string): void => { this.redirectUri = value; })
              this.field('Client Secret（按注册类型选填）', this.clientSecret, this.provider.oauthClientSecretRef.length > 0 ? '已保存在本机；留空保留' : '留空不提供',
                (value: string): void => { this.clientSecret = value; this.clearClientSecret = false; }, true)
              if (this.provider.oauthClientSecretRef.length > 0 && !this.clearClientSecret) {
                BtnGhost({ label: '保存时移除 Client Secret', onTap: (): void => { this.clientSecret = ''; this.clearClientSecret = true; this.changed(); } })
                  .width('100%').enabled(!this.busy).margin({ bottom: 12 })
              }
              if (this.clearClientSecret) { this.note('保存后移除 Client Secret。') }
            }
            BtnFill({ label: this.busy ? '正在保存…' : '保存认证配置', onTap: (): void => { this.save(); } })
              .width('100%').enabled(!this.busy).margin({ top: 16, bottom: 12 })
            this.section('已保存配置的状态')
            Text(this.statusLabel()).fontColor(INK).fontSize(TEXT_STYLES.body.size).width('100%')
            if (this.status !== null) {
              this.note(this.status.message)
              if (this.status.projectId.length > 0) { this.note(`已开通项目：${this.status.projectId}`) }
              if (this.status.validationUrl !== null) {
                BtnGhost({ label: '打开服务器要求的验证页', onTap: (): void => {
                  if (this.status !== null && this.status.validationUrl !== null) {
                    this.openBrowser(this.status.validationUrl).catch((): void => { this.message = '无法打开验证页。'; });
                  }
                } }).width('100%').margin({ top: 8 })
              }
            }
            if (this.isOAuth()) {
              BtnFill({ label: this.authBusy ? '等待授权…' : '在系统浏览器登录', onTap: (): void => { this.login(); } })
                .width('100%').enabled(!this.busy && !this.authBusy && !this.dirty).margin({ top: 12 })
              if (this.authBusy) {
                BtnGhost({ label: '取消当前授权', onTap: (): void => { this.cancelAttempt(); this.message = '当前授权已取消。'; this.refreshStatus(); } })
                  .width('100%').margin({ top: 8 })
              }
              if (this.status !== null && this.status.phase === 'onboarding_required') {
                BtnGhost({ label: '重试服务开通', onTap: (): void => { this.retryOnboarding(); } })
                  .width('100%').enabled(!this.busy && !this.authBusy && !this.dirty).margin({ top: 8 })
              }
              BtnGhost({ label: '退出当前授权', onTap: (): void => { this.logout(); } })
                .width('100%').enabled(!this.busy).margin({ top: 8 })
            }
          }
          if (this.message.length > 0) {
            Text(this.message).fontColor(this.message.indexOf('失败') >= 0 ? ERROR : INK3)
              .fontSize(TEXT_STYLES.secondary.size).lineHeight(TEXT_STYLES.secondary.lineHeight)
              .width('100%').margin({ top: 12 })
          }
          PageSpacer()
        }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 16, right: 16, top: 12 })
      }.layoutWeight(1).width('100%')
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(getProductKind() === 'deepread' ? D.paper : BG)
      .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
