package com.local.tianqi;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用的极简 HTTP 服务：android.jar 里没有 com.sun.net.httpserver，
 * 所以直接用 ServerSocket 手写。支持固定状态码/响应体/ETag（命中 If-None-Match 回 304）。
 */
final class StubHttpServer implements Closeable {

    private final ServerSocket socket;
    private final int status;
    private final byte[] body;
    private final String contentType;
    private final String etag;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> requestLines = new ArrayList<>();
    private final List<String> headers = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    private StubHttpServer(int status, byte[] body, String contentType, String etag)
            throws IOException {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        this.contentType = contentType;
        this.etag = etag;
        socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                serve();
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    static StubHttpServer start(int status, String body) throws IOException {
        return new StubHttpServer(status, body == null ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8), "application/json", null);
    }

    static StubHttpServer start(int status, String body, String etag) throws IOException {
        return new StubHttpServer(status, body == null ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8), "application/json", etag);
    }

    /** 原样返回二进制响应，用于 /audio/speech 这类直接回音频字节的接口。 */
    static StubHttpServer startBytes(int status, byte[] body, String contentType)
            throws IOException {
        return new StubHttpServer(status, body, contentType, null);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + socket.getLocalPort();
    }

    int requestCount() {
        return requests.get();
    }

    synchronized String requestLine() {
        return requestLines.isEmpty() ? "" : requestLines.get(requestLines.size() - 1);
    }

    synchronized String requestBody() {
        return bodies.isEmpty() ? "" : bodies.get(bodies.size() - 1);
    }

    /** 取最近一次请求的某个头，没有则返回空串。 */
    synchronized String header(String name) {
        if (headers.isEmpty()) {
            return "";
        }
        String raw = headers.get(headers.size() - 1);
        for (String line : raw.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                handle(client);
            } catch (IOException ignored) {
                return;
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        String rawHeaders = readHeaders(in);
        if (rawHeaders.isEmpty()) {
            return;
        }
        synchronized (this) {
            requests.incrementAndGet();
            headers.add(rawHeaders);
            int lineEnd = rawHeaders.indexOf("\r\n");
            requestLines.add(lineEnd > 0 ? rawHeaders.substring(0, lineEnd) : rawHeaders);
        }

        int contentLength = 0;
        String length = headerOf(rawHeaders, "Content-Length");
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
        synchronized (this) {
            bodies.add(new String(payload, 0, offset, StandardCharsets.UTF_8));
        }

        boolean notModified = etag != null
                && etag.equals(headerOf(rawHeaders, "If-None-Match"));
        OutputStream out = client.getOutputStream();
        if (notModified) {
            out.write(("HTTP/1.1 304 Not Modified\r\n"
                    + (etag == null ? "" : "ETag: " + etag + "\r\n")
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            byte[] response = body;
            StringBuilder head = new StringBuilder()
                    .append("HTTP/1.1 ").append(status).append(" Status\r\n")
                    .append("Content-Type: ").append(contentType).append("\r\n")
                    .append("Content-Length: ").append(response.length).append("\r\n");
            if (etag != null) {
                head.append("ETag: ").append(etag).append("\r\n");
            }
            head.append("Connection: close\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(response);
        }
        out.flush();
    }

    /** 读到空行为止，返回整段请求头（含请求行）。 */
    private static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int read = in.read();
            if (read < 0) {
                return "";
            }
            buffer.write(read);
            byte[] tail = buffer.toByteArray();
            int length = tail.length;
            boolean crlf = length >= 4 && tail[length - 4] == '\r' && tail[length - 3] == '\n'
                    && tail[length - 2] == '\r' && tail[length - 1] == '\n';
            matched = crlf ? 4 : 0;
        }
        return buffer.toString("UTF-8");
    }

    private static String headerOf(String rawHeaders, String name) {
        for (String line : rawHeaders.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return "";
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
