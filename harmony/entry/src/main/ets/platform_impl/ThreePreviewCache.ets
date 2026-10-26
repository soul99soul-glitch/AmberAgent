import { fileIo } from '@kit.CoreFileKit';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import type { ThreeSceneObject, ThreeSceneSpec } from '@amber/chat-domain';
import { sharedKvMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';

export interface ThreePreviewRecord {
  snapshot: string;
  view: string;
}

interface ThreePreviewMetadata {
  file: string;
  view: string;
}

interface ThreePreviewEntry {
  key: string;
  file: string;
  size: number;
  modified: number;
}

const CACHE_VERSION: string = 'amber-three-preview-v1';
const MAX_ENTRIES: number = 64;
const MAX_TOTAL_BYTES: number = 32 * 1024 * 1024;
const MAX_PNG_BYTES: number = 4 * 1024 * 1024;
const MAX_VIEW_CHARS: number = 16384;
const PNG_PREFIX: string = 'data:image/png;base64,';
const KEY_PATTERN: RegExp = /^[a-f0-9]{64}$/;
const PNG_PATTERN: RegExp = /^[a-f0-9]{64}-[a-f0-9-]{32,36}\.png$/;

const rootPath = (): string => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined) throw new Error('abilityContext unavailable for 3D previews');
  return `${context.cacheDir}/three-previews`;
};

const requireKey = (key: string): void => {
  if (!KEY_PATTERN.test(key)) throw new Error('Invalid 3D preview key');
};

export const threePreviewKey = async (scene: ThreeSceneSpec, dark: boolean): Promise<string> => {
  // Reconstruct field order so equivalent normalized scenes have the same key.
  const canonical: ThreeSceneSpec = {
    objects: scene.objects.map((item: ThreeSceneObject): ThreeSceneObject => ({
      id: item.id, type: item.type, color: item.color, position: item.position,
      rotation: item.rotation, scale: item.scale, size: item.size,
    })),
  };
  const text: string = `${CACHE_VERSION}\n${dark ? 'dark' : 'light'}\n${JSON.stringify(canonical)}`;
  const md: cryptoFramework.Md = cryptoFramework.createMd('SHA256');
  await md.update({ data: util.TextEncoder.create('utf-8').encodeInto(text) });
  const digest: cryptoFramework.DataBlob = await md.digest();
  let key: string = '';
  for (const byte of digest.data) key += byte.toString(16).padStart(2, '0');
  return key;
};

const readMetadata = async (root: string, key: string): Promise<ThreePreviewMetadata | null> => {
  try {
    const path: string = `${root}/${key}.json`;
    const stat: fileIo.Stat = await fileIo.stat(path);
    if (stat.size <= 0 || stat.size > MAX_VIEW_CHARS * 4 + 1024) return null;
    const metadata: ThreePreviewMetadata = JSON.parse(await fileIo.readText(path)) as ThreePreviewMetadata;
    if (metadata === null || typeof metadata.file !== 'string' || typeof metadata.view !== 'string' ||
      !metadata.file.startsWith(`${key}-`) || !PNG_PATTERN.test(metadata.file) ||
      metadata.view.length > MAX_VIEW_CHARS) return null;
    return metadata;
  } catch {
    // System cache eviction or a partial previous session is a cache miss.
    return null;
  }
};

const removeIfPresent = async (path: string): Promise<void> => {
  if (await fileIo.access(path)) await fileIo.unlink(path);
};

export const readThreePreview = async (key: string): Promise<ThreePreviewRecord | null> => {
  requireKey(key);
  const root: string = rootPath();
  return sharedKvMutex.withLock(root, async (): Promise<ThreePreviewRecord | null> => {
    const metadata: ThreePreviewMetadata | null = await readMetadata(root, key);
    if (metadata === null) return null;
    try {
      const stat: fileIo.Stat = await fileIo.stat(`${root}/${metadata.file}`);
      if (stat.size <= 0 || stat.size > MAX_PNG_BYTES) return null;
      return { snapshot: `file://${root}/${metadata.file}`, view: metadata.view };
    } catch {
      return null;
    }
  });
};

const writePng = async (path: string, bytes: Uint8Array): Promise<void> => {
  const file: fileIo.File = await fileIo.open(path,
    fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  try {
    let written: number = 0;
    while (written < bytes.byteLength) {
      const buffer: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset + written,
        bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      const count: number = await fileIo.write(file.fd, buffer);
      if (count <= 0 || count > buffer.byteLength) throw new Error('Incomplete 3D preview write');
      written += count;
    }
  } finally {
    await fileIo.close(file);
  }
};

const prunePreviews = async (root: string, keepFile: string): Promise<void> => {
  const names: string[] = await fileIo.listFile(root);
  const referenced: Set<string> = new Set<string>();
  const entries: ThreePreviewEntry[] = [];
  let total: number = 0;
  for (const name of names) {
    if (!name.endsWith('.json')) continue;
    const key: string = name.substring(0, name.length - 5);
    if (!KEY_PATTERN.test(key)) continue;
    const metadata: ThreePreviewMetadata | null = await readMetadata(root, key);
    if (metadata === null || !(await fileIo.access(`${root}/${metadata.file}`))) {
      await removeIfPresent(`${root}/${name}`);
      continue;
    }
    const stat: fileIo.Stat = await fileIo.stat(`${root}/${metadata.file}`);
    referenced.add(metadata.file);
    entries.push({ key: key, file: metadata.file, size: stat.size, modified: stat.mtime });
    total += stat.size;
  }
  // PNGs not referenced by committed metadata are interrupted writes or replaced views.
  for (const name of names) {
    if (PNG_PATTERN.test(name) && !referenced.has(name)) await removeIfPresent(`${root}/${name}`);
  }
  entries.sort((a: ThreePreviewEntry, b: ThreePreviewEntry): number => a.modified - b.modified);
  let count: number = entries.length;
  for (const entry of entries) {
    if (count <= MAX_ENTRIES && total <= MAX_TOTAL_BYTES) break;
    if (entry.file === keepFile) continue;
    await removeIfPresent(`${root}/${entry.key}.json`);
    await removeIfPresent(`${root}/${entry.file}`);
    count--;
    total -= entry.size;
  }
};

export const writeThreePreview = async (
  key: string, snapshotDataUrl: string, view: string,
): Promise<ThreePreviewRecord> => {
  requireKey(key);
  if (!snapshotDataUrl.startsWith(PNG_PREFIX) ||
    snapshotDataUrl.length > PNG_PREFIX.length + Math.ceil(MAX_PNG_BYTES / 3) * 4 || view.length > MAX_VIEW_CHARS) {
    throw new Error('Invalid 3D preview snapshot');
  }
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(snapshotDataUrl.substring(PNG_PREFIX.length));
  if (bytes.length < 8 || bytes.length > MAX_PNG_BYTES || bytes[0] !== 0x89 || bytes[1] !== 0x50 ||
    bytes[2] !== 0x4e || bytes[3] !== 0x47 || bytes[4] !== 0x0d || bytes[5] !== 0x0a ||
    bytes[6] !== 0x1a || bytes[7] !== 0x0a) throw new Error('Invalid 3D preview PNG');
  const root: string = rootPath();
  return sharedKvMutex.withLock(root, async (): Promise<ThreePreviewRecord> => {
    if (!(await fileIo.access(root))) await fileIo.mkdir(root, true);
    const previous: ThreePreviewMetadata | null = await readMetadata(root, key);
    const file: string = `${key}-${util.generateRandomUUID(false)}.png`;
    try {
      await writePng(`${root}/${file}`, bytes);
      const metadata: ThreePreviewMetadata = { file: file, view: view };
      // Commit one pointer only after the complete PNG is closed, keeping image and camera paired.
      await writeTextFileAtomic(`${root}/${key}.json`, JSON.stringify(metadata));
    } catch (error) {
      try { await removeIfPresent(`${root}/${file}`); } catch { /* Keep the original write error. */ }
      throw error as Error;
    }
    try {
      if (previous !== null) await removeIfPresent(`${root}/${previous.file}`);
      await prunePreviews(root, file);
    } catch (error) {
      console.warn('[ThreePreviewCache] Cleanup failed: ' + (error as Error).message);
    }
    return { snapshot: `file://${root}/${file}`, view: view };
  });
};
