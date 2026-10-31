// 主密钥丢失后的用户可见提示:provider 设置页读一次标记、提示一次、随即清除,
// 避免每次进页面都重复弹同一条提示。驱动真实 ChatProviderSettingsPage 的方法体。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

const RECOVERY_KEY = 'provider_secret_recovery';

const fixture = (entries = {}) => {
  const store = new Map(Object.entries(entries));
  const kv = {
    get: async key => (store.has(key) ? store.get(key) : null),
    put: async (key, value) => { store.set(key, value); },
    delete: async key => { store.delete(key); },
  };
  const page = actualPage('pages/ChatProviderSettingsPage.ets', ['loadSecretRecoveryNotice'], {
    getChatKvStore: () => kv,
    SECRET_RECOVERY_NOTICE_KEY: RECOVERY_KEY,
    Promise, Error,
  });
  Object.assign(page, { secretRecoveryNotice: false });
  return { page, store };
};

test('a recovery marker raises the notice once and is cleared afterwards', async () => {
  const { page, store } = fixture({ [RECOVERY_KEY]: '1' });
  await page.loadSecretRecoveryNotice();
  assert.equal(page.secretRecoveryNotice, true, 'the page must explain why keys are empty');
  assert.equal(store.has(RECOVERY_KEY), false, 'the notice is one-shot');

  const second = fixture();
  await second.page.loadSecretRecoveryNotice();
  assert.equal(second.page.secretRecoveryNotice, false, 'no marker, no notice');
});

test('a failing notice read never breaks the settings page', async () => {
  const page = actualPage('pages/ChatProviderSettingsPage.ets', ['loadSecretRecoveryNotice'], {
    getChatKvStore: () => ({ get: async () => { throw new Error('kv unavailable'); }, put: async () => {},
      delete: async () => {} }),
    SECRET_RECOVERY_NOTICE_KEY: RECOVERY_KEY,
    Promise, Error,
  });
  Object.assign(page, { secretRecoveryNotice: false });
  await page.loadSecretRecoveryNotice();
  assert.equal(page.secretRecoveryNotice, false);
});
