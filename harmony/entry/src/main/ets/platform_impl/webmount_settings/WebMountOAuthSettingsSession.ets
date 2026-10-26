import type {
  WebMountOAuthApplication, WebMountOAuthApplicationDraft, WebMountOAuthState,
} from '@amber/chat-domain';

export interface WebMountOAuthSettingsPorts {
  loadApplications: () => Promise<WebMountOAuthApplication[]>;
  saveApplication: (draft: WebMountOAuthApplicationDraft) => Promise<WebMountOAuthApplication>;
  readState: (siteId: string) => Promise<WebMountOAuthState>;
  subscribe: (siteId: string, listener: (state: WebMountOAuthState) => void) => (() => void);
  connect: (siteId: string) => Promise<WebMountOAuthState>;
  cancel: (siteId: string) => void;
  disconnect: (siteId: string) => Promise<void>;
  animateForm: (apply: () => void) => void;
}

// The page owns subscriptions; theme remounts only replace the form view.
@Observed
export class WebMountOAuthSettingsSession {
  // 通用站点 OAuth
  oauthApps: WebMountOAuthApplication[] = [];
  oauthStates: Map<string, WebMountOAuthState> = new Map();
  oauthSiteId: string = '';
  oauthAuthEndpoint: string = '';
  oauthTokenEndpoint: string = '';
  oauthClientId: string = '';
  oauthRedirect: string = '';
  oauthScope: string = '';
  oauthEncoding: string = 'form';
  oauthClientAuth: string = 'body';
  oauthApiOrigins: string = '';
  oauthSecret: string = '';
  oauthNote: string = '';
  oauthBusy: boolean = false;
  // 长表单默认收起(有已配置应用时);添加/编辑展开,保存成功收起
  oauthFormOpen: boolean = false;
  private oauthUnsubscribers: Array<() => void> = [];
  private alive: boolean = false;
  private refreshSequence: number = 0;
  private ports: WebMountOAuthSettingsPorts | null = null;
  private get deps(): WebMountOAuthSettingsPorts { return this.ports as WebMountOAuthSettingsPorts; }

  start(ports: WebMountOAuthSettingsPorts): void {
    this.ports = ports;
    this.alive = true;
    this.refreshOauth();
  }

  dispose(): void {
    this.alive = false;
    this.refreshSequence++;
    for (const unsubscribe of this.oauthUnsubscribers) unsubscribe();
    this.oauthUnsubscribers = [];
  }

  refreshOauth(): void {
    if (!this.alive) return;
    const sequence: number = ++this.refreshSequence;
    for (const unsubscribe of this.oauthUnsubscribers) unsubscribe();
    this.oauthUnsubscribers = [];
    this.deps.loadApplications().then((apps: WebMountOAuthApplication[]): void => {
      // A completed load must not attach observers after disposal or a newer refresh.
      if (!this.alive || sequence !== this.refreshSequence) return;
      this.oauthApps = apps;
      for (const app of apps) {
        this.deps.readState(app.siteId).then((state: WebMountOAuthState): void => {
          if (!this.alive || sequence !== this.refreshSequence) return;
          this.oauthStates.set(app.siteId, state);
          this.oauthStates = new Map(this.oauthStates);
        }).catch((): void => {});
        this.oauthUnsubscribers.push(this.deps.subscribe(app.siteId,
          (state: WebMountOAuthState): void => {
            if (!this.alive || sequence !== this.refreshSequence) return;
            this.oauthStates.set(app.siteId, state);
            this.oauthStates = new Map(this.oauthStates);
          }));
      }
    }).catch((): void => {});
  }

  oauthStatusText(siteId: string): string {
    const state: WebMountOAuthState | undefined = this.oauthStates.get(siteId);
    if (state === undefined) return '状态未知';
    if (state.status === 'connected') return '已连接';
    if (state.status === 'awaiting_callback') return '等待浏览器回调…';
    if (state.status === 'expired') return '已过期';
    if (state.status === 'failed') return `失败：${state.message ?? state.errorCode ?? ''}`;
    if (state.status === 'disconnected') return '未连接';
    return '未配置';
  }

  oauthParseClientAuth(value: string): 'none' | 'body' | 'basic' {
    const trimmed: string = value.trim().toLowerCase();
    if (trimmed === 'none' || trimmed === 'basic') return trimmed;
    return 'body';
  }

  editOauth(app: WebMountOAuthApplication): void {
    this.oauthSiteId = app.siteId;
    this.oauthAuthEndpoint = app.authorizationEndpoint;
    this.oauthTokenEndpoint = app.tokenEndpoint;
    this.oauthClientId = app.clientId;
    this.oauthRedirect = app.redirectUri;
    this.oauthScope = app.scope;
    this.oauthEncoding = app.tokenEncoding;
    this.oauthClientAuth = app.clientAuthentication;
    this.oauthApiOrigins = app.apiOrigins.join(',');
    this.oauthSecret = '';
    this.oauthNote = `编辑 ${app.siteId}；密钥留空则保留`;
    this.setOauthFormOpen(true);
  }

  saveOauth(): void {
    if (this.oauthBusy) return;
    this.oauthBusy = true;
    this.oauthNote = '保存中…';
    const application: WebMountOAuthApplication = {
      siteId: this.oauthSiteId.trim(),
      bindingHash: '',
      authorizationEndpoint: this.oauthAuthEndpoint.trim(),
      tokenEndpoint: this.oauthTokenEndpoint.trim(),
      clientId: this.oauthClientId.trim(),
      redirectUri: this.oauthRedirect.trim().length > 0 ? this.oauthRedirect.trim() : 'http://127.0.0.1:53682/callback',
      scope: this.oauthScope.trim(),
      tokenEncoding: this.oauthEncoding.trim().toLowerCase() === 'json' ? 'json' : 'form',
      clientAuthentication: this.oauthParseClientAuth(this.oauthClientAuth),
      apiOrigins: this.oauthApiOrigins.split(',').map((item: string): string => item.trim())
        .filter((item: string): boolean => item.length > 0),
      clientSecretRef: null,
    };
    const draft: WebMountOAuthApplicationDraft = {
      application: application,
      secretAction: this.oauthSecret.trim().length > 0 ? 'replace' : 'keep',
      clientSecret: this.oauthSecret.trim().length > 0 ? this.oauthSecret.trim() : null,
    };
    this.deps.saveApplication(draft).then((saved: WebMountOAuthApplication): void => {
      if (!this.alive) return;
      this.oauthNote = `已保存 ${saved.siteId}(绑定 ${saved.bindingHash.substring(0, 8)}…)`;
      this.oauthSecret = '';
      this.setOauthFormOpen(false);
      this.refreshOauth();
    }).catch((error: Error): void => {
      if (!this.alive) return;
      this.oauthNote = `保存失败：${error.message}`;
    }).finally((): void => {
      if (this.alive) this.oauthBusy = false;
    });
  }

  connectOauth(siteId: string): void {
    if (this.oauthBusy) return;
    this.oauthBusy = true;
    this.oauthNote = '';
    this.deps.connect(siteId).then((state: WebMountOAuthState): void => {
      if (!this.alive) return;
      if (state.status === 'awaiting_callback') this.oauthNote = '已在系统浏览器打开授权页，完成后自动回填';
    }).catch((error: Error): void => {
      if (!this.alive) return;
      this.oauthNote = `连接失败：${error.message}`;
    }).finally((): void => {
      if (this.alive) this.oauthBusy = false;
    });
  }

  cancelOauth(siteId: string): void {
    this.deps.cancel(siteId);
    this.oauthNote = '已取消本次授权';
  }

  disconnectOauth(siteId: string): void {
    this.deps.disconnect(siteId).then((): void => {
      if (!this.alive) return;
      this.oauthNote = `已断开 ${siteId}`;
    }).catch((): void => {});
  }

  openAdd(): void {
    this.oauthSiteId = '';
    this.oauthAuthEndpoint = '';
    this.oauthTokenEndpoint = '';
    this.oauthClientId = '';
    this.oauthRedirect = '';
    this.oauthScope = '';
    this.oauthApiOrigins = '';
    this.oauthSecret = '';
    this.oauthNote = '';
    this.setOauthFormOpen(true);
  }

  setOauthFormOpen(open: boolean): void {
    this.deps.animateForm((): void => {
      this.oauthFormOpen = open;
    });
  }

}
