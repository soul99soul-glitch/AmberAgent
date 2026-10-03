import type { Conversation } from './conversation.ts';
import { currentMessages, nodeCurrentMessage } from './conversation.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import type { JsonObject } from './json.ts';

export type RecapNodeKind = 'decision' | 'milestone' | 'failure' | 'artifact';
export interface ConversationRecapNode {
  kind: RecapNodeKind;
  title: string;
  messageRef: string;
  messageId: string | null;
}
export interface ConversationRecap {
  conversationId: string;
  overview: string;
  nodes: ConversationRecapNode[];
  nextSteps: string[];
  coveredThroughMessageId: string;
  branchId: string;
  sourceSignature: string;
  generatedAt: string;
}
export interface RecapInput {
  prompt: string;
  references: Record<string, string>;
  coveredThroughMessageId: string;
  branchId: string;
  sourceSignature: string;
}

// Local file URLs and reasoning never leave the device in the recap transcript.
const partText = (part: UIMessagePart): string => {
  switch (part.type) {
    case 'text': return part.text.trim();
    case 'tool': return `Tool: ${part.toolName}\nInput: ${part.input.slice(0, 300)}\nOutput: ${part.output.map(partText).join('\n').slice(0, 300)}`;
    case 'image': return '[图片]';
    case 'document': return `[文件: ${part.fileName}]`;
    case 'mini_app': return `[交互成果: ${part.title}]`;
    default: return '';
  }
};
const messageText = (message: UIMessage): string =>
  message.parts.map(partText).filter((text: string): boolean => text.length > 0).join('\n\n').slice(0, 1500);
export const recapMessages = (conversation: Conversation): UIMessage[] =>
  currentMessages(conversation).filter((message: UIMessage): boolean =>
    (message.role === 'user' || message.role === 'assistant') && messageText(message).length > 0);
export const recapEligible = (conversation: Conversation): boolean =>
  recapMessages(conversation).filter((message: UIMessage): boolean => message.role === 'user').length >= 3;
export const recapBranchId = (conversation: Conversation): string =>
  conversation.messageNodes.filter((node): boolean => node.messages.length > 1)
    .map((node): string => `${node.id}:${nodeCurrentMessage(node).id}`).join('|') || 'main';

// A small content digest also detects edits that keep the message ID unchanged.
const sourceSignature = (messages: UIMessage[]): string => {
  let hash = 2166136261;
  const source = messages.map((message): string => `${message.id}:${messageText(message)}`).join('\n');
  for (let i = 0; i < source.length; i++) hash = Math.imul(hash ^ source.charCodeAt(i), 16777619);
  return (hash >>> 0).toString(16);
};
export const recapIsStale = (recap: ConversationRecap, conversation: Conversation): boolean => {
  const messages = recapMessages(conversation);
  return recap.conversationId !== conversation.id || recap.branchId !== recapBranchId(conversation)
    || recap.coveredThroughMessageId !== messages[messages.length - 1]?.id
    || recap.sourceSignature !== sourceSignature(messages);
};
export const projectRecap = (recap: ConversationRecap, conversation: Conversation): ConversationRecap => {
  const ids = new Set(currentMessages(conversation).map((message): string => message.id));
  return { ...recap, nodes: recap.nodes.map((node): ConversationRecapNode => ({
    ...node, messageId: node.messageId !== null && ids.has(node.messageId) ? node.messageId : null,
  })) };
};
export const makeRecapInput = (
  conversation: Conversation, previous: ConversationRecap | null, compactSummary: string = '', forceFull: boolean = false,
): RecapInput => {
  const messages = recapMessages(conversation);
  const branchId = recapBranchId(conversation);
  const prior = previous?.conversationId === conversation.id ? previous : null;
  const coveredIndex = messages.findIndex((message): boolean => message.id === prior?.coveredThroughMessageId);
  const coveredUnchanged = coveredIndex >= 0
    && sourceSignature(messages.slice(0, coveredIndex + 1)) === prior?.sourceSignature;
  const incremental = !forceFull && prior !== null && prior.branchId === branchId && coveredUnchanged;
  const transcript = (incremental ? messages.slice(coveredIndex + 1) : messages).slice(-32);
  const references: Record<string, string> = {};
  const referenceFor = (id: string): string => {
    const index = messages.findIndex((message): boolean => message.id === id);
    if (index < 0) return 'unavailable';
    const ref = `m${index + 1}`;
    references[ref] = id;
    return ref;
  };
  const previousText = prior === null ? '' : `<previous_recap>\nOverview: ${prior.overview}\n`
    + prior.nodes.map((node): string => `- [${node.kind}] ${node.title} (${node.messageId === null ? 'unavailable' : referenceFor(node.messageId)})`).join('\n')
    + `\nNext steps: ${prior.nextSteps.join('; ')}\n</previous_recap>`;
  const transcriptText = transcript.map((message): string =>
    `${referenceFor(message.id)} ${message.role}: ${messageText(message)}`).join('\n\n');
  return {
    prompt: `Create a structured recap in the user's primary language. Return only valid JSON:
{"overview":"...","nodes":[{"kind":"decision|milestone|failure|artifact","title":"...","messageRef":"m1"}],"nextSteps":["..."]}
Overview: one paragraph, at most about 120 characters, covering progress, results and unresolved points.
Include 3 to 8 important nodes and 0 to 3 actionable next steps. Preserve still-relevant previous content and incorporate new messages.
Use only the supplied message references; do not invent events or cite unavailable sources.
${incremental ? '' : 'Re-evaluate previous content against the current transcript; it may have been edited or branched.'}
${compactSummary.trim().length > 0 ? `<context_summary>\n${compactSummary}\n</context_summary>` : ''}
${previousText}
<new_messages>\n${transcriptText}\n</new_messages>`,
    references, coveredThroughMessageId: messages[messages.length - 1]?.id ?? '',
    branchId, sourceSignature: sourceSignature(messages),
  };
};
export const parseRecap = (raw: string, conversationId: string, input: RecapInput): ConversationRecap => {
  let response: JsonObject;
  try {
    const start = raw.indexOf('{');
    const end = raw.lastIndexOf('}');
    response = JSON.parse(raw.slice(start, end + 1)) as JsonObject;
    if (response === null || Array.isArray(response) || typeof response !== 'object') throw new Error();
  } catch { throw new Error('回顾内容格式错误，请重试。'); }
  if (typeof response['overview'] !== 'string' || !Array.isArray(response['nodes'])
    || !Array.isArray(response['nextSteps'])) throw new Error('回顾内容不完整，请重试。');
  const overview = response['overview'].replace(/\s+/g, ' ').trim().slice(0, 120);
  const nodes: ConversationRecapNode[] = [];
  for (const value of response['nodes']) {
    if (value === null || Array.isArray(value) || typeof value !== 'object') continue;
    const node = value as JsonObject;
    const kind = node['kind'];
    if (kind !== 'decision' && kind !== 'milestone' && kind !== 'failure' && kind !== 'artifact') continue;
    if (typeof node['title'] !== 'string' || typeof node['messageRef'] !== 'string') continue;
    const title = node['title'].trim();
    if (title.length === 0) throw new Error('回顾内容不完整，请重试。');
    nodes.push({ kind, title, messageRef: node['messageRef'], messageId: input.references[node['messageRef']] ?? null });
    if (nodes.length === 8) break;
  }
  const nextSteps: string[] = [];
  for (const value of response['nextSteps']) {
    if (typeof value !== 'string' || value.trim().length === 0) throw new Error('回顾内容不完整，请重试。');
    nextSteps.push(value.trim());
  }
  if (overview.length === 0 || nodes.length === 0 || nextSteps.length > 3) throw new Error('回顾内容不完整，请重试。');
  return { conversationId, overview, nodes, nextSteps, coveredThroughMessageId: input.coveredThroughMessageId,
    branchId: input.branchId, sourceSignature: input.sourceSignature, generatedAt: new Date().toISOString() };
};

// A derived cache may be discarded when its schema is damaged; messages stay intact.
export const decodeStoredRecap = (raw: string, id: string): ConversationRecap | null => {
  try {
    const value = JSON.parse(raw) as ConversationRecap;
    if (value === null || value.conversationId !== id || typeof value.overview !== 'string'
      || typeof value.branchId !== 'string' || typeof value.sourceSignature !== 'string'
      || typeof value.coveredThroughMessageId !== 'string' || typeof value.generatedAt !== 'string'
      || !Array.isArray(value.nodes) || !Array.isArray(value.nextSteps)) return null;
    if (!value.nodes.every((node): boolean => node !== null
      && ['decision', 'milestone', 'failure', 'artifact'].includes(node.kind)
      && typeof node.title === 'string' && typeof node.messageRef === 'string'
      && (node.messageId === null || typeof node.messageId === 'string'))
      || !value.nextSteps.every((step): boolean => typeof step === 'string')) return null;
    return value;
  } catch { return null; }
};

export interface RecapState { recap: ConversationRecap | null; loading: boolean; error: string; }
export interface RecapServiceDeps {
  loadConversation: (id: string) => Promise<Conversation | null>;
  read: (id: string) => Promise<ConversationRecap | null>;
  write: (recap: ConversationRecap, isCurrent: () => boolean) => Promise<boolean>;
  remove: (id: string) => Promise<void>;
  generate: (conversation: Conversation, prompt: string) => Promise<string>;
  compactSummary: (conversation: Conversation) => Promise<string>;
  begin?: (id: string) => () => void;
}
export class ConversationRecapService {
  private readonly states = new Map<string, RecapState>();
  private readonly listeners = new Map<string, Set<(state: RecapState) => void>>();
  private readonly requests = new Map<string, Promise<void>>();
  private readonly pending = new Map<string, boolean>();
  private readonly tokens = new Map<string, object>();
  constructor(private readonly deps: RecapServiceDeps) {}
  state(id: string): RecapState { return this.states.get(id) ?? { recap: null, loading: false, error: '' }; }
  private publish(id: string, state: RecapState): void {
    this.states.set(id, state);
    this.listeners.get(id)?.forEach((listener): void => listener(state));
  }
  subscribe(id: string, listener: (state: RecapState) => void): () => void {
    const listeners = this.listeners.get(id) ?? new Set<(state: RecapState) => void>();
    listeners.add(listener); this.listeners.set(id, listeners); listener(this.state(id));
    if (!this.states.has(id)) {
      const baseline = this.state(id);
      this.states.set(id, baseline);
      this.deps.read(id).then((recap): void => {
        if (this.states.get(id) === baseline) this.publish(id, { recap, loading: false, error: '' });
      }).catch((error: Error): void => {
        if (this.states.get(id) === baseline) this.publish(id, { recap: null, loading: false, error: error.message });
      });
    }
    return (): void => { listeners.delete(listener); if (listeners.size === 0) this.listeners.delete(id); };
  }
  request(id: string, force: boolean = false): Promise<void> {
    const active = this.requests.get(id);
    if (active !== undefined) {
      this.pending.set(id, force || (this.pending.get(id) ?? false));
      return active;
    }
    const token = {};
    this.tokens.set(id, token);
    const request = this.drain(id, token, force).finally((): void => {
      if (this.requests.get(id) === request) this.requests.delete(id);
      if (this.tokens.get(id) === token) this.tokens.delete(id);
    });
    this.requests.set(id, request);
    return request;
  }
  private async drain(id: string, token: object, force: boolean): Promise<void> {
    do {
      this.pending.delete(id);
      await this.run(id, token, force);
      force = this.pending.get(id) ?? false;
    } while (this.pending.has(id) && this.tokens.get(id) === token);
  }
  private async run(id: string, token: object, force: boolean): Promise<void> {
    const isCurrent = (): boolean => this.tokens.get(id) === token;
    let release: (() => void) | undefined;
    this.publish(id, { ...this.state(id), loading: true, error: '' });
    try {
      release = this.deps.begin?.(id);
      const conversation = await this.deps.loadConversation(id);
      if (!isCurrent()) return;
      if (conversation === null) throw new Error('会话不存在或已删除。');
      const previous = this.state(id).recap ?? await this.deps.read(id);
      if (!isCurrent()) return;
      if (!recapEligible(conversation)) throw new Error('至少需要 3 条用户消息才能生成回顾。');
      if (!force && previous !== null && !recapIsStale(previous, conversation)) {
        this.publish(id, { recap: previous, loading: true, error: '' }); return;
      }
      this.publish(id, { recap: previous, loading: true, error: '' });
      const input = makeRecapInput(conversation, previous, await this.deps.compactSummary(conversation), force);
      if (!isCurrent()) return;
      const raw = await this.deps.generate(conversation, input.prompt);
      if (!isCurrent()) return;
      const recap = parseRecap(raw, id, input);
      const saved = await this.deps.write(recap, isCurrent);
      if (!isCurrent()) return;
      if (!saved) throw new Error('对话已删除或恢复，回顾未写入，请重新生成。');
      this.publish(id, { recap, loading: true, error: '' });
    } catch (error) {
      if (isCurrent()) this.publish(id, { ...this.state(id), error: (error as Error).message });
    } finally {
      release?.();
      if (isCurrent()) {
        this.publish(id, { ...this.state(id), loading: false });
      }
    }
  }
  async invalidate(id: string): Promise<void> {
    this.tokens.delete(id);
    this.pending.delete(id);
    // The outstanding provider request remains single-flight until it settles.
    this.publish(id, { recap: null, loading: false, error: '' });
    await this.deps.remove(id);
  }
}
