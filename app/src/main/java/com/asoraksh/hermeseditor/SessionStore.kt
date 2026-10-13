package com.asoraksh.hermeseditor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Durable identity of the document the user last had open.
 *
 * The ViewModel keeps the live text; this keeps only *which file* was open, so a
 * cold start after a clean save can reopen the workspace instead of returning to
 * a blank editor. It deliberately holds no document text -- draft.txt covers
 * unsaved edits, and text in SharedPreferences was the thing that made this app
 * serialise the whole document on every keystroke.
 */
interface SessionStore {
    fun saveLastDocument(uri: Uri, title: String)
    fun loadLastDocument(): Pair<Uri, String>?
    fun clearLastDocument()
}

/** SharedPreferences-backed [SessionStore]. Small values only, written on save. */
class PrefsSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("hermes_editor", Context.MODE_PRIVATE)

    override fun saveLastDocument(uri: Uri, title: String) {
        prefs.edit()
            .putString(KEY_URI, uri.toString())
            .putString(KEY_TITLE, title)
            .apply()
    }

    override fun loadLastDocument(): Pair<Uri, String>? {
        val uri = prefs.getString(KEY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val title = prefs.getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.parse(uri) to title }.getOrNull()
    }

    override fun clearLastDocument() {
        prefs.edit().remove(KEY_URI).remove(KEY_TITLE).apply()
    }

    private companion object {
        const val KEY_URI = "last_uri"
        const val KEY_TITLE = "last_title"
    }
}