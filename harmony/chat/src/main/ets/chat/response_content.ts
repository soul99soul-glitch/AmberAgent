// One message-level router for new declared content and the legacy widget protocol.
// The source message remains unchanged; these segments are a disposable native projection.
import type { JsonValue } from './json.ts';
import {
  parseGenerativeWidgets,
  sanitizeWidgetHtml,
  completedThreeObjects,
} from './generative_widget.ts';
import type { GenerativeWidget, GenerativeWidgetSegment } from './generative_widget.ts';
import {
  normalizeThreeScene,
  renderThreeSceneCover,
} from './three_scene.ts';
import type { ThreeSceneSpec } from './three_scene.ts';
import { parseSmartUi } from './smart_ui/protocol.ts';
import type { SmartUiDiagnostic, SmartUiDocument } from './smart_ui/protocol.ts';
import { utf8ByteLength } from './miniapp/miniapp_models.ts';

export type ResponseContentSegmentType = 'text' | 'ui' | 'svg' | 'three' | 'widget';
export type ResponseContentStatus = 'partial' | 'ready' | 'error';
export type VisualArtifactType = 'svg' | 'three';
export type ResponseContentDiagnosticSeverity = 'warning' | 'error';

export interface ResponseContentDiagnostic {
  code: string;
  message: string;
  line: number;
  severity: ResponseContentDiagnosticSeverity;
  ref?: string;
  nodeId?: string;
}

/** A native preview input. XML validation remains the trusted renderer's job. */
export interface VisualArtifact {
  type: VisualArtifactType;
  status: ResponseContentStatus;
  closed: boolean;
  sourceStart: number;
  id: string;
  raw: string;
  content: string;
  snapshot: string | null;
  scene: ThreeSceneSpec | null;
}

/** Flat projection consumed by native UI; nullable fields make the route explicit. */
export interface ResponseContentSegment {
  type: ResponseContentSegmentType;
  status: ResponseContentStatus;
  closed: boolean;
  complete: boolean;
  draftEnabled: boolean;
  sourceStart: number;
  id: string | null;
  raw: string;
  content: string;
  ui: SmartUiDocument | null;
  artifact: VisualArtifact | null;
  widget: GenerativeWidget | null;
  diagnostics: ResponseContentDiagnostic[];
  assetDiagnostics: ResponseContentDiagnostic[];
}

export type ResponseContentAssetRegistry = ReadonlyArray<VisualArtifact>;

const ID_PATTERN: RegExp = /^[A-Za-z][A-Za-z0-9_-]{0,47}$/;
const MAX_SVG_CHARS: number = 120_000;
const MAX_THREE_BYTES: number = 120_000;
const LEGACY_LANGUAGES: string[] = ['show-widget', 'widget', 'generative-ui'];

type DeclaredKind = 'ui' | 'svg' | 'three' | 'legacy';

interface ParsedHeader {
  kind: DeclaredKind;
  id: string | null;
  diagnostics: ResponseContentDiagnostic[];
}

interface FenceBlock {
  marker: string;
  length: number;
  header: string;
  bodyStart: number;
  bodyEnd: number;
  end: number;
  closed: boolean;
  sourceStart: number;
}

interface FenceHeaderInfo {
  language: string;
  args: string[];
}

const diagnostic = (
  code: string,
  message: string,
  line: number = 1,
  severity: ResponseContentDiagnosticSeverity = 'error',
): ResponseContentDiagnostic => ({ code, message, line, severity });

const parseHeaderInfo = (header: string): FenceHeaderInfo => {
  const tokens: string[] = header.trim().length === 0 ? [] : header.trim().split(/[\t ]+/);
  return { language: (tokens[0] ?? '').toLowerCase(), args: tokens.slice(1) };
};

const languageKind = (language: string): DeclaredKind | null => {
  if (language === 'amber-ui') return 'ui';
  if (language === 'amber-svg') return 'svg';
  if (language === 'amber-three') return 'three';
  if (LEGACY_LANGUAGES.includes(language)) return 'legacy';
  return null;
};

const parseNewHeader = (info: FenceHeaderInfo, kind: DeclaredKind): ParsedHeader => {
  const diagnostics: ResponseContentDiagnostic[] = [];
  const values = new Map<string, string>();
  const seen = new Set<string>();
  for (const token of info.args) {
    const match: RegExpMatchArray | null = /^([A-Za-z][A-Za-z0-9_-]*)=(\S+)$/.exec(token);
    if (match === null) {
      diagnostics.push(diagnostic('unknown-argument', `无法识别参数 ${token}`));
      continue;
    }
    const name: string = match[1];
    if (seen.has(name)) {
      diagnostics.push(diagnostic('duplicate-argument', `参数 ${name} 重复`));
      continue;
    }
    seen.add(name);
    values.set(name, match[2]);
  }
  if (!seen.has('v')) diagnostics.push(diagnostic('missing-version', '缺少 v=1 参数'));
  else if (values.get('v') !== '1') diagnostics.push(diagnostic('unsupported-version', '只支持 v=1'));
  if (!seen.has('id')) diagnostics.push(diagnostic('missing-id', '缺少 id 参数'));
  const id: string | null = values.get('id') ?? null;
  if (id !== null && !ID_PATTERN.test(id)) diagnostics.push(diagnostic('invalid-id', 'id 必须是 ASCII 字母开头、最多 48 字符的 ID'));
  for (const name of seen) {
    if (name !== 'v' && name !== 'id') diagnostics.push(diagnostic('unknown-argument', `不支持参数 ${name}`));
  }
  return { kind, id: id !== null && ID_PATTERN.test(id) ? id : null, diagnostics };
};

const parseHeader = (header: string): ParsedHeader | null => {
  const info: FenceHeaderInfo = parseHeaderInfo(header);
  const kind: DeclaredKind | null = languageKind(info.language);
  if (kind === null) return null;
  if (kind === 'legacy') return { kind, id: null, diagnostics: [] };
  return parseNewHeader(info, kind);
};

const closingFence = (content: string, start: number, marker: string, length: number): RegExpExecArray | null => {
  const escaped: string = marker === '`' ? '`' : '~';
  const close: RegExp = new RegExp(`^[\\t ]*${escaped}{${length},}[\\t ]*(?:\\r?\\n|$)`, 'gm');
  close.lastIndex = start;
  return close.exec(content);
};

const nextFence = (content: string, opener: RegExp, start: number): FenceBlock | null => {
  opener.lastIndex = start;
  const match: RegExpExecArray | null = opener.exec(content);
  if (match === null) return null;
  const marker: string = match[1][0];
  const length: number = match[1].length;
  const bodyStart: number = opener.lastIndex;
  const close: RegExpExecArray | null = closingFence(content, bodyStart, marker, length);
  const bodyEnd: number = close === null ? content.length : close.index;
  const end: number = close === null ? content.length : close.index + close[0].length;
  return {
    marker,
    length,
    header: match[2],
    bodyStart,
    bodyEnd,
    end,
    closed: close !== null,
    sourceStart: match.index,
  };
};

const mapSmartUiDiagnostics = (items: SmartUiDiagnostic[]): ResponseContentDiagnostic[] => items.map(
  (item: SmartUiDiagnostic): ResponseContentDiagnostic => ({
    code: item.code,
    message: item.message,
    line: item.line,
    severity: item.severity,
  }),
);

const hasError = (items: ResponseContentDiagnostic[]): boolean =>
  items.some((item: ResponseContentDiagnostic): boolean => item.severity === 'error');

const textSegment = (content: string, sourceStart: number): ResponseContentSegment => ({
  type: 'text', status: 'ready', closed: true, complete: true, draftEnabled: false,
  sourceStart, id: null, raw: content, content, ui: null, artifact: null, widget: null,
  diagnostics: [], assetDiagnostics: [],
});

const artifactSegment = (
  type: 'svg' | 'three',
  block: FenceBlock,
  id: string | null,
  body: string,
  artifact: VisualArtifact | null,
  status: ResponseContentStatus,
  diagnostics: ResponseContentDiagnostic[],
): ResponseContentSegment => ({
  type, status, closed: block.closed,
  complete: block.closed && status === 'ready', draftEnabled: false,
  sourceStart: block.sourceStart, id, raw: body, content: body,
  ui: null, artifact, widget: null, diagnostics,
  assetDiagnostics: [],
});

const makeSvgSegment = (block: FenceBlock, id: string | null, body: string): ResponseContentSegment => {
  const diagnostics: ResponseContentDiagnostic[] = [];
  if (id === null) {
    diagnostics.push(diagnostic('invalid-block-header', 'SVG 块头无有效 ID'));
    return artifactSegment('svg', block, id, body, null, 'error', diagnostics);
  }
  if (body.length > MAX_SVG_CHARS) {
    diagnostics.push(diagnostic('content-limit', `SVG 内容超过 ${MAX_SVG_CHARS} 字符限制`));
    return artifactSegment('svg', block, id, body, null, 'error', diagnostics);
  }
  let cleaned: string;
  try {
    cleaned = sanitizeWidgetHtml(body.trim());
  } catch (error) {
    diagnostics.push(diagnostic('content-limit', error instanceof Error ? error.message : 'SVG 内容过大'));
    return artifactSegment('svg', block, id, body, null, 'error', diagnostics);
  }
  const roughCompleteRoot: boolean = /<svg\b[^>]*\/\s*>/i.test(cleaned) ||
    (/<svg\b[^>]*>/i.test(cleaned) && /<\/svg\s*>/i.test(cleaned));
  const hasCandidate: boolean = cleaned.trim().length > 0 && (block.closed || roughCompleteRoot);
  if (!block.closed && !roughCompleteRoot) {
    diagnostics.push(diagnostic('incomplete-svg-root', '等待 SVG 根元素', 1, 'warning'));
  }
  const artifact: VisualArtifact = {
    type: 'svg', status: 'partial', closed: block.closed, sourceStart: block.sourceStart,
    id, raw: body, content: cleaned,
    snapshot: hasCandidate ? cleaned : null, scene: null,
  };
  return artifactSegment('svg', block, id, body, artifact, 'partial', diagnostics);
};

/** Apply the trusted SVG result only to the still-current candidate snapshot. */
export const applySvgValidation = (
  segments: ReadonlyArray<ResponseContentSegment>,
  sourceStart: number,
  id: string,
  snapshot: string,
  ok: boolean,
  error: string = 'SVG 结构校验失败',
): ResponseContentSegment[] => {
  const index: number = segments.findIndex((segment: ResponseContentSegment): boolean =>
    segment.type === 'svg' && segment.sourceStart === sourceStart && segment.id === id &&
    segment.artifact !== null && segment.artifact.snapshot === snapshot);
  if (index < 0) return segments.slice();
  const current: ResponseContentSegment = segments[index];
  const currentArtifact: VisualArtifact = current.artifact as VisualArtifact;
  const diagnostics: ResponseContentDiagnostic[] = current.diagnostics.filter(
    (item: ResponseContentDiagnostic): boolean => item.code !== 'svg-validation-failed',
  );
  const artifact: VisualArtifact = { ...currentArtifact };
  if (!ok) {
    diagnostics.push(diagnostic('svg-validation-failed', error));
    artifact.status = 'error';
  } else {
    artifact.status = current.closed ? 'ready' : 'partial';
  }
  const next: ResponseContentSegment = {
    ...current,
    status: !ok ? 'error' : current.closed ? 'ready' : 'partial',
    complete: ok && current.closed,
    artifact,
    diagnostics,
  };
  const result: ResponseContentSegment[] = segments.slice();
  result[index] = next;
  return result;
};

const jsonString = (value: object): string => JSON.stringify(value) ?? '';

const makeThreeSegment = (block: FenceBlock, id: string | null, body: string): ResponseContentSegment => {
  const diagnostics: ResponseContentDiagnostic[] = [];
  if (id === null) {
    diagnostics.push(diagnostic('invalid-block-header', '3D 块头无有效 ID'));
    return artifactSegment('three', block, id, body, null, 'error', diagnostics);
  }
  if (utf8ByteLength(body) > MAX_THREE_BYTES) {
    diagnostics.push(diagnostic('content-limit', `3D 内容超过 ${MAX_THREE_BYTES} 字节限制`));
    return artifactSegment('three', block, id, body, null, 'error', diagnostics);
  }
  let scene: ThreeSceneSpec | null = null;
  let status: ResponseContentStatus = 'partial';
  if (block.closed) {
    try {
      const parsed: JsonValue = JSON.parse(body) as JsonValue;
      scene = normalizeThreeScene(parsed);
      if (scene === null) throw new Error('3D 场景结构无效或超出限制');
      status = 'ready';
    } catch (error) {
      diagnostics.push(diagnostic('invalid-three-scene', error instanceof Error ? error.message : '3D 场景结构无效'));
      status = 'error';
    }
  }
  if (scene === null) {
    // A malformed terminal tail must not remove objects already safe to preview.
    const objects = completedThreeObjects(body);
    if (objects.length > 0) scene = { objects };
  }
  if (scene === null) {
    return artifactSegment('three', block, id, body, null, status, diagnostics);
  }
  const artifact: VisualArtifact = {
    type: 'three', status, closed: block.closed, sourceStart: block.sourceStart,
    id, raw: body, content: jsonString(scene),
    snapshot: renderThreeSceneCover(scene), scene,
  };
  return artifactSegment('three', block, id, body, artifact, status, diagnostics);
};

const makeUiSegment = (
  block: FenceBlock,
  id: string | null,
  body: string,
  streaming: boolean,
): ResponseContentSegment => {
  const diagnostics: ResponseContentDiagnostic[] = [];
  if (id === null) {
    diagnostics.push(diagnostic('invalid-block-header', '智能 UI 块头无有效 ID'));
    return {
      type: 'ui', status: 'error', closed: block.closed, complete: false, draftEnabled: false,
      sourceStart: block.sourceStart, id, raw: body, content: body,
      ui: null, artifact: null, widget: null, diagnostics, assetDiagnostics: [],
    };
  }
  const document: SmartUiDocument = parseSmartUi(body, id, !streaming || block.closed);
  diagnostics.push(...mapSmartUiDiagnostics(document.diagnostics));
  const status: ResponseContentStatus = block.closed && document.status === 'error'
    ? 'error' : block.closed && document.status === 'ready' ? 'ready' : 'partial';
  const complete: boolean = block.closed && document.status === 'ready' && !hasError(diagnostics);
  return {
    type: 'ui', status, closed: block.closed, complete, draftEnabled: complete,
    sourceStart: block.sourceStart, id, raw: body, content: body,
      ui: document, artifact: null, widget: null, diagnostics, assetDiagnostics: [],
  };
};

const makeLegacySegments = (block: FenceBlock, body: string, streaming: boolean): ResponseContentSegment[] => {
  const source: string = blockSource(block, body);
  const legacy: GenerativeWidgetSegment[] = parseGenerativeWidgets(source, streaming);
  const result: ResponseContentSegment[] = [];
  for (const item of legacy) {
    const diagnostics: ResponseContentDiagnostic[] = [];
    const widget: GenerativeWidget | null = item.widget;
    let status: ResponseContentStatus = item.kind === 'error' ? 'error' : item.kind === 'loading' ? 'partial' : 'ready';
    if (!block.closed && item.kind === 'widget') status = 'partial';
    if (item.kind === 'error') diagnostics.push(diagnostic('legacy-widget-error', item.content || '旧组件无法显示'));
    const complete: boolean = block.closed && item.kind === 'widget' && widget !== null && widget.complete;
    result.push({
      type: 'widget', status, closed: block.closed, complete, draftEnabled: false,
      sourceStart: block.sourceStart + item.start, id: null, raw: body, content: item.content,
      ui: null, artifact: null, widget, diagnostics,
      assetDiagnostics: [],
    });
  }
  if (result.length === 0) {
    result.push({
      type: 'widget', status: 'partial', closed: block.closed, complete: false, draftEnabled: false,
      sourceStart: block.sourceStart, id: null, raw: body, content: body,
      ui: null, artifact: null, widget: null, diagnostics: [],
      assetDiagnostics: [],
    });
  }
  return result;
};

const blockSource = (block: FenceBlock, body: string): string => {
  // Reconstructing the exact opener is unnecessary for the old parser; its fence scanner only needs the body.
  return '```' + block.header + '\n' + body + (block.closed ? '\n```' : '');
};

const collectAssets = (segments: ReadonlyArray<ResponseContentSegment>): VisualArtifact[] => {
  const result: VisualArtifact[] = [];
  for (const segment of segments) if (segment.artifact !== null) result.push(segment.artifact);
  return result;
};

interface AssetReference {
  ref: string;
  nodeId: string;
}

const assetRefs = (document: SmartUiDocument): AssetReference[] => {
  const refs: AssetReference[] = [];
  for (const node of document.nodes) {
    if (node.type === 'asset' && node.ref !== undefined) refs.push({ ref: node.ref, nodeId: node.id });
  }
  return refs;
};

/** Validate refs using the caller's complete same-message registry, never another text group. */
export const validateResponseContentReferences = (
  document: SmartUiDocument,
  registry: ResponseContentAssetRegistry,
  messageClosed: boolean,
): ResponseContentDiagnostic[] => {
  const diagnostics: ResponseContentDiagnostic[] = [];
  for (const reference of assetRefs(document)) {
    const ref: string = reference.ref;
    const matches: VisualArtifact[] = registry.filter((item: VisualArtifact): boolean => item.id === ref);
    const withSlot = (item: ResponseContentDiagnostic): ResponseContentDiagnostic => ({
      ...item, ref, nodeId: reference.nodeId,
    });
    if (matches.length === 0) {
      diagnostics.push(withSlot(diagnostic(
        messageClosed ? 'asset-not-found' : 'asset-pending',
        messageClosed ? `找不到可视化资源 ${ref}` : `等待可视化资源 ${ref}`,
        0,
        messageClosed ? 'error' : 'warning',
      )));
    } else if (matches.length > 1) {
      diagnostics.push(withSlot(diagnostic('duplicate-asset-id', `可视化资源 ${ref} 的 ID 重复`)));
    } else if (matches[0].status !== 'ready' || !matches[0].closed || matches[0].snapshot === null) {
      diagnostics.push(withSlot(diagnostic(
        messageClosed ? 'asset-incomplete' : 'asset-pending',
        messageClosed ? `可视化资源 ${ref} 未完成` : `等待可视化资源 ${ref} 完成`,
        0,
        messageClosed ? 'error' : 'warning',
      )));
    }
  }
  return diagnostics;
};

const applyReferenceDiagnostics = (
  segments: ResponseContentSegment[],
  suppliedRegistry: ResponseContentAssetRegistry | undefined,
  messageClosed: boolean,
): void => {
  const localArtifacts: VisualArtifact[] = collectAssets(segments);
  const registry: ResponseContentAssetRegistry = suppliedRegistry ?? localArtifacts;
  const counts = new Map<string, number>();
  for (const segment of segments) {
    if ((segment.type === 'ui' || segment.type === 'svg' || segment.type === 'three') && segment.id !== null) {
      counts.set(segment.id, (counts.get(segment.id) ?? 0) + 1);
    }
  }
  for (const segment of segments) {
    if ((segment.type === 'ui' || segment.type === 'svg' || segment.type === 'three') &&
      segment.id !== null && (counts.get(segment.id) ?? 0) > 1) {
      segment.diagnostics.push(diagnostic('duplicate-id', `内容块 ${segment.id} 的 ID 重复`));
      segment.status = 'error';
      segment.complete = false;
      segment.draftEnabled = false;
      if (segment.artifact !== null) segment.artifact.status = 'error';
    }
    if (segment.ui === null) continue;
    const referenceDiagnostics: ResponseContentDiagnostic[] = validateResponseContentReferences(
      segment.ui, registry, messageClosed,
    );
    segment.assetDiagnostics = referenceDiagnostics;
    segment.diagnostics.push(...referenceDiagnostics);
  }
};

/** Parse one complete assistant message while preserving every segment's source order. */
export const parseResponseContent = (
  content: string,
  streaming: boolean,
  messageRegistry?: ResponseContentAssetRegistry,
  messageClosed: boolean = !streaming,
): ResponseContentSegment[] => {
  const segments: ResponseContentSegment[] = [];
  const opener: RegExp = /^[\t ]*(`{3,}|~{3,})([^\r\n]*)(?:\r?\n|$)/gm;
  let cursor: number = 0;
  let textStart: number = 0;
  while (true) {
    const block: FenceBlock | null = nextFence(content, opener, cursor);
    if (block === null) break;
    const header: ParsedHeader | null = parseHeader(block.header);
    if (header === null) {
      cursor = block.end;
      continue;
    }
    if (block.sourceStart > textStart) segments.push(textSegment(content.slice(textStart, block.sourceStart), textStart));
    const body: string = content.slice(block.bodyStart, block.bodyEnd);
    if (header.diagnostics.length > 0) {
      segments.push({
        type: header.kind === 'legacy' ? 'widget' : header.kind,
        status: 'error', closed: block.closed, complete: false, draftEnabled: false,
        sourceStart: block.sourceStart, id: header.id, raw: body, content: body,
        ui: null, artifact: null, widget: null, diagnostics: header.diagnostics, assetDiagnostics: [],
      });
    } else if (header.kind === 'ui') {
      segments.push(makeUiSegment(block, header.id, body, streaming));
    } else if (header.kind === 'svg') {
      segments.push(makeSvgSegment(block, header.id, body));
    } else if (header.kind === 'three') {
      segments.push(makeThreeSegment(block, header.id, body));
    } else {
      segments.push(...makeLegacySegments(block, body, streaming));
    }
    textStart = block.end;
    cursor = block.end;
  }
  if (textStart < content.length || segments.length === 0) segments.push(textSegment(content.slice(textStart), textStart));
  applyReferenceDiagnostics(segments, messageRegistry, messageClosed);
  return segments;
};

/** Remove only declared new-protocol blocks for MiniApp candidate detection. */
export const stripDeclaredResponseBlocks = (content: string): string => {
  const opener: RegExp = /^[\t ]*(`{3,}|~{3,})([^\r\n]*)(?:\r?\n|$)/gm;
  let cursor: number = 0;
  let output: string = '';
  let removed: boolean = false;
  while (true) {
    const block: FenceBlock | null = nextFence(content, opener, cursor);
    if (block === null) break;
    const info: FenceHeaderInfo = parseHeaderInfo(block.header);
    const kind: DeclaredKind | null = languageKind(info.language);
    if (kind !== null && kind !== 'legacy') {
      output += content.slice(cursor, block.sourceStart);
      cursor = block.end;
      removed = true;
    } else {
      output += content.slice(cursor, block.end);
      cursor = block.end;
    }
  }
  if (!removed) return content;
  output += content.slice(cursor);
  return output;
};

