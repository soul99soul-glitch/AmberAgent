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
  peerAddress?: string;
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

export interface PythonOptions {
  source: string;
  stdin: string;
  timeoutMs: number;
  resourceRoot: string;
}
export interface PythonResult {
  status: 'completed' | 'failed' | 'cancelled' | 'timed_out';
  exitCode: number | null;
  stdout: string;
  stderr: string;
  errorCode: string | null;
}
export const pythonVersion: () => string;
export const pythonExecute: (requestId: string, options: PythonOptions) => Promise<PythonResult>;
export const pythonCancel: (requestId: string) => void;

export interface PluginJsRequest {
  executionId: string; source: string; inputJson: string; hostTools: string[];
  timeoutMs: number; maxOutputChars: number;
}
export type PluginJsEvent =
  | { type: 'host_call'; sessionId: string; callId: string; toolName: string; argsJson: string }
  | { type: 'finished'; sessionId: string; resultJson: string; logs: string[] }
  | { type: 'failed'; sessionId: string; errorCode: string; message: string; logs: string[]; abandoned: boolean };
export const pluginJsStart: (request: PluginJsRequest) => Promise<PluginJsEvent>;
export const pluginJsReply: (sessionId: string, callId: string, resultJson: string) => Promise<PluginJsEvent>;
export const pluginJsReject: (sessionId: string, callId: string, reason: string) => Promise<PluginJsEvent>;
export const pluginJsCancel: (sessionId: string) => void;
export const pluginJsHasSession: (sessionId: string) => boolean;

export interface MoshHandle { id: string; kind: 'mosh'; }
export interface MoshOptions {
  peerAddress: string;
  port: number;
  sessionKey: string;
  columns: number;
  rows: number;
  connectTimeoutMs: number;
}
export interface MoshPacket {
  state: 'running' | 'reconnecting' | 'closed' | 'cancelled' | 'disconnected' | 'failed';
  bytes: Uint8Array;
  errorCode: string | null;
  lastHeardMs: number | null;
}
export const moshStart: (requestId: string, options: MoshOptions) => Promise<MoshHandle>;
export const moshCancel: (requestId: string) => void;
export const moshRead: (handle: MoshHandle, maxBytes: number) => MoshPacket;
export const moshWrite: (handle: MoshHandle, bytes: Uint8Array) => Promise<void>;
export const moshResize: (handle: MoshHandle, columns: number, rows: number) => Promise<void>;
export const moshClose: (
  handle: MoshHandle, reason: 'cancelled' | 'disconnected' | 'release'
) => Promise<MoshPacket>;

/** Bounded loopback OAuth callback transport; no credentials are persisted. */
export interface GoogleLoopbackHandle { handleId: string; port: number; }
export interface GoogleLoopbackRequest {
  connectionId: string;
  requestTarget: string;
  errorCode: string | null;
}
export const startGoogleLoopback: (
  port: number, callback: (request: GoogleLoopbackRequest) => void
) => Promise<GoogleLoopbackHandle>;
export const replyGoogleLoopback: (
  handleId: string, connectionId: string, status: number
) => Promise<void>;
export const closeGoogleLoopback: (handleId: string) => Promise<void>;

/** API20 native VSync request; returns0 when accepted. */
export const setStreamingFrameRateActive: (active: boolean) => number;
