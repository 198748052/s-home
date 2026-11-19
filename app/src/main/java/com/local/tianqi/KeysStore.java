package com.local.tianqi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 云端 Key：从服务器拉一份只含 Key 的 JSON，缓存到本地并**写进设置页那几个输入框**。
 * <p>
 * 策略：
 * <ul>
 *   <li>打开 App 先用本地缓存（永不过期）请求数据，不等网络</li>
 *   <li>后台再拉一次；60 秒内不重复拉</li>
 *   <li>拉不到就一直用旧的，不弹错</li>
 * </ul>
 */
public final class KeysStore {

    public interface Callback {
        /**
         * @param keys    当前可用的 Key（失败时是旧值，不会为 null）
         * @param changed 这次是否真的换了内容，界面据此决定要不要重新取数
         * @param status  给用户看的一句话结果
         */
        void onKeys(CloudKeys keys, boolean changed, String status);
    }

    private static final String KEY_JSON = "cloud_keys_json";
    private static final String KEY_ETAG = "cloud_keys_etag";
    /** 上次拉取时间；测试里要手动把它拨回去，所以不是 private。 */
    static final String KEY_FETCHED_AT = "cloud_keys_fetched_at";

    private final Context context;
    private final AppSettings settings;
    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private CloudKeys cached;
    private long cachedParsedAt = -1L;
    private boolean appliedToLocal;

    public KeysStore(Context context, AppSettings settings) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.preferences = settings.preferences();
    }

    /** 当前可用的 Key：有缓存就用缓存，否则空。 */
    public CloudKeys current() {
        String raw = preferences.getString(KEY_JSON, "");
        if (raw == null || raw.trim().isEmpty()) {
            return CloudKeys.empty();
        }
        long fetchedAt = preferences.getLong(KEY_FETCHED_AT, 0L);
        if (cached != null && cachedParsedAt == fetchedAt) {
            return cached;
        }
        try {
            cached = CloudKeys.fromJson(raw);
        } catch (IOException e) {
            cached = CloudKeys.empty();
        }
        cachedParsedAt = fetchedAt;
        // 升级上来的用户：缓存里有 Key，但输入框还空着，补写一次
        if (!appliedToLocal) {
            appliedToLocal = true;
            settings.applyCloudKeys(cached);
        }
        return cached;
    }

    public long fetchedAt() {
        return preferences.getLong(KEY_FETCHED_AT, 0L);
    }

    /** 刚同步过就跳过，避免一次启动里反复请求；缓存本身永不过期。 */
    public boolean isFresh() {
        long fetchedAt = fetchedAt();
        return fetchedAt > 0
                && System.currentTimeMillis() - fetchedAt
                < ConfigDefaults.KEYS_REFRESH_THROTTLE_MS;
    }

    public boolean hasCache() {
        String raw = preferences.getString(KEY_JSON, "");
        return raw != null && !raw.trim().isEmpty();
    }

    /** 取 Key 的地址：设置页可覆盖，默认取编译进 App 的地址。 */
    public String url() {
        String value = settings.getKeysUrl().trim();
        return normalizeUrl(value.isEmpty() ? ConfigDefaults.KEYS_URL : value);
    }

    /** 只填域名时自动补 /api/keys，省得用户记路径。 */
    static String normalizeUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        String path;
        try {
            java.net.URL parsed = new java.net.URL(value);
            path = parsed.getPath() == null ? "" : parsed.getPath().trim();
        } catch (Exception e) {
            return value;
        }
        if (path.isEmpty() || "/".equals(path)) {
            while (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            return value + "/api/keys";
        }
        return value;
    }

    /** 拉一次 Key。{@code force=false} 时刚拉过就跳过。 */
    public void refresh(boolean force, final Callback callback) {
        if (executor.isShutdown()) {
            // 页面已经销毁：别再排队
            callback.onKeys(current(), false, context.getString(R.string.keys_fresh));
            return;
        }
        final String target = url();
        if (target.isEmpty()) {
            callback.onKeys(current(), false, context.getString(R.string.keys_no_url));
            return;
        }
        if (!force && isFresh()) {
            callback.onKeys(current(), false, context.getString(R.string.keys_fresh));
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            callback.onKeys(current(), false, context.getString(R.string.keys_busy));
            return;
        }

        // 手动同步（force）时不带 If-None-Match，强制服务器回完整内容，
        // 避免「304 但其实内容变了」这种情况把新 Key 挡住
        final String etag = force ? "" : preferences.getString(KEY_ETAG, "");
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Result result = fetchOnce(target, etag);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing.set(false);
                        callback.onKeys(result.keys, result.changed, result.status);
                    }
                });
            }
        });
    }

    private Result fetchOnce(String target, String etag) {
        try {
            HttpJson.Fetch response = HttpJson.fetch(target, headers(etag));
            if (response.isNotModified()) {
                touch();
                return new Result(current(), false, context.getString(R.string.keys_unchanged));
            }
            if (response.isSuccess()) {
                CloudKeys parsed = CloudKeys.fromJson(response.body);
                String previous = preferences.getString(KEY_JSON, "");
                boolean changed = !response.body.trim().equals(previous.trim());
                save(response.body, response.etag, parsed);
                appliedToLocal = true;
                // 关键：直接写进设置页那几个输入框
                settings.applyCloudKeys(parsed);
                return new Result(parsed, changed,
                        context.getString(R.string.keys_updated, parsed.version));
            }
            return new Result(current(), false, describeHttpError(response.status));
        } catch (Exception e) {
            return new Result(current(), false,
                    context.getString(R.string.keys_failed, explain(e)));
        }
    }

    private String describeHttpError(int status) {
        if (status == 401 || status == 403) {
            return context.getString(R.string.keys_http_401);
        }
        if (status == 404) {
            return context.getString(R.string.keys_http_404);
        }
        return context.getString(R.string.keys_http_error, status);
    }

    /** 把网络异常翻译成能照着修的一句话。 */
    static String explain(Exception e) {
        String raw = e.getMessage() == null ? e.toString() : e.getMessage();
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("cleartext")) {
            return "系统不允许明文 HTTP，请把地址改成 https://（或在服务端配上证书）";
        }
        if (lower.contains("unable to resolve host") || e instanceof java.net.UnknownHostException) {
            return "域名解析失败，检查地址拼写和 DNS";
        }
        if (lower.contains("econnrefused") || lower.contains("connection refused")) {
            return "连接被拒绝：服务没启动，或端口/反代没配对";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时：端口没放行，或服务器防火墙挡了";
        }
        if (lower.contains("econnreset") || lower.contains("connection reset")) {
            return "连接被重置：多半是反代没配好或 HTTPS/HTTP 协议不匹配";
        }
        if (lower.contains("certificate") || lower.contains("trust anchor")) {
            return "HTTPS 证书有问题（自签名或域名不匹配）";
        }
        return raw;
    }

    /** 页面销毁时调用：停掉常驻线程，丢掉还没回到主线程的回调。 */
    public void release() {
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    /** 清掉缓存，同时清掉由云端写入的本地 Key（用户自己填的保留）。 */
    public void clear() {
        preferences.edit()
                .remove(KEY_JSON)
                .remove(KEY_ETAG)
                .remove(KEY_FETCHED_AT)
                .apply();
        settings.clearCloudKeys();
        cached = null;
        cachedParsedAt = -1L;
        appliedToLocal = false;
    }

    /** 设置页展示用的一句话摘要。 */
    public String summary() {
        if (!hasCache()) {
            return context.getString(R.string.keys_source_none);
        }
        CloudKeys keys = current();
        return context.getString(R.string.keys_summary, keys.version, timeOf(fetchedAt()));
    }

    private Map<String, String> headers(String etag) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!etag.isEmpty()) {
            headers.put("If-None-Match", etag);
        }
        String token = settings.getKeysToken().trim();
        if (!token.isEmpty()) {
            headers.put("X-Config-Token", token);
        }
        return headers;
    }

    private void save(String raw, String etag, CloudKeys keys) {
        preferences.edit()
                .putString(KEY_JSON, raw)
                .putString(KEY_ETAG, etag == null ? "" : etag)
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply();
        cached = keys;
        cachedParsedAt = preferences.getLong(KEY_FETCHED_AT, 0L);
    }

    private void touch() {
        preferences.edit()
                .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                .apply();
        cachedParsedAt = preferences.getLong(KEY_FETCHED_AT, 0L);
    }

    private static String timeOf(long timestamp) {
        if (timestamp <= 0) {
            return "-";
        }
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.US, "%04d-%02d-%02d %02d:%02d",
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH) + 1,
                calendar.get(java.util.Calendar.DAY_OF_MONTH),
                calendar.get(java.util.Calendar.HOUR_OF_DAY),
                calendar.get(java.util.Calendar.MINUTE));
    }

    private static final class Result {
        final CloudKeys keys;
        final boolean changed;
        final String status;

        Result(CloudKeys keys, boolean changed, String status) {
            this.keys = keys;
            this.changed = changed;
            this.status = status;
        }
    }
}
