package com.local.tianqi;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * 和风天气官方图标（QWeather Icons 1.8.0）。
 * <p>
 * 字体取自 QWeather-Icons-1.8.0/font/fonts/qweather-icons.ttf，字形码位取自同目录 qweather-icons.json；
 * 天气现象代码的中文含义取自官方 weather-conditions.csv：
 * https://raw.githubusercontent.com/qwd/dev-site/master/assets/table/weather-conditions.csv
 */
public final class QWeatherIcons {

    private static final String ASSET = "fonts/qweather-icons.ttf";

    /** 无法识别的代码统一用「未知」图标。 */
    private static final char UNKNOWN = '\uF146';

    private static Typeface typeface;

    private QWeatherIcons() {
    }

    public static Typeface typeface(Context context) {
        if (typeface == null) {
            try {
                typeface = Typeface.createFromAsset(context.getAssets(), ASSET);
            } catch (RuntimeException e) {
                typeface = Typeface.DEFAULT;
            }
        }
        return typeface;
    }

    /** 套用图标字体。字号用像素，避免跟随系统字体缩放把图标放大。 */
    public static void apply(TextView view, String code, boolean night, int sizePx) {
        view.setTypeface(typeface(view.getContext()));
        view.setText(String.valueOf(glyph(code, night)));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx);
        view.setIncludeFontPadding(false);
    }

    /** 图标配色：晴天金色、雷雨橙色、雨雪蓝色、阴天灰蓝。 */
    public static int colorRes(WeatherCondition condition) {
        if (condition == null) {
            return R.color.icon_cloud;
        }
        switch (condition) {
            case SUNNY:
                return R.color.icon_sunny;
            case THUNDER:
                return R.color.icon_thunder;
            case RAIN:
                return R.color.icon_rain;
            case SNOW:
                return R.color.icon_snow;
            case PARTLY_CLOUDY:
            case CLOUDY:
            default:
                return R.color.icon_cloud;
        }
    }

    /** 天气现象代码对应的图标字形；夜间把晴/多云/少云/晴间多云换成夜间版本。 */
    public static char glyph(String code, boolean night) {
        int value = parse(code);
        if (night) {
            value = nightVariant(value);
        }
        char glyph = codepoint(value);
        return glyph == 0 ? UNKNOWN : glyph;
    }

    private static int nightVariant(int code) {
        switch (code) {
            case 100: return 150;
            case 101: return 151;
            case 102: return 152;
            case 103: return 153;
            default: return code;
        }
    }

    /** 天气现象代码的中文说明，代码表取自官方 weather-conditions.csv。 */
    public static String text(String code) {
        switch (parse(code)) {
            case 100: return "晴";
            case 101: return "多云";
            case 102: return "少云";
            case 103: return "晴间多云";
            case 104: return "阴";
            case 300: return "阵雨";
            case 301: return "强阵雨";
            case 302: return "雷阵雨";
            case 303: return "强雷阵雨";
            case 304: return "雷阵雨伴有冰雹";
            case 305: return "小雨";
            case 306: return "中雨";
            case 307: return "大雨";
            case 308: return "极端降雨";
            case 309: return "毛毛雨/细雨";
            case 310: return "暴雨";
            case 311: return "大暴雨";
            case 312: return "特大暴雨";
            case 313: return "冻雨";
            case 314: return "小到中雨";
            case 315: return "中到大雨";
            case 316: return "大到暴雨";
            case 317: return "暴雨到大暴雨";
            case 318: return "大暴雨到特大暴雨";
            case 399: return "雨";
            case 400: return "小雪";
            case 401: return "中雪";
            case 402: return "大雪";
            case 403: return "暴雪";
            case 404: return "雨夹雪";
            case 405: return "雨雪天气";
            case 406: return "阵雨夹雪";
            case 407: return "阵雪";
            case 408: return "小到中雪";
            case 409: return "中到大雪";
            case 410: return "大到暴雪";
            case 499: return "雪";
            case 500: return "薄雾";
            case 501: return "雾";
            case 502: return "霾";
            case 503: return "扬沙";
            case 504: return "浮尘";
            case 507: return "沙尘暴";
            case 508: return "强沙尘暴";
            case 509: return "浓雾";
            case 510: return "强浓雾";
            case 511: return "中度霾";
            case 512: return "重度霾";
            case 513: return "严重霾";
            case 514: return "大雾";
            case 515: return "特强浓雾";
            case 900: return "热";
            case 901: return "冷";
            case 999: return "未知";
            default: return "";
        }
    }

    private static char codepoint(int code) {
        switch (code) {
            case 100: return '\uF101';
            case 101: return '\uF102';
            case 102: return '\uF103';
            case 103: return '\uF104';
            case 104: return '\uF105';
            case 150: return '\uF106';
            case 151: return '\uF107';
            case 152: return '\uF108';
            case 153: return '\uF109';
            case 300: return '\uF10A';
            case 301: return '\uF10B';
            case 302: return '\uF10C';
            case 303: return '\uF10D';
            case 304: return '\uF10E';
            case 305: return '\uF10F';
            case 306: return '\uF110';
            case 307: return '\uF111';
            case 308: return '\uF112';
            case 309: return '\uF113';
            case 310: return '\uF114';
            case 311: return '\uF115';
            case 312: return '\uF116';
            case 313: return '\uF117';
            case 314: return '\uF118';
            case 315: return '\uF119';
            case 316: return '\uF11A';
            case 317: return '\uF11B';
            case 318: return '\uF11C';
            case 350: return '\uF11D';
            case 351: return '\uF11E';
            case 399: return '\uF11F';
            case 400: return '\uF120';
            case 401: return '\uF121';
            case 402: return '\uF122';
            case 403: return '\uF123';
            case 404: return '\uF124';
            case 405: return '\uF125';
            case 406: return '\uF126';
            case 407: return '\uF127';
            case 408: return '\uF128';
            case 409: return '\uF129';
            case 410: return '\uF12A';
            case 456: return '\uF12B';
            case 457: return '\uF12C';
            case 499: return '\uF12D';
            case 500: return '\uF12E';
            case 501: return '\uF12F';
            case 502: return '\uF130';
            case 503: return '\uF131';
            case 504: return '\uF132';
            case 507: return '\uF133';
            case 508: return '\uF134';
            case 509: return '\uF135';
            case 510: return '\uF136';
            case 511: return '\uF137';
            case 512: return '\uF138';
            case 513: return '\uF139';
            case 514: return '\uF13A';
            case 515: return '\uF13B';
            case 800: return '\uF13C';
            case 801: return '\uF13D';
            case 802: return '\uF13E';
            case 803: return '\uF13F';
            case 804: return '\uF140';
            case 805: return '\uF141';
            case 806: return '\uF142';
            case 807: return '\uF143';
            case 900: return '\uF144';
            case 901: return '\uF145';
            case 999: return '\uF146';
            default: return 0;
        }
    }

    private static int parse(String code) {
        if (code == null) {
            return -1;
        }
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
