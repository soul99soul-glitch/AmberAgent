// Debug-only real weather_read fixture. It contains no credentials and never performs I/O.

export interface ChatVisualWeatherFixture {
  location: string;
  temperature_celsius: number;
  condition: string;
  observed_at: string;
  result: string;
}

export const CHAT_VISUAL_WEATHER_FIXTURE: ChatVisualWeatherFixture = {
  location: '31.23, 121.47',
  temperature_celsius: 22.8,
  condition: '小毛毛雨',
  observed_at: '2026-10-10T08:45:00.000Z',
  result: '{"ok":true,"tool":"weather_read","location":"31.23, 121.47","temperature_celsius":22.8,"apparent_temperature_celsius":24.7,"humidity_percent":77,"wind_speed_kmh":8.2,"condition":"小毛毛雨","weather_code":51,"is_day":true,"observed_at":"2026-10-10T08:45:00.000Z","timezone":"Asia/Shanghai","forecast":[{"date":"2026-10-10","condition":"毛毛雨","weather_code":53,"high_celsius":25.3,"low_celsius":18.7,"precipitation_chance_percent":89},{"date":"2026-10-11","condition":"阴","weather_code":3,"high_celsius":25.3,"low_celsius":19.2,"precipitation_chance_percent":8},{"date":"2026-10-12","condition":"阴","weather_code":3,"high_celsius":23.6,"low_celsius":16.6,"precipitation_chance_percent":2},{"date":"2026-10-13","condition":"大部晴朗","weather_code":1,"high_celsius":26,"low_celsius":18.6,"precipitation_chance_percent":2},{"date":"2026-10-14","condition":"阴","weather_code":3,"high_celsius":23.9,"low_celsius":18,"precipitation_chance_percent":8}],"attribution":{"service":"Open-Meteo","legal_url":"https://open-meteo.com/","legal_text":"Weather data by Open-Meteo.com (CC BY 4.0)."}}',
};
