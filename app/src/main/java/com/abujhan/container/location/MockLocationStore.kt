package com.abujhan.container.location

import android.content.Context

/** الموقع الوهمي النشط الذي يُطبَّق على النظام كله. */
data class MockPoint(val lat: Double, val lng: Double, val accuracy: Float)

object MockLocationStore {

    private const val PREFS = "mock_location"

    fun save(context: Context, point: MockPoint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("lat", point.lat.toString())
            .putString("lng", point.lng.toString())
            .putFloat("acc", point.accuracy)
            .apply()
    }

    /** هل التزييف مفعّل من المستخدم؟ (يمنع إعادة التشغيل التلقائي بعد الإيقاف). */
    fun isActive(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("active", false)

    fun setActive(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", active)
            .apply()
    }

    fun load(context: Context): MockPoint? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull() ?: return null
        val lng = prefs.getString("lng", null)?.toDoubleOrNull() ?: return null
        return MockPoint(lat, lng, prefs.getFloat("acc", 5f))
    }
}
