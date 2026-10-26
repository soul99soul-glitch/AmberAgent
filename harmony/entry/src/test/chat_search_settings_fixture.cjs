const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Execute the production owners and page lifecycle; only ArkUI and module boundaries are replaced.
exports.loadSearchSettings = domain => {
  const compile = source => ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const load = (name, dependencies) => {
    const filename = path.resolve(__dirname, '../main/ets/platform_impl/search_settings', name);
    const output = {};
    new Function('exports', 'require', 'Observed', 'animateTo', 'Curve', compile(fs.readFileSync(filename, 'utf8')))(
      output, dependency => {
        if (dependency === '@amber/chat-domain') return domain;
        if (dependency.endsWith('tokens.ets')) return { MOTION_OVERLAY: 220 };
        if (dependency.endsWith('ChatSearchSettingsSession.ets')) return dependencies;
        throw new Error(`Unexpected ChatSearch dependency: ${dependency}`);
      }, constructor => constructor, (_options, apply) => apply(), { EaseOut: 0 });
    return output;
  };
  const settings = load('ChatSearchSettingsSession.ets');
  const editor = load('ChatSearchServiceEditorSession.ets', settings);
  const source = fs.readFileSync(path.resolve(__dirname, '../main/ets/pages/ChatSearchSettingsPage.ets'), 'utf8');
  const start = source.indexOf('struct ChatSearchSettingsPage {');
  const body = source.slice(start, source.indexOf('  @Builder', start))
    .replace('struct ChatSearchSettingsPage', 'class ChatSearchSettingsPage')
    .replace(/@(?:State|StorageProp)(?:\([^\n]*?\))?\s*/g, '');
  const create = kv => {
    const modes = [], page = new Function('ChatSearchSettingsSession', 'ChatSearchServiceEditorSession',
      'getChatKvStore', 'KeyboardAvoidMode', 'ACCENT', compile(`${body}\n}\nreturn new ChatSearchSettingsPage();`))(
      settings.ChatSearchSettingsSession, editor.ChatSearchServiceEditorSession, () => kv,
      { OFFSET: 0, RESIZE: 1 }, '#000000');
    page.getUIContext = () => ({ getKeyboardAvoidMode: () => 7, setKeyboardAvoidMode: value => modes.push(value) });
    page.aboutToAppear();
    return { page, modes };
  };
  return { ...settings, ...editor, create };
};
