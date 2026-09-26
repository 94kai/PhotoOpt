package com.xk.photoopt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedLocation(val id: Long, val note: String, val latitude: Double, val longitude: Double, val createdAt: Long)

object SavedLocationStore {
    private const val PREFERENCES = "saved-locations"
    private const val KEY = "items"

    fun load(context: Context): List<SavedLocation> = runCatching {
        val array = JSONArray(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(SavedLocation(item.getLong("id"), item.getString("note"), item.getDouble("latitude"), item.getDouble("longitude"), item.getLong("createdAt")))
            }
        }
    }.getOrDefault(emptyList())

    fun add(context: Context, note: String, latitude: Double, longitude: Double): SavedLocation {
        val item = SavedLocation(System.currentTimeMillis(), note.trim(), latitude, longitude, System.currentTimeMillis())
        save(context, listOf(item) + load(context))
        return item
    }

    fun delete(context: Context, id: Long) = save(context, load(context).filterNot { it.id == id })

    private fun save(context: Context, items: List<SavedLocation>) {
        val array = JSONArray()
        items.take(50).forEach { item ->
            array.put(JSONObject().put("id", item.id).put("note", item.note).put("latitude", item.latitude).put("longitude", item.longitude).put("createdAt", item.createdAt))
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
