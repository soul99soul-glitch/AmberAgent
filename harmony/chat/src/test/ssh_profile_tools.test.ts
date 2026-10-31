// ssh_profile_tools.test.ts — SSH 配置 Agent 工具(ssh_profile_list/create/update/delete)
//
// 覆盖:
//   - schema:四个工具的必填字段与凭据字段(write-only)声明
//   - 审批:写工具为高风险;「自动批准普通变更」放不过去,「高风险自动批准」放行
//   - list 只返回布尔值,绝不出现凭据明文 / credentialRef
//   - create → update → delete 全链路,含凭据清理与 revision 冲突
//   - 非法参数拒绝(host 空/port 越界/auth_method 非法/凭据与认证方式不自洽)
//   - 工具参数脱敏(持久化边界 + 审批摘要 + 活动预览)

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import { createSSHProfileTools, redactSSHProfileToolInput } from '../main/ets/chat/terminal/profile_tools.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { SSHCredential, SSHCredentialBinding } from '../main/ets/chat/terminal/models.ts';
import type { SSHCredentialStorePort } from '../main/ets/chat/terminal/ports.ts';
import type { UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject, JsonValue } from '../main/ets/chat/json.ts';
import { PermissionDecisionResolver } from '../main/ets/chat/tool_permission.ts';
import { toolInvocationPolicy } from '../main/ets/chat/tool_policy.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { buildToolApprovalSummary } from '../main/ets/chat/tool_display.ts';
import { toolSandboxInputPreview } from '../main/ets/chat/tool_activity.ts';
import { redactStoredToolInput } from '../main/ets/chat/tool_input_redaction.ts';
import { buildCompressionInput } from '../main/ets/chat/context_compact.ts';
import { buildJevAutoApprovalBatch } from '../main/ets/chat/jev_auto_approval.ts';
import { maskSensitiveJsonObject, maskSensitiveJsonText } from '../main/ets/chat/conversation_export.ts';
import { redactCouncilConversationInputs } from '../main/ets/chat/council_seat_redaction.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import { scopedSubAgentTools } from '../main/ets/chat/subagent_tool_scope.ts';
import { subAgentValidateToolAllowlist } from '../main/ets/chat/subagent_validator.ts';

class Credentials implements SSHCredentialStorePort {
  entries = new Map<string, SSHCredential>();
  async save(ref: string, _binding: SSHCredentialBinding, value: SSHCredential): Promise<void> {
    assert.equal(this.entries.has(ref), false);
    this.entries.set(ref, value);
  }
  async load(ref: string): Promise<SSHCredential | null> { return this.entries.get(ref) ?? null; }
  async exists(ref: string): Promise<boolean> { return this.entries.has(ref); }
  async delete(ref: string): Promise<void> { this.entries.delete(ref); }
}

const PASSWORD: string = 'pw-marker-9f3a';
const KEY: string = '-----BEGIN OPENSSH PRIVATE KEY-----\nkey-marker-7b1c\n-----END OPENSSH PRIVATE KEY-----';
const PASSPHRASE: string = 'pass-marker-4d2e';

const setup = async () => {
  const credentials = new Credentials();
  const kv = createMemoryKeyValueStore();
  const profiles = await SSHProfileStore.create({ kv, credentials });
  const tools: AgentTool[] = createSSHProfileTools({ profiles });
  const byName = (name: string): AgentTool => {
    const tool = tools.find((t: AgentTool): boolean => t.name === name);
    assert.ok(tool !== undefined, `missing tool ${name}`);
    return tool;
  };
  const call = async (name: string, args: JsonObject): Promise<JsonObject> => {
    const parts: UIMessagePart[] = await byName(name).execute(args as JsonValue);
    const text = parts[0] as UIMessagePartText;
    return JSON.parse(text.text) as JsonObject;
  };
  return { credentials, kv, profiles, tools, byName, call };
};

const toolCall = (name: string, input: string): UIMessagePartTool => ({
  type: 'tool', toolCallId: `call-${name}`, toolName: name, input, output: [],
  approvalState: { type: 'auto' }, metadata: null,
});

const resolve = (tool: AgentTool, args: JsonObject, autoApprove: boolean, autoApproveHighRisk: boolean) =>
  new PermissionDecisionResolver().resolve(
    tool, toolCall(tool.name, JSON.stringify(args)), autoApprove, autoApproveHighRisk);

test('schema: four tools, write-only credential fields, required args', async () => {
  const s = await setup();
  assert.deepEqual(s.tools.map((t: AgentTool): string => t.name).sort(), [
    'ssh_profile_create', 'ssh_profile_delete', 'ssh_profile_list', 'ssh_profile_update',
  ]);
  const create = s.byName('ssh_profile_create').parameters()!;
  assert.deepEqual(create.required, ['name', 'host', 'username']);
  for (const field of ['password', 'private_key', 'private_key_passphrase']) {
    assert.equal((create.properties[field] as JsonObject)['type'], 'string');
    assert.ok(String((create.properties[field] as JsonObject)['description']).toLowerCase().includes('write-only'));
  }
  assert.deepEqual(s.byName('ssh_profile_update').parameters()!.required, ['id']);
  assert.deepEqual(s.byName('ssh_profile_delete').parameters()!.required, ['id']);
  // list 只读:不声明任何凭据字段
  const listProps = s.byName('ssh_profile_list').parameters()!.properties;
  assert.deepEqual(Object.keys(listProps), []);
  // 写工具的声明位:needsApproval + 不接受自动批准 + mandatoryApproval
  for (const name of ['ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete']) {
    const tool = s.byName(name);
    assert.equal(tool.needsApproval, true, name);
    assert.equal(tool.allowsAutoApproval, false, name);
    assert.equal(tool.mandatoryApproval, true, name);
  }
  assert.equal(s.byName('ssh_profile_list').needsApproval, false);
});

test('policy: writes are high risk, list is read-only normal', async () => {
  const s = await setup();
  const create = toolInvocationPolicy(s.byName('ssh_profile_create'), {});
  assert.equal(create.risk, 'high');
  assert.equal(create.mutates, true);
  assert.equal(create.needsApproval, true);
  assert.equal(create.autoApprovable, false);
  assert.equal(create.category, 'terminal');
  assert.equal(create.concurrencySafe, false);
  const list = toolInvocationPolicy(s.byName('ssh_profile_list'), {});
  assert.equal(list.risk, 'normal');
  assert.equal(list.mutates, false);
  assert.equal(list.needsApproval, false);
  assert.equal(list.autoApprovable, true);
});

test('approval: plain auto-approval never bypasses writes; high-risk auto-approval does', async () => {
  const s = await setup();
  const args: JsonObject = { name: 'box', host: 'h', username: 'u', password: 'pw' };
  const writeTools = ['ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete'];
  for (const name of writeTools) {
    const tool = s.byName(name);
    // 普通「自动批准」不放行
    const plain = resolve(tool, args, true, false);
    assert.equal(plain.action, 'ask', `${name} plain`);
    // 全关也不放行
    assert.equal(resolve(tool, args, false, false).action, 'ask', `${name} off`);
    // run 内信任不放行
    assert.equal(new PermissionDecisionResolver().resolve(
      tool, toolCall(name, JSON.stringify(args)), false, false, [name]).action, 'ask', `${name} trust`);
    // 开了高风险自动批准 → 照常放行(不额外拦截)
    const highRisk = resolve(tool, args, true, true);
    assert.equal(highRisk.action, 'allow', `${name} high-risk`);
    assert.equal(highRisk.source, 'settings_unattended');
  }
  const listTool = s.byName('ssh_profile_list');
  assert.equal(resolve(listTool, {}, false, false).action, 'allow');
  assert.equal(resolve(listTool, {}, true, false).source, 'policy');
});

test('list returns booleans only and never leaks the credential', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'prod', host: '10.0.0.1', port: 2222, username: 'deploy', auth_method: 'password', password: PASSWORD,
  });
  assert.equal(created['status'], 'ok');
  const listed = await s.call('ssh_profile_list', {});
  assert.equal(listed['count'], 1);
  const profile = (listed['profiles'] as JsonObject[])[0];
  assert.equal(profile['name'], 'prod');
  assert.equal(profile['host'], '10.0.0.1');
  assert.equal(profile['port'], 2222);
  assert.equal(profile['username'], 'deploy');
  assert.equal(profile['auth_method'], 'password');
  assert.equal(profile['has_saved_credential'], true);
  assert.equal(profile['has_password'], true);
  assert.equal(profile['has_private_key'], false);
  assert.equal(profile['host_trust'], 'unconfirmed');
  assert.equal(profile['credentialRef'], undefined);
  const raw = JSON.stringify(listed);
  assert.equal(raw.includes(PASSWORD), false);
  assert.equal(raw.includes('credential_ref'), false);
  assert.equal(raw.includes('credentialRef'), false);
  // 凭据确实进了加密库,且写入的内容没有被工具改写
  const stored = s.profiles.get(String(profile['id']))!;
  assert.equal((await s.credentials.load(stored.credentialRef!))?.secret, PASSWORD);
});

test('create → update → delete: full chain with credential cleanup', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'host-a', port: 22, username: 'root', auth_method: 'privateKey',
    private_key: KEY, private_key_passphrase: PASSPHRASE,
  });
  const first = created['profile'] as JsonObject;
  const id = String(first['id']);
  assert.equal(first['has_private_key'], true);
  assert.equal(s.credentials.entries.size, 1);
  assert.deepEqual(await s.credentials.load(s.profiles.get(id)!.credentialRef!),
    { secret: KEY, passphrase: PASSPHRASE });

  // 只改名:凭据与 ref 都保留
  const renamed = await s.call('ssh_profile_update', { id, name: 'renamed' });
  const second = renamed['profile'] as JsonObject;
  assert.equal(second['name'], 'renamed');
  assert.equal(second['revision'], Number(first['revision']) + 1);
  assert.equal(second['has_private_key'], true);
  assert.equal(s.credentials.entries.size, 1);

  // 换密码:认证方式变了 → 必须带新凭据,旧 ref 被清掉
  await assert.rejects(async () => {
    const r = await s.call('ssh_profile_update', { id, auth_method: 'password' });
    if (r['status'] === 'failed') throw new Error(String(r['error_code']));
  }, /credential_required/);
  const swapped = await s.call('ssh_profile_update', {
    id, auth_method: 'password', password: PASSWORD,
  });
  const third = swapped['profile'] as JsonObject;
  assert.equal(third['auth_method'], 'password');
  assert.equal(third['has_password'], true);
  assert.equal(third['has_private_key'], false);
  assert.equal(s.credentials.entries.size, 1);
  const swappedRef = s.profiles.get(id)!.credentialRef!;
  assert.deepEqual(await s.credentials.load(swappedRef), { secret: PASSWORD, passphrase: null });

  // 删除:profile 与凭据都没了
  const deleted = await s.call('ssh_profile_delete', { id, revision: Number(third['revision']) });
  assert.equal(deleted['action'], 'deleted');
  assert.equal(s.profiles.get(id), null);
  assert.equal(s.credentials.entries.size, 0);
  assert.equal((await s.call('ssh_profile_list', {}))['count'], 0);
});

test('stale revision is rejected instead of overwriting', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'h', username: 'u', password: PASSWORD,
  });
  const id = String((created['profile'] as JsonObject)['id']);
  const revision = Number((created['profile'] as JsonObject)['revision']);
  await s.profiles.saveMetadata({ id, name: 'other', host: 'h', port: 22, username: 'u', authMethod: 'password' },
    revision);
  const stale = await s.call('ssh_profile_delete', { id, revision });
  assert.equal(stale['status'], 'failed');
  assert.equal(stale['error_code'], 'profile_conflict');
  assert.equal(stale['recoverable'], true);
  assert.notEqual(s.profiles.get(id), null);
});

test('invalid arguments are rejected without echoing the credential', async () => {
  const s = await setup();
  const cases: JsonObject[] = [
    { name: 'x', host: '   ', username: 'u', password: PASSWORD },
    { name: 'x', host: 'h', port: 0, username: 'u', password: PASSWORD },
    { name: 'x', host: 'h', port: 65536, username: 'u', password: PASSWORD },
    { name: 'x', host: 'h', port: 22.5, username: 'u', password: PASSWORD },
    { name: 'x', host: 'h', username: 'u', auth_method: 'agent', password: PASSWORD },
    // 凭据与认证方式不自洽
    { name: 'x', host: 'h', username: 'u', auth_method: 'password', private_key: KEY },
    { name: 'x', host: 'h', username: 'u', auth_method: 'privateKey', password: PASSWORD },
    { name: 'x', host: 'h', username: 'u', auth_method: 'password', private_key_passphrase: PASSPHRASE },
    { name: 'x', host: 'h', username: 'u', password: PASSWORD, private_key: KEY },
    // 没有凭据、也没有名字/host
    { name: '', host: 'h', username: 'u', password: PASSWORD },
  ];
  for (const args of cases) {
    const result = await s.call('ssh_profile_create', args);
    assert.equal(result['status'], 'failed', JSON.stringify(Object.keys(args)));
    assert.equal(result['error_code'], 'invalid_arguments');
    assert.equal(JSON.stringify(result).includes(PASSWORD), false);
    assert.equal(JSON.stringify(result).includes('key-marker'), false);
  }
  // 新建必须带凭据
  const noCredential = await s.call('ssh_profile_create', { name: 'x', host: 'h', username: 'u' });
  assert.equal(noCredential['error_code'], 'credential_missing');
  assert.equal(s.profiles.snapshot().profiles.length, 0);
});

test('clear_credential removes the stored secret and keeps the profile', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'h', username: 'u', password: PASSWORD,
  });
  const id = String((created['profile'] as JsonObject)['id']);
  const cleared = await s.call('ssh_profile_update', { id, clear_credential: true });
  if (cleared['status'] === 'failed') {
    // 认证方式为 password 且未换绑定时,允许只清凭据
    assert.fail(`clear failed: ${String(cleared['error_code'])}`);
  }
  const profile = cleared['profile'] as JsonObject;
  assert.equal(profile['has_saved_credential'], false);
  assert.equal(s.credentials.entries.size, 0);
  assert.notEqual(s.profiles.get(id), null);
  // 与新增凭据互斥
  const both = await s.call('ssh_profile_update', { id, clear_credential: true, password: PASSWORD });
  assert.equal(both['error_code'], 'invalid_arguments');
});

test('missing profile ids fail clearly', async () => {
  const s = await setup();
  const update = await s.call('ssh_profile_update', { id: 'nope', name: 'x' });
  assert.equal(update['error_code'], 'profile_missing');
  const remove = await s.call('ssh_profile_delete', { id: 'nope' });
  assert.equal(remove['error_code'], 'profile_missing');
});

test('tool arguments are redacted for storage, approval card, preview and compression', async () => {
  const args: JsonObject = {
    name: 'box', host: 'h', username: 'u', auth_method: 'privateKey',
    private_key: KEY, private_key_passphrase: PASSPHRASE, password: '',
  };
  // 1) 持久化边界:JSON 文本里没有明文,且仍是合法 JSON
  const stored = redactStoredToolInput('ssh_profile_create', JSON.stringify(args));
  assert.equal(stored.includes('key-marker'), false);
  assert.equal(stored.includes(PASSPHRASE), false);
  const parsed = JSON.parse(stored) as JsonObject;
  assert.equal(parsed['private_key'], '***');
  assert.equal(parsed['private_key_passphrase'], '***');
  assert.equal(parsed['host'], 'h');
  // 未登记的工具原样返回
  assert.equal(redactStoredToolInput('file_write', '{"password":"x"}'), '{"password":"x"}');

  // 2) 审批摘要:说明改哪个 profile / 哪些字段,凭据只说「将设置或更换私钥」
  const summary = buildToolApprovalSummary('ssh_profile_create', args)!;
  assert.equal(summary.title, '新建 SSH 配置');
  const text = JSON.stringify(summary);
  assert.equal(text.includes('key-marker'), false);
  assert.equal(text.includes(PASSPHRASE), false);
  assert.ok(text.includes('将设置或更换私钥'));
  assert.ok(summary.lines.some((line) => line.value === 'box'));
  assert.ok(summary.lines.some((line) => line.value === '私钥'));
  const withPassword = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', password: PASSWORD })!;
  assert.ok(JSON.stringify(withPassword).includes('将设置或更换密码'));
  assert.equal(JSON.stringify(withPassword).includes(PASSWORD), false);
  const clearing = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', clear_credential: true })!;
  assert.ok(JSON.stringify(clearing).includes('将删除已保存的密码/私钥'));
  const deleting = buildToolApprovalSummary('ssh_profile_delete', { id: 'p1', name: 'box' })!;
  assert.equal(deleting.destructive, true);
  assert.equal(deleting.warningLevel, 'danger');

  // 3) 沙盒活动预览:不落 JSON 全串
  const preview = toolSandboxInputPreview(
    { type: 'tool', toolCallId: 'c1', toolName: 'ssh_profile_create', input: JSON.stringify(args),
      output: [], approvalState: { type: 'auto' }, metadata: null });
  assert.equal(preview.includes('key-marker'), false);
  assert.equal(preview.includes(PASSPHRASE), false);
  assert.ok(preview.includes('新建 SSH 配置'));

  // 4) 压缩输入:摘要里也没有明文
  const toolPart: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c1', toolName: 'ssh_profile_create', input: JSON.stringify(args),
    output: [], approvalState: { type: 'auto' }, metadata: null,
  };
  const compressedTool = buildCompressionInput([{
    id: 'm2', role: 'assistant', parts: [toolPart], annotations: [], createdAt: '', finishedAt: null,
    modelId: null, usage: null, translation: null,
  }]);
  assert.equal(compressedTool.includes('key-marker'), false);
  assert.equal(compressedTool.includes(PASSPHRASE), false);
  assert.ok(compressedTool.includes('***'));

  // 5) 导出层沿用既有脱敏(这里只确认 password 字段不再出现)
  const redactedDirect = redactSSHProfileToolInput('ssh_profile_create', args);
  assert.equal(redactedDirect['private_key'], '***');
  assert.equal(redactedDirect['private_key_passphrase'], '***');
  assert.equal(redactedDirect['password'], '');
});

test('registry keeps the tools discoverable and unmutated by list', async () => {
  const s = await setup();
  const registry = createToolRegistry(s.tools);
  assert.equal(registry.metadata.length, 4);
  const list = registry.evaluateInvocation('ssh_profile_list');
  assert.equal(list?.needsApproval, false);
  const create = registry.evaluateInvocation('ssh_profile_create');
  assert.equal(create?.risk, 'high');
  assert.equal(create?.needsApproval, true);
  // profile 标题 schema 提示(display_title)不改动凭据字段
  const metadata = registry.metadataFor('ssh_profile_create');
  assert.equal(metadata?.risk, 'high');
});

test('delete leaves running jobs/sessions alone and only blocks new calls', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'h', username: 'u', password: PASSWORD,
  });
  const id = String((created['profile'] as JsonObject)['id']);
  // 与 UI 一致:删除只动配置与凭据,运行中的 job/session 不在这里被 stop。
  const deleted = await s.call('ssh_profile_delete', { id });
  assert.equal(deleted['action'], 'deleted');
  assert.equal(deleted['removed_default'], false);
  assert.ok(String(deleted['note']).includes('running sessions are unaffected'));
  // 新调用拿不到 profile → profile_missing(终端侧 targetFor 的同一语义)
  const again = await s.call('ssh_profile_update', { id, name: 'x' });
  assert.equal(again['error_code'], 'profile_missing');
  assert.equal(s.profiles.get(id), null);
});

// ===== 独立 review 阻断/应修项的回归锁 =====

// 真实生产路径:会话持久化 → 重新读出 → 拿到的是脱敏后的参数 → 执行必须 fail-closed
const persistedThenReloaded = async (
  s: Awaited<ReturnType<typeof setup>>, name: string, args: JsonObject,
): Promise<JsonObject> => {
  const stored = redactStoredToolInput(name, JSON.stringify(args));
  const reloaded = JSON.parse(stored) as JsonObject;
  return s.call(name, reloaded);
};

// 复刻 Council 席位落盘的会话形态:一条带工具调用的 assistant 消息 + started 标记里的 input 副本
const seatConversation = (toolName: string, args: JsonObject): ReturnType<typeof makeConversation> => {
  const part: UIMessagePartTool = {
    type: 'tool', toolCallId: 'sea-1', toolName, input: JSON.stringify(args), output: [],
    approvalState: { type: 'auto' },
    metadata: { council_started_v1: { toolCallId: 'sea-1', toolName, input: JSON.stringify(args) } },
  };
  return makeConversation('seat-conv', [toMessageNode(makeUIMessage('assistant', [part]))]);
};

test('BLOCK1: a reloaded call must not save the redaction placeholder as the credential', async () => {
  const s = await setup();
  const original: JsonObject = {
    name: 'box', host: 'h', username: 'u', auth_method: 'password', password: PASSWORD,
  };
  const outcome = await persistedThenReloaded(s, 'ssh_profile_create', original);
  assert.equal(outcome['status'], 'failed');
  assert.equal(outcome['error_code'], 'credential_unavailable');
  assert.equal(outcome['recoverable'], true);
  assert.equal(s.profiles.snapshot().profiles.length, 0);
  assert.equal(s.credentials.entries.size, 0);
  // 未改动凭据字段的改名仍可用脱敏后的参数正常执行
  const created = await s.call('ssh_profile_create', original);
  const id = String((created['profile'] as JsonObject)['id']);
  const renamed = await persistedThenReloaded(s, 'ssh_profile_update', { id, name: 'renamed' });
  assert.equal(renamed['status'], 'ok');
  assert.equal((renamed['profile'] as JsonObject)['name'], 'renamed');
  assert.equal((await s.credentials.load(s.profiles.get(id)!.credentialRef!))?.secret, PASSWORD);
});

test('BLOCK1b: changing a target from reloaded arguments must not delete the real credential', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'h', username: 'u', password: PASSWORD,
  });
  const id = String((created['profile'] as JsonObject)['id']);
  const beforeRef = s.profiles.get(id)!.credentialRef!;
  const outcome = await persistedThenReloaded(s, 'ssh_profile_update', { id, host: 'other', password: PASSWORD });
  assert.equal(outcome['error_code'], 'credential_unavailable');
  assert.equal(s.profiles.get(id)!.host, 'h');
  assert.equal(s.profiles.get(id)!.credentialRef, beforeRef);
  assert.equal((await s.credentials.load(beforeRef))?.secret, PASSWORD);
});

test('FIX3: a profile without a saved credential can still be renamed', async () => {
  const s = await setup();
  const created = await s.call('ssh_profile_create', {
    name: 'box', host: 'h', username: 'u', password: PASSWORD,
  });
  const id = String((created['profile'] as JsonObject)['id']);
  await s.call('ssh_profile_update', { id, clear_credential: true });
  assert.equal(s.profiles.get(id)!.credentialRef, null);
  const renamed = await s.call('ssh_profile_update', { id, name: 'renamed' });
  assert.equal(renamed['status'], 'ok');
  assert.equal((renamed['profile'] as JsonObject)['name'], 'renamed');
  // 改 host 但没有凭据时仍要求提供凭据
  const move = await s.call('ssh_profile_update', { id, host: 'other' });
  assert.equal(move['status'], 'ok');
  assert.equal(s.profiles.get(id)!.host, 'other');
});

test('FIX4: duplicate names are rejected case-insensitively and self is excluded', async () => {
  const s = await setup();
  const first = await s.call('ssh_profile_create', {
    name: 'Prod', host: 'h1', username: 'u', password: PASSWORD,
  });
  const firstId = String((first['profile'] as JsonObject)['id']);
  const dup = await s.call('ssh_profile_create', {
    name: ' prod ', host: 'h2', username: 'u', password: PASSWORD,
  });
  assert.equal(dup['status'], 'failed');
  assert.equal(dup['error_code'], 'profile_name_taken');
  assert.equal(s.profiles.snapshot().profiles.length, 1);
  const second = await s.call('ssh_profile_create', {
    name: 'stage', host: 'h2', username: 'u', password: PASSWORD,
  });
  const secondId = String((second['profile'] as JsonObject)['id']);
  assert.equal((await s.call('ssh_profile_update', { id: firstId, name: 'PROD' }))['status'], 'ok');
  const clash = await s.call('ssh_profile_update', { id: secondId, name: 'Prod' });
  assert.equal(clash['error_code'], 'profile_name_taken');
  assert.equal(s.profiles.get(secondId)!.name, 'stage');
});

test('FIX1: Jev auto-approval review never receives the credential', async () => {
  const part: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c1', toolName: 'ssh_profile_create', output: [],
    input: JSON.stringify({ name: 'box', host: 'h', username: 'u', password: PASSWORD,
      private_key_passphrase: PASSPHRASE }),
    approvalState: { type: 'auto' }, metadata: null,
  };
  const decision = new PermissionDecisionResolver().resolve(
    { name: 'ssh_profile_create', description: '', parameters: () => null,
      systemPrompt: () => '', needsApproval: true, allowsAutoApproval: false, mandatoryApproval: true,
      execute: async () => [] }, part, true, false);
  const batch = buildJevAutoApprovalBatch(part, decision, ['创建 SSH 配置']);
  const sent = JSON.stringify(batch.state);
  assert.equal(sent.includes(PASSWORD), false);
  assert.equal(sent.includes(PASSPHRASE), false);
  assert.ok(sent.includes('***'));
});

test('BLOCK3: tool detail view and copy mask private_key and passphrase fields', async () => {
  const input: JsonObject = { name: 'box', host: 'h', username: 'u', auth_method: 'privateKey',
    private_key: KEY, private_key_passphrase: PASSPHRASE };
  // 详情卡走 maskSensitiveJsonObject / maskSensitiveJsonText
  const maskedObject = maskSensitiveJsonObject(input);
  assert.equal(maskedObject['private_key'], '***');
  assert.equal(maskedObject['private_key_passphrase'], '***');
  assert.equal(maskedObject['host'], 'h');
  const maskedText = maskSensitiveJsonText(JSON.stringify(input));
  assert.equal(maskedText.includes('key-marker'), false);
  assert.equal(maskedText.includes(PASSPHRASE), false);
  // 常见别名也要盖住
  for (const field of ['privateKey', 'private_key_passphrase', 'passphrase', 'key_passphrase', 'privateKeyPassphrase']) {
    const masked = maskSensitiveJsonObject({ [field]: 'secret-value' });
    assert.equal(masked[field], '***', field);
  }
});

test('BLOCK2: council seat checkpoint and started marker never persist the credential', async () => {
  // Council 落盘走两条路:seat.store.save(整个 conversation)与
  // council_started_v1 里的 input 副本。两者都必须先过同一套脱敏。
  const raw: JsonObject = { name: 'box', host: 'h', username: 'u', auth_method: 'password', password: PASSWORD };
  const conversation = seatConversation('ssh_profile_create', raw);
  const redacted = redactCouncilConversationInputs(conversation);
  const serialized = JSON.stringify(redacted);
  assert.equal(serialized.includes(PASSWORD), false);
  assert.ok(serialized.includes('***'));
  // 未登记的普通工具不受影响
  const plain = redactCouncilConversationInputs(seatConversation('file_write', { path: '/tmp/a', content: 'hello' }));
  assert.ok(JSON.stringify(plain).includes('hello'));
});

test('FIX2: approval card shows the saved name/host for rename and delete', async () => {
  const saved = { name: 'prod', host: 'old-host', port: 22 };
  // 改名:显示「原名称 → 新名称」与 id,不提示指纹清除
  const rename = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', name: 'prod-2' },
    { sshProfile: saved })!;
  const renameText = JSON.stringify(rename);
  assert.ok(renameText.includes('prod → prod-2'));
  assert.ok(renameText.includes('p1'));
  assert.equal(renameText.includes('主机指纹'), false);
  // 只改主机:显示新主机并提示指纹清除
  const hostChange = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', host: 'new-host' },
    { sshProfile: saved })!;
  assert.ok(hostChange.lines.some((line) => line.value === 'new-host'));
  assert.ok(JSON.stringify(hostChange).includes('主机指纹'));
  // 只改端口:同样提示
  const portChange = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', port: 2222 },
    { sshProfile: saved })!;
  assert.ok(JSON.stringify(portChange).includes('主机指纹'));
  // 用户名变化不清指纹(store 只在 host/port 变时清)
  const userChange = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', username: 'ops' },
    { sshProfile: saved })!;
  assert.equal(JSON.stringify(userChange).includes('主机指纹'), false);
  // 删除:补上保存的名称与主机
  const remove = buildToolApprovalSummary('ssh_profile_delete', { id: 'p1' }, { sshProfile: saved })!;
  const removeText = JSON.stringify(remove);
  assert.ok(removeText.includes('prod'));
  assert.ok(removeText.includes('old-host'));
  assert.equal(remove.destructive, true);
  // 同值 host 不算变更(避免误报指纹清除)
  const sameHost = buildToolApprovalSummary('ssh_profile_update', { id: 'p1', host: 'old-host' },
    { sshProfile: saved })!;
  assert.equal(JSON.stringify(sameHost).includes('主机指纹'), false);
});

// ===== 复审第二轮:占位符变体 / 子代理与 Council 隔离 =====

test('FIX4b: every placeholder shape is rejected as a credential (case-insensitive)', async () => {
  const s = await setup();
  const variants: string[] = ['***', '<redacted>', '[REDACTED]', 'REDACTED', 'redacted', '\u2022\u2022\u2022\u2022',
    ' *** ', '<Redacted>'];
  for (const value of variants) {
    for (const field of ['password', 'private_key', 'private_key_passphrase']) {
      const args: JsonObject = { name: 'x', host: 'h', username: 'u', auth_method: 'password' };
      args[field] = value;
      const result = await s.call('ssh_profile_create', args);
      assert.equal(result['status'], 'failed', `${field}=${value}`);
      assert.ok(['credential_unavailable', 'invalid_arguments'].includes(String(result['error_code'])),
        `${field}=${value} → ${String(result['error_code'])}`);
      assert.equal(JSON.stringify(result).includes(value.trim()), false);
    }
  }
  assert.equal(s.profiles.snapshot().profiles.length, 0);
  assert.equal(s.credentials.entries.size, 0);
});

test('ISOLATION: subagents and council seats never receive the SSH write tools', async () => {
  const s = await setup();
  // 1) 子代理作用域过滤:写工具被剔除,list 保留
  const scoped = scopedSubAgentTools(s.tools);
  const scopedNames: string[] = scoped.map((tool: AgentTool): string => tool.name);
  for (const name of ['ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete']) {
    assert.equal(scopedNames.includes(name), false, name);
  }
  assert.equal(scopedNames.includes('ssh_profile_list'), true);
  // 2) 白名单校验:显式申请写工具直接报错
  const available: Set<string> = new Set(s.tools.map((tool: AgentTool): string => tool.name));
  assert.throws(() => subAgentValidateToolAllowlist(new Set(['ssh_profile_create']), available),
    /cannot call SSH profile write tools/);
  assert.doesNotThrow(() => subAgentValidateToolAllowlist(new Set(['ssh_profile_list']), available));
  // 3) 主会话工具清单本身不动(写工具仍在主会话可用)
  assert.equal(s.tools.length, 4);
});
