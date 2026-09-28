import type { AbortSignalLike } from '@amber/deepread-domain';
import type { MoshCloseReason, MoshHandle, MoshOptions, MoshPacket } from './models.ts';

export interface MoshTransportPort {
  start(requestId: string, options: MoshOptions, signal?: AbortSignalLike): Promise<MoshHandle>;
  read(handle: MoshHandle, maxBytes: number): Promise<MoshPacket>;
  write(handle: MoshHandle, bytes: Uint8Array): Promise<void>;
  resize(handle: MoshHandle, columns: number, rows: number): Promise<void>;
  close(handle: MoshHandle, reason: MoshCloseReason): Promise<MoshPacket>;
}
