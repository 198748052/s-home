package com.local.tianqi;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 语音播报：优先用小米 MiMo TTS 合成，合成失败或没填 Key 时退回系统自带语音。
 * <p>
 * 合成结果按「音色 + 文案」缓存在 cacheDir，同一段话再点一次不会再请求网络。
 * 所有方法都在主线程调用；网络请求走单线程 executor，结果回主线程播放。
 */
public final class VoiceAnnouncer {

    private static final String CACHE_DIR = "tts";
    /** 缓存文件上限，超出后删最旧的，避免长期使用占满缓存目录。 */
    private static final int MAX_CACHE_FILES = 40;

    private final Context context;
    private final AppSettings settings;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** 播报代次：连续点击时用最新一次，过期的合成结果直接丢掉。 */
    private final AtomicInteger generation = new AtomicInteger();

    private MediaPlayer player;
    private TextToSpeech systemTts;
    private boolean systemTtsReady;
    private boolean systemTtsUnavailable;
    private String pendingSystemText;

    public VoiceAnnouncer(Context context) {
        this.context = context.getApplicationContext();
        this.settings = new AppSettings(context);
    }

    /** 播报一段文案；已经在播的会先停掉。 */
    public void announce(String text) {
        final String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return;
        }
        final int token = generation.incrementAndGet();
        stopPlayback();

        final String voice = ConfigDefaults.normalizeVoice(settings.getMimoVoice());
        final String speed = ConfigDefaults.normalizeSpeed(settings.getMimoSpeed());
        final String style = settings.getStyleInstruction(speed);
        // 缓存键把「模型 + 音色 + 语速 + 风格」都算进去，改任何一项都不会串音
        final File cached = cacheFile(voice, speed, style, value);
        if (cached.isFile() && cached.length() > 0) {
            if (play(cached)) {
                return;
            }
            // 缓存坏了就删掉重来
            cached.delete();
        }

        final String key = settings.getTtsKey();
        if (key.isEmpty()) {
            speakWithSystem(value, context.getString(R.string.voice_need_key));
            return;
        }

        Toast.makeText(context, R.string.voice_synthesizing, Toast.LENGTH_SHORT).show();
        executor.execute(new Runnable() {
            @Override
            public void run() {
                byte[] audio;
                String failure = null;
                try {
                    audio = TtsClient.synthesize(key, voice, style, value);
                } catch (Exception e) {
                    audio = null;
                    failure = message(e);
                }
                if (generation.get() != token) {
                    return;
                }
                final byte[] bytes = audio;
                final String failureMessage = failure;
                if (bytes != null) {
                    writeFile(cached, bytes);
                    pruneCache(cached.getParentFile());
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (generation.get() != token) {
                            return;
                        }
                        if (bytes != null && cached.isFile() && play(cached)) {
                            return;
                        }
                        String reason = failureMessage == null ? "音频无法播放" : failureMessage;
                        if (isAuthFailure(reason)) {
                            // 401/403 说明 Key 本身被拒，直接告诉用户去哪儿改
                            reason = context.getString(R.string.voice_key_invalid);
                        }
                        speakWithSystem(value, context.getString(R.string.voice_tts_failed, reason));
                    }
                });
            }
        });
    }

    /** 停止播放（含系统语音）。 */
    public void stop() {
        generation.incrementAndGet();
        stopPlayback();
    }

    /** 页面销毁时释放播放器和系统语音引擎。 */
    public void release() {
        stop();
        executor.shutdownNow();
        if (systemTts != null) {
            try {
                systemTts.shutdown();
            } catch (RuntimeException ignored) {
            }
            systemTts = null;
        }
        systemTtsReady = false;
        pendingSystemText = null;
    }

    private void stopPlayback() {
        releasePlayer();
        if (systemTts != null && systemTtsReady) {
            try {
                systemTts.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** 播放缓存的 wav；返回 false 表示这份音频放不出来。 */
    private boolean play(File file) {
        releasePlayer();
        MediaPlayer created = new MediaPlayer();
        try {
            created.setDataSource(context, Uri.fromFile(file));
            created.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    releasePlayer();
                }
            });
            created.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    releasePlayer();
                    return true;
                }
            });
            created.prepare();
            created.start();
            player = created;
            return true;
        } catch (Exception e) {
            try {
                created.release();
            } catch (RuntimeException ignored) {
            }
            return false;
        }
    }

    private void releasePlayer() {
        MediaPlayer current = player;
        player = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isPlaying()) {
                current.stop();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            current.release();
        } catch (RuntimeException ignored) {
        }
    }

    /** 兜底：用系统 TTS 念，同时用 Toast 说明为什么没走 MiMo。 */
    private void speakWithSystem(String text, String note) {
        if (note != null && !note.isEmpty()) {
            Toast.makeText(context, note, Toast.LENGTH_SHORT).show();
        }
        if (systemTtsUnavailable) {
            return;
        }
        pendingSystemText = text;
        if (systemTts == null) {
            systemTts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS) {
                        systemTtsUnavailable = true;
                        pendingSystemText = null;
                        Toast.makeText(context, R.string.voice_system_missing,
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    systemTts.setLanguage(Locale.CHINA);
                    systemTtsReady = true;
                    String waiting = pendingSystemText;
                    pendingSystemText = null;
                    if (waiting != null) {
                        speakNow(waiting);
                    }
                }
            });
            return;
        }
        if (systemTtsReady) {
            pendingSystemText = null;
            speakNow(text);
        }
    }

    private void speakNow(String text) {
        try {
            // 语速档位对系统语音同样生效
            systemTts.setSpeechRate(ConfigDefaults.systemRate(settings.getMimoSpeed()));
            systemTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tianqi-announce");
        } catch (RuntimeException e) {
            systemTtsUnavailable = true;
            Toast.makeText(context, R.string.voice_system_missing, Toast.LENGTH_LONG).show();
        }
    }

    /** 缓存文件名用「模型 + 音色 + 语速 + 风格指令 + 文案」的 SHA-1。 */
    private File cacheFile(String voice, String speed, String style, String text) {
        File dir = new File(context.getCacheDir(), CACHE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return new File(dir, "voice.wav");
        }
        String key = ConfigDefaults.TTS_MODEL + "|" + voice + "|" + speed + "|" + style
                + "|" + text;
        return new File(dir, sha1(key) + ".wav");
    }

    private static void writeFile(File target, byte[] bytes) {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        } catch (Exception ignored) {
        }
    }

    private static void pruneCache(File dir) {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_CACHE_FILES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        for (int i = 0; i < files.length - MAX_CACHE_FILES; i++) {
            files[i].delete();
        }
    }

    private static String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format(Locale.US, "%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    /** Key 被拒（401/403 或厂商返回 Invalid API Key）时给更明确的提示。 */
    private static boolean isAuthFailure(String reason) {
        if (reason == null) {
            return false;
        }
        String lower = reason.toLowerCase(java.util.Locale.US);
        return reason.contains("401") || reason.contains("403")
                || lower.contains("invalid api key") || lower.contains("unauthorized");
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.isEmpty() ? e.toString() : value;
    }
}
