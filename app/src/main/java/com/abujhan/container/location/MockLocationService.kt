package com.abujhan.container.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.abujhan.container.R

/**
 * يعمل كتطبيق الموقع الوهمي للنظام (ABUJHAN هو المصدر الوحيد).
 * كل التطبيقات، بما فيها النسخ، تقرأ الموقع من النظام فتحصل على الموقع الوهمي.
 *
 * يتطلّب أن يكون ABUJHAN مختارًا كتطبيق موقع وهمي؛ على الجهاز المروّت
 * يُمنح ذلك تلقائيًا عبر appops.
 */
class MockLocationService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            push()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMocking()
            return START_NOT_STICKY
        }
        // إعادة التشغيل التلقائي بعد قتل النظام للخدمة لا تُفعّل الموقع الوهمي بعد أن أوقفه المستخدم.
        if (!MockLocationStore.isActive(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification())
        } catch (t: SecurityException) {
            // إذن الموقع غير ممنوح بعد؛ نوقف الخدمة بدل الانهيار.
            Log.w(TAG, "location permission missing: $t")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    /** يوقف البث ويزيل مزوّدات الاختبار فيعود الموقع الحقيقي للنظام. */
    private fun stopMocking() {
        MockLocationStore.setActive(this, false)
        handler.removeCallbacks(ticker)
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        for (provider in PROVIDERS) {
            try {
                lm?.setTestProviderEnabled(provider, false)
                lm?.removeTestProvider(provider)
            } catch (t: Throwable) {
                Log.w(TAG, "remove $provider: $t")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.mock_running))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()

    private fun push() {
        val point = MockLocationStore.load(this) ?: return
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        for (provider in PROVIDERS) {
            try {
                // استدعاؤه دائمًا يحوّل المزوّد إلى مزوّد اختبار.
                lm.addTestProvider(
                    provider, false, false, false, false, true, true, true,
                    Criteria.POWER_LOW, Criteria.ACCURACY_FINE,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "addTestProvider $provider: $t")
            }
            try {
                lm.setTestProviderEnabled(provider, true)
                lm.setTestProviderLocation(provider, buildLocation(provider, point))
            } catch (t: Throwable) {
                Log.w(TAG, "provider $provider: $t")
            }
        }
    }

    private fun buildLocation(provider: String, point: MockPoint): Location {
        val location = Location(provider)
        location.latitude = point.lat
        location.longitude = point.lng
        location.accuracy = point.accuracy
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            location.verticalAccuracyMeters = 1f
            location.speedAccuracyMetersPerSecond = 1f
            location.bearingAccuracyDegrees = 1f
        }
        return location
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 7001
        private const val INTERVAL_MS = 1000L
        private val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            "fused",
        )

        const val ACTION_STOP = "com.abujhan.container.action.STOP_MOCK"

        /** يحفظ الموقع ويشغّل الخدمة. */
        fun activate(context: Context, point: MockPoint) {
            MockLocationStore.save(context, point)
            MockLocationStore.setActive(context, true)
            val intent = Intent(context, MockLocationService::class.java)
            context.startForegroundService(intent)
        }

        /** يوقف الموقع الوهمي فورًا (يُرسل أمر الإيقاف إلى الخدمة). */
        fun deactivate(context: Context) {
            MockLocationStore.setActive(context, false)
            val intent = Intent(context, MockLocationService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
