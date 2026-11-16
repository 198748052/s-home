package com.local.tianqi;

import java.util.List;

/**
 * 天气预报数据源。测试阶段使用本地模拟实现，
 * 后续接入服务端时新增一个实现类替换即可，界面层无需改动。
 */
public interface WeatherRepository {

    /** 从今天开始返回 count 天的预报，第 0 天为今天。 */
    List<WeatherDay> getForecast(int count);

    /** 从当前小时开始返回 count 小时的预报，第 0 个为当前小时。 */
    List<HourlyWeather> getHourlyForecast(int count);
}
