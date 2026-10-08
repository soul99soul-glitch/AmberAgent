import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelProjectOperationTools, executeNovelProjectOperation } from '../main/ets/chat/novel_project_operations.ts';
import { createNovelWorkspaceTools } from '../main/ets/chat/novel_workspace_tools.ts';
import { toChatToolDefinition } from '../main/ets/chat/tool.ts';
import type { NovelWorkspaceToolPort } from '../main/ets/chat/novel_workspace_tools.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

test('strict object schemas survive provider serialization and publish actual bounded iOS arguments', () => {
  const tools = createNovelProjectOperationTools(async () => ({}));
  for (const tool of tools) {
    assert.equal(toChatToolDefinition(tool).parameters.additionalProperties, false);
  }
  const schema = (name: string): JsonObject => toChatToolDefinition(tools.find(tool => tool.name === name)!).parameters;
  assert.deepEqual((schema('novel_upsert_upcoming_arc').properties as JsonObject).beats, {
    type: 'array', items: { type: 'string', minLength: 1, maxLength: 160 }, minItems: 1, maxItems: 8,
  });
  assert.deepEqual((schema('novel_revert_recent_chapters').properties as JsonObject).chapter_count,
    { type: 'integer', minimum: 1, maximum: 64 });
  assert.deepEqual((schema('novel_prepare_ghostwrite').properties as JsonObject).suggested_chapter_count,
    { type: 'integer', minimum: 1, maximum: 10 });
  assert.deepEqual(schema('novel_revise_chapter').required, ['start_paragraph', 'end_paragraph', 'new_text']);
  assert.deepEqual((schema('novel_revise_chapter').properties as JsonObject).new_text,
    { type: 'string', minLength: 1, maxLength: 32000 });
  assert.deepEqual(schema('novel_read_chapter').required, []);
  const material = schema('novel_revise_material').properties as JsonObject;
  assert.deepEqual(material.tags, { type: 'array', items: { type: 'string' } });
  assert.deepEqual(material.injection_mode, { type: 'string', enum: ['always', 'smart', 'off'] });
  assert.ok(!(schema('novel_revise_material').required as string[]).includes('tags'));
  assert.ok(!(schema('novel_revise_material').required as string[]).includes('injection_mode'));
  assert.deepEqual(schema('novel_delete_chapters').anyOf,
    [{ required: ['chapter_ordinals'] }, { required: ['chapter_ids'] }]);
});

test('project catalog is opt-in and leaves old workspace write approval unchanged', () => {
  const noResult = async (): Promise<JsonObject> => ({});
  const port: NovelWorkspaceToolPort = {
    list: noResult, read: noResult, grep: noResult, status: noResult,
    write: noResult, audit: noResult, ingestSettingProposals: noResult,
  };
  assert.equal(createNovelWorkspaceTools(port).length, 7);
  const tools = createNovelWorkspaceTools({ ...port, projectOperation: noResult });
  assert.equal(tools.length, 22);
  assert.equal(tools.find(tool => tool.name === 'novel_workspace_write')?.needsApproval, true);
  assert.equal(tools.find(tool => tool.name === 'novel_workspace_write')?.allowsAutoApproval, false);
});

test('real project tool catalog calls creation and repository, with durable author approval after transcript', async () => {
  const { createNovelCreation } = await import('../../../deepread/src/main/ets/novel/creation.ts');
  const { createFileNovelRepository } = await import('../../../deepread/src/main/ets/novel/repository.ts');
  const { createMemoryFileStore } = await import('../../../deepread/src/main/ets/platform/files.ts');
  const { makeNovelProject, makeNovelChapter, makeNovelMessage } = await import('../../../deepread/src/main/ets/novel/models.ts');
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({ ...makeNovelProject({ id: 'actual-tool', name: '工具测试', now: 1000 }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '原段\n\n第二段', now: 1000 })] });
  const creation = createNovelCreation({ repository, nowMs: () => 1001, modelRunning: {
    async validate() {}, cancel() {}, start() { throw new Error('tool operation should not generate'); },
  } });
  const tools = createNovelProjectOperationTools((name, input) => executeNovelProjectOperation(creation, 'actual-tool', name, input));
  const output = await tools.find(tool => tool.name === 'novel_revise_chapter')!.execute({
    chapter_id: 'c1', start_paragraph: 2, end_paragraph: 2, new_text: '确认后的第二段', expected_text: '第二段',
  });
  assert.equal(output[0].type, 'text');
  const result = output[0].type === 'text' ? JSON.parse(output[0].text) : null;
  assert.equal(result.requires_author_approval, true);
  assert.equal((await repository.loadProject('actual-tool')).chapters[0].content, '原段\n\n第二段');
  await repository.commitProject('actual-tool', (await repository.workspaceStatus('actual-tool')).cas, 'tool-discussion-saved', 'transcript_checkpoint', p => ({
    ...p, messages: [makeNovelMessage({ id: 'assistant', role: 'assistant', mode: 'discuss', content: '已提出修改，待作者确认', createdAt: 1001 })],
  }));
  const reloaded = createFileNovelRepository(store);
  await reloaded.resolveProposal('actual-tool', result.proposal_id, true, 'author-accept', 1002);
  assert.equal((await reloaded.loadProject('actual-tool')).chapters[0].content, '原段\n\n确认后的第二段');
});
