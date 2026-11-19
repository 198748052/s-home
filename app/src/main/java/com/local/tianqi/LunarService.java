package com.local.tianqi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LunarService {

    public interface Callback {
        void onLunar(LunarInfo info, String status);
    }

    private static final long REFRESH_INTERVAL_MS = 2L * 60L * 60L * 1000L;
    private static final String CACHE_KEY = "lunar_cache";

    private final AppSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public LunarService(Context context) {
        settings = new AppSettings(context);
    }

    public LunarInfo loadCachedOrLocal() {
        Calendar today = Calendar.getInstance();
        LunarInfo cached = readCache();
        if (cached != null && cached.dateKey.equals(dateKey(today))) {
            return cached;
        }
        if (cached != null) {
            return local(today, cached.updatedAt);
        }
        return local(today, 0L);
    }

    public void refreshIfNeeded(Callback callback) {
        if (executor.isShutdown()) {
            // 页面已经销毁：别再排队
            callback.onLunar(loadCachedOrLocal(), "已销毁");
            return;
        }
        Calendar now = Calendar.getInstance();
        LunarInfo cached = readCache();
        boolean sameDay = cached != null && cached.dateKey.equals(dateKey(now));
        boolean due = cached == null || !sameDay
                || now.getTimeInMillis() - cached.updatedAt >= REFRESH_INTERVAL_MS;

        if (!due) {
            callback.onLunar(cached, "两小时内已更新");
            return;
        }

        String key = settings.getCalendarKey();
        if (key.isEmpty()) {
            callback.onLunar(fallback(cached, now), "未配置万年历 Key");
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            callback.onLunar(fallback(cached, now), "更新中");
            return;
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                LunarInfo info;
                String status;
                try {
                    info = fetch(now, key);
                    writeCache(info);
                    status = "已更新";
                } catch (Exception e) {
                    info = fallback(cached, now);
                    status = "离线，已推算今日农历";
                }
                final LunarInfo result = info;
                final String resultStatus = status;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing.set(false);
                        callback.onLunar(result, resultStatus);
                    }
                });
            }
        });
    }

    /** 页面销毁时调用：停掉常驻线程，丢掉还没回到主线程的回调。 */
    public void release() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    public void clearCache() {
        settings.preferences().edit().remove(CACHE_KEY).apply();
    }

    public String cacheSummary() {
        LunarInfo cached = readCache();
        if (cached == null) {
            return "农历缓存：暂无";
        }
        return "农历缓存：" + cached.dateKey + " " + cached.monthName + cached.dayName
                + (cached.fromNetwork ? "" : "（推算）");
    }

    private LunarInfo fallback(LunarInfo cached, Calendar now) {
        if (cached != null && cached.dateKey.equals(dateKey(now))) {
            return cached;
        }
        long base = cached == null ? 0L : cached.updatedAt;
        return local(now, base);
    }

    private LunarInfo local(Calendar calendar, long updatedAt) {
        try {
            LunarCalendar.LunarDate date = LunarCalendar.fromSolar(
                    calendar.get(Calendar.YEAR),
                    calendar.get(Calendar.MONTH) + 1,
                    calendar.get(Calendar.DAY_OF_MONTH));
            return new LunarInfo(dateKey(calendar), date.monthName(), date.dayName(), date.day,
                    "", "", updatedAt, false);
        } catch (IllegalArgumentException e) {
            return new LunarInfo(dateKey(calendar), "", "--", 0, "", "", updatedAt, false);
        }
    }

    private LunarInfo fetch(Calendar calendar, String key) throws Exception {
        // 接口地址写死在 ConfigDefaults 里，云端只下发 Key
        String url = ConfigDefaults.CALENDAR_URL
                + "?date=" + HttpJson.encode(apiDate(calendar))
                + "&key=" + HttpJson.encode(key);
        JSONObject root = HttpJson.get(url);
        int errorCode = root.optInt("error_code", -1);
        if (errorCode != 0) {
            throw new IllegalStateException(root.optString("reason", "接口返回错误"));
        }
        JSONObject data = root.getJSONObject("result").getJSONObject("data");
        String[] lunar = splitLunar(data.optString("lunar"));
        if (lunar == null) {
            throw new IllegalStateException("农历字段无法解析");
        }
        int dayNumber = parseDayNumber(lunar[1]);
        if (dayNumber <= 0) {
            throw new IllegalStateException("农历日期无法解析");
        }
        return new LunarInfo(dateKey(calendar), lunar[0], lunar[1], dayNumber,
                data.optString("lunarYear"), data.optString("holiday"),
                System.currentTimeMillis(), true);
    }

    private LunarInfo readCache() {
        SharedPreferences preferences = settings.preferences();
        String raw = preferences.getString(CACHE_KEY, null);
        if (raw == null) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(raw);
            return new LunarInfo(
                    json.optString("date"),
                    json.optString("month"),
                    json.optString("dayName"),
                    json.optInt("dayNumber"),
                    json.optString("lunarYear"),
                    json.optString("holiday"),
                    json.optLong("updatedAt"),
                    json.optBoolean("fromNetwork"));
        } catch (Exception e) {
            return null;
        }
    }

    private void writeCache(LunarInfo info) {
        try {
            JSONObject json = new JSONObject();
            json.put("date", info.dateKey);
            json.put("month", info.monthName);
            json.put("dayName", info.dayName);
            json.put("dayNumber", info.dayNumber);
            json.put("lunarYear", info.lunarYear);
            json.put("holiday", info.holiday);
            json.put("updatedAt", info.updatedAt);
            json.put("fromNetwork", info.fromNetwork);
            settings.preferences().edit().putString(CACHE_KEY, json.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static String[] splitLunar(String lunar) {
        if (lunar == null) {
            return null;
        }
        String value = lunar.trim();
        int monthEnd = value.indexOf('月');
        if (monthEnd <= 0 || monthEnd >= value.length() - 1) {
            return null;
        }
        String month = value.substring(0, monthEnd + 1);
        String day = value.substring(monthEnd + 1);
        return new String[]{normalizeMonth(month), day};
    }

    private static String normalizeMonth(String month) {
        boolean leap = month.startsWith("闰");
        String base = leap ? month.substring(1) : month;
        if ("一月".equals(base)) {
            base = "正月";
        } else if ("十一月".equals(base)) {
            base = "冬月";
        } else if ("十二月".equals(base)) {
            base = "腊月";
        }
        return (leap ? "闰" : "") + base;
    }

    private static int parseDayNumber(String dayName) {
        for (int day = 1; day <= 30; day++) {
            if (LunarCalendar.dayName(day).equals(dayName)) {
                return day;
            }
        }
        return 0;
    }

    static String dateKey(Calendar calendar) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String apiDate(Calendar calendar) {
        return String.format(Locale.US, "%d-%d-%d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }
}
