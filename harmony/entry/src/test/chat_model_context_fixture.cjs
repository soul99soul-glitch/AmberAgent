const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Execute the real observed owner; only SDK calls and V1 decoration are replaced.
exports.loadChatModelContextSession = (domain, nativeCounter = () => Promise.resolve(0), onToast = () => {}) => {
  const filename = path.resolve(__dirname, '../main/ets/platform_impl/chat/ChatModelContextSession.ets');
  const js = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    reportDiagnostics: true,
  });
  if (js.diagnostics.length > 0) throw new Error('Model context fixture could not transpile production owner');
  const exports = {};
  new Function('exports', 'require', 'Observed', 'animateTo', 'Curve', js.outputText)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {} } };
    if (name === '@kit.ArkUI') return { promptAction: { showToast: onToast } };
    if (name.endsWith('/NativeTokenCounter.ets')) return { countNativeContextTokens: nativeCounter };
    if (name.endsWith('/design/tokens.ets')) {
      return { ERROR: 'red', INK3: 'muted', STATUS_AMBER: 'amber', STATUS_BLUE: 'blue' };
    }
    throw new Error(`Unexpected model context dependency: ${name}`);
  }, constructor => constructor, (_options, update) => update(), { EaseOut: 'EaseOut' });
  return exports.ChatModelContextSession;
};
