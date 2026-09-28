import type { AgentTaskStatus } from '../agent_task.ts';

export type SSHAuthMethod = 'password' | 'privateKey';
export type SSHHandleKind = 'exec' | 'pty';
export type SSHReadState = 'running' | 'exited' | 'failed' | 'cancelled' | 'timed_out' | 'disconnected';
export type SSHCloseReason = 'cancelled' | 'disconnected' | 'release';
export type SSHNativeErrorCode = 'invalid_arguments' | 'unknown_handle' | 'network_error' |
  'host_key_mismatch' | 'authentication_failed' | 'unsupported_key' | 'connection_timeout' | 'cancelled' | 'channel_error';
export interface SSHNativeError extends Error { code: SSHNativeErrorCode; }
export interface SSHHandle { id: string; kind: SSHHandleKind; peerAddress?: string; }
export interface SSHProbeOptions { host: string; port: number; timeoutMs: number; }
export interface SSHProbeResult { fingerprintSHA256: string; hostKeyType: string; }
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
export interface SSHExecOptions { command: string; timeoutMs: number; }
export interface SSHPtyOptions { term: string; columns: number; rows: number; }
export interface SSHOutputChunk { bytes: Uint8Array; isStderr: boolean; }
export interface SSHReadPacket {
  chunks: SSHOutputChunk[];
  state: SSHReadState;
  exitCode: number | null;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface SSHCredentialBinding { host: string; port: number; username: string; authMethod: SSHAuthMethod; }
export interface SSHCredential { secret: string; passphrase: string | null; }
export interface SSHProfileDraft extends SSHCredentialBinding { id: string; name: string; }
export interface SSHProfile extends SSHProfileDraft {
  revision: number;
  knownHostSHA256: string | null;
  knownHostHost: string | null;
  knownHostPort: number | null;
  credentialRef: string | null;
}
export interface SSHProfileSettings { profiles: SSHProfile[]; defaultProfileId: string | null; }
export interface SSHTrustProbe {
  draftDigest: string;
  profileRevision: number | null;
  fingerprintSHA256: string;
  hostKeyType: string;
  expectedFingerprintSHA256: string | null;
  trustState: 'trusted' | 'untrusted' | 'mismatch';
}
export interface SSHTargetSnapshot { profileId: string; digest: string; usesDefault: boolean; }
export interface TerminalCommandRequest {
  target: SSHTargetSnapshot;
  command: string;
  cwd: string | null;
  timeoutMs: number;
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface TerminalSessionRequest {
  target: SSHTargetSnapshot;
  cwd: string | null;
  columns: number;
  rows: number;
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface TerminalJobSnapshot {
  jobId: string;
  profileId: string;
  command: string;
  cwd: string | null;
  status: AgentTaskStatus;
  exitCode: number | null;
  stdoutTail: string;
  stderrTail: string;
  outputTail: string;
  outputLogPath: string;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface TerminalSessionSnapshot {
  sessionId: string;
  profileId: string;
  status: AgentTaskStatus;
  exitCode: number | null;
  outputTail: string;
  outputLogPath: string;
  columns: number;
  rows: number;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface TerminalSessionEvent { snapshot: TerminalSessionSnapshot; chunks: SSHOutputChunk[]; }
