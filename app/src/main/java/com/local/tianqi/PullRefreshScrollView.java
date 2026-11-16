package com.local.tianqi;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 支持下拉刷新的 ScrollView。
 * <p>
 * 内容滚到顶部后继续下拉，顶部提示条会被拉出来；松手超过阈值就回调刷新。
 * 自己实现而不是引入 androidx.swiperefreshlayout，是为了让 APK 保持无第三方运行时依赖。
 */
public class PullRefreshScrollView extends ScrollView {

    public interface OnRefreshListener {
        void onRefresh();
    }

    /** 触发刷新需要的下拉距离。 */
    private static final int TRIGGER_DP = 64;
    /** 刷新中提示条保持的高度。 */
    private static final int HOLD_DP = 48;
    /** 下拉阻尼：手指移动 2dp 只拉出 1dp。 */
    private static final float DRAG_RATE = 0.5f;
    /** 最多能拉出的高度，比阈值多一点手感余量。 */
    private static final int MAX_EXTRA_DP = 24;

    private final int touchSlop;
    private final int triggerPx;
    private final int holdPx;
    private final int maxPullPx;

    private View header;
    private OnRefreshListener listener;

    private float startY;
    private float startX;
    private boolean pulling;
    private boolean refreshing;
    private int pullPx;

    public PullRefreshScrollView(Context context) {
        this(context, null);
    }

    public PullRefreshScrollView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PullRefreshScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        triggerPx = dp(TRIGGER_DP);
        holdPx = dp(HOLD_DP);
        maxPullPx = triggerPx + dp(MAX_EXTRA_DP);
    }

    /** 顶部提示条，放在内容布局的第一个位置。 */
    public void setRefreshHeader(View header) {
        this.header = header;
        setHeaderHeight(0);
    }

    public void setOnRefreshListener(OnRefreshListener listener) {
        this.listener = listener;
    }

    public boolean isRefreshing() {
        return refreshing;
    }

    /** 刷新结束调用，收起提示条。 */
    public void setRefreshing(boolean value) {
        if (refreshing == value) {
            return;
        }
        refreshing = value;
        if (refreshing) {
            setHeaderHeight(holdPx);
            setHeaderText(R.string.pull_refreshing);
        } else {
            setHeaderHeight(0);
            setHeaderText(R.string.pull_refresh_hint);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // 有子控件吃掉按下事件时，这里负责把下拉手势抢过来
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getY();
                startX = ev.getX();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (shouldStartPull(ev)) {
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        // 没有子控件消费事件时，事件直接落到这里，同样要能识别下拉
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = ev.getY();
                startX = ev.getX();
                pulling = false;
                break;
            case MotionEvent.ACTION_MOVE:
                shouldStartPull(ev);
                if (pulling) {
                    updatePull(ev.getY() - startY);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pulling) {
                    pulling = false;
                    finishPull();
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(ev);
    }

    /** 判定是否进入下拉状态，并把起点重置到当前位置，避免算进触发拦截的那段位移。 */
    private boolean shouldStartPull(MotionEvent ev) {
        if (pulling || refreshing || getScrollY() > 0) {
            return false;
        }
        float dy = ev.getY() - startY;
        float dx = Math.abs(ev.getX() - startX);
        if (dy > touchSlop && dy > dx) {
            pulling = true;
            startY = ev.getY();
            return true;
        }
        return false;
    }

    private void updatePull(float delta) {
        int value = Math.max(0, Math.round(delta * DRAG_RATE));
        pullPx = Math.min(value, maxPullPx);
        setHeaderHeight(pullPx);
        setHeaderText(pullPx >= triggerPx
                ? R.string.pull_refresh_release : R.string.pull_refresh_hint);
    }

    private void finishPull() {
        if (pullPx >= triggerPx && listener != null) {
            refreshing = true;
            setHeaderHeight(holdPx);
            setHeaderText(R.string.pull_refreshing);
            listener.onRefresh();
        } else {
            setHeaderHeight(0);
            setHeaderText(R.string.pull_refresh_hint);
        }
        pullPx = 0;
    }

    private void setHeaderHeight(int height) {
        if (header == null) {
            return;
        }
        ViewGroup.LayoutParams params = header.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            header.setLayoutParams(params);
        }
        header.setVisibility(height > 0 ? View.VISIBLE : View.GONE);
    }

    private void setHeaderText(int resId) {
        if (header instanceof TextView) {
            ((TextView) header).setText(resId);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
