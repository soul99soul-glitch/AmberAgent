import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { isAllowedPluginFilePath, pluginPathCompare, preparePluginPackage } from '@amber/chat-domain';
import type { AgentTool, PluginArchivePort, PluginFile, PluginHashPort, PluginPackage,
  PluginReadResult, PluginSignature, PluginTrust } from '@amber/chat-domain';

export class PluginArchiveError extends Error {
  public readonly code: string;
  constructor(code: string) { super(code); this.code = code; }
}

export interface PluginArchiveMember { path: string; bytes: Uint8Array; }
interface ArchiveMemberJSON { path: string; dataBase64: string; }
interface ArchiveJSON { schema: string; files: ArchiveMemberJSON[]; signature?: PluginSignature; }
export interface DecodedPluginArchive { files: PluginArchiveMember[]; signature: PluginSignature | null; }

export const pluginUTF8 = (text: string): Uint8Array => util.TextEncoder.create('utf-8').encodeInto(text);
export const pluginText = (bytes: Uint8Array): string =>
  util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes);
export const pluginSHA256 = (bytes: Uint8Array): string => {
  const md: cryptoFramework.Md = cryptoFramework.createMd('SHA256');
  md.updateSync({ data: bytes });
  let hash: string = '';
  for (const byte of md.digestSync().data) hash += byte.toString(16).padStart(2, '0');
  return hash;
};

export const isPluginMemberPath = (path: string): boolean => isAllowedPluginFilePath(path);

const base64Bytes = (text: string, limit: number): Uint8Array => {
  const match: RegExpMatchArray | null = text.match(/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/);
  if (match === null || match[0] !== text || text.length > Math.ceil(limit / 3) * 4) {
    throw new PluginArchiveError('archive_invalid');
  }
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(text);
  if (bytes.byteLength > limit || new util.Base64Helper().encodeToStringSync(bytes) !== text) {
    throw new PluginArchiveError('archive_invalid');
  }
  return bytes;
};

export const decodePluginArchive = (bytes: Uint8Array): DecodedPluginArchive => {
  if (bytes.byteLength > 2 * 1024 * 1024) throw new PluginArchiveError('archive_invalid');
  try {
    const archive: ArchiveJSON = JSON.parse(pluginText(bytes)) as ArchiveJSON;
    if (archive === null || archive.schema !== 'amber.plugin.archive.v1' || !Array.isArray(archive.files) ||
      archive.files.length === 0 || archive.files.length > 32) throw new PluginArchiveError('archive_invalid');
    const files: PluginArchiveMember[] = [];
    const paths: Set<string> = new Set<string>();
    let total: number = 0;
    for (const member of archive.files) {
      if (member === null || !isPluginMemberPath(member.path) || paths.has(member.path)) {
        throw new PluginArchiveError('archive_invalid');
      }
      const data: Uint8Array = base64Bytes(member.dataBase64, 256 * 1024);
      total += data.byteLength;
      if (total > 1024 * 1024) throw new PluginArchiveError('file_budget_exceeded');
      paths.add(member.path); files.push({ path: member.path, bytes: data });
    }
    return { files, signature: archive.signature ?? null };
  } catch (error) {
    if (error instanceof PluginArchiveError) throw error;
    throw new PluginArchiveError('archive_invalid');
  }
};

export const pluginPublicKeyFingerprint = (publicKeyBase64: string): string => {
  try {
    const bytes: Uint8Array = base64Bytes(publicKeyBase64, 32);
    if (bytes.byteLength !== 32) throw new PluginArchiveError('signature_invalid');
    return pluginSHA256(bytes);
  } catch (error) {
    if (error instanceof PluginArchiveError || error instanceof TypeError) throw new PluginArchiveError('signature_invalid');
    throw error as Error;
  }
};

export const verifyPluginSignature = async (
  signature: PluginSignature, packageHash: string,
): Promise<string> => {
  if (signature.algorithm !== 'ed25519') throw new PluginArchiveError('signature_invalid');
  let key: Uint8Array; let signed: Uint8Array;
  try { key = base64Bytes(signature.publicKeyBase64, 32); signed = base64Bytes(signature.signatureBase64, 64); }
  catch (error) {
    if (error instanceof PluginArchiveError || error instanceof TypeError) throw new PluginArchiveError('signature_invalid');
    throw error as Error;
  }
  const fingerprint: string = pluginPublicKeyFingerprint(signature.publicKeyBase64);
  if (key.byteLength !== 32 || signed.byteLength !== 64 || signature.keyId !== fingerprint.slice(0, 16)) {
    throw new PluginArchiveError('signature_invalid');
  }
  let hex: string = '';
  for (const byte of key) hex += byte.toString(16).padStart(2, '0');
  // AsyKeySpec bigint parameters use positive big-endian values (official API 11).
  const spec: cryptoFramework.ED25519PubKeySpec = { algName: 'Ed25519',
    specType: cryptoFramework.AsyKeySpecType.PUBLIC_KEY_SPEC, pk: BigInt(`0x${hex}`) };
  const publicKey: cryptoFramework.PubKey = await cryptoFramework.createAsyKeyGeneratorBySpec(spec).generatePubKey();
  const verifier: cryptoFramework.Verify = cryptoFramework.createVerify('Ed25519');
  await verifier.init(publicKey);
  if (!await verifier.verify({ data: pluginUTF8(`amber.plugin.signature.v1\0${packageHash}`) }, { data: signed })) {
    throw new PluginArchiveError('signature_invalid');
  }
  return fingerprint;
};

export const encodePluginArchive = (
  files: PluginArchiveMember[], signature: PluginSignature | null,
): Uint8Array => {
  const members: ArchiveMemberJSON[] = files.slice().sort(
    (a: PluginArchiveMember, b: PluginArchiveMember): number => pluginPathCompare(a.path, b.path),
  ).map((file: PluginArchiveMember): ArchiveMemberJSON => ({ path: file.path,
    dataBase64: new util.Base64Helper().encodeToStringSync(file.bytes) }));
  const archive: ArchiveJSON = { schema: 'amber.plugin.archive.v1', files: members };
  if (signature !== null) archive.signature = signature;
  const bytes: Uint8Array = pluginUTF8(JSON.stringify(archive));
  decodePluginArchive(bytes);
  return bytes;
};

export const pluginHashPort: PluginHashPort = {
  sha256: async (bytes: Uint8Array): Promise<string> => pluginSHA256(bytes),
};

class EntryPluginArchive implements PluginArchivePort {
  async decode(bytes: Uint8Array, primitives: AgentTool[]): Promise<PluginReadResult> {
    const archive: DecodedPluginArchive = decodePluginArchive(bytes);
    const files: PluginFile[] = archive.files.map((member: PluginArchiveMember): PluginFile =>
      ({ path: member.path, data: Array.from(member.bytes) }));
    const candidate: PluginPackage = await preparePluginPackage(files, primitives, pluginHashPort);
    const trust: PluginTrust = { tier: 'local_unsigned', publisherTrusted: false,
      keyId: null, fingerprint: null, signature: null };
    if (archive.signature !== null) {
      trust.fingerprint = await verifyPluginSignature(archive.signature, candidate.hash);
      trust.tier = 'signed'; trust.keyId = archive.signature.keyId;
      const signature: PluginSignature = { algorithm: 'ed25519', keyId: archive.signature.keyId,
        publicKeyBase64: archive.signature.publicKeyBase64, signatureBase64: archive.signature.signatureBase64 };
      trust.signature = signature;
    }
    return { candidate, trust };
  }

  async encode(candidate: PluginPackage, trust: PluginTrust): Promise<Uint8Array> {
    if (trust.tier === 'signed') {
      if (trust.signature === null || trust.fingerprint !== await verifyPluginSignature(trust.signature, candidate.hash)) {
        throw new PluginArchiveError('signature_invalid');
      }
    } else if (trust.tier !== 'local_unsigned' || trust.signature !== null) {
      throw new PluginArchiveError('signature_invalid');
    }
    return encodePluginArchive(candidate.files.map((file: PluginFile): PluginArchiveMember =>
      ({ path: file.path, bytes: new Uint8Array(file.data) })), trust.signature);
  }
}

export const createEntryPluginArchive = (): PluginArchivePort => new EntryPluginArchive();
