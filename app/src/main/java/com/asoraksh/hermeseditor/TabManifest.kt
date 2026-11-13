package com.asoraksh.hermeseditor

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tab as it survives across a process death.
 *
 * Document text never lives here: text belongs to the tab's draft file, and
 * [charCount] is stored only so the memory budget can be enforced without
 * hydrating every tab first.
 */
data class TabRecord(
    val id: String,
    val uri: String?,
    val title: String,
    val isDirty: Boolean,
    val charCount: Int,
    val mdPreview: Boolean,
    val docRtl: Boolean,
    val scrollY: Int,
)

data class TabManifest(val tabs: List<TabRecord>, val activeTabId: String)

/**
 * JSON encode/decode for [TabManifest]. Pure Kotlin plus org.json, so a JVM
 * unit test can exercise it without an Android runtime.
 *
 * A malformed manifest must never lose work: [decode] returns null and the
 * caller falls back to the legacy single-document migration path, which still
 * finds draft_1.txt.
 */
object TabManifestCodec {

    fun encode(manifest: TabManifest): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("active", manifest.activeTabId)
        val array = JSONArray()
        for (tab in manifest.tabs) {
            array.put(
                JSONObject()
                    .put("id", tab.id)
                    .put("uri", tab.uri ?: JSONObject.NULL)
                    .put("title", tab.title)
                    .put("dirty", tab.isDirty)
                    .put("chars", tab.charCount)
                    .put("preview", tab.mdPreview)
                    .put("rtl", tab.docRtl)
                    .put("scroll", tab.scrollY)
            )
        }
        root.put("tabs", array)
        return root.toString()
    }

    fun decode(raw: String?): TabManifest? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(raw)
            val array = root.optJSONArray("tabs") ?: return null
            val tabs = ArrayList<TabRecord>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                tabs.add(
                    TabRecord(
                        id = id,
                        uri = if (item.isNull("uri")) null else item.optString("uri").takeIf { it.isNotBlank() },
                        title = item.optString("title").ifBlank { "untitled.txt" },
                        isDirty = item.optBoolean("dirty", false),
                        charCount = item.optInt("chars", 0).coerceAtLeast(0),
                        mdPreview = item.optBoolean("preview", false),
                        docRtl = item.optBoolean("rtl", false),
                        scrollY = item.optInt("scroll", 0).coerceAtLeast(0),
                    )
                )
            }
            if (tabs.isEmpty()) return null
            val active = root.optString("active").takeIf { id -> tabs.any { it.id == id } } ?: tabs.first().id
            TabManifest(tabs, active)
        }.getOrNull()
    }

    /** v1.5 (and older) kept one document plus one draft; that becomes tab_1. */
    fun migrate(legacyUri: String?, legacyTitle: String?, legacyDirty: Boolean, legacyChars: Int): TabManifest =
        TabManifest(
            tabs = listOf(
                TabRecord(
                    id = "tab_1",
                    uri = legacyUri,
                    title = legacyTitle?.takeIf { it.isNotBlank() } ?: "untitled.txt",
                    isDirty = legacyDirty,
                    charCount = legacyChars.coerceAtLeast(0),
                    mdPreview = false,
                    docRtl = false,
                    scrollY = 0,
                )
            ),
            activeTabId = "tab_1",
        )
}

/**
 * Memory guard rails for the tab set.
 *
 * The text budget is summed from [TabRecord.charCount] rather than from live
 * editor state, because inactive tabs are hydrated lazily and hold no text;
 * summing live text would silently never reach the limit.
 */
object TabBudget {

    const val MAX_TABS: Int = 10
    const val MAX_TOTAL_CHARS: Int = 6_000_000

    fun totalChars(records: List<TabRecord>): Long = records.sumOf { it.charCount.toLong() }

    fun canAddTab(records: List<TabRecord>, usedChars: Long = totalChars(records)): Boolean =
        records.size < MAX_TABS && usedChars <= MAX_TOTAL_CHARS

    fun canAccept(records: List<TabRecord>, incomingChars: Int): Boolean {
        if (records.size >= MAX_TABS) return false
        return totalChars(records) + incomingChars.toLong() <= MAX_TOTAL_CHARS
    }
}