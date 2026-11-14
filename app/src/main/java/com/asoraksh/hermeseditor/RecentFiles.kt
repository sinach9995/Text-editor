package com.asoraksh.hermeseditor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A document the editor has opened, as the Recent tab remembers it. */
data class RecentFile(val uri: String, val title: String)

/**
 * The files this app has opened, newest first.
 *
 * Only files that were actually opened here: the editor has no file manager and
 * cannot list a directory, so the list is the app's own history rather than a
 * view of storage. Duplicates are collapsed by uri, because the same document
 * reached through Open and through a share link is one entry, not two.
 *
 * Stored beside the tab manifest (one JSON string in the same preferences), so a
 * closed document stays on the Recent tab after a cold start; opening it again
 * asks the provider for the text the usual way.
 */
object RecentFiles {

    private const val PREFS = "hermes_editor"
    private const val KEY = "recent_files"
    private const val LIMIT = 12

    fun load(context: Context): List<RecentFile> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            val out = ArrayList<RecentFile>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: continue
                out.add(RecentFile(uri, item.optString("title").ifBlank { "untitled.txt" }))
            }
            out
        }.getOrDefault(emptyList())
    }

    /** Records [uri] as the most recent file; a repeat moves it up instead of adding a copy. */
    fun record(context: Context, uri: String, title: String) {
        if (uri.isBlank()) return
        val merged = ArrayList<RecentFile>(LIMIT)
        merged.add(RecentFile(uri, title))
        for (item in load(context)) {
            if (item.uri == uri) continue
            merged.add(item)
            if (merged.size >= LIMIT) break
        }
        val array = JSONArray()
        for (item in merged) {
            array.put(JSONObject().put("uri", item.uri).put("title", item.title))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }

    /** Forgets a file that turned out to be unreadable, so it stops failing on every tap. */
    fun forget(context: Context, uri: String) {
        val kept = load(context).filterNot { it.uri == uri }
        val array = JSONArray()
        for (item in kept) array.put(JSONObject().put("uri", item.uri).put("title", item.title))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }
}