// builtin_episode_recall_tool — 免审批常驻只读工具 episode_recall
// 允许模型按 episode_id 或关键词调阅当前单流会话中已封存的前序 Episode 原文片段。

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { ChatEpisodeLedger, ChatEpisodeSearchHit } from './episode_models.ts';
import { searchEpisodeMessages } from './episode_models.ts';

export const EPISODE_RECALL_TOOL_NAME: string = 'episode_recall';

export interface EpisodeRecallToolDeps {
  loadLedger: () => Promise<ChatEpisodeLedger>;
  loadMessages: () => Promise<UIMessage[]>;
}

const optionalString = (obj: JsonObject, key: string): string => {
  const val: JsonValue | undefined = obj[key];
  return typeof val === 'string' ? val.trim() : '';
};

const optionalLimit = (obj: JsonObject, key: string, fallback: number): number => {
  const val: JsonValue | undefined = obj[key];
  if (typeof val === 'number' && Number.isFinite(val)) {
    return Math.max(1, Math.min(10, Math.floor(val)));
  }
  return fallback;
};

export const createEpisodeRecallTool = (deps: EpisodeRecallToolDeps): AgentTool => {
  const properties: Record<string, JsonObject> = {
    episode_id: {
      type: 'string',
      description: '要调阅的封存 Episode ID（如 ep_0001），来自 Recent Episodes Index。',
    },
    query: {
      type: 'string',
      description: '按关键词检索前序归档话题中的相关对话内容。',
    },
    limit: {
      type: 'integer',
      description: '最多返回的 Episode 条数（1~10，默认 4）。',
    },
  };
  const schema: InputSchemaObj = makeInputSchemaObj(properties, []);
  return makeAgentTool({
    name: EPISODE_RECALL_TOOL_NAME,
    description: '调阅当前单流对话中已封存前序话题（Episode）的原文片段。按 episode_id 或关键词只读检索，无需审批。',
    parameters: (): InputSchemaObj => schema,
    needsApproval: false,
    allowsAutoApproval: true,
    execute: async (input: JsonValue): Promise<UIMessage['parts']> => {
      const obj: JsonObject = (input !== null && typeof input === 'object' && !Array.isArray(input))
        ? input as JsonObject : {};
      const episodeId: string = optionalString(obj, 'episode_id');
      const query: string = optionalString(obj, 'query');
      const limit: number = optionalLimit(obj, 'limit', 4);
      const ledger: ChatEpisodeLedger = await deps.loadLedger();
      const messages: UIMessage[] = await deps.loadMessages();
      if (ledger.episodes.length === 0) {
        return [{ type: 'text', text: JSON.stringify({ episodes: [], note: '当前会话尚无已封存的历史话题分段。' }), metadata: null }];
      }
      const hits: ChatEpisodeSearchHit[] = searchEpisodeMessages(ledger, messages, episodeId, query, limit);
      return [{
        type: 'text',
        text: JSON.stringify({
          totalSealedEpisodes: ledger.episodes.length,
          hits,
        }),
        metadata: null,
      }];
    },
  });
};
