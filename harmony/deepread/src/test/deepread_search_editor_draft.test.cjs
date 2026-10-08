const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage } = require('./deepread_ui_fixture.cjs');

const settings = () => ({ enableWebSearch: true, searchCommonOptions: { resultSize: 12 },
  searchServices: [{ type: 'tavily', id: 'first', apiKey: 'old', depth: 'advanced' },
    { type: 'searxng', id: 'selected', url: 'https://search.example', engines: 'google,bing', language: 'zh', username: 'user', password: 'old-password' }],
  searchEnabledServiceIds: ['first', 'selected'], searchServiceSelected: 1,
  searchBuiltinDuckDuckGoEnabled: true, searchBuiltinBingEnabled: true, searchBuiltinJinaEnabled: true,
  searchBuiltinWikipediaEnabled: true, searchBuiltinHackerNewsEnabled: true, searchGoogleWebViewFallbackEnabled: false });

function editor(draft) {
  const writes = []; let backs = 0;
  const page = actualPage('pages/DeepReadSearchServiceEditorPage.ets',
    ['aboutToAppear', 'loadDraft', 'draftOptions', 'optionalTokenLimit', 'preserveDraft', 'finish', 'deleteService', 'edited'], {
      readDeepReadSearchEditorDraft: () => structuredClone(draft),
      writeDeepReadSearchEditorDraft: value => writes.push(value),
      router: { getParams: () => ({ serviceId: 'selected' }), back: () => { backs++; } },
    });
  Object.assign(page, { changed: false, loaded: false, error: '' });
  page.aboutToAppear();
  return { page, writes, backs: () => backs };
}

test('search editor returns staged credentials and enabled state without persisting or changing the preferred ID', () => {
  const original = { settings: settings(), selectedId: 'selected', dirty: false };
  const h = editor(original);
  h.page.dPassword = 'new-password'; h.page.serviceEnabled = false; h.page.edited(); h.page.finish();
  assert.equal(h.backs(), 1);
  assert.equal(h.writes.length, 1);
  const returned = h.writes[0];
  assert.equal(returned.selectedId, 'selected');
  assert.equal(returned.dirty, true);
  assert.deepEqual(returned.settings.searchServices[1], {
    type: 'searxng', id: 'selected', url: 'https://search.example', engines: 'google,bing',
    language: 'zh', username: 'user', password: 'new-password',
  });
  assert.deepEqual(returned.settings.searchEnabledServiceIds, ['first']);
  assert.equal(original.settings.searchServices[1].password, 'old-password');
  assert.deepEqual(original.settings.searchEnabledServiceIds, ['first', 'selected']);
  const unedited = editor(original);
  unedited.page.finish();
  assert.deepEqual(unedited.writes[0], original, 'opening and returning leaves the draft unchanged');
});

test('deleting a service reindexes the surviving preferred ID and clears a deleted preference in the same draft', () => {
  const form = actualPage('components/deepread/DeepReadSettingsForm.ets', ['deleteSearch'], {});
  Object.assign(form, { search: settings(), selectedSearchId: 'selected', onEdited() {} });
  form.deleteSearch('first');
  assert.equal(form.selectedSearchId, 'selected');
  assert.equal(form.search.searchServiceSelected, 0);
  assert.deepEqual(form.search.searchEnabledServiceIds, ['selected']);
  const h = editor({ settings: form.search, selectedId: form.selectedSearchId, dirty: true });
  h.page.deleteService();
  assert.equal(h.writes[0].selectedId, '');
  assert.equal(h.writes[0].settings.searchServiceSelected, -1);
  assert.deepEqual(h.writes[0].settings.searchServices, []);
  assert.deepEqual(h.writes[0].settings.searchEnabledServiceIds, []);
});

test('invalid numeric limits keep the editor open with its credential draft available to correct', () => {
  const draft = { settings: settings(), selectedId: 'selected', dirty: false };
  draft.settings.searchServices[1] = { type: 'perplexity', id: 'selected', apiKey: 'credential', maxTokens: null, maxTokensPerPage: 512 };
  const h = editor(draft);
  h.page.dMaxTokens = '12oops'; h.page.edited(); h.page.finish();
  assert.equal(h.writes.length, 0); assert.equal(h.backs(), 0);
  assert.match(h.page.error, /正整数/); assert.equal(h.page.dApiKey, 'credential');
  h.page.dMaxTokens = '1024'; h.page.finish();
  assert.equal(h.writes[0].settings.searchServices[1].maxTokens, 1024);
  assert.equal(h.writes[0].settings.searchServices[1].maxTokensPerPage, 512);
});


test('Dock departure preserves valid editor drafts without moving the route and refuses invalid drafts', () => {
  const draft = { settings: settings(), selectedId: 'selected', dirty: false };
  const valid = editor(draft);
  valid.page.dPassword = 'draft-before-tab-change'; valid.page.edited();
  assert.equal(valid.page.preserveDraft(), true);
  assert.equal(valid.backs(), 0);
  assert.equal(valid.writes[0].settings.searchServices[1].password, 'draft-before-tab-change');
  draft.settings.searchServices[1] = { type: 'perplexity', id: 'selected', apiKey: 'credential', maxTokens: null, maxTokensPerPage: 512 };
  const invalid = editor(draft);
  invalid.page.dMaxTokens = 'invalid'; invalid.page.edited();
  assert.equal(invalid.page.preserveDraft(), false);
  assert.equal(invalid.writes.length, 0);
  assert.equal(invalid.backs(), 0);
});
