package com.local.tianqi;

/**
 * 写死在 App 里的常量：接口地址、字段位置、TTS 协议参数。
 * <p>
 * 云端只下发 **Key**（见 {@link KeysStore}），接口和解析都不再走服务端配置，
 * 换厂商就需要改这里并发版——这是刻意的取舍：逻辑简单、出错面小。
 */
public final class ConfigDefaults {

    /** 云端取 Key 的地址，例如 https://tq.example.com/api/keys。留空则只用本地手填。 */
    static final String KEYS_URL = "";
    /** 与服务端 server.json 的 token 一致；留空表示服务端没开令牌校验。 */
    static final String KEYS_TOKEN = "";
    /** 两次拉取之间的最短间隔；Key 缓存永不过期，这个只防止一次启动里重复请求。 */
    static final long KEYS_REFRESH_THROTTLE_MS = 60L * 1000L;

    /** 聚合数据万年历接口（写死）。 */
    static final String CALENDAR_URL = "https://v.juhe.cn/calendar/day";

    /** 和风天气 v1 路径（写死），{lat} {lon} 会被替换。 */
    static final String WEATHER_CURRENT_PATH = "/weather/v1/current/{lat}/{lon}?localTime=true&lang=zh";
    static final String WEATHER_HOURLY_PATH =
            "/weather/v1/hourly/{lat}/{lon}?hours={hours}&localTime=true&lang=zh";
    static final String WEATHER_DAILY_PATH =
            "/weather/v1/daily/{lat}/{lon}?days={days}&localTime=true&lang=zh";

    /** 小米 MiMo 语音合成（写死）。 */
    static final String TTS_BASE_URL = "https://api.xiaomimimo.com/v1";
    static final String TTS_PATH = "/chat/completions";
    static final String TTS_MODEL = "mimo-v2.5-tts";
    static final String TTS_FORMAT = "wav";
    static final String DEFAULT_VOICE = "冰糖";
    static final String[] VOICES = {"冰糖", "茉莉", "苏打", "白桦"};

    /** 语速档位：用自然语言指令控制，接口没有数值参数。 */
    static final String SPEED_SLOW = "slow";
    static final String SPEED_NORMAL = "normal";
    static final String SPEED_FAST = "fast";
    static final String[] SPEEDS = {SPEED_SLOW, SPEED_NORMAL, SPEED_FAST};

    private ConfigDefaults() {
    }

    static String normalizeVoice(String voice) {
        String value = voice == null ? "" : voice.trim();
        return value.isEmpty() ? DEFAULT_VOICE : value;
    }

    static String normalizeSpeed(String speed) {
        String value = speed == null ? "" : speed.trim();
        for (String item : SPEEDS) {
            if (item.equals(value)) {
                return item;
            }
        }
        return SPEED_NORMAL;
    }

    /** 下拉框里显示的中文档位名。 */
    static String[] speedLabels() {
        return new String[]{"慢一点", "正常", "快一点"};
    }

    static int speedIndex(String speed) {
        String value = normalizeSpeed(speed);
        for (int i = 0; i < SPEEDS.length; i++) {
            if (SPEEDS[i].equals(value)) {
                return i;
            }
        }
        return 1;
    }

    static String speedAt(int index) {
        return index >= 0 && index < SPEEDS.length ? SPEEDS[index] : SPEED_NORMAL;
    }

    /** 系统语音兜底用的语速倍率。 */
    static float systemRate(String speed) {
        String value = normalizeSpeed(speed);
        if (SPEED_SLOW.equals(value)) {
            return 0.8f;
        }
        return SPEED_FAST.equals(value) ? 1.25f : 1.0f;
    }

    /** 播报风格指令。 */
    static String style(String speed) {
        String value = normalizeSpeed(speed);
        if (SPEED_SLOW.equals(value)) {
            return "请用清晰自然、语速偏慢的播报语气朗读，像天气预报播音员一样，"
                    + "句子之间稍作停顿。";
        }
        if (SPEED_FAST.equals(value)) {
            return "请用清晰自然、语速偏快的播报语气朗读，像天气预报播音员一样，"
                    + "保持吐字清楚。";
        }
        return "请用清晰自然、语速适中的播报语气朗读，像天气预报播音员一样。";
    }
}
