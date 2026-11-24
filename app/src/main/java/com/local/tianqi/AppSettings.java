package com.local.tianqi;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本地设置。接口地址和字段解析都写死在 {@link ConfigDefaults} / 各 Service 里，
 * 云端只负责下发 Key，同步时直接写进这里的本地字段——所以「本地存的就是当前值」。
 */
public final class AppSettings {

    private static final String PREFS_NAME = "tianqi_prefs";
    static final String KEY_CALENDAR = "calendar_key";
    static final String KEY_WEATHER = "weather_key";
    static final String KEY_WEATHER_HOST = "weather_host";
    static final String KEY_TTS = "tts_key";
    private static final String KEY_TTS_VOICE = "tts_voice";
    private static final String KEY_TTS_SPEED = "tts_speed";
    private static final String KEY_CITY = "city";
    private static final String DEFAULT_CITY = "北京";

    /** 云端取 Key 的地址（留空则用编译进 App 的默认值）。 */
    private static final String KEY_KEYS_URL = "keys_url";
    /** 旧版本手填的地址只迁移一次，之后默认使用 App 内置地址。 */
    static final String KEY_KEYS_URL_MIGRATED = "keys_url_migrated";
    /** 记录哪些本地值是由云端同步写入的（逗号分隔的 pref key）。 */
    private static final String KEY_SYNCED = "synced_from_cloud";

    /** 是否使用设备定位的坐标。 */
    private static final String KEY_USE_LOCATION = "use_location";
    private static final String KEY_LOCATION_LAT = "location_lat";
    private static final String KEY_LOCATION_LON = "location_lon";
    private static final String KEY_LOCATION_NAME = "location_name";
    private static final String KEY_LOCATION_PLACE = "location_place";
    private static final String KEY_LOCATION_COUNTRY = "location_country";

    private final Context context;
    private final SharedPreferences preferences;
    private KeysStore keysStore;

    public AppSettings(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        migrateLegacyKeysUrl();
    }

    /**
     * 以前地址要手填，升级后默认用内置地址：把旧地址清掉一次，Key 都保留。
     * 清过之后用户再手填的地址仍然有效。
     */
    private void migrateLegacyKeysUrl() {
        if (preferences.getBoolean(KEY_KEYS_URL_MIGRATED, false)) {
            return;
        }
        String saved = preferences.getString(KEY_KEYS_URL, "").trim();
        String builtIn = KeysStore.normalizeUrl(ConfigDefaults.KEYS_URL);
        if (!saved.isEmpty() && !KeysStore.normalizeUrl(saved).equals(builtIn)) {
            preferences.edit().remove(KEY_KEYS_URL).apply();
        }
        preferences.edit().putBoolean(KEY_KEYS_URL_MIGRATED, true).apply();
    }

    public SharedPreferences preferences() {
        return preferences;
    }

    /** 云端 Key 拉取器（懒加载，避免互相构造时递归）。 */
    public KeysStore keysStore() {
        if (keysStore == null) {
            keysStore = new KeysStore(context, this);
        }
        return keysStore;
    }

    /** 页面销毁时释放拉取器：它常驻一个线程，不放会随 Activity 重建累积。 */
    public void releaseKeysStore() {
        if (keysStore != null) {
            keysStore.release();
            keysStore = null;
        }
    }

    // ---------- 接口 Key（云端同步写进来的就是这几个） ----------

    public String getCalendarKey() {
        return preferences.getString(KEY_CALENDAR, "").trim();
    }

    public String getWeatherKey() {
        return preferences.getString(KEY_WEATHER, "").trim();
    }

    public String getWeatherHost() {
        return preferences.getString(KEY_WEATHER_HOST, "").trim();
    }

    public String getTtsKey() {
        return preferences.getString(KEY_TTS, "").trim();
    }

    // ---------- 播报音色与语速（纯本地偏好） ----------

    public String getMimoVoice() {
        String value = preferences.getString(KEY_TTS_VOICE, "").trim();
        return ConfigDefaults.normalizeVoice(value);
    }

    public void setMimoVoice(String voice) {
        preferences.edit().putString(KEY_TTS_VOICE,
                voice == null ? "" : voice.trim()).apply();
        markLocalEdited(KEY_TTS_VOICE);
    }

    public String getMimoSpeed() {
        String value = preferences.getString(KEY_TTS_SPEED, "").trim();
        return ConfigDefaults.normalizeSpeed(value);
    }

    public void setMimoSpeed(String speed) {
        preferences.edit().putString(KEY_TTS_SPEED,
                speed == null ? "" : speed.trim()).apply();
        markLocalEdited(KEY_TTS_SPEED);
    }

    /** 某档语速对应的播报风格指令（写死在 App 里）。 */
    public String getStyleInstruction(String speed) {
        return ConfigDefaults.style(speed);
    }

    public String getCity() {
        String city = preferences.getString(KEY_CITY, DEFAULT_CITY).trim();
        return city.isEmpty() ? DEFAULT_CITY : city;
    }

    // ---------- 云端 Key 地址与令牌 ----------

    public String getKeysUrl() {
        return preferences.getString(KEY_KEYS_URL, "").trim();
    }

    public void setKeysUrl(String url) {
        preferences.edit().putString(KEY_KEYS_URL, url == null ? "" : url.trim()).apply();
    }

    // ---------- 云端 Key 落地 ----------

    /**
     * 把云端下发的 Key 写进本地输入框对应的位置。
     * 云端有值就覆盖；云端没值但上次是同步写进来的，就清掉；用户自己填的保留。
     */
    public void applyCloudKeys(CloudKeys keys) {
        if (keys == null) {
            return;
        }
        Set<String> synced = syncedKeys();
        boolean dirty = false;
        dirty |= writeFromCloud(KEY_CALENDAR, keys.calendarKey, synced);
        dirty |= writeFromCloud(KEY_WEATHER_HOST, keys.weatherHost, synced);
        dirty |= writeFromCloud(KEY_WEATHER, keys.weatherKey, synced);
        dirty |= writeFromCloud(KEY_TTS, keys.ttsKey, synced);
        if (dirty) {
            preferences.edit().putString(KEY_SYNCED, TextUtils.join(",", synced)).apply();
        }
    }

    private boolean writeFromCloud(String key, String cloud, Set<String> synced) {
        String remote = cloud == null ? "" : cloud.trim();
        boolean wasSynced = synced.contains(key);
        if (!remote.isEmpty()) {
            String local = preferences.getString(key, "").trim();
            if (remote.equals(local)) {
                synced.add(key);
                return !wasSynced;
            }
            preferences.edit().putString(key, remote).apply();
            synced.add(key);
            return true;
        }
        if (wasSynced) {
            preferences.edit().remove(key).apply();
            synced.remove(key);
            return true;
        }
        return false;
    }

    /** 清掉云端同步写入的 Key，用户自己填的保留。 */
    public void clearCloudKeys() {
        Set<String> synced = syncedKeys();
        if (synced.isEmpty()) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (String key : synced) {
            editor.remove(key);
        }
        editor.remove(KEY_SYNCED);
        editor.apply();
    }

    private Set<String> syncedKeys() {
        String raw = preferences.getString(KEY_SYNCED, "");
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String key = item.trim();
            if (!key.isEmpty()) {
                result.add(key);
            }
        }
        return result;
    }

    /** 这个值现在来自云端还是本地（设置页展示用）。 */
    public boolean isFromCloud(String localKey) {
        return syncedKeys().contains(localKey);
    }

    private void markLocalEdited(String key) {
        Set<String> synced = syncedKeys();
        if (synced.remove(key)) {
            preferences.edit().putString(KEY_SYNCED, TextUtils.join(",", synced)).apply();
        }
    }

    /** 设置页展示：每个 Key 现在是从云端同步来的，还是本地自己填的。 */
    public String effectiveSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("万年历 Key：").append(describe(KEY_CALENDAR)).append('\n');
        builder.append("天气 Host：").append(describe(KEY_WEATHER_HOST)).append('\n');
        builder.append("天气 Key：").append(describe(KEY_WEATHER)).append('\n');
        builder.append("语音 Key：").append(describe(KEY_TTS));
        return builder.toString();
    }

    private String describe(String localKey) {
        String value = preferences.getString(localKey, "").trim();
        if (value.isEmpty()) {
            return "未配置";
        }
        return (isFromCloud(localKey) ? "云端 " : "本地 ") + mask(value);
    }

    /** Key 只露头尾，避免截图泄露。 */
    private static String mask(String value) {
        if (value.length() <= 8) {
            return value;
        }
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }

    // ---------- 定位 ----------

    public boolean isLocationMode() {
        return preferences.getBoolean(KEY_USE_LOCATION, true);
    }

    public void setLocationMode(boolean enabled) {
        preferences.edit().putBoolean(KEY_USE_LOCATION, enabled).apply();
    }

    public boolean hasLocation() {
        return !getLocationLatitude().isEmpty() && !getLocationLongitude().isEmpty();
    }

    public String getLocationLatitude() {
        return preferences.getString(KEY_LOCATION_LAT, "").trim();
    }

    public String getLocationLongitude() {
        return preferences.getString(KEY_LOCATION_LON, "").trim();
    }

    public String getLocationName() {
        return preferences.getString(KEY_LOCATION_NAME, "").trim();
    }

    public String getLocationPlace() {
        return preferences.getString(KEY_LOCATION_PLACE, "").trim();
    }

    public String getLocationCountry() {
        return preferences.getString(KEY_LOCATION_COUNTRY, "").trim();
    }

    /** 记录一次定位结果，之后天气直接按这些坐标查。 */
    public void saveDeviceLocation(QWeatherApi.Location location) {
        preferences.edit()
                .putBoolean(KEY_USE_LOCATION, true)
                .putString(KEY_LOCATION_LAT, location.latitude)
                .putString(KEY_LOCATION_LON, location.longitude)
                .putString(KEY_LOCATION_NAME, location.searchKey())
                .putString(KEY_LOCATION_PLACE, location.display())
                .putString(KEY_LOCATION_COUNTRY, location.country)
                .apply();
    }

    /** 只更新坐标，保留已有的地名。 */
    public void updateLocationCoordinates(String latitude, String longitude) {
        preferences.edit()
                .putString(KEY_LOCATION_LAT, latitude)
                .putString(KEY_LOCATION_LON, longitude)
                .apply();
    }

    /** 改回按城市名查询。 */
    public void clearDeviceLocation() {
        preferences.edit()
                .putBoolean(KEY_USE_LOCATION, false)
                .remove(KEY_LOCATION_LAT)
                .remove(KEY_LOCATION_LON)
                .remove(KEY_LOCATION_NAME)
                .remove(KEY_LOCATION_PLACE)
                .remove(KEY_LOCATION_COUNTRY)
                .apply();
    }

    /** 保存设置页里的 Key 与城市。 */
    public void save(String calendarKey, String weatherKey, String weatherHost, String city) {
        preferences.edit()
                .putString(KEY_CALENDAR, calendarKey == null ? "" : calendarKey.trim())
                .putString(KEY_WEATHER, weatherKey == null ? "" : weatherKey.trim())
                .putString(KEY_WEATHER_HOST, weatherHost == null ? "" : weatherHost.trim())
                .putString(KEY_CITY, city == null || city.trim().isEmpty() ? DEFAULT_CITY : city.trim())
                .apply();
        markLocalEdited(KEY_CALENDAR);
        markLocalEdited(KEY_WEATHER);
        markLocalEdited(KEY_WEATHER_HOST);
    }

    /** 保存语音合成 Key。 */
    public void saveTtsKey(String key) {
        preferences.edit().putString(KEY_TTS, key == null ? "" : key.trim()).apply();
        markLocalEdited(KEY_TTS);
    }
}
