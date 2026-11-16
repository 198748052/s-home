package com.local.tianqi;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 刷新节奏：实时天气每次打开都更新，每日与逐小时预报一小时一次。
 * 这里只验证节流判断，不真的发网络请求。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ForecastRefreshTest {

    /** 与 WeatherService 里的 LAST_FORECAST_AT 保持一致。 */
    private static final String LAST_FORECAST_AT = "forecast_fetched_at";

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @Test
    public void firstLaunchNeedsForecast() {
        assertTrue(new WeatherService(context).forecastDue());
    }

    @Test
    public void withinOneHourSkipsForecast() {
        preferences.edit()
                .putLong(LAST_FORECAST_AT, System.currentTimeMillis() - 30L * 60L * 1000L)
                .commit();
        assertFalse(new WeatherService(context).forecastDue());
    }

    @Test
    public void afterOneHourForecastIsDueAgain() {
        preferences.edit()
                .putLong(LAST_FORECAST_AT, System.currentTimeMillis() - 61L * 60L * 1000L)
                .commit();
        assertTrue(new WeatherService(context).forecastDue());
    }

    @Test
    public void clearingCacheForcesForecastRefresh() {
        preferences.edit()
                .putLong(LAST_FORECAST_AT, System.currentTimeMillis())
                .commit();
        WeatherService service = new WeatherService(context);
        assertFalse(service.forecastDue());
        service.clearCache();
        assertTrue(service.forecastDue());
    }

    @Test
    public void refreshWithoutCredentialsFallsBackToLocalData() {
        final WeatherSnapshot[] holder = new WeatherSnapshot[1];
        new WeatherService(context).refresh(new WeatherService.Callback() {
            @Override
            public void onSnapshot(WeatherSnapshot snapshot) {
                holder[0] = snapshot;
            }
        });
        assertNotNull(holder[0]);
        assertTrue(holder[0].status.contains("未配置"));
    }
}
