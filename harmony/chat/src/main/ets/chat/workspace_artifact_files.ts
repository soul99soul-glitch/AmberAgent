// Shared file naming, MIME metadata, and per-entry byte limit used by archive and Office reads.
export const MAX_ARCHIVE_ENTRY_BYTES: number = 64 * 1024 * 1024;

export const safeFileName = (name: string): string => {
  const cleaned: string = name.replace(/[^A-Za-z0-9._-]/g, '_');
  return cleaned.length > 0 ? cleaned : 'file.bin';
};

export const safeBaseName = (path: string): string => {
  const base: string = path.substring(path.lastIndexOf('/') + 1);
  const dot: number = base.lastIndexOf('.');
  const stem: string = dot > 0 ? base.substring(0, dot) : base;
  return safeFileName(stem.length > 0 ? stem : 'artifact');
};

// MimeTypeMap 常用子集(表外 application/octet-stream,:712-715)
const MIME_TABLE: Record<string, string> = {
  txt: 'text/plain', md: 'text/markdown', json: 'application/json',
  csv: 'text/csv', xml: 'text/xml', html: 'text/html', htm: 'text/html',
  css: 'text/css', js: 'application/javascript', mjs: 'text/javascript',
  png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
  webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml',
  pdf: 'application/pdf', zip: 'application/zip', tar: 'application/x-tar',
  gz: 'application/gzip', mp3: 'audio/mpeg', mp4: 'video/mp4',
  wav: 'audio/wav', doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pptx: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
};

export const mimeFromName = (name: string): string => {
  const dot: number = name.lastIndexOf('.');
  const ext: string = dot >= 0 ? name.substring(dot + 1).toLowerCase() : '';
  const hit: string | undefined = MIME_TABLE[ext];
  return hit !== undefined ? hit : 'application/octet-stream';
};
