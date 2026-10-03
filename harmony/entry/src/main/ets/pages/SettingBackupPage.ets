// SettingBackupPage — 本地加密备份导出 / 恢复
// 域层 sync_snapshot + entry SyncCryptoPort / WebDavTransport。
// 2026-09-27 二级页 iOS 对齐:仅换视觉骨架(CenteredTitleBar/PlainSection/
//   CardGroup r18+描边/CardRow/CardToggleRow/FooterNote),导出/导入/加密/恢复逻辑零改动。

import { promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import {
  BG, INK, INK2, INK3, INK4, LINE, SURFACE2, ACCENT,
  BODY, LABEL, META, GUTTER, MOTION_OVERLAY,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { getChatRepository, getChatKvStore } from '../di/AppContainer.ets';
import { createWebDavTransport } from '../platform_impl/WebDavTransport.ets';
import { createEntrySyncCryptoPort } from '../platform_impl/SyncCryptoPort.ets';
import {
  hasActiveConversationRuns, beginConversationRestore, markConversationsRestored,
} from '../platform_impl/ConversationRunGuard.ets';
import { clearConversationDeletedMarker } from '../platform_impl/EntryCompactStore.ets';
import { getConversationRecapService } from '../platform_impl/ConversationRecapSupport.ets';
import type { Conversation, ConversationSummary, JsonObject } from '@amber/chat-domain';
import {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive,
  createWebDavClient, webDavBackupFileName,
  buildSyncSettingsBlob, applySyncRestoreSettings,
  conversationFromSyncDto, settingsFromSyncPayload,
  type SyncConversationDto, type SyncInspectPreview, type SyncRestoreResult, type SyncRestoreSettings, type WebDavConfig,
  type WebDavRemoteFile, type WebDavClient,
} from '@amber/chat-domain';

@Entry
@Component
struct SettingBackupPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State passphrase: string = '';
  @State showPassphrase: boolean = false;
  @State usePassphrase: boolean = true;
  @State busy: boolean = false;
  @State statusMsg: string = '';
  @State preview: SyncInspectPreview | null = null;
  @State pendingArchivePath: string = '';
  // D4:按类别导出/恢复(默认 = 现状语义:导出全选;恢复仅会话,settings 默认不覆盖)
  @State exportConversations: boolean = true;
  @State exportAssistants: boolean = true;
  @State exportProviders: boolean = true;
  @State restoreConversations: boolean = true;
  @State restoreAssistants: boolean = false;
  @State restoreProviders: boolean = false;
  @State davUrl: string = '';
  @State davUser: string = '';
  @State davPass: string = '';
  @State showDavPass: boolean = false;
  @State davPath: string = 'amber_agent_backups';
  @State davSnapshots: WebDavRemoteFile[] = [];
  @State davListBusy: boolean = false;
  private pageAlive: boolean = false;

  aboutToAppear(): void {
    this.pageAlive = true;
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
  }

  private toast(msg: string): void {
    promptAction.showToast({ message: msg, duration: 1800 });
  }

  private passOrNull(): string | null {
    if (!this.usePassphrase) return null;
    if (this.passphrase.trim().length < 4) {
      this.toast('密码至少 4 位');
      return null;
    }
    return this.passphrase;
  }

  private ctx(): common.Context {
    return getContext(this);
  }

  // D4:打包(导出/上传共用):按类别组装 conversations 与 settings blob
  private async packBackupBytes(pass: string | null): Promise<Uint8Array> {
    const repo = getChatRepository();
    const conversations: Conversation[] = [];
    if (this.exportConversations) {
      const summaries: ConversationSummary[] = await repo.list();
      for (const s of summaries) {
        const c: Conversation | null = await repo.getById(s.id);
        if (c !== null) conversations.push(c);
      }
      // list/getById 首次初始化也可能进入内存降级，读取完成后再检查。
      if (AppStorage.get<boolean>('chatStorageDegraded') === true) {
        throw new Error('会话存储不可用，无法创建完整备份');
      }
    }
    const kv = getChatKvStore();
    const settingsBlob: JsonObject = await buildSyncSettingsBlob(kv, this.exportAssistants, this.exportProviders);
    const crypto = createEntrySyncCryptoPort();
    return createSyncBackup(crypto, {
      conversations,
      settings: settingsBlob,
      appVersionName: 'harmony-preview',
      appVersionCode: 1,
      deviceId: 'harmony',
      deviceLabel: 'HarmonyOS',
      passphrase: pass,
    }, Date.now());
  }

  private writeBackupFile(path: string, bytes: Uint8Array): void {
    const file = fileIo.openSync(
      path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    try {
      const buffer: ArrayBuffer = bytes.slice().buffer as ArrayBuffer;
      if (fileIo.writeSync(file.fd, buffer) !== bytes.byteLength) {
        throw new Error('备份文件写入不完整');
      }
    } finally {
      fileIo.closeSync(file.fd);
    }
  }

  private readBackupFile(path: string): Uint8Array {
    const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
    try {
      const stat = fileIo.statSync(file.fd);
      const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
      if (fileIo.readSync(file.fd, buffer) !== stat.size) {
        throw new Error('备份文件读取不完整');
      }
      return new Uint8Array(buffer);
    } finally {
      fileIo.closeSync(file.fd);
    }
  }

  async doExport(): Promise<void> {
    if (this.busy) return;
    const pass = this.passOrNull();
    if (this.usePassphrase && pass === null) return;
    if (!this.exportConversations && !this.exportAssistants && !this.exportProviders) {
      this.toast('请至少勾选一个导出类别');
      return;
    }
    this.busy = true;
    this.statusMsg = '正在打包…';
    try {
      const bytes = await this.packBackupBytes(pass);
      const fileName = `amber-backup-${new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19)}.abin`;
      const docPicker = new picker.DocumentViewPicker(this.ctx());
      const uris: string[] = await docPicker.save({ newFileNames: [fileName] });
      // UI-F25:选择器返回空 URI = 用户取消。取消不得私有落盘，也不得报成功。
      if (uris.length === 0 || uris[0].length === 0) {
        this.statusMsg = '已取消导出（未选择保存位置）';
        this.toast('已取消导出');
        return;
      }
      const target: string = uris[0];
      this.writeBackupFile(target, bytes);
      this.statusMsg = `已导出 → ${fileName}`;
      this.toast('备份已导出');
    } catch (e) {
      this.statusMsg = `导出失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.busy = false;
    }
  }

  async doUploadWebDav(): Promise<void> {
    if (this.busy) return;
    if (this.davUrl.trim().length === 0) {
      this.toast('请填写 WebDAV URL');
      return;
    }
    const pass = this.passOrNull();
    if (this.usePassphrase && pass === null) return;
    if (!this.exportConversations && !this.exportAssistants && !this.exportProviders) {
      this.toast('请至少勾选一个导出类别');
      return;
    }
    this.busy = true;
    this.statusMsg = '打包并上传…';
    try {
      const bytes = await this.packBackupBytes(pass);
      const config: WebDavConfig = {
        url: this.davUrl.trim(),
        username: this.davUser.trim(),
        password: this.davPass,
        path: this.davPath.trim().length > 0 ? this.davPath.trim() : 'amber_agent_backups',
      };
      const client = createWebDavClient(createWebDavTransport(), config);
      const name = webDavBackupFileName();
      await client.mkcol('');
      await client.put(name, bytes);
      this.statusMsg = `已上传 ${name}`;
      this.toast('上传成功');
    } catch (e) {
      this.statusMsg = `上传失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.busy = false;
    }
  }

  private davClientOrNull(): WebDavClient | null {
    if (this.davUrl.trim().length === 0) {
      this.toast('请填写 WebDAV URL');
      return null;
    }
    const config: WebDavConfig = {
      url: this.davUrl.trim(),
      username: this.davUser.trim(),
      password: this.davPass,
      path: this.davPath.trim().length > 0 ? this.davPath.trim() : 'amber_agent_backups',
    };
    return createWebDavClient(createWebDavTransport(), config);
  }

  private formatSize(bytes: number): string {
    if (bytes <= 0) return '';
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  }

  // WebDAV 快照列表(与上传同配置;.abin 过滤)
  async doListWebDav(): Promise<void> {
    if (this.davListBusy || this.busy) return;
    const client = this.davClientOrNull();
    if (client === null) return;
    this.davListBusy = true;
    this.statusMsg = '正在读取远程快照…';
    try {
      const files: WebDavRemoteFile[] = await client.list();
      const abins: WebDavRemoteFile[] = files.filter(
        (f: WebDavRemoteFile): boolean => f.name.endsWith('.abin'));
      this.davSnapshots = abins;
      this.statusMsg = abins.length > 0 ? `远程共 ${abins.length} 个快照` : '远程目录中没有 .abin 快照';
    } catch (e) {
      this.statusMsg = `读取失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.davListBusy = false;
    }
  }

  // 下载远程快照 → 落到缓存文件 → 走与本地文件相同的预览/恢复管线
  async doDownloadWebDav(item: WebDavRemoteFile): Promise<void> {
    if (this.busy) return;
    const client = this.davClientOrNull();
    if (client === null) return;
    this.busy = true;
    this.statusMsg = `正在下载 ${item.name}…`;
    try {
      const bytes: Uint8Array = await client.get(item.name);
      const path: string = `${this.ctx().cacheDir}/webdav-restore.abin`;
      this.writeBackupFile(path, bytes);
      const preview = await inspectSyncArchive(bytes, path);
      this.setPreview(preview);
      this.pendingArchivePath = path;
      this.statusMsg =
        `预览：v${preview.manifest.archiveVersion} · ${preview.manifest.deviceLabel || preview.manifest.deviceId}` +
        ` · ${preview.needsPassphrase ? '需密码' : '无密码'}`;
      this.toast('已载入恢复预览');
    } catch (e) {
      this.setPreview(null);
      this.statusMsg = `下载失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.busy = false;
    }
  }

  async doPickRestore(): Promise<void> {
    if (this.busy) return;
    try {
      const docPicker = new picker.DocumentViewPicker(this.ctx());
      const uris: string[] = await docPicker.select({
        maxSelectNumber: 1,
        fileSuffixFilters: ['abin', 'json'],
      });
      if (uris.length === 0) return;
      const bytes: Uint8Array = this.readBackupFile(uris[0]);
      const preview = await inspectSyncArchive(bytes, uris[0]);
      this.setPreview(preview);
      this.pendingArchivePath = uris[0];
      this.statusMsg =
        `预览：v${preview.manifest.archiveVersion} · ${preview.manifest.deviceLabel || preview.manifest.deviceId}` +
        ` · ${preview.needsPassphrase ? '需密码' : '无密码'}`;
    } catch (e) {
      this.setPreview(null);
      this.statusMsg = `读取失败：${e instanceof Error ? e.message : String(e)}`;
    }
  }

  async doRestore(): Promise<void> {
    if (this.busy || this.pendingArchivePath.length === 0) return;
    if (!this.restoreConversations && !this.restoreAssistants && !this.restoreProviders) {
      this.toast('请至少勾选一个恢复类别');
      return;
    }
    const needPass = this.preview !== null && this.preview.needsPassphrase;
    let pass: string | null = null;
    if (needPass) {
      pass = this.passOrNull();
      if (pass === null) return;
    }
    this.busy = true;
    this.statusMsg = '恢复中…';
    let release: (() => void) | null = null;
    try {
      const bytes: Uint8Array = this.readBackupFile(this.pendingArchivePath);
      const crypto = createEntrySyncCryptoPort();
      // restoreSyncBackup 内部已用真实序列化器整体校验结构/ID/时间/selectIndex(R03)；
      // 校验失败在此抛出，尚未写任何会话。
      const result: SyncRestoreResult = await restoreSyncBackup(crypto, bytes, pass);
      const settings: SyncRestoreSettings = settingsFromSyncPayload(result.payload);
      const kv = getChatKvStore();
      // D4-1:会话类别(勾选才恢复;未勾选跳过活跃检查/事务/标记清理)
      let convNote: string = '';
      let compactFailed: boolean = false;
      let compactErr: string = '';
      if (this.restoreConversations) {
        const dtos: SyncConversationDto[] = result.payload.conversations;
        const ids: string[] = dtos.map((d: SyncConversationDto): string => d.id);
        // 活跃生成检查:目标会话正在生成时拒绝，避免恢复与生成尾写互相覆盖
        if (hasActiveConversationRuns(ids)) {
          this.statusMsg = '恢复失败：目标会话正在生成，请先停止该会话的生成后再恢复';
          this.toast('目标会话正在生成');
          return;
        }
        // 同步占用恢复代际(覆盖预检后的新 run 竞态)；beginRun 在此期间会被拒
        release = beginConversationRestore(ids);
        const convs: Conversation[] = dtos.map(
          (d: SyncConversationDto): Conversation => conversationFromSyncDto(d));
        // 会话:单事务批量恢复(全有或全无)
        await getChatRepository().restoreConversations(convs);
        markConversationsRestored(ids);
        // R09:恢复成功 → 清除 compact 删除标记(仅压缩状态标记；绝不触碰
        // memory/managedfiles 的损坏隔离保护——恢复不携带这些 store 的数据)。
        // 会话已落库，故此处失败不得泛报「恢复失败」隐去已写。
        for (const id of ids) {
          try {
            await getConversationRecapService().invalidate(id);
          } catch (e) {
            compactFailed = true;
            compactErr = e instanceof Error ? e.message : String(e);
          }
          try {
            await clearConversationDeletedMarker(kv, id);
          } catch (e) {
            compactFailed = true;
            compactErr = e instanceof Error ? e.message : String(e);
          }
        }
        convNote = `已恢复 ${convs.length} 个会话`;
      }
      // D4-1:settings 类别(按勾选过滤后走既有覆盖/回滚管线)
      let settingsNote: string = '';
      if (this.restoreAssistants || this.restoreProviders) {
        const filtered: SyncRestoreSettings = {
          assistants: this.restoreAssistants ? settings.assistants : null,
          providers: this.restoreProviders ? settings.providers : null,
        };
        const note: string | null = await applySyncRestoreSettings(filtered, kv);
        settingsNote = note !== null ? note : '（备份不含所选 settings 类别）';
      }
      if (compactFailed) {
        this.statusMsg = `${convNote}，但回顾或压缩状态清理失败：${compactErr}${settingsNote}`;
        this.toast('会话已恢复，回顾或压缩状态清理失败');
      } else if (settingsNote.indexOf('失败') >= 0) {
        this.statusMsg = `${convNote}${settingsNote}`;
        this.toast('settings 覆盖失败');
      } else {
        this.statusMsg = `${convNote}${settingsNote}`;
        this.toast('恢复完成');
      }
      this.setPreview(null);
      this.pendingArchivePath = '';
    } catch (e) {
      this.statusMsg = `恢复失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      if (release !== null) release();
      this.busy = false;
    }
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '备份与恢复' })
      Scroll() {
        Column() {
          PlainSection({ label: '导出' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ExportCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: '恢复' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.RestoreCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          PlainSection({ label: 'WebDAV' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.WebDavCard(); } })
            .margin({ left: GUTTER, right: GUTTER })

          if (this.statusMsg.length > 0 && this.preview === null) {
            FooterNote({ text: this.statusMsg })
          }
          FooterNote({ text: '备份包含所选服务商凭证，建议使用密码加密。WebDAV 支持上传与快照下载恢复；跨端加密互通（KDF）待后续。' })
          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1).backgroundColor(BG)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  PasswordField(isWebDav: boolean): void {
    Row() {
      TextInput({
        text: isWebDav ? this.davPass : this.passphrase,
        placeholder: isWebDav ? '密码 / App Token' : '备份密码（至少 4 位）',
      })
        .type((isWebDav ? this.showDavPass : this.showPassphrase) ? InputType.Normal : InputType.Password)
        .showPasswordIcon(false)
        .height(44).layoutWeight(1)
        .fontSize(BODY).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(Color.Transparent)
        .padding({ left: 14, right: 0 })
        .onChange((value: string): void => {
          if (isWebDav) this.davPass = value;
          else this.passphrase = value;
        })
      Row() {
        Icon({
          name: (isWebDav ? this.showDavPass : this.showPassphrase) ? 'eye' : 'eye_off',
          iconSize: 20, color: INK2,
        })
      }
      .width(44).height(44)
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .accessibilityText((isWebDav ? this.showDavPass : this.showPassphrase) ? '隐藏密码' : '显示密码')
      .onClick((): void => {
        if (!this.busy) {
          if (isWebDav) this.showDavPass = !this.showDavPass;
          else this.showPassphrase = !this.showPassphrase;
        }
      })
    }
    .width('100%').height(44)
    .alignItems(VerticalAlign.Center)
    .backgroundColor(SURFACE2).borderRadius(10)
    .enabled(!this.busy)
  }

  @Builder
  ExportCard(): void {
    Column() {
      Column() {
        CardToggleRow({
          label: '使用密码加密',
          checked: this.usePassphrase,
          onToggle: (): void => {
            if (this.busy) return;
            this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
              this.usePassphrase = !this.usePassphrase;
            });
          },
        })
        if (this.usePassphrase) {
          Column() {
            this.PasswordField(false)
            Row() {
              Row().width('100%').height(1).backgroundColor(LINE)
            }
            .width('100%')
            .margin({ top: 12 })
          }
          .width('100%')
          .padding({ left: 14, right: 14, top: 2 })
          .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
            .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
        }
      }
      .width('100%')
      .clip(true)
      CardToggleRow({
        label: '会话', desc: '打包全部会话内容',
        checked: this.exportConversations,
        onToggle: (): void => { if (!this.busy) this.exportConversations = !this.exportConversations; },
      })
      CardToggleRow({
        label: '助手', desc: '打包助手配置',
        checked: this.exportAssistants,
        onToggle: (): void => { if (!this.busy) this.exportAssistants = !this.exportAssistants; },
      })
      CardToggleRow({
        label: '服务商', desc: '打包服务商配置及凭证',
        checked: this.exportProviders,
        onToggle: (): void => { if (!this.busy) this.exportProviders = !this.exportProviders; },
      })
      CardRow({
        label: '导出备份',
        desc: '所选类别打包为加密 .abin 文件',
        iconName: 'folder',
        plateStyle: 'accent',
        isLast: true,
        onRowClick: (): void => { this.doExport(); },
      })
    }
    .width('100%')
  }

  private setPreview(preview: SyncInspectPreview | null): void {
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.preview = preview;
    });
  }

  @Builder
  RestorePreview(preview: SyncInspectPreview): void {
    Column() {
      Text(this.statusMsg).fontSize(META).fontColor(INK3).lineHeight(18)
      Text(`设备：${preview.manifest.deviceLabel.length > 0 ? preview.manifest.deviceLabel : preview.manifest.deviceId}`)
        .fontSize(LABEL).fontColor(INK2).margin({ top: 6 })
      Text(`创建：${new Date(preview.manifest.createdAt).toLocaleString()}`)
        .fontSize(LABEL).fontColor(INK3).margin({ top: 4 })
      Text('恢复').fontSize(BODY).fontWeight(FontWeight.Medium)
        .fontColor(this.accentColorStore)
        .height(44).padding({ left: 12, right: 12 })
        .margin({ top: 8 })
        .onClick((): void => { this.doRestore(); })
      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .margin({ top: 12 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 14, right: 14, top: 2 })
    .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  RestoreCard(): void {
    Column() {
      CardRow({
        label: '选择备份文件',
        desc: '预览 manifest 后再恢复',
        iconName: 'folder',
        plateStyle: 'accent',
        onRowClick: (): void => { this.doPickRestore(); },
      })
      if (this.preview !== null) {
        this.RestorePreview(this.preview)
      }
      CardToggleRow({
        label: '会话', desc: '恢复全部会话内容',
        checked: this.restoreConversations,
        onToggle: (): void => { if (!this.busy) this.restoreConversations = !this.restoreConversations; },
      })
      CardToggleRow({
        label: '助手', desc: '以备份内容覆盖助手配置',
        checked: this.restoreAssistants,
        onToggle: (): void => { if (!this.busy) this.restoreAssistants = !this.restoreAssistants; },
      })
      CardToggleRow({
        label: '服务商', desc: '以备份内容覆盖服务商配置',
        checked: this.restoreProviders,
        isLast: true,
        onToggle: (): void => { if (!this.busy) this.restoreProviders = !this.restoreProviders; },
      })
    }
    .width('100%')
  }

  @Builder
  WebDavCard(): void {
    Column() {
      Column() {
        TextInput({ text: this.davUrl, placeholder: 'https://dav.example.com/dav' })
          .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 14, right: 14 })
          .onChange((v: string): void => { this.davUrl = v; })
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14, top: 12 })
      Column() {
        TextInput({ text: this.davUser, placeholder: '用户名' })
          .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 14, right: 14 })
          .onChange((v: string): void => { this.davUser = v; })
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
      Column() {
        this.PasswordField(true)
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
      Column() {
        TextInput({ text: this.davPath, placeholder: '远程目录' })
          .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 14, right: 14 })
          .onChange((v: string): void => { this.davPath = v; })
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .margin({ top: 12 })
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
      CardRow({
        label: '打包并上传到 WebDAV',
        desc: '与本地导出同一 .abin，PUT 到远程目录',
        iconName: 'folder',
        plateStyle: 'accent',
        isLast: false,
        onRowClick: (): void => { this.doUploadWebDav(); },
      })
      CardRow({
        label: this.davListBusy ? '正在读取…' : '列出远程快照',
        desc: '读取远程目录中的 .abin 备份',
        iconName: 'folder',
        plateStyle: 'accent',
        isLast: this.davSnapshots.length === 0,
        onRowClick: (): void => { this.doListWebDav(); },
      })
      ForEach(this.davSnapshots, (item: WebDavRemoteFile, idx: number): void => {
        CardRow({
          label: item.name,
          desc: this.formatSize(item.size).length > 0 ? this.formatSize(item.size) : '点击下载并预览恢复',
          iconName: 'folder',
          isLast: idx === this.davSnapshots.length - 1,
          onRowClick: (): void => { this.doDownloadWebDav(item); },
        })
      }, (item: WebDavRemoteFile): string => item.name)
    }
    .width('100%')
  }
}
