import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ts from 'typescript';
import * as domain from '../main/ets/index.ts';
import type { FilesRepository, ManagedFileEntity } from '../main/ets/chat/managed_files.ts';
import type { UIMessagePart, UIMessagePartDocument } from '../main/ets/chat/message.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import type { DocumentReaderDeps } from '../main/ets/chat/document_transformer.ts';

interface AttachmentAPI {
  materializeDocumentAttachment: (document: UIMessagePartDocument) => Promise<UIMessagePartDocument>;
  materializeDocumentParts: (parts: UIMessagePart[]) => Promise<UIMessagePart[]>;
  documentAttachmentMime: (name: string, mime: string) => string;
  materializeConversationDocuments: (conversation: Conversation) => Promise<Conversation>;
}

const sourceRoot = new URL('../../../entry/src/main/ets/platform_impl/', import.meta.url);
const loadEntry = <T>(name: string, imports: Record<string, unknown>, filesDir: string): T => {
  const source = fs.readFileSync(new URL(name, sourceRoot), 'utf8');
  const compiled = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const module = { exports: {} };
  new Function('require', 'exports', 'module', 'AppStorage', compiled)(
    (id: string) => {
      assert.ok(id in imports, `unexpected platform import ${id}`);
      return imports[id];
    }, module.exports, module, { get: () => ({ filesDir }) },
  );
  return module.exports as T;
};

const harness = (t: test.TestContext, rejectRegistration = false) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-document-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const rows: ManagedFileEntity[] = [];
  const pdfCalls: string[] = [];
  const parsePdfPath = async (absolutePath: string): Promise<string> => {
    pdfCalls.push(absolutePath);
    return 'PDF parser result';
  };
  const repository = {
    getByPath: async (relativePath: string) => rows.find((row) => row.relativePath === relativePath) ?? null,
    insert: async (row: ManagedFileEntity) => {
      if (rejectRegistration) throw new Error('registry unavailable');
      const registered = { ...row, id: rows.length + 1 };
      rows.push(registered);
      return registered;
    },
  } as FilesRepository;
  const fileIo = {
    OpenMode: { READ_ONLY: 0, READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT,
      TRUNC: fs.constants.O_TRUNC },
    accessSync: fs.existsSync,
    mkdirSync: (directory: string) => fs.mkdirSync(directory, { recursive: true }),
    openSync: (file: string, mode: number) => ({ fd: fs.openSync(file, mode) }),
    writeSync: (fd: number, buffer: ArrayBuffer) => fs.writeSync(fd, new Uint8Array(buffer)),
    closeSync: (file: { fd: number }) => fs.closeSync(file.fd),
    unlinkSync: fs.unlinkSync,
    statSync: (file: string | number) => {
      const stat = typeof file === 'string' ? fs.statSync(file) : fs.fstatSync(file);
      // Harmony Stat.mode exposes permissions; type checks use Stat.isFile().
      return { mode: stat.mode & 0o777, size: stat.size, isFile: () => stat.isFile() };
    },
    readSync: (fd: number, buffer: ArrayBuffer) => fs.readSync(fd, new Uint8Array(buffer)),
  };
  const imports: Record<string, unknown> = {
    '@kit.AbilityKit': {}, '@kit.CoreFileKit': { fileIo },
    '@kit.ArkTS': { util: {
      Base64Helper: class { decodeSync(value: string) { return new Uint8Array(Buffer.from(value, 'base64')); } },
      TextDecoder: { create: () => ({ decodeToString: (bytes: Uint8Array) => Buffer.from(bytes).toString('utf8') }) },
    } },
    '@amber/chat-domain': domain,
    './ManagedFileStore.ets': { getFilesRepository: () => repository,
      getDeleteChatFilesDeps: () => ({
        toRelativePath: (url: string) => url.startsWith(`file://${root}/`) ? url.slice(`file://${root}/`.length) : null,
        fileExists: async (relative: string) => fs.existsSync(path.join(root, relative)),
        deleteFile: async (relative: string) => fs.unlinkSync(path.join(root, relative)),
        deleteByPath: async (relative: string) => {
          const index = rows.findIndex((row) => row.relativePath === relative);
          if (index < 0) return 0;
          rows.splice(index, 1);
          return 1;
        },
      }),
    },
    './EntryDocumentParser.ets': {},
    './PdfDocumentParser.ets': {
      parsePdfEntry: (handle: domain.DocumentFileHandle): Promise<string> => parsePdfPath(handle.absolutePath),
      parsePdfPath,
      parsePdfPreviewBytes: async (bytes: Uint8Array): Promise<string> => {
        pdfCalls.push(`bytes:${bytes.length}`);
        return 'PDF parser result';
      },
    },
  };
  const attachments = loadEntry<AttachmentAPI>('DocumentAttachmentSupport.ets', imports, root);
  imports['./DocumentAttachmentSupport.ets'] = attachments;
  const documents = loadEntry<{ createEntryDocumentReader: () => DocumentReaderDeps;
    readDocumentPreview: (document: UIMessagePartDocument) => Promise<string> }>(
    'DocumentSupport.ets', imports, root,
  );
  return { root, rows, attachments, reader: documents.createEntryDocumentReader(),
    preview: documents.readDocumentPreview, pdfCalls };
};

const pasted = (text: string, name = 'paste.txt', mime = 'text/plain'): UIMessagePartDocument => ({
  type: 'document', url: `data:${mime};base64,${Buffer.from(text).toString('base64')}`,
  fileName: name, mime, metadata: { source: 'paste' },
});

test('entry data document becomes a registered upload and real production reader sees Unicode text', async (t) => {
  const h = harness(t);
  const text = '中文附件\nsecond line 😀';
  const input = pasted(text);
  assert.match(await domain.readDocumentContent(input, h.reader), /invalid file uri/);
  const output = await h.attachments.materializeDocumentAttachment(input);
  assert.ok(output.url.startsWith(`file://${h.root}/upload/`));
  assert.equal(output.fileName, input.fileName);
  assert.deepEqual(output.metadata, input.metadata);
  assert.equal(await domain.readDocumentContent(output, h.reader), text);
  assert.equal(h.rows.length, 1);
  assert.equal(h.rows[0]!.displayName, input.fileName);
  assert.equal(h.rows[0]!.sizeBytes, Buffer.byteLength(text));
  assert.equal(h.rows[0]!.relativePath, output.url.slice(`file://${h.root}/`.length));
  const stored = JSON.parse(JSON.stringify(output)) as UIMessagePartDocument;
  assert.equal(await domain.readDocumentContent(stored, h.reader), text);
  assert.equal(await h.preview(stored), text);
  const conversation = domain.makeConversation('documents', [domain.makeMessageNode([
    domain.makeUIMessage('user', [stored]),
  ])]);
  assert.deepEqual(domain.conversationFileUris(conversation), [output.url]);
});

test('registration failure removes the newly written file and rejects import', async (t) => {
  const h = harness(t, true);
  await assert.rejects(h.attachments.materializeDocumentAttachment(pasted('keep draft')), /registry unavailable/);
  assert.deepEqual(fs.readdirSync(path.join(h.root, 'upload')), []);
  assert.equal(h.rows.length, 0);
});

test('materialization preserves non-document parts and does not duplicate an existing owned file', async (t) => {
  const h = harness(t);
  const owned = await h.attachments.materializeDocumentAttachment(pasted('existing'));
  const text: UIMessagePart = { type: 'text', text: 'prompt', metadata: null };
  const parts = await h.attachments.materializeDocumentParts([text, owned]);
  assert.equal(parts[0], text);
  assert.equal(parts[1], owned);
  assert.equal(h.rows.length, 1);
});

test('failed multi-document preparation cleans only newly created files, preserving an existing attachment', async (t) => {
  const h = harness(t);
  const owned = await h.attachments.materializeDocumentAttachment(pasted('keep owned'));
  await assert.rejects(h.attachments.materializeDocumentParts([
    owned, pasted('new'), { ...pasted('bad'), url: 'data:text/plain,not base64' },
  ]), /base64/);
  assert.equal(h.rows.length, 1);
  assert.equal(await domain.readDocumentContent(owned, h.reader), 'keep owned');
  assert.equal(fs.readdirSync(path.join(h.root, 'upload')).length, 1);
});

test('office extension normalizes MIME, filenames cannot create nested uploads, and PDF dispatches to its platform parser', async (t) => {
  const h = harness(t);
  const epub = await h.attachments.materializeDocumentAttachment(pasted('archive', '../../book.epub', 'application/octet-stream'));
  assert.equal(epub.mime, 'application/epub+zip');
  assert.match(epub.url, /\/upload\/[^/]+\.epub$/);
  assert.equal(h.rows[0]!.displayName, '../../book.epub');
  const pdf = await h.attachments.materializeDocumentAttachment(pasted('pdf bytes', 'paper.pdf', 'application/octet-stream'));
  assert.equal(pdf.mime, 'application/pdf');
  assert.equal(await domain.readDocumentContent(pdf, h.reader), 'PDF parser result');
  assert.equal(await h.preview(pdf), 'PDF parser result');
  assert.deepEqual(h.pdfCalls, [pdf.url.slice('file://'.length), pdf.url.slice('file://'.length)]);
});

test('malformed and over-limit data fail before a file is published', async (t) => {
  const h = harness(t);
  await assert.rejects(h.attachments.materializeDocumentAttachment({ ...pasted('x'), url: 'data:text/plain,x' }), /base64/);
  const tooLarge = 'A'.repeat(Math.ceil((10 * 1024 * 1024) / 3) * 4 + 4);
  await assert.rejects(h.attachments.materializeDocumentAttachment({ ...pasted('x'), url: `data:text/plain;base64,${tooLarge}` }), /10MB/);
  assert.deepEqual(fs.readdirSync(h.root), []);
});

test('legacy history conversion keeps all variants and metadata; a second load writes no new files', async (t) => {
  const h = harness(t);
  const unchanged = domain.makeMessageNode([domain.makeUserMessage('ordinary prompt')], 0, 'unchanged-node');
  const variant0 = domain.makeUIMessage('user', [pasted('first variant')], {
    id: 'message-0', createdAt: '2026-09-30T01:00:00', finishedAt: '2026-09-30T01:01:00',
    modelId: 'model-a', translation: 'translation',
    annotations: [{ type: 'url_context', url: 'https://example.com', status: 'ok' }],
  });
  const variant1 = domain.makeUIMessage('user', [pasted('selected variant')], {
    id: 'message-1', createdAt: '2026-09-30T02:00:00', modelId: 'model-b',
  });
  const original = domain.makeConversation('legacy-documents', [
    unchanged, domain.makeMessageNode([variant0, variant1], 1, 'branched-node'),
  ], { assistantId: 'assistant', title: 'history', chatSuggestions: ['next'], isPinned: true,
    autoApproveToolCalls: true, createAt: 'created', updateAt: 'updated' });
  const converted = await h.attachments.materializeConversationDocuments(original);
  assert.notEqual(converted, original);
  assert.equal(converted.messageNodes[0], unchanged);
  assert.equal(converted.messageNodes[1]!.id, 'branched-node');
  assert.equal(converted.messageNodes[1]!.selectIndex, 1);
  assert.equal(converted.messageNodes[1]!.messages.length, 2);
  assert.deepEqual({ ...converted, messageNodes: [] }, { ...original, messageNodes: [] });
  for (const [index, message] of converted.messageNodes[1]!.messages.entries()) {
    const before = original.messageNodes[1]!.messages[index]!;
    assert.deepEqual({ ...message, parts: [] }, { ...before, parts: [] });
    assert.ok((before.parts[0] as UIMessagePartDocument).url.startsWith('data:'));
    assert.equal(await domain.readDocumentContent(message.parts[0] as UIMessagePartDocument, h.reader),
      index === 0 ? 'first variant' : 'selected variant');
  }
  assert.equal(h.rows.length, 2);
  const restored = JSON.parse(JSON.stringify(converted)) as Conversation;
  assert.equal(await h.attachments.materializeConversationDocuments(restored), restored);
  assert.equal(h.rows.length, 2);
  assert.equal(fs.readdirSync(path.join(h.root, 'upload')).length, 2);
});

test('failed legacy conversion rolls back newly owned files and leaves the original history intact', async (t) => {
  const h = harness(t);
  const original = domain.makeConversation('failed-legacy', [
    domain.makeMessageNode([domain.makeUIMessage('user', [pasted('valid')])]),
    domain.makeMessageNode([domain.makeUIMessage('user', [{ ...pasted('bad'), url: 'data:text/plain,invalid' }])]),
  ]);
  const baseline = JSON.stringify(original);
  await assert.rejects(h.attachments.materializeConversationDocuments(original), /base64/);
  assert.equal(JSON.stringify(original), baseline);
  assert.equal(h.rows.length, 0);
  assert.deepEqual(fs.readdirSync(path.join(h.root, 'upload')), []);
});
