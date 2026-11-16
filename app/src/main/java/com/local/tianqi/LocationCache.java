package com.local.tianqi;

import org.json.JSONObject;

/**
 * 城市名 → 经纬度的缓存。
 * GeoAPI 查一次就够，城市名改动后缓存自动失效。
 */
final class LocationCache {

    private static final String KEY = "weather_location_cache";

    private LocationCache() {
    }

    static QWeatherApi.Location read(AppSettings settings, String city) {
        String raw = settings.preferences().getString(KEY, null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            if (!city.equals(json.optString("city"))) {
                return null;
            }
            String latitude = json.optString("lat", "");
            String longitude = json.optString("lon", "");
            if (latitude.isEmpty() || longitude.isEmpty()) {
                return null;
            }
            return new QWeatherApi.Location(latitude, longitude,
                    json.optString("name", city),
                    json.optString("adm1", ""),
                    json.optString("adm2", ""),
                    json.optString("country", ""));
        } catch (Exception e) {
            return null;
        }
    }

    static void write(AppSettings settings, String city, QWeatherApi.Location location) {
        try {
            JSONObject json = new JSONObject();
            json.put("city", city);
            json.put("lat", location.latitude);
            json.put("lon", location.longitude);
            json.put("name", location.name);
            json.put("adm1", location.adm1);
            json.put("adm2", location.adm2);
            json.put("country", location.country);
            json.put("updatedAt", System.currentTimeMillis());
            settings.preferences().edit().putString(KEY, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static void clear(AppSettings settings) {
        settings.preferences().edit().remove(KEY).apply();
    }
}
