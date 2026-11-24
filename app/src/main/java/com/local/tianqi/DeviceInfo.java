package com.local.tianqi;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * 上报给服务端的设备信息，只用于管理页统计「有几台设备对接过」。
 */
final class DeviceInfo {

    private DeviceInfo() {
    }

    /** 稳定的设备标识；取不到时用固定值，服务端会把它们算成同一台。 */
    static String id(Context context) {
        try {
            String value = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ANDROID_ID);
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        } catch (Exception ignored) {
            // 某些定制系统会限制读取，退回固定值即可
        }
        return "unknown";
    }

    static String name() {
        String manufacturer = trim(Build.MANUFACTURER);
        String model = trim(Build.MODEL);
        if (model.isEmpty()) {
            return manufacturer.isEmpty() ? "Android" : manufacturer;
        }
        if (manufacturer.isEmpty()
                || model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model;
        }
        return manufacturer + " " + model;
    }

    static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            String name = info.versionName == null ? "" : info.versionName.trim();
            long code = Build.VERSION.SDK_INT >= 28
                    ? info.getLongVersionCode() : info.versionCode;
            return name.isEmpty() ? String.valueOf(code) : name + " (" + code + ")";
        } catch (Exception e) {
            return "";
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
