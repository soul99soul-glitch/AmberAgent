import type { FileStore } from '../platform/files.ts';
import type { ModelCouncilRun, ModelCouncilTurn } from './models.ts';

interface ArchiveEvent { event: string; run?: ModelCouncilRun; turn?: ModelCouncilTurn; }

// 归档仅恢复可读结果，绝不重放网络或工具执行。
export const readCouncilArchive = async (files: FileStore, runId: string): Promise<ModelCouncilRun | null> => {
  if (!/^[A-Za-z0-9_-]{1,100}$/.test(runId)) return null;
  const text = await files.readText(`model-council/runs/${runId}.jsonl`);
  if (text === null) return null;
  let run: ModelCouncilRun | null = null;
  try {
    for (const line of text.split('\n')) {
      if (line.trim().length === 0) continue;
      const event = JSON.parse(line) as ArchiveEvent;
      if (event.run !== undefined && event.run.runId === runId && Array.isArray(event.run.seats) &&
        Array.isArray(event.run.turns) && event.run.task !== undefined) {
        run = event.run;
      } else if (event.event === 'turn' && event.turn !== undefined && run !== null) {
        run.turns.push(event.turn);
      }
    }
  } catch {
    return null;
  }
  if (run !== null && run.status === 'running') run.status = 'interrupted';
  return run;
};
