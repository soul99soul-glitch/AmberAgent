// SettingBackupPage — 本地加密备份导出 / 恢复
// 域层 sync_snapshot + entry SyncCryptoPort；无云上传（下一圈）。

import { promptAction } from '@kit.ArkUI';
import { fileIo, picker } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import {
  BG, INK, INK2, INK3, INK4, LINE, SURFACE, SURFACE2, ACCENT, ERROR,
  BODY, LABEL, META, TITLE,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, NavRow, PageSpacer, ToggleRow } from '../components/PageTemplate.ets';
import { getChatRepository, getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { createEntrySyncCryptoPort } from '../platform_impl/SyncCryptoPort.ets';
import type { Conversation, ConversationSummary } from '@amber/chat-domain';
import {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive,
  createWebDavClient, webDavBackupFileName,
  loadAssistants, saveAssistants, loadProviders, saveProviders,
  type SyncInspectPreview, type SyncRestoreResult, type WebDavConfig,
} from '@amber/chat-domain';

@Entry
@Component
struct SettingBackupPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State passphrase: string = '';
  @State usePassphrase: boolean = true;
  @State busy: boolean = false;
  @State statusMsg: string = '';
  @State preview: SyncInspectPreview | null = null;
  @State pendingArchivePath: string = '';
  @State restoreSettings: boolean = false;
  @State davUrl: string = '';
  @State davUser: string = '';
  @State davPass: string = '';
  @State davPath: string = 'amber_agent_backups';
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

  async doExport(): Promise<void> {
    if (this.busy) return;
    const pass = this.passOrNull();
    if (this.usePassphrase && pass === null) return;
    this.busy = true;
    this.statusMsg = '正在打包…';
    try {
      const repo = getChatRepository();
      const summaries: ConversationSummary[] = await repo.list();
      const conversations: Conversation[] = [];
      for (const s of summaries) {
        const c: Conversation | null = await repo.getById(s.id);
        if (c !== null) conversations.push(c);
      }
      const kv = getChatKvStore();
      const assistants = await loadAssistants(kv);
      const providers = await loadProviders(kv);
      const settingsBlob: Record<string, object> = {};
      if (assistants !== null) settingsBlob['assistants'] = assistants as unknown as object;
      if (providers !== null) settingsBlob['providers'] = providers as unknown as object;
      const crypto = createEntrySyncCryptoPort();
      const bytes = await createSyncBackup(crypto, {
        conversations,
        settings: settingsBlob as never,
        appVersionName: 'harmony-preview',
        appVersionCode: 1,
        deviceId: 'harmony',
        deviceLabel: 'HarmonyOS',
        passphrase: pass,
      }, Date.now());
      const fileName = `amber-backup-${new Date().toISOString().replace(/[:.]/g, '-').substring(0, 19)}.abin`;
      const docPicker = new picker.DocumentViewPicker(this.ctx());
      const uris: string[] = await docPicker.save({ newFileNames: [fileName] });
      let target = '';
      if (uris.length > 0) {
        target = uris[0];
      } else {
        const dir = `${this.ctx().filesDir}/backups`;
        try { fileIo.mkdirSync(dir, true); } catch { /* exists */ }
        target = `${dir}/${fileName}`;
      }
      const file = fileIo.openSync(
        target, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, bytes.buffer as ArrayBuffer);
      fileIo.closeSync(file.fd);
      this.statusMsg = `已导出 ${conversations.length} 个会话 → ${fileName}`;
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
    this.busy = true;
    this.statusMsg = '打包并上传…';
    try {
      const repo = getChatRepository();
      const summaries: ConversationSummary[] = await repo.list();
      const conversations: Conversation[] = [];
      for (const s of summaries) {
        const c: Conversation | null = await repo.getById(s.id);
        if (c !== null) conversations.push(c);
      }
      const kv = getChatKvStore();
      const assistants = await loadAssistants(kv);
      const providers = await loadProviders(kv);
      const settingsBlob: Record<string, object> = {};
      if (assistants !== null) settingsBlob['assistants'] = assistants as unknown as object;
      if (providers !== null) settingsBlob['providers'] = providers as unknown as object;
      const crypto = createEntrySyncCryptoPort();
      const bytes = await createSyncBackup(crypto, {
        conversations,
        settings: settingsBlob as never,
        appVersionName: 'harmony-preview',
        appVersionCode: 1,
        deviceId: 'harmony',
        deviceLabel: 'HarmonyOS',
        passphrase: pass,
      }, Date.now());
      const config: WebDavConfig = {
        url: this.davUrl.trim(),
        username: this.davUser.trim(),
        password: this.davPass,
        path: this.davPath.trim().length > 0 ? this.davPath.trim() : 'amber_agent_backups',
      };
      const client = createWebDavClient(getAppContainer().httpClient, config);
      const name = webDavBackupFileName();
      await client.put(name, bytes);
      this.statusMsg = `已上传 ${name}（${conversations.length} 会话）`;
      this.toast('上传成功');
    } catch (e) {
      this.statusMsg = `上传失败：${e instanceof Error ? e.message : String(e)}`;
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
      const file = fileIo.openSync(uris[0], fileIo.OpenMode.READ_ONLY);
      const stat = fileIo.statSync(file.fd);
      const buf = new ArrayBuffer(stat.size);
      fileIo.readSync(file.fd, buf);
      fileIo.closeSync(file.fd);
      const preview = await inspectSyncArchive(new Uint8Array(buf), uris[0]);
      this.preview = preview;
      this.pendingArchivePath = uris[0];
      this.statusMsg =
        `预览：v${preview.manifest.archiveVersion} · ${preview.manifest.deviceLabel || preview.manifest.deviceId}` +
        ` · ${preview.needsPassphrase ? '需密码' : '无密码'}`;
    } catch (e) {
      this.preview = null;
      this.statusMsg = `读取失败：${e instanceof Error ? e.message : String(e)}`;
    }
  }

  async doRestore(): Promise<void> {
    if (this.busy || this.pendingArchivePath.length === 0) return;
    const needPass = this.preview !== null && this.preview.needsPassphrase;
    let pass: string | null = null;
    if (needPass) {
      pass = this.passOrNull();
      if (pass === null) return;
    }
    this.busy = true;
    this.statusMsg = '恢复中…';
    try {
      const file = fileIo.openSync(this.pendingArchivePath, fileIo.OpenMode.READ_ONLY);
      const stat = fileIo.statSync(file.fd);
      const buf = new ArrayBuffer(stat.size);
      fileIo.readSync(file.fd, buf);
      fileIo.closeSync(file.fd);
      const crypto = createEntrySyncCryptoPort();
      const result: SyncRestoreResult = await restoreSyncBackup(crypto, new Uint8Array(buf), pass);
      const repo = getChatRepository();
      let count = 0;
      for (const item of result.payload.conversations) {
        const conv: Conversation = {
          id: item.id,
          assistantId: item.assistantId,
          title: item.title,
          createAt: item.createAt,
          updateAt: item.updateAt,
          chatSuggestions: [],
          isPinned: false,
          autoApproveToolCalls: false,
          messageNodes: [],
        };
        await repo.save(conv);
        count += 1;
      }
      let settingsNote = '（未勾选，settings 未覆盖）';
      if (this.restoreSettings && result.payload.settings !== undefined) {
        const blob = result.payload.settings as Record<string, unknown>;
        const kv = getChatKvStore();
        if (blob['assistants'] !== undefined && Array.isArray(blob['assistants'])) {
          await saveAssistants(kv, blob['assistants'] as never);
        }
        if (blob['providers'] !== undefined && Array.isArray(blob['providers'])) {
          await saveProviders(kv, blob['providers'] as never);
        }
        settingsNote = '（已覆盖 settings）';
      }
      this.statusMsg = `已恢复 ${count} 个会话${settingsNote}`;
      this.toast('恢复完成');
      this.preview = null;
      this.pendingArchivePath = '';
    } catch (e) {
      this.statusMsg = `恢复失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.busy = false;
    }
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '备份与恢复' })
      Scroll() {
        Column() {
          SectionHeader({ label: '导出' })
          ToggleRow({
            label: '使用密码加密',
            checked: this.usePassphrase,
            isLast: false,
            onToggle: (): void => { this.usePassphrase = !this.usePassphrase; },
          })
          if (this.usePassphrase) {
            TextInput({ text: this.passphrase, placeholder: '备份密码（至少 4 位）' })
              .type(InputType.Password)
              .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(10)
              .padding({ left: 14, right: 14 })
              .margin({ left: 16, right: 16, bottom: 12 })
              .onChange((v: string): void => { this.passphrase = v; })
          }
          NavRow({
            label: '导出全部会话',
            desc: '打包为加密 .abin 文件',
            iconName: 'folder',
            onRowClick: (): void => { this.doExport(); },
          })

          SectionHeader({ label: '恢复' })
          NavRow({
            label: '选择备份文件',
            desc: '预览 manifest 后再恢复',
            iconName: 'folder',
            onRowClick: (): void => { this.doPickRestore(); },
          })
          if (this.preview !== null) {
            Column() {
              Text(this.statusMsg).fontSize(META).fontColor(INK3).lineHeight(18)
              Text(`设备：${this.preview.manifest.deviceLabel.length > 0 ? this.preview.manifest.deviceLabel : this.preview.manifest.deviceId}`)
                .fontSize(LABEL).fontColor(INK2).margin({ top: 6 })
              Text(`创建：${new Date(this.preview.manifest.createdAt).toLocaleString()}`)
                .fontSize(LABEL).fontColor(INK3).margin({ top: 4 })
              Text('恢复').fontSize(BODY).fontColor(ACCENT)
                .height(44).padding({ left: 12, right: 12 })
                .margin({ top: 8 })
                .onClick((): void => { this.doRestore(); })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 8, bottom: 12 })
            .backgroundColor(SURFACE).borderRadius(10)
            .margin({ left: 16, right: 16, top: 8 })
          }

          ToggleRow({
            label: '恢复时覆盖设置',
            desc: '助手与提供商将被备份内容替换',
            checked: this.restoreSettings,
            isLast: true,
            onToggle: (): void => { this.restoreSettings = !this.restoreSettings; },
          })

          SectionHeader({ label: 'WebDAV 上传' })
          TextInput({ text: this.davUrl, placeholder: 'https://dav.example.com/dav' })
            .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
            .backgroundColor(SURFACE2).borderRadius(10)
            .padding({ left: 14, right: 14 })
            .margin({ left: 16, right: 16, bottom: 8 })
            .onChange((v: string): void => { this.davUrl = v; })
          TextInput({ text: this.davUser, placeholder: '用户名' })
            .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
            .backgroundColor(SURFACE2).borderRadius(10)
            .padding({ left: 14, right: 14 })
            .margin({ left: 16, right: 16, bottom: 8 })
            .onChange((v: string): void => { this.davUser = v; })
          TextInput({ text: this.davPass, placeholder: '密码 / App Token' })
            .type(InputType.Password)
            .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
            .backgroundColor(SURFACE2).borderRadius(10)
            .padding({ left: 14, right: 14 })
            .margin({ left: 16, right: 16, bottom: 8 })
            .onChange((v: string): void => { this.davPass = v; })
          TextInput({ text: this.davPath, placeholder: '远程目录' })
            .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
            .backgroundColor(SURFACE2).borderRadius(10)
            .padding({ left: 14, right: 14 })
            .margin({ left: 16, right: 16, bottom: 8 })
            .onChange((v: string): void => { this.davPath = v; })
          NavRow({
            label: '打包并上传到 WebDAV',
            desc: '与本地导出同一 .abin，PUT 到远程目录',
            iconName: 'folder',
            onRowClick: (): void => { this.doUploadWebDav(); },
          })

          if (this.statusMsg.length > 0 && this.preview === null) {
            Text(this.statusMsg).fontSize(META).fontColor(INK3).lineHeight(18)
              .width('100%').padding({ left: 20, right: 20, top: 12 })
          }

          Text('说明：当前仅本地导出/恢复；云同步与跨端 KDF 互通待后续。')
            .fontSize(11).fontColor(INK4).lineHeight(16)
            .width('100%').padding({ left: 20, right: 20, top: 20 })
          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
