package com.local.tianqi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 和风天气（QWeather）请求公共部分：API Host 归一化、认证头、经纬度解析、GeoAPI 城市搜索。
 * 接口与字段说明见 docs/qweather-docs。
 */
final class QWeatherApi {

    private QWeatherApi() {
    }

    /**
     * 把控制台分配的 API Host 归一化成可拼接的基地址。
     * 用户可能填 {@code abc1234xyz.re.qweatherapi.com}，也可能带协议头或结尾斜杠。
     */
    static String baseUrl(String host) {
        String value = host == null ? "" : host.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 和风天气支持两种凭据：控制台的 API Key 与 JWT。
     * API Key 走 {@code X-QW-Api-Key} 请求头，JWT 走 {@code Authorization: Bearer}。
     * JWT 形如 a.b.c，API Key 是 32 位十六进制，用是否含点号区分。
     */
    static Map<String, String> authHeaders(String key) {
        Map<String, String> headers = new LinkedHashMap<>();
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            return headers;
        }
        if (value.indexOf('.') > 0) {
            headers.put("Authorization", "Bearer " + value);
        } else {
            headers.put("X-QW-Api-Key", value);
        }
        return headers;
    }

    /** 认证方式的中文描述，用于设置页自检展示。 */
    static String describeAuth(String key) {
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            return "未填写";
        }
        return value.indexOf('.') > 0 ? "Authorization: Bearer <JWT>" : "X-QW-Api-Key: <API Key>";
    }

    /** 实时天气 / 逐小时 / 每日预报的 v1 路径。 */
    static String weatherUrl(String base, String kind, Location location) {
        return base + "/weather/v1/" + kind + "/" + location.latitude + "/" + location.longitude;
    }

    /** 解析用户输入的「经度,纬度」坐标，不是坐标则返回 null。 */
    static Location parseCoordinates(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        String[] parts = value.split("[,，\\s]+");
        if (parts.length != 2) {
            return null;
        }
        double first;
        double second;
        try {
            first = Double.parseDouble(parts[0]);
            second = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            return null;
        }
        double latitude;
        double longitude;
        if (Math.abs(first) > 90 && Math.abs(second) <= 90) {
            longitude = first;
            latitude = second;
        } else if (Math.abs(second) > 90 && Math.abs(first) <= 90) {
            latitude = first;
            longitude = second;
        } else {
            // 和风天气约定为「经度,纬度」
            longitude = first;
            latitude = second;
        }
        if (Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
            return null;
        }
        return new Location(format(latitude), format(longitude),
                format(latitude) + "," + format(longitude));
    }

    /** 按城市名搜索，只在中国范围内找。 */
    static Location searchCity(String base, Map<String, String> headers, String city)
            throws IOException {
        String keyword = city == null ? "" : city.trim();
        if (keyword.isEmpty()) {
            throw new IOException("未填写城市");
        }
        // 多取几条，避免模糊匹配把「河南」匹配到韩国「河南市」；range=cn 限定中国
        String url = base + "/geo/v2/city/lookup?location=" + HttpJson.encode(keyword)
                + "&number=5&range=cn&lang=zh";
        return parseLookupResponse(HttpJson.get(url, headers), keyword, true);
    }

    /** 用经纬度反查最近的行政区，不限制国家（用户可能在境外）。 */
    static Location reverseGeocode(String base, Map<String, String> headers,
                                   String longitude, String latitude) throws IOException {
        String keyword = longitude + "," + latitude;
        String url = base + "/geo/v2/city/lookup?location=" + HttpJson.encode(keyword)
                + "&number=1&lang=zh";
        return parseLookupResponse(HttpJson.get(url, headers), keyword, false);
    }

    static Location parseLookupResponse(JSONObject root, String keyword) throws IOException {
        return parseLookupResponse(root, keyword, false);
    }

    /**
     * 从城市搜索响应里挑结果：优先名称完全一致的那条，否则取第一条能对上的。
     * 名称带省市前缀时（例如「河南省龙亭」）会匹配不到，所以调用方应只传城市名。
     */
    static Location parseLookupResponse(JSONObject root, String keyword, boolean requireChina)
            throws IOException {
        String code = root.optString("code", "");
        if (!code.isEmpty() && !"200".equals(code)) {
            throw new IOException("城市搜索失败（code=" + code + "）");
        }
        JSONArray array = root.optJSONArray("location");
        if (array == null || array.length() == 0) {
            throw new IOException("未找到城市：" + keyword);
        }
        JSONObject best = null;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("name", "");
            if (keyword.equals(name)) {
                best = item;
                break;
            }
            if (best == null && !looksMismatched(keyword, name)) {
                best = item;
            }
        }
        if (best == null) {
            throw new IOException("未找到城市：" + keyword);
        }
        if (requireChina && !"中国".equals(best.optString("country", ""))) {
            throw new IOException("只支持中国城市，未找到：" + keyword);
        }
        String latitude = best.optString("lat", "");
        String longitude = best.optString("lon", "");
        if (latitude.isEmpty() || longitude.isEmpty()) {
            throw new IOException("城市搜索结果缺少经纬度");
        }
        return new Location(
                format(parseDouble(latitude)),
                format(parseDouble(longitude)),
                best.optString("name", keyword),
                best.optString("adm1", ""),
                best.optString("adm2", ""),
                best.optString("country", ""));
    }

    static String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    /**
     * 中文关键词和结果名互相都不包含时，判定为模糊错配。
     * 例如「河南省龙亭」会被 GeoAPI 匹配到韩国「河南市」，这种结果宁可报「未找到」也不能拿去查天气。
     * 纯英文/数字关键词（例如经纬度、New York）不做这个校验，避免误杀。
     */
    private static boolean looksMismatched(String keyword, String name) {
        if (name.isEmpty() || keyword.isEmpty()) {
            return false;
        }
        if (keyword.contains(name) || name.contains(keyword)) {
            return false;
        }
        return containsCjk(keyword);
    }

    private static boolean containsCjk(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF')) {
                return true;
            }
        }
        return false;
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 一个已解析的位置。 */
    static final class Location {
        final String latitude;
        final String longitude;
        /** GeoAPI 的 name，例如「龙亭」。 */
        final String name;
        /** 一级行政区，例如「河南省」。 */
        final String adm1;
        /** 二级行政区，例如「开封市」。 */
        final String adm2;
        /** 国家，例如「中国」。 */
        final String country;

        Location(String latitude, String longitude, String name) {
            this(latitude, longitude, name, "", "", "");
        }

        Location(String latitude, String longitude, String name, String adm1, String adm2,
                 String country) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.name = name == null ? "" : name;
            this.adm1 = adm1 == null ? "" : adm1;
            this.adm2 = adm2 == null ? "" : adm2;
            this.country = country == null ? "" : country;
        }

        String coordinateText() {
            return latitude + "," + longitude;
        }

        /**
         * 再次按名称搜索时用的键：只取城市名，绝不能把省市拼进去。
         * 「河南省龙亭」这种串会被 GeoAPI 模糊匹配到韩国「河南市」。
         */
        String searchKey() {
            return name.isEmpty() ? coordinateText() : name;
        }

        /** 界面展示：市 + 名称，例如「开封市龙亭」。name 已带前缀时不重复拼接。 */
        String display() {
            if (name.isEmpty()) {
                return coordinateText();
            }
            if (adm2.isEmpty() || adm2.equals(adm1) || adm2.contains(name)
                    || name.startsWith(adm2)) {
                return name;
            }
            return adm2 + name;
        }

        /** 完整展示：国家 + 省 + 市 + 名称，例如「中国 河南省开封市龙亭」。 */
        String full() {
            StringBuilder builder = new StringBuilder();
            if (!country.isEmpty()) {
                builder.append(country).append(' ');
            }
            if (!adm1.isEmpty() && !name.startsWith(adm1)) {
                builder.append(adm1);
            }
            if (!adm2.isEmpty() && !adm2.equals(adm1) && !adm1.endsWith(adm2)
                    && !name.startsWith(adm2)) {
                builder.append(adm2);
            }
            builder.append(name);
            String text = builder.toString().trim();
            return text.isEmpty() ? coordinateText() : text;
        }
    }
}
