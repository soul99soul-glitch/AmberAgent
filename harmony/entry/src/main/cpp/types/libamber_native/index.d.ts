/** Counts UTF-8 text with o200k_base, cl100k_base, claude, or gemini. */
export const countBatch: (tokenizerIds: string[], texts: string[]) => Promise<number[]>;

export type SSHAuthMethod = 'password' | 'privateKey';
export type SSHHandleKind = 'exec' | 'pty';
export type SSHReadState =
  'running' | 'exited' | 'failed' | 'cancelled' | 'timed_out' | 'disconnected';
export type SSHCloseReason = 'cancelled' | 'disconnected' | 'release';
export type SSHNativeErrorCode =
  'invalid_arguments' | 'unknown_handle' | 'network_error' |
  'host_key_mismatch' | 'authentication_failed' | 'unsupported_key' |
  'connection_timeout' | 'cancelled' | 'channel_error';

export interface SSHNativeError extends Error {
  code: SSHNativeErrorCode;
}
export interface SSHHandle {
  id: string;
  kind: SSHHandleKind;
}
export interface SSHProbeOptions {
  host: string;
  port: number;
  timeoutMs: number;
}
export interface SSHProbeResult {
  fingerprintSHA256: string;
  hostKeyType: string;
}
export interface SSHConnectionOptions {
  host: string;
  port: number;
  username: string;
  expectedFingerprintSHA256: string;
  authMethod: SSHAuthMethod;
  secret: string;
  passphrase: string | null;
  connectTimeoutMs: number;
}
export interface SSHExecOptions {
  command: string;
  timeoutMs: number;
}
export interface SSHPtyOptions {
  term: string;
  columns: number;
  rows: number;
}
export interface SSHOutputChunk {
  bytes: Uint8Array;
  isStderr: boolean;
}
export interface SSHReadPacket {
  chunks: SSHOutputChunk[];
  state: SSHReadState;
  exitCode: number | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export const sshProbe: (
  requestId: string, options: SSHProbeOptions
) => Promise<SSHProbeResult>;
export const sshStartExec: (
  requestId: string, connection: SSHConnectionOptions, options: SSHExecOptions
) => Promise<SSHHandle>;
export const sshStartPty: (
  requestId: string, connection: SSHConnectionOptions, options: SSHPtyOptions
) => Promise<SSHHandle>;
export const sshCancel: (requestId: string) => Promise<void>;
export const sshRead: (handle: SSHHandle, maxBytes: number) => SSHReadPacket;
export const sshWrite: (handle: SSHHandle, bytes: Uint8Array) => Promise<void>;
export const sshResize: (handle: SSHHandle, columns: number, rows: number) => Promise<void>;
export const sshClose: (
  handle: SSHHandle, reason: SSHCloseReason
) => Promise<SSHReadPacket>;
