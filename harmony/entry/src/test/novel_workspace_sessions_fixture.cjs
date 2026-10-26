const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const models = require('../../../deepread/src/main/ets/novel/models.ts');
const collected = require('../../../deepread/src/main/ets/novel/collected_candidates.ts');
const { novelMessageText } = require('../../../deepread/src/main/ets/novel/transcript.ts');
const composer = require('../main/ets/novel/NovelComposerState.ts');
const { normalizeNovelMaterialFields } = require('../../../deepread/src/main/ets/novel/material_fields.ts');

function loadSession(name, state, creation, dialogs = []) {
  const source = fs.readFileSync(path.resolve(__dirname, '../main/ets/platform_impl/novel', `${name}.ets`), 'utf8');
  const code = ts.transpileModule(source.replace(/^@Observed\s*$/gm, ''), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const dependencies = {
    '@amber/deepread-domain': { ...models, ...collected, novelMessageText },
    '../../novel/NovelComposerState': composer,
    '../../design/tokens.ets': { ERROR: 'red' },
    '../../components/NovelMaterialEditorSheet.ets': {
      emptyMaterialEditorDraft: () => ({ kind: 'world', title: '', content: '', enabled: true, scope: 'branch',
        aliases: [], tags: [], customKind: '', injectionMode: 'always' }),
      materialEditorDraftFor: (material, scope) => ({ kind: material.kind, title: material.title,
        content: material.content, enabled: material.enabled, scope,
        ...normalizeNovelMaterialFields(material, material.enabled) }),
    },
  };
  const exports = {};
  new Function('exports', 'require', 'TextInputController', 'TextAreaController', 'AlertDialog', code)(
    exports, name => {
      if (name in dependencies) return dependencies[name];
      throw Error(`Unexpected novel session dependency: ${name}`);
    }, class { stopEditing() {} }, class { stopEditing() {} }, { show: dialog => dialogs.push(dialog) });
  const session = new exports[name]();
  const ports = {
    creation, currentProject: () => state.project, workspaceStatus: () => state.workspaceStatus,
    currentStatus: () => state.workspaceStatus, projectId: () => state.projectId,
    busy: () => state.busy, isBusy: () => state.busy, setBusy: value => { state.busy = value; },
    error: () => state.errorMsg, readError: () => state.errorMsg, setError: value => { state.errorMsg = value; },
    reload: () => state.reload(), pageAlive: () => state.pageAlive, pageHidden: () => state.pageHiddenOnce,
    hasBlockingNovelRun: () => state.hasBlockingNovelRun(), novelJobLocksWorkspace: () => false,
    visibleCycle: () => state.visibleCycle ?? state.studioVisibleCycle ?? 1, onCollected: (...args) => state.onCollected?.(...args),
    showOperationError: (title, error) => { state.errorMsg = `${title}: ${error}`; },
  };
  session.start(ports);
  return session;
}
module.exports = { loadSession };
