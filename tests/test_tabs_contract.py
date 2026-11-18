#!/usr/bin/env python3
"""Source-level contract for the v1.6 tab strip, per-tab state and budget.

Reads the Kotlin source and the Gradle/workflow files; needs no Android SDK.
Runtime behaviour (a tab really switching, a draft really landing in its own
file) is covered by the JVM unit tests plus device testing.
"""

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/asoraksh/hermeseditor"
MAIN = (SRC / "MainActivity.kt").read_text(encoding='utf-8')
HEADER = (SRC / "HeaderBar.kt").read_text(encoding='utf-8')
VM = (SRC / "EditorViewModel.kt").read_text(encoding='utf-8')
STRIP = (SRC / "TabStrip.kt").read_text(encoding='utf-8') if (SRC / "TabStrip.kt").exists() else ""
CONTROLS = (SRC / "EditorFloatingControls.kt").read_text(encoding='utf-8')
RECENT = (SRC / "RecentPage.kt").read_text(encoding='utf-8') if (SRC / "RecentPage.kt").exists() else ""
RECENT_STORE = (SRC / "RecentFiles.kt").read_text(encoding='utf-8') if (SRC / "RecentFiles.kt").exists() else ""
README = (ROOT / "README.md").read_text(encoding='utf-8')
MANIFEST = (SRC / "TabManifest.kt").read_text(encoding='utf-8')
GRADLE = (ROOT / "app/build.gradle.kts").read_text(encoding='utf-8')
WORKFLOW = (ROOT / ".github/workflows/android-release.yml").read_text(encoding='utf-8')
STRINGS_EN = (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding='utf-8')
STRINGS_FA = (ROOT / "app/src/main/res/values-fa/strings.xml").read_text(encoding='utf-8')

failures = []


def check(label, condition):
    print(f"{'PASS' if condition else 'FAIL'}  {label}")
    if not condition:
        failures.append(label)


def body(text, signature):
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


new_tab = body(VM, "fun newTab(")
close_tab = body(VM, "fun closeTab(")
switch_tab = body(VM, "fun switchTab(")
close_ui = body(MAIN, "fun requestCloseTab(")
new_ui = body(MAIN, "fun newFile(")
header = body(HEADER, "internal fun HeaderBar(")
buttons = body(CONTROLS, "fun FloatingActions(")

# --- Tab strip ------------------------------------------------------------
check("TabStrip exists", bool(STRIP))
check("strip is 42dp tall, tabs fill it", "height(42.dp)" in STRIP and "var height = if (active) 42.dp else 40.dp" in STRIP.replace("val height", "var height"))
check("strip shows an unsaved dot", "if (dirty)" in STRIP and "size(6.dp)" in STRIP)
check("the active tab is cut out of the page, not floating on it",
      "MergedTabShape()" in STRIP and "MaterialTheme.colorScheme.background" in STRIP)
check("its feet reach the page through a visible S-curve, not a chamfer",
      "lineTo(right, h - drop)" in STRIP
      and "cubicTo(" in STRIP and "arcTo(" not in STRIP
      and "left, h - drop," in STRIP
      and "private class MergedTabShape" in STRIP)
check("the strip starts flush under the top bar", "top = 12.dp" not in STRIP)
check("a title wider than the tab can be dragged sideways",
      "horizontalScroll(scroll)" in STRIP and "rememberTextMeasurer" in STRIP)
# --- One band at the top (1.7) -------------------------------------------
check("the header is a single band, not a title bar plus a strip",
      "fun HeaderBar(" in HEADER and "fun TopBar(" not in MAIN)
check("the band starts with the app icon", "R.drawable.hermes_editor_icon" in header)
check("the band is one row of 52dp", "height(52.dp)" in header)
check("the file name is no longer repeated above the tabs", "20.sp" not in header)
check("tapping the icon goes home", "onHome" in header and ".clickable(onClick = onHome)" in header)
check("the menu is a whole disc in the corner",
      "CornerOverflow(" in header and "shape = CircleShape" in HEADER
      and "HalfDiscShape" not in HEADER + MAIN)
check("the menu dots run vertically", "verticalArrangement = Arrangement.spacedBy(4.dp)" in HEADER)
check("strip closes with a dedicated hit target", "\"×\"" in STRIP and "onClose" in STRIP)
check("strip scrolls and follows the active tab", "LazyRow" in STRIP and "animateScrollToItem(activeIndex)" in STRIP)
check("strip is rendered inside the header", "TabStrip(" in header)
check("the strip is never dropped for compact chrome", "compactChrome) TabStrip(" not in MAIN)
check("strip is bound to the ViewModel, not duplicated state", "tabs = vm.tabs" in MAIN and "activeTabId = vm.activeTabId" in MAIN)
# 1.7 revision: Recent is not a tab. It took a slot, and with a long name it
# covered the strip; the icon is the home affordance and carries the state.
check("Recent is not a tab in the strip", "HomeTab" not in STRIP and "homeLabel" not in STRIP)
check("the strip is only documents", "items(tabs, key = { it.id })" in STRIP and "tabs: List<TabState>," in STRIP)
check("the active tab is the one the ViewModel points at",
      "active = !homeActive && tab.id == activeId" in STRIP)
check("no tab looks active while Recent is on screen", "homeActive: Boolean = false," in STRIP)
check("the icon marks the Recent state",
      "if (homeActive) 34.dp else 30.dp" in header
      and "MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)" in header)
check("Recent paints over the document instead of letting the placeholder show",
      "MaterialTheme.colorScheme.background)" in RECENT)
check("Recent swallows taps instead of falling through to the editor",
      "detectTapGestures { }" in RECENT and "pointerInput(Unit)" in RECENT)
check("Recent opens with the app name over the page name",
      "R.string.recent_app_name" in RECENT
      and RECENT.index("recent_app_name") < RECENT.index("R.string.recent_title"))
check("a rule separates the title block from the list",
      "HorizontalDivider(" in RECENT and "thickness = 1.dp" in RECENT)
check("Recent has a heading and no hint sentence",
      "R.string.recent_title" in RECENT and "recent_hint" not in RECENT
      and "recent_hint" not in (ROOT / "app/src/main/res/values/strings.xml").read_text(encoding="utf-8"))

# --- Tab operations -------------------------------------------------------
check("a new tab is refused past the cap", "if (!canOpenAnotherTab()) return false" in new_tab)
check("a new tab becomes active", "activeTabId = tab.id" in new_tab)
check("a new tab bumps the document session", "bumpSession()" in new_tab)
check("switching hydrates a stub on demand", "if (!target.hydrated) hydrate(target)" in switch_tab)
check("switching persists the new active id", "persistManifest()" in switch_tab)
check("closing selects the adjacent tab", "index.coerceAtMost(_tabs.lastIndex)" in close_tab)
check("every structural change persists the manifest",
      all("persistManifest()" in fn for fn in (new_tab, close_tab, switch_tab)))
# The untouched-tab rule must look at a real tab: with nothing open, activeTab
# is a detached placeholder that matched every field, so New did nothing at all
# on the Recent page.
check("New looks at a real tab, not the detached placeholder",
      "vm.tabs.firstOrNull { it.id == vm.activeTabId }" in new_ui
      and "current != null && current.uri == null" in new_ui)
check("New reuses an untouched blank tab by going back to it",
      "vm.showDocument()" in new_ui)
check("New reports the tab limit", "R.string.tab_limit_reached" in new_ui)
check("closing a dirty tab asks first", "if (tab.isDirty) { discardAction = action; showDiscardDialog = true }" in close_ui)
# The grant has to outlive the tab when a Recent row still needs it; otherwise
# every file on that list failed to reopen.
check("a closed tab keeps the grant its Recent row needs",
      "recentFiles.none { it.uri == uri.toString() }" in close_ui
      and "releasePermission(uri)" in close_ui)

# --- Per-tab chrome state -------------------------------------------------
check("preview mode lives on the tab, not in rememberSaveable",
      "rememberSaveable { mutableStateOf(false) }\n        var mdPreview" not in MAIN)
check("direction lives on the tab", "val docRtl by vm::docRtl" in MAIN)
check("preview toggle goes through the ViewModel", "vm.togglePreview()" in MAIN)
check("direction changes go through the ViewModel", "vm.applyDirection(" in MAIN)
check("scroll is stored per tab", "scrollYFor(" in VM and "fun setScrollY(" in VM)
check("a switch restores that tab's scroll", "pendingScrollY = vm.scrollYFor(vm.activeTabId)" in MAIN)
check("scroll is saved before switching", "saveScrollPosition()" in body(MAIN, "fun selectTab("))
check("scroll is saved on pause", "saveScrollPosition(); vm.flushAutosave()" in MAIN)
check("the tab owns the preview default", "fun beginSession(markdownName: Boolean)" in VM)

# --- Memory budget --------------------------------------------------------
check("tab cap is ten", "MAX_TABS: Int = 10" in MANIFEST)
check("text budget is 6M characters", "MAX_TOTAL_CHARS: Int = 6_000_000" in MANIFEST)
check("budget is summed from the manifest records, not live text",
      "records.sumOf { it.charCount.toLong() }" in MANIFEST)
check("per-tab history is capped", "EditorHistory(maxEntries = 30, maxTotalChars = 120_000)" in VM)

# --- Font stepper ---------------------------------------------------------
check("the header takes a font size", "fontSize: Float" in header and "onFontSize: (Float) -> Unit" in header)
check("the font row starts in the same column as the others", "padding(start = 12.dp, end = 12.dp)" in header)
check("the stepper hugs the number", "size(28.dp)" in HEADER and "width(28.dp)" in HEADER)
check("the language row has no globe glyph", "LanguageGlyph" not in HEADER + MAIN)
check("the theme icon trails its text", "trailingIcon" in header)
check("the menu has minus and plus steps", "FontStep(\"−\"" in HEADER and "FontStep(\"+\"" in HEADER)
check("the stepper disables at the ends", "enabled = fontSize > 13f" in HEADER and "enabled = fontSize < 30f" in HEADER)
check("a chosen size is persisted", 'prefs.edit().putFloat("document_font_size", fontSize).apply()' in MAIN)

# --- Resources ------------------------------------------------------------
for key in ("font_size", "tab_limit_reached"):
    check(f"string {key} exists in both locales",
          f'name="{key}"' in STRINGS_EN and f'name="{key}"' in STRINGS_FA)

# --- Help text ------------------------------------------------------------
import re as _re


def _help(text, name):
    block = _re.search(r'<string-array name="%s">(.*?)</string-array>' % name, text, _re.S).group(1)
    return _re.findall(r"<item>(.*?)</item>", block, _re.S)


for name in ("help_titles", "help_bodies"):
    en, fa = _help(STRINGS_EN, name), _help(STRINGS_FA, name)
    check(f"{name} has the same number of items in both locales", len(en) == len(fa))
    check(f"{name} is not empty", len(en) > 0)

titles_en, titles_fa = _help(STRINGS_EN, "help_titles"), _help(STRINGS_FA, "help_titles")
check("help documents the tab strip", "Tabs" in titles_en and "تب‌ها" in titles_fa)
check("help documents the session coming back",
      "Reopening the app" in titles_en and "بازگشت به برنامه" in titles_fa)
check("the tab topic describes the welded foot in both locales",
      any("welded to the page" in b for b in _help(STRINGS_EN, "help_bodies"))
      and any("پای قوسی" in b for b in _help(STRINGS_FA, "help_bodies")))

# An unescaped apostrophe is a resource-compile failure, not a warning, and it
# only shows up in CI because there is no SDK here to run aapt.
for _name in ("help_titles", "help_bodies"):
    for _loc, _text in (("en", STRINGS_EN), ("fa", STRINGS_FA)):
        check(f"{_name} has no unescaped apostrophes ({_loc})",
              all("'" not in _item for _item in _help(_text, _name)))

# About must read the installed version, not a literal that drifts.
check("About shows the installed version",
      "getPackageInfo(packageName, 0).versionName" in MAIN
      and "stringResource(R.string.about_body, versionName)" in HEADER)

# --- The corner column (1.7) ----------------------------------------------
check("the full-width bottom bar is gone", "ActionBar(" not in MAIN and "bottomBar =" not in MAIN)
check("the actions are one composable, in the corner",
      "FloatingActions(" in MAIN and "Alignment.BottomEnd" in MAIN)
check("the order is Save, New, Open, Undo, Redo from the top",
      buttons.index("R.string.ui_save") < buttons.index("R.string.ui_new") <
      buttons.index("R.string.ui_open") < buttons.index("R.string.undo") <
      buttons.index("R.string.redo"))
check("the stack is vertical unless the keyboard is up",
      "inRow = imeBottom > 0" in MAIN and "if (inRow) Row(" in CONTROLS and "else Column(" in CONTROLS)
check("a keyboard open in portrait hides New and Open",
      "showDocuments = imeBottom == 0" in MAIN and "if (showDocuments) {" in CONTROLS)
check("the buttons are 46dp dots, not a bar", "private val DOT = 46.dp" in CONTROLS and "CircleShape" in CONTROLS)
check("the five glyphs scale with the dot", "private val GLYPH = 25.dp" in CONTROLS and "size(GLYPH)" in CONTROLS)
check("New, Open and Save are line icons",
      all(k in CONTROLS for k in ("LineIconKind.NewFile", "LineIconKind.OpenFolder", "LineIconKind.Save"))
      and "Stroke(" in CONTROLS)
check("Save as and Export are on a long press of Save",
      "onLongClick = { saveMenu = true }" in CONTROLS and "R.string.ui_save_as" in CONTROLS)
check("New still offers Text or Markdown on a long press",
      "onLongClick = { newMenu = true }" in CONTROLS and "R.string.new_md" in CONTROLS)

# --- The Recent tab (1.7) -------------------------------------------------
check("Recent is a page of its own", bool(RECENT) and "fun RecentPage(" in RECENT)
check("Recent offers New and Open", "R.string.ui_new" in RECENT and "R.string.ui_open" in RECENT)
check("Recent lists files, not folders", "files: List<RecentFile>" in RECENT and "onFile" in RECENT)
check("Recent marks the files that are open right now", "in openUris" in RECENT or "file.uri in openUris" in RECENT)
check("the history is stored, capped and de-duplicated",
      bool(RECENT_STORE) and "LIMIT = 12" in RECENT_STORE and "if (item.uri == uri) continue" in RECENT_STORE)
check("opening a file records it", "rememberRecent(target, displayName(target))" in MAIN)
check("a recent file that is already open is jumped to, not reopened twice",
      "vm.tabs.firstOrNull { it.uri?.toString() == file.uri }" in MAIN)
check("closing the last tab lands on Recent, it does not open a blank one",
      'activeTabId = ""' in close_tab and "home = true" in close_tab)
check("an empty session starts on Recent", "legacyDraft.isNullOrEmpty() && lastUri == null" in VM)
check("an empty manifest is cleared instead of left to be restored", "s.clearManifest()" in VM)
check("the Recent page covers the document instead of unmounting it",
      "if (vm.home) RecentPage(" in MAIN)
check("Recent is inset below the header instead of starting at the window top",
      "modifier = Modifier.fillMaxSize().padding(innerPadding)," in MAIN)
check("tapping the corner menu in a document tab leaves Recent",
      "if (id == vm.activeTabId && !vm.home) return" in MAIN)


# --- Recent page behaviour (1.7 review) -----------------------------------
check("Recent lists open documents, not only history",
      "private fun recentList()" in MAIN and "vm.tabs.mapNotNull { tab -> tab.uri?.let" in MAIN)
check("a Recent row that cannot be read is forgotten",
      "RecentFiles.forget(" in MAIN and "fromRecent" in MAIN)
check("the page opens without resetting the editor session",
      "bumpSession()" not in body(VM, "fun goHome("))
check("leaving the page without a document change does not reset it either",
      "bumpSession()" not in body(VM, "fun showDocument("))
check("an edit with no tab open is ignored",
      "if (_tabs.isEmpty()) return" in body(VM, "fun onTextChanged("))
check("the draft ceiling is the same on read and write",
      "DRAFT_MAX_BYTES" in body(VM, "private fun writeDraft(")
      and "DRAFT_MAX_BYTES" in body(VM, "private fun readDraft("))
check("keyboard mode reserves room for the button row",
      "if (imeBottom == 0) 280.dp else 70.dp" in MAIN)
check("Recent headings exist in both languages",
      'name="recent_app_name"' in STRINGS_EN and 'name="recent_app_name"' in STRINGS_FA
      and 'name="recent_title">Recent<' in STRINGS_EN)
check("Back returns from Recent overlay when tabs exist",
      "vm.home && vm.tabs.isNotEmpty() -> vm.showDocument()" in MAIN)
check("document-only menu items are gated on an open visible document",
      "val showingDoc = !homeActive && tabs.isNotEmpty()" in header and "if (showingDoc)" in header)
check("openInNewTab enforces total char budget and reports memory cap",
      "TabBudget.canAccept(records(), text.length)" in body(VM, "fun openInNewTab(")
      and 'name="tab_memory_reached"' in STRINGS_EN and 'name="tab_memory_reached"' in STRINGS_FA)
check("long-pressing a closed Recent row forgets it",
      "onForget = ::forgetRecent" in MAIN and "combinedClickable(" in RECENT)
check("legacy bottom-bar arrow hints are removed from both locales",
      "New ▲" not in STRINGS_EN and "Save ▲" not in STRINGS_EN and "▲" not in STRINGS_FA)

# --- Version + CI ---------------------------------------------------------
check("versionName is 1.7.0", 'versionName = "1.7.0"' in GRADLE)
check("versionCode was bumped past 1.6.8", "versionCode = 16" in GRADLE)
check("JVM unit tests are declared", 'testImplementation("junit:junit' in GRADLE)
check("CI runs the JVM unit tests", "testDebugUnitTest" in WORKFLOW)
check("JVM tests cover codec, reader, history, and anchors",
      (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/TabManifestCodecTest.kt").exists()
      and (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/BoundedReaderTest.kt").exists()
      and (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/EditorHistoryTest.kt").exists()
      and (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/DocumentAnchorsTest.kt").exists()
      and not (ROOT / "tests/EditorHistoryTest.kt").exists()
      and not (ROOT / "tests/DocumentAnchorsTest.kt").exists())

if failures:
    print(f"\n{len(failures)} contract(s) failed")
    sys.exit(1)
print("\nall tab contracts hold")