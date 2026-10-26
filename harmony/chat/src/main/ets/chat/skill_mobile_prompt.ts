// Product-specific mobile constraints applied when loading skill instructions.

// ===== 移动运行时包装(SkillsTools.kt:155-194 逐字;GuizangHtmlDeckValidator.kt:18-24) =====

const GUIZANG_RENDERER: string = 'full_html';
const GUIZANG_LOCAL_MOTION_URL: string = 'https://amberagent.local/full-html/motion.min.js';
const GUIZANG_LOCAL_LUCIDE_URL: string = 'https://amberagent.local/full-html/lucide.min.js';

export const buildSkillMobileRuntimePrompt = (
  skillName: string, filePath: string | null, body: string,
): string => {
  const pathLabel: string =
    (filePath !== null && filePath.trim().length > 0) ? filePath : 'SKILL.md';
  let out: string = '';
  const line = (s: string): void => {
    out += s + '\n';
  };
  line('[AmberAgent Mobile Runtime — applies to the skill content below]');
  line('You are running inside AmberAgent on a HarmonyOS phone/tablet — NOT desktop Claude Code, NOT Codex, NOT a CLI environment.');
  line('These mobile constraints OVERRIDE any conflicting instruction in the skill body:');
  line('- The user has no physical keyboard, no mouse, no system shell. Never write "press ← →", "F for fullscreen", "S for speaker mode", "Ctrl+C to quit", or any keyboard/mouse hint into your visible reply or into widget content.');
  line('- There is no system browser to open .html / .pdf / .pptx files for preview. Visual previews should render inside the chat as show-widget blocks (SVG, HTML, vchart, slides) so they appear as cards in the conversation timeline.');
  line(`- For multi-page presentations / decks / PPT / 幻灯片 / 演示文稿, emit one final show-widget deck preview only after the deck HTML is complete. Use renderer "${GUIZANG_RENDERER}" with the complete static deck HTML in spec.html. During generation, use short progress text or a tiny SVG widget_code cover/status; never emit partial spec.html. Do NOT generate a .pptx file as the only deliverable; do NOT pack a multi-page deck into a single SVG grid; do NOT turn PPT requests into MiniApps.`);
  line('- HarmonyOS HTML/SVG widget previews are offline: generated JavaScript, external resources, native bridges and automatic actions do not run. A declarative renderer:"threejs" spec is supported by the installed local 3D runtime; emit bounded geometry objects, never scripts/CDN imports. Every full_html slide must remain visible without scripts or a canvas/WebGL dependency. Native actions only fill the input draft after a user tap.');
  line('- Structured vchart previews support one ungrouped dataset with numeric yField and unique xField labels only; seriesField, stacking and percent/group normalization are unsupported. For grouped charts, produce the correct static SVG directly instead of an unsupported vchart spec.');
  line('- Use the available execution runtime accurately: terminal tools run on the approved Remote SSH host; python_execute uses embedded CPython for local text/data processing. Do not assume Alpine, package installation, direct Python file access, or automatic Workspace synchronization.');
  line('- File outputs go to /workspace via file_write; users browse them through the in-app file sheet, not through Finder/Explorer.');
  line('- If the skill describes a desktop-only workflow, translate it into the mobile equivalent: replace "open in PowerPoint" with "emit a show-widget deck preview", "open in browser" with "emit the appropriate show-widget renderer", etc.');
  line('- IMPORTANT about use_skill paths: many skills installed via download only ship the SKILL.md file — the references/, scripts/, assets/ subfolders mentioned in the SKILL.md links may NOT exist locally. Do NOT chain a second use_skill(path=...) call just because SKILL.md links to it; treat SKILL.md as self-contained instructions and only retry with a path if a previous call confirms that file exists.');
  if (skillName.toLowerCase().indexOf('guizang-ppt') >= 0) {
    line(`- guizang-ppt-skill SPECIAL MOBILE ADAPTER: the default and preferred final output is a complete \`show-widget\` using \`renderer:"${GUIZANG_RENDERER}"\`, not renderer:"slides", not widget_code HTML, not a MiniApp, and not a standalone saved HTML page. Give the user an inline PPT preview card in the chat after the HTML is complete; while generating, show only concise progress or a tiny SVG cover/status.`);
    line('- Required full_html skeleton: `spec.html` contains one `<div id="deck">` wrapper and 6-10 `<section class="slide ..." data-animate="...">...</section>` pages copied from/adapted to the guizang template style. `widget_code` is only a tiny static SVG cover.');
    line(`- HarmonyOS does NOT load runtime scripts, including Lucide at ${GUIZANG_LOCAL_LUCIDE_URL} or Motion at ${GUIZANG_LOCAL_MOTION_URL}; these are not available to its static preview. Use inline SVG icons and CSS/SVG visuals, never script imports or CDN assets.`);
    line('- Keep the skill\'s visual style in static HTML/CSS: magazine/Swiss typography, grid layout and inline SVG backgrounds/icons. Do not depend on canvas/WebGL, Motion, Lucide initialization or swipe JavaScript. Pages are browsed by vertical scrolling. Do NOT add bridge calls, popups, downloads, file/content/intent/android_asset URLs, flat non-deck pages, or keyboard-only instructions.');
  }
  if (skillName.toLowerCase().indexOf('guizang-social-card') >= 0) {
    line(`- guizang-social-card-skill SPECIAL MOBILE ADAPTER: default chat preview output is one complete \`show-widget\` using \`renderer:"${GUIZANG_RENDERER}"\`. Do not output raw HTML as ordinary Markdown/code, a MiniApp, or a standalone saved HTML page as the only deliverable.`);
    line('- Required social-card full_html skeleton: `spec.html` contains one `<div id="deck">` wrapper. Each card is one deck page: `<section class="slide social-card poster xhs">...</section>`, `<section class="slide social-card poster wide">...</section>`, or `<section class="slide social-card poster square">...</section>`. A single social card is a one-slide deck; a carousel is a multi-slide deck.');
    line('- `widget_code` is only a tiny static SVG cover/status thumbnail. Keep progress visible with concise text or the tiny cover; emit the full_html show-widget only after `spec.html` is complete, and never emit a partial or truncated `spec.html`.');
    line('- Preview and export are separate: for normal "show me / preview / make a card" requests, render the inline full_html preview in chat. Only create /workspace files, PNG/JPG exports, or screenshot workflows when the user explicitly asks to export, save, share, or download image files.');
    line('- Social-card HTML must stay mobile-native: inline CSS, touch-friendly sizing, no external CSS/CDN/runtime scripts, no iframe/form/nav/download/popups, no file/content/intent/android_asset URLs, and no desktop/browser/keyboard instructions.');
  }
  line('');
  line(`Skill: ${skillName}  (${pathLabel})`);
  line('--- skill content begins ---');
  out += body;
  if (!body.endsWith('\n')) out += '\n';
  line('--- skill content ends ---');
  line('');
  line('Reminder: the mobile constraints above take priority. If the skill says "open in a browser" or "add keyboard shortcuts", you ignore that part and use the AmberAgent-native equivalent.');
  return out;
};

