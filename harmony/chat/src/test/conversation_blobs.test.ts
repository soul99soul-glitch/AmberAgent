import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  CHAT_BLOB_MISSING_DATA_URL, CHAT_BLOB_MISSING_METADATA_KEY,
  CHAT_BLOB_PREFIX, CHAT_BLOB_REF_METADATA_KEY,
  collectConversationBlobHashes, externalizeConversation, pruneOrphanBlobs,
  resolveConversation,
} from '../main/ets/chat/conversation_blobs.ts';
import type { ChatBlobPort } from '../main/ets/chat/conversation_blobs.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

class MemoryBlobPort implements ChatBlobPort {
  readonly files: Map<string, string> = new Map<string, string>();
  hash(dataUrl: string): string {
    return createHash('sha256').update(dataUrl).digest('hex');
  }
  async write(hash: string, dataUrl: string): Promise<void> { this.files.set(hash, dataUrl); }
  async read(hash: string): Promise<string | null> { return this.files.get(hash) ?? null; }
  async list(): Promise<string[]> { return Array.from(this.files.keys()); }
  async remove(hash: string): Promise<void> { this.files.delete(hash); }
}

const image = (url: string): UIMessagePart => ({ type: 'image', url, metadata: null });
const text = (value: string): UIMessagePart => ({ type: 'text', text: value, metadata: null });

test('large image data URLs inside tool output externalize and resolve without changing text', async () => {
  const port = new MemoryBlobPort();
  const dataUrl: string = `data:image/png;base64,${'A'.repeat(8000)}`;
  const tool: UIMessagePart = {
    type: 'tool', toolCallId: 'call-1', toolName: 'wm_screenshot', input: '{}',
    output: [text('page loaded'), image(dataUrl)], approvalState: { type: 'auto' }, metadata: null,
  };
  const conv = makeConversation('conv-1', [toMessageNode(makeUIMessage('assistant', [tool]))]);
  const externalized = await externalizeConversation(conv, port);
  const storedTool = externalized.conversation.messageNodes[0].messages[0].parts[0] as { output: UIMessagePart[] };
  assert.equal(externalized.hashes.length, 1);
  assert.ok((storedTool.output[1] as { url: string }).url.startsWith(CHAT_BLOB_PREFIX));
  assert.equal(port.files.size, 1);
  assert.deepEqual(collectConversationBlobHashes(externalized.conversation), externalized.hashes);

  const resolved = await resolveConversation(externalized.conversation, port);
  const resolvedTool = resolved.messageNodes[0].messages[0].parts[0] as { output: UIMessagePart[] };
  assert.equal((resolvedTool.output[0] as { text: string }).text, 'page loaded');
  assert.equal((resolvedTool.output[1] as { url: string }).url, dataUrl);
});

test('missing blob resolves to a marked placeholder instead of a broken data URL', async () => {
  const port = new MemoryBlobPort();
  const dataUrl: string = `data:image/jpeg;base64,${'B'.repeat(5000)}`;
  const tool: UIMessagePart = {
    type: 'tool', toolCallId: 'call-2', toolName: 'wm_screenshot', input: '{}',
    output: [image(dataUrl)], approvalState: { type: 'auto' }, metadata: null,
  };
  const conv = makeConversation('conv-2', [toMessageNode(makeUIMessage('assistant', [tool]))]);
  const externalized = await externalizeConversation(conv, port);
  port.files.clear();
  const resolved = await resolveConversation(externalized.conversation, port);
  const resolvedTool = resolved.messageNodes[0].messages[0].parts[0] as { output: UIMessagePart[] };
  const part = resolvedTool.output[0] as { url: string; metadata: Record<string, unknown> | null };
  assert.equal(part.url, CHAT_BLOB_MISSING_DATA_URL);
  assert.equal(part.metadata?.[CHAT_BLOB_MISSING_METADATA_KEY], true);
  assert.equal(part.metadata?.[CHAT_BLOB_REF_METADATA_KEY], `${CHAT_BLOB_PREFIX}${externalized.hashes[0]}`);

  const restoredRef = await externalizeConversation(resolved, port);
  const restoredTool = restoredRef.conversation.messageNodes[0].messages[0].parts[0] as { output: UIMessagePart[] };
  assert.equal((restoredTool.output[0] as { url: string }).url, `${CHAT_BLOB_PREFIX}${externalized.hashes[0]}`);
  assert.equal((restoredTool.output[0] as { metadata: Record<string, unknown> | null }).metadata, null);
  await port.write(externalized.hashes[0], dataUrl);
  const recovered = await resolveConversation(restoredRef.conversation, port);
  const recoveredTool = recovered.messageNodes[0].messages[0].parts[0] as { output: UIMessagePart[] };
  assert.equal((recoveredTool.output[0] as { url: string }).url, dataUrl);
});

test('a blob shared by two conversations survives until both references are gone', async () => {
  const port = new MemoryBlobPort();
  const dataUrl: string = `data:image/png;base64,${'S'.repeat(5000)}`;
  const makeTool = (id: string): UIMessagePart => ({
    type: 'tool', toolCallId: id, toolName: 'wm_screenshot', input: '{}',
    output: [image(dataUrl)], approvalState: { type: 'auto' }, metadata: null,
  });
  const first = await externalizeConversation(makeConversation('conv-a', [
    toMessageNode(makeUIMessage('assistant', [makeTool('a')])),
  ]), port);
  const second = await externalizeConversation(makeConversation('conv-b', [
    toMessageNode(makeUIMessage('assistant', [makeTool('b')])),
  ]), port);
  assert.deepEqual(first.hashes, second.hashes);
  assert.equal(await pruneOrphanBlobs(new Set<string>(first.hashes), port), 0);
  assert.equal(port.files.has(first.hashes[0]), true);
  assert.equal(await pruneOrphanBlobs(new Set<string>(), port), 1);
  assert.equal(port.files.size, 0);
});

test('orphan sweep removes unreferenced files and keeps referenced content-addressed blobs', async () => {
  const port = new MemoryBlobPort();
  const dataUrl: string = `data:image/png;base64,${'C'.repeat(5000)}`;
  const tool: UIMessagePart = {
    type: 'tool', toolCallId: 'call-3', toolName: 'wm_screenshot', input: '{}',
    output: [image(dataUrl)], approvalState: { type: 'auto' }, metadata: null,
  };
  const conv = makeConversation('conv-3', [toMessageNode(makeUIMessage('assistant', [tool]))]);
  const externalized = await externalizeConversation(conv, port);
  const orphan: string = 'd'.repeat(64);
  await port.write(orphan, 'data:image/png;base64,old');
  const removed = await pruneOrphanBlobs(new Set<string>(externalized.hashes), port);
  assert.equal(removed, 1);
  assert.equal(port.files.has(orphan), false);
  assert.equal(port.files.has(externalized.hashes[0]), true);
});
