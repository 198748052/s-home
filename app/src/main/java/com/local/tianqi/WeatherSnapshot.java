package com.local.tianqi;

import java.util.Collections;
import java.util.List;

public final class WeatherSnapshot {

    public final List<WeatherDay> daily;
    public final List<HourlyWeather> hourly;
    public final NowWeather now;
    public final long dailyUpdatedAt;
    public final long hourlyUpdatedAt;
    public final long nowUpdatedAt;
    public final boolean staleDaily;
    public final String status;

    public WeatherSnapshot(List<WeatherDay> daily, List<HourlyWeather> hourly, NowWeather now,
                           long dailyUpdatedAt, long hourlyUpdatedAt, long nowUpdatedAt,
                           boolean staleDaily, String status) {
        this.daily = daily == null ? Collections.<WeatherDay>emptyList() : daily;
        this.hourly = hourly == null ? Collections.<HourlyWeather>emptyList() : hourly;
        this.now = now;
        this.dailyUpdatedAt = dailyUpdatedAt;
        this.hourlyUpdatedAt = hourlyUpdatedAt;
        this.nowUpdatedAt = nowUpdatedAt;
        this.staleDaily = staleDaily;
        this.status = status == null ? "" : status;
    }

    public WeatherSnapshot withStatus(String newStatus) {
        return new WeatherSnapshot(daily, hourly, now, dailyUpdatedAt, hourlyUpdatedAt,
                nowUpdatedAt, staleDaily, newStatus);
    }
}
