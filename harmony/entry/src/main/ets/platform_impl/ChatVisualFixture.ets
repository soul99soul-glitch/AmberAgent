// Debug replay; provider calls are disabled. RDB writing requires the explicit test persistence flag.
import { Want } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import BuildProfile from 'BuildProfile';
import type {
  Conversation, MiniAppGeneratedOutput, MiniAppRecord, ThreeSceneObject, ThreeSceneSpec,
  UIMessage, UIMessagePartMiniApp, UIMessagePartTool,
} from '@amber/chat-domain';
import {
  makeConversation, makeUIMessage, makeUserMessage, makeMessageNode, nowIso, patchConversation, toMessageNode,
} from '@amber/chat-domain';
import { getConversationRunService, getChatRepository } from '../di/AppContainer.ets';
import { DEBUG_VISUAL_CONVERSATION_PREFIX } from './ConversationRunService.ets';
import { getMiniAppPlatform } from './MiniAppPlatform.ets';
import { CHAT_VISUAL_WEATHER_FIXTURE } from './ChatVisualWeatherFixture.ets';
import {
  debugSmartUiComponentGalleryPayload, debugSmartUiLinkagePayload, debugSmartUiStudyPayload, debugSmartUiTaskPayload,
} from './ChatSmartUiComponentFixture.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.VisualFixture';
const FRAME_COUNT: number = 24;
const FRAME_STEP_MS: number = 600;
const DEBUG_APP_TITLE: string = 'Amber Debug';
const ANSWER_SUFFIX: string = '-answer';

type VisualFixtureCase = 'svg' | 'three' | 'miniapp' | 'widget-error' | 'raw-svg' | 'raw-three' | 'smart-ui' | 'ui-svg-error' | 'weather-ui' | 'style-gallery' | 'component-gallery' | 'task-ui' | 'study-ui' | 'linkage-ui' | 'mixed';

interface VisualFixtureData {
  initial: Conversation;
  frames: UIMessage[];
  finalConversation: Conversation;
}

const activeRuns: Set<string> = new Set<string>();

const isVisualFixtureCase = (value: string): boolean =>
  value === 'svg' || value === 'three' || value === 'miniapp' || value === 'widget-error' ||
    value === 'raw-svg' || value === 'raw-three' || value === 'smart-ui' || value === 'ui-svg-error' ||
    value === 'weather-ui' || value === 'style-gallery' || value === 'component-gallery' || value === 'task-ui' ||
    value === 'study-ui' || value === 'linkage-ui' || value === 'mixed';

const conversationIdFor = (runId: string): string => `${DEBUG_VISUAL_CONVERSATION_PREFIX}${runId}`;
const answerIdFor = (runId: string): string => `debug-visual-${runId}${ANSWER_SUFFIX}`;

const frameMessage = (id: string, text: string): UIMessage => makeUIMessage('assistant', [
  { type: 'text', text, metadata: null },
], { id, finishedAt: null });

interface WidgetPayload {
  title: string;
  renderer: string;
  widget_code?: string;
  spec?: ThreeSceneSpec;
}

const streamFrames = (id: string, content: string): UIMessage[] => {
  const frames: UIMessage[] = [];
  for (let index: number = 1; index <= FRAME_COUNT; index++) {
    const end: number = Math.max(1, Math.ceil(content.length * index / FRAME_COUNT));
    frames.push(frameMessage(id, content.substring(0, end)));
  }
  return frames;
};

const svgPayload = (): string => {
  const svg: string = '<svg viewBox="0 0 680 320" xmlns="http://www.w3.org/2000/svg">' +
    '<rect width="680" height="320" rx="24" fill="#10131a"/>' +
    '<circle cx="150" cy="160" r="72" fill="#7c6cff"/>' +
    '<path d="M260 220 C340 60 440 60 520 220" fill="none" stroke="#55d6be" stroke-width="18"/>' +
    '<text x="340" y="150" text-anchor="middle" fill="#fff" font-size="30">Amber visual</text>' +
    '</svg>';
  const payload: WidgetPayload = {
    title: '渐进 SVG', renderer: 'svg', widget_code: svg,
  };
  return '```show-widget\n' + JSON.stringify(payload) + '\n```';
};

const threePayload = (): string => {
  const objects: ThreeSceneObject[] = [
    { id: 'cube', type: 'box', color: '#7c6cff', position: [-1.1, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], size: [1, 1, 1] },
    { id: 'orb', type: 'sphere', color: '#55d6be', position: [1.1, 0.2, 0], rotation: [0, 0, 0], scale: [1, 1, 1], size: [0.72] },
    { id: 'ring', type: 'torus', color: '#ffb45e', position: [0, 0.8, -0.7], rotation: [0.6, 0, 0], scale: [1, 1, 1], size: [0.75, 0.18] },
  ];
  const spec: ThreeSceneSpec = { objects };
  const payload: WidgetPayload = {
    title: '渐进 Three.js 场景', renderer: 'threejs', spec: spec,
  };
  return '```show-widget\n' + JSON.stringify(payload) + '\n```';
};

const rawSvgPayload = (): string => '```amber-svg v=1 id=ca-chain\n' +
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 360 150">' +
  '<rect x="8" y="18" width="120" height="52" rx="10" fill="#b8623a"/>' +
  '<text x="20" y="50" fill="#fff" font-size="18">根证书</text>' +
  '<path d="M132 44H218" stroke="#b8623a" stroke-width="3"/>' +
  '<rect x="222" y="18" width="130" height="52" rx="10" fill="#3d6657"/>' +
  '<text x="230" y="50" fill="#fff" font-size="18">服务器证书</text></svg>\n```';

const rawThreePayload = (): string => {
  const widgetText: string = threePayload();
  const start: number = widgetText.indexOf('\n');
  const payload: WidgetPayload = JSON.parse(widgetText.substring(start + 1, widgetText.lastIndexOf('\n'))) as WidgetPayload;
  return '```amber-three v=1 id=ca-scene\n' + JSON.stringify(payload.spec) + '\n```';
};

const smartUiCertificatePayload = (): string => '```amber-ui v=1 id=ca-controls\n' +
  '{"op":"state","id":"selected","initial":"root"}\n' +
  '{"op":"state","id":"note","initial":""}\n' +
  '{"op":"node","id":"root","type":"column"}\n' +
  '{"op":"node","id":"heading","parent":"root","type":"text","variant":"heading","text":"企业 CA 证书链"}\n' +
  '{"op":"node","id":"status","parent":"root","type":"text","variant":"badge","tone":"info","text":"本地交互 · 无需再次生成"}\n' +
  '{"op":"node","id":"intro","parent":"root","type":"text","variant":"caption","text":"选择一层证书，查看它在信任链中的职责。"}\n' +
  '{"op":"node","id":"overview","parent":"root","type":"column","layout":"row"}\n' +
  '{"op":"node","id":"layers","parent":"overview","type":"column","variant":"group","tone":"accent"}\n' +
  '{"op":"node","id":"layer-value","parent":"layers","type":"text","variant":"metric","tone":"accent","text":"3"}\n' +
  '{"op":"node","id":"layer-label","parent":"layers","type":"text","variant":"caption","text":"证书层级"}\n' +
  '{"op":"node","id":"scope","parent":"overview","type":"column","variant":"group","tone":"info"}\n' +
  '{"op":"node","id":"scope-value","parent":"scope","type":"text","variant":"metric","text":"内网"}\n' +
  '{"op":"node","id":"scope-label","parent":"scope","type":"text","variant":"caption","text":"信任范围"}\n' +
  '{"op":"node","id":"choice","parent":"root","type":"choice","variant":"chips","state":"selected","options":[{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"}]}\n' +
  '{"op":"node","id":"choice","parent":"root","type":"choice","variant":"chips","state":"selected","options":[{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"},{"value":"intermediate","label":"中间证书"}]}\n' +
  '{"op":"node","id":"details","parent":"root","type":"column","variant":"group","tone":"accent"}\n' +
  '{"op":"node","id":"root-detail","parent":"details","type":"text","text":"根证书负责建立企业信任锚点。","visibleWhen":{"state":"selected","equals":"root"}}\n' +
  '{"op":"node","id":"server-detail","parent":"details","type":"text","text":"服务器证书证明当前服务端身份。","visibleWhen":{"state":"selected","equals":"server"}}\n' +
  '{"op":"node","id":"intermediate-detail","parent":"details","type":"text","text":"中间证书连接根证书与服务器证书。","visibleWhen":{"state":"selected","equals":"intermediate"}}\n' +
  '{"op":"node","id":"chain-code","parent":"root","type":"text","variant":"code","text":"根 CA → 中间 CA → 服务器证书"}\n' +
  '{"op":"node","id":"note-field","parent":"root","type":"input","state":"note","placeholder":"还想了解什么？"}\n' +
  '{"op":"node","id":"diagram","parent":"root","type":"asset","ref":"ca-chain","label":"证书链图示"}\n' +
  '{"op":"node","id":"explain","parent":"root","type":"button","label":"继续解释","action":{"type":"draft","text":"请继续解释证书链中的 {{selected}}。我的问题：{{note}}"}}\n' +
  '{"op":"end"}\n```';

const styleGalleryPayload = (): string => '```amber-ui v=1 id=style-gallery\n' +
  '{"op":"state","id":"selected","initial":"root"}\n' +
  '{"op":"state","id":"mode","initial":"brief"}\n' +
  '{"op":"state","id":"topic","initial":"chain"}\n' +
  '{"op":"state","id":"note","initial":""}\n' +
  '{"op":"node","id":"root","type":"column"}\n' +
  '{"op":"node","id":"eyebrow","parent":"root","type":"text","variant":"eyebrow","tone":"accent","text":"原生交互 · 样式组合"}\n' +
  '{"op":"node","id":"hero","parent":"root","type":"text","variant":"hero","text":"信任，从根开始"}\n' +
  '{"op":"node","id":"lead","parent":"root","type":"text","variant":"lead","text":"用分组、排版和控件形态组织内容，选择与说明在卡片内直接联动。"}\n' +
  '{"op":"node","id":"stats","parent":"root","type":"column","layout":"grid"}\n' +
  '{"op":"node","id":"outlined","parent":"stats","type":"column","variant":"outlined","tone":"accent"}\n' +
  '{"op":"node","id":"count","parent":"outlined","type":"text","variant":"metric","text":"3"}\n' +
  '{"op":"node","id":"count-label","parent":"outlined","type":"text","variant":"caption","text":"证书层级 · 描边"}\n' +
  '{"op":"node","id":"elevated","parent":"stats","type":"column","variant":"elevated"}\n' +
  '{"op":"node","id":"domain","parent":"elevated","type":"text","variant":"metric","text":"内网"}\n' +
  '{"op":"node","id":"domain-label","parent":"elevated","type":"text","variant":"caption","text":"信任范围 · 浮起"}\n' +
  '{"op":"node","id":"section","parent":"root","type":"column","variant":"section","tone":"info"}\n' +
  '{"op":"node","id":"section-title","parent":"section","type":"text","variant":"subheading","text":"选择证书层级"}\n' +
  '{"op":"node","id":"tiles","parent":"section","type":"choice","variant":"tiles","state":"selected","options":[{"value":"root","label":"根证书"},{"value":"intermediate","label":"中间证书"},{"value":"server","label":"服务器证书"},{"value":"long","label":"用于企业内部身份校验的完整证书链"}]}\n' +
  '{"op":"node","id":"callout","parent":"root","type":"column","variant":"callout","tone":"warning"}\n' +
  '{"op":"node","id":"quote","parent":"callout","type":"text","variant":"quote","text":"信任根证书，才能沿着签名链验证服务器身份。"}\n' +
  '{"op":"node","id":"root-description","parent":"root","type":"text","text":"根证书是信任链起点。","visibleWhen":{"state":"selected","equals":"root"}}\n' +
  '{"op":"node","id":"server-description","parent":"root","type":"text","text":"服务器证书证明服务端身份。","visibleWhen":{"state":"selected","equals":"server"}}\n' +
  '{"op":"node","id":"mode-title","parent":"root","type":"text","variant":"caption","text":"解释深度 · 分段选择"}\n' +
  '{"op":"node","id":"segments","parent":"root","type":"choice","variant":"segmented","state":"mode","options":[{"value":"brief","label":"简明"},{"value":"technical","label":"技术细节"},{"value":"examples","label":"举例"}]}\n' +
  '{"op":"node","id":"long-chip","parent":"root","type":"choice","variant":"chips","state":"topic","options":[{"value":"chain","label":"验证企业内部根证书、中间证书与服务器证书的完整签名链，并判断每一层证书的签名者、有效期与适用范围"}]}\n' +
  '{"op":"node","id":"input","parent":"root","type":"input","variant":"underline","state":"note","placeholder":"写下你想进一步了解的问题"}\n' +
  '{"op":"node","id":"actions","parent":"root","type":"column","layout":"row"}\n' +
  '{"op":"node","id":"ghost","parent":"actions","type":"button","variant":"ghost","label":"整理选择","action":{"type":"draft","text":"请整理 {{selected}} 的用途。"}}\n' +
  '{"op":"node","id":"pill","parent":"actions","type":"button","variant":"pill","label":"继续解释","action":{"type":"draft","text":"请用 {{mode}} 解释 {{selected}}。补充：{{note}}"}}\n' +
  '{"op":"end"}\n' +
  '```';

const weatherUiPayload = (): string => '```amber-ui v=1 id=weather-controls\n' +
  '{"op":"state","id":"activity","initial":"walk"}\n' +
  '{"op":"state","id":"note","initial":""}\n' +
  '{"op":"node","id":"panel","type":"column"}\n' +
  '{"op":"node","id":"heading","parent":"panel","type":"text","variant":"heading","text":"上海出行 · 天气快照"}\n' +
  '{"op":"node","id":"weather-metrics","parent":"panel","type":"column","variant":"group","layout":"grid","tone":"info"}\n' +
  '{"op":"node","id":"temperature","parent":"weather-metrics","type":"text","variant":"metric","tone":"accent","text":"气温","bind":{"ref":"weather_weather-shanghai","path":"temperature_celsius"}}\n' +
  '{"op":"node","id":"condition","parent":"weather-metrics","type":"text","text":"天气","bind":{"ref":"weather_weather-shanghai","path":"condition"}}\n' +
  '{"op":"node","id":"observed","parent":"panel","type":"text","variant":"caption","text":"观测时间","bind":{"ref":"weather_weather-shanghai","path":"observed_at"}}\n' +
  '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"activity","options":[{"value":"walk","label":"散步"},{"value":"commute","label":"通勤"}]}\n' +
  '{"op":"node","id":"note-field","parent":"panel","type":"input","state":"note","placeholder":"补充出行需求"}\n' +
  '{"op":"node","id":"draft","parent":"panel","type":"button","label":"准备出行问题","action":{"type":"draft","text":"请结合上方天气说明 {{activity}} 的注意事项。备注：{{note}}"}}\n' +
  '{"op":"end"}\n```';

const miniAppHtml = (): string => '<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body>' +
  '<section style="padding:24px;border-radius:20px;background:#10131a;color:#fff">' +
  '<h1 style="margin:0 0 12px;color:#55d6be">Amber Mini App</h1>' +
  '<p style="margin:0;color:#d8dbe8">这是 Debug 回放生成的小应用卡片。</p>' +
  '<p id="count">0</p><button onclick="document.getElementById(\'count\').textContent=String(++count)">增加</button>' +
  '</section><script>let count=0;</script></body></html>';

export const debugVisualMiniAppHtml = (): string => miniAppHtml();

const baseConversation = (conversationId: string, prompt: string): Conversation => {
  const user: UIMessage = makeUserMessage(prompt);
  return makeConversation(conversationId, [toMessageNode(user)], { title: '视觉回放' });
};

const buildVisualFixture = async (runId: string, kind: VisualFixtureCase): Promise<VisualFixtureData> => {
  const conversationId: string = conversationIdFor(runId);
  const answerId: string = answerIdFor(runId);
  const prompt: string = kind === 'miniapp'
    ? '请生成一个小应用，展示 Amber 的视觉回放。'
    : kind === 'component-gallery' ? '展示八类原生智能 UI 组件，使用本地示例数据。'
    : kind === 'task-ui' ? '做一张学习决策卡，支持选择本地方案、调整时间预算、填写约束，并准备聊天草稿。'
    : kind === 'study-ui' ? '用学习卡帮我复习明朝十六帝，包含翻卡和选择题。'
    : kind === 'linkage-ui' ? '用滑块调整预算，并让指标、进度和图表跟随。'
    : kind === 'style-gallery' ? '展示可组合的原生样式，包括卡片、引用、选项、按钮和输入框。'
    : kind === 'smart-ui' ? '请做一张“企业 CA 证书链”的原生智能 UI 交互卡片：\n\n' +
      '1. 提供“根证书 / 中间证书 / 服务器证书”三个选项。\n' +
      '2. 点击选项后，在卡片内立即切换对应的作用说明。\n' +
      '3. 提供一个输入框，让我填写想进一步了解的问题。\n' +
      '4. 提供“继续解释”按钮，将当前选择和输入内容追加到聊天草稿，发送由我确认。\n' +
      '5. 单独生成一张 SVG 证书链图，卡片中提供“查看图示”入口。\n\n' +
      '控件使用 amber-ui v=1，图示使用独立的 amber-svg v=1。'
    : '请展示一个逐步生成的视觉组件。';
  let initial: Conversation = baseConversation(conversationId, prompt);
  if (kind === 'weather-ui') {
    const part: UIMessagePartTool = {
      type: 'tool', toolCallId: 'weather-shanghai', toolName: 'weather_read',
      input: '{"latitude":31.23,"longitude":121.47}',
      output: [{ type: 'text', text: CHAT_VISUAL_WEATHER_FIXTURE.result, metadata: null }],
      approvalState: { type: 'auto' }, metadata: null,
    };
    const message: UIMessage = makeUIMessage('assistant', [part], {
      id: `debug-weather-${runId}`, finishedAt: nowIso(),
    });
    initial = patchConversation(initial, {
      messageNodes: initial.messageNodes.concat([makeMessageNode([message], 0, `debug-weather-node-${runId}`)]),
    });
  }
  if (kind === 'miniapp') {
    const html: string = miniAppHtml();
    const output: MiniAppGeneratedOutput = {
      title: DEBUG_APP_TITLE, description: 'Debug 视觉回放临时小应用', icon: '▣',
      category: 'custom', permissions: [], html,
    };
    const platform = await getMiniAppPlatform();
    const record: MiniAppRecord = await platform.repository.saveGenerated(output, conversationId, answerId);
    const part: UIMessagePartMiniApp = {
      type: 'mini_app', appId: record.id, title: record.title, description: record.description,
      iconEmoji: record.iconEmoji, category: record.category, permissions: record.permissions,
      htmlHash: record.htmlHash, version: record.version, metadata: null,
    };
    const raw: string = JSON.stringify({
      title: DEBUG_APP_TITLE, description: 'Debug 视觉回放临时小应用', icon: '▣',
      category: 'custom', permissions: [], html,
    });
    const finalMessage: UIMessage = makeUIMessage('assistant', [
      { type: 'text', text: '已生成小应用：Amber Debug', metadata: null }, part,
    ], { id: answerId, finishedAt: nowIso() });
    return {
      initial, frames: streamFrames(answerId, raw),
      finalConversation: patchConversation(initial, {
        messageNodes: initial.messageNodes.concat([toMessageNode(finalMessage)]), updateAt: nowIso(),
      }),
    };
  }
  const content: string = kind === 'widget-error' ? svgPayload().slice(0, -12) :
    kind === 'raw-svg' ? rawSvgPayload() : kind === 'raw-three' ? rawThreePayload() :
    kind === 'smart-ui' ? smartUiCertificatePayload() + '\n\n' + rawSvgPayload() :
    kind === 'ui-svg-error' ? smartUiCertificatePayload() + '\n\n' + rawSvgPayload().replace('</svg>', '</g>') :
    kind === 'weather-ui' ? weatherUiPayload() : kind === 'style-gallery' ? styleGalleryPayload() :
    kind === 'component-gallery' ? debugSmartUiComponentGalleryPayload() : kind === 'task-ui' ? debugSmartUiTaskPayload() :
    kind === 'study-ui' ? debugSmartUiStudyPayload() : kind === 'linkage-ui' ? debugSmartUiLinkagePayload() :
    kind === 'mixed' ? '下面是证书链交互卡、独立矢量图和三维场景。\n\n' +
      smartUiCertificatePayload() + '\n\n' + rawSvgPayload() + '\n\n' + rawThreePayload() :
    kind === 'svg' ? svgPayload() : threePayload();
  const finalMessage: UIMessage = makeUIMessage('assistant', [
    { type: 'text', text: content, metadata: null },
  ], { id: answerId, finishedAt: nowIso() });
  return {
    initial, frames: streamFrames(answerId, content),
    finalConversation: patchConversation(initial, {
      messageNodes: initial.messageNodes.concat([makeMessageNode([finalMessage], 0, `debug-answer-node-${runId}`)]), updateAt: nowIso(),
    }),
  };
};

const cleanupMiniAppFixture = async (runId: string): Promise<void> => {
  const conversationId: string = conversationIdFor(runId);
  const snapshot = getConversationRunService().snapshot(conversationId);
  if (snapshot !== null && snapshot.sending) {
    getConversationRunService().cancel(conversationId, snapshot.runId);
  }
  const platform = await getMiniAppPlatform();
  const sourceConversationId: string = conversationId;
  const sourceMessageId: string = answerIdFor(runId);
  let deleted: number = 0;
  const records: MiniAppRecord[] = await platform.repository.listAll();
  for (const record of records) {
    if (record.title !== DEBUG_APP_TITLE || record.sourceConversationId !== sourceConversationId ||
      record.sourceMessageId !== sourceMessageId) continue;
    await platform.repository.delete(record.id);
    deleted++;
  }
  activeRuns.delete(runId);
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify({ runId, stage: 'cleanup', deleted }));
};

const startVisualFixture = async (runId: string, kind: VisualFixtureCase, persist: boolean): Promise<void> => {
  const data: VisualFixtureData = await buildVisualFixture(runId, kind);
  const handle = getConversationRunService().replayDebugVisual(
    data.initial, data.frames, data.finalConversation, FRAME_STEP_MS);
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify({ runId, kind, stage: 'started' }));
  await handle.done;
  if (persist) await getChatRepository().save(data.finalConversation);
  hilog.info(DOMAIN, TAG, '%{public}s', JSON.stringify({ runId, kind, stage: 'finished' }));
};

export const runChatVisualFixture = (want: Want): void => {
  if (!BuildProfile.DEBUG) return;
  const kind: string = String(want.parameters?.['amberChatVisualFixture'] ?? '').trim();
  const runId: string = String(want.parameters?.['amberChatVisualRunId'] ?? '').trim();
  if (!/^[A-Za-z0-9]{1,32}$/.test(runId)) return;
  if (kind === 'cleanup') {
    cleanupMiniAppFixture(runId).catch((): void => {
      hilog.error(DOMAIN, TAG, '%{public}s', JSON.stringify({ runId, stage: 'cleanup_failed' }));
    });
    return;
  }
  if (!isVisualFixtureCase(kind) || activeRuns.has(runId)) return;
  activeRuns.add(runId);
  const persist: boolean = String(want.parameters?.['amberChatVisualPersist'] ?? '') === 'true';
  startVisualFixture(runId, kind as VisualFixtureCase, persist).catch((): void => {
    activeRuns.delete(runId);
    hilog.error(DOMAIN, TAG, '%{public}s', JSON.stringify({ runId, kind, stage: 'failed' }));
  });
};
