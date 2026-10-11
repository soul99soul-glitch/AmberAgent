// Run with: node --test harmony/entry/src/test/model_enterprise_ca.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

const sourceRoot = path.resolve(__dirname, '../../..');
const modulePath = path.join(sourceRoot, 'entry/src/main/ets/platform_impl/ModelEnterpriseCa.ets');

function loadModelEnterpriseCa({ context, access = true } = {}) {
  const exports = {};
  const source = ts.transpileModule(fs.readFileSync(modulePath, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, {
    exports,
    require: name => {
      if (name === '@kit.CoreFileKit') return { fileIo: { accessSync: () => access } };
      if (name === '@kit.ArkTS') return { url: { URL: { parseURL: value => new URL(value) } } };
      throw new Error('Unexpected dependency ' + name);
    },
    AppStorage: { get: () => context },
    Error, Object, String, URL,
  }, { filename: modulePath });
  return exports;
}

test('only the provisioned private CA file enables the exact model host set', () => {
  const ca = loadModelEnterpriseCa({ context: { filesDir: '/data/files' } });
  for (const host of [
    'open.bigmodel.cn', 'api.kimi.com', 'api.minimax.cn', 'opencode.ai',
    'api.commandcode.ai', 'api.deepseek.com', 'token-plan.cn-beijing.maas.aliyuncs.com',
    'api.minimaxi.com', 'api.moonshot.cn',
  ]) {
    assert.equal(ca.modelEnterpriseCaPathForUrl(`https://${host}/v1/chat/completions`), '/data/files/model-enterprise-ca.pem');
  }
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://api.minimax.chat/v1/models'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://api.openai.com/v1/models'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://generativelanguage.googleapis.com/v1beta/models'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://api.tavily.com/search'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('http://api.deepseek.com/v1/models'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://api.deepseek.com.evil.test/v1/models'), undefined);
  assert.equal(ca.modelEnterpriseCaPathForUrl('https://user:pass@api.deepseek.com/v1/models'), undefined);
});

test('missing context or CA file leaves the system CA path untouched', () => {
  const missingContext = loadModelEnterpriseCa();
  assert.equal(missingContext.modelEnterpriseCaPathForUrl('https://api.deepseek.com/v1/models'), undefined);
  const missingFile = loadModelEnterpriseCa({ context: { filesDir: '/data/files' }, access: false });
  assert.equal(missingFile.modelEnterpriseCaPathForUrl('https://api.deepseek.com/v1/models'), undefined);
});
