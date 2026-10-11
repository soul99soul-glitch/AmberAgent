// PersonalContextPort — contacts_pick / photos_pick 的 entry 实现
//
// 联系人:@kit.ContactsKit contact.selectContacts()(系统选择器,无需 READ_CONTACTS;
//   仅返回用户在选择器里勾选的联系人,绝不枚举通讯录)。
// 照片:@kit.MediaLibraryKit photoAccessHelper.PhotoViewPicker(无需媒体库权限)→
//   复用发图管线 compressImageAsDataUrl(JPEG q85 长边 ≤2048 + EXIF 归一,gif 直通)
//   → data: URL + 解码出的字节(域层落 workspace)。
// 选择器同一时刻只允许一个(busy 守卫),避免模型并发调用叠窗。
//
// 偏差/不确定:
//   - selectContacts 用户取消的表现(返回空数组 vs reject)以官方 d.ts 为准:
//     "returns empty contact list if user not select" → 空数组 = 域层 empty_selection。
//   - PhotoViewPicker.select / selectContacts:用户取消官方表现为 resolve 空结果(域层 empty_selection);
//     reject 时按 message 含 "cancel" 判取消,其余按 failed 并带 BusinessError code(d.ts 未列出取消专用码)。
//   - 应用在后台(AppStorage 'appBackgrounded')时不拉起选择器,返回 not_foreground。
//   - GIF 直通不压缩:读文件大小 > 3.5MB 的 GIF 在 compressImageAsDataUrl(整文件读入 + base64)之前就拒绝,
//     经 PickResult.skippedTooLarge 回报域层。
//   - ContactSelectionOptions.maxSelectable 为 API 15,工程 compatibleSdk=12,不使用;
//     超选由域层 slice 并标 truncated。

import { contact } from '@kit.ContactsKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type {
  PersonalContextPort, PickResult, PickStatus, PickedContact, PickedPhoto,
} from '@amber/chat-domain';
import type { AbortSignalLike } from '@amber/deepread-domain';
import { compressImageAsDataUrl } from './ImageCompress.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'PersonalContextPort';
const ERR_CAPABILITY_NOT_SUPPORTED: number = 801;
// 与域层 PHOTOS_PICK_MAX_GIF_BYTES 保持一致(index.ts 未导出该常量)
const MAX_GIF_BYTES: number = 3.5 * 1024 * 1024;

class ContactItem implements PickedContact {
  name: string;
  phoneNumbers: string[];
  emailAddresses: string[];

  constructor(name: string, phoneNumbers: string[], emailAddresses: string[]) {
    this.name = name;
    this.phoneNumbers = phoneNumbers;
    this.emailAddresses = emailAddresses;
  }
}

class PhotoItem implements PickedPhoto {
  mimeType: string;
  bytes: Uint8Array;
  dataUrl: string;

  constructor(mimeType: string, bytes: Uint8Array, dataUrl: string) {
    this.mimeType = mimeType;
    this.bytes = bytes;
    this.dataUrl = dataUrl;
  }
}

class ContactPickOutcome implements PickResult<PickedContact> {
  status: PickStatus;
  reason: string;
  items: PickedContact[];

  constructor(status: PickStatus, reason: string, items: PickedContact[]) {
    this.status = status;
    this.reason = reason;
    this.items = items;
  }
}

class PhotoPickOutcome implements PickResult<PickedPhoto> {
  status: PickStatus;
  reason: string;
  items: PickedPhoto[];
  skippedUnreadable: number;
  skippedTooLarge: number;

  constructor(status: PickStatus, reason: string, items: PickedPhoto[],
    skippedUnreadable: number = 0, skippedTooLarge: number = 0) {
    this.status = status;
    this.reason = reason;
    this.items = items;
    this.skippedUnreadable = skippedUnreadable;
    this.skippedTooLarge = skippedTooLarge;
  }
}

const NOT_FOREGROUND_REASON: string =
  'Amber is in the background, so the system picker cannot be shown. Ask the user to bring Amber to the foreground and retry.';

const isBackgrounded = (): boolean => AppStorage.get<boolean>('appBackgrounded') === true;

const looksCancelled = (message: string): boolean => message.toLowerCase().indexOf('cancel') >= 0;

// fullName 为空时回退 姓+名(CJK 直接拼接,其余空格分隔)→ 昵称
const contactName = (c: contact.Contact): string => {
  const n: contact.Name | undefined = c.name;
  if (n !== undefined) {
    const full: string = (n.fullName ?? '').trim();
    if (full.length > 0) return full;
    const family: string = (n.familyName ?? '').trim();
    const given: string = (n.givenName ?? '').trim();
    if (family.length > 0 || given.length > 0) {
      const probe: string = family.length > 0 ? family : given;
      const cjk: boolean = probe.charCodeAt(0) >= 0x2E80;
      return cjk ? `${family}${given}` : `${family} ${given}`.trim();
    }
  }
  const nick: contact.NickName | undefined = c.nickName;
  return nick !== undefined ? (nick.nickName ?? '').trim() : '';
};

// GIF 魔数 'GIF8' 且超过上限 → true(任何读取失败当作"不是超大 GIF",交给后续管线报 unreadable)
const isOversizedGif = (uri: string): boolean => {
  let file: fileIo.File | null = null;
  try {
    file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
    const size: number = fileIo.statSync(file.fd).size;
    if (size <= MAX_GIF_BYTES) return false;
    const buf: ArrayBuffer = new ArrayBuffer(4);
    fileIo.readSync(file.fd, buf, { offset: 0, length: 4 });
    const head: Uint8Array = new Uint8Array(buf);
    return head[0] === 0x47 && head[1] === 0x49 && head[2] === 0x46 && head[3] === 0x38;
  } catch {
    return false;
  } finally {
    if (file !== null) {
      try {
        fileIo.closeSync(file);
      } catch {
        // ignore
      }
    }
  }
};

// data:<mime>;base64,<payload> → mime + 字节
const decodeDataUrl = (dataUrl: string): PhotoItem => {
  const comma: number = dataUrl.indexOf(',');
  if (!dataUrl.startsWith('data:') || comma < 0) {
    throw new Error('Unexpected image encoding.');
  }
  const header: string = dataUrl.substring(5, comma);
  const semi: number = header.indexOf(';');
  const mime: string = semi >= 0 ? header.substring(0, semi) : header;
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(dataUrl.substring(comma + 1));
  return new PhotoItem(mime.length > 0 ? mime : 'image/jpeg', bytes, dataUrl);
};

class EntryPersonalContextPort implements PersonalContextPort {
  private busy: boolean = false;

  async pickContacts(maxCount: number, signal?: AbortSignalLike): Promise<PickResult<PickedContact>> {
    if (signal !== undefined && signal.aborted) return new ContactPickOutcome('cancelled', 'The request was cancelled.', []);
    if (isBackgrounded()) return new ContactPickOutcome('not_foreground', NOT_FOREGROUND_REASON, []);
    if (this.busy) return new ContactPickOutcome('busy', 'Another picker is already open.', []);
    this.busy = true;
    try {
      const options: contact.ContactSelectionOptions = {};
      options.isMultiSelect = maxCount > 1;
      const selected: contact.Contact[] = await contact.selectContacts(options);
      const items: PickedContact[] = [];
      for (const c of selected) {
        const phones: string[] = [];
        const emails: string[] = [];
        const phoneList: contact.PhoneNumber[] | undefined = c.phoneNumbers;
        if (phoneList !== undefined) {
          for (const p of phoneList) phones.push(p.phoneNumber);
        }
        const emailList: contact.Email[] | undefined = c.emails;
        if (emailList !== undefined) {
          for (const e of emailList) emails.push(e.email);
        }
        items.push(new ContactItem(contactName(c), phones, emails));
      }
      return new ContactPickOutcome('ok', '', items);
    } catch (e) {
      const err: BusinessError = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'selectContacts failed: %{public}d %{public}s', err.code, err.message);
      if (err.code === ERR_CAPABILITY_NOT_SUPPORTED) {
        return new ContactPickOutcome('unavailable', 'The contact picker is not supported on this device.', []);
      }
      if (looksCancelled(err.message ?? '')) {
        return new ContactPickOutcome('cancelled', 'The user closed the contact picker.', []);
      }
      return new ContactPickOutcome('failed', `Contact picker failed (code ${err.code}): ${err.message}`, []);
    } finally {
      this.busy = false;
    }
  }

  async pickPhotos(maxCount: number, signal?: AbortSignalLike): Promise<PickResult<PickedPhoto>> {
    if (signal !== undefined && signal.aborted) return new PhotoPickOutcome('cancelled', 'The request was cancelled.', []);
    if (isBackgrounded()) return new PhotoPickOutcome('not_foreground', NOT_FOREGROUND_REASON, []);
    if (this.busy) return new PhotoPickOutcome('busy', 'Another picker is already open.', []);
    this.busy = true;
    try {
      const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
      options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
      options.maxSelectNumber = maxCount;
      const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
      let uris: string[];
      try {
        const result: photoAccessHelper.PhotoSelectResult = await picker.select(options);
        uris = result.photoUris;
      } catch (e) {
        // 用户取消通常 resolve 空 photoUris;reject 时按 message 判取消,其余 failed 并带 code
        const err: BusinessError = e as BusinessError;
        hilog.warn(DOMAIN, TAG, 'photo picker rejected: %{public}d %{public}s', err.code, err.message);
        if (looksCancelled(err.message ?? '')) {
          return new PhotoPickOutcome('cancelled', 'The user closed the photo picker.', []);
        }
        return new PhotoPickOutcome('failed', `Photo picker failed (code ${err.code}): ${err.message}`, []);
      }
      const items: PickedPhoto[] = [];
      let failed: number = 0;
      let tooLarge: number = 0;
      for (const uri of uris) {
        if (signal !== undefined && signal.aborted) break;
        if (isOversizedGif(uri)) {
          tooLarge += 1;
          continue;
        }
        try {
          items.push(decodeDataUrl(await compressImageAsDataUrl(uri)));
        } catch (e) {
          failed += 1;
          hilog.warn(DOMAIN, TAG, 'photo materialize failed: %{public}s', (e as Error).message);
        }
      }
      if (items.length === 0 && failed > 0 && tooLarge === 0) {
        return new PhotoPickOutcome('failed', `${failed} selected image(s) could not be read.`, [], failed, 0);
      }
      return new PhotoPickOutcome('ok', '', items, failed, tooLarge);
    } finally {
      this.busy = false;
    }
  }
}

export const createEntryPersonalContextPort = (): PersonalContextPort => new EntryPersonalContextPort();
