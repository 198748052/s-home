package com.local.tianqi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** 播报文案：农历、今天、明天三种说法。 */
public class SpeechTextTest {

    private static LunarInfo lunar(String month, String day, String holiday) {
        return new LunarInfo("2026-11-16", month, day, 2, "丙午", holiday, 0L, true);
    }

    private static WeatherSnapshot snapshot(WeatherDay... days) {
        return new WeatherSnapshot(Arrays.asList(days), Collections.<HourlyWeather>emptyList(),
                null, 0L, 0L, 0L, false, "");
    }

    private static WeatherDay day(int index, WeatherCondition condition, int high, int low) {
        return new WeatherDay("2026-11-1" + (6 + index), 2026, 11, 16 + index, 1 + index,
                condition, high, low, 0);
    }

    @Test
    public void lunarSaysMonthAndDay() {
        assertEquals("今天是农历冬月初二。", SpeechText.lunar(lunar("冬月", "初二", "")));
    }

    @Test
    public void lunarAppendsHoliday() {
        assertEquals("今天是农历正月初一，春节。",
                SpeechText.lunar(lunar("正月", "初一", "春节")));
    }

    @Test
    public void lunarWithoutDataIsEmpty() {
        assertEquals("", SpeechText.lunar(null));
        assertEquals("", SpeechText.lunar(lunar("", "初二", "")));
        assertEquals("", SpeechText.lunar(lunar("冬月", "", "")));
    }

    @Test
    public void todayUsesFirstForecastDay() {
        WeatherSnapshot value = snapshot(
                day(0, WeatherCondition.PARTLY_CLOUDY, 12, 3),
                day(1, WeatherCondition.RAIN, 9, 2));
        assertEquals("今天多云，最高12度，最低3度。", SpeechText.today(value));
    }

    @Test
    public void tomorrowUsesSecondForecastDay() {
        WeatherSnapshot value = snapshot(
                day(0, WeatherCondition.PARTLY_CLOUDY, 12, 3),
                day(1, WeatherCondition.RAIN, 9, 2));
        assertEquals("明天小雨，最高9度，最低2度。", SpeechText.tomorrow(value));
    }

    @Test
    public void belowZeroIsReadAsLingXia() {
        WeatherSnapshot value = snapshot(day(0, WeatherCondition.SNOW, -1, -8));
        assertEquals("今天小雪，最高零下1度，最低零下8度。", SpeechText.today(value));
    }

    @Test
    public void missingForecastFallsBackToCurrentWeather() {
        WeatherSnapshot value = new WeatherSnapshot(Collections.<WeatherDay>emptyList(),
                Collections.<HourlyWeather>emptyList(),
                new NowWeather("多云", 12, 11, 40, "", "", 0L),
                0L, 0L, 0L, false, "");
        assertEquals("现在多云，12度。", SpeechText.today(value));
        assertEquals("现在多云，12度。", SpeechText.tomorrow(value));
    }

    @Test
    public void onlyOneForecastDayStillSpeaksToday() {
        WeatherSnapshot value = snapshot(day(0, WeatherCondition.SUNNY, 20, 10));
        assertEquals("今天晴，最高20度，最低10度。", SpeechText.today(value));
        // 只有一天预报且没有实时天气时，明天没有内容可念
        assertEquals("", SpeechText.tomorrow(value));
    }

    @Test
    public void nullSnapshotIsEmpty() {
        assertEquals("", SpeechText.today(null));
        assertEquals("", SpeechText.tomorrow(null));
    }
}
