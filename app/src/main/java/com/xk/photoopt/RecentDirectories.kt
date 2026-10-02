package com.xk.photoopt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RecentDirectory(val uri: String, val name: String, val usedAt: Long)

object RecentDirectoryStore {
    private const val PREFERENCES = "location-directories"
    private const val HISTORY_KEY = "history"

    fun load(context: Context): List<RecentDirectory> {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val history = runCatching {
            val array = JSONArray(preferences.getString(HISTORY_KEY, "[]"))
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(RecentDirectory(item.getString("uri"), item.getString("name"), item.optLong("usedAt", 0L)))
                }
            }
        }.getOrDefault(emptyList())
        if (history.isNotEmpty()) return history.sortedByDescending { it.usedAt }

        // 兼容旧版仅保存的“上次目录”。
        val legacyUri = preferences.getString("last-uri", null) ?: return emptyList()
        val migrated = listOf(RecentDirectory(legacyUri, preferences.getString("last-name", null) ?: "照片目录", System.currentTimeMillis()))
        save(context, migrated)
        return migrated
    }

    fun add(context: Context, uri: String, name: String): List<RecentDirectory> {
        val updated = listOf(RecentDirectory(uri, name, System.currentTimeMillis())) + load(context).filterNot { it.uri == uri }
        save(context, updated)
        return updated
    }

    fun touch(context: Context, uri: String): List<RecentDirectory> {
        val current = load(context)
        val target = current.firstOrNull { it.uri == uri } ?: return current
        return add(context, target.uri, target.name)
    }

    fun delete(context: Context, uri: String): List<RecentDirectory> {
        val updated = load(context).filterNot { it.uri == uri }
        save(context, updated)
        return updated
    }

    private fun save(context: Context, items: List<RecentDirectory>) {
        val array = JSONArray()
        items.forEach { item -> array.put(JSONObject().put("uri", item.uri).put("name", item.name).put("usedAt", item.usedAt)) }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(HISTORY_KEY, array.toString()).remove("last-uri").remove("last-name").apply()
    }
}
