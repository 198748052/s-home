package com.local.tianqi;

public final class NowWeather {

    public final String text;
    public final int temperature;
    public final int feelsLike;
    public final int humidity;
    public final String windDirection;
    public final String windScale;
    public final long updatedAt;

    public NowWeather(String text, int temperature, int feelsLike, int humidity,
                      String windDirection, String windScale, long updatedAt) {
        this.text = text == null ? "" : text;
        this.temperature = temperature;
        this.feelsLike = feelsLike;
        this.humidity = humidity;
        this.windDirection = windDirection == null ? "" : windDirection;
        this.windScale = windScale == null ? "" : windScale;
        this.updatedAt = updatedAt;
    }
}
