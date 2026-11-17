package com.local.tianqi;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class HttpJson {

    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 10000;

    private HttpJson() {
    }

    static JSONObject get(String url) throws IOException {
        return get(url, null);
    }

    static JSONObject get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                throw new IOException("HTTP " + status + ": " + body);
            }
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException("响应不是合法 JSON", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 取一次原始响应：状态码、响应体、ETag。云端配置要靠 304 判断「没更新」，
     * 所以这里不对非 2xx 抛异常，交给调用方决定怎么处理。
     */
    static Fetch fetch(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

            int status = connection.getResponseCode();
            String etag = connection.getHeaderField("ETag");
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return new Fetch(status, "", etag);
            }
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            return new Fetch(status, readAll(stream), etag);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** {@link #fetch} 的结果。 */
    static final class Fetch {
        final int status;
        final String body;
        final String etag;

        Fetch(int status, String body, String etag) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.etag = etag == null ? "" : etag;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        boolean isNotModified() {
            return status == 304;
        }
    }
    /**
     * POST 一段 JSON，拿回原始响应（字节 + Content-Type）。
     * 语音合成有的厂商回 JSON（音频在 base64 字段里），有的直接回音频字节，
     * 所以这里不假设响应类型。
     */
    static Raw postRaw(String url, Map<String, String> headers, String body, int readTimeoutMs)
            throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeoutMs > 0 ? readTimeoutMs : READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json, audio/*");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    if (header.getKey() != null && header.getValue() != null) {
                        connection.setRequestProperty(header.getKey(), header.getValue());
                    }
                }
            }

            byte[] payload = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            return new Raw(status, connection.getContentType(), readAllBytes(stream));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** {@link #postRaw} 的结果：状态码、Content-Type、原始字节。 */
    static final class Raw {
        final int status;
        final String contentType;
        final byte[] bytes;

        Raw(int status, String contentType, byte[] bytes) {
            this.status = status;
            this.contentType = contentType == null ? "" : contentType;
            this.bytes = bytes == null ? new byte[0] : bytes;
        }

        boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        String text() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /** POST 一段 JSON 并解析响应（语音合成用，返回 JSON）。 */
    static JSONObject postJson(String url, Map<String, String> headers, String body,
                               int readTimeoutMs) throws IOException {
        Raw response = postRaw(url, headers, body, readTimeoutMs);
        if (!response.isSuccess()) {
            throw new IOException("HTTP " + response.status + "：" + briefError(response));
        }
        try {
            return new JSONObject(response.text());
        } catch (org.json.JSONException e) {
            throw new IOException("响应不是合法 JSON", e);
        }
    }

    /** 错误响应优先取 error.message，其次截断原文。 */
    private static String briefError(Raw response) {
        String text = response.text();
        try {
            JSONObject json = new JSONObject(text);
            JSONObject error = json.optJSONObject("error");
            if (error != null && !error.optString("message").isEmpty()) {
                return error.optString("message");
            }
        } catch (org.json.JSONException ignored) {
        }
        if (response.status == 401 || response.status == 403) {
            return "鉴权失败，请检查 Key";
        }
        String value = text.replace('\n', ' ').trim();
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return value == null ? "" : value;
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    /** 二进制安全地读完响应体：语音合成返回的是音频字节，不能按文本读。 */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        if (stream == null) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
