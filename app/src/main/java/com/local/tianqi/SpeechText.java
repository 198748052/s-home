package com.local.tianqi;

import java.util.List;

/**
 * 语音播报文案：把农历和天气数据拼成适合朗读的中文句子。
 * <p>
 * 这里只做纯文本拼接，不碰网络和播放器，方便单元测试。
 * 播报内容按需求分成三种：农历月日、今天天气、明天天气。
 */
public final class SpeechText {

    private SpeechText() {
    }

    /** 农历：今天是农历冬月初二，春节。 */
    public static String lunar(LunarInfo info) {
        if (info == null) {
            return "";
        }
        String month = trim(info.monthName);
        String day = trim(info.dayName);
        if (month.isEmpty() || day.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("今天是农历").append(month).append(day);
        String holiday = trim(info.holiday);
        if (!holiday.isEmpty()) {
            builder.append("，").append(holiday);
        }
        return builder.append("。").toString();
    }

    /** 今天：今天多云，最高12度，最低3度。 */
    public static String today(WeatherSnapshot snapshot) {
        return day(snapshot, 0, "今天");
    }

    /** 明天：明天小雨，最高9度，最低2度。 */
    public static String tomorrow(WeatherSnapshot snapshot) {
        return day(snapshot, 1, "明天");
    }

    /** 取第 index 天的预报；没有预报数据时退回实时天气。 */
    private static String day(WeatherSnapshot snapshot, int index, String label) {
        if (snapshot == null) {
            return "";
        }
        List<WeatherDay> daily = snapshot.daily;
        if (daily != null && daily.size() > index) {
            WeatherDay day = daily.get(index);
            return label + condition(day)
                    + "，最高" + degrees(day.high)
                    + "，最低" + degrees(day.low) + "。";
        }
        return now(snapshot);
    }

    /** 没有预报时念实时天气：现在多云，12度。 */
    static String now(WeatherSnapshot snapshot) {
        if (snapshot == null || snapshot.now == null) {
            return "";
        }
        NowWeather now = snapshot.now;
        String condition = trim(now.text);
        return "现在" + condition + "，" + degrees(now.temperature) + "。";
    }

    /** 零下的温度念成「零下3度」，避免机器把负号读成减号。 */
    static String degrees(int value) {
        return value < 0 ? "零下" + (-value) + "度" : value + "度";
    }

    private static String condition(WeatherDay day) {
        if (day == null || day.condition == null) {
            return "";
        }
        return trim(day.condition.label);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
