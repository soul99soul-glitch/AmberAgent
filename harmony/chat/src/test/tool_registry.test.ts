// tool_registry.test.ts — ToolRegistry 注册/包装层(D-058 TDD)
//
// Android 基准: feature/tools/api ToolRegistry.kt:31-161
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { createToolRegistry, toToolMetadata } from '../main/ets/chat/tool_registry.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';

const echoTool = (name: string, extra: {
  needsApproval?: boolean; allowsAutoApproval?: boolean; mandatoryApproval?: boolean;
} = {}): AgentTool => makeAgentTool({
  name,
  description: `echo ${name}`,
  parameters: () => makeInputSchemaObj({ q: { type: 'string' } }),
  needsApproval: extra.needsApproval ?? false,
  allowsAutoApproval: extra.allowsAutoApproval ?? false,
  mandatoryApproval: extra.mandatoryApproval ?? false,
  execute: (input: JsonValue): Promise<UIMessagePart[]> => {
    const q = (input as { q?: string }).q ?? '';
    return Promise.resolve([{ type: 'text', text: `ran:${name}:${q}`, metadata: null }]);
  },
});

describe('ToolRegistry.from(:84-97)', () => {
  it('重名注册 → require 失败(文案逐字,排序逗号连接)', () => {
    assert.throws(
      () => createToolRegistry([echoTool('b_tool'), echoTool('a_tool'), echoTool('b_tool')]),
      (e: Error): boolean => e.message === 'Duplicate tool names registered: b_tool',
    );
    assert.throws(
      () => createToolRegistry([
        echoTool('z'), echoTool('a'), echoTool('z'), echoTool('a'),
      ]),
      (e: Error): boolean => e.message === 'Duplicate tool names registered: a, z',
    );
  });
});

describe('toMetadata 派生(:99-124)', () => {
  it('mutates 名称(conversation_compact)→ needsApproval=true 即使工具声明 false', () => {
    const m = toToolMetadata(echoTool('conversation_compact'));
    assert.equal(m.mutates, true);
    assert.equal(m.needsApproval, true);
    assert.equal(m.category, 'context');
  });
  it('high risk 显式(memory_tool)→ needsApproval 且恒不可 auto', () => {
    const m = toToolMetadata(echoTool('memory_tool', { allowsAutoApproval: true }));
    assert.equal(m.risk, 'high');
    assert.equal(m.needsApproval, true);
    assert.equal(m.autoApprovable, false);
  });
  it('fail-closed:未知 mutates 工具(sms_send)→ 不可 auto', () => {
    // sms_send 显式 high risk;用未知 mutates 名验证 fail-closed 类目路径
    const m = toToolMetadata(echoTool('workspace_write', { allowsAutoApproval: true }));
    assert.equal(m.mutates, true);
    assert.equal(m.autoApprovable, false);
  });
  it('只读普通工具 + allowsAutoApproval → autoApprovable=true', () => {
    const m = toToolMetadata(echoTool('session_read', { needsApproval: true, allowsAutoApproval: true }));
    assert.equal(m.risk, 'sensitive');
    assert.equal(m.sensitiveRead, true);
    assert.equal(m.needsApproval, true);
    assert.equal(m.autoApprovable, true);
  });
  it('mandatoryApproval → needsApproval 且不可 auto(绕过普通 auto-approval)', () => {
    const m = toToolMetadata(echoTool('wm_eval', { mandatoryApproval: true, allowsAutoApproval: true }));
    assert.equal(m.needsApproval, true);
    assert.equal(m.autoApprovable, false);
    assert.equal(m.mandatoryApproval, true);
  });
});

describe('tools() 包装副本(:66-75)', () => {
  it('needsApproval/allowsAutoApproval 按 metadata 覆盖', () => {
    const reg = createToolRegistry([
      echoTool('conversation_compact'), // mutates → needsApproval 覆盖 true
      echoTool('session_read', { needsApproval: true, allowsAutoApproval: true }),
    ]);
    const wrapped = reg.tools();
    assert.equal(wrapped[0].needsApproval, true);
    assert.equal(wrapped[1].needsApproval, true);
    assert.equal(wrapped[1].allowsAutoApproval, true);
  });
  it('display_title 注入 properties(subagent_start 豁免)', () => {
    const reg = createToolRegistry([echoTool('conversation_search'), echoTool('subagent_start')]);
    const wrapped = reg.tools();
    const schema0 = wrapped[0].parameters();
    assert.ok(schema0 !== null && schema0.properties['display_title'] !== undefined);
    const schema1 = wrapped[1].parameters();
    assert.ok(schema1 !== null && schema1.properties['display_title'] === undefined);
  });
  it('execute 包 output 预算(超长 → truncated envelope)', async () => {
    const big = makeAgentTool({
      name: 'conversation_search',
      description: 'd',
      execute: (): Promise<UIMessagePart[]> => Promise.resolve([
        { type: 'text', text: 'x'.repeat(90000), metadata: null },
      ]),
    });
    const reg = createToolRegistry([big]);
    const out = await reg.tools()[0].execute({});
    const payload = JSON.parse((out[0] as UIMessagePartText).text);
    assert.equal(payload.status, 'truncated');
    assert.equal(payload.total_chars, 90000);
    assert.equal(payload.max_chars, 80000);
  });
});
