package com.local.tianqi;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 天气数据源：和风天气（QWeather）天气预报 v1 接口，外加本地缓存与离线兜底。
 * <p>
 * 实时天气 {@code /weather/v1/current/{lat}/{lon}}、
 * 逐小时 {@code /weather/v1/hourly/{lat}/{lon}}、
 * 每日预报 {@code /weather/v1/daily/{lat}/{lon}}。
 * 城市名先经 GeoAPI {@code /geo/v2/city/lookup} 换成经纬度，也可以直接在设置里填「经度,纬度」。
 * 接口与字段说明见 docs/qweather-docs。万年历仍走聚合数据接口，见 {@link LunarService}。
 */
public final class WeatherService {

    public interface Callback {
        void onSnapshot(WeatherSnapshot snapshot);
    }

    private static final String CACHE_NOW = "weather_now_cache";
    private static final String CACHE_HOURLY = "weather_hourly_cache";
    private static final String CACHE_DAILY = "weather_daily_cache";
    /** 上次成功更新每日/逐小时预报的时间。 */
    private static final String LAST_FORECAST_AT = "forecast_fetched_at";

    /** 和风天气每日预报最多 10 天。 */
    private static final int DAILY_COUNT = 10;
    private static final int HOURLY_COUNT = 12;
    /** 请求 24 小时，界面上只展示接下来的 12 小时。 */
    private static final int HOURLY_REQUEST = 24;
    /** 每日与逐小时预报的更新间隔：一小时。实时天气则每次打开都刷新。 */
    private static final long FORECAST_INTERVAL_MS = 60L * 60L * 1000L;
    /** 打开 app 时最多等这么久的定位；拿不到就回退上次位置。 */
    private static final long LOCATION_TIMEOUT_MS = 4000L;
    /** 已经有上次位置时，等待时间可以短一些。 */
    private static final long FALLBACK_LOCATION_TIMEOUT_MS = 1500L;
    /** 位置移动超过这个经纬度差（约 5 公里）才重新反查地名。 */
    private static final double LOCATION_MOVE_THRESHOLD = 0.05;

    private final Context context;
    private final AppSettings settings;
    private final LocalWeatherRepository local = new LocalWeatherRepository();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public WeatherService(Context context) {
        this.context = context.getApplicationContext();
        settings = new AppSettings(context);
    }

    public WeatherSnapshot loadSnapshot() {
        return loadSnapshot(null);
    }

    private WeatherSnapshot loadSnapshot(String statusOverride) {
        long now = System.currentTimeMillis();
        CachedNow cachedNow = readNowCache();
        CachedHourly cachedHourly = readHourlyCache();
        CachedDaily cachedDaily = readDailyCache();

        List<WeatherDay> daily = resolveDaily(cachedDaily.days, cachedDaily.updatedAt, now);
        List<HourlyWeather> hourly = resolveHourly(cachedHourly.hours, cachedHourly.updatedAt, now);
        boolean staleDaily = cachedDaily.updatedAt > 0
                && daysAgo(cachedDaily.updatedAt, now) >= 2;
        if (staleDaily && !isSameDay(cachedHourly.updatedAt, now)) {
            hourly = Collections.emptyList();
        }

        NowWeather nowWeather = cachedNow.now;
        if (nowWeather == null || !isSameDay(nowWeather.updatedAt, now)) {
            nowWeather = syntheticNow(hourly, daily);
        }

        String status = statusOverride != null
                ? statusOverride
                : buildStatus(cachedDaily.updatedAt, cachedHourly.updatedAt, now, hourly.isEmpty());
        status = withSimulatedNote(status, countSimulatedDays(daily), countSimulatedHours(hourly));
        return new WeatherSnapshot(daily, hourly, nowWeather,
                cachedDaily.updatedAt, cachedHourly.updatedAt,
                cachedNow.updatedAt, staleDaily, status);
    }

    public void refresh(final Callback callback) {
        refresh(callback, false);
    }

    /** 下拉刷新：不管一小时的节流，实时天气和预报都重新拉一次。 */
    public void refreshNow(final Callback callback) {
        refresh(callback, true);
    }

    private void refresh(final Callback callback, final boolean force) {
        if (executor.isShutdown()) {
            // 页面已经销毁：别再排队，直接回当前缓存
            callback.onSnapshot(loadSnapshot());
            return;
        }
        final String key = settings.getWeatherKey();
        final String base = QWeatherApi.baseUrl(settings.getWeatherHost());
        if (key.isEmpty() || base.isEmpty()) {
            callback.onSnapshot(loadSnapshot("未配置天气 Key，当前为模拟数据（灰显）"));
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            callback.onSnapshot(loadSnapshot());
            return;
        }

        final Map<String, String> headers = QWeatherApi.authHeaders(key);
        final String city = settings.getCity();
        final boolean forecastDue = force || forecastDue();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean nowOk = false;
                boolean hourlyOk = false;
                boolean dailyOk = false;
                String failure = null;
                long now = System.currentTimeMillis();

                // 先尝试系统定位，成功就更新坐标；失败则沿用上次定位
                final boolean locationFailed;
                if (settings.isLocationMode()) {
                    locationFailed = !updateDeviceLocation(base, headers);
                } else {
                    locationFailed = false;
                }

                QWeatherApi.Location location = null;
                try {
                    location = resolveLocation(base, headers, city);
                } catch (Exception e) {
                    failure = "定位失败：" + message(e);
                }

                if (location != null) {
                    // 实时天气：每次打开 app 都刷新
                    try {
                        JSONObject root = fetch(weatherUrl(base, ConfigDefaults.WEATHER_CURRENT_PATH,
                                location, 0, 0), headers);
                        NowWeather value = parseNow(root, now);
                        writeNowCache(value);
                        nowOk = true;
                    } catch (Exception ignored) {
                    }

                    // 每日与逐小时预报：距上次更新满一小时才请求
                    if (forecastDue) {
                        try {
                            JSONObject root = fetch(weatherUrl(base, ConfigDefaults.WEATHER_HOURLY_PATH,
                                    location, HOURLY_REQUEST, 0), headers);
                            List<HourlyWeather> value = parseHourly(root);
                            if (!value.isEmpty()) {
                                writeHourlyCache(value, now);
                                hourlyOk = true;
                            }
                        } catch (Exception ignored) {
                        }

                        try {
                            JSONObject root = fetch(weatherUrl(base, ConfigDefaults.WEATHER_DAILY_PATH,
                                    location, 0, DAILY_COUNT), headers);
                            List<WeatherDay> value = parseDaily(root);
                            if (!value.isEmpty()) {
                                writeDailyCache(value, now);
                                dailyOk = true;
                            }
                        } catch (Exception ignored) {
                        }

                        if (hourlyOk || dailyOk) {
                            markForecastFetched(now);
                        }
                    }
                }

                final boolean anyOk = nowOk || hourlyOk || dailyOk;
                final boolean partial = forecastDue && anyOk && !(hourlyOk && dailyOk);
                final String locationLabel = location == null ? "" : location.display();
                final String failureMessage = failure;
                final String suffix = locationFailed && settings.hasLocation()
                        ? "（定位失败，沿用上次位置）" : "";
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing.set(false);
                        if (!anyOk) {
                            callback.onSnapshot(loadSnapshot(failureMessage != null
                                    ? failureMessage : "离线，显示缓存天气"));
                        } else if (partial) {
                            callback.onSnapshot(loadSnapshot(
                                    "部分数据已更新 · " + locationLabel + suffix));
                        } else {
                            callback.onSnapshot(loadSnapshot("已更新 · " + locationLabel + suffix));
                        }
                    }
                });
            }
        });
    }

    /** 每日与逐小时预报是否到了更新时间。 */
    boolean forecastDue() {
        long last = settings.preferences().getLong(LAST_FORECAST_AT, 0L);
        return System.currentTimeMillis() - last >= FORECAST_INTERVAL_MS;
    }

    private void markForecastFetched(long timestamp) {
        settings.preferences().edit().putLong(LAST_FORECAST_AT, timestamp).apply();
    }

    /**
     * 取一次系统定位并写回坐标。
     * 位置没怎么动就只更新坐标，省掉一次反查地名请求。
     *
     * @return 是否拿到了一次新的定位（false 表示要回退到上次位置或城市名）
     */
    private boolean updateDeviceLocation(String base, Map<String, String> headers) {
        // 已经有上次位置时不用等太久，反正能回退
        long timeout = settings.hasLocation() ? FALLBACK_LOCATION_TIMEOUT_MS : LOCATION_TIMEOUT_MS;
        DeviceLocation.Fix fix = DeviceLocation.await(context, timeout);
        if (fix == null) {
            return false;
        }
        String latitude = QWeatherApi.format(fix.latitude);
        String longitude = QWeatherApi.format(fix.longitude);
        boolean moved = !settings.hasLocation()
                || Math.abs(parseDouble(latitude) - parseDouble(settings.getLocationLatitude()))
                > LOCATION_MOVE_THRESHOLD
                || Math.abs(parseDouble(longitude) - parseDouble(settings.getLocationLongitude()))
                > LOCATION_MOVE_THRESHOLD;
        if (!moved) {
            settings.updateLocationCoordinates(latitude, longitude);
            return true;
        }
        QWeatherApi.Location place = null;
        try {
            place = QWeatherApi.reverseGeocode(base, headers, longitude, latitude);
        } catch (Exception ignored) {
        }
        if (place != null) {
            settings.saveDeviceLocation(place);
        } else {
            settings.updateLocationCoordinates(latitude, longitude);
        }
        return true;
    }

    /** 页面销毁时调用：停掉常驻线程，丢掉还没回到主线程的回调。 */
    public void release() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    public void clearCache() {
        settings.preferences().edit()
                .remove(CACHE_NOW)
                .remove(CACHE_HOURLY)
                .remove(CACHE_DAILY)
                .remove(LAST_FORECAST_AT)
                .apply();
        LocationCache.clear(settings);
    }

    public String cacheSummary() {
        CachedNow now = readNowCache();
        CachedHourly hourly = readHourlyCache();
        CachedDaily daily = readDailyCache();
        if (daily.updatedAt <= 0 && hourly.updatedAt <= 0 && now.updatedAt <= 0) {
            return "天气缓存：暂无";
        }
        return "天气缓存：" + DAILY_COUNT + " 天 " + daily.days.size() + " 条，逐小时 "
                + hourly.hours.size() + " 条，更新于 " + dateTimeOf(daily.updatedAt);
    }

    private static String dateTimeOf(long timestamp) {
        if (timestamp <= 0) {
            return "无";
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE));
    }

    /** 定位优先，其次直接填的坐标，再次城市名缓存，最后才走 GeoAPI 名称搜索（限中国）。 */
    QWeatherApi.Location resolveLocation(String base, Map<String, String> headers,
                                         String city) throws IOException {
        if (settings.isLocationMode() && settings.hasLocation()) {
            // 定位的坐标就是权威位置，不再拿名字去搜，避免「河南省龙亭」被匹配到韩国「河南市」
            return new QWeatherApi.Location(
                    settings.getLocationLatitude(),
                    settings.getLocationLongitude(),
                    settings.getLocationPlace(),
                    "",
                    "",
                    settings.getLocationCountry());
        }
        QWeatherApi.Location direct = QWeatherApi.parseCoordinates(city);
        if (direct != null) {
            return direct;
        }
        QWeatherApi.Location cached = LocationCache.read(settings, city);
        if (cached != null && isChina(cached)) {
            return cached;
        }
        QWeatherApi.Location found = QWeatherApi.searchCity(base, headers, city);
        LocationCache.write(settings, city, found);
        return found;
    }

    /** 城市名查询只认中国；旧版本可能缓存过国外同名城市，这里跳过。 */
    static boolean isChina(QWeatherApi.Location location) {
        return location.country.isEmpty() || "中国".equals(location.country);
    }

    private JSONObject fetch(String url, Map<String, String> headers) throws IOException {
        JSONObject root = HttpJson.get(url, headers);
        String code = root.optString("code", "");
        if (!code.isEmpty() && !"200".equals(code)) {
            throw new IOException("接口返回错误（code=" + code + "）");
        }
        return root;
    }

    NowWeather parseNow(JSONObject root, long updatedAt) throws IOException {
        JSONObject condition = root.optJSONObject("condition");
        if (condition == null) {
            throw new IOException("实况字段缺失");
        }
        JSONObject wind = root.optJSONObject("wind");
        String compass = "";
        int scale = 0;
        if (wind != null) {
            JSONObject direction = wind.optJSONObject("direction");
            if (direction != null) {
                compass = compassName(direction.optString("compass"));
            }
            scale = wind.optInt("scale", 0);
        }
        return new NowWeather(
                condition.optString("text"),
                round(metric(root, "temperature")),
                round(metric(root, "feelsLike")),
                (int) Math.round(root.optDouble("humidity", 0) * 100),
                compass,
                scale > 0 ? scale + "级" : "",
                updatedAt);
    }

    List<HourlyWeather> parseHourly(JSONObject root) {
        JSONArray array = root.optJSONArray("hours");
        List<HourlyWeather> hours = new ArrayList<>();
        if (array == null) {
            return hours;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String time = item.optString("forecastTime");
            JSONObject condition = item.optJSONObject("condition");
            String code = condition == null ? "" : condition.optString("code");
            String text = condition == null ? "" : condition.optString("text");
            if (text.isEmpty()) {
                text = QWeatherIcons.text(code);
            }
            hours.add(new HourlyWeather(dateKeyOf(time), hourOf(time),
                    WeatherCondition.fromText(text, code), code,
                    round(metric(item, "temperature"))));
        }
        return hours;
    }

    List<WeatherDay> parseDaily(JSONObject root) {
        JSONArray array = root.optJSONArray("days");
        List<WeatherDay> days = new ArrayList<>();
        if (array == null) {
            return days;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String dateKey = dateKeyOf(item.optString("forecastStartTime"));
            Calendar calendar = parseDate(dateKey);
            if (calendar == null) {
                continue;
            }
            int calendarDow = calendar.get(Calendar.DAY_OF_WEEK);
            int isoDow = calendarDow == Calendar.SUNDAY ? 7 : calendarDow - 1;

            WeatherCondition condition = WeatherCondition.CLOUDY;
            String code = "";
            int rainChance = 0;
            JSONObject daytime = item.optJSONObject("daytime");
            if (daytime != null) {
                JSONObject conditionNode = daytime.optJSONObject("condition");
                if (conditionNode != null) {
                    code = conditionNode.optString("code");
                    String text = conditionNode.optString("text");
                    if (text.isEmpty()) {
                        text = QWeatherIcons.text(code);
                    }
                    condition = WeatherCondition.fromText(text, code);
                }
                JSONObject precipitation = daytime.optJSONObject("precipitation");
                if (precipitation != null) {
                    rainChance = (int) Math.round(
                            precipitation.optDouble("probability", 0) * 100);
                }
            }
            days.add(new WeatherDay(
                    dateKey,
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH),
                    isoDow,
                    condition,
                    code,
                    round(metric(item, "temperatureMax")),
                    round(metric(item, "temperatureMin")),
                    Math.max(0, Math.min(100, rainChance))));
        }
        return days;
    }

    /** 和风天气的数值字段都是 {@code {"value": 31.71, "unit": "°C"}} 结构。 */
    private static double metric(JSONObject root, String name) {
        JSONObject node = root.optJSONObject(name);
        return node == null ? 0 : node.optDouble("value", 0);
    }

    /** 按模板拼接口地址：{lat} {lon} {hours} {days}。 */
    String weatherUrl(String base, String template, QWeatherApi.Location location,
                      int hours, int days) {
        String value = template == null ? "" : template.trim();
        value = value.replace("{lat}", location.latitude)
                .replace("{lon}", location.longitude)
                .replace("{hours}", String.valueOf(hours))
                .replace("{days}", String.valueOf(days));
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        return base + value;
    }

    /** 取日期键 YYYY-MM-DD：ISO 字符串截前 10 位，纯数字时间戳先换算。 */
    private static String dateKeyOf(String time) {
        Calendar calendar = timeAsCalendar(time);
        if (calendar != null) {
            return dateKey(calendar);
        }
        String value = time == null ? "" : time.trim();
        return value.length() >= 10 ? value.substring(0, 10) : "";
    }

    /** forecastTime 形如 2024-05-31T03:00+08:00（localTime=true），也兼容时间戳。 */
    private static int hourOf(String time) {
        Calendar calendar = timeAsCalendar(time);
        if (calendar != null) {
            return calendar.get(Calendar.HOUR_OF_DAY);
        }
        if (time == null || time.length() < 13) {
            return 0;
        }
        return parseInt(time.substring(11, 13));
    }

    /** 纯数字时间戳（10 位秒 / 13 位毫秒）转成 Calendar；不是时间戳返回 null。 */
    private static Calendar timeAsCalendar(String time) {
        String value = time == null ? "" : time.trim();
        if (!value.matches("\\d{10}|\\d{13}")) {
            return null;
        }
        long millis;
        try {
            millis = value.length() == 13
                    ? Long.parseLong(value) : Long.parseLong(value) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar;
    }

    /** 风向方位代码转中文，取值见 docs/qweather-docs/weather/08-wind-guide.md。 */
    private static String compassName(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.US);
        switch (value) {
            case "n":
                return "北风";
            case "nne":
                return "东北偏北风";
            case "ne":
                return "东北风";
            case "ene":
                return "东北偏东风";
            case "e":
                return "东风";
            case "ese":
                return "东南偏东风";
            case "se":
                return "东南风";
            case "sse":
                return "东南偏南风";
            case "s":
                return "南风";
            case "ssw":
                return "西南偏南风";
            case "sw":
                return "西南风";
            case "wsw":
                return "西南偏西风";
            case "w":
                return "西风";
            case "wnw":
                return "西北偏西风";
            case "nw":
                return "西北风";
            case "nnw":
                return "西北偏北风";
            case "vrb":
                return "风向不定";
            case "none":
            default:
                return "";
        }
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isEmpty() ? e.toString() : value;
    }

    private List<WeatherDay> resolveDaily(List<WeatherDay> cached, long updatedAt, long now) {
        if (cached != null && !cached.isEmpty()) {
            Calendar today = Calendar.getInstance();
            String todayKey = dateKey(today);
            int index = indexOfDate(cached, todayKey);
            if (index >= 0) {
                List<WeatherDay> result = new ArrayList<>();
                for (int i = index; i < cached.size(); i++) {
                    result.add(cached.get(i));
                }
                if (result.size() < DAILY_COUNT) {
                    for (WeatherDay day : local.getForecast(DAILY_COUNT + 2)) {
                        if (result.size() >= DAILY_COUNT) {
                            break;
                        }
                        if (indexOfDate(result, day.dateKey) < 0) {
                            result.add(day);
                        }
                    }
                }
                return result;
            }
        }
        return local.getForecast(DAILY_COUNT);
    }

    private List<HourlyWeather> resolveHourly(List<HourlyWeather> cached,
                                              long updatedAt, long now) {
        if (cached == null || cached.isEmpty()) {
            return local.getHourlyForecast(HOURLY_COUNT);
        }
        if (!isSameDay(updatedAt, now)) {
            return Collections.emptyList();
        }

        Calendar current = Calendar.getInstance();
        current.set(Calendar.MINUTE, 0);
        current.set(Calendar.SECOND, 0);
        current.set(Calendar.MILLISECOND, 0);

        List<HourlyWeather> result = new ArrayList<>();
        for (HourlyWeather hour : cached) {
            Calendar target = parseDate(hour.dateKey);
            if (target == null) {
                continue;
            }
            target.set(Calendar.HOUR_OF_DAY, hour.hour);
            target.set(Calendar.MINUTE, 0);
            target.set(Calendar.SECOND, 0);
            target.set(Calendar.MILLISECOND, 0);
            if (!target.before(current)) {
                result.add(hour);
            }
            if (result.size() >= HOURLY_COUNT) {
                break;
            }
        }
        return result.isEmpty() ? local.getHourlyForecast(HOURLY_COUNT) : result;
    }

    private NowWeather syntheticNow(List<HourlyWeather> hourly, List<WeatherDay> daily) {
        if (hourly != null && !hourly.isEmpty()) {
            HourlyWeather current = hourly.get(0);
            return new NowWeather(current.condition.label, current.temp, current.temp,
                    0, "", "", 0L);
        }
        if (daily != null && !daily.isEmpty()) {
            WeatherDay today = daily.get(0);
            return new NowWeather(today.condition.label, today.high, today.high,
                    0, "", "", 0L);
        }
        return null;
    }

    /**
     * 状态文案。除了「缓存多旧」之外，还要说明有多少天/多少小时是本地模拟补出来的——
     * 否则真实缓存不够 10 天时，用户会把补出来的那几天当成真预报。
     */
    private String buildStatus(long dailyUpdatedAt, long hourlyUpdatedAt,
                               long now, boolean hourlyHidden) {
        if (dailyUpdatedAt <= 0) {
            return "本地模拟数据（灰显）";
        }
        int days = daysAgo(dailyUpdatedAt, now);
        String status;
        if (days <= 0) {
            status = "已缓存 " + timeOf(dailyUpdatedAt);
        } else if (days == 1) {
            status = "离线 · 显示 1 天前缓存";
        } else {
            status = "离线 · 显示 " + days + " 天前缓存";
        }
        if (hourlyHidden) {
            status += "，逐小时已隐藏";
        }
        return status;
    }

    /**
     * 补一句「有多少天/小时是模拟数据」；文案里已经说明是本地数据的就不再重复。
     * 措辞尽量短：首页只留一行，太长会挤掉别的信息。
     */
    static String withSimulatedNote(String status, int simulatedDays, int simulatedHours) {
        String text = status == null ? "" : status;
        if (text.contains("模拟") || text.contains("本地数据")) {
            return text;
        }
        if (simulatedDays > 0) {
            text += "，含 " + simulatedDays + " 天模拟（灰显）";
        }
        if (simulatedHours > 0) {
            text += "，逐小时为模拟（灰显）";
        }
        return text;
    }

    /** 列表里有几天是本地模拟补出来的。 */
    static int countSimulatedDays(List<WeatherDay> days) {
        int count = 0;
        for (WeatherDay day : days) {
            if (day.simulated) {
                count++;
            }
        }
        return count;
    }

    /** 列表里有几小时是本地模拟补出来的。 */
    static int countSimulatedHours(List<HourlyWeather> hours) {
        int count = 0;
        for (HourlyWeather hour : hours) {
            if (hour.simulated) {
                count++;
            }
        }
        return count;
    }

    private CachedNow readNowCache() {
        String raw = settings.preferences().getString(CACHE_NOW, null);
        if (raw == null) {
            return new CachedNow(null, 0L);
        }
        try {
            JSONObject json = new JSONObject(raw);
            NowWeather now = new NowWeather(
                    json.optString("text"),
                    json.optInt("temperature"),
                    json.optInt("feelsLike"),
                    json.optInt("humidity"),
                    json.optString("windDirection"),
                    json.optString("windScale"),
                    json.optLong("updatedAt"));
            return new CachedNow(now, now.updatedAt);
        } catch (Exception e) {
            return new CachedNow(null, 0L);
        }
    }

    private void writeNowCache(NowWeather now) {
        try {
            JSONObject json = new JSONObject();
            json.put("text", now.text);
            json.put("temperature", now.temperature);
            json.put("feelsLike", now.feelsLike);
            json.put("humidity", now.humidity);
            json.put("windDirection", now.windDirection);
            json.put("windScale", now.windScale);
            json.put("updatedAt", now.updatedAt);
            settings.preferences().edit().putString(CACHE_NOW, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private CachedHourly readHourlyCache() {
        String raw = settings.preferences().getString(CACHE_HOURLY, null);
        if (raw == null) {
            return new CachedHourly(Collections.<HourlyWeather>emptyList(), 0L);
        }
        try {
            JSONObject json = new JSONObject(raw);
            long updatedAt = json.optLong("updatedAt");
            JSONArray array = json.optJSONArray("hours");
            List<HourlyWeather> hours = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    hours.add(new HourlyWeather(
                            item.optString("date"),
                            item.optInt("hour"),
                            WeatherCondition.fromText(item.optString("text"),
                                    item.optString("code")),
                            item.optString("code"),
                            item.optInt("temp")));
                }
            }
            return new CachedHourly(hours, updatedAt);
        } catch (Exception e) {
            return new CachedHourly(Collections.<HourlyWeather>emptyList(), 0L);
        }
    }

    private void writeHourlyCache(List<HourlyWeather> hours, long updatedAt) {
        try {
            JSONArray array = new JSONArray();
            for (HourlyWeather hour : hours) {
                JSONObject item = new JSONObject();
                item.put("date", hour.dateKey);
                item.put("hour", hour.hour);
                item.put("text", hour.condition.label);
                item.put("code", hour.code);
                item.put("temp", hour.temp);
                array.put(item);
            }
            JSONObject json = new JSONObject();
            json.put("updatedAt", updatedAt);
            json.put("hours", array);
            settings.preferences().edit().putString(CACHE_HOURLY, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private CachedDaily readDailyCache() {
        String raw = settings.preferences().getString(CACHE_DAILY, null);
        if (raw == null) {
            return new CachedDaily(Collections.<WeatherDay>emptyList(), 0L);
        }
        try {
            JSONObject json = new JSONObject(raw);
            long updatedAt = json.optLong("updatedAt");
            JSONArray array = json.optJSONArray("days");
            List<WeatherDay> days = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    days.add(new WeatherDay(
                            item.optString("date"),
                            item.optInt("year"),
                            item.optInt("month"),
                            item.optInt("day"),
                            item.optInt("dayOfWeek"),
                            WeatherCondition.fromText(item.optString("text"),
                                    item.optString("code")),
                            item.optString("code"),
                            item.optInt("high"),
                            item.optInt("low"),
                            item.optInt("rainChance")));
                }
            }
            return new CachedDaily(days, updatedAt);
        } catch (Exception e) {
            return new CachedDaily(Collections.<WeatherDay>emptyList(), 0L);
        }
    }

    private void writeDailyCache(List<WeatherDay> days, long updatedAt) {
        try {
            JSONArray array = new JSONArray();
            for (WeatherDay day : days) {
                JSONObject item = new JSONObject();
                item.put("date", day.dateKey);
                item.put("year", day.year);
                item.put("month", day.month);
                item.put("day", day.dayOfMonth);
                item.put("dayOfWeek", day.dayOfWeek);
                item.put("text", day.condition.label);
                item.put("code", day.code);
                item.put("high", day.high);
                item.put("low", day.low);
                item.put("rainChance", day.rainChance);
                array.put(item);
            }
            JSONObject json = new JSONObject();
            json.put("updatedAt", updatedAt);
            json.put("days", array);
            settings.preferences().edit().putString(CACHE_DAILY, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static int indexOfDate(List<WeatherDay> days, String dateKey) {
        if (dateKey == null) {
            return -1;
        }
        for (int i = 0; i < days.size(); i++) {
            if (dateKey.equals(days.get(i).dateKey)) {
                return i;
            }
        }
        return -1;
    }

    private static Calendar parseDate(String date) {
        if (date == null) {
            return null;
        }
        String[] parts = date.split("-");
        if (parts.length != 3) {
            return null;
        }
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.clear();
            calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1,
                    Integer.parseInt(parts[2]));
            return calendar;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String dateKey(Calendar calendar) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static boolean isSameDay(long timestamp, long now) {
        if (timestamp <= 0) {
            return false;
        }
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(timestamp);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now);
        return dateKey(a).equals(dateKey(b));
    }

    private static int daysAgo(long timestamp, long now) {
        if (timestamp <= 0) {
            return 0;
        }
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(timestamp);
        a.set(Calendar.HOUR_OF_DAY, 0);
        a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0);
        a.set(Calendar.MILLISECOND, 0);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(now);
        b.set(Calendar.HOUR_OF_DAY, 0);
        b.set(Calendar.MINUTE, 0);
        b.set(Calendar.SECOND, 0);
        b.set(Calendar.MILLISECOND, 0);
        long diff = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) (diff / (24L * 60L * 60L * 1000L));
    }

    private static String timeOf(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.US, "%02d:%02d",
                calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE));
    }

    private static int parseInt(String value) {
        try {
            return (int) Math.round(Double.parseDouble(value == null ? "" : value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final class CachedNow {
        final NowWeather now;
        final long updatedAt;

        CachedNow(NowWeather now, long updatedAt) {
            this.now = now;
            this.updatedAt = updatedAt;
        }
    }

    private static final class CachedHourly {
        final List<HourlyWeather> hours;
        final long updatedAt;

        CachedHourly(List<HourlyWeather> hours, long updatedAt) {
            this.hours = hours;
            this.updatedAt = updatedAt;
        }
    }

    private static final class CachedDaily {
        final List<WeatherDay> days;
        final long updatedAt;

        CachedDaily(List<WeatherDay> days, long updatedAt) {
            this.days = days;
            this.updatedAt = updatedAt;
        }
    }
}
