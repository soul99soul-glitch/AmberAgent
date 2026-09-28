import type { AgentTool, JsonValue, UIMessagePart } from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { makeAgentTool } from '@amber/chat-domain';

interface BrowserLease { revision: number; lost: boolean; }
let visibleConversation: string | null = null;
let pageRevision: number = 0;
let pageBinding: number = 0;
let inFlight: string | null = null;
const leases: Map<string, BrowserLease> = new Map<string, BrowserLease>();

// A WebView belongs to its visible page. Leaving it invalidates the old tool chain.
export const bindConversationBrowserPage = (conversationId: string): (() => void) => {
  const binding: number = ++pageBinding;
  if (visibleConversation !== conversationId) {
    if (visibleConversation !== null) {
      const previous: BrowserLease | undefined = leases.get(visibleConversation);
      if (previous !== undefined) previous.lost = true;
    }
    pageRevision++;
  }
  visibleConversation = conversationId;
  return (): void => {
    if (pageBinding !== binding) return;
    const lease: BrowserLease | undefined = leases.get(conversationId);
    if (lease !== undefined) lease.lost = true;
    visibleConversation = null;
    pageRevision++;
  };
};

export const releaseConversationBrowserRun = (conversationId: string, awaitingTools: boolean): void => {
  if (!awaitingTools) leases.delete(conversationId);
};

// Plugin URL scopes mutate the same WebView before invoking a primitive.
export const assertConversationBrowserOwner = (conversationId: string): (() => void) => {
  if (visibleConversation !== conversationId) throw new Error('浏览器工具需要当前会话的页面宿主，请返回该会话后操作。');
  let lease: BrowserLease | undefined = leases.get(conversationId);
  if (lease === undefined) {
    lease = { revision: pageRevision, lost: false };
    leases.set(conversationId, lease);
  }
  const owned: BrowserLease = lease;
  const assertOwner = (): void => {
    if (visibleConversation !== conversationId || owned.lost || owned.revision !== pageRevision)
      throw new Error('该工具链的浏览器页面已离开，原页面状态不再可用。请结束本轮后重新打开目标页面。');
  };
  assertOwner();
  return assertOwner;
};

export const guardConversationBrowserTools = (tools: AgentTool[], conversationId: string): AgentTool[] =>
  tools.map((tool: AgentTool): AgentTool => {
    if (!tool.name.startsWith('wm_') && tool.name !== 'javascript_execute') return tool;
    // E12(goal):wm_run_goal 只做宿主编排(原 lease/owner 断言),不占子操作的
    //   inFlight 槽 — 否则其经 dispatcher 调用的 guarded 子工具会自锁
    if (tool.name === 'wm_run_goal') {
      return makeAgentTool({ name: tool.name, description: tool.description, parameters: tool.parameters,
        needsApproval: tool.needsApproval, allowsAutoApproval: tool.allowsAutoApproval,
        mandatoryApproval: tool.mandatoryApproval, systemPrompt: tool.systemPrompt,
        recipeEnvelope: tool.recipeEnvelope,
        pluginEnvelope: tool.pluginEnvelope, mcpTarget: tool.mcpTarget,
        execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
          if (visibleConversation !== conversationId) {
            throw new Error('浏览器工具需要当前会话的页面宿主，请返回该会话后操作。');
          }
          let lease: BrowserLease | undefined = leases.get(conversationId);
          if (lease === undefined) {
            lease = { revision: pageRevision, lost: false };
            leases.set(conversationId, lease);
          }
          if (lease.lost || lease.revision !== pageRevision) {
            throw new Error('该工具链的浏览器页面已离开，原页面状态不再可用。请结束本轮后重新打开目标页面。');
          }
          return tool.execute(input, signal);
        } });
    }
    return makeAgentTool({ name: tool.name, description: tool.description, parameters: tool.parameters,
      needsApproval: tool.needsApproval, allowsAutoApproval: tool.allowsAutoApproval,
      mandatoryApproval: tool.mandatoryApproval, systemPrompt: tool.systemPrompt,
      recipeEnvelope: tool.recipeEnvelope,
      pluginEnvelope: tool.pluginEnvelope, mcpTarget: tool.mcpTarget,
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      if (visibleConversation !== conversationId) {
        throw new Error('浏览器工具需要当前会话的页面宿主，请返回该会话后操作。');
      }
      let lease: BrowserLease | undefined = leases.get(conversationId);
      if (lease === undefined) {
        lease = { revision: pageRevision, lost: false };
        leases.set(conversationId, lease);
      }
      if (lease.lost || lease.revision !== pageRevision) {
        throw new Error('该工具链的浏览器页面已离开，原页面状态不再可用。请结束本轮后重新打开目标页面。');
      }
      if (inFlight !== null) throw new Error('浏览器工具仍被另一次实际操作占用，请等待其结束。');
      inFlight = conversationId;
      try {
        const output: UIMessagePart[] = await tool.execute(input, signal);
        if (lease.lost || lease.revision !== pageRevision || visibleConversation !== conversationId) {
          return [{ type: 'text', text: JSON.stringify({ status: 'outcome_unknown',
            error: '浏览器页面在操作期间离开，原操作结果无法继续确认。' }), metadata: null }];
        }
        return output;
      } finally { inFlight = null; }
    } });
  });
