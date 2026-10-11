// WeatherPort — weather_read 的 entry 实现
//
// HTTP:getAppContainer().httpClient(Open-Meteo forecast / geocoding 均为免 key GET)。
// 设备定位:前台请求 ohos.permission.APPROXIMATELY_LOCATION(仅近似定位;不单独申请
//   LOCATION——系统要求精确定位必须与近似同时申请),再 geoLocationManager.getCurrentLocation
//   (LOW_POWER,带 15s 超时)。天气只需城市级精度。
// 逆地理编码:geoLocationManager.getAddressesFromLocation(需设备有 Geocoder 服务;不可用
//   或超时 → null,域层回退"当前位置"/坐标)。
//
// 前置条件(工程配置,本文件不改):module.json5 requestPermissions 需声明
//   ohos.permission.APPROXIMATELY_LOCATION(目前仍是注释状态,且 debug 签名 profile 的 ACL
//   需含该权限,否则安装报 9568289)。未声明时 use_current_location 返回
//   status=permission_denied;按城市名/坐标查询不受影响(不需要任何权限)。
//
// 权限流程:先 checkAccessTokenSync(已授权直接用)→ requestPermissionsFromUser;若用户此前已拒绝
//   (dialogShownResults 全 false,系统不再弹首次授权框)→ requestPermissionOnSetting 二次引导
//   (与 DeviceSchedulePort 同口径);仍被拒 → permission_denied + 去设置里开启的提示。
// 后台:AppStorage 'appBackgrounded' 为 true 时不发起任何权限弹窗/定位,返回 not_foreground。
// context 复用 AppStorage 'abilityContext'(UIAbilityContext)。

import { geoLocationManager } from '@kit.LocationKit';
import { abilityAccessCtrl, bundleManager } from '@kit.AbilityKit';
import type { Context, Permissions, PermissionRequestResult } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { BusinessError } from '@kit.BasicServicesKit';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { WeatherPort, WeatherHttpResult, DeviceLocationResult } from '@amber/chat-domain';
import { getAppContainer } from '../di/AppContainer.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'WeatherPort';
const LOCATION_TIMEOUT_MS: number = 15000;
const GEOCODE_TIMEOUT_MS: number = 6000;
const PERMISSION_APPROX: Permissions = 'ohos.permission.APPROXIMATELY_LOCATION';
const ERR_LOCATION_SWITCH_OFF: number = 3301100;
const ERR_LOCATION_SERVICE_UNAVAILABLE: number = 3301000;
const PERMISSION_SETTINGS_HINT: string =
  'Approximate location is not allowed. Ask the user to enable location for Amber in Settings > Apps > Amber > Permissions, then retry; or call weather_read with a city name instead.';
const NOT_FOREGROUND_REASON: string =
  'Amber is in the background, so the location permission dialog cannot be shown. Ask the user to bring Amber to the foreground and retry, or pass a city name.';

// 'zh' | 'en':Geocoder 只认这两类;BCP-47(zh-Hans-CN)等归一
const normalizeGeocoderLocale = (raw: string): string => raw.toLowerCase().startsWith('zh') ? 'zh' : 'en';

const isApproxGranted = (): boolean => {
  try {
    const info: bundleManager.BundleInfo = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    return atManager.checkAccessTokenSync(info.appInfo.accessTokenId, PERMISSION_APPROX)
      === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } catch (e) {
    hilog.debug(DOMAIN, TAG, 'checkAccessTokenSync failed: %{public}s', (e as BusinessError).message);
    return false;
  }
};

class HttpResult implements WeatherHttpResult {
  status: number;
  body: string;

  constructor(status: number, body: string) {
    this.status = status;
    this.body = body;
  }
}

class LocationOutcome implements DeviceLocationResult {
  ok: boolean;
  status: string;
  reason: string;
  latitude: number;
  longitude: number;

  constructor(ok: boolean, status: string, reason: string, latitude: number, longitude: number) {
    this.ok = ok;
    this.status = status;
    this.reason = reason;
    this.latitude = latitude;
    this.longitude = longitude;
  }
}

const failure = (status: string, reason: string): LocationOutcome =>
  new LocationOutcome(false, status, reason, 0, 0);

function withTimeout<T>(task: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise<T>((resolve: (value: T) => void, reject: (reason: Error) => void): void => {
    const timer: number = setTimeout((): void => { reject(new Error(message)); }, ms);
    task.then((value: T): void => {
      clearTimeout(timer);
      resolve(value);
    }).catch((e: Error): void => {
      clearTimeout(timer);
      reject(e);
    });
  });
}

class EntryWeatherPort implements WeatherPort {
  private readonly geocoderLocale: string;

  constructor(geocoderLocale: string) {
    this.geocoderLocale = normalizeGeocoderLocale(geocoderLocale);
  }

  async fetchText(url: string, signal?: AbortSignalLike): Promise<WeatherHttpResult> {
    const headers: Record<string, string> = {};
    headers['Accept'] = 'application/json';
    const resp = await getAppContainer().httpClient.fetch({
      url: url,
      method: 'GET',
      headers: headers,
    }, { signal: signal });
    return new HttpResult(resp.status, resp.body);
  }

  async locateDevice(signal?: AbortSignalLike): Promise<DeviceLocationResult> {
    if (signal !== undefined && signal.aborted) return failure('location_unavailable', 'The request was cancelled.');
    if (AppStorage.get<boolean>('appBackgrounded') === true) {
      return failure('not_foreground', NOT_FOREGROUND_REASON);
    }
    if (!isApproxGranted()) {
      const context: Context | undefined = AppStorage.get<Context>('abilityContext');
      if (context === undefined) {
        return failure('location_unavailable', 'The app UI is not ready, so location permission cannot be requested.');
      }
      const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
      const perms: Permissions[] = [PERMISSION_APPROX];
      let granted: boolean = false;
      let dialogShown: boolean = true;
      try {
        const result: PermissionRequestResult = await atManager.requestPermissionsFromUser(context, perms);
        granted = result.authResults.length > 0 && result.authResults[0] === 0;
        const shown: boolean[] | undefined = result.dialogShownResults;
        dialogShown = shown === undefined || shown.some((v: boolean): boolean => v);
      } catch (e) {
        const err: BusinessError = e as BusinessError;
        hilog.warn(DOMAIN, TAG, 'location permission request failed: %{public}d %{public}s', err.code, err.message);
        return failure('permission_denied', `Location permission is unavailable: ${err.message}`);
      }
      if (!granted && !dialogShown) {
        // 用户此前已拒绝:系统不再弹首次授权框 → 权限设置弹框二次引导(API 12)
        try {
          const statuses: abilityAccessCtrl.GrantStatus[] = await atManager.requestPermissionOnSetting(context, perms);
          granted = statuses.length > 0 && statuses[0] === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
        } catch (e) {
          hilog.warn(DOMAIN, TAG, 'requestPermissionOnSetting failed: %{public}s', (e as BusinessError).message);
        }
      }
      if (!granted) return failure('permission_denied', PERMISSION_SETTINGS_HINT);
    }
    try {
      if (!geoLocationManager.isLocationEnabled()) {
        return failure('location_disabled', 'The device location switch is off.');
      }
      const request: geoLocationManager.CurrentLocationRequest = {
        priority: geoLocationManager.LocationRequestPriority.LOW_POWER,
        timeoutMs: LOCATION_TIMEOUT_MS,
      };
      const location: geoLocationManager.Location = await withTimeout<geoLocationManager.Location>(
        geoLocationManager.getCurrentLocation(request), LOCATION_TIMEOUT_MS + 2000, 'Timed out waiting for a location fix.');
      return new LocationOutcome(true, '', '', location.latitude, location.longitude);
    } catch (e) {
      const err: BusinessError = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'getCurrentLocation failed: %{public}d %{public}s', err.code, err.message);
      if (err.code === ERR_LOCATION_SWITCH_OFF) {
        return failure('location_disabled', 'The device location switch is off.');
      }
      if (err.code === ERR_LOCATION_SERVICE_UNAVAILABLE) {
        return failure('location_unavailable', 'The location service is unavailable.');
      }
      return failure('location_unavailable', `Could not determine the current location: ${err.message}`);
    }
  }

  async reverseGeocode(latitude: number, longitude: number): Promise<string | null> {
    try {
      if (!geoLocationManager.isGeocoderAvailable()) return null;
      const request: geoLocationManager.ReverseGeoCodeRequest = {
        latitude: latitude,
        longitude: longitude,
        maxItems: 1,
        locale: this.geocoderLocale,
      };
      const addresses: geoLocationManager.GeoAddress[] = await withTimeout<geoLocationManager.GeoAddress[]>(
        geoLocationManager.getAddressesFromLocation(request), GEOCODE_TIMEOUT_MS, 'Reverse geocoding timed out.');
      if (addresses.length === 0) return null;
      const a: geoLocationManager.GeoAddress = addresses[0];
      const city: string = a.locality ?? a.subAdministrativeArea ?? a.administrativeArea ?? '';
      if (city.length === 0) return a.placeName ?? null;
      const country: string = a.countryName ?? '';
      return country.length > 0 && country !== city ? `${city}, ${country}` : city;
    } catch (e) {
      hilog.info(DOMAIN, TAG, 'reverse geocode unavailable: %{public}s', (e as Error).message);
      return null;
    }
  }
}

// geocoderLocale:'zh' | 'en' | BCP-47 皆可(构造时归一为 'zh' / 'en')
export const createEntryWeatherPort = (geocoderLocale: string): WeatherPort => new EntryWeatherPort(geocoderLocale);
