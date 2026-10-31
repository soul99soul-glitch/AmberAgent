import { router } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import { SSHProfileStore, TerminalRuntime, patchAssistant } from '@amber/chat-domain';
import type { Assistant, LocalToolOption, SSHProfile, SSHProfileDraft, SSHProfileSettings, SSHTrustProbe, SSHCredential, SSHKey } from '@amber/chat-domain';
import { getSSHProfileStore, getTerminalRuntime, getChatAssistants } from '../di/AppContainer.ets';
import { mutateAssistant } from '../platform_impl/AssistantMutation.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { CenteredTitleBar, CardGroup, PlainSection, FooterNote, PageSpacer, Seg } from '../components/PageTemplate.ets';
import { SSHKeysSection } from '../components/SSHKeysSection.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { sshPageError } from '../components/SSHPageErrors.ets';
import { Icon } from '../components/ui/Icon.ets';
import { ACCENT, BODY, BODY2, GUTTER, HEADER, LABEL, META, MOTION_OVERLAY, PAD_CARD, accentSoftBg } from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_MEDIUM } from '../design/fonts.ets';

@Entry
@Component
struct SettingSSHProfilesPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State profiles: SSHProfile[] = [];
  @State assistants: Assistant[] = [];
  @State assistantSavingId: string = '';
  @State assistantError: string = '';
  @State defaultId: string = '';
  @State loaded: boolean = false;
  @State editing: boolean = false;
  @State name: string = '';
  @State host: string = '';
  @State portText: string = '22';
  @State username: string = '';
  @State authIndex: number = 0;
  @State keys: SSHKey[] = [];
  @State keyId: string = '';
  @State jumpProfileId: string = '';
  @State secret: string = '';
  @State passphrase: string = '';
  @State showSecret: boolean = false;
  @State showPassphrase: boolean = false;
  @State hasSavedCredential: boolean = false;
  @State candidate: SSHTrustProbe | null = null;
  @State busy: string = '';
  @State error: string = '';
  @State notice: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('appBackgrounded') @Watch('backgroundChanged') backgrounded: boolean = false;
  private store: SSHProfileStore | null = null;
  private runtime: TerminalRuntime | null = null;
  private unsubscribe: (() => void) | null = null;
  private unsubscribeKeys: (() => void) | null = null;
  private operation: EntryAbortController | null = null;
  private alive: boolean = false;
  private draftId: string = '';
  private expectedRevision: number | null = null;
  private original: SSHProfile | null = null;
  private operationVersion: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.load();
    this.loadAssistantsForTools();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.cancelOperation();
    this.clearSecrets();
    if (this.unsubscribe !== null) this.unsubscribe();
    this.unsubscribe = null;
    this.unsubscribeKeys?.(); this.unsubscribeKeys = null;
  }

  onPageHide(): void {
    this.cancelOperation();
    this.clearSecrets();
  }

  onBackPress(): boolean {
    if (!this.editing) return false;
    this.closeEditor();
    return true;
  }

  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private backgroundChanged(): void {
    if (this.backgrounded) {
      this.cancelOperation();
      this.clearSecrets();
    }
  }

  private clearSecrets(): void {
    this.secret = ''; this.passphrase = ''; this.showSecret = false; this.showPassphrase = false;
  }

  private async load(): Promise<void> {
    try {
      const store: SSHProfileStore = await getSSHProfileStore();
      const runtime: TerminalRuntime = await getTerminalRuntime();
      if (!this.alive) return;
      this.store = store;
      this.runtime = runtime;
      this.applySettings(store.snapshot());
      this.unsubscribe = store.subscribe((settings: SSHProfileSettings): void => {
        if (this.alive) this.applySettings(settings);
      });
      const keys = store.keyStore();
      if (keys !== undefined) this.unsubscribeKeys = keys.subscribe((list: SSHKey[]): void => {
        if (this.alive) this.keys = list;
      });
      this.loaded = true;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? sshPageError(error) : String(error);
    }
  }

  private applySettings(settings: SSHProfileSettings): void {
    this.profiles = settings.profiles;
    this.defaultId = settings.defaultProfileId ?? '';
  }

  private async loadAssistantsForTools(): Promise<void> {
    this.assistantError = '';
    try {
      const assistants: Assistant[] = await getChatAssistants();
      if (this.alive) this.assistants = assistants;
    } catch (error) {
      if (this.alive) this.assistantError = '加载聊天助手失败：' + (error instanceof Error ? error.message : String(error));
    }
  }

  private assistantAllowsTerminal(assistant: Assistant): boolean {
    return assistant.toolProfile === 'full' || assistant.toolProfile === 'coding';
  }

  private async setAssistantTerminal(assistantId: string, enabled: boolean): Promise<void> {
    if (!this.alive || this.assistantSavingId.length > 0) return;
    this.assistantSavingId = assistantId;
    this.assistantError = '';
    try {
      const saved: Assistant | null = await mutateAssistant(assistantId, (current: Assistant): Assistant => {
        if (enabled && !this.assistantAllowsTerminal(current)) {
          throw new Error('当前助手的工具范围不包含终端');
        }
        const localTools: LocalToolOption[] = current.localTools.filter((option: LocalToolOption): boolean => option !== 'terminal');
        if (enabled) localTools.push('terminal');
        return patchAssistant(current, { localTools });
      });
      if (saved === null) throw new Error('助手已不存在，请重新打开此页');
      if (this.alive) {
        this.assistants = this.assistants.map((assistant: Assistant): Assistant => assistant.id === saved.id ? saved : assistant);
      }
    } catch (error) {
      if (this.alive) this.assistantError = '保存终端工具设置失败：' + (error instanceof Error ? error.message : String(error));
    } finally {
      if (this.alive) this.assistantSavingId = '';
    }
  }

  private edit(profile: SSHProfile | null): void {
    if (!this.loaded || this.busy.length > 0) return;
    this.cancelOperation();
    this.clearSecrets();
    this.original = profile;
    this.draftId = profile === null ? util.generateRandomUUID(false) : profile.id;
    this.expectedRevision = profile === null ? null : profile.revision;
    this.name = profile === null ? '' : profile.name;
    this.host = profile === null ? '' : profile.host;
    this.portText = profile === null ? '22' : String(profile.port);
    this.username = profile === null ? '' : profile.username;
    this.authIndex = profile !== null && profile.authMethod === 'privateKey' ? 1 : 0;
    this.keyId = profile?.keyId ?? '';
    this.jumpProfileId = profile?.jumpProfileId ?? '';
    this.hasSavedCredential = profile !== null && (profile.credentialRef !== null || !!profile.keyId);
    this.candidate = null;
    this.notice = '';
    this.error = '';
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.editing = true;
    });
  }

  private closeEditor(): void {
    this.cancelOperation();
    this.clearSecrets();
    this.candidate = null;
    this.original = null;
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.editing = false;
    });
    this.error = '';
  }

  private draft(): SSHProfileDraft {
    return {
      id: this.draftId, name: this.name, host: this.host, port: Number(this.portText),
      username: this.username, authMethod: this.authIndex === 1 ? 'privateKey' : 'password',
      keyId: this.authIndex === 1 && this.keyId.length > 0 ? this.keyId : null,
      jumpProfileId: this.jumpProfileId.length > 0 ? this.jumpProfileId : null,
    };
  }

  private draftChanged(): void {
    this.cancelOperation();
    this.candidate = null;
    this.notice = '';
    this.error = '';
  }

  private cancelOperation(): void {
    this.operationVersion++;
    if (this.operation !== null) this.operation.abort();
    this.operation = null;
    this.busy = '';
  }

  private editorRevisionMatches(): boolean {
    if (this.store === null || (this.store.get(this.draftId)?.revision ?? null) !== this.expectedRevision) {
      this.error = '配置已被修改或删除，请取消并重新打开编辑';
      this.candidate = null;
      this.clearSecrets();
      return false;
    }
    return true;
  }

  private async probe(): Promise<void> {
    const runtime: TerminalRuntime | null = this.runtime;
    if (runtime === null || this.busy.length > 0 || this.backgrounded) return;
    if (!this.editorRevisionMatches()) return;
    this.error = '';
    this.notice = '';
    this.candidate = null;
    const controller: EntryAbortController = new EntryAbortController();
    this.operation = controller;
    const version: number = ++this.operationVersion;
    this.busy = '正在探测主机…';
    try {
      const candidate: SSHTrustProbe = await runtime.probe(this.draft(), controller.signal);
      if (this.alive && version === this.operationVersion && !controller.signal.aborted) {
        this.candidate = candidate;
      }
    } catch (error) {
      if (this.alive && version === this.operationVersion && !controller.signal.aborted) {
        this.error = error instanceof Error ? sshPageError(error) : String(error);
      }
    } finally {
      if (version === this.operationVersion) { this.operation = null; this.busy = ''; }
    }
  }

  private async verify(): Promise<void> {
    const runtime: TerminalRuntime | null = this.runtime;
    const candidate: SSHTrustProbe | null = this.candidate;
    if (runtime === null || candidate === null || this.busy.length > 0 || this.backgrounded) return;
    if (!this.editorRevisionMatches()) return;
    if (this.secret.length === 0 && this.keyId.length === 0 && !this.hasSavedCredential) {
      this.error = this.authIndex === 1 ? '请输入私钥' : '请输入密码';
      return;
    }
    if (this.secret.length === 0 && this.passphrase.length > 0) {
      this.error = '修改私钥口令时请同时重新输入私钥';
      return;
    }
    const credential: SSHCredential | null = this.secret.length > 0
      ? { secret: this.secret, passphrase: this.authIndex === 1 && this.passphrase.length > 0 ? this.passphrase : null }
      : null;
    const draft: SSHProfileDraft = this.draft();
    const controller: EntryAbortController = new EntryAbortController();
    this.operation = controller;
    const version: number = ++this.operationVersion;
    this.busy = '正在验证并保存…';
    this.error = '';
    try {
      await runtime.verifyAndSave(draft, candidate, credential, candidate.trustState !== 'trusted', controller.signal);
      if (this.alive && version === this.operationVersion && !controller.signal.aborted) {
        this.closeEditor();
        this.notice = '认证成功，SSH 配置已保存';
      }
    } catch (error) {
      if (this.alive && version === this.operationVersion && !controller.signal.aborted) {
        this.error = error instanceof Error ? sshPageError(error) : String(error);
      }
    } finally {
      if (version === this.operationVersion) {
        this.operation = null;
        this.busy = '';
        this.clearSecrets();
      }
    }
  }

  private renameOnlyAvailable(): boolean {
    const original: SSHProfile | null = this.original;
    return original !== null && this.host.trim() === original.host && Number(this.portText) === original.port &&
      this.username.trim() === original.username && (this.authIndex === 1 ? 'privateKey' : 'password') === original.authMethod &&
      this.keyId === (original.keyId ?? '') && this.jumpProfileId === (original.jumpProfileId ?? '') &&
      this.secret.length === 0 && this.passphrase.length === 0;
  }

  private async renameOnly(): Promise<void> {
    const store: SSHProfileStore | null = this.store;
    if (store === null || this.busy.length > 0 || !this.renameOnlyAvailable()) return;
    const version: number = ++this.operationVersion;
    this.busy = '正在保存名称…';
    try {
      await store.saveMetadata(this.draft(), this.expectedRevision);
      if (this.alive && version === this.operationVersion) { this.closeEditor(); this.notice = '名称已保存'; }
    } catch (error) {
      if (this.alive && version === this.operationVersion) this.error = error instanceof Error ? sshPageError(error) : String(error);
    } finally { if (this.alive && version === this.operationVersion) this.busy = ''; }
  }

  private async makeDefault(profile: SSHProfile): Promise<void> {
    if (this.store === null) return;
    try { await this.store.setDefault(profile.id); }
    catch (error) { this.error = error instanceof Error ? sshPageError(error) : String(error); }
  }

  private deleteProfile(profile: SSHProfile): void {
    AlertDialog.show({
      title: '删除 SSH 配置', message: `删除「${profile.name}」及其本设备凭据？`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: this.colors.error, action: (): void => {
        if (this.store === null) return;
        this.store.deleteProfile(profile.id, profile.revision).catch((error: Error): void => {
          if (this.alive) this.error = sshPageError(error);
        });
      } },
    });
  }

  @Builder
  private AssistantToolRows(): void {
    ForEach(this.assistants, (assistant: Assistant): void => {
      Row() {
        Column({ space: 4 }) {
          Text(assistant.name.length > 0 ? assistant.name : '未命名助手')
            .fontSize(HEADER).fontColor(this.colors.ink).fontFamily(FONT_SANS_MEDIUM)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
          Text(!this.assistantAllowsTerminal(assistant) ? '当前助手的工具范围不包含终端'
            : assistant.localTools.indexOf('terminal') >= 0 ? '已启用 SSH / Mosh 终端工具' : '尚未启用终端工具')
            .fontSize(META).fontColor(this.colors.secondary)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Button(this.assistantSavingId === assistant.id ? '保存中…'
          : assistant.localTools.indexOf('terminal') >= 0 ? '停用' : '启用')
          .type(ButtonType.Normal).constraintSize({ minHeight: 44 }).fontSize(BODY)
          .fontColor(this.accentColor).backgroundColor(Color.Transparent)
          .enabled(this.assistantSavingId.length === 0 && (this.assistantAllowsTerminal(assistant)
            || assistant.localTools.indexOf('terminal') >= 0))
          .onClick((): void => { this.setAssistantTerminal(assistant.id, assistant.localTools.indexOf('terminal') < 0); })
      }.width('100%').padding(PAD_CARD).border({ width: { bottom: 0.5 }, color: this.colors.line })
    }, (assistant: Assistant): string => `${assistant.id}-${assistant.localTools.indexOf('terminal') >= 0}`)
  }

  @Builder
  private ProfileRows(): void {
    ForEach(this.profiles, (profile: SSHProfile): void => {
      Column() {
        Row() {
          Column() {
            Text(profile.name).fontSize(HEADER).fontColor(this.colors.ink).fontFamily(FONT_SANS_MEDIUM)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
            Text(`${profile.username}@${profile.host}:${profile.port}`)
              .fontSize(META).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 4 })
            Text(profile.id === this.defaultId ? '默认配置' : 'SSH 配置')
              .fontSize(META).fontColor(profile.id === this.defaultId ? this.accentColor : this.colors.muted).margin({ top: 4 })
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Button('编辑').type(ButtonType.Normal).constraintSize({ minHeight: 44 }).fontSize(BODY)
            .fontColor(this.accentColor).backgroundColor(Color.Transparent)
            .onClick((): void => { this.edit(profile); })
        }.constraintSize({ minHeight: 52 }).width('100%')
        Row() {
          Button(profile.id === this.defaultId ? '已默认' : '设为默认')
            .type(ButtonType.Normal).constraintSize({ minHeight: 44 }).padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 8 })
            .fontSize(BODY).fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.secondary)
            .border({ width: 0.5, color: this.colors.line })
            .borderRadius(22)
            .backgroundColor(this.colors.inset).enabled(profile.id !== this.defaultId)
            .onClick((): void => { this.makeDefault(profile); })
          Button({ type: ButtonType.Normal }) {
            Row({ space: 6 }) {
              Icon({ name: 'ph_terminalWindow', iconSize: 16, color: this.accentColor })
              Text('终端').fontSize(BODY).fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColor)
            }.justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          }.constraintSize({ minHeight: 44 }).borderRadius(22).padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 8 })
            .backgroundColor(accentSoftBg(this.accentColor)).margin({ left: 8 })
            .enabled(profile.knownHostSHA256 !== null && (profile.credentialRef !== null || !!profile.keyId))
            .onClick((): void => { router.pushUrl({ url: 'pages/TerminalPage', params: { profileId: profile.id } }); })
          Blank()
          Button('删除').type(ButtonType.Normal).constraintSize({ minHeight: 44 }).fontSize(BODY2)
            .fontColor(this.colors.error).backgroundColor(Color.Transparent)
            .onClick((): void => { this.deleteProfile(profile); })
        }.width('100%').margin({ top: 8 })
      }.width('100%').padding(PAD_CARD).border({ width: { bottom: 0.5 }, color: this.colors.line })
    }, (profile: SSHProfile): string => profile.id)
  }

  @Builder
  private ConnectionFields(): void {
    Column({ space: 8 }) {
      TextInput({ text: this.name, placeholder: '配置名称' }).constraintSize({ minHeight: 52 })
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.name = value; this.draftChanged(); })
      TextInput({ text: this.host, placeholder: '主机名或 IP' }).constraintSize({ minHeight: 52 })
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).fontFamily(FONT_MONO).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.host = value; this.draftChanged(); })
      TextInput({ text: this.portText, placeholder: '端口' }).constraintSize({ minHeight: 52 }).type(InputType.Number)
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).fontFamily(FONT_MONO).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.portText = value; this.draftChanged(); })
      TextInput({ text: this.username, placeholder: '用户名' }).constraintSize({ minHeight: 52 })
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
        .onChange((value: string): void => { this.username = value; this.draftChanged(); })
      Text('跳板主机').fontSize(META).fontColor(this.colors.secondary).width('100%')
      Select([{ value: '直连' }, ...this.profiles.filter((p: SSHProfile): boolean => p.id !== this.draftId)
        .map((p: SSHProfile): SelectOption => ({ value: p.name }))])
        .value(this.profiles.find((p: SSHProfile): boolean => p.id === this.jumpProfileId)?.name ?? '直连')
        .enabled(this.busy.length === 0)
        .onSelect((index: number): void => {
          this.jumpProfileId = index === 0 ? '' : this.profiles.filter((p: SSHProfile): boolean => p.id !== this.draftId)[index - 1].id;
          this.draftChanged();
        })
      Seg({ options: ['密码', '私钥'], selected: this.authIndex, onSelect: (index: number): void => {
        if (this.busy.length > 0) return;
        this.clearSecrets(); this.keyId = '';
        this.draftChanged();
        this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.authIndex = index;
        });
      } })
    }.width('100%').padding(PAD_CARD)
  }

  @Builder
  private CredentialFields(): void {
    Column({ space: 10 }) {
      if (this.authIndex === 1) {
        Column({ space: 10 }) {
          Select([{ value: '粘贴私钥（兼容）' }, ...this.keys.map((key: SSHKey): SelectOption => ({ value: key.name }))])
            .value(this.keys.find((key: SSHKey): boolean => key.id === this.keyId)?.name ?? '粘贴私钥（兼容）')
            .enabled(this.busy.length === 0)
            .onSelect((index: number): void => {
              this.keyId = index === 0 ? '' : this.keys[index - 1].id;
              this.clearSecrets(); this.draftChanged();
            })
          if (this.keyId.length === 0) {
          TextArea({ text: this.secret, placeholder: '粘贴 OpenSSH / PEM 私钥' })
            .constraintSize({ minHeight: 150 }).fontSize(BODY2).fontFamily(FONT_MONO).fontColor(this.colors.ink)
            .placeholderColor(this.colors.muted)
            .backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
            .copyOption(CopyOptions.None)
            .onChange((value: string): void => { this.secret = value; })
          Row() {
            TextInput({ text: this.passphrase, placeholder: '私钥口令（可选）' })
              .type(this.showPassphrase ? InputType.Normal : InputType.Password).showPasswordIcon(false).constraintSize({ minHeight: 52 })
              .layoutWeight(1).fontColor(this.colors.ink).placeholderColor(this.colors.muted)
              .backgroundColor(Color.Transparent).enabled(this.busy.length === 0)
              .onChange((value: string): void => { this.passphrase = value; })
            Row() { Icon({ name: this.showPassphrase ? 'eye' : 'eye_off', iconSize: 20, color: this.colors.secondary }) }
              .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
              .accessibilityText(this.showPassphrase ? '隐藏私钥口令' : '显示私钥口令')
              .enabled(this.busy.length === 0).onClick((): void => { this.showPassphrase = !this.showPassphrase; })
          }.width('100%').constraintSize({ minHeight: 52 }).padding({ right: 4 }).borderRadius(26).backgroundColor(this.colors.inset)
          }
        }.width('100%')
        .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      } else {
        Row() {
          TextInput({ text: this.secret, placeholder: '密码' })
            .type(this.showSecret ? InputType.Normal : InputType.Password).showPasswordIcon(false).constraintSize({ minHeight: 52 })
            .layoutWeight(1).fontColor(this.colors.ink).placeholderColor(this.colors.muted)
            .backgroundColor(Color.Transparent).enabled(this.busy.length === 0)
            .onChange((value: string): void => { this.secret = value; })
          Row() { Icon({ name: this.showSecret ? 'eye' : 'eye_off', iconSize: 20, color: this.colors.secondary }) }
            .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .accessibilityText(this.showSecret ? '隐藏密码' : '显示密码')
            .enabled(this.busy.length === 0).onClick((): void => { this.showSecret = !this.showSecret; })
        }.width('100%').constraintSize({ minHeight: 52 }).padding({ right: 4 }).borderRadius(26).backgroundColor(this.colors.inset)
        .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      Text(this.hasSavedCredential ? '已有本设备凭据。连接信息相同时可留空沿用，秘密不会回填。' : '认证成功后才保存凭据。')
        .fontSize(META).fontColor(this.colors.secondary).width('100%')
    }.width('100%').padding(PAD_CARD)
  }

  @Builder
  private TrustDetails(candidate: SSHTrustProbe): void {
    Text(candidate.trustState === 'trusted' ? '主机指纹匹配' : candidate.trustState === 'mismatch'
      ? '主机指纹已改变。请核对服务器后再信任。' : '首次连接。请通过可信渠道核对完整主机指纹。')
      .fontSize(BODY).fontColor(candidate.trustState === 'trusted' ? this.colors.success : this.colors.warning)
      .width('100%')
    Text(`第 ${candidate.hopIndex ?? 1} 跳 · ${this.host.trim()}:${this.portText} · ${candidate.hostKeyType}`)
      .fontSize(META).fontFamily(FONT_MONO).fontColor(this.colors.secondary).width('100%')
    Text(candidate.fingerprintSHA256).fontSize(BODY2).fontFamily(FONT_MONO)
      .fontColor(this.colors.ink).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
    if (candidate.trustState === 'mismatch' && candidate.expectedFingerprintSHA256 !== null) {
      Text('原主机指纹').fontSize(META).fontColor(this.colors.muted).width('100%')
      Text(candidate.expectedFingerprintSHA256).fontSize(META).fontFamily(FONT_MONO)
        .fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
    }
    Button(candidate.trustState === 'trusted' ? '验证并保存' : '信任并验证')
      .width('100%').constraintSize({ minHeight: 52 }).fontSize(BODY).backgroundColor(this.accentColor)
      .fontColor(this.colors.accentInk).enabled(this.busy.length === 0)
      .onClick((): void => { this.verify(); })
  }

  @Builder
  private TrustCard(): void {
    Column({ space: 10 }) {
      if (this.candidate !== null) {
        this.TrustDetails(this.candidate)
      }
      Button(this.candidate === null ? '探测主机指纹' : '重新探测').width('100%').constraintSize({ minHeight: 52 })
        .type(ButtonType.Normal).fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .enabled(this.busy.length === 0).onClick((): void => { this.probe(); })
      if (this.renameOnlyAvailable()) {
        Button('仅保存名称').width('100%').constraintSize({ minHeight: 52 }).type(ButtonType.Normal)
          .fontColor(this.colors.secondary).backgroundColor(this.colors.inset).enabled(this.busy.length === 0)
          .onClick((): void => { this.renameOnly(); })
      }
      if (this.busy.length > 0) {
        Text(this.busy).fontSize(BODY2).fontColor(this.colors.secondary)
        Button('取消').constraintSize({ minHeight: 52 }).width('100%').type(ButtonType.Normal)
          .fontColor(this.colors.secondary).backgroundColor(this.colors.inset)
          .onClick((): void => { this.cancelOperation(); this.clearSecrets(); this.notice = '已取消，原配置保持可用'; })
      }
    }.width('100%').padding(PAD_CARD)
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: this.editing ? (this.original === null ? '新建 SSH 配置' : '编辑 SSH 配置') : 'SSH 配置',
        actionText: this.editing ? '' : '新建', onAction: (): void => { this.edit(null); },
        onBack: (): void => { if (this.editing) this.closeEditor(); else router.back(); } })
      Scroll() {
        Column() {
          if (this.error.length > 0) {
            Text(this.error).fontSize(14).fontColor(this.colors.error).width('100%').padding(16)
          }
          if (this.notice.length > 0) {
            Text(this.notice).fontSize(14).fontColor(this.colors.success).width('100%').padding(16)
          }
          Stack({ alignContent: Alignment.TopStart }) {
            if (this.editing) {
              Column() {
                PlainSection({ label: '连接信息' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.ConnectionFields(); } }).margin({ left: 16, right: 16 })
                PlainSection({ label: '认证凭据' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.CredentialFields(); } }).margin({ left: 16, right: 16 })
                PlainSection({ label: '主机确认' })
                CardGroup({ radius: 18, stroke: true, content: (): void => { this.TrustCard(); } }).margin({ left: 16, right: 16 })
                FooterNote({ text: '探测只认证已信任的前置跳板，不向当前主机发送凭据；正式认证会再次校验指纹。失败或取消不会替换原配置。' })
              }.width('100%')
              .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 12 }))
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            } else {
              Column() {
                SSHKeysSection({ colors: this.colors, accent: this.accentColor })
                PlainSection({ label: '远程主机' })
                if (this.profiles.length > 0) {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.ProfileRows(); } }).margin({ left: 16, right: 16 })
                } else {
                  Text(this.loaded ? '暂无 SSH 配置，点击“新建”连接远程主机' : '正在加载…')
                    .fontSize(14).fontColor(this.colors.secondary).width('100%').padding(24)
                }
                FooterNote({ text: '凭据只保存在本设备安全仓，备份恢复后需重新录入。切到后台会断开 SSH，回到前台不会自动重发命令。' })
                PlainSection({ label: '供聊天助手使用' })
                if (this.assistants.length > 0) {
                  CardGroup({ radius: 18, stroke: true, content: (): void => { this.AssistantToolRows(); } }).margin({ left: 16, right: 16 })
                }
                if (this.assistantError.length > 0) {
                  Text(this.assistantError).fontSize(14).fontColor(this.colors.error).width('100%').padding(16)
                  if (this.assistants.length === 0) {
                    Button('重新加载助手').height(44).fontColor(this.accentColor).backgroundColor(Color.Transparent)
                      .onClick((): void => { this.loadAssistantsForTools(); })
                  }
                }
                FooterNote({ text: '保存 SSH 配置后，还需为聊天助手启用终端工具。启用后从下一次发送消息起生效，执行远程命令仍需审批。' })
              }.width('100%')
              .transition(TransitionEffect.OPACITY
                .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
            }
          }.width('100%')
          PageSpacer()
        }.width('100%').justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off).edgeEffect(EdgeEffect.Spring)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
