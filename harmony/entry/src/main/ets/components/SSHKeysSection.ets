import { pasteboard } from '@kit.BasicServicesKit';
import type { SSHKey, SSHKeyStore, SSHProfileStore } from '@amber/chat-domain';
import { getSSHProfileStore } from '../di/AppContainer.ets';
import { SSHUIColors } from './SSHUIColors.ets';
import { sshPageError } from './SSHPageErrors.ets';
import { PlainSection, CardGroup, FooterNote } from './PageTemplate.ets';
import { ACCENT, BODY, META, PAD_CARD } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';

@Component
export struct SSHKeysSection {
  @Prop colors: SSHUIColors = new SSHUIColors();
  @Prop accent: string = ACCENT;
  @StorageProp('appBackgrounded') backgrounded: boolean = false;
  @State keys: SSHKey[] = [];
  @State name: string = '';
  @State busy: boolean = false;
  @State message: string = '';
  private profiles: SSHProfileStore | null = null;
  private store: SSHKeyStore | null = null;
  private unsubscribe: (() => void) | null = null;
  private alive: boolean = false;

  aboutToAppear(): void { this.alive = true; this.load(); }
  aboutToDisappear(): void { this.alive = false; this.unsubscribe?.(); this.unsubscribe = null; }
  private async load(): Promise<void> {
    try {
      const profiles: SSHProfileStore = await getSSHProfileStore();
      if (!this.alive) return;
      this.profiles = profiles;
      this.store = profiles.keyStore() ?? null;
      if (this.store !== null) this.unsubscribe = this.store.subscribe((keys: SSHKey[]): void => {
        if (this.alive) this.keys = keys;
      });
    } catch (_error) { if (this.alive) this.message = '本机密钥暂不可用'; }
  }
  private async generate(): Promise<void> {
    if (this.store === null || this.busy || this.backgrounded) return;
    this.busy = true; this.message = '';
    try {
      await this.store.generateKey(this.name);
      if (this.alive) { this.name = ''; this.message = '密钥已生成。复制公钥，添加到服务器后选择此密钥创建配置。'; }
    } catch (error) {
      if (this.alive) this.message = error instanceof Error ? sshPageError(error) : '生成失败';
    } finally { if (this.alive) this.busy = false; }
  }
  private copyPublicKey(key: SSHKey): void {
    try {
      pasteboard.getSystemPasteboard().setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, key.publicKey));
      this.message = '公钥已复制';
    } catch (_error) { this.message = '复制失败，请长按公钥复制'; }
  }
  private remove(key: SSHKey): void {
    AlertDialog.show({ title: '删除 SSH 密钥', message: `删除「${key.name}」？被配置引用的密钥无法删除。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '删除', fontColor: this.colors.error, action: (): void => {
        if (this.store === null || this.profiles === null || this.backgrounded) return;
        this.store.deleteKey(key.id)
          .catch((error: Error): void => { if (this.alive) this.message = sshPageError(error); });
      } },
    });
  }
  @Builder
  private Rows(): void {
    Column({ space: 10 }) {
      TextInput({ text: this.name, placeholder: '密钥名称（1–64 字符）' }).maxLength(64)
        .fontColor(this.colors.ink).placeholderColor(this.colors.muted).backgroundColor(this.colors.inset)
        .enabled(!this.busy).onChange((value: string): void => { this.name = value; })
      Button(this.busy ? '生成中…' : '生成密钥').constraintSize({ minHeight: 44 }).width('100%')
        .backgroundColor(this.accent).fontColor(this.colors.accentInk)
        .enabled(!this.busy && !this.backgrounded && this.store !== null && this.name.trim().length > 0)
        .onClick((): void => { this.generate(); })
      ForEach(this.keys, (key: SSHKey): void => {
        Column({ space: 6 }) {
          Text(`${key.name} · ${key.algorithm}`).fontSize(BODY).fontColor(this.colors.ink).width('100%')
          Text(key.fingerprintSHA256).fontSize(META).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
            .width('100%').wordBreak(WordBreak.BREAK_ALL).copyOption(CopyOptions.LocalDevice)
          Text(key.publicKey).fontSize(META).fontFamily(FONT_MONO).fontColor(this.colors.secondary)
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').copyOption(CopyOptions.LocalDevice)
          Row() {
            Button('复制公钥').constraintSize({ minHeight: 44 }).fontColor(this.accent).backgroundColor(Color.Transparent)
              .onClick((): void => { this.copyPublicKey(key); })
            Blank()
            Button('删除').constraintSize({ minHeight: 44 }).fontColor(this.colors.error).backgroundColor(Color.Transparent)
              .onClick((): void => { this.remove(key); })
          }.width('100%')
        }.width('100%').padding({ top: 10 }).border({ width: { top: 0.5 }, color: this.colors.line })
      }, (key: SSHKey): string => key.id)
      if (this.message.length > 0) { Text(this.message).fontSize(META).fontColor(this.colors.secondary).width('100%') }
    }.width('100%').padding(PAD_CARD)
  }
  build(): void {
    Column() {
      PlainSection({ label: '本机密钥' })
      CardGroup({ radius: 18, stroke: true, content: (): void => { this.Rows(); } }).margin({ left: 16, right: 16 })
      FooterNote({ text: '默认生成 Ed25519 密钥。私钥加密保存在本设备，只能复制公钥。' })
    }.width('100%')
  }
}
