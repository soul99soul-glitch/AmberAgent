const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Execute the real observed owner with only ArkUI decoration and logging replaced.
exports.loadChatSmartUiSession = domain => {
  const filename = path.resolve(__dirname, '../main/ets/platform_impl/chat/ChatSmartUiSession.ets');
  const js = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exports = {};
  new Function('exports', 'require', 'Observed', js)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn: () => {} } };
    throw new Error(`Unexpected Smart UI dependency: ${name}`);
  }, constructor => constructor);
  return exports.ChatSmartUiSession;
};
