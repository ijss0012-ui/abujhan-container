package com.abujhan.container.location

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** موقع محفوظ في المفضلة. */
data class Favorite(val name: String, val lat: Double, val lng: Double, val accuracy: Float)

object FavoriteStore {

    private const val PREFS = "mock_favorites"
    private const val KEY = "items"

    fun all(context: Context): List<Favorite> {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Favorite(o.getString("name"), o.getDouble("lat"), o.getDouble("lng"), o.getDouble("acc").toFloat())
        }
    }

    fun add(context: Context, favorite: Favorite) {
        write(context, all(context) + favorite)
    }

    fun remove(context: Context, index: Int) {
        val list = all(context).toMutableList()
        if (index in list.indices) list.removeAt(index)
        write(context, list)
    }

    private fun write(context: Context, list: List<Favorite>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("name", it.name)
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("acc", it.accuracy.toDouble()),
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
