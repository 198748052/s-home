package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Map;

/**
 * 和风天气（QWeather）响应解析的回归测试。
 * 样本取自 docs/qweather-docs/weather 下的官方示例，接口结构变化时这里会先报错。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class QWeatherParsingTest {

    private WeatherService service;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        service = new WeatherService(context);
    }

    @Test
    public void realtimeWeatherIsParsedFromV1Payload() throws Exception {
        JSONObject payload = new JSONObject("{\n"
                + "  \"condition\": {\"text\": \"少云\", \"code\": \"102\"},\n"
                + "  \"temperature\": {\"value\": 31.71, \"unit\": \"°C\"},\n"
                + "  \"feelsLike\": {\"value\": 33.64, \"unit\": \"°C\"},\n"
                + "  \"humidity\": 0.69,\n"
                + "  \"wind\": {\"direction\": {\"degree\": 226, \"compass\": \"sw\"},\n"
                + "            \"speed\": {\"value\": 4.74, \"unit\": \"m/s\"}, \"scale\": 3}\n"
                + "}");

        NowWeather now = service.parseNow(payload, 1000L);

        assertEquals("少云", now.text);
        assertEquals(32, now.temperature);
        assertEquals(34, now.feelsLike);
        assertEquals(69, now.humidity);
        assertEquals("西南风", now.windDirection);
        assertEquals("3级", now.windScale);
        assertEquals(1000L, now.updatedAt);
    }

    @Test
    public void hourlyForecastIsParsedFromV1Payload() throws Exception {
        JSONObject payload = new JSONObject("{\"hours\": [\n"
                + "  {\"forecastTime\": \"2024-05-31T03:00+08:00\",\n"
                + "   \"condition\": {\"text\": \"阴\", \"code\": \"104\"},\n"
                + "   \"temperature\": {\"value\": 31.12, \"unit\": \"°C\"}},\n"
                + "  {\"forecastTime\": \"2024-05-31T04:00+08:00\",\n"
                + "   \"condition\": {\"text\": \"小雨\", \"code\": \"305\"},\n"
                + "   \"temperature\": {\"value\": 22.4, \"unit\": \"°C\"}}\n"
                + "]}");

        List<HourlyWeather> hours = service.parseHourly(payload);

        assertEquals(2, hours.size());
        assertEquals("2024-05-31", hours.get(0).dateKey);
        assertEquals(3, hours.get(0).hour);
        assertEquals(31, hours.get(0).temp);
        assertEquals(WeatherCondition.CLOUDY, hours.get(0).condition);
        assertEquals("104", hours.get(0).code);
        assertEquals(4, hours.get(1).hour);
        assertEquals(22, hours.get(1).temp);
        assertEquals(WeatherCondition.RAIN, hours.get(1).condition);
        assertEquals("305", hours.get(1).code);
    }

    @Test
    public void dailyForecastIsParsedFromV1Payload() throws Exception {
        JSONObject payload = new JSONObject("{\"days\": [\n"
                + "  {\"forecastStartTime\": \"2024-08-11T00:00+08:00\",\n"
                + "   \"forecastEndTime\": \"2024-08-12T00:00+08:00\",\n"
                + "   \"temperatureMax\": {\"value\": 29.94, \"unit\": \"°C\"},\n"
                + "   \"temperatureMin\": {\"value\": 20.93, \"unit\": \"°C\"},\n"
                + "   \"daytime\": {\n"
                + "     \"condition\": {\"text\": \"小雨\", \"code\": \"305\"},\n"
                + "     \"precipitation\": {\"amount\": {\"value\": 0.75, \"unit\": \"mm\"},\n"
                + "                        \"probability\": 0.64, \"type\": \"rain\"}}}\n"
                + "]}");

        List<WeatherDay> days = service.parseDaily(payload);

        assertEquals(1, days.size());
        WeatherDay day = days.get(0);
        assertEquals("2024-08-11", day.dateKey);
        assertEquals(2024, day.year);
        assertEquals(8, day.month);
        assertEquals(11, day.dayOfMonth);
        assertEquals(7, day.dayOfWeek);
        assertEquals(30, day.high);
        assertEquals(21, day.low);
        assertEquals(64, day.rainChance);
        assertEquals(WeatherCondition.RAIN, day.condition);
        assertEquals("305", day.code);
    }

    @Test
    public void apiHostIsNormalized() {
        assertEquals("https://abc.re.qweatherapi.com",
                QWeatherApi.baseUrl("abc.re.qweatherapi.com"));
        assertEquals("https://abc.re.qweatherapi.com",
                QWeatherApi.baseUrl("https://abc.re.qweatherapi.com/"));
        assertEquals("", QWeatherApi.baseUrl("   "));
    }

    @Test
    public void apiKeyAndJwtUseDifferentHeaders() {
        Map<String, String> keyHeaders = QWeatherApi.authHeaders("0123456789abcdef0123456789abcdef");
        assertEquals("0123456789abcdef0123456789abcdef", keyHeaders.get("X-QW-Api-Key"));
        assertNull(keyHeaders.get("Authorization"));

        Map<String, String> jwtHeaders = QWeatherApi.authHeaders("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig");
        assertEquals("Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
                jwtHeaders.get("Authorization"));
        assertNull(jwtHeaders.get("X-QW-Api-Key"));
    }

    @Test
    public void coordinatesAcceptEitherOrder() {
        QWeatherApi.Location standard = QWeatherApi.parseCoordinates("116.41,39.92");
        assertEquals("39.92", standard.latitude);
        assertEquals("116.41", standard.longitude);

        QWeatherApi.Location swapped = QWeatherApi.parseCoordinates("39.92,116.41");
        assertEquals("39.92", swapped.latitude);
        assertEquals("116.41", swapped.longitude);

        assertNull("城市名不该被当成坐标", QWeatherApi.parseCoordinates("北京"));
        assertNull(QWeatherApi.parseCoordinates("116.41"));
    }

    @Test
    public void cityLookupResponseYieldsCoordinates() throws Exception {
        JSONObject payload = new JSONObject("{\"code\": \"200\", \"location\": [{\n"
                + "  \"name\": \"东城\", \"id\": \"101011600\",\n"
                + "  \"lat\": \"39.91755\", \"lon\": \"116.41876\",\n"
                + "  \"adm1\": \"北京市\", \"country\": \"中国\"}]}");

        QWeatherApi.Location location = QWeatherApi.parseLookupResponse(payload, "东城");

        assertEquals("39.92", location.latitude);
        assertEquals("116.42", location.longitude);
        assertEquals("东城", location.name);
        assertEquals("中国", location.country);
        assertEquals("中国 北京市东城", location.full());
    }

    @Test
    public void qweatherConditionCodesMapToIcons() {
        assertEquals(WeatherCondition.SUNNY, WeatherCondition.fromText("", "100"));
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WeatherCondition.fromText("", "103"));
        assertEquals(WeatherCondition.CLOUDY, WeatherCondition.fromText("", "104"));
        assertEquals(WeatherCondition.RAIN, WeatherCondition.fromText("", "305"));
        assertEquals(WeatherCondition.THUNDER, WeatherCondition.fromText("", "302"));
        assertEquals(WeatherCondition.SNOW, WeatherCondition.fromText("", "400"));
        assertEquals(WeatherCondition.CLOUDY, WeatherCondition.fromText("", "502"));
        assertEquals(WeatherCondition.CLOUDY, WeatherCondition.fromText("", "999"));
    }
}
