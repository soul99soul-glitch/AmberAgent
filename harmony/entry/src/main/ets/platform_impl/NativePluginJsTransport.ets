import native from 'libamber_native.so';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { PluginJsPort, PluginJsRequest, PluginJsEvent } from '@amber/chat-domain';

interface PluginJsSession {
  id: string;
  conversationId: string | null;
  signal: AbortSignalLike | undefined;
  abort: () => void;
}

class PluginJsTransportError extends Error {
  readonly code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

const activeSessions: Map<string, PluginJsSession> = new Map<string, PluginJsSession>();

const forgetSession = (id: string): void => {
  const session: PluginJsSession | undefined = activeSessions.get(id);
  if (session !== undefined) session.signal?.removeEventListener?.('abort', session.abort);
  activeSessions.delete(id);
};

const stopSession = (id: string): void => {
  native.pluginJsCancel(id);
  forgetSession(id);
};

// EntryAbility calls this synchronously when appBackgrounded becomes true.
// JSVM execution may continue, but no later tool callback can enter the broker.
export const cancelNativePluginJsSessions = (): void => {
  const ids: string[] = Array.from(activeSessions.keys());
  for (const id of ids) stopSession(id);
};

export const cancelNativePluginJsConversation = (conversationId: string): void => {
  for (const session of Array.from(activeSessions.values())) {
    if (session.conversationId === conversationId) stopSession(session.id);
  }
};

const stopped = (signal?: AbortSignalLike): boolean =>
  signal?.aborted === true || AppStorage.get<boolean>('appBackgrounded') === true;

const beforeStart = (id: string): PluginJsEvent => ({
  type: 'failed', sessionId: id, errorCode: 'cancelled',
  message: '插件脚本执行已取消', logs: [], abandoned: false,
});

const receive = async (event: PluginJsEvent): Promise<PluginJsEvent> => {
  const session: PluginJsSession | undefined = activeSessions.get(event.sessionId);
  if (event.type === 'finished' && (session === undefined || stopped(session.signal))) {
    forgetSession(event.sessionId);
    const cancelled: PluginJsEvent = {
      type: 'failed', sessionId: event.sessionId, errorCode: 'cancelled',
      message: '插件脚本执行已取消', logs: event.logs, abandoned: false,
    };
    return cancelled;
  }
  if (event.type !== 'host_call') {
    forgetSession(event.sessionId);
    return event;
  }
  if (session === undefined || stopped(session.signal)) {
    stopSession(event.sessionId);
    // Fetch the terminal event after a queued host_call lost a cancellation race.
    return await native.pluginJsReject(event.sessionId, event.callId, '插件脚本执行已取消');
  }
  return event;
};

export const createNativePluginJsTransport = (conversationId: string | null = null): PluginJsPort => ({
  start: async (request: PluginJsRequest, signal?: AbortSignalLike): Promise<PluginJsEvent> => {
    if (stopped(signal)) return beforeStart(request.executionId);
    const id: string = request.executionId;
    if (activeSessions.has(id)) throw new PluginJsTransportError('runtime_busy', '插件脚本会话仍在运行');
    const session: PluginJsSession = {
      id: id, conversationId: conversationId, signal: signal,
      abort: (): void => { stopSession(id); },
    };
    activeSessions.set(id, session);
    signal?.addEventListener?.('abort', session.abort);
    try {
      if (stopped(signal)) {
        forgetSession(id);
        return beforeStart(id);
      }
      // The native callback resolves here at the next host_call or terminal event.
      // Keep the abort listener attached while the original VM waits for approval.
      return await receive(await native.pluginJsStart(request));
    } catch (error) {
      forgetSession(id);
      throw error instanceof Error ? error : new Error(String(error));
    }
  },
  reply: async (sessionId: string, callId: string, resultJson: string): Promise<PluginJsEvent> => {
    const session: PluginJsSession | undefined = activeSessions.get(sessionId);
    if (stopped(session?.signal)) stopSession(sessionId);
    return await receive(await native.pluginJsReply(sessionId, callId, resultJson));
  },
  reject: async (sessionId: string, callId: string, reason: string): Promise<PluginJsEvent> =>
    receive(await native.pluginJsReject(sessionId, callId, reason)),
  cancel: (sessionId: string): void => { stopSession(sessionId); },
  hasSession: (sessionId: string): boolean => native.pluginJsHasSession(sessionId),
});
