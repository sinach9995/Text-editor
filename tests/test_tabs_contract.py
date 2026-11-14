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
VM = (SRC / "EditorViewModel.kt").read_text(encoding='utf-8')
STRIP = (SRC / "TabStrip.kt").read_text(encoding='utf-8') if (SRC / "TabStrip.kt").exists() else ""
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
topbar_call = body(MAIN, "Column {\n                    TopBar(")

# --- Tab strip ------------------------------------------------------------
check("TabStrip exists", bool(STRIP))
check("strip is 42dp tall, tabs fill it", "height(42.dp)" in STRIP and "var height = if (active) 42.dp else 40.dp" in STRIP.replace("val height", "var height"))
check("strip shows an unsaved dot", "if (dirty)" in STRIP and "size(6.dp)" in STRIP)
check("the active tab is cut out of the page, not floating on it",
      "MergedTabShape()" in STRIP and "MaterialTheme.colorScheme.background" in STRIP)
check("its feet flare outwards (convex) and travel with the tab",
      "arcTo(" in STRIP and "270f, 90f" in STRIP and "180f, 90f" in STRIP
      and "private class MergedTabShape" in STRIP)
check("the strip starts flush under the top bar", "top = 12.dp" not in STRIP)
check("a title wider than the tab can be dragged sideways",
      "horizontalScroll(scroll)" in STRIP and "rememberTextMeasurer" in STRIP)
check("the top bar no longer repeats the active tab's name",
      "fun TopBar(theme: AppTheme" in MAIN and '"Hermes Text Editor",' in MAIN)
check("strip closes with a dedicated hit target", "\"×\"" in STRIP and "onClose" in STRIP)
check("strip scrolls and follows the active tab", "LazyRow" in STRIP and "animateScrollToItem(activeIndex)" in STRIP)
check("strip is rendered under TopBar", "TabStrip(" in topbar_call)
check("strip disappears with compact chrome", "if (!compactChrome) TabStrip(" in MAIN)
check("strip is bound to the ViewModel, not duplicated state", "tabs = vm.tabs" in MAIN and "activeId = vm.activeTabId" in MAIN)

# --- Tab operations -------------------------------------------------------
check("a new tab is refused past the cap", "if (!canOpenAnotherTab()) return false" in new_tab)
check("a new tab becomes active", "activeTabId = tab.id" in new_tab)
check("a new tab bumps the document session", "bumpSession()" in new_tab)
check("switching hydrates a stub on demand", "if (!target.hydrated) hydrate(target)" in switch_tab)
check("switching persists the new active id", "persistManifest()" in switch_tab)
check("closing selects the adjacent tab", "index.coerceAtMost(_tabs.lastIndex)" in close_tab)
check("every structural change persists the manifest",
      all("persistManifest()" in fn for fn in (new_tab, close_tab, switch_tab)))
check("New reuses an untouched blank tab", "tab.uri == null && tab.editor.text.isEmpty() && !tab.isDirty" in new_ui)
check("New reports the tab limit", "R.string.tab_limit_reached" in new_ui)
check("closing a dirty tab asks first", "if (tab.isDirty) { discardAction = action; showDiscardDialog = true }" in close_ui)
check("closing a tab gives its SAF grant back", "releasePermission(uri)" in close_ui)

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
check("TopBar takes a font size", "fontSize: Float = 17f" in MAIN and "onFontSize: (Float) -> Unit" in MAIN)
check("the menu has minus and plus steps", "FontStep(\"−\"" in MAIN and "FontStep(\"+\"" in MAIN)
check("the stepper disables at the ends", "enabled = fontSize > 13f" in MAIN and "enabled = fontSize < 30f" in MAIN)
check("a chosen size is persisted", 'prefs.edit().putFloat("document_font_size", fontSize).apply()' in MAIN)

# --- Resources ------------------------------------------------------------
for key in ("font_size", "tab_limit_reached"):
    check(f"string {key} exists in both locales",
          f'name="{key}"' in STRINGS_EN and f'name="{key}"' in STRINGS_FA)

# --- Version + CI ---------------------------------------------------------
check("versionName is 1.6.5", 'versionName = "1.6.5"' in GRADLE)
check("versionCode was bumped past 1.6.2", "versionCode = 12" in GRADLE)
check("JVM unit tests are declared", 'testImplementation("junit:junit' in GRADLE)
check("CI runs the JVM unit tests", "testDebugUnitTest" in WORKFLOW)
check("JVM tests cover the codec and the reader",
      (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/TabManifestCodecTest.kt").exists()
      and (ROOT / "app/src/test/java/com/asoraksh/hermeseditor/BoundedReaderTest.kt").exists())

if failures:
    print(f"\n{len(failures)} contract(s) failed")
    sys.exit(1)
print("\nall tab contracts hold")