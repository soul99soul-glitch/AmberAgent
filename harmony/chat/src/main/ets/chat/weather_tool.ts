// weather_tool — weather_read(iOS createWeatherReadToolDeclaration / IOSWeatherToolExecutor 对齐)
//
// iOS 基准:
//   ai-core Tool.kt createWeatherReadToolDeclaration(weather_read):参数 location(string,
//     ≤80 字符)/ use_current_location(boolean);二者须恰好给一个;无需审批;只读。
//   iosApp IOSWeatherView.swift IOSWeatherToolExecutor:结果 JSON 字段
//     ok/tool/location/temperature_celsius/apparent_temperature_celsius/humidity_percent/
//     condition/observed_at/forecast[date,condition,high_celsius,low_celsius,
//     precipitation_chance_percent]/attribution{service,legal_url,legal_text};
//     失败 {ok:false,tool,status:'failed',reason}。
//
// 偏差登记:
//   - 数据源 Apple WeatherKit → Open-Meteo(免 key):forecast + geocoding 两个端点,
//     attribution 改为 Open-Meteo(CC BY 4.0)。
//   - 新增可选 latitude/longitude(显式坐标,第三种互斥来源;不触发定位权限)。
//   - 失败 status 细分(invalid_arguments/permission_denied/location_disabled/
//     location_unavailable/not_foreground/not_found/network_error/upstream_error),iOS 恒为 'failed'。
//   - 地理编码:Open-Meteo geocoder 对「深圳市/朝阳区」这类带行政后缀的中文名零命中、「上海市」
//     会命中美国伊利诺伊州——故中文查询先去掉末尾 市/区/县/省/自治州/自治区/特别行政区 再查,
//     取 10 条候选后按「含 CJK 的查询优先 country_code=CN → 人口最多」择优。
//   - 每次 HTTP 请求(地理编码/预报)10s 超时;AbortSignal 触发时抛 AbortError(不再伪装 network_error)。
//   - 纯域层:HTTP / 设备定位 / 逆地理编码全部经 WeatherPort 注入(entry 实现)。

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart } from './message.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { AgentTool } from './tool.ts';

export const WEATHER_TOOL_NAME: string = 'weather_read';
export const WEATHER_LOCATION_MAX_CHARS: number = 80;
export const WEATHER_FORECAST_DAYS: number = 5;

const FORECAST_ENDPOINT: string = 'https://api.open-meteo.com/v1/forecast';
const GEOCODING_ENDPOINT: string = 'https://geocoding-api.open-meteo.com/v1/search';

export type WeatherLanguage = 'zh' | 'en';

// ===== Port =====

export interface WeatherHttpResult {
  status: number;
  body: string;
}

export interface DeviceLocationResult {
  ok: boolean;
  // ok=false 时的稳定状态码:permission_denied | location_disabled | location_unavailable
  status: string;
  reason: string;
  latitude: number;
  longitude: number;
}

export interface WeatherPort {
  /** GET 文本(Open-Meteo JSON);网络层失败直接 reject */
  fetchText(url: string, signal?: AbortSignalLike): Promise<WeatherHttpResult>;
  /**
   * 前台请求近似定位权限并取一次当前位置;失败走 ok=false,不 reject。
   * status 稳定码:permission_denied | location_disabled | location_unavailable | not_foreground
   */
  locateDevice(signal?: AbortSignalLike): Promise<DeviceLocationResult>;
  /** 坐标 → 地名(逆地理编码);不可用返回 null */
  reverseGeocode(latitude: number, longitude: number): Promise<string | null>;
}

// ===== WMO weather code → 文案 =====

interface WmoText {
  zh: string;
  en: string;
}

const WMO_TEXT: Record<string, WmoText> = {
  '0': { zh: '晴', en: 'Clear sky' },
  '1': { zh: '大部晴朗', en: 'Mainly clear' },
  '2': { zh: '多云', en: 'Partly cloudy' },
  '3': { zh: '阴', en: 'Overcast' },
  '45': { zh: '雾', en: 'Fog' },
  '48': { zh: '雾凇', en: 'Depositing rime fog' },
  '51': { zh: '小毛毛雨', en: 'Light drizzle' },
  '53': { zh: '毛毛雨', en: 'Moderate drizzle' },
  '55': { zh: '浓毛毛雨', en: 'Dense drizzle' },
  '56': { zh: '冻毛毛雨', en: 'Light freezing drizzle' },
  '57': { zh: '强冻毛毛雨', en: 'Dense freezing drizzle' },
  '61': { zh: '小雨', en: 'Slight rain' },
  '63': { zh: '中雨', en: 'Moderate rain' },
  '65': { zh: '大雨', en: 'Heavy rain' },
  '66': { zh: '冻雨', en: 'Light freezing rain' },
  '67': { zh: '强冻雨', en: 'Heavy freezing rain' },
  '71': { zh: '小雪', en: 'Slight snow' },
  '73': { zh: '中雪', en: 'Moderate snow' },
  '75': { zh: '大雪', en: 'Heavy snow' },
  '77': { zh: '雪粒', en: 'Snow grains' },
  '80': { zh: '小阵雨', en: 'Slight rain showers' },
  '81': { zh: '阵雨', en: 'Moderate rain showers' },
  '82': { zh: '强阵雨', en: 'Violent rain showers' },
  '85': { zh: '小阵雪', en: 'Slight snow showers' },
  '86': { zh: '大阵雪', en: 'Heavy snow showers' },
  '95': { zh: '雷暴', en: 'Thunderstorm' },
  '96': { zh: '雷暴伴小冰雹', en: 'Thunderstorm with slight hail' },
  '99': { zh: '雷暴伴大冰雹', en: 'Thunderstorm with heavy hail' },
};

export const weatherCodeText = (code: number | null, language: WeatherLanguage): string => {
  if (code === null || !Number.isFinite(code)) return language === 'zh' ? '未知' : 'Unknown';
  const entry: WmoText | undefined = WMO_TEXT[String(Math.trunc(code))];
  if (entry === undefined) return language === 'zh' ? `未知天气(代码 ${Math.trunc(code)})` : `Unknown (code ${Math.trunc(code)})`;
  return language === 'zh' ? entry.zh : entry.en;
};

// ===== 输入解析 =====

export interface WeatherRequest {
  kind: 'named' | 'current' | 'coordinates';
  location: string;
  latitude: number;
  longitude: number;
}

export interface WeatherRequestError {
  reason: string;
}

const asObject = (v: JsonValue | null | undefined): JsonObject | null =>
  v !== null && v !== undefined && typeof v === 'object' && !Array.isArray(v) ? v as JsonObject : null;

const numberField = (obj: JsonObject, key: string): number | null | undefined => {
  const raw: JsonValue | undefined = obj[key];
  if (raw === undefined || raw === null) return undefined;
  if (typeof raw === 'number') return Number.isFinite(raw) ? raw : null;
  if (typeof raw === 'string' && raw.trim().length > 0) {
    const n: number = Number(raw.trim());
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

const boolField = (obj: JsonObject, key: string): boolean | null => {
  const raw: JsonValue | undefined = obj[key];
  if (raw === undefined || raw === null) return false;
  if (typeof raw === 'boolean') return raw;
  if (typeof raw === 'string') {
    const s: string = raw.trim().toLowerCase();
    if (s === 'true') return true;
    if (s === 'false' || s.length === 0) return false;
  }
  return null;
};

export const parseWeatherRequest = (input: JsonValue): WeatherRequest | WeatherRequestError => {
  const obj: JsonObject | null = asObject(input);
  const source: JsonObject = obj === null ? {} : obj;
  const rawLocation: JsonValue | undefined = source['location'];
  if (rawLocation !== undefined && rawLocation !== null && typeof rawLocation !== 'string') {
    return { reason: 'location must be a string.' };
  }
  const location: string = typeof rawLocation === 'string' ? rawLocation.trim() : '';
  if (location.length > WEATHER_LOCATION_MAX_CHARS) {
    return { reason: `location must be at most ${WEATHER_LOCATION_MAX_CHARS} characters.` };
  }
  const current: boolean | null = boolField(source, 'use_current_location');
  if (current === null) return { reason: 'use_current_location must be a boolean.' };
  const lat: number | null | undefined = numberField(source, 'latitude');
  const lon: number | null | undefined = numberField(source, 'longitude');
  if (lat === null || lon === null) return { reason: 'latitude and longitude must be numbers.' };
  const hasCoords: boolean = lat !== undefined || lon !== undefined;
  if (hasCoords && (lat === undefined || lon === undefined)) {
    return { reason: 'latitude and longitude must be provided together.' };
  }
  const sources: number = (location.length > 0 ? 1 : 0) + (current ? 1 : 0) + (hasCoords ? 1 : 0);
  if (sources !== 1) {
    return { reason: 'Provide exactly one of `location`, `use_current_location: true`, or `latitude`+`longitude`.' };
  }
  if (hasCoords) {
    const la: number = lat as number;
    const lo: number = lon as number;
    if (la < -90 || la > 90 || lo < -180 || lo > 180) {
      return { reason: 'latitude must be within -90...90 and longitude within -180...180.' };
    }
    return { kind: 'coordinates', location: '', latitude: la, longitude: lo };
  }
  if (current) return { kind: 'current', location: '', latitude: 0, longitude: 0 };
  return { kind: 'named', location, latitude: 0, longitude: 0 };
};

const isRequestError = (r: WeatherRequest | WeatherRequestError): r is WeatherRequestError =>
  (r as WeatherRequestError).reason !== undefined;

// ===== Open-Meteo 解析 =====

export interface WeatherDay {
  date: string;
  weatherCode: number | null;
  highCelsius: number | null;
  lowCelsius: number | null;
  precipitationChancePercent: number | null;
}

export interface WeatherSnapshot {
  locationName: string;
  temperatureCelsius: number | null;
  apparentTemperatureCelsius: number | null;
  humidityPercent: number | null;
  windSpeedKmh: number | null;
  weatherCode: number | null;
  isDay: boolean | null;
  observedAtIso: string;
  timezone: string;
  days: WeatherDay[];
}

const numOrNull = (v: JsonValue | undefined): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null;

const numAt = (arr: JsonValue | undefined, i: number): number | null =>
  Array.isArray(arr) ? numOrNull(arr[i]) : null;

// Open-Meteo `timezone=auto` 的 current.time 是本地墙钟 "YYYY-MM-DDTHH:mm"(无偏移);
//   用 utc_offset_seconds 换算成 UTC ISO,解析失败回退原串
export const localTimeToIso = (local: string, utcOffsetSeconds: number): string => {
  const m: RegExpExecArray | null = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local);
  if (m === null) return local;
  const ms: number = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]))
    - utcOffsetSeconds * 1000;
  return Number.isFinite(ms) ? new Date(ms).toISOString() : local;
};

export const parseOpenMeteoForecast = (body: string, locationName: string): WeatherSnapshot | null => {
  let root: JsonObject | null;
  try {
    root = asObject(JSON.parse(body) as JsonValue);
  } catch (_e) {
    return null;
  }
  if (root === null) return null;
  const current: JsonObject | null = asObject(root['current']);
  const daily: JsonObject | null = asObject(root['daily']);
  if (current === null) return null;
  const offset: number = numOrNull(root['utc_offset_seconds']) ?? 0;
  const timeRaw: JsonValue | undefined = current['time'];
  const days: WeatherDay[] = [];
  if (daily !== null && Array.isArray(daily['time'])) {
    const times: JsonValue[] = daily['time'] as JsonValue[];
    const count: number = Math.min(times.length, WEATHER_FORECAST_DAYS);
    for (let i: number = 0; i < count; i++) {
      const t: JsonValue = times[i];
      if (typeof t !== 'string') continue;
      days.push({
        date: t,
        weatherCode: numAt(daily['weather_code'], i),
        highCelsius: numAt(daily['temperature_2m_max'], i),
        lowCelsius: numAt(daily['temperature_2m_min'], i),
        precipitationChancePercent: numAt(daily['precipitation_probability_max'], i),
      });
    }
  }
  const isDayRaw: number | null = numOrNull(current['is_day']);
  return {
    locationName,
    temperatureCelsius: numOrNull(current['temperature_2m']),
    apparentTemperatureCelsius: numOrNull(current['apparent_temperature']),
    humidityPercent: numOrNull(current['relative_humidity_2m']),
    windSpeedKmh: numOrNull(current['wind_speed_10m']),
    weatherCode: numOrNull(current['weather_code']),
    isDay: isDayRaw === null ? null : isDayRaw !== 0,
    observedAtIso: typeof timeRaw === 'string' ? localTimeToIso(timeRaw, offset) : new Date().toISOString(),
    timezone: typeof root['timezone'] === 'string' ? root['timezone'] as string : '',
    days,
  };
};

export interface GeocodedPlace {
  name: string;
  latitude: number;
  longitude: number;
}

const CJK_RE: RegExp = /[\u3400-\u9fff\uf900-\ufaff]/;

export const containsCjk = (text: string): boolean => CJK_RE.test(text);

// 末尾行政区划后缀(长的在前,只剥一层)
const ADMIN_SUFFIXES: string[] = ['特别行政区', '自治州', '自治区', '市', '区', '县', '省'];

// 「深圳市」→「深圳」;不含 CJK 或剥后不足 2 字符则原样返回
export const stripAdminSuffix = (name: string): string => {
  const t: string = name.trim();
  if (!containsCjk(t)) return t;
  for (const suffix of ADMIN_SUFFIXES) {
    if (t.endsWith(suffix) && t.length - suffix.length >= 2) return t.substring(0, t.length - suffix.length).trim();
  }
  return t;
};

// 查询候选:CJK 带后缀时先查去后缀版(「上海市」原样会命中伊利诺伊州),零命中再回退原文
export const geocodingCandidates = (query: string): string[] => {
  const original: string = query.trim();
  const stripped: string = stripAdminSuffix(original);
  return stripped !== original ? [stripped, original] : [original];
};

export const parseOpenMeteoGeocoding = (body: string, preferChina: boolean = false): GeocodedPlace | null => {
  let root: JsonObject | null;
  try {
    root = asObject(JSON.parse(body) as JsonValue);
  } catch (_e) {
    return null;
  }
  if (root === null || !Array.isArray(root['results']) || (root['results'] as JsonValue[]).length === 0) return null;
  interface Scored { hit: JsonObject; cn: number; population: number; order: number; }
  const scored: Scored[] = [];
  const rawResults: JsonValue[] = root['results'] as JsonValue[];
  for (let i: number = 0; i < rawResults.length; i++) {
    const hit: JsonObject | null = asObject(rawResults[i]);
    if (hit === null) continue;
    if (numOrNull(hit['latitude']) === null || numOrNull(hit['longitude']) === null) continue;
    scored.push({
      hit,
      cn: preferChina && hit['country_code'] === 'CN' ? 1 : 0,
      population: numOrNull(hit['population']) ?? 0,
      order: i,
    });
  }
  if (scored.length === 0) return null;
  scored.sort((a: Scored, b: Scored): number =>
    (b.cn - a.cn) || (b.population - a.population) || (a.order - b.order));
  const best: JsonObject = scored[0].hit;
  const parts: string[] = [];
  for (const key of ['name', 'admin1', 'country']) {
    const v: JsonValue | undefined = best[key];
    if (typeof v === 'string' && v.trim().length > 0 && parts.indexOf(v.trim()) < 0) parts.push(v.trim());
  }
  return { name: parts.join(', '), latitude: best['latitude'] as number, longitude: best['longitude'] as number };
};

// ===== URL 构造 =====

export const buildForecastUrl = (latitude: number, longitude: number): string =>
  `${FORECAST_ENDPOINT}?latitude=${latitude.toFixed(4)}&longitude=${longitude.toFixed(4)}`
  + '&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m'
  + '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max'
  + `&forecast_days=${WEATHER_FORECAST_DAYS}&timezone=auto`;

export const buildGeocodingUrl = (name: string, language: WeatherLanguage): string =>
  `${GEOCODING_ENDPOINT}?name=${encodeURIComponent(name)}&count=10&language=${language}&format=json`;

// ===== 结果 JSON =====

const round1 = (v: number | null): number | null => v === null ? null : Math.round(v * 10) / 10;

export const weatherSuccessJson = (snapshot: WeatherSnapshot, language: WeatherLanguage): string => {
  const forecast: JsonObject[] = snapshot.days.map((d: WeatherDay): JsonObject => ({
    date: d.date,
    condition: weatherCodeText(d.weatherCode, language),
    weather_code: d.weatherCode,
    high_celsius: round1(d.highCelsius),
    low_celsius: round1(d.lowCelsius),
    precipitation_chance_percent: d.precipitationChancePercent,
  }));
  const payload: JsonObject = {
    ok: true,
    tool: WEATHER_TOOL_NAME,
    location: snapshot.locationName,
    temperature_celsius: round1(snapshot.temperatureCelsius),
    apparent_temperature_celsius: round1(snapshot.apparentTemperatureCelsius),
    humidity_percent: snapshot.humidityPercent,
    wind_speed_kmh: round1(snapshot.windSpeedKmh),
    condition: weatherCodeText(snapshot.weatherCode, language),
    weather_code: snapshot.weatherCode,
    is_day: snapshot.isDay,
    observed_at: snapshot.observedAtIso,
    timezone: snapshot.timezone,
    forecast,
    attribution: {
      service: 'Open-Meteo',
      legal_url: 'https://open-meteo.com/',
      legal_text: 'Weather data by Open-Meteo.com (CC BY 4.0).',
    },
  };
  return JSON.stringify(payload);
};

export const weatherFailureJson = (status: string, reason: string): string =>
  JSON.stringify({ ok: false, tool: WEATHER_TOOL_NAME, status, reason });

// ===== 工具 =====

export interface WeatherToolOptions {
  /** condition 文案语言;默认 zh */
  language?: WeatherLanguage;
}

const textPart = (text: string): UIMessagePart => ({ type: 'text', text, metadata: null });

export const WEATHER_REQUEST_TIMEOUT_MS: number = 10000;

const makeAbortError = (): Error => {
  const e: Error = new Error('The operation was aborted.');
  e.name = 'AbortError';
  return e;
};

const isAbortError = (e: Error, signal?: AbortSignalLike): boolean =>
  signal?.aborted === true || (e instanceof Error && e.name === 'AbortError');

// 把 task 与 AbortSignal / 可选超时竞速;底层请求本身不一定可取消,但工具不再被它拖住
const guarded = <T>(task: Promise<T>, signal: AbortSignalLike | undefined, timeoutMs: number): Promise<T> =>
  new Promise<T>((resolve: (value: T) => void, reject: (reason: Error) => void): void => {
    if (signal?.aborted === true) {
      reject(makeAbortError());
      return;
    }
    let settled: boolean = false;
    let timer: ReturnType<typeof setTimeout> | null = null;
    const onAbort = (): void => {
      if (settled) return;
      cleanup();
      reject(makeAbortError());
    };
    const cleanup = (): void => {
      settled = true;
      if (timer !== null) clearTimeout(timer);
      if (signal?.removeEventListener !== undefined) signal.removeEventListener('abort', onAbort);
    };
    if (timeoutMs > 0) {
      timer = setTimeout((): void => {
        if (settled) return;
        cleanup();
        const e: Error = new Error(`Request timed out after ${Math.round(timeoutMs / 1000)}s.`);
        e.name = 'TimeoutError';
        reject(e);
      }, timeoutMs);
    }
    if (signal?.addEventListener !== undefined) signal.addEventListener('abort', onAbort);
    task.then((value: T): void => {
      if (settled) return;
      cleanup();
      resolve(value);
    }, (err: Error): void => {
      if (settled) return;
      cleanup();
      reject(err);
    });
  });

export const createWeatherReadTool = (port: WeatherPort, options?: WeatherToolOptions): AgentTool => {
  const language: WeatherLanguage = options?.language ?? 'zh';
  const fail = (status: string, reason: string): UIMessagePart[] => [textPart(weatherFailureJson(status, reason))];

  return makeAgentTool({
    name: WEATHER_TOOL_NAME,
    description:
      'Read current conditions and a bounded five-day forecast through Open-Meteo. ' +
      'Pass a bare city name in `location` (for example "深圳", "Hangzhou", "Paris"): do NOT append ' +
      'administrative suffixes such as 市/区/县/省 or "City"/"District", and do not combine several places. ' +
      'A named `location` does not need device location permission; alternatively set ' +
      '`use_current_location` to true (asks the user for approximate-location permission in the foreground), ' +
      'or give explicit `latitude` and `longitude`. Provide exactly one of these. ' +
      'Results include Open-Meteo attribution and are read-only.',
    parameters: () => makeInputSchemaObj({
      location: {
        type: 'string',
        description: 'Optional bare city name without 市/区/县 or "City" suffix (e.g. 深圳, Hangzhou), maximum 80 characters.',
      },
      use_current_location: {
        type: 'boolean',
        description: 'Optional. Set true to request the device\'s current location in the foreground.',
      },
      latitude: {
        type: 'number',
        description: 'Optional explicit latitude (-90...90); must be given together with longitude.',
      },
      longitude: {
        type: 'number',
        description: 'Optional explicit longitude (-180...180); must be given together with latitude.',
      },
    }, null),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      const parsed: WeatherRequest | WeatherRequestError = parseWeatherRequest(input);
      if (isRequestError(parsed)) return fail('invalid_arguments', parsed.reason);

      if (signal?.aborted === true) throw makeAbortError();
      let latitude: number = parsed.latitude;
      let longitude: number = parsed.longitude;
      let name: string = '';
      try {
        if (parsed.kind === 'current') {
          const located: DeviceLocationResult = await guarded(port.locateDevice(signal), signal, 0);
          if (!located.ok) {
            return fail(located.status.length > 0 ? located.status : 'location_unavailable',
              located.reason.length > 0 ? located.reason : 'Device location is unavailable.');
          }
          latitude = located.latitude;
          longitude = located.longitude;
          const place: string | null = await guarded(port.reverseGeocode(latitude, longitude), signal, 0);
          name = place !== null && place.trim().length > 0 ? place.trim()
            : (language === 'zh' ? '当前位置' : 'Current location');
        } else if (parsed.kind === 'coordinates') {
          const place: string | null = await guarded(port.reverseGeocode(latitude, longitude), signal, 0);
          name = place !== null && place.trim().length > 0 ? place.trim()
            : `${latitude.toFixed(2)}, ${longitude.toFixed(2)}`;
        } else {
          const preferChina: boolean = containsCjk(parsed.location);
          let place: GeocodedPlace | null = null;
          for (const candidate of geocodingCandidates(parsed.location)) {
            const geo: WeatherHttpResult = await guarded(
              port.fetchText(buildGeocodingUrl(candidate, language), signal), signal, WEATHER_REQUEST_TIMEOUT_MS);
            if (geo.status < 200 || geo.status >= 300) {
              return fail('upstream_error', `Geocoding service returned HTTP ${geo.status}.`);
            }
            place = parseOpenMeteoGeocoding(geo.body, preferChina);
            if (place !== null) break;
          }
          if (place === null) {
            return fail('not_found',
              `No place found for "${parsed.location}". Try a bare city name without 市/区/县 or "City" suffix.`);
          }
          latitude = place.latitude;
          longitude = place.longitude;
          name = place.name.length > 0 ? place.name : parsed.location;
        }
        const resp: WeatherHttpResult = await guarded(
          port.fetchText(buildForecastUrl(latitude, longitude), signal), signal, WEATHER_REQUEST_TIMEOUT_MS);
        if (resp.status < 200 || resp.status >= 300) {
          return fail('upstream_error', `Weather service returned HTTP ${resp.status}.`);
        }
        const snapshot: WeatherSnapshot | null = parseOpenMeteoForecast(resp.body, name);
        if (snapshot === null) return fail('upstream_error', 'Weather service returned an unreadable response.');
        return [textPart(weatherSuccessJson(snapshot, language))];
      } catch (e) {
        if (isAbortError(e as Error, signal)) throw (e instanceof Error ? e : makeAbortError());
        const message: string = e instanceof Error ? e.message : String(e);
        return fail('network_error', message.length > 0 ? message : 'Weather request failed.');
      }
    },
  });
};
