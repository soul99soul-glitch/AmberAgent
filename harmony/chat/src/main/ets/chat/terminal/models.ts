import type { AgentTaskStatus } from '../agent_task.ts';

export type SSHAuthMethod = 'password' | 'privateKey';
export type SSHHandleKind = 'exec' | 'pty';
export type SSHReadState = 'running' | 'exited' | 'failed' | 'cancelled' | 'timed_out' | 'disconnected';
export type SSHCloseReason = 'cancelled' | 'disconnected' | 'release';
export type SSHNativeErrorCode = 'invalid_arguments' | 'unknown_handle' | 'network_error' |
  'host_key_mismatch' | 'host_key_changed' | 'host_key_untrusted' | 'authentication_failed' | 'hop_auth_failed' |
  'unsupported_key' | 'passphrase_required' | 'invalid_passphrase' |
  'jump_forward_denied' | 'jump_target_unreachable' | 'jump_chain_too_long' |
  'connection_timeout' | 'cancelled' | 'channel_error';
export interface SSHNativeError extends Error {
  code: SSHNativeErrorCode;
  hopIndex?: number;
  hopCount?: number;
  profileId?: string;
  expectedFingerprintSHA256?: string;
  presentedFingerprintSHA256?: string;
}
export interface SSHHandle { id: string; kind: SSHHandleKind; peerAddress?: string; }
export interface SSHHopConnectionOptions {
  profileId?: string;
  host: string;
  port: number;
  username: string;
  expectedFingerprintSHA256: string;
  authMethod: SSHAuthMethod;
  secret: string;
  passphrase: string | null;
}
export interface SSHProbeOptions {
  host: string;
  port: number;
  timeoutMs: number;
  profileId?: string;
  jumpHops?: SSHHopConnectionOptions[];
}
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
  profileId?: string;
  jumpHops?: SSHHopConnectionOptions[];
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
export type SSHKeyAlgorithm = 'ed25519' | 'ecdsa-p256' | 'ecdsa-p384' | 'ecdsa-p521' | 'rsa' | string;
export type SSHKeyOrigin = 'generated' | 'imported';
export type SSHKeyProtection = 'huks_wrapped' | 'asset_encrypted';
export interface SSHKey {
  id: string;
  name: string;
  algorithm: SSHKeyAlgorithm;
  publicKey: string;
  fingerprintSHA256: string;
  origin: SSHKeyOrigin;
  protection: SSHKeyProtection;
  hasPassphrase: boolean;
  requireUserPresence: boolean;
  credentialRef: string;
  createdAtMs: number;
}
export interface SSHGeneratedKeyMaterial {
  algorithm: string;
  publicKey: string;
  fingerprintSHA256: string;
  privateKey: string;
}
export interface SSHInspectedKeyMaterial {
  algorithm: string;
  publicKey: string;
  fingerprintSHA256: string;
  hasPassphrase: boolean;
}
export interface SSHRouteHopSummary {
  hop: number;
  profile_id: string;
  name: string;
  endpoint: string;
  host: string;
  port: number;
  username: string;
  host_key_trusted: boolean;
  fingerprint: string | null;
}
export interface SSHProfileDraft extends SSHCredentialBinding {
  id: string;
  name: string;
  keyId?: string | null;
  jumpProfileId?: string | null;
}
export interface SSHProfile extends SSHProfileDraft {
  revision: number;
  knownHostSHA256: string | null;
  knownHostHost: string | null;
  knownHostPort: number | null;
  knownHostViaJump?: string | null;
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
  routeDigest?: string;
  hopIndex?: number;
  hopCount?: number;
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
  route?: SSHRouteHopSummary[];
  hopIndex?: number | null;
  hopCount?: number | null;
  failedProfileId?: string | null;
  expectedFingerprintSHA256?: string | null;
  presentedFingerprintSHA256?: string | null;
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
  route?: SSHRouteHopSummary[];
  hopIndex?: number | null;
  hopCount?: number | null;
  failedProfileId?: string | null;
  expectedFingerprintSHA256?: string | null;
  presentedFingerprintSHA256?: string | null;
}
export interface TerminalSessionEvent { snapshot: TerminalSessionSnapshot; chunks: SSHOutputChunk[]; }
