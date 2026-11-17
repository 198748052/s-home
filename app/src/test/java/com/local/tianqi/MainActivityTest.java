package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainActivityTest {

    @Test
    public void screenRendersWithoutOverflow() throws Exception {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertContent(activity);
        View decor = measure(activity);
        View scroll = activity.findViewById(R.id.contentScroll);
        assertTrue("首屏内容需要滚动", ((ViewGroup) scroll).getChildAt(0).getHeight()
                <= scroll.getHeight());

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
