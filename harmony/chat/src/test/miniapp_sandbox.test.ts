// miniapp_sandbox — 权限门语义钉死(对齐 Android MiniAppSandboxTest)
import test from 'node:test';
import assert from 'node:assert/strict';
import { MiniAppSandbox } from '../main/ets/chat/miniapp/miniapp_sandbox.ts';
import { makeMiniAppSetting } from '../main/ets/chat/miniapp/miniapp_setting.ts';
import type { MiniAppSetting } from '../main/ets/chat/miniapp/miniapp_setting.ts';

const fails = (fn: () => void): boolean => {
  try {
    fn();
    return false;
  } catch (_e) {
    return true;
  }
};

test('requires declared permission', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['storage']);
  sandbox.require('storage');
  assert.equal(fails((): void => sandbox.require('theme')), true);
});

test('respects global capability switches', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['network'], {
    setting: (): MiniAppSetting => makeMiniAppSetting({ networkEnabled: false }),
  });
  assert.equal(fails((): void => sandbox.require('network')), true);
});

test('respects grant deny', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['search'], {
    grantLookup: (): 'DENY' => 'DENY',
  });
  assert.equal(fails((): void => sandbox.require('search')), true);
});

test('respects grant allow', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['search'], {
    grantLookup: (): 'ALLOW' => 'ALLOW',
  });
  sandbox.require('search');
});

test('v3 sensitive capabilities default closed', () => {
  const sensitive: Array<'host.context' | 'host.sendToConversation' | 'host.createArtifact' | 'location' | 'clipboard.read'> = [
    'host.context', 'host.sendToConversation', 'host.createArtifact', 'location', 'clipboard.read',
  ];
  for (const permission of sensitive) {
    const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', [permission]);
    assert.equal(
      fails((): void => sandbox.require(permission)),
      true,
      `Expected ${permission} to be disabled by default`,
    );
  }
});

test('v3 app capabilities default open', () => {
  const capabilities: Array<'ai.generate' | 'sensor' | 'sharedStore' | 'eventBus' | 'launch'> = [
    'ai.generate', 'sensor', 'sharedStore', 'eventBus', 'launch',
  ];
  for (const permission of capabilities) {
    const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', [permission]);
    sandbox.require(permission);
  }
});

test('v3 platform capabilities can be enabled explicitly', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['sharedStore', 'eventBus', 'launch'], {
    setting: (): MiniAppSetting =>
      makeMiniAppSetting({ sharedStoreEnabled: true, eventBusEnabled: true, launchEnabled: true }),
  });
  sandbox.require('sharedStore');
  sandbox.require('eventBus');
  sandbox.require('launch');
});

test('disabled global switch gates everything', () => {
  const sandbox: MiniAppSandbox = new MiniAppSandbox('app-1', ['storage'], {
    setting: (): MiniAppSetting => makeMiniAppSetting({ enabled: false }),
  });
  assert.equal(fails((): void => sandbox.require('storage')), true);
});
