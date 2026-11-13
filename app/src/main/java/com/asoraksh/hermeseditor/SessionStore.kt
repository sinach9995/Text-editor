package com.asoraksh.hermeseditor

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Durable identity of the open tabs.
 *
 * As of v1.6 this stores the whole tab manifest instead of one last_uri.
 * Document text still never lives here: draft_<tabId>.txt holds unsaved edits
 * and the manifest records only which files were open, in what order, which
 * one was active, and how many characters each held (for the memory budget).
 *
 * Writes go through [saveManifest], called on every structural change and from
 * onPause, so a process kill loses at most the last few hundred milliseconds of
 * keystrokes -- and those are already in the draft file.
 */
interface SessionStore {
    fun loadManifest(): TabManifest?
    fun saveManifest(manifest: TabManifest)
    fun clearManifest()

    /** Pre-v1.6 single-document identity, read once for the migration. */
    fun loadLegacyDocument(): Pair<Uri, String>?
    fun clearLegacyDocument()
}

/** SharedPreferences-backed [SessionStore]. One JSON string, written atomically by the platform. */
class PrefsSessionStore(context: Context) : SessionStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("hermes_editor", Context.MODE_PRIVATE)

    override fun loadManifest(): TabManifest? = TabManifestCodec.decode(prefs.getString(KEY_MANIFEST, null))

    override fun saveManifest(manifest: TabManifest) {
        // commit(), not apply(): onPause needs the bytes on disk before the
        // process can be killed. The manifest is a few hundred bytes.
        prefs.edit().putString(KEY_MANIFEST, TabManifestCodec.encode(manifest)).commit()
    }

    override fun clearManifest() {
        prefs.edit().remove(KEY_MANIFEST).commit()
    }

    override fun loadLegacyDocument(): Pair<Uri, String>? {
        val uri = prefs.getString(KEY_LEGACY_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val title = prefs.getString(KEY_LEGACY_TITLE, null)?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { Uri.parse(uri) to title }.getOrNull()
    }

    override fun clearLegacyDocument() {
        prefs.edit().remove(KEY_LEGACY_URI).remove(KEY_LEGACY_TITLE).commit()
    }

    private companion object {
        const val KEY_MANIFEST = "tabs_manifest"
        const val KEY_LEGACY_URI = "last_uri"
        const val KEY_LEGACY_TITLE = "last_title"
    }
}