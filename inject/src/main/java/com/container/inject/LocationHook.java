package com.container.inject;

import android.content.Context;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * نقاط الاعتراض التي يستدعيها كود التطبيق المستنسخ بدل واجهات الموقع الأصلية.
 *
 * كل دالة تقدّم موقعًا وهميًا إن كان التزييف مفعّلًا في ABUJHAN، وإلا تستدعي الواجهة
 * الأصلية. الموقع يُقرأ من مزوّد المحتوى الذي تنشره ABUJHAN، فلا تحتاج النسخة إلى root
 * ولا إلى خيارات المطوّر.
 */
public final class LocationHook {

    private static final Uri CURRENT = Uri.parse("content://com.abujhan.container.location/current");
    private static final long CACHE_MS = 500L;
    private static final long TICK_MS = 1000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Object, Runnable> FUSED_TICKS = new IdentityHashMap<>();
    private static final Map<Object, Runnable> MANAGER_TICKS = new IdentityHashMap<>();

    private static Context sContext;
    private static long sCachedAt;
    private static double sLat;
    private static double sLng;
    private static float sAcc;
    private static boolean sActive;

    private LocationHook() {
    }

    /** يُستدعى من InjectProvider عند بدء النسخة. */
    public static void init(Context context) {
        sContext = context.getApplicationContext();
    }

    // ---------------------------------------------------------- LocationManager

    /** بديل LocationManager.getLastKnownLocation(String). */
    public static Location getLastKnownLocation(LocationManager manager, String provider) {
        Location fake = fakeLocation(provider);
        if (fake != null) {
            return fake;
        }
        return manager.getLastKnownLocation(provider);
    }

    /** بديل LocationManager.requestLocationUpdates(String, long, float, LocationListener). */
    public static void requestLocationUpdates(LocationManager manager, String provider,
                                              long minTime, float minDistance, LocationListener listener) {
        if (!refresh()) {
            manager.requestLocationUpdates(provider, minTime, minDistance, listener);
            return;
        }
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                Location fake = fakeLocation(provider);
                if (fake == null) {
                    return;
                }
                listener.onLocationChanged(fake);
                HANDLER.postDelayed(this, TICK_MS);
            }
        };
        synchronized (MANAGER_TICKS) {
            Runnable previous = MANAGER_TICKS.put(listener, tick);
            if (previous != null) {
                HANDLER.removeCallbacks(previous);
            }
        }
        HANDLER.post(tick);
    }

    /** بديل LocationManager.requestLocationUpdates(String, long, float, LocationListener, Looper). */
    public static void requestLocationUpdates(LocationManager manager, String provider, long minTime,
                                              float minDistance, LocationListener listener, Looper looper) {
        requestLocationUpdates(manager, provider, minTime, minDistance, listener);
    }

    /** بديل LocationManager.requestLocationUpdates(String, android.location.LocationRequest, Executor, LocationListener). */
    public static void requestLocationUpdates(LocationManager manager, String provider,
                                              android.location.LocationRequest request,
                                              java.util.concurrent.Executor executor, LocationListener listener) {
        long interval = request == null ? 1000L : Math.max(1000L, request.getIntervalMillis());
        requestLocationUpdates(manager, provider, interval, 0f, listener);
    }

    /** بديل LocationManager.removeUpdates(LocationListener). */
    public static void removeUpdates(LocationManager manager, LocationListener listener) {
        Runnable tick;
        synchronized (MANAGER_TICKS) {
            tick = MANAGER_TICKS.remove(listener);
        }
        if (tick != null) {
            HANDLER.removeCallbacks(tick);
            return;
        }
        manager.removeUpdates(listener);
    }

    // ------------------------------------------------------------ Fused (GMS)

    /** بديل FusedLocationProviderClient.getLastLocation(). */
    public static Task<Location> getLastLocation(FusedLocationProviderClient client) {
        Location fake = fakeLocation("fused");
        if (fake != null) {
            return Tasks.forResult(fake);
        }
        return client.getLastLocation();
    }

    /** بديل FusedLocationProviderClient.requestLocationUpdates(LocationRequest, LocationCallback, Looper). */
    public static Task<Void> requestLocationUpdates(FusedLocationProviderClient client, LocationRequest request,
                                                    LocationCallback callback, Looper looper) {
        if (!refresh()) {
            return client.requestLocationUpdates(request, callback, looper);
        }
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                Location fake = fakeLocation("fused");
                if (fake == null) {
                    return;
                }
                callback.onLocationResult(LocationResult.create(Collections.singletonList(fake)));
                HANDLER.postDelayed(this, TICK_MS);
            }
        };
        synchronized (FUSED_TICKS) {
            Runnable previous = FUSED_TICKS.put(callback, tick);
            if (previous != null) {
                HANDLER.removeCallbacks(previous);
            }
        }
        HANDLER.post(tick);
        return Tasks.forResult(null);
    }

    /** بديل FusedLocationProviderClient.removeLocationUpdates(LocationCallback). */
    public static Task<Void> removeLocationUpdates(FusedLocationProviderClient client, LocationCallback callback) {
        Runnable tick;
        synchronized (FUSED_TICKS) {
            tick = FUSED_TICKS.remove(callback);
        }
        if (tick != null) {
            HANDLER.removeCallbacks(tick);
            return Tasks.forResult(null);
        }
        return client.removeLocationUpdates(callback);
    }

    /**
     * بديل PlayServicesLocationManager.requestLocationUpdates(LocationRequest, LocationCallback)
     * الخاص بمكتبة React Native للموقع. عند الإيقاف تُستدعى الدالة الأصلية عبر الانعكاس.
     */
    public static void requestPlayServicesUpdates(Object owner, LocationRequest request, LocationCallback callback) {
        if (!refresh()) {
            try {
                java.lang.reflect.Method original = owner.getClass().getDeclaredMethod(
                        "requestLocationUpdates", LocationRequest.class, LocationCallback.class);
                original.setAccessible(true);
                original.invoke(owner, request, callback);
            } catch (Throwable ignored) {
            }
            return;
        }
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                Location fake = fakeLocation("fused");
                if (fake == null) {
                    return;
                }
                callback.onLocationResult(LocationResult.create(Collections.singletonList(fake)));
                HANDLER.postDelayed(this, TICK_MS);
            }
        };
        synchronized (FUSED_TICKS) {
            Runnable previous = FUSED_TICKS.put(callback, tick);
            if (previous != null) {
                HANDLER.removeCallbacks(previous);
            }
        }
        HANDLER.post(tick);
    }

    // ----------------------------------------------------------------- helpers

    private static synchronized Location fakeLocation(String provider) {
        if (!refresh()) {
            return null;
        }
        Location location = new Location(provider == null ? "fused" : provider);
        location.setLatitude(sLat);
        location.setLongitude(sLng);
        location.setAccuracy(sAcc);
        location.setTime(System.currentTimeMillis());
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return location;
    }

    /** يحدّث الموقع من ABUJHAN بعد مهلة قصيرة. يعيد false إن لم يكن مفعّلًا. */
    private static synchronized boolean refresh() {
        long now = SystemClock.elapsedRealtime();
        if (now - sCachedAt < CACHE_MS) {
            return sActive;
        }
        sCachedAt = now;
        sActive = false;
        if (sContext == null) {
            return false;
        }
        try (Cursor cursor = sContext.getContentResolver().query(CURRENT, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                sActive = cursor.getInt(cursor.getColumnIndexOrThrow("active")) == 1;
                sLat = cursor.getDouble(cursor.getColumnIndexOrThrow("lat"));
                sLng = cursor.getDouble(cursor.getColumnIndexOrThrow("lng"));
                sAcc = cursor.getFloat(cursor.getColumnIndexOrThrow("acc"));
            }
        } catch (Throwable ignored) {
            sActive = false;
        }
        return sActive;
    }
}
