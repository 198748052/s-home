package com.local.tianqi;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 小时预报的时间文案：统一 12 小时制。 */
public class TimeTextTest {

    @Test
    public void midnightAndNoonUseTwelve() {
        assertEquals("晚上12时", TimeText.hour12(0));
        assertEquals("中午12时", TimeText.hour12(12));
    }

    @Test
    public void periodsCoverTheWholeDay() {
        assertEquals("凌晨1时", TimeText.hour12(1));
        assertEquals("凌晨4时", TimeText.hour12(4));
        assertEquals("早上5时", TimeText.hour12(5));
        assertEquals("早上7时", TimeText.hour12(7));
        assertEquals("上午8时", TimeText.hour12(8));
        assertEquals("上午11时", TimeText.hour12(11));
        assertEquals("下午1时", TimeText.hour12(13));
        assertEquals("下午5时", TimeText.hour12(17));
        assertEquals("晚上6时", TimeText.hour12(18));
        assertEquals("晚上11时", TimeText.hour12(23));
    }

    @Test
    public void outOfRangeHourStillWorks() {
        assertEquals("上午9时", TimeText.hour12(33));
        assertEquals("晚上10时", TimeText.hour12(-2));
    }
}
