import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import ts from 'typescript';
import * as domain from '../main/ets/index.ts';

interface ImageSource { path: string; extension: string; temporary: boolean }
interface SourceAPI {
  prepareImageSource: (url: string, extension?: string) => Promise<ImageSource>;
  releaseImageSource: (source: ImageSource) => void;
}
interface SaveAPI { saveImageToAlbum: (url: string) => Promise<void> }
interface ShareAPI { shareImage: (url: string, context: object, anchor?: string) => Promise<void> }
interface SharedRecord { utd: string; uri: string }
const sourceRoot = new URL('../../../entry/src/main/ets/platform_impl/', import.meta.url);

const harness = (t: test.TestContext) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-image-actions-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const album = path.join(root, 'album');
  const cache = path.join(root, 'cache');
  fs.mkdirSync(album); fs.mkdirSync(cache);
  const toasts: string[] = [];
  const configs: { fileNameExtension: string }[] = [];
  const shared: SharedRecord[] = [];
  let dismiss: (() => void) | undefined;
  let shareError = false;
  let albumCancel = false;
  let httpCode = 200;
  let httpBytes = new Uint8Array([0xff, 0xd8, 0xff, 0x42]);
  let destroyed = 0;
  const filePath = (value: string) => value.replace(/^file:\/\/app/, '');
  const fileIo = {
    OpenMode: { READ_ONLY: fs.constants.O_RDONLY, WRITE_ONLY: fs.constants.O_WRONLY,
      READ_WRITE: fs.constants.O_RDWR, CREATE: fs.constants.O_CREAT, TRUNC: fs.constants.O_TRUNC },
    openSync: (name: string, flags: number) => ({ fd: fs.openSync(filePath(name), flags) }),
    writeSync: (fd: number, bytes: ArrayBuffer) => fs.writeSync(fd, new Uint8Array(bytes)),
    readSync: (fd: number, bytes: ArrayBuffer) => fs.readSync(fd, new Uint8Array(bytes)),
    closeSync: fs.closeSync,
    unlinkSync: fs.unlinkSync,
    copyFileSync: (src: number, dest: number) => fs.writeSync(dest, fs.readFileSync(src)),
  };
  const imports: Record<string, unknown> = {
    '@kit.AbilityKit': {},
    '@kit.CoreFileKit': { fileIo, fileUri: { getUriFromPath: (name: string) => `file://app${name}` } },
    '@kit.ArkTS': { util: { Base64Helper: class {
      decodeSync(value: string) { return new Uint8Array(Buffer.from(value, 'base64')); }
    } } },
    '@kit.NetworkKit': { http: { RequestMethod: { GET: 'GET' }, HttpDataType: { ARRAY_BUFFER: 'arraybuffer' },
      createHttp: () => ({ request: async () => ({ responseCode: httpCode, result: httpBytes.buffer }),
        destroy: () => { destroyed++; } }) } },
    '@kit.ArkUI': { promptAction: { showToast: ({ message }: { message: string }) => toasts.push(message) } },
    '@kit.ImageKit': {}, '@amber/chat-domain': domain,
    '@kit.ArkData': { uniformTypeDescriptor: { UniformDataType: { IMAGE: 'image' } } },
    '@kit.ShareKit': { systemShare: {
      SharedData: class { constructor(public record: SharedRecord) {} },
      ShareController: class {
        constructor(public data: { record: SharedRecord }) { shared.push(data.record); }
        on(_event: string, callback: () => void) { dismiss = callback; }
        async show() { if (shareError) throw new Error('share rejected'); }
      },
    } },
    '@kit.MediaLibraryKit': { photoAccessHelper: {
      PhotoType: { IMAGE: 1 }, PhotoSubtype: { DEFAULT: 0 },
      getPhotoAccessHelper: () => ({
        showAssetsCreationDialog: async (_uris: string[], values: { fileNameExtension: string }[]) => {
          configs.push(...values);
          if (albumCancel) return [];
          const target = path.join(album, `saved.${values[0]!.fileNameExtension}`);
          fs.writeFileSync(target, '');
          return [target];
        }, release: async () => {},
      }),
    } },
  };
  const load = <T>(name: string): T => {
    const compiled = ts.transpileModule(fs.readFileSync(new URL(name, sourceRoot), 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
    }).outputText;
    const module = { exports: {} };
    new Function('require', 'exports', 'module', 'AppStorage', 'canIUse', compiled)(
      (id: string) => { assert.ok(id in imports, `unexpected import ${id}`); return imports[id]; },
      module.exports, module, { get: () => ({ cacheDir: cache }) }, () => true,
    );
    return module.exports as T;
  };
  const source = load<SourceAPI>('ImageSourceSupport.ets');
  imports['./ImageSourceSupport.ets'] = source;
  return { root, album, cache, configs, shared, toasts, source,
    save: load<SaveAPI>('ImageSaveSupport.ets'), share: load<ShareAPI>('ImageShareSupport.ets'),
    dismiss: () => { assert.ok(dismiss); dismiss(); },
    failShare: () => { shareError = true; }, cancelAlbum: () => { albumCancel = true; },
    httpStatus: (code: number) => { httpCode = code; }, destroyed: () => destroyed,
  };
};
const jpgBytes = new Uint8Array([0xff, 0xd8, 0xff, 0x42]);
const dataImage = `data:image/jpeg;base64,${Buffer.from(jpgBytes).toString('base64')}`;

test('generated data image saves real bytes with correct extension and cleans its cache', async (t) => {
  const h = harness(t);
  await h.save.saveImageToAlbum(dataImage);
  assert.deepEqual(fs.readFileSync(path.join(h.album, 'saved.jpg')), Buffer.from(jpgBytes));
  assert.equal(h.configs[0]!.fileNameExtension, 'jpg');
  assert.deepEqual(fs.readdirSync(h.cache), []);
  assert.equal(h.toasts.at(-1), '已保存到相册');
});

test('cancelled album request removes only temporary source, existing files survive save', async (t) => {
  const h = harness(t);
  h.cancelAlbum();
  await h.save.saveImageToAlbum(dataImage);
  assert.deepEqual(fs.readdirSync(h.cache), []);
  const owned = path.join(h.root, 'owned.png');
  fs.writeFileSync(owned, 'owned');
  await h.save.saveImageToAlbum(`file://${owned}`);
  assert.equal(fs.readFileSync(owned, 'utf8'), 'owned');
});

test('system share publishes an image file URI and keeps it readable until panel dismissal', async (t) => {
  const h = harness(t);
  await h.share.shareImage(dataImage, {}, 'share-anchor');
  assert.equal(h.shared[0]!.utd, 'image');
  assert.match(h.shared[0]!.uri, /^file:\/\/app.*\.jpg$/);
  const sharedPath = h.shared[0]!.uri.slice('file://app'.length);
  assert.deepEqual(fs.readFileSync(sharedPath), Buffer.from(jpgBytes));
  h.dismiss();
  assert.equal(fs.existsSync(sharedPath), false);
});

test('failed share removes cache; legacy JPEG with png filename shares without deleting owned source', async (t) => {
  const h = harness(t);
  const owned = path.join(h.root, 'owned.png');
  fs.writeFileSync(owned, jpgBytes);
  const source = await h.source.prepareImageSource(`file://${owned}`);
  assert.equal(source.extension, 'jpg');
  assert.equal(source.temporary, false);
  await h.share.shareImage(`file://${owned}`, {});
  h.dismiss();
  assert.equal(fs.existsSync(owned), true);
  h.failShare();
  await h.share.shareImage(dataImage, {});
  assert.deepEqual(fs.readdirSync(h.cache), []);
  assert.match(h.toasts.at(-1)!, /分享失败:share rejected/);
});

test('network image uses detected byte type; unsuccessful HTTP does not publish an image file', async (t) => {
  const h = harness(t);
  const source = await h.source.prepareImageSource('https://example.com/generated?token=opaque');
  assert.equal(source.extension, 'jpg');
  assert.deepEqual(fs.readFileSync(source.path), Buffer.from(jpgBytes));
  h.source.releaseImageSource(source);
  h.httpStatus(403);
  await assert.rejects(h.source.prepareImageSource('https://example.com/blocked.png'), /下载失败/);
  assert.equal(h.destroyed(), 2);
  assert.deepEqual(fs.readdirSync(h.cache), []);
});

test('unsupported or empty data is rejected before creating cache files', async (t) => {
  const h = harness(t);
  await assert.rejects(h.source.prepareImageSource('data:text/plain;base64,aGVsbG8='), /受支持/);
  await assert.rejects(h.source.prepareImageSource('data:image/png;base64,'), /内容为空/);
  assert.deepEqual(fs.readdirSync(h.cache), []);
});
