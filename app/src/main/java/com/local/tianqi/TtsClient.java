package com.local.tianqi;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小米 MiMo 语音合成客户端（协议写死在这里）。
 * <p>
 * POST {base}/chat/completions，模型 mimo-v2.5-tts：要念的文本放 {@code assistant} 消息，
 * 风格指令（含语速）放 {@code user} 消息；音频在 {@code choices[0].message.audio.data} 里，
 * 是 base64 的 wav。文档见 mimo-docs/audio/speech-synthesis-v2.5.md。
 */
final class TtsClient {

    /** 语音合成要等模型推理，读超时给到 30 秒。 */
    private static final int READ_TIMEOUT_MS = 30000;

    private TtsClient() {
    }

    /** 组装请求体：模型、风格指令、要朗读的文本、音频格式与音色。 */
    static JSONObject buildRequest(String text, String voice, String styleInstruction)
            throws JSONException {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user")
                .put("content", styleInstruction == null ? "" : styleInstruction));
        messages.put(new JSONObject()
                .put("role", "assistant")
                .put("content", text == null ? "" : text));
        return new JSONObject()
                .put("model", ConfigDefaults.TTS_MODEL)
                .put("messages", messages)
                .put("audio", new JSONObject()
                        .put("format", ConfigDefaults.TTS_FORMAT)
                        .put("voice", ConfigDefaults.normalizeVoice(voice)));
    }

    /** 拼接口地址：base + /chat/completions。 */
    static String endpoint() {
        String base = ConfigDefaults.TTS_BASE_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + ConfigDefaults.TTS_PATH;
    }

    /** 合成一段语音，返回 wav 字节。 */
    static byte[] synthesize(String key, String voice, String styleInstruction, String text)
            throws IOException {
        String token = key == null ? "" : key.trim();
        if (token.isEmpty()) {
            throw new IOException("未填写语音合成 Key");
        }
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new IOException("没有可播报的内容");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        JSONObject body;
        try {
            body = buildRequest(content, voice, styleInstruction);
        } catch (JSONException e) {
            throw new IOException("请求内容不合法", e);
        }
        JSONObject root = HttpJson.postJson(endpoint(), headers, body.toString(), READ_TIMEOUT_MS);
        return parseAudio(root);
    }

    /** 从响应里取出 base64 音频并解码。 */
    static byte[] parseAudio(JSONObject root) throws IOException {
        if (root == null) {
            throw new IOException("语音合成没有返回内容");
        }
        JSONObject error = root.optJSONObject("error");
        if (error != null) {
            throw new IOException("语音合成失败：" + error.optString("message", "未知错误"));
        }
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new IOException("语音合成没有返回音频");
        }
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        JSONObject audio = message == null ? null : message.optJSONObject("audio");
        String data = audio == null ? "" : audio.optString("data", "");
        if (data.isEmpty()) {
            throw new IOException("语音合成没有返回音频");
        }
        byte[] bytes;
        try {
            bytes = Base64.decode(data, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("音频数据无法解码", e);
        }
        if (bytes.length == 0) {
            throw new IOException("语音合成返回的音频为空");
        }
        return bytes;
    }
}
