package com.local.tianqi;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 取设备当前位置。
 * <p>
 * 只用系统 {@link LocationManager}，不依赖 Google Play 服务，国内机型也能用。
 * 先取各 provider 的最后已知位置（10 分钟内算新鲜），没有再等一次实时定位，最多 12 秒。
 */
final class DeviceLocation {

    interface Callback {
        void onLocation(double latitude, double longitude);

        void onFailure(String message);
    }

    /** 一次定位结果。 */
    static final class Fix {
        final double latitude;
        final double longitude;

        Fix(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        static Fix of(Location location) {
            return new Fix(location.getLatitude(), location.getLongitude());
        }
    }

    private static final long TIMEOUT_MS = 12000L;
    /** 最后已知位置超过这个时间就不算新鲜。 */
    private static final long FRESH_MS = 10L * 60L * 1000L;

    private DeviceLocation() {
    }

    static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void request(Context context, Callback callback) {
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            callback.onFailure("系统没有定位服务");
            return;
        }
        if (!hasPermission(context)) {
            callback.onFailure("没有定位权限");
            return;
        }

        Location lastKnown = newestLastKnown(manager);
        if (lastKnown != null && isFresh(lastKnown)) {
            callback.onLocation(lastKnown.getLatitude(), lastKnown.getLongitude());
            return;
        }

        String provider = pickProvider(manager);
        if (provider == null) {
            if (lastKnown != null) {
                // 定位开关关了，但还有旧位置，先用着
                callback.onLocation(lastKnown.getLatitude(), lastKnown.getLongitude());
            } else {
                callback.onFailure("定位开关未打开，请在系统设置里打开定位，或直接填写城市名");
            }
            return;
        }

        new OneShot(manager, provider, callback).start();
    }

    /**
     * 取一次位置，阻塞到拿到或超时。只能在后台线程调用。
     * 有新鲜的最后已知位置就直接用，否则等一次实时定位；都拿不到返回 null。
     */
    static Fix await(Context context, long timeoutMs) {
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null || !hasPermission(context)) {
            return null;
        }
        Location lastKnown = newestLastKnown(manager);
        if (lastKnown != null && isFresh(lastKnown)) {
            return Fix.of(lastKnown);
        }
        String provider = pickProvider(manager);
        if (provider == null) {
            return lastKnown == null ? null : Fix.of(lastKnown);
        }
        return awaitUpdate(manager, provider, timeoutMs, lastKnown);
    }

    private static Fix awaitUpdate(LocationManager manager, String provider, long timeoutMs,
                                   Location fallback) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] received = new Location[1];
        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location != null) {
                    received[0] = location;
                }
                latch.countDown();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
                latch.countDown();
            }
        };
        try {
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                manager.removeUpdates(listener);
            } catch (RuntimeException ignored) {
            }
        }
        if (received[0] != null) {
            return Fix.of(received[0]);
        }
        return fallback == null ? null : Fix.of(fallback);
    }

    private static Location newestLastKnown(LocationManager manager) {
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (!manager.isProviderEnabled(provider)) {
                    continue;
                }
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.getTime() > best.getTime()) {
                    best = candidate;
                }
            } catch (SecurityException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        return best;
    }

    private static boolean isFresh(Location location) {
        return System.currentTimeMillis() - location.getTime() <= FRESH_MS;
    }

    private static String pickProvider(LocationManager manager) {
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER}) {
            try {
                if (manager.isProviderEnabled(provider)) {
                    return provider;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    /** 等一次实时定位，拿到就取消监听。 */
    private static final class OneShot implements LocationListener {

        private final LocationManager manager;
        private final String provider;
        private final Callback callback;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private boolean finished;

        private final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                fail("定位超时，请到空旷处重试，或直接填写城市名");
            }
        };

        OneShot(LocationManager manager, String provider, Callback callback) {
            this.manager = manager;
            this.provider = provider;
            this.callback = callback;
        }

        void start() {
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, this, Looper.getMainLooper());
                handler.postDelayed(timeout, TIMEOUT_MS);
            } catch (SecurityException e) {
                fail("没有定位权限");
            } catch (RuntimeException e) {
                fail("无法启动定位：" + e.getMessage());
            }
        }

        private void finish() {
            if (finished) {
                return;
            }
            finished = true;
            handler.removeCallbacks(timeout);
            try {
                manager.removeUpdates(this);
            } catch (RuntimeException ignored) {
            }
        }

        private void fail(String message) {
            if (finished) {
                return;
            }
            finish();
            callback.onFailure(message);
        }

        @Override
        public void onLocationChanged(Location location) {
            if (finished || location == null) {
                return;
            }
            finish();
            callback.onLocation(location.getLatitude(), location.getLongitude());
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            fail("定位被关闭");
        }
    }
}
