package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;

import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.concurrent.Callable;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsActivityTest {

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void manualTestWithoutKeyShowsHint() {
        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        activity.findViewById(R.id.btnTest).performClick();

        TextView result = activity.findViewById(R.id.tvTestResult);
        assertEquals(activity.getString(R.string.settings_test_need_key),
                result.getText().toString());
        saveScreenshot(activity);
    }

    /** 在设置页勾上「使用系统定位」时就申请权限，别等回到首页才发现没权限。 */
    @Test
    public void enablingLocationRequestsPermission() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("use_location", false).commit();

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        CheckBox box = activity.findViewById(R.id.cbUseLocation);
        assertFalse(box.isChecked());

        box.setChecked(true);

        ShadowActivity.PermissionsRequest request =
                Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull("勾选定位后应立刻申请权限", request);
        assertTrue(Arrays.asList(request.requestedPermissions)
                .contains(Manifest.permission.ACCESS_FINE_LOCATION));
    }

    /** 拒绝权限后开关要回到「关」，否则设置页会一直说「打开首页会自动定位」。 */
    @Test
    public void denyingPermissionUnchecksLocation() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("use_location", false).commit();

        SettingsActivity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        CheckBox box = activity.findViewById(R.id.cbUseLocation);
        box.setChecked(true);
        assertTrue(box.isChecked());

        // 与 SettingsActivity.REQUEST_LOCATION 保持一致
        activity.onRequestPermissionsResult(1001,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                new int[]{PackageManager.PERMISSION_DENIED});

        assertFalse("拒绝后开关应回到关闭", box.isChecked());
        assertFalse(new AppSettings(context).isLocationMode());
    }

    @Test
    public void editingCityTurnsOffDeviceLocation() {
        Context context = RuntimeEnvironment.getApplication();
        AppSettings settings = new AppSettings(context);
        settings.saveDeviceLocation(new QWeatherApi.Location(
                "34.80", "114.31", "龙亭", "河南省", "开封市", "中国"));

        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        EditText city = activity.findViewById(R.id.etCity);
        assertEquals("龙亭", city.getText().toString());

        city.setText("上海");
        activity.findViewById(R.id.btnSave).performClick();

        assertFalse("改了城市名就该退出定位模式", new AppSettings(context).isLocationMode());
        assertEquals("上海", new AppSettings(context).getCity());
    }

    @Test
    public void savingUnchangedCityKeepsDeviceLocation() {
        Context context = RuntimeEnvironment.getApplication();
        AppSettings settings = new AppSettings(context);
        settings.saveDeviceLocation(new QWeatherApi.Location(
                "34.80", "114.31", "龙亭", "河南省", "开封市", "中国"));

        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        activity.findViewById(R.id.btnSave).performClick();

        assertTrue("没改城市名应保留定位", new AppSettings(context).isLocationMode());
    }

    /** 语音播报设置：Key、音色、语速都要能存下来，试听按钮要能滚到。 */
    @Test
    public void voiceSettingsAreSavedAndReachable() {
        Context context = RuntimeEnvironment.getApplication();
        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        EditText key = activity.findViewById(R.id.etMimoKey);
        Spinner voice = activity.findViewById(R.id.spMimoVoice);
        Spinner speed = activity.findViewById(R.id.spMimoSpeed);
        assertEquals(ConfigDefaults.VOICES.length, voice.getAdapter().getCount());
        assertEquals(ConfigDefaults.SPEEDS.length, speed.getAdapter().getCount());
        assertEquals("默认语速应为正常", 1, speed.getSelectedItemPosition());

        key.setText(" mimo-test-key ");
        voice.setSelection(1);
        speed.setSelection(2);
        activity.findViewById(R.id.btnSave).performClick();

        AppSettings saved = new AppSettings(context);
        assertEquals("mimo-test-key", saved.getTtsKey());
        assertEquals(ConfigDefaults.VOICES[1], saved.getMimoVoice());
        assertEquals(ConfigDefaults.SPEED_FAST, saved.getMimoSpeed());

        // 重新进入设置页，音色与语速应回显到刚才选中的档位
        Activity again = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("mimo-test-key",
                ((EditText) again.findViewById(R.id.etMimoKey)).getText().toString());
        assertEquals(1, ((Spinner) again.findViewById(R.id.spMimoVoice)).getSelectedItemPosition());
        assertEquals(2, ((Spinner) again.findViewById(R.id.spMimoSpeed)).getSelectedItemPosition());

        saveVoiceScreenshot(again);
    }

    /** 设置页点「立即同步配置」应该把云端值拉下来并显示来源。 */
    @Test
    public void syncConfigButtonAppliesCloudValues() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        StubHttpServer server = StubHttpServer.start(200, "{"
                + "\"version\":5,"
                + "\"weather\":{\"host\":\"cloud.re.qweatherapi.com\",\"key\":\"cloud-key\"},"
                + "\"tts\":{\"key\":\"cloud-tts\"}"
                + "}", "\"v5\"");
        try {
            Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
            EditText url = activity.findViewById(R.id.etConfigUrl);
            url.setText(server.baseUrl() + "/api/config");
            EditText token = activity.findViewById(R.id.etConfigToken);
            token.setText("page-token");
            activity.findViewById(R.id.btnConfigSync).performClick();

            TextView info = activity.findViewById(R.id.tvConfigInfo);
            waitUntil(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return info.getText().toString().contains("v5");
                }
            });

            AppSettings settings = new AppSettings(context);
            assertEquals("cloud-key", settings.getWeatherKey());
            assertEquals("cloud.re.qweatherapi.com", settings.getWeatherHost());
            assertEquals("cloud-tts", settings.getTtsKey());
            // 令牌从设置页读，并真的带到请求头
            assertEquals("page-token", settings.getKeysToken());
            assertEquals("page-token", server.header("X-Config-Token"));
            // 云端值应该直接写进本地输入框，用户看到的就是当前在用的
            EditText weatherKey = activity.findViewById(R.id.etWeatherKey);
            EditText weatherHost = activity.findViewById(R.id.etWeatherHost);
            assertEquals("cloud-key", weatherKey.getText().toString());
            assertEquals("cloud.re.qweatherapi.com", weatherHost.getText().toString());
            // 音色列表写死在 App 里，云端只下发 Key
            Spinner voice = activity.findViewById(R.id.spMimoVoice);
            assertEquals(ConfigDefaults.VOICES.length, voice.getAdapter().getCount());
        } finally {
            server.close();
        }
    }

    private static void waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.call()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("等待同步回调超时");
    }

    /** 把设置页滚到语音播报区域再截图，确认这一块能正常显示。 */
    private void saveVoiceScreenshot(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        layout(decor, widthPx, heightPx);

        View button = activity.findViewById(R.id.btnVoiceTest);
        ScrollView page = activity.findViewById(R.id.settingsScroll);
        page.scrollTo(0, Math.max(0, button.getTop() - 460));
        layout(decor, widthPx, heightPx);

        assertTrue("试听按钮应可见", button.isShown());
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File out = new File(System.getProperty("java.io.tmpdir"),
                "android_settings_voice_screenshot.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void layout(View decor, int widthPx, int heightPx) {
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, widthPx, heightPx);
    }

    private void saveScreenshot(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, widthPx, heightPx);
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File out = new File(System.getProperty("java.io.tmpdir"), "android_settings_screenshot.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
