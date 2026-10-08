import type { UIMessagePartTool } from '@amber/chat-domain';
import { readSubAgentHistoricalText } from '@amber/chat-domain';
import { createEntrySubAgentTranscriptPort, subAgentRunsDir } from './SubAgentStore.ets';

export const loadSubAgentHistoricalText = (
  runId: string, tools: UIMessagePartTool[],
): Promise<string> => readSubAgentHistoricalText(
  runId, tools, subAgentRunsDir(), createEntrySubAgentTranscriptPort());
