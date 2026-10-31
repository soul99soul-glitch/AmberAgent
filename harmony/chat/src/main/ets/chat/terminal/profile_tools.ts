// profile_tools — SSH 配置的 Agent 工具(列出 / 新建 / 修改 / 删除)
//
// 设计边界:
//   - list 只读:只返回 id/名称/host/port/用户名/认证方式/key_id/jump_profile_id/route +
//     「是否已存凭据」布尔 + 主机指纹是否已确认;绝不返回 credentialRef 或任何凭据明文。
//   - create/update/delete 是写操作:risk=high + mandatoryApproval,因此
//     「自动批准普通变更」放不过去;用户开了「高风险自动批准」时按
//     PermissionDecisionResolver 既有语义照常放行(本模块不额外拦截)。
//   - 凭据只写不读:password / private_key / private_key_passphrase / passphrase 只进内存与
//     加密凭据库;返回值、日志、卡片都只说「已设置/已清除」。
//     对话记录里的参数脱敏由 redactSSHProfileToolInput 提供(接在保存与展示链路上)。
//   - 主机指纹:换了 host/port/jump_profile_id 的配置会清空 knownHost,结果里
//     host_trust=unconfirmed 提示用户需确认一次指纹后才能连接。

import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { InputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { SSHAuthMethod, SSHCredential, SSHKey, SSHProfile, SSHProfileDraft } from './models.ts';
import type { SSHKeyStore } from './key_store.ts';
import { SSH_KEY_TOOL_NAMES, SSH_PROFILE_TOOL_NAMES, SSH_PROFILE_SECRET_FIELDS } from './ssh_tool_contract.ts';
export { SSH_PROFILE_TOOL_NAMES, SSH_PROFILE_SECRET_FIELDS, SSH_PROFILE_MAIN_SESSION_ONLY_TOOLS,
  isSSHProfileMainSessionOnly } from './ssh_tool_contract.ts';
import type { SSHProfileStore } from './profile_store.ts';
import { TerminalError, normalizeDraft, sameBinding } from './profile_store.ts';
import { buildRouteHopJsonList, isProfileHostTrusted, resolveSSHRoute } from './route_resolver.ts';
import { newId } from '../ids.ts';
import { isRedactedPlaceholder } from '../tool_input_redaction.ts';

export interface SSHProfileToolsDeps {
  profiles: SSHProfileStore;
  keys?: SSHKeyStore;
}

const textResult = (value: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(value), metadata: null }];

const failure = (code: string, message: string, recoverable: boolean, details: JsonObject | null = null): UIMessagePart[] => {
  const payload: JsonObject = { ok: false, status: 'failed', error_code: code, message, recoverable };
  if (details !== null) {
    for (const k of Object.keys(details)) {
      payload[k] = details[k];
    }
  }
  return textResult(payload);
};

const objectArg = (input: JsonValue): JsonObject => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) {
    throw new TerminalError('invalid_arguments');
  }
  return input as JsonObject;
};

const stringArg = (j: JsonObject, key: string): string | null => {
  const value: JsonValue | undefined = j[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') throw new TerminalError('invalid_arguments');
  return value;
};

const hasExplicitNullOrEmpty = (j: JsonObject, key: string): boolean =>
  Object.prototype.hasOwnProperty.call(j, key) &&
  (j[key] === null || (typeof j[key] === 'string' && (j[key] as string).trim().length === 0));

const requiredString = (j: JsonObject, key: string): string => {
  const value: string | null = stringArg(j, key);
  if (value === null || value.trim().length === 0) throw new TerminalError('invalid_arguments');
  return value;
};

const numberArg = (j: JsonObject, key: string): number | null => {
  const value: JsonValue | undefined = j[key];
  if (value === undefined || value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new TerminalError('invalid_arguments');
  return value;
};

const booleanArg = (j: JsonObject, key: string): boolean => {
  const value: JsonValue | undefined = j[key];
  return value === true || value === 'true';
};

const authMethodArg = (j: JsonObject): SSHAuthMethod | null => {
  const value: JsonValue | undefined = j['auth_method'];
  if (value === undefined || value === null || value === '') return null;
  if (value === 'key' || value === 'private_key') return 'privateKey';
  if (value !== 'password' && value !== 'privateKey') throw new TerminalError('invalid_arguments');
  return value;
};

/**
 * 把凭据字段换成占位,供对话记录 / 卡片 / 日志复用。
 * 同时覆盖 ssh_profile_* 与 ssh_key_import。
 */
export const redactSSHProfileToolInput = (toolName: string, input: JsonObject): JsonObject => {
  if (SSH_PROFILE_TOOL_NAMES.indexOf(toolName) < 0 && SSH_KEY_TOOL_NAMES.indexOf(toolName) < 0) return input;
  const out: JsonObject = {};
  for (const key of Object.keys(input)) {
    const value: JsonValue = input[key];
    out[key] = SSH_PROFILE_SECRET_FIELDS.indexOf(key) >= 0 && typeof value === 'string' && value.length > 0
      ? '***' : value;
  }
  return out;
};

const looksRedacted = (value: string | null): boolean => isRedactedPlaceholder(value);

const credentialFor = (
  j: JsonObject,
  authMethod: SSHAuthMethod,
  hasKeyId: boolean,
  clear: boolean,
): SSHCredential | null => {
  const password: string | null = stringArg(j, 'password');
  const privateKey: string | null = stringArg(j, 'private_key');
  const passphrase: string | null = stringArg(j, 'private_key_passphrase') ?? stringArg(j, 'passphrase');
  const hasPassword: boolean = password !== null && password.length > 0;
  const hasKey: boolean = privateKey !== null && privateKey.length > 0;
  const hasPassphrase: boolean = passphrase !== null && passphrase.length > 0;
  if (looksRedacted(password) || looksRedacted(privateKey) || looksRedacted(passphrase)) {
    throw new TerminalError('credential_unavailable');
  }
  if (hasPassword && hasKey) throw new TerminalError('invalid_arguments');
  if (hasKeyId && (hasPassword || hasKey)) throw new TerminalError('invalid_arguments');
  if (authMethod === 'password' && (hasKey || hasPassphrase || hasKeyId)) throw new TerminalError('invalid_arguments');
  if (authMethod === 'privateKey' && hasPassword) throw new TerminalError('invalid_arguments');
  if (clear && (hasPassword || hasKey || hasPassphrase || hasKeyId)) throw new TerminalError('invalid_arguments');
  let secret: string | null = null;
  if (authMethod === 'password' && hasPassword) secret = password;
  else if (authMethod === 'privateKey' && hasKey) secret = privateKey;
  if (secret === null) return null;
  if (secret.indexOf('\0') >= 0) throw new TerminalError('invalid_arguments');
  return { secret, passphrase: authMethod === 'privateKey' && hasPassphrase ? passphrase : null };
};

const failureFor = (error: unknown, fallback: string): UIMessagePart[] => {
  if (error instanceof TerminalError) {
    const messages: Record<string, string> = {
      profile_conflict: 'Profile changed since it was read. Call ssh_profile_list and retry.',
      profile_missing: 'No saved SSH profile with that id.',
      invalid_arguments: 'Invalid arguments: check name, host, port (1-65535), username, auth_method, key_id, jump_profile_id and credential fields.',
      credential_required: 'Changing host, port, username or auth method needs the new credential or key_id.',
      credential_missing: 'No usable credential is stored for this profile; provide a password, key_id, or private key.',
      credential_unavailable: 'The stored arguments no longer contain the credential. Ask the user for the password '
        + 'or private key again; the placeholder cannot be saved.',
      profile_name_taken: 'Another SSH profile already uses that name. Pick a different name.',
      key_missing: 'No saved SSH key with that key_id. Call ssh_key_list or ssh_key_generate first.',
      jump_profile_not_found: 'The specified jump_profile_id does not exist.',
      jump_cycle: 'The jump_profile_id configuration forms a cycle.',
      jump_chain_too_long: 'Maximum 3 jump hosts allowed (4 total hops).',
      in_use_as_jump: 'This profile is referenced as jump_profile_id by other profiles and cannot be deleted until those references are removed.',
    };
    const message: string = messages[error.code] ?? error.message ?? `Remote SSH: ${error.code}`;
    const recoverable: boolean = [
      'profile_conflict', 'profile_missing', 'profile_name_taken',
      'credential_missing', 'credential_required', 'credential_unavailable',
      'key_missing', 'jump_profile_not_found', 'jump_cycle', 'jump_chain_too_long', 'in_use_as_jump',
    ].includes(error.code);
    return failure(error.code, message, recoverable, error.details);
  }
  return failure('profile_write_failed', fallback, false);
};

export const createSSHProfileTools = (deps: SSHProfileToolsDeps): AgentTool[] => {
  const describe = async (p: SSHProfile): Promise<JsonObject> => {
    const settings = deps.profiles.snapshot();
    const keyId: string | null = p.keyId ?? null;
    const keyRecord: SSHKey | null = (keyId !== null && deps.keys !== undefined) ? deps.keys.get(keyId) : null;
    const hasInlineCred: boolean = await deps.profiles.credentialExists(p.credentialRef);
    const hasKeyCred: boolean = keyId !== null && (deps.keys !== undefined ? await deps.keys.credentialExists(keyId) : false);
    const hasCredential: boolean = hasInlineCred || hasKeyCred;
    let routeJson: JsonObject[] = [];
    let routeReady: boolean = false;
    try {
      const resolved: SSHProfile[] = resolveSSHRoute(p.id, settings.profiles);
      routeJson = buildRouteHopJsonList(resolved);
      routeReady = true;
      for (const hop of resolved) {
        const credentialReady: boolean = hop.keyId ? (deps.keys !== undefined && await deps.keys.credentialExists(hop.keyId))
          : await deps.profiles.credentialExists(hop.credentialRef);
        if (!isProfileHostTrusted(hop) || !credentialReady) routeReady = false;
      }
    } catch {
      routeJson = [];
      routeReady = false;
    }
    const hostTrusted: boolean = isProfileHostTrusted(p);
    return {
      id: p.id,
      name: p.name,
      host: p.host,
      port: p.port,
      username: p.username,
      auth_method: p.authMethod,
      key_id: keyId,
      key_fingerprint: keyRecord?.fingerprintSHA256 ?? null,
      jump_profile_id: p.jumpProfileId ?? null,
      is_default: p.id === settings.defaultProfileId,
      has_saved_credential: hasCredential,
      has_password: p.authMethod === 'password' && hasInlineCred,
      has_private_key: p.authMethod === 'privateKey' && hasCredential,
      host_trust: hostTrusted ? 'confirmed' : 'unconfirmed',
      route: routeJson,
      route_ready: routeReady && hasCredential,
      revision: p.revision,
    };
  };

  const draftFor = (j: JsonObject, existing: SSHProfile | null): SSHProfileDraft => {
    const explicitKeyId: string | null = stringArg(j, 'key_id');
    const clearKeyId: boolean = hasExplicitNullOrEmpty(j, 'key_id') || booleanArg(j, 'clear_credential');
    const explicitJumpId: string | null = stringArg(j, 'jump_profile_id');
    const clearJumpId: boolean = hasExplicitNullOrEmpty(j, 'jump_profile_id');

    let keyId: string | null | undefined = existing?.keyId;
    if (clearKeyId) keyId = null;
    else if (explicitKeyId !== null && explicitKeyId.trim().length > 0) keyId = explicitKeyId.trim();

    let jumpProfileId: string | null | undefined = existing?.jumpProfileId;
    if (clearJumpId) jumpProfileId = null;
    else if (explicitJumpId !== null && explicitJumpId.trim().length > 0) jumpProfileId = explicitJumpId.trim();

    const explicitAuth: SSHAuthMethod | null = authMethodArg(j);
    if (explicitKeyId !== null && explicitKeyId.trim().length > 0 && explicitAuth === 'password') {
      throw new TerminalError('invalid_arguments');
    }
    if ((explicitAuth === 'password' || typeof j['private_key'] === 'string') && explicitKeyId === null) {
      keyId = null;
    }
    const resolvedAuth: SSHAuthMethod = explicitAuth ??
      ((explicitKeyId !== null && explicitKeyId.trim().length > 0) ? 'privateKey' : (existing?.authMethod ?? 'password'));

    if (keyId !== undefined && keyId !== null && (deps.keys === undefined || deps.keys.get(keyId) === null)) {
      throw new TerminalError('key_missing');
    }

    const rawDraft: SSHProfileDraft = {
      id: existing === null ? newId() : existing.id,
      name: stringArg(j, 'name') ?? existing?.name ?? '',
      host: stringArg(j, 'host') ?? existing?.host ?? '',
      port: numberArg(j, 'port') ?? existing?.port ?? 22,
      username: stringArg(j, 'username') ?? existing?.username ?? '',
      authMethod: resolvedAuth,
    };
    if (keyId !== undefined) rawDraft.keyId = keyId;
    if (jumpProfileId !== undefined) rawDraft.jumpProfileId = jumpProfileId;
    return normalizeDraft(rawDraft);
  };

  const assertNameAvailable = (draft: SSHProfileDraft): void => {
    const wanted: string = draft.name.trim().toLowerCase();
    for (const other of deps.profiles.snapshot().profiles) {
      if (other.id === draft.id) continue;
      if (other.name.trim().toLowerCase() === wanted) throw new TerminalError('profile_name_taken');
    }
  };

  const apply = async (j: JsonObject, existing: SSHProfile | null): Promise<SSHProfile> => {
    const clear: boolean = booleanArg(j, 'clear_credential');
    const explicitKeyId: string | null = stringArg(j, 'key_id');
    const hasNewKeyId: boolean = explicitKeyId !== null && explicitKeyId.trim().length > 0;
    const draft: SSHProfileDraft = draftFor(j, existing);
    assertNameAvailable(draft);
    let credential: SSHCredential | null = credentialFor(j, draft.authMethod, hasNewKeyId, clear);
    let imported: SSHKey | null = null;
    if (draft.authMethod === 'privateKey' && credential !== null && deps.keys !== undefined) {
      imported = await deps.keys.importKey(`${draft.name.slice(0, 40)}-imported-${newId().slice(0, 8)}`, credential.secret, credential.passphrase);
      draft.keyId = imported.id;
      credential = null;
    }
    const expected: number | null = existing === null ? null : existing.revision;
    const hasEffectiveKeyId: boolean = draft.authMethod === 'privateKey' &&
      draft.keyId !== undefined && draft.keyId !== null;
    try {
      if (existing === null) {
        if (credential === null && !hasEffectiveKeyId) throw new TerminalError('credential_missing');
        return await deps.profiles.saveWithCredential(draft, null, credential, false);
      }
      if (credential !== null || hasNewKeyId || imported !== null || hasEffectiveKeyId) {
        return await deps.profiles.saveWithCredential(draft, expected, credential, false);
      }
      if (!clear && !sameBinding(existing, draft) && (existing.credentialRef !== null || (existing.keyId ?? null) !== null)) {
        throw new TerminalError('credential_required', 'Changing host, port, username or auth method needs the new credential');
      }
      if (clear) return await deps.profiles.saveWithCredential(draft, expected, null, true);
      return await deps.profiles.saveMetadata(draft, expected);
    } catch (error) {
      if (imported !== null && deps.keys !== undefined) await deps.keys.deleteKey(imported.id);
      throw error;
    }
  };

  const secretProperties: JsonObject = {
    key_id: {
      type: 'string',
      description: 'ID of a local SSH key from ssh_key_list or ssh_key_generate (preferred for privateKey auth).',
    },
    jump_profile_id: {
      type: ['string', 'null'],
      description: 'Null/empty string clears the route. Optional ID of a jump/bastion SSH profile (ProxyJump). Up to 3 jump hosts supported.',
    },
    password: { type: 'string', description: 'Password. Write-only: stored in the encrypted store, never returned or logged.' },
    private_key: { type: 'string', description: 'OpenSSH/PEM private key. Write-only.' },
    passphrase: { type: 'string', description: 'Private key passphrase. Write-only.' },
    private_key_passphrase: { type: 'string', description: 'Passphrase for the private key. Write-only.' },
  };

  return [
    makeAgentTool({
      name: 'ssh_profile_list',
      description: 'List saved SSH profiles: id, name, host, port, username, auth method, key_id, key_fingerprint, '
        + 'jump_profile_id, resolved multi-hop route, route_ready, whether a credential is stored (booleans only), '
        + 'whether the host key is confirmed, and the revision. Read-only; never returns passwords, private keys or passphrases.',
      needsApproval: false,
      allowsAutoApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({}),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        try {
          objectArg(input);
          const settings = deps.profiles.snapshot();
          const profiles: JsonObject[] = [];
          for (const profile of settings.profiles) {
            profiles.push(await describe(profile));
          }
          return textResult({ status: 'ok', count: profiles.length,
            default_profile_id: settings.defaultProfileId, profiles });
        } catch {
          return failure('profile_read_failed', 'Saved SSH profiles could not be read.', false);
        }
      },
    }),
    makeAgentTool({
      name: 'ssh_profile_create',
      description: 'Create an SSH profile with password auth, a saved local SSH key (key_id from ssh_key_generate / '
        + 'ssh_key_list), or an inline private key, and optionally chain through a bastion/jump host via jump_profile_id '
        + '(ProxyJump, up to 3 jump hosts). When the user has no SSH key yet, call ssh_key_generate first, give the '
        + 'returned public_key to the user to add to ~/.ssh/authorized_keys, then call ssh_profile_create with key_id. '
        + 'Credentials are write-only: never repeat them in your reply. High risk: needs explicit approval.',
      needsApproval: true,
      allowsAutoApproval: false,
      mandatoryApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({
        name: { type: 'string', description: 'Profile name shown in Settings.' },
        host: { type: 'string', description: 'Host name or IP address.' },
        port: { type: 'number', description: 'SSH port, 1-65535. Defaults to 22.' },
        username: { type: 'string' },
        auth_method: { type: 'string', enum: ['password', 'key', 'private_key', 'privateKey'], description: 'Defaults to password (or privateKey when key_id is set).' },
        ...secretProperties,
      }, ['name', 'host', 'username']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        try {
          const saved: SSHProfile = await apply(objectArg(input), null);
          return textResult({ status: 'ok', action: 'created', profile: await describe(saved),
            note: 'Confirm the host-key fingerprint in Settings before connecting.' });
        } catch (error) {
          return failureFor(error, 'SSH profile could not be created.');
        }
      },
    }),
    makeAgentTool({
      name: 'ssh_profile_update',
      description: 'Update a saved SSH profile: rename it, change host/port/username/auth method, bind a local SSH key '
        + '(key_id), set or clear jump_profile_id (ProxyJump), replace its password or private key, or clear the stored '
        + 'credential. Changing host, port, or jump_profile_id clears the confirmed host key. Credentials are write-only. '
        + 'High risk: needs explicit approval.',
      needsApproval: true,
      allowsAutoApproval: false,
      mandatoryApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({
        id: { type: 'string', description: 'Profile id from ssh_profile_list.' },
        name: { type: 'string' },
        host: { type: 'string' },
        port: { type: 'number', description: 'SSH port, 1-65535.' },
        username: { type: 'string' },
        auth_method: { type: 'string', enum: ['password', 'key', 'private_key', 'privateKey'] },
        ...secretProperties,
        clear_credential: { type: 'boolean', description: 'Delete the stored password/private key without replacing it.' },
      }, ['id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        try {
          const j: JsonObject = objectArg(input);
          const existing: SSHProfile | null = deps.profiles.get(requiredString(j, 'id'));
          if (existing === null) throw new TerminalError('profile_missing');
          const saved: SSHProfile = await apply(j, existing);
          return textResult({ status: 'ok', action: 'updated', profile: await describe(saved),
            note: !isProfileHostTrusted(saved)
              ? 'The host key is no longer confirmed; confirm the fingerprint in Settings before connecting.'
              : 'Profile saved.' });
        } catch (error) {
          return failureFor(error, 'SSH profile could not be updated.');
        }
      },
    }),
    makeAgentTool({
      name: 'ssh_profile_delete',
      description: 'Delete a saved SSH profile and its stored password/private key. Rejected with in_use_as_jump if '
        + 'other profiles reference this profile as jump_profile_id. Terminal jobs and sessions that already started '
        + 'keep running, but new calls on that profile fail with profile_missing. High risk: needs explicit approval.',
      needsApproval: true,
      allowsAutoApproval: false,
      mandatoryApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({
        id: { type: 'string', description: 'Profile id from ssh_profile_list.' },
        revision: { type: 'number', description: 'Optional revision from ssh_profile_list; the delete is rejected if the profile has changed.' },
      }, ['id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        try {
          const j: JsonObject = objectArg(input);
          const id: string = requiredString(j, 'id');
          const current: SSHProfile | null = deps.profiles.get(id);
          if (current === null) throw new TerminalError('profile_missing');
          const wasDefault: boolean = deps.profiles.snapshot().defaultProfileId === id;
          await deps.profiles.deleteProfile(id, numberArg(j, 'revision') ?? current.revision);
          return textResult({ status: 'ok', action: 'deleted', id, name: current.name,
            removed_default: wasDefault,
            note: 'Stored credential removed with the profile; already running sessions are unaffected.' });
        } catch (error) {
          return failureFor(error, 'SSH profile could not be deleted.');
        }
      },
    }),
  ];
};
