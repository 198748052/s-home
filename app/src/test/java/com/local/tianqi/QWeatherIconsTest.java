package com.local.tianqi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 官方图标字体的字形映射。
 * 码位来自 QWeather-Icons-1.8.0/font/qweather-icons.json，
 * 中文含义来自官方 weather-conditions.csv。
 */
public class QWeatherIconsTest {

    @Test
    public void dayIconsUseTheirOwnCodepoint() {
        assertEquals('\uF101', QWeatherIcons.glyph("100", false));
        assertEquals('\uF102', QWeatherIcons.glyph("101", false));
        assertEquals('\uF105', QWeatherIcons.glyph("104", false));
        assertEquals('\uF10F', QWeatherIcons.glyph("305", false));
    }

    @Test
    public void nightVariantSwapsClearAndCloudyIcons() {
        assertEquals('\uF106', QWeatherIcons.glyph("100", true));
        assertEquals('\uF107', QWeatherIcons.glyph("101", true));
        assertEquals('\uF108', QWeatherIcons.glyph("102", true));
        assertEquals('\uF109', QWeatherIcons.glyph("103", true));
        // 下雨下雪不分昼夜
        assertEquals(QWeatherIcons.glyph("305", false), QWeatherIcons.glyph("305", true));
    }

    @Test
    public void unknownCodeFallsBackToUnknownIcon() {
        char unknown = QWeatherIcons.glyph("999", false);
        assertEquals(unknown, QWeatherIcons.glyph("abc", false));
        assertEquals(unknown, QWeatherIcons.glyph("", false));
        assertEquals(unknown, QWeatherIcons.glyph(null, false));
        assertEquals(unknown, QWeatherIcons.glyph("12345", false));
    }

    @Test
    public void chineseTextComesFromOfficialTable() {
        assertEquals("晴", QWeatherIcons.text("100"));
        assertEquals("多云", QWeatherIcons.text("101"));
        assertEquals("小雨", QWeatherIcons.text("305"));
        assertEquals("雨夹雪", QWeatherIcons.text("404"));
        assertEquals("严重霾", QWeatherIcons.text("513"));
        assertEquals("", QWeatherIcons.text("12345"));
        assertEquals("", QWeatherIcons.text(null));
    }

    @Test
    public void hourlyNightFlagFollowsTheClock() {
        assertEquals(false, new HourlyWeather("2024-05-31", 17, WeatherCondition.SUNNY, 20).isNight());
        assertEquals(true, new HourlyWeather("2024-05-31", 18, WeatherCondition.SUNNY, 20).isNight());
        assertEquals(true, new HourlyWeather("2024-05-31", 3, WeatherCondition.SUNNY, 20).isNight());
        assertEquals(false, new HourlyWeather("2024-05-31", 6, WeatherCondition.SUNNY, 20).isNight());
    }

    @Test
    public void modelsKeepApiCodeAndFallBackForLegacyValues() {
        WeatherDay withCode = new WeatherDay("2024-05-31", 2024, 5, 31, 5,
                WeatherCondition.RAIN, "307", 25, 18, 80);
        assertEquals("307", withCode.code);

        WeatherDay legacy = new WeatherDay("2024-05-31", 2024, 5, 31, 5,
                WeatherCondition.RAIN, "0", 25, 18, 80);
        assertEquals(WeatherCondition.RAIN.code, legacy.code);

        HourlyWeather emptyCode = new HourlyWeather("2024-05-31", 9,
                WeatherCondition.SNOW, "", -3);
        assertEquals(WeatherCondition.SNOW.code, emptyCode.code);
    }
}
