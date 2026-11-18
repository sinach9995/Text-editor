# Hermes Text Editor

A clean, private, offline-focused text and Markdown editor for Android.

Hermes Text Editor is made for distraction-free writing: open a file, write comfortably, save safely, and return to your work whenever you need it.

## Features

- Create plain-text (`.txt`) and Markdown (`.md`) documents
- Open `.txt`, `.md`, and `.markdown` files with Android's system file picker
- Open supported files directly from your Android file manager
- Save the current document normally, with clear save and error feedback
- Press and hold the floppy icon (bottom-right corner) to use:
  - **Save as…** — save a copy with a new filename, folder, or `.txt` / `.md` extension while preserving the exact original content
  - **Export as plain text…** — create a separate readable `.txt` copy of a Markdown document without Markdown symbols
- Automatic local draft recovery, per tab
- Up to ten open documents in a tab strip; each tab keeps its own unsaved draft, scroll position, Markdown preview mode and writing direction
- **Undo** and **Redo** using compact floating controls; history is local and bounded, and rapid typing is grouped into practical undo steps
- **Find in current document** with match highlighting, current/total result count, and previous/next navigation
- **Two-finger text zoom** in the editor and Markdown Preview; your selected text size is stored locally
- Markdown Preview for headings, emphasis, lists, links, quotes, code blocks, and more
- Live styled Markdown source editing: Markdown symbols remain visible and editable while the source receives readable visual styling
- Long filenames stay on one tab line and can be swiped sideways to reveal the full name
- Light and Dark themes
- English and فارسی app interface
- Right-to-left (RTL) and left-to-right (LTR) writing controls
- A local direction suggestion when a clearly Persian or English document does not match the current writing direction
- First-run guide in English and فارسی, plus an in-app Help & Guide that can be replayed; it documents the tab strip and the session that comes back with it

## Markdown support

Existing Markdown files open in **Preview** mode for comfortable reading. Use the three-dot menu to switch between **Preview Markdown** and **Edit Markdown**.

Markdown source remains unchanged when you edit or save it. Source mode adds visual styling for common Markdown elements while keeping syntax visible and editable. New Markdown documents open in source-editing mode.

If you need a readable text-only copy, press and hold the floppy icon and choose **Export as plain text…**. The original Markdown file is not changed.

## Find, Undo, and Redo

Choose **Find** from the three-dot menu to search within the current document. Hermes highlights the active result, shows the current result and total number of matches, and provides previous/next navigation. Find never changes your document text.

The floating curved-arrow controls provide Undo and Redo while editing. They use a bounded history to avoid unlimited memory use. Undo and Redo change the current draft only; your saved file is not changed until you save.

## Text direction

Hermes can suggest the suitable writing direction when you open a clearly Persian or English document:

- Persian text can use Right-to-Left (RTL)
- English text can use Left-to-Right (LTR)

The suggestion appears only when the current writing direction does not match the document. You can always choose the direction yourself from the three-dot menu. Changing the app language does not translate your document.

## Privacy

Hermes Text Editor is designed to work offline.

- No ads
- No analytics
- No tracking
- No WebView
- No cloud sync
- No Internet permission

Your files remain on your device. The app accesses a file only when you choose it through Android's system file picker or share/open it from your file manager.

## Install

1. Open the [latest GitHub Release](https://github.com/sinach9995/Text-editor/releases/latest).
2. Download the signed release APK — not the debug APK.
3. Open the downloaded APK on your Android device and follow Android's installation steps.
4. If Google Play Protect recommends a scan for a newly published direct-download APK, choose **Scan app**.

> **Note:** GitHub APKs are installed directly rather than through Google Play. Android may show a scan recommendation for a newly published build. This does not change the app's offline/privacy design.

## Requirements

- Android 6.0 (API 23) or later

## The buttons

The five document actions sit in a column in the bottom-right corner, as line icons rather than words in a bar: **Save** (floppy), **New** (page with a plus), **Open** (folder), **Undo** and **Redo**, in that order from the top down. Each one is a 46dp dot, so the target is bigger than the 40dp square it replaced.

The column reacts to the keyboard. While the keyboard is open, New and Open are hidden and the remaining three (Save, Undo and Redo) lie down in a compact row along the bottom-right so they stay clear of the line you are typing.

## The header and the Recent page

The top of the screen is one band: the app icon, then the tabs, then a round menu button at the right end with the three dots stacked on it. There is no separate title bar any more, because it only repeated the name of the tab below it.

**Recent is a page behind the app icon, not a tab.** Tapping the icon opens it, and while it is open no tab in the strip is drawn as active -- the icon itself grows and takes a tinted plate, which is the only marker that you are not in a document. Tapping a tab, or New, leaves the page again.

The page opens with the app name over the word **Recent** and a rule across the width under them; everything below the rule is the list. New and Open sit under the rule, and beneath them every document open right now followed by the files this app has opened, newest first. A restored tab is listed too: a document you have open is recent by definition. Press and hold a closed entry on the list to forget it.

Grants matter here. A file opened from Recent is read through a permission this app was given when the file was first picked, so that permission is kept while a list entry still points at the file, and handed back when the entry is pushed off the end (the list keeps twelve) or the row turns out to be unreadable. Closing the last tab lands on Recent with an empty strip rather than leaving a blank document behind.

## Tabs

A strip in that band shows every open document. Tap a tab to switch to it; the dot before its name means it has unsaved changes, and **×** closes it. Closing a tab that has unsaved changes asks first.

The open tab is not a floating chip: it takes the colour of the page and is welded to the text below it by a curved foot that leaves the tab's side vertically, sweeps outwards and lands flat on the page edge. The foot is drawn per tab, so it travels with the tab while the strip scrolls. A long tab name can be dragged sideways to read the rest of it, and the row scrolls sideways once the tabs no longer fit.

Each tab keeps its own draft, undo history, scroll position, Markdown preview state and writing direction, so switching away and back returns you to the same place. A file that is already open is never opened twice: choosing it again switches to its tab instead.

Up to ten documents can stay open, and the app refuses new tabs once the combined text approaches its memory budget, rather than letting Android kill the app while you type.

## Current version

- **Version:** 1.7.0
- **Version code:** 16
- **Package:** `com.asoraksh.hermeseditor`

## Independent project notice

**Hermes Text Editor is an independent project by Sina Chaghamirza under AsoraKSH.**

It was created with assistance from the Hermes Agent AI and Notion AI as development tools. It is **not** built, maintained, endorsed, sponsored, or affiliated with any Hermes team, organization, company, or product. Use of those AI tools does not imply an official relationship or endorsement.

## Development

This repository contains the Android source code and GitHub Actions configuration used to build debug APKs and signed release APK/AAB files.

For security, signing keys, passwords, tokens, and GitHub Actions secret values are never stored in this repository.
