// url_codec — Java URLEncoder/URLDecoder + okhttp query 组装(D-066)
//
// Android 基准:
//   java.net.URLEncoder.encode(s,"UTF-8") — 表单语义:alphanum + ".-*_" 保留,
//     空格 '+',其余 UTF-8 字节 %XX(大写 hex;Java 连 '~' 也编码)
//   java.net.URLDecoder.decode(s,"UTF-8") — '+'→空格,%XX UTF-8 解码
//   okhttp HttpUrl.Builder.addQueryParameter — percent 编码(空格 %20);
//     以 encodeURIComponent 近似(!'()* 不编码差异登记,服务端解码等价)
// ArkTS 约束:HAR 无全局 TextEncoder/TextDecoder(Node 全局;entry 才可用
//   @kit.ArkTS util)→ 手写 UTF-8 编解码(纯逻辑,无平台依赖)

// ===== 手写 UTF-8(UTF-16 code units ↔ bytes,含代理对) =====

const utf8Encode = (s: string): number[] => {
  const out: number[] = [];
  for (let i = 0; i < s.length; i++) {
    let cp: number = s.charCodeAt(i);
    if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < s.length) {
      const lo: number = s.charCodeAt(i + 1);
      if (lo >= 0xDC00 && lo <= 0xDFFF) {
        cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xC0 | (cp >> 6), 0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      out.push(0xE0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
    } else {
      out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F),
        0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
    }
  }
  return out;
};

const utf8Decode = (bytes: number[]): string => {
  let out: string = '';
  let i: number = 0;
  while (i < bytes.length) {
    const b0: number = bytes[i];
    if (b0 < 0x80) {
      out += String.fromCharCode(b0);
      i += 1;
    } else if (b0 >= 0xC0 && b0 < 0xE0 && i + 1 < bytes.length) {
      out += String.fromCharCode(((b0 & 0x1F) << 6) | (bytes[i + 1] & 0x3F));
      i += 2;
    } else if (b0 >= 0xE0 && b0 < 0xF0 && i + 2 < bytes.length) {
      out += String.fromCharCode(
        ((b0 & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F));
      i += 3;
    } else if (b0 >= 0xF0 && i + 3 < bytes.length) {
      const cp: number = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) |
        ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
      const v: number = cp - 0x10000;
      out += String.fromCharCode(0xD800 + (v >> 10), 0xDC00 + (v & 0x3FF));
      i += 4;
    } else {
      // 截断/非法序列:按单字节原样(URLDecoder 宽松路径差异登记)
      out += String.fromCharCode(b0);
      i += 1;
    }
  }
  return out;
};

// ===== Java URLEncoder/URLDecoder =====

// Java URLEncoder 保留集:A-Za-z0-9 与 . - * _
const JAVA_FORM_SAFE: RegExp = /^[A-Za-z0-9.\-*_]$/;

export const javaUrlEncodeForm = (s: string): string => {
  const bytes: number[] = utf8Encode(s);
  let out: string = '';
  for (let i = 0; i < bytes.length; i++) {
    const b: number = bytes[i];
    const ch: string = String.fromCharCode(b);
    if (b < 0x80 && JAVA_FORM_SAFE.test(ch)) {
      out += ch;
    } else if (b === 0x20) {
      out += '+';
    } else {
      out += '%' + b.toString(16).toUpperCase().padStart(2, '0');
    }
  }
  return out;
};

export const javaUrlDecodeForm = (s: string): string => {
  const bytes: number[] = [];
  for (let i = 0; i < s.length; i++) {
    const ch: string = s[i];
    if (ch === '+') {
      bytes.push(0x20);
    } else if (ch === '%' && /^[0-9A-Fa-f]{2}$/.test(s.slice(i + 1, i + 3))) {
      bytes.push(parseInt(s.slice(i + 1, i + 3), 16));
      i += 2;
    } else {
      // 非编码字符按 UTF-8 重编码(等价 URLDecoder 宽松路径)
      const encoded: number[] = utf8Encode(ch);
      for (let j = 0; j < encoded.length; j++) bytes.push(encoded[j]);
    }
  }
  return utf8Decode(bytes);
};

// okhttp addQueryParameter 链:保持插入序,空格 %20
export const buildQuery = (params: Array<[string, string]>): string =>
  params
    .map(([k, v]: [string, string]): string =>
      `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&');

// ===== okhttp Credentials.basic(user, pass)(ISO-8859-1)→ Base64 =====

const B64_ALPHABET: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

// Latin-1 字节化(charCode & 0xFF)+ 标准 Base64(含 padding)
export const base64EncodeLatin1 = (s: string): string => {
  const bytes: number[] = [];
  for (let i = 0; i < s.length; i++) bytes.push(s.charCodeAt(i) & 0xFF);
  let out: string = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const b0: number = bytes[i];
    const b1: number = i + 1 < bytes.length ? bytes[i + 1] : -1;
    const b2: number = i + 2 < bytes.length ? bytes[i + 2] : -1;
    out += B64_ALPHABET[b0 >> 2];
    out += B64_ALPHABET[((b0 & 0x03) << 4) | (b1 >= 0 ? b1 >> 4 : 0)];
    out += b1 >= 0 ? B64_ALPHABET[((b1 & 0x0F) << 2) | (b2 >= 0 ? b2 >> 6 : 0)] : '=';
    out += b2 >= 0 ? B64_ALPHABET[b2 & 0x3F] : '=';
  }
  return out;
};

// Credentials.basic(username, password) = "Basic " + base64("u:p")
export const basicAuthHeader = (username: string, password: string): string =>
  `Basic ${base64EncodeLatin1(`${username}:${password}`)}`;

// base64url(无 padding 亦可)→ UTF-8 字符串;非法输入返回 null
// 用途:Bing 跳转链接 u=a1<base64url(目标 URL)>
export const base64UrlDecodeUtf8 = (s: string): string | null => {
  const clean: string = s.replace(/-/g, '+').replace(/_/g, '/').replace(/=+$/, '');
  let bits: number = 0;
  let acc: number = 0;
  let hex: string = '';
  for (let i = 0; i < clean.length; i++) {
    const v: number = B64_ALPHABET.indexOf(clean[i]);
    if (v < 0) return null;
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      hex += '%' + ((acc >> bits) & 0xFF).toString(16).padStart(2, '0');
      acc &= (1 << bits) - 1; // 只留未消费的低位,防左移溢出
    }
  }
  try {
    return decodeURIComponent(hex);
  } catch {
    return null;
  }
};
