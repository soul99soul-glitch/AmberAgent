import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { TerminalLogPort } from '@amber/chat-domain';
import { KeyedAsyncMutex } from './AtomicJsonFile.ets';

const validLogId = (id: string): boolean => /^[A-Za-z0-9_-]{1,128}$/.test(id);

class EntryTerminalLogPort implements TerminalLogPort {
  private readonly mutex: KeyedAsyncMutex = new KeyedAsyncMutex();

  private directory(): string {
    const context: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (context === undefined) throw new Error('terminal_context_unavailable');
    return `${context.filesDir}/amberagent/terminal/logs`;
  }

  async create(id: string): Promise<string> {
    if (!validLogId(id)) throw new Error('terminal_log_invalid_id');
    const directory: string = this.directory();
    const path: string = `${directory}/${id}.log`;
    return this.mutex.withLock<string>(path, async (): Promise<string> => {
      if (!await fileIo.access(directory)) await fileIo.mkdir(directory, true);
      if (await fileIo.access(path)) throw new Error('terminal_log_already_exists');
      const file: fileIo.File = await fileIo.open(path,
        fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.NOFOLLOW);
      await fileIo.close(file);
      return path;
    });
  }

  async append(path: string, text: string): Promise<void> {
    const prefix: string = `${this.directory()}/`;
    if (!path.startsWith(prefix) || !path.endsWith('.log') ||
      !validLogId(path.slice(prefix.length, -4))) throw new Error('terminal_log_invalid_path');
    await this.mutex.withLock<void>(path, async (): Promise<void> => {
      const file: fileIo.File = await fileIo.open(path,
        fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.APPEND | fileIo.OpenMode.NOFOLLOW);
      try {
        const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
        let offset: number = 0;
        while (offset < bytes.length) {
          const remaining: ArrayBuffer = (bytes.buffer as ArrayBuffer)
            .slice(bytes.byteOffset + offset, bytes.byteOffset + bytes.byteLength);
          const written: number = await fileIo.write(file.fd, remaining);
          if (written <= 0 || written > remaining.byteLength) throw new Error('terminal_log_short_write');
          offset += written;
        }
      } finally {
        await fileIo.close(file);
      }
    });
  }
}

export const createEntryTerminalLogPort = (): TerminalLogPort => new EntryTerminalLogPort();
