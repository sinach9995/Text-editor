#!/usr/bin/env python3
"""Source-level contract for v1.6 session restore (tab manifest + crash draft).

These assertions read the Kotlin source rather than running it, so they run
without an Android SDK. They lock in the behaviours that are easy to regress:
bounded reads on every boot path, draft-before-file priority, lazy hydration,
atomic manifest writes, and the v1.5 -> v1.6 migration.

They do NOT prove runtime behaviour: a grep cannot show that a coroutine ran.
Device testing is still required for that. The unit tests in
app/src/test/java cover the pure logic (codec, budget, bounded reader).
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/asoraksh/hermeseditor"
MAIN = (SRC / "MainActivity.kt").read_text(encoding='utf-8')
VM = (SRC / "EditorViewModel.kt").read_text(encoding='utf-8')
STORE = (SRC / "SessionStore.kt").read_text(encoding='utf-8')
BOUNDED = (SRC / "BoundedReader.kt").read_text(encoding='utf-8')

failures = []


def check(label, condition):
    print(f"{'PASS' if condition else 'FAIL'}  {label}")
    if not condition:
        failures.append(label)


def body(text, signature):
    """Return the source of the function starting at `signature`."""
    start = text.index(signature)
    depth, i = 0, start
    while True:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1


restore = body(VM, "fun restoreSession(")
hydrate = body(VM, "private fun hydrate(")
attach_draft = body(VM, "fun attachDraft(")
on_create = body(MAIN, "override fun onCreate(savedInstanceState: Bundle?)")
write_tab = body(MAIN, "private fun writeTabToUri(")
open_doc = body(MAIN, "private val openDocument = registerForActivityResult")
open_in = body(MAIN, "private fun openIncoming(")
# 1.7: the picker and the share intent both hand the uri to openUri, so the
# bounded-read contract is asserted on the one function they share.
open_uri = body(MAIN, "private fun openUri(")
restore_last = body(MAIN, "private fun restoreLastSession(")

# --- The manifest replaces single-document identity ----------------------
check("SessionStore stores a tab manifest", "tabs_manifest" in STORE)
check("manifest write is async by default, sync on pause",
      "if (sync) editor.commit() else editor.apply()" in STORE)
check("the exit path asks for a synchronous manifest write", "persistManifest(sync = true)" in VM)
check("manifest holds no document text", "editor.text" not in STORE and 'getString("draft"' not in STORE)
check("legacy last_uri/last_title are still readable for the migration",
      '"last_uri"' in STORE and '"last_title"' in STORE)
check("legacy keys are cleared once migrated", "clearLegacyDocument" in restore)
check("an install with no manifest migrates into tab_1", "TabManifestCodec.migrate(" in restore)

# --- Cold start: stubs first, one hydrate ---------------------------------
check("restore rebuilds a stub per manifest row", "for (record in records)" in restore)
check("restore hydrates only the active tab", "hydrate(activeTab)" in restore)
check("a rotation does not restore twice", "if (restored || _tabs.isNotEmpty())" in restore)
check("the draft directory is attached before the idempotence guard",
      restore.find("draftDir = draftFile.parentFile") < restore.find("if (restored || _tabs.isNotEmpty())"))
check("restore persists the manifest it just built", "persistManifest()" in restore)

# --- Hydration priority and bounds ---------------------------------------
check("draft is read before the file", hydrate.find("readDraft(tab)") < hydrate.find("reader?.invoke"))
check("hydrate and awaitTextOf share resolveHydration precedence",
      "resolveHydration(draft, uri != null)" in hydrate
      and "resolveHydration(draft, uri != null)" in body(VM, "suspend fun awaitTextOf(")
      and "applyText(tab, resolved.text, dirty = resolved.dirty)" in hydrate)
check("draft read is bounded", "BoundedReader.readText(file, DRAFT_MAX_BYTES)" in body(VM, "private fun readDraft("))
check("file read is bounded", "runCatching { reader?.invoke(" in hydrate)
check("a hydrate that lost its tab is dropped", "_tabs.contains(tab)" in hydrate)
check("an unreadable uri degrades to untitled instead of crashing", "tab.title = UNTOOLED_TXT" in hydrate)

# --- Every open path is bounded ------------------------------------------
check("BoundedReader has a 5 MB ceiling", "MAX_BYTES: Int = 5 * 1024 * 1024" in BOUNDED)
check("BoundedReader throws instead of truncating", "TooLargeException" in BOUNDED)
check("the Open dialog is bounded", "openUri(it)" in open_doc and "BoundedReader.readText" in open_uri)
check("the share intent path is bounded", "private fun openIncoming(uri: Uri) = openUri(uri)" in MAIN and "BoundedReader.readText" in open_uri)
check("the cold-start read lambda is bounded", "BoundedReader.readText" in restore_last)
check("a too-large file reports instead of silently failing",
      open_uri.count("TooLargeException") >= 1)
check("no unbounded readText() call is left in the Activity",
      "bufferedReader(Charsets.UTF_8)?.use { r -> r.readText() }" not in MAIN)
check("an already-open file is jumped to, not opened twice", "documentKey(" in open_uri and "vm.switchTab(existing.id)" in open_uri)

# --- Recovery draft lifecycle --------------------------------------------
retire = body(VM, "private fun retireDraft(")
write = body(VM, "private fun writeDraft(")
flush = body(VM, "fun flushAutosave(")
set_doc = body(VM, "fun setDocument(")
new_tab = body(VM, "fun newTab(")
close_tab = body(VM, "fun closeTab(")
switch_tab = body(VM, "fun switchTab(")
mark = body(VM, "fun markClean(")
check("drafts are per tab", '"draft_${tab.id}.txt"' in VM)
check("new tab flushes the outgoing draft", "scheduleFlush(activeTab)" in new_tab)
check("switch flushes the outgoing draft", "scheduleFlush(activeTab)" in switch_tab)
check("closing a tab retires its draft", "retireDraft(closing)" in close_tab)
check("closing the last tab leaves no document and shows Recent",
      "if (_tabs.isEmpty())" in close_tab and "home = true" in close_tab)
check("opening a document retires that tab's draft", "retireDraft(tab)" in set_doc)
check("saving retires the draft", "retireDraft(tab)" in mark)
check("retire bumps the epoch under the draft lock", "tab.draftEpoch++" in retire and "synchronized(draftLock)" in retire)
check("stale writes are dropped by epoch", "epoch != tab.draftEpoch" in write and "synchronized(draftLock)" in write)
check("draft write is atomic (temp + rename)", ".tmp" in write and "renameTo(file)" in write)
check("exit flush is synchronous, not a cancellable launch", "scope.launch" not in flush and "writeDraft(tab" in flush)
check("switch-time flush is on IO, not the frame thread", "withContext(Dispatchers.IO) { writeDraft" in switch_tab or "withContext(Dispatchers.IO) { writeDraft" in body(VM, "private fun scheduleFlush("))

# --- Restore survives process death --------------------------------------
gate = on_create.find("savedInstanceState == null")
check("onCreate attaches the session store", "vm.attachStore(store)" in on_create)
check("the store is attached before the savedInstanceState gate", 0 <= on_create.find("vm.attachStore(store)") < gate)
check("onCreate attaches the recovery draft file", 'vm.attachDraft(File(filesDir, "draft.txt"))' in on_create)
check("the draft file is attached before the gate", 0 <= on_create.find("vm.attachDraft(") < gate)
check("restore is reached on a recreation too", on_create.find("restoreLastSession(legacy, store)") > gate)
check("the launch intent is still gated on a fresh activity", on_create.find("if (fresh) intent?.takeIf { it.data != null }") > 0)
check("attachDraft keeps the v1.5 signature", "fun attachDraft(recoveryFile: File)" in VM)

# --- Incoming intent wins over restore -----------------------------------
check("setDocument invalidates an in-flight hydrate", "tab.loadToken++" in set_doc)
check("an incoming file opens beside the open documents, not over them",
      "openIncoming(uri)" in MAIN and "pendingIncoming" not in MAIN
      and "vm.openInNewTab(target, displayName(target), text)" in open_uri)

# --- Grants ---------------------------------------------------------------
check("writeTabToUri persists the grant and backs both Save and Save As",
      "takePersistablePermissions(uri)" in write_tab
      and "private fun saveToUri(uri: Uri) = writeTabToUri(uri, saveAs = false)" in MAIN
      and "private fun saveAsToUri(uri: Uri) = writeTabToUri(uri, saveAs = true)" in MAIN)
check("closing a tab releases its grant", "releasePermission(uri)" in body(MAIN, "fun requestCloseTab("))

# --- A save clears the dirty flag only when the file holds the live text --
check("writeTabToUri compares written text with that tab's live hydrated text",
      "vm.sameText(tabId, draft)" in write_tab and "val tabId = takeSaveTabId()" in write_tab and "vm.awaitTextOf(tabId)" in write_tab)
check("writeTabToUri clears dirty state only under the guard",
      "if (vm.sameText(tabId, draft)) vm.markClean(tabId)" in write_tab and write_tab.count("markClean(") == 1)
check("failed Save As does not re-open the Save As prompt",
      "if (saveAs) notifyMessage(getString(R.string.save_failed))" in write_tab and "else fallbackSaveAsAfterDenied(failure)" in write_tab)
check("pending save tab survives Activity recreation",
      "onSaveInstanceState(" in MAIN and '"pending_save_tab_id"' in MAIN and 'pendingSaveTabId = savedInstanceState?.getString("pending_save_tab_id")' in on_create)
check("unhydrated tabs never masquerade as empty and awaitTextOf hydrates stubs",
      "it.id == tabId && it.hydrated" in VM and "fun textOf(" not in VM
      and "suspend fun awaitTextOf(" in VM and "applyText(tab, resolved.text" in body(VM, "suspend fun awaitTextOf("))
check("hydration precedence helper is tested in JUnit",
      "HydrationPrecedenceTest" in (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/TabManifestCodecTest.kt").read_text(encoding='utf-8'))
check("awaitTextOf failure notifies user of save failure across Save/Save As and Export",
      "notifyMessage(getString(R.string.save_failed))" in write_tab
      and "notifyMessage(getString(R.string.save_failed))" in body(MAIN, "private val exportDocument ="))
check("Save and Save As deduplicate tabs sharing the saved URI and notify when closed",
      "dedupeSavedUri(tabId, uri)" in write_tab and "R.string.saved_deduped" in write_tab)
check("first Save on an untitled document says Saved instead of Saved copy",
      "val hadUri = vm.tabs.firstOrNull { it.id == tabId }?.uri != null" in write_tab
      and "saveAs && hadUri -> R.string.saved_copy" in write_tab)

# --- Consumer-QA fixes that must not regress ------------------------------
check("blank untitled document is not dirty", "tab.isDirty = tab.uri != null || text.isNotEmpty()" in VM)
check("selection-only change is not an edit", "if (changed) afterEdit(tab, newValue.text)" in VM)
check("undo/redo share afterEdit", VM.count("afterEdit(tab, target.text)") == 2)
check("surface reset skipped on recreation", "vm.surfaceSession != documentSession" in MAIN)
check("the ViewModel stays Context-free", "android.content.Context" not in VM)

# --- Threading ------------------------------------------------------------
# State mutation has to stay on Main because EditorHistory is not thread-safe.
unsafe = []
for m in __import__("re").finditer(r"withContext\(Dispatchers\.IO\)", VM):
    tail = VM[m.end():]
    rest_of_line = tail.split("\n", 1)[0]
    if rest_of_line.count("(") == rest_of_line.count(")"):
        block = rest_of_line
    else:
        depth, i = 0, tail.index("{")
        while True:
            if tail[i] == "{":
                depth += 1
            elif tail[i] == "}":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        block = tail[:i + 1]
    if any(w in block for w in ("history.", "tab.editor =", "tab.title =", "tab.uri =", "tab.isDirty =")):
        unsafe.append(VM[:m.start()].count("\n") + 1)
check(f"no state mutation inside any Dispatchers.IO block (offenders: {unsafe})", not unsafe)

# --- v1.6.1: the four data-loss bugs must not come back -------------------
new_file = body(MAIN, "fun newFile(")
check("v1.5 recovery file is a hydrate source, not an orphan",
      "legacyDraftFile" in restore and "legacyDraftFile" in body(VM, "private fun readDraft("))
check("restore no longer queues a draft write (it raced the read)",
      "scope.launch" not in restore and "writeDraft" not in restore)
check("the legacy draft is adopted into the tab's own draft file",
      "writeDraft(tab, carried, tab.draftEpoch)" in body(VM, "private fun readDraft("))
check("document operations take the tab id instead of resolving activeTab",
      "fun setDocument(tabId: String" in VM and "fun setDocumentUri(tabId: String" in VM
      and "fun renameTitle(tabId: String" in VM)
check("open paths add a tab instead of replacing the active one",
      "vm.openInNewTab(target, displayName(target), text)" in open_uri
      and "vm.setDocument(tabId" not in open_uri
      and "vm.setDocument(tabId" not in open_doc and "vm.setDocument(tabId" not in open_in)
check("a share on cold start rebuilds the tab strip before it is applied",
      MAIN.index("restoreLastSession(legacy, store)") < MAIN.index("if (incoming != null) handleIncomingIntent(incoming)"))
check("the font menu anchors the first visible line",
      "topVisibleAnchor()" in MAIN and "settleAnchor(anchor, expected)" in MAIN
      and "settleAnchor(anchor, null)" in MAIN)
check("a replaced uri gives its SAF grant back", "onReleaseUriPermission" in VM
      and "onReleaseUriPermission = ::releasePermission" in MAIN)
check("New no longer claims it will discard the current text",
      not ("if (isDirty) { discardAction" in new_file) and "vm.newDocument(markdown)" in new_file)
check("find only scans while the Find bar is open", "if (finding) findMatches(text, query)" in MAIN)

# --- External / Telegram temporary grants & single-task launch ------------
MANIFEST_XML = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding='utf-8')
check("MainActivity uses singleTask launchMode for external intents",
      'android:launchMode="singleTask"' in MANIFEST_XML)
check("non-persistable URIs copy into app-private ImportedFiles cache",
      "ImportedFiles.targetFile(" in open_uri
      and "ImportedFiles.writeAtomic(" in open_uri
      and "openInputStreamFor(" in open_uri
      and "openInputStreamFor(" in restore_last)
check("local import file access enforces canonical path ownership boundary",
      "ImportedFiles.ownedFile(" in MAIN
      and "ImportedFiles.delete(" in body(MAIN, "private fun releasePermission(")
      and (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/ImportedFilesTest.kt").exists())

if failures:
    print(f"\n{len(failures)} contract(s) failed")
    sys.exit(1)
print("\nall contracts hold")