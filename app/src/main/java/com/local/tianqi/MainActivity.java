package com.local.tianqi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends Activity {

    private static final String[] WEEK_NAMES = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final int REQUEST_LOCATION = 2001;

    private LunarService lunarService;
    private WeatherService weatherService;
    private VoiceAnnouncer voice;
    private AppSettings settings;

    private TextView tvSolarDate;
    private TextView tvWeekday;
    private TextView tvNowWeather;
    private TextView tvStatus;
    private TextView tvLunarMonth;
    private TextView tvLunarDayName;
    private TextView tvLunarDayNum;
    private View lunarCard;
    private View hourlyScroll;
    private LinearLayout hourlyRow;
    private LinearLayout forecastRow;
    private View gateEntry;
    private PullRefreshScrollView contentScroll;
    private boolean locationRequested;
    /** 上次看到的定位开关状态，用来发现用户是不是刚在设置页改过。 */
    private boolean lastLocationMode;

    /** 播报用的最新数据：点哪个区域就念对应内容。 */
    private LunarInfo lunarInfo;
    private WeatherSnapshot snapshot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        settings = new AppSettings(this);
        lunarService = new LunarService(this);
        weatherService = new WeatherService(this);
        voice = new VoiceAnnouncer(this);

        tvSolarDate = findViewById(R.id.tvSolarDate);
        tvWeekday = findViewById(R.id.tvWeekday);
        tvNowWeather = findViewById(R.id.tvNowWeather);
        tvStatus = findViewById(R.id.tvStatus);
        tvLunarMonth = findViewById(R.id.tvLunarMonth);
        tvLunarDayName = findViewById(R.id.tvLunarDayName);
        tvLunarDayNum = findViewById(R.id.tvLunarDayNum);
        lunarCard = findViewById(R.id.lunarCard);
        hourlyScroll = findViewById(R.id.hourlyScroll);
        hourlyRow = findViewById(R.id.hourlyRow);
        forecastRow = findViewById(R.id.forecastRow);
        gateEntry = findViewById(R.id.gateEntry);

        // 点农历卡片：播报农历月日
        lunarCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                announce(SpeechText.lunar(lunarInfo));
            }
        });

        contentScroll = findViewById(R.id.contentScroll);
        contentScroll.setRefreshHeader(findViewById(R.id.tvRefreshHint));
        contentScroll.setOnRefreshListener(new PullRefreshScrollView.OnRefreshListener() {
            @Override
            public void onRefresh() {
                // 下拉只刷天气，不碰万年历
                refreshWeather(true);
            }
        });

        findViewById(R.id.tvSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        gateEntry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, R.string.gate_placeholder, Toast.LENGTH_SHORT).show();
            }
        });

        lastLocationMode = settings.isLocationMode();
        ensureLocationPermission();
        render();
        syncConfig();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚在设置页打开了「使用系统定位」：模式变了就重新申请一次权限，
        // 否则拒绝过一次之后回到首页永远不再弹窗，定位功能等于静默失效。
        boolean locationMode = settings.isLocationMode();
        if (locationMode != lastLocationMode) {
            lastLocationMode = locationMode;
            locationRequested = false;
        }
        ensureLocationPermission();
        render();
        refreshData();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voice != null) {
            voice.release();
        }
        // 这三个各带一个常驻线程，不释放会随 Activity 重建一直累积
        if (weatherService != null) {
            weatherService.release();
        }
        if (lunarService != null) {
            lunarService.release();
        }
        if (settings != null) {
            settings.releaseKeysStore();
        }
    }

    /**
     * 启动时同步云端配置：接口地址、Key、TTS 模型/音色/语速都从这里来，
     * 云端换了配置就不用发版。只有内容真的变了才重新取数据，避免重复请求。
     */
    private void syncConfig() {
        settings.keysStore().refresh(false, new KeysStore.Callback() {
            @Override
            public void onKeys(CloudKeys keys, boolean changed, String status) {
                if (!changed || isFinishing() || isDestroyed()) {
                    return;
                }
                render();
                refreshData();
            }
        });
    }

    /** 播报一段文案；没有数据时提示一下，不弹空语音。 */
    private void announce(String text) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(this, R.string.voice_no_data, Toast.LENGTH_SHORT).show();
            return;
        }
        voice.announce(text);
    }

    /** 定位模式下没有权限就先申请一次，拒绝也没关系，会回退到上次位置或城市名。 */
    private void ensureLocationPermission() {
        if (locationRequested) {
            return;
        }
        if (!settings.isLocationMode()) {
            return;
        }
        if (DeviceLocation.hasPermission(this)) {
            return;
        }
        locationRequested = true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            refreshData();
        }
    }

    private void refreshData() {
        lunarService.refreshIfNeeded(new LunarService.Callback() {
            @Override
            public void onLunar(LunarInfo info, String status) {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                applyLunar(info);
            }
        });
        refreshWeather(false);
    }

    /** @param force true 时忽略一小时的节流，用于下拉刷新。 */
    private void refreshWeather(boolean force) {
        WeatherService.Callback callback = new WeatherService.Callback() {
            @Override
            public void onSnapshot(WeatherSnapshot snapshot) {
                if (contentScroll != null) {
                    contentScroll.setRefreshing(false);
                }
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                applySnapshot(snapshot);
            }
        };
        if (force) {
            weatherService.refreshNow(callback);
        } else {
            weatherService.refresh(callback);
        }
    }

    private void render() {
        applyAdaptiveTextSizes();
        updateSolarDate();
        applyLunar(lunarService.loadCachedOrLocal());
        applySnapshot(weatherService.loadSnapshot());
    }

    private void updateSolarDate() {
        Calendar today = Calendar.getInstance();
        int month = today.get(Calendar.MONTH) + 1;
        int dayOfMonth = today.get(Calendar.DAY_OF_MONTH);
        int calendarDow = today.get(Calendar.DAY_OF_WEEK);
        int isoDow = calendarDow == Calendar.SUNDAY ? 7 : calendarDow - 1;

        tvSolarDate.setText(month + "月" + dayOfMonth + "日");
        tvWeekday.setText(WEEK_NAMES[isoDow - 1]);
    }

    private void applyLunar(LunarInfo lunar) {
        lunarInfo = lunar;
        if (lunar == null) {
            tvLunarMonth.setText("");
            tvLunarDayName.setText("");
            tvLunarDayNum.setText("--");
            return;
        }
        tvLunarMonth.setText(lunar.monthName);
        tvLunarDayName.setText(lunar.dayName);
        tvLunarDayNum.setText(lunar.dayNumber > 0 ? String.valueOf(lunar.dayNumber) : "--");
    }

    private void applySnapshot(WeatherSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.snapshot = snapshot;
        applyStatus(snapshot.status);
        if (snapshot.now != null) {
            tvNowWeather.setText(snapshot.now.text + " " + snapshot.now.temperature + "°");
        } else {
            tvNowWeather.setText("");
        }

        if (snapshot.hourly.isEmpty()) {
            hourlyScroll.setVisibility(View.GONE);
            hourlyRow.removeAllViews();
        } else {
            hourlyScroll.setVisibility(View.VISIBLE);
            buildHourlyRow(snapshot.hourly);
        }
        buildForecastRow(snapshot.daily);
    }

    /**
     * 显示数据状态。除了「已更新」以外的状态（离线缓存、本地模拟数据、定位失败、未配置）
     * 都用警示色，避免把模拟/过期数据当成刚拿到的真实天气。
     */
    private void applyStatus(String status) {
        if (status == null || status.isEmpty()) {
            tvStatus.setVisibility(View.GONE);
            return;
        }
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(status);
        tvStatus.setTextColor(getColor(status.startsWith("已更新")
                ? R.color.text_secondary : R.color.warm));
    }

    private boolean isNarrow() {
        return Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density) < 340;
    }

    private void applyAdaptiveTextSizes() {
        boolean narrow = isNarrow();
        tvSolarDate.setTextSize(narrow ? 24 : 28);
        tvWeekday.setTextSize(narrow ? 15 : 18);
        tvNowWeather.setTextSize(narrow ? 15 : 18);
        tvLunarDayNum.setTextSize(narrow ? 78 : 96);
        tvLunarMonth.setTextSize(narrow ? 32 : 40);
        tvLunarDayName.setTextSize(narrow ? 24 : 30);
    }

    private void buildHourlyRow(List<HourlyWeather> hours) {
        boolean narrow = isNarrow();
        hourlyRow.removeAllViews();
        for (int i = 0; i < hours.size(); i++) {
            HourlyWeather hour = hours.get(i);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    dp(narrow ? 70 : 76), ViewGroup.LayoutParams.WRAP_CONTENT);
            columnParams.setMargins(dp(2), 0, dp(2), 0);
            column.setLayoutParams(columnParams);
            column.setPadding(0, dp(6), 0, dp(8));
            if (i == 0) {
                column.setBackgroundResource(R.drawable.bg_today_column);
            }
            if (hour.simulated) {
                // 本地模拟补出来的小时：灰显（状态行会说明），别让人当成真预报
                column.setAlpha(0.5f);
            }
            // 点逐小时区域：播报今天整天的天气
            column.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    announce(SpeechText.today(snapshot));
                }
            });

            TextView hourLabel = new TextView(this);
            hourLabel.setText(TimeText.hour12(hour.hour));
            hourLabel.setTextSize(narrow ? 13 : 14);
            hourLabel.setTextColor(getColor(R.color.text_secondary));
            hourLabel.setGravity(Gravity.CENTER);
            column.addView(hourLabel);

            TextView icon = new TextView(this);
            int iconSize = dp(narrow ? 30 : 36);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.topMargin = dp(4);
            icon.setLayoutParams(iconParams);
            icon.setGravity(Gravity.CENTER);
            QWeatherIcons.apply(icon, hour.code, hour.isNight(), iconSize);
            icon.setTextColor(getColor(QWeatherIcons.colorRes(hour.condition)));
            column.addView(icon);

            TextView temp = new TextView(this);
            String tempText = hour.temp + "°";
            temp.setText(tempText);
            temp.setTextSize(tempText.length() >= 4 ? (narrow ? 16 : 18) : (narrow ? 19 : 22));
            temp.setTextColor(getColor(R.color.warm));
            temp.setTypeface(Typeface.DEFAULT_BOLD);
            temp.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tempParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tempParams.topMargin = dp(2);
            column.addView(temp, tempParams);

            hourlyRow.addView(column);
        }
    }

    private void buildForecastRow(List<WeatherDay> forecast) {
        boolean narrow = isNarrow();
        forecastRow.removeAllViews();
        for (int i = 0; i < forecast.size(); i++) {
            WeatherDay day = forecast.get(i);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    dp(narrow ? 66 : 74), ViewGroup.LayoutParams.WRAP_CONTENT);
            int horizontalMargin = dp(3);
            columnParams.setMargins(horizontalMargin, 0, horizontalMargin, 0);
            column.setLayoutParams(columnParams);
            column.setPadding(dp(2), dp(8), dp(2), dp(10));
            column.setBackgroundResource(i == 0
                    ? R.drawable.bg_day_card_today : R.drawable.bg_day_card);
            if (day.simulated) {
                // 真实缓存不够 10 天时补出来的那几天：灰显，状态行里也会写明
                column.setAlpha(0.5f);
            }
            // 点每日预报区域：播报明天的天气预报
            column.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    announce(SpeechText.tomorrow(snapshot));
                }
            });

            TextView dayLabel = new TextView(this);
            dayLabel.setText(dayLabel(i, day.dayOfWeek));
            dayLabel.setTextSize(narrow ? 15 : 17);
            dayLabel.setTextColor(getColor(R.color.text_secondary));
            dayLabel.setGravity(Gravity.CENTER);
            column.addView(dayLabel);

            TextView icon = new TextView(this);
            int iconSize = dp(narrow ? 38 : 44);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
            iconParams.topMargin = dp(4);
            icon.setLayoutParams(iconParams);
            icon.setGravity(Gravity.CENTER);
            QWeatherIcons.apply(icon, day.code, false, iconSize);
            icon.setTextColor(getColor(QWeatherIcons.colorRes(day.condition)));
            column.addView(icon);

            TextView high = new TextView(this);
            String highText = day.high + "°";
            high.setText(highText);
            high.setTextSize(highText.length() >= 4 ? (narrow ? 19 : 21) : (narrow ? 22 : 26));
            high.setTextColor(getColor(R.color.warm));
            high.setTypeface(Typeface.DEFAULT_BOLD);
            high.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams highParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            highParams.topMargin = dp(2);
            column.addView(high, highParams);

            TextView low = new TextView(this);
            String lowText = day.low + "°";
            low.setText(lowText);
            low.setTextSize(lowText.length() >= 4 ? (narrow ? 16 : 18) : (narrow ? 18 : 21));
            low.setTextColor(getColor(R.color.cool));
            low.setGravity(Gravity.CENTER);
            column.addView(low);

            forecastRow.addView(column);
        }
    }

    private String dayLabel(int index, int isoDow) {
        if (index == 0) {
            return getString(R.string.day_today);
        }
        if (index == 1) {
            return getString(R.string.day_tomorrow);
        }
        if (index == 2) {
            return getString(R.string.day_after_tomorrow);
        }
        return WEEK_NAMES[isoDow - 1];
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
