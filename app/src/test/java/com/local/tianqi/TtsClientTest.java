package com.local.tianqi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Base64;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;

/** 语音合成客户端（协议写死为小米 MiMo）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TtsClientTest {

    @Test
    public void requestPutsTextInAssistantMessage() throws Exception {
        String style = ConfigDefaults.style(ConfigDefaults.SPEED_NORMAL);
        JSONObject body = TtsClient.buildRequest("今天多云，最高12度。", "茉莉", style);

        assertEquals("mimo-v2.5-tts", body.getString("model"));
        assertEquals("user",
                body.getJSONArray("messages").getJSONObject(0).getString("role"));
        assertEquals(style,
                body.getJSONArray("messages").getJSONObject(0).getString("content"));
        assertEquals("assistant",
                body.getJSONArray("messages").getJSONObject(1).getString("role"));
        assertEquals("今天多云，最高12度。",
                body.getJSONArray("messages").getJSONObject(1).getString("content"));
        assertEquals("wav", body.getJSONObject("audio").getString("format"));
        assertEquals("茉莉", body.getJSONObject("audio").getString("voice"));
    }

    @Test
    public void blankVoiceFallsBackToDefault() throws Exception {
        JSONObject body = TtsClient.buildRequest("测试", "  ", "");
        assertEquals(ConfigDefaults.DEFAULT_VOICE,
                body.getJSONObject("audio").getString("voice"));
    }

    @Test
    public void endpointIsHardcoded() {
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions", TtsClient.endpoint());
    }

    /** 语速靠 user 消息里的自然语言指令控制。 */
    @Test
    public void speedIsExpressedInStyleInstruction() {
        assertTrue(ConfigDefaults.style(ConfigDefaults.SPEED_SLOW).contains("语速偏慢"));
        assertTrue(ConfigDefaults.style(ConfigDefaults.SPEED_NORMAL).contains("语速适中"));
        assertTrue(ConfigDefaults.style(ConfigDefaults.SPEED_FAST).contains("语速偏快"));
    }

    @Test
    public void speedDefaultsToNormalAndMapsToSystemRate() {
        assertEquals(ConfigDefaults.SPEED_NORMAL, ConfigDefaults.normalizeSpeed(null));
        assertEquals(ConfigDefaults.SPEED_NORMAL, ConfigDefaults.normalizeSpeed("turbo"));
        assertEquals(0.8f, ConfigDefaults.systemRate("slow"), 0.001);
        assertEquals(1.0f, ConfigDefaults.systemRate("normal"), 0.001);
        assertEquals(1.25f, ConfigDefaults.systemRate("fast"), 0.001);
        assertEquals(0, ConfigDefaults.speedIndex("slow"));
        assertEquals(ConfigDefaults.SPEED_NORMAL, ConfigDefaults.speedAt(-1));
        assertEquals(ConfigDefaults.SPEEDS.length, ConfigDefaults.speedLabels().length);
    }

    @Test
    public void audioIsDecodedFromChoicesMessage() throws Exception {
        byte[] wav = {0x52, 0x49, 0x46, 0x46, 0x00, 0x01};
        String data = Base64.encodeToString(wav, Base64.NO_WRAP);
        JSONObject root = new JSONObject().put("choices", new org.json.JSONArray()
                .put(new JSONObject().put("message", new JSONObject()
                        .put("audio", new JSONObject().put("data", data)))));

        assertArrayEquals(wav, TtsClient.parseAudio(root));
    }

    @Test
    public void missingAudioReportsReadableFailure() throws Exception {
        try {
            TtsClient.parseAudio(new JSONObject().put("choices", new org.json.JSONArray()));
            fail("没有音频时应该抛异常");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("音频"));
        }
    }

    @Test
    public void apiErrorIsSurfaced() throws Exception {
        JSONObject root = new JSONObject().put("error",
                new JSONObject().put("message", "invalid api key"));
        try {
            TtsClient.parseAudio(root);
            fail("接口报错时应该抛异常");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("invalid api key"));
        }
    }

    @Test
    public void emptyKeyIsRejectedBeforeRequest() {
        try {
            TtsClient.synthesize("  ", "冰糖", "", "今天多云。");
            fail("没有 Key 时不该发请求");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Key"));
        }
        try {
            TtsClient.synthesize("key", "冰糖", "", "   ");
            fail("没有文本时不该发请求");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("内容"));
        }
    }

}
