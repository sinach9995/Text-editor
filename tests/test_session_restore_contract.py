#!/usr/bin/env python3
"""Source-level contract for last-document restore and its race/priority rules.

These assertions read the Kotlin source rather than running it, so they run
without an Android SDK. They lock in behaviours that are easy to regress:
the cold-start priority order, restore after process death, intent-wins-over-
restore, grant persistence, and the save-versus-typing guard.

They do NOT prove runtime behaviour: a grep cannot show that a coroutine ran.
Device testing is still required for that.
"""

import re
import sys
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / "app/src/main/java/com/asoraksh/hermeseditor"
MAIN = (SRC / "MainActivity.kt").read_text(encoding='utf-8')
VM = (SRC / "EditorViewModel.kt").read_text(encoding='utf-8')

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
attach_draft = body(VM, "fun attachDraft(")
on_create = body(MAIN, "override fun onCreate(savedInstanceState: Bundle?)")
save_to = body(MAIN, "private fun saveToUri(")
save_as = body(MAIN, "private fun saveAsToUri(")

# --- Cold-start priority --------------------------------------------------
try:
    draft_idx = restore.index("if (draft != null) {")
    uri_idx = restore.index("if (lastUri == null || lastTitle == null) return@launch")
    priority_ok = draft_idx < uri_idx
except ValueError as exc:
    priority_ok = False
    print(f"      (restore branch missing: {exc})")
check("draft.txt is considered before last_uri", priority_ok)
check("draft restore marks the document dirty", re.search(r"if \(draft != null\) \{.*?isDirty = true", restore, re.S) is not None)
check("draft branch is live, not disabled", "if (false" not in restore)
check("draft restore keeps the remembered uri/title", "documentUri = lastUri" in restore.split("if (lastUri == null")[0])
check("no-draft restore re-reads last_uri", "readUri(lastUri)" in restore)
check("no-draft restore is clean, not dirty", re.search(r"documentUri = lastUri.*?isDirty = false", restore, re.S) is not None)
check("unreadable last_uri falls back to untitled.txt", "title = UNTOOLED_TXT" in restore and "forgetLastDocument()" in restore)
check("an empty draft.txt is a real (erased-everything) draft", "takeIf { it.isNotEmpty() }" not in restore.split("legacyDraft")[0])

# --- Recovery draft lifecycle: nothing may outlive its document -----------
retire = body(VM, "private fun retireDraft(")
write = body(VM, "private fun writeDraft(")
flush = body(VM, "fun flushAutosave(")
set_doc = body(VM, "fun setDocument(")
new_doc = body(VM, "fun newDocument(")
mark = body(VM, "fun markClean(")
check("new document retires the draft", "retireDraft()" in new_doc)
check("opening a document retires the draft", "retireDraft()" in set_doc)
check("saving retires the draft", "retireDraft()" in mark)
check("opening a document remembers its identity", "rememberDocument()" in set_doc)
check("Save As identity is remembered before markClean can be skipped", "rememberDocument()" in body(VM, "fun setDocumentUri(") and "rememberDocument()" in body(VM, "fun renameTitle("))
check("retire bumps the epoch under the draft lock", "draftEpoch++" in retire and "synchronized(draftLock)" in retire)
check("stale writes are dropped by epoch", "epoch != draftEpoch" in write and "synchronized(draftLock)" in write)
check("draft write is atomic (temp + rename)", ".tmp" in write and "renameTo(file)" in write)
check("exit flush is synchronous, not a cancellable launch", "scope.launch" not in flush and "writeDraft(" in flush)
check("legacy migration goes through the guarded writer", "writeDraft(legacy" in restore)
check("failed Save As does not re-open the Save As prompt", "fallbackSaveAsAfterDenied" not in save_as and "R.string.save_failed" in save_as)

# --- Restore survives process death --------------------------------------
# After the system kills the process it recreates the Activity WITH
# savedInstanceState, so anything inside an `savedInstanceState == null`
# block is skipped in exactly the case the recovery draft exists for.
gate = on_create.find("savedInstanceState == null")
check("onCreate attaches the session store", "vm.attachStore(store)" in on_create)
check("the session store is attached before the savedInstanceState gate", 0 <= on_create.find("vm.attachStore(store)") < gate)
check("onCreate attaches the recovery draft file", 'vm.attachDraft(File(filesDir, "draft.txt"))' in on_create)
check("the draft file is attached before the savedInstanceState gate", 0 <= on_create.find("vm.attachDraft(") < gate)
check("restore is reached on a recreation too", on_create.find("restoreLastSession(legacy, store)") > gate)
check("the launch intent is still gated on a fresh activity", on_create.find("if (fresh) intent?.takeIf { it.data != null }") > 0)
check("attachDraft sets the recovery file without restoring", "draftFile = recoveryFile" in attach_draft)
check("draftFile is attached before the idempotence guard", restore.find("this.draftFile = draftFile") < restore.find("if (restored) return"))

# --- Reads happen off the main thread ------------------------------------
check("restore body runs in a coroutine", "scope.launch {" in restore)
check("draft.txt read runs on Dispatchers.IO", re.search(r"withContext\(Dispatchers\.IO\) \{.*?draftFile\.readText", restore, re.S) is not None)
check("last_uri read runs on Dispatchers.IO", re.search(r"withContext\(Dispatchers\.IO\) \{ runCatching \{ readUri", restore) is not None)

# --- Incoming intent wins over restore -----------------------------------
check("setDocument bumps the restore generation", re.search(r"fun setDocument\(.*?restoreGeneration\+\+", VM, re.S) is not None)
check("restore checks its generation before applying", restore.count("if (generation != restoreGeneration) return@launch") >= 2)

# --- Grants persisted on the save paths ----------------------------------
check("saveToUri persists the grant", "takePersistablePermissions(uri)" in save_to)
check("saveAsToUri persists the grant", "takePersistablePermissions(uri)" in save_as)

# --- A save clears the dirty flag only when the file holds the live text --
check("saveToUri compares written text with live text", "vm.editor.text == draft" in save_to)
check("saveToUri clears dirty state only under the guard", "if (vm.editor.text == draft) vm.markClean()" in save_to and save_to.count("vm.markClean()") == 1)
check("saveAsToUri compares written text with live text", "vm.editor.text == draft" in save_as)

# --- Persistence shape ----------------------------------------------------
check("markClean remembers the document", "rememberDocument()" in mark and "saveLastDocument" in body(VM, "private fun rememberDocument("))
check("newDocument forgets the document", re.search(r"fun newDocument\(.*?forgetLastDocument\(\)", VM, re.S) is not None)
check("SessionStore exists", (SRC / "SessionStore.kt").exists())
store = (SRC / "SessionStore.kt").read_text(encoding='utf-8') if (SRC / "SessionStore.kt").exists() else ""
check("SessionStore keys are last_uri/last_title", '"last_uri"' in store and '"last_title"' in store)
check("SessionStore stores no document text", 'getString("draft"' not in store and "editor.text" not in store)
check("ViewModel stays Context-free", "android.content.Context" not in VM)

# --- Threading ------------------------------------------------------------
# State mutation has to stay on Main because EditorHistory is not thread-safe.
# An IO block is either a single expression ending at the line's last ')' or a
# brace-delimited lambda.
unsafe = []
for m in re.finditer(r"withContext\(Dispatchers\.IO\)", VM):
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
    if any(w in block for w in ("history.", "editor =", "title =", "documentUri =", "isDirty =")):
        unsafe.append(VM[:m.start()].count("\n") + 1)
check(f"no state mutation inside any Dispatchers.IO block (offenders: {unsafe})", not unsafe)

# --- Consumer-QA fixes ------------------------------------------------------
MD = (SRC / "MarkdownPreview.kt").read_text(encoding='utf-8')
check("blank untitled document is not dirty", "isDirty = documentUri != null || text.isNotEmpty()" in VM)
check("selection-only change is not an edit", "if (changed) afterEdit(newValue.text)" in VM)
check("undo/redo share afterEdit", VM.count("afterEdit(target.text)") == 2)
check("surface reset skipped on recreation", "vm.surfaceSession != documentSession" in MAIN)
check("incoming file asks before replacing dirty text", "if (vm.isDirty) pendingIncoming = uri else openIncoming(uri)" in MAIN)
check("export walks nested lists", "blocks(part)" in MD)
if failures:
    print(f"\n{len(failures)} contract(s) failed")
    sys.exit(1)
print("\nall contracts hold")