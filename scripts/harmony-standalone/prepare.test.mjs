import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { prepareProduct, readConfiguration, selectProducts } from './prepare.mjs';

function fixture(t) {
  const parent = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-standalone-'));
  const root = path.join(parent, 'amber');
  t.after(() => fs.rmSync(parent, { recursive: true, force: true }));
  const files = {
    'build-profile.json5': `{ app: { products: [{ name: 'default', signingConfig: 'host' }], signingConfigs: [{ material: { storePassword: 'private-host-password', profile: '/private/host.p7b' } }] }, modules: [{name:'entry',srcPath:'./entry'}] }`,
    'AppScope/app.json5': JSON.stringify({ app: { bundleName: 'app.amber.deepread', versionCode: 2 } }),
    'AppScope/resources/base/element/string.json': JSON.stringify({ string: [{ name: 'app_name', value: 'Amber' }] }),
    'AppScope/resources/base/media/app_icon.svg': '<svg />',
    'products/novel/app_icon.png': 'novel-icon-fixture',
    'products/deepread/app_icon.png': 'deepread-icon-fixture',
    'entry/src/main/resources/base/element/string.json': JSON.stringify({ string: [{ name: 'EntryAbility_label', value: 'Amber' }] }),
    'entry/src/main/resources/base/profile/main_pages.json': JSON.stringify({ src: ['pages/Index', 'pages/NovelWorkspacePage'] }),
    'entry/src/main/module.json5': JSON.stringify({ module: {
      abilities: [{ name: 'EntryAbility', backgroundModes: ['dataTransfer'] }],
      extensionAbilities: [{ name: 'NovelPolishWorkAbility', type: 'workScheduler' }],
      requestPermissions: ['INTERNET', 'GET_NETWORK_INFO', 'KEEP_BACKGROUND_RUNNING', 'VIBRATE', 'READ_CALENDAR'].map((name) => ({ name: `ohos.permission.${name}` })),
    } }),
    'entry/src/main/ets/pages/Index.ets': 'current page source',
    'entry/oh-package.json5': JSON.stringify({ dependencies: { '@amber/chat-domain': 'file:../chat' } }),
    'chat/src/main/ets/index.ts': 'chat current source',
    'deepread/src/main/ets/index.ts': 'deepread current source',
    'native/rust-napi/src/lib.rs': 'pub fn current() {}',
    'hvigor/hvigor-config.json5': '{}',
    'hvigorfile.ts': 'export default {};',
    'scripts/sign-hap.sh': '#!/bin/sh\necho product-local-signer\n',
  };
  for (const [name, content] of Object.entries(files)) {
    const file = path.join(root, 'harmony', name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, content);
  }
  return { root, files };
}

test('both products have isolated identity and permissions while host configuration stays unchanged', (t) => {
  const { root, files } = fixture(t);
  for (const [product, bundle] of [['novel', 'app.amber.novel'], ['deepread', 'app.amber.deepread.reader']]) {
    const output = prepareProduct(product, root);
    assert.equal(readConfiguration(path.join(output, 'AppScope/app.json5')).app.bundleName, bundle);
    const manifest = readConfiguration(path.join(output, 'entry/src/main/module.json5')).module;
    assert.equal(manifest.extensionAbilities.length, product === 'novel' ? 1 : 0);
    assert.equal(manifest.requestPermissions.length, product === 'deepread' ? 5 : 4);
    assert.equal(manifest.requestPermissions.some((permission) =>
      permission.name === 'ohos.permission.ACCELEROMETER'), product === 'deepread');
    assert.equal(manifest.requestPermissions.some((permission) => permission.name.endsWith('READ_CALENDAR')), false);
    assert.deepEqual(JSON.parse(JSON.stringify(manifest.abilities)), [{ name: 'EntryAbility', backgroundModes: ['dataTransfer'],
      icon: `$media:${product}_app_icon`, startWindowIcon: `$media:${product}_start_icon` }]);
    assert.equal(readConfiguration(path.join(output, 'AppScope/app.json5')).app.icon, `$media:${product}_app_icon`);
    for (const directory of ['AppScope/resources/base/media', 'entry/src/main/resources/base/media']) {
      assert.equal(fs.readFileSync(path.join(output, directory, `${product}_app_icon.png`), 'utf8'), `${product}-icon-fixture`);
    }
    const startIcon = fs.readFileSync(path.join(output,
      `entry/src/main/resources/base/media/${product}_start_icon.svg`), 'utf8');
    assert.match(startIcon, /<rect[^>]+rx="56"/);
    assert.match(startIcon, /clip-path="url\(#icon-corners\)"/);
    const embedded = startIcon.match(/data:image\/png;base64,([A-Za-z0-9+/=]+)/)[1];
    assert.equal(Buffer.from(embedded, 'base64').toString(), `${product}-icon-fixture`);
    assert.equal(readConfiguration(path.join(output, 'entry/src/main/resources/base/element/string.json')).string.find((item) => item.name === 'product_kind').value, product);
    assert.equal(fs.readFileSync(path.join(output, 'AppScope/resources/base/media/app_icon.svg'), 'utf8'), '<svg />');
    assert.equal(fs.readFileSync(path.join(output, 'entry/oh-package.json5'), 'utf8'), files['entry/oh-package.json5']);
    assert.equal(fs.readFileSync(path.join(output, 'entry/src/main/resources/base/profile/main_pages.json'), 'utf8'), files['entry/src/main/resources/base/profile/main_pages.json']);
    const profile = readConfiguration(path.join(output, 'build-profile.json5'));
    assert.equal(profile.app.signingConfigs, undefined);
    assert.equal(profile.app.products[0].signingConfig, undefined);
    assert.equal(fs.readFileSync(path.join(output, 'build-profile.json5'), 'utf8').includes('private-host-password'), false);
  }
  for (const [name, content] of Object.entries(files)) assert.equal(fs.readFileSync(path.join(root, 'harmony', name), 'utf8'), content);
});

test('dependency caches, signing materials and local user data are excluded; symlinks cannot leak external files', (t) => {
  const { root } = fixture(t);
  const excluded = ['entry/build/signed.hap', 'entry/oh_modules/private.txt', '.hvigor/private.json',
    'native/rust-napi/target/debug/lib.a', 'native/ssh/archives/archive.tar.gz', 'entry/.env',
    'entry/private.p12', 'entry/profile.p7b', 'entry/credentials/secret.json', 'entry/user-data/data.json',
    'entry/data.sqlite', 'entry/node_modules/dependency.js', 'entry/local.properties'];
  for (const name of excluded) {
    const file = path.join(root, 'harmony', name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, 'must not be copied');
  }
  fs.writeFileSync(path.join(root, 'external-secret'), 'secret');
  fs.symlinkSync(path.join(root, 'external-secret'), path.join(root, 'harmony/entry/external-link'));
  const output = prepareProduct('novel', root);
  for (const name of [...excluded, 'entry/external-link']) assert.equal(fs.existsSync(path.join(output, name)), false, name);
  assert.equal(fs.existsSync(path.join(output, 'native/rust-napi/src/lib.rs')), true);
  assert.equal(fs.existsSync(path.join(output, 'scripts/sign-hap.sh')), true);
});

test('preparing again snapshots current dirty and untracked source and removes obsolete files', (t) => {
  const { root } = fixture(t);
  let output = prepareProduct('novel', root);
  fs.writeFileSync(path.join(root, 'harmony/chat/src/main/ets/index.ts'), 'changed current source');
  fs.writeFileSync(path.join(root, 'harmony/chat/src/main/ets/new.ts'), 'untracked current source');
  fs.rmSync(path.join(root, 'harmony/deepread/src/main/ets/index.ts'));
  output = prepareProduct('novel', root);
  assert.equal(fs.readFileSync(path.join(output, 'chat/src/main/ets/index.ts'), 'utf8'), 'changed current source');
  assert.equal(fs.readFileSync(path.join(output, 'chat/src/main/ets/new.ts'), 'utf8'), 'untracked current source');
  assert.equal(fs.existsSync(path.join(output, 'deepread/src/main/ets/index.ts')), false);
});

test('incremental prepare preserves build caches, signing identity and files outside the source manifest', (t) => {
  const { root } = fixture(t);
  const output = prepareProduct('novel', root);
  const preserved = [
    'entry/build/default/outputs/default/signed/material/app-keystore.p12',
    'entry/build/default/outputs/default/signed/material/profile.p7b',
    'oh_modules/.ohpm/resolved.json', '.hvigor/state.json',
    'native/rust-napi/target/release/lib.a', 'native/ssh/build/ohos/install/lib/libssh2.a',
    'native/ssh/archives/libssh2.tar.gz', 'entry/src/main/ets/local-experiment.ets',
  ];
  for (const relative of preserved) {
    const file = path.join(output, relative);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, `retained ${relative}`);
  }
  const unchanged = path.join(output, 'native/rust-napi/src/lib.rs');
  fs.utimesSync(unchanged, 1000, 1000);
  fs.writeFileSync(path.join(root, 'harmony/chat/src/main/ets/index.ts'), 'new source revision');
  prepareProduct('novel', root);
  for (const relative of preserved) assert.equal(fs.readFileSync(path.join(output, relative), 'utf8'), `retained ${relative}`);
  assert.equal(fs.statSync(unchanged).mtimeMs, 1000000);
  assert.equal(fs.readFileSync(path.join(output, 'chat/src/main/ets/index.ts'), 'utf8'), 'new source revision');
});

test('preparing refuses to delete an existing user-owned directory', (t) => {
  const { root } = fixture(t);
  const output = path.join(root, '.harmony-standalone/novel');
  fs.mkdirSync(output, { recursive: true });
  fs.writeFileSync(path.join(output, 'keep-me'), 'user work');
  assert.throws(() => prepareProduct('novel', root), /Refusing to replace/);
  assert.equal(fs.readFileSync(path.join(output, 'keep-me'), 'utf8'), 'user work');
});

test('CLI accepts only supported products', () => {
  assert.deepEqual(selectProducts(['--product', 'all']), ['novel', 'deepread']);
  assert.deepEqual(selectProducts(['--product', 'deepread']), ['deepread']);
  assert.throws(() => selectProducts(['--product', 'host']), /Usage/);
});

test('DeepRead preparation returns the relocated project without replacing its source or signing', (t) => {
  const { root } = fixture(t);
  const relocated = path.resolve(root, '../DeepRead/harmony');
  fs.mkdirSync(path.join(relocated, 'AppScope'), { recursive: true });
  fs.writeFileSync(path.join(relocated, 'AppScope/app.json5'), JSON.stringify({ app: { bundleName: 'app.amber.deepread.reader' } }));
  fs.writeFileSync(path.join(relocated, 'build-profile.json5'), 'local-signing-kept');
  assert.equal(prepareProduct('deepread', root), relocated);
  assert.equal(fs.readFileSync(path.join(relocated, 'build-profile.json5'), 'utf8'), 'local-signing-kept');
  assert.equal(fs.existsSync(path.join(root, '.harmony-standalone/deepread')), false);
  assert.throws(() => {
    fs.writeFileSync(path.join(relocated, 'AppScope/app.json5'), JSON.stringify({ app: { bundleName: 'wrong.product' } }));
    prepareProduct('deepread', root);
  }, /Unexpected DeepRead bundle identity/);
});
