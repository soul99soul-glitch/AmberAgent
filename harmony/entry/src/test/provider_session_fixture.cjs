const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const entry = path.resolve(__dirname, '../main/ets');

// Load actual owners. Platform effects and existing domain persistence functions
// are supplied by each regression; no production method body is replaced.
exports.loadProviderSessions = (domain = {}, platform = {}) => {
  const load = (file, symbol) => {
    const source = fs.readFileSync(path.join(entry, 'platform_impl/provider', file + '.ets'), 'utf8');
    const result = ts.transpileModule(source, {
      compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS }, reportDiagnostics: true,
    });
    if (result.diagnostics.length) throw new Error('Cannot transpile ' + file);
    const output = {};
    new Function('exports', 'require', 'Observed', 'setTimeout', result.outputText)(output, name => {
      if (name === '@amber/chat-domain') return domain;
      if (name === '@kit.ArkUI') return { promptAction: platform.promptAction ?? { showToast() {} } };
      if (name === '@kit.BasicServicesKit') return { pasteboard: platform.pasteboard ?? {} };
      if (name.endsWith('/EntryAbortController.ets')) return { EntryAbortController: class {
        signal = { aborted: false }; abort() { this.signal.aborted = true; }
      } };
      throw new Error('Unexpected provider owner dependency: ' + name);
    }, constructor => constructor, () => 0);
    return output[symbol];
  };
  return {
    ProviderEditSession: load('ProviderEditSession', 'ProviderEditSession'),
    ProviderLoginSession: load('ProviderLoginSession', 'ProviderLoginSession'),
    ProviderModelCatalogSession: load('ProviderModelCatalogSession', 'ProviderModelCatalogSession'),
  };
};

exports.providerPage = (names, environment = {}, fields = {}) => {
  const source = fs.readFileSync(path.join(entry, 'pages/ChatProviderDetailPage.ets'), 'utf8');
  const methods = names.map(name => {
    const match = new RegExp('^  (?:private )?(?:async )?' + name + '\\(', 'm').exec(source);
    if (!match) throw new Error('Missing provider page method: ' + name);
    let end = source.indexOf('{', match.index) + 1, depth = 1;
    while (depth && end < source.length) {
      if (source[end] === '{') depth++;
      if (source[end] === '}') depth--;
      end++;
    }
    return source.slice(match.index, end);
  });
  const code = ts.transpileModule('class Page {\n' + methods.join('\n') + '\n} return new Page();', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  return Object.assign(new Function(...Object.keys(environment), code)(...Object.values(environment)), fields);
};
