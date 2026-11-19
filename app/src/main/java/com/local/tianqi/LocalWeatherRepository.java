package com.local.tianqi;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

/**
 * 本地模拟天气数据源，测试阶段使用。
 * 同一天的模拟结果稳定，便于反复调试界面；数据不联网、不读取系统天气服务。
 */
public final class LocalWeatherRepository implements WeatherRepository {

    /** 各月典型最高气温（华北平原），第 0 位占位。 */
    private static final int[] BASE_HIGH = {0, 3, 6, 13, 21, 27, 31, 32, 31, 27, 20, 11, 4};
    /** 各月典型最低气温，第 0 位占位。 */
    private static final int[] BASE_LOW = {0, -7, -4, 1, 8, 14, 20, 23, 22, 16, 9, 1, -5};

    @Override
    public List<WeatherDay> getForecast(int count) {
        Calendar calendar = Calendar.getInstance();
        List<WeatherDay> days = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int year = calendar.get(Calendar.YEAR);
            int month = calendar.get(Calendar.MONTH) + 1;
            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            int calendarDow = calendar.get(Calendar.DAY_OF_WEEK);
            int isoDow = calendarDow == Calendar.SUNDAY ? 7 : calendarDow - 1;

            Random random = new Random((year * 10000L + month * 100L + dayOfMonth) * 7919L + 13L);
            WeatherCondition condition = pickCondition(month, random);
            int swing = random.nextInt(7) - 3;
            int high = BASE_HIGH[month] + swing;
            int low = BASE_LOW[month] + swing + random.nextInt(3) - 1;
            if (high - low < 4) {
                high = low + 4;
            }

            days.add(new WeatherDay(dateKey(year, month, dayOfMonth), year, month, dayOfMonth,
                    isoDow, condition, condition.code, high, low,
                    rainChance(condition, random), true));
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        return days;
    }

    @Override
    public List<HourlyWeather> getHourlyForecast(int count) {
        Calendar calendar = Calendar.getInstance();
        int currentHour = calendar.get(Calendar.HOUR_OF_DAY);
        List<WeatherDay> days = getForecast(2);
        List<HourlyWeather> hours = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int absoluteHour = currentHour + i;
            int hour = absoluteHour % 24;
            int dayIndex = Math.min(absoluteHour / 24, days.size() - 1);
            WeatherDay day = days.get(dayIndex);
            Calendar target = (Calendar) calendar.clone();
            target.add(Calendar.HOUR_OF_DAY, i);
            // 昼夜温度曲线：5 点最低，17 点最高
            double factor = 0.5 - 0.5 * Math.cos(2 * Math.PI * (hour - 5) / 24.0);
            int temp = (int) Math.round(day.low + (day.high - day.low) * factor);
            hours.add(new HourlyWeather(dateKey(target), hour, day.condition, day.code, temp,
                    true));
        }
        return hours;
    }

    private static WeatherCondition pickCondition(int month, Random random) {
        WeatherCondition[] candidates;
        int[] weights;
        if (month == 12 || month <= 2) {
            candidates = new WeatherCondition[]{
                    WeatherCondition.SUNNY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY,
                    WeatherCondition.SNOW, WeatherCondition.RAIN};
            weights = new int[]{35, 25, 20, 12, 8};
        } else if (month >= 6 && month <= 8) {
            candidates = new WeatherCondition[]{
                    WeatherCondition.SUNNY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY,
                    WeatherCondition.THUNDER, WeatherCondition.RAIN};
            weights = new int[]{30, 20, 15, 20, 15};
        } else if (month >= 3 && month <= 5) {
            candidates = new WeatherCondition[]{
                    WeatherCondition.SUNNY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY,
                    WeatherCondition.RAIN, WeatherCondition.THUNDER};
            weights = new int[]{30, 25, 20, 20, 5};
        } else {
            candidates = new WeatherCondition[]{
                    WeatherCondition.SUNNY, WeatherCondition.PARTLY_CLOUDY, WeatherCondition.CLOUDY,
                    WeatherCondition.RAIN};
            weights = new int[]{40, 25, 20, 15};
        }

        int total = 0;
        for (int weight : weights) {
            total += weight;
        }
        int value = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            if (value < weights[i]) {
                return candidates[i];
            }
            value -= weights[i];
        }
        return WeatherCondition.SUNNY;
    }

    static String dateKey(Calendar calendar) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String dateKey(int year, int month, int dayOfMonth) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d", year, month, dayOfMonth);
    }

    private static int rainChance(WeatherCondition condition, Random random) {
        switch (condition) {
            case RAIN:
                return 60 + random.nextInt(31);
            case THUNDER:
                return 70 + random.nextInt(26);
            case SNOW:
                return 60 + random.nextInt(31);
            case CLOUDY:
                return 20 + random.nextInt(21);
            case PARTLY_CLOUDY:
                return 10 + random.nextInt(16);
            case SUNNY:
            default:
                return random.nextInt(11);
        }
    }
}
