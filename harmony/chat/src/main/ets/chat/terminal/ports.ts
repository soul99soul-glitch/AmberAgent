import type { AbortSignalLike } from '@amber/deepread-domain';
import type {
  SSHCloseReason, SSHConnectionOptions, SSHCredential, SSHCredentialBinding, SSHExecOptions,
  SSHHandle, SSHProbeOptions, SSHProbeResult, SSHPtyOptions, SSHReadPacket,
} from './models.ts';

export interface SSHTransportPort {
  probe(requestId: string, options: SSHProbeOptions, signal?: AbortSignalLike): Promise<SSHProbeResult>;
  startExec(requestId: string, connection: SSHConnectionOptions,
    options: SSHExecOptions, signal?: AbortSignalLike): Promise<SSHHandle>;
  startPty(requestId: string, connection: SSHConnectionOptions,
    options: SSHPtyOptions, signal?: AbortSignalLike): Promise<SSHHandle>;
  read(handle: SSHHandle, maxBytes: number): Promise<SSHReadPacket>;
  write(handle: SSHHandle, bytes: Uint8Array): Promise<void>;
  resize(handle: SSHHandle, columns: number, rows: number): Promise<void>;
  close(handle: SSHHandle, reason: SSHCloseReason): Promise<SSHReadPacket>;
}
export interface SSHCredentialStorePort {
  save(reference: string, binding: SSHCredentialBinding, credential: SSHCredential): Promise<void>;
  load(reference: string, binding: SSHCredentialBinding): Promise<SSHCredential | null>;
  exists(reference: string): Promise<boolean>;
  delete(reference: string): Promise<void>;
}
export interface TerminalLogPort {
  create(id: string): Promise<string>;
  append(path: string, text: string): Promise<void>;
}
