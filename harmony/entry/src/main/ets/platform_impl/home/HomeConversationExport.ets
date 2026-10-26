import { promptAction } from '@kit.ArkUI';
import { picker, fileIo } from '@kit.CoreFileKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { Conversation, ConversationSummary, ConversationRepository } from '@amber/chat-domain';
import { exportConversationMarkdown, exportFileNameFor } from '@amber/chat-domain';

export class HomeConversationExport {
  private readonly repository: ConversationRepository;
  constructor(repository: ConversationRepository) { this.repository = repository; }
  copy(item: ConversationSummary): void {
    this.repository.getById(item.id).then((conv: Conversation | null): void => {
      if (conv === null) {
        promptAction.showToast({ message: '会话不存在', duration: 1600 });
        return;
      }
      const md: string = exportConversationMarkdown(conv, { fallbackTitle: '未命名对话' });
      const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md);
      const pb: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      pb.setData(data).then((): void => {
        promptAction.showToast({ message: '已复制 Markdown 到剪贴板', duration: 1800 });
      }).catch((e: Error): void => {
        promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
    });
  }

  // 会话导出 Markdown 文件(DocumentViewPicker;失败回落 filesDir/exports)

  saveFile(item: ConversationSummary): void {
    this.repository.getById(item.id).then((conv: Conversation | null): void => {
      if (conv === null) {
        promptAction.showToast({ message: '会话不存在', duration: 1600 });
        return;
      }
      const md: string = exportConversationMarkdown(conv, { fallbackTitle: '未命名对话' });
      const fileName: string = exportFileNameFor(conv, 'md');
      const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
      const docPicker = new picker.DocumentViewPicker(ctx);
      docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
        if (uris.length > 0) {
          this.writeExportFile(uris[0], md, fileName);
        } else {
          this.exportToAppDir(md, fileName);
        }
      }).catch((): void => {
        this.exportToAppDir(md, fileName);
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
    });
  }

  private writeExportFile(uri: string, content: string, fileName: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已导出 ${fileName}`, duration: 2000 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }

  private exportToAppDir(content: string, fileName: string): void {
    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
    const dir: string = `${ctx.filesDir}/exports`;
    try {
      fileIo.mkdirSync(dir, true);
    } catch {
      // 已存在
    }
    const path: string = `${dir}/${fileName}`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已保存到应用目录 ${fileName}`, duration: 2200 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }
}
