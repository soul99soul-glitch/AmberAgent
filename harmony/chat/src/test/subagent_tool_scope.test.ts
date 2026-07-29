// subagent_tool_scope.test.ts — D-132a Task 4 child-only tool scope
// Android baseline: SubAgentToolScopeTest.kt + SubAgentToolScope.kt.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import type { UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import { TOOL_SEARCH_TOOL_NAME } from '../main/ets/chat/builtin_introspection_tools.ts';
import { scopedSubAgentTools } from '../main/ets/chat/subagent_tool_scope.ts';

const textPayload = (parts: UIMessagePart[]): JsonObject => {
  const text: UIMessagePartText = parts[0] as UIMessagePartText;
  return JSON.parse(text.text) as JsonObject;
};

const jsonArray = (value: JsonValue | undefined): JsonValue[] => {
  assert.ok(Array.isArray(value));
  return value;
};

const tool = (
  name: string,
  description: string = 'test tool',
  schemaMarker: string = name,
): AgentTool => makeAgentTool({
  name,
  description,
  parameters: () => makeInputSchemaObj({
    marker: { type: 'string', description: schemaMarker },
  }),
  execute: (_input: JsonValue): Promise<UIMessagePart[]> => Promise.resolve([
    { type: 'text', text: 'ok', metadata: null },
  ]),
});

const parentDiscoveryTool = (name: string): AgentTool => makeAgentTool({
  name,
  description: `Parent full-catalog ${name}.`,
  parameters: () => makeInputSchemaObj({
    parent_secret_schema: { type: 'string', description: 'PARENT_ONLY_SCHEMA' },
  }),
  execute: (_input: JsonValue): Promise<UIMessagePart[]> => Promise.resolve([
    {
      type: 'text',
      text: JSON.stringify({ tools: [{ name: 'parent_secret' }] }),
      metadata: null,
    },
  ]),
});

const namesOf = (tools: AgentTool[]): string[] =>
  tools.map((candidate: AgentTool): string => candidate.name);

const listedToolNames = (payload: JsonObject): string[] =>
  jsonArray(payload['tools']).map((value: JsonValue): string => {
    const item: JsonObject = value as JsonObject;
    return item['name'] as string;
  });

describe('scopedSubAgentTools Android contracts', () => {
  it('scoped tool_search only sees allowed subagent tools', async () => {
    const fileRead: AgentTool = tool('file_read', 'Read workspace files.');
    const terminalExecute: AgentTool = tool(
      'terminal_execute', 'Run a shell command.', 'TERMINAL_CHILD_SCHEMA');

    const scoped: AgentTool[] = scopedSubAgentTools([fileRead, terminalExecute]);
    const search: AgentTool = scoped.find(
      (candidate: AgentTool): boolean => candidate.name === TOOL_SEARCH_TOOL_NAME) as AgentTool;
    const payload: JsonObject = textPayload(
      await search.execute({ query: 'terminal', limit: 5 }));

    assert.deepEqual(payload['expanded_tools'], ['terminal_execute']);
    assert.equal(scoped.some((candidate: AgentTool): boolean => candidate.name === 'file_read'), true);
    assert.equal(scoped.some((candidate: AgentTool): boolean => candidate.name === 'file_write'), false);
    assert.equal(JSON.stringify(payload).includes('TERMINAL_CHILD_SCHEMA'), true);
    assert.equal(JSON.stringify(payload).includes('PARENT_ONLY_SCHEMA'), false);
  });

  it('scoped tool_search replaces any parent search tool', async () => {
    const scoped: AgentTool[] = scopedSubAgentTools([
      tool('file_read'),
      parentDiscoveryTool(TOOL_SEARCH_TOOL_NAME),
    ]);

    assert.equal(namesOf(scoped).filter((name: string): boolean =>
      name === TOOL_SEARCH_TOOL_NAME).length, 1);
    const search: AgentTool = scoped.find(
      (candidate: AgentTool): boolean => candidate.name === TOOL_SEARCH_TOOL_NAME) as AgentTool;
    const payload: JsonObject = textPayload(await search.execute({ query: 'parent_secret' }));
    assert.deepEqual(payload['expanded_tools'], []);
    assert.deepEqual(payload['tools'], []);
  });

  it('scoped tools_list replaces the parent catalog and exposes child metadata only', async () => {
    const scoped: AgentTool[] = scopedSubAgentTools([
      tool('file_read', 'Read workspace files.', 'FILE_READ_CHILD_SCHEMA'),
      parentDiscoveryTool('tools_list'),
    ]);
    const listTool: AgentTool = scoped.find(
      (candidate: AgentTool): boolean => candidate.name === 'tools_list') as AgentTool;
    const payload: JsonObject = textPayload(
      await listTool.execute({ query: '', include_schema: true }));
    const listed: JsonValue[] = jsonArray(payload['tools']);
    const fileRead: JsonObject = listed[0] as JsonObject;

    assert.equal(payload['scoped'], true);
    assert.equal(payload['enabled_count'], 1);
    assert.deepEqual(listedToolNames(payload), ['file_read']);
    assert.equal(namesOf(scoped).filter((name: string): boolean => name === 'tools_list').length, 1);
    assert.equal(fileRead['category'], 'workspace');
    assert.equal(fileRead['description'], 'Read workspace files.');
    assert.equal(fileRead['enabled'], true);
    assert.equal(fileRead['mutates'], false);
    assert.equal(fileRead['sensitive_read'], false);
    assert.equal(fileRead['needs_approval'], false);
    assert.equal(fileRead['allows_auto_approval'], true);
    assert.equal(fileRead['risk'], 'Normal');
    assert.equal(typeof fileRead['concurrency_safe'], 'boolean');
    assert.equal(typeof fileRead['speculative_eligible'], 'boolean');
    assert.equal((fileRead['schema'] as string).includes('FILE_READ_CHILD_SCHEMA'), true);
    assert.equal(JSON.stringify(payload).includes('PARENT_ONLY_SCHEMA'), false);
    assert.equal(JSON.stringify(payload).includes('parent_secret'), false);
  });

  it('scoped tool_policy_explain cannot inspect a parent-only tool', async () => {
    const scoped: AgentTool[] = scopedSubAgentTools([
      tool('file_read'),
      parentDiscoveryTool('tool_policy_explain'),
    ]);
    const policyTool: AgentTool = scoped.find(
      (candidate: AgentTool): boolean => candidate.name === 'tool_policy_explain') as AgentTool;
    const payload: JsonObject = textPayload(
      await policyTool.execute({ tool_name: 'terminal_execute' }));

    assert.deepEqual(payload, {
      scoped: true,
      status: 'not_found',
      tool_name: 'terminal_execute',
    });
    assert.equal(namesOf(scoped).filter(
      (name: string): boolean => name === 'tool_policy_explain').length, 1);
  });
});

describe('scopedSubAgentTools isolation, order, and collision behavior', () => {
  it('removes public subagent tools and parent discovery candidates before appending replacements', () => {
    const terminalExecute: AgentTool = tool('terminal_execute');
    const fileRead: AgentTool = tool('file_read');
    const scoped: AgentTool[] = scopedSubAgentTools([
      terminalExecute,
      tool('subagent_start'),
      parentDiscoveryTool(TOOL_SEARCH_TOOL_NAME),
      fileRead,
      tool('subagent_wait'),
      parentDiscoveryTool('tools_list'),
      parentDiscoveryTool('tool_policy_explain'),
    ]);

    assert.deepEqual(namesOf(scoped), [
      'terminal_execute',
      'file_read',
      TOOL_SEARCH_TOOL_NAME,
      'tools_list',
      'tool_policy_explain',
    ]);
    assert.equal(scoped[0], terminalExecute);
    assert.equal(scoped[1], fileRead);
  });

  it('always adds scoped tool_search but only recreates list and policy when requested', () => {
    assert.deepEqual(namesOf(scopedSubAgentTools([tool('file_read')])), [
      'file_read', TOOL_SEARCH_TOOL_NAME,
    ]);
    assert.deepEqual(namesOf(scopedSubAgentTools([parentDiscoveryTool('tools_list')])), [
      TOOL_SEARCH_TOOL_NAME, 'tools_list',
    ]);
    assert.deepEqual(
      namesOf(scopedSubAgentTools([parentDiscoveryTool('tool_policy_explain')])),
      [TOOL_SEARCH_TOOL_NAME, 'tool_policy_explain'],
    );
  });

  it('returns no tools when no executable or requested discovery tools survive', () => {
    assert.deepEqual(scopedSubAgentTools([]), []);
    assert.deepEqual(scopedSubAgentTools([
      tool('subagent_start'), tool('subagent_read'),
    ]), []);
  });

  it('preserves ToolRegistry duplicate executable collision behavior', () => {
    assert.throws(
      () => scopedSubAgentTools([tool('file_read'), tool('file_read')]),
      (error: Error): boolean => error.message === 'Duplicate tool names registered: file_read',
    );
  });
});
