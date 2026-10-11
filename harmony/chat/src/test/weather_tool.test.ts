import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createWeatherReadTool, parseWeatherRequest, weatherCodeText, localTimeToIso,
  buildForecastUrl, buildGeocodingUrl, parseOpenMeteoGeocoding, stripAdminSuffix, geocodingCandidates,
} from '../main/ets/chat/weather_tool.ts';
import type { WeatherPort, DeviceLocationResult, WeatherHttpResult } from '../main/ets/chat/weather_tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const FORECAST_BODY = JSON.stringify({
  latitude: 39.9, longitude: 116.4, utc_offset_seconds: 28800, timezone: 'Asia/Shanghai',
  current: {
    time: '2026-10-09T14:30', temperature_2m: 21.46, relative_humidity_2m: 45, apparent_temperature: 20.04,
    is_day: 1, weather_code: 2, wind_speed_10m: 9.4,
  },
  daily: {
    time: ['2026-10-09', '2026-10-10', '2026-10-11', '2026-10-12', '2026-10-13', '2026-10-14'],
    weather_code: [2, 61, 95, 0, 71, 3],
    temperature_2m_max: [22, 19.5, 18, 20, 5, 6],
    temperature_2m_min: [10, 11, 12, 9, -2, 0],
    precipitation_probability_max: [5, 70, 90, 0, 40, 10],
  },
});
const GEO_BODY = JSON.stringify({ results: [
  { name: '北京', admin1: '北京', country: '中国', latitude: 39.9075, longitude: 116.39723 },
] });

interface Rig { port: WeatherPort; urls: string[]; }
const rig = (opts: {
  located?: DeviceLocationResult; place?: string | null;
  responses?: WeatherHttpResult[]; throwOn?: boolean;
} = {}): Rig => {
  const urls: string[] = [];
  const queue: WeatherHttpResult[] = [...(opts.responses ?? [])];
  return {
    urls,
    port: {
      fetchText: async (url) => {
        urls.push(url);
        if (opts.throwOn) throw new Error('offline');
        const next = queue.shift();
        assert.ok(next, `unexpected fetch ${url}`);
        return next;
      },
      locateDevice: async () => opts.located ?? { ok: true, status: '', reason: '', latitude: 31.23, longitude: 121.47 },
      reverseGeocode: async () => (opts.place === undefined ? '上海市' : opts.place),
    },
  };
};

const json = (parts: UIMessagePart[]): Record<string, unknown> => {
  const t = parts[0];
  assert.equal(t.type, 'text');
  return JSON.parse((t as { text: string }).text) as Record<string, unknown>;
};

test('WMO code text zh/en with fallbacks', () => {
  assert.equal(weatherCodeText(0, 'zh'), '晴');
  assert.equal(weatherCodeText(61, 'en'), 'Slight rain');
  assert.equal(weatherCodeText(95, 'zh'), '雷暴');
  assert.equal(weatherCodeText(null, 'zh'), '未知');
  assert.match(weatherCodeText(42, 'zh'), /42/);
});

test('localTimeToIso applies utc offset', () => {
  assert.equal(localTimeToIso('2026-10-09T14:30', 28800), '2026-10-09T06:30:00.000Z');
  assert.equal(localTimeToIso('garbage', 0), 'garbage');
});

test('parseWeatherRequest enforces exactly-one source', () => {
  assert.deepEqual(parseWeatherRequest({ location: ' Paris ' }), { kind: 'named', location: 'Paris', latitude: 0, longitude: 0 });
  assert.equal((parseWeatherRequest({ use_current_location: true }) as { kind: string }).kind, 'current');
  assert.equal((parseWeatherRequest({ latitude: 1, longitude: 2 }) as { kind: string }).kind, 'coordinates');
  for (const bad of [
    {}, { location: 'x', use_current_location: true }, { latitude: 1 }, { latitude: 91, longitude: 0 },
    { location: 'a'.repeat(81) }, { location: 'x', latitude: 1, longitude: 2 }, { use_current_location: 'maybe' },
  ]) {
    assert.ok('reason' in parseWeatherRequest(bad), JSON.stringify(bad));
  }
  assert.equal((parseWeatherRequest({ use_current_location: false, location: 'Rome' }) as { kind: string }).kind, 'named');
});

test('URL builders', () => {
  assert.match(buildForecastUrl(39.9, 116.4), /^https:\/\/api\.open-meteo\.com\/v1\/forecast\?latitude=39\.9000&longitude=116\.4000&current=.*&forecast_days=5&timezone=auto$/);
  assert.equal(buildGeocodingUrl('New York', 'en'),
    'https://geocoding-api.open-meteo.com/v1/search?name=New%20York&count=10&language=en&format=json');
});

test('named location: geocode then forecast, iOS-shaped JSON capped to 5 days', async () => {
  const r = rig({ responses: [{ status: 200, body: GEO_BODY }, { status: 200, body: FORECAST_BODY }] });
  const out = json(await createWeatherReadTool(r.port).execute({ location: '北京' }));
  assert.match(r.urls[0], /name=%E5%8C%97%E4%BA%AC&count=10/);
  assert.equal(r.urls.length, 2);
  assert.match(r.urls[0], /geocoding-api/);
  assert.match(r.urls[1], /latitude=39\.9075&longitude=116\.3972/);
  assert.equal(out.ok, true);
  assert.equal(out.tool, 'weather_read');
  assert.equal(out.location, '北京, 中国'); // dedupes admin1 == name
  assert.equal(out.temperature_celsius, 21.5);
  assert.equal(out.apparent_temperature_celsius, 20);
  assert.equal(out.humidity_percent, 45);
  assert.equal(out.condition, '多云');
  assert.equal(out.observed_at, '2026-10-09T06:30:00.000Z');
  const fc = out.forecast as Record<string, unknown>[];
  assert.equal(fc.length, 5);
  assert.deepEqual(fc[1], {
    date: '2026-10-10', condition: '小雨', weather_code: 61, high_celsius: 19.5, low_celsius: 11,
    precipitation_chance_percent: 70,
  });
  assert.equal((out.attribution as Record<string, string>).service, 'Open-Meteo');
});

test('current location: uses device fix + reverse geocode; English language option', async () => {
  const r = rig({ responses: [{ status: 200, body: FORECAST_BODY }] });
  const out = json(await createWeatherReadTool(r.port, { language: 'en' }).execute({ use_current_location: true }));
  assert.equal(out.ok, true);
  assert.equal(out.location, '上海市');
  assert.equal(out.condition, 'Partly cloudy');
  assert.match(r.urls[0], /latitude=31\.2300&longitude=121\.4700/);
});

test('current location falls back to generic name; explicit coordinates skip device', async () => {
  const r = rig({ place: null, responses: [{ status: 200, body: FORECAST_BODY }, { status: 200, body: FORECAST_BODY }] });
  const tool = createWeatherReadTool(r.port);
  assert.equal(json(await tool.execute({ use_current_location: true })).location, '当前位置');
  assert.equal(json(await tool.execute({ latitude: 10.123, longitude: 20.456 })).location, '10.12, 20.46');
});

test('failure paths carry stable status codes', async () => {
  const denied = rig({ located: { ok: false, status: 'permission_denied', reason: '未授予定位权限', latitude: 0, longitude: 0 } });
  const d = json(await createWeatherReadTool(denied.port).execute({ use_current_location: true }));
  assert.deepEqual([d.ok, d.status, d.reason], [false, 'permission_denied', '未授予定位权限']);
  assert.equal(denied.urls.length, 0);

  const invalid = json(await createWeatherReadTool(rig().port).execute({}));
  assert.equal(invalid.status, 'invalid_arguments');

  const nf = json(await createWeatherReadTool(rig({ responses: [{ status: 200, body: '{}' }] }).port).execute({ location: 'Nowhereville' }));
  assert.equal(nf.status, 'not_found');

  const http = json(await createWeatherReadTool(rig({ responses: [{ status: 503, body: '' }] }).port).execute({ latitude: 1, longitude: 1 }));
  assert.equal(http.status, 'upstream_error');

  const bad = json(await createWeatherReadTool(rig({ responses: [{ status: 200, body: 'not json' }] }).port).execute({ latitude: 1, longitude: 1 }));
  assert.equal(bad.status, 'upstream_error');

  const net = json(await createWeatherReadTool(rig({ throwOn: true }).port).execute({ location: 'x' }));
  assert.equal(net.status, 'network_error');
  assert.equal(net.reason, 'offline');
});

test('declaration: schema and no approval', () => {
  const t = createWeatherReadTool(rig().port);
  assert.equal(t.name, 'weather_read');
  assert.equal(t.needsApproval, false);
  const schema = t.parameters();
  assert.ok(schema);
  assert.deepEqual(Object.keys(schema.properties), ['location', 'use_current_location', 'latitude', 'longitude']);
  assert.equal(schema.required, null);
});

test('stripAdminSuffix / geocodingCandidates', () => {
  assert.equal(stripAdminSuffix('深圳市'), '深圳');
  assert.equal(stripAdminSuffix('朝阳区'), '朝阳');
  assert.equal(stripAdminSuffix('香港特别行政区'), '香港');
  assert.equal(stripAdminSuffix('内蒙古自治区'), '内蒙古');
  assert.equal(stripAdminSuffix('Paris'), 'Paris');
  assert.equal(stripAdminSuffix('市'), '市');
  assert.equal(stripAdminSuffix('北京'), '北京');
  assert.deepEqual(geocodingCandidates('上海市'), ['上海', '上海市']);
  assert.deepEqual(geocodingCandidates('Paris'), ['Paris']);
});

const hit = (o: Record<string, unknown>): Record<string, unknown> =>
  ({ latitude: 1, longitude: 2, ...o });

test('geocoding picks CN + highest population for CJK queries, else highest population', () => {
  const body = JSON.stringify({ results: [
    hit({ name: '上海市', admin1: '伊利诺伊州', country: '美国', country_code: 'US', latitude: 40, longitude: -89 }),
    hit({ name: '上海', admin1: '浙江', country: '中国', country_code: 'CN', latitude: 29.3, longitude: 121 }),
    hit({ name: '上海', admin1: '上海市', country: '中国', country_code: 'CN', population: 24874500, latitude: 31.22, longitude: 121.45 }),
  ] });
  const cn = parseOpenMeteoGeocoding(body, true);
  assert.deepEqual(cn, { name: '上海, 上海市, 中国', latitude: 31.22, longitude: 121.45 });
  // 无 CN 偏好:人口最多者仍是上海;人口全缺失时保持接口返回顺序
  assert.equal(parseOpenMeteoGeocoding(body, false)?.latitude, 31.22);
  const noPop = JSON.stringify({ results: [hit({ name: 'A', latitude: 5 }), hit({ name: 'B', latitude: 6 })] });
  assert.equal(parseOpenMeteoGeocoding(noPop)?.latitude, 5);
  assert.equal(parseOpenMeteoGeocoding(JSON.stringify({ results: [{ name: 'x' }] })), null);
});

test('CJK query with admin suffix searches the stripped name first and falls back to the original', async () => {
  const r = rig({ responses: [{ status: 200, body: GEO_BODY }, { status: 200, body: FORECAST_BODY }] });
  const out = json(await createWeatherReadTool(r.port).execute({ location: '北京市' }));
  assert.equal(out.ok, true);
  assert.equal(r.urls.length, 2);
  assert.match(r.urls[0], /name=%E5%8C%97%E4%BA%AC&/); // 北京(已去掉「市」)

  const fb = rig({ responses: [
    { status: 200, body: '{}' }, { status: 200, body: GEO_BODY }, { status: 200, body: FORECAST_BODY },
  ] });
  assert.equal(json(await createWeatherReadTool(fb.port).execute({ location: '北京市' })).ok, true);
  assert.equal(fb.urls.length, 3);
  assert.match(fb.urls[0], /name=%E5%8C%97%E4%BA%AC&/);
  assert.match(fb.urls[1], /name=%E5%8C%97%E4%BA%AC%E5%B8%82&/);

  const none = rig({ responses: [{ status: 200, body: '{}' }, { status: 200, body: '{}' }] });
  const nf = json(await createWeatherReadTool(none.port).execute({ location: '火星市' }));
  assert.equal(nf.status, 'not_found');
  assert.equal(none.urls.length, 2);
});

test('Latin queries are searched once as-is', async () => {
  const r = rig({ responses: [{ status: 200, body: '{}' }] });
  assert.equal(json(await createWeatherReadTool(r.port).execute({ location: 'Nowhere City' })).status, 'not_found');
  assert.equal(r.urls.length, 1);
});

test('description tells the model to pass a bare city name', () => {
  const t = createWeatherReadTool(rig().port);
  assert.match(t.description, /bare city name/);
  assert.match(t.description, /市\/区\/县/);
});

test('abort: pre-aborted signal and mid-flight abort rethrow AbortError instead of network_error', async () => {
  const pre = { aborted: true };
  const r0 = rig();
  await assert.rejects(createWeatherReadTool(r0.port).execute({ location: 'x' }, pre), { name: 'AbortError' });
  assert.equal(r0.urls.length, 0);

  let fire: () => void = () => {};
  const signal = {
    aborted: false,
    addEventListener: (_t: string, l: () => void) => { fire = l; },
    removeEventListener: () => {},
  };
  const port: WeatherPort = {
    fetchText: () => new Promise<WeatherHttpResult>(() => {}), // never settles
    locateDevice: async () => { throw new Error('unused'); },
    reverseGeocode: async () => null,
  };
  const pending = createWeatherReadTool(port).execute({ location: 'x' }, signal);
  signal.aborted = true;
  fire();
  await assert.rejects(pending, { name: 'AbortError' });

  // 底层 fetch 抛 AbortError 同样透传
  const abortingPort: WeatherPort = {
    ...port,
    fetchText: async () => { const e = new Error('aborted'); e.name = 'AbortError'; throw e; },
  };
  await assert.rejects(createWeatherReadTool(abortingPort).execute({ latitude: 1, longitude: 1 }), { name: 'AbortError' });
});

test('a hanging request times out after 10s as network_error', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] });
  const port: WeatherPort = {
    fetchText: () => new Promise<WeatherHttpResult>(() => {}),
    locateDevice: async () => { throw new Error('unused'); },
    reverseGeocode: async () => null,
  };
  const pending = createWeatherReadTool(port).execute({ location: 'Paris' });
  t.mock.timers.tick(10000);
  const out = json(await pending);
  assert.equal(out.status, 'network_error');
  assert.match(String(out.reason), /timed out/i);
});

test('locateDevice receives the signal and not_foreground status is forwarded', async () => {
  let seen: unknown = null;
  const sig = { aborted: false };
  const port: WeatherPort = {
    fetchText: async () => { throw new Error('unused'); },
    locateDevice: async (s) => {
      seen = s;
      return { ok: false, status: 'not_foreground', reason: 'background', latitude: 0, longitude: 0 };
    },
    reverseGeocode: async () => null,
  };
  const out = json(await createWeatherReadTool(port).execute({ use_current_location: true }, sig));
  assert.equal(seen, sig);
  assert.deepEqual([out.ok, out.status], [false, 'not_foreground']);
});
