// SSH chunks are arbitrary byte boundaries. Each stream owns its own trailing bytes.
export class TerminalUTF8Decoder {
  private pending: Uint8Array = new Uint8Array(0);
  decode(bytes: Uint8Array, final: boolean = false): string {
    const data: Uint8Array = new Uint8Array(this.pending.length + bytes.length);
    data.set(this.pending); data.set(bytes, this.pending.length);
    let out: string = '';
    let i: number = 0;
    while (i < data.length) {
      const lead: number = data[i];
      const length: number = lead < 0x80 ? 1 : lead >= 0xc2 && lead <= 0xdf ? 2 :
        lead >= 0xe0 && lead <= 0xef ? 3 : lead >= 0xf0 && lead <= 0xf4 ? 4 : 0;
      if (length === 0) { out += '\ufffd'; i++; continue; }
      if (i + length > data.length) {
        if (!final) break;
        out += '\ufffd'; i = data.length; continue;
      }
      let cp: number = lead & (length === 1 ? 0x7f : length === 2 ? 0x1f : length === 3 ? 0x0f : 7);
      let valid: boolean = true;
      for (let n: number = 1; n < length; n++) {
        if ((data[i + n] & 0xc0) !== 0x80) { valid = false; break; }
        cp = (cp << 6) | (data[i + n] & 0x3f);
      }
      if (!valid || (length === 2 && cp < 0x80) || (length === 3 && cp < 0x800) ||
        (length === 4 && cp < 0x10000) || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) {
        out += '\ufffd'; i++; continue;
      }
      out += cp <= 0xffff ? String.fromCharCode(cp) :
        String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff));
      i += length;
    }
    this.pending = data.slice(i);
    return out;
  }
}
export const terminalUTF8Encode = (text: string): Uint8Array => {
  const bytes: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let cp: number = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff) {
      const low: number = text.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) { cp = 0x10000 + ((cp - 0xd800) << 10) + low - 0xdc00; i++; }
      else cp = 0xfffd;
    } else if (cp >= 0xdc00 && cp <= 0xdfff) cp = 0xfffd;
    if (cp < 0x80) bytes.push(cp);
    else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
    else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
    else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
  }
  return new Uint8Array(bytes);
};
export const terminalTail = (text: string, limit: number): string => {
  let start: number = Math.max(0, text.length - limit);
  if (start > 0 && text.charCodeAt(start) >= 0xdc00 && text.charCodeAt(start) <= 0xdfff) start++;
  return text.slice(start);
};
export const posixQuote = (text: string): string => "'" + text.replace(/'/g, "'\\''") + "'";
