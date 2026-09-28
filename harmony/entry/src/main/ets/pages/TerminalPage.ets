import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { SSHProfileStore, TerminalRuntime } from '@amber/chat-domain';
import type { SSHProfile, SSHProfileSettings, TerminalSessionEvent, TerminalSessionSnapshot,
  TerminalSessionRequest } from '@amber/chat-domain';
import { getSSHProfileStore, getTerminalRuntime } from '../di/AppContainer.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { CenteredTitleBar } from '../components/PageTemplate.ets';
import { TerminalView, TerminalViewController } from '../components/TerminalView.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { sshPageError, sshPageErrorMessage } from '../components/SSHPageErrors.ets';
import { Icon } from '../components/ui/Icon.ets';
import { ACCENT } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

interface TerminalRouteParams { profileId?: string; }

@Entry
@Component
struct TerminalPage {
  @State profiles: SSHProfile[] = [];
  @State selectedId: string = '';
  @State choosingProfile: boolean = false;
  @State loaded: boolean = false;
  @State terminalReady: boolean = false;
  @State connecting: boolean = false;
  @State disconnecting: boolean = false;
  @State session: TerminalSessionSnapshot | null = null;
  @State connectionLabel: string = '';
  @State error: string = '';
  @State columns: number = 0;
  @State rows: number = 0;
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('appBackgrounded') @Watch('backgroundChanged') backgrounded: boolean = false;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  private runtime: TerminalRuntime | null = null;
  private store: SSHProfileStore | null = null;
  private terminal: TerminalViewController = new TerminalViewController();
  private profileUnsubscribe: (() => void) | null = null;
  private sessionUnsubscribe: (() => void) | null = null;
  private operation: EntryAbortController | null = null;
  private alive: boolean = false;
  private operationVersion: number = 0;
  private ownedSessionId: string = '';

  aboutToAppear(): void {
    this.alive = true;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    const params: TerminalRouteParams = router.getParams() as TerminalRouteParams;
    if (params !== undefined && params !== null) this.selectedId = params.profileId ?? '';
    this.terminal.onError = (message: string): void => {
      if (this.alive) { this.error = sshPageErrorMessage(message); this.disconnect(); }
    };
    this.load();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.OFFSET);
    this.releaseOwnedSession();
    if (this.profileUnsubscribe !== null) this.profileUnsubscribe();
    this.profileUnsubscribe = null;
  }

  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private backgroundChanged(): void {
    if (!this.backgrounded) return;
    // EntryAbility owns interruptForBackground; this page only cancels its pending start.
    this.operationVersion++;
    if (this.operation !== null) this.operation.abort();
    this.operation = null;
    this.connecting = false;
    this.terminal.setInputEnabled(false);
  }

  private async load(): Promise<void> {
    try {
      const store: SSHProfileStore = await getSSHProfileStore();
      const runtime: TerminalRuntime = await getTerminalRuntime();
      if (!this.alive) return;
      this.store = store;
      this.runtime = runtime;
      this.applySettings(store.snapshot());
      this.profileUnsubscribe = store.subscribe((settings: SSHProfileSettings): void => {
        if (this.alive) this.applySettings(settings);
      });
      this.loaded = true;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? sshPageError(error) : String(error);
    }
  }

  private applySettings(settings: SSHProfileSettings): void {
    this.profiles = settings.profiles;
    if (this.selectedId.length === 0) this.selectedId = settings.defaultProfileId ?? '';
    if (!this.isRunning() && !this.connecting && this.profiles.find((profile: SSHProfile): boolean =>
      profile.id === this.selectedId) === undefined) this.selectedId = settings.defaultProfileId ?? '';
  }

  private selectedProfile(): SSHProfile | null {
    return this.profiles.find((profile: SSHProfile): boolean => profile.id === this.selectedId) ?? null;
  }

  private isRunning(): boolean {
    return this.session !== null && (this.session.status === 'RUNNING' || this.session.status === 'QUEUED');
  }

  private statusText(): string {
    if (this.connecting) return '正在连接…';
    if (this.disconnecting) return '正在断开…';
    if (this.session === null) return this.terminalReady ? '未连接' : '终端加载中…';
    if (this.session.status === 'RUNNING') return `已连接 · ${this.columns} × ${this.rows}`;
    if (this.session.status === 'QUEUED') return '正在连接…';
    if (this.session.status === 'COMPLETED') return '远程 Shell 已退出';
    if (this.session.status === 'CANCELLED') return '已断开';
    if (this.session.status === 'INTERRUPTED') return '连接已中断，请重新连接';
    if (this.session.status === 'TIMED_OUT') return '连接超时';
    return '连接失败';
  }

  private canConnect(): boolean {
    const profile: SSHProfile | null = this.selectedProfile();
    return this.loaded && this.terminalReady && !this.connecting && !this.disconnecting &&
      !this.isRunning() && !this.backgrounded && this.columns >= 1 && this.rows >= 1 &&
      profile !== null && profile.knownHostSHA256 !== null && profile.credentialRef !== null;
  }

  private async connect(): Promise<void> {
    const runtime: TerminalRuntime | null = this.runtime;
    const profile: SSHProfile | null = this.selectedProfile();
    if (runtime === null || profile === null || !this.canConnect()) return;
    if (this.sessionUnsubscribe !== null) this.sessionUnsubscribe();
    this.sessionUnsubscribe = null;
    this.ownedSessionId = '';
    this.session = null;
    this.choosingProfile = false;
    this.error = '';
    this.connecting = true;
    this.connectionLabel = `${profile.username}@${profile.host}:${profile.port}`;
    const controller: EntryAbortController = new EntryAbortController();
    this.operation = controller;
    const version: number = ++this.operationVersion;
    try {
      await this.terminal.reset();
      const request: TerminalSessionRequest = {
        target: runtime.captureTarget(profile.id), cwd: null, columns: this.columns, rows: this.rows,
        sourceToolName: null, sourceConversationId: null,
      };
      const session: TerminalSessionSnapshot = await runtime.startSession(request, controller.signal);
      if (!this.alive || version !== this.operationVersion || controller.signal.aborted || this.backgrounded) {
        await runtime.stopSession(session.sessionId);
        return;
      }
      this.ownedSessionId = session.sessionId;
      this.session = session;
      // Domain's first pump is delayed until the next tick; subscribe before it drains the prompt.
      this.sessionUnsubscribe = runtime.subscribeSession(session.sessionId, (event: TerminalSessionEvent): void => {
        this.consumeEvent(event);
      });
      if (this.isRunning() && (session.columns !== this.columns || session.rows !== this.rows)) {
        await runtime.resizeSession(session.sessionId, this.columns, this.rows);
      }
      this.terminal.setInputEnabled(this.isRunning());
    } catch (error) {
      if (this.alive && version === this.operationVersion && !controller.signal.aborted) {
        this.error = error instanceof Error ? sshPageError(error) : String(error);
      }
    } finally {
      if (version === this.operationVersion) { this.operation = null; this.connecting = false; }
    }
  }

  private consumeEvent(event: TerminalSessionEvent): void {
    if (!this.alive || event.snapshot.sessionId !== this.ownedSessionId) return;
    this.session = event.snapshot;
    if (event.snapshot.errorMessage !== null) {
      this.error = sshPageErrorMessage(event.snapshot.errorMessage, event.snapshot.errorCode);
    }
    const eventSessionId: string = event.snapshot.sessionId;
    for (const chunk of event.chunks) {
      this.writeChunk(chunk.bytes, eventSessionId);
    }
    this.terminal.setInputEnabled(this.isRunning() && !this.backgrounded);
  }

  private writeChunk(bytes: Uint8Array, sessionId: string): void {
    this.terminal.write(bytes).catch((error: Error): void => {
      if (this.alive && sessionId === this.ownedSessionId) {
        this.error = sshPageError(error);
        this.disconnect();
      }
    });
  }

  private async sendBytes(bytes: Uint8Array): Promise<void> {
    const runtime: TerminalRuntime | null = this.runtime;
    if (runtime === null || this.ownedSessionId.length === 0 || !this.isRunning() || this.backgrounded) return;
    try { await runtime.sendSessionBytes(this.ownedSessionId, bytes); }
    catch (error) { if (this.alive) this.error = error instanceof Error ? sshPageError(error) : String(error); }
  }

  private resize(columns: number, rows: number): void {
    if (columns === this.columns && rows === this.rows) return;
    this.columns = columns;
    this.rows = rows;
    if (this.runtime === null || this.ownedSessionId.length === 0 || !this.isRunning() || this.backgrounded) return;
    this.runtime.resizeSession(this.ownedSessionId, columns, rows).catch((error: Error): void => {
      if (this.alive) this.error = sshPageError(error);
    });
  }

  private async disconnect(): Promise<void> {
    this.operationVersion++;
    if (this.operation !== null) this.operation.abort();
    this.operation = null;
    this.connecting = false;
    this.terminal.setInputEnabled(false);
    const runtime: TerminalRuntime | null = this.runtime;
    const id: string = this.ownedSessionId;
    if (runtime === null || id.length === 0 || this.disconnecting || !this.isRunning()) return;
    this.disconnecting = true;
    try {
      const snapshot: TerminalSessionSnapshot = await runtime.stopSession(id);
      if (this.alive && id === this.ownedSessionId) this.session = snapshot;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? sshPageError(error) : String(error);
    } finally { if (this.alive) this.disconnecting = false; }
  }

  private releaseOwnedSession(): void {
    this.operationVersion++;
    if (this.operation !== null) this.operation.abort();
    this.operation = null;
    this.connecting = false;
    this.terminal.setInputEnabled(false);
    if (this.sessionUnsubscribe !== null) this.sessionUnsubscribe();
    this.sessionUnsubscribe = null;
    const id: string = this.ownedSessionId;
    this.ownedSessionId = '';
    this.session = null;
    this.connectionLabel = '';
    if (this.runtime !== null && id.length > 0) {
      this.runtime.stopSession(id).catch((error: Error): void => {
        if (this.alive) this.error = sshPageError(error);
      });
    }
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '终端', actionText: '配置', onAction: (): void => {
        this.releaseOwnedSession(); router.pushUrl({ url: 'pages/SettingSSHProfilesPage' });
      } })
      Column({ space: 6 }) {
        if (this.profiles.length > 0) {
          Row() {
            Text(this.selectedProfile()?.name ?? '选择 SSH 配置').fontSize(14).fontColor(this.colors.ink)
              .layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Icon({ name: 'chevron_down', iconSize: 18, color: this.colors.secondary })
          }
            .width('100%').height(52).padding({ left: 14, right: 14 })
            .backgroundColor(this.colors.surface).borderRadius(16)
            .enabled(!this.isRunning() && !this.connecting && !this.disconnecting)
            .accessibilityText(`选择 SSH 配置: ${this.selectedProfile()?.name ?? '未选择'}`)
            .onClick((): void => { this.choosingProfile = !this.choosingProfile; })
          if (this.choosingProfile && !this.isRunning() && !this.connecting) {
            Scroll() {
              Column() {
                ForEach(this.profiles, (profile: SSHProfile): void => {
                  Row() {
                    Text(profile.name).fontSize(14).fontColor(this.colors.ink).layoutWeight(1)
                      .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                    if (profile.id === this.selectedId) {
                      Text('✓').fontSize(14).fontColor(this.accentColor).margin({ left: 12 })
                    }
                  }.width('100%').height(52).padding({ left: 14, right: 14 })
                    .border({ width: { bottom: 0.5 }, color: this.colors.line })
                    .onClick((): void => { this.selectedId = profile.id; this.choosingProfile = false; })
                }, (profile: SSHProfile): string => profile.id)
              }.width('100%')
            }.width('100%').height(Math.min(this.profiles.length * 52, 208)).align(Alignment.TopStart)
              .backgroundColor(this.colors.surface).borderRadius(16).scrollBar(BarState.Auto)
          }
        } else {
          Text(this.loaded ? '先新建并验证 SSH 配置' : '正在加载 SSH 配置…')
            .fontSize(14).fontColor(this.colors.secondary).width('100%').height(52)
        }
        Text(this.isRunning() || this.connecting ? this.connectionLabel : this.selectedProfile() === null ? ''
          : `${this.selectedProfile()!.username}@${this.selectedProfile()!.host}:${this.selectedProfile()!.port}`)
          .fontSize(12).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Row() {
          Text(this.statusText()).fontSize(12).fontColor(this.isRunning() ? this.colors.success : this.colors.secondary)
            .layoutWeight(1).maxLines(2)
          Button(this.connecting ? '取消' : this.isRunning() ? '断开' : '连接')
            .type(ButtonType.Normal).height(44).fontSize(14).fontColor(this.accentColor)
            .backgroundColor(this.colors.surface).enabled(this.connecting || this.isRunning() || this.canConnect())
            .onClick((): void => { if (this.connecting || this.isRunning()) this.disconnect(); else this.connect(); })
        }.width('100%').constraintSize({ minHeight: 44 })
        if (this.error.length > 0) {
          Text(this.error).fontSize(12).fontColor(this.colors.error).width('100%').maxLines(3)
        }
      }.width('100%').padding({ left: 16, right: 16, bottom: 8 })
      Column() {
        TerminalView({ viewController: this.terminal,
          onReady: (columns: number, rows: number): void => {
            this.terminalReady = true; this.resize(columns, rows);
          },
          onInput: (bytes: Uint8Array): void => { this.sendBytes(bytes); },
          onResize: (columns: number, rows: number): void => { this.resize(columns, rows); },
        })
      }.width('100%').layoutWeight(1).padding({ bottom: 16 + this.navBarHeight })
        .backgroundColor(this.colors.inset)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}
