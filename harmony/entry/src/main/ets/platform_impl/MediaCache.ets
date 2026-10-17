// MediaCache — 远程图片/视频的持久文件缓存。
//
// 路径: filesDir/amberagent/media-cache/<sha256>.<ext>
// - 同 URL 并发只下载一次(inflight 合并);
// - 命中直接返回 file:// 路径,App 重启后仍可复用;
// - 只缓存 http(s);data/file/relative 原样返回;
// - 单文件与总容量有上限,按 mtime LRU 清理,避免无限增长;
// - 下载失败返回 null,展示层回退远程 URL。

import { http } from '@kit.NetworkKit';
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Media.Cache';
const CACHE_DIR: string = 'amberagent/media-cache';
const IMAGE_MAX_BYTES: number = 25 * 1024 * 1024;
const VIDEO_MAX_BYTES: number = 120 * 1024 * 1024;
const TOTAL_MAX_BYTES: number = 256 * 1024 * 1024;
const MAX_AGE_MS: number = 30 * 24 * 60 * 60 * 1000;
const PRUNE_INTERVAL_MS: number = 10 * 60 * 1000;
const HASH_PATTERN: RegExp = /^([a-f0-9]{64})(?:\.[a-z0-9]+)?$/i;

export type MediaKind = 'image' | 'video';

interface MediaEntry {
  hash: string;
  path: string;
  size: number;
  mtime: number;
}

const isRemote = (url: string): boolean => /^https?:\/\//i.test(url);
const isHttpOk = (status: number): boolean => status >= 200 && status < 300;

const extensionFor = (mime: string, url: string, kind: MediaKind): string => {
  const normalized: string = mime.toLowerCase().split(';')[0].trim();
  if (normalized === 'image/jpeg' || normalized === 'image/jpg') return 'jpg';
  if (normalized === 'image/png') return 'png';
  if (normalized === 'image/webp') return 'webp';
  if (normalized === 'image/gif') return 'gif';
  if (normalized === 'image/avif') return 'avif';
  if (normalized === 'video/mp4') return 'mp4';
  if (normalized === 'video/webm') return 'webm';
  if (normalized === 'video/quicktime') return 'mov';
  const path: string = url.split('?')[0].split('#')[0];
  const match: RegExpMatchArray | null = path.match(/\.([a-z0-9]{2,5})$/i);
  if (match !== null) return match[1].toLowerCase();
  return kind === 'video' ? 'mp4' : 'jpg';
};

class MediaCache {
  private root: string | null = null;
  private index: Map<string, string> | null = null;
  private indexTask: Promise<Map<string, string>> | null = null;
  private inflight: Map<string, Promise<string | null>> = new Map<string, Promise<string | null>>();
  private lastPruneAt: number = 0;

  private rootPath(): string {
    if (this.root !== null) return this.root;
    const ctx: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (ctx === undefined) throw new Error('abilityContext unavailable for media cache');
    this.root = `${ctx.filesDir}/${CACHE_DIR}`;
    return this.root;
  }

  private async ensureRoot(): Promise<void> {
    const root: string = this.rootPath();
    try { await fileIo.mkdir(root, true); } catch { /* already exists */ }
  }

  private async ensureIndex(): Promise<Map<string, string>> {
    if (this.index !== null) return this.index;
    if (this.indexTask === null) this.indexTask = this.buildIndex();
    const task: Promise<Map<string, string>> = this.indexTask;
    try {
      return await task;
    } finally {
      if (this.indexTask === task) this.indexTask = null;
    }
  }

  private async buildIndex(): Promise<Map<string, string>> {
    await this.ensureRoot();
    const map: Map<string, string> = new Map<string, string>();
    const names: string[] = await fileIo.listFile(this.rootPath());
    for (const name of names) {
      const match: RegExpMatchArray | null = name.match(HASH_PATTERN);
      if (match === null) continue;
      const hash: string = match[1].toLowerCase();
      const path: string = `${this.rootPath()}/${name}`;
      const existing: string | undefined = map.get(hash);
      if (existing === undefined) {
        map.set(hash, path);
        continue;
      }
      // 同一 URL 的 Content-Type 变过 → 旧扩展名文件不再被索引；
      // 只保留较新的一个，避免重复文件永远不计入容量。
      try {
        const oldStat: fileIo.Stat = await fileIo.stat(existing);
        const newStat: fileIo.Stat = await fileIo.stat(path);
        if (newStat.mtime >= oldStat.mtime) {
          map.set(hash, path);
          await fileIo.unlink(existing);
        } else {
          await fileIo.unlink(path);
        }
      } catch {
        map.set(hash, path);
      }
    }
    this.index = map;
    return map;
  }

  private async exists(path: string): Promise<boolean> {
    try { return await fileIo.access(path); } catch { return false; }
  }

  private async touch(path: string): Promise<void> {
    try { fileIo.utimes(path, Date.now()); } catch { /* best effort */ }
  }

  private async cachedPath(hash: string): Promise<string | null> {
    const index: Map<string, string> = await this.ensureIndex();
    const path: string | undefined = index.get(hash);
    if (path === undefined) return null;
    if (!(await this.exists(path))) {
      index.delete(hash);
      return null;
    }
    let stat: fileIo.Stat;
    try {
      stat = await fileIo.stat(path);
    } catch {
      index.delete(hash);
      return null;
    }
    if (stat.size <= 0) {
      index.delete(hash);
      return null;
    }
    await this.touch(path);
    return `file://${path}`;
  }

  private async finalizeTemp(hash: string, temp: string, mime: string, url: string, kind: MediaKind): Promise<string> {
    const path: string = `${this.rootPath()}/${hash}.${extensionFor(mime, url, kind)}`;
    const index: Map<string, string> = await this.ensureIndex();
    const previous: string | undefined = index.get(hash);
    if (previous !== undefined && previous !== path) {
      try { if (await this.exists(previous)) await fileIo.unlink(previous); } catch { /* stale */ }
    }
    try {
      await fileIo.rename(temp, path);
    } catch (error) {
      if (await this.exists(temp)) await fileIo.unlink(temp);
      if (!(await this.exists(path))) throw error instanceof Error ? error : new Error(String(error));
    }
    index.set(hash, path);
    return `file://${path}`;
  }

  private async writeTemp(hash: string, mime: string, url: string, kind: MediaKind, data: ArrayBuffer): Promise<string> {
    await this.ensureRoot();
    const temp: string = `${this.rootPath()}/${hash}.tmp-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(temp, mode);
    try {
      const written: number = await fileIo.write(file.fd, data);
      if (written !== data.byteLength) throw new Error('media short write');
    } catch (error) {
      try { await fileIo.close(file); } catch { /* already closed */ }
      try { if (await this.exists(temp)) await fileIo.unlink(temp); } catch { /* ignore */ }
      throw error instanceof Error ? error : new Error(String(error));
    }
    await fileIo.close(file);
    return this.finalizeTemp(hash, temp, mime, url, kind);
  }

  private async downloadStream(url: string, hash: string, kind: MediaKind): Promise<string | null> {
    const maxBytes: number = kind === 'video' ? VIDEO_MAX_BYTES : IMAGE_MAX_BYTES;
    await this.ensureRoot();
    const temp: string = `${this.rootPath()}/${hash}.tmp-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(temp, mode);
    const request: http.HttpRequest = http.createHttp();
    let mime: string = '';
    let received: number = 0;
    let tooLarge: boolean = false;
    let writeError: Error | null = null;
    let closed: boolean = false;
    let finished: boolean = false;
    let resolveDone: () => void = (): void => {};
    let rejectDone: (error: Error) => void = (_error: Error): void => {};
    const done: Promise<void> = new Promise<void>((resolve, reject): void => {
      resolveDone = resolve;
      rejectDone = reject;
    });
    done.catch((): void => {});
    let writes: Promise<void> = Promise.resolve();
    const fail = (error: Error): void => {
      if (finished) return;
      finished = true;
      rejectDone(error);
    };
    request.on('headersReceive', (headers: Object): void => {
      const rec: Record<string, string> = headers as Record<string, string>;
      for (const name of Object.keys(rec)) {
        const lower: string = name.toLowerCase();
        if (lower === 'content-type') mime = String(rec[name]);
        if (lower !== 'content-length') continue;
        const length: number = Number.parseInt(String(rec[name]), 10);
        if (Number.isFinite(length) && length > maxBytes) {
          tooLarge = true;
          try { request.destroy(); } catch { /* destroyed */ }
          fail(new Error('media response too large'));
        }
      }
    });
    request.on('dataReceive', (chunk: ArrayBuffer): void => {
      if (tooLarge || writeError !== null || finished) return;
      received += chunk.byteLength;
      if (received > maxBytes) {
        tooLarge = true;
        try { request.destroy(); } catch { /* destroyed */ }
        fail(new Error('media response too large'));
        return;
      }
      writes = writes.then(async (): Promise<void> => {
        const written: number = await fileIo.write(file.fd, chunk);
        if (written !== chunk.byteLength) throw new Error('media short write');
      }).catch((error: Error): void => {
        writeError = error;
        try { request.destroy(); } catch { /* destroyed */ }
        fail(error);
      });
    });
    request.on('dataEnd', (): void => {
      writes.then((): void => {
        if (writeError !== null) { fail(writeError); return; }
        if (received <= 0) { fail(new Error('empty media response')); return; }
        if (finished) return;
        finished = true;
        resolveDone();
      }).catch((error: Error): void => { fail(error); });
    });
    try {
      const status: number = await request.requestInStream(url, {
        method: http.RequestMethod.GET,
        header: { 'User-Agent': 'AmberAgent/0.8' },
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        usingCache: false,
        connectTimeout: 15000,
        readTimeout: 60000,
      });
      if (!isHttpOk(status) || tooLarge) {
        fail(new Error(`media stream failed (HTTP ${status})`));
        return null;
      }
      await done;
      if (tooLarge || writeError !== null) return null;
      await fileIo.close(file);
      closed = true;
      const path: string = await this.finalizeTemp(hash, temp, mime, url, kind);
      this.pruneIfNeeded().catch((): void => {});
      return path;
    } catch (error) {
      fail(error instanceof Error ? error : new Error(String(error)));
      return null;
    } finally {
      if (!closed) {
        try { await fileIo.close(file); } catch { /* already closed */ }
      }
      try { if (await this.exists(temp)) await fileIo.unlink(temp); } catch { /* ignore */ }
      try { request.destroy(); } catch { /* destroyed */ }
    }
  }
  private async download(url: string, hash: string, kind: MediaKind): Promise<string | null> {
    if (kind === 'video') return this.downloadStream(url, hash, kind);
    const maxBytes: number = IMAGE_MAX_BYTES;
    const request: http.HttpRequest = http.createHttp();
    let tooLarge: boolean = false;
    request.on('headersReceive', (headers: Object): void => {
      const rec: Record<string, string> = headers as Record<string, string>;
      for (const name of Object.keys(rec)) {
        if (name.toLowerCase() !== 'content-length') continue;
        const length: number = Number.parseInt(String(rec[name]), 10);
        if (Number.isFinite(length) && length > maxBytes) {
          tooLarge = true;
          try { request.destroy(); } catch { /* destroyed */ }
        }
      }
    });
    try {
      const response: http.HttpResponse = await request.request(url, {
        method: http.RequestMethod.GET,
        header: { 'User-Agent': 'AmberAgent/0.8' },
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        usingCache: false,
        connectTimeout: 15000,
        readTimeout: 60000,
      });
      if (tooLarge || !isHttpOk(response.responseCode) || !(response.result instanceof ArrayBuffer)) return null;
      const data: ArrayBuffer = response.result as ArrayBuffer;
      if (data.byteLength <= 0 || data.byteLength > maxBytes) return null;
      const headers: Record<string, string> = (response.header ?? {}) as Record<string, string>;
      let mime: string = '';
      for (const name of Object.keys(headers)) {
        if (name.toLowerCase() === 'content-type') mime = String(headers[name]);
      }
      const path: string = await this.writeTemp(hash, mime, url, kind, data);
      this.pruneIfNeeded().catch((): void => {});
      return path;
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'media download failed: %{public}s', error instanceof Error ? error.message : String(error));
      return null;
    } finally {
      request.destroy();
    }
  }

  async findCached(url: string, kind: MediaKind): Promise<string | null> {
    const trimmed: string = url.trim();
    if (!isRemote(trimmed)) return trimmed;
    const hash: string = sha256HexUtf8(`${kind}:${trimmed}`);
    return this.cachedPath(hash);
  }

  async get(url: string, kind: MediaKind): Promise<string> {
    const trimmed: string = url.trim();
    if (!isRemote(trimmed)) return trimmed;
    const hash: string = sha256HexUtf8(`${kind}:${trimmed}`);
    const cached: string | null = await this.cachedPath(hash);
    if (cached !== null) return cached;
    const key: string = `${kind}:${hash}`;
    const existing: Promise<string | null> | undefined = this.inflight.get(key);
    if (existing !== undefined) {
      const path: string | null = await existing;
      return path ?? trimmed;
    }
    const task: Promise<string | null> = this.download(trimmed, hash, kind);
    this.inflight.set(key, task);
    try {
      const path: string | null = await task;
      return path ?? trimmed;
    } finally {
      this.inflight.delete(key);
    }
  }

  async warmUp(): Promise<void> {
    try {
      await this.ensureIndex();
      this.lastPruneAt = 0;
      await this.pruneIfNeeded();
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'media cache warm-up failed: %{public}s', error instanceof Error ? error.message : String(error));
    }
  }

  private async pruneIfNeeded(): Promise<void> {
    const now: number = Date.now();
    if (now - this.lastPruneAt < PRUNE_INTERVAL_MS) return;
    this.lastPruneAt = now;
    try {
      const root: string = this.rootPath();
      const names: string[] = await fileIo.listFile(root);
      for (const name of names) {
        if (!name.includes('.tmp-')) continue;
        const tempPath: string = `${root}/${name}`;
        try {
          const stat: fileIo.Stat = await fileIo.stat(tempPath);
          if (now - stat.mtime * 1000 > 60 * 60 * 1000) await fileIo.unlink(tempPath);
        } catch { /* already gone */ }
      }
      const index: Map<string, string> = await this.ensureIndex();
      const entries: MediaEntry[] = [];
      let total: number = 0;
      for (const hash of index.keys()) {
        const path: string = index.get(hash) as string;
        if (!(await this.exists(path))) { index.delete(hash); continue; }
        let stat: fileIo.Stat;
        try {
          stat = await fileIo.stat(path);
        } catch {
          index.delete(hash);
          continue;
        }
        total += stat.size;
        entries.push({ hash, path, size: stat.size, mtime: stat.mtime * 1000 });
      }
      entries.sort((a, b): number => a.mtime - b.mtime);
      for (const entry of entries) {
        const expired: boolean = now - entry.mtime > MAX_AGE_MS;
        const overBudget: boolean = total > TOTAL_MAX_BYTES;
        if (!expired && !overBudget) continue;
        try { await fileIo.unlink(entry.path); } catch { /* ignore */ }
        index.delete(entry.hash);
        total -= entry.size;
      }
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'media prune failed: %{public}s', error instanceof Error ? error.message : String(error));
    }
  }
}

let instance: MediaCache | null = null;

export const getMediaCache = (): MediaCache => {
  if (instance === null) instance = new MediaCache();
  return instance;
};

export const cachedMediaUrl = (url: string, kind: MediaKind): Promise<string> =>
  getMediaCache().get(url, kind);

export const cachedMediaUrlIfPresent = (url: string, kind: MediaKind): Promise<string | null> =>
  getMediaCache().findCached(url, kind);

export const warmMediaCache = (): void => { getMediaCache().warmUp().catch((): void => {}); };
