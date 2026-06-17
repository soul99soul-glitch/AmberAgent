/** Source-only Preferences shapes for Phase 0. */

export interface ProviderConfigV1 {
  providers: Array<{
    id: string;
    name: string;
    baseUrl: string;
    models: string[];
    /** Secret reference, not the raw API key. */
    apiKeyRef?: string;
  }>;
}

export interface UiSettingsV1 {
  theme: 'system' | 'light' | 'dark';
  streamingMarkdownBatchMs: number;
}

export interface HarmonyPreferencesV1 {
  provider_config_v1: ProviderConfigV1;
  ui_settings_v1: UiSettingsV1;
}

export const DEFAULT_HARMONY_PREFERENCES_V1: HarmonyPreferencesV1 = {
  provider_config_v1: {
    providers: [],
  },
  ui_settings_v1: {
    theme: 'system',
    streamingMarkdownBatchMs: 80,
  },
};
