// Derived profile storage. MemoryStore calls these inside its source-write queue.
import { Context } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import type { MemoryProfile, MemoryRecord, MemoryTopicApplyResult } from '@amber/chat-domain';
import { coveredMemoryProfileRecords, parseStoredMemoryProfile } from '@amber/chat-domain';
import { readGuardedJson, writeTextFileAtomic, clearQuarantineMarker } from './AtomicJsonFile.ets';

export interface MemoryOptimizationApplyResult extends MemoryTopicApplyResult {
  profileUpdated: boolean;
  mergedIds: number[];
}
const profilePath = (): string => {
  const context: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${context.filesDir}/memory/profile.json`;
};
export const readMemoryProfile = async (): Promise<MemoryProfile | null> => {
  const read = await readGuardedJson<MemoryProfile>(profilePath(), parseStoredMemoryProfile);
  return read.status === 'ok' ? read.value : null;
};
export const writeMemoryProfile = async (profile: MemoryProfile | null): Promise<void> => {
  if (profile === null) {
    if (await fileIo.access(profilePath())) await fileIo.unlink(profilePath());
  } else {
    await writeTextFileAtomic(profilePath(), JSON.stringify(profile));
    await clearQuarantineMarker(profilePath());
  }
};
export const refreshMemoryProfile = async (records: MemoryRecord[], now: number): Promise<MemoryProfile | null> => {
  const profile: MemoryProfile | null = await readMemoryProfile();
  if (profile === null) return null;
  let valid: boolean = false;
  try { valid = coveredMemoryProfileRecords(profile, records, ['core', 'short_term', 'long_term'], now) !== null; }
  catch { valid = false; }
  if (valid) return profile;
  await writeMemoryProfile(null);
  return null;
};
