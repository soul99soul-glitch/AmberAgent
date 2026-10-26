import {
  SMART_UI_COMPONENT_KINDS, componentKindAllowsChildren, componentKindHasOptions,
  componentKindIsNumeric, componentKindIsPresentationState, componentKindIsMultiSelection,
} from './smart_ui/components.ts';

const nativeComponentInstruction = (): string[] => [
  '- Use native task controls only when choosing or entering information helps the user proceed. If the task is already clear and authorized, complete it directly. Do not force a decision card into simple answers, use a card as a substitute for investigating facts, or present generated status as a verified tool result.',
  '- Rich native nodes use type:"component" with component:{kind,title?,text?,items?,rows?,study?,value?,valueFieldId?,min?,max?,step?,columns?,symbol?,url?,style?}. State, options, label, required, action, parent and visibleWhen remain on the node, outside component. No component id, field_id, children array, arbitrary properties or CSS.',
  `- Native component kinds: ${SMART_UI_COMPONENT_KINDS.join(', ')}.`,
  `- Container kinds: ${SMART_UI_COMPONENT_KINDS.filter(componentKindAllowsChildren).join(', ')}. Declare children as separate node records whose parent is the container node id; the root remains a regular column.`,
  `- Option controls: ${SMART_UI_COMPONENT_KINDS.filter(componentKindHasOptions).join(', ')}. Declare node.options:[{value,label,detail?,color?}]; use stable short string values for multiple selection. colorChoice uses accent|red|orange|yellow|green|cyan|blue|indigo|purple|pink|gray tokens.`,
  `- Numeric controls: ${SMART_UI_COMPONENT_KINDS.filter(componentKindIsNumeric).join(', ')} bind number states. Set finite component.min/max/step; defaults are 0/100/1 (rating 0/5/1, integer max 1-10). Initial and current values must stay in range and on the step grid.`,
  `- Multiple selection: ${SMART_UI_COMPONENT_KINDS.filter(componentKindIsMultiSelection).join(', ')} bind a string containing a JSON array of selected option values (accordion uses child node ids), e.g. initial:"[]". Do not use a raw array state. toggle/disclosure bind boolean; textField/textArea/datePicker/timePicker bind string; dates use YYYY-MM-DD and times HH:mm. tabs binds a child node id string.`,
  `- Presentation-only states: ${SMART_UI_COMPONENT_KINDS.filter(componentKindIsPresentationState).join(', ')} restore locally but are not task constraints. required:true marks a task field that must be answered; false is a valid toggle answer. Use label to name the field and title/text to explain it. Never ask for passwords or secrets.`,
  '- rows have {label,value?,valueFieldId?,detail?,cells?}; tables use cells, charts require finite value or valueFieldId in every row, timeline/steps use label/detail. valueFieldId references a declared number state and makes the row follow that input live; metric/progress/stepProgress may use component.valueFieldId the same way. Never invent formulas or executable expressions. items is an array of plain strings for lists. progress uses a finite value and max. Diagram uses a structured list of nodes/relationships as items, not SVG or script. Generated numbers are explanations unless explicitly grounded in a verified data binding.',
  '- study renders a local flashcard/quiz card: component.study:[{id,prompt,answer,detail?,options?,correctOptionId?}], 1-40 items; options 2-8 with stable ids; when options exist, correctOptionId is required. The host owns reveal, grading, next/previous, replay and progress; do not generate scripts or fake percentages. To persist progress, bind the study node to a declared string state via node.state. Use study for genuine learning/review tasks, not as decoration.',
  '- Rich styles use component.style:{tone:neutral|accent|success|warning|danger,emphasis:plain|soft|outlined|filled,density:compact|comfortable,alignment:leading|center,spacing:none|small|medium|large,padding:none|small|medium|large}. Choose a small, coherent set of styles for each task. The host controls actual colors, dimensions, focus and motion.',
  '- button/submitButton still require node.action:{type:"draft",text:"..."}; they fill the existing composer with a visible frozen snapshot of current task choices and inputs. The user reviews and sends it normally; no tools or network are called on tap. resetButton resets local fields to their declared defaults. Never imply that filling a draft executed an operation. A clear text follow-up such as “按这个继续” can carry the most recently edited valid card in the selected conversation branch.',
  'Rich component example:',
  '```amber-ui v=1 id=task-options',
  '{"op":"state","id":"mode","initial":"local"}',
  '{"op":"state","id":"budget","initial":50}',
  '{"op":"state","id":"note","initial":""}',
  '{"op":"node","id":"root","type":"column"}',
  '{"op":"node","id":"goal","parent":"root","type":"component","component":{"kind":"goal","title":"选择存储方案","text":"比较后补充约束，再继续分析。"}}',
  '{"op":"node","id":"mode-field","parent":"root","type":"component","label":"存储方案","state":"mode","options":[{"value":"local","label":"本地存储","detail":"离线可用"},{"value":"cloud","label":"云端存储","detail":"跨设备同步"}],"component":{"kind":"singleChoice"}}',
  '{"op":"node","id":"budget-field","parent":"root","type":"component","label":"每月预算","state":"budget","component":{"kind":"slider","min":0,"max":100,"step":10}}',
  '{"op":"node","id":"note-field","parent":"root","type":"component","label":"额外约束","required":true,"state":"note","component":{"kind":"textArea","text":"例如必须离线可用"}}',
  '{"op":"node","id":"continue","parent":"root","type":"component","label":"继续分析","action":{"type":"draft","text":"根据当前选择继续比较存储方案。"},"component":{"kind":"submitButton"}}',
  '{"op":"end"}',
  '```',
];

// Injected only when the chat can display visual cards.
export const GENERATIVE_WIDGET_INSTRUCTION: string = `
Visual cards on this HarmonyOS device:
- When a diagram, illustration, 3D scene or visual explanation helps, output one fenced show-widget JSON object. Ordinary answers remain ordinary text.
- SVG/HTML format: {"title":"short title","renderer":"svg","widget_code":"<svg viewBox='0 0 360 180'><text x='16' y='40'>Diagram</text></svg>"}. Use renderer:"html" for inline HTML/CSS. Use single quotes for markup attributes and JSON-escape any double quotes, backslashes and line breaks inside widget_code. Keep cards compact and close both the SVG and JSON object before closing the fence. Write styles and meaningful visible content first; append elements in reading order so the same card becomes useful during streaming. Keep stable element ids when revising. Do not produce a series of replacement cards.
- SVG/HTML previews are offline: scripts, native bridges and external resources do not run. Use mobile-sized text, responsive widths and real task content. Native actions may contain up to three {id,label,instruction} entries, which only fill the draft after a user tap.
- For 3D, use renderer:"threejs" and a declarative spec:{objects:[...],voxels?:[...]}. Put "renderer":"threejs" before "spec". The installed local Three.js renderer adds complete objects as they arrive and supports touch rotation and pinch zoom. Never emit JavaScript, CDN imports, loaders, textures or external models.
- Every 3D object has {id,type?,color,position,rotation?,scale?,size?}. id is unique and at most 48 characters; type is box|sphere|cylinder|cone|torus|plane (defaults to "box"); color is #rrggbb or #rgb. position and rotation are three finite numbers bounded by ±200 (rotation in radians, defaults to [0,0,0]). scale is three positive numbers at most 20 (defaults to [1,1,1]). size contains positive dimensions at most 100: box=[width,height,depth] (defaults to [1,1,1]), sphere=[radius], cylinder=[radius,height], cone=[radius,height], torus=[radius,tubeRadius], plane=[width,height]. For dense voxel scenes, you may also supply spec.voxels:[[x,y,z,"#rrggbb",w?,h?,d?],...]. Limit scenes to 512 total objects/voxels. Example: {"title":"结构示意","renderer":"threejs","spec":{"objects":[{"id":"core","type":"sphere","color":"#b8623a","position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1],"size":[1]}]}}.
- Interactive small apps use the existing MiniApp JSON protocol when explicitly requested. Their stream shows a visual preview; final execution goes through the app's Run action and permission checks.
- Multi-page full_html slide decks still require a complete spec.html; use brief progress while building them.
`;

/** The declared v1 prompt is opt-in until the model gates and native UI acceptance pass. */
export type GenerativeWidgetPromptMode = 'legacy' | 'declared-content-v1';

export const DECLARED_CONTENT_INSTRUCTION: string = [
  'Declared content on this HarmonyOS device (opt-in v1):',
  '- Ordinary explanations and answers stay ordinary Markdown/plain text. Do not emit a content fence just because the request contains a diagram, UI, or 3D keyword; follow the requested outcome.',
  '- When a task needs controls and a visual, emit separate declared blocks in the same answer. Smart UI owns controls and local state; amber-svg or amber-three owns the visual. Never put SVG/XML or Three.js scene data inside Smart UI JSON.',
  '- Every block id and node id is stable ASCII (a letter first, then letters, numbers, _ or -, at most 48 characters). Do not reuse an id for two blocks in one answer.',
  '',
  'Smart UI task controls:',
  '- Use exactly: ```amber-ui v=1 id=<block-id> followed by one complete JSON object per line and a final {"op":"end"} line. No comments, HTML, JavaScript, functions, expressions, patches, or unknown fields.',
  '- State comes first: declare every state before any node that references it. A state initial value is only a string, finite number, or boolean; input controls bind to string states.',
  '- The node types are column, text, choice, input, button, asset, component. root must be a column; child parent must be a previously declared column or a rich container component.',
  '- Compose native visual hierarchy with optional variant tokens: column plain|group|outlined|elevated|callout|section; text body|heading|subheading|caption|metric|code|badge|hero|lead|eyebrow|quote; choice list|chips|tiles|segmented; button primary|secondary|ghost|pill; input underline (omitting variant keeps the filled field). column also supports layout stack|row|grid: stack is vertical, row equally sizes 2-3 short items, grid has two equal columns. column and text support tone neutral|accent|success|warning|info; a non-neutral column tone creates a tinted section. Combine these fields independently for metric tiles, status sections, compact choices, and reading panels. Do not invent CSS, colors, dimensions, animation code, or other style fields. The host supplies theme and interaction motion. Prefer one heading, captions for hints, metric for a short key value, and one primary main action. Omitted fields use the standard appearance.',
  '- Choose styles for the task: outlined gives an outline; elevated a raised surface; callout a tinted panel with a semantic leading rule; section a leading rule without a background. hero is a large display title, lead an introductory paragraph, eyebrow a small spaced label, quote a serif quotation with a leading rule. tiles makes two-column option cards; segmented distributes short options equally, at most three per row. ghost is a lightweight text action; pill a filled capsule. Preserve 44vp touch targets and let long labels wrap. Avoid using every style in one card; combine related sections with a clear main action.',
  '- column: {"op":"node","id":"panel","type":"column"}',
  '- text: {"op":"node","id":"detail","parent":"panel","type":"text","text":"说明"}',
  '- choice: {"op":"node","id":"choice","parent":"panel","type":"choice","variant":"segmented","state":"selected","options":[{"value":"root","label":"根证书"}]}',
  '- input: {"op":"node","id":"name","parent":"panel","type":"input","state":"name","placeholder":"输入名称"}. Declare the string state name before this node; do not reuse a choice state unless it is intentionally the same field.',
  '- In v1, one state may bind to only one choice or input control. Text visibleWhen and button draft references may reuse states; do not create two controls with competing options for one state.',
  '- button: {"op":"node","id":"explain","parent":"panel","type":"button","label":"继续解释","action":{"type":"draft","text":"请继续解释 {{selected}}"}}. Buttons only fill the chat draft after a user tap; they do not call tools or the network.',
  '- asset: {"op":"node","id":"diagram","parent":"panel","type":"asset","ref":"ca-chain","label":"证书链图示"}. ref is the stable internal id; optional label is the user-facing name (1-120 characters) for the linked amber-svg or amber-three block.',
  '- text may use a static visibility condition: "visibleWhen":{"state":"selected","equals":"root"}. equals is only a string, finite number, or boolean literal; do not use expressions, scripts, or computed conditions.',
  '- text always requires fallback text. It may also bind one prepared data descriptor: {"bind":{"ref":"weather","path":"temperature_celsius"}}. The only paths are location, temperature_celsius, condition, and observed_at; bind.ref is a data descriptor, never a Smart UI state. If a known binding has no field, render an explicit unknown value and never present fallback text as actual data. Keep at most 16 text bindings per block.',
  '- Keep Smart UI within 64 nodes, depth 6, 256 records, 16 KiB UTF-8 per line, 128 KiB UTF-8 per block, 32 states, and 32 choice options. Emit complete lines while streaming; close the fence and end the block.',
  ...nativeComponentInstruction(),
  'Example:',
  '```amber-ui v=1 id=ca-controls',
  '{"op":"state","id":"selected","initial":"root"}',
  '{"op":"node","id":"panel","type":"column"}',
  '{"op":"node","id":"choice","parent":"panel","type":"choice","variant":"segmented","state":"selected","options":[{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"}]}',
  '{"op":"node","id":"root-detail","parent":"panel","type":"text","text":"根证书是信任起点。","visibleWhen":{"state":"selected","equals":"root"}}',
  '{"op":"node","id":"server-detail","parent":"panel","type":"text","text":"服务器证书标识访问目标。","visibleWhen":{"state":"selected","equals":"server"}}',
  '{"op":"node","id":"diagram","parent":"panel","type":"asset","ref":"ca-chain","label":"证书链图示"}',
  '{"op":"node","id":"explain","parent":"panel","type":"button","label":"继续解释","action":{"type":"draft","text":"请继续解释 {{selected}}"}}',
  '{"op":"end"}',
  '```',
  '',
  'Raw SVG visual:',
  '- Use exactly: ```amber-svg v=1 id=<asset-id> followed by raw SVG/XML, not a JSON string or show-widget wrapper. Keep it under 120000 characters, close the svg root, and put visible mobile-sized content in the source order.',
  '- SVG is passive and offline: no script, event attributes, HTML, iframe, form, bridge, external resource, URL, or automatic action.',
  'Example: ```amber-svg v=1 id=ca-chain',
  '<svg viewBox="0 0 360 180" xmlns="http://www.w3.org/2000/svg"><rect x="16" y="24" width="120" height="48"/><text x="28" y="54">根证书</text></svg>',
  '```',
  '',
  'Bounded Three.js visual:',
  '- Use exactly: ```amber-three v=1 id=<asset-id> followed by one JSON object {"objects":[...],"voxels"?:[...]}. Do not emit JavaScript, Three.js source, CDN imports, loaders, textures, URLs, or external models.',
  '- Each object is {id,type?,color,position,rotation?,scale?,size?}; type is box|sphere|cylinder|cone|torus|plane (defaults to box), color is #rrggbb or #rgb, position and rotation are three finite numbers within +/-200 (rotation defaults to [0,0,0]), scale is three positive numbers up to 20 (defaults to [1,1,1]), and size dimensions are positive up to 100 (box defaults to [1,1,1]). For dense voxel models, you may also use voxels:[[x,y,z,"#rrggbb",w?,h?,d?],...]. Limit the scene to 512 objects/voxels and keep it under 384000 UTF-8 bytes.',
  'Example: ```amber-three v=1 id=ca-space',
  '{"objects":[{"id":"core","type":"sphere","color":"#b8623a","position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1],"size":[1]}]}',
  '```',
  '',
  'MiniApp remains a separate explicit path:',
  '- Only when the user explicitly asks for a MiniApp, use the existing MiniApp transformer and schema. Do not combine its executable HTML/permissions with amber-ui, amber-svg, or amber-three, and do not invent automatic external actions.',
  '- Never request, include, or echo passwords, API keys, access tokens, or other secrets in generated content. Do not create scripts or hidden side effects.',
].join('\n');

/** Route selection is explicit so the existing caller keeps the legacy default until gates pass. */
export const resolveGenerativeWidgetInstruction = (
  mode: GenerativeWidgetPromptMode = 'legacy',
): string => mode === 'declared-content-v1' ? DECLARED_CONTENT_INSTRUCTION : GENERATIVE_WIDGET_INSTRUCTION;
