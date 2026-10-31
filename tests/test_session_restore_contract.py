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
MAIN = (SRC / "MainActivity.kt").read_text()
VM = (SRC / "EditorViewModel.kt").read_text()

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
check("empty draft.txt is treated as clean", "takeIf { it.isNotEmpty() }" in restore)

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
check("markClean remembers the document", re.search(r"fun markClean\(\).*?saveLastDocument", VM, re.S) is not None)
check("newDocument forgets the document", re.search(r"fun newDocument\(.*?forgetLastDocument\(\)", VM, re.S) is not None)
check("SessionStore exists", (SRC / "SessionStore.kt").exists())
store = (SRC / "SessionStore.kt").read_text() if (SRC / "SessionStore.kt").exists() else ""
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

if failures:
    print(f"\n{len(failures)} contract(s) failed")
    sys.exit(1)
print("\nall contracts hold")