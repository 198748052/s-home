package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Calendar;
import java.util.Locale;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OfflineFallbackTest {

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void lunarUsesPreviousDayCacheToComputeToday() throws Exception {
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_MONTH, -1);

        JSONObject cache = new JSONObject();
        cache.put("date", dateKey(yesterday));
        cache.put("month", "九月");
        cache.put("dayName", "初一");
        cache.put("dayNumber", 1);
        cache.put("lunarYear", "");
        cache.put("holiday", "");
        cache.put("updatedAt", yesterday.getTimeInMillis());
        cache.put("fromNetwork", true);
        preferences.edit().putString("lunar_cache", cache.toString()).commit();

        LunarInfo info = new LunarService(context).loadCachedOrLocal();
        Calendar today = Calendar.getInstance();
        LunarCalendar.LunarDate expected = LunarCalendar.fromSolar(
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH) + 1,
                today.get(Calendar.DAY_OF_MONTH));

        assertEquals(dateKey(today), info.dateKey);
        assertEquals(expected.monthName(), info.monthName);
        assertEquals(expected.dayName(), info.dayName);
        assertEquals(expected.day, info.dayNumber);
    }

    @Test
    public void staleDailyCacheShowsTodayAndHidesHourly() throws Exception {
        Calendar threeDaysAgo = Calendar.getInstance();
        threeDaysAgo.add(Calendar.DAY_OF_MONTH, -3);

        JSONArray days = new JSONArray();
        Calendar cursor = (Calendar) threeDaysAgo.clone();
        for (int i = 0; i < 10; i++) {
            JSONObject day = new JSONObject();
            day.put("date", dateKey(cursor));
            day.put("year", cursor.get(Calendar.YEAR));
            day.put("month", cursor.get(Calendar.MONTH) + 1);
            day.put("day", cursor.get(Calendar.DAY_OF_MONTH));
            day.put("dayOfWeek", isoDayOfWeek(cursor));
            day.put("text", "晴");
            day.put("code", "0");
            day.put("high", 10 + i);
            day.put("low", i);
            day.put("rainChance", 10);
            days.put(day);
            cursor.add(Calendar.DAY_OF_MONTH, 1);
        }

        JSONObject cache = new JSONObject();
        cache.put("updatedAt", threeDaysAgo.getTimeInMillis());
        cache.put("days", days);
        preferences.edit().putString("weather_daily_cache", cache.toString()).commit();

        WeatherSnapshot snapshot = new WeatherService(context).loadSnapshot();
        Calendar today = Calendar.getInstance();

        assertEquals(10, snapshot.daily.size());
        assertEquals(dateKey(today), snapshot.daily.get(0).dateKey);
        assertTrue("连续多天离线时应隐藏逐小时", snapshot.hourly.isEmpty());
        assertTrue(snapshot.staleDaily);
        assertNotNull("离线时仍需要有当前天气展示", snapshot.now);
        assertTrue(snapshot.status.contains("3 天前"));
    }

    @Test
    public void noCacheStillShowsLocalForecast() {
        WeatherSnapshot snapshot = new WeatherService(context).loadSnapshot();

        assertEquals(10, snapshot.daily.size());
        assertEquals(12, snapshot.hourly.size());
        assertNotNull(snapshot.now);
        assertTrue(snapshot.status.contains("本地模拟数据"));
    }

    private static int isoDayOfWeek(Calendar calendar) {
        int dow = calendar.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }

    private static String dateKey(Calendar calendar) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }
}
