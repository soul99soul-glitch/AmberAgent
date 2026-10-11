import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createContactsPickTool, createPhotosPickTool, createPersonalContextTools, parseMaxCount,
} from '../main/ets/chat/personal_context_tools.ts';
import type {
  PersonalContextPort, PersonalContextDeps, PickResult, PickedContact, PickedPhoto,
} from '../main/ets/chat/personal_context_tools.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const textOf = (parts: UIMessagePart[]): Record<string, unknown> => {
  const t = parts.find((p) => p.type === 'text');
  assert.ok(t && t.type === 'text');
  return JSON.parse(t.text) as Record<string, unknown>;
};

interface Harness {
  deps: PersonalContextDeps;
  calls: { contacts: number[]; photos: number[] };
  written: { path: string; size: number; mime: string }[];
}

const harness = (
  contacts: PickResult<PickedContact>, photos: PickResult<PickedPhoto>, failWrite: boolean = false,
  includeImages: boolean = true,
): Harness => {
  const calls = { contacts: [] as number[], photos: [] as number[] };
  const written: { path: string; size: number; mime: string }[] = [];
  const port: PersonalContextPort = {
    pickContacts: async (n) => { calls.contacts.push(n); return contacts; },
    pickPhotos: async (n) => { calls.photos.push(n); return photos; },
  };
  return {
    calls, written,
    deps: {
      port,
      includeImages,
      now: () => Date.UTC(2026, 9, 9, 8, 7, 6),
      random: () => 0.5, // 'iiii'
      writeWorkspaceBytes: async (path, bytes, mime) => {
        if (failWrite) throw new Error('disk full');
        written.push({ path, size: bytes.length, mime });
        return path;
      },
    },
  };
};

const ok = <T>(items: T[]): PickResult<T> => ({ status: 'ok', reason: '', items });
const photo = (n: number, mime: string = 'image/jpeg'): PickedPhoto =>
  ({ mimeType: mime, bytes: new Uint8Array(n), dataUrl: `data:${mime};base64,AAAA` });

test('parseMaxCount defaults, clamps and rejects junk', () => {
  assert.equal(parseMaxCount({}, 8), 8);
  assert.equal(parseMaxCount({ max_count: 3 }, 8), 3);
  assert.equal(parseMaxCount({ max_count: 99 }, 4), 4);
  assert.equal(parseMaxCount({ max_count: 0 }, 4), 1);
  assert.equal(parseMaxCount({ max_count: '2' }, 4), 2);
  assert.equal(parseMaxCount({ max_count: 'abc' }, 4), null);
  assert.equal(parseMaxCount({ max_count: true }, 4), null);
});

test('tool declarations mirror iOS: no approval, no auto-approval, max_count only', () => {
  const [contacts, photos] = createPersonalContextTools(harness(ok([]), ok([])).deps);
  assert.equal(contacts.name, 'contacts_pick');
  assert.equal(photos.name, 'photos_pick');
  for (const t of [contacts, photos]) {
    assert.equal(t.needsApproval, false);
    assert.equal(t.allowsAutoApproval, false);
    const schema = t.parameters();
    assert.ok(schema);
    assert.deepEqual(Object.keys(schema.properties), ['max_count']);
  }
});

test('contacts_pick returns only picked contacts, trimmed and deduped', async () => {
  const h = harness(ok([
    { name: ' 张三 ', phoneNumbers: ['138 0000 0000', '138 0000 0000', ' '], emailAddresses: ['a@b.c'] },
    { name: 'Li', phoneNumbers: [], emailAddresses: [] },
  ]), ok([]));
  const out = textOf(await createContactsPickTool(h.deps).execute({ max_count: 5 }));
  assert.deepEqual(h.calls.contacts, [5]);
  assert.equal(out.ok, true);
  assert.equal(out.selected_count, 2);
  assert.deepEqual((out.contacts as unknown[])[0], {
    name: '张三', phone_numbers: ['138 0000 0000'], email_addresses: ['a@b.c'],
  });
});

test('contacts_pick default max is 8 and truncates over-selection', async () => {
  const many = Array.from({ length: 10 }, (_, i): PickedContact =>
    ({ name: `c${i}`, phoneNumbers: [], emailAddresses: [] }));
  const h = harness(ok(many), ok([]));
  const out = textOf(await createContactsPickTool(h.deps).execute({}));
  assert.deepEqual(h.calls.contacts, [8]);
  assert.equal(out.selected_count, 8);
  assert.equal(out.truncated, true);
  assert.equal(out.picked_count, 10);
});

test('contacts_pick failure statuses', async () => {
  const cancelled = harness({ status: 'cancelled', reason: '', items: [] }, ok([]));
  assert.equal(textOf(await createContactsPickTool(cancelled.deps).execute({})).status, 'cancelled');
  const empty = harness(ok([]), ok([]));
  const e = textOf(await createContactsPickTool(empty.deps).execute({}));
  assert.equal(e.ok, false);
  assert.equal(e.status, 'empty_selection');
  const invalid = textOf(await createContactsPickTool(empty.deps).execute({ max_count: 'x' }));
  assert.equal(invalid.status, 'invalid_arguments');
  assert.deepEqual(empty.calls.contacts, [8]);
  const throwing = createContactsPickTool({
    ...empty.deps,
    port: { pickContacts: async () => { throw new Error('boom'); }, pickPhotos: empty.deps.port.pickPhotos },
  });
  const t = textOf(await throwing.execute({}));
  assert.equal(t.status, 'load_failed');
  assert.equal(t.reason, 'boom');
});

test('photos_pick writes to workspace and returns image parts + json', async () => {
  const h = harness(ok([]), ok([photo(100), photo(50, 'image/png')]));
  const parts = await createPhotosPickTool(h.deps).execute({ max_count: 2 });
  assert.deepEqual(h.calls.photos, [2]);
  assert.equal(parts.length, 3);
  assert.equal(parts[0].type, 'image');
  assert.equal(parts[1].type, 'image');
  const out = textOf(parts);
  assert.equal(out.ok, true);
  assert.equal(out.selected_count, 2);
  assert.deepEqual(h.written.map((w) => w.path), [
    'photos_pick/photo-20261009-080706-1-iiii.jpg',
    'photos_pick/photo-20261009-080706-2-iiii.png',
  ]);
  assert.equal(out.images_attached, true);
  assert.equal(out.hint, undefined);
  const first = (out.photos as Record<string, unknown>[])[0];
  assert.equal(first.path, '/workspace/photos_pick/photo-20261009-080706-1-iiii.jpg');
  assert.equal(first.mime_type, 'image/jpeg');
  assert.equal(first.byte_count, 100);
});

test('photos_pick default max 4, clamps, handles oversize and write failures', async () => {
  const h = harness(ok([]), ok([photo(13 * 1024 * 1024), photo(10)]));
  const parts = await createPhotosPickTool(h.deps).execute({ max_count: 100 });
  assert.deepEqual(h.calls.photos, [4]);
  const out = textOf(parts);
  assert.equal(out.selected_count, 1);
  assert.equal(out.skipped_too_large, 1);
  assert.equal(parts.filter((p) => p.type === 'image').length, 1);

  const onlyBig = harness(ok([]), ok([photo(13 * 1024 * 1024)]));
  assert.equal(textOf(await createPhotosPickTool(onlyBig.deps).execute({})).status, 'media_too_large');

  const failing = harness(ok([]), ok([photo(10)]), true);
  assert.equal(textOf(await createPhotosPickTool(failing.deps).execute({})).status, 'load_failed');

  const none = harness(ok([]), ok([]));
  assert.equal(textOf(await createPhotosPickTool(none.deps).execute({})).status, 'empty_selection');
  const busy = harness(ok([]), { status: 'busy', reason: 'picker open', items: [] });
  const b = textOf(await createPhotosPickTool(busy.deps).execute({}));
  assert.equal(b.status, 'busy');
  assert.equal(b.reason, 'picker open');
});

test('photos_pick without includeImages returns text only, images_attached=false and an ocr/image_info hint', async () => {
  const h = harness(ok([]), ok([photo(100), photo(50, 'image/png')]), false, false);
  const parts = await createPhotosPickTool(h.deps).execute({});
  assert.equal(parts.length, 1);
  assert.equal(parts[0].type, 'text');
  const out = textOf(parts);
  assert.equal(out.ok, true);
  assert.equal(out.images_attached, false);
  assert.match(String(out.hint), /ocr_image/);
  assert.match(String(out.hint), /image_info/);
  assert.equal(out.selected_count, 2);
  assert.equal(h.written.length, 2);
});

test('photos_pick description does not promise attached images', () => {
  const t = createPhotosPickTool(harness(ok([]), ok([])).deps);
  assert.doesNotMatch(t.description, /plus the images themselves/);
  assert.match(t.description, /images_attached/);
});

test('photos_pick caps GIFs at 3.5MB (other types keep 12MB) and reports port-side rejections', async () => {
  const gif = (n: number): PickedPhoto => photo(n, 'image/gif');
  const h = harness(ok([]), ok([gif(4 * 1024 * 1024), gif(3 * 1024 * 1024), photo(5 * 1024 * 1024)]));
  const out = textOf(await createPhotosPickTool(h.deps).execute({ max_count: 4 }));
  assert.equal(out.selected_count, 2);
  assert.equal(out.skipped_too_large, 1);
  assert.deepEqual(h.written.map((w) => w.mime), ['image/gif', 'image/jpeg']);

  const onlyBigGif = harness(ok([]), ok([gif(4 * 1024 * 1024)]));
  assert.equal(textOf(await createPhotosPickTool(onlyBigGif.deps).execute({})).status, 'media_too_large');

  // port 在编码前拒绝的超大 GIF:items 为空但 skippedTooLarge>0 → media_too_large,而非 empty_selection
  const portRejected = harness(ok([]), { status: 'ok', reason: '', items: [], skippedTooLarge: 2 });
  assert.equal(textOf(await createPhotosPickTool(portRejected.deps).execute({})).status, 'media_too_large');
  const mixed = harness(ok([]), { status: 'ok', reason: '', items: [photo(10)], skippedTooLarge: 1, skippedUnreadable: 2 });
  const m = textOf(await createPhotosPickTool(mixed.deps).execute({}));
  assert.equal(m.skipped_too_large, 1);
  assert.equal(m.skipped_unreadable, 2);
});

test('photos_pick filenames carry a random suffix so same-second picks do not collide', async () => {
  let n = 0;
  const seq = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7];
  const h = harness(ok([]), ok([photo(10)]));
  h.deps.random = () => seq[n++ % seq.length];
  await createPhotosPickTool(h.deps).execute({});
  const h2 = harness(ok([]), ok([photo(10)]));
  await createPhotosPickTool(h2.deps).execute({});
  assert.notEqual(h.written[0].path, h2.written[0].path);
  assert.match(h.written[0].path, /^photos_pick\/photo-\d{8}-\d{6}-1-[0-9a-z]{4}\.jpg$/);
});

test('not_foreground status maps through for both pickers', async () => {
  const nf: PickResult<never> = { status: 'not_foreground', reason: 'app in background', items: [] };
  const h = harness(nf, nf);
  const c = textOf(await createContactsPickTool(h.deps).execute({}));
  const p = textOf(await createPhotosPickTool(h.deps).execute({}));
  for (const out of [c, p]) {
    assert.equal(out.ok, false);
    assert.equal(out.status, 'not_foreground');
    assert.equal(out.reason, 'app in background');
  }
});

test('abort: pre-aborted signal throws AbortError without opening the picker; mid-flight abort races the picker', async () => {
  const h = harness(ok([]), ok([]));
  await assert.rejects(createContactsPickTool(h.deps).execute({}, { aborted: true }), { name: 'AbortError' });
  await assert.rejects(createPhotosPickTool(h.deps).execute({}, { aborted: true }), { name: 'AbortError' });
  assert.deepEqual(h.calls, { contacts: [], photos: [] });

  let fire: () => void = () => {};
  const signal = {
    aborted: false,
    addEventListener: (_t: string, l: () => void) => { fire = l; },
    removeEventListener: () => {},
  };
  const hanging: PersonalContextPort = {
    pickContacts: () => new Promise<PickResult<PickedContact>>(() => {}),
    pickPhotos: () => new Promise<PickResult<PickedPhoto>>(() => {}),
  };
  const pending = createPhotosPickTool({ ...h.deps, port: hanging }).execute({}, signal);
  signal.aborted = true;
  fire();
  await assert.rejects(pending, { name: 'AbortError' });
});

test('port receives the abort signal', async () => {
  let seen: unknown = null;
  const sig = { aborted: false };
  const h = harness(ok([]), ok([]));
  const port: PersonalContextPort = {
    pickContacts: async (_n, s) => { seen = s; return ok([]); },
    pickPhotos: h.deps.port.pickPhotos,
  };
  await createContactsPickTool({ ...h.deps, port }).execute({}, sig);
  assert.equal(seen, sig);
});
