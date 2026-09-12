import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  webDavBuildUrl, webDavBackupFileName, createWebDavClient, type WebDavConfig,
} from '../main/ets/chat/sync_webdav.ts';
import type { HttpClient, HttpRequest } from '@amber/deepread-domain';

const cfg: WebDavConfig = {
  url: 'https://dav.example.com/dav/',
  username: 'u',
  password: 'p',
  path: 'amber_agent_backups',
};

test('webDavBuildUrl joins base + path + name', () => {
  assert.equal(webDavBuildUrl(cfg, ['a.abin']),
    'https://dav.example.com/dav/amber_agent_backups/a.abin');
  assert.equal(webDavBuildUrl({ ...cfg, path: '/' }, []),
    'https://dav.example.com/dav');
});

test('backup file name format', () => {
  const name = webDavBackupFileName(0);
  assert.ok(name.startsWith('amber-backup-'));
  assert.ok(name.endsWith('.abin'));
});

test('put/get/basic auth', async () => {
  const calls: HttpRequest[] = [];
  const http: HttpClient = {
    fetch: async (req: HttpRequest) => {
      calls.push(req);
      if (req.method === 'GET') {
        return { status: 200, headers: {}, body: 'AB' };
      }
      return { status: 201, headers: {}, body: '' };
    },
    fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
  };
  const client = createWebDavClient(http, cfg);
  await client.put('x.abin', new Uint8Array([1, 2, 3]));
  assert.equal(calls[0].method, 'PUT');
  assert.ok(calls[0].url.endsWith('/amber_agent_backups/x.abin'));
  assert.ok((calls[0].headers['Authorization'] ?? '').startsWith('Basic '));
  const data = await client.get('x.abin');
  assert.equal(data.length, 2);
  assert.equal(data[0], 65); // 'A'
});

test('put non-2xx throws', async () => {
  const http: HttpClient = {
    fetch: async () => ({ status: 500, headers: {}, body: 'err' }),
    fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
  };
  const client = createWebDavClient(http, cfg);
  await assert.rejects(() => client.put('y', new Uint8Array([1])), /PUT failed: 500/);
});

test('list parses href entries', async () => {
  const xml = `<d:multistatus><d:response><d:href>/dav/amber_agent_backups/</d:href></d:response>`
    + `<d:response><d:href>/dav/amber_agent_backups/a.abin</d:href></d:response></d:multistatus>`;
  const http: HttpClient = {
    fetch: async () => ({ status: 207, headers: {}, body: xml }),
    fetchStream: async () => ({ status: 200, headers: {}, body: '' }),
  };
  const client = createWebDavClient(http, cfg);
  const files = await client.list();
  assert.equal(files.length, 1);
  assert.equal(files[0].name, 'a.abin');
});
