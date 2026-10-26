import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { JsonObject } from '../main/ets/chat/json.ts';
import { defaultSearchPrefs } from '../main/ets/search/search_prefs.ts';
import { initSearchSdk, makeSearchServiceOptions } from '../main/ets/search/search_service.ts';
import { createSearchTools } from '../main/ets/search/search_tools.ts';
import { searchAggregatorSearch } from '../main/ets/search/search_aggregator.ts';
import { searchOrchestratorSearch } from '../main/ets/search/search_orchestrator.ts';

const configuredSettings = () => {
  const settings = defaultSearchPrefs();
  const options = makeSearchServiceOptions('grok');
  if (options.type === 'grok') options.apiKey = 'fixture-key';
  settings.searchServices = [options];
  settings.searchEnabledServiceIds = [options.id];
  settings.searchBuiltinDuckDuckGoEnabled = false;
  settings.searchBuiltinBingEnabled = false;
  settings.searchBuiltinWikipediaEnabled = false;
  settings.searchBuiltinHackerNewsEnabled = false;
  return settings;
};

test('the real Grok search tool retains its synthesized answer separately from cited source text', async () => {
  const settings = configuredSettings();
  const answer = 'The source announces an October 25 launch.';
  initSearchSdk({ fetch: async () => ({ status: 200, headers: {}, body: JSON.stringify({
    output: [{ type: 'message', role: 'assistant', content: [{ type: 'output_text', text: answer,
      annotations: [{ type: 'url_citation', url: 'https://example.com/launch' }] }] }],
  }) }), fetchStream: async () => { throw Error('unused'); } });
  const tool = createSearchTools(settings).find(tool => tool.name === 'search_web')!;
  const parts = await tool.execute({ query: 'launch date', depth: 'quick' });
  assert.equal(parts[0].type, 'text');
  if (parts[0].type !== 'text') throw Error('expected text');
  const result = JSON.parse(parts[0].text) as JsonObject;
  const answers = result.answers as JsonObject[];
  assert.equal(answers[0].answer, answer);
  assert.equal(answers[0].service, 'Grok');
  assert.equal(answers[0].service_id, settings.searchServices[0].id);
  const items = result.items as JsonObject[];
  assert.equal(items[0].url, 'https://example.com/launch');
  assert.equal(items[0].text, '', 'provider synthesis must not masquerade as source text');
  assert.match(tool.description, /provider-synthesized/i);
});

test('both aggregation entries retain answer-only content without reporting a contradictory empty result', async () => {
  const settings = configuredSettings();
  settings.searchGoogleWebViewFallbackEnabled = false;
  const response = { answer: 'Provider synthesis without a cited page.', items: [] };
  for (const result of [
    await searchAggregatorSearch(settings, { query: 'question' }, async () => response),
    await searchOrchestratorSearch(settings, { query: 'question', depth: 'quick' }, async () => response),
  ]) {
    assert.equal(result.status, 'ok');
    assert.deepEqual(result.items, []);
    assert.equal((result.answers as JsonObject[])[0].answer, response.answer);
    assert.equal(result.message, undefined);
  }
});

test('browser fallback exposes only authorized actual browser actions and otherwise keeps manual links', async () => {
  const settings = configuredSettings();
  const empty = async () => ({ answer: null, items: [] });
  const params = { query: 'a b', depth: 'quick' };
  const manual = await searchOrchestratorSearch(settings, params, empty);
  const manualFallback = manual.webview_fallback as JsonObject;
  assert.equal(manualFallback.status, 'manual_only');
  assert.ok((manualFallback.suggestions as JsonObject[]).every(item => item.available === false && item.tool === undefined));
  assert.doesNotMatch(JSON.stringify(manual), /webview_search_open/);
  let allowed = true;
  const deps = { browserFallback: async (url: string) => allowed && url.startsWith('https://www.google.com/')
    ? null : 'This site is not currently available.' };
  const result = await searchOrchestratorSearch(settings, params, empty, deps);
  const fallback = result.webview_fallback as JsonObject;
  assert.equal(fallback.status, 'available');
  const suggestions = fallback.suggestions as JsonObject[];
  assert.equal(suggestions[0].tool, 'wm_open');
  assert.equal(suggestions[0].url, 'https://www.google.com/search?q=a+b');
  assert.equal(suggestions[1].available, false);
  assert.equal(suggestions[1].tool, undefined);
  allowed = false;
  const changed = await searchOrchestratorSearch(settings, params, empty, deps);
  assert.equal((changed.webview_fallback as JsonObject).status, 'manual_only');
  settings.searchEnabledServiceIds = [];
  allowed = true;
  const browserOnly = await searchOrchestratorSearch(settings, params, async () => {
    throw Error('there are no ordinary sources to call');
  }, deps);
  assert.equal((browserOnly.webview_fallback as JsonObject).status, 'available');
  settings.searchGoogleWebViewFallbackEnabled = false;
  const disabled = await searchOrchestratorSearch(settings, params, empty, deps);
  assert.equal(disabled.webview_fallback, undefined);
});
