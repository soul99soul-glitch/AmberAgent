// Readonly Markdown projection; MemoryStore owns serialization and the source of truth.
import { fileIo } from '@kit.CoreFileKit';
import type { MemoryDerivedDocument, MemoryDocumentProjection, MemoryRecord, MemoryProfile } from '@amber/chat-domain';
import { renderMemoryDocuments } from '@amber/chat-domain';
import { writeTextFileAtomic } from './AtomicJsonFile.ets';

const documentRoot = (): string => {
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${context.filesDir}/memory/documents`;
};
const validPath = (path: string): boolean => path === 'index.md'
  || /^topics\/[1-9][0-9]*-[\p{L}\p{N}-]+\.md$/u.test(path);
const fullPath = (path: string): string => {
  if (!validPath(path)) throw new Error('只能读取生成的记忆文档');
  return `${documentRoot()}/${path}`;
};
const existingPaths = async (): Promise<string[]> => {
  const paths: string[] = [];
  if (await fileIo.access(fullPath('index.md'))) paths.push('index.md');
  const topics: string = `${documentRoot()}/topics`;
  if (await fileIo.access(topics)) {
    const names: string[] = await fileIo.listFile(topics);
    for (const name of names) {
      const path: string = `topics/${name}`;
      if (validPath(path)) paths.push(path);
    }
  }
  return paths;
};

export const syncMemoryDocuments = async (records: MemoryRecord[], now: number, profile: MemoryProfile | null = null): Promise<void> => {
  const projections: MemoryDocumentProjection[] = renderMemoryDocuments(records, now, profile);
  const expected: string[] = [];
  for (const projection of projections) {
    const path: string = fullPath(projection.relativePath);
    expected.push(projection.relativePath);
    const existing: string | null = await fileIo.access(path) ? await fileIo.readText(path) : null;
    if (existing !== projection.content) await writeTextFileAtomic(path, projection.content);
  }
  const old: string[] = await existingPaths();
  for (const path of old) {
    if (expected.indexOf(path) < 0) await fileIo.unlink(fullPath(path));
  }
};

export const listMemoryDocuments = async (): Promise<MemoryDerivedDocument[]> => {
  const result: MemoryDerivedDocument[] = [];
  const paths: string[] = await existingPaths();
  for (const relativePath of paths) {
    const path: string = fullPath(relativePath);
    const content: string = await fileIo.readText(path);
    const stat: fileIo.Stat = await fileIo.stat(path);
    const title: string = content.split('\n')[0].replace(/^#\s+/, '').trim();
    const topicId: number | null = relativePath === 'index.md' ? null : Number(relativePath.split('/')[1].split('-')[0]);
    const document: MemoryDerivedDocument = {
      relativePath, title, preview: content.replace(/\s+/g, ' ').trim().slice(0, 160),
      sizeBytes: stat.size, modifiedAt: stat.mtime * 1000, topicId,
    };
    result.push(document);
  }
  return result;
};

export const readMemoryDocument = async (relativePath: string): Promise<string | null> => {
  const path: string = fullPath(relativePath);
  return await fileIo.access(path) ? await fileIo.readText(path) : null;
};
