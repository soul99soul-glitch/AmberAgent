import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  evaluateWebMountWait, parseWaitProbeJson,
} from '../main/ets/chat/webmount_wait.ts';

test('evaluateWebMountWait: url/title/selector/text', () => {
  const probe = {
    url: 'https://example.com/docs/page',
    title: 'Docs · Example',
    bodyText: 'hello world ready',
    selectorExists: true,
  };
  assert.equal(evaluateWebMountWait({ urlIncludes: '/docs/' }, probe).matched, true);
  assert.equal(evaluateWebMountWait({ titleIncludes: 'Docs' }, probe).matched, true);
  assert.equal(evaluateWebMountWait({ selector: 'h1' }, probe).matched, true);
  assert.equal(evaluateWebMountWait({ textIncludes: 'ready' }, probe).matched, true);
  assert.equal(evaluateWebMountWait({ urlIncludes: '/other/' }, probe).reason, 'url');
  assert.equal(evaluateWebMountWait(
    { selector: '.missing' }, { ...probe, selectorExists: false },
  ).reason, 'selector');
  assert.equal(evaluateWebMountWait({}, probe).reason, 'no_condition');
});

test('parseWaitProbeJson', () => {
  assert.equal(parseWaitProbeJson('{"ok":true,"value":{"matched":true}}').matched, true);
  assert.equal(parseWaitProbeJson('{"ok":true,"value":{"matched":false}}').matched, false);
  assert.equal(parseWaitProbeJson('not-json').matched, false);
  assert.equal(parseWaitProbeJson('{"ok":false}').matched, false);
});
