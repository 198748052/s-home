package com.local.tianqi;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends Activity {

    private static final int REQUEST_LOCATION = 1001;

    private AppSettings settings;
    private EditText etCalendarKey;
    private EditText etWeatherKey;
    private EditText etWeatherHost;
    private EditText etCity;
    private EditText etMimoKey;
    private Spinner spMimoVoice;
    private TextView tvCacheInfo;
    private TextView tvTestResult;
    private TextView tvLocationInfo;
    private Button btnTest;
    private Button btnLocate;
    private Button btnVoiceTest;
    private CheckBox cbUseLocation;
    private ScrollView settingsScroll;
    private ScrollView testResultScroll;
    private ExecutorService executor;
    private Handler mainHandler;
    private VoiceAnnouncer voice;
    private boolean testing;
    private boolean locating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = new AppSettings(this);
        etCalendarKey = findViewById(R.id.etCalendarKey);
        etWeatherKey = findViewById(R.id.etWeatherKey);
        etWeatherHost = findViewById(R.id.etWeatherHost);
        etCity = findViewById(R.id.etCity);
        etMimoKey = findViewById(R.id.etMimoKey);
        spMimoVoice = findViewById(R.id.spMimoVoice);
        tvCacheInfo = findViewById(R.id.tvCacheInfo);
        tvTestResult = findViewById(R.id.tvTestResult);
        tvLocationInfo = findViewById(R.id.tvLocationInfo);
        settingsScroll = findViewById(R.id.settingsScroll);
        testResultScroll = findViewById(R.id.testResultScroll);
        btnTest = findViewById(R.id.btnTest);
        btnLocate = findViewById(R.id.btnLocate);
        btnVoiceTest = findViewById(R.id.btnVoiceTest);
        cbUseLocation = findViewById(R.id.cbUseLocation);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        voice = new VoiceAnnouncer(this);

        etCalendarKey.setText(settings.getCalendarKey());
        etWeatherKey.setText(settings.getWeatherKey());
        etWeatherHost.setText(settings.getWeatherHost());
        etMimoKey.setText(settings.getMimoKey());
        setUpVoiceSpinner(settings.getMimoVoice());
        // 定位启用时输入框显示定位到的城市名，改掉它就会退回按城市名查询
        etCity.setText(settings.isLocationMode() && settings.hasLocation()
                ? settings.getLocationName() : settings.getCity());
        cbUseLocation.setChecked(settings.isLocationMode());
        cbUseLocation.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                settings.setLocationMode(isChecked);
                if (!isChecked) {
                    settings.clearDeviceLocation();
                }
                updateLocationInfo();
            }
        });

        findViewById(R.id.btnBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String city = etCity.getText().toString().trim();
                // 定位开着但用户改了城市名，就切回按城市名查询，否则天气还在用旧坐标
                if (settings.isLocationMode() && !city.isEmpty()
                        && !city.equals(settings.getLocationName())) {
                    settings.clearDeviceLocation();
                    cbUseLocation.setChecked(false);
                }
                settings.setLocationMode(cbUseLocation.isChecked());
                settings.save(etCalendarKey.getText().toString(),
                        etWeatherKey.getText().toString(),
                        etWeatherHost.getText().toString(),
                        city);
                settings.setMimoKey(etMimoKey.getText().toString());
                settings.setMimoVoice(selectedVoice());
                Toast.makeText(SettingsActivity.this, R.string.settings_saved,
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        });

        findViewById(R.id.btnClearCache).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new LunarService(SettingsActivity.this).clearCache();
                new WeatherService(SettingsActivity.this).clearCache();
                refreshCacheInfo();
                Toast.makeText(SettingsActivity.this, R.string.cache_cleared,
                        Toast.LENGTH_SHORT).show();
            }
        });

        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runManualTest();
            }
        });

        btnLocate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startLocate();
            }
        });

        // 试听：先按输入框里的 Key 和音色存一次，再播一句示例文案
        btnVoiceTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = etMimoKey.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(SettingsActivity.this, R.string.tts_test_need_key,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                settings.setMimoKey(key);
                settings.setMimoVoice(selectedVoice());
                voice.announce(getString(R.string.voice_sample));
            }
        });
    }

    private void setUpVoiceSpinner(String current) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, MimoTtsClient.VOICES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMimoVoice.setAdapter(adapter);
        String voice = MimoTtsClient.normalizeVoice(current);
        for (int i = 0; i < MimoTtsClient.VOICES.length; i++) {
            if (MimoTtsClient.VOICES[i].equals(voice)) {
                spMimoVoice.setSelection(i);
                return;
            }
        }
    }

    /** 下拉框里选中的音色。 */
    private String selectedVoice() {
        Object item = spMimoVoice == null ? null : spMimoVoice.getSelectedItem();
        return item == null ? MimoTtsClient.DEFAULT_VOICE : item.toString();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_LOCATION) {
            return;
        }
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) {
                granted = true;
                break;
            }
        }
        if (granted) {
            startLocate();
        } else {
            Toast.makeText(this, R.string.settings_locate_denied, Toast.LENGTH_LONG).show();
        }
    }

    /** 用设备定位取坐标，再交给 GeoAPI 反查城市名。 */
    private void startLocate() {
        if (locating) {
            return;
        }
        if (!DeviceLocation.hasPermission(this)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        locating = true;
        btnLocate.setEnabled(false);
        btnLocate.setText(R.string.settings_locating);
        DeviceLocation.request(this, new DeviceLocation.Callback() {
            @Override
            public void onLocation(double latitude, double longitude) {
                resolveLocationName(latitude, longitude);
            }

            @Override
            public void onFailure(String message) {
                finishLocate();
                Toast.makeText(SettingsActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finishLocate() {
        locating = false;
        if (btnLocate != null) {
            btnLocate.setEnabled(true);
            btnLocate.setText(R.string.settings_locate);
        }
    }

    /** GeoAPI 支持用「经度,纬度」反查最近的行政区，解析不出就用坐标本身。 */
    private void resolveLocationName(final double latitude, final double longitude) {
        final String base = QWeatherApi.baseUrl(etWeatherHost.getText().toString());
        final Map<String, String> headers =
                QWeatherApi.authHeaders(etWeatherKey.getText().toString());
        executor.execute(new Runnable() {
            @Override
            public void run() {
                QWeatherApi.Location resolved = new QWeatherApi.Location(
                        QWeatherApi.format(latitude), QWeatherApi.format(longitude),
                        QWeatherApi.format(latitude) + "," + QWeatherApi.format(longitude));
                String failure = null;
                if (!base.isEmpty() && !headers.isEmpty()) {
                    try {
                        resolved = QWeatherApi.reverseGeocode(base, headers,
                                resolved.longitude, resolved.latitude);
                    } catch (Exception e) {
                        failure = message(e);
                    }
                } else {
                    failure = "未配置 API Host 或 Key，只记录了坐标";
                }
                final QWeatherApi.Location result = resolved;
                final String failureMessage = failure;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        finishLocate();
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        etCity.setText(result.searchKey());
                        AppSettings current = new AppSettings(SettingsActivity.this);
                        // 坐标写进设置，天气直接按定位坐标查，不再拿名字去搜
                        current.saveDeviceLocation(result);
                        LocationCache.write(current, result.searchKey(), result);
                        cbUseLocation.setChecked(true);
                        updateLocationInfo();
                        String toast = failureMessage == null
                                ? getString(R.string.settings_located, result.full())
                                : getString(R.string.settings_located_offline,
                                        result.coordinateText());
                        Toast.makeText(SettingsActivity.this, toast, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCacheInfo();
        updateLocationInfo();
    }

    /** 显示当前天气用的是定位坐标还是城市名，避免定位后还以为在按城市查。 */
    private void updateLocationInfo() {
        if (tvLocationInfo == null) {
            return;
        }
        AppSettings current = new AppSettings(this);
        boolean locationMode = cbUseLocation != null
                ? cbUseLocation.isChecked() : current.isLocationMode();
        if (!locationMode) {
            tvLocationInfo.setText(R.string.settings_location_info_city);
            return;
        }
        if (!current.hasLocation()) {
            tvLocationInfo.setText(R.string.settings_location_info_no_fix);
            return;
        }
        String place = current.getLocationPlace();
        String country = current.getLocationCountry();
        String label = country.isEmpty() ? place : country + " " + place;
        tvLocationInfo.setText(getString(R.string.settings_location_info_gps, label,
                current.getLocationLatitude() + "," + current.getLocationLongitude()));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executor != null) {
            executor.shutdownNow();
        }
        if (voice != null) {
            voice.release();
        }
    }

    private void refreshCacheInfo() {
        String lunar = new LunarService(this).cacheSummary();
        String weather = new WeatherService(this).cacheSummary();
        tvCacheInfo.setText(lunar + "\n" + weather);
    }

    private void runManualTest() {
        if (testing) {
            return;
        }
        final String calendarKey = etCalendarKey.getText().toString().trim();
        final String weatherKey = etWeatherKey.getText().toString().trim();
        final String weatherHost = etWeatherHost.getText().toString().trim();
        final String city = etCity.getText().toString().trim();
        final String mimoKey = etMimoKey.getText().toString().trim();
        final String mimoVoice = selectedVoice();
        if (calendarKey.isEmpty() && weatherKey.isEmpty() && mimoKey.isEmpty()) {
            tvTestResult.setText(R.string.settings_test_need_key);
            testResultScroll.scrollTo(0, 0);
            scrollToResult();
            return;
        }

        testing = true;
        btnTest.setEnabled(false);
        btnTest.setText(R.string.settings_testing);
        tvTestResult.setText(R.string.settings_testing);
        testResultScroll.scrollTo(0, 0);
        scrollToResult();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final String result = buildTestResult(calendarKey, weatherKey, weatherHost, city,
                        mimoKey, mimoVoice);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        testing = false;
                        btnTest.setEnabled(true);
                        btnTest.setText(R.string.settings_test);
                        tvTestResult.setText(result);
                        testResultScroll.scrollTo(0, 0);
                        scrollToResult();
                    }
                });
            }
        });
    }

    private void scrollToResult() {
        if (settingsScroll == null || testResultScroll == null) {
            return;
        }
        settingsScroll.post(new Runnable() {
            @Override
            public void run() {
                settingsScroll.smoothScrollTo(0, testResultScroll.getTop());
            }
        });
    }

    private String buildTestResult(String calendarKey, String weatherKey,
                                   String weatherHost, String city,
                                   String mimoKey, String mimoVoice) {
        String location = city.isEmpty() ? "北京" : city;
        StringBuilder out = new StringBuilder();
        out.append("调用时间：").append(dateTime()).append("\n");
        out.append("城市输入：").append(location).append("\n\n");

        Calendar today = Calendar.getInstance();
        appendJuheSection(out, "万年历接口（聚合数据，未改动）", "https://v.juhe.cn/calendar/day",
                "date=" + HttpJson.encode(apiDate(today)), calendarKey);
        appendWeatherSections(out, weatherHost, weatherKey, location);
        appendTtsSection(out, mimoKey, mimoVoice);
        return out.toString();
    }

    /** 小米 MiMo 语音合成自检：只报状态和音频大小，不把 base64 塞进结果框。 */
    private void appendTtsSection(StringBuilder out, String mimoKey, String mimoVoice) {
        out.append("===== 小米 MiMo 语音合成（TTS）=====\n");
        if (mimoKey == null || mimoKey.isEmpty()) {
            out.append("未填写 MiMo Key，已跳过\n\n");
            return;
        }
        String sample = getString(R.string.voice_sample);
        out.append("模型：").append(MimoTtsClient.MODEL)
                .append("，音色：").append(mimoVoice).append("\n");
        out.append("文案：").append(sample).append("\n");
        out.append("请求：").append(MimoTtsClient.BASE_URL).append("/chat/completions\n");
        try {
            byte[] audio = MimoTtsClient.synthesize(mimoKey, mimoVoice, sample);
            out.append("状态：成功，返回 wav ").append(audio.length).append(" 字节\n\n");
        } catch (Exception e) {
            out.append("状态：失败\n").append(message(e)).append("\n\n");
        }
    }

    /** 和风天气：按定位坐标或城市名解析位置，再查实时天气 / 逐小时 / 每日预报。 */
    private void appendWeatherSections(StringBuilder out, String weatherHost,
                                       String weatherKey, String city) {
        String base = QWeatherApi.baseUrl(weatherHost);
        Map<String, String> headers = QWeatherApi.authHeaders(weatherKey);
        AppSettings current = new AppSettings(this);

        out.append("===== 和风天气（QWeather）=====\n");
        out.append("API Host：").append(base.isEmpty() ? "未填写" : base).append("\n");
        out.append("认证方式：").append(QWeatherApi.describeAuth(weatherKey)).append("\n");
        boolean locationMode = current.isLocationMode() && current.hasLocation();
        out.append("位置来源：").append(locationMode
                ? "设备定位（" + current.getLocationPlace() + "）"
                : "城市名「" + city + "」（仅限中国）").append("\n\n");
        if (base.isEmpty() || weatherKey.isEmpty()) {
            out.append("未填写 API Host 或 API Key，已跳过\n\n");
            return;
        }

        QWeatherApi.Location resolved = null;
        if (locationMode) {
            resolved = new QWeatherApi.Location(current.getLocationLatitude(),
                    current.getLocationLongitude(), current.getLocationPlace(),
                    "", "", current.getLocationCountry());
            out.append("使用定位坐标，跳过城市搜索\n\n");
        } else {
            resolved = QWeatherApi.parseCoordinates(city);
            if (resolved != null) {
                out.append("已填写经纬度，跳过城市搜索\n\n");
            } else {
                String lookupUrl = base + "/geo/v2/city/lookup?location="
                        + HttpJson.encode(city) + "&number=5&range=cn&lang=zh";
                out.append("----- 城市搜索（GeoAPI，仅限中国）-----\n");
                out.append("请求：").append(lookupUrl).append("\n");
                try {
                    JSONObject root = HttpJson.get(lookupUrl, headers);
                    out.append("状态：").append(statusOf(root)).append("\n");
                    out.append("返回内容：\n").append(pretty(root)).append("\n\n");
                    resolved = QWeatherApi.parseLookupResponse(root, city, true);
                } catch (Exception e) {
                    out.append("状态：请求失败\n").append(message(e)).append("\n\n");
                }
            }
        }
        if (resolved == null) {
            out.append("未能解析出经纬度，跳过天气接口\n\n");
            return;
        }

        out.append("查询位置：").append(resolved.full()).append("\n");
        out.append("国家/地区：").append(resolved.country.isEmpty() ? "未知" : resolved.country)
                .append("\n");
        out.append("坐标：纬度 ").append(resolved.latitude)
                .append("，经度 ").append(resolved.longitude).append("\n\n");

        appendQWeatherSection(out, "实时天气",
                QWeatherApi.weatherUrl(base, "current", resolved)
                        + "?localTime=true&lang=zh", headers);
        appendQWeatherSection(out, "逐小时天气预报",
                QWeatherApi.weatherUrl(base, "hourly", resolved)
                        + "?hours=24&localTime=true&lang=zh", headers);
        appendQWeatherSection(out, "未来 10 天预报",
                QWeatherApi.weatherUrl(base, "daily", resolved)
                        + "?days=10&localTime=true&lang=zh", headers);
    }

    private void appendJuheSection(StringBuilder out, String title, String endpoint,
                                   String query, String key) {
        out.append("===== ").append(title).append(" =====\n");
        if (key == null || key.trim().isEmpty()) {
            out.append("未填写 Key，已跳过\n\n");
            return;
        }
        out.append("请求：").append(endpoint).append("?").append(query).append("\n");
        String url = endpoint + "?" + query + "&key=" + HttpJson.encode(key.trim());
        try {
            JSONObject root = HttpJson.get(url);
            int errorCode = root.optInt("error_code", -1);
            if (errorCode == 0) {
                out.append("状态：成功\n");
            } else {
                out.append("状态：失败（error_code=").append(errorCode)
                        .append("，reason=").append(root.optString("reason")).append("）\n");
            }
            out.append("返回内容：\n").append(pretty(root)).append("\n\n");
        } catch (Exception e) {
            out.append("状态：请求失败\n").append(message(e)).append("\n\n");
        }
    }

    private void appendQWeatherSection(StringBuilder out, String title, String url,
                                       Map<String, String> headers) {
        out.append("----- ").append(title).append(" -----\n");
        out.append("请求：").append(url).append("\n");
        try {
            JSONObject root = HttpJson.get(url, headers);
            out.append("状态：").append(statusOf(root)).append("\n");
            out.append("返回内容：\n").append(pretty(root)).append("\n\n");
        } catch (Exception e) {
            out.append("状态：请求失败\n").append(message(e)).append("\n\n");
        }
    }

    /** 和风天气 v1 接口正常时不返回 code，GeoAPI 返回 code=200。 */
    private static String statusOf(JSONObject root) {
        String code = root.optString("code", "");
        if (code.isEmpty()) {
            return "成功（响应未带 code）";
        }
        return "200".equals(code) ? "成功（code=200）" : "失败（code=" + code + "）";
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isEmpty() ? e.toString() : value;
    }

    private static String pretty(JSONObject json) {
        try {
            return json.toString(2);
        } catch (Exception e) {
            return json.toString();
        }
    }

    private static String apiDate(Calendar calendar) {
        return String.format(Locale.US, "%d-%d-%d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    private static String dateTime() {
        Calendar calendar = Calendar.getInstance();
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND));
    }
}
