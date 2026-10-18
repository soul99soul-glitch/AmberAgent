import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { searchOrchestratorSearch } from '../main/ets/search/search_orchestrator.ts';

test('orchestrator: IPv6 不同端口保留独立结果，同服务同标题仍合并', async () => {
  const settings = defaultSearchPrefs();
  settings.searchBuiltinDuckDuckGoEnabled = false;
  settings.searchBuiltinBingEnabled = true;
  settings.searchBuiltinWikipediaEnabled = false;
  settings.searchBuiltinHackerNewsEnabled = false;
  const result = await searchOrchestratorSearch(settings, { query: 'dashboard', depth: 'quick' }, async () => ({
    answer: null,
    items: [
      { title: 'Dashboard status', url: 'http://[2001:db8::1]:8080/status',
        text: 'Service A status', publishedAt: null, images: [] },
      { title: 'Dashboard status', url: 'http://[2001:db8::1]:9090/status',
        text: 'Service B status', publishedAt: null, images: [] },
      { title: 'Dashboard status', url: 'http://[2001:db8::1]:8080/overview',
        text: 'Service A overview', publishedAt: null, images: [] },
    ],
  }));
  assert.equal(result['status'], 'ok');
  const items = result['items'] as JsonObject[];
  assert.deepEqual(items.map((item) => ({
    url: item['url'], domain: item['domain'], duplicateCount: item['duplicate_count'], text: item['text'],
  })), [
    { url: 'http://[2001:db8::1]:8080/status', domain: '[2001:db8::1]:8080',
      duplicateCount: 2, text: 'Service A overview' },
    { url: 'http://[2001:db8::1]:9090/status', domain: '[2001:db8::1]:9090',
      duplicateCount: 1, text: 'Service B status' },
  ]);
});
