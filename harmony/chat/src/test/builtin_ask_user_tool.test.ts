// builtin_ask_user_tool.test.ts — ask_user HITL 工具(D-058 TDD)
//
// Android 基准: app/core/ai/tools/AskUserTool.kt 全文
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createAskUserTool } from '../main/ets/chat/builtin_ask_user_tool.ts';

describe('createAskUserTool(AskUserTool.kt)', () => {
  it('定义:name/needsApproval/description 逐字 + questions 必填嵌套 schema', () => {
    const tool = createAskUserTool();
    assert.equal(tool.name, 'ask_user');
    assert.equal(tool.needsApproval, true);
    assert.ok(tool.description.startsWith('Ask the user one or more questions'));
    const schema = tool.parameters();
    assert.ok(schema !== null);
    assert.deepEqual(schema.required, ['questions']);
    const questions = schema.properties['questions'] as {
      type: string; items: { required: string[]; properties: Record<string, { enum?: string[] }> };
    };
    assert.equal(questions.type, 'array');
    assert.deepEqual(questions.items.required, ['id', 'question']);
    assert.deepEqual(questions.items.properties['selection_type'].enum, ['text', 'single', 'multi']);
  });
  it('execute = stub(HITL 流接管,正常管线触达即抛)', async () => {
    const tool = createAskUserTool();
    await assert.rejects(
      tool.execute({}),
      (e: Error): boolean => e.message === 'ask_user tool should be handled by HITL flow',
    );
  });
});
