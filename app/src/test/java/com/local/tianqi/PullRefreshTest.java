package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 下拉刷新：拉到阈值才触发，收起时提示条归零。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w360dp-h640dp-xhdpi")
public class PullRefreshTest {

    private Activity activity;
    private PullRefreshScrollView scroll;
    private int triggered;

    private void setUpActivity() {
        activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        scroll = activity.findViewById(R.id.contentScroll);
        triggered = 0;
        scroll.setOnRefreshListener(new PullRefreshScrollView.OnRefreshListener() {
            @Override
            public void onRefresh() {
                triggered++;
            }
        });
        layout();
    }

    private void layout() {
        View decor = activity.getWindow().getDecorView();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    @Test
    public void longPullTriggersRefresh() {
        setUpActivity();
        dragDown(scroll.getHeight() * 0.6f);
        assertEquals("拉过阈值应触发一次刷新", 1, triggered);
        assertTrue(scroll.isRefreshing());
    }

    @Test
    public void shortPullDoesNotTrigger() {
        setUpActivity();
        dragDown(20f);
        assertEquals("没拉到阈值不该刷新", 0, triggered);
        assertFalse(scroll.isRefreshing());
    }

    @Test
    public void finishingRefreshHidesHeader() {
        setUpActivity();
        dragDown(scroll.getHeight() * 0.6f);
        scroll.setRefreshing(false);
        assertFalse(scroll.isRefreshing());
        TextView header = activity.findViewById(R.id.tvRefreshHint);
        assertEquals("提示条应收回", 0, header.getLayoutParams().height);
    }

    private void dragDown(float distance) {
        long start = SystemClock.uptimeMillis();
        float x = scroll.getWidth() / 2f;
        float y = 40f;
        send(scroll, start, start, MotionEvent.ACTION_DOWN, x, y);
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            send(scroll, start, start + i * 16L, MotionEvent.ACTION_MOVE,
                    x, y + distance * i / steps);
        }
        send(scroll, start, start + steps * 16L + 16L, MotionEvent.ACTION_UP,
                x, y + distance);
    }

    private static void send(View target, long downTime, long eventTime, int action,
                             float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
    }
}
