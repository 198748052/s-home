package com.local.tianqi;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {

    private static final String PREFS_NAME = "tianqi_prefs";
    private static final String KEY_CALENDAR = "calendar_key";
    private static final String KEY_WEATHER = "weather_key";
    private static final String KEY_WEATHER_HOST = "weather_host";
    private static final String KEY_CITY = "city";
    private static final String DEFAULT_CITY = "北京";
    /** 小米 MiMo 语音合成（TTS）的 API Key。 */
    private static final String KEY_TTS = "tts_key";
    /** 播报音色，取值见 MimoTtsClient.VOICES。 */
    private static final String KEY_TTS_VOICE = "tts_voice";

    /** 是否使用设备定位的坐标。开启后天气按坐标查，不再走城市名搜索。 */
    private static final String KEY_USE_LOCATION = "use_location";
    private static final String KEY_LOCATION_LAT = "location_lat";
    private static final String KEY_LOCATION_LON = "location_lon";
    /** 定位解析出的城市名，用于和输入框比对，判断用户是否改了城市。 */
    private static final String KEY_LOCATION_NAME = "location_name";
    /** 定位位置的展示名，例如「开封市龙亭」。 */
    private static final String KEY_LOCATION_PLACE = "location_place";
    /** 定位位置的国家或地区，例如「中国」。 */
    private static final String KEY_LOCATION_COUNTRY = "location_country";

    private final SharedPreferences preferences;

    public AppSettings(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public SharedPreferences preferences() {
        return preferences;
    }

    /** 聚合数据（juhe.cn）万年历接口的 Key。 */
    public String getCalendarKey() {
        return preferences.getString(KEY_CALENDAR, "").trim();
    }

    /** 和风天气（QWeather）的 API Key 或 JWT。 */
    public String getWeatherKey() {
        return preferences.getString(KEY_WEATHER, "").trim();
    }

    /** 和风天气控制台分配的专属 API Host，例如 abc1234xyz.re.qweatherapi.com。 */
    public String getWeatherHost() {
        return preferences.getString(KEY_WEATHER_HOST, "").trim();
    }

    public String getCity() {
        String city = preferences.getString(KEY_CITY, DEFAULT_CITY).trim();
        return city.isEmpty() ? DEFAULT_CITY : city;
    }

    /** 小米 MiMo 语音合成的 API Key，留空则只用系统自带语音播报。 */
    public String getMimoKey() {
        return preferences.getString(KEY_TTS, "").trim();
    }

    public void setMimoKey(String key) {
        preferences.edit().putString(KEY_TTS, key == null ? "" : key.trim()).apply();
    }

    /** 播报音色，默认冰糖。 */
    public String getMimoVoice() {
        String voice = preferences.getString(KEY_TTS_VOICE, "").trim();
        return voice.isEmpty() ? MimoTtsClient.DEFAULT_VOICE : voice;
    }

    public void setMimoVoice(String voice) {
        preferences.edit().putString(KEY_TTS_VOICE,
                MimoTtsClient.normalizeVoice(voice)).apply();
    }

    /** 是否使用设备定位。默认开启，失败时回退到上次定位坐标。 */
    public boolean isLocationMode() {
        return preferences.getBoolean(KEY_USE_LOCATION, true);
    }

    public void setLocationMode(boolean enabled) {
        preferences.edit().putBoolean(KEY_USE_LOCATION, enabled).apply();
    }

    /** 是否已经存过定位坐标（上次定位）。 */
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

    /** 只更新坐标，保留已有的地名（定位成功但没反查到新地名时用）。 */
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

    public void save(String calendarKey, String weatherKey, String weatherHost, String city) {
        preferences.edit()
                .putString(KEY_CALENDAR, calendarKey == null ? "" : calendarKey.trim())
                .putString(KEY_WEATHER, weatherKey == null ? "" : weatherKey.trim())
                .putString(KEY_WEATHER_HOST, weatherHost == null ? "" : weatherHost.trim())
                .putString(KEY_CITY, city == null || city.trim().isEmpty() ? DEFAULT_CITY : city.trim())
                .apply();
    }
}
