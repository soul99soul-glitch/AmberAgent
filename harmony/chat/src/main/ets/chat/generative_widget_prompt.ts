// Injected only when the chat can display visual cards.
export const GENERATIVE_WIDGET_INSTRUCTION: string = `
Visual cards on this HarmonyOS device:
- When a diagram, illustration, 3D scene or visual explanation helps, output one fenced show-widget JSON object. Ordinary answers remain ordinary text.
- SVG/HTML format: {"title":"short title","renderer":"svg","widget_code":"<svg viewBox='0 0 360 180'><text x='16' y='40'>Diagram</text></svg>"}. Use renderer:"html" for inline HTML/CSS. Use single quotes for markup attributes and JSON-escape any double quotes, backslashes and line breaks inside widget_code. Keep cards compact and close both the SVG and JSON object before closing the fence. Write styles and meaningful visible content first; append elements in reading order so the same card becomes useful during streaming. Keep stable element ids when revising. Do not produce a series of replacement cards.
- SVG/HTML previews are offline: scripts, native bridges and external resources do not run. Use mobile-sized text, responsive widths and real task content. Native actions may contain up to three {id,label,instruction} entries, which only fill the draft after a user tap.
- For 3D, use renderer:"threejs" and a declarative spec:{objects:[...]}. The installed local Three.js renderer adds complete objects as they arrive and supports touch rotation and pinch zoom. Never emit JavaScript, CDN imports, loaders, textures or external models.
- Every 3D object has {id,type,color,position,rotation,scale,size}. id is unique and at most 48 characters; type is box|sphere|cylinder|cone|torus|plane; color is #rrggbb. position and rotation are three finite numbers bounded by ±100 (rotation in radians). scale is three positive numbers at most 10. size contains positive dimensions at most 40: box=[width,height,depth], sphere=[radius], cylinder=[radius,height], cone=[radius,height], torus=[radius,tubeRadius], plane=[width,height]. Limit scenes to 48 objects. Example: {"title":"结构示意","renderer":"threejs","spec":{"objects":[{"id":"core","type":"sphere","color":"#b8623a","position":[0,0,0],"rotation":[0,0,0],"scale":[1,1,1],"size":[1]}]}}.
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
  '- The only node types are column, text, choice, input, button, asset. root must be a column; child parent must be a previously declared column.',
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
  '- Use exactly: ```amber-three v=1 id=<asset-id> followed by one JSON object {"objects":[...]}. Do not emit JavaScript, Three.js source, CDN imports, loaders, textures, URLs, or external models.',
  '- Each object is {id,type,color,position,rotation,scale,size}; type is box|sphere|cylinder|cone|torus|plane, color is #rrggbb, position and rotation are three finite numbers within +/-100, scale is three positive numbers up to 10, and size dimensions are positive up to 40. Limit the scene to 48 objects and keep it under 120000 UTF-8 bytes.',
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
