package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;

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

    /** 语音播报设置：Key 与音色要能存下来，试听按钮要能滚到。 */
    @Test
    public void voiceSettingsAreSavedAndReachable() {
        Context context = RuntimeEnvironment.getApplication();
        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();

        EditText key = activity.findViewById(R.id.etMimoKey);
        Spinner voice = activity.findViewById(R.id.spMimoVoice);
        assertEquals(MimoTtsClient.VOICES.length, voice.getAdapter().getCount());

        key.setText(" mimo-test-key ");
        voice.setSelection(1);
        activity.findViewById(R.id.btnSave).performClick();

        AppSettings saved = new AppSettings(context);
        assertEquals("mimo-test-key", saved.getMimoKey());
        assertEquals(MimoTtsClient.VOICES[1], saved.getMimoVoice());

        // 重新进入设置页，音色应回显到刚才选中的那一项
        Activity again = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        assertEquals("mimo-test-key",
                ((EditText) again.findViewById(R.id.etMimoKey)).getText().toString());
        assertEquals(1, ((Spinner) again.findViewById(R.id.spMimoVoice)).getSelectedItemPosition());

        saveVoiceScreenshot(again);
    }

    /** 把设置页滚到语音播报区域再截图，确认这一块能正常显示。 */
    private void saveVoiceScreenshot(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        layout(decor, widthPx, heightPx);

        View button = activity.findViewById(R.id.btnVoiceTest);
        ScrollView page = activity.findViewById(R.id.settingsScroll);
        page.scrollTo(0, Math.max(0, button.getTop() - 260));
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
