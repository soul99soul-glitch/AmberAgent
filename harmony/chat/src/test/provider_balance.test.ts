import { test } from 'node:test';
import assert from 'node:assert/strict';
import { providerBalanceRequestIdentity } from '../main/ets/chat/provider_balance.ts';
import {
  makeBalanceOption, makeProviderModel, makeProviderSettingOpenAIVariant,
} from '../main/ets/chat/provider_settings.ts';

const makeProvider = () => makeProviderSettingOpenAIVariant({
  id: 'balance-provider',
  apiKey: 'test-key',
  baseUrl: 'https://example.invalid/v1',
  balanceOption: makeBalanceOption({ enabled: true, apiPath: '/credits', resultPath: 'data.balance' }),
});

test('余额请求身份：更换同一 Provider 的 Key、地址、查询或解析路径都不能复用余额', () => {
  const provider = makeProvider();
  const identity = providerBalanceRequestIdentity(provider);
  const changedKey = makeProvider();
  changedKey.apiKey = 'different-test-key';
  const changedBaseUrl = makeProvider();
  changedBaseUrl.baseUrl = 'https://other.invalid/v1';
  const changedApiPath = makeProvider();
  changedApiPath.balanceOption.apiPath = '/balance';
  const changedResultPath = makeProvider();
  changedResultPath.balanceOption.resultPath = 'data.remaining';
  const otherProvider = makeProvider();
  otherProvider.id = 'other-provider';
  for (const changed of [changedKey, changedBaseUrl, changedApiPath, changedResultPath, otherProvider]) {
    assert.notEqual(providerBalanceRequestIdentity(changed), identity);
  }
});

test('余额请求身份：名称与模型列表更新仍复用相同请求，尾部斜线按实际 URL 归一', () => {
  const provider = makeProvider();
  const identity = providerBalanceRequestIdentity(provider);
  provider.name = 'Renamed provider';
  provider.models = [makeProviderModel({ modelId: 'model' })];
  provider.baseUrl += '///';
  assert.equal(providerBalanceRequestIdentity(provider), identity);
});

test('余额请求身份：字段中的分隔符不导致两组凭据与路径碰撞', () => {
  const left = makeProvider();
  left.apiKey = 'a|b';
  left.balanceOption.resultPath = 'c';
  const right = makeProvider();
  right.apiKey = 'a';
  right.balanceOption.resultPath = 'b|c';
  assert.notEqual(providerBalanceRequestIdentity(left), providerBalanceRequestIdentity(right));
});
