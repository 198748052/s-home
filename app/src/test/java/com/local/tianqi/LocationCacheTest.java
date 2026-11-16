package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 城市 → 经纬度缓存：同一个城市复用，换城市后失效。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocationCacheTest {

    private AppSettings settings;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        settings = new AppSettings(context);
    }

    @Test
    public void sameCityHitsCache() {
        LocationCache.write(settings, "北京", new QWeatherApi.Location("39.92", "116.41", "北京"));

        QWeatherApi.Location cached = LocationCache.read(settings, "北京");
        assertEquals("39.92", cached.latitude);
        assertEquals("116.41", cached.longitude);
        assertEquals("北京", cached.name);
    }

    @Test
    public void differentCityMissesCache() {
        LocationCache.write(settings, "北京", new QWeatherApi.Location("39.92", "116.41", "北京"));
        assertNull(LocationCache.read(settings, "上海"));
    }

    @Test
    public void clearRemovesCache() {
        LocationCache.write(settings, "北京", new QWeatherApi.Location("39.92", "116.41", "北京"));
        LocationCache.clear(settings);
        assertNull(LocationCache.read(settings, "北京"));
    }

    @Test
    public void coordinatesAreParsedInEitherOrder() {
        QWeatherApi.Location fromLonLat = QWeatherApi.parseCoordinates("116.41,39.92");
        assertEquals("39.92", fromLonLat.latitude);
        assertEquals("116.41", fromLonLat.longitude);

        QWeatherApi.Location fromLatLon = QWeatherApi.parseCoordinates("39.92,116.41");
        assertEquals("39.92", fromLatLon.latitude);
        assertEquals("116.41", fromLatLon.longitude);
    }
}
