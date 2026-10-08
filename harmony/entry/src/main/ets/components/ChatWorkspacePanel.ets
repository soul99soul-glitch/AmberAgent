// Chat Workspace 的小型宿主：真实文件列表、归档读回、预览与来源回调。
import type { Conversation, ChatMessageArchiveSource } from '@amber/chat-domain';
import { fileUri } from '@kit.CoreFileKit';
import { archiveChatMessage, readArchivedMessage } from '../platform_impl/ChatMessageArchive.ets';
import { workspaceRootAbs } from '../platform_impl/WorkspaceStore.ets';
import { WorkspaceFileVM, WorkspaceFileInfo, extOf } from './WorkspaceFileVM.ets';
import { WorkspaceFileSheet } from './WorkspaceFileSheet.ets';
import { WorkspaceFilePreview } from './WorkspaceFilePreview.ets';
import { ACCENT, BODY, ERROR, INK, INK3, OVERLAY, SURFACE } from '../design/tokens.ets';

@Component
export struct ChatWorkspacePanel {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @Prop conversation: Conversation;
  @Prop initialMessageId: string = '';
  onDismiss: () => void = (): void => {};
  onOpenSource: (source: ChatMessageArchiveSource) => void = (_source: ChatMessageArchiveSource): void => {};
  @State files: WorkspaceFileInfo[] = [];
  @State previewFile: WorkspaceFileInfo | null = null;
  @State previewText: string = '';
  @State previewImageUri: string = '';
  @State loading: boolean = true;
  @State errorMessage: string = '';
  private alive: boolean = false;
  private archivedFile: WorkspaceFileInfo | null = null;
  private vm: WorkspaceFileVM = new WorkspaceFileVM();

  aboutToAppear(): void {
    this.alive = true;
    this.initialize();
  }

  aboutToDisappear(): void { this.alive = false; }

  private showError(error: Error): void {
    if (this.alive) this.errorMessage = error.message;
  }

  private async initialize(): Promise<void> {
    this.loading = true;
    this.errorMessage = '';
    try {
      if (this.initialMessageId.length > 0) {
        if (this.archivedFile === null) {
          this.archivedFile = await archiveChatMessage(this.conversation, this.initialMessageId);
        }
        if (!this.alive) return;
        await this.readFile(this.archivedFile);
      } else {
        const files: WorkspaceFileInfo[] = await this.vm.loadFiles();
        if (this.alive) this.files = files;
      }
    } catch (error) {
      this.showError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private async refreshFiles(): Promise<void> {
    this.loading = true;
    this.errorMessage = '';
    try {
      const files: WorkspaceFileInfo[] = await this.vm.loadFiles();
      if (this.alive) this.files = files;
    } catch (error) {
      this.showError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private closePreview(): void {
    this.previewFile = null;
    if (this.initialMessageId.length > 0 && this.files.length === 0) this.refreshFiles();
  }

  private async readFile(file: WorkspaceFileInfo): Promise<void> {
    let text: string = '';
    let imageUri: string = '';
    if (file.mimeType.startsWith('image/')) {
      imageUri = fileUri.getUriFromPath(`${workspaceRootAbs()}/${file.path}`);
    } else if (['md', 'markdown', 'txt', 'json', 'csv', 'html', 'htm', 'xml', 'yml', 'yaml', 'log'].includes(extOf(file.name))) {
      text = await readArchivedMessage(file);
    }
    if (!this.alive) return;
    this.previewText = text;
    this.previewImageUri = imageUri;
    this.previewFile = file;
  }

  private async openFile(file: WorkspaceFileInfo): Promise<void> {
    if (this.loading) return;
    this.loading = true;
    try {
      await this.readFile(file);
    } catch (error) {
      this.showError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private async deleteFile(file: WorkspaceFileInfo): Promise<void> {
    if (this.loading) return;
    this.loading = true;
    try {
      await this.vm.deleteFile(file.path);
      const files: WorkspaceFileInfo[] = await this.vm.loadFiles();
      if (this.alive) this.files = files;
    } catch (error) {
      this.showError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      if (this.alive) this.loading = false;
    }
  }

  private openSource(source: ChatMessageArchiveSource): void {
    this.onDismiss();
    this.onOpenSource(source);
  }

  build(): void {
    if (this.themeEpoch >= 0) {
      Stack() {
        WorkspaceFileSheet({ files: this.files, show: true, showUploadPlaceholder: false,
          onDismiss: (): void => { this.onDismiss(); },
          onOpenFile: (file: WorkspaceFileInfo): void => { this.openFile(file); },
          onDeleteFile: (file: WorkspaceFileInfo): void => { this.deleteFile(file); } })
          .enabled(!this.loading && this.errorMessage.length === 0)
        if (this.previewFile !== null) {
          WorkspaceFilePreview({ file: this.previewFile, textContent: this.previewText, imageUri: this.previewImageUri,
            onDismiss: (): void => { this.closePreview(); },
            onOpenSource: (source: ChatMessageArchiveSource): void => { this.openSource(source); } })
            .padding({ top: this.statusBarHeight, bottom: this.navBarHeight })
            .backgroundColor(SURFACE)
            .enabled(!this.loading && this.errorMessage.length === 0)
        }
        if (this.loading || this.errorMessage.length > 0) {
          Column({ space: 16 }) {
            Text(this.loading ? '正在读取 Workspace…' : this.errorMessage)
              .fontSize(BODY).fontColor(this.loading ? INK : ERROR).width('100%')
            if (!this.loading) {
              Text('重新读取文件列表').fontSize(BODY).fontColor(ACCENT)
                .onClick((): void => { this.previewFile = null; this.refreshFiles(); })
            }
            Text('关闭').fontSize(BODY).fontColor(INK3)
              .onClick((): void => { this.onDismiss(); })
          }.width('85%').padding(20).backgroundColor(SURFACE).borderRadius(16)
        }
      }.width('100%').height('100%').backgroundColor(OVERLAY).alignContent(Alignment.Center)
    }
  }
}
