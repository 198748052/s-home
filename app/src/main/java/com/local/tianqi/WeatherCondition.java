package com.local.tianqi;

/**
 * 天气状况的大类，用于播报文案、本地模拟数据和离线兜底。
 * <p>
 * 每个大类都带一个和风天气现象代码作为默认值；真实接口返回的具体代码
 * 会另外保存在 {@link WeatherDay#code} / {@link HourlyWeather#code} 里，
 * 图标按具体代码取，代码含义见 QWeatherIcons 与官方 weather-conditions.csv。
 */
public enum WeatherCondition {
    SUNNY("100", "晴"),
    PARTLY_CLOUDY("101", "多云"),
    CLOUDY("104", "阴"),
    RAIN("305", "小雨"),
    THUNDER("302", "雷阵雨"),
    SNOW("400", "小雪");

    /** 和风天气天气现象代码。 */
    public final String code;
    public final String label;

    WeatherCondition(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 由天气现象描述与代码推断大类。
     * 和风天气在 lang=zh 时返回中文描述，先按关键词判断，再用天气现象代码兜底。
     * 代码表见 docs/qweather-docs/weather/07-weather-conditions.md 与官方 weather-conditions.csv。
     */
    public static WeatherCondition fromText(String text, String code) {
        String value = text == null ? "" : text;
        if (value.contains("雷")) {
            return THUNDER;
        }
        if (value.contains("雪") || value.contains("冰")) {
            return SNOW;
        }
        if (value.contains("雨")) {
            return RAIN;
        }
        if (value.contains("阴")) {
            return CLOUDY;
        }
        if (value.contains("云")) {
            return PARTLY_CLOUDY;
        }
        if (value.contains("晴")) {
            return SUNNY;
        }
        WeatherCondition byCode = fromQWeatherCode(parseInt(code));
        if (byCode != null) {
            return byCode;
        }
        int numeric = parseInt(code);
        if (numeric == 0 || numeric == 1) {
            return SUNNY;
        }
        if (numeric <= 3) {
            return PARTLY_CLOUDY;
        }
        if (numeric <= 9) {
            return CLOUDY;
        }
        if (numeric <= 19) {
            return RAIN;
        }
        if (numeric <= 29) {
            return SNOW;
        }
        return CLOUDY;
    }

    /** 和风天气天气现象代码（100-999）。无法识别时返回 null。 */
    static WeatherCondition fromQWeatherCode(int code) {
        if (code == 100 || code == 150) {
            return SUNNY;
        }
        if (code == 101 || code == 102 || code == 103
                || code == 151 || code == 152 || code == 153) {
            return PARTLY_CLOUDY;
        }
        if (code == 104) {
            return CLOUDY;
        }
        if (code >= 300 && code <= 399) {
            return code == 302 || code == 303 || code == 304 ? THUNDER : RAIN;
        }
        if (code >= 400 && code <= 499) {
            return SNOW;
        }
        if (code >= 500 && code <= 515) {
            // 雾、霾、沙尘一类，没有对应大类，按阴天展示
            return CLOUDY;
        }
        if (code == 900) {
            return SUNNY;
        }
        if (code == 901) {
            return CLOUDY;
        }
        return null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
