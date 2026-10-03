import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  webDavBuildUrl, webDavBackupFileName, createWebDavClient, type WebDavConfig, type WebDavRequest, type WebDavClient,
} from '../main/ets/chat/sync_webdav.ts';

const cfg: WebDavConfig = {
  url: 'https://dav.example.com/dav/', username: 'u', password: 'p', path: 'amber_agent_backups',
};
const bytes = (text: string) => new TextEncoder().encode(text);
const response = (status: number, text = '') => ({ status, body: bytes(text) });

test('webDavBuildUrl joins base + path + name', () => {
  assert.equal(webDavBuildUrl(cfg, ['a.abin']), 'https://dav.example.com/dav/amber_agent_backups/a.abin');
  assert.equal(webDavBuildUrl({ ...cfg, path: '/' }, []), 'https://dav.example.com/dav');
});

test('decoded remote filename is encoded exactly once for requests', () => {
  assert.equal(webDavBuildUrl(cfg, ['备份 #1%.abin']),
    'https://dav.example.com/dav/amber_agent_backups/%E5%A4%87%E4%BB%BD%20%231%25.abin');
});

test('backup file name format', () => {
  assert.match(webDavBackupFileName(0), /^amber-backup-.*\.abin$/);
});

test('put/get preserve all byte values and binary subarrays', async () => {
  const original = Uint8Array.from({ length: 256 }, (_, index) => index);
  const backing = new Uint8Array(258); backing.set(original, 1);
  const view = backing.subarray(1, 257);
  const calls: WebDavRequest[] = [];
  const client = createWebDavClient({ fetch: async (request: WebDavRequest) => {
    calls.push(request);
    return request.method === 'GET' ? { status: 200, body: original } : response(201);
  } }, cfg);
  await client.put('x.abin', view);
  assert.equal(calls[0].method, 'PUT');
  assert.equal(calls[0].headers.Authorization, 'Basic dTpw');
  assert.deepEqual(calls[0].body, original);
  assert.deepEqual(await client.get('x.abin'), original);
});

const operations: [string, (client: WebDavClient) => Promise<unknown>][] = [
  ['PUT', client => client.put('x', new Uint8Array([1]))],
  ['GET', client => client.get('x')], ['PROPFIND', client => client.list()], ['MKCOL', client => client.mkcol('x')],
];
for (const [method, operation] of operations) {
  test(`${method} reports HTTP errors instead of empty/success result`, async () => {
    const client = createWebDavClient({ fetch: async () => response(401, 'Unauthorized') }, cfg);
    await assert.rejects(() => operation(client), new RegExp(`${method} failed: 401`));
  });
}

test('list uses PROPFIND Depth 1 and accepts arbitrary DAV namespace prefixes', async () => {
  const calls: WebDavRequest[] = [];
  const xml = '<D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/amber_agent_backups/</D:href></D:response>'
    + '<D:response><D:href>/dav/amber_agent_backups/%E5%A4%87%E4%BB%BD%20%231%25.abin</D:href>'
    + '<D:propstat><D:prop><D:getcontentlength>256</D:getcontentlength>'
    + '<D:getlastmodified>Fri, 02 Oct 2026 00:00:00 GMT</D:getlastmodified></D:prop>'
    + '<D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>';
  const client = createWebDavClient({ fetch: async (request: WebDavRequest) => { calls.push(request); return response(207, xml); } }, cfg);
  assert.deepEqual(await client.list(), [{
    name: '备份 #1%.abin', href: '/dav/amber_agent_backups/%E5%A4%87%E4%BB%BD%20%231%25.abin',
    size: 256, lastModified: 'Fri, 02 Oct 2026 00:00:00 GMT',
  }]);
  assert.equal(calls[0].method, 'PROPFIND');
  assert.equal(calls[0].headers.Depth, '1');
});

test('list unescapes XML filename entities and omits collection entries', async () => {
  const xml = '<multistatus xmlns="DAV:"><response><href>/dav/amber_agent_backups/folder</href>'
    + '<propstat><prop><resourcetype><collection/></resourcetype></prop></propstat></response>'
    + '<response><href>/dav/amber_agent_backups/a&amp;b.abin</href></response></multistatus>';
  const client = createWebDavClient({ fetch: async () => response(207, xml) }, cfg);
  assert.equal((await client.list())[0].name, 'a&b.abin');
  assert.equal((await client.list()).length, 1);
});

test('MKCOL sends the DAV method and does not ignore a missing parent (409)', async () => {
  let request: WebDavRequest | undefined;
  const client = createWebDavClient({ fetch: async (value: WebDavRequest) => { request = value; return response(201); } }, cfg);
  await client.mkcol('folder');
  assert.equal(request?.method, 'MKCOL');
  const conflict = createWebDavClient({ fetch: async () => response(409) }, cfg);
  await assert.rejects(() => conflict.mkcol('folder'), /MKCOL failed: 409/);
});

test('successful properties survive a missing-property 404 propstat', async () => {
  const xml = '<d:multistatus xmlns:d="DAV:"><d:response><d:href>/dav/amber_agent_backups/a.abin</d:href>'
    + '<d:propstat><d:prop><d:getlastmodified/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>'
    + '<d:propstat><d:prop><d:getcontentlength>256</d:getcontentlength></d:prop>'
    + '<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>';
  const client = createWebDavClient({ fetch: async () => response(207, xml) }, cfg);
  assert.deepEqual(await client.list(), [{ name: 'a.abin', href: '/dav/amber_agent_backups/a.abin',
    size: 256, lastModified: '' }]);
});
test('HTML directory success is not mistaken for an empty DAV listing', async () => {
  const client = createWebDavClient({ fetch: async () => response(200, '<html>directory</html>') }, cfg);
  await assert.rejects(() => client.list(), /PROPFIND failed: 200/);
});
test('MKCOL 405 means the configured target already exists', async () => {
  const client = createWebDavClient({ fetch: async () => response(405) }, cfg);
  await client.mkcol('');
});

for (const href of ['/dav/amber_agent_backups/a&#38;b.abin', '/dav/amber_agent_backups/a&#x26;b.abin']) {
  test('numeric XML entities produce the actual remote filename: ' + href, async () => {
    const requests: WebDavRequest[] = [];
    const xml = `<d:multistatus><d:response><d:href>${href}</d:href></d:response></d:multistatus>`;
    const client = createWebDavClient({ fetch: async (request: WebDavRequest) => {
      requests.push(request); return request.method === 'PROPFIND' ? response(207, xml) : response(200);
    } }, cfg);
    const files = await client.list();
    assert.equal(files[0].name, 'a&b.abin');
    await client.get(files[0].name);
    assert.equal(requests[1].url, 'https://dav.example.com/dav/amber_agent_backups/a%26b.abin');
  });
}
test('escaped literal entity is decoded once, not interpreted a second time', async () => {
  const xml = '<d:multistatus><d:response><d:href>/dav/amber_agent_backups/a&amp;#38;b.abin</d:href>'
    + '</d:response></d:multistatus>';
  const client = createWebDavClient({ fetch: async () => response(207, xml) }, cfg);
  assert.equal((await client.list())[0].name, 'a&#38;b.abin');
});
for (const password of ['🔑', '密🔑码', '\uD83D', '\uDC11']) {
  test('Basic auth uses real UTF-8 for credentials ' + JSON.stringify(password), async () => {
    let authorization = '';
    const client = createWebDavClient({ fetch: async (request: WebDavRequest) => {
      authorization = request.headers.Authorization; return response(200);
    } }, { ...cfg, password });
    await client.get('backup.abin');
    assert.equal(authorization, 'Basic ' + Buffer.from(new TextEncoder().encode(`u:${password}`)).toString('base64'));
  });
}
