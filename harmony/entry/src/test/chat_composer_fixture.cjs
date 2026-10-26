const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');

// Execute the complete production owner with the platform boundary replaced.
// Draft timers remain controlled by each fixture rather than scheduling work after its test.
exports.loadChatComposerSession = (domain, platform = {}) => {
  const filename = path.resolve(__dirname, '../main/ets/platform_impl/chat/ChatComposerSession.ets');
  const result = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    reportDiagnostics: true,
  });
  if (result.diagnostics.length) throw new Error('Could not transpile production composer');
  const exports = {};
  new Function('exports', 'require', 'Observed', 'setTimeout', 'clearTimeout', result.outputText)(exports, name => {
    if (name === '@amber/chat-domain') return domain;
    if (name === '@kit.ArkUI') return { promptAction: { showToast() {} } };
    if (name === '@kit.CoreFileKit') return { picker: platform.picker ?? {} };
    if (name === '@kit.CameraKit') return { camera: {}, cameraPicker: {} };
    if (name === '@kit.MediaLibraryKit') return { photoAccessHelper: {} };
    if (name === '@kit.ArkTS') return { util: platform.util ?? {} };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { warn() {}, info() {} } };
    if (name.endsWith('/ImageCompress.ets')) return { compressImageAsDataUrl: async uri => uri };
    if (name.endsWith('/EntryImageEncoder.ets')) return { encodeImageDetailed() {} };
    if (name.endsWith('/EntryLocalToolPorts.ets')) return { sha256HexUtf8: () => 'hash' };
    if (name.endsWith('/ChatAttachmentIO.ets')) return platform.attachmentIO ?? {};
    throw new Error(`Unexpected composer dependency: ${name}`);
  }, constructor => constructor, () => 0, () => {});
  return exports.ChatComposerSession;
};
