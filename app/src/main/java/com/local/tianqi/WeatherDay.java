package com.local.tianqi;

/** 某一天的预报数据。 */
public final class WeatherDay {
    public final String dateKey;
    public final int year;
    public final int month;
    public final int dayOfMonth;
    /** 1 = 周一 ... 7 = 周日 */
    public final int dayOfWeek;
    public final WeatherCondition condition;
    /** 和风天气天气现象代码，本地模拟数据时用大类的默认代码。 */
    public final String code;
    public final int high;
    public final int low;
    /** 降水概率，0-100 */
    public final int rainChance;

    public WeatherDay(int year, int month, int dayOfMonth, int dayOfWeek,
                      WeatherCondition condition, int high, int low, int rainChance) {
        this(null, year, month, dayOfMonth, dayOfWeek, condition, condition.code,
                high, low, rainChance);
    }

    public WeatherDay(String dateKey, int year, int month, int dayOfMonth, int dayOfWeek,
                      WeatherCondition condition, int high, int low, int rainChance) {
        this(dateKey, year, month, dayOfMonth, dayOfWeek, condition, condition.code,
                high, low, rainChance);
    }

    public WeatherDay(String dateKey, int year, int month, int dayOfMonth, int dayOfWeek,
                      WeatherCondition condition, String code, int high, int low,
                      int rainChance) {
        this.dateKey = dateKey;
        this.year = year;
        this.month = month;
        this.dayOfMonth = dayOfMonth;
        this.dayOfWeek = dayOfWeek;
        this.condition = condition;
        this.code = normalizeCode(code, condition);
        this.high = high;
        this.low = low;
        this.rainChance = rainChance;
    }

    /** 和风天气的现象代码固定三位；历史缓存里的空值或异常值回退到大类默认代码。 */
    static String normalizeCode(String code, WeatherCondition condition) {
        String value = code == null ? "" : code.trim();
        if (value.length() != 3) {
            return condition.code;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return condition.code;
            }
        }
        return value;
    }
}
