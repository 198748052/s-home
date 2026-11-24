package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.concurrent.Callable;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {

    /** 首页打开就会自动拉云端 Key：默认指到本地一个拒绝连接的端口，避免测试打到线上。 */
    @Before
    public void useLocalKeysUrl() {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean(AppSettings.KEY_KEYS_URL_MIGRATED, true)
                .putString("keys_url", "http://127.0.0.1:1/api/keys")
                .commit();
    }

    /** 每次打开 App 自动拉一次云端 Key，并上报设备信息。 */
    @Test
    public void openingAppPullsCloudKeysAutomatically() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, "{"
                + "\"version\":9,"
                + "\"weather\":{\"host\":\"cloud.re.qweatherapi.com\",\"key\":\"cloud-qw\"},"
                + "\"tts\":{\"key\":\"cloud-tts\"}"
                + "}", "\"v9\"");
        try {
            Context context = RuntimeEnvironment.getApplication();
            context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                    .edit().clear()
                    .putBoolean(AppSettings.KEY_KEYS_URL_MIGRATED, true)
                    .putString("keys_url", server.baseUrl() + "/api/keys")
                    .commit();

            Robolectric.buildActivity(MainActivity.class).setup().get();

            AppSettings settings = new AppSettings(context);
            waitUntil(new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    return "cloud-qw".equals(settings.getWeatherKey());
                }
            });
            assertEquals("cloud-qw", settings.getWeatherKey());
            assertEquals("cloud-tts", settings.getTtsKey());
            assertEquals(DeviceInfo.id(context), server.header("X-Device-Id"));
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
        throw new AssertionError("等待自动拉取 Key 超时");
    }

    @Test
    public void screenRendersWithoutOverflow() throws Exception {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertContent(activity);
        View decor = measure(activity);
        View scroll = activity.findViewById(R.id.contentScroll);
        View content = ((ViewGroup) scroll).getChildAt(0);
        assertTrue("首屏内容不能超出屏幕：" + content.getHeight() + " > " + scroll.getHeight(),
                content.getHeight() <= scroll.getHeight());

        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        Bitmap bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(bitmap));
        File out = new File(System.getProperty("java.io.tmpdir"), "android_ui_screenshot.png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        assertTrue("截图未生成", out.length() > 0);
        System.out.println("SCREENSHOT=" + out.getAbsolutePath() + " size=" + out.length());
    }

    @Test
    @Config(qualifiers = "w320dp-h480dp-mdpi")
    public void narrowScreenRendersWithoutOverflow() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertContent(activity);
        measure(activity);
    }

    /** 三处语音播报入口：农历卡片、逐小时、每日预报。 */
    @Test
    public void speechZonesAreClickable() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertTrue("农历卡片应可点击播报",
                activity.findViewById(R.id.lunarCard).isClickable());

        LinearLayout hourlyRow = activity.findViewById(R.id.hourlyRow);
        LinearLayout forecastRow = activity.findViewById(R.id.forecastRow);
        assertTrue("逐小时区域应可点击播报", hourlyRow.getChildAt(0).isClickable());
        assertTrue("每日预报区域应可点击播报", forecastRow.getChildAt(0).isClickable());
    }

    @Test
    public void tappingSpeechZonesDoesNotCrash() {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        activity.findViewById(R.id.lunarCard).performClick();
        ((ViewGroup) activity.findViewById(R.id.hourlyRow)).getChildAt(0).performClick();
        ((ViewGroup) activity.findViewById(R.id.forecastRow)).getChildAt(0).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** 用户后来在设置页打开定位，回到首页要重新申请权限，而不是永远静默失效。 */
    @Test
    public void turningLocationOnLaterAsksForPermissionAgain() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().putBoolean("use_location", false).commit();

        ActivityController<MainActivity> controller =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = controller.get();
        assertNull("定位关着时不该申请权限",
                Shadows.shadowOf(activity).getLastRequestedPermission());

        // 模拟去设置页打开「使用系统定位」，再回到首页
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("use_location", true).commit();
        controller.pause().resume();

        ShadowActivity.PermissionsRequest request =
                Shadows.shadowOf(activity).getLastRequestedPermission();
        assertNotNull("打开定位开关后应重新申请权限", request);
    }

    private void assertContent(Activity activity) {
        TextView solarDate = activity.findViewById(R.id.tvSolarDate);
        TextView weekday = activity.findViewById(R.id.tvWeekday);
        TextView lunarDayNum = activity.findViewById(R.id.tvLunarDayNum);
        TextView lunarMonth = activity.findViewById(R.id.tvLunarMonth);
        TextView lunarDayName = activity.findViewById(R.id.tvLunarDayName);
        LinearLayout forecastRow = activity.findViewById(R.id.forecastRow);
        LinearLayout hourlyRow = activity.findViewById(R.id.hourlyRow);

        Calendar now = Calendar.getInstance();
        int month = now.get(Calendar.MONTH) + 1;
        int dayOfMonth = now.get(Calendar.DAY_OF_MONTH);
        LunarCalendar.LunarDate lunar = LunarCalendar.fromSolar(
                now.get(Calendar.YEAR), month, dayOfMonth);

        assertEquals(month + "月" + dayOfMonth + "日", solarDate.getText().toString());
        assertEquals(lunar.monthName(), lunarMonth.getText().toString());
        assertEquals(lunar.dayName(), lunarDayName.getText().toString());
        assertEquals(String.valueOf(lunar.day), lunarDayNum.getText().toString());
        assertTrue(weekday.getText().length() > 0);
        assertEquals(10, forecastRow.getChildCount());
        assertEquals(12, hourlyRow.getChildCount());

        // 首装没有真实数据：模拟出来的卡片灰显，状态行要说明是模拟数据
        assertEquals(0.5f, forecastRow.getChildAt(0).getAlpha(), 0.01f);
        assertEquals(0.5f, hourlyRow.getChildAt(0).getAlpha(), 0.01f);
        TextView status = activity.findViewById(R.id.tvStatus);
        assertEquals(View.VISIBLE, status.getVisibility());
        assertTrue(status.getText().toString(), status.getText().toString().contains("模拟"));
    }

    private void assertNoChildOverflow(ViewGroup row, String name) {
        for (int i = 0; i < row.getChildCount(); i++) {
            ViewGroup column = (ViewGroup) row.getChildAt(i);
            for (int j = 0; j < column.getChildCount(); j++) {
                View child = column.getChildAt(j);
                assertTrue(name + "内容溢出 col=" + i + " right=" + child.getRight()
                        + " width=" + column.getWidth(), child.getRight() <= column.getWidth());
            }
        }
    }

    private View measure(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, widthPx, heightPx);

        LinearLayout forecastRow = activity.findViewById(R.id.forecastRow);
        View lunarMonth = activity.findViewById(R.id.tvLunarMonth);
        View lunarDayNum = activity.findViewById(R.id.tvLunarDayNum);
        View lunarCard = activity.findViewById(R.id.lunarCard);

        View gateEntry = activity.findViewById(R.id.gateEntry);
        assertTrue("大门入口超出屏幕", gateEntry.getTop() >= 0 && gateEntry.getBottom() <= heightPx);

        assertTrue("月份文字溢出卡片", lunarMonth.getRight() <= lunarCard.getWidth());
        assertTrue("日期数字溢出卡片", lunarDayNum.getRight() <= lunarCard.getWidth());

        LinearLayout hourlyRow = activity.findViewById(R.id.hourlyRow);
        assertNoChildOverflow(forecastRow, "每日预报");
        assertNoChildOverflow(hourlyRow, "逐小时预报");
        return decor;
    }
}
