// SettingMacGatewayPage — Mac Gateway:配对 Mac 上的 amber-gateway,接收 Claude Code / Codex 任务推送
//
// 语义对齐 iOS MacGatewaySettingsView / Android SettingExperimentalMacGatewayPage:
//   未配对 → 扫码/粘贴 → 核对指纹确认 → 已配对(连接、推送、Mac 状态、任务开关、取消配对)。
//   鸿蒙推送走华为 Push Kit(Mac 端 creds import huawei);配对/开启推送时申请通知授权,未授权不上报 token。

import { scanBarcode, scanCore } from '@kit.ScanKit';
import { pasteboard, BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import { FONT_SANS } from '../design/fonts.ets';
import { BG, INK3, ERROR, LABEL, META, TEXT_STYLES } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, FooterNote, NavRow, ToggleRow, BtnFill, BtnGhost, PageSpacer } from '../components/PageTemplate.ets';
import {
  MacGatewayClient, MacGatewayError, describeMacGatewayError, parsePairingLink, loadMacGatewayConnection,
  forgetMacGatewayConnection, pairMacGateway, uploadMacGatewayPushToken,
  type MacGatewayConnection, type MacGatewayPairingPayload, type MacGatewayStatus, type MacGatewaySession,
  type MacGatewayHealth,
} from '../platform_impl/MacGatewayClient.ets';

const REFRESH_INTERVAL_MS: number = 10000;
const VISIBLE_TASK_LIMIT: number = 20;
const SCAN_CANCELED: number = 1000500002;
const NOTIFICATIONS_OFF: string = '未允许通知，无法接收推送。请在系统设置中允许 Amber 通知后再点「开启」';

@Entry
@Component
struct SettingMacGatewayPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State loaded: boolean = false;
  @State connection: MacGatewayConnection | null = null;
  @State status: MacGatewayStatus | null = null;
  @State reachable: number = 0; // 0 连接中 · 1 在线 · -1 无法连接
  @State pending: MacGatewayPairingPayload | null = null;
  @State busy: boolean = false;
  @State notice: string = '';
  @State noticeIsError: boolean = false;
  private timer: number = -1;
  private disposed: boolean = false;

  aboutToAppear(): void {
    loadMacGatewayConnection().then((c: MacGatewayConnection | null): void => {
      this.connection = c;
      this.loaded = true;
      this.startPolling();
    }).catch((): void => { this.loaded = true; });
  }

  onPageShow(): void {
    this.startPolling();
  }

  onPageHide(): void {
    this.stopPolling();
  }

  aboutToDisappear(): void {
    this.disposed = true;
    this.stopPolling();
  }

  private startPolling(): void {
    if (this.disposed || this.connection === null || this.timer >= 0) return;
    this.refresh();
    this.timer = setInterval((): void => { this.refresh(); }, REFRESH_INTERVAL_MS);
  }

  private stopPolling(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private showNotice(text: string, isError: boolean): void {
    this.notice = text;
    this.noticeIsError = isError;
  }

  private async refresh(): Promise<void> {
    const c: MacGatewayConnection | null = this.connection;
    if (c === null) return;
    try {
      const status: MacGatewayStatus = await MacGatewayClient.of(c).status();
      if (this.connection !== c) return;
      this.status = status;
      this.reachable = 1;
    } catch (e) {
      // 请求期间已取消/重新配对:旧连接的结果(包括 401)不能作用到新状态上
      if (this.connection !== c) return;
      const err: Error = e as Error;
      if (await this.forgetIfRevoked(err)) return;
      this.reachable = -1;
      if (errorKind(err) === 'certificate') this.showNotice(describeMacGatewayError(err), true);
    }
  }

  /** 401 = Mac 已吊销此设备:任一请求遇到都清除本地配对并给出同一说明 */
  private async forgetIfRevoked(err: Error): Promise<boolean> {
    if (errorKind(err) !== 'unauthorized') return false;
    await this.forget();
    this.showNotice(describeMacGatewayError(err), true);
    return true;
  }

  private async report(err: Error): Promise<void> {
    if (!(await this.forgetIfRevoked(err))) this.showNotice(describeMacGatewayError(err), true);
  }

  private async forget(): Promise<void> {
    this.stopPolling();
    try {
      await forgetMacGatewayConnection();
    } catch (_e) {
      // 本地存储删除失败也要退出已配对界面;残留记录下次打开时会因 401 再次被清理
    }
    this.connection = null;
    this.status = null;
    this.reachable = 0;
  }

  private receiveLink(text: string): void {
    this.pending = parsePairingLink(text);
    if (this.pending === null) this.showNotice('配对链接无效，请在 Mac 上重新运行 amber-gateway pair', true);
    else this.showNotice('', false);
  }

  private scan(): void {
    const options: scanBarcode.ScanOptions = { scanTypes: [scanCore.ScanType.QR_CODE], enableMultiMode: false, enableAlbum: true };
    try {
      scanBarcode.startScanForResult(this.getUIContext().getHostContext(), options)
        .then((r: scanBarcode.ScanResult): void => { this.receiveLink(r.originalValue); })
        .catch((err: BusinessError): void => {
          if (err.code !== SCAN_CANCELED) this.showNotice(`扫码失败：${err.message}`, true);
        });
    } catch (e) {
      this.showNotice(`扫码失败：${(e as BusinessError).message}`, true);
    }
  }

  private paste(): void {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      this.receiveLink(board.hasDataSync() ? (board.getDataSync().getPrimaryText() ?? '') : '');
    } catch (_e) {
      this.receiveLink('');
    }
  }

  private async confirmPairing(): Promise<void> {
    const p: MacGatewayPairingPayload | null = this.pending;
    if (p === null || this.busy) return;
    this.busy = true;
    try {
      const c: MacGatewayConnection = await pairMacGateway(p);
      this.pending = null;
      this.connection = c;
      this.showNotice('', false);
      if (!(await this.notificationsAllowed())) {
        this.showNotice(NOTIFICATIONS_OFF, true);
      } else {
        try {
          await uploadMacGatewayPushToken(c);
        } catch (e) {
          this.showNotice(describeMacGatewayError(e as Error), true);
        }
      }
      this.startPolling();
    } catch (e) {
      this.showNotice(describeMacGatewayError(e as Error), true);
    } finally {
      this.busy = false;
    }
  }

  private async run(action: (c: MacGatewayConnection) => Promise<void>, success: string): Promise<void> {
    const c: MacGatewayConnection | null = this.connection;
    if (c === null || this.busy) return;
    this.busy = true;
    try {
      await action(c);
      this.showNotice(success, false);
    } catch (e) {
      await this.report(e as Error);
    } finally {
      this.busy = false;
    }
  }

  /** 已授权时直接成功;用户拒绝或系统通知开关关闭时失败 */
  private async notificationsAllowed(): Promise<boolean> {
    try {
      await notificationManager.requestEnableNotification(this.getUIContext().getHostContext() as common.UIAbilityContext);
      return true;
    } catch (_e) {
      return false;
    }
  }

  private enablePush(): void {
    this.run(async (c: MacGatewayConnection): Promise<void> => {
      if (!(await this.notificationsAllowed())) throw new MacGatewayError('server', NOTIFICATIONS_OFF);
      await uploadMacGatewayPushToken(c);
      await this.refresh();
    }, '');
  }

  private testPush(): void {
    this.run((c: MacGatewayConnection): Promise<void> => MacGatewayClient.of(c).testPush(), '测试推送已发出，留意通知栏');
  }

  private toggleTask(s: MacGatewaySession): void {
    const c: MacGatewayConnection | null = this.connection;
    const status: MacGatewayStatus | null = this.status;
    if (c === null || status === null) return;
    const next: boolean = !s.monitored;
    this.status = this.withMonitored(status, s.key, next);
    MacGatewayClient.of(c).setMonitored(s.key, next).catch((e: Error): void => {
      if (this.status !== null) this.status = this.withMonitored(this.status, s.key, !next);
      this.report(e);
    });
  }

  private withMonitored(status: MacGatewayStatus, key: string, monitored: boolean): MacGatewayStatus {
    const sessions: MacGatewaySession[] = status.sessions.map((s: MacGatewaySession): MacGatewaySession => {
      if (s.key !== key) return s;
      const copy: MacGatewaySession = {
        key: s.key, agent: s.agent, subject: s.subject, origin: s.origin, host: s.host, state: s.state,
        waitReason: s.waitReason, abnormal: s.abnormal, monitored: monitored, updatedAt: s.updatedAt,
      };
      return copy;
    });
    const next: MacGatewayStatus = {
      gateway: status.gateway, device: status.device, serverTime: status.serverTime, health: status.health,
      synaraURL: status.synaraURL, sessions: sessions,
    };
    return next;
  }

  private confirmUnpair(): void {
    AlertDialog.show({
      title: '取消与这台 Mac 的配对？',
      message: '此设备将不再收到这台 Mac 的推送。',
      primaryButton: { value: '保留', action: (): void => {} },
      secondaryButton: {
        value: '取消配对',
        fontColor: ERROR,
        action: (): void => { this.unpair(); },
      },
    });
  }

  private async unpair(): Promise<void> {
    const c: MacGatewayConnection | null = this.connection;
    if (c === null || this.busy) return;
    this.busy = true;
    let reached: boolean = true;
    try {
      await MacGatewayClient.of(c).unpair();
    } catch (e) {
      // 401 说明 Mac 已删除此设备,正是取消配对想要的结果
      reached = errorKind(e as Error) === 'unauthorized';
    }
    await this.forget();
    this.busy = false;
    this.showNotice(reached ? '' : '已在本机取消配对。Mac 当前不可达，可在 Mac 上运行 amber-gateway devices revoke 移除此设备', false);
  }

  // ── 文案 ──

  private reachabilityText(): string {
    if (this.reachable === 0) return '正在连接…';
    if (this.reachable < 0) return '无法连接';
    const health: MacGatewayHealth | undefined = this.status?.health;
    return health === undefined ? '在线' : `在线 · 更新于 ${relativeTime(health.sampledAt)}`;
  }

  private health(): MacGatewayHealth | undefined {
    return this.status?.health;
  }

  private sessions(): MacGatewaySession[] {
    return this.status === null ? [] : this.status.sessions.slice(0, VISIBLE_TASK_LIMIT);
  }

  private pushRegistered(): boolean {
    return this.status !== null && this.status.device.pushRegistered;
  }

  private taskDetail(s: MacGatewaySession): string {
    const agent: string = s.agent === 'claude' ? 'Claude Code' : (s.agent === 'codex' ? 'Codex' : '任务');
    let state: string = s.abnormal ? '意外中断' : '已停止';
    if (s.state === 'running') state = '运行中';
    else if (s.state === 'waiting') state = s.waitReason === 'permission' ? '等你确认' : '等你回复';
    else if (s.state === 'stalled') state = '可能卡住了';
    else if (s.state === 'completed') state = '已完成';
    const host: string = s.host === undefined ? '' : `${s.host} · `;
    return `${agent} · ${host}${state} · ${relativeTime(s.updatedAt)}`;
  }

  private openSynara(url: string): void {
    const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext;
    ctx.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: url })
      .catch((e: Error): void => { this.report(e); });
  }

  // ── 分区 ──

  @Builder
  PairCard(): void {
    NavRow({ label: '扫码配对', desc: '在 Mac 终端运行 amber-gateway pair，扫描显示的二维码', iconName: 'hi_camera',
      onRowClick: (): void => { this.scan(); } })
    NavRow({ label: '粘贴配对链接', desc: '从其他设备复制的 amber:// 链接', iconName: 'clipboard', isLast: true,
      onRowClick: (): void => { this.paste(); } })
  }

  @Builder
  PairSection(): void {
    PlainSection({ label: '配对' })
    CardGroup({ radius: 18, stroke: true, content: (): void => { this.PairCard(); } })
      .margin({ left: 16, right: 16 })
    FooterNote({ text: '把 Mac 上 Claude Code / Codex 任务的状态推送到手机。推送只含项目名与状态，不含代码与对话内容。' })
  }

  // 分区 builder 一律从 this 读状态:@Builder 按值传参时,参数变化不会触发内部刷新
  @Builder
  ConfirmCard(): void {
    NavRow({ label: this.pending?.name ?? '', desc: this.pending?.addrs.join(' · ') ?? '', showChevron: false })
    NavRow({ label: '证书指纹', desc: this.pending?.fp ?? '', showChevron: false, isLast: true })
  }

  @Builder
  ConfirmSection(): void {
    PlainSection({ label: '确认配对' })
    CardGroup({ radius: 18, stroke: true, content: (): void => { this.ConfirmCard(); } })
      .margin({ left: 16, right: 16 })
    FooterNote({ text: '请核对与 Mac 终端显示的证书指纹一致。' })
    Row() {
      BtnGhost({ label: '取消', onTap: (): void => { this.pending = null; } })
      Blank()
      BtnFill({ label: this.busy ? '配对中…' : '配对', onTap: (): void => { this.confirmPairing(); } })
    }
    .width('100%').padding({ left: 16, right: 16, top: 12 })
  }

  @Builder
  MacCard(): void {
    NavRow({ label: this.connection?.name ?? '', desc: this.reachabilityText(), linkText: '刷新',
      onRowClick: (): void => { this.refresh(); } })
    NavRow({
      label: '推送通知',
      // 没拿到状态(Mac 睡眠)时推送状态未知,不能显示成"未开启"
      desc: this.status === null ? '—' : (this.pushRegistered() ? '已开启，任务需要你时会通知' : '未开启'),
      linkText: this.status === null ? '' : (this.pushRegistered() ? (this.busy ? '发送中…' : '测试') : (this.busy ? '开启中…' : '开启')),
      showChevron: false,
      isLast: true,
      onRowClick: (): void => {
        if (this.status === null) return;
        if (this.pushRegistered()) this.testPush(); else this.enablePush();
      },
    })
  }

  @Builder
  MacSection(): void {
    PlainSection({ label: 'Mac' })
    CardGroup({ radius: 18, stroke: true, content: (): void => { this.MacCard(); } })
      .margin({ left: 16, right: 16 })
    if (this.reachable < 0) {
      FooterNote({ text: 'Mac 睡眠、合盖或断网时无法推送，也无法在这里查看状态。' })
    }
  }

  @Builder
  HealthCard(): void {
    if (this.health()?.diskFreeBytes !== undefined) {
      NavRow({ label: '磁盘剩余', valueText: formatBytes(this.health()?.diskFreeBytes ?? 0), showChevron: false })
    }
    if (this.health()?.batteryPercent !== undefined) {
      NavRow({ label: '电量', showChevron: false,
        valueText: `${this.health()?.batteryPercent}%${this.health()?.onBattery === true ? '' : ' · 接电源'}` })
    }
    NavRow({ label: '内存压力', valueText: levelLabel(this.health()?.memoryPressure ?? ''), showChevron: false })
    NavRow({ label: '温度', valueText: levelLabel(this.health()?.thermal ?? ''), showChevron: false, isLast: true })
  }

  @Builder
  HealthSection(): void {
    PlainSection({ label: 'Mac 状态' })
    CardGroup({ radius: 18, stroke: true, content: (): void => { this.HealthCard(); } })
      .margin({ left: 16, right: 16 })
  }

  @Builder
  TasksCard(): void {
    if (this.status?.synaraURL !== undefined) {
      NavRow({ label: '在 Synara 中回复或确认', desc: '在浏览器中打开 Synara，处理其中的任务', iconName: 'hi_message',
        onRowClick: (): void => { this.openSynara(this.status?.synaraURL ?? ''); } })
    }
    if (this.sessions().length === 0) {
      NavRow({ label: '暂无任务', desc: '在 Mac 上使用 Claude Code 或 Codex 后会出现在这里', showChevron: false, isLast: true })
    } else {
      // key 带 serverTime:每次刷新重建行,相对时间才会更新
      ForEach(this.sessions(), (s: MacGatewaySession, i: number): void => {
        ToggleRow({ label: s.subject, desc: this.taskDetail(s), checked: s.monitored, isLast: i === this.sessions().length - 1,
          onToggle: (): void => { this.toggleTask(s); } })
      }, (s: MacGatewaySession): string => `${s.key}-${s.monitored}-${s.state}-${s.updatedAt}-${this.status?.serverTime}`)
    }
  }

  @Builder
  TasksSection(): void {
    PlainSection({ label: '任务' })
    CardGroup({ radius: 18, stroke: true, content: (): void => { this.TasksCard(); } })
      .margin({ left: 16, right: 16 })
    if (this.sessions().length > 0) {
      FooterNote({ text: '关闭开关后，该任务不再推送。' })
    }
  }

  @Builder
  UnpairCard(): void {
    NavRow({ label: this.busy ? '处理中…' : '取消配对', danger: true, showChevron: false, isLast: true,
      onRowClick: (): void => { this.confirmUnpair(); } })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: 'Mac Gateway' })
      if (!this.loaded) {
        Text('加载中…').fontSize(META).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            if (this.notice.length > 0) {
              Text(this.notice)
                .fontSize(LABEL).lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_SANS)
                .fontColor(this.noticeIsError ? ERROR : INK3)
                .width('100%').padding({ left: 16, right: 16, top: 12 })
            }
            if (this.pending !== null) {
              this.ConfirmSection()
            } else if (this.connection !== null) {
              this.MacSection()
              if (this.health() !== undefined) {
                this.HealthSection()
              }
              this.TasksSection()
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.UnpairCard(); } })
              .margin({ left: 16, right: 16, top: 20 })
            } else {
              this.PairSection()
            }
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

const relativeTime = (secondsSince1970: number): string => {
  const diff: number = Math.max(0, Date.now() / 1000 - secondsSince1970);
  if (diff < 60) return '刚刚';
  if (diff < 3600) return `${Math.floor(diff / 60)} 分钟前`;
  if (diff < 86400) return `${Math.floor(diff / 3600)} 小时前`;
  return `${Math.floor(diff / 86400)} 天前`;
};

const errorKind = (e: Error): string => e instanceof MacGatewayError ? (e as MacGatewayError).kind : '';

const formatBytes = (bytes: number): string => `${(bytes / 1e9).toFixed(1)} GB`;

const levelLabel = (level: string): string => {
  if (level === 'normal' || level === 'nominal') return '正常';
  if (level === 'warning' || level === 'fair') return '偏高';
  if (level === 'serious') return '过高';
  return '严重';
};
