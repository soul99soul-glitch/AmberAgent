// Selected conversation history is the source of truth for the artifact shelf.
import type { Conversation } from './conversation.ts';
import { nodeCurrentMessage } from './conversation.ts';
import type { UIMessagePartDocument, UIMessagePart } from './message.ts';
import type { GenerativeWidget } from './generative_widget.ts';
import { parseGenerativeWidgets, sanitizeWidgetHtml, widgetDocument } from './generative_widget.ts';
import { groupMessageParts } from './message_grouping.ts';
import { parseMarkdown } from './markdown_blocks.ts';
import type { InlineToken, MarkdownBlock } from './markdown_blocks.ts';
import { parseResponseContent } from './response_content.ts';
import type { ResponseContentSegment, VisualArtifact } from './response_content.ts';
import type { SmartUiNode, SmartUiState } from './smart_ui/protocol.ts';
import { cloneSmartUiComponent } from './smart_ui/components.ts';
import type { ThreeSceneSpec } from './three_scene.ts';
import { renderThreeSceneCover } from './three_scene.ts';

export interface ConversationArtifactSource { conversationId: string; nodeId: string; messageId: string; }
export interface ConversationImageArtifact { id: string; url: string; source: ConversationArtifactSource; }
export interface ConversationVideoArtifact { id: string; url: string; mime: string; source: ConversationArtifactSource; }
export interface ConversationDocumentArtifact { id: string; part: UIMessagePartDocument; source: ConversationArtifactSource; }
export interface ConversationMiniAppArtifact { id: string; appId: string; title: string; source: ConversationArtifactSource; }
export interface ConversationWidgetArtifact { id: string; widget: GenerativeWidget; source: ConversationArtifactSource; }

export type ConversationDeclaredArtifactProtocol = 'amber-ui' | 'amber-svg' | 'amber-three';

/** Source identity for a declared v1 block. blockIndex is the message text-group index. */
export interface ConversationDeclaredArtifactSource extends ConversationArtifactSource {
  blockIndex: number;
  sourceStart: number;
  blockId: string;
  protocol: ConversationDeclaredArtifactProtocol;
  version: 1;
}

/** The only trust hand-off accepted for SVG export: the preview host's DOMParser result. */
export interface ConversationSvgValidationProof {
  source: ConversationDeclaredArtifactSource;
  snapshot: string;
  validator: 'domparser';
  ok: boolean;
  /** Host-serialized XML. It may add a missing namespace; export requires it. */
  canonicalSvg?: string;
}

export interface ConversationSvgArtifact {
  id: string;
  protocol: 'amber-svg';
  version: 1;
  status: 'partial' | 'ready' | 'error';
  source: ConversationDeclaredArtifactSource;
  svg: string;
  validation: 'domparser' | null;
}

export interface ConversationSceneArtifact {
  id: string;
  protocol: 'amber-three';
  version: 1;
  status: 'ready';
  source: ConversationDeclaredArtifactSource;
  scene: ThreeSceneSpec;
  cover: string;
}

export interface ConversationUiDefinition {
  id: string;
  states: SmartUiState[];
  nodes: SmartUiNode[];
}

export interface ConversationUiSnapshotArtifact {
  id: string;
  protocol: 'amber-ui';
  version: 1;
  status: 'ready';
  source: ConversationDeclaredArtifactSource;
  summary: string;
  definition: ConversationUiDefinition;
  /** Export is a static snapshot; it never promises a runnable interaction surface. */
  interactive: false;
}

export interface ConversationArtifactIndex {
  images: ConversationImageArtifact[];
  videos: ConversationVideoArtifact[];
  docs: ConversationDocumentArtifact[];
  miniApps: ConversationMiniAppArtifact[];
  widgets: ConversationWidgetArtifact[];
  svg: ConversationSvgArtifact[];
  scenes: ConversationSceneArtifact[];
  uiSnapshots: ConversationUiSnapshotArtifact[];
  duplicateDocumentNames: string[];
}

export interface ArtifactFileExport { fileName: string; content: string; }
export interface SceneArtifactExport {
  jsonFileName: string;
  json: string;
  coverFileName: string;
  cover: string;
}

const declaredProtocolVersion: 1 = 1;

const safeFileStem = (id: string, fallback: string): string => {
  const stem: string = id.replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 64);
  return stem.length > 0 ? stem : fallback;
};

const sameDeclaredSource = (
  left: ConversationDeclaredArtifactSource,
  right: ConversationDeclaredArtifactSource,
): boolean => left.conversationId === right.conversationId && left.nodeId === right.nodeId &&
  left.messageId === right.messageId && left.blockIndex === right.blockIndex &&
  left.sourceStart === right.sourceStart && left.blockId === right.blockId &&
  left.protocol === right.protocol && left.version === right.version;

const declaredSource = (
  base: ConversationArtifactSource,
  blockIndex: number,
  segment: ResponseContentSegment,
  protocol: ConversationDeclaredArtifactProtocol,
): ConversationDeclaredArtifactSource | null => segment.id === null ? null : ({
  conversationId: base.conversationId,
  nodeId: base.nodeId,
  messageId: base.messageId,
  blockIndex,
  sourceStart: segment.sourceStart,
  blockId: segment.id,
  protocol,
  version: declaredProtocolVersion,
});

const collectInlineImages = (tokens: InlineToken[], out: string[]): void => {
  for (const token of tokens) {
    if (token.type === 'image' && token.url.trim().length > 0) out.push(token.url.trim());
  }
};

const markdownImageUrls = (text: string): string[] => {
  const out: string[] = [];
  let blocks: MarkdownBlock[] = [];
  try { blocks = parseMarkdown(text); } catch { return out; }
  for (const block of blocks) {
    if (block.kind === 'heading' || block.kind === 'paragraph' || block.kind === 'blockquote') {
      collectInlineImages(block.inlines, out);
    } else if (block.kind === 'list') {
      for (const item of block.items) collectInlineImages(item, out);
    } else if (block.kind === 'table') {
      for (const header of block.headers) collectInlineImages(header, out);
      for (const row of block.rows) for (const cell of row) collectInlineImages(cell, out);
    }
  }
  return out;
};

const collectPartMedia = (
  part: UIMessagePart, source: ConversationArtifactSource, idBase: string,
  imageUrls: Set<string>, videoUrls: Set<string>,
  images: ConversationImageArtifact[], videos: ConversationVideoArtifact[],
): void => {
  if (part.type === 'image' && part.url.length > 0 && !imageUrls.has(part.url)) {
    imageUrls.add(part.url);
    images.push({ id: idBase, url: part.url, source });
  } else if (part.type === 'video' && part.url.length > 0 && !videoUrls.has(part.url)) {
    videoUrls.add(part.url);
    videos.push({ id: idBase, url: part.url, mime: part.mime, source });
  } else if (part.type === 'tool') {
    for (let index: number = 0; index < part.output.length; index++) {
      collectPartMedia(part.output[index], source, `${idBase}:tool:${index}`, imageUrls, videoUrls, images, videos);
    }
  }
};

const cloneSmartUiDefinition = (id: string, states: SmartUiState[], nodes: SmartUiNode[]): ConversationUiDefinition => ({
  id,
  states: states.map((state: SmartUiState): SmartUiState => ({
    id: state.id,
    initial: state.initial,
    valueType: state.valueType,
  })),
  nodes: nodes.map((node: SmartUiNode): SmartUiNode => {
    const copy: SmartUiNode = { id: node.id, parent: node.parent, type: node.type };
    if (node.variant !== undefined) copy.variant = node.variant;
    if (node.layout !== undefined) copy.layout = node.layout;
    if (node.tone !== undefined) copy.tone = node.tone;
    if (node.text !== undefined) copy.text = node.text;
    if (node.bind !== undefined) copy.bind = { ref: node.bind.ref, path: node.bind.path };
    if (node.state !== undefined) copy.state = node.state;
    if (node.options !== undefined) {
      copy.options = node.options.map((option) => ({ value: option.value, label: option.label, detail: option.detail, color: option.color }));
    }
    if (node.placeholder !== undefined) copy.placeholder = node.placeholder;
    if (node.label !== undefined) copy.label = node.label;
    if (node.action !== undefined) copy.action = node.action.type === 'draft' ? { type: 'draft', text: node.action.text } : { type: 'reset' };
    if (node.component !== undefined) copy.component = cloneSmartUiComponent(node.component);
    if (node.required !== undefined) copy.required = node.required;
    if (node.ref !== undefined) copy.ref = node.ref;
    if (node.visibleWhen !== undefined) {
      copy.visibleWhen = { state: node.visibleWhen.state, equals: node.visibleWhen.equals };
    }
    return copy;
  }),
});

const smartUiSummary = (nodes: SmartUiNode[]): string => {
  const lines: string[] = [];
  for (const node of nodes) {
    if (node.type === 'text' && node.bind !== undefined) {
      const label: string = node.text?.trim() ?? '数据';
      lines.push(`${label.length > 0 ? label : '数据'}（只读引用：${node.bind.ref}/${node.bind.path}，未包含当前值）`);
    } else if (node.type === 'text' && node.text !== undefined && node.text.trim().length > 0) {
      lines.push(node.text.trim());
    } else if (node.type === 'component' && node.component !== undefined) {
      const component = node.component;
      if (component.title !== undefined && component.title.trim().length > 0) lines.push(component.title.trim());
      if (component.text !== undefined && component.text.trim().length > 0) lines.push(component.text.trim());
      if (component.items !== undefined) lines.push(component.items.join('、'));
      if (component.rows !== undefined) lines.push(component.rows.map((row): string => `${row.label}${row.value !== undefined ? `：${row.value}` : ''}${row.detail ? ` ${row.detail}` : ''}${row.cells !== undefined ? ` ${row.cells.join(' | ')}` : ''}`).join('\n'));
      if (node.options !== undefined) lines.push(`选择：${node.options.map((option): string => option.label).join('、')}`);
    } else if (node.type === 'choice' && node.options !== undefined) {
      lines.push(`选择：${node.options.map((option) => option.label).join('、')}`);
    } else if (node.type === 'input') {
      const label: string = node.label?.trim() ?? '';
      const placeholder: string = node.placeholder?.trim() ?? '';
      lines.push(label.length > 0 ? `输入：${label}` : placeholder.length > 0 ? `输入：${placeholder}` : '输入');
    } else if (node.type === 'button' && node.label !== undefined && node.label.trim().length > 0) {
      lines.push(`操作：${node.label.trim()}`);
    } else if (node.type === 'asset' && node.ref !== undefined) {
      lines.push(`资源：${node.ref}`);
    }
  }
  const summary: string = lines.join('\n').trim();
  return summary.length > 0 ? summary.slice(0, 4096) : '智能 UI 静态快照';
};

const collectDeclaredArtifacts = (
  conversation: Conversation,
  proofs: ReadonlyArray<ConversationSvgValidationProof>,
): { svg: ConversationSvgArtifact[]; scenes: ConversationSceneArtifact[]; uiSnapshots: ConversationUiSnapshotArtifact[] } => {
  const svg: ConversationSvgArtifact[] = [];
  const scenes: ConversationSceneArtifact[] = [];
  const uiSnapshots: ConversationUiSnapshotArtifact[] = [];
  for (const node of conversation.messageNodes) {
    const message = nodeCurrentMessage(node);
    if (message.role !== 'assistant') continue;
    const source: ConversationArtifactSource = {
      conversationId: conversation.id,
      nodeId: node.id,
      messageId: message.id,
    };
    const blocks = groupMessageParts(message.parts);
    const parsedBlocks: { index: number; segments: ResponseContentSegment[] }[] = [];
    const declaredIds = new Map<string, number>();
    for (const block of blocks) {
      if (block.kind !== 'content' || block.part.type !== 'text') continue;
      const segments: ResponseContentSegment[] = parseResponseContent(block.part.text, false, undefined, true);
      parsedBlocks.push({ index: block.index, segments });
      for (const segment of segments) {
        if ((segment.type === 'ui' || segment.type === 'svg' || segment.type === 'three') && segment.id !== null) {
          declaredIds.set(segment.id, (declaredIds.get(segment.id) ?? 0) + 1);
        }
      }
    }
    for (const parsed of parsedBlocks) {
      for (const segment of parsed.segments) {
        if (segment.id === null || (declaredIds.get(segment.id) ?? 0) > 1) continue;
        if (segment.type === 'svg') {
          const artifact: VisualArtifact | null = segment.artifact;
          const currentSource: ConversationDeclaredArtifactSource | null = declaredSource(
            source, parsed.index, segment, 'amber-svg');
          if (artifact === null || artifact.snapshot === null || !segment.closed || currentSource === null) continue;
          const proof: ConversationSvgValidationProof | undefined = proofs.find((item) =>
            item.validator === 'domparser' && item.snapshot === artifact.snapshot &&
            sameDeclaredSource(item.source, currentSource));
          svg.push({
            id: segment.id,
            protocol: 'amber-svg',
            version: declaredProtocolVersion,
            status: proof === undefined ? 'partial' : proof.ok ? 'ready' : 'error',
            source: currentSource,
            svg: artifact.snapshot,
            validation: proof === undefined ? null : proof.validator,
          });
        } else if (segment.type === 'three') {
          const artifact: VisualArtifact | null = segment.artifact;
          const currentSource: ConversationDeclaredArtifactSource | null = declaredSource(
            source, parsed.index, segment, 'amber-three');
          if (artifact === null || artifact.scene === null || artifact.scene.objects.length === 0 ||
            artifact.status !== 'ready' ||
            !segment.closed || currentSource === null) continue;
          scenes.push({
            id: segment.id,
            protocol: 'amber-three',
            version: declaredProtocolVersion,
            status: 'ready',
            source: currentSource,
            scene: artifact.scene,
            cover: renderThreeSceneCover(artifact.scene),
          });
        } else if (segment.type === 'ui') {
          const currentSource: ConversationDeclaredArtifactSource | null = declaredSource(
            source, parsed.index, segment, 'amber-ui');
          if (segment.ui === null || segment.status !== 'ready' || !segment.complete ||
            !segment.closed || currentSource === null) continue;
          uiSnapshots.push({
            id: segment.id,
            protocol: 'amber-ui',
            version: declaredProtocolVersion,
            status: 'ready',
            source: currentSource,
            summary: smartUiSummary(segment.ui.nodes),
            definition: cloneSmartUiDefinition(segment.ui.id, segment.ui.states, segment.ui.nodes),
            interactive: false,
          });
        }
      }
    }
  }
  return { svg, scenes, uiSnapshots };
};

export const collectConversationArtifacts = (
  conversation: Conversation,
  widgetsEnabled: boolean,
  svgProofs: ReadonlyArray<ConversationSvgValidationProof> = [],
): ConversationArtifactIndex => {
  const declared = collectDeclaredArtifacts(conversation, svgProofs);
  const index: ConversationArtifactIndex = {
    images: [], videos: [], docs: [], miniApps: [], widgets: [],
    svg: declared.svg,
    scenes: declared.scenes,
    uiSnapshots: declared.uiSnapshots,
    duplicateDocumentNames: [],
  };
  const imageUrls = new Set<string>();
  const videoUrls = new Set<string>();
  const miniAppIndices = new Map<string, number>();
  const documentCounts = new Map<string, number>();
  for (const node of conversation.messageNodes) {
    const message = nodeCurrentMessage(node);
    const source: ConversationArtifactSource = { conversationId: conversation.id, nodeId: node.id, messageId: message.id };
    const blocks = groupMessageParts(message.parts);
    // Match visible Markdown without changing attachment order or source part indices.
    const bodyTextByPartIndex = new Map<number, string>();
    for (const block of blocks) {
      if (block.kind === 'content' && block.part.type === 'text') bodyTextByPartIndex.set(block.index, block.part.text);
    }
    for (let partIndex = 0; partIndex < message.parts.length; partIndex++) {
      const part = message.parts[partIndex];
      const id = `${message.id}:${partIndex}`;
      collectPartMedia(part, source, id, imageUrls, videoUrls, index.images, index.videos);
      const bodyText: string | undefined = bodyTextByPartIndex.get(partIndex);
      if (bodyText !== undefined) {
        const markdownImages: string[] = markdownImageUrls(bodyText);
        for (let imageIndex: number = 0; imageIndex < markdownImages.length; imageIndex++) {
          const url: string = markdownImages[imageIndex];
          if (imageUrls.has(url)) continue;
          imageUrls.add(url);
          index.images.push({ id: `${id}:md:${imageIndex}`, url, source });
        }
      }
      if (part.type === 'document') {
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
      for (const block of blocks) {
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

/** Export only when this exact candidate has a successful proof from the trusted SVG host. */
export const svgArtifactExport = (
  artifact: ConversationSvgArtifact,
  proof: ConversationSvgValidationProof,
): ArtifactFileExport => {
  if (artifact.svg.trim().length === 0 || artifact.status === 'error' ||
    !proof.ok || proof.validator !== 'domparser' || proof.snapshot !== artifact.svg ||
    !sameDeclaredSource(proof.source, artifact.source)) {
    throw new Error('SVG 尚未通过 DOM 结构校验');
  }
  const canonical: string = proof.canonicalSvg?.trim() ?? '';
  if (canonical.length > 120_000) throw new Error('SVG 规范化内容过大，无法导出');
  const cleanedCanonical: string = sanitizeWidgetHtml(canonical).trim();
  if (cleanedCanonical.length === 0) throw new Error('SVG 缺少宿主规范化内容');
  return { fileName: `${safeFileStem(artifact.id, 'visual')}.svg`, content: cleanedCanonical };
};

/** 3D export is a normalized scene JSON plus an explicit static cover. */
export const sceneArtifactExport = (artifact: ConversationSceneArtifact, dark: boolean = false): SceneArtifactExport => {
  if (artifact.status !== 'ready' || artifact.scene.objects.length === 0) {
    throw new Error('3D 场景尚未完成');
  }
  const stem: string = safeFileStem(artifact.id, 'scene');
  return {
    jsonFileName: `${stem}.json`,
    json: JSON.stringify(artifact.scene, null, 2),
    coverFileName: `${stem}-cover.svg`,
    cover: renderThreeSceneCover(artifact.scene, dark),
  };
};

/** UI export is intentionally static and carries the normalized definition beside its summary. */
export const uiSnapshotArtifactExport = (artifact: ConversationUiSnapshotArtifact): ArtifactFileExport => {
  if (artifact.status !== 'ready' || artifact.interactive !== false) {
    throw new Error('智能 UI 快照不可导出为交互界面');
  }
  const payload = {
    protocol: artifact.protocol,
    version: artifact.version,
    id: artifact.id,
    interactive: false,
    stateValues: 'definition-defaults',
    summary: artifact.summary,
    definition: artifact.definition,
  };
  return {
    fileName: `${safeFileStem(artifact.id, 'smart-ui')}.json`,
    content: JSON.stringify(payload, null, 2),
  };
};
