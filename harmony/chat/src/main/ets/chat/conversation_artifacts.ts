// Selected conversation history is the source of truth for the artifact shelf.
import type { Conversation } from './conversation.ts';
import { nodeCurrentMessage } from './conversation.ts';
import type { UIMessagePartDocument } from './message.ts';
import type { GenerativeWidget } from './generative_widget.ts';
import { parseGenerativeWidgets, widgetDocument } from './generative_widget.ts';
import { groupMessageParts } from './message_grouping.ts';

export interface ConversationArtifactSource { conversationId: string; nodeId: string; messageId: string; }
export interface ConversationImageArtifact { id: string; url: string; source: ConversationArtifactSource; }
export interface ConversationDocumentArtifact { id: string; part: UIMessagePartDocument; source: ConversationArtifactSource; }
export interface ConversationMiniAppArtifact { id: string; appId: string; title: string; source: ConversationArtifactSource; }
export interface ConversationWidgetArtifact { id: string; widget: GenerativeWidget; source: ConversationArtifactSource; }
export interface ConversationArtifactIndex {
  images: ConversationImageArtifact[];
  docs: ConversationDocumentArtifact[];
  miniApps: ConversationMiniAppArtifact[];
  widgets: ConversationWidgetArtifact[];
  duplicateDocumentNames: string[];
}

export const collectConversationArtifacts = (conversation: Conversation, widgetsEnabled: boolean): ConversationArtifactIndex => {
  const index: ConversationArtifactIndex = { images: [], docs: [], miniApps: [], widgets: [], duplicateDocumentNames: [] };
  const imageUrls = new Set<string>();
  const miniAppIndices = new Map<string, number>();
  const documentCounts = new Map<string, number>();
  for (const node of conversation.messageNodes) {
    const message = nodeCurrentMessage(node);
    const source: ConversationArtifactSource = { conversationId: conversation.id, nodeId: node.id, messageId: message.id };
    for (let partIndex = 0; partIndex < message.parts.length; partIndex++) {
      const part = message.parts[partIndex];
      const id = `${message.id}:${partIndex}`;
      if (part.type === 'image' && part.url.length > 0 && !imageUrls.has(part.url)) {
        imageUrls.add(part.url);
        index.images.push({ id, url: part.url, source });
      } else if (part.type === 'document') {
        index.docs.push({ id, part, source });
        const name = part.fileName.length > 0 ? part.fileName : '(未命名)';
        documentCounts.set(name, (documentCounts.get(name) ?? 0) + 1);
      } else if (part.type === 'mini_app' && part.appId.length > 0) {
        const artifact: ConversationMiniAppArtifact = { id: `miniapp:${part.appId}`, appId: part.appId,
          title: part.title.trim() || part.appId, source };
        const previous = miniAppIndices.get(part.appId);
        if (previous === undefined) {
          miniAppIndices.set(part.appId, index.miniApps.length);
          index.miniApps.push(artifact);
        } else index.miniApps[previous] = artifact;
      }
    }
    if (widgetsEnabled && message.role === 'assistant') {
      // Use the same adjacent-text grouping as the visible message body.
      for (const block of groupMessageParts(message.parts)) {
        if (block.kind !== 'content' || block.part.type !== 'text') continue;
        // Requiring a closed fence keeps interrupted partial JSON out of the shelf.
        for (const segment of parseGenerativeWidgets(block.part.text, true)) {
          if (segment.kind === 'widget' && segment.widget !== null && segment.widget.complete) {
            index.widgets.push({ id: `${message.id}:${block.index}:${segment.start}`, widget: segment.widget, source });
          }
        }
      }
    }
  }
  documentCounts.forEach((count: number, name: string): void => {
    if (count >= 2) index.duplicateDocumentNames.push(name);
  });
  return index;
};

export const artifactSourceNodeIndex = (conversation: Conversation, source: ConversationArtifactSource): number => {
  if (conversation.id !== source.conversationId) return -1;
  const index = conversation.messageNodes.findIndex((node): boolean => node.id === source.nodeId);
  if (index < 0 || nodeCurrentMessage(conversation.messageNodes[index]).id !== source.messageId) return -1;
  return index;
};

export interface WidgetArtifactExport { fileName: string; content: string; }
export const widgetArtifactExport = (artifact: ConversationWidgetArtifact, dark: boolean): WidgetArtifactExport => {
  const title = artifact.widget.title.replace(/[\x00-\x1f\x7f/\\:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 60);
  // The preview inherits its native card background; a standalone file needs its own.
  const content = widgetDocument(artifact.widget.html, dark).replace('</head>',
    `<style>html,body{background:${dark ? '#14110E' : '#EFE7D6'}}</style></head>`);
  return { fileName: `${title || '可视化组件'}.html`, content };
};
