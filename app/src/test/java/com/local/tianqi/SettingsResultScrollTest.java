package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * 设置页「接口返回内容」区域必须能独立滚动。
 * 返回内容很长，若只能靠外层页面滚动，后面的行就看不到了。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
public class SettingsResultScrollTest {

    @Test
    public void resultBoxScrollsOnDrag() {
        Activity activity = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        TextView result = activity.findViewById(R.id.tvTestResult);
        ScrollView box = activity.findViewById(R.id.testResultScroll);
        ScrollView page = activity.findViewById(R.id.settingsScroll);

        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 120; i++) {
            text.append("第 ").append(i).append(" 行返回内容\n");
        }
        result.setText(text.toString());

        View decor = activity.getWindow().getDecorView();
        int widthPx = activity.getResources().getDisplayMetrics().widthPixels;
        int heightPx = activity.getResources().getDisplayMetrics().heightPixels;
        layout(decor, widthPx, heightPx);

        assertTrue("内容应比可视区域长", result.getHeight() > box.getHeight());
        assertEquals(0, box.getScrollY());

        // 结果框在页面下方，先把外层滚到它露出来的位置，否则手势落在视口之外
        page.scrollTo(0, Math.max(0, box.getTop() - 40));
        layout(decor, widthPx, heightPx);

        int[] origin = absolutePosition(box);
        assertTrue("结果框应位于可视区域内", origin[1] >= 0
                && origin[1] + box.getHeight() <= heightPx);

        float centerX = origin[0] + box.getWidth() / 2f;
        drag(decor, centerX, origin[1] + box.getHeight() - 24f, origin[1] + 24f);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("在返回内容区域上向上拖动后应滚动，实际 scrollY=" + box.getScrollY(),
                box.getScrollY() > 0);
    }

    private static void layout(View decor, int widthPx, int heightPx) {
        decor.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, widthPx, heightPx);
    }

    private static void drag(View target, float x, float fromY, float toY) {
        long start = SystemClock.uptimeMillis();
        send(target, start, start, MotionEvent.ACTION_DOWN, x, fromY);

        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            float y = fromY + (toY - fromY) * i / steps;
            send(target, start, start + i * 16L, MotionEvent.ACTION_MOVE, x, y);
        }

        send(target, start, start + steps * 16L, MotionEvent.ACTION_UP, x, toY);
    }

    private static void send(View target, long downTime, long eventTime, int action,
                             float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
    }

    private static int[] absolutePosition(View view) {
        int x = 0;
        int y = 0;
        View cursor = view;
        while (true) {
            x += cursor.getLeft() - cursor.getScrollX();
            y += cursor.getTop() - cursor.getScrollY();
            if (!(cursor.getParent() instanceof ViewGroup)) {
                break;
            }
            cursor = (View) cursor.getParent();
        }
        return new int[]{x, y};
    }
}
