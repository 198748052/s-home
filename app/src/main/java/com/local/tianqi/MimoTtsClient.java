package com.local.tianqi;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小米 MiMo 语音合成（MiMo-V2.5-TTS）客户端。
 * <p>
 * 接口与 OpenAI 兼容：POST {base}/chat/completions，模型 {@code mimo-v2.5-tts}。
 * 要念的文本必须放在 {@code role=assistant} 的消息里，{@code role=user} 只用来下风格指令；
 * 音频在响应里以 base64 返回：{@code choices[0].message.audio.data}。
 * 文档见 mimo-docs/audio/speech-synthesis-v2.5.md。
 */
final class MimoTtsClient {

    static final String BASE_URL = "https://api.xiaomimimo.com/v1";
    static final String MODEL = "mimo-v2.5-tts";
    static final String DEFAULT_VOICE = "冰糖";
    /** 预置音色，见文档「预置音色列表」。 */
    static final String[] VOICES = {"冰糖", "茉莉", "苏打", "白桦"};

    /** 自然语言风格指令，放在 user 消息里；不会出现在合成的语音中。 */
    private static final String STYLE = "请用清晰自然、语速适中的播报语气朗读，像天气预报播音员一样。";
    /** 语音合成要等模型推理，读超时给到 30 秒。 */
    private static final int READ_TIMEOUT_MS = 30000;

    private MimoTtsClient() {
    }

    /** 组装请求体：模型、风格指令、要朗读的文本、音频格式与音色。 */
    static JSONObject buildRequest(String text, String voice) throws JSONException {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "user").put("content", STYLE));
        messages.put(new JSONObject()
                .put("role", "assistant")
                .put("content", text == null ? "" : text));
        return new JSONObject()
                .put("model", MODEL)
                .put("messages", messages)
                .put("audio", new JSONObject()
                        .put("format", "wav")
                        .put("voice", normalizeVoice(voice)));
    }

    static String normalizeVoice(String voice) {
        String value = voice == null ? "" : voice.trim();
        return value.isEmpty() ? DEFAULT_VOICE : value;
    }

    /**
     * 合成一段语音，返回 wav 字节。非流式调用，一次拿完整音频，直接落盘交给播放器。
     */
    static byte[] synthesize(String key, String voice, String text) throws IOException {
        return synthesize(key, voice, text, BASE_URL);
    }

    /** 同 {@link #synthesize(String, String, String)}，基地址可替换，方便本地假接口测试。 */
    static byte[] synthesize(String key, String voice, String text, String baseUrl)
            throws IOException {
        String token = key == null ? "" : key.trim();
        if (token.isEmpty()) {
            throw new IOException("未填写 MiMo TTS Key");
        }
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new IOException("没有可播报的内容");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        JSONObject body;
        try {
            body = buildRequest(content, voice);
        } catch (JSONException e) {
            throw new IOException("请求内容不合法", e);
        }
        JSONObject root = HttpJson.postJson(baseUrl + "/chat/completions", headers,
                body.toString(), READ_TIMEOUT_MS);
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
        if (bytes == null || bytes.length == 0) {
            throw new IOException("语音合成返回的音频为空");
        }
        return bytes;
    }
}
