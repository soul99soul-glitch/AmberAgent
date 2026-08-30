import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { migrateLegacyUIMessage } from '../main/ets/agent/legacy_message.ts';
import type { UIMessagePartTool } from '../main/ets/agent/message.ts';

test('legacy DeepRead wire migrates kind, epoch, string approval, and streamIndex once', () => {
  const migrated = migrateLegacyUIMessage({
    id: 'legacy-1', role: 'assistant', createdAt: 1000, finishedAt: 2000, modelId: 'm1',
    parts: [{
      kind: 'tool', toolCallId: 'c1', toolName: 'search', input: '{}',
      output: [{ kind: 'text', text: 'done' }], approvalState: 'approved', streamIndex: 3,
    }],
  });

  assert.equal(migrated.createdAt, '1970-01-01T00:00:01.000Z');
  assert.equal(migrated.finishedAt, '1970-01-01T00:00:02.000Z');
  assert.deepEqual(migrated.annotations, []);
  assert.equal(migrated.usage, null);
  assert.equal(migrated.translation, null);
  const tool = migrated.parts[0] as UIMessagePartTool;
  assert.deepEqual(tool.approvalState, { type: 'approved' });
  assert.deepEqual(tool.metadata, { stream_tool_index: 3 });
  assert.deepEqual(tool.output, [{ type: 'text', text: 'done', metadata: null }]);
});
