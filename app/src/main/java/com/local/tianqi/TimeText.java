package com.local.tianqi;

import java.util.Locale;

/** 时间文案。小时预报统一用 12 小时制，老年人更容易看懂。 */
final class TimeText {

    private TimeText() {
    }

    /** 把 0-23 点转成「上午9时」「下午3时」「晚上11时」这样的 12 小时制。 */
    static String hour12(int hour) {
        int value = ((hour % 24) + 24) % 24;
        String period;
        int display;
        if (value == 0) {
            period = "晚上";
            display = 12;
        } else if (value < 5) {
            period = "凌晨";
            display = value;
        } else if (value < 8) {
            period = "早上";
            display = value;
        } else if (value < 12) {
            period = "上午";
            display = value;
        } else if (value == 12) {
            period = "中午";
            display = 12;
        } else if (value < 18) {
            period = "下午";
            display = value - 12;
        } else {
            period = "晚上";
            display = value - 12;
        }
        return String.format(Locale.US, "%s%d时", period, display);
    }
}
