package com.abujhan.container.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * تخزين بسيط للمستودعات في SharedPreferences بصيغة JSON.
 * (بديل خفيف عن Room لتبقى قابليّة التعديل سهلة.)
 */
class ContainerStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("containers", Context.MODE_PRIVATE)

    fun all(): MutableList<ContainerRecord> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ContainerRecord(
                    originalPackage = o.getString("originalPackage"),
                    containerPackage = o.getString("containerPackage"),
                    label = o.optString("label"),
                    apkPaths = o.optJSONArray("apkPaths")?.let { a ->
                        List(a.length()) { j -> a.getString(j) }
                    } ?: emptyList(),
                    latitude = if (o.has("lat")) o.getDouble("lat") else null,
                    longitude = if (o.has("lng")) o.getDouble("lng") else null,
                    accuracy = o.optDouble("accuracy", 5.0).toFloat(),
                    pendingDelete = o.optBoolean("pendingDelete", false),
                )
            }
        } catch (t: Throwable) {
            mutableListOf()
        }
    }

    fun get(containerPackage: String): ContainerRecord? =
        all().firstOrNull { it.containerPackage == containerPackage }

    fun upsert(record: ContainerRecord) {
        val list = all()
        val idx = list.indexOfFirst { it.containerPackage == record.containerPackage }
        if (idx >= 0) list[idx] = record else list.add(record)
        persist(list)
    }

    fun remove(containerPackage: String) {
        val list = all()
        list.removeAll { it.containerPackage == containerPackage }
        persist(list)
    }

    fun setPendingDelete(containerPackage: String, pending: Boolean) {
        val list = all()
        list.firstOrNull { it.containerPackage == containerPackage }?.pendingDelete = pending
        persist(list)
    }

    private fun persist(list: List<ContainerRecord>) {
        val arr = JSONArray()
        for (r in list) {
            val o = JSONObject()
            o.put("originalPackage", r.originalPackage)
            o.put("containerPackage", r.containerPackage)
            o.put("label", r.label)
            o.put("apkPaths", JSONArray(r.apkPaths))
            r.latitude?.let { o.put("lat", it) }
            r.longitude?.let { o.put("lng", it) }
            o.put("accuracy", r.accuracy.toDouble())
            o.put("pendingDelete", r.pendingDelete)
            arr.put(o)
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "records"
    }
}
