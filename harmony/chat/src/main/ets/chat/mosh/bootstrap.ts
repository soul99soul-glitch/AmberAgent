import { TerminalError } from '../terminal/profile_store.ts';
import { posixQuote } from '../terminal/utf8.ts';
import type { MoshServerLocale } from './models.ts';

export const moshServerCommand = (port?: number | null, locale: MoshServerLocale = 'C.UTF-8'): string => {
  if (port !== undefined && port !== null && (!Number.isInteger(port) || port < 1 || port > 65535)) {
    throw new TerminalError('invalid_arguments');
  }
  if (locale !== 'C.UTF-8' && locale !== 'en_US.UTF-8') throw new TerminalError('invalid_arguments');
  return 'mosh-server new -s -c 256 -l ' + posixQuote('LANG=' + locale) + ' -l ' + posixQuote('LC_ALL=' + locale) +
    (port == null ? '' : ' -p ' + port);
};
export interface MoshConnect { port: number; sessionKey: string; }
export const parseMoshConnect = (stdout: string, requestedPort?: number | null): MoshConnect => {
  const lines: string[] = stdout.split('\n').map((line: string): string => line.replace(/\r$/, ''));
  const candidates: string[] = lines.filter((line: string): boolean => line.startsWith('MOSH CONNECT'));
  if (candidates.length !== 1) throw new TerminalError('mosh_bootstrap_invalid');
  const match: RegExpMatchArray | null = candidates[0].match(/^MOSH CONNECT ([0-9]+) ([A-Za-z0-9/+]{22})\s*$/);
  if (match === null) throw new TerminalError('mosh_bootstrap_invalid');
  const port: number = Number(match[1]);
  if (!Number.isInteger(port) || port < 1 || port > 65535 || (requestedPort != null && requestedPort !== port)) {
    throw new TerminalError('mosh_bootstrap_invalid');
  }
  return { port, sessionKey: match[2] };
};
export interface MoshFailure { code: string; message: string; }
const knownCodes: string[] = ['invalid_arguments', 'profile_missing', 'target_changed', 'host_trust_required',
  'credential_missing', 'cancelled', 'disconnected', 'network_error', 'host_key_mismatch', 'authentication_failed',
  'unsupported_key', 'connection_timeout', 'channel_error', 'mosh_bootstrap_invalid', 'mosh_server_missing',
  'mosh_bootstrap_failed', 'mosh_bootstrap_too_large', 'ssh_peer_address_missing', 'udp_connection_timeout',
  'unknown_handle', 'session_missing', 'session_closed', 'background_interrupted', 'native_closed', 'duplicate_request',
  'session_limit', 'connect_timeout', 'output_limit', 'queue_full', 'locale_unavailable'];
export const moshFailure = (error: Error): MoshFailure => {
  const candidate: string | undefined = (error as TerminalError).code;
  const code: string = candidate !== undefined && knownCodes.indexOf(candidate) >= 0 ? candidate : 'mosh_error';
  let message: string = 'Remote Mosh: ' + code;
  if (code === 'mosh_server_missing') message = 'Remote host needs mosh-server installed and available in PATH.';
  else if (code.startsWith('mosh_bootstrap_')) message = 'Mosh bootstrap failed. Check mosh-server and the selected UTF-8 locale on the remote host.';
  else if (code === 'udp_connection_timeout' || code === 'connect_timeout') message = 'Mosh UDP did not respond. Check the server UDP port and firewall.';
  else if (code === 'connection_timeout') message = 'Mosh SSH bootstrap timed out. Check the remote SSH connection and mosh-server.';
  else if (code === 'locale_unavailable') message = 'The Mosh client could not initialize its UTF-8 locale.';
  else if (code === 'ssh_peer_address_missing') message = 'Mosh requires the peer IP from this authenticated SSH connection.';
  else if (code === 'background_interrupted') message = 'Mosh interrupted in background. Start a new session to reconnect.';
  return { code, message };
};
