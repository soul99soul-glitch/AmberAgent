import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePartTool, UIMessagePartText } from '../main/ets/chat/message.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { toolInvocationPolicy, toolRiskProfile } from '../main/ets/chat/tool_policy.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { toolSandboxInputPreview } from '../main/ets/chat/tool_activity.ts';

const definition = (name: string) => makeAgentTool({
  name, description: 'Manage account settings', needsApproval: name !== 'provider_list',
  execute: async () => [],
});

const part = (name: string, input: string = '{}'): UIMessagePartTool => ({
  type: 'tool', toolCallId: 'provider-call', toolName: name, input, output: [],
  approvalState: { type: 'auto' }, metadata: null,
});

test('provider mutation and network credential use keep approval, concurrency and speculative policy accurate', () => {
  const list = toolInvocationPolicy(definition('provider_list'), null);
  assert.equal(list.category, 'provider');
  assert.equal(list.mutates, false);
  assert.equal(list.risk, 'normal');
  assert.equal(list.needsApproval, false);

  const configure = toolInvocationPolicy(definition('provider_configure'), null);
  assert.equal(configure.category, 'provider');
  assert.equal(configure.mutates, true);
  assert.equal(configure.risk, 'sensitive');
  assert.equal(configure.concurrencySafe, false);
  assert.equal(configure.speculativeEligible, false);
  assert.equal(configure.autoApprovable, true);

  const models = toolInvocationPolicy(definition('provider_models'), null);
  assert.equal(models.mutates, false);
  assert.equal(models.risk, 'sensitive');
  assert.equal(models.speculativeEligible, false);
  assert.deepEqual(toolRiskProfile('provider_models'), { risk: 'sensitive', explicit: true });

  const resolver = new PermissionDecisionResolver();
  for (const name of ['provider_configure', 'provider_models']) {
    assert.equal(resolver.resolve(definition(name), part(name), false, false).action, 'ask');
    assert.equal(resolver.resolve(definition(name), part(name), true, false).action, 'allow');
    assert.equal(resolver.resolve(definition(name), part(name), true, false, [], 'subagent').action, 'ask');
  }
});

test('provider tools are discoverable through Chinese and API-key aliases', async () => {
  const tools = ['provider_list', 'provider_configure', 'provider_models'].map(definition);
  const search = createToolSearchTool(createToolRegistry(tools));
  for (const query of ['服务商', '提供商', '接口配置', 'API key', '密钥', 'provider', '模型配置']) {
    const result = await search.execute({ query });
    const payload = JSON.parse((result[0] as UIMessagePartText).text);
    assert.ok(payload.expanded_tools.includes('provider_configure'), query);
  }
});

test('provider activity summaries never echo credential-bearing input or endpoint URLs', () => {
  const input = JSON.stringify({
    name: 'secret-in-name', provider_id: 'secret-in-id', api_key: 'secret-key',
    base_url: 'https://secret-user:secret-password@example.test?token=secret-query',
  });
  for (const name of ['provider_list', 'provider_configure', 'provider_models']) {
    const preview = toolSandboxInputPreview(part(name, input));
    assert.ok(preview.length > 0);
    assert.equal(preview.includes('secret'), false, name);
    assert.equal(preview.includes('{'), false, name);
    assert.equal(preview.includes('https://'), false, name);
  }
});
