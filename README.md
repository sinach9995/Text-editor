# Hermes Text Editor

A clean, private, offline-focused text and Markdown editor for Android.

Hermes Text Editor is made for distraction-free writing: open a file, write comfortably, save safely, and return to your work whenever you need it.

## Features

- Create plain-text (`.txt`) and Markdown (`.md`) documents
- Open `.txt`, `.md`, and `.markdown` files with Android's system file picker
- Open supported files directly from your Android file manager
- Save the current document normally, with clear save and error feedback
- Press and hold **Save ▲** to use:
  - **Save as…** — save a copy with a new filename, folder, or `.txt` / `.md` extension while preserving the exact original content
  - **Export as plain text…** — create a separate readable `.txt` copy of a Markdown document without Markdown symbols
- Automatic local draft recovery
- **Undo** and **Redo** using compact floating controls; history is local and bounded, and rapid typing is grouped into practical undo steps
- **Find in current document** with match highlighting, current/total result count, and previous/next navigation
- **Two-finger text zoom** in the editor and Markdown Preview; your selected text size is stored locally
- Markdown Preview for headings, emphasis, lists, links, quotes, code blocks, and more
- Live styled Markdown source editing: Markdown symbols remain visible and editable while the source receives readable visual styling
- Long filenames stay on one header line and can be swiped sideways to reveal the full name
- Light and Dark themes
- English and فارسی app interface
- Right-to-left (RTL) and left-to-right (LTR) writing controls
- A local direction suggestion when a clearly Persian or English document does not match the current writing direction
- First-run guide in English and فارسی, plus an in-app Help & Guide that can be replayed

## Markdown support

Existing Markdown files open in **Preview** mode for comfortable reading. Use the three-dot menu to switch between **Preview Markdown** and **Edit Markdown**.

Markdown source remains unchanged when you edit or save it. Source mode adds visual styling for common Markdown elements while keeping syntax visible and editable. New Markdown documents open in source-editing mode.

If you need a readable text-only copy, press and hold **Save ▲** and choose **Export as plain text…**. The original Markdown file is not changed.

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

## Current version

- **Version:** 1.2
- **Version code:** 3
- **Package:** `com.asoraksh.hermeseditor`

## Independent project notice

**Hermes Text Editor is an independent project by Sina Chaghamirza under AsoraKSH.**

It was created with assistance from the Hermes Agent AI and Notion AI as development tools. It is **not** built, maintained, endorsed, sponsored, or affiliated with any Hermes team, organization, company, or product. Use of those AI tools does not imply an official relationship or endorsement.

## Development

This repository contains the Android source code and GitHub Actions configuration used to build debug APKs and signed release APK/AAB files.

For security, signing keys, passwords, tokens, and GitHub Actions secret values are never stored in this repository.
