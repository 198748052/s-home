package com.local.tianqi;

/** 某个小时的预报数据。 */
public final class HourlyWeather {
    public final String dateKey;
    /** 0-23 */
    public final int hour;
    public final WeatherCondition condition;
    /** 和风天气天气现象代码，本地模拟数据时用大类的默认代码。 */
    public final String code;
    public final int temp;
    /** 这一小时是不是本地模拟出来的（没有真实逐小时缓存时的兜底）。 */
    public final boolean simulated;

    public HourlyWeather(int hour, WeatherCondition condition, int temp) {
        this(null, hour, condition, condition.code, temp);
    }

    public HourlyWeather(String dateKey, int hour, WeatherCondition condition, int temp) {
        this(dateKey, hour, condition, condition.code, temp);
    }

    public HourlyWeather(String dateKey, int hour, WeatherCondition condition, String code,
                         int temp) {
        this(dateKey, hour, condition, code, temp, false);
    }

    public HourlyWeather(String dateKey, int hour, WeatherCondition condition, String code,
                         int temp, boolean simulated) {
        this.dateKey = dateKey;
        this.hour = hour;
        this.condition = condition;
        this.code = WeatherDay.normalizeCode(code, condition);
        this.temp = temp;
        this.simulated = simulated;
    }

    /** 18 点到次日 6 点算夜间，用于挑夜间图标。 */
    public boolean isNight() {
        return hour >= 18 || hour < 6;
    }
}
