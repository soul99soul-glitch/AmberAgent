// profile_tools — SSH 配置的 Agent 工具(列出 / 新建 / 修改 / 删除)
//
// 设计边界:
//   - list 只读:只返回 id/名称/host/port/用户名/认证方式 + 「是否已存凭据」布尔 +
//     主机指纹是否已确认;绝不返回 credentialRef 或任何凭据明文。
//   - create/update/delete 是写操作:risk=high + mandatoryApproval,因此
//     「自动批准普通变更」放不过去;用户开了「高风险自动批准」时按
//     PermissionDecisionResolver 既有语义照常放行(本模块不额外拦截)。
//   - 凭据只写不读:password / private_key / private_key_passphrase 只进内存与
//     加密凭据库;返回值、日志、卡片都只说「已设置/已清除」。
//     对话记录里的参数脱敏由 redactSSHProfileToolInput 提供(接在保存与展示链路上)。
//   - 主机指纹:模型无法探测未知主机,所以换了 host/port/用户名/认证方式的配置会清空
//     knownHost(沿用 SSHProfileStore 语义),结果里 host_trust=unconfirmed 提示用户
//     需在设置页完成一次指纹确认后才能连接。

import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { InputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { SSHAuthMethod, SSHCredential, SSHProfile, SSHProfileDraft } from './models.ts';
import type { SSHProfileStore } from './profile_store.ts';
import { TerminalError, normalizeDraft, sameBinding } from './profile_store.ts';
import { newId } from '../ids.ts';
import { isRedactedPlaceholder } from '../tool_input_redaction.ts';

export const SSH_PROFILE_TOOL_NAMES: string[] = [
  'ssh_profile_list', 'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete',
];

/**
 * 只留给主会话的 SSH 配置工具:增/改/删会写保存的凭据并改变连接目标。
 * 子代理与 Council 席位只拿 list(只读),写操作一律在主会话里由用户当面批准。
 */
export const SSH_PROFILE_MAIN_SESSION_ONLY_TOOLS: string[] = [
  'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete',
];

export const isSSHProfileMainSessionOnly = (toolName: string): boolean =>
  SSH_PROFILE_MAIN_SESSION_ONLY_TOOLS.indexOf(toolName) >= 0;

/** 工具参数里承载凭据的字段名(供对话记录脱敏复用) */
export const SSH_PROFILE_SECRET_FIELDS: string[] = ['password', 'private_key', 'private_key_passphrase'];

export interface SSHProfileToolsDeps {
  // 凭据库不单独注入:store 只对外暴露 credentialExists,工具层无法绕过
  // revision/清理约束直接读写凭据。
  profiles: SSHProfileStore;
}

const textResult = (value: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(value), metadata: null }];

const failure = (code: string, message: string, recoverable: boolean): UIMessagePart[] =>
  textResult({ status: 'failed', error_code: code, message, recoverable });

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
  if (value !== 'password' && value !== 'privateKey') throw new TerminalError('invalid_arguments');
  return value;
};

/** 已保存配置的对外摘要:凭据只有布尔值。 */
const profileJson = (p: SSHProfile, defaultId: string | null, hasCredential: boolean): JsonObject => ({
  id: p.id, name: p.name, host: p.host, port: p.port, username: p.username,
  auth_method: p.authMethod, is_default: p.id === defaultId,
  has_saved_credential: hasCredential,
  has_password: p.authMethod === 'password' && hasCredential,
  has_private_key: p.authMethod === 'privateKey' && hasCredential,
  host_trust: p.knownHostSHA256 === null ? 'unconfirmed' : 'confirmed',
  revision: p.revision,
});

/**
 * 把凭据字段换成占位,供对话记录 / 卡片 / 日志复用。
 * 只处理本工具自己的参数形状;其他工具的输入原样返回(不重构现有脱敏)。
 */
export const redactSSHProfileToolInput = (toolName: string, input: JsonObject): JsonObject => {
  if (SSH_PROFILE_TOOL_NAMES.indexOf(toolName) < 0) return input;
  const out: JsonObject = {};
  for (const key of Object.keys(input)) {
    const value: JsonValue = input[key];
    out[key] = SSH_PROFILE_SECRET_FIELDS.indexOf(key) >= 0 && typeof value === 'string' && value.length > 0
      ? '***' : value;
  }
  return out;
};

// 占位符识别复用共享清单(*** / <redacted> / [REDACTED] / REDACTED / ••••,大小写不敏感)
const looksRedacted = (value: string | null): boolean => isRedactedPlaceholder(value);

// 认证方式与凭据字段必须自洽,避免把密码存进私钥槽或反之。
const credentialFor = (j: JsonObject, authMethod: SSHAuthMethod, clear: boolean): SSHCredential | null => {
  const password: string | null = stringArg(j, 'password');
  const privateKey: string | null = stringArg(j, 'private_key');
  const passphrase: string | null = stringArg(j, 'private_key_passphrase');
  const hasPassword: boolean = password !== null && password.length > 0;
  const hasKey: boolean = privateKey !== null && privateKey.length > 0;
  const hasPassphrase: boolean = passphrase !== null && passphrase.length > 0;
  // 审批后从存储回放时会拿到脱敏占位符:绝不能再把它当凭据写进存储(BLOCK1)。
  if (looksRedacted(password) || looksRedacted(privateKey) || looksRedacted(passphrase)) {
    throw new TerminalError('credential_unavailable');
  }
  if (hasPassword && hasKey) throw new TerminalError('invalid_arguments');
  if (authMethod === 'password' && (hasKey || hasPassphrase)) throw new TerminalError('invalid_arguments');
  if (authMethod === 'privateKey' && hasPassword) throw new TerminalError('invalid_arguments');
  if (clear && (hasPassword || hasKey || hasPassphrase)) throw new TerminalError('invalid_arguments');
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
      invalid_arguments: 'Invalid arguments: check name, host, port (1-65535), username, auth_method and credential fields.',
      credential_required: 'Changing host, port, username or auth method needs the new credential.',
      credential_missing: 'No usable credential is stored for this profile; provide a password or private key.',
      credential_unavailable: 'The stored arguments no longer contain the credential. Ask the user for the password '
        + 'or private key again; the placeholder cannot be saved.',
      profile_name_taken: 'Another SSH profile already uses that name. Pick a different name.',
    };
    const message: string = messages[error.code] ?? `Remote SSH: ${error.code}`;
    // 可恢复 = 用户重新提供参数后能成功;名称重复与凭据缺失都属此类。
    const recoverable: boolean = ['profile_conflict', 'profile_missing', 'profile_name_taken',
      'credential_missing', 'credential_required', 'credential_unavailable'].includes(error.code);
    return failure(error.code, message, recoverable);
  }
  return failure('profile_write_failed', fallback, false);
};

export const createSSHProfileTools = (deps: SSHProfileToolsDeps): AgentTool[] => {
  const describe = async (p: SSHProfile): Promise<JsonObject> =>
    profileJson(p, deps.profiles.snapshot().defaultProfileId, await deps.profiles.credentialExists(p.credentialRef));

  const draftFor = (j: JsonObject, existing: SSHProfile | null): SSHProfileDraft => normalizeDraft({
    id: existing === null ? newId() : existing.id,
    name: stringArg(j, 'name') ?? existing?.name ?? '',
    host: stringArg(j, 'host') ?? existing?.host ?? '',
    port: numberArg(j, 'port') ?? existing?.port ?? 22,
    username: stringArg(j, 'username') ?? existing?.username ?? '',
    authMethod: authMethodArg(j) ?? existing?.authMethod ?? 'password',
  });

  // 名称按大小写不敏感去重(排除自身),与设置页的「名称唯一」一致。
  const assertNameAvailable = (draft: SSHProfileDraft): void => {
    const wanted: string = draft.name.trim().toLowerCase();
    for (const other of deps.profiles.snapshot().profiles) {
      if (other.id === draft.id) continue;
      if (other.name.trim().toLowerCase() === wanted) throw new TerminalError('profile_name_taken');
    }
  };

  const apply = async (j: JsonObject, existing: SSHProfile | null): Promise<SSHProfile> => {
    const draft: SSHProfileDraft = draftFor(j, existing);
    assertNameAvailable(draft);
    const clear: boolean = booleanArg(j, 'clear_credential');
    const credential: SSHCredential | null = credentialFor(j, draft.authMethod, clear);
    const expected: number | null = existing === null ? null : existing.revision;
    if (existing === null) {
      // 新建必须带凭据:没有凭据的配置连不上,只会在终端里报 credential_missing。
      if (credential === null) throw new TerminalError('credential_missing');
      return deps.profiles.saveWithCredential(draft, null, credential, false);
    }
    if (credential !== null) return deps.profiles.saveWithCredential(draft, expected, credential, false);
    // 无新凭据:
    //   clear → store 在同一事务里清掉旧 ref(设置页语义:清凭据不动元数据)
    //   想沿用旧凭据 → 必须 binding 未变且凭据仍在,否则明确报错而不是静默丢凭据
    //   本来就没有凭据 → 与设置页一致,只改名称/端口等元数据不要求凭据
    if (!clear && !sameBinding(existing, draft) && existing.credentialRef !== null) {
      throw new TerminalError('credential_required', 'Changing host, port, username or auth method needs the new credential');
    }
    if (clear) return deps.profiles.saveWithCredential(draft, expected, null, true);
    return deps.profiles.saveMetadata(draft, expected);
  };

  const secretProperties: JsonObject = {
    password: { type: 'string', description: 'Password. Write-only: stored in the encrypted store, never returned or logged.' },
    private_key: { type: 'string', description: 'OpenSSH/PEM private key. Write-only.' },
    private_key_passphrase: { type: 'string', description: 'Passphrase for the private key. Write-only.' },
  };

  return [
    makeAgentTool({
      name: 'ssh_profile_list',
      description: 'List saved SSH profiles: id, name, host, port, username, auth method, whether a credential is '
        + 'stored (password or private key as booleans only), whether the host key is confirmed, and the revision. '
        + 'Read-only; never returns passwords, private keys or passphrases.',
      needsApproval: false,
      allowsAutoApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({}),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        try {
          objectArg(input);
          const settings = deps.profiles.snapshot();
          const profiles: JsonObject[] = [];
          for (const profile of settings.profiles) {
            profiles.push(profileJson(profile, settings.defaultProfileId, await deps.profiles.credentialExists(profile.credentialRef)));
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
      description: 'Create an SSH profile and store its password or private key. The credential is write-only: never '
        + 'repeat it in your reply. Host-key trust starts unconfirmed, so tell the user the fingerprint must be '
        + 'confirmed in Settings once before the first connection. High risk: needs explicit approval unless '
        + 'high-risk auto-approval is enabled.',
      needsApproval: true,
      allowsAutoApproval: false,
      mandatoryApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({
        name: { type: 'string', description: 'Profile name shown in Settings.' },
        host: { type: 'string', description: 'Host name or IP address.' },
        port: { type: 'number', description: 'SSH port, 1-65535. Defaults to 22.' },
        username: { type: 'string' },
        auth_method: { type: 'string', enum: ['password', 'privateKey'], description: 'Defaults to password.' },
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
      description: 'Update a saved SSH profile: rename it, change host/port/username/auth method, replace its '
        + 'password or private key, or clear the stored credential. Changing host, port, username or auth method '
        + 'requires the new credential and clears the confirmed host key. Credentials are write-only. High risk: '
        + 'needs explicit approval unless high-risk auto-approval is enabled.',
      needsApproval: true,
      allowsAutoApproval: false,
      mandatoryApproval: true,
      parameters: (): InputSchemaObj => makeInputSchemaObj({
        id: { type: 'string', description: 'Profile id from ssh_profile_list.' },
        name: { type: 'string' },
        host: { type: 'string' },
        port: { type: 'number', description: 'SSH port, 1-65535.' },
        username: { type: 'string' },
        auth_method: { type: 'string', enum: ['password', 'privateKey'] },
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
            note: saved.knownHostSHA256 === null
              ? 'The host key is no longer confirmed; confirm the fingerprint in Settings before connecting.'
              : 'Profile saved.' });
        } catch (error) {
          return failureFor(error, 'SSH profile could not be updated.');
        }
      },
    }),
    makeAgentTool({
      name: 'ssh_profile_delete',
      description: 'Delete a saved SSH profile and its stored password/private key. Terminal jobs and sessions that '
        + 'already started keep running, but new calls on that profile fail with profile_missing. High risk: needs '
        + 'explicit approval unless high-risk auto-approval is enabled.',
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
