package com.local.tianqi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.concurrent.Callable;

/**
 * 云端 Key 的拉取与落地：拉下来直接写进本地那几个输入框，拉不到就用旧的（永不过期）。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class KeysStoreTest {

    private static final String KEYS_JSON = "{"
            + "\"version\":5,"
            + "\"updatedAt\":\"2026-11-17T10:00:00+08:00\","
            + "\"calendar\":{\"key\":\"cloud-juhe\"},"
            + "\"weather\":{\"host\":\"cloud.re.qweatherapi.com\",\"key\":\"cloud-qw\"},"
            + "\"tts\":{\"key\":\"cloud-tts\"}"
            + "}";

    private Context context;
    private AppSettings settings;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit();
        settings = new AppSettings(context);
    }

    @Test
    public void withoutManualUrlUsesBuiltInAddress() {
        assertEquals("http://home.topsygf.cn/api/keys", settings.keysStore().url());
    }

    @Test
    public void failedFetchWithoutCacheKeepsEmptyKeys() {
        settings.setKeysUrl("http://127.0.0.1:1/api/keys");
        Result result = refresh(settings, true);
        assertFalse(result.changed);
        assertTrue(result.keys.isEmpty());
        assertEquals("", settings.getWeatherKey());
    }

    /** 旧版本手填的地址升级后清掉一次，改用内置地址；之后手填的仍然生效。 */
    @Test
    public void legacyManualUrlIsReplacedOnce() {
        context.getSharedPreferences("tianqi_prefs", Context.MODE_PRIVATE)
                .edit().clear()
                .putString("keys_url", "http://old.example.com/api/keys")
                .commit();

        AppSettings upgraded = new AppSettings(context);
        assertEquals("", upgraded.getKeysUrl());
        assertEquals("http://home.topsygf.cn/api/keys", upgraded.keysStore().url());

        upgraded.setKeysUrl("http://custom.example.com/api/keys");
        assertEquals("http://custom.example.com/api/keys", upgraded.keysStore().url());
    }

    @Test
    public void fetchWritesKeysIntoLocalFields() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            Result result = refresh(settings, true);

            assertTrue("首次拉取算变化", result.changed);
            assertTrue(result.status, result.status.contains("v5"));

            // 关键：云端 Key 直接落到本地那几个输入框
            assertEquals("cloud-juhe", settings.getCalendarKey());
            assertEquals("cloud.re.qweatherapi.com", settings.getWeatherHost());
            assertEquals("cloud-qw", settings.getWeatherKey());
            assertEquals("cloud-tts", settings.getTtsKey());
            assertTrue(settings.isFromCloud(AppSettings.KEY_WEATHER));
        } finally {
            server.close();
        }
    }

    @Test
    public void secondFetchSendsEtagAndAccepts304() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);

            // 60 秒节流会把第二次请求挡在本地，这里把它拨回去，模拟「过一会儿再启动」
            settings.preferences().edit().putLong(KeysStore.KEY_FETCHED_AT, 0L).commit();

            Result second = refresh(settings, false);
            assertFalse("304 不算变化", second.changed);
            assertEquals("\"v5\"", server.header("If-None-Match"));
            assertEquals("cloud-qw", settings.getWeatherKey());
        } finally {
            server.close();
        }
    }

    /** 手动同步（force）故意不带 If-None-Match，强制服务端回完整内容。 */
    @Test
    public void forcedRefreshSkipsEtagOnPurpose() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);

            Result second = refresh(settings, true);
            assertFalse("内容没变就不算变化", second.changed);
            assertEquals("", server.header("If-None-Match"));
            assertEquals("cloud-qw", settings.getWeatherKey());
        } finally {
            server.close();
        }
    }

    @Test
    public void freshCacheSkipsNetwork() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);
            refresh(settings, false);
            Thread.sleep(120);
            assertEquals(1, server.requestCount());
        } finally {
            server.close();
        }
    }

    /** 拉取失败时继续用本地已有的 Key，不报错清空。 */
    @Test
    public void failureKeepsExistingKeys() throws Exception {
        StubHttpServer good = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        StubHttpServer bad = StubHttpServer.start(500, "{\"error\":\"boom\"}");
        try {
            settings.setKeysUrl(good.baseUrl() + "/api/keys");
            refresh(settings, true);
            assertEquals("cloud-qw", settings.getWeatherKey());

            settings.setKeysUrl(bad.baseUrl() + "/api/keys");
            Result result = refresh(settings, true);
            assertFalse(result.changed);
            assertTrue(result.status, result.status.contains("500"));
            assertEquals("失败后仍用旧 Key", "cloud-qw", settings.getWeatherKey());
        } finally {
            good.close();
            bad.close();
        }
    }

    /** 云端清空某个 Key 时，之前同步下来的也要清掉。 */
    @Test
    public void clearingCloudKeyClearsSyncedLocalValue() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);

            String cleared = KEYS_JSON.replace("\"cloud-qw\"", "\"\"")
                    .replace("\"version\":5", "\"version\":6");
            StubHttpServer empty = StubHttpServer.start(200, cleared);
            try {
                settings.setKeysUrl(empty.baseUrl() + "/api/keys");
                refresh(settings, true);
                assertEquals("", settings.getWeatherKey());
            } finally {
                empty.close();
            }
        } finally {
            server.close();
        }
    }

    /** 用户手填的 Key 不会被云端空值清掉。 */
    @Test
    public void userKeySurvivesEmptyCloudValue() throws Exception {
        settings.save("my-juhe", "my-qw", "my.host", "上海");
        StubHttpServer server = StubHttpServer.start(200,
                "{\"version\":1,\"calendar\":{\"key\":\"\"},\"weather\":{\"host\":\"\",\"key\":\"\"}}");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);
            assertEquals("my-juhe", settings.getCalendarKey());
            assertEquals("my-qw", settings.getWeatherKey());
            assertEquals("my.host", settings.getWeatherHost());
        } finally {
            server.close();
        }
    }

    @Test
    public void clearDropsCacheAndSyncedKeys() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);
            assertTrue(settings.keysStore().hasCache());

            settings.keysStore().clear();
            assertFalse(settings.keysStore().hasCache());
            assertEquals("", settings.getWeatherKey());
            assertEquals("", settings.getTtsKey());
        } finally {
            server.close();
        }
    }

    @Test
    public void urlWithoutPathGetsApiKeysAppended() {
        assertEquals("https://tq.example.com/api/keys",
                KeysStore.normalizeUrl("https://tq.example.com"));
        assertEquals("https://tq.example.com/api/keys",
                KeysStore.normalizeUrl("https://tq.example.com/"));
        assertEquals("http://10.0.0.1:8787/api/keys",
                KeysStore.normalizeUrl("http://10.0.0.1:8787"));
        assertEquals("https://tq.example.com/api/keys",
                KeysStore.normalizeUrl("https://tq.example.com/api/keys"));
        assertEquals("", KeysStore.normalizeUrl(""));
    }

    /** 服务端不再校验令牌，改为靠设备信息统计对接设备。 */
    @Test
    public void deviceInfoIsSentAsHeaders() throws Exception {
        StubHttpServer server = StubHttpServer.start(200, KEYS_JSON, "\"v5\"");
        try {
            settings.setKeysUrl(server.baseUrl() + "/api/keys");
            refresh(settings, true);
            assertEquals(DeviceInfo.id(context), server.header("X-Device-Id"));
            assertEquals(DeviceInfo.name(), server.header("X-Device-Name"));
            assertEquals(DeviceInfo.version(context), server.header("X-App-Version"));
            assertEquals("", server.header("X-Config-Token"));
        } finally {
            server.close();
        }
    }

    /** release() 之后还有同步请求（销毁瞬间的点击），要同步回结果而不是抛异常。 */
    @Test
    public void refreshAfterReleaseStillAnswers() {
        KeysStore store = settings.keysStore();
        store.release();

        Result result = refresh(settings, true);
        assertFalse(result.changed);
    }

    @Test
    public void networkErrorsAreTranslatedToActionableHints() {
        assertTrue(KeysStore.explain(new java.io.IOException(
                "Cleartext HTTP traffic to home.example.com not permitted")).contains("https://"));
        assertTrue(KeysStore.explain(new java.io.IOException("connect timed out"))
                .contains("超时"));
        assertTrue(KeysStore.explain(new java.io.IOException("Connection refused"))
                .contains("拒绝"));
    }

    /** 发一次刷新并等回调跑完（回调在主线程，需要驱动 Looper）。 */
    private static Result refresh(AppSettings settings, boolean force) {
        final Result result = new Result();
        settings.keysStore().refresh(force, new KeysStore.Callback() {
            @Override
            public void onKeys(CloudKeys keys, boolean changed, String status) {
                result.keys = keys;
                result.changed = changed;
                result.status = status;
                result.done = true;
            }
        });
        waitUntil(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                return result.done;
            }
        });
        return result;
    }

    private static void waitUntil(Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try {
                if (condition.call()) {
                    return;
                }
                Thread.sleep(20);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("等待 Key 回调超时");
    }

    private static final class Result {
        CloudKeys keys;
        boolean changed;
        String status = "";
        boolean done;
    }
}
