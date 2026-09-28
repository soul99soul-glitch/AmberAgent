import type { JsonObject } from '../json.ts';
import type { SSHCredential, SSHCredentialBinding } from './models.ts';
import { TerminalError } from './profile_store.ts';
import { terminalUTF8Encode } from './utf8.ts';

export const decodeSSHCredential = (text: string, binding: SSHCredentialBinding): SSHCredential => {
  const raw: JsonObject = JSON.parse(text) as JsonObject;
  if (raw === null || Array.isArray(raw) || Object.keys(raw).sort().join(',') !==
    'authMethod,host,passphrase,port,secret,username' || raw['host'] !== binding.host ||
    raw['port'] !== binding.port || raw['username'] !== binding.username || raw['authMethod'] !== binding.authMethod ||
    typeof raw['secret'] !== 'string' || !raw['secret'] ||
    (raw['passphrase'] !== null && typeof raw['passphrase'] !== 'string')) throw new TerminalError('credential_invalid');
  const secret: string = raw['secret'] as string;
  if (terminalUTF8Encode(secret).length > 65536) throw new TerminalError('credential_too_large');
  return { secret, passphrase: raw['passphrase'] as string | null };
};
export const encodeSSHCredential = (binding: SSHCredentialBinding, credential: SSHCredential): string => {
  const payload: JsonObject = { host: binding.host, port: binding.port, username: binding.username,
    authMethod: binding.authMethod, secret: credential.secret, passphrase: credential.passphrase };
  const text: string = JSON.stringify(payload);
  decodeSSHCredential(text, binding);
  return text;
};
