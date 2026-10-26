// Pure candidate evidence, image/diagram validation and visual diagnostics.
import type { DeepReadOutput } from '../domain/models.ts';
import type { ScoredImageCandidate } from '../research/image_scorer.ts';
import {
  IMAGE_CONFIDENCE, MAX_DIAGRAM_NODES, MAX_LINEAR_DIAGRAM_EDGES, MAX_RELATION_DIAGRAM_EDGES,
  DIAGRAM_TITLE_MAX_CHARS, DIAGRAM_NODE_LABEL_MAX_CHARS, DIAGRAM_NODE_NOTE_MAX_CHARS,
  DIAGRAM_NODE_GROUP_MAX_CHARS, DIAGRAM_EDGE_LABEL_MAX_CHARS, IMAGE_ASSETS_MAX,
  READING_LINK_SOURCE_MAX, HERO_CAPTION_MAX, HERO_REASON_MAX,
} from '../domain/enums.ts';
import type { DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram } from '../domain/models.ts';
import { cleanText, isHttpUrl, urlKey, objString, objObjectList, urlString } from './section_writer_codec.ts';

export const mergeImageAssets = (existing: DeepReadImageAsset[], incoming: DeepReadImageAsset[], limit: number): DeepReadImageAsset[] => {
  const seen = new Set<string>();
  const out: DeepReadImageAsset[] = [];
  for (const asset of [...existing, ...incoming]) {
    if (!isHttpUrl(asset.url)) continue;
    const key = urlKey(asset.url);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(asset);
    if (out.length >= limit) break;
  }
  return out;
};

export const parseImageAssets = (o: Record<string, unknown>): DeepReadImageAsset[] =>
  objObjectList(o, 'image_assets').map(obj => {
    const url = urlString(obj, 'url');
    if (url === null) return null;
    const caption = objString(obj, 'caption');
    const source = objString(obj, 'source');
    const qualityHint = objString(obj, 'quality_hint');
    const selectionReason = objString(obj, 'selection_reason');
    return {
      url,
      caption: caption !== null ? cleanText(caption, HERO_CAPTION_MAX) : null,
      source: source !== null ? cleanText(source, READING_LINK_SOURCE_MAX) : null,
      confidence: IMAGE_CONFIDENCE.INLINE,
      score: null,
      qualityHint: qualityHint !== null ? cleanText(qualityHint, 60) : null,
      selectionReason: selectionReason !== null ? cleanText(selectionReason, HERO_REASON_MAX) : null,
      relatedEntities: [],
      relatedTimelineIndex: null,
    } as DeepReadImageAsset;
  }).filter((x): x is DeepReadImageAsset => x !== null).slice(0, IMAGE_ASSETS_MAX);

const DIAGRAM_TYPES = new Set(['causal_chain', 'process_flow', 'stakeholder_map', 'system_structure', 'comparison_matrix']);

export const parseDiagram = (o: Record<string, unknown>): DeepReadDiagram | null => {
  const typeRaw = objString(o, 'type');
  const type = typeRaw !== null ? typeRaw.toLowerCase() : null;
  if (type === null || !DIAGRAM_TYPES.has(type)) return null;
  const titleRaw = objString(o, 'title');
  if (titleRaw === null) return null;
  const title = cleanText(titleRaw, DIAGRAM_TITLE_MAX_CHARS);
  const seenIds = new Set<string>();
  const nodes: DiagramNode[] = [];
  for (const obj of objObjectList(o, 'nodes')) {
    const id = objString(obj, 'id');
    const label = objString(obj, 'label');
    if (id === null || label === null) continue;
    const idC = cleanText(id, 32);
    const labelC = cleanText(label, DIAGRAM_NODE_LABEL_MAX_CHARS);
    if (seenIds.has(idC)) continue;
    seenIds.add(idC);
    const note = objString(obj, 'note');
    const group = objString(obj, 'group');
    nodes.push({
      id: idC,
      label: labelC,
      note: note !== null ? cleanText(note, DIAGRAM_NODE_NOTE_MAX_CHARS) : null,
      group: group !== null ? cleanText(group, DIAGRAM_NODE_GROUP_MAX_CHARS) : null,
    });
    if (nodes.length >= MAX_DIAGRAM_NODES) break;
  }
  if (nodes.length < 2) return null;
  const nodeIds = new Set(nodes.map(n => n.id));
  const rawEdges: DiagramEdge[] = [];
  for (const obj of objObjectList(o, 'edges')) {
    const from = objString(obj, 'from');
    const to = objString(obj, 'to');
    if (from === null || to === null) continue;
    const fromC = cleanText(from, 32);
    const toC = cleanText(to, 32);
    if (!nodeIds.has(fromC) || !nodeIds.has(toC) || fromC === toC) continue;
    const label = objString(obj, 'label');
    rawEdges.push({ from: fromC, to: toC, label: label !== null ? cleanText(label, DIAGRAM_EDGE_LABEL_MAX_CHARS) : null });
  }
  const limit = type === 'process_flow' || type === 'causal_chain' ? MAX_LINEAR_DIAGRAM_EDGES : MAX_RELATION_DIAGRAM_EDGES;
  const edgeSeen = new Set<string>();
  const edges: DiagramEdge[] = [];
  for (const e of rawEdges) {
    const key = `${e.from}->${e.to}`;
    if (edgeSeen.has(key)) continue;
    edgeSeen.add(key);
    edges.push(e);
    if (edges.length >= limit) break;
  }
  const reason = objString(o, 'reason');
  const caption = objString(o, 'caption');
  return {
    type,
    title,
    reason: reason !== null ? cleanText(reason, 220) : null,
    nodes,
    edges,
    caption: caption !== null ? cleanText(caption, HERO_CAPTION_MAX) : null,
  };
};

export const diagramDropReason = (o: Record<string, unknown>): string => {
  const typeRaw = objString(o, 'type');
  const type = typeRaw !== null ? typeRaw.toLowerCase() : null;
  if (type === null || !DIAGRAM_TYPES.has(type)) return 'invalid type';
  const titleRaw = objString(o, 'title');
  if (titleRaw === null || cleanText(titleRaw, DIAGRAM_TITLE_MAX_CHARS).length === 0) return 'title missing';
  const acceptedNodes = objObjectList(o, 'nodes').filter(obj => objString(obj, 'id') !== null && objString(obj, 'label') !== null);
  if (acceptedNodes.length < 2) return 'nodes < 2';
  return 'type, title, nodes';
};

export const buildDiagramDropReasons = (rawNodes: number, nodes: number, rawEdges: number, edges: number): string[] => {
  const out: string[] = [];
  if (rawNodes > nodes) out.push(`nodes dropped: missing id/label, duplicate id, or truncated limit=${MAX_DIAGRAM_NODES}`);
  if (rawEdges > edges) out.push('edges dropped: unknown node, self edge, duplicate edge, or truncated limit');
  return out;
};

export const candidateForUrl = (imageCandidates: ScoredImageCandidate[], url: string): ScoredImageCandidate | null =>
  imageCandidates.find(c => c.url === url) ?? null;

export const isHeroCandidate = (imageCandidates: ScoredImageCandidate[], url: string): boolean =>
  candidateForUrl(imageCandidates, url)?.confidence === IMAGE_CONFIDENCE.HERO;

// withCandidateEvidence — 照搬 Android:asset 必须在候选池且非 reject
export const withCandidateEvidence = (asset: DeepReadImageAsset, imageCandidates: ScoredImageCandidate[], topicTitle: string): DeepReadImageAsset | null => {
  const candidate = candidateForUrl(imageCandidates, asset.url);
  if (candidate === null) return null;
  if (candidate.confidence === IMAGE_CONFIDENCE.REJECT) return null;
  return {
    ...asset,
    source: asset.source ?? null,
    confidence: candidate.confidence,
    score: candidate.score,
    qualityHint: asset.qualityHint ?? candidate.confidence,
    selectionReason: asset.selectionReason ?? selectionReasonFor(candidate, topicTitle),
  };
};

// ===== visualDiagnostics 构造(照搬 Android buildVisualDiagnostics) =====
export const selectionReasonFor = (c: ScoredImageCandidate, topicTitle: string): string => {
  switch (c.confidence) {
    case IMAGE_CONFIDENCE.HERO: return `候选图与「${topicTitle}」的标题实体或事件词匹配，且未命中 logo/icon 风险。`;
    case IMAGE_CONFIDENCE.INLINE: return '候选图可作为正文上下文图，但标题相关性不足以做头图。';
    default: return c.riskFlags.length > 0 ? c.riskFlags.join('、') : '图片相关性或质量不足。';
  }
};

export const buildVisualDiagnostics = (
  previous: DeepReadOutput['visualDiagnostics'],
  heroCandidate: ScoredImageCandidate | null,
  heroReason: string | null,
  inlineAssets: DeepReadImageAsset[],
  imageCandidates: ScoredImageCandidate[] = [],
): DeepReadOutput['visualDiagnostics'] => {
  // candidateCount/rejectedImages 基于完整候选池(inline 子集会低报,
  // reject 候选此前恒空 — 诊断失真)
  const prevCount = previous?.candidateCount ?? 0;
  const selectedUrls = new Set<string>(inlineAssets.map(a => a.url));
  if (heroCandidate !== null) selectedUrls.add(heroCandidate.url);
  const rejected = imageCandidates
    .filter(c => !selectedUrls.has(c.url) && c.confidence === 'reject')
    .map(c => ({ url: c.url, reason: c.selectionReason, score: c.score }));
  return {
    candidateCount: Math.max(prevCount, Math.max(imageCandidates.length, inlineAssets.length)),
    heroSelection: heroCandidate !== null ? {
      url: heroCandidate.url,
      reason: heroReason ?? selectionReasonFor(heroCandidate, ''),
    } : (previous?.heroSelection ?? null),
    inlineSelections: inlineAssets.map(a => ({ url: a.url, reason: a.selectionReason ?? '' })).slice(0, 6),
    rejectedImages: rejected.length > 0 ? rejected : (previous?.rejectedImages ?? []),
  };
};

