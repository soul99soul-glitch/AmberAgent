import type { AgentTaskStatus } from '../agent_task.ts';
import type { SSHOutputChunk, SSHTargetSnapshot } from '../terminal/models.ts';

export type MoshCloseReason = 'cancelled' | 'disconnected' | 'release';
export type MoshReadState = 'running' | 'reconnecting' | 'closed' | 'cancelled' | 'disconnected' | 'failed';
export type MoshConnectionState = MoshReadState | 'connecting';
export type MoshServerLocale = 'C.UTF-8' | 'en_US.UTF-8';
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
  state: MoshReadState;
  bytes: Uint8Array;
  errorCode: string | null;
  lastHeardMs: number | null;
}
export interface MoshSessionRequest {
  target: SSHTargetSnapshot;
  columns: number;
  rows: number;
  udpPort?: number | null;
  serverLocale?: MoshServerLocale;
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface MoshSessionSnapshot {
  runtime: 'remote_mosh';
  sessionId: string;
  profileId: string;
  status: AgentTaskStatus;
  connectionState: MoshConnectionState;
  reconnectLastHeardMs: number | null;
  outputTail: string;
  columns: number;
  rows: number;
  errorCode: string | null;
  errorMessage: string | null;
}
export interface MoshSessionEvent { snapshot: MoshSessionSnapshot; chunks: SSHOutputChunk[]; }
