package com.local.tianqi;

import org.json.JSONObject;

import java.io.IOException;

/**
 * 云端下发的 Key 集合。这是现在唯一会从服务器更新的东西：
 * 万年历 Key、和风天气 Host + Key、语音合成 Key。
 * <p>
 * 接口地址与字段解析都写死在 App 里，所以这份数据只有几个字符串。
 */
public final class CloudKeys {

    public final int version;
    public final String updatedAt;
    public final String calendarKey;
    public final String weatherHost;
    public final String weatherKey;
    public final String ttsKey;

    CloudKeys(int version, String updatedAt, String calendarKey, String weatherHost,
              String weatherKey, String ttsKey) {
        this.version = version;
        this.updatedAt = updatedAt == null ? "" : updatedAt;
        this.calendarKey = calendarKey == null ? "" : calendarKey;
        this.weatherHost = weatherHost == null ? "" : weatherHost;
        this.weatherKey = weatherKey == null ? "" : weatherKey;
        this.ttsKey = ttsKey == null ? "" : ttsKey;
    }

    /** 空集合（还没同步过）。 */
    static CloudKeys empty() {
        return new CloudKeys(0, "", "", "", "", "");
    }

    /** 解析服务端返回的 JSON；不是合法对象时抛异常，调用方保留旧 Key。 */
    static CloudKeys fromJson(String raw) throws IOException {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IOException("内容为空");
        }
        JSONObject root;
        try {
            root = new JSONObject(raw);
        } catch (Exception e) {
            throw new IOException("不是合法 JSON", e);
        }
        JSONObject calendar = root.optJSONObject("calendar");
        JSONObject weather = root.optJSONObject("weather");
        JSONObject tts = root.optJSONObject("tts");
        return new CloudKeys(
                root.optInt("version", 1),
                root.optString("updatedAt", ""),
                text(calendar, "key"),
                text(weather, "host"),
                text(weather, "key"),
                text(tts, "key"));
    }

    private static String text(JSONObject node, String name) {
        return node == null ? "" : node.optString(name, "").trim();
    }

    /** 一个 Key 都没有时说明服务端还没配。 */
    boolean isEmpty() {
        return calendarKey.isEmpty() && weatherHost.isEmpty()
                && weatherKey.isEmpty() && ttsKey.isEmpty();
    }
}
