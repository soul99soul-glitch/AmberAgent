const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
function session(creation, timers = []) {
  const filename = path.join(__dirname, '../main/ets/platform_impl/novel/NovelJobSession.ets');
  const source = fs.readFileSync(filename, 'utf8').replace(/^@Observed\s*$/gm, '');
  const exports = {};
  vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS } }).outputText,
    { exports, require: name => name.includes('ProductIdentity') ? { getProductKind: () => 'novel' } : {},
      setTimeout: callback => { timers.push(callback); return timers.length; }, Error, String });
  const owner = new exports.NovelJobSession();
  const state = { project: { id: 'p', chapters: [] }, status: { activeBranchId: 'main' }, error: '' };
  owner.start({ creation, projectId: () => 'p', currentProject: () => state.project,
    currentStatus: () => state.status, requestedPolishJobId: () => '', clearRequestedPolishJob() {}, isBusy: () => false,
    hasBlockingNovelRun: () => false, hasOrdinaryRun: () => false, publishWorkspace: (project, status) => { state.project = project; state.status = status; },
    reload: async () => {}, error: value => { state.error = value; }, operationError: (_title, error) => { state.error = String(error); },
    closeControl() {}, showJobSettings() {} });
  return { owner, state, timers };
}

module.exports = { session };
