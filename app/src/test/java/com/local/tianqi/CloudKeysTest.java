package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

/** 云端 Key 的解析：只认几个字段，缺了就空。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CloudKeysTest {

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @Test
    public void parsesAllFourKeys() throws Exception {
        CloudKeys keys = CloudKeys.fromJson("{"
                + "\"version\":3,\"updatedAt\":\"2026-11-17T10:00:00+08:00\","
                + "\"calendar\":{\"key\":\"juhe-key\"},"
                + "\"weather\":{\"host\":\"h.re.qweatherapi.com\",\"key\":\"qw-key\"},"
                + "\"tts\":{\"key\":\"mimo-key\"}}");
        assertEquals(3, keys.version);
        assertEquals("2026-11-17T10:00:00+08:00", keys.updatedAt);
        assertEquals("juhe-key", keys.calendarKey);
        assertEquals("h.re.qweatherapi.com", keys.weatherHost);
        assertEquals("qw-key", keys.weatherKey);
        assertEquals("mimo-key", keys.ttsKey);
        assertFalse(keys.isEmpty());
    }

    @Test
    public void missingFieldsBecomeEmpty() throws Exception {
        CloudKeys keys = CloudKeys.fromJson("{}");
        assertEquals(1, keys.version);
        assertEquals("", keys.calendarKey);
        assertEquals("", keys.weatherKey);
        assertTrue("一个 Key 都没有", keys.isEmpty());
    }

    @Test
    public void whitespaceIsTrimmed() throws Exception {
        CloudKeys keys = CloudKeys.fromJson(
                "{\"weather\":{\"host\":\"  h.example.com  \",\"key\":\" k \"}}");
        assertEquals("h.example.com", keys.weatherHost);
        assertEquals("k", keys.weatherKey);
    }

    @Test
    public void invalidJsonIsRejected() {
        try {
            CloudKeys.fromJson("not json");
            fail("非法 JSON 应该抛异常");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("JSON"));
        }
        try {
            CloudKeys.fromJson("   ");
            fail("空内容应该抛异常");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("空"));
        }
    }
}
