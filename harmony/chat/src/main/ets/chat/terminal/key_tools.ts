// key_tools — SSH 本机密钥管理 Agent 工具(ssh_key_list / ssh_key_generate / ssh_key_import / ssh_key_delete)
//
// 安全铁律:
//   - ssh_key_list 只读(risk=normal, needsApproval=false):只返回 id/name/algorithm/public_key/fingerprint/
//     origin/protection/has_passphrase/require_user_presence/used_by_profile_ids;绝不返回私钥或口令。
//   - ssh_key_generate / ssh_key_import / ssh_key_delete 是高风险写操作(risk=high, mandatoryApproval=true),
//     只留给主会话,子代理与 Council 席位不可见、不可申请。
//   - ssh_key_import 的 private_key / passphrase 走 tool_input_vault + tool_input_redaction,
//     决不落盘、决不回显、决不把脱敏占位符当真实私钥导入。

import type { AgentTool, InputSchemaObj } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { SSHKey } from './models.ts';
import type { SSHKeyStore } from './key_store.ts';
import type { SSHProfileStore } from './profile_store.ts';
import { TerminalError } from './profile_store.ts';
import { isRedactedPlaceholder } from '../tool_input_redaction.ts';

export { SSH_KEY_TOOL_NAMES, SSH_KEY_MAIN_SESSION_ONLY_TOOLS } from './ssh_tool_contract.ts';

export interface SSHKeyToolsDeps {
  keys: SSHKeyStore;
  profiles: SSHProfileStore;
}

const textResult = (value: JsonObject): UIMessagePart[] =>
  [{ type: 'text', text: JSON.stringify(value), metadata: null }];

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

const booleanArg = (j: JsonObject, key: string): boolean => {
  const value: JsonValue | undefined = j[key];
  return value === true || value === 'true';
};

const keySummaryJson = (k: SSHKey, usedByProfileIds: string[]): JsonObject => ({
  id: k.id,
  name: k.name,
  algorithm: k.algorithm,
  public_key: k.publicKey,
  fingerprint: k.fingerprintSHA256,
  origin: k.origin,
  protection: k.protection,
  has_passphrase: k.hasPassphrase,
  require_user_presence: k.requireUserPresence,
  used_by_profile_ids: usedByProfileIds,
});

const keyErrorResult = (error: unknown, fallbackMessage: string): UIMessagePart[] => {
  if (error instanceof TerminalError) {
    const messages: Record<string, string> = {
      invalid_arguments: 'Invalid arguments: check name, key type (ed25519 or ecdsa-p256), or key_id.',
      key_name_taken: 'An SSH key with that name already exists. Choose a different name.',
      key_missing: 'No saved SSH key with that key_id.',
      key_in_use: 'This SSH key is still referenced by saved SSH profiles and cannot be deleted.',
      passphrase_required: 'This private key is encrypted and requires a passphrase.',
      invalid_passphrase: 'The passphrase for the encrypted private key is incorrect.',
      unsupported_key: 'Unsupported or malformed SSH private key format.',
      credential_unavailable: 'The stored arguments no longer contain the private key or passphrase. Ask the user to provide it again.',
    };
    const recoverable: boolean = [
      'invalid_arguments', 'key_name_taken', 'key_missing', 'key_in_use',
      'passphrase_required', 'invalid_passphrase', 'credential_unavailable',
    ].includes(error.code);
    const payload: JsonObject = {
      ok: false,
      status: 'failed',
      error_code: error.code,
      message: messages[error.code] ?? error.message ?? fallbackMessage,
      recoverable,
    };
    if (error.details !== null) {
      for (const k of Object.keys(error.details)) {
        payload[k] = error.details[k];
      }
    }
    return textResult(payload);
  }
  return textResult({
    ok: false,
    status: 'failed',
    error_code: 'key_operation_failed',
    message: fallbackMessage,
    recoverable: false,
  });
};

export const createSSHKeyTools = (deps: SSHKeyToolsDeps): AgentTool[] => [
  makeAgentTool({
    name: 'ssh_key_list',
    description: 'List saved local SSH keys: id, name, algorithm, OpenSSH public_key, SHA256 fingerprint, origin '
      + '(generated or imported), protection, has_passphrase, require_user_presence, and used_by_profile_ids. '
      + 'Read-only; never returns private keys or passphrases.',
    needsApproval: false,
    allowsAutoApproval: true,
    parameters: (): InputSchemaObj => makeInputSchemaObj({}),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      try {
        objectArg(input);
        const list: SSHKey[] = deps.keys.list();
        const keys: JsonObject[] = list.map((k: SSHKey): JsonObject =>
          keySummaryJson(k, deps.profiles.profilesUsingKey(k.id)));
        return textResult({ ok: true, status: 'ok', count: keys.length, keys });
      } catch (error) {
        return keyErrorResult(error, 'Saved SSH keys could not be listed.');
      }
    },
  }),
  makeAgentTool({
    name: 'ssh_key_generate',
    description: 'Generate a new SSH key pair locally on the device (Ed25519 by default, or ECDSA P-256). The private '
      + 'key is sealed inside the device encrypted store and never leaves the device or appears in tool output. Returns '
      + 'ONLY the OpenSSH public_key line and SHA256 fingerprint. Workflow when setting up key auth for a server or '
      + 'jump host: (1) call ssh_key_generate, (2) show the returned public_key to the user so they can append it to '
      + '~/.ssh/authorized_keys on the server, (3) call ssh_profile_create with auth_method="key" and key_id.',
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    parameters: (): InputSchemaObj => makeInputSchemaObj({
      name: { type: 'string', description: 'Key name shown in Settings (used in comment amber-<name>).' },
      type: {
        type: 'string',
        enum: ['ed25519', 'ecdsa-p256'],
        description: 'Key algorithm. Defaults to ed25519.',
      },
    }, ['name']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      try {
        const j: JsonObject = objectArg(input);
        const name: string = requiredString(j, 'name');
        const rawType: string | null = stringArg(j, 'type');
        const keyType: 'ed25519' | 'ecdsa-p256' = (rawType === null || rawType === '')
          ? 'ed25519'
          : (rawType === 'ed25519' || rawType === 'ecdsa-p256')
            ? rawType
            : ((): never => { throw new TerminalError('invalid_arguments'); })();
        const requireUserPresence: boolean = booleanArg(j, 'require_user_presence');
        const created: SSHKey = await deps.keys.generateKey(name, keyType, requireUserPresence);
        return textResult({
          ok: true,
          status: 'ok',
          action: 'generated',
          key: keySummaryJson(created, []),
          next_step: `请将 public_key 追加到目标服务器或跳板机的 ~/.ssh/authorized_keys，随后调用 ssh_profile_create(..., auth_method="key", key_id="${created.id}") 创建连接配置。`,
        });
      } catch (error) {
        return keyErrorResult(error, 'SSH key could not be generated.');
      }
    },
  }),
  makeAgentTool({
    name: 'ssh_key_import',
    description: 'Import an existing OpenSSH or PEM private key into the device encrypted store. Supports unencrypted '
      + 'and passphrase-protected private keys. The private_key and passphrase parameters are write-only and redacted '
      + 'from transcripts; returns ONLY the derived OpenSSH public_key and SHA256 fingerprint.',
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    parameters: (): InputSchemaObj => makeInputSchemaObj({
      name: { type: 'string', description: 'Key name shown in Settings.' },
      private_key: { type: 'string', description: 'OpenSSH or PEM private key text. Write-only.' },
      passphrase: { type: 'string', description: 'Optional passphrase for encrypted private key. Write-only.' },
    }, ['name', 'private_key']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      try {
        const j: JsonObject = objectArg(input);
        const name: string = requiredString(j, 'name');
        const privateKey: string | null = stringArg(j, 'private_key');
        const passphrase: string | null = stringArg(j, 'passphrase') ?? stringArg(j, 'private_key_passphrase');
        if (isRedactedPlaceholder(privateKey) || isRedactedPlaceholder(passphrase)) {
          throw new TerminalError('credential_unavailable');
        }
        if (privateKey === null || privateKey.trim().length === 0) {
          throw new TerminalError('invalid_arguments');
        }
        const imported: SSHKey = await deps.keys.importKey(
          name,
          privateKey,
          passphrase !== null && passphrase.length > 0 ? passphrase : null,
          false,
        );
        return textResult({
          ok: true,
          status: 'ok',
          action: 'imported',
          key: keySummaryJson(imported, []),
        });
      } catch (error) {
        return keyErrorResult(error, 'SSH key could not be imported.');
      }
    },
  }),
  makeAgentTool({
    name: 'ssh_key_delete',
    description: 'Delete a saved local SSH key and its encrypted private key material. Fails with key_in_use if any '
      + 'saved SSH profile still references this key_id.',
    needsApproval: true,
    allowsAutoApproval: false,
    mandatoryApproval: true,
    parameters: (): InputSchemaObj => makeInputSchemaObj({
      key_id: { type: 'string', description: 'Key id from ssh_key_list.' },
    }, ['key_id']),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      try {
        const j: JsonObject = objectArg(input);
        const keyId: string = stringArg(j, 'key_id') ?? requiredString(j, 'id');
        if (keyId.trim().length === 0) throw new TerminalError('invalid_arguments');
        const removed: SSHKey = await deps.keys.deleteKey(keyId);
        return textResult({
          ok: true,
          status: 'ok',
          action: 'deleted',
          key_id: removed.id,
          name: removed.name,
        });
      } catch (error) {
        return keyErrorResult(error, 'SSH key could not be deleted.');
      }
    },
  }),
];
