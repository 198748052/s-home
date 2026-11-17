package com.local.tianqi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** MiMo 语音合成请求体与响应解析。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MimoTtsClientTest {

    @Test
    public void requestPutsTextInAssistantMessage() throws Exception {
        JSONObject body = MimoTtsClient.buildRequest("今天多云，最高12度。", "茉莉");

        assertEquals("mimo-v2.5-tts", body.getString("model"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(2, messages.length());
        // 风格指令放 user，要念的文本必须放 assistant
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals("assistant", messages.getJSONObject(1).getString("role"));
        assertEquals("今天多云，最高12度。",
                messages.getJSONObject(1).getString("content"));

        JSONObject audio = body.getJSONObject("audio");
        assertEquals("wav", audio.getString("format"));
        assertEquals("茉莉", audio.getString("voice"));
    }

    @Test
    public void blankVoiceFallsBackToDefault() throws Exception {
        JSONObject body = MimoTtsClient.buildRequest("测试", "  ");
        assertEquals(MimoTtsClient.DEFAULT_VOICE,
                body.getJSONObject("audio").getString("voice"));
        assertEquals("冰糖", MimoTtsClient.normalizeVoice(null));
    }

    @Test
    public void audioIsDecodedFromChoicesMessage() throws Exception {
        byte[] wav = {0x52, 0x49, 0x46, 0x46, 0x00, 0x01};
        String data = Base64.encodeToString(wav, Base64.NO_WRAP);
        JSONObject root = new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject()
                                .put("audio", new JSONObject().put("data", data)))));

        byte[] decoded = MimoTtsClient.parseAudio(root);
        assertEquals(wav.length, decoded.length);
        for (int i = 0; i < wav.length; i++) {
            assertEquals(wav[i], decoded[i]);
        }
    }

    @Test
    public void missingAudioReportsReadableFailure() throws Exception {
        try {
            MimoTtsClient.parseAudio(new JSONObject().put("choices", new JSONArray()));
            fail("没有音频时应该抛异常");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("音频"));
        }
    }

    @Test
    public void apiErrorIsSurfaced() throws Exception {
        JSONObject root = new JSONObject().put("error",
                new JSONObject().put("message", "invalid api key"));
        try {
            MimoTtsClient.parseAudio(root);
            fail("接口报错时应该抛异常");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("invalid api key"));
        }
    }

    @Test
    public void emptyKeyIsRejectedBeforeRequest() {
        try {
            MimoTtsClient.synthesize("  ", "冰糖", "今天多云。");
            fail("没有 Key 时不该发请求");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Key"));
        }
    }

    /** 起一个本地假接口，验证请求头、请求体和音频解码整条链路。 */
    @Test
    public void synthesizePostsRequestAndReturnsAudio() throws Exception {
        byte[] wav = tinyWav();
        String payload = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\""
                + Base64.encodeToString(wav, Base64.NO_WRAP) + "\"}}}]}";
        StubServer server = new StubServer(200, payload);
        try {
            byte[] audio = MimoTtsClient.synthesize("key-123", "苏打",
                    "明天小雨，最高9度。", server.baseUrl());

            assertArrayEquals(wav, audio);
            assertEquals("Bearer key-123", server.header("Authorization"));
            assertTrue(server.header("Content-Type").startsWith("application/json"));
            assertEquals("POST /chat/completions HTTP/1.1", server.requestLine());

            JSONObject body = new JSONObject(server.requestBody());
            assertEquals("mimo-v2.5-tts", body.getString("model"));
            assertEquals("明天小雨，最高9度。",
                    body.getJSONArray("messages").getJSONObject(1).getString("content"));
            assertEquals("苏打", body.getJSONObject("audio").getString("voice"));
            assertEquals("wav", body.getJSONObject("audio").getString("format"));
        } finally {
            server.close();
        }
    }

    @Test
    public void httpErrorSurfacesApiMessage() throws Exception {
        StubServer server = new StubServer(429,
                "{\"error\":{\"message\":\"Rate limit exceeded\",\"type\":\"rate_limit\"}}");
        try {
            MimoTtsClient.synthesize("busy-key", "冰糖", "今天多云。", server.baseUrl());
            fail("接口报错时应该抛异常");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Rate limit exceeded"));
            assertTrue(e.getMessage(), e.getMessage().contains("429"));
        } finally {
            server.close();
        }
    }

    /** 401 的响应体在部分运行时会被丢掉，至少要给出能看懂的鉴权提示。 */
    @Test
    public void unauthorizedReportsReadableMessage() throws Exception {
        StubServer server = new StubServer(401,
                "{\"error\":{\"message\":\"Invalid API Key\",\"type\":\"invalid_key\"}}");
        try {
            MimoTtsClient.synthesize("bad-key", "冰糖", "今天多云。", server.baseUrl());
            fail("401 时应该抛异常");
        } catch (IOException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("401"));
            assertTrue(message, message.contains("Invalid API Key") || message.contains("Key"));
        } finally {
            server.close();
        }
    }

    /** 44 字节 wav 头 + 几个字节数据，够验证音频字节透传。 */
    private static byte[] tinyWav() {
        byte[] header = new byte[44];
        header[0] = 'R';
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        byte[] data = new byte[8];
        byte[] wav = new byte[header.length + data.length];
        System.arraycopy(header, 0, wav, 0, header.length);
        System.arraycopy(data, 0, wav, header.length, data.length);
        return wav;
    }

    /**
     * 只应答一次的极简 HTTP 服务。
     * android.jar 里没有 com.sun.net.httpserver，所以直接用 ServerSocket 手写。
     */
    private static final class StubServer implements Closeable {
        private final ServerSocket socket;
        private final int status;
        private final String responseBody;
        private final AtomicReference<String> requestLine = new AtomicReference<>();
        private final AtomicReference<String> headers = new AtomicReference<>("");
        private final AtomicReference<String> body = new AtomicReference<>("");

        StubServer(int status, String responseBody) throws IOException {
            this.status = status;
            this.responseBody = responseBody;
            socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        String requestLine() {
            return requestLine.get();
        }

        String requestBody() {
            return body.get();
        }

        /** 取某个请求头，没有则返回空串。 */
        String header(String name) {
            for (String line : headers.get().split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return "";
        }

        private void serve() {
            try (Socket client = socket.accept()) {
                InputStream in = client.getInputStream();
                ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
                int matched = 0;
                while (matched < 4) {
                    int read = in.read();
                    if (read < 0) {
                        return;
                    }
                    headerBuffer.write(read);
                    byte[] tail = headerBuffer.toByteArray();
                    int length = tail.length;
                    boolean crlf = length >= 4 && tail[length - 4] == '\r' && tail[length - 3] == '\n'
                            && tail[length - 2] == '\r' && tail[length - 1] == '\n';
                    matched = crlf ? 4 : 0;
                }
                String rawHeaders = headerBuffer.toString("UTF-8");
                int lineEnd = rawHeaders.indexOf("\r\n");
                requestLine.set(lineEnd > 0 ? rawHeaders.substring(0, lineEnd) : rawHeaders);
                headers.set(rawHeaders);

                int contentLength = 0;
                String length = header("Content-Length");
                if (!length.isEmpty()) {
                    contentLength = Integer.parseInt(length);
                }
                byte[] payload = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int read = in.read(payload, offset, contentLength - offset);
                    if (read < 0) {
                        break;
                    }
                    offset += read;
                }
                body.set(new String(payload, 0, offset, StandardCharsets.UTF_8));

                byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
                OutputStream out = client.getOutputStream();
                String head = "HTTP/1.1 " + status + " OK\r\n"
                        + "Content-Type: application/json\r\n"
                        + "Content-Length: " + response.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8));
                out.write(response);
                out.flush();
            } catch (Exception ignored) {
                // 客户端提前断开时不用处理
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
