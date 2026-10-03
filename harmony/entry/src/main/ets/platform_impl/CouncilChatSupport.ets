import type { ModelCouncilManager, ModelCouncilRun } from '@amber/deepread-domain';
import { readCouncilArchive } from '@amber/deepread-domain';
import type { AgentTool } from '@amber/chat-domain';
import { createCouncilTools } from '@amber/chat-domain';
import { getAgentPromptSettingsPort, getCouncilManager, getFileStore, prepareCouncilManager } from '../di/AppContainer.ets';

const chatCouncilManager = async (starting: boolean = false): Promise<ModelCouncilManager> => {
  const setting = await getAgentPromptSettingsPort().getCouncilSetting();
  if (!setting.enabled) throw new Error('模型议会已在设置中关闭');
  const manager: ModelCouncilManager | null = getCouncilManager();
  return manager !== null && (!starting || manager.listActive().length > 0) ? manager : prepareCouncilManager(setting);
};

export const readChatCouncilRun = async (runId: string): Promise<ModelCouncilRun | null> =>
  getCouncilManager()?.snapshot(runId) ?? await readCouncilArchive(getFileStore(), runId);

export const getChatCouncilTools = async (): Promise<AgentTool[]> => {
  if (!(await getAgentPromptSettingsPort().getCouncilSetting()).enabled) return [];
  return createCouncilTools({ manager: chatCouncilManager, read: readChatCouncilRun });
};
