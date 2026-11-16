package com.local.tianqi;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * 嵌在外层 ScrollView 里使用的固定高度滚动区域。
 * <p>
 * 原生 ScrollView 嵌套时，外层会在手指移动超过 touch slop 时抢先拦截竖直手势，
 * 内层收不到拖动事件，表现为「内容只显示前几行、怎么拖都不动」。
 * 这里在自身还有可滚动距离时禁止外层拦截，滚到边界后再把手势交还给外层页面。
 */
public class InnerScrollView extends ScrollView {

    private float lastY;

    public InnerScrollView(Context context) {
        super(context);
    }

    public InnerScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public InnerScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        holdGesture(ev);
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        holdGesture(ev);
        return super.onTouchEvent(ev);
    }

    private void holdGesture(MotionEvent ev) {
        ViewParent parent = getParent();
        if (parent == null) {
            return;
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastY = ev.getY();
                // 内容本身不超出可视区域时，竖直滑动留给外层页面
                parent.requestDisallowInterceptTouchEvent(canScrollEitherWay());
                break;
            case MotionEvent.ACTION_MOVE: {
                float y = ev.getY();
                float delta = y - lastY;
                lastY = y;
                if (delta == 0) {
                    return;
                }
                // 手指上移（delta<0）表示要继续往下看内容
                boolean hasRoom = delta < 0 ? canScrollVertically(1) : canScrollVertically(-1);
                parent.requestDisallowInterceptTouchEvent(hasRoom);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                parent.requestDisallowInterceptTouchEvent(false);
                break;
            default:
                break;
        }
    }

    private boolean canScrollEitherWay() {
        return canScrollVertically(-1) || canScrollVertically(1);
    }
}
