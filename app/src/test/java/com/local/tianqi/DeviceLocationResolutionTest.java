package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Collections;

/**
 * 定位坐标必须优先于城市名。
 * <p>
 * 曾经的 bug：定位后把「河南省龙亭」这种带省市的名字当城市名再搜一次，
 * GeoAPI 模糊命中了韩国京畿道的「河南市」，天气就查到了韩国。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DeviceLocationResolutionTest {

    private Context context;
    private AppSettings settings;
    private WeatherService service;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        settings = new AppSettings(context);
        service = new WeatherService(context);
    }

    @Test
    public void storedDeviceLocationWinsOverCityName() throws Exception {
        settings.saveDeviceLocation(new QWeatherApi.Location(
                "34.80", "114.31", "龙亭", "河南省", "开封市", "中国"));

        assertTrue(settings.isLocationMode());
        assertTrue(settings.hasLocation());

        QWeatherApi.Location resolved = service.resolveLocation(
                "https://example.com", Collections.<String, String>emptyMap(), "北京");

        assertEquals("34.80", resolved.latitude);
        assertEquals("114.31", resolved.longitude);
        assertEquals("中国", resolved.country);
        assertEquals("开封市龙亭", resolved.display());
    }

    @Test
    public void withoutDeviceLocationTheCityNameIsUsed() throws Exception {
        assertFalse(settings.hasLocation());

        QWeatherApi.Location resolved = service.resolveLocation(
                "https://example.com", Collections.<String, String>emptyMap(), "116.41,39.92");

        assertEquals("39.92", resolved.latitude);
        assertEquals("116.41", resolved.longitude);
    }

    @Test
    public void clearingDeviceLocationFallsBackToCity() throws Exception {
        settings.saveDeviceLocation(new QWeatherApi.Location(
                "34.80", "114.31", "龙亭", "河南省", "开封市", "中国"));
        settings.clearDeviceLocation();

        assertFalse(settings.hasLocation());
        assertFalse(settings.isLocationMode());
        QWeatherApi.Location resolved = service.resolveLocation(
                "https://example.com", Collections.<String, String>emptyMap(), "116.41,39.92");
        assertEquals("39.92", resolved.latitude);
    }

    @Test
    public void citySearchRejectsForeignMatches() throws Exception {
        // range=cn 之外再兜一层：结果落在国外直接报错
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"河南市\", \"id\": \"2FDB\", \"lat\": \"37.53917\", \"lon\": \"127.21472\",\n"
                + "  \"adm1\": \"京畿道\", \"adm2\": \"京畿道\", \"country\": \"韩国\"}]}");

        try {
            QWeatherApi.parseLookupResponse(payload, "河南", true);
            org.junit.Assert.fail("只支持中国城市，不该接受韩国结果");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("只支持中国城市"));
        }
    }

    @Test
    public void citySearchAcceptsChineseMatches() throws Exception {
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"开封\", \"id\": \"101180101\", \"lat\": \"34.79718\", \"lon\": \"114.30756\",\n"
                + "  \"adm1\": \"河南省\", \"adm2\": \"开封市\", \"country\": \"中国\"}]}");

        QWeatherApi.Location picked = QWeatherApi.parseLookupResponse(payload, "开封", true);
        assertEquals("开封", picked.name);
        assertEquals("中国", picked.country);
    }

    @Test
    public void chineseCacheIsUsedWithoutNetwork() throws Exception {
        LocationCache.write(settings, "开封",
                new QWeatherApi.Location("34.80", "114.31", "开封", "河南省", "开封市", "中国"));

        // 域名故意不可用：能返回结果说明走的是缓存，没发请求
        QWeatherApi.Location resolved = service.resolveLocation(
                "https://invalid.example", Collections.<String, String>emptyMap(), "开封");
        assertEquals("34.80", resolved.latitude);
        assertEquals("中国", resolved.country);
    }

    @Test
    public void foreignCacheEntryIsNotTrustedForCityLookup() {
        assertTrue(WeatherService.isChina(
                new QWeatherApi.Location("34.80", "114.31", "开封", "河南省", "开封市", "中国")));
        assertTrue(WeatherService.isChina(
                new QWeatherApi.Location("34.80", "114.31", "", "", "", "")));
        assertFalse(WeatherService.isChina(
                new QWeatherApi.Location("37.54", "127.21", "河南市", "京畿道", "京畿道", "韩国")));
    }

    @Test
    public void lookupPrefersExactNameMatchOverFuzzyForeignHit() throws Exception {
        // 真实返回顺序：模糊命中的韩国「河南市」排在前面，真正要的「龙亭」在后面
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"河南市\", \"id\": \"2FDB\", \"lat\": \"37.53917\", \"lon\": \"127.21472\",\n"
                + "  \"adm1\": \"京畿道\", \"adm2\": \"京畿道\", \"country\": \"韩国\"},\n"
                + "  {\"name\": \"龙亭\", \"id\": \"101180101\", \"lat\": \"34.79983\", \"lon\": \"114.35474\",\n"
                + "   \"adm1\": \"河南省\", \"adm2\": \"开封市\", \"country\": \"中国\"}]}");

        QWeatherApi.Location picked = QWeatherApi.parseLookupResponse(payload, "龙亭");

        assertEquals("龙亭", picked.name);
        assertEquals("中国", picked.country);
        assertEquals("34.80", picked.latitude);
        assertEquals("114.35", picked.longitude);
        assertEquals("开封市龙亭", picked.display());
        assertEquals("中国 河南省开封市龙亭", picked.full());
    }

    @Test
    public void searchKeyNeverCarriesProvincePrefix() {        QWeatherApi.Location location = new QWeatherApi.Location(
                "34.80", "114.31", "龙亭", "河南省", "开封市", "中国");
        // 带省市前缀的名字会被 GeoAPI 模糊匹配到韩国「河南市」
        assertEquals("龙亭", location.searchKey());
        assertFalse(location.searchKey().contains("省"));
    }

    @Test
    public void locationWithoutNameFallsBackToCoordinates() {
        QWeatherApi.Location location = new QWeatherApi.Location("34.80", "114.31", "");
        assertEquals("34.80,114.31", location.searchKey());
        assertEquals("34.80,114.31", location.display());
    }

    @Test
    public void composedLabelIsRejectedInsteadOfMatchingForeignCity() throws Exception {
        // 旧版本把「河南省龙亭」存成了城市名，GeoAPI 只会模糊命中韩国「河南市」
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"河南市\", \"id\": \"2FDB\", \"lat\": \"37.53917\", \"lon\": \"127.21472\",\n"
                + "  \"adm1\": \"京畿道\", \"adm2\": \"京畿道\", \"country\": \"韩国\"}]}");

        try {
            QWeatherApi.parseLookupResponse(payload, "河南省龙亭");
            org.junit.Assert.fail("不该把韩国「河南市」当成「河南省龙亭」");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("未找到城市"));
        }
    }

    @Test
    public void latinOrCoordinateKeywordIsNotRejected() throws Exception {
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"纽约\", \"id\": \"10001\", \"lat\": \"40.71\", \"lon\": \"-74.01\",\n"
                + "  \"adm1\": \"纽约州\", \"country\": \"美国\"}]}");

        QWeatherApi.Location picked = QWeatherApi.parseLookupResponse(payload, "New York");
        assertEquals("纽约", picked.name);
        assertEquals("美国", picked.country);
    }
}
